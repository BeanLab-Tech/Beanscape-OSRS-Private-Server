package org.rsmod.content.other.admin.potato

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onCommand
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.type.dbrow.DbRowTypeList
import org.rsmod.game.type.varbit.VarBitTypeList
import org.rsmod.game.type.varp.VarpTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * ::questsdone [display name] - marks every quest (and miniquest) in the cache's `quest` dbtable as
 * complete for yourself or an online player, and sets quest points to the full total.
 *
 * Each quest's progress var comes from [questProgressVars]; the completion value is that row's
 * quest:endstate. Vars shared by several quests get the highest endstate. Admin only.
 */
public class QuestCommands
@Inject
constructor(
    private val playerList: PlayerList,
    private val dbRows: DbRowTypeList,
    private val varpTypes: VarpTypeList,
    private val varBitTypes: VarBitTypeList,
) : PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        onCommand("questsdone") {
            modLevel = modlevels.admin
            desc = "Complete all quests: ::questsdone [display name]"
            cheat { completeAll(this) }
        }
    }

    private fun completeAll(cheat: Cheat) {
        val name = cheat.args.joinToString(" ").trim()
        val target =
            if (name.isEmpty()) cheat.player
            else playerList.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
        if (target == null) {
            cheat.player.mes("No online player named '$name'.")
            return
        }

        // Highest endstate per var, plus the quest-point total.
        val targets = HashMap<QuestVar, Int>()
        var questPoints = 0
        var quests = 0
        var skipped = 0
        for ((rowId, row) in dbRows.types) {
            if (row.table != QUEST_TABLE) continue
            val endState = row.data[COL_ENDSTATE]?.firstOrNull() as? Int
            val questVar = questProgressVars[rowId]
            if (endState == null || questVar == null) {
                skipped++
                continue
            }
            targets.merge(questVar, endState, ::maxOf)
            questPoints += (row.data[COL_QUESTPOINTS]?.firstOrNull() as? Int) ?: 0
            quests++
        }

        var failed = 0
        for ((questVar, value) in targets) {
            try {
                target.setQuestVar(questVar, value)
            } catch (e: Exception) {
                failed++
                logger.warn(e) {
                    "[quests] could not set $questVar=$value for ${target.displayName}"
                }
            }
        }
        varpTypes[VARP_QP]?.let { VarPlayerIntMapSetter.set(target, it, questPoints) }

        logger.info {
            "[quests] ${cheat.player.displayName} completed $quests quests for ${target.displayName} " +
                "(qp=$questPoints, skipped=$skipped, failed=$failed)"
        }
        val msg =
            "Marked $quests quests complete ($questPoints QP). Relog to refresh the quest list."
        cheat.player.mes(if (target === cheat.player) msg else "${target.displayName}: $msg")
        if (failed > 0) cheat.player.mes("$failed quest vars failed to set - see server log.")
        if (target !== cheat.player)
            target.mes("All quests have been marked complete. Relog to refresh.")
    }

    private fun Player.setQuestVar(questVar: QuestVar, value: Int) {
        when (questVar) {
            is QuestVar.Varp -> {
                val type = varpTypes[questVar.id] ?: error("varp ${questVar.id} missing")
                VarPlayerIntMapSetter.set(this, type, value)
            }
            is QuestVar.Bit -> {
                val type = varBitTypes[questVar.id] ?: error("varbit ${questVar.id} missing")
                val width = type.bits.last - type.bits.first + 1
                val max = if (width >= 31) Int.MAX_VALUE else (1 shl width) - 1
                VarPlayerIntMapSetter.set(this, type, value.coerceAtMost(max))
            }
        }
    }

    private companion object {
        const val QUEST_TABLE = 0
        const val COL_QUESTPOINTS = 17
        const val COL_ENDSTATE = 19
        const val VARP_QP = 101
    }
}
