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
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
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
 * the portal is spawned here on [GameLifecycle.Startup] and its "Enter" option teleports to
 * [ARENA_ENTRY]. A `soul_wars_leave_soulwars_portal` in the hub returns to the Exchange.
 *
 * The hub is [HubLayout.SIZE] x [HubLayout.SIZE] tiles at [ARENA_MIN], outside every real map
 * square. Its floors come from [HubLayout.floorAt], packed into the cache by `map/SoulWarsMapTiles`
 * when the `packCache` task runs (the test regenerates the files if the layout changes). Walls,
 * shopkeepers, bankers and clerks are spawned from the same layout at startup. The server allocates
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
        onOpLoc1(enter) { teleport(ARENA_ENTRY) }
        onOpLoc2(enter) { teleport(ARENA_ENTRY) }
        onOpLoc1(leave) { teleport(EXCHANGE_EXIT) }
        onOpLoc2(leave) { teleport(EXCHANGE_EXIT) }
        onEvent<GameLifecycle.Startup> {
            buildArena()
            locRepo.add(
                EXCHANGE_PORTAL,
                enter,
                Int.MAX_VALUE,
                LocAngle.South,
                LocShape.CentrepieceStraight,
            )
            locRepo.add(
                ARENA_EXIT_PORTAL,
                leave,
                Int.MAX_VALUE,
                LocAngle.South,
                LocShape.CentrepieceStraight,
            )
            val walls = buildWalls()
            val npcs = spawnNpcs()
            logger.info {
                "[soulwars] portal at $EXCHANGE_PORTAL -> hub at $ARENA_MIN " +
                    "($walls walls, $npcs npcs)"
            }
        }
    }

    /** Allocates the zones covering the arena and blocks every tile outside it. */
    private fun buildArena() {
        val zones = (HubLayout.SIZE + ZONE - 1) / ZONE
        val span = zones * ZONE
        for (dx in 0 until span) {
            for (dz in 0 until span) {
                val x = ARENA_MIN.x + dx
                val z = ARENA_MIN.z + dz
                val inside = dx < HubLayout.SIZE && dz < HubLayout.SIZE
                collision[x, z, ARENA_MIN.level] = if (inside) 0 else CollisionFlag.BLOCK_WALK
            }
        }
    }

    private fun buildWalls(): Int {
        val wall = locTypes.values.firstOrNull { it.internalName == WALL }
        if (wall == null) {
            logger.warn { "[soulwars] wall loc $WALL missing from this cache; no walls placed" }
            return 0
        }
        var placed = 0
        val pieces =
            (HubLayout.SHOPS + HubLayout.EXCHANGE).flatMap(HubLayout::walls) + HubLayout.boardWall()
        for (piece in pieces) {
            val shape = if (piece.corner) LocShape.WallL else LocShape.WallStraight
            locRepo.add(local(piece.x, piece.z), wall, Int.MAX_VALUE, piece.angle, shape)
            placed++
        }
        val board = locTypes.values.firstOrNull { it.internalName == BOARD }
        if (board != null) {
            for (x in HubLayout.BOARD_XS) {
                val at = local(x, HubLayout.BOARD_Z)
                val shape = LocShape.WallDecorStraightNoOffset
                locRepo.add(at, board, Int.MAX_VALUE, LocAngle.North, shape)
                placed++
            }
        }
        return placed
    }

    private fun spawnNpcs(): Int {
        var placed = 0
        for (spawn in HubLayout.SPAWNS) {
            val type = npcTypes.values.firstOrNull { it.internalName == spawn.npc }
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
        const val WALL = "blackpaintedbrickwall_1"
        const val BOARD = "noticeboard"
        const val ZONE = 8

        /** South-west corner of the hub (zone aligned, outside all real map squares). */
        val ARENA_MIN = CoordGrid(9024, 3072, 0)
        val ARENA_ENTRY =
            CoordGrid(ARENA_MIN.x + HubLayout.ARRIVAL_X, ARENA_MIN.z + HubLayout.ARRIVAL_Z, 0)
        val ARENA_EXIT_PORTAL =
            CoordGrid(ARENA_MIN.x + HubLayout.PORTAL_X, ARENA_MIN.z + HubLayout.PORTAL_Z, 0)

        /** Grand Exchange plaza (the ::tp "Grand Exchange" entry is 3164, 3486). */
        val EXCHANGE_PORTAL = CoordGrid(3172, 3489, 0)
        val EXCHANGE_EXIT = CoordGrid(3164, 3486, 0)
    }
}
