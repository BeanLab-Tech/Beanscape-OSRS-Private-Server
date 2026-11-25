package org.rsmod.content.other.beanlab.fletching

/** One bow type: a log that can be cut into shafts, a short bow, or a long bow. */
data class LogCut(
    val log: String,
    val shafts: Int,
    val shortUnstrung: String,
    val shortLevel: Int,
    val shortXp: Double,
    val longUnstrung: String,
    val longLevel: Int,
    val longXp: Double,
)

/** An unstrung bow plus a bow string makes the finished bow. */
data class Stringing(val unstrung: String, val bow: String, val level: Int, val xp: Double)

/** A batch recipe: tips plus a partner item, up to [max] at a time. */
data class Batch(
    val tips: String,
    val partner: String,
    val product: String,
    val max: Int,
    val level: Int,
    val xp: Double,
    val noun: String,
)

object Fletching {
    val logs =
        listOf(
            LogCut("logs", 15, "unstrung_shortbow", 5, 5.0, "unstrung_longbow", 10, 10.0),
            LogCut(
                "oak_logs",
                0,
                "unstrung_oak_shortbow",
                20,
                16.5,
                "unstrung_oak_longbow",
                25,
                25.0,
            ),
            LogCut(
                "willow_logs",
                0,
                "unstrung_willow_shortbow",
                35,
                33.3,
                "unstrung_willow_longbow",
                40,
                41.5,
            ),
            LogCut(
                "maple_logs",
                0,
                "unstrung_maple_shortbow",
                50,
                50.0,
                "unstrung_maple_longbow",
                55,
                58.3,
            ),
            LogCut(
                "yew_logs",
                0,
                "unstrung_yew_shortbow",
                65,
                67.5,
                "unstrung_yew_longbow",
                70,
                75.0,
            ),
            LogCut(
                "magic_logs",
                0,
                "unstrung_magic_shortbow",
                80,
                83.3,
                "unstrung_magic_longbow",
                85,
                91.5,
            ),
        )

    val stringing =
        listOf(
            Stringing("unstrung_shortbow", "shortbow", 5, 5.0),
            Stringing("unstrung_longbow", "longbow", 10, 10.0),
            Stringing("unstrung_oak_shortbow", "oak_shortbow", 20, 16.5),
            Stringing("unstrung_oak_longbow", "oak_longbow", 25, 25.0),
            Stringing("unstrung_willow_shortbow", "willow_shortbow", 35, 33.2),
            Stringing("unstrung_willow_longbow", "willow_longbow", 40, 41.5),
            Stringing("unstrung_maple_shortbow", "maple_shortbow", 50, 50.0),
            Stringing("unstrung_maple_longbow", "maple_longbow", 55, 58.2),
            Stringing("unstrung_yew_shortbow", "yew_shortbow", 65, 67.5),
            Stringing("unstrung_yew_longbow", "yew_longbow", 70, 75.0),
            Stringing("unstrung_magic_shortbow", "magic_shortbow", 80, 83.2),
            Stringing("unstrung_magic_longbow", "magic_longbow", 85, 91.5),
        )

    /** Arrowheads go on headless arrows. */
    val arrows =
        listOf(
            Batch("bronze_arrowheads", "headless_arrow", "bronze_arrow", 15, 1, 1.3, "arrows"),
            Batch("iron_arrowheads", "headless_arrow", "iron_arrow", 15, 15, 2.5, "arrows"),
            Batch("steel_arrowheads", "headless_arrow", "steel_arrow", 15, 30, 5.0, "arrows"),
            Batch("mithril_arrowheads", "headless_arrow", "mithril_arrow", 15, 45, 7.5, "arrows"),
            Batch("adamant_arrowheads", "headless_arrow", "adamant_arrow", 15, 60, 10.0, "arrows"),
            Batch("rune_arrowheads", "headless_arrow", "rune_arrow", 15, 75, 12.5, "arrows"),
        )

    /** Dart tips take a feather. */
    val darts =
        listOf(
            Batch("bronze_dart_tip", "feather", "bronze_dart", 10, 1, 1.8, "darts"),
            Batch("iron_dart_tip", "feather", "iron_dart", 10, 22, 3.8, "darts"),
            Batch("steel_dart_tip", "feather", "steel_dart", 10, 37, 7.5, "darts"),
            Batch("mithril_dart_tip", "feather", "mithril_dart", 10, 52, 11.2, "darts"),
            Batch("adamant_dart_tip", "feather", "adamant_dart", 10, 67, 15.0, "darts"),
            Batch("rune_dart_tip", "feather", "rune_dart", 10, 81, 18.8, "darts"),
        )
}
