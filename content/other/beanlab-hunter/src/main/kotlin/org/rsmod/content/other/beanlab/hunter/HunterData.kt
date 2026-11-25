package org.rsmod.content.other.beanlab.hunter

/** Item reward line: internal name, min count, max count. */
data class Reward(val obj: String, val min: Int, val max: Int = min)

/**
 * A creature caught in a laid trap. [npc] is the cache internal name of the wild creature that must
 * be within [Hunter.TRAP_RANGE] tiles; [full] is the trap loc it turns into.
 */
data class TrapCatch(
    val label: String,
    val npc: String,
    val level: Int,
    val xp: Double,
    val full: String,
    val rewards: List<Reward>,
)

/** A trap item and the locs it becomes when laid. */
data class TrapKind(
    val item: String,
    val label: String,
    val laid: String,
    val failed: String,
    val catches: List<TrapCatch>,
)

/** A creature caught with a butterfly net into a jar. */
data class NetCatch(
    val npc: String,
    val label: String,
    val level: Int,
    val xp: Double,
    val jar: String,
    val product: String,
)

object Hunter {
    private fun bird(
        label: String,
        npc: String,
        level: Int,
        xp: Double,
        full: String,
        feather: String,
    ) =
        TrapCatch(
            label,
            npc,
            level,
            xp,
            full,
            listOf(Reward("bones", 1), Reward("spit_raw_bird_meat", 1), Reward(feather, 5, 10)),
        )

    val snare =
        TrapKind(
            item = "hunting_ojibway_bird_snare",
            label = "bird snare",
            laid = "hunting_ojibway_trap",
            failed = "hunting_ojibway_trap_broken",
            catches =
                listOf(
                    bird(
                        "crimson swift",
                        "hunting_bird_jungle",
                        1,
                        34.0,
                        "hunting_ojibway_trap_full_jungle",
                        "hunting_jungle_feather",
                    ),
                    bird(
                        "golden warbler",
                        "hunting_bird_desert",
                        5,
                        47.0,
                        "hunting_ojibway_trap_full_desert",
                        "hunting_desert_feather",
                    ),
                    bird(
                        "copper longtail",
                        "hunting_bird_woodland",
                        9,
                        61.2,
                        "hunting_ojibway_trap_full_woodland",
                        "hunting_woodland_feather",
                    ),
                    bird(
                        "cerulean twitch",
                        "hunting_bird_polar",
                        11,
                        64.67,
                        "hunting_ojibway_trap_full_polar",
                        "hunting_polar_feather",
                    ),
                    bird(
                        "tropical wagtail",
                        "multicoloured_bird",
                        19,
                        95.2,
                        "hunting_ojibway_trap_full_coloured",
                        "hunting_stripy_bird_feather",
                    ),
                ),
        )

    val box =
        TrapKind(
            item = "hunting_box_trap",
            label = "box trap",
            laid = "hunting_boxtrap_empty",
            failed = "hunting_boxtrap_failed",
            catches =
                listOf(
                    TrapCatch(
                        "ferret",
                        "hunting_ferret",
                        27,
                        115.0,
                        "hunting_boxtrap_full_ferret",
                        listOf(Reward("hunting_ferret", 1)),
                    ),
                    TrapCatch(
                        "chinchompa",
                        "hunting_chinchompa",
                        53,
                        198.25,
                        "hunting_boxtrap_full_chinchompa",
                        listOf(Reward("chinchompa_captured", 1)),
                    ),
                    TrapCatch(
                        "carnivorous chinchompa",
                        "hunting_chinchompa_big",
                        63,
                        265.0,
                        "hunting_boxtrap_full_chinchompa_big",
                        listOf(Reward("chinchompa_big_captured", 1)),
                    ),
                    TrapCatch(
                        "black chinchompa",
                        "hunting_chinchompa_black",
                        73,
                        315.0,
                        "hunting_boxtrap_full_chinchompa_black",
                        listOf(Reward("chinchompa_black", 1)),
                    ),
                ),
        )

    val traps: List<TrapKind> = listOf(snare, box)

    const val IMP_JAR: String = "ii_impling_jar"
    const val BUTTERFLY_JAR: String = "butterfly_jar"

