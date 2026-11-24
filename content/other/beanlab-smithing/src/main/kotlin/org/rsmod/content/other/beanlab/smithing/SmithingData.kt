package org.rsmod.content.other.beanlab.smithing

/**
 * Mining and smithing data (OSRS levels/XP). Items are looked up by their in-game name at startup,
 * so this file never needs obj ids.
 */

/**
 * @param rockName in-game loc name, e.g. "Copper rocks"
 * @param low success chance (out of 256) at level 1 with the slowest pickaxe
 * @param high success chance (out of 256) at level 99
 * @param respawnTicks how long the depleted rock stays empty
 */
data class Ore(
    val rockName: String,
    val level: Int,
    val xp: Double,
    val oreName: String,
    val respawnTicks: Int,
    val low: Int,
    val high: Int,
)

object Ores {
    val all =
        listOf(
            Ore("Clay rocks", 1, 5.0, "Clay", 2, 100, 255),
            Ore("Copper rocks", 1, 17.5, "Copper ore", 4, 100, 255),
            Ore("Tin rocks", 1, 17.5, "Tin ore", 4, 100, 255),
            Ore("Iron rocks", 15, 35.0, "Iron ore", 9, 60, 200),
            Ore("Silver rocks", 20, 40.0, "Silver ore", 100, 25, 150),
            Ore("Coal rocks", 30, 50.0, "Coal", 50, 16, 100),
            Ore("Gold rocks", 40, 65.0, "Gold ore", 100, 8, 75),
            Ore("Mithril rocks", 55, 80.0, "Mithril ore", 200, 4, 50),
            Ore("Adamantite rocks", 70, 95.0, "Adamantite ore", 400, 2, 25),
            Ore("Runite rocks", 85, 125.0, "Runite ore", 1200, 1, 18),
            Ore("Limestone rock", 10, 26.5, "Limestone", 15, 60, 200),
            Ore("Soft clay rocks", 70, 5.0, "Soft clay", 2, 30, 200),
            Ore("Amethyst crystals", 92, 240.0, "Amethyst", 125, 1, 40),
        )

    /** Newer areas name the same rocks as ore veins. */
    private val aliases =
        mapOf(
            "copper ore vein" to "copper rocks",
            "tin ore vein" to "tin rocks",
            "clay ore vein" to "clay rocks",
            "iron ore vein" to "iron rocks",
            "coal ore vein" to "coal rocks",
            "gold vein" to "gold rocks",
            "mithril ore vein" to "mithril rocks",
            "adamant ore vein" to "adamantite rocks",
            "runite ore vein" to "runite rocks",
        )

    val byRockName: Map<String, Ore> =
        all.associateBy { it.rockName.lowercase() } +
            aliases.mapValues { (_, base) -> all.first { it.rockName.lowercase() == base } }

    /**
     * Standard ore rocks and their depleted variant (Jagex names `<ore>rock1/2` -> `rocks1/2`).
     * Rocks not listed here (region variants) disappear while depleted instead.
     */
    val depleted: Map<Int, Int> =
        mapOf(
            10943 to 11390,
            11161 to 11391, // copper
            11360 to 11390,
            11361 to 11391, // tin
            11362 to 11390,
            11363 to 11391, // clay
            11364 to 11390,
            11365 to 11391, // iron
            11366 to 11390,
            11367 to 11391, // coal
            11368 to 11390,
            11369 to 11391, // silver
            11370 to 11390,
            11371 to 11391, // gold
            11372 to 11390,
            11373 to 11391, // mithril
            11374 to 11390,
            11375 to 11391, // adamantite
            11376 to 11390,
            11377 to 11391, // runite
        )
}

/**
 * @param interval ticks between mining attempts
 * @param bonus multiplier applied to the ore's success chance
 * @param anim mining animation (seq id)
 */
data class Pickaxe(
    val name: String,
    val level: Int,
    val interval: Int,
    val bonus: Double,
    val anim: Int,
)

