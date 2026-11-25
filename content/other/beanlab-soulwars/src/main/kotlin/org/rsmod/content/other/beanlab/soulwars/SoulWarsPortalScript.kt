package org.rsmod.content.other.beanlab.soulwars

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.flag.CollisionFlag

/**
 * A Soul Wars portal at the Grand Exchange that leads to a blank walking arena.
 *
 * In the cache the Soul Wars portal is the loc `soul_wars_edgeville_portal` (the one that sits in
 * Edgeville in the real game). RsMod ships no Soul Wars content, so nothing places or handles it:
 * the portal is spawned here on [GameLifecycle.Startup] and its "Enter" option teleports to
 * [ARENA_ENTRY]. A `soul_wars_leave_soulwars_portal` inside the arena returns to the Exchange.
 *
 * The arena is [ARENA_SIZE] x [ARENA_SIZE] tiles of flat, walkable ground at [ARENA_MIN]. That
 * square is outside every real map square. The flat terrain for its four map squares (141..142 x
 * 48..49) is packed into the cache by `map/SoulWarsMapTiles` and `map/SoulWarsMapLocs` when the
 * `packCache` task runs; swap those `m` files for real terrain to build the area out later. The
 * server allocates the collision zones itself. Tiles of the allocated zones that fall outside the
 * arena are marked blocked, so players cannot walk off the edge.
 */
class SoulWarsPortalScript
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val locRepo: LocRepository,
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
            logger.info { "[soulwars] portal at $EXCHANGE_PORTAL -> arena at $ARENA_MIN" }
        }
    }

    /** Allocates the zones covering the arena and blocks every tile outside it. */
    private fun buildArena() {
        val zones = (ARENA_SIZE + ZONE - 1) / ZONE
        val span = zones * ZONE
        for (dx in 0 until span) {
            for (dz in 0 until span) {
                val x = ARENA_MIN.x + dx
                val z = ARENA_MIN.z + dz
                val inside = dx < ARENA_SIZE && dz < ARENA_SIZE
                collision[x, z, ARENA_MIN.level] = if (inside) 0 else CollisionFlag.BLOCK_WALK
            }
        }
    }

    private companion object {
        const val ENTER_PORTAL = "soul_wars_edgeville_portal"
        const val LEAVE_PORTAL = "soul_wars_leave_soulwars_portal"
        const val ARENA_SIZE = 100
        const val ZONE = 8

        /** South-west corner of the arena (zone aligned, outside all real map squares). */
        val ARENA_MIN = CoordGrid(9024, 3072, 0)
        val ARENA_ENTRY = CoordGrid(9074, 3122, 0)
        val ARENA_EXIT_PORTAL = CoordGrid(9074, 3125, 0)

        /** Grand Exchange plaza (the ::tp "Grand Exchange" entry is 3164, 3486). */
        val EXCHANGE_PORTAL = CoordGrid(3165, 3489, 0)
        val EXCHANGE_EXIT = CoordGrid(3164, 3486, 0)
    }
}
