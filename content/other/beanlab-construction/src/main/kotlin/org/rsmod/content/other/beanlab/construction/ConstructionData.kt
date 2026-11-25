package org.rsmod.content.other.beanlab.construction

/**
 * One buildable option. [locs] maps each hotspot loc internal name in the group to the furniture
 * loc that replaces it (rugs and curtains cover several hotspot tiles at once).
 */
data class Furniture(
    val label: String,
    val level: Int,
    val xp: Double,
    val materials: List<Pair<String, Int>>,
    val locs: Map<String, String>,
)

/** Hotspots built together. [key] is stored in the database with the room name. */
data class HotspotGroup(val key: String, val hotspots: Set<String>, val options: List<Furniture>)

/** A room: the template zone it is copied from and where it sits in the house (region zones). */
data class Room(
    val name: String,
    val templateZoneX: Int,
    val templateZoneZ: Int,
    val houseZoneX: Int,
    val houseZoneZ: Int,
    val groups: List<HotspotGroup>,
)

data class PlankRecipe(val log: String, val plank: String, val cost: Int)

object Construction {
    const val PLANK = "woodplank"
    const val OAK = "plank_oak"
    const val TEAK = "plank_teak"
    const val MAHOGANY = "plank_mahogany"
    const val NAILS = "nails"
    const val CLOTH = "cloth"

    val sawmill: List<PlankRecipe> =
        listOf(
            PlankRecipe("logs", PLANK, 100),
            PlankRecipe("oak_logs", OAK, 250),
            PlankRecipe("teak_logs", TEAK, 500),
            PlankRecipe("mahogany_logs", MAHOGANY, 1500),
        )

    private fun single(
        label: String,
        level: Int,
        xp: Double,
        hotspot: String,
        loc: String,
        vararg materials: Pair<String, Int>,
    ) = Furniture(label, level, xp, materials.toList(), mapOf(hotspot to loc))

    private fun chairs(hotspot: String) =
        HotspotGroup(
            hotspot,
            setOf(hotspot),
            listOf(
                single(
                    "Crude wooden chair",
                    1,
                    58.0,
                    hotspot,
                    "poh_chair1",
                    PLANK to 2,
                    NAILS to 2,
                ),
                single("Wooden chair", 8, 87.0, hotspot, "poh_chair2", PLANK to 3, NAILS to 3),
                single("Rocking chair", 14, 87.0, hotspot, "poh_chair3", PLANK to 3, NAILS to 3),
                single("Oak chair", 19, 120.0, hotspot, "poh_chair4", OAK to 2),
                single("Oak armchair", 26, 180.0, hotspot, "poh_chair5", OAK to 3),
                single("Teak armchair", 35, 180.0, hotspot, "poh_chair6", TEAK to 2),
                single("Mahogany armchair", 50, 280.0, hotspot, "poh_chair7", MAHOGANY to 2),
            ),
        )

    private fun rug(
        label: String,
        level: Int,
        xp: Double,
        tier: Int,
        vararg materials: Pair<String, Int>,
    ) =
        Furniture(
            label,
            level,
            xp,
            materials.toList(),
            mapOf(
                "poh_parlour_4_corner" to "poh_rugcorner$tier",
                "poh_parlour_4_side" to "poh_rugside$tier",
                "poh_parlour_4_middle" to "poh_rugmiddle$tier",
            ),
        )

