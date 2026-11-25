package org.rsmod.content.other.beanlab.farming

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import org.rsmod.api.config.refs.stats
import org.rsmod.api.db.DatabaseConnection
import org.rsmod.api.db.gateway.GameDbManager
import org.rsmod.api.db.gateway.model.GameDbResult
import org.rsmod.api.db.gateway.model.fold
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.interact.LocUEvents
import org.rsmod.api.player.events.interact.LocUnimplementedEvents
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.farmingLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.events.EventBus
import org.rsmod.events.SuspendEvent
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.obj.ObjTypeList
import org.rsmod.game.type.obj.UnpackedObjType
import org.rsmod.game.type.seq.SeqTypeList
import org.rsmod.game.type.varbit.VarBitType
import org.rsmod.game.type.varbit.VarBitTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Farming: allotments, herbs, flowers and hops ([Farming.crops]).
 *
 * Rake weeds, add compost, plant seeds with a seed dibber, wait, then harvest (allotments and hops
 * need a spade; herbs and flowers are picked). Each patch's growth stages are read from the patch
 * loc's multiloc names, and the client sees the patch through its region `farming_transmit` varbit,
 * which this script refreshes for the nearest patch every few ticks. State per character and patch
 * lives in `beanlab_farming_patches` (V16), loaded at login and written on every change; growth
 * comes from the planting time, so crops grow while offline. No disease or watering.
 */
