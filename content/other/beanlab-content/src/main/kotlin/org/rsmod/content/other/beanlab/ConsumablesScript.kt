package org.rsmod.content.other.beanlab

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onEvent
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.game.type.stat.StatType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Eating food and drinking potions, which RsMod doesn't implement at all.
 *
 * Handlers are registered on [GameLifecycle.Startup], after every plugin has started, and only for
 * items that have no handler for that option yet. So any real food/potion content added later wins
 * automatically instead of clashing (a duplicate registration would stop the server).
 * - Food: any item with an "Eat" option heals by [FoodTable] (unknown foods heal 3).
 * - Potions: any item with a "Drink" option and a dose suffix like "(3)". Effects come from
 *   [PotionTable] by base name; the next dose is looked up by name, and the last dose leaves a
 *   vial.
 * - 3-tick delay between eats and between drinks, like OSRS.
 */
class ConsumablesScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val seqTypes: SeqTypeList,
    private val gameClock: MapClock,
) : PluginScript() {
    private val logger = InlineLogger()
    private val lastEat = HashMap<String, Int>()
    private val lastDrink = HashMap<String, Int>()

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
    }

    private fun register(bus: EventBus) {
        val byName = HashMap<String, Int>()
        for ((id, type) in objTypes) {
            if (type.isCert || type.isPlaceholder || type.name == "null") continue
            byName.putIfAbsent(type.name.lowercase(), id)
        }
        val vial = byName["vial"]
        var foods = 0
        var potions = 0
        var skipped = 0
        for ((id, type) in objTypes) {
            if (type.isCert || type.isPlaceholder) continue
            val opIndex =
                type.iop.indexOfFirst { it.equals("Eat", true) || it.equals("Drink", true) }
            if (opIndex !in 0..4) continue
            val isEat = type.iop[opIndex].equals("Eat", true)
            val action: suspend ProtectedAccess.(UnpackedObjType, Int) -> Unit =
                if (isEat) {
                    { t, slot -> eat(t, slot) }
                } else {
                    val dose = DOSE.matchEntire(type.name.lowercase()) ?: continue
                    val base = dose.groupValues[1].trim()
                    val n = dose.groupValues[2].toInt()
                    val next = if (n > 1) byName["$base(${n - 1})"] else vial
                    val effect = PotionTable.effects[base]
                    val nextType = next?.let { objTypes[it] }
                    ({ t, slot -> drink(t, slot, base, effect, nextType) })
                }
            if (subscribe(bus, opIndex, id, action)) {
                if (isEat) foods++ else potions++
            } else {
                skipped++
            }
        }
        logger.info {
            "[beanlab] consumables registered: $foods foods, $potions potion doses " +
                "($skipped already scripted)"
        }
    }

    /**
     * Registers [action] for op [opIndex] (0-based) of obj [id] unless something already owns it.
     */
    private fun subscribe(
        bus: EventBus,
        opIndex: Int,
        id: Int,
        action: suspend ProtectedAccess.(UnpackedObjType, Int) -> Unit,
    ): Boolean {
        val key = id.toLong()
        fun <T : HeldObjEvents> add(
            cls: Class<T>,
            handler: suspend ProtectedAccess.(T) -> Unit,
        ): Boolean {
            if (bus.contains(cls, key)) return false
            bus.subscribeSuspend(cls, key, handler)
            return true
        }
        return when (opIndex) {
            0 -> add(HeldObjEvents.Op1::class.java) { action(it.type, it.slot) }
            1 -> add(HeldObjEvents.Op2::class.java) { action(it.type, it.slot) }
            2 -> add(HeldObjEvents.Op3::class.java) { action(it.type, it.slot) }
            3 -> add(HeldObjEvents.Op4::class.java) { action(it.type, it.slot) }
            else -> add(HeldObjEvents.Op5::class.java) { action(it.type, it.slot) }
        }
    }

    private fun ProtectedAccess.ready(last: HashMap<String, Int>): Boolean {
        val now = gameClock.cycle
        val prev = last[player.username]
        if (prev != null && now - prev < CONSUME_DELAY) return false
        last[player.username] = now
        return true
    }

    private fun ProtectedAccess.eat(type: UnpackedObjType, slot: Int) {
        if (!ready(lastEat)) return
        val removed = invDel(player.inv, type, count = 1, slot = slot)
        if (!removed.success) return
        seqTypes[EAT_SEQ]?.let { anim(it) }
        val heal = FoodTable.heal[type.name.lowercase()] ?: DEFAULT_HEAL
        statHeal(stats.hitpoints, heal, 0)
        mes("You eat the ${type.name.lowercase()}.")
    }

    private fun ProtectedAccess.drink(
        type: UnpackedObjType,
        slot: Int,
        base: String,
        effect: PotionEffect?,
        next: UnpackedObjType?,
    ) {
        if (!ready(lastDrink)) return
        val removed = invDel(player.inv, type, count = 1, slot = slot)
        if (!removed.success) return
        if (next != null) invAdd(player.inv, next, 1, slot = slot)
        seqTypes[EAT_SEQ]?.let { anim(it) }
        mes("You drink some of your ${base.trim()}.")
        if (effect == null) {
            mes("It has no special effect here.")
            return
        }
        effect.apply(this)
    }

    private companion object {
        const val CONSUME_DELAY = 3
        const val DEFAULT_HEAL = 3
        /** human_eat: used for eating and drinking in OSRS. */
        const val EAT_SEQ = 829
        val DOSE = Regex("""^(.*)\((\d)\)$""")
    }
}