object Pickaxes {
    /** Best first. */
    val all =
        listOf(
            Pickaxe("Crystal pickaxe", 71, 3, 1.8, 8347),
            Pickaxe("3rd age pickaxe", 61, 3, 1.7, 7283),
            Pickaxe("Infernal pickaxe", 61, 3, 1.7, 4482),
            Pickaxe("Dragon pickaxe", 61, 3, 1.7, 7139),
            Pickaxe("Gilded pickaxe", 41, 3, 1.5, 8313),
            Pickaxe("Rune pickaxe", 41, 3, 1.5, 624),
            Pickaxe("Adamant pickaxe", 31, 4, 1.35, 628),
            Pickaxe("Mithril pickaxe", 21, 5, 1.25, 629),
            Pickaxe("Black pickaxe", 11, 5, 1.15, 3873),
            Pickaxe("Steel pickaxe", 6, 6, 1.1, 627),
            Pickaxe("Iron pickaxe", 1, 7, 1.05, 626),
            Pickaxe("Bronze pickaxe", 1, 8, 1.0, 625),
        )
}

/** @param coal extra coal per bar; [successPercent] < 100 means the bar can fail (iron). */
data class Bar(
    val name: String,
    val level: Int,
    val xp: Double,
    val ores: List<Pair<String, Int>>,
    val successPercent: Int = 100,
)

object Bars {
    val all =
        listOf(
            Bar("Bronze bar", 1, 6.2, listOf("Copper ore" to 1, "Tin ore" to 1)),
            Bar("Iron bar", 15, 12.5, listOf("Iron ore" to 1), successPercent = 50),
            Bar("Silver bar", 20, 13.7, listOf("Silver ore" to 1)),
            Bar("Steel bar", 30, 17.5, listOf("Iron ore" to 1, "Coal" to 2)),
            Bar("Gold bar", 40, 22.5, listOf("Gold ore" to 1)),
            Bar("Mithril bar", 50, 30.0, listOf("Mithril ore" to 1, "Coal" to 4)),
            Bar("Adamantite bar", 70, 37.5, listOf("Adamantite ore" to 1, "Coal" to 6)),
            Bar("Runite bar", 85, 50.0, listOf("Runite ore" to 1, "Coal" to 8)),
        )
    val ores: Set<String> = all.flatMap { b -> b.ores.map { it.first } }.toSet()
}

/** A smithable item: [suffix] is appended to the metal prefix, e.g. "Bronze" + " dagger". */
data class SmithItem(val suffix: String, val levelOffset: Int, val bars: Int, val amount: Int = 1)

/** @param prefix item-name prefix ("Adamant", not "Adamantite"); [xpPerBar] OSRS smithing xp. */
data class Metal(val bar: String, val prefix: String, val level: Int, val xpPerBar: Double)

object Smithing {
    val metals =
        listOf(
            Metal("Bronze bar", "Bronze", 1, 12.5),
            Metal("Iron bar", "Iron", 15, 25.0),
            Metal("Steel bar", "Steel", 30, 37.5),
            Metal("Mithril bar", "Mithril", 50, 50.0),
            Metal("Adamantite bar", "Adamant", 70, 62.5),
            Metal("Runite bar", "Rune", 85, 75.0),
        )
    val items =
        listOf(
            SmithItem(" dagger", 0, 1),
            SmithItem(" axe", 1, 1),
            SmithItem(" mace", 2, 1),
            SmithItem(" med helm", 3, 1),
            SmithItem(" bolts (unf)", 3, 1, 10),
            SmithItem(" sword", 4, 1),
            SmithItem(" dart tip", 4, 1, 10),
            SmithItem(" nails", 4, 1, 15),
            SmithItem(" arrowtips", 5, 1, 15),
            SmithItem(" scimitar", 5, 2),
            SmithItem(" longsword", 6, 2),
            SmithItem(" full helm", 7, 2),
            SmithItem(" knife", 7, 1, 5),
            SmithItem(" sq shield", 8, 2),
            SmithItem(" warhammer", 9, 3),
            SmithItem(" battleaxe", 10, 3),
            SmithItem(" chainbody", 11, 3),
            SmithItem(" kiteshield", 12, 3),
            SmithItem(" 2h sword", 14, 3),
            SmithItem(" platelegs", 16, 3),
            SmithItem(" plateskirt", 16, 3),
            SmithItem(" platebody", 18, 5),
        )
}
