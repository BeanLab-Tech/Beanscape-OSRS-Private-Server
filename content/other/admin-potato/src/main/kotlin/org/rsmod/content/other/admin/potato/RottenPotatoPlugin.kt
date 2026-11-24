package org.rsmod.content.other.admin.potato

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.annotations.InternalApi
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.objs
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.invtx.invClear
import org.rsmod.api.invtx.invDel
import org.rsmod.api.player.output.MiscOutput
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.PlayerSkillXP
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.utils.system.SafeServiceExit
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.game.type.mod.ModLevelType
import org.rsmod.game.type.mod.ModLevelTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.stat.StatTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript

/**
 * BeanLab admin "rotten potato" (obj 5733) — mirrors the Jagex moderator potato.
 *
 * Right-click > Use opens a menu filtered by modlevel: moderator (teleports, my position, kick me),
 * admin (+ set stats, wipe inventory, spawn item, set modlevel up to your own), owner (+ reboots).
 * A non-staff holder has the potato confiscated. Get one as an admin with ::invadd 5733.
 *
 * Rollback: delete content/other/admin-potato and rebuild :server:app:installDist.
 */
public class RottenPotatoPlugin
@Inject
constructor(
    private val statTypes: StatTypeList,
    private val modLevelTypes: ModLevelTypeList,
    private val objTypes: ObjTypeList,
) : PluginScript() {

    private val logger = InlineLogger()

    override fun org.rsmod.plugin.scripts.ScriptContext.startup() {

        // ---- Use the potato: open admin menu ----
        // The onOpHeld1 lambda's *implicit* `this` is the player's ProtectedAccess,
        // but the `Op1` event is passed as the lambda parameter, so inside the `when`
        // branch `this` re-binds to the event. Capture the ProtectedAccess receiver
        // explicitly and call the menu helpers on it.
        onOpHeld1(objs.rotten_potato) { openPotatoMenu() }
    }

    /**
     * One menu entry: [label] shown only to players with at least [level]; [action] re-checks the
     * level before running, in case it changed while the menu was open.
     */
    private class PotatoAction(
        val label: String,
        val level: ModLevelType,
        val action: suspend ProtectedAccess.() -> Unit,
    )

    private val actions =
        listOf(
            PotatoAction("Teleports", modlevels.moderator) { openTeleportMenu() },
            PotatoAction("My position", modlevels.moderator) { myPosition() },
            PotatoAction("Kick me", modlevels.moderator) { kickMe() },
            PotatoAction("Set all stats", modlevels.admin) { setAllStats() },
            PotatoAction("Wipe inventory", modlevels.admin) { wipeInventory() },
            PotatoAction("Spawn item", modlevels.admin) { spawnItem() },
            PotatoAction("Set my modlevel", modlevels.admin) { setMyModlevel() },
            PotatoAction("Reboot world", modlevels.owner) { rebootWorld() },
            PotatoAction("Slow reboot", modlevels.owner) { slowReboot() },
        )

    private suspend fun ProtectedAccess.openPotatoMenu() {
        if (!player.modLevel.hasAccessTo(modlevels.moderator)) {
            // Not staff: confiscate every potato they hold. Nothing else in the menu is reachable.
            logger.warn {
                "[potato] non-staff ${player.displayName} (modLevel=${player.modLevel}) used a " +
                    "rotten potato; removing it"
            }
            while (player.inv.contains(objs.rotten_potato)) {
                val removed = player.invDel(player.inv, objs.rotten_potato, 1, strict = false)
                if (!removed.success) break
            }
            mes("The potato crumbles to dust in your hands.")
            return
        }
        val allowed = actions.filter { player.modLevel.hasAccessTo(it.level) }
        val choice =
            menu("BeanLab Admin Potato", hotkeys = false, allowed.map { it.label } + "Done")
        logger.info { "[potato] ${player.displayName} chose ${allowed.getOrNull(choice)?.label}" }
        // Close the menu modal before any follow-up prompt. Otherwise stringDialog captures the
        // menu as its expected modal, and when that modal closes RsMod aborts the input
        // (ProtectedAccessLostException, swallowed silently).
        ifClose()
        val picked = allowed.getOrNull(choice) ?: return
        if (!player.modLevel.hasAccessTo(picked.level)) {
            mes("You no longer have access to that.")
            return
        }
        picked.action(this)
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
        // Native OSRS item search (same box as the GE): type part of a name, pick from the list.
        // stockMarketRestriction = false so untradeables are searchable too.
        val picked = objDialog("Spawn which item?", stockMarketRestriction = false)
        // The unrestricted search also lists each item's bank placeholder: a separate obj named
        // "null" server-side. Spawn the real item it stands for instead.
        val obj = if (picked.isPlaceholder) objTypes[picked.placeholderlink] ?: picked else picked
        if (obj.isPlaceholder || obj.name == "null") {
            mes("That entry isn't a real item (id ${picked.id}). Pick the other result.")
            return
        }
        val count = countDialog("How many ${obj.name}?")
        if (count <= 0) {
            mes("Nothing spawned.")
            return
        }
        logger.info { "[potato] spawnItem requested ${obj.name} (id=${obj.id}) count=$count" }
        val res = player.invAdd(player.inv, obj.id, count, strict = false)
        logger.info {
            "[potato] spawnItem ${obj.name} id=${obj.id} count=$count success=${res.success}"
        }
        if (res.success) mes("Spawned $count x ${obj.name}.") else mes("Spawn failed (no room?).")
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
        // Only levels up to your own: an admin can step down but never promote themselves.
        val levels =
            listOf(
                    "player" to modlevels.player,
                    "moderator" to modlevels.moderator,
                    "admin" to modlevels.admin,
                    "owner" to modlevels.owner,
                )
                .filter { (_, level) -> player.modLevel.hasAccessTo(level) }
        val choice = menu("Set my modlevel", hotkeys = false, levels.map { it.first } + "Cancel")
        val (name, level) = levels.getOrNull(choice) ?: return
        player.modLevel = modLevelTypes[level]
        logger.info { "[potato] ${player.displayName} set own modlevel to $name" }
        mes("Modlevel now $name.")
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