/** What a potion dose does; applied after the dose is consumed. */
class PotionEffect(val apply: ProtectedAccess.() -> Unit)

private fun ProtectedAccess.boost(vararg s: StatType, constant: Int, percent: Int) =
    s.forEach { statBoost(it, constant, percent) }

private fun ProtectedAccess.restore(vararg s: StatType, constant: Int, percent: Int) =
    s.forEach { statHeal(it, constant, percent) }

private fun ProtectedAccess.runEnergyPercent(percent: Int) {
    player.runEnergy = (player.runEnergy + percent * 100).coerceAtMost(10_000)
}

object PotionTable {
    val effects: Map<String, PotionEffect> =
        mapOf(
            "attack potion" to PotionEffect { boost(stats.attack, constant = 3, percent = 10) },
            "strength potion" to PotionEffect { boost(stats.strength, constant = 3, percent = 10) },
            "defence potion" to PotionEffect { boost(stats.defence, constant = 3, percent = 10) },
            "super attack" to PotionEffect { boost(stats.attack, constant = 5, percent = 15) },
            "super strength" to PotionEffect { boost(stats.strength, constant = 5, percent = 15) },
            "super defence" to PotionEffect { boost(stats.defence, constant = 5, percent = 15) },
            "combat potion" to
                PotionEffect { boost(stats.attack, stats.strength, constant = 3, percent = 10) },
            "super combat potion" to
                PotionEffect {
                    boost(stats.attack, stats.strength, stats.defence, constant = 5, percent = 15)
                },
            "ranging potion" to PotionEffect { boost(stats.ranged, constant = 4, percent = 10) },
            "magic potion" to PotionEffect { boost(stats.magic, constant = 4, percent = 0) },
            "prayer potion" to PotionEffect { restore(stats.prayer, constant = 7, percent = 25) },
            "super restore" to
                PotionEffect {
                    restore(
                        stats.attack,
                        stats.strength,
                        stats.defence,
                        stats.ranged,
                        stats.magic,
                        stats.prayer,
                        constant = 8,
                        percent = 25,
                    )
                },
            "restore potion" to
                PotionEffect {
                    restore(
                        stats.attack,
                        stats.strength,
                        stats.defence,
                        stats.ranged,
                        stats.magic,
                        constant = 10,
                        percent = 30,
                    )
                },
            "saradomin brew" to
                PotionEffect {
                    boost(stats.hitpoints, constant = 2, percent = 15)
                    boost(stats.defence, constant = 2, percent = 20)
                    listOf(stats.attack, stats.strength, stats.ranged, stats.magic).forEach {
                        statDrain(it, 0, 10)
                    }
                },
            "energy potion" to PotionEffect { runEnergyPercent(10) },
            "super energy" to PotionEffect { runEnergyPercent(20) },
            "stamina potion" to PotionEffect { runEnergyPercent(20) },
        )
}

/** Hitpoints healed per food, by lowercase item name (OSRS values; multi-bite foods eat in one). */
object FoodTable {
    val heal: Map<String, Int> =
        mapOf(
            "anchovies" to 1,
            "cabbage" to 1,
            "onion" to 1,
            "potato" to 1,
            "banana" to 2,
            "baked potato" to 2,
            "cheese" to 2,
            "tomato" to 2,
            "shrimps" to 3,
            "cooked chicken" to 3,
            "cooked meat" to 3,
            "chocolate bar" to 3,
            "purple sweets" to 3,
            "sardine" to 4,
            "cake" to 4,
            "bread" to 5,
            "herring" to 5,
            "chocolate cake" to 5,
            "cooked rabbit" to 5,
            "redberry pie" to 5,
            "mackerel" to 6,
            "meat pie" to 6,
            "fish pie" to 6,
            "garden pie" to 6,
            "cooked slimy eel" to 6,
            "trout" to 7,
            "cod" to 7,
            "plain pizza" to 7,
            "apple pie" to 7,
            "botanical pie" to 7,
            "cave eel" to 7,
            "pike" to 8,
            "meat pizza" to 8,
            "mushroom pie" to 8,
            "peach" to 8,
            "roast beast meat" to 8,
            "salmon" to 9,
            "anchovy pizza" to 9,
            "tuna" to 10,
            "cooked sweetcorn" to 10,
            "rainbow fish" to 11,
            "pineapple pizza" to 11,
            "stew" to 11,
            "lava eel" to 11,
            "egg potato" to 11,
            "summer pie" to 11,
            "wild pie" to 11,
            "lobster" to 12,
            "bass" to 13,
            "swordfish" to 14,
            "chilli potato" to 14,
            "cooked jubbly" to 15,
            "potato with cheese" to 16,
            "monkfish" to 16,
            "cooked karambwan" to 18,
            "curry" to 19,
            "ugthanki kebab" to 19,
            "shark" to 20,
            "mushroom potato" to 20,
            "sea turtle" to 21,
            "manta ray" to 22,
            "tuna potato" to 22,
            "dark crab" to 22,
            "anglerfish" to 22,
        )
}
