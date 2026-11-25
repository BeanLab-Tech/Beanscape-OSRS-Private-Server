package org.rsmod.content.other.beanlab.herblore

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.events.interact.HeldUEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.herbloreLvl
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.game.type.seq.UnpackedSeqType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Cleaning grimy herbs, unfinished potions, and finished potions.
 *
 * Items resolve by internal name, then by display name. Certs and placeholders are skipped, and the
 * lowest id wins. A recipe whose item is missing is skipped instead of failing boot. Drinking stays
 * with ConsumablesScript.
 */
class HerbloreScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val seqTypes: SeqTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private var byInternal: Map<String, UnpackedObjType> = emptyMap()
    private var byDisplay: Map<String, UnpackedObjType> = emptyMap()
    private var mixAnim: UnpackedSeqType? = null

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
    }

    private fun register(bus: EventBus) {
        val usable = objTypes.values.filter { !it.isCert && !it.isPlaceholder }.sortedBy { it.id }
        byInternal =
            usable
                .mapNotNull { type -> type.internalName?.lowercase()?.let { it to type } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.first() }
        byDisplay =
            usable
                .filter { it.name.isNotBlank() && it.name != "null" }
                .groupBy { it.name.lowercase() }
                .mapValues { it.value.first() }
        mixAnim = findMixAnim()

        var cleans = 0
        var unfinished = 0
        var finished = 0
        var skipped = 0
        for (herb in Herblore.cleans) {
            if (!present(herb.grimy, herb.clean)) {
                skipped++
            } else if (addClean(bus, herb)) {
                cleans++
            }
        }
        for (mix in Herblore.unfinished) {
            if (!present(mix.ingredient, mix.base, mix.product)) {
                skipped++
            } else if (addMix(bus, mix)) {
                unfinished++
            }
        }
        for (mix in Herblore.finished) {
            if (!present(mix.ingredient, mix.base, mix.product)) {
                skipped++
            } else if (addMix(bus, mix)) {
                finished++
            }
        }
        logger.info {
            "[beanlab] herblore registered: $cleans cleans, $unfinished unfinished, " +
                "$finished finished, $skipped skipped missing objs"
        }
    }

    private fun obj(name: String): UnpackedObjType? =
        byInternal[name.lowercase()] ?: byDisplay[name.lowercase()]

    private fun present(vararg names: String): Boolean = names.all { obj(it) != null }

    private fun findMixAnim(): UnpackedSeqType? {
        val named =
            seqTypes.values.firstOrNull { seq ->
                MIX_SEQ.equals(seq.internalName, ignoreCase = true) ||
                    MIX_SEQ.equals(seq.debugName, ignoreCase = true)
            }
        return named ?: seqTypes[MIX_SEQ_ID]
    }

    private fun <T : SuspendEvent<ProtectedAccess>> addIfFree(
        bus: EventBus,
        cls: Class<T>,
        key: Long,
        handler: suspend ProtectedAccess.(T) -> Unit,
    ): Boolean {
        if (bus.contains(cls, key)) return false
        bus.subscribeSuspend(cls, key, handler)
        return true
    }

    private fun addHeldOp(
        bus: EventBus,
        opIndex: Int,
        objId: Int,
        action: suspend ProtectedAccess.() -> Unit,
    ): Boolean {
        val key = objId.toLong()
        return when (opIndex) {
            0 -> addIfFree(bus, HeldObjEvents.Op1::class.java, key) { action() }
            1 -> addIfFree(bus, HeldObjEvents.Op2::class.java, key) { action() }
            2 -> addIfFree(bus, HeldObjEvents.Op3::class.java, key) { action() }
            3 -> addIfFree(bus, HeldObjEvents.Op4::class.java, key) { action() }
            else -> addIfFree(bus, HeldObjEvents.Op5::class.java, key) { action() }
        }
    }

    private fun addClean(bus: EventBus, herb: HerbClean): Boolean {
        val grimy = obj(herb.grimy) ?: return false
        val op = grimy.iop.indexOfFirst { it.equals("Clean", ignoreCase = true) }
        if (op !in 0..4) return false
        return addHeldOp(bus, op, grimy.id) { clean(herb) }
    }

    private fun addMix(bus: EventBus, mix: PotionMix): Boolean {
        val ingredient = obj(mix.ingredient) ?: return false
        val base = obj(mix.base) ?: return false
        val forward = EventBus.composeLongKey(ingredient.id, base.id)
        val reverse = EventBus.composeLongKey(base.id, ingredient.id)
        var added = false
        if (!bus.contains(HeldUEvents.Type::class.java, forward)) {
            bus.subscribeSuspend(HeldUEvents.Type::class.java, forward) { mix(mix) }
            added = true
        }
        if (reverse != forward && !bus.contains(HeldUEvents.Type::class.java, reverse)) {
            bus.subscribeSuspend(HeldUEvents.Type::class.java, reverse) { mix(mix) }
            added = true
        }
        return added
    }

    private suspend fun ProtectedAccess.askAmount(): Int? {
        val choice = menu("How many?", hotkeys = false, listOf("1", "5", "10", "All", "Cancel"))
        ifClose()
        return when (choice) {
            0 -> 1
            1 -> 5
            2 -> 10
            3 -> Int.MAX_VALUE
            else -> null
        }
    }

    private suspend fun ProtectedAccess.clean(herb: HerbClean) {
        if (player.herbloreLvl < herb.level) {
            mes("You need a Herblore level of ${herb.level} to clean this herb.")
            return
        }
        val grimy = obj(herb.grimy) ?: return
        val clean = obj(herb.clean) ?: return
        val held = inv.count(grimy)
        if (held < 1) return
        val amount = if (held > 1) askAmount() ?: return else 1
        var done = 0
        while (done < amount) {
            if (inv.count(grimy) < 1) return
            delay(TICKS)
            if (!invDel(inv, grimy, count = 1).success) return
            invAdd(inv, clean, 1)
            statAdvance(stats.herblore, herb.xp * xpMods.get(player, stats.herblore))
            mes(herb.message)
            done++
        }
    }

    private suspend fun ProtectedAccess.mix(mix: PotionMix) {
        if (player.herbloreLvl < mix.level) {
            mes("You need a Herblore level of ${mix.level} to make this potion.")
            return
        }
        val ingredient = obj(mix.ingredient) ?: return
        val base = obj(mix.base) ?: return
        val product = obj(mix.product) ?: return
        val held = minOf(inv.count(ingredient), inv.count(base))
        if (held < 1) return
        val amount = if (held > 1) askAmount() ?: return else 1
        var done = 0
        while (done < amount) {
            if (inv.count(ingredient) < 1 || inv.count(base) < 1) return
            mixAnim?.let { anim(it) }
            delay(TICKS)
            if (inv.count(ingredient) < 1 || inv.count(base) < 1) return
            if (!invDel(inv, ingredient, count = 1).success) return
            if (!invDel(inv, base, count = 1).success) {
                invAdd(inv, ingredient, 1)
                return
            }
            invAdd(inv, product, 1)
            statAdvance(stats.herblore, mix.xp * xpMods.get(player, stats.herblore))
            mes(mix.message)
            done++
        }
    }

    private companion object {
        const val TICKS = 2
        const val MIX_SEQ = "human_herbing_vial"
        const val MIX_SEQ_ID = 363
    }
}
