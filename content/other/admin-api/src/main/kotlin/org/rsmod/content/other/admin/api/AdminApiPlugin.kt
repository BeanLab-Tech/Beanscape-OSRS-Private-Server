package org.rsmod.content.other.admin.api

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.player.output.mes
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onGameStartup
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Starts the admin API when the game starts and adds `::admintoken` (owners) for issuing and
 * revoking API tokens. Set `ADMIN_API_ENABLED=false` to keep the listener off, and `ADMIN_API_PORT`
 * to change the loopback port (default 8087).
 */
public class AdminApiPlugin
@Inject
constructor(
    private val access: GameAccess,
    private val backend: GameBackend,
    private val tokens: DbTokenStore,
    private val audit: DbAuditSink,
    private val playerList: PlayerList,
) : PluginScript() {
    private val logger = InlineLogger()
    private var server: AdminHttpServer? = null

    override fun ScriptContext.startup() {
        onGameStartup { startServer() }
        onCommand("admintoken") {
            modLevel = modlevels.owner
            desc = "Issue an API token for the admin console: ::admintoken [revoke]"
            cheat { handleToken(this) }
        }
    }

    private fun startServer() {
        if (System.getenv("ADMIN_API_ENABLED")?.equals("false", ignoreCase = true) == true) {
            logger.info { "[admin-api] disabled by ADMIN_API_ENABLED" }
            return
        }
        val port = System.getenv("ADMIN_API_PORT")?.toIntOrNull() ?: DEFAULT_PORT
        val created = AdminHttpServer(port, tokens, AdminRouter(backend, audit))
        try {
            created.start()
            server = created
            Runtime.getRuntime().addShutdownHook(Thread { created.stop() })
        } catch (error: java.io.IOException) {
            logger.error(error) { "[admin-api] could not listen on port $port" }
        }
    }

    private fun handleToken(cheat: Cheat) =
        with(cheat) {
            val accountId = player.accountId
            val uid = player.uid
            val revoke = args.firstOrNull().equals("revoke", ignoreCase = true)
            val raw = if (revoke) null else AdminTokens.generate()
            access.fireAndForget { connection ->
                if (raw == null) {
                    connection
                        .prepareStatement(
                            "UPDATE admin_tokens SET revoked_at = CURRENT_TIMESTAMP " +
                                "WHERE account_id = ? AND revoked_at IS NULL"
                        )
                        .use {
                            it.setInt(1, accountId)
                            it.executeUpdate()
                        }
                } else {
                    connection
                        .prepareStatement(
                            "INSERT INTO admin_tokens (account_id, token_hash, label) VALUES (?, ?, ?)"
                        )
                        .use {
                            it.setInt(1, accountId)
                            it.setString(2, AdminTokens.hash(raw))
                            it.setString(3, "in-game")
                            it.executeUpdate()
                        }
                }
            }
            val message =
                if (raw == null) {
                    "All your admin tokens are revoked."
                } else {
                    "Admin token (shown once, copy it now): $raw"
                }
            uid.resolve(playerList)?.mes(message)
        }

    private companion object {
        private const val DEFAULT_PORT = 8087
    }
}
