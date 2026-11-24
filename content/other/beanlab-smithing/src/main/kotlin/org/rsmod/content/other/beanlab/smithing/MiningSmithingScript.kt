package org.rsmod.content.other.beanlab.smithing

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import kotlin.math.min
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.miningLvl
import org.rsmod.api.player.stat.smithingLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Mining, smelting and smithing, which RsMod upstream doesn't implement.
 * - Mining: every loc named like "Copper rocks" with a "Mine" option ([Ores]). Pickaxe from the
 *   inventory or right hand ([Pickaxes]); a success roll every few ticks; one ore depletes the rock
 *   (standard rocks swap to their empty variant, region variants vanish) until it respawns.
 * - Smelting: "Smelt" on any furnace, or use an ore on it. Pick a bar and an amount ([Bars]).
 * - Smithing: use a bar on any anvil (or its "Smith" option) with a hammer. Pick an item and an
 *   amount ([Smithing]). Menus use the scrollable list interface, like the teleport menu.
 *
 * Handlers are registered on GameLifecycle.Startup, only where nothing else already handles that
 * loc/op, so real upstream content added later takes precedence instead of clashing.
 */
class MiningSmithingScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val seqTypes: SeqTypeList,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private var objByName: Map<String, UnpackedObjType> = emptyMap()

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
    }

    // ---------------------------------------------------------------- registration

    private fun register(bus: EventBus) {
        objByName =
            objTypes.values
                .filter { !it.isCert && !it.isPlaceholder && it.name != "null" }
                .sortedBy { it.id }
                .groupBy { it.name.lowercase() }
                .mapValues { it.value.first() }

        var rocks = 0
        var furnaces = 0
        var anvils = 0
        val unmatchedMine = HashMap<String, Int>()
        for ((id, type) in locTypes) {
            val name = type.name.lowercase()
            val ore = Ores.byRockName[name]
            if (ore == null && type.opIndex("Mine") != null) {
                unmatchedMine.merge(type.name, 1, Int::plus)
            }
            if (ore != null) {
                val op = type.opIndex("Mine") ?: continue
                if (addLocOp(bus, op, id) { loc, t -> mine(loc, t, ore) }) rocks++
                continue
            }
            if (name == "furnace") {
                type.opIndex("Smelt")?.let { addLocOp(bus, it, id) { _, _ -> smelt() } }
                for (oreName in Bars.ores) {
                    val oreType = obj(oreName) ?: continue
                    addLocU(bus, id, oreType.id) { smelt() }
                }
                furnaces++
                continue
            }
            if (name == "anvil") {
                type.opIndex("Smith")?.let { addLocOp(bus, it, id) { _, _ -> smith(null) } }
                for (metal in Smithing.metals) {
                    val barType = obj(metal.bar) ?: continue
                    addLocU(bus, id, barType.id) { smith(metal) }
                }
                anvils++
            }
        }
        logger.info {
            "[beanlab] mining/smithing registered: $rocks rock types, $furnaces furnace types, " +
                "$anvils anvil types"
        }
        logger.info {
            "[beanlab] 'Mine' locs not covered (name x types): " +
                unmatchedMine.entries.sortedByDescending { it.value }.take(25).joinToString()
        }
    }

    private fun UnpackedLocType.opIndex(name: String): Int? =
        op.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it in 0..4 }

    private fun obj(name: String): UnpackedObjType? = objByName[name.lowercase()]

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

    private fun addLocOp(
        bus: EventBus,
        opIndex: Int,
        locId: Int,
        action: suspend ProtectedAccess.(BoundLocInfo, UnpackedLocType) -> Unit,
    ): Boolean {
        val key = locId.toLong()
        return when (opIndex) {
            0 -> addIfFree(bus, LocEvents.Op1::class.java, key) { action(it.loc, it.type) }
            1 -> addIfFree(bus, LocEvents.Op2::class.java, key) { action(it.loc, it.type) }
            2 -> addIfFree(bus, LocEvents.Op3::class.java, key) { action(it.loc, it.type) }
            3 -> addIfFree(bus, LocEvents.Op4::class.java, key) { action(it.loc, it.type) }
            else -> addIfFree(bus, LocEvents.Op5::class.java, key) { action(it.loc, it.type) }
        }
    }

    private fun addLocU(
        bus: EventBus,
        locId: Int,
        objId: Int,
        action: suspend ProtectedAccess.() -> Unit,
    ) =
        addIfFree(bus, LocUEvents.Op::class.java, EventBus.composeLongKey(locId, objId)) {
            action()
        }

    // ---------------------------------------------------------------- shared helpers

    private fun ProtectedAccess.count(name: String): Int = obj(name)?.let { inv.count(it) } ?: 0

    private fun ProtectedAccess.playAnim(seqId: Int) {
        seqTypes[seqId]?.let { anim(it) }
    }

    private fun ProtectedAccess.xp(stat: org.rsmod.game.type.stat.StatType, base: Double) {
        statAdvance(stat, base * xpMods.get(player, stat))
    }

    /** 1 / 5 / 10 / All, via the list interface. Null if cancelled. */
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

    // ---------------------------------------------------------------- mining

    private fun ProtectedAccess.bestPickaxe(): Pickaxe? {
        val held = player.righthand?.id
        return Pickaxes.all.firstOrNull { pick ->
            val type = obj(pick.name) ?: return@firstOrNull false
            player.miningLvl >= pick.level && (type in inv || held == type.id)
        }
    }

    private suspend fun ProtectedAccess.mine(
        rock: BoundLocInfo,
        rockType: UnpackedLocType,
        ore: Ore,
    ) {
        val oreType = obj(ore.oreName) ?: return
        while (true) {
            if (player.miningLvl < ore.level) {
                mes("You need a Mining level of ${ore.level} to mine this rock.")
                return
            }
            val pick = bestPickaxe()
            if (pick == null) {
                mes("You need a pickaxe to mine this rock.")
                mes("You do not have a pickaxe which you have the Mining level to use.")
                return
            }
            if (inv.isFull()) {
                mes("Your inventory is too full to hold any more ${ore.oreName.lowercase()}.")
                return
            }
            if (locRepo.findExact(rock.coords, rockType) == null) return
            playAnim(pick.anim)
            delay(pick.interval)
            if (locRepo.findExact(rock.coords, rockType) == null) {
                resetAnim()
                return
            }
            val low = min(255, (ore.low * pick.bonus).toInt())
            val high = min(255, (ore.high * pick.bonus).toInt())
            if (statRandom(stats.mining, low, high, 0)) {
                invAdd(inv, oreType, 1)
                xp(stats.mining, ore.xp)
                spam("You manage to mine some ${ore.oreName.lowercase()}.")
                val empty = Ores.depleted[rockType.id]
                if (empty != null) {
                    locRepo.change(rock, locTypes.getValue(empty), ore.respawnTicks)
                } else {
                    locRepo.del(rock, ore.respawnTicks)
                }
                resetAnim()
                return
            }
        }
    }

    // ---------------------------------------------------------------- smelting

    private fun ProtectedAccess.hasOres(bar: Bar): Boolean =
        bar.ores.all { (n, c) -> count(n) >= c }

    private suspend fun ProtectedAccess.smelt() {
        val makeable = Bars.all.filter { hasOres(it) }
        if (makeable.isEmpty()) {
            mes("You don't have the ores to smelt anything.")
            return
        }
        val choice =
            menu(
                "What would you like to smelt?",
                hotkeys = false,
                makeable.map { "${it.name} (Smithing ${it.level})" } + "Cancel",
            )
        ifClose()
        val bar = makeable.getOrNull(choice) ?: return
        if (player.smithingLvl < bar.level) {
            mes("You need a Smithing level of ${bar.level} to smelt ${bar.name.lowercase()}s.")
            return
        }
        val barType = obj(bar.name) ?: return
        val amount = askAmount() ?: return
        var made = 0
        while (made < amount) {
            if (!hasOres(bar)) {
                if (made > 0) mes("You have run out of ores.")
                return
            }
            playAnim(SMELT_ANIM)
            delay(4)
            if (!hasOres(bar)) return
            for ((n, c) in bar.ores) invDel(inv, obj(n)!!, c)
            made++
            if (bar.successPercent < 100 && random.of(1, 100) > bar.successPercent) {
                spam("The ore is too impure and you fail to refine it.")
                continue
            }
            invAdd(inv, barType, 1)
            xp(stats.smithing, bar.xp)
            spam("You retrieve a bar of ${bar.name.removeSuffix(" bar").lowercase()}.")
        }
    }

    // ---------------------------------------------------------------- smithing

    private suspend fun ProtectedAccess.smith(metalHint: Metal?) {
        if (count("Hammer") == 0 && count("Imcando hammer") == 0) {
            mes("You need a hammer to work the metal with.")
            return
        }
        val metal =
            metalHint
                ?: run {
                    val available = Smithing.metals.filter { count(it.bar) > 0 }
                    if (available.isEmpty()) {
                        mes("You don't have any bars to smith.")
                        return
                    }
                    val pick =
                        menu("Which bar?", hotkeys = false, available.map { it.bar } + "Cancel")
                    ifClose()
                    available.getOrNull(pick) ?: return
                }
        if (player.smithingLvl < metal.level) {
            mes("You need a Smithing level of ${metal.level} to work ${metal.bar.lowercase()}s.")
            return
        }
        val options =
            Smithing.items.mapNotNull { item ->
                val type = obj(metal.prefix + item.suffix) ?: return@mapNotNull null
                Triple(item, type, min(99, metal.level + item.levelOffset))
            }
        val choice =
            menu(
                "${metal.prefix} smithing",
                hotkeys = false,
                options.map { (item, type, level) ->
                    val qty = if (item.amount > 1) "${item.amount} x " else ""
                    "$qty${type.name} - level $level, ${item.bars} bar${if (item.bars > 1) "s" else ""}"
                } + "Cancel",
            )
        ifClose()
        val (item, product, level) = options.getOrNull(choice) ?: return
        if (player.smithingLvl < level) {
            mes("You need a Smithing level of $level to make ${product.name.lowercase()}.")
            return
        }
        val amount = askAmount() ?: return
        var made = 0
        while (made < amount) {
            if (count(metal.bar) < item.bars) {
                if (made == 0)
                    mes("You don't have enough bars to make ${product.name.lowercase()}.")
                return
            }
            playAnim(SMITH_ANIM)
            delay(4)
            if (count(metal.bar) < item.bars) return
            invDel(inv, obj(metal.bar)!!, item.bars)
            invAdd(inv, product, item.amount)
            xp(stats.smithing, metal.xpPerBar * item.bars)
            spam("You hammer the ${metal.prefix.lowercase()} and make ${product.name.lowercase()}.")
            made++
        }
    }

    private companion object {
        const val SMELT_ANIM = 899 // human_furnace
        const val SMITH_ANIM = 898 // human_smithing
    }
}
