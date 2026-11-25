package org.rsmod.content.other.beanlab.hunter

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.events.interact.LocUnimplementedEvents
import org.rsmod.api.player.events.interact.NpcUnimplementedEvents
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.righthand
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.PlayerUid
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Hunter: bird snares and box traps, butterfly and impling netting, and looting impling jars.
 *
 * "Lay" a trap item to place the trap loc on your tile. Every few ticks a laid trap may catch the
 * best creature of that trap's kind within [Hunter.TRAP_RANGE] tiles that the owner can catch, and
 * turns into the cache's full-trap loc. "Check" it for the rewards and the trap back. Traps that
 * catch nothing collapse after [Hunter.COLLAPSE_TICKS] and the trap item drops on the ground. Traps
 * are kept in memory and belong to the player who laid them this session.
 */
class HunterScript
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypeList: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val seqTypes: SeqTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val players: PlayerList,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private val traps = ArrayList<LaidTrap>()
    private var cycle = 0
    private var objs: Map<String, UnpackedObjType> = emptyMap()
    private var locs: Map<String, UnpackedLocType> = emptyMap()

    private class LaidTrap(
        val owner: PlayerUid,
        val kind: TrapKind,
        var loc: LocInfo,
        val laidAt: Int,
        var caught: TrapCatch? = null,
    )

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
        onEvent<GameLifecycle.StartCycle> { tick() }
    }

    private fun register(bus: EventBus) {
        objs =
            objTypes.values
                .filter { !it.isCert && !it.isPlaceholder }
                .sortedBy { it.id }
                .mapNotNull { type -> type.internalName?.let { it to type } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.first() }
        locs = locTypes.values.mapNotNull { t -> t.internalName?.let { it to t } }.toMap()

        var bound = 0
        for (kind in Hunter.traps) {
            val item = objs[kind.item] ?: continue
            val lay = item.iop.indexOfFirst { it.equals("Lay", ignoreCase = true) }
            if (bindHeld(bus, lay, item.id) { lay(kind) }) bound++
            for (name in listOf(kind.laid, kind.failed)) {
                val loc = locs[name] ?: continue
                loc.slot("Dismantle")?.let {
                    if (bindLoc(bus, it, loc.id) { b -> dismantle(b) }) bound++
                }
            }
            for (catch in kind.catches) {
                val loc = locs[catch.full] ?: continue
                for (option in listOf("Check", "Reset")) {
                    loc.slot(option)?.let {
                        if (bindLoc(bus, it, loc.id) { b -> check(b) }) bound++
                    }
                }
            }
        }
        for (net in Hunter.nets) {
            for (type in npcTypesNamed(net.npc)) {
                val slot = type.op.indexOfFirst { it.equals("Catch", ignoreCase = true) }
                if (bindNpc(bus, slot, type.id) { catchWithNet(it, net) }) bound++
            }
        }
        for ((jar, loot) in Hunter.implingLoot) {
            val item = objs[jar] ?: continue
            val slot = item.iop.indexOfFirst { it.equals("Loot", ignoreCase = true) }
            if (bindHeld(bus, slot, item.id) { lootJar(item, loot) }) bound++
        }
        logger.info { "[beanlab] hunter registered: $bound bindings" }
    }

    private fun npcTypesNamed(name: String) = npcTypeList.values.filter { it.internalName == name }

    private fun UnpackedLocType.slot(option: String): Int? =
        op.indexOfFirst { it.equals(option, ignoreCase = true) }.takeIf { it in 0..4 }

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

    private fun bindLoc(
        bus: EventBus,
        slot: Int,
        id: Int,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ): Boolean {
        val key = id.toLong()
        return when (slot) {
            0 -> addIfFree(bus, LocUnimplementedEvents.Op1::class.java, key) { action(it.loc) }
            1 -> addIfFree(bus, LocUnimplementedEvents.Op2::class.java, key) { action(it.loc) }
            2 -> addIfFree(bus, LocUnimplementedEvents.Op3::class.java, key) { action(it.loc) }
            3 -> addIfFree(bus, LocUnimplementedEvents.Op4::class.java, key) { action(it.loc) }
            else -> addIfFree(bus, LocUnimplementedEvents.Op5::class.java, key) { action(it.loc) }
        }
    }

    private fun bindNpc(
        bus: EventBus,
        slot: Int,
        id: Int,
        action: suspend ProtectedAccess.(Npc) -> Unit,
    ): Boolean {
        val key = id.toLong()
        return when (slot) {
            0 -> addIfFree(bus, NpcUnimplementedEvents.Op1::class.java, key) { action(it.npc) }
            1 -> addIfFree(bus, NpcUnimplementedEvents.Op2::class.java, key) { action(it.npc) }
            2 -> addIfFree(bus, NpcUnimplementedEvents.Op3::class.java, key) { action(it.npc) }
            3 -> addIfFree(bus, NpcUnimplementedEvents.Op4::class.java, key) { action(it.npc) }
            4 -> addIfFree(bus, NpcUnimplementedEvents.Op5::class.java, key) { action(it.npc) }
            else -> false
        }
    }

    private fun bindHeld(
        bus: EventBus,
        slot: Int,
        id: Int,
        action: suspend ProtectedAccess.() -> Unit,
    ): Boolean {
        val key = id.toLong()
        return when (slot) {
            0 -> addIfFree(bus, HeldObjEvents.Op1::class.java, key) { action() }
            1 -> addIfFree(bus, HeldObjEvents.Op2::class.java, key) { action() }
            2 -> addIfFree(bus, HeldObjEvents.Op3::class.java, key) { action() }
            3 -> addIfFree(bus, HeldObjEvents.Op4::class.java, key) { action() }
            4 -> addIfFree(bus, HeldObjEvents.Op5::class.java, key) { action() }
            else -> false
        }
    }

    // ------------------------------------------------------------------ traps

    private suspend fun ProtectedAccess.lay(kind: TrapKind) {
        val item = objs[kind.item] ?: return
        val laid = locs[kind.laid] ?: return
        val mine = traps.count { it.owner == player.uid }
        if (mine >= Hunter.maxTraps(player.hunterLvl)) {
            mes("You don't have a high enough Hunter level to set up more than $mine traps.")
            return
        }
        val blocked = locRepo.findAll(coords).any { it.layer == GROUND_LAYER }
        if (traps.any { it.loc.coords == coords } || blocked) {
            mes("You can't lay a trap here.")
            return
        }
        seqTypes.values
            .firstOrNull { it.internalName == "hunting_setting_trap_small" }
            ?.let { anim(it) }
        delay(2)
        if (!invDel(inv, item, count = 1).success) return
        val loc =
            locRepo.add(coords, laid, Int.MAX_VALUE, LocAngle.West, LocShape.CentrepieceStraight)
        traps += LaidTrap(player.uid, kind, loc, cycle)
        mes("You set up the ${kind.label}.")
    }

    private fun ProtectedAccess.trapAt(loc: BoundLocInfo): LaidTrap? {
        val trap = traps.firstOrNull { it.loc.coords == loc.coords } ?: return null
        if (trap.owner != player.uid) {
            mes("This isn't your trap.")
            return null
        }
        return trap
    }

    private fun ProtectedAccess.dismantle(loc: BoundLocInfo) {
        val trap = trapAt(loc) ?: return
        val item = objs[trap.kind.item] ?: return
        if (inv.isFull()) {
            mes("You don't have enough inventory space.")
            return
        }
        remove(trap)
        invAdd(inv, item, 1)
        mes("You dismantle the ${trap.kind.label}.")
    }

    private fun ProtectedAccess.check(loc: BoundLocInfo) {
        val trap = trapAt(loc) ?: return
        val caught = trap.caught ?: return dismantle(loc)
        val item = objs[trap.kind.item] ?: return
        if (inv.freeSpace() < caught.rewards.size + 1) {
            mes("You don't have enough inventory space.")
            return
        }
        remove(trap)
        for (reward in caught.rewards) {
            val type = objs[reward.obj] ?: continue
            val count = reward.min + random.of(reward.max - reward.min + 1)
            invAdd(inv, type, count)
        }
        invAdd(inv, item, 1)
        statAdvance(stats.hunter, caught.xp * xpMods.get(player, stats.hunter))
        mes("You've caught a ${caught.label}.")
    }

    private fun remove(trap: LaidTrap) {
        traps.remove(trap)
        locRepo.del(trap.loc, Int.MAX_VALUE)
    }

    private fun tick() {
        cycle++
        if (cycle % Hunter.ROLL_TICKS != 0 || traps.isEmpty()) return
        for (trap in traps.toList()) {
            val owner = trap.owner.resolve(players)
            val age = cycle - trap.laidAt
            val limit =
                if (trap.caught == null) Hunter.COLLAPSE_TICKS else Hunter.COLLAPSE_TICKS * 3
            if (owner == null || age > limit) {
                collapse(trap)
                continue
            }
            if (trap.caught != null) continue
            val level = owner.hunterLvl
            val coords = trap.loc.coords
            val nearby =
                npcRepo
                    .findAll(ZoneKey.from(coords), 1)
                    .filter { it.coords.level == coords.level }
                    .filter { it.coords.chebyshevDistance(coords) <= Hunter.TRAP_RANGE }
                    .mapNotNull { it.type.internalName }
                    .toSet()
            val target =
                trap.kind.catches
                    .filter { it.npc in nearby && level >= it.level }
                    .maxByOrNull { it.level } ?: continue
            if (random.of(100) >= catchChance(level, target.level)) continue
            val full = locs[target.full] ?: continue
            locRepo.del(trap.loc, Int.MAX_VALUE)
            trap.loc =
                locRepo.add(
                    coords,
                    full,
                    Int.MAX_VALUE,
                    LocAngle.West,
                    LocShape.CentrepieceStraight,
                )
            trap.caught = target
            owner.mes("Your ${trap.kind.label} has caught something.")
        }
    }

    /** Percent chance per roll: 30% at the creature's level, +2% per level above, capped at 80%. */
    private fun catchChance(level: Int, required: Int): Int = minOf(80, 30 + (level - required) * 2)

    private fun collapse(trap: LaidTrap) {
        remove(trap)
        val item = objs[trap.kind.item] ?: return
        val owner = trap.owner.resolve(players)
        objRepo.add(item, trap.loc.coords, COLLAPSED_ITEM_TICKS, owner)
        owner?.mes("Your ${trap.kind.label} has collapsed.")
    }

    // ------------------------------------------------------------------- nets

    private suspend fun ProtectedAccess.catchWithNet(npc: Npc, net: NetCatch) {
        val netType = objs[Hunter.NET]
        val jar = objs[net.jar] ?: return
        val product = objs[net.product] ?: return
        if (player.hunterLvl < net.level) {
            mes("You need a Hunter level of ${net.level} to catch a ${net.label}.")
            return
        }
        val holding = netType != null && (player.righthand?.id == netType.id || netType in inv)
        if (!holding) {
            mes("You need a butterfly net to catch that.")
            return
        }
        if (jar !in inv) {
            mes("You need an empty jar to put the ${net.label} in.")
            return
        }
        seqTypes.values
            .firstOrNull { it.internalName == "human_butterflynet_swing" }
            ?.let { anim(it) }
        delay(2)
        if (random.of(100) >= catchChance(player.hunterLvl, net.level) + 15) {
            mes("You fail to catch the ${net.label}.")
            return
        }
        if (!invDel(inv, jar, count = 1).success) return
        invAdd(inv, product, 1)
        statAdvance(stats.hunter, net.xp * xpMods.get(player, stats.hunter))
        npcRepo.hide(npc, NET_RESPAWN_TICKS)
        mes("You manage to catch the ${net.label}.")
    }

    private fun ProtectedAccess.lootJar(jar: UnpackedObjType, loot: List<Reward>) {
        if (inv.freeSpace() < 1 && jar !in inv) return
        val reward = loot[random.of(loot.size)]
        val type = objs[reward.obj] ?: return
        if (!invDel(inv, jar, count = 1).success) return
        val count = reward.min + random.of(reward.max - reward.min + 1)
        invAdd(inv, type, count)
        objs[Hunter.IMP_JAR]?.let { if (random.of(10) != 0) invAdd(inv, it, 1) }
        mes("You open the jar and find ${type.name.lowercase()}.")
    }

    private companion object {
        const val COLLAPSED_ITEM_TICKS = 100
        const val NET_RESPAWN_TICKS = 50

        /** Loc layer of centrepiece (scenery) locs; ground decoration does not block a trap. */
        const val GROUND_LAYER = 2
    }
}
