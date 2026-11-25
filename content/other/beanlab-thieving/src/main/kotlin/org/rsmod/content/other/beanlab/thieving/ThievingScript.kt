package org.rsmod.content.other.beanlab.thieving

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.LocUnimplementedEvents
import org.rsmod.api.player.events.interact.NpcUnimplementedEvents
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.thievingLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onProtectedEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.game.type.seq.UnpackedSeqType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Thieving: pickpocketing NPCs and stealing from market stalls.
 *
 * Both bind the engine's unimplemented option slot, which runs only when no type or content script
 * already handles that NPC or loc. A name missing from the cache is skipped and counted instead of
 * failing boot. Guards and stall owners do not react to a theft.
 */
class ThievingScript
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val seqTypes: SeqTypeList,
    private val locRepo: LocRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private var byInternal: Map<String, UnpackedObjType> = emptyMap()
    private var pickAnim: UnpackedSeqType? = null
    private var tableAnim: UnpackedSeqType? = null
    private var emptyStall: UnpackedLocType? = null

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
    }

    private fun ScriptContext.register(bus: EventBus) {
        byInternal =
            objTypes.values
                .filter { !it.isCert && !it.isPlaceholder }
                .sortedBy { it.id }
                .mapNotNull { type -> type.internalName?.lowercase()?.let { it to type } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.first() }
        pickAnim = seq("human_pickpocket")
        tableAnim = seq("human_pickuptable")
        emptyStall = locTypes.values.firstOrNull { it.internalName == Thieving.EMPTY_STALL }

        var npcCount = 0
        var stallCount = 0
        var skipped = 0
        for (group in Thieving.pockets) {
            val bound = bindPockets(bus, group)
            if (bound == 0) {
                skipped++
                logger.info { "[beanlab] thieving unmatched npcs: ${group.npcs}" }
            }
            npcCount += bound
        }
        for (stall in Thieving.stalls) {
            val bound = bindStall(bus, stall)
            if (bound == 0) {
                skipped++
                logger.info { "[beanlab] thieving unmatched stall: ${stall.loc}" }
            }
            stallCount += bound
        }
        logger.info {
            "[beanlab] thieving registered: $npcCount pickpocket npcs, $stallCount stall locs, " +
                "$skipped groups skipped"
        }
    }

    private fun seq(name: String): UnpackedSeqType? =
        seqTypes.values.firstOrNull { it.internalName.equals(name, ignoreCase = true) }

    private fun slot(options: List<String?>, wanted: String): Int? =
        options.indexOfFirst { norm(it) == norm(wanted) }.takeIf { it in 0..4 }

    private fun norm(text: String?): String = text.orEmpty().trim().lowercase().replace("-", " ")

    private fun ScriptContext.bindPockets(bus: EventBus, group: Pocket): Int {
        var bound = 0
        for (name in group.npcs) {
            val type = npcTypes.values.firstOrNull { it.internalName == name } ?: continue
            val slot = slot(type.op.toList(), "Pickpocket") ?: continue
            val id = type.id
            val key = id.toLong()
            val added =
                when (slot) {
                    0 -> bindNpc1(bus, id, key, group)
                    1 -> bindNpc2(bus, id, key, group)
                    2 -> bindNpc3(bus, id, key, group)
                    3 -> bindNpc4(bus, id, key, group)
                    else -> bindNpc5(bus, id, key, group)
                }
            if (added) bound++
        }
        return bound
    }

    private fun ScriptContext.bindNpc1(bus: EventBus, id: Int, key: Long, g: Pocket): Boolean {
        if (bus.contains(NpcUnimplementedEvents.Op1::class.java, key)) return false
        onProtectedEvent<NpcUnimplementedEvents.Op1>(id) { pickpocket(it.npc, g) }
        return true
    }

    private fun ScriptContext.bindNpc2(bus: EventBus, id: Int, key: Long, g: Pocket): Boolean {
        if (bus.contains(NpcUnimplementedEvents.Op2::class.java, key)) return false
        onProtectedEvent<NpcUnimplementedEvents.Op2>(id) { pickpocket(it.npc, g) }
        return true
    }

    private fun ScriptContext.bindNpc3(bus: EventBus, id: Int, key: Long, g: Pocket): Boolean {
        if (bus.contains(NpcUnimplementedEvents.Op3::class.java, key)) return false
        onProtectedEvent<NpcUnimplementedEvents.Op3>(id) { pickpocket(it.npc, g) }
        return true
    }

    private fun ScriptContext.bindNpc4(bus: EventBus, id: Int, key: Long, g: Pocket): Boolean {
        if (bus.contains(NpcUnimplementedEvents.Op4::class.java, key)) return false
        onProtectedEvent<NpcUnimplementedEvents.Op4>(id) { pickpocket(it.npc, g) }
        return true
    }

    private fun ScriptContext.bindNpc5(bus: EventBus, id: Int, key: Long, g: Pocket): Boolean {
        if (bus.contains(NpcUnimplementedEvents.Op5::class.java, key)) return false
        onProtectedEvent<NpcUnimplementedEvents.Op5>(id) { pickpocket(it.npc, g) }
        return true
    }

    private fun ScriptContext.bindStall(bus: EventBus, stall: Stall): Int {
        val matches =
            locTypes.values.filter {
                it.internalName == stall.loc || norm(it.name) == norm(stall.label)
            }
        var bound = 0
        for (type in matches) {
            val slot = slot(type.op.toList(), "Steal-from") ?: continue
            val id = type.id
            val key = id.toLong()
            val added =
                when (slot) {
                    0 -> {
                        val free = !bus.contains(LocUnimplementedEvents.Op1::class.java, key)
                        if (free)
                            onProtectedEvent<LocUnimplementedEvents.Op1>(id) {
                                steal(it.loc, stall)
                            }
                        free
                    }
                    1 -> {
                        val free = !bus.contains(LocUnimplementedEvents.Op2::class.java, key)
                        if (free)
                            onProtectedEvent<LocUnimplementedEvents.Op2>(id) {
                                steal(it.loc, stall)
                            }
                        free
                    }
                    2 -> {
                        val free = !bus.contains(LocUnimplementedEvents.Op3::class.java, key)
                        if (free)
                            onProtectedEvent<LocUnimplementedEvents.Op3>(id) {
                                steal(it.loc, stall)
                            }
                        free
                    }
                    3 -> {
                        val free = !bus.contains(LocUnimplementedEvents.Op4::class.java, key)
                        if (free)
                            onProtectedEvent<LocUnimplementedEvents.Op4>(id) {
                                steal(it.loc, stall)
                            }
                        free
                    }
                    else -> {
                        val free = !bus.contains(LocUnimplementedEvents.Op5::class.java, key)
                        if (free)
                            onProtectedEvent<LocUnimplementedEvents.Op5>(id) {
                                steal(it.loc, stall)
                            }
                        free
                    }
                }
            if (added) bound++
        }
        return bound
    }

    private suspend fun ProtectedAccess.pickpocket(npc: Npc, group: Pocket) {
        if (player.thievingLvl < group.level) {
            mes("You need a Thieving level of ${group.level} to pick the ${group.pocket}.")
            return
        }
        if (isInCombat()) {
            mes("You can't pickpocket during combat.")
            return
        }
        mes("You attempt to pick the ${group.pocket}.")
        delay(1)
        if (!statRandom(stats.thieving, group.chanceLow, group.chanceHigh, 0)) {
            fail(npc, group)
            return
        }
        roll(group.loot)
        pickAnim?.let { anim(it) }
        statAdvance(stats.thieving, group.xp * xpMods.get(player, stats.thieving))
        mes("You pick the ${group.pocket}.")
    }

    private suspend fun ProtectedAccess.fail(npc: Npc, group: Pocket) {
        npc.say("What do you think you're doing?")
        mes("You fail to pick the ${group.pocket}.")
        delay(1)
        player.queueHit(npc, 1, HitType.Typeless, group.stunDamage)
        mes("You've been stunned!")
        delay(group.stunTicks)
    }

    private suspend fun ProtectedAccess.steal(loc: BoundLocInfo, stall: Stall) {
        if (player.thievingLvl < stall.level) {
            mes("You need a Thieving level of ${stall.level} to steal from this stall.")
            return
        }
        if (isInCombat()) {
            mes("You can't steal from the market stall during combat!")
            return
        }
        val empty = emptyStall
        tableAnim?.let { anim(it) }
        delay(1)
        roll(stall.loot)
        statAdvance(stats.thieving, stall.xp * xpMods.get(player, stats.thieving))
        if (empty != null) locRepo.change(loc, empty, stall.respawnTicks)
    }

    private fun ProtectedAccess.roll(loot: List<Loot>) {
        val total = loot.sumOf { it.weight }
        var pick = random.of(total)
        for (line in loot) {
            if (pick < line.weight) {
                val type = byInternal[line.obj.lowercase()] ?: return
                val count =
                    if (line.max > line.min) line.min + random.of(line.max - line.min + 1)
                    else line.min
                invAdd(inv, type, count)
                return
            }
            pick -= line.weight
        }
    }
}
