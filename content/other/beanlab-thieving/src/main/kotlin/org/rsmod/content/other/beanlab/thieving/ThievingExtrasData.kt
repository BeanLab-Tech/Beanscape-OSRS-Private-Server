package org.rsmod.content.other.beanlab.thieving

/**
 * A chest with "Search for traps". [loc] is the cache internal name; [empty] is what it turns into
 * for [respawnTicks] after a successful search. Every [loot] line is given.
 */
data class TrapChest(
    val loc: String,
    val label: String,
    val level: Int,
    val xp: Double,
    val loot: List<Triple<String, Int, Int>>,
    val respawnTicks: Int,
    val empty: String = "emptytrapchest",
    val trapDamage: Int = 3,
)

/** A locked door or gate with "Pick-lock". [lockpick] doors need a lockpick in the inventory. */
data class LockedDoor(val loc: String, val level: Int, val xp: Double, val lockpick: Boolean)

object ThievingExtras {
    val chests =
        listOf(
            TrapChest("trapchest1", "10 coin chest", 13, 7.8, listOf(t("coins", 10)), 12),
            TrapChest(
                "trapchest2",
                "nature rune chest",
                28,
                25.0,
                listOf(t("coins", 3), t("naturerune", 1)),
                15,
            ),
            TrapChest("trapchest3", "50 coin chest", 43, 125.0, listOf(t("coins", 50)), 85),
            TrapChest(
                "trapchest4",
                "steel arrowtip chest",
                47,
                150.0,
                listOf(t("steel_arrowheads", 5)),
                350,
            ),
            TrapChest(
                "trapchest5",
                "blood rune chest",
                59,
                250.0,
                listOf(t("coins", 500), t("bloodrune", 2)),
                225,
                trapDamage = 6,
            ),
            TrapChest(
                "pickchest3",
                "paladin chest",
                72,
                500.0,
                listOf(
                    t("coins", 1000),
                    t("raw_shark", 1),
                    t("adamantite_ore", 1),
                    t("uncut_sapphire", 1),
                ),
                400,
                empty = "emptypickchest",
                trapDamage = 8,
            ),
        )

    /**
     * Ardougne-era lockpicking doors, ordered as the cache numbers them. Levels follow the wiki's
     * locked-door table in the same order; the "toollock" doors need a lockpick.
     */
    val doors =
        listOf(
            LockedDoor("picklock1", 1, 3.8, lockpick = false),
            LockedDoor("picklock2", 16, 15.0, lockpick = false),
            LockedDoor("picklock3_l", 23, 25.0, lockpick = false),
            LockedDoor("picklock3_r", 23, 25.0, lockpick = false),
            LockedDoor("picklock4", 39, 35.0, lockpick = false),
            LockedDoor("picklock5", 46, 37.5, lockpick = false),
            LockedDoor("picklock6", 61, 50.0, lockpick = false),
            LockedDoor("toollock2", 82, 50.0, lockpick = true),
            LockedDoor("toollock3", 82, 50.0, lockpick = true),
            LockedDoor("toollock5", 82, 50.0, lockpick = true),
        )

    /** Npc display names that watch market stalls. Guards attack; everyone else only shouts. */
    val stallWatchers =
        setOf(
            "guard",
            "knight of ardougne",
            "baker",
            "silk merchant",
            "fur trader",
            "gem merchant",
            "silver merchant",
            "spice seller",
        )

    val guards = setOf("guard", "knight of ardougne")

    /** Tiles within which a watcher notices a theft (and must have line of sight). */
    const val WATCH_RANGE: Int = 5

    /** 1 in [NOTICE_CHANCE] thefts in view of a watcher are noticed. */
    const val NOTICE_CHANCE: Int = 3

    private fun t(obj: String, count: Int) = Triple(obj, count, count)
}
