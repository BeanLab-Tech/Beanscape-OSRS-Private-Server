package org.rsmod.content.other.beanlab.slayer

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.params
import org.rsmod.api.config.refs.stats
import org.rsmod.api.death.NpcKilled
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.events.interact.NpcUnimplementedEvents
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.slayerLvl
import org.rsmod.api.player.stat.statAdvance
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.type.dbrow.DbRowTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.game.type.varbit.VarBitTypeList
import org.rsmod.game.type.varp.VarpType
import org.rsmod.game.type.varp.VarpTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Slayer, driven by the cache's own task tables (`slayer_task`, `slayer_master_task`).
 *
 * Masters give tasks from "Assignment" and run a small "Rewards" menu (cancel a task). A kill
 * counts when the npc's slayer category (param 50) matches the task; it pays slayer xp from the
 * npc's slayer xp param, or its hitpoints. The task, count, master, points and streak are the OSRS
 * vars (perm), so they survive logout and show in the client. The enchanted gem's "Check" reports
 * the task. Quest-gated tasks and boss tasks are never given.
 */
class SlayerScript
@Inject
constructor(
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val dbRows: DbRowTypeList,
    private val varpTypes: VarpTypeList,
    private val varBitTypes: VarBitTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private var tasks: SlayerTasks = SlayerTasks(emptyList(), emptySet())
    private var taskById: Map<Int, SlayerTask> = emptyMap()
    private lateinit var target: VarpType
    private lateinit var count: VarpType
    private var points: VarBitType? = null
    private var completed: VarBitType? = null
    private var master: VarBitType? = null

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
        onEvent<NpcKilled> { hero?.let { onKill(it, npc) } }
    }

    private fun register(bus: EventBus) {
        target = varpTypes[VARP_TARGET] ?: error("varp slayer_target missing")
        count = varpTypes[VARP_COUNT] ?: error("varp slayer_count missing")
        points = varBitTypes[VARBIT_POINTS]
        completed = varBitTypes[VARBIT_COMPLETED]
        master = varBitTypes[VARBIT_MASTER]

        val taskRows = HashMap<Int, SlayerTask>()
        for ((rowId, row) in dbRows.types) {
            if (row.table != TABLE_TASK) continue
            val id = row.int(COL_TASK_ID) ?: continue
            val stat = row.data[COL_TASK_STAT].orEmpty()
            val slayerReq = if (stat.getOrNull(1) == SLAYER_STAT_ID) stat[0] as? Int ?: 1 else 1
            taskRows[rowId] =
                SlayerTask(
                    id = id,
                    name = row.data[COL_TASK_NAME]?.firstOrNull() as? String ?: "monsters",
                    combatLevel = row.int(COL_TASK_COMBAT) ?: 0,
                    slayerLevel = slayerReq,
                    needsQuest = !row.data[COL_TASK_QUESTS].isNullOrEmpty(),
                )
        }
        val masterRows =
            dbRows.types.values
                .filter { it.table == TABLE_MASTER_TASK }
                .mapNotNull { row ->
                    val task = row.int(COL_MT_TASK)?.let { taskRows[it] } ?: return@mapNotNull null
                    MasterTask(
                        master = row.int(COL_MT_MASTER) ?: return@mapNotNull null,
                        task = task,
                        weight = row.int(COL_MT_WEIGHT) ?: 0,
                        min = row.int(COL_MT_MIN) ?: 1,
                        max = row.int(COL_MT_MAX) ?: 1,
                    )
                }
        val killable =
            npcTypes.values
                .mapNotNull { it.paramMap?.primitiveMap?.get(PARAM_CATEGORY) as? Int }
                .toSet()
        tasks = SlayerTasks(masterRows, killable)
        taskById = taskRows.values.associateBy { it.id }

        var masters = 0
        for ((id, type) in npcTypes) {
            val masterId = type.internalName?.let { SlayerTasks.masterIdFor(it) } ?: continue
            val info = SlayerTasks.masters[masterId] ?: continue
            for ((slot, option) in type.op.withIndex()) {
                when (option?.lowercase()) {
                    "assignment" -> bindNpc(bus, slot, id) { assign(it, info) }
                    "rewards" -> bindNpc(bus, slot, id) { rewards(it) }
                }
            }
            masters++
        }
        objTypes.values
            .firstOrNull { it.internalName == "slayer_gem" }
            ?.let { gem ->
                val slot = gem.iop.indexOfFirst { it.equals("Check", ignoreCase = true) }
                bindHeld(bus, slot, gem.id) { checkTask() }
            }
        logger.info {
            "[beanlab] slayer registered: ${tasks.taskCount} tasks, ${masterRows.size} master " +
                "rows, $masters master npcs"
        }
    }

    private fun org.rsmod.game.type.dbrow.UnpackedDbRowType.int(col: Int): Int? =
        data[col]?.firstOrNull() as? Int

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

    private fun bindNpc(
        bus: EventBus,
        slot: Int,
        id: Int,
        action: suspend ProtectedAccess.(Npc) -> Unit,
    ) {
        val key = id.toLong()
        when (slot) {
            0 -> addIfFree(bus, NpcUnimplementedEvents.Op1::class.java, key) { action(it.npc) }
            1 -> addIfFree(bus, NpcUnimplementedEvents.Op2::class.java, key) { action(it.npc) }
            2 -> addIfFree(bus, NpcUnimplementedEvents.Op3::class.java, key) { action(it.npc) }
            3 -> addIfFree(bus, NpcUnimplementedEvents.Op4::class.java, key) { action(it.npc) }
            else -> addIfFree(bus, NpcUnimplementedEvents.Op5::class.java, key) { action(it.npc) }
        }
    }

    private fun bindHeld(
        bus: EventBus,
        slot: Int,
        id: Int,
        action: suspend ProtectedAccess.() -> Unit,
    ) {
        val key = id.toLong()
        when (slot) {
            0 -> addIfFree(bus, HeldObjEvents.Op1::class.java, key) { action() }
            1 -> addIfFree(bus, HeldObjEvents.Op2::class.java, key) { action() }
            2 -> addIfFree(bus, HeldObjEvents.Op3::class.java, key) { action() }
            3 -> addIfFree(bus, HeldObjEvents.Op4::class.java, key) { action() }
            4 -> addIfFree(bus, HeldObjEvents.Op5::class.java, key) { action() }
        }
    }

    private fun Player.taskName(): String? = taskById[vars[target]]?.name

    private fun Player.setVar(type: VarBitType?, value: Int) {
        type ?: return
        val width = type.bits.last - type.bits.first + 1
        VarPlayerIntMapSetter.set(this, type, value.coerceIn(0, (1 shl width) - 1))
    }

    private suspend fun ProtectedAccess.assign(npc: Npc, info: SlayerMaster) {
        val remaining = player.vars[count]
        val current = player.taskName()
        if (remaining > 0 && current != null) {
            startDialogue(npc) {
                chatNpc(neutral, "You're still hunting $current; you have $remaining to go.")
            }
            return
        }
        if (player.combatLevel < info.combatLevel || player.slayerLvl < info.slayerLevel) {
            startDialogue(npc) {
                chatNpc(
                    neutral,
                    "You need a combat level of ${info.combatLevel} and a Slayer level of " +
                        "${info.slayerLevel} before I'll give you a task.",
                )
            }
            return
        }
        val options = tasks.eligible(info.id, player.combatLevel, player.slayerLvl)
        val picked = tasks.pick(options) { random.of(it) }
        if (picked == null) {
            startDialogue(npc) { chatNpc(neutral, "I don't have anything for you right now.") }
            return
        }
        val amount = picked.min + random.of(maxOf(1, picked.max - picked.min + 1))
        VarPlayerIntMapSetter.set(player, target, picked.task.id)
        VarPlayerIntMapSetter.set(player, count, amount)
        player.setVar(master, info.id)
        startDialogue(npc) {
            chatNpc(happy, "Your new task is to kill $amount ${picked.task.name}.")
        }
    }

    private suspend fun ProtectedAccess.rewards(npc: Npc) {
        val have = points?.let { player.vars[it] } ?: 0
        val choice =
            menu(
                "Slayer points: $have",
                false,
                listOf("Cancel my task (${SlayerTasks.CANCEL_COST} points)", "Nothing"),
            )
        ifClose()
        if (choice != 0) return
        if (player.vars[count] <= 0) {
            startDialogue(npc) { chatNpc(neutral, "You don't have a task to cancel.") }
            return
        }
        if (have < SlayerTasks.CANCEL_COST) {
            startDialogue(npc) { chatNpc(neutral, "You don't have enough points for that.") }
            return
        }
        player.setVar(points, have - SlayerTasks.CANCEL_COST)
        VarPlayerIntMapSetter.set(player, count, 0)
        VarPlayerIntMapSetter.set(player, target, 0)
        startDialogue(npc) { chatNpc(happy, "Your task has been cancelled. Ask me for a new one.") }
    }

    private fun ProtectedAccess.checkTask() {
        val remaining = player.vars[count]
        val name = player.taskName()
        if (remaining <= 0 || name == null) {
            mes("You need something new to hunt.")
            return
        }
        mes("You're assigned to kill $name; only $remaining more to go.")
    }

    private fun onKill(player: Player, npc: Npc) {
        val task = player.vars[target]
        val remaining = player.vars[count]
        if (task <= 0 || remaining <= 0) return
        val category = npc.visType.paramMap?.primitiveMap?.get(PARAM_CATEGORY) as? Int
        if (category != task) return
        val xpParam = npc.visType.paramOrNull(params.slayer_experience) ?: 0
        val xp = if (xpParam > 0) xpParam / 10.0 else npc.visType.hitpoints.toDouble()
        player.statAdvance(stats.slayer, xp * xpMods.get(player, stats.slayer))
        val left = remaining - 1
        VarPlayerIntMapSetter.set(player, count, left)
        if (left > 0) return
        VarPlayerIntMapSetter.set(player, target, 0)
        val done = (completed?.let { player.vars[it] } ?: 0) + 1
        player.setVar(completed, done)
        val masterInfo = master?.let { SlayerTasks.masters[player.vars[it]] }
        val earned = masterInfo?.points ?: 0
        if (earned > 0) player.setVar(points, (points?.let { player.vars[it] } ?: 0) + earned)
        player.mes(
            "You've completed $done tasks" +
                (if (earned > 0) " and received $earned points" else "") +
                "; return to a Slayer master."
        )
    }

    private companion object {
        const val VARP_COUNT = 394
        const val VARP_TARGET = 395
        const val VARBIT_MASTER = 4067
        const val VARBIT_POINTS = 4068
        const val VARBIT_COMPLETED = 4069
        const val PARAM_CATEGORY = 50
        const val SLAYER_STAT_ID = 18

        const val TABLE_TASK = 113
        const val COL_TASK_ID = 0
        const val COL_TASK_COMBAT = 1
        const val COL_TASK_STAT = 2
        const val COL_TASK_QUESTS = 6
        const val COL_TASK_NAME = 9

        const val TABLE_MASTER_TASK = 114
        const val COL_MT_MASTER = 0
        const val COL_MT_TASK = 1
        const val COL_MT_WEIGHT = 2
        const val COL_MT_MIN = 3
        const val COL_MT_MAX = 4
    }
}
