package org.rsmod.content.other.beanlab.crafting

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.HeldUEvents
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.craftingLvl
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Crafting extras: gold and silver jewellery at a furnace, stringing amulets and symbols with a
 * ball of wool, and dyeing capes.
 *
 * Use a gold or silver bar on any furnace; the menu lists what the held moulds and gems can make.
 */
class JewelleryScript
@Inject
constructor(
    private val objTypes: ObjTypeList,
    private val locTypes: LocTypeList,
    private val seqTypes: SeqTypeList,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private var byInternal: Map<String, UnpackedObjType> = emptyMap()

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
    }

    private fun register(bus: EventBus) {
        byInternal =
            objTypes.values
                .filter { !it.isCert && !it.isPlaceholder }
                .sortedBy { it.id }
                .mapNotNull { type -> type.internalName?.lowercase()?.let { it to type } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.first() }

        val missing = Jewellery.all.count { obj(it.product) == null || obj(it.mould) == null }
        var furnaces = 0
        for (loc in locTypes.values) {
            if (!loc.name.equals("furnace", ignoreCase = true)) continue
            for (bar in Jewellery.bars) {
                val type = obj(bar) ?: continue
                val key = EventBus.composeLongKey(loc.id, type.id)
                if (addIfFree(bus, LocUEvents.Op::class.java, key) { furnace(bar) }) furnaces++
            }
        }
        var strung = 0
        val wool = obj("ball_of_wool")
        for ((unstrung, product) in Jewellery.stringing) {
            val a = obj(unstrung) ?: continue
            if (wool == null || obj(product) == null) continue
            if (bindPair(bus, wool, a) { string(unstrung, product) }) strung++
        }
        var dyed = 0
        for ((dye, cape) in Jewellery.dyes) {
            val dyeType = obj(dye) ?: continue
            if (obj(cape) == null) continue
            for (from in Jewellery.capes) {
                if (from == cape) continue
                val capeType = obj(from) ?: continue
                if (bindPair(bus, dyeType, capeType) { dyeCape(dye, from, cape) }) dyed++
            }
        }
        logger.info {
            "[beanlab] jewellery registered: $furnaces furnace bindings, $strung stringing, " +
                "$dyed cape dyes, $missing recipes skipped (missing objs)"
        }
    }

    private fun obj(name: String): UnpackedObjType? = byInternal[name.lowercase()]

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

    private fun bindPair(
        bus: EventBus,
        a: UnpackedObjType,
        b: UnpackedObjType,
        action: suspend ProtectedAccess.() -> Unit,
    ): Boolean {
        val keys = setOf(EventBus.composeLongKey(a.id, b.id), EventBus.composeLongKey(b.id, a.id))
        var added = false
        for (key in keys) {
            if (addIfFree(bus, HeldUEvents.Type::class.java, key) { action() }) added = true
        }
        return added
    }

    private suspend fun ProtectedAccess.furnace(bar: String) {
        val options =
            Jewellery.all.filter { jewel ->
                jewel.bar == bar &&
                    obj(jewel.product) != null &&
                    obj(jewel.mould)?.let { inv.count(it) > 0 } == true &&
                    (jewel.gem == null || obj(jewel.gem)?.let { inv.count(it) > 0 } == true)
            }
        if (options.isEmpty()) {
            mes("You need a mould to craft anything with this bar.")
            return
        }
        val jewel =
            if (options.size == 1) {
                options[0]
            } else {
                val labels =
                    options.map {
                        "${it.label.replaceFirstChar { c -> c.uppercase() }} (${it.level})"
                    }
                val picked = menu("What would you like to make?", false, labels + "Cancel")
                ifClose()
                options.getOrNull(picked) ?: return
            }
        if (player.craftingLvl < jewel.level) {
            mes("You need a Crafting level of ${jewel.level} to make a ${jewel.label}.")
            return
        }
        val amount = askAmount() ?: return
        val barType = obj(jewel.bar) ?: return
        val gemType = jewel.gem?.let { obj(it) ?: return }
        val product = obj(jewel.product) ?: return
        var made = 0
        while (made < amount) {
            if (inv.count(barType) < 1) break
            if (gemType != null && inv.count(gemType) < 1) break
            seqTypes.values.firstOrNull { it.internalName == "human_furnace" }?.let { anim(it) }
            delay(3)
            if (!invDel(inv, barType, count = 1).success) break
            if (gemType != null && !invDel(inv, gemType, count = 1).success) {
                invAdd(inv, barType, 1)
                break
            }
            invAdd(inv, product, 1)
            statAdvance(stats.crafting, jewel.xp * xpMods.get(player, stats.crafting))
            mes("You make a ${jewel.label}.")
            made++
        }
    }

    private suspend fun ProtectedAccess.askAmount(): Int? {
        val choice = menu("How many?", hotkeys = false, listOf("1", "5", "10", "All", "Cancel"))
        ifClose()
        return when (choice) {
            0 -> 1
            1 -> 5
            2 -> 10
            3 -> Int.MAX_VALUE
            else -> null
        }
    }

    private suspend fun ProtectedAccess.string(unstrung: String, product: String) {
        val wool = obj("ball_of_wool") ?: return
        val item = obj(unstrung) ?: return
        val out = obj(product) ?: return
        delay(1)
        if (!invDel(inv, item, count = 1).success) return
        if (!invDel(inv, wool, count = 1).success) {
            invAdd(inv, item, 1)
            return
        }
        invAdd(inv, out, 1)
        statAdvance(stats.crafting, Jewellery.STRING_XP * xpMods.get(player, stats.crafting))
        mes("You put some string on the ${out.name.lowercase()}.")
    }

    private suspend fun ProtectedAccess.dyeCape(dye: String, from: String, into: String) {
        val dyeType = obj(dye) ?: return
        val cape = obj(from) ?: return
        val out = obj(into) ?: return
        delay(1)
        if (!invDel(inv, cape, count = 1).success) return
        if (!invDel(inv, dyeType, count = 1).success) {
            invAdd(inv, cape, 1)
            return
        }
        invAdd(inv, out, 1)
        mes("You dye the cape ${out.name.lowercase().removeSuffix(" cape")}.")
    }
}
