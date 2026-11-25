package org.rsmod.content.other.beanlab.soulwars

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.io.IOException
import java.nio.file.Path
import org.rsmod.api.config.refs.modlevels
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onCommand
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Npc
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.game.type.loc.LocType
import org.rsmod.game.type.loc.LocTypeList
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.game.type.npc.UnpackedNpcType
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Admin-only `::hub` commands that edit the Market Hub live. They keep a working copy of the layout
 * ([HubEditor], loaded the same way the portal script loads it) and mirror every change into the
 * world, so everyone sees it at once and `::hub save` writes exactly what the world shows. Floors
 * are not editable here: they live in the packed cache.
 */
class HubCommands
@Inject
constructor(
    private val protectedAccess: ProtectedAccessLauncher,
    private val locTypes: LocTypeList,
    private val npcTypes: NpcTypeList,
    private val locRepo: LocRepository,
    private val npcRepo: NpcRepository,
) : PluginScript() {
    private val logger = InlineLogger()

    private lateinit var editor: HubEditor

    private val locByName: Map<String, LocType> by lazy {
        buildMap { for (type in locTypes.values) type.internalName?.let { put(it, type) } }
    }

    private val npcByName: Map<String, UnpackedNpcType> by lazy {
        buildMap { for (type in npcTypes.values) type.internalName?.let { put(it, type) } }
    }

    override fun ScriptContext.startup() {
        editor = HubEditor(HubMapLoader.load().map)
        onCommand("hub") {
            modLevel = modlevels.admin
            desc = "Edit the Market Hub live: ::hub help"
            cheat { handle(this) }
        }
    }

    private fun handle(cheat: Cheat) {
        val player = cheat.player
        fun say(text: String) = player.mes(text)

        val args = cheat.args
        val sub = args.firstOrNull()?.lowercase() ?: "help"
        val here = local(player.coords)
        when (sub) {
            "help" -> HELP.forEach(::say)
            "tp" -> {
                val a = editor.map.arrival
                val dest = CoordGrid(ARENA_X + a.x, ARENA_Z + a.z, 0)
                if (!protectedAccess.launch(player) { telejump(dest) }) say("You are busy.")
            }
            "where" ->
                say(
                    if (here == null) "You are outside the hub."
                    else "Hub tile ${here.x}, ${here.z}"
                )
            "reload" -> {
                say("Edits already show live. A restart loads the saved file:")
                say(overridePath().toString())
            }
            "save" -> save(::say)
            "undo" -> {
                val before = editor.map
                val result = editor.undo()
                if (result.ok) mirror(before, editor.map)
                say(result.message)
            }
            "npc",
            "loc",
            "wall",
            "rm" -> {
                if (here == null) return say("Stand inside the hub first (::hub tp).")
                edit(sub, args.drop(1), here, player.displayName, ::say)
            }
            else -> say("Unknown ::hub command '$sub'. Try ::hub help")
        }
    }

    private fun edit(
        sub: String,
        args: List<String>,
        here: Point,
        who: String,
        say: (String) -> Unit,
    ) {
        val before = editor.map
        val result =
            when (sub) {
                "npc" -> {
                    val name = args.getOrNull(0) ?: return say("Use ::hub npc <internal_name>")
                    if (name !in npcByName) return say("No npc named '$name' in this cache.")
                    editor.placeNpc(who, name, here.x, here.z)
                }
                "loc" -> {
                    val name = args.getOrNull(0) ?: return say(USAGE_LOC)
                    if (name !in locByName) return say("No object named '$name' in this cache.")
                    var shape = LocShape.CentrepieceStraight
                    var angle = LocAngle.South
                    val rest = args.drop(1)
                    for ((i, word) in rest.withIndex()) {
                        val s = parseShape(word)
                        val a = parseAngle(word)
                        when {
                            s != null && i == 0 -> shape = s
                            a != null -> angle = a
                            else -> return say("Unknown shape or angle '$word'. $USAGE_LOC")
                        }
                    }
                    editor.placeLoc(who, name, here.x, here.z, shape, angle)
                }
                "wall" -> {
                    val name = args.getOrNull(0) ?: return say(USAGE_WALL)
                    if (name !in locByName) return say("No object named '$name' in this cache.")
                    val facing = parseAngle(args.getOrNull(1)) ?: return say(USAGE_WALL)
                    val length =
                        args.getOrNull(2)?.let { it.toIntOrNull() ?: return say(USAGE_WALL) } ?: 1
                    editor.placeWall(who, name, here.x, here.z, facing, length)
                }
                else -> editor.remove(who, here.x, here.z)
            }
        if (result.ok) {
            mirror(before, editor.map)
            logger.info { "[hub] $who: ${result.message}" }
        }
        say(if (result.ok) "Done: ${result.message}" else "Refused: ${result.message}")
    }

    private fun save(say: (String) -> Unit) {
        val problems = editor.problems()
        if (problems.isNotEmpty()) {
            say("Not saved, the layout has ${problems.size} problem(s):")
            problems.take(MAX_LINES).forEach(say)
            if (problems.size > MAX_LINES) say("... and ${problems.size - MAX_LINES} more")
            return
        }
        val path = overridePath()
        try {
            HubEditor.save(editor.map, path)
            say("Saved to $path (previous file kept as .bak).")
        } catch (e: IOException) {
            logger.warn(e) { "[hub] save failed" }
            say("Could not save: ${e.message}")
        }
    }

    /** Applies the spawn differences between two layouts to the world: removals, then additions. */
    private fun mirror(before: HubMap, after: HubMap) {
        val diff = HubDiff.between(before, after)
        for (npc in diff.removedNpcs) removeNpc(npc)
        for (loc in diff.removedLocs) {
            val type = locByName[loc.loc] ?: continue
            removeLoc(type, local(loc.x, loc.z), loc.shape, loc.angle)
        }
        for (piece in diff.removedWalls) {
            val type = locByName[piece.loc] ?: continue
            removeLoc(type, local(piece.x, piece.z), wallShape(piece), piece.angle)
        }
        for (npc in diff.addedNpcs) {
            val type = npcByName[npc.npc] ?: continue
            npcRepo.add(Npc(type, local(npc.x, npc.z)), Int.MAX_VALUE)
        }
        for (loc in diff.addedLocs) {
            val type = locByName[loc.loc] ?: continue
            locRepo.add(local(loc.x, loc.z), type, Int.MAX_VALUE, loc.angle, loc.shape)
        }
        for (piece in diff.addedWalls) {
            val type = locByName[piece.loc] ?: continue
            locRepo.add(local(piece.x, piece.z), type, Int.MAX_VALUE, piece.angle, wallShape(piece))
        }
    }

    private fun removeNpc(placement: NpcPlacement) {
        val coords = local(placement.x, placement.z)
        // NPCs may have wandered off their spawn tile, so match on where they were spawned.
        val found =
            npcRepo.findAll(ZoneKey.from(coords), NPC_SEARCH_ZONES).firstOrNull {
                it.spawnCoords == coords && it.type.internalName == placement.npc
            }
        if (found != null) npcRepo.del(found, Int.MAX_VALUE)
    }

    private fun removeLoc(type: LocType, coords: CoordGrid, shape: LocShape, angle: LocAngle) {
        val found =
            locRepo.findAll(coords).firstOrNull {
                it.id == type.id && it.shapeId == shape.id && it.angleId == angle.id
            }
        if (found != null) locRepo.del(found, Int.MAX_VALUE)
    }

    private fun wallShape(piece: WallPiece) =
        if (piece.corner) LocShape.WallL else LocShape.WallStraight

    private fun overridePath(): Path =
        Path.of(System.getenv(HubMapLoader.OVERRIDE_ENV) ?: HubMapLoader.DEFAULT_OVERRIDE)

    private fun local(x: Int, z: Int) = CoordGrid(ARENA_X + x, ARENA_Z + z, 0)

    /** Arena-local tile for [coords], or null when outside the hub. */
    private fun local(coords: CoordGrid): Point? {
        val x = coords.x - ARENA_X
        val z = coords.z - ARENA_Z
        val size = editor.map.size
        return if (coords.level == 0 && x in 0 until size && z in 0 until size) Point(x, z)
        else null
    }

    private companion object {
        // Same corner as SoulWarsPortalScript.ARENA_MIN (private there).
        const val ARENA_X = 5120
        const val ARENA_Z = 3072
        const val NPC_SEARCH_ZONES = 3
        const val MAX_LINES = 6
        const val USAGE_LOC = "Use ::hub loc <internal_name> [shape] [angle]"
        const val USAGE_WALL = "Use ::hub wall <internal_name> <north|south|east|west> [length]"

        val HELP =
            listOf(
                "::hub tp - go to the hub arrival tile; ::hub where - your hub tile",
                "::hub npc <name> - place an npc on your tile",
                "::hub loc <name> [shape] [angle] - place an object (default shape " +
                    "CentrepieceStraight, angle south)",
                "::hub wall <name> <north|south|east|west> [length] - wall run from your tile, " +
                    "facing that way; north/south walls run east, east/west walls run north",
                "::hub rm - remove the npc, object or wall piece on or next to your tile",
                "::hub undo - revert the last change (anyone's, shared)",
                "::hub save - write the layout to the override file (backup kept)",
                "::hub reload - a restart loads the saved file; edits already show live",
                "Floors are not editable here: they need generateHubTerrain and a cache repack.",
            )

        fun parseAngle(word: String?): LocAngle? =
            word?.let { w -> LocAngle.entries.firstOrNull { it.name.equals(w, true) } }

        fun parseShape(word: String): LocShape? =
            LocShape.entries.firstOrNull { it.name.equals(word, true) }
    }
}
