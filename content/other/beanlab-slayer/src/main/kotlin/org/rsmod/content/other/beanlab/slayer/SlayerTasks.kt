package org.rsmod.content.other.beanlab.slayer

/** A slayer task from the cache `slayer_task` table. [id] matches npc param 50. */
data class SlayerTask(
    val id: Int,
    val name: String,
    val combatLevel: Int,
    val slayerLevel: Int,
    val needsQuest: Boolean,
)

/** One row of the cache `slayer_master_task` table. */
data class MasterTask(
    val master: Int,
    val task: SlayerTask,
    val weight: Int,
    val min: Int,
    val max: Int,
)

/** A slayer master. [id] is the cache master id used by `slayer_master_task`. */
data class SlayerMaster(
    val id: Int,
    val name: String,
    val combatLevel: Int,
    val slayerLevel: Int,
    val points: Int,
)

/** Picks tasks a player may be given, independent of the game so it can be unit tested. */
class SlayerTasks(rows: List<MasterTask>, private val killable: Set<Int>) {
    private val byMaster: Map<Int, List<MasterTask>> = rows.groupBy { it.master }

    val taskCount: Int = rows.map { it.task.id }.toSet().size

    fun eligible(master: Int, combat: Int, slayer: Int, exclude: Int = -1): List<MasterTask> =
        byMaster[master].orEmpty().filter {
            it.weight > 0 &&
                it.task.id > 0 &&
                it.task.id != exclude &&
                !it.task.needsQuest &&
                it.task.id in killable &&
                combat >= it.task.combatLevel &&
                slayer >= it.task.slayerLevel
        }

    /** Weighted pick from [eligible] using [roll] in `[0, total weight)`. */
    fun pick(options: List<MasterTask>, roll: (Int) -> Int): MasterTask? {
        val total = options.sumOf { it.weight }
        if (total <= 0) return null
        var r = roll(total)
        for (option in options) {
            if (r < option.weight) return option
            r -= option.weight
        }
        return options.last()
    }

    companion object {
        /** Masters by cache master id; combat/slayer requirements and points per task. */
        val masters: Map<Int, SlayerMaster> =
            listOf(
                    SlayerMaster(1, "Turael", 0, 1, 0),
                    SlayerMaster(2, "Mazchna", 20, 1, 6),
                    SlayerMaster(3, "Vannaka", 40, 1, 8),
                    SlayerMaster(4, "Chaeldar", 70, 1, 10),
                    SlayerMaster(5, "Duradel", 100, 50, 15),
                    SlayerMaster(6, "Nieve", 85, 1, 12),
                    SlayerMaster(7, "Krystilia", 0, 1, 25),
                    SlayerMaster(8, "Konar quo Maten", 75, 1, 18),
                    SlayerMaster(9, "Spria", 0, 1, 0),
                )
                .associateBy { it.id }

        const val CANCEL_COST: Int = 30

        private val MASTER_NAME = Regex("""slayer_master_(\d)(_[a-z]+)?""")

        /** Npc internal name -> master id. */
        fun masterIdFor(internalName: String): Int? =
            when {
                internalName == "slayer_master_nieve" || internalName == "slayer_master_steve" -> 6
                internalName == "slayer_master_9_active" -> 9
                else -> MASTER_NAME.matchEntire(internalName)?.groupValues?.get(1)?.toIntOrNull()
            }
    }
}
