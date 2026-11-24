package org.rsmod.content.other.admin.api

import jakarta.inject.Inject

/**
 * Checks a bearer token against `admin_tokens`. The account's mod level is read fresh each time, so
 * a demoted or deleted account loses access on its next request. Only ranks of at least
 * [MIN_TOKEN_RANK] may use the API for now (owners only).
 */
public class DbTokenStore @Inject constructor(private val access: GameAccess) : TokenStore {
    override fun verify(rawToken: String): Actor? {
        val hash = AdminTokens.hash(rawToken)
        return access.query { connection ->
            val actor =
                connection
                    .prepareStatement(
                        "SELECT a.id, a.login_username, a.modlevel FROM admin_tokens t " +
                            "JOIN accounts a ON a.id = t.account_id " +
                            "WHERE t.token_hash = ? AND t.revoked_at IS NULL"
                    )
                    .use { statement ->
                        statement.setString(1, hash)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                Actor(rs.getInt(1), rs.getString(2), Rank.parse(rs.getString(3)))
                            } else {
                                null
                            }
                        }
                    }
            if (actor != null && actor.rank.atLeast(MIN_TOKEN_RANK)) {
                connection
                    .prepareStatement(
                        "UPDATE admin_tokens SET last_used_at = CURRENT_TIMESTAMP " +
                            "WHERE token_hash = ?"
                    )
                    .use { statement ->
                        statement.setString(1, hash)
                        statement.executeUpdate()
                    }
                actor
            } else {
                null
            }
        }
    }

    public companion object {
        /** Widen this to let moderators or admins connect. Owners only for now. */
        public val MIN_TOKEN_RANK: Rank = Rank.Owner
    }
}

/** Writes every admin action to `admin_audit` without making the request wait for it. */
public class DbAuditSink @Inject constructor(private val access: GameAccess) : AuditSink {
    override fun record(entry: AuditEntry) {
        access.fireAndForget { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO admin_audit (actor_account_id, actor, action, target, detail, ok) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
                )
                .use { statement ->
                    statement.setInt(1, entry.actor.accountId)
                    statement.setString(2, entry.actor.username)
                    statement.setString(3, entry.action)
                    statement.setString(4, entry.target)
                    statement.setString(5, entry.detail)
                    statement.setInt(6, if (entry.ok) 1 else 0)
                    statement.executeUpdate()
                }
        }
    }
}