    private fun imp(n: Int, label: String, level: Int, xp: Double) =
        NetCatch("ii_impling_type_$n", label, level, xp, IMP_JAR, "ii_captured_impling_$n")

    val nets: List<NetCatch> =
        listOf(
            NetCatch(
                "butterfly_ruby",
                "ruby harvest",
                15,
                24.0,
                BUTTERFLY_JAR,
                "butterfly_jar_ruby",
            ),
            NetCatch(
                "butterfly_glacialis",
                "sapphire glacialis",
                25,
                34.0,
                BUTTERFLY_JAR,
                "butterfly_jar_glacialis",
            ),
            NetCatch(
                "butterfly_snowy",
                "snowy knight",
                35,
                44.0,
                BUTTERFLY_JAR,
                "butterfly_jar_snowy",
            ),
            NetCatch(
                "butterfly_warlock",
                "black warlock",
                45,
                54.0,
                BUTTERFLY_JAR,
                "butterfly_jar_warlock",
            ),
            imp(1, "baby impling", 17, 20.0),
            imp(2, "young impling", 22, 22.0),
            imp(3, "gourmet impling", 28, 24.0),
            imp(4, "earth impling", 36, 27.0),
            imp(5, "essence impling", 42, 29.0),
            imp(6, "eclectic impling", 50, 32.0),
            imp(7, "nature impling", 58, 45.0),
            imp(8, "magpie impling", 65, 216.0),
            imp(9, "ninja impling", 74, 240.0),
            imp(10, "dragon impling", 83, 300.0),
            imp(11, "lucky impling", 89, 380.0),
        )

    /** Impling jar -> loot table (one line, equal weights). */
    val implingLoot: Map<String, List<Reward>> =
        mapOf(
            "ii_captured_impling_1" to
                listOf(
                    Reward("cabbage", 1),
                    Reward("potato", 1),
                    Reward("wool", 1),
                    Reward("flax", 1),
                ),
            "ii_captured_impling_2" to
                listOf(Reward("steel_bar", 1), Reward("iron_dagger", 1), Reward("clay", 3)),
            "ii_captured_impling_3" to
                listOf(Reward("tuna", 1), Reward("cake", 1), Reward("curry", 1), Reward("beer", 2)),
            "ii_captured_impling_4" to
                listOf(Reward("coal", 3), Reward("iron_ore", 2), Reward("uncut_emerald", 1)),
            "ii_captured_impling_5" to
                listOf(
                    Reward("blankrune_high", 20, 30),
                    Reward("cosmicrune", 4),
                    Reward("bloodrune", 2),
                ),
            "ii_captured_impling_6" to
                listOf(
                    Reward("mithril_platebody", 1),
                    Reward("chaosrune", 10),
                    Reward("lobster", 2),
                ),
            "ii_captured_impling_7" to
                listOf(
                    Reward("ranarr_seed", 1),
                    Reward("snape_grass", 2),
                    Reward("unidentified_ranarr", 1),
                ),
            "ii_captured_impling_8" to
                listOf(
                    Reward("uncut_diamond", 2),
                    Reward("runite_bar", 1),
                    Reward("magic_logs", 3),
                ),
            "ii_captured_impling_9" to
                listOf(
                    Reward("rune_dagger", 1),
                    Reward("deathrune", 15),
                    Reward("dragon_dart_tip", 5),
                ),
            "ii_captured_impling_10" to
                listOf(
                    Reward("dragon_arrowheads", 10, 20),
                    Reward("dragon_dart_tip", 15, 30),
                    Reward("uncut_dragonstone", 1),
                ),
            "ii_captured_impling_11" to
                listOf(Reward("dragon_spear", 1), Reward("snapdragon_seed", 1), Reward("shark", 5)),
        )

    const val NET: String = "hunting_butterfly_net"

    /** Tiles within which a matching creature must be for a trap to catch it. */
    const val TRAP_RANGE: Int = 6

    /** A laid trap rolls a catch every [ROLL_TICKS]; it collapses after [COLLAPSE_TICKS]. */
    const val ROLL_TICKS: Int = 5
    const val COLLAPSE_TICKS: Int = 200

    /** How many traps a player may lay: 1 at level 1, +1 at 20, 40, 60 and 80. */
    fun maxTraps(level: Int): Int = 1 + listOf(20, 40, 60, 80).count { level >= it }
}
