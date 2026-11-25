package org.rsmod.content.other.admin.api

import com.fasterxml.jackson.databind.JsonNode
import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.lang.management.ManagementFactory
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.rsmod.annotations.InternalApi
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.config.refs.objs
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.invtx.invClear
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.output.MiscOutput
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.PlayerSkillXP
import org.rsmod.api.player.ui.PlayerInterfaceUpdates
import org.rsmod.api.pw.hash.PasswordHashing
import org.rsmod.api.realm.Realm
import org.rsmod.api.utils.system.SafeServiceExit
import org.rsmod.content.other.admin.potato.TeleportCatalog
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.stat.PlayerSkillXPTable
import org.rsmod.game.type.mod.ModLevelType
import org.rsmod.game.type.mod.ModLevelTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.stat.StatTypeList
import org.rsmod.map.CoordGrid

/**
 * The real [AdminBackend]: reads and changes the running game. Anything that touches a [Player]
 * runs on the game thread through [GameAccess.onGameThread]; account work is plain SQL.
 *
 * Permission rules are not repeated here. [AdminRouter] has already checked rank, target rank and
 * input before any of these methods run.
 */
public class GameBackend
@Inject
constructor(
    private val access: GameAccess,
    private val playerList: PlayerList,
    private val launcher: ProtectedAccessLauncher,
    private val statTypes: StatTypeList,
    private val objTypes: ObjTypeList,
    private val modLevelTypes: ModLevelTypeList,
    private val realm: Realm,
    private val hashing: PasswordHashing,
) : AdminBackend {
    private val logger = InlineLogger()
    private val random = SecureRandom()
    private val rebootScheduled = AtomicBoolean(false)
    private val identities = ConcurrentHashMap<Int, Identity>()

    // ---------------------------------------------------------------- reads

    override fun players(): Map<String, Any?> {
        val rows =
            access.onGameThread {
                playerList.map { player ->
                    player.accountId to
                        mapOf(
                            "name" to player.displayName,
                            "combat" to player.appearance.combatLevel,
                            "modlevel" to rankOf(player).label,
                            "coords" to coords(player),
                            "area" to "",
                        )
                }
            }
        val known = identitiesFor(rows.map { it.first })
        return mapOf("players" to rows.map { (id, row) -> row + identityFields(known[id]) })
    }

    override fun identity(accountId: Int): Map<String, Any?> =
        identityFields(identitiesFor(listOf(accountId))[accountId])

    @OptIn(InternalApi::class)
    override fun player(name: String): Map<String, Any?> =
        access.onGameThread {
            val player = online(name)
            val levels =
                statTypes.values.associate { stat ->
                    SkillNames.toApi(stat.displayName) to player.statMap.getBaseLevel(stat).toInt()
                }
            mapOf(
                "name" to player.displayName,
                "combat" to player.appearance.combatLevel,
                "modlevel" to rankOf(player).label,
                "coords" to coords(player),
                "stats" to levels,
            )
        }

    override fun playerRank(name: String): Rank? =
        access.onGameThread { findOnline(name)?.let(::rankOf) }

    override fun world(): Map<String, Any?> =
        access.onGameThread {
            mapOf(
                "online" to playerList.count(),
                "uptimeSeconds" to ManagementFactory.getRuntimeMXBean().uptime / MILLIS,
                "xpRate" to realm.config.baseXpRate,
            )
        }

    override fun teleports(): Map<String, Any?> =
        mapOf(
            "categories" to
                TeleportCatalog.categories.map { category ->
                    mapOf(
                        "name" to category.name,
                        "destinations" to
                            category.destinations.map {
                                mapOf(
                                    "name" to it.name,
                                    "x" to it.x,
                                    "z" to it.z,
                                    "level" to it.level,
                                )
                            },
                    )
                }
        )

    override fun searchItems(query: String): Map<String, Any?> {
        val needle = query.trim().lowercase()
        if (needle.length < MIN_SEARCH) throw ApiError(400, "Type at least two letters.")
        val items =
            objTypes.values
                .asSequence()
                .filter { it.name.isNotBlank() && !it.name.equals("null", ignoreCase = true) }
                .filter { it.certtemplate <= 0 && it.placeholdertemplate == 0 }
                .filter { it.name.lowercase().contains(needle) }
                .map { it to ItemKinds.classify(it) }
                .sortedWith(
                    compareBy<Pair<UnpackedObjType, String>>({ it.second == ItemKinds.JUNK })
                        .thenBy { it.first.name.length }
                )
                .take(MAX_ITEMS)
                .map { (type, kind) ->
                    mapOf(
                        "id" to type.id,
                        "name" to type.name,
                        "kind" to kind,
                        "tradeable" to type.tradeable,
                        "members" to type.members,
                    )
                }
                .toList()
        return mapOf("items" to items)
    }

    // ---------------------------------------------------------------- player actions

    override fun playerAction(actor: Actor, name: String, action: String, body: JsonNode): String =
        access.onGameThread {
            val target = online(name)
            when (action) {
                "teleport-to" -> teleportTo(actor, target)
                "summon" -> summon(actor, target)
                "heal" -> heal(target)
                "kick" -> kick(target)
                "give" -> give(target, body)
                "wipe-inventory" -> wipe(target)
                "stats" -> setStats(target, body)
                "modlevel" -> setOnlineRank(target, Rank.parse(body.path("modlevel").asText()))
                else -> throw ApiError(404, "Unknown action: $action.")
            }
        }

    private fun teleportTo(actor: Actor, target: Player): String {
        val me = actorPlayer(actor)
        val destination = target.coords
        launch(me) { telejump(destination) }
        return "Teleported to ${target.displayName}."
    }

    private fun summon(actor: Actor, target: Player): String {
        val me = actorPlayer(actor)
        val destination = me.coords
        launch(target) { telejump(destination) }
        return "Summoned ${target.displayName}."
    }

    private fun heal(target: Player): String {
        launch(target) { statRestoreAll(statTypes.values) }
        return "Restored ${target.displayName}'s stats."
    }

    private fun kick(target: Player): String {
        MiscOutput.logoutKicked(target)
        return "Kicked ${target.displayName}."
    }

    private fun give(target: Player, body: JsonNode): String {
        val id = body.path("id").asInt(-1)
        val qty = body.path("qty").asInt(0)
        val type = objTypes[id] ?: throw ApiError(400, "No item with id $id.")
        if (qty < 1) throw ApiError(400, "Quantity must be at least 1.")
        val result = target.invAdd(target.inv, id, qty, strict = false)
        if (!result.success) throw ApiError(409, "${target.displayName} has no room for that.")
        return "Gave ${target.displayName} $qty x ${type.name}."
    }

    private fun wipe(target: Player): String {
        target.invClear(target.inv)
        target.invAdd(target.inv, objs.rotten_potato, 1, strict = false)
        return "Wiped ${target.displayName}'s inventory."
    }

    private fun setStats(target: Player, body: JsonNode): String {
        val requested = body.path("levels")
        if (!requested.isObject) throw ApiError(400, "Send levels as an object of skill to level.")
        val levels = mutableMapOf<String, Int>()
        requested.fields().forEach { (skill, value) ->
            val level = value.asInt(-1)
            if (level !in 1..MAX_LEVEL) throw ApiError(400, "$skill must be 1 to $MAX_LEVEL.")
            levels[skill] = level
        }
        launch(target) { applyLevels(levels) }
        return "Updated ${levels.size} levels for ${target.displayName}."
    }

    @OptIn(InternalApi::class)
    private fun ProtectedAccess.applyLevels(levels: Map<String, Int>) {
        for (stat in statTypes.values) {
            val level = levels[SkillNames.toApi(stat.displayName)] ?: continue
            val xp = PlayerSkillXPTable.getXPFromLevel(level)
            val baseLevel = player.statMap.getBaseLevel(stat)
            val targetLevel = maxOf(stat.minLevel, level)
            if (baseLevel > targetLevel) {
                player.statMap.setCurrentLevel(stat, baseLevel)
                val delta = stat(stat) - targetLevel
                player.statMap.setXP(stat, xp)
                player.statMap.setBaseLevel(stat, targetLevel.toByte())
                if (delta > 0) statSub(stat, constant = delta, percent = 0)
            } else {
                val xpDelta = xp - player.statMap.getXP(stat)
                player.statMap.setCurrentLevel(stat, targetLevel.toByte())
                statAdvance(stat, xpDelta.toDouble(), rate = 1.0)
            }
        }
        player.appearance.combatLevel = PlayerSkillXP.calculateCombatLevel(player)
        PlayerInterfaceUpdates.updateCombatLevel(player)
    }

    private fun setOnlineRank(target: Player, rank: Rank): String {
        target.modLevel = modLevelTypes[modLevelRef(rank)]
        saveRank(target.accountId, rank)
        return "${target.displayName} is now ${rank.label}."
    }

    // ---------------------------------------------------------------- teleport, broadcast, reboot

    override fun teleport(actor: Actor, target: String, x: Int, z: Int, level: Int): String =
        access.onGameThread {
            val player = online(target)
            launch(player) { telejump(CoordGrid(x, z, level)) }
            "Teleported ${player.displayName} to $x, $z."
        }

    override fun broadcast(actor: Actor, message: String): String =
        access.onGameThread {
            val text = "[Broadcast] $message"
            playerList.forEach { it.mes(text, ChatType.Broadcast) }
            "Sent to ${playerList.count()} players."
        }

    override fun reboot(actor: Actor, seconds: Int): String {
        if (!rebootScheduled.compareAndSet(false, true)) {
            throw ApiError(409, "A reboot is already counting down.")
        }
        val scheduler =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "admin-api-reboot").also { it.isDaemon = true }
            }
        val warnings = listOf(seconds, seconds / 2, 30, 10).filter { it in 1..seconds }.distinct()
        for (warning in warnings) {
            scheduler.schedule(
                { announce("The world restarts in ${describe(warning)}.") },
                (seconds - warning).toLong(),
                TimeUnit.SECONDS,
            )
        }
        scheduler.schedule(
            {
                logger.info { "[admin-api] reboot by ${actor.username}" }
                SafeServiceExit.terminate()
            },
            seconds.toLong(),
            TimeUnit.SECONDS,
        )
        announce("The world restarts in ${describe(seconds)}.")
        return "Reboot in ${describe(seconds)}."
    }

    private fun announce(text: String) {
        try {
            access.onGameThread {
                playerList.forEach { it.mes("[Broadcast] $text", ChatType.Broadcast) }
            }
        } catch (error: ApiError) {
            logger.warn { "[admin-api] could not announce reboot: ${error.message}" }
        }
    }

    private fun describe(seconds: Int): String =
        if (seconds >= SECONDS_PER_MINUTE && seconds % SECONDS_PER_MINUTE == 0) {
            "${seconds / SECONDS_PER_MINUTE} minute(s)"
        } else {
            "$seconds seconds"
        }

    // ---------------------------------------------------------------- accounts

    override fun accounts(query: String): Map<String, Any?> {
        val onlineIds = access.onGameThread { playerList.map { it.accountId }.toSet() }
        val rows =
            access.query { connection ->
                connection
                    .prepareStatement(
                        "SELECT id, login_username, modlevel, discord_id, discord_username, " +
                            "discord_avatar FROM accounts " +
                            "WHERE login_username LIKE ? ORDER BY id DESC LIMIT $MAX_ACCOUNTS"
                    )
                    .use { statement ->
                        statement.setString(1, "%" + query.trim().lowercase() + "%")
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        mapOf(
                                            "id" to rs.getInt(1),
                                            "username" to rs.getString(2),
                                            "modlevel" to Rank.parse(rs.getString(3)).label,
                                            "discord" to (rs.getString(5) ?: ""),
                                            "avatar" to
                                                DiscordAvatars.url(
                                                    rs.getLong(4).takeUnless { rs.wasNull() },
                                                    rs.getString(6),
                                                ),
                                        )
                                    )
                                }
                            }
                        }
                    }
            }
        return mapOf("accounts" to rows.map { it + ("online" to (it["id"] in onlineIds)) })
    }

    override fun accountRank(id: Int): Rank? =
        access.query { connection ->
            connection.prepareStatement("SELECT modlevel FROM accounts WHERE id = ?").use {
                it.setInt(1, id)
                it.executeQuery().use { rs -> if (rs.next()) Rank.parse(rs.getString(1)) else null }
            }
        }

    override fun createAccount(actor: Actor, username: String, rank: Rank): Map<String, Any?> {
        val password = generatePassword()
        val hash = hashing.hash(password.toCharArray())
        val created =
            access.query { connection ->
                val taken =
                    connection
                        .prepareStatement(
                            "SELECT COUNT(*) FROM accounts WHERE LOWER(login_username) = ? " +
                                "OR LOWER(display_name) = ?"
                        )
                        .use {
                            it.setString(1, username)
                            it.setString(2, username)
                            it.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                        }
                if (taken) {
                    false
                } else {
                    connection
                        .prepareStatement(
                            "INSERT INTO accounts (login_username, display_name, password_hash, " +
                                "modlevel) VALUES (?, ?, ?, ?)"
                        )
                        .use {
                            it.setString(1, username)
                            it.setString(2, username)
                            it.setString(3, hash)
                            it.setString(4, storedRank(rank))
                            it.executeUpdate()
                        }
                    true
                }
            }
        if (!created) throw ApiError(409, "That name is already taken.")
        return mapOf("message" to "Created $username.", "password" to password)
    }

    override fun setAccountRank(actor: Actor, id: Int, rank: Rank): String {
        saveRank(id, rank)
        access.onGameThread {
            playerList.firstOrNull { it.accountId == id }?.modLevel =
                modLevelTypes[modLevelRef(rank)]
        }
        return "Mod level is now ${rank.label}."
    }

    override fun resetPassword(actor: Actor, id: Int): Map<String, Any?> {
        val password = generatePassword()
        val hash = hashing.hash(password.toCharArray())
        access.query { connection ->
            connection.prepareStatement("UPDATE accounts SET password_hash = ? WHERE id = ?").use {
                it.setString(1, hash)
                it.setInt(2, id)
                it.executeUpdate()
            }
        }
        return mapOf("message" to "Password reset.", "password" to password)
    }

    override fun deleteAccount(actor: Actor, id: Int): String {
        if (id == actor.accountId) throw ApiError(409, "You can't delete your own account.")
        access.onGameThread {
            playerList.firstOrNull { it.accountId == id }?.let(MiscOutput::logoutKicked)
        }
        access.query { connection ->
            connection.prepareStatement("DELETE FROM accounts WHERE id = ?").use {
                it.setInt(1, id)
                it.executeUpdate()
            }
        }
        return "Account deleted."
    }

    override fun audit(limit: Int): Map<String, Any?> {
        val entries =
            access.query { connection ->
                connection
                    .prepareStatement(
                        "SELECT created_at, actor, action, target, detail, ok FROM admin_audit " +
                            "ORDER BY id DESC LIMIT ?"
                    )
                    .use { statement ->
                        statement.setInt(1, limit)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    val detail =
                                        listOfNotNull(rs.getString(4), rs.getString(5))
                                            .joinToString(": ")
                                    add(
                                        mapOf(
                                            "time" to
                                                rs.getString(1).orEmpty().takeLast(TIME_CHARS),
                                            "actor" to rs.getString(2),
                                            "action" to
                                                rs.getString(3) +
                                                    if (rs.getInt(6) == 0) " (refused)" else "",
                                            "detail" to detail,
                                        )
                                    )
                                }
                            }
                        }
                    }
            }
        return mapOf("entries" to entries)
    }

    // ---------------------------------------------------------------- helpers

    private fun saveRank(accountId: Int, rank: Rank) {
        access.query { connection ->
            connection.prepareStatement("UPDATE accounts SET modlevel = ? WHERE id = ?").use {
                it.setString(1, storedRank(rank))
                it.setInt(2, accountId)
                it.executeUpdate()
            }
        }
    }

    /** Ordinary players are stored as NULL, like every other account. */
    private fun storedRank(rank: Rank): String? = if (rank == Rank.Player) null else rank.label

    private fun modLevelRef(rank: Rank): ModLevelType =
        when (rank) {
            Rank.Player -> modlevels.player
            Rank.Moderator -> modlevels.moderator
            Rank.Admin -> modlevels.admin
            Rank.Owner -> modlevels.owner
        }

    private fun rankOf(player: Player): Rank =
        when {
            player.modLevel.hasAccessTo(modlevels.owner) -> Rank.Owner
            player.modLevel.hasAccessTo(modlevels.admin) -> Rank.Admin
            player.modLevel.hasAccessTo(modlevels.moderator) -> Rank.Moderator
            else -> Rank.Player
        }

    private fun coords(player: Player): String =
        "${player.coords.x}, ${player.coords.z}, ${player.coords.level}"

    private fun findOnline(name: String): Player? =
        playerList.firstOrNull { it.displayName.equals(name, ignoreCase = true) }

    private fun online(name: String): Player =
        findOnline(name) ?: throw ApiError(404, "$name isn't online.")

    /** Your own character; actions like teleport-to need you logged in. */
    private fun actorPlayer(actor: Actor): Player =
        playerList.firstOrNull { it.accountId == actor.accountId }
            ?: throw ApiError(409, "Log in to the game first, then try again.")

    private fun launch(player: Player, block: suspend ProtectedAccess.() -> Unit) {
        if (!launcher.launch(player, block = block)) {
            throw ApiError(409, "${player.displayName} is busy right now. Try again in a moment.")
        }
    }

    private fun generatePassword(): String = buildString {
        repeat(PASSWORD_LENGTH) { append(PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)]) }
    }

    private data class Identity(
        val discordId: Long?,
        val discordName: String,
        val avatarHash: String?,
        val loadedAt: Long,
    )

    private fun identityFields(identity: Identity?): Map<String, Any?> =
        mapOf(
            "avatar" to DiscordAvatars.url(identity?.discordId, identity?.avatarHash),
            "discord" to (identity?.discordName ?: ""),
        )

    /**
     * Discord identity for accounts, cached for a minute because the console polls the player list
     * every few seconds. A busy database leaves the old entry in place rather than failing the
     * poll.
     */
    private fun identitiesFor(ids: Collection<Int>): Map<Int, Identity> {
        val now = System.currentTimeMillis()
        val stale =
            ids.distinct().filter { (identities[it]?.loadedAt ?: 0L) + IDENTITY_TTL_MS < now }
        if (stale.isNotEmpty()) {
            try {
                val rows =
                    access.query { connection ->
                        val marks = stale.joinToString(",") { "?" }
                        connection
                            .prepareStatement(
                                "SELECT id, discord_id, discord_username, discord_avatar " +
                                    "FROM accounts WHERE id IN ($marks)"
                            )
                            .use { statement ->
                                stale.forEachIndexed { index, id ->
                                    statement.setInt(index + 1, id)
                                }
                                statement.executeQuery().use { rs ->
                                    buildList {
                                        while (rs.next()) {
                                            add(
                                                rs.getInt(1) to
                                                    Identity(
                                                        rs.getLong(2).takeUnless { rs.wasNull() },
                                                        rs.getString(3) ?: "",
                                                        rs.getString(4),
                                                        now,
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                    }
                val found = rows.toMap()
                stale.forEach { id -> identities[id] = found[id] ?: Identity(null, "", null, now) }
            } catch (ex: ApiError) {
                logger.debug { "Discord identity lookup skipped: ${ex.message}" }
            }
        }
        return ids.distinct().mapNotNull { id -> identities[id]?.let { id to it } }.toMap()
    }

    private companion object {
        private const val IDENTITY_TTL_MS = 60_000L
        private const val MILLIS = 1000L
        private const val MIN_SEARCH = 2
        private const val MAX_ITEMS = 40
        private const val MAX_ACCOUNTS = 60
        private const val MAX_LEVEL = 99
        private const val SECONDS_PER_MINUTE = 60
        private const val PASSWORD_LENGTH = 16
        private const val TIME_CHARS = 8
        private const val PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    }
}
