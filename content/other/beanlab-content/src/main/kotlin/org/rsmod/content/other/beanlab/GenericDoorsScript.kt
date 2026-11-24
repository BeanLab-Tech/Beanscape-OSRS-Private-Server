package org.rsmod.content.other.beanlab

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.constants
import org.rsmod.api.player.events.interact.LocUnimplementedEvents
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onProtectedEvent
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Makes every *unscripted* door and gate passable.
 *
 * RsMod only scripts the doors and gates it maps by hand (closed loc -> open loc), so most of the
 * world's doors and gates, including the members gates, answer "Nothing interesting happens". This
 * registers a handler in the "unimplemented" slot for every loc whose name is a door or gate and
 * whose first option is "Open". The dispatcher only reaches that slot when no type or content
 * script exists, so hand-scripted (animated) doors keep working exactly as before.
 *
 * Using one walks the player through to the tile on the other side. The loc itself doesn't swing
 * open: unmapped doors have no known open/closed pair.
 */
class GenericDoorsScript @Inject constructor(private val locTypes: LocTypeList) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        var count = 0
        for ((id, type) in locTypes) {
            if (!type.isDoorOrGate()) continue
            onProtectedEvent<LocUnimplementedEvents.Op1>(id) { passThrough(it.loc) }
            count++
        }
        logger.info {
            "[beanlab] generic pass-through registered for $count unscripted doors/gates"
        }
    }

    private fun org.rsmod.game.type.loc.UnpackedLocType.isDoorOrGate(): Boolean {
        val first = op.getOrNull(0) ?: return false
        if (!first.equals("Open", ignoreCase = true)) return false
        val lower = name.lowercase()
        if ("trapdoor" in lower || "trap door" in lower) return false
        return DOOR_NAME.containsMatchIn(lower)
    }

    private fun ProtectedAccess.passThrough(loc: BoundLocInfo) {
        if (loc.shape != LocShape.WallStraight) {
            mes(constants.dm_default, ChatType.Engine)
            return
        }
        teleport(otherSide(loc, coords))
    }

    /** The tile across the wall edge the loc sits on, relative to where the player stands. */
    private fun otherSide(loc: BoundLocInfo, player: CoordGrid): CoordGrid {
        val c = loc.coords
        return when (loc.angle) {
            // Wall on the tile's west edge: tiles (x-1) | (x).
            LocAngle.West -> if (player.x >= c.x) c.translateX(-1) else c
            // Wall on the north edge: tiles (z) | (z+1).
            LocAngle.North -> if (player.z <= c.z) c.translateZ(1) else c
            // Wall on the east edge: tiles (x) | (x+1).
            LocAngle.East -> if (player.x <= c.x) c.translateX(1) else c
            // Wall on the south edge: tiles (z-1) | (z).
            LocAngle.South -> if (player.z >= c.z) c.translateZ(-1) else c
        }
    }

    private companion object {
        val DOOR_NAME = Regex("""\b(door|doors|gate|gates)\b""")
    }
}
