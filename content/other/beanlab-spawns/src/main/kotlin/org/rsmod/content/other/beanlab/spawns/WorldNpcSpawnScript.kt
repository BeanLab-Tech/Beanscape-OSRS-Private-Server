package org.rsmod.content.other.beanlab.spawns

import com.github.michaelbull.logging.InlineLogger
import jakarta.inject.Inject
import java.io.DataInputStream
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.script.onEvent
import org.rsmod.game.entity.Npc
import org.rsmod.game.type.npc.NpcTypeList
import org.rsmod.map.CoordGrid
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Spawns the static NPCs outside Lumbridge.
 *
 * NPC positions are not in the client cache. RsMod only packs squares that a content plugin lists,
 * which today is the six Lumbridge squares in `LumbridgeNpcSpawns`. This queues the rest from
 * [SPAWN_RESOURCE] the same way map decoding does (`addDelayed`, permanent duration), so they
 * respawn after death. Ids that are not in this cache are skipped. The Lumbridge squares are not in
 * the file, so those hand-packed spawns are not doubled.
 *
 * The file is built from mejrs/data_osrs `NPCList_OSRS.json` (id, x, y, plane).
 */
class WorldNpcSpawnScript
@Inject
constructor(private val npcTypes: NpcTypeList, private val npcRepo: NpcRepository) :
    PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        onEvent<GameLifecycle.Startup> { spawnWorld() }
    }

    private fun spawnWorld() {
        val input =
            WorldNpcSpawnScript::class.java.getResourceAsStream(SPAWN_RESOURCE)
                ?: error("Missing NPC spawn resource: $SPAWN_RESOURCE")
        var spawned = 0
        var unknown = 0
        DataInputStream(input).use { data ->
            val magic = data.readInt()
            check(magic == MAGIC) { "Bad NPC spawn file magic: $magic" }
            val version = data.readUnsignedShort()
            check(version == VERSION) { "Unsupported NPC spawn file version: $version" }
            val count = data.readInt()
            repeat(count) {
                val id = data.readUnsignedShort()
                val x = data.readUnsignedShort()
                val z = data.readUnsignedShort()
                val level = data.readUnsignedByte()
                data.readUnsignedByte()
                val type = npcTypes[id]
                if (type == null) {
                    unknown++
                    return@repeat
                }
                npcRepo.addDelayed(
                    Npc(type, CoordGrid(x, z, level)),
                    spawnDelay = 0,
                    duration = Int.MAX_VALUE,
                )
                spawned++
            }
        }
        logger.info {
            "Queued $spawned world NPC spawns ($unknown skipped, unknown in this cache)."
        }
    }

    private companion object {
        const val SPAWN_RESOURCE = "world-npc-spawns.bin"
        const val MAGIC = 0x424E5043
        const val VERSION = 1
    }
}
