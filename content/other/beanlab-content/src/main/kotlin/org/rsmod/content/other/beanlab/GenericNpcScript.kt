package org.rsmod.content.other.beanlab

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.currencies
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.config.refs.invs
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.events.interact.NpcUnimplementedEvents
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onProtectedEvent
import org.rsmod.api.shops.Shops
import org.rsmod.game.entity.Npc
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.type.inv.InvTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Fallback for NPC options that have no content script.
 *
 * RsMod only scripts the NPCs it maps by hand, almost all of them in Lumbridge. Everywhere else a
 * click reaches the engine default, which answers "Nothing interesting happens." Combat is the
 * exception: it is already bound to option 2. This registers the unimplemented slot, which the
 * dispatcher only uses when no type or content script exists, so those hand-scripted NPCs stay as
 * they are.
 *
 * Attack is left on option 2 so the combat script still owns it. If Attack is a different option
 * and option 2 is empty, option 2 is filled in and the click is handed to that combat path.
 */
class GenericNpcScript
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val npcInteractions: NpcInteractions,
    private val shops: Shops,
    invTypes: InvTypeList,
    objTypes: ObjTypeList,
) : PluginScript() {
    private val logger = InlineLogger()
    private val shopCatalog = ShopCatalog(invTypes, objTypes)

    override fun ScriptContext.startup() {
        var talk = 0
        var bank = 0
        var collect = 0
        var trade = 0
        var shopSpecific = 0
        var attack = 0
        for ((id, type) in npcTypes) {
            val attackElsewhere = attackOutsideOp2(type)
            for (slot in 0..4) {
                when (option(type, slot)) {
                    "talk-to",
                    "talk" -> {
                        bind(id, slot) { talkTo(it) }
                        talk++
                    }
                    "bank" -> {
                        bind(id, slot) { openBank() }
                        bank++
                    }
                    "collect" -> {
                        bind(id, slot) { openCollect() }
                        collect++
                    }
                    "trade",
                    "shop" -> {
                        bind(id, slot) { openTrade(it) }
                        trade++
                        if (shopCatalog.inventoryFor(type) != null) shopSpecific++
                    }
                    "attack" -> {
                        if (slot == ATTACK_SLOT || !attackElsewhere) continue
                        bind(id, slot) { attack(it) }
                        attack++
                    }
                    else -> Unit
                }
            }
        }
        logger.info {
            "[beanlab] generic npc options: talk=$talk bank=$bank collect=$collect " +
                "trade=$trade attack=$attack"
        }
        logger.info {
            "[beanlab] shops: recipes=${shopCatalog.recipeCount} specific=$shopSpecific " +
                "general=${trade - shopSpecific}"
        }
    }

    /**
     * Combat listens on option 2. When Attack lives on another option and option 2 is unused, give
     * option 2 the same label so that listener accepts the npc, then forward the original click.
     */
    private fun attackOutsideOp2(type: UnpackedNpcType): Boolean {
        val attackSlot = (0..4).firstOrNull { option(type, it) == "attack" } ?: return false
        if (attackSlot == ATTACK_SLOT) return false
        if (type.op.size <= ATTACK_SLOT || type.hasOp(InteractionOp.Op2)) return false
        type.op[ATTACK_SLOT] = "Attack"
        return true
    }

    private fun option(type: UnpackedNpcType, slot: Int): String? {
        val text = type.op.getOrNull(slot)?.trim()?.lowercase() ?: return null
        if (text.isEmpty() || text == "hidden") return null
        return text
    }

    private fun ScriptContext.bind(
        id: Int,
        slot: Int,
        action: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        // Some npc types share an option list, and a later edit can make the same option visible
        // twice while this loop is still walking the list. The bus rejects a second handler.
        when (slot) {
            0 -> {
                if (eventBus.contains(NpcUnimplementedEvents.Op1::class.java, id)) return
                onProtectedEvent<NpcUnimplementedEvents.Op1>(id) { action(it.npc) }
            }
            1 -> {
                if (eventBus.contains(NpcUnimplementedEvents.Op2::class.java, id)) return
                onProtectedEvent<NpcUnimplementedEvents.Op2>(id) { action(it.npc) }
            }
            2 -> {
                if (eventBus.contains(NpcUnimplementedEvents.Op3::class.java, id)) return
                onProtectedEvent<NpcUnimplementedEvents.Op3>(id) { action(it.npc) }
            }
            3 -> {
                if (eventBus.contains(NpcUnimplementedEvents.Op4::class.java, id)) return
                onProtectedEvent<NpcUnimplementedEvents.Op4>(id) { action(it.npc) }
            }
            4 -> {
                if (eventBus.contains(NpcUnimplementedEvents.Op5::class.java, id)) return
                onProtectedEvent<NpcUnimplementedEvents.Op5>(id) { action(it.npc) }
            }
            else -> error("NPC option slot out of range: $slot")
        }
    }

    private suspend fun ProtectedAccess.talkTo(npc: Npc) {
        startDialogue(npc) { hello() }
    }

    private suspend fun Dialogue.hello() {
        chatNpc(happy, "Hello.")
    }

    private fun ProtectedAccess.openBank() {
        ifOpenMainSidePair(main = interfaces.bank_main, side = interfaces.bank_side)
    }

    private fun ProtectedAccess.openCollect() {
        ifOpenMainModal(interfaces.ge_collection_box)
    }

    private fun ProtectedAccess.openTrade(npc: Npc) {
        val stock = shopCatalog.inventoryFor(npc.type)
        val general = stock == null || shopCatalog.isGeneral(stock)
        if (stock != null) {
            shops.open(
                player = player,
                title = npc.type.name,
                shopInv = stock,
                sideInv = player.inv,
                currency = currencies.standard_gp,
                buyPercentage = if (general) GENERAL_BUY else SPECIAL_BUY,
                sellPercentage = if (general) GENERAL_SELL else SPECIAL_SELL,
                changePercentage = if (general) GENERAL_CHANGE else SPECIAL_CHANGE,
                subtext = Shops.DEFAULT_SUBTEXT,
            )
            return
        }
        shops.open(
            player = player,
            title = npc.type.name,
            shopInv = invs.generalshop1,
            buyPercentage = if (general) GENERAL_BUY else SPECIAL_BUY,
            sellPercentage = if (general) GENERAL_SELL else SPECIAL_SELL,
            changePercentage = if (general) GENERAL_CHANGE else SPECIAL_CHANGE,
        )
    }

    private fun ProtectedAccess.attack(npc: Npc) {
        npcInteractions.interact(player, npc, InteractionOp.Op2)
    }

    private companion object {
        const val ATTACK_SLOT = 1
        const val GENERAL_BUY = 60.0
        const val GENERAL_SELL = 100.0
        const val GENERAL_CHANGE = 2.0
        const val SPECIAL_BUY = 40.0
        const val SPECIAL_SELL = 130.0
        const val SPECIAL_CHANGE = 3.0
    }
}
