package org.rsmod.content.other.beanlab.farming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FarmingTest {
    /** Allotment multiloc names from the rev 233 cache (values 0..12, plus a watered variant). */
    private val allotment =
        listOf(
            "veg_patch_weeds_3",
            "veg_patch_weeds_2",
            "veg_patch_weeds_1",
            "veg_patch_weeded",
            "veg_patch_weeds_3",
            "veg_patch_weeds_3",
            "potato_seed",
            "potato_1",
            "potato_2",
            "potato_3",
            "potato_fullygrown",
            "potato_fullygrown",
            "potato_fullygrown",
            "potato_seed_watered",
            "potato_1_diseased",
        )

    private val potato = Farming.crops.first { it.stagePrefix == "potato" }

    @Test
    fun `stages are read from multiloc names`() {
        val stages = Farming.parseStages(allotment)
        assertEquals(setOf("potato"), stages.keys)
        assertEquals(CropStages(seed = 6, growing = listOf(7, 8, 9), full = 10), stages["potato"])
    }

    @Test
    fun `display follows growth over time`() {
        val stages = Farming.parseStages(allotment).getValue("potato")
        val planted = PatchState(crop = "potato", weeds = Farming.CLEAR, plantedAt = 1_000)
        val minute = 60L
        assertEquals(6, Farming.display(planted, potato, stages, 1_000))
        assertEquals(6, Farming.display(planted, potato, stages, 1_000 + 9 * minute))
        assertEquals(7, Farming.display(planted, potato, stages, 1_000 + 10 * minute))
        assertEquals(9, Farming.display(planted, potato, stages, 1_000 + 30 * minute))
        assertFalse(Farming.grown(planted, potato, stages, 1_000 + 39 * minute))
        assertEquals(10, Farming.display(planted, potato, stages, 1_000 + 40 * minute))
        assertTrue(Farming.grown(planted, potato, stages, 1_000 + 999 * minute))
    }

    @Test
    fun `empty patches show their weeds`() {
        assertEquals(0, Farming.display(PatchState(), null, null, 0))
        assertEquals(2, Farming.display(PatchState(weeds = 2), null, null, 0))
        assertEquals(3, Farming.display(PatchState(weeds = 3), null, null, 0))
    }

    @Test
    fun `every crop has a unique stage prefix`() {
        val prefixes = Farming.crops.map { it.kind to it.stagePrefix }
        assertEquals(prefixes.size, prefixes.toSet().size)
    }
}
