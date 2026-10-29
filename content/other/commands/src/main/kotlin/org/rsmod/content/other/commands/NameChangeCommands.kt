package org.rsmod.content.other.commands

import jakarta.inject.Inject
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.api.db.gateway.GameDbManager
import org.rsmod.api.db.gateway.model.GameDbResult
import org.rsmod.api.db.gateway.model.fold
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.script.onCommand
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

class NameChangeCommands
@Inject
constructor(
    private val playerList: PlayerList,
    private val db: GameDbManager,
    private val protectedAccess: ProtectedAccessLauncher,
) : PluginScript() {

    override fun ScriptContext.startup() {
        // Deliberately not the internal `onCommand(command, desc, cheat)` helper in ScriptHelpers:
        // that one restricts commands to admins, and name changes are for every player.
        onCommand("name") {
            desc = "Change your display name: ::name <new name>"
            cheat { changeName(this) }
        }
    }

    private fun changeName(cheat: Cheat) =
        with(cheat) {
            val newName = args.joinToString(" ").trim()
            val launched = protectedAccess.launch(player) { applyName(newName) }
            if (!launched) {
                player.mes("You are busy - try again in a moment.")
            }
        }

    private fun ProtectedAccess.applyName(newName: String) {
        if (newName.isEmpty()) {
            mes("Usage: ::name <new name> (2-12 chars: letters, numbers, space, _ or -)")
            return
        }
        if (!NAME_REGEX.matches(newName)) {
            mes("Invalid name: use 2-12 characters, letters, numbers, spaces, _ or - only.")
            return
        }
        if (newName == player.displayName) {
            mes("That is already your display name.")
            return
        }
        // Accounts that have not been saved since their last rename are only visible in memory.
        val onlineClash =
            playerList.any {
                it.accountId != player.accountId && it.displayName.equals(newName, ignoreCase = true)
            }
        if (onlineClash) {
            mes("That display name is already taken.")
            return
        }
        val accountId = player.accountId
        val playerUid = player.uid
        db.request(
            request = { conn -> GameDbResult.Ok(updateDisplayName(conn, accountId, newName)) },
            response = response@{ result ->
                val player = playerUid.resolve(playerList) ?: return@response
                result.fold(
                    onOk = { code ->
                        when (code) {
                            NAME_TAKEN -> player.mes("That display name is already taken.")
                            ACCOUNT_MISSING ->
                                player.mes("Account record not found - contact an admin.")
                            else -> {
                                player.displayName = newName
                                player.rebuildAppearance()
                                player.mes("Your display name is now $newName.")
                            }
                        }
                    },
                    onErr = { player.mes("Database error - name not changed, try again.") },
                )
            },
        )
    }

    private fun updateDisplayName(conn: DatabaseConnection, accountId: Int, newName: String): Int {
        // Case-insensitive, and login usernames count too, so nobody can pose as "Zezima" while
        // the real zezima's display name differs or is still unset.
        val taken =
            conn
                .prepareStatement(
                    "SELECT COUNT(*) FROM accounts WHERE id != ? AND " +
                        "(LOWER(display_name) = LOWER(?) OR LOWER(login_username) = LOWER(?))"
                )
                .use {
                    it.setInt(1, accountId)
                    it.setString(2, newName)
                    it.setString(3, newName)
                    it.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                }
        if (taken) {
            return NAME_TAKEN
        }
        val rows =
            conn
                .prepareStatement(
                    "UPDATE accounts SET display_name = ?, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE id = ?"
                )
                .use {
                    it.setString(1, newName)
                    it.setInt(2, accountId)
                    it.executeUpdate()
                }
        return if (rows > 0) NAME_OK else ACCOUNT_MISSING
    }

    private companion object {
        const val NAME_TAKEN = -1
        const val NAME_OK = 1
        const val ACCOUNT_MISSING = 0
        val NAME_REGEX = Regex("^[A-Za-z0-9_-]([A-Za-z0-9_ -]{0,10}[A-Za-z0-9_-])$")
    }
}
