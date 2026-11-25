package org.rsmod.content.other.beanlab.agility

import org.rsmod.map.CoordGrid

/** How an obstacle moves the player. Destinations come from the player's tile and the loc's. */
sealed class Move {
    /** Walk across in a straight line (log, rope, ledge, pipe) over one tick per tile. */
    data class Cross(
        val anim: String?,
        val dest: (player: CoordGrid, loc: CoordGrid) -> CoordGrid,
    ) : Move()

    /** Play [anim], wait [ticks], then appear at the destination (nets, branches, walls). */
    data class Climb(
        val anim: String?,
        val ticks: Int,
        val dest: (player: CoordGrid, loc: CoordGrid) -> CoordGrid,
    ) : Move()
}

/**
 * One obstacle. [loc] is a cache internal name; [stage] is its bit in the lap; [last] obstacles pay
 * the course's lap bonus when every stage was done in order since the first obstacle.
 */
data class Obstacle(
    val loc: String,
    val option: String,
    val stage: Int,
    val xp: Double,
    val move: Move,
    val message: String? = null,
    val last: Boolean = false,
)

data class Course(
    val name: String,
    val level: Int,
    val lapBonus: Double,
    val obstacles: List<Obstacle>,
    /** 1 in [graceChance] laps drops a mark of grace; 0 for none. */
    val graceChance: Int = 0,
) {
    val stageMask: Int = obstacles.fold(0) { acc, o -> acc or (1 shl o.stage) }
}

object Agility {
    private const val CLIMB = "human_reachforladder"
    private const val LOG = "human_walk_logbalance"

    private fun at(x: Int, z: Int, level: Int = 0) = CoordGrid(x, z, level)

    /** Gnome Stronghold, level 1. 86.5 xp per lap. */
    val gnome =
        Course(
            name = "Gnome Stronghold Agility Course",
            level = 1,
            lapBonus = 39.0,
            obstacles =
                listOf(
                    Obstacle(
                        "gnome_log_balance1",
                        "Walk-across",
                        0,
                        7.5,
                        Move.Cross(LOG) { _, _ -> at(2474, 3429) },
                        "You walk carefully across the slippery log...",
                    ),
                    Obstacle(
                        "obstical_net2",
                        "Climb-over",
                        1,
                        7.5,
                        Move.Climb(CLIMB, 2) { p, _ -> at(p.x, 3424, 1) },
                        "You climb the netting...",
                    ),
                    Obstacle(
                        "climbing_branch",
                        "Climb",
                        2,
                        5.0,
                        Move.Climb(CLIMB, 2) { _, _ -> at(2473, 3420, 2) },
                        "You climb the tree...",
                    ),
                    Obstacle(
                        "balancing_rope",
                        "Walk-on",
                        3,
                        7.5,
                        Move.Cross(LOG) { _, _ -> at(2483, 3420, 2) },
                        "You carefully cross the tightrope.",
                    ),
                    Obstacle(
                        "climbing_tree",
                        "Climb-down",
                        4,
                        5.0,
                        Move.Climb(CLIMB, 2) { _, _ -> at(2487, 3420, 0) },
                        "You climb down the tree...",
                    ),
                    Obstacle(
                        "obstical_net3",
                        "Climb-over",
                        5,
                        7.5,
                        Move.Climb(CLIMB, 2) { p, _ -> at(p.x, 3427, 0) },
                        "You climb the netting...",
                    ),
                    Obstacle(
                        "obstical_pipe3_1",
                        "Squeeze-through",
                        6,
                        7.5,
                        Move.Cross("human_pipesqueeze") { p, _ -> at(p.x, 3437, 0) },
                        "You squeeze through the pipe...",
                        last = true,
                    ),
                    Obstacle(
                        "obstical_pipe3_2",
                        "Squeeze-through",
                        6,
                        7.5,
                        Move.Cross("human_pipesqueeze") { p, _ -> at(p.x, 3437, 0) },
                        "You squeeze through the pipe...",
                        last = true,
                    ),
                ),
        )

