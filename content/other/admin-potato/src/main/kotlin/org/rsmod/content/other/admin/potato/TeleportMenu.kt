package org.rsmod.content.other.admin.potato

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onCommand
import org.rsmod.game.cheat.Cheat
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val logger = InlineLogger()

/** Last destination per player (in memory; resets on server restart). */
private val lastTeleport = ConcurrentHashMap<String, TeleportDestination>()

/**
 * BeanLab teleport menu. Opened from the rotten potato ("Teleports") or with ::teleports / ::tp.
 *
 * Uses RsMod's scrollable list modal (the same interface as ::realmconfig). Every menu closes
 * itself (ifClose) before the next prompt, so a follow-up stringDialog isn't aborted.
 */
public suspend fun ProtectedAccess.openTeleportMenu() {
    while (true) {
        val cats = TeleportCatalog.categories
        val last = lastTeleport[player.username]
        val lines = buildList {
            cats.forEach { add("${it.name}  (${it.destinations.size})") }
            add("Search by name...")
            if (last != null) add("Previous: ${last.name}")
            add("Teleport to coordinates...")
            add("Close")
        }
        val choice = menu("BeanLab Teleports", hotkeys = false, choices = lines)
        ifClose()
        val label = lines.getOrNull(choice) ?: return
        when {
            choice < cats.size -> {
                val picked = pickDestination(cats[choice].name, cats[choice].destinations)
                if (picked != null) return teleportTo(picked)
            }
            label == "Search by name..." -> {
                val query = stringDialog("Search teleports (e.g. bank, slayer, guild):")
                val results = TeleportCatalog.search(query)
                if (results.isEmpty()) {
                    mes("No teleports match \"${query.trim()}\".")
                    continue
                }
                if (results.size == 1) return teleportTo(results.first())
                val picked = pickDestination("Results: ${query.trim()}", results)
                if (picked != null) return teleportTo(picked)
            }
            label.startsWith("Previous: ") && last != null -> return teleportTo(last)
            label == "Teleport to coordinates..." -> return teleportToTypedCoords()
            else -> return
        }
    }
}

/** Shows [destinations] with a Back entry. Returns null when the player goes back. */
private suspend fun ProtectedAccess.pickDestination(
    title: String,
    destinations: List<TeleportDestination>,
): TeleportDestination? {
    val lines = listOf("« Back") + destinations.map { it.name }
    val choice = menu(title, hotkeys = false, choices = lines)
    ifClose()
    return if (choice <= 0) null else destinations.getOrNull(choice - 1)
}

private fun ProtectedAccess.teleportTo(dest: TeleportDestination) {
    lastTeleport[player.username] = dest
    logger.info {
        "[teleport] ${player.displayName} -> ${dest.name} (${dest.x}, ${dest.z}, ${dest.level})"
    }
    telejump(dest.coords)
    mes("Teleported to ${dest.name}.")
}

private suspend fun ProtectedAccess.teleportToTypedCoords() {
    val raw = stringDialog("x z [level]  (ex: 3222 3218 0)")
    val parts = raw.trim().split(Regex("[\\s,]+")).map { it.toIntOrNull() }
    if (parts.size !in 2..3 || parts.any { it == null }) {
        mes("Expected: x z [level]  (ex: 3222 3218 0)")
        return
    }
    teleportTo(
        TeleportDestination(
            "${parts[0]}, ${parts[1]}",
            parts[0]!!,
            parts[1]!!,
            parts.getOrNull(2) ?: 0,
        )
    )
}

/** ::teleports and ::tp open the same menu (moderator+, same as the potato). */
public class TeleportCommands
@Inject
constructor(private val protectedAccess: ProtectedAccessLauncher) : PluginScript() {
    override fun ScriptContext.startup() {
        for (name in listOf("teleports", "tp")) {
            onCommand(name) {
                modLevel = modlevels.moderator
                desc = "Open the BeanLab teleport menu"
                cheat { open(this) }
            }
        }
    }

    private fun open(cheat: Cheat) {
        val launched = protectedAccess.launch(cheat.player) { openTeleportMenu() }
        if (!launched) cheat.player.mes("You're busy right now.")
    }
}
