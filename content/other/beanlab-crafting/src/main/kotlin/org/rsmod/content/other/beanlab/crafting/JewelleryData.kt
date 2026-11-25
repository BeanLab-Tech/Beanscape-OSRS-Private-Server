package org.rsmod.content.other.beanlab.crafting

/**
 * One furnace product: [bar] plus an optional [gem], shaped by [mould] (kept). Names are cache
 * internal names.
 */
data class Jewel(
    val label: String,
    val bar: String,
    val mould: String,
    val gem: String?,
    val product: String,
    val level: Int,
    val xp: Double,
)

object Jewellery {
    private const val GOLD = "gold_bar"
    private const val SILVER = "silver_bar"

    /** Gem internal name, display label, then ring/necklace/amulet/bracelet (level, xp) pairs. */
    private data class GemRow(
        val gem: String?,
        val label: String,
        val ring: Pair<Int, Double>,
        val necklace: Pair<Int, Double>,
        val amulet: Pair<Int, Double>,
        val bracelet: Pair<Int, Double>,
        val products: List<String>,
    )

    private val goldRows =
        listOf(
            GemRow(
                null,
                "gold",
                5 to 15.0,
                6 to 20.0,
                8 to 30.0,
                7 to 25.0,
                listOf("gold_ring", "gold_necklace", "unstrung_gold_amulet", "jewl_gold_bracelet"),
            ),
            GemRow(
                "sapphire",
                "sapphire",
                20 to 40.0,
                22 to 55.0,
                24 to 65.0,
                23 to 60.0,
                listOf(
                    "sapphire_ring",
                    "sapphire_necklace",
                    "unstrung_sapphire_amulet",
                    "jewl_sapphire_bracelet",
                ),
            ),
            GemRow(
                "emerald",
                "emerald",
                27 to 55.0,
                29 to 60.0,
                31 to 70.0,
                30 to 65.0,
                listOf(
                    "emerald_ring",
                    "emerald_necklace",
                    "unstrung_emerald_amulet",
                    "jewl_emerald_bracelet",
                ),
            ),
            GemRow(
                "ruby",
                "ruby",
                34 to 70.0,
                40 to 75.0,
                50 to 85.0,
                42 to 80.0,
                listOf("ruby_ring", "ruby_necklace", "unstrung_ruby_amulet", "jewl_ruby_bracelet"),
            ),
            GemRow(
                "diamond",
                "diamond",
                43 to 85.0,
                56 to 90.0,
                70 to 100.0,
                58 to 95.0,
                listOf(
                    "diamond_ring",
                    "diamond_necklace",
                    "unstrung_diamond_amulet",
                    "jewl_diamond_bracelet",
                ),
            ),
            GemRow(
                "dragonstone",
                "dragonstone",
                55 to 100.0,
                72 to 105.0,
                80 to 150.0,
                74 to 110.0,
                listOf(
                    "dragonstone_ring",
                    "dragonstone_necklace",
                    "unstrung_dragonstone_amulet",
                    "jewl_dragonstone_bracelet",
                ),
            ),
            GemRow(
                "onyx",
                "onyx",
                67 to 115.0,
                82 to 120.0,
                90 to 165.0,
                84 to 125.0,
                listOf("onyx_ring", "onyx_necklace", "unstrung_onyx_amulet", "jewl_onyx_bracelet"),
            ),
            GemRow(
                "zenyte",
                "zenyte",
                89 to 150.0,
                92 to 165.0,
                98 to 200.0,
                95 to 180.0,
                listOf(
                    "zenyte_ring",
                    "zenyte_necklace",
                    "unstrung_zenyte_amulet",
                    "zenyte_bracelet",
                ),
            ),
        )

    private val silverRows =
        listOf(
            GemRow(
                "opal",
                "opal",
                1 to 10.0,
                16 to 35.0,
                27 to 55.0,
                22 to 45.0,
                listOf("opal_ring", "opal_necklace", "unstrung_opal_amulet", "opal_bracelet"),
            ),
            GemRow(
                "jade",
                "jade",
                13 to 32.0,
                25 to 54.0,
                34 to 70.0,
                29 to 60.0,
                listOf("jade_ring", "jade_necklace", "unstrung_jade_amulet", "jade_bracelet"),
            ),
            GemRow(
                "red_topaz",
                "topaz",
                16 to 35.0,
                32 to 70.0,
                45 to 80.0,
                38 to 75.0,
                listOf("topaz_ring", "topaz_necklace", "unstrung_topaz_amulet", "topaz_bracelet"),
            ),
        )

    private val moulds =
        listOf(
            "ring_mould" to "ring",
            "necklace_mould" to "necklace",
            "amulet_mould" to "amulet",
            "jewl_bracelet_mould" to "bracelet",
        )

    private fun expand(bar: String, rows: List<GemRow>): List<Jewel> =
        rows.flatMap { row ->
            val stats = listOf(row.ring, row.necklace, row.amulet, row.bracelet)
            moulds.mapIndexed { i, (mould, kind) ->
                Jewel(
                    label = "${row.label} $kind",
                    bar = bar,
                    mould = mould,
                    gem = row.gem,
                    product = row.products[i],
                    level = stats[i].first,
                    xp = stats[i].second,
                )
            }
        }

    /** Plain silver items, no gem. */
    private val silverware =
        listOf(
            Jewel("unstrung symbol", SILVER, "holy_symbol_mould", null, "nostringstar", 16, 50.0),
            Jewel(
                "unstrung emblem",
                SILVER,
                "unholy_symbol_mould",
                null,
                "nostringsnake",
                17,
                50.0,
            ),
            Jewel("silver sickle", SILVER, "sickle_mould", null, "silver_sickle", 18, 50.0),
            Jewel("tiara", SILVER, "tiara_mould", null, "tiara", 23, 52.5),
        )

    val all: List<Jewel> = expand(GOLD, goldRows) + expand(SILVER, silverRows) + silverware

    val bars: List<String> = listOf(GOLD, SILVER)

    /** Unstrung item to strung item, using a ball of wool. 4 xp each. */
    val stringing: List<Pair<String, String>> =
        listOf(
            "unstrung_gold_amulet" to "strung_gold_amulet",
            "unstrung_sapphire_amulet" to "strung_sapphire_amulet",
            "unstrung_emerald_amulet" to "strung_emerald_amulet",
            "unstrung_ruby_amulet" to "strung_ruby_amulet",
            "unstrung_diamond_amulet" to "strung_diamond_amulet",
            "unstrung_dragonstone_amulet" to "strung_dragonstone_amulet",
            "unstrung_onyx_amulet" to "strung_onyx_amulet",
            "unstrung_zenyte_amulet" to "zenyte_amulet",
            "unstrung_opal_amulet" to "strung_opal_amulet",
            "unstrung_jade_amulet" to "strung_jade_amulet",
            "unstrung_topaz_amulet" to "strung_topaz_amulet",
            "nostringstar" to "stringstar",
            "nostringsnake" to "stringsnake",
        )

    const val STRING_XP: Double = 4.0

    /** Dye internal name to the cape it produces. Any of these capes can be re-dyed. */
    val dyes: List<Pair<String, String>> =
        listOf(
            "reddye" to "red_cape",
            "yellowdye" to "yellow_cape",
            "bluedye" to "blue_cape",
            "orangedye" to "orange_cape",
            "greendye" to "green_cape",
            "purpledye" to "purple_cape",
        )

    val capes: List<String> =
        listOf(
            "red_cape",
            "black_cape",
            "blue_cape",
            "yellow_cape",
            "green_cape",
            "purple_cape",
            "orange_cape",
        )
}
