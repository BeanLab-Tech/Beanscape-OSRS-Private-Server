package org.rsmod.content.other.beanlab.farming

/** Patch families this module farms. [prefix] matches the patch loc's internal name. */
enum class PatchKind(val prefix: String, val weedsPrefix: String) {
    Allotment("farming_veg_patch", "veg_patch_weeds"),
    Herb("farming_herb_patch", "herb_patch_weeds"),
    Flower("farming_flower_patch", "flower_patch_weeds"),
    Hops("farming_hops_patch", "hop_patch_weeds"),
}

/**
 * A crop. [stagePrefix] is the internal-name prefix of its growth locs in the patch's multiloc
 * (`<prefix>_seed`, `<prefix>_1`.., `<prefix>_fullygrown`). [harvests] is the base number of
 * harvests before the patch empties; compost adds one per level. Flowers give one harvest.
 */
data class Crop(
    val kind: PatchKind,
    val stagePrefix: String,
    val seed: String,
    val seeds: Int,
    val level: Int,
    val plantXp: Double,
    val harvestXp: Double,
    val product: String,
    val minutesPerStage: Int,
    val harvests: Int,
)

/** Varbit values for one crop in one patch layout, read from the patch loc's multiloc. */
data class CropStages(val seed: Int, val growing: List<Int>, val full: Int) {
    /** Growth steps from planted to fully grown. */
    val steps: Int
        get() = growing.size + 1
}

/** A player's patch. Empty patches have [crop] = "" and [weeds] 0 (overgrown) to 3 (clear). */
data class PatchState(
    val crop: String = "",
    val weeds: Int = 0,
    val plantedAt: Long = 0,
    val compost: Int = 0,
    val lives: Int = 0,
) {
    val empty: Boolean
        get() = crop.isEmpty()
}

object Farming {
    const val CLEAR: Int = 3
    const val RAKE_XP: Double = 4.0

    private val STAGE = Regex("""(.+)_(seed|\d+|fullygrown)""")

    /** Compost item, its level (1..3), xp for applying it. */
    val composts: List<Triple<String, Int, Double>> =
        listOf(
            Triple("bucket_compost", 1, 18.0),
            Triple("bucket_supercompost", 2, 26.0),
            Triple("bucket_ultracompost", 3, 36.0),
        )

    private fun veg(prefix: String, level: Int, plant: Double, harvest: Double, product: String) =
        Crop(
            PatchKind.Allotment,
            prefix,
            "${prefix}_seed",
            3,
            level,
            plant,
            harvest,
            product,
            10,
            3,
        )

    private fun herb(
        prefix: String,
        seed: String,
        level: Int,
        plant: Double,
        harvest: Double,
        grimy: String,
    ) = Crop(PatchKind.Herb, "herb_$prefix", seed, 1, level, plant, harvest, grimy, 20, 3)

    private fun flower(
        prefix: String,
        level: Int,
        plant: Double,
        harvest: Double,
        product: String,
    ) = Crop(PatchKind.Flower, prefix, "${prefix}_seed", 1, level, plant, harvest, product, 5, 1)

    private fun hop(
        prefix: String,
        seed: String,
        level: Int,
        plant: Double,
        harvest: Double,
        product: String,
        seeds: Int = 4,
    ) = Crop(PatchKind.Hops, prefix, seed, seeds, level, plant, harvest, product, 10, 3)

