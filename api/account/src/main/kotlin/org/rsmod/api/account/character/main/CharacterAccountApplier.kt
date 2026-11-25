package org.rsmod.api.account.character.main

import jakarta.inject.Inject
import java.time.LocalDateTime
import org.rsmod.api.account.character.CharacterDataStage
import org.rsmod.game.entity.Player
import org.rsmod.game.type.mod.ModLevelTypeList
import org.rsmod.map.CoordGrid

public class CharacterAccountApplier
@Inject
constructor(private val modLevelTypes: ModLevelTypeList) :
    CharacterDataStage.Applier<CharacterAccountData> {
    override fun apply(player: Player, data: CharacterAccountData) {
        player.accountId = data.accountId
        player.characterId = data.characterId

        val accountHash = (data.accountId.toLong() shl 32) or data.realm.toLong()
        val userHash = data.loginName.hashCode().toLong()
        player.userId = data.characterId.toLong()
        player.accountHash = accountHash
        player.userHash = userHash

        val uuid = data.characterId.toLong()
        player.uuid = uuid
        player.observerUUID = uuid

        val device = data.knownDevice
        player.lastKnownDevice = device
        // BeanLab: every account is members, regardless of the DB flag (accounts are created by
        // the server, web, admin panel and Discord bot, all with members DEFAULT 0).
        player.members = true
        player.username = data.loginName
        player.displayName = data.displayName ?: ""
        player.coords = CoordGrid(data.coordX, data.coordZ, data.coordLevel)
        // BeanLab: a player saved inside an instance (e.g. a player-owned house) would log in to a
        // region that no longer exists. Put them outside the Rimmington house portal instead.
        if (data.coordX >= INSTANCE_AREA_START_X) {
            player.coords = INSTANCE_LOGOUT_COORDS
        }
        player.runEnergy = data.runEnergy
        player.xpRate = data.xpRate
        player.lastLogin = LocalDateTime.now()
        player.vars.backing.putAll(data.varps)
        // BeanLab: also report membership days remaining (varp 1780 account_credit), which Jagex
        // sends and RsMod never sets. Clients and plugins that check it (e.g. Microbot's
        // Rs2Player.isMember: account_credit > 0) otherwise treat every account as free-to-play.
        // Set after the saved varps so a stale saved value cannot override it.
        player.vars.backing[ACCOUNT_CREDIT_VARP] = MEMBERSHIP_DAYS
        player.assignModLevel(data.modLevel)
    }

    private fun Player.assignModLevel(modLevelName: String?) {
        val match =
            if (modLevelName != null) {
                modLevelTypes.values.firstOrNull { it.internalName == modLevelName }
            } else {
                null
            }
        modLevel = match ?: modLevelTypes.default()
    }
}

/** Varp 1780 `account_credit`: membership days remaining, shown in the account tab. */
private const val ACCOUNT_CREDIT_VARP = 1780

/** Instances (RsMod `RegionRegistry` working area) start at this x; the map ends near 4100. */
private const val INSTANCE_AREA_START_X = 6400

/** Outside the Rimmington house portal. */
private val INSTANCE_LOGOUT_COORDS = CoordGrid(2953, 3224, 0)

/** Reported membership length for every account (10 years). */
private const val MEMBERSHIP_DAYS = 3650