class FarmingScript
@Inject
constructor(
    private val locTypes: LocTypeList,
    private val objTypes: ObjTypeList,
    private val seqTypes: SeqTypeList,
    private val varBitTypes: VarBitTypeList,
    private val locRepo: LocRepository,
    private val players: PlayerList,
    private val db: GameDbManager,
    private val xpMods: XpModifiers,
) : PluginScript() {
    private val logger = InlineLogger()
    private val layouts = HashMap<Int, Layout>()
    private val states = HashMap<Int, HashMap<Int, PatchState>>()
    private val nearby = HashMap<Int, Pair<ZoneKey, List<Pair<Int, CoordGrid>>>>()
    private var objs: Map<String, UnpackedObjType> = emptyMap()
    private var cropsByPrefix: Map<String, Crop> = emptyMap()
    private var cycle = 0

    private class Layout(
        val kind: PatchKind,
        val varbit: VarBitType,
        val stages: Map<String, CropStages>,
    )

    override fun ScriptContext.startup() {
        val bus = eventBus
        onEvent<GameLifecycle.Startup> { register(bus) }
        onEvent<GameLifecycle.StartCycle> { refresh() }
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
        cropsByPrefix = Farming.crops.associateBy { it.stagePrefix }

        var patches = 0
        var seedBinds = 0
        val variants = HashSet<Int>()
        for (type in locTypes.values) {
            val name = type.internalName ?: continue
            val kind = PatchKind.entries.firstOrNull { name.startsWith(it.prefix) } ?: continue
            if (type.multiVarBit <= 0 || type.multiLoc.isEmpty()) continue
            val varbit = varBitTypes[type.multiVarBit] ?: continue
            val ids = type.multiLoc.map { it.toInt() and 0xFFFF }
            val stages = Farming.parseStages(ids.map { locTypes[it]?.internalName })
            layouts[type.id] = Layout(kind, varbit, stages)
            patches++
            for (crop in Farming.crops.filter { it.kind == kind && it.stagePrefix in stages }) {
                val seed = objs[crop.seed] ?: continue
                if (bindLocU(bus, type.id, seed.id) { plant(it, crop) }) seedBinds++
            }
            for ((compost, level, xp) in Farming.composts) {
                val item = objs[compost] ?: continue
                bindLocU(bus, type.id, item.id) { compost(it, compost, level, xp) }
            }
            variants += ids.filter { locTypes.containsKey(it) }
        }
        var ops = 0
        for (id in variants) {
            val type = locTypes[id] ?: continue
            for ((slot, option) in type.op.withIndex()) {
                val bound =
                    when (option?.lowercase()) {
                        "rake" -> bindLoc(bus, slot, id) { rake(it) }
                        "harvest",
                        "pick" -> bindLoc(bus, slot, id) { harvest(it) }
                        "inspect" -> bindLoc(bus, slot, id) { inspect(it) }
                        "clear" -> bindLoc(bus, slot, id) { clear(it) }
                        else -> false
                    }
                if (bound) ops++
            }
        }
        logger.info {
            "[beanlab] farming registered: $patches patch types, ${variants.size} stage locs, " +
                "$ops options, $seedBinds seed bindings"
        }
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
            else -> addIfFree(bus, LocUnimplementedEvents.Op5::class.java, key) { action(it.loc) }
        }
    }

    private fun bindLocU(
        bus: EventBus,
        locId: Int,
        objId: Int,
        action: suspend ProtectedAccess.(BoundLocInfo) -> Unit,
    ): Boolean =
        addIfFree(bus, LocUEvents.Op::class.java, EventBus.composeLongKey(locId, objId)) {
            action(it.loc)
        }

    // ------------------------------------------------------------------ state

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun Player.patches(): HashMap<Int, PatchState>? = states[characterId]

    private fun ProtectedAccess.state(loc: BoundLocInfo): PatchState? {
        val patches = player.patches()
        if (patches == null) {
            mes("Your farming patches are still loading; try again in a moment.")
            return null
        }
        return patches[loc.coords.packed] ?: PatchState()
    }

    private fun ProtectedAccess.save(loc: BoundLocInfo, state: PatchState) {
        val patches = player.patches() ?: return
        val key = loc.coords.packed
        patches[key] = state
        push(player, loc.id, loc.coords, state)
        val characterId = player.characterId
        db.request(
            request = { conn ->
                upsert(conn, characterId, key, state)
                GameDbResult.Ok(Unit)
            },
            response = { result ->
                result.fold(
                    onOk = {},
                    onErr = { logger.error { "[farming] save failed for $characterId: $it" } },
                )
            },
        )
    }

    private fun upsert(conn: DatabaseConnection, characterId: Int, patch: Int, s: PatchState) {
        conn
            .prepareStatement(
                "INSERT INTO beanlab_farming_patches " +
                    "(character_id, patch, crop, weeds, planted_at, compost, lives) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(character_id, patch) DO UPDATE SET crop = excluded.crop, " +
                    "weeds = excluded.weeds, planted_at = excluded.planted_at, " +
                    "compost = excluded.compost, lives = excluded.lives"
            )
            .use {
                it.setInt(1, characterId)
                it.setInt(2, patch)
                it.setString(3, s.crop)
                it.setInt(4, s.weeds)
                it.setLong(5, s.plantedAt)
                it.setInt(6, s.compost)
                it.setInt(7, s.lives)
                it.executeUpdate()
            }
    }

    private fun load(player: Player) {
        val characterId = player.characterId
        val uid = player.uid
        db.request(
            request = { conn ->
                val rows = HashMap<Int, PatchState>()
                conn
                    .prepareStatement(
                        "SELECT patch, crop, weeds, planted_at, compost, lives " +
                            "FROM beanlab_farming_patches WHERE character_id = ?"
                    )
                    .use { stmt ->
                        stmt.setInt(1, characterId)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                rows[rs.getInt(1)] =
                                    PatchState(
                                        crop = rs.getString(2),
                                        weeds = rs.getInt(3),
                                        plantedAt = rs.getLong(4),
                                        compost = rs.getInt(5),
                                        lives = rs.getInt(6),
                                    )
                            }
                        }
                    }
                GameDbResult.Ok(rows)
            },
            response = { result ->
                result.fold(
                    onOk = { rows ->
                        states[characterId] = rows
                        uid.resolve(players)?.let { nearby.remove(it.characterId) }
                    },
                    onErr = { logger.error { "[farming] load failed for $characterId: $it" } },
                )
            },
        )
    }

    // ---------------------------------------------------------------- visuals

    /** Every few ticks, show each player the nearest patch for every transmit varbit. */
    private fun refresh() {
        cycle++
        if (cycle % REFRESH_TICKS != 0) return
        val time = now()
        for (player in players) {
            val patches = player.patches() ?: continue
            val zone = ZoneKey.from(player.coords)
            val cached = nearby[player.characterId]
            val found =
                if (cached != null && cached.first == zone) {
                    cached.second
                } else {
                    scan(zone).also { nearby[player.characterId] = zone to it }
                }
            val best = HashMap<Int, Pair<Int, CoordGrid>>()
            for ((locId, coords) in found) {
                val layout = layouts[locId] ?: continue
                val current = best[layout.varbit.id]
                val distance = coords.chebyshevDistance(player.coords)
                if (current == null || current.second.chebyshevDistance(player.coords) > distance) {
                    best[layout.varbit.id] = locId to coords
                }
            }
            for ((locId, coords) in best.values) {
                push(player, locId, coords, patches[coords.packed] ?: PatchState(), time)
            }
        }
    }

    private fun scan(center: ZoneKey): List<Pair<Int, CoordGrid>> {
        val out = ArrayList<Pair<Int, CoordGrid>>()
        for (dx in -SCAN_ZONES..SCAN_ZONES) {
            for (dz in -SCAN_ZONES..SCAN_ZONES) {
                val zone = ZoneKey(center.x + dx, center.z + dz, center.level)
                for (loc in locRepo.findAll(zone)) {
                    if (loc.id in layouts) out += loc.id to loc.coords
                }
            }
        }
        return out
    }

    private fun push(
        player: Player,
        locId: Int,
        coords: CoordGrid,
        state: PatchState,
        time: Long = now(),
    ) {
        val layout = layouts[locId] ?: return
        val crop = cropsByPrefix[state.crop]
        val value = Farming.display(state, crop, crop?.let { layout.stages[it.stagePrefix] }, time)
        if (player.vars[layout.varbit] != value) {
            VarPlayerIntMapSetter.set(player, layout.varbit, value)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun ProtectedAccess.seq(name: String) =
        seqTypes.values.firstOrNull { it.internalName == name }?.let { anim(it) }

    private fun ProtectedAccess.has(name: String): Boolean = objs[name]?.let { it in inv } == true

    private suspend fun ProtectedAccess.rake(loc: BoundLocInfo) {
        var state = state(loc) ?: return
        if (!state.empty || state.weeds >= Farming.CLEAR) return
        if (!has("rake")) {
            mes("You need a rake to clear this patch.")
            return
        }
        val weeds = objs["weeds"]
        while (state.weeds < Farming.CLEAR) {
            seq("farming_raking")
            delay(3)
            state = state.copy(weeds = state.weeds + 1)
            save(loc, state)
            // Weeds go in the inventory, or are discarded when it is full.
            weeds?.let { if (!inv.isFull()) invAdd(inv, it, 1) }
            statAdvance(stats.farming, Farming.RAKE_XP * xpMods.get(player, stats.farming))
        }
        resetAnim()
        mes("You rake the patch clear of weeds.")
    }

    private suspend fun ProtectedAccess.compost(
        loc: BoundLocInfo,
        item: String,
        level: Int,
        xp: Double,
    ) {
        val layout = layouts[loc.id] ?: return
        val state = state(loc) ?: return
        val crop = cropsByPrefix[state.crop]
        val stages = crop?.let { layout.stages[it.stagePrefix] }
        if (state.empty && state.weeds < Farming.CLEAR) {
            mes("This patch needs weeding first.")
            return
        }
        if (state.compost > 0) {
            mes("This patch has already been treated with compost.")
            return
        }
        if (crop != null && stages != null && Farming.grown(state, crop, stages, now())) {
            mes("There's no point adding compost to a fully grown crop.")
            return
        }
        val bucket = objs[item] ?: return
        seq("farming_pour_water")
        delay(2)
        if (!invDel(inv, bucket, count = 1).success) return
        objs["bucket_empty"]?.let { invAdd(inv, it, 1) }
        save(loc, state.copy(compost = level))
        statAdvance(stats.farming, xp * xpMods.get(player, stats.farming))
        mes("You treat the patch with ${bucket.name.lowercase()}.")
    }

    private suspend fun ProtectedAccess.plant(loc: BoundLocInfo, crop: Crop) {
        val state = state(loc) ?: return
        if (!state.empty) {
            mes("This patch already has something growing in it.")
            return
        }
        if (state.weeds < Farming.CLEAR) {
            mes("This patch needs weeding first.")
            return
        }
        if (player.farmingLvl < crop.level) {
            mes("You need a Farming level of ${crop.level} to plant that.")
            return
        }
        if (!has("dibber")) {
            mes("You need a seed dibber to plant seeds.")
            return
        }
        val seed = objs[crop.seed] ?: return
        if (inv.count(seed) < crop.seeds) {
            mes("You need ${crop.seeds} seeds to plant this patch.")
            return
        }
        seq("farming_seed_dibbing")
        delay(3)
        if (!invDel(inv, seed, count = crop.seeds).success) return
        save(
            loc,
            PatchState(
                crop = crop.stagePrefix,
                weeds = Farming.CLEAR,
                plantedAt = now(),
                compost = state.compost,
            ),
        )
        statAdvance(stats.farming, crop.plantXp * xpMods.get(player, stats.farming))
        mes("You plant ${if (crop.seeds > 1) "${crop.seeds} seeds" else "the seed"} in the patch.")
    }

    private suspend fun ProtectedAccess.harvest(loc: BoundLocInfo) {
        val layout = layouts[loc.id] ?: return
        var state = state(loc) ?: return
        val crop = cropsByPrefix[state.crop] ?: return
        val stages = layout.stages[crop.stagePrefix] ?: return
        if (!Farming.grown(state, crop, stages, now())) return
        val needsSpade = crop.kind == PatchKind.Allotment || crop.kind == PatchKind.Hops
        if (needsSpade && !has("spade")) {
            mes("You need a spade to harvest this patch.")
            return
        }
        val product = objs[crop.product] ?: return
        if (state.lives <= 0) {
            val bonus = if (crop.harvests > 1) state.compost + random.of(3) else 0
            state = state.copy(lives = crop.harvests + bonus)
        }
        while (state.lives > 0) {
            if (inv.isFull()) {
                mes("You don't have enough inventory space.")
                save(loc, state)
                return
            }
            seq(if (needsSpade) "human_dig" else "picking_low")
            delay(2)
            invAdd(inv, product, 1)
            statAdvance(stats.farming, crop.harvestXp * xpMods.get(player, stats.farming))
            state = state.copy(lives = state.lives - 1)
        }
        resetAnim()
        save(loc, PatchState(weeds = Farming.CLEAR))
        mes("The patch is now empty.")
    }

    private fun ProtectedAccess.clear(loc: BoundLocInfo) {
        val state = state(loc) ?: return
        if (state.empty) return
        save(loc, PatchState(weeds = Farming.CLEAR))
        mes("You clear the patch.")
    }

    private fun ProtectedAccess.inspect(loc: BoundLocInfo) {
        val layout = layouts[loc.id] ?: return
        val state = state(loc) ?: return
        val kind = layout.kind.name.lowercase()
        val soil = if (state.compost > 0) "The soil has been treated." else "The soil is untreated."
        val crop = cropsByPrefix[state.crop]
        val stages = crop?.let { layout.stages[it.stagePrefix] }
        val status =
            when {
                crop == null || stages == null ->
                    if (state.weeds < Farming.CLEAR) "The patch needs weeding."
                    else "The patch is empty."
                Farming.grown(state, crop, stages, now()) ->
                    "The ${crop.product.replace('_', ' ')} is ready to harvest."
                else ->
                    "Something is growing here (${Farming.step(crop, stages, state.plantedAt, now())}/${stages.steps})."
            }
        mes("This is a $kind patch. $soil $status")
    }

    private companion object {
        const val REFRESH_TICKS = 3
        const val SCAN_ZONES = 4
    }
}
