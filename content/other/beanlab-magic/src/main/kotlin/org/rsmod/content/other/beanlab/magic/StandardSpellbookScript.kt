package org.rsmod.content.other.beanlab.magic

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.combat.commons.magic.MagicSpell
import org.rsmod.api.combat.manager.MagicRuneManager
import org.rsmod.api.combat.manager.MagicRuneManager.Companion.isSuccess
import org.rsmod.api.config.refs.components
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.objs
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.config.refs.varbits
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.stat.magicLvl
import org.rsmod.api.player.ui.ifSetEvents
import org.rsmod.api.script.onIfOpen
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onIfOverlayButtonT
import org.rsmod.api.spells.MagicSpellRegistry
import org.rsmod.game.MapClock
import org.rsmod.game.entity.player.PlayerUid
import org.rsmod.game.type.interf.IfEvent
import org.rsmod.game.type.obj.ObjType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.obj.isType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.game.type.spot.SpotanimTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Standard spellbook teleports and alchemy. Destinations and cast experience are already on the
 * spell objs ([params.spell_telecoord], [params.spell_castxp]). Combat spells stay with upstream.
 *
 * Home Teleport, Varrock, Lumbridge, Falador, Camelot, Kourend Castle, Ardougne, Civitas illa
 * Fortis, Watchtower, Trollheim and Ape Atoll. Low and High Alchemy convert one inventory item.
 */
class StandardSpellbookScript
@Inject
constructor(
    private val spells: MagicSpellRegistry,
    private val runes: MagicRuneManager,
    private val objTypes: ObjTypeList,
    private val seqTypes: SeqTypeList,
    private val spotTypes: SpotanimTypeList,
    private val protectedAccess: ProtectedAccessLauncher,
    private val clock: MapClock,
) : PluginScript() {
    private val logger = InlineLogger()
    private val homeReadyAt = HashMap<PlayerUid, Int>()

    override fun ScriptContext.startup() {
        val teleports = TELEPORT_OBJS.mapNotNull { spells.getObjSpell(it) }
        val alchemy = ALCHEMY_OBJS.mapNotNull { spells.getObjSpell(it) }
        val bound = teleports + alchemy
        logger.info {
            "[beanlab] standard spells: teleports=${teleports.size} alchemy=${alchemy.size}"
        }

        onIfOpen(interfaces.magic_spellbook) {
            for (spell in bound) {
                player.ifSetEvents(
                    spell.component,
                    0..0,
                    IfEvent.Op1,
                    IfEvent.TgtCom,
                    IfEvent.TgtNpc,
                    IfEvent.TgtPlayer,
                    IfEvent.TgtLoc,
                    IfEvent.TgtObj,
                )
            }
        }

        for (spell in teleports) {
            onIfOverlayButton(spell.component) {
                protectedAccess.launch(player) { castTeleport(spell) }
            }
        }
        for (spell in alchemy) {
            onIfOverlayButtonT(spell.component, components.inv_items) {
                val item = targetObj ?: return@onIfOverlayButtonT
                protectedAccess.launch(player) { castAlchemy(spell, item, targetSlot) }
            }
        }
    }

    private suspend fun ProtectedAccess.castTeleport(spell: MagicSpell) {
        if (!canCast(spell)) return
        val dest = objTypes[spell.obj].paramOrNull(params.spell_telecoord) ?: LUMBRIDGE_HOME
        if (spell.obj.isType(objs.spell_hometeleport_lumbridge)) {
            val ready = homeReadyAt[player.uid] ?: 0
            if (clock.cycle < ready) {
                val minutes = ((ready - clock.cycle) / TICKS_PER_MINUTE).coerceAtLeast(1)
                mes("You need to wait $minutes more minutes to cast this spell.")
                return
            }
        }
        if (spell.objReqs.isNotEmpty()) {
            val spent = runes.attemptCast(player, spell)
            if (!spent.isSuccess()) return
        }
        val seq = if (spell.obj.isType(objs.spell_hometeleport_lumbridge)) HOME_ANIM else TELE_ANIM
        seqTypes[seq]?.let { anim(it) }
        spotTypes[TELE_GFX]?.let { spotanim(it) }
        delay(TELE_DELAY)
        teleport(dest)
        if (spell.castXp > 0.0) statAdvance(stats.magic, spell.castXp)
        if (spell.obj.isType(objs.spell_hometeleport_lumbridge)) {
            homeReadyAt[player.uid] = clock.cycle + HOME_COOLDOWN
        }
    }

    private fun ProtectedAccess.castAlchemy(spell: MagicSpell, item: UnpackedObjType, slot: Int) {
        if (item.isType(objs.coins) || item.paramOrNull(params.no_alchemy) == 1 || item.cost <= 0) {
            mes("You can't cast ${spell.name} on that.")
            return
        }
        if (!canCast(spell)) return
        val spent = runes.attemptCast(player, spell)
        if (!spent.isSuccess()) return
        val removed = invDel(inv, item, count = 1, slot = slot)
        if (!removed.success) return
        val high = spell.obj.isType(objs.spell_high_alchemy)
        val coins = if (high) item.cost * 60 / 100 else item.cost * 40 / 100
        if (coins > 0) invAdd(inv, objs.coins, coins)
        val seq = if (high) HIGH_ALCH_ANIM else LOW_ALCH_ANIM
        val gfx = if (high) HIGH_ALCH_GFX else LOW_ALCH_GFX
        seqTypes[seq]?.let { anim(it) }
        spotTypes[gfx]?.let { spotanim(it) }
        statAdvance(stats.magic, spell.castXp)
        mes("You receive $coins coins.")
    }

    private fun ProtectedAccess.canCast(spell: MagicSpell): Boolean {
        val book = spell.spellbook
        if (book != null && player.vars[varbits.spellbook] != book.varValue) return false
        if (player.magicLvl < spell.levelReq) {
            mes("Your Magic level is not high enough for this spell.")
            return false
        }
        return true
    }

    private companion object {
        val TELEPORT_OBJS: List<ObjType> =
            listOf(
                objs.spell_hometeleport_lumbridge,
                objs.spell_varrock_teleport,
                objs.spell_lumbridge_teleport,
                objs.spell_falador_teleport,
                objs.spell_camelot_teleport,
                objs.spell_kourendcastle_teleport,
                objs.spell_ardougne_teleport,
                objs.spell_civitas_fortis_teleport,
                objs.spell_watchtower_teleport,
                objs.spell_trollheim_teleport,
                objs.spell_apeatoll_teleport,
            )
        val ALCHEMY_OBJS: List<ObjType> = listOf(objs.spell_low_alchemy, objs.spell_high_alchemy)

        val LUMBRIDGE_HOME = CoordGrid(0, 50, 50, 21, 18)
        const val HOME_COOLDOWN = 3_000
        const val TICKS_PER_MINUTE = 100
        const val TELE_DELAY = 3
        const val TELE_ANIM = 714
        const val HOME_ANIM = 4847
        const val TELE_GFX = 111
        const val LOW_ALCH_ANIM = 712
        const val HIGH_ALCH_ANIM = 713
        const val LOW_ALCH_GFX = 112
        const val HIGH_ALCH_GFX = 113
    }
}
