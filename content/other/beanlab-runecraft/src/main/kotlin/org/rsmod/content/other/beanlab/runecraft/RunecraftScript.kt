package org.rsmod.content.other.beanlab.runecraft

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.runecraftingLvl
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
import org.rsmod.game.type.seq.UnpackedSeqType
import org.rsmod.game.type.spot.SpotanimTypeList
import org.rsmod.game.type.spot.UnpackedSpotanimType
import org.rsmod.game.type.synth.SynthType
import org.rsmod.game.type.synth.SynthTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Runecraft altars, mysterious ruins, and temple exit portals.
 *
 * Craft-rune, or rune essence / pure essence used on the altar, binds every essence in the
 * inventory. The rune count per essence follows the table multiplier. A matching talisman used on
 * the ruins enters the temple, and the exit portal leaves it.
 */
class RunecraftScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val seqTypes: SeqTypeList,
    private val spotTypes: SpotanimTypeList,
    private val synthTypes: SynthTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private var objByInternal: Map<String, UnpackedObjType> = emptyMap()
    private var objByName: Map<String, UnpackedObjType> = emptyMap()
    private var essence: List<UnpackedObjType> = emptyList()
    private var craftSeq: UnpackedSeqType? = null
    private var pickupSeq: UnpackedSeqType? = null
    private var craftSpot: UnpackedSpotanimType? = null
    private var teleportSynth: SynthType? = null

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
    }

    private fun register(bus: EventBus) {
        val objs = objTypes.values.filter { !it.isCert && !it.isPlaceholder }.sortedBy { it.id }
        objByInternal =
            objs
                .mapNotNull { obj -> obj.internalName?.lowercase()?.let { it to obj } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.first() }
        objByName =
            objs
                .filter { it.name != "null" }
                .groupBy { it.name.lowercase() }
                .mapValues { it.value.first() }

        craftSeq = seqNamed(CRAFT_SEQ)
        pickupSeq = seqNamed(PICKUP_SEQ)
        craftSpot = spotTypes.values.firstOrNull { it.internalName == CRAFT_SPOT }
        teleportSynth = synthTypes.values.firstOrNull { it.internalName == TELEPORT_SYNTH }

        val skipped = LinkedHashSet<String>()
        essence =
            RunecraftTable.essence.mapNotNull { named ->
                val obj = obj(named.internal, named.display)
                if (obj == null) skipped += named.internal
                obj
            }

        var altars = 0
        for (altar in RunecraftTable.altars) {
            val locs = locs(altar.loc, "Altar")
            val rune = obj(altar.rune, altar.runeDisplay)
            if (locs.isEmpty()) skipped += altar.loc
            if (rune == null) skipped += altar.rune
            if (locs.isEmpty() || rune == null) continue
            var bound = 0
            for (loc in locs) {
                if (bindAltar(bus, loc, altar, rune)) bound++
            }
            if (bound == 0) skipped += altar.loc
            altars += bound
        }

        var ruins = 0
        for (ruin in RunecraftTable.ruins) {
            val locs = locs(ruin.loc, "Mysterious ruins")
            val talisman = obj(ruin.talisman, ruin.talismanDisplay)
            if (locs.isEmpty()) skipped += ruin.loc
            if (talisman == null) skipped += ruin.talisman
            if (locs.isEmpty() || talisman == null) continue
            var bound = 0
            for (loc in locs) {
                val key = EventBus.composeLongKey(loc.id, talisman.id)
                val added = addIfFree(bus, LocUEvents.Op::class.java, key) { enterRuin(ruin) }
                if (added) bound++
            }
            if (bound == 0) skipped += ruin.loc
            ruins += bound
        }

        var exits = 0
        for (portal in RunecraftTable.exits) {
            val locs = locs(portal.loc, "Portal")
            if (locs.isEmpty()) {
                skipped += portal.loc
                continue
            }
            var bound = 0
            for (loc in locs) {
                val op =
                    loc.opIndex(EXIT_OPTION)
                        ?: loc.opIndex(ENTER_OPTION)
                        ?: loc.opIndex(LEAVE_OPTION)
                        ?: continue
                if (addLocOp(bus, op, loc.id) { _, _ -> leaveTemple(portal) }) bound++
            }
            if (bound == 0) skipped += portal.loc
            exits += bound
        }

        val skipText =
            if (skipped.isEmpty()) {
                "0"
            } else {
                "${skipped.size} (${skipped.joinToString(", ")})"
            }
        logger.info {
            "[beanlab] runecraft registered: $altars altars, $TIARA_RECIPES tiara recipes, " +
                "$ruins ruins, $exits exits, skipped $skipText"
        }
    }

    private fun bindAltar(
        bus: EventBus,
        loc: UnpackedLocType,
        altar: RuneAltar,
        rune: UnpackedObjType,
    ): Boolean {
        var bound = false
        val craft = loc.opIndex(CRAFT_OPTION) ?: loc.opIndex(CRAFT_OPTION_ALT)
        if (craft != null && addLocOp(bus, craft, loc.id) { _, _ -> craftRunes(altar, rune) }) {
            bound = true
        }
        for (stack in essence) {
            val key = EventBus.composeLongKey(loc.id, stack.id)
            val added = addIfFree(bus, LocUEvents.Op::class.java, key) { craftRunes(altar, rune) }
            if (added) bound = true
        }
        return bound
    }

    private fun seqNamed(internal: String): UnpackedSeqType? =
        seqTypes.values.firstOrNull { it.internalName == internal }

    private fun obj(internal: String, display: String): UnpackedObjType? =
        objByInternal[internal.lowercase()] ?: objByName[display.lowercase()]

    private fun locs(internal: String, display: String): List<UnpackedLocType> {
        val key = internal.lowercase()
        val prefix = "${key}_"
        val matched = ArrayList<UnpackedLocType>()
        for (type in locTypes.values) {
            val name = type.internalName?.lowercase() ?: continue
            if (name == key || name.startsWith(prefix)) matched += type
        }
        if (matched.isNotEmpty()) return matched.distinctBy { it.id }
        val byDisplay = locTypes.values.filter { it.name.equals(display, true) }
        return if (byDisplay.size == 1) byDisplay else emptyList()
    }

    private fun UnpackedLocType.opIndex(name: String): Int? =
        op.indexOfFirst { it.equals(name, true) }.takeIf { it in 0..4 }

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

    private suspend fun ProtectedAccess.craftRunes(altar: RuneAltar, rune: UnpackedObjType) {
        if (altar.members && !player.members) {
            mes("You must be on a members' world to do that.")
            return
        }
        if (player.runecraftingLvl < altar.level) {
            mes(
                "You need a Runecrafting level of at least ${altar.level} to craft " +
                    "${rune.name}."
            )
            return
        }
        val stacks = essence.filter { inv.count(it) > 0 }
        val total = stacks.sumOf { inv.count(it) }
        if (total <= 0) {
            mes("You do not have any rune stones to bind.")
            return
        }
        val perEss = altar.runesPerEssence(player.runecraftingLvl)
        val produced = total.toLong() * perEss
        val held = inv.count(rune).toLong()
        if (produced > Int.MAX_VALUE || held + produced > Int.MAX_VALUE) {
            val plural = rune.name.lowercase() + "s"
            mes("You do not have enough inventory space to craft your essence into $plural.")
            return
        }
        craftSeq?.let { anim(it) }
        craftSpot?.let { spotanim(it, height = SPOT_HEIGHT) }
        var spent = 0
        for (stack in stacks) {
            val count = inv.count(stack)
            if (count <= 0) continue
            if (!invDel(inv, stack, count).success) break
            spent += count
        }
        if (spent <= 0) return
        val made = spent * perEss
        statAdvance(stats.runecrafting, altar.xp * spent * xpMods.get(player, stats.runecrafting))
        invAdd(inv, rune, made)
        mes("You bind the temple's power into ${altar.label} Runes.")
        delay(CRAFT_DELAY)
    }

    private suspend fun ProtectedAccess.enterRuin(ruin: RuneRuin) {
        if (ruin.members && !player.members) {
            mes("You must be on a members' world to do that.")
            return
        }
        if (ruin.enters.isEmpty()) return
        pickupSeq?.let { anim(it) }
        teleportSynth?.let { soundSynth(it) }
        mes("You hold the ${ruin.label} Talisman towards the mysterious ruins.")
        mes("You feel a powerful force take hold of you...")
        delay(ENTER_DELAY)
        telejump(pick(ruin.enters))
    }

    private fun ProtectedAccess.leaveTemple(portal: RuneExit) {
        if (portal.exits.isEmpty()) return
        teleportSynth?.let { soundSynth(it) }
        mes("You step through the portal...")
        telejump(pick(portal.exits))
    }

    private fun ProtectedAccess.pick(coords: List<TempleCoord>): CoordGrid {
        val index = if (coords.size == 1) 0 else random.of(coords.size)
        return coords[index].toGrid()
    }

    private companion object {
        const val CRAFT_OPTION = "Craft-rune"
        const val CRAFT_OPTION_ALT = "Craft"
        const val EXIT_OPTION = "Use"
        const val ENTER_OPTION = "Enter"
        const val LEAVE_OPTION = "Exit"
        const val CRAFT_SEQ = "human_runecraft"
        const val CRAFT_SPOT = "runecrafting"
        const val PICKUP_SEQ = "human_pickupfloor"
        const val TELEPORT_SYNTH = "teleport_all"
        const val SPOT_HEIGHT = 96
        const val CRAFT_DELAY = 3
        const val ENTER_DELAY = 1
        const val TIARA_RECIPES = 0
    }
}
