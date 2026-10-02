package world.phantasmal.psolib.cursor

import world.phantasmal.psolib.Endianness
import world.phantasmal.core.Success
import world.phantasmal.psolib.fileFormats.ninja.parseNj
import world.phantasmal.psolib.test.LibTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Test suite for all [Cursor] implementations. There is a subclass of this suite for every [Cursor]
 * implementation.
 */
abstract class CursorTests : LibTestSuite {
    abstract fun createCursor(
        bytes: ByteArray,
        endianness: Endianness,
        size: Int = bytes.size,
    ): Cursor

    @Test
    fun reads_cannot_escape_nested_views() {
        val reads: List<Pair<Int, Cursor.() -> Any>> = listOf(
            1 to { uByte() },
            2 to { uShort() },
            4 to { uInt() },
            1 to { byte() },
            2 to { short() },
            4 to { int() },
            4 to { float() },
            2 to { uByteArray(2) },
            4 to { uShortArray(2) },
            8 to { uIntArray(2) },
            2 to { byteArray(2) },
            8 to { intArray(2) },
            4 to { take(4) },
            4 to { buffer(4) },
        )

        for (endianness in Endianness.values()) {
            for ((size, read) in reads) {
                val parent = createCursor(ByteArray(32), endianness).seekStart(3).take(16)
                val child = parent.seekStart(2).take(size - 1)

                assertFailsWith<IllegalArgumentException> { child.read() }
                assertEquals(0, child.position)
                assertEquals(size - 1, child.bytesLeft)
                assertEquals(size + 1, parent.position)
            }
        }
    }

    @Test
    fun negative_lengths_do_not_change_position() {
        val reads: List<Cursor.() -> Any> = listOf(
            { uByteArray(-1) },
            { uShortArray(-1) },
            { uIntArray(-1) },
            { byteArray(-1) },
            { intArray(-1) },
            { take(-1) },
            { buffer(-1) },
            { stringAscii(-1) },
            { stringUtf16(-1) },
        )

        for (read in reads) {
            val cursor = createCursor(ByteArray(16), Endianness.Little).seekStart(3)
            assertFailsWith<IllegalArgumentException> { cursor.read() }
            assertEquals(3, cursor.position)
            assertEquals(16, cursor.size)
        }
    }

    @Test
    fun array_byte_lengths_cannot_overflow_before_bounds_checks() {
        val reads: List<Cursor.(Int) -> Any> = listOf(
            { n -> uShortArray(n) },
            { n -> uIntArray(n) },
            { n -> intArray(n) },
        )

        for (read in reads) {
            for (length in listOf(Int.MIN_VALUE, -0x40000000, 0x40000000, Int.MAX_VALUE)) {
                val cursor = createCursor(ByteArray(8), Endianness.Little).seekStart(1)
                assertFailsWith<IllegalArgumentException> { cursor.read(length) }
                assertEquals(1, cursor.position)
            }
        }
    }

    @Test
    fun nested_views_allow_exact_boundary_and_empty_reads() {
        val cursor = createCursor(byteArrayOf(1, 2, 3, 4, 5, 6), Endianness.Little)
        val child = cursor.seekStart(1).take(4).seekStart(1).take(2)

        assertEquals(0x0403, child.uShort().toInt())
        assertEquals(0, child.bytesLeft)
        assertEquals(0, child.take(0).size)
        assertEquals(0, child.buffer(0).size)
        assertEquals(5, cursor.position)
        assertEquals(6, cursor.byte().toInt())
    }

    @Test
    fun strings_cannot_read_a_terminator_outside_the_view() {
        val ascii = createCursor(byteArrayOf(65, 0), Endianness.Little, size = 1)
        assertFailsWith<IllegalArgumentException> { ascii.stringAscii(2) }
        assertEquals(1, ascii.position)

        val utf16 = createCursor(byteArrayOf(65, 0, 0, 0), Endianness.Little, size = 2)
        assertFailsWith<IllegalArgumentException> { utf16.stringUtf16(4) }
        assertEquals(2, utf16.position)
    }

