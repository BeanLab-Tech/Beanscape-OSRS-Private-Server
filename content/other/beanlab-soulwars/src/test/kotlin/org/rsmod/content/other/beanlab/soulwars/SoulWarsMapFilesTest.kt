package org.rsmod.content.other.beanlab.soulwars

import io.netty.buffer.Unpooled
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.rsmod.api.cache.map.loc.MapLocListDecoder
import org.rsmod.api.cache.map.tile.MapTileDecoder
import org.rsmod.api.cache.util.InlineByteBuf
import org.rsmod.content.other.beanlab.soulwars.map.SoulWarsMapFiles

class SoulWarsMapFilesTest {
    private fun read(name: String): ByteArray {
        val stream = SoulWarsPortalScript::class.java.getResourceAsStream("map/$name")
        assertNotNull(stream, "missing $name")
        return stream.use { it.readAllBytes() }
    }

    private fun parse(square: String): Pair<Int, Int> {
        val parts = square.split("_")
        return parts[0].toInt() to parts[1].toInt()
    }

    /**
     * The committed `m` files must match [HubLayout]. After editing the layout, run this test once
     * with `SOULWARS_REGEN=1` to rewrite them (then re-run without it).
     */
    @Test
    fun `terrain files match the hub layout`() {
        val regen = System.getenv("SOULWARS_REGEN") == "1"
        val dir = File("src/main/resources/org/rsmod/content/other/beanlab/soulwars/map")
        for (square in SoulWarsMapFiles.SQUARES) {
            val (x, z) = parse(square)
            val expected = HubLayout.terrain(x, z)
            if (regen) {
                File(dir, "m$square").writeBytes(expected)
            } else {
                assertContentEquals(expected, read("m$square"), "m$square is stale")
            }
        }
    }

    @Test
    fun `terrain files decode to coloured level zero tiles`() {
        for (square in SoulWarsMapFiles.SQUARES) {
            val bytes = read("m$square")
            val buf = Unpooled.wrappedBuffer(bytes)
            val def = MapTileDecoder.decode(buf)
            assertEquals(64 * 64, def.underlays.size, "m$square underlays")
            assertTrue(def.overlays.isEmpty())
            assertEquals(0, buf.readableBytes(), "m$square has trailing bytes")
            MapTileDecoder.decode(InlineByteBuf(bytes))
        }
    }

    @Test
    fun `loc files decode to no spawns`() {
        for (square in SoulWarsMapFiles.SQUARES) {
            val def = MapLocListDecoder.decode(InlineByteBuf(read("l$square")))
            assertTrue(def.spawns.isEmpty())
        }
    }

    @Test
    fun `layout has no overlapping rooms and every spawn is inside a room`() {
        val rooms = HubLayout.SHOPS + HubLayout.EXCHANGE
        for (a in rooms) {
            for (b in rooms) {
                if (a === b) continue
                val overlap = a.x0 <= b.x1 && b.x0 <= a.x1 && a.z0 <= b.z1 && b.z0 <= a.z1
                assertTrue(!overlap, "${a.name} overlaps ${b.name}")
            }
        }
        for (s in HubLayout.SPAWNS) {
            val inside =
                rooms.any { s.x in (it.x0 + 1) until it.x1 && s.z in (it.z0 + 1) until it.z1 }
            assertTrue(inside, "${s.npc} at ${s.x},${s.z} is not inside a room")
        }
    }
}
