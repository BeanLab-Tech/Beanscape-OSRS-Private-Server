package org.rsmod.content.other.beanlab.skills

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.annotations.InternalApi
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.events.interact.HeldUEvents
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.cookingLvl
import org.rsmod.api.player.stat.firemakingLvl
import org.rsmod.api.player.stat.stat
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.MapClock
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.game.type.stat.StatType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Prayer, firemaking and cooking, which RsMod upstream doesn't implement (its cooking module is an
 * empty placeholder).
 * - Prayer: "Bury" bones and "Scatter" demonic ashes for XP ([Bones]); "Pray-at" any altar restores
 *   Prayer points.
 * - Firemaking: use a tinderbox on logs ([Logs]) to light a fire where you stand, then step west.
 *   The fire burns 100-200 ticks and leaves ashes.
 * - Cooking: use raw food ([Cookables]) on any fire or range; burn chance falls to 0 at the
 *   stop-burn level (ranges burn slightly less). Amount menu 1/5/10/All.
 *
 * Registered on GameLifecycle.Startup, only where nothing else handles that item/loc yet.
 */
class SkillsScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val seqTypes: SeqTypeList,
    private val locRepo: LocRepository,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
    private val clock: MapClock,
) : PluginScript() {
    private val logger = InlineLogger()
    private var objByName: Map<String, UnpackedObjType> = emptyMap()
    private val pendingAshes = ArrayList<Pair<CoordGrid, Int>>()

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
        onEvent<GameLifecycle.LateCycle> { dropDueAshes() }
    }

    // ---------------------------------------------------------------- registration

    private fun register(bus: EventBus) {
        objByName =
            objTypes.values
                .filter { !it.isCert && !it.isPlaceholder && it.name != "null" }
                .sortedBy { it.id }
                .groupBy { it.name.lowercase() }
                .mapValues { it.value.first() }

        var buryable = 0
        for ((id, type) in objTypes) {
            if (type.isCert || type.isPlaceholder) continue
            val bury = type.iop.indexOfFirst { it.equals("Bury", true) }
            if (bury in 0..4) {
                val xp = Bones.xp[type.name.lowercase()] ?: Bones.DEFAULT_BONE_XP
                if (
                    addHeldOp(bus, bury, id) { t, slot -> bury(t, slot, xp, "You bury the bones.") }
                ) {
                    buryable++
                }
                continue
            }
            val scatter = type.iop.indexOfFirst { it.equals("Scatter", true) }
            val ashXp = Bones.ashesXp[type.name.lowercase()]
            if (scatter in 0..4 && ashXp != null) {
                if (
                    addHeldOp(bus, scatter, id) { t, slot ->
                        bury(t, slot, ashXp, "You scatter the ashes.")
                    }
                ) {
                    buryable++
                }
            }
        }

        var logs = 0
        val tinderbox = obj("Tinderbox")
        if (tinderbox != null) {
            for (log in Logs.all) {
                val logType = obj(log.name) ?: continue
                val key = EventBus.composeLongKey(tinderbox.id, logType.id)
                val reverse = EventBus.composeLongKey(logType.id, tinderbox.id)
                if (bus.contains(HeldUEvents.Type::class.java, key)) continue
                if (bus.contains(HeldUEvents.Type::class.java, reverse)) continue
                bus.subscribeSuspend(HeldUEvents.Type::class.java, key) {
                    light(log, if (it.first.id == logType.id) it.firstSlot else it.secondSlot)
                }
                logs++
            }
        }

        var altars = 0
        var cookers = 0
        for ((id, type) in locTypes) {
            val pray = type.op.indexOfFirst { it.equals("Pray-at", true) }
            if (pray in 0..4 && addLocOp(bus, pray, id) { prayAt() }) altars++
            if (type.name.lowercase() in Cookables.cookingLocNames) {
                val isRange = type.name.lowercase() != "fire"
                var any = false
                for (food in Cookables.all) {
                    val raw = obj(food.raw) ?: continue
                    val key = EventBus.composeLongKey(id, raw.id)
                    if (bus.contains(LocUEvents.Op::class.java, key)) continue
                    bus.subscribeSuspend(LocUEvents.Op::class.java, key) {
                        cook(food, it.loc, it.type, isRange)
                    }
                    any = true
                }
                if (any) cookers++
            }
        }
        logger.info {
            "[beanlab] skills registered: $buryable bones/ashes, $logs log types, $altars altar " +
                "types, $cookers fire/range types"
        }
    }

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

    private fun addHeldOp(
        bus: EventBus,
        opIndex: Int,
        objId: Int,
        action: suspend ProtectedAccess.(UnpackedObjType, Int) -> Unit,
    ): Boolean {
        val key = objId.toLong()
        return when (opIndex) {
            0 -> addIfFree(bus, HeldObjEvents.Op1::class.java, key) { action(it.type, it.slot) }
            1 -> addIfFree(bus, HeldObjEvents.Op2::class.java, key) { action(it.type, it.slot) }
            2 -> addIfFree(bus, HeldObjEvents.Op3::class.java, key) { action(it.type, it.slot) }
            3 -> addIfFree(bus, HeldObjEvents.Op4::class.java, key) { action(it.type, it.slot) }
            else -> addIfFree(bus, HeldObjEvents.Op5::class.java, key) { action(it.type, it.slot) }
        }
    }

    private fun addLocOp(
        bus: EventBus,
        opIndex: Int,
        locId: Int,
        action: suspend ProtectedAccess.() -> Unit,
    ): Boolean {
        val key = locId.toLong()
        return when (opIndex) {
            0 -> addIfFree(bus, LocEvents.Op1::class.java, key) { action() }
            1 -> addIfFree(bus, LocEvents.Op2::class.java, key) { action() }
            2 -> addIfFree(bus, LocEvents.Op3::class.java, key) { action() }
            3 -> addIfFree(bus, LocEvents.Op4::class.java, key) { action() }
            else -> addIfFree(bus, LocEvents.Op5::class.java, key) { action() }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun ProtectedAccess.playAnim(seqId: Int) {
        seqTypes[seqId]?.let { anim(it) }
    }

    private fun ProtectedAccess.xp(stat: StatType, base: Double) {
        statAdvance(stat, base * xpMods.get(player, stat))
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

    // ---------------------------------------------------------------- prayer

    private suspend fun ProtectedAccess.bury(
        type: UnpackedObjType,
        slot: Int,
        xp: Double,
        message: String,
    ) {
        val removed = invDel(inv, type, count = 1, slot = slot)
        if (!removed.success) return
        playAnim(BURY_ANIM)
        xp(stats.prayer, xp)
        mes(message)
        delay(2)
    }

    @OptIn(InternalApi::class)
    private fun ProtectedAccess.prayAt() {
        val base = player.statMap.getBaseLevel(stats.prayer).toInt()
        if (player.stat(stats.prayer) >= base) {
            mes("You already have full Prayer points.")
            return
        }
        playAnim(PRAY_ANIM)
        statRestore(stats.prayer)
        mes("You recharge your Prayer points.")
    }

    // ---------------------------------------------------------------- firemaking

    private suspend fun ProtectedAccess.light(log: Log, slot: Int) {
        if (player.firemakingLvl < log.level) {
            mes("You need a Firemaking level of ${log.level} to burn ${log.name.lowercase()}.")
            return
        }
        val fireType = locTypes[FIRE_LOC] ?: return
        val logType = obj(log.name) ?: return
        val here = coords
        if (locRepo.findExact(here, fireType) != null) {
            mes("You can't light a fire here.")
            return
        }
        spam("You attempt to light the logs.")
        while (true) {
            playAnim(LIGHT_ANIM)
            delay(4)
            if (coords != here) return
            if (statRandom(stats.firemaking, 65, 255, 0)) break
        }
        val removed = invDel(inv, logType, count = 1, slot = slot)
        if (!removed.success) {
            resetAnim()
            return
        }
        val duration = random.of(100, 200)
        locRepo.add(here, fireType, duration, LocAngle.West, LocShape.CentrepieceStraight)
        pendingAshes += here to (clock.cycle + duration)
        resetAnim()
        xp(stats.firemaking, log.xp)
        spam("The fire catches and the logs begin to burn.")
        walk(here.translateX(-1))
    }

    private fun dropDueAshes() {
        if (pendingAshes.isEmpty()) return
        val now = clock.cycle
        val ashes = obj("Ashes") ?: return
        val due = pendingAshes.filter { it.second <= now }
        if (due.isEmpty()) return
        pendingAshes.removeAll(due.toSet())
        for ((coords, _) in due) objRepo.add(ashes, coords, ASHES_DURATION)
    }

    // ---------------------------------------------------------------- cooking

    private fun cookSuccess(level: Int, food: Cookable, isRange: Boolean, roll: Int): Boolean {
        if (level >= food.stopBurn) return true
        val span = (food.stopBurn - food.level).coerceAtLeast(1)
        val progress = (level - food.level).coerceIn(0, span)
        var chance = 50 + 50 * progress / span
        if (isRange) chance += 5
        return roll <= chance
    }

    private suspend fun ProtectedAccess.cook(
        food: Cookable,
        source: BoundLocInfo,
        sourceType: UnpackedLocType,
        isRange: Boolean,
    ) {
        if (player.cookingLvl < food.level) {
            mes("You need a Cooking level of ${food.level} to cook this.")
            return
        }
        val raw = obj(food.raw) ?: return
        val cooked = obj(food.cooked) ?: return
        val burnt = obj(food.burnt) ?: obj("Burnt fish")
        val amount = if (inv.count(raw) > 1) askAmount() ?: return else 1
        var done = 0
        while (done < amount) {
            if (inv.count(raw) == 0) return
            if (locRepo.findExact(source.coords, sourceType) == null) {
                mes("The fire has gone out.")
                return
            }
            playAnim(COOK_ANIM)
            delay(4)
            if (!invDel(inv, raw, count = 1).success) return
            if (cookSuccess(player.cookingLvl, food, isRange, random.of(1, 100))) {
                invAdd(inv, cooked, 1)
                xp(stats.cooking, food.xp)
                spam("You successfully cook the ${food.cooked.lowercase()}.")
            } else {
                if (burnt != null) invAdd(inv, burnt, 1)
                spam("You accidentally burn the ${food.cooked.lowercase()}.")
            }
            done++
        }
    }

    private companion object {
        const val FIRE_LOC = 26185 // fire
        const val ASHES_DURATION = 200
        const val BURY_ANIM = 827 // human_pickupfloor
        const val PRAY_ANIM = 645 // human_pray
        const val LIGHT_ANIM = 733 // human_createfire
        const val COOK_ANIM = 896 // human_cooking
    }
}
