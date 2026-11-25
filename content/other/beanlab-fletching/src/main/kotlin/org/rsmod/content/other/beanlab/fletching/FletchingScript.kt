package org.rsmod.content.other.beanlab.fletching

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.HeldUEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.fletchingLvl
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Fletching: knife on logs, stringing bows, headless arrows, arrowheads, and dart tips.
 *
 * Items resolve by internal name, then by display name. A recipe whose item is missing is skipped
 * and counted instead of failing boot. Experience values are the source tenths divided by ten.
 */
class FletchingScript
@Inject
constructor(private val objTypes: ObjTypeList, private val xpMods: XpModifiers) : PluginScript() {
    private val logger = InlineLogger()
    private var byInternal: Map<String, UnpackedObjType> = emptyMap()
    private var byDisplay: Map<String, UnpackedObjType> = emptyMap()

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

        var cuts = 0
        var strings = 0
        var batches = 0
        var skipped = 0
        for (cut in Fletching.logs) {
            if (!present("knife", cut.log, cut.shortUnstrung, cut.longUnstrung)) {
                skipped++
            } else if (bindUse(bus, "knife", cut.log) { cutLog(cut) }) {
                cuts++
            }
        }
        for (recipe in Fletching.stringing) {
            if (!present("bow_string", recipe.unstrung, recipe.bow)) {
                skipped++
            } else if (bindUse(bus, recipe.unstrung, "bow_string") { stringBow(recipe) }) {
                strings++
            }
        }
        if (present("feather", "arrow_shaft", "headless_arrow")) {
            if (bindUse(bus, "feather", "arrow_shaft") { headless() }) batches++
        } else {
            skipped++
        }
        for (batch in Fletching.arrows + Fletching.darts) {
            if (!present(batch.tips, batch.partner, batch.product)) {
                skipped++
            } else if (bindUse(bus, batch.tips, batch.partner) { make(batch) }) {
                batches++
            }
        }
        logger.info {
            "[beanlab] fletching registered: $cuts log cuts, $strings strings, " +
                "$batches batches, $skipped skipped missing objs"
        }
    }

    private fun obj(name: String): UnpackedObjType? =
        byInternal[name.lowercase()] ?: byDisplay[name.lowercase()]

    private fun present(vararg names: String): Boolean = names.all { obj(it) != null }

    /** Subscribes both item orders. Returns true when at least one was free. */
    private fun bindUse(
        bus: EventBus,
        first: String,
        second: String,
        action: suspend ProtectedAccess.() -> Unit,
    ): Boolean {
        val a = obj(first) ?: return false
        val b = obj(second) ?: return false
        val keys = setOf(EventBus.composeLongKey(a.id, b.id), EventBus.composeLongKey(b.id, a.id))
        var added = false
        for (key in keys) {
            if (!bus.contains(HeldUEvents.Type::class.java, key)) {
                bus.subscribeSuspend(HeldUEvents.Type::class.java, key) { action() }
                added = true
            }
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

    private suspend fun ProtectedAccess.cutLog(cut: LogCut) {
        val log = obj(cut.log) ?: return
        val options = ArrayList<String>()
        if (cut.shafts > 0) options += "Arrow shafts"
        options += "Short bow"
        options += "Long bow"
        options += "Cancel"
        val picked = options.getOrNull(menu("What would you like to make?", false, options))
        ifClose()
        when (picked) {
            "Arrow shafts" -> {
                val shaft = obj("arrow_shaft") ?: return
                if (!invDel(inv, log, count = 1).success) return
                invAdd(inv, shaft, cut.shafts)
                statAdvance(stats.fletching, cut.shafts * 0.5 * xpMods.get(player, stats.fletching))
                mes("You carefully cut the wood into ${cut.shafts} arrow shafts.")
            }
            "Short bow" -> cutBow(log, cut.shortUnstrung, cut.shortLevel, cut.shortXp, "shortbow")
            "Long bow" -> cutBow(log, cut.longUnstrung, cut.longLevel, cut.longXp, "longbow")
            else -> return
        }
    }

    private suspend fun ProtectedAccess.cutBow(
        log: UnpackedObjType,
        product: String,
        level: Int,
        xp: Double,
        word: String,
    ) {
        val bow = obj(product) ?: return
        if (player.fletchingLvl < level) {
            mes("You need a Fletching level of at least $level to make this.")
            return
        }
        if (!invDel(inv, log, count = 1).success) return
        invAdd(inv, bow, 1)
        statAdvance(stats.fletching, xp * xpMods.get(player, stats.fletching))
        mes("You carefully cut the wood into a $word.")
    }

    private suspend fun ProtectedAccess.stringBow(recipe: Stringing) {
        val unstrung = obj(recipe.unstrung) ?: return
        val string = obj("bow_string") ?: return
        val bow = obj(recipe.bow) ?: return
        if (player.fletchingLvl < recipe.level) {
            mes("You need level ${recipe.level} Fletching to string that bow.")
            return
        }
        val held = minOf(inv.count(unstrung), inv.count(string))
        if (held < 1) return
        val amount = if (held > 1) askAmount() ?: return else 1
        var done = 0
        while (done < amount) {
            if (inv.count(unstrung) < 1 || inv.count(string) < 1) return
            delay(2)
            if (!invDel(inv, unstrung, count = 1).success) return
            if (!invDel(inv, string, count = 1).success) {
                invAdd(inv, unstrung, 1)
                return
            }
            invAdd(inv, bow, 1)
            statAdvance(stats.fletching, recipe.xp * xpMods.get(player, stats.fletching))
            mes("You add a string to the bow.")
            done++
        }
    }

    private suspend fun ProtectedAccess.headless() {
        val feather = obj("feather") ?: return
        val shaft = obj("arrow_shaft") ?: return
        val product = obj("headless_arrow") ?: return
        val count = minOf(inv.count(feather), inv.count(shaft), BATCH_ARROWS)
        if (count < 1) return
        invDel(inv, feather, count = count)
        invDel(inv, shaft, count = count)
        invAdd(inv, product, count)
        statAdvance(stats.fletching, count * 1.0 * xpMods.get(player, stats.fletching))
        if (count > 1) {
            mes("You attach feathers to $count arrow shafts.")
        } else {
            mes("You attach a feather to an arrow shaft.")
        }
    }

    private suspend fun ProtectedAccess.make(batch: Batch) {
        val tips = obj(batch.tips) ?: return
        val partner = obj(batch.partner) ?: return
        val product = obj(batch.product) ?: return
        if (player.fletchingLvl < batch.level) {
            mes("You need a Fletching level of ${batch.level} to make ${batch.noun}.")
            return
        }
        val count = minOf(inv.count(tips), inv.count(partner), batch.max)
        if (count < 1) return
        invDel(inv, tips, count = count)
        invDel(inv, partner, count = count)
        invAdd(inv, product, count)
        statAdvance(stats.fletching, count * batch.xp * xpMods.get(player, stats.fletching))
        mes("You finish making $count ${batch.noun}.")
    }

    private companion object {
        const val BATCH_ARROWS = 15
    }
}
