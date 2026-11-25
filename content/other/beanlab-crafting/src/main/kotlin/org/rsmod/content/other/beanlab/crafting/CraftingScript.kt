package org.rsmod.content.other.beanlab.crafting

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.HeldUEvents
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.craftingLvl
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.game.type.seq.UnpackedSeqType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Crafting: gem cutting, leather and dragonhide, studded armour, battlestaves, glassblowing, soft
 * clay, pottery, and the spinning wheel.
 *
 * Items resolve by internal name, then by display name. A recipe whose item is missing is skipped
 * and counted instead of failing boot. Jewellery, stringing and cape dyeing live in
 * [JewelleryScript].
 */
class CraftingScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val seqTypes: SeqTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
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

        var gems = 0
        var combos = 0
        var clay = 0
        var wheels = 0
        var skipped = 0
        for (gem in Crafting.gems) {
            if (!present("chisel", gem.uncut, gem.cut)) {
                skipped++
            } else if (bindUse(bus, "chisel", gem.uncut) { cutGem(gem) }) {
                gems++
            }
        }
        for (combo in Crafting.combos) {
            val names =
                listOf(combo.first, combo.second) +
                    combo.options.flatMap { r -> r.inputs.map { it.first } + r.product }
            if (!present(*names.toTypedArray())) {
                skipped++
            } else if (bindUse(bus, combo.first, combo.second) { offer(combo) }) {
                combos++
            }
        }
        for ((full, empty) in Crafting.waterSources) {
            if (!present("clay", "softclay", full, empty)) {
                skipped++
            } else if (bindUse(bus, "clay", full) { softClay(full, empty) }) {
                clay++
            }
        }
        wheels +=
            bindLocs(bus, "spinning wheel", Crafting.spinning.map { it.first.first }) { item ->
                spin(item)
            }
        wheels += bindLocs(bus, "potter's wheel", listOf("softclay")) { _ -> pottery() }
        wheels +=
            bindLocs(bus, "pottery oven", Crafting.firing.map { it.first }) { item -> fire(item) }
        logger.info {
            "[beanlab] crafting registered: $gems gems, $combos combos, $clay clay, " +
                "$wheels loc bindings, $skipped skipped missing objs"
        }
    }

    private fun obj(name: String): UnpackedObjType? =
        byInternal[name.lowercase()] ?: byDisplay[name.lowercase()]

    private fun present(vararg names: String): Boolean = names.all { obj(it) != null }

    private fun seq(name: String): UnpackedSeqType? =
        seqTypes.values.firstOrNull { it.internalName.equals(name, ignoreCase = true) }

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
            if (addIfFree(bus, HeldUEvents.Type::class.java, key) { action() }) added = true
        }
        return added
    }

    /** Binds item-on-loc for every loc whose name matches. Returns how many bindings were made. */
    private fun bindLocs(
        bus: EventBus,
        locName: String,
        items: List<String>,
        action: suspend ProtectedAccess.(String) -> Unit,
    ): Int {
        var count = 0
        val locs = locTypes.values.filter { it.name.equals(locName, ignoreCase = true) }
        for (loc in locs) {
            for (item in items) {
                val type = obj(item) ?: continue
                val key = EventBus.composeLongKey(loc.id, type.id)
                if (addIfFree(bus, LocUEvents.Op::class.java, key) { action(item) }) count++
            }
        }
        return count
    }

    private fun xp(player: Player, amount: Double): Double =
        amount * xpMods.get(player, stats.crafting)

    private suspend fun ProtectedAccess.cutGem(gem: Gem) {
        val uncut = obj(gem.uncut) ?: return
        val cut = obj(gem.cut) ?: return
        if (player.craftingLvl < gem.level) {
            mes("You need a Crafting level of ${gem.level} to cut this ${gem.label}.")
            return
        }
        seq(gem.anim)?.let { anim(it) }
        delay(2)
        if (!invDel(inv, uncut, count = 1).success) return
        if (gem.rateHigh > 0 && !statRandom(stats.crafting, gem.rateLow, gem.rateHigh, 0)) {
            statAdvance(stats.crafting, xp(player, (gem.xp * 10 + 2) / 4 / 10))
            mes("You mis-hit the chisel and smash the ${gem.label} to pieces!")
            obj("crushed_gemstone")?.let { invAdd(inv, it, 1) }
            return
        }
        invAdd(inv, cut, 1)
        statAdvance(stats.crafting, xp(player, gem.xp))
        mes("You cut the ${gem.label}.")
    }

    private suspend fun ProtectedAccess.offer(combo: Combo) {
        val recipe =
            if (combo.options.size == 1) {
                combo.options[0]
            } else {
                val labels =
                    combo.options.map { it.label.replaceFirstChar { c -> c.uppercase() } } +
                        "Cancel"
                val picked = menu("What would you like to make?", false, labels)
                ifClose()
                combo.options.getOrNull(picked) ?: return
            }
        make(recipe)
    }

    private suspend fun ProtectedAccess.make(recipe: Recipe) {
        if (player.craftingLvl < recipe.level) {
            mes("You need a Crafting level of ${recipe.level} to make ${recipe.label}.")
            return
        }
        val thread = obj("thread")
        if (recipe.thread && (thread == null || inv.count(thread) < 1)) {
            mes("You need some thread to make anything out of leather.")
            return
        }
        for ((name, count) in recipe.inputs) {
            val type = obj(name) ?: return
            if (inv.count(type) < count) {
                mes("You don't have enough ${type.name.lowercase()} to make ${recipe.label}.")
                return
            }
        }
        val product = obj(recipe.product) ?: return
        delay(2)
        for ((name, count) in recipe.inputs) {
            val type = obj(name) ?: return
            if (!invDel(inv, type, count = count).success) return
        }
        invAdd(inv, product, 1)
        statAdvance(stats.crafting, xp(player, recipe.xp))
        mes("You make ${recipe.label}.")
        if (recipe.thread && thread != null && random.of(5) == 0) {
            invDel(inv, thread, count = 1)
            mes("You use up one of your reels of thread.")
        }
    }

    private suspend fun ProtectedAccess.softClay(full: String, empty: String) {
        val clay = obj("clay") ?: return
        val soft = obj("softclay") ?: return
        val water = obj(full) ?: return
        val container = obj(empty) ?: return
        if (inv.count(clay) < 1 || inv.count(water) < 1) return
        mes("You mix the clay and water.")
        delay(1)
        if (!invDel(inv, clay, count = 1).success) return
        if (!invDel(inv, water, count = 1).success) {
            invAdd(inv, clay, 1)
            return
        }
        invAdd(inv, container, 1)
        invAdd(inv, soft, 1)
        mes("You now have some soft workable clay.")
    }

    private suspend fun ProtectedAccess.pottery() {
        val labels =
            Crafting.pottery.map { it.label.replaceFirstChar { c -> c.uppercase() } } + "Cancel"
        val picked = menu("What would you like to make?", false, labels)
        ifClose()
        val recipe = Crafting.pottery.getOrNull(picked) ?: return
        if (obj(recipe.product) == null) return
        seq("human_potterywheel")?.let { anim(it) }
        make(recipe)
    }

    private suspend fun ProtectedAccess.fire(item: String) {
        val entry = Crafting.firing.firstOrNull { it.first == item } ?: return
        val unfired = obj(entry.first) ?: return
        val product = obj(entry.second) ?: return
        if (inv.count(unfired) < 1) return
        mes("You put the ${unfired.name.lowercase()} in the oven.")
        seq("human_furnace")?.let { anim(it) }
        delay(2)
        mes("The ${product.name.lowercase()} hardens in the oven.")
        delay(2)
        if (!invDel(inv, unfired, count = 1).success) return
        if (statRandom(stats.crafting, 180, 800, 0)) {
            invAdd(inv, product, 1)
            statAdvance(stats.crafting, xp(player, entry.third))
            mes("You remove the ${product.name.lowercase()} from the oven.")
        } else {
            mes("The ${product.name.lowercase()} cracks in the oven.")
        }
    }

    private suspend fun ProtectedAccess.spin(item: String) {
        val entry = Crafting.spinning.firstOrNull { it.first.first == item } ?: return
        val ingredient = obj(entry.first.first) ?: return
        val product = obj(entry.first.second) ?: return
        val level = entry.first.third
        if (player.craftingLvl < level) {
            mes("You need level $level Crafting to make that.")
            return
        }
        val held = inv.count(ingredient)
        if (held < 1) return
        var done = 0
        while (done < held) {
            seq("human_spinningwheel")?.let { anim(it) }
            delay(2)
            if (!invDel(inv, ingredient, count = 1).success) return
            invAdd(inv, product, 1)
            statAdvance(stats.crafting, xp(player, entry.second))
            mes("You spin the ${ingredient.name.lowercase()} into ${product.name.lowercase()}.")
            done++
        }
    }
}
