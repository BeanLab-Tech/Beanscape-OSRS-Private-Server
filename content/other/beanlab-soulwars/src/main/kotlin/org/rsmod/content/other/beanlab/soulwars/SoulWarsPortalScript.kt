package org.rsmod.content.other.beanlab.soulwars

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * A purple Soul Wars portal at the Grand Exchange that leads to the Market Hub.
 *
 * In the cache the Soul Wars portal is the loc `soul_wars_edgeville_portal` (the one that sits in
 * Edgeville in the real game). RsMod ships no Soul Wars content, so nothing places or handles it:
 * the portal is spawned here on [GameLifecycle.Startup] and its "Enter" option teleports to the
 * hub's arrival tile. A `soul_wars_leave_soulwars_portal` in the hub returns to the Exchange.
 *
 * The hub is a [HubMap] (`hub-layout.json`, or an override file, see [HubMapLoader]) of up to 128 x
 * 128 tiles at [ARENA_MIN], outside every real map square. Its floors are packed into the cache by
 * `map/SoulWarsMapTiles` when the `packCache` task runs (`generateHubTerrain` rewrites them from
 * the layout). Walls, objects and NPCs are spawned from the layout at startup. The server allocates
 * the collision zones itself and blocks everything outside the hub.
 */
class SoulWarsPortalScript
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val collision: CollisionFlagMap,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        val enter = locTypes.values.firstOrNull { it.internalName == ENTER_PORTAL }
        val leave = locTypes.values.firstOrNull { it.internalName == LEAVE_PORTAL }
        if (enter == null || leave == null) {
            logger.warn { "[soulwars] portal locs missing from this cache; nothing registered" }
            return
        }
        val loaded = HubMapLoader.load()
        for (problem in loaded.problems) logger.warn { "[soulwars] layout: $problem" }
        val map = loaded.map
        val entry = local(map.arrival.x, map.arrival.z)
        onOpLoc1(enter) { teleport(entry) }
        onOpLoc2(enter) { teleport(entry) }
        onOpLoc1(leave) { teleport(EXCHANGE_EXIT) }
        onOpLoc2(leave) { teleport(EXCHANGE_EXIT) }
        onEvent<GameLifecycle.Startup> {
            buildArena(map.size)
            val shape = LocShape.CentrepieceStraight
            locRepo.add(EXCHANGE_PORTAL, enter, Int.MAX_VALUE, LocAngle.South, shape)
            val exit = local(map.leavePortal.x, map.leavePortal.z)
            locRepo.add(exit, leave, Int.MAX_VALUE, LocAngle.South, shape)
            val placed = buildLocs(map)
            val npcs = spawnNpcs(map)
            logger.info {
                "[soulwars] portal at $EXCHANGE_PORTAL -> hub at $ARENA_MIN " +
                    "(layout: ${loaded.source}, $placed objects, $npcs npcs)"
            }
        }
    }

    /** Allocates the zones covering the arena and blocks every tile outside it. */
    private fun buildArena(size: Int) {
        val zones = (size + ZONE - 1) / ZONE
        val span = zones * ZONE
        for (dx in 0 until span) {
            for (dz in 0 until span) {
                val x = ARENA_MIN.x + dx
                val z = ARENA_MIN.z + dz
                val inside = dx < size && dz < size
                collision[x, z, ARENA_MIN.level] = if (inside) 0 else CollisionFlag.BLOCK_WALK
            }
        }
    }

    private fun buildLocs(map: HubMap): Int {
        val byName = HashMap<String, LocType>()
        for (type in locTypes.values) type.internalName?.let { byName[it] = type }
        val missing = HashSet<String>()

        fun type(name: String): LocType? =
            byName[name]
                ?: run {
                    if (missing.add(name)) logger.warn { "[soulwars] loc $name missing; skipped" }
                    null
                }

        var placed = 0
        for (piece in map.wallPieces()) {
            val wall = type(piece.loc) ?: continue
            val shape = if (piece.corner) LocShape.WallL else LocShape.WallStraight
            locRepo.add(local(piece.x, piece.z), wall, Int.MAX_VALUE, piece.angle, shape)
            placed++
        }
        for (loc in map.locs) {
            val type = type(loc.loc) ?: continue
            locRepo.add(local(loc.x, loc.z), type, Int.MAX_VALUE, loc.angle, loc.shape)
            placed++
        }
        return placed
    }

    private fun spawnNpcs(map: HubMap): Int {
        val byName = HashMap<String, UnpackedNpcType>()
        for (type in npcTypes.values) type.internalName?.let { byName[it] = type }
        var placed = 0
        for (spawn in map.npcs) {
            val type = byName[spawn.npc]
            if (type == null) {
                logger.warn { "[soulwars] npc ${spawn.npc} missing from this cache; skipped" }
                continue
            }
            npcRepo.add(Npc(type, local(spawn.x, spawn.z)), Int.MAX_VALUE)
            placed++
        }
        return placed
    }

    private fun local(x: Int, z: Int) = CoordGrid(ARENA_MIN.x + x, ARENA_MIN.z + z, ARENA_MIN.level)

    private companion object {
        const val ENTER_PORTAL = "soul_wars_edgeville_portal"
        const val LEAVE_PORTAL = "soul_wars_leave_soulwars_portal"
        const val ZONE = 8

        /**
         * South-west corner of the hub: zone aligned, outside all real map squares, and below x
         * 6432, where the engine reserves tiles for instances ([org.rsmod.api.registry.region]).
         */
        val ARENA_MIN = CoordGrid(5120, 3072, 0)

        /** Grand Exchange plaza (the ::tp "Grand Exchange" entry is 3164, 3486). */
        val EXCHANGE_PORTAL = CoordGrid(3172, 3489, 0)
        val EXCHANGE_EXIT = CoordGrid(3164, 3486, 0)
    }
}
