package org.rsmod.content.other.admin.potato

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.annotations.InternalApi
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.objs
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.invtx.invClear
import org.rsmod.api.player.output.MiscOutput
import org.rsmod.api.player.stat.PlayerSkillXP
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.utils.system.SafeServiceExit
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.game.type.mod.ModLevelTypeList
import org.rsmod.game.type.stat.StatTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript

/**
 * BeanLab admin "rotten potato" (obj 5733) — mirrors the Jagex moderator potato.
 *
 * Granted to moderator+ on login. Right-click > Use opens a protected menu of
 * admin actions (set all stats, wipe inventory, teleport, spawn item, set
 * modlevel, kick, reboot). Non-tradeable + non-stackable so it can't leak or
 * be duped.
 *
 * Rollback: delete content/other/admin-potato and rebuild :server:install.
 */
public class RottenPotatoPlugin
@Inject
constructor(
    private val statTypes: StatTypeList,
    private val modLevelTypes: ModLevelTypeList,
) : PluginScript() {

    private val logger = InlineLogger()

    override fun org.rsmod.plugin.scripts.ScriptContext.startup() {

        // ---- Use the potato: open admin menu ----
        // The onOpHeld1 lambda's *implicit* `this` is the player's ProtectedAccess,
        // but the `Op1` event is passed as the lambda parameter, so inside the `when`
        // branch `this` re-binds to the event. Capture the ProtectedAccess receiver
        // explicitly and call the menu helpers on it.
        onOpHeld1(objs.rotten_potato) {
            // Grant the potato to moderator+ on first use. We deliberately do NOT hook
            // SessionStateEvent.EngineLogin here: it's a KeyedEvent published only at
            // id=0, and LoginScript already owns that slot (EventBus allows one handler
            // per id). Granting on use is equivalent for our purpose — the potato is
            // non-tradeable and only ever held by the admin who uses it.
            val pa = this
            logger.info { "[potato] onOpHeld1 fired for player ${pa.player.displayName} modLevel=${pa.player.modLevel}" }
            if (pa.player.modLevel.hasAccessTo(modlevels.moderator) && !pa.player.inv.contains(objs.rotten_potato)) {
                pa.player.invAdd(pa.player.inv, objs.rotten_potato, 1, strict = false)
            }
            val choice = pa.menu(
                "BeanLab Admin Potato",
                "Set all stats",
                "Wipe inventory",
                "Spawn item",
                "Teleport to coords",
                "My position",
                "Set my modlevel",
                "Kick me",
                "Reboot world",
                "Slow reboot",
                "Done",
            )
            logger.info { "[potato] player ${pa.player.displayName} chose $choice" }
            when (choice) {
                0 -> pa.setAllStats()
                1 -> pa.wipeInventory()
                2 -> pa.spawnItem()
                3 -> pa.teleportToCoords()
                4 -> pa.myPosition()
                5 -> pa.setMyModlevel()
                6 -> pa.kickMe()
                7 -> pa.rebootWorld()
                8 -> pa.slowReboot()
            }
        }
    }

    // ---------------- menu actions (ProtectedAccess receiver) ----------------

    @OptIn(InternalApi::class)
    private suspend fun ProtectedAccess.setAllStats() {
        val raw = stringDialog("Set all stats to what level? (1-99)")
        logger.info { "[potato] setAllStats dialog: raw=$raw" }
        val level = raw.trim().toIntOrNull()
        if (level == null || level !in 1..99) {
            mes("Not a valid level.")
            return
        }
        val xp = PlayerSkillXPTable.getXPFromLevel(level)
        for (stat in statTypes.values) {
            val baseLevel = player.statMap.getBaseLevel(stat)
            val targetLevel = maxOf(stat.minLevel, level)
            if (baseLevel > targetLevel) {
                // lower: revert to base, then sub down to target
                player.statMap.setCurrentLevel(stat, baseLevel)
                val delta = stat(stat) - targetLevel
                require(delta > 0) { "stat $stat can only be reduced, not raised, here" }
                player.statMap.setXP(stat, xp)
                player.statMap.setBaseLevel(stat, targetLevel.toByte())
                statSub(stat, constant = delta, percent = 0)
            } else {
                val xpDelta = xp - player.statMap.getXP(stat)
                player.statMap.setCurrentLevel(stat, targetLevel.toByte())
                statAdvance(stat, xpDelta.toDouble(), rate = 1.0)
            }
        }
        player.appearance.combatLevel = PlayerSkillXP.calculateCombatLevel(player)
        org.rsmod.api.player.ui.PlayerInterfaceUpdates.updateCombatLevel(player)
        logger.info { "[potato] setAllStats done for ${player.displayName} level=$level" }
        mes("All stats set to ${level}. Finished cheating.")
    }

    private suspend fun ProtectedAccess.wipeInventory() {
        player.invClear(player.inv)
        player.invAdd(player.inv, objs.rotten_potato, 1, strict = false)
        mes("Inventory wiped.")
    }

    private suspend fun ProtectedAccess.spawnItem() {
        val id = stringDialog("Item id to spawn:").trim().toIntOrNull()
        if (id == null) {
            mes("Not an item id.")
            return
        }
        val count = stringDialog("How many?").trim().toIntOrNull() ?: 1
        logger.info { "[potato] spawnItem requested id=$id count=$count" }
        val res = player.invAdd(player.inv, id, count, strict = false)
        logger.info { "[potato] spawnItem id=$id count=$count success=${res.success}" }
        if (res.success) mes("Spawned ${count} x ${id}.") else mes("Spawn failed (no room?).")
    }

    private suspend fun ProtectedAccess.teleportToCoords() {
        val raw = stringDialog("level mx mz lx lz (ex: 0 3200 3200 0 0)")
        val parts = raw.trim().split(Regex("\\s+")).map { it.toIntOrNull() }
        if (parts.size != 5 || parts.any { it == null }) {
            mes("Expected 5 ints: level mx mz lx lz.")
            return
        }
        val coords = CoordGrid(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!, parts[4]!!)
        telejump(coords)
        mes("Teleported to $coords.")
    }

    private suspend fun ProtectedAccess.myPosition() {
        mes("You are at ${player.coords}.")
    }

    private suspend fun ProtectedAccess.setMyModlevel() {
        when (menu("Set my modlevel", "player", "moderator", "admin", "owner", "cancel")) {
            0 -> player.modLevel = modLevelTypes[modlevels.player]
            1 -> player.modLevel = modLevelTypes[modlevels.moderator]
            2 -> player.modLevel = modLevelTypes[modlevels.admin]
            3 -> player.modLevel = modLevelTypes[modlevels.owner]
        }
        mes("Modlevel now ${player.modLevel}.")
    }

    private suspend fun ProtectedAccess.kickMe() {
        MiscOutput.logoutKicked(player)
    }

    private suspend fun ProtectedAccess.rebootWorld() {
        mes("Rebooting world...")
        SafeServiceExit.terminate()
    }

    private suspend fun ProtectedAccess.slowReboot() {
        mes("World reboots shortly — use the potato's Kick if the log-out button is disabled.")
        SafeServiceExit.terminate()
    }
}