    /** Barbarian Outpost, level 35. 153.2 xp per lap. The entrance pipe is outside the lap. */
    val barbarian =
        Course(
            name = "Barbarian Outpost Agility Course",
            level = 35,
            lapBonus = 46.2,
            obstacles =
                listOf(
                    Obstacle(
                        "obstical_ropeswing1",
                        "Swing-on",
                        0,
                        22.0,
                        Move.Climb("human_ropeswing", 2) { _, _ -> at(2551, 3549) },
                        "You skillfully swing across.",
                    ),
                    Obstacle(
                        "barbarian_log_balance1",
                        "Walk-across",
                        1,
                        13.7,
                        Move.Cross(LOG) { _, _ -> at(2541, 3546) },
                        "You walk carefully across the slippery log...",
                    ),
                    Obstacle(
                        "agility_obstical_net_barbarian",
                        "Climb-over",
                        2,
                        8.2,
                        Move.Climb(CLIMB, 2) { _, _ -> at(2537, 3546, 1) },
                        "You climb the netting...",
                    ),
                    Obstacle(
                        "balancing_ledge1",
                        "Walk-across",
                        3,
                        22.0,
                        Move.Cross("human_walk_sidestepl") { _, _ -> at(2532, 3547, 1) },
                        "You put your foot on the ledge and try to edge across...",
                    ),
                    Obstacle(
                        "barbarian_laddertop_norim",
                        "Climb-down",
                        4,
                        0.0,
                        Move.Climb(CLIMB, 2) { _, _ -> at(2532, 3546, 0) },
                    ),
                    Obstacle(
                        "castlecrumbly1",
                        "Climb-over",
                        5,
                        13.7,
                        Move.Climb("human_walk_crumbledwall", 2) { p, l -> at(l.x + 1, p.z) },
                        "You climb the low wall...",
                        last = true,
                    ),
                ),
        )

    /** Draynor Village rooftop, level 10. 120 xp per lap. */
    val draynor =
        Course(
            name = "Draynor Village Rooftop Course",
            level = 10,
            lapBonus = 0.0,
            graceChance = 4,
            obstacles =
                listOf(
                    Obstacle(
                        "rooftops_draynor_wallclimb",
                        "Climb",
                        0,
                        5.0,
                        Move.Climb(CLIMB, 2) { _, _ -> at(3102, 3279, 3) },
                    ),
                    Obstacle(
                        "rooftops_draynor_tightrope_1",
                        "Cross",
                        1,
                        8.0,
                        Move.Cross(LOG) { _, _ -> at(3090, 3277, 3) },
                    ),
                    Obstacle(
                        "rooftops_draynor_tightrope_2",
                        "Cross",
                        2,
                        7.0,
                        Move.Cross(LOG) { _, _ -> at(3092, 3267, 3) },
                    ),
                    Obstacle(
                        "rooftops_draynor_wallcrossing",
                        "Balance",
                        3,
                        7.0,
                        Move.Cross("human_walk_sidestepl") { _, _ -> at(3088, 3261, 3) },
                    ),
                    Obstacle(
                        "rooftops_draynor_wallscramble",
                        "Jump-up",
                        4,
                        10.0,
                        Move.Climb("agility_shortcut_wall_jump2", 2) { _, _ -> at(3088, 3255, 3) },
                    ),
                    Obstacle(
                        "rooftops_draynor_leapdown",
                        "Jump",
                        5,
                        4.0,
                        Move.Climb("human_spot_jump", 2) { _, _ -> at(3096, 3256, 3) },
                    ),
                    Obstacle(
                        "rooftops_draynor_crate",
                        "Climb-down",
                        6,
                        79.0,
                        Move.Climb(CLIMB, 2) { _, _ -> at(3103, 3261, 0) },
                        last = true,
                    ),
                ),
        )

    /** Barbarian Outpost entrance pipe: level 35, no xp, either direction. */
    const val BARBARIAN_PIPE: String = "agility_obstical_pipe_barbarian"

    val courses: List<Course> = listOf(gnome, barbarian, draynor)
}
