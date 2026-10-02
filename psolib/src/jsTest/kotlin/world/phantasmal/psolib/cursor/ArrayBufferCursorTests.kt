package world.phantasmal.psolib.cursor

import org.khronos.webgl.Uint8Array
import world.phantasmal.psolib.Endianness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArrayBufferCursorTests : WritableCursorTests() {
    override fun createCursor(bytes: ByteArray, endianness: Endianness, size: Int) =
        ArrayBufferCursor(Uint8Array(bytes.toTypedArray()).buffer, endianness, size = size)

    @Test
    fun constructor_checks_offset_and_size_without_integer_overflow() {
        val buffer = Uint8Array(8).buffer
        for ((offset, size) in listOf(-1 to 1, 9 to 0, 4 to 5, 4 to Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                ArrayBufferCursor(buffer, Endianness.Little, offset, size)
            }
        }
    }

    @Test
    fun size_cannot_grow_past_the_backing_buffer() {
        val cursor = ArrayBufferCursor(Uint8Array(8).buffer, Endianness.Little, 2, 3)
        cursor.seekStart(2)
        for (size in listOf(7, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { cursor.size = size }
            assertEquals(3, cursor.size)
            assertEquals(2, cursor.position)
        }
        cursor.size = 6
        assertEquals(6, cursor.size)
        assertEquals(2, cursor.position)
    }

    @Test
    fun writes_stay_inside_the_view_even_when_the_backing_buffer_has_space() {
        val writes: List<Pair<Int, WritableCursor.() -> Any>> = listOf(
            1 to { writeUByte(1u) },
            2 to { writeUShort(1u) },
            4 to { writeUInt(1u) },
            1 to { writeByte(1) },
            2 to { writeShort(1) },
            4 to { writeInt(1) },
            4 to { writeFloat(1f) },
            2 to { writeUByteArray(ubyteArrayOf(1u, 2u)) },
            4 to { writeUShortArray(ushortArrayOf(1u, 2u)) },
            8 to { writeUIntArray(uintArrayOf(1u, 2u)) },
            2 to { writeByteArray(byteArrayOf(1, 2)) },
            8 to { writeIntArray(intArrayOf(1, 2)) },
            2 to { writeStringAscii("ab", 2) },
            4 to { writeStringUtf16("ab", 4) },
        )

        for ((size, write) in writes) {
            val bytes = Uint8Array(16)
            val cursor = ArrayBufferCursor(bytes.buffer, Endianness.Little, 3, size - 1)
            assertFailsWith<IllegalArgumentException> { cursor.write() }
            assertEquals(0, cursor.position)
            assertEquals(size - 1, cursor.size)
            val verification = ArrayBufferCursor(bytes.buffer, Endianness.Little)
            repeat(16) { assertEquals(0, verification.byte().toInt()) }
        }
    }
}