    val parlour =
        Room(
            name = "parlour",
            templateZoneX = 232,
            templateZoneZ = 719,
            houseZoneX = 2,
            houseZoneZ = 1,
            groups =
                listOf(
                    chairs("poh_parlour_1"),
                    chairs("poh_parlour_2"),
                    chairs("poh_parlour_3"),
                    HotspotGroup(
                        "rug",
                        setOf("poh_parlour_4_corner", "poh_parlour_4_side", "poh_parlour_4_middle"),
                        listOf(
                            rug("Brown rug", 2, 30.0, 1, CLOTH to 2),
                            rug("Rug", 13, 60.0, 2, CLOTH to 4),
                            rug("Opulent rug", 65, 360.0, 3, CLOTH to 4, "gold_leaf" to 1),
                        ),
                    ),
                    HotspotGroup(
                        "bookcase",
                        setOf("poh_parlour_5"),
                        listOf(
                            single(
                                "Wooden bookcase",
                                4,
                                115.0,
                                "poh_parlour_5",
                                "poh_bookcase1",
                                PLANK to 4,
                                NAILS to 4,
                            ),
                            single(
                                "Oak bookcase",
                                29,
                                180.0,
                                "poh_parlour_5",
                                "poh_bookcase2",
                                OAK to 3,
                            ),
                            single(
                                "Mahogany bookcase",
                                40,
                                420.0,
                                "poh_parlour_5",
                                "poh_bookcase3",
                                MAHOGANY to 3,
                            ),
                        ),
                    ),
                    HotspotGroup(
                        "fireplace",
                        setOf("poh_parlour_6"),
                        listOf(
                            single(
                                "Clay fireplace",
                                3,
                                30.0,
                                "poh_parlour_6",
                                "poh_fireplace_1",
                                "softclay" to 3,
                            ),
                            single(
                                "Stone fireplace",
                                33,
                                40.0,
                                "poh_parlour_6",
                                "poh_fireplace_2",
                                "limestonebrick" to 2,
                            ),
                            single(
                                "Marble fireplace",
                                63,
                                500.0,
                                "poh_parlour_6",
                                "poh_fireplace_3",
                                "marble_block" to 1,
                            ),
                        ),
                    ),
                    HotspotGroup(
                        "curtains",
                        setOf("poh_parlour_7"),
                        listOf(
                            single(
                                "Torn curtains",
                                2,
                                132.0,
                                "poh_parlour_7",
                                "poh_curtains_1",
                                PLANK to 3,
                                CLOTH to 3,
                                NAILS to 3,
                            ),
                            single(
                                "Curtains",
                                18,
                                225.0,
                                "poh_parlour_7",
                                "poh_curtains_2",
                                OAK to 3,
                                CLOTH to 3,
                            ),
                            single(
                                "Opulent curtains",
                                40,
                                315.0,
                                "poh_parlour_7",
                                "poh_curtains_3",
                                TEAK to 3,
                                CLOTH to 3,
                            ),
                        ),
                    ),
                ),
        )

    private fun tools(hotspot: String) =
        HotspotGroup(
            hotspot,
            setOf(hotspot),
            (1..5).map { tier ->
                single(
                    "Tool store $tier",
                    5 + tier * 10,
                    121.0,
                    hotspot,
                    "poh_tools$tier",
                    OAK to 2,
                )
            },
        )

    val workshop =
        Room(
            name = "workshop",
            templateZoneX = 232,
            templateZoneZ = 717,
            houseZoneX = 1,
            houseZoneZ = 2,
            groups =
                listOf(
                    HotspotGroup(
                        "workbench",
                        setOf("poh_workshop_1"),
                        listOf(
                            single(
                                "Wooden workbench",
                                17,
                                143.0,
                                "poh_workshop_1",
                                "poh_workbench_1",
                                PLANK to 5,
                                NAILS to 5,
                            ),
                            single(
                                "Oak workbench",
                                32,
                                300.0,
                                "poh_workshop_1",
                                "poh_workbench_2",
                                OAK to 5,
                            ),
                            single(
                                "Steel framed bench",
                                46,
                                440.0,
                                "poh_workshop_1",
                                "poh_workbench_3",
                                OAK to 6,
                                "steel_bar" to 4,
                            ),
                            single(
                                "Bench with vice",
                                62,
                                750.0,
                                "poh_workshop_1",
                                "poh_workbench_4",
                                OAK to 2,
                                "steel_bar" to 1,
                            ),
                            single(
                                "Bench with lathe",
                                77,
                                1000.0,
                                "poh_workshop_1",
                                "poh_workbench_5",
                                OAK to 2,
                                "steel_bar" to 1,
                            ),
                        ),
                    ),
                    HotspotGroup(
                        "repair",
                        setOf("poh_workshop_4"),
                        listOf(
                            single(
                                "Repair bench",
                                15,
                                120.0,
                                "poh_workshop_4",
                                "poh_repair_1",
                                OAK to 2,
                            ),
                            single(
                                "Whetstone",
                                35,
                                260.0,
                                "poh_workshop_4",
                                "poh_repair_2",
                                OAK to 4,
                                "limestonebrick" to 1,
                            ),
                            single(
                                "Armour repair stand",
                                55,
                                500.0,
                                "poh_workshop_4",
                                "poh_repair_3",
                                OAK to 8,
                                "limestonebrick" to 1,
                            ),
                        ),
                    ),
                    tools("poh_workshop_3a"),
                    tools("poh_workshop_3b"),
                    tools("poh_workshop_3c"),
                    tools("poh_workshop_3d"),
                    tools("poh_workshop_3e"),
                ),
        )

    /** The garden holds the exit portal (on its centrepiece space) and is where players arrive. */
    val garden = Room("garden", 232, 713, 1, 1, emptyList())

    val rooms: List<Room> = listOf(garden, parlour, workshop)

    const val GARDEN_CENTREPIECE: String = "poh_crude_garden_1"
    const val EXIT_PORTAL: String = "poh_exit_portal"

    /** Arrival tile inside the garden zone (local x, z). */
    const val ARRIVE_LOCAL_X: Int = 2
    const val ARRIVE_LOCAL_Z: Int = 3

    fun room(name: String): Room? = rooms.firstOrNull { it.name == name }
}
