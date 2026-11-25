package org.rsmod.content.other.beanlab.agility

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.util.WeakHashMap
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.LocUnimplementedEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.agilityLvl
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.game.type.seq.UnpackedSeqType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Agility courses: Gnome Stronghold, Barbarian Outpost and the Draynor rooftop ([Agility]).
 *
 * Obstacles bind the unimplemented option slot by loc internal name. Destinations are fixed tiles
 * checked against the cache map spawns. Doing every obstacle of a course in order pays the lap
 * bonus on the last one; rooftop laps can also drop a mark of grace. Lap progress is in memory.
 */
class AgilityScript
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val seqTypes: SeqTypeList,
    private val objRepo: ObjRepository,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private val laps = WeakHashMap<Player, LapState>()
    private var seqs: Map<String, UnpackedSeqType> = emptyMap()

    private data class LapState(val course: Course, var done: Int)

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
    }

    private fun register(bus: EventBus) {
        seqs =
            seqTypes.values
                .filter { it.internalName != null }
                .associateBy { it.internalName!!.lowercase() }
        val byName = locTypes.values.groupBy { it.internalName }
        var bound = 0
        var missing = 0
        for (course in Agility.courses) {
            for (obstacle in course.obstacles) {
                val types = byName[obstacle.loc].orEmpty()
                if (types.isEmpty()) missing++
                for (type in types) {
                    val slot = type.slot(obstacle.option) ?: continue
                    if (bindLoc(bus, slot, type.id) { cross(it, course, obstacle) }) bound++
                }
            }
        }
        for (type in byName[Agility.BARBARIAN_PIPE].orEmpty()) {
            val slot = type.slot("Squeeze-through") ?: continue
            if (bindLoc(bus, slot, type.id) { barbarianPipe(it) }) bound++
        }
        logger.info { "[beanlab] agility registered: $bound obstacle locs, $missing missing" }
    }

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

    private suspend fun ProtectedAccess.cross(
        loc: BoundLocInfo,
        course: Course,
        obstacle: Obstacle,
    ) {
        if (player.agilityLvl < course.level) {
            mes("You need an Agility level of ${course.level} to attempt this obstacle.")
            return
        }
        obstacle.message?.let { mes(it) }
        move(obstacle.move, loc.coords)
        statAdvance(stats.agility, obstacle.xp * xpMods.get(player, stats.agility))
        progress(course, obstacle)
    }

    private suspend fun ProtectedAccess.move(move: Move, loc: CoordGrid) {
        when (move) {
            is Move.Cross -> {
                val start = coords
                val end = move.dest(start, loc)
                val tiles = maxOf(1, start.chebyshevDistance(end))
                move.anim?.let { seqs[it] }?.let { anim(it) }
                exactMove(
                    start,
                    end,
                    delay1 = 0,
                    delay2 = tiles * CYCLES_PER_TICK,
                    dir = face(start, end),
                )
                delay(tiles)
                resetAnim()
            }
            is Move.Climb -> {
                val end = move.dest(coords, loc)
                faceSquare(loc)
                move.anim?.let { seqs[it] }?.let { anim(it) }
                delay(move.ticks)
                telejump(end)
            }
        }
    }

    /** Client direction (0..2047, 0 = south) for a straight move from [from] to [to]. */
    private fun face(from: CoordGrid, to: CoordGrid): Int {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return when {
            dz < 0 && dx == 0 -> 0
            dx < 0 && dz == 0 -> 512
            dz > 0 && dx == 0 -> 1024
            dx > 0 && dz == 0 -> 1536
            else -> 0
        }
    }

    private fun ProtectedAccess.progress(course: Course, obstacle: Obstacle) {
        val bit = 1 shl obstacle.stage
        val state = laps[player]?.takeIf { it.course === course }
        val current =
            when {
                obstacle.stage == 0 -> LapState(course, 0).also { laps[player] = it }
                state == null -> return
                else -> state
            }
        current.done = current.done or bit
        if (!obstacle.last) return
        laps.remove(player)
        if (current.done != course.stageMask) return
        if (course.lapBonus > 0) {
            statAdvance(stats.agility, course.lapBonus * xpMods.get(player, stats.agility))
        }
        mes("You complete a lap of the ${course.name}.")
        if (course.graceChance > 0 && random.of(course.graceChance) == 0) {
            val grace = objTypes.values.firstOrNull { it.internalName == "grace" } ?: return
            invAddOrDrop(objRepo, grace)
            mes("You find a Mark of grace!")
        }
    }

    private suspend fun ProtectedAccess.barbarianPipe(loc: BoundLocInfo) {
        if (player.agilityLvl < Agility.barbarian.level) {
            mes("You need an Agility level of ${Agility.barbarian.level} to squeeze through.")
            return
        }
        val c = loc.coords
        val north = coords.z > c.z
        val end = CoordGrid(c.x, if (north) c.z - 1 else c.z + 2, 0)
        mes("You squeeze through the pipe.")
        move(Move.Cross("human_pipesqueeze") { _, _ -> end }, c)
    }

    private companion object {
        const val CYCLES_PER_TICK = 30
    }
}
