package org.rsmod.content.other.beanlab.runecraft

import org.rsmod.map.CoordGrid

/**
 * Runecraft numbers rewritten from the Lost City `runecraft_table`.
 *
 * Experience in that table is stored in tenths (air 50 is 5.0). A multiplier, when present, is the
 * level step in `level / step + 1` runes per essence. Law and death have no multiplier, so they
 * stay at one rune per essence. Soul and blood have no row.
 */
data class TempleCoord(val level: Int, val mx: Int, val mz: Int, val lx: Int, val lz: Int) {
    fun toGrid(): CoordGrid = CoordGrid(level, mx, mz, lx, lz)
}

data class RuneAltar(
    val loc: String,
    val level: Int,
    val xp: Double,
    val rune: String,
    val runeDisplay: String,
    val label: String,
    val members: Boolean,
    val multiplier: Int?,
) {
    fun runesPerEssence(rcLevel: Int): Int {
        val step = multiplier ?: return 1
        if (step <= 0) return 1
        return rcLevel / step + 1
    }
}

data class RuneRuin(
    val loc: String,
    val talisman: String,
    val talismanDisplay: String,
    val label: String,
    val members: Boolean,
    val enters: List<TempleCoord>,
)

data class RuneExit(val loc: String, val exits: List<TempleCoord>)

data class NamedObj(val internal: String, val display: String)

object RunecraftTable {
    val essence =
        listOf(NamedObj("blankrune", "Rune essence"), NamedObj("blankrune_high", "Pure essence"))

    val altars =
        listOf(
            RuneAltar("air_altar", 1, 5.0, "airrune", "Air rune", "Air", false, 11),
            RuneAltar("mind_altar", 2, 5.5, "mindrune", "Mind rune", "Mind", false, 14),
            RuneAltar("water_altar", 5, 6.0, "waterrune", "Water rune", "Water", false, 19),
            RuneAltar("earth_altar", 9, 6.5, "earthrune", "Earth rune", "Earth", false, 26),
            RuneAltar("fire_altar", 14, 7.0, "firerune", "Fire rune", "Fire", false, 35),
            RuneAltar("body_altar", 20, 7.5, "bodyrune", "Body rune", "Body", false, 46),
            RuneAltar("cosmic_altar", 27, 8.0, "cosmicrune", "Cosmic rune", "Cosmic", true, 59),
            RuneAltar("chaos_altar", 35, 8.5, "chaosrune", "Chaos rune", "Chaos", true, 74),
            RuneAltar("nature_altar", 44, 9.0, "naturerune", "Nature rune", "Nature", true, 91),
            RuneAltar("law_altar", 54, 9.5, "lawrune", "Law rune", "Law", true, null),
            RuneAltar("death_altar", 65, 10.0, "deathrune", "Death rune", "Death", true, null),
        )

    val ruins =
        listOf(
            RuneRuin(
                "airtemple_ruined",
                "air_talisman",
                "Air talisman",
                "Air",
                false,
                listOf(TempleCoord(0, 44, 75, 25, 30)),
            ),
            RuneRuin(
                "mindtemple_ruined",
                "mind_talisman",
                "Mind talisman",
                "Mind",
                false,
                listOf(TempleCoord(0, 43, 75, 41, 29)),
            ),
            RuneRuin(
                "watertemple_ruined",
                "water_talisman",
                "Water talisman",
                "Water",
                false,
                listOf(TempleCoord(0, 42, 75, 37, 32)),
            ),
            RuneRuin(
                "earthtemple_ruined",
                "earth_talisman",
                "Earth talisman",
                "Earth",
                false,
                listOf(TempleCoord(0, 41, 75, 33, 30)),
            ),
            RuneRuin(
                "firetemple_ruined",
                "fire_talisman",
                "Fire talisman",
                "Fire",
                false,
                listOf(TempleCoord(0, 40, 75, 16, 48)),
            ),
            RuneRuin(
                "bodytemple_ruined",
                "body_talisman",
                "Body talisman",
                "Body",
                false,
                listOf(TempleCoord(0, 39, 75, 27, 38)),
            ),
            RuneRuin(
                "cosmictemple_ruined",
                "cosmic_talisman",
                "Cosmic talisman",
                "Cosmic",
                true,
                listOf(
                    TempleCoord(0, 33, 75, 30, 13),
                    TempleCoord(0, 33, 75, 50, 33),
                    TempleCoord(0, 33, 75, 30, 53),
                    TempleCoord(0, 33, 75, 10, 33),
                ),
            ),
            RuneRuin(
                "chaostemple_ruined",
                "chaos_talisman",
                "Chaos talisman",
                "Chaos",
                true,
                listOf(TempleCoord(3, 35, 75, 35, 47)),
            ),
            RuneRuin(
                "naturetemple_ruined",
                "nature_talisman",
                "Nature talisman",
                "Nature",
                true,
                listOf(TempleCoord(0, 37, 75, 32, 35)),
            ),
            RuneRuin(
                "lawtemple_ruined",
                "law_talisman",
                "Law talisman",
                "Law",
                true,
                listOf(TempleCoord(0, 38, 75, 32, 19)),
            ),
        )

    val exits =
        listOf(
            RuneExit("airtemple_exit_portal", listOf(TempleCoord(0, 46, 51, 39, 24))),
            RuneExit("mindtemple_exit_portal", listOf(TempleCoord(0, 46, 54, 36, 55))),
            RuneExit("watertemple_exit_portal", listOf(TempleCoord(0, 49, 49, 46, 26))),
            RuneExit("earthtemple_exit_portal", listOf(TempleCoord(0, 51, 54, 39, 21))),
            RuneExit("firetemple_exit_portal", listOf(TempleCoord(0, 51, 50, 46, 52))),
            RuneExit("bodytemple_exit_portal", listOf(TempleCoord(0, 47, 53, 42, 50))),
            RuneExit("cosmictemple_exit_portal", listOf(TempleCoord(0, 49, 148, 37, 29))),
            RuneExit(
                "chaostemple_exit_portal",
                listOf(
                    TempleCoord(0, 47, 56, 52, 1),
                    TempleCoord(0, 47, 56, 52, 1),
                    TempleCoord(0, 47, 56, 55, 3),
                    TempleCoord(0, 47, 56, 58, 7),
                    TempleCoord(0, 47, 56, 55, 12),
                    TempleCoord(0, 47, 56, 52, 13),
                    TempleCoord(0, 47, 56, 48, 10),
                    TempleCoord(0, 47, 56, 47, 7),
                    TempleCoord(0, 47, 56, 47, 4),
                ),
            ),
            RuneExit("naturetemple_exit_portal", listOf(TempleCoord(0, 44, 47, 49, 14))),
            RuneExit("lawtemple_exit_portal", listOf(TempleCoord(0, 44, 52, 42, 50))),
            RuneExit("deathtemple_exit_portal", listOf(TempleCoord(0, 50, 50, 21, 18))),
        )
}