    @Test
    fun null_terminated_strings_can_stop_before_their_length_limit() {
        val ascii = createCursor(byteArrayOf(65, 0), Endianness.Little)
        assertEquals("A", ascii.stringAscii(Int.MAX_VALUE, dropRemaining = false))
        assertEquals(2, ascii.position)

        val utf16 = createCursor(byteArrayOf(65, 0, 0, 0), Endianness.Little)
        assertEquals("A", utf16.stringUtf16(Int.MAX_VALUE, dropRemaining = false))
        assertEquals(4, utf16.position)
    }

    @Test
    fun seek_rejects_out_of_bounds_offsets_without_moving() {
        val cursor = createCursor(ByteArray(16), Endianness.Little).seekStart(3)
        for (offset in listOf(Int.MIN_VALUE, -4, 14, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { cursor.seek(offset) }
            assertEquals(3, cursor.position)
        }
        for (offset in listOf(-1, 17, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { cursor.seekStart(offset) }
            assertFailsWith<IllegalArgumentException> { cursor.seekEnd(offset) }
            assertEquals(3, cursor.position)
        }
    }

    @Test
    fun nj_object_cannot_consume_bytes_after_its_declared_chunk() {
        // A valid zeroed NJS_OBJECT needs 52 bytes. Padding outside a one-byte NJCM
        // payload must not be accepted as the rest of that object.
        val bytes = ByteArray(64)
        bytes[0] = 'N'.code.toByte()
        bytes[1] = 'J'.code.toByte()
        bytes[2] = 'C'.code.toByte()
        bytes[3] = 'M'.code.toByte()
        bytes[4] = 1
        assertFailsWith<IllegalArgumentException> {
            parseNj(createCursor(bytes, Endianness.Little))
        }

        bytes[4] = 52
        val result = assertIs<Success<*>>(parseNj(createCursor(bytes, Endianness.Little)))
        assertEquals(1, (result.value as List<*>).size)
    }

    @Test
    fun simple_cursor_properties_and_invariants() {
        simple_cursor_properties_and_invariants(Endianness.Little)
        simple_cursor_properties_and_invariants(Endianness.Big)
    }

    private fun simple_cursor_properties_and_invariants(endianness: Endianness) {
        val cursor = createCursor(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), endianness)

        for ((seek_to, expectedPos) in listOf(
            0 to 0,
            3 to 3,
            5 to 8,
            2 to 10,
            -10 to 0,
        )) {
            cursor.seek(seek_to)

            assertEquals(10, cursor.size)
            assertEquals(expectedPos, cursor.position)
            assertEquals(cursor.position + cursor.bytesLeft, cursor.size)
            assertEquals(endianness, cursor.endianness)
        }
    }

    @Test
    fun cursor_handles_byte_order_correctly() {
        cursor_handles_byte_order_correctly(Endianness.Little)
        cursor_handles_byte_order_correctly(Endianness.Big)
    }

    private fun cursor_handles_byte_order_correctly(endianness: Endianness) {
        val cursor = createCursor(byteArrayOf(1, 2, 3, 4), endianness)

        if (endianness == Endianness.Little) {
            assertEquals(0x04030201u, cursor.uInt())
        } else {
            assertEquals(0x01020304u, cursor.uInt())
        }
    }

    @Test
    fun uByte() {
        testIntegerRead(1, { uByte().toInt() }, Endianness.Little)
        testIntegerRead(1, { uByte().toInt() }, Endianness.Big)
    }

    @Test
    fun uShort() {
        testIntegerRead(2, { uShort().toInt() }, Endianness.Little)
        testIntegerRead(2, { uShort().toInt() }, Endianness.Big)
    }

    @Test
    fun uInt() {
        testIntegerRead(4, { uInt().toInt() }, Endianness.Little)
        testIntegerRead(4, { uInt().toInt() }, Endianness.Big)
    }

    @Test
    fun byte() {
        testIntegerRead(1, { byte().toInt() }, Endianness.Little)
        testIntegerRead(1, { byte().toInt() }, Endianness.Big)
    }

    @Test
    fun short() {
        testIntegerRead(2, { short().toInt() }, Endianness.Little)
        testIntegerRead(2, { short().toInt() }, Endianness.Big)
    }

    @Test
    fun int() {
        testIntegerRead(4, { int() }, Endianness.Little)
        testIntegerRead(4, { int() }, Endianness.Big)
    }

    /**
     * Reads two integers.
     */
    private fun testIntegerRead(byteCount: Int, read: Cursor.() -> Int, endianness: Endianness) {
        // Generate two numbers of the form 0x010203...
        val expectedNumber1 = 0x01020304 shr (8 * (4 - byteCount))
        val expectedNumber2 = 0x05060708 shr (8 * (4 - byteCount))

        // Put them in a byte array.
        val bytes = ByteArray(2 * byteCount)

        for (i in 0 until byteCount) {
            val shift =
                if (endianness == Endianness.Little) {
                    8 * i
                } else {
                    8 * (byteCount - i - 1)
                }

            bytes[i] = (expectedNumber1 shr shift).toByte()
            bytes[byteCount + i] = (expectedNumber2 shr shift).toByte()
        }

        // Check that individual bytes are in the correct order when read as part of a larger
        // integer.
        val cursor = createCursor(bytes, endianness)

        assertEquals(expectedNumber1, cursor.read())
        assertEquals(byteCount, cursor.position)

        assertEquals(expectedNumber2, cursor.read())
        assertEquals(2 * byteCount, cursor.position)
    }

    @Test
    fun float() {
        float(Endianness.Little)
        float(Endianness.Big)
    }

    private fun float(endianness: Endianness) {
        val bytes = byteArrayOf(0x40, 0x20, 0, 0, 0x42, 1, 0, 0)

        if (endianness == Endianness.Little) {
            bytes.reverse(0, 4)
            bytes.reverse(4, 8)
        }

        val cursor = createCursor(bytes, endianness)

        assertEquals(2.5f, cursor.float())
        assertEquals(4, cursor.position)

        assertEquals(32.25f, cursor.float())
        assertEquals(8, cursor.position)
    }

    @Test
    fun uByteArray() {
        val read: Cursor.(Int) -> IntArray = { n ->
            val arr = uByteArray(n)
            IntArray(n) { arr[it].toInt() }
        }

        testIntegerArrayRead(1, read, Endianness.Little)
        testIntegerArrayRead(1, read, Endianness.Big)
    }

    @Test
    fun uShortArray() {
        val read: Cursor.(Int) -> IntArray = { n ->
            val arr = uShortArray(n)
            IntArray(n) { arr[it].toInt() }
        }

        testIntegerArrayRead(2, read, Endianness.Little)
        testIntegerArrayRead(2, read, Endianness.Big)
    }

    @Test
    fun uIntArray() {
        val read: Cursor.(Int) -> IntArray = { n ->
            val arr = uIntArray(n)
            IntArray(n) { arr[it].toInt() }
        }

        testIntegerArrayRead(4, read, Endianness.Little)
        testIntegerArrayRead(4, read, Endianness.Big)
    }

    @Test
    fun byteArray() {
        val read: Cursor.(Int) -> IntArray = { n ->
            val arr = byteArray(n)
            IntArray(n) { arr[it].toInt() }
        }

        testIntegerArrayRead(1, read, Endianness.Little)
        testIntegerArrayRead(1, read, Endianness.Big)
    }

    @Test
    fun intArray() {
        val read: Cursor.(Int) -> IntArray = { n ->
            val arr = intArray(n)
            IntArray(n) { arr[it] }
        }

        testIntegerArrayRead(4, read, Endianness.Little)
        testIntegerArrayRead(4, read, Endianness.Big)
    }

    private fun testIntegerArrayRead(
        byteCount: Int,
        read: Cursor.(Int) -> IntArray,
        endianness: Endianness,
    ) {
        // Generate array of the form 1, 2, 3, 4, 5, 6, 7, 8.
        val bytes = ByteArray(8 * byteCount)

        for (i in 0 until 8) {
            if (endianness == Endianness.Little) {
                bytes[i * byteCount] = (i + 1).toByte()
            } else {
                bytes[i * byteCount + byteCount - 1] = (i + 1).toByte()
            }
        }

        // Test cursor.
        val cursor = createCursor(bytes, endianness)

        val array1 = cursor.read(3)
        assertEquals(1, array1[0])
        assertEquals(2, array1[1])
        assertEquals(3, array1[2])
        assertEquals(3 * byteCount, cursor.position)

        cursor.seekStart(2 * byteCount)
        val array2 = cursor.read(4)
        assertEquals(3, array2[0])
        assertEquals(4, array2[1])
        assertEquals(5, array2[2])
        assertEquals(6, array2[3])
        assertEquals(6 * byteCount, cursor.position)

        cursor.seekStart(5 * byteCount)
        val array3 = cursor.read(3)
        assertEquals(6, array3[0])
        assertEquals(7, array3[1])
        assertEquals(8, array3[2])
        assertEquals(8 * byteCount, cursor.position)
    }

    @Test
    fun take() {
        testTake(Endianness.Little)
        testTake(Endianness.Big)
    }

    private fun testTake(endianness: Endianness) {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val cursor = createCursor(bytes, endianness)

        val newCursor = cursor.seek(2).take(4)

        assertEquals(6, cursor.position)
        assertEquals(4, newCursor.size)
        assertEquals(3u, newCursor.uByte())
        assertEquals(4u, newCursor.uByte())
        assertEquals(5u, newCursor.uByte())
        assertEquals(6u, newCursor.uByte())
    }

    @Test
    fun stringAscii() {
        testStringRead(1, Cursor::stringAscii, Endianness.Little)
        testStringRead(1, Cursor::stringAscii, Endianness.Big)
    }

    @Test
    fun stringUtf16() {
        testStringRead(2, Cursor::stringUtf16, Endianness.Little)
        testStringRead(2, Cursor::stringUtf16, Endianness.Big)
    }

    private fun testStringRead(
        byteCount: Int,
        read: Cursor.(
            maxByteLength: Int,
            nullTerminated: Boolean,
            dropRemaining: Boolean,
        ) -> String,
        endianness: Endianness,
    ) {
        val chars = byteArrayOf(7, 65, 66, 0, (255).toByte(), 13)
        val bytes = ByteArray(chars.size * byteCount)

        for (i in chars.indices) {
            if (endianness == Endianness.Little) {
                bytes[byteCount * i] = chars[i]
            } else {
                bytes[byteCount * i + byteCount - 1] = chars[i]
            }
        }

        val cursor = createCursor(bytes, endianness)

        cursor.seekStart(byteCount)
        assertEquals("AB", cursor.read(4 * byteCount, true, true))
        assertEquals(5 * byteCount, cursor.position)
        cursor.seekStart(byteCount)
        assertEquals("AB", cursor.read(2 * byteCount, true, true))
        assertEquals(3 * byteCount, cursor.position)

        cursor.seekStart(byteCount)
        assertEquals("AB", cursor.read(4 * byteCount, true, false))
        assertEquals(4 * byteCount, cursor.position)
        cursor.seekStart(byteCount)
        assertEquals("AB", cursor.read(2 * byteCount, true, false))
        assertEquals(3 * byteCount, cursor.position)

        cursor.seekStart(byteCount)
        assertEquals("AB\u0000ÿ", cursor.read(4 * byteCount, false, true))
        assertEquals(5 * byteCount, cursor.position)

        cursor.seekStart(byteCount)
        assertEquals("AB\u0000ÿ", cursor.read(4 * byteCount, false, false))
        assertEquals(5 * byteCount, cursor.position)
    }

    @Test
    fun buffer() {
        testBuffer(Endianness.Little)
        testBuffer(Endianness.Big)
    }

    private fun testBuffer(endianness: Endianness) {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val cursor = createCursor(bytes, endianness)

        val buf = cursor.seek(2).buffer(4)

        assertEquals(6, cursor.position)
        assertEquals(4, buf.size)
        assertEquals(3u, buf.getUByte(0))
        assertEquals(4u, buf.getUByte(1))
        assertEquals(5u, buf.getUByte(2))
        assertEquals(6u, buf.getUByte(3))
    }
}