    val crops: List<Crop> =
        listOf(
            veg("potato", 1, 8.0, 9.0, "potato"),
            veg("onion", 5, 9.5, 10.5, "onion"),
            veg("cabbage", 7, 10.0, 11.5, "cabbage"),
            veg("tomato", 12, 12.5, 14.0, "tomato"),
            veg("sweetcorn", 20, 17.0, 19.0, "sweetcorn"),
            veg("strawberry", 31, 26.0, 29.0, "strawberry"),
            veg("watermelon", 47, 48.5, 54.5, "watermelon"),
            herb("guam_leaf", "guam_seed", 9, 11.0, 12.5, "unidentified_guam"),
            herb("marrentill", "marrentill_seed", 14, 13.5, 15.0, "unidentified_marentill"),
            herb("tarromin", "tarromin_seed", 19, 16.0, 18.0, "unidentified_tarromin"),
            herb("harralander", "harralander_seed", 26, 21.5, 24.0, "unidentified_harralander"),
            herb("ranarr_weed", "ranarr_seed", 32, 27.0, 30.5, "unidentified_ranarr"),
            herb("toadflax", "toadflax_seed", 38, 34.0, 38.5, "unidentified_toadflax"),
            herb("irit_leaf", "irit_seed", 44, 43.0, 48.5, "unidentified_irit"),
            herb("avantoe", "avantoe_seed", 50, 54.5, 61.5, "unidentified_avantoe"),
            herb("kwuarm", "kwuarm_seed", 56, 69.0, 78.0, "unidentified_kwuarm"),
            herb("huasca", "huasca_seed", 58, 86.0, 86.0, "unidentified_huasca"),
            herb("snapdragon", "snapdragon_seed", 62, 87.5, 98.5, "unidentified_snapdragon"),
            herb("cadantine", "cadantine_seed", 67, 106.5, 120.0, "unidentified_cadantine"),
            herb("lantadyme", "lantadyme_seed", 73, 134.5, 151.5, "unidentified_lantadyme"),
            herb("dwarf_weed", "dwarf_weed_seed", 79, 170.5, 192.0, "unidentified_dwarf_weed"),
            herb("torstol", "torstol_seed", 85, 199.5, 224.5, "unidentified_torstol"),
            flower("marigold", 2, 8.5, 47.0, "marigold"),
            flower("rosemary", 11, 12.0, 66.5, "rosemary"),
            flower("nasturtium", 24, 19.5, 111.0, "nasturtium"),
            flower("woad", 25, 20.5, 115.5, "woadleaf"),
            flower("limpwurt", 26, 21.5, 120.0, "limpwurt_root"),
            hop("barley", "barley_seed", 3, 8.5, 9.5, "barley"),
            hop("hammer_hops", "hammerstone_hop_seed", 4, 9.0, 10.0, "hammerstone_hops"),
            hop("asgarnian_hops", "asgarnian_hop_seed", 8, 10.9, 12.0, "asgarnian_hops"),
            hop("jute", "jute_seed", 13, 13.0, 14.5, "jute_fibre", seeds = 3),
            hop("yanillian_hops", "yanillian_hop_seed", 16, 14.5, 16.0, "yanillian_hops"),
            hop("krandorian_hops", "krandorian_hop_seed", 21, 17.5, 19.5, "krandorian_hops"),
            hop("wildblood_hops", "wildblood_hop_seed", 28, 23.0, 26.0, "wildblood_hops"),
        )

    /** Growth step (0 = just planted .. [CropStages.steps] = fully grown) at [nowSec]. */
    fun step(crop: Crop, stages: CropStages, plantedAt: Long, nowSec: Long): Int {
        val minutes = ((nowSec - plantedAt).coerceAtLeast(0) / 60).toInt()
        return minOf(stages.steps, minutes / crop.minutesPerStage)
    }

    /** The varbit value the client shows for [state]. */
    fun display(state: PatchState, crop: Crop?, stages: CropStages?, nowSec: Long): Int {
        if (state.empty || crop == null || stages == null) return state.weeds.coerceIn(0, CLEAR)
        val step = step(crop, stages, state.plantedAt, nowSec)
        return when {
            step == 0 -> stages.seed
            step < stages.steps -> stages.growing[step - 1]
            else -> stages.full
        }
    }

    fun grown(state: PatchState, crop: Crop, stages: CropStages, nowSec: Long): Boolean =
        !state.empty && step(crop, stages, state.plantedAt, nowSec) >= stages.steps

    /** Reads crop stages for every crop from a patch's multiloc names (index = varbit value). */
    fun parseStages(multiLocNames: List<String?>): Map<String, CropStages> {
        val seeds = HashMap<String, Int>()
        val growing = HashMap<String, MutableMap<Int, Int>>()
        val full = HashMap<String, Int>()
        for ((value, name) in multiLocNames.withIndex()) {
            val match = STAGE.matchEntire(name ?: continue) ?: continue
            val (prefix, stage) = match.destructured
            when (stage) {
                "seed" -> seeds.putIfAbsent(prefix, value)
                "fullygrown" -> full.putIfAbsent(prefix, value)
                else -> growing.getOrPut(prefix) { sortedMapOf() }.putIfAbsent(stage.toInt(), value)
            }
        }
        return seeds.keys
            .filter { it in full }
            .associateWith { prefix ->
                CropStages(
                    seed = seeds.getValue(prefix),
                    growing = growing[prefix].orEmpty().values.toList(),
                    full = full.getValue(prefix),
                )
            }
    }
}
