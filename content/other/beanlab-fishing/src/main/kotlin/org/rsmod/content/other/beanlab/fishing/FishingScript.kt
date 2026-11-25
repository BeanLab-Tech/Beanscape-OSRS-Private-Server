package org.rsmod.content.other.beanlab.fishing

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.fishingLvl
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Fishing spots, which the upstream fishing module leaves empty.
 *
 * A spot is any loc whose name contains "fish". The option set picks the method: Net/Bait,
 * Lure/Bait, Cage/Harpoon, Big Net/Harpoon, or a lone Cage, Harpoon or Net. Spots stay put.
 */
class FishingScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val seqTypes: SeqTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private var objByName: Map<String, UnpackedObjType> = emptyMap()

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
    }

    private fun register(bus: EventBus) {
        objByName =
            objTypes.values
                .filter { !it.isCert && !it.isPlaceholder && it.name != "null" }
                .sortedBy { it.id }
                .groupBy { it.name.lowercase() }
                .mapValues { it.value.first() }

        var spots = 0
        for ((id, type) in locTypes) {
            if (!type.name.contains("fish", ignoreCase = true)) continue
            val options = type.op.mapNotNull { it?.lowercase() }.toSet()
            val methods = methodsFor(options)
            if (methods.isEmpty()) continue
            var any = false
            for (method in methods) {
                val op = type.opIndex(method.option) ?: continue
                if (addLocOp(bus, op, id) { loc, _ -> fish(loc, method) }) any = true
            }
            if (any) spots++
        }
        logger.info { "[beanlab] fishing registered: $spots spot types" }
    }

    private fun UnpackedLocType.opIndex(name: String): Int? =
        op.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it in 0..4 }

    private fun obj(name: String): UnpackedObjType? = objByName[name.lowercase()]

    private fun <T : SuspendEvent<ProtectedAccess>> addIfFree(
        bus: EventBus,
        cls: Class<T>,
        key: Long,
        handler: suspend ProtectedAccess.(T) -> Unit,
    ): Boolean {
        if (bus.contains(cls, key)) return false
        bus.subscribeSuspend(cls, key, handler)
        return true
    }

    private fun addLocOp(
        bus: EventBus,
        opIndex: Int,
        locId: Int,
        action: suspend ProtectedAccess.(BoundLocInfo, UnpackedLocType) -> Unit,
    ): Boolean {
        val key = locId.toLong()
        return when (opIndex) {
            0 -> addIfFree(bus, LocEvents.Op1::class.java, key) { action(it.loc, it.type) }
            1 -> addIfFree(bus, LocEvents.Op2::class.java, key) { action(it.loc, it.type) }
            2 -> addIfFree(bus, LocEvents.Op3::class.java, key) { action(it.loc, it.type) }
            3 -> addIfFree(bus, LocEvents.Op4::class.java, key) { action(it.loc, it.type) }
            else -> addIfFree(bus, LocEvents.Op5::class.java, key) { action(it.loc, it.type) }
        }
    }

    private suspend fun ProtectedAccess.fish(spot: BoundLocInfo, method: Method) {
        val tool = obj(method.tool)
        val bait = method.bait?.let { obj(it) }
        val table = method.fish.mapNotNull { fish -> obj(fish.name)?.let { fish to it } }
        if (tool == null || table.isEmpty()) {
            mes("Nothing interesting happens.")
            return
        }
        val lowest = method.fish.minOf { it.level }
        if (player.fishingLvl < lowest) {
            mes("You need a Fishing level of $lowest to catch these fish.")
            return
        }
        if (tool !in inv) {
            mes("You need a ${method.tool.lowercase()} to catch these fish.")
            return
        }
        faceLoc(spot)
        while (true) {
            val available = table.filter { player.fishingLvl >= it.first.level }
            if (available.isEmpty()) {
                mes("You need a Fishing level of $lowest to catch these fish.")
                return
            }
            if (inv.isFull()) {
                mes("Your inventory is too full to hold any more fish.")
                resetAnim()
                return
            }
            if (bait != null && bait !in inv) {
                mes("You don't have any ${bait.name.lowercase()}.")
                resetAnim()
                return
            }
            if (bait != null) {
                val used = invDel(inv, bait, count = 1)
                if (!used.success) return
            }
            seqTypes[method.anim]?.let { anim(it) }
            delay(TICKS)
            if (!statRandom(stats.fishing, SUCCESS_LOW, SUCCESS_HIGH, 0)) {
                spam("You fail to catch anything.")
                continue
            }
            val caught =
                if (available.size == 1 || random.of(100) < 60) {
                    available.last()
                } else {
                    available[available.lastIndex - 1]
                }
            invAdd(inv, caught.second, 1)
            statAdvance(stats.fishing, caught.first.xp * xpMods.get(player, stats.fishing))
            spam("You catch a ${caught.second.name.lowercase()}.")
        }
    }

    private fun methodsFor(options: Set<String>): List<Method> {
        val methods = ArrayList<Method>()
        val net = "net" in options
        val bait = "bait" in options
        val lure = "lure" in options
        val cage = "cage" in options
        val harpoon = "harpoon" in options
        val bigNet = "big net" in options
        if (bigNet) {
            methods +=
                Method(
                    "Big Net",
                    "Big fishing net",
                    null,
                    listOf(
                        Fish("Raw mackerel", 16, 20.0),
                        Fish("Raw cod", 23, 45.0),
                        Fish("Raw bass", 46, 100.0),
                    ),
                    BIG_NET_ANIM,
                )
        }
        if (lure) {
            methods +=
                Method(
                    "Lure",
                    "Fly fishing rod",
                    "Feather",
                    listOf(Fish("Raw trout", 20, 50.0), Fish("Raw salmon", 30, 70.0)),
                    LURE_ANIM,
                )
        }
        if (cage && harpoon) {
            methods +=
                Method(
                    "Cage",
                    "Lobster pot",
                    null,
                    listOf(Fish("Raw lobster", 40, 90.0)),
                    CAGE_ANIM,
                )
            methods +=
                Method(
                    "Harpoon",
                    "Harpoon",
                    null,
                    listOf(Fish("Raw tuna", 35, 80.0), Fish("Raw swordfish", 50, 100.0)),
                    HARPOON_ANIM,
                )
        } else if (cage) {
            methods +=
                Method(
                    "Cage",
                    "Lobster pot",
                    null,
                    listOf(Fish("Raw monkfish", 62, 120.0)),
                    CAGE_ANIM,
                )
        } else if (harpoon && !bigNet) {
            methods +=
                Method(
                    "Harpoon",
                    "Harpoon",
                    null,
                    listOf(Fish("Raw shark", 76, 110.0)),
                    HARPOON_ANIM,
                )
        }
        if (bigNet && harpoon) {
            methods +=
                Method(
                    "Harpoon",
                    "Harpoon",
                    null,
                    listOf(Fish("Raw shark", 76, 110.0)),
                    HARPOON_ANIM,
                )
        }
        if (net && bait && !lure) {
            methods +=
                Method(
                    "Net",
                    "Small fishing net",
                    null,
                    listOf(Fish("Raw shrimps", 1, 10.0), Fish("Raw anchovies", 15, 40.0)),
                    NET_ANIM,
                )
            methods +=
                Method(
                    "Bait",
                    "Fishing rod",
                    "Fishing bait",
                    listOf(Fish("Raw sardine", 5, 20.0), Fish("Raw herring", 10, 30.0)),
                    BAIT_ANIM,
                )
        } else if (bait && lure) {
            methods +=
                Method(
                    "Bait",
                    "Fishing rod",
                    "Fishing bait",
                    listOf(Fish("Raw pike", 25, 60.0)),
                    BAIT_ANIM,
                )
        } else if (net && !bait) {
            methods +=
                Method(
                    "Net",
                    "Small fishing net",
                    null,
                    listOf(Fish("Raw karambwanji", 5, 5.0)),
                    NET_ANIM,
                )
        }
        return methods
    }

    private data class Fish(val name: String, val level: Int, val xp: Double)

    private data class Method(
        val option: String,
        val tool: String,
        val bait: String?,
        val fish: List<Fish>,
        val anim: Int,
    )

    private companion object {
        const val TICKS = 5
        const val SUCCESS_LOW = 64
        const val SUCCESS_HIGH = 200
        const val NET_ANIM = 621
        const val BAIT_ANIM = 622
        const val LURE_ANIM = 623
        const val CAGE_ANIM = 619
        const val HARPOON_ANIM = 618
        const val BIG_NET_ANIM = 620
    }
}
