package world.phantasmal.psolib.cursor

import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.buffer.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BufferCursorTests : WritableCursorTests() {
    override fun createCursor(bytes: ByteArray, endianness: Endianness, size: Int) =
        BufferCursor(Buffer.fromByteArray(bytes, endianness), size = size)

    @Test
    fun constructor_checks_offset_and_size_without_integer_overflow() {
        val buffer = Buffer.withSize(8)
        for ((offset, size) in listOf(-1 to 1, 9 to 0, 4 to 5, 4 to Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { BufferCursor(buffer, offset, size) }
        }
    }

    @Test
    fun view_writes_can_grow_the_buffer_without_changing_its_prefix() {
        val buffer = Buffer.fromByteArray(byteArrayOf(1, 2, 3, 4))
        val cursor = BufferCursor(buffer, offset = 2, size = 2)
        cursor.seekEnd(0).writeInt(0x08070605)

        assertEquals(6, cursor.size)
        assertEquals(6, cursor.position)
        assertEquals(8, buffer.size)
        assertEquals(0x04030201, buffer.getInt(0))
        assertEquals(0x08070605, buffer.getInt(4))
    }

    @Test
    fun growing_extent_cannot_overflow_absolute_offsets() {
        val buffer = Buffer.withSize(8)
        val cursor = BufferCursor(buffer, offset = 2, size = 4)
        cursor.seekStart(1)

        assertFailsWith<IllegalArgumentException> { cursor.size = Int.MAX_VALUE }
        assertFailsWith<IllegalArgumentException> { cursor.writeStringAscii("", Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { cursor.writeStringUtf16("", Int.MAX_VALUE) }
        assertEquals(1, cursor.position)
        assertEquals(4, cursor.size)
        assertEquals(8, buffer.size)
    }

    @Test
    fun resizing_a_view_grows_its_backing_buffer_from_any_position() {
        val buffer = Buffer.fromByteArray(byteArrayOf(1, 2, 3, 4))
        val cursor = BufferCursor(buffer, offset = 2, size = 2)
        cursor.size = 8

        assertEquals(8, cursor.size)
        assertEquals(0, cursor.position)
        assertEquals(10, buffer.size)
        assertEquals(0x04030201, buffer.getInt(0))
        cursor.seekEnd(0).writeByte(5)
        assertEquals(9, cursor.size)
        assertEquals(11, buffer.size)
        assertEquals(5, buffer.getByte(10).toInt())
    }

    @Test
    fun writeUByte_increases_size_correctly() {
        testIntegerWriteSize(1, { writeUByte(it.toUByte()) }, Endianness.Little)
        testIntegerWriteSize(1, { writeUByte(it.toUByte()) }, Endianness.Big)
    }

    @Test
    fun writeUShort_increases_size_correctly() {
        testIntegerWriteSize(2, { writeUShort(it.toUShort()) }, Endianness.Little)
        testIntegerWriteSize(2, { writeUShort(it.toUShort()) }, Endianness.Big)
    }

    @Test
    fun writeUInt_increases_size_correctly() {
        testIntegerWriteSize(4, { writeUInt(it.toUInt()) }, Endianness.Little)
        testIntegerWriteSize(4, { writeUInt(it.toUInt()) }, Endianness.Big)
    }

    @Test
    fun writeByte_increases_size_correctly() {
        testIntegerWriteSize(1, { writeByte(it.toByte()) }, Endianness.Little)
        testIntegerWriteSize(1, { writeByte(it.toByte()) }, Endianness.Big)
    }

    @Test
    fun writeShort_increases_size_correctly() {
        testIntegerWriteSize(2, { writeShort(it.toShort()) }, Endianness.Little)
        testIntegerWriteSize(2, { writeShort(it.toShort()) }, Endianness.Big)
    }

    @Test
    fun writeInt_increases_size_correctly() {
        testIntegerWriteSize(4, { writeInt(it) }, Endianness.Little)
        testIntegerWriteSize(4, { writeInt(it) }, Endianness.Big)
    }

    private fun testIntegerWriteSize(
        byteCount: Int,
        write: BufferCursor.(Int) -> Unit,
        endianness: Endianness,
    ) {
        val expectedNumber1 = 7891378
        val expectedNumber2 = 893894273

        val buffer = Buffer.withCapacity(8, endianness)
        val cursor = BufferCursor(buffer)

        assertEquals(0, buffer.size)
        assertEquals(0, cursor.size)

        cursor.write(expectedNumber1)

        assertEquals(byteCount, buffer.size)
        assertEquals(byteCount, cursor.size)

        cursor.write(expectedNumber2)

        assertEquals(2 * byteCount, buffer.size)
        assertEquals(2 * byteCount, cursor.size)
    }
}
