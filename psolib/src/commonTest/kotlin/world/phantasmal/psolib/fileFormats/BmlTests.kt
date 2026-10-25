package world.phantasmal.psolib.fileFormats

import world.phantasmal.core.Success
import world.phantasmal.core.Failure
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.readFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BmlTests : LibTestSuite {
    private fun archive(count: Int, paired: Boolean = true): Buffer {
        val start = if (count <= 31) 0x800 else 0x1000
        val step = if (paired) 0x20 else 0x800
        val buffer = Buffer.withSize(start + count * step * 2)
        buffer.setInt(4, count)
        buffer.setByte(8, 0x50)
        buffer.setByte(9, if (paired) 1 else 0)
        for (i in 0 until count) {
            val header = 0x40 + i * 0x40
            buffer.setInt(header + 0x20, 3)
            buffer.setInt(header + 0x28, 1)
            buffer.setInt(header + 0x2C, 3)
            buffer.setInt(header + 0x30, 1)
            buffer.setByte(start + i * step * 2, (i + 1).toByte())
            buffer.setByte(start + i * step * 2 + step, (i + 101).toByte())
        }
        return buffer
    }

    @Test fun directory_page_boundaries_and_stream_alignment() {
        for (count in listOf(2, 31, 32, 42)) for (paired in listOf(false, true)) {
            val entries = parseBml(archive(count, paired).cursor()).unwrap()
            assertEquals(count, entries.size)
            for ((i, entry) in entries.withIndex()) {
                assertEquals((i + 1).toByte(), entry.data.getByte(0), "count=$count paired=$paired entry=$i")
                assertEquals((i + 101).toByte(), entry.textureData.getByte(0))
                assertEquals(1, entry.decompressedSize)
                assertEquals(1, entry.decompressedTextureSize)
            }
        }
    }

    @Test fun corrupt_archive_is_not_partial_success() {
        val truncated = archive(2).apply { size = 0x841 }
        assertIs<Failure>(parseBml(truncated.cursor()))
        val negative = archive(2).apply { setInt(0x60, -1) }
        assertIs<Failure>(parseBml(negative.cursor()))
        val overflow = archive(2).apply { setInt(0x60, Int.MAX_VALUE) }
        assertIs<Failure>(parseBml(overflow.cursor()))
        val directory = archive(42).apply { size = 0x800 }
        assertIs<Failure>(parseBml(directory.cursor()))
        val texture = archive(2).apply { size = 0x822 }
        assertIs<Failure>(parseBml(texture.cursor()))
        val negativeTexture = archive(2).apply { setInt(0x6C, -1) }
        assertIs<Failure>(parseBml(negativeTexture.cursor()))
        val unsupported = archive(2).apply { setByte(8, 0) }
        assertIs<Failure>(parseBml(unsupported.cursor()))
    }

    @Test
    fun parse_small_bml() = testAsync {
        val result = parseBml(readFile("/minimal.bml"))

        assertIs<Success<List<BmlEntry>>>(result)

        val entries = result.value

        assertEquals(2, entries.size)

        // Entry 1: model_a.nj with model data and texture data.
        assertEquals("model_a.nj", entries[0].name)
        assertEquals(16, entries[0].data.size)
        assertEquals(0xAA.toByte(), entries[0].data.getByte(0))
        assertEquals(8, entries[0].textureData.size)
        assertEquals(0xBB.toByte(), entries[0].textureData.getByte(0))

        // Entry 2: model_b.xj with model data only, no textures.
        assertEquals("model_b.xj", entries[1].name)
        assertEquals(24, entries[1].data.size)
        assertEquals(0xCC.toByte(), entries[1].data.getByte(0))
        assertEquals(0, entries[1].textureData.size)
    }
}
