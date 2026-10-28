package org.rsmod.content.other.commands

import jakarta.inject.Inject
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.api.db.gateway.GameDbManager
import org.rsmod.api.db.gateway.model.GameDbResult
import org.rsmod.api.db.gateway.model.fold
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
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
        onCommand("name", "Change your display name: ::name <new name>", ::changeName)
    }

    private fun changeName(cheat: Cheat) =
        with(cheat) {
            val newName = args.joinToString(" ").trim()
            val launched = protectedAccess.launch(player) { applyName(newName) }
            if (!launched) {
                player.mes("You are busy - try again in a moment.")
            }
        }

    private suspend fun ProtectedAccess.applyName(newName: String) {
        if (newName.isEmpty()) {
            mes("Usage: ::name <new name> (2-12 chars: letters, numbers, space, _ or -)")
            return
        }
        if (!NAME_REGEX.matches(newName)) {
            mes("Invalid name: use 2-12 characters, letters, numbers, spaces, _ or - only.")
            return
        }
        val accountId = player.accountId
        db.request(
            request = { conn -> GameDbResult.Ok(updateDisplayName(conn, accountId, newName)) },
            response = { result ->
                result.fold(
                    onOk = { code ->
                        when (code) {
                            NAME_TAKEN -> player.mes("That display name is already taken.")
                            ACCOUNT_MISSING -> player.mes("Account record not found - contact an admin.")
                            else -> {
                                player.displayName = newName
                                player.mes("Your display name is now  + newName + .")
                            }
                        }
                    },
                    onErr = { player.mes("Database error - name not changed, try again.") },
                )
            },
        )
    }

    private fun updateDisplayName(
        conn: DatabaseConnection,
        accountId: Int,
        newName: String,
    ): Int {
        val check =
            conn.prepareStatement(
                "SELECT COUNT(*) FROM accounts WHERE display_name = ? AND id != ?"
            )
        check.setString(1, newName)
        check.setInt(2, accountId)
        val rs = check.executeQuery()
        val taken = rs.next() && rs.getInt(1) > 0
        rs.close()
        check.close()
        if (taken) {
            return NAME_TAKEN
        }
        val upd =
            conn.prepareStatement(
                "UPDATE accounts SET display_name = ?, updated_at = CURRENT_TIMESTAMP " +
                    "WHERE id = ?"
            )
        upd.setString(1, newName)
        upd.setInt(2, accountId)
        val rows = upd.executeUpdate()
        upd.close()
        return if (rows > 0) NAME_OK else ACCOUNT_MISSING
    }

    private companion object {
        const val NAME_TAKEN = -1
        const val NAME_OK = 1
        const val ACCOUNT_MISSING = 0
        val NAME_REGEX = Regex("^[A-Za-z0-9_ -]{2,12}$")
    }
}
