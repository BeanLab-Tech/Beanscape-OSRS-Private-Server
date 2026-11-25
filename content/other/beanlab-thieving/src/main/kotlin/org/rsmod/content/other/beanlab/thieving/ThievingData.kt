package org.rsmod.content.other.beanlab.thieving

/** One loot line. [weight] is the share out of the table's total. */
data class Loot(val obj: String, val min: Int, val max: Int, val weight: Int)

/** A pickpocketable NPC group. [chanceLow] and [chanceHigh] feed the stat success roll. */
data class Pocket(
    val npcs: List<String>,
    val level: Int,
    val xp: Double,
    val stunTicks: Int,
    val stunDamage: Int,
    val chanceLow: Int,
    val chanceHigh: Int,
    val loot: List<Loot>,
    val pocket: String,
)

/** A market stall. [loc] is the cache name of the stocked stall. */
data class Stall(
    val loc: String,
    val level: Int,
    val xp: Double,
    val respawnTicks: Int,
    val loot: List<Loot>,
    val label: String,
)

object Thieving {
    /** Cache name of the emptied stall every stocked stall turns into. */
    const val EMPTY_STALL = "market"

    private val coins3 = listOf(Loot("coins", 3, 3, 128))

    val pockets =
        listOf(
            Pocket(
                listOf("man", "man2", "man3", "al_kharid_man", "ardougnian_male1"),
                1,
                8.0,
                8,
                1,
                180,
                240,
                coins3,
                "man's pocket",
            ),
            Pocket(
                listOf("woman", "woman2", "woman3", "ardougnian_female1"),
                1,
                8.0,
                8,
                1,
                180,
                240,
                coins3,
                "woman's pocket",
            ),
            Pocket(
                listOf("farmer1"),
                10,
                14.5,
                8,
                1,
                150,
                240,
                listOf(Loot("coins", 9, 9, 123)),
                "farmer's pocket",
            ),
            Pocket(
                listOf("warrior_woman", "al_kharid_warrior"),
                25,
                26.0,
                8,
                2,
                100,
                240,
                listOf(Loot("coins", 18, 18, 128)),
                "warrior's pocket",
            ),
            Pocket(
                listOf("rogue"),
                32,
                36.5,
                8,
                2,
                74,
                240,
                listOf(
                    Loot("coins", 25, 40, 108),
                    Loot("airrune", 8, 8, 8),
                    Loot("jug_wine", 1, 1, 6),
                    Loot("lockpick", 1, 1, 5),
                    Loot("iron_dagger_p", 1, 1, 1),
                ),
                "rogue's pocket",
            ),
            Pocket(
                listOf("guard1", "guard2", "ardougne_guard"),
                40,
                46.8,
                8,
                2,
                50,
                240,
                listOf(Loot("coins", 30, 30, 128)),
                "guard's pocket",
            ),
            Pocket(
                listOf("knight_of_ardougne", "knight_of_ardougne2"),
                55,
                84.3,
                8,
                3,
                50,
                240,
                listOf(Loot("coins", 50, 50, 128)),
                "knight's pocket",
            ),
            Pocket(
                listOf("yanille_watchman"),
                65,
                137.5,
                8,
                3,
                15,
                160,
                listOf(Loot("coins", 60, 60, 128), Loot("bread", 1, 1, 128)),
                "watchman's pocket",
            ),
            Pocket(
                listOf("paladin"),
                70,
                151.8,
                8,
                3,
                50,
                150,
                listOf(Loot("coins", 80, 80, 128), Loot("chaosrune", 2, 2, 128)),
                "paladin's pocket",
            ),
            Pocket(
                listOf(
                    "gnome",
                    "browclothedgnome",
                    "darkskinned_gnome",
                    "gnomechildgreen",
                    "gnomechildblue",
                    "gnomechild",
                    "gnomefemale",
                    "gnomefemale_dskinned",
                ),
                75,
                198.3,
                8,
                1,
                8,
                120,
                listOf(
                    Loot("king_worm", 1, 1, 55),
                    Loot("coins", 300, 300, 30),
                    Loot("swamp_toad", 1, 1, 28),
                    Loot("gold_ore", 1, 1, 8),
                    Loot("earthrune", 1, 1, 5),
                    Loot("fire_orb", 1, 1, 2),
                ),
                "gnome's pocket",
            ),
            Pocket(
                listOf("hero"),
                80,
                273.3,
                10,
                4,
                6,
                100,
                listOf(
                    Loot("coins", 200, 300, 105),
                    Loot("deathrune", 2, 2, 8),
                    Loot("jug_wine", 1, 1, 6),
                    Loot("bloodrune", 1, 1, 5),
                    Loot("fire_orb", 1, 1, 2),
                    Loot("diamond", 1, 1, 1),
                    Loot("gold_ore", 1, 1, 1),
                ),
                "hero's pocket",
            ),
        )

    val stalls =
        listOf(
            Stall(
                "bakerymarket",
                5,
                16.0,
                8,
                listOf(
                    Loot("cake", 1, 1, 13),
                    Loot("bread", 1, 1, 5),
                    Loot("chocolate_slice", 1, 1, 2),
                ),
                "baker's stall",
            ),
            Stall("tea_stall", 5, 16.0, 16, listOf(Loot("cup_of_tea", 1, 1, 128)), "tea stall"),
            Stall("silkmarket", 20, 24.0, 16, listOf(Loot("silk", 1, 1, 128)), "silk stall"),
            Stall("furmarket", 35, 36.0, 32, listOf(Loot("grey_wolf_fur", 1, 1, 128)), "fur stall"),
            Stall(
                "silvermarket",
                50,
                54.0,
                100,
                listOf(Loot("silver_ore", 1, 1, 128)),
                "silver stall",
            ),
            Stall(
                "spicemarket",
                65,
                81.0,
                166,
                listOf(Loot("spicespot", 1, 1, 128)),
                "spice stall",
            ),
            Stall(
                "gemmarket",
                75,
                16.0,
                600,
                listOf(
                    Loot("uncut_sapphire", 1, 1, 105),
                    Loot("uncut_emerald", 1, 1, 17),
                    Loot("uncut_ruby", 1, 1, 5),
                    Loot("uncut_diamond", 1, 1, 1),
                ),
                "gem stall",
            ),
        )
}
