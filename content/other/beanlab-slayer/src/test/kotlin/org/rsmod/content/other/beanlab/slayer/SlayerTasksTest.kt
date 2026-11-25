package org.rsmod.content.other.beanlab.slayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlayerTasksTest {
    private val goblins = SlayerTask(2, "goblins", 0, 1, needsQuest = false)
    private val crawlers = SlayerTask(37, "cave crawlers", 10, 10, needsQuest = false)
    private val abyssals = SlayerTask(42, "abyssal demons", 85, 85, needsQuest = false)
    private val questTask = SlayerTask(90, "quest monsters", 0, 1, needsQuest = true)
    private val noNpcs = SlayerTask(91, "unspawned", 0, 1, needsQuest = false)

    private val rows =
        listOf(
            MasterTask(1, goblins, 7, 15, 30),
            MasterTask(1, crawlers, 8, 15, 30),
            MasterTask(1, abyssals, 5, 100, 150),
            MasterTask(1, questTask, 5, 10, 20),
            MasterTask(1, noNpcs, 5, 10, 20),
            MasterTask(2, goblins, 0, 15, 30),
        )

    private val tasks = SlayerTasks(rows, killable = setOf(2, 37, 42, 90))

    @Test
    fun `eligible respects slayer and combat levels, quests and spawns`() {
        assertEquals(listOf(goblins), tasks.eligible(1, combat = 3, slayer = 1).map { it.task })
        assertEquals(
            listOf(goblins, crawlers),
            tasks.eligible(1, combat = 50, slayer = 20).map { it.task },
        )
        assertEquals(3, tasks.eligible(1, combat = 126, slayer = 99).size)
    }

    @Test
    fun `zero weight rows are never offered`() {
        assertEquals(emptyList(), tasks.eligible(2, combat = 126, slayer = 99))
    }

    @Test
    fun `pick is weighted by row weight`() {
        val options = tasks.eligible(1, combat = 126, slayer = 99)
        assertEquals(goblins, tasks.pick(options) { 0 }?.task)
        assertEquals(goblins, tasks.pick(options) { 6 }?.task)
        assertEquals(crawlers, tasks.pick(options) { 7 }?.task)
        assertEquals(abyssals, tasks.pick(options) { 19 }?.task)
        assertNull(tasks.pick(emptyList()) { 0 })
    }

    @Test
    fun `master ids come from npc internal names`() {
        assertEquals(1, SlayerTasks.masterIdFor("slayer_master_1_tureal"))
        assertEquals(3, SlayerTasks.masterIdFor("slayer_master_3"))
        assertEquals(5, SlayerTasks.masterIdFor("slayer_master_5_kuradal"))
        assertEquals(6, SlayerTasks.masterIdFor("slayer_master_steve"))
        assertEquals(9, SlayerTasks.masterIdFor("slayer_master_9_active"))
        assertNull(SlayerTasks.masterIdFor("wgs_slayer_master_1_tureal"))
    }
}
