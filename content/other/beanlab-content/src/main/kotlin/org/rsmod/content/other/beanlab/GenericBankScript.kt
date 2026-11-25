package org.rsmod.content.other.beanlab

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.interfaces
import org.rsmod.api.player.events.interact.LocUnimplementedEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onProtectedEvent
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Opens the bank from booths and chests that have no content script.
 *
 * Upstream only tags two booth loc types (`aide_bankbooth` and `aide_bankbooth_multi`). Every other
 * booth still answers "Nothing interesting happens." This binds the unimplemented slot, which runs
 * only when no type or content script already handles that loc, so those two booths stay on the
 * upstream script.
 */
class GenericBankScript @Inject constructor(private val locTypes: LocTypeList) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        var count = 0
        for ((id, type) in locTypes) {
            val slot = bankSlot(type) ?: continue
            if (bind(id, slot)) count++
        }
        logger.info { "[beanlab] bank locs registered: $count" }
    }

    private fun bankSlot(type: UnpackedLocType): Int? {
        for (slot in 0..4) {
            val option = type.op.getOrNull(slot)?.trim()?.lowercase() ?: continue
            if (opensBank(type.name, option)) return slot
        }
        return null
    }

    private fun opensBank(name: String, option: String): Boolean {
        if (option == "bank") return true
        if (option != "use" && option != "open") return false
        val lower = name.lowercase()
        return "bank booth" in lower || "bank chest" in lower
    }

    private fun ScriptContext.bind(id: Int, slot: Int): Boolean {
        val key = id.toLong()
        when (slot) {
            0 -> {
                if (eventBus.contains(LocUnimplementedEvents.Op1::class.java, key)) return false
                onProtectedEvent<LocUnimplementedEvents.Op1>(id) { openBank() }
            }
            1 -> {
                if (eventBus.contains(LocUnimplementedEvents.Op2::class.java, key)) return false
                onProtectedEvent<LocUnimplementedEvents.Op2>(id) { openBank() }
            }
            2 -> {
                if (eventBus.contains(LocUnimplementedEvents.Op3::class.java, key)) return false
                onProtectedEvent<LocUnimplementedEvents.Op3>(id) { openBank() }
            }
            3 -> {
                if (eventBus.contains(LocUnimplementedEvents.Op4::class.java, key)) return false
                onProtectedEvent<LocUnimplementedEvents.Op4>(id) { openBank() }
            }
            else -> {
                if (eventBus.contains(LocUnimplementedEvents.Op5::class.java, key)) return false
                onProtectedEvent<LocUnimplementedEvents.Op5>(id) { openBank() }
            }
        }
        return true
    }

    private fun ProtectedAccess.openBank() {
        ifOpenMainSidePair(main = interfaces.bank_main, side = interfaces.bank_side)
    }
}
