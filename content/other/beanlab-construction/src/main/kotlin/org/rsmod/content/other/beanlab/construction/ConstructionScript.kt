package org.rsmod.content.other.beanlab.construction

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.db.gateway.GameDbManager
import org.rsmod.api.db.gateway.model.GameDbResult
import org.rsmod.api.db.gateway.model.fold
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.LocUnimplementedEvents
import org.rsmod.api.player.events.interact.NpcUnimplementedEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.constructionLvl
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.region.RegionRepository
import org.rsmod.api.repo.region.RegionTemplate
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.region.Region
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.loc.UnpackedLocType
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Construction: sawmill planks and a starter player-owned house.
 *
 * Any house portal ("Enter", "Home", "Build mode") opens the player's house: an instance copied
 * from the cache's Rimmington room templates, a garden (arrival and exit portal) with a parlour to
 * the east and a workshop to the north. In build mode, "Build" on a hotspot offers that hotspot's
 * furniture ([Construction]); it needs a hammer, a saw and the materials, and "Remove" takes the
 * furniture away again. Built furniture is stored per character in `beanlab_poh_furniture` (V16)
 * and rebuilt every time the house is entered. No room building, guests or servants yet.
 */
class ConstructionScript
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val objTypes: ObjTypeList,
    private val seqTypes: SeqTypeList,
    private val locRepo: LocRepository,
    private val regionRepo: RegionRepository,
    private val db: GameDbManager,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private val houses = HashMap<Int, House>()
    private val furniture = HashMap<Int, HashMap<Pair<String, String>, Int>>()
    private var objs: Map<String, UnpackedObjType> = emptyMap()
    private var locs: Map<String, UnpackedLocType> = emptyMap()

    private class House(
        val region: Region,
        var buildMode: Boolean,
        var returnTo: CoordGrid,
        /** Furniture tile -> the hotspot loc type it replaced. */
        val replaced: HashMap<Int, Int> = HashMap(),
    )

    private val template =
        RegionTemplate.create {
            for (room in Construction.rooms) {
                this[room.houseZoneX, room.houseZoneZ, 0] =
                    ZoneKey(room.templateZoneX, room.templateZoneZ, 0)
            }
        }

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
        onPlayerLogin { load(player) }
    }

    private fun register(bus: EventBus) {
        objs =
            objTypes.values
                .filter { !it.isCert && !it.isPlaceholder }
                .sortedBy { it.id }
                .mapNotNull { type -> type.internalName?.let { it to type } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.first() }
        locs = locTypes.values.mapNotNull { t -> t.internalName?.let { it to t } }.toMap()

        var bound = 0
        for (type in npcTypes.values.filter { it.internalName == "poh_sawmill_opp" }) {
            val slot = type.op.indexOfFirst { it.equals("Buy-plank", ignoreCase = true) }
            if (bindNpc(bus, slot, type.id) { buyPlanks() }) bound++
        }
        for (type in locTypes.values) {
            val name = type.internalName ?: continue
            if (
                !name.startsWith("poh_") ||
                    !name.endsWith("_portal") ||
                    name == Construction.EXIT_PORTAL
            ) {
                continue
            }
            for ((slot, option) in type.op.withIndex()) {
                val action: (suspend ProtectedAccess.(BoundLocInfo) -> Unit)? =
                    when (option?.lowercase()) {
                        "enter",
                        "home" -> { _ -> enter(build = false) }
                        "build mode" -> { _ -> enter(build = true) }
                        "friend's house" -> { _ ->
                                mes("Visiting friends' houses isn't available yet.")
                            }
                        else -> null
                    }
                if (action != null && bindLoc(bus, slot, type.id, action)) bound++
            }
        }
        locs[Construction.EXIT_PORTAL]?.let { portal ->
            if (bindLoc(bus, 0, portal.id) { leave() }) bound++
        }
        val hotspots = Construction.rooms.flatMap { it.groups }.flatMap { it.hotspots }.toSet()
        for (name in hotspots) {
            val type = locs[name] ?: continue
            val slot = type.op.indexOfFirst { it.equals("Build", ignoreCase = true) }
            if (bindLoc(bus, slot, type.id) { build(it) }) bound++
        }
        val built =
            Construction.rooms
                .flatMap { it.groups }
                .flatMap { it.options }
                .flatMap { it.locs.values }
                .toSet()
        for (name in built) {
            val type = locs[name] ?: continue
            val slot = type.op.indexOfFirst { it.equals("Remove", ignoreCase = true) }
            if (bindLoc(bus, slot, type.id) { remove(it) }) bound++
        }
        logger.info { "[beanlab] construction registered: $bound bindings" }
    }

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
            4 -> addIfFree(bus, LocUnimplementedEvents.Op5::class.java, key) { action(it.loc) }
            else -> false
        }
    }

    private fun bindNpc(
        bus: EventBus,
        slot: Int,
        id: Int,
        action: suspend ProtectedAccess.() -> Unit,
    ): Boolean {
        val key = id.toLong()
        return when (slot) {
            0 -> addIfFree(bus, NpcUnimplementedEvents.Op1::class.java, key) { action() }
            1 -> addIfFree(bus, NpcUnimplementedEvents.Op2::class.java, key) { action() }
            2 -> addIfFree(bus, NpcUnimplementedEvents.Op3::class.java, key) { action() }
            3 -> addIfFree(bus, NpcUnimplementedEvents.Op4::class.java, key) { action() }
            4 -> addIfFree(bus, NpcUnimplementedEvents.Op5::class.java, key) { action() }
            else -> false
        }
    }

    // ---------------------------------------------------------------- sawmill

    private suspend fun ProtectedAccess.buyPlanks() {
        val labels =
            Construction.sawmill.map { "${objs[it.plank]?.name ?: it.plank} (${it.cost} gp)" }
        val picked = menu("Which planks would you like?", false, labels + "Cancel")
        ifClose()
        val recipe = Construction.sawmill.getOrNull(picked) ?: return
        val log = objs[recipe.log] ?: return
        val plank = objs[recipe.plank] ?: return
        val coins = objs["coins"] ?: return
        val count = minOf(inv.count(log), inv.count(coins) / recipe.cost)
        if (count <= 0) {
            mes("You need ${log.name.lowercase()} and ${recipe.cost} coins for each plank.")
            return
        }
        if (!invDel(inv, coins, count = count * recipe.cost).success) return
        if (!invDel(inv, log, count = count).success) {
            invAdd(inv, coins, count * recipe.cost)
            return
        }
        invAdd(inv, plank, count)
        mes(
            "The sawmill operator makes $count ${plank.name.lowercase()}${if (count > 1) "s" else ""}."
        )
    }

    // ------------------------------------------------------------------ house

    private fun Player.house(): House? = houses[characterId]?.takeIf { it.region.slot != -1 }

    private fun zoneOf(region: Region, room: Room): ZoneKey {
        val sw = region.southWestZone
        return ZoneKey(sw.x + room.houseZoneX, sw.z + room.houseZoneZ, 0)
    }

    private fun roomAt(region: Region, coords: CoordGrid): Room? =
        Construction.rooms.firstOrNull { ZoneKey.from(coords) == zoneOf(region, it) }

    private fun ProtectedAccess.enter(build: Boolean) {
        val built = furniture[player.characterId]
        if (built == null) {
            mes("Your house is still loading; try again in a moment.")
            return
        }
        val existing = player.house()
        val house =
            if (existing != null) {
                existing.buildMode = build
                existing.returnTo = coords
                existing
            } else {
                val region = regionRepo.add(template)
                if (region == null) {
                    mes("There's no room for your house right now; try again later.")
                    return
                }
                House(region, build, coords).also {
                    houses[player.characterId] = it
                    furnish(it, built)
                }
            }
        val garden = zoneOf(house.region, Construction.garden)
        val arrive =
            CoordGrid(
                garden.x * ZONE + Construction.ARRIVE_LOCAL_X,
                garden.z * ZONE + Construction.ARRIVE_LOCAL_Z,
                0,
            )
        telejump(arrive)
        mes(if (build) "You enter your house in building mode." else "You enter your house.")
    }

    private fun ProtectedAccess.leave() {
        val house = player.house()
        val back = house?.returnTo ?: CoordGrid(2953, 3224, 0)
        telejump(back)
        mes("You leave the house.")
    }

    /** Puts the exit portal and every saved piece of furniture into a freshly built house. */
    private fun furnish(house: House, built: Map<Pair<String, String>, Int>) {
        val garden = zoneOf(house.region, Construction.garden)
        val portal = locs[Construction.EXIT_PORTAL]
        if (portal != null) {
            locRepo
                .findAll(garden)
                .firstOrNull { locTypes[it.id]?.internalName == Construction.GARDEN_CENTREPIECE }
                ?.let { locRepo.change(it, portal, Int.MAX_VALUE) }
        }
        for ((key, index) in built) {
            val room = Construction.room(key.first) ?: continue
            val group = room.groups.firstOrNull { it.key == key.second } ?: continue
            val option = group.options.getOrNull(index) ?: continue
            place(house, room, group, option)
        }
    }

    private fun place(house: House, room: Room, group: HotspotGroup, option: Furniture): Int {
        var placed = 0
        val zone = zoneOf(house.region, room)
        for (loc in locRepo.findAll(zone).toList()) {
            val name = locTypes[loc.id]?.internalName ?: continue
            if (name !in group.hotspots) continue
            val into = option.locs[name]?.let { locs[it] } ?: continue
            house.replaced[loc.coords.packed] = loc.id
            locRepo.change(loc, into, Int.MAX_VALUE)
            placed++
        }
        return placed
    }

    private fun ProtectedAccess.ownedHouse(coords: CoordGrid): House? {
        val house = player.house()
        if (house == null || roomAt(house.region, coords) == null) {
            mes("You can only build in your own house.")
            return null
        }
        if (!house.buildMode) {
            mes("You need to be in building mode to do that.")
            return null
        }
        return house
    }

    private suspend fun ProtectedAccess.build(loc: BoundLocInfo) {
        val house = ownedHouse(loc.coords) ?: return
        val room = roomAt(house.region, loc.coords) ?: return
        val hotspot = locTypes[loc.id]?.internalName ?: return
        val group = room.groups.firstOrNull { hotspot in it.hotspots } ?: return
        val labels =
            group.options.map { o ->
                val needs = o.materials.joinToString(", ") { (n, c) -> "$c ${objs[n]?.name ?: n}" }
                "${o.label} (lvl ${o.level}: $needs)"
            }
        val picked = menu("Build what?", false, labels + "Cancel")
        ifClose()
        val option = group.options.getOrNull(picked) ?: return
        if (player.constructionLvl < option.level) {
            mes("You need a Construction level of ${option.level} to build that.")
            return
        }
        val hammer = objs["hammer"]
        val saw = objs["poh_saw"]
        if (hammer == null || saw == null || hammer !in inv || saw !in inv) {
            mes("You need a hammer and a saw to build furniture.")
            return
        }
        for ((name, count) in option.materials) {
            val type = objs[name] ?: return
            if (inv.count(type) < count) {
                mes("You need $count ${type.name.lowercase()} to build that.")
                return
            }
        }
        seqTypes.values.firstOrNull { it.internalName == "human_poh_build" }?.let { anim(it) }
        delay(3)
        for ((name, count) in option.materials) {
            val type = objs[name] ?: return
            if (!invDel(inv, type, count = count).success) return
        }
        if (place(house, room, group, option) == 0) return
        statAdvance(stats.construction, option.xp * xpMods.get(player, stats.construction))
        val index = group.options.indexOf(option)
        furniture.getOrPut(player.characterId) { HashMap() }[room.name to group.key] = index
        persist(player.characterId, room.name, group.key, index)
        mes("You build a ${option.label.lowercase()}.")
    }

    private fun ProtectedAccess.remove(loc: BoundLocInfo) {
        val house = ownedHouse(loc.coords) ?: return
        val room = roomAt(house.region, loc.coords) ?: return
        val name = locTypes[loc.id]?.internalName ?: return
        val group =
            room.groups.firstOrNull { g -> g.options.any { name in it.locs.values } } ?: return
        val built = group.options.flatMap { it.locs.values }.toSet()
        val zone = zoneOf(house.region, room)
        for (info in locRepo.findAll(zone).toList()) {
            val type = locTypes[info.id] ?: continue
            if (type.internalName !in built) continue
            val original =
                house.replaced.remove(info.coords.packed)?.let { locTypes[it] } ?: continue
            locRepo.change(info, original, Int.MAX_VALUE)
        }
        furniture[player.characterId]?.remove(room.name to group.key)
        persist(player.characterId, room.name, group.key, null)
        mes("You remove the ${locTypes[loc.id]?.name?.lowercase() ?: "furniture"}.")
    }

    // --------------------------------------------------------------- database

    private fun persist(characterId: Int, room: String, hotspot: String, index: Int?) {
        db.request(
            request = { conn ->
                val sql =
                    if (index == null) {
                        "DELETE FROM beanlab_poh_furniture WHERE character_id = ? AND room = ? " +
                            "AND hotspot = ?"
                    } else {
                        "INSERT INTO beanlab_poh_furniture (character_id, room, hotspot, furniture) " +
                            "VALUES (?, ?, ?, ?) ON CONFLICT(character_id, room, hotspot) " +
                            "DO UPDATE SET furniture = excluded.furniture"
                    }
                conn.prepareStatement(sql).use {
                    it.setInt(1, characterId)
                    it.setString(2, room)
                    it.setString(3, hotspot)
                    if (index != null) it.setInt(4, index)
                    it.executeUpdate()
                }
                GameDbResult.Ok(Unit)
            },
            response = { result ->
                result.fold(
                    onOk = {},
                    onErr = { logger.error { "[construction] save failed for $characterId: $it" } },
                )
            },
        )
    }

    private fun load(player: Player) {
        val characterId = player.characterId
        db.request(
            request = { conn ->
                val rows = HashMap<Pair<String, String>, Int>()
                conn
                    .prepareStatement(
                        "SELECT room, hotspot, furniture FROM beanlab_poh_furniture " +
                            "WHERE character_id = ?"
                    )
                    .use { stmt ->
                        stmt.setInt(1, characterId)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) rows[rs.getString(1) to rs.getString(2)] =
                                rs.getInt(3)
                        }
                    }
                GameDbResult.Ok(rows)
            },
            response = { result ->
                result.fold(
                    onOk = { rows -> furniture[characterId] = rows },
                    onErr = { logger.error { "[construction] load failed for $characterId: $it" } },
                )
            },
        )
    }

    private companion object {
        const val ZONE = 8
    }
}
