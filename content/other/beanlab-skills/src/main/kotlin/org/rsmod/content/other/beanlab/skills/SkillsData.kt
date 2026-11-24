package org.rsmod.content.other.beanlab.skills

/** Prayer, firemaking and cooking data (OSRS values), keyed by in-game item name. */

/** Prayer XP for burying bones (option "Bury"). Unlisted bones give [DEFAULT_BONE_XP]. */
object Bones {
    const val DEFAULT_BONE_XP = 4.5
    val xp: Map<String, Double> =
        mapOf(
            "bones" to 4.5,
            "wolf bones" to 4.5,
            "burnt bones" to 4.5,
            "monkey bones" to 5.0,
            "bat bones" to 5.3,
            "big bones" to 15.0,
            "jogre bones" to 15.0,
            "zogre bones" to 22.5,
            "shaikahan bones" to 25.0,
            "babydragon bones" to 30.0,
            "wyrm bones" to 50.0,
            "wyvern bones" to 72.0,
            "dragon bones" to 72.0,
            "drake bones" to 80.0,
            "fayrg bones" to 84.0,
            "lava dragon bones" to 85.0,
            "raurg bones" to 96.0,
            "hydra bones" to 110.0,
            "dagannoth bones" to 125.0,
            "ourg bones" to 140.0,
            "superior dragon bones" to 150.0,
        )

    /** Demonic ashes (option "Scatter"). */
    val ashesXp: Map<String, Double> =
        mapOf(
            "fiendish ashes" to 10.0,
            "vile ashes" to 25.0,
            "malicious ashes" to 65.0,
            "abyssal ashes" to 85.0,
            "infernal ashes" to 110.0,
        )
}

data class Log(val name: String, val level: Int, val xp: Double)

object Logs {
    val all =
        listOf(
            Log("Logs", 1, 40.0),
            Log("Achey tree logs", 1, 40.0),
            Log("Oak logs", 15, 60.0),
            Log("Willow logs", 30, 90.0),
            Log("Teak logs", 35, 105.0),
            Log("Arctic pine logs", 42, 125.0),
            Log("Maple logs", 45, 135.0),
            Log("Mahogany logs", 50, 157.5),
            Log("Yew logs", 60, 202.5),
            Log("Blisterwood logs", 62, 96.0),
            Log("Magic logs", 75, 303.8),
            Log("Redwood logs", 90, 350.0),
        )
}

/**
 * @param stopBurn level at which this food no longer burns
 * @param burnt name of the burnt item ("Burnt fish", "Burnt meat", ...)
 */
data class Cookable(
    val raw: String,
    val cooked: String,
    val level: Int,
    val xp: Double,
    val stopBurn: Int,
    val burnt: String,
)

object Cookables {
    val all =
        listOf(
            Cookable("Raw shrimps", "Shrimps", 1, 30.0, 34, "Burnt shrimp"),
            Cookable("Raw anchovies", "Anchovies", 1, 30.0, 34, "Burnt fish"),
            Cookable("Raw chicken", "Cooked chicken", 1, 30.0, 34, "Burnt chicken"),
            Cookable("Raw beef", "Cooked meat", 1, 30.0, 34, "Burnt meat"),
            Cookable("Raw rat meat", "Cooked meat", 1, 30.0, 34, "Burnt meat"),
            Cookable("Raw bear meat", "Cooked meat", 1, 30.0, 34, "Burnt meat"),
            Cookable("Raw sardine", "Sardine", 1, 40.0, 38, "Burnt fish"),
            Cookable("Raw herring", "Herring", 5, 50.0, 41, "Burnt fish"),
            Cookable("Raw mackerel", "Mackerel", 10, 60.0, 45, "Burnt fish"),
            Cookable("Raw trout", "Trout", 15, 70.0, 49, "Burnt fish"),
            Cookable("Raw cod", "Cod", 18, 75.0, 51, "Burnt fish"),
            Cookable("Raw pike", "Pike", 20, 80.0, 54, "Burnt fish"),
            Cookable("Raw salmon", "Salmon", 25, 90.0, 58, "Burnt fish"),
            Cookable("Raw tuna", "Tuna", 30, 100.0, 64, "Burnt fish"),
            Cookable("Raw karambwan", "Cooked karambwan", 30, 190.0, 99, "Burnt karambwan"),
            Cookable("Raw rainbow fish", "Rainbow fish", 35, 110.0, 64, "Burnt rainbow fish"),
            Cookable("Raw cave eel", "Cave eel", 38, 115.0, 74, "Burnt cave eel"),
            Cookable("Raw lobster", "Lobster", 40, 120.0, 74, "Burnt lobster"),
            Cookable("Raw bass", "Bass", 43, 130.0, 80, "Burnt fish"),
            Cookable("Raw swordfish", "Swordfish", 45, 140.0, 86, "Burnt swordfish"),
            Cookable("Raw monkfish", "Monkfish", 62, 150.0, 92, "Burnt monkfish"),
            Cookable("Raw shark", "Shark", 80, 210.0, 99, "Burnt shark"),
            Cookable("Raw sea turtle", "Sea turtle", 82, 211.3, 99, "Burnt sea turtle"),
            Cookable("Raw anglerfish", "Anglerfish", 84, 230.0, 99, "Burnt anglerfish"),
            Cookable("Raw dark crab", "Dark crab", 90, 215.0, 99, "Burnt dark crab"),
            Cookable("Raw manta ray", "Manta ray", 91, 216.3, 99, "Burnt manta ray"),
        )

    /** Loc names that can cook (use raw food on them). */
    val cookingLocNames = setOf("fire", "range", "cooking range", "clay oven", "stove")
}
