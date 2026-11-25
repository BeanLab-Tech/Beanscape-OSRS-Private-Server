package org.rsmod.content.other.beanlab.crafting

/** A cut gem. [rateLow] and [rateHigh] are set for gems that can shatter. */
data class Gem(
    val uncut: String,
    val cut: String,
    val level: Int,
    val xp: Double,
    val anim: String,
    val label: String,
    val rateLow: Int = 0,
    val rateHigh: Int = 0,
)

/** One thing a player can make. Every input is consumed. */
data class Recipe(
    val label: String,
    val inputs: List<Pair<String, Int>>,
    val product: String,
    val level: Int,
    val xp: Double,
    val thread: Boolean = false,
)

/** Using [first] on [second] offers [options]. */
data class Combo(val first: String, val second: String, val options: List<Recipe>)

object Crafting {
    val gems =
        listOf(
            Gem("uncut_sapphire", "sapphire", 20, 50.0, "human_sapphirecutting", "sapphire"),
            Gem("uncut_emerald", "emerald", 27, 67.5, "human_emeraldcutting", "emerald"),
            Gem("uncut_ruby", "ruby", 34, 85.0, "human_rubycutting", "ruby"),
            Gem("uncut_diamond", "diamond", 43, 107.5, "human_diamondcutting", "diamond"),
            Gem(
                "uncut_dragonstone",
                "dragonstone",
                55,
                137.5,
                "human_dragonstonecutting",
                "dragonstone",
            ),
            Gem("uncut_opal", "opal", 1, 15.0, "human_opalcutting", "opal", 128, 250),
            Gem("uncut_jade", "jade", 13, 20.0, "human_jadecutting", "jade", 90, 240),
            Gem(
                "uncut_red_topaz",
                "red_topaz",
                16,
                25.0,
                "human_redtopazcutting",
                "red topaz",
                100,
                245,
            ),
        )

    private fun leather(
        label: String,
        level: Int,
        xp: Double,
        product: String,
        hide: String = "leather",
        count: Int = 1,
    ) = Recipe(label, listOf(hide to count), product, level, xp, thread = true)

    private fun dhide(
        color: String,
        hide: String,
        level: Int,
        body: String,
        vambs: String,
        chaps: String,
    ) =
        Combo(
            "needle",
            hide,
            listOf(
                leather("$color dragonhide body", level + 6, bodyXp(level), body, hide, 3),
                leather("$color dragonhide vambraces", level, vambXp(level), vambs, hide, 1),
                leather("$color dragonhide chaps", level + 3, chapsXp(level), chaps, hide, 2),
            ),
        )

    private fun vambXp(level: Int): Double =
        when (level) {
            57 -> 62.0
            66 -> 70.0
            73 -> 78.0
            else -> 86.0
        }

    private fun chapsXp(level: Int): Double = vambXp(level) * 2

    private fun bodyXp(level: Int): Double = vambXp(level) * 3

    val combos =
        listOf(
            Combo(
                "needle",
                "leather",
                listOf(
                    leather("leather gloves", 1, 13.8, "leather_gloves"),
                    leather("leather boots", 7, 16.3, "leather_boots"),
                    leather("leather cowl", 9, 18.5, "leather_cowl"),
                    leather("leather vambraces", 11, 22.0, "leather_vambraces"),
                    leather("leather body", 14, 25.0, "leather_armour"),
                    leather("leather chaps", 18, 27.0, "leather_chaps"),
                    leather("coif", 38, 37.0, "coif"),
                ),
            ),
            Combo(
                "needle",
                "hard_leather",
                listOf(leather("hard leather body", 28, 35.0, "hardleather_body", "hard_leather")),
            ),
            dhide(
                "green",
                "dragon_leather",
                57,
                "dragonhide_body",
                "dragon_vambraces",
                "dragonhide_chaps",
            ),
            dhide(
                "blue",
                "dragon_leather_blue",
                66,
                "blue_dragonhide_body",
                "blue_dragon_vambraces",
                "blue_dragonhide_chaps",
            ),
            dhide(
                "red",
                "dragon_leather_red",
                73,
                "red_dragonhide_body",
                "red_dragon_vambraces",
                "red_dragonhide_chaps",
            ),
            dhide(
                "black",
                "dragon_leather_black",
                79,
                "black_dragonhide_body",
                "black_dragon_vambraces",
                "black_dragonhide_chaps",
            ),
            Combo(
                "studs",
                "leather_armour",
                listOf(
                    Recipe(
                        "studded body",
                        listOf("leather_armour" to 1, "studs" to 1),
                        "studded_body",
                        41,
                        40.0,
                    )
                ),
            ),
            Combo(
                "studs",
                "leather_chaps",
                listOf(
                    Recipe(
                        "studded chaps",
                        listOf("leather_chaps" to 1, "studs" to 1),
                        "studded_chaps",
                        44,
                        42.0,
                    )
                ),
            ),
            Combo("battlestaff", "water_orb", listOf(staff("water", 54, 100.0))),
            Combo("battlestaff", "earth_orb", listOf(staff("earth", 58, 112.5))),
            Combo("battlestaff", "fire_orb", listOf(staff("fire", 62, 125.0))),
            Combo("battlestaff", "air_orb", listOf(staff("air", 66, 137.5))),
            Combo(
                "glassblowingpipe",
                "molten_glass",
                listOf(
                    Recipe("beer glass", listOf("molten_glass" to 1), "beer_glass", 1, 17.5),
                    Recipe("vial", listOf("molten_glass" to 1), "vial_empty", 33, 35.0),
                    Recipe("orb", listOf("molten_glass" to 1), "stafforb", 46, 52.5),
                ),
            ),
        )

    private fun staff(element: String, level: Int, xp: Double) =
        Recipe(
            "$element battlestaff",
            listOf("battlestaff" to 1, "${element}_orb" to 1),
            "${element}_battlestaff",
            level,
            xp,
        )

    /** Clay plus a filled container makes soft clay and leaves the empty container. */
    val waterSources =
        listOf(
            "bowl_water" to "bowl_empty",
            "bucket_water" to "bucket_empty",
            "jug_water" to "jug_empty",
        )

    /** Soft clay on a potter's wheel. */
    val pottery =
        listOf(
            Recipe("pot", listOf("softclay" to 1), "pot_unfired", 1, 6.3),
            Recipe("pie dish", listOf("softclay" to 1), "piedish_unfired", 7, 15.0),
            Recipe("bowl", listOf("softclay" to 1), "bowl_unfired", 8, 18.0),
        )

    /** Unfired item, fired item, experience on success. */
    val firing =
        listOf(
            Triple("pot_unfired", "pot_empty", 6.3),
            Triple("piedish_unfired", "piedish", 10.0),
            Triple("bowl_unfired", "bowl_empty", 15.0),
        )

    /** Wheel ingredient, product, level, experience. */
    val spinning =
        listOf(Triple("wool", "ball_of_wool", 1) to 2.5, Triple("flax", "bow_string", 10) to 15.0)
}
