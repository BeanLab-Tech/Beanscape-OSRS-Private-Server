package org.rsmod.content.other.beanlab.thieving

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.LocUnimplementedEvents
import org.rsmod.api.player.hit.queueHit
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.thievingLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.hit.HitType
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Thieving extras: trapped chests ("Search for traps", or "Open" and set the trap off) and locked
 * doors ("Pick-lock", then walk through). Stall guard reactions live in [ThievingScript].
 */
class ThievingExtrasScript
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val locRepo: LocRepository,
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
        val byName = locTypes.values.associateBy { it.internalName }

        var chests = 0
        for (chest in ThievingExtras.chests) {
            val type = byName[chest.loc] ?: continue
            val search = type.slot("Search for traps")
            val open = type.slot("Open")
            if (search != null && bindLoc(bus, search, type.id) { search(it, chest) }) chests++
            if (open != null) bindLoc(bus, open, type.id) { springTrap(chest) }
        }
        for (empty in setOf("emptytrapchest", "emptypickchest")) {
            val type = byName[empty] ?: continue
            for (option in listOf("Open", "Search for traps")) {
                val slot = type.slot(option) ?: continue
                bindLoc(bus, slot, type.id) { mes("The chest is empty.") }
            }
        }
        var doors = 0
        for (door in ThievingExtras.doors) {
            val type = byName[door.loc] ?: continue
            val pick = type.slot("Pick-lock")
            val open = type.slot("Open")
            if (pick != null && bindLoc(bus, pick, type.id) { pickLock(it, door) }) doors++
            if (open != null) bindLoc(bus, open, type.id) { mes("This door is locked.") }
        }
        logger.info { "[beanlab] thieving extras registered: $chests chests, $doors locked doors" }
    }

    private fun UnpackedLocType.slot(option: String): Int? =
        op.indexOfFirst { it.equals(option, ignoreCase = true) }.takeIf { it in 0..4 }

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

    private fun bindLoc(
        bus: EventBus,
        slot: Int,
        id: Int,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ): Boolean {
        val key = id.toLong()
        return when (slot) {
            0 -> addIfFree(bus, LocUnimplementedEvents.Op1::class.java, key) { action(it.loc) }
            1 -> addIfFree(bus, LocUnimplementedEvents.Op2::class.java, key) { action(it.loc) }
            2 -> addIfFree(bus, LocUnimplementedEvents.Op3::class.java, key) { action(it.loc) }
            3 -> addIfFree(bus, LocUnimplementedEvents.Op4::class.java, key) { action(it.loc) }
            else -> addIfFree(bus, LocUnimplementedEvents.Op5::class.java, key) { action(it.loc) }
        }
    }

    private suspend fun ProtectedAccess.search(loc: BoundLocInfo, chest: TrapChest) {
        if (player.thievingLvl < chest.level) {
            mes("You need a Thieving level of ${chest.level} to search this chest for traps.")
            return
        }
        if (inv.freeSpace() < chest.loot.size) {
            mes("You don't have enough inventory space.")
            return
        }
        mes("You search the chest for traps.")
        delay(2)
        if (!statRandom(stats.thieving, 90, 240, 0)) {
            mes("You fail to find the trap and set it off!")
            hurt(chest.trapDamage)
            return
        }
        mes("You find a trap on the chest...")
        delay(1)
        mes("You disable the trap.")
        delay(1)
        for ((name, min, max) in chest.loot) {
            val type = obj(name) ?: continue
            val count = if (max > min) min + random.of(max - min + 1) else min
            invAdd(inv, type, count)
        }
        statAdvance(stats.thieving, chest.xp * xpMods.get(player, stats.thieving))
        mes("You open the chest and find some treasure.")
        locTypes.values
            .firstOrNull { it.internalName == chest.empty }
            ?.let { locRepo.change(loc, it, chest.respawnTicks) }
    }

    private suspend fun ProtectedAccess.springTrap(chest: TrapChest) {
        mes("You have activated a trap on the chest.")
        delay(1)
        hurt(chest.trapDamage)
    }

    private fun ProtectedAccess.hurt(damage: Int) {
        player.queueHit(1, HitType.Typeless, 1 + random.of(damage))
    }

    private suspend fun ProtectedAccess.pickLock(loc: BoundLocInfo, door: LockedDoor) {
        if (player.thievingLvl < door.level) {
            mes("You need a Thieving level of ${door.level} to pick this lock.")
            return
        }
        val lockpick = obj("lockpick")
        if (door.lockpick && (lockpick == null || lockpick !in inv)) {
            mes("You need a lockpick for this lock.")
            return
        }
        mes("You attempt to pick the lock.")
        delay(2)
        if (!statRandom(stats.thieving, 100, 250, 0)) {
            mes("You fail to pick the lock.")
            return
        }
        mes("You manage to pick the lock.")
        statAdvance(stats.thieving, door.xp * xpMods.get(player, stats.thieving))
        teleport(otherSide(loc, coords))
    }

    /** The tile across the wall edge the door sits on, relative to where the player stands. */
    private fun otherSide(loc: BoundLocInfo, player: CoordGrid): CoordGrid {
        val c = loc.coords
        return when (loc.angle) {
            LocAngle.West -> if (player.x >= c.x) c.translateX(-1) else c
            LocAngle.North -> if (player.z <= c.z) c.translateZ(1) else c
            LocAngle.East -> if (player.x <= c.x) c.translateX(1) else c
            LocAngle.South -> if (player.z >= c.z) c.translateZ(-1) else c
        }
    }
}
