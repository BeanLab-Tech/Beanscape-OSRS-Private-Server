package org.rsmod.content.other.beanlab.soulwars

import kotlin.test.Test
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

    @Test
    fun `terrain files decode to four levels of 64x64 tiles`() {
        for (square in SoulWarsMapFiles.SQUARES) {
            val bytes = read("m$square")
            val def = MapTileDecoder.decode(io.netty.buffer.Unpooled.wrappedBuffer(bytes))
            assertEquals(64 * 64, def.underlays.size, "m$square underlays")
            assertTrue(def.overlays.isEmpty())
            // The decoder must also consume the whole file.
            val buf = io.netty.buffer.Unpooled.wrappedBuffer(bytes)
            MapTileDecoder.decode(buf)
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
}
