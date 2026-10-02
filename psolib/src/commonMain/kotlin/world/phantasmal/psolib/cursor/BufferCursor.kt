package world.phantasmal.psolib.cursor

import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.buffer.Buffer

/**
 * @param buffer The Buffer to read from and write to.
 * @param offset The start offset of the part that will be read from.
 * @param size The size of the part that will be read from.
 */
class BufferCursor(
    private val buffer: Buffer,
    offset: Int = 0,
    size: Int = buffer.size - offset,
) : AbstractWritableCursor(offset) {
    private var _size = size

    override var size: Int
        get() = _size
        set(value) {
            require(value >= 0 && value <= Int.MAX_VALUE - offset) {
                "Size $value is out of bounds."
            }

            if (buffer.size < offset + value) {
                buffer.size = offset + value
            }

            _size = value

            if (position > _size) {
                position = _size
            }
        }

    /**
     * Mirrors the underlying buffer's endianness.
     */
    override var endianness: Endianness
        get() = buffer.endianness
        set(value) {
            buffer.endianness = value
        }

    init {
        require(offset in 0..buffer.size) {
            "Offset $offset is out of bounds."
        }

        require(size >= 0 && size <= buffer.size - offset) {
            "Size $size is out of bounds."
        }
    }

    override fun uByte(): UByte {
        requireSize(1)
        val r = buffer.getUByte(absolutePosition)
        position++
        return r
    }

    override fun uShort(): UShort {
        requireSize(2)
        val r = buffer.getUShort(absolutePosition)
        position += 2
        return r
    }

    override fun uInt(): UInt {
        requireSize(4)
        val r = buffer.getUInt(absolutePosition)
        position += 4
        return r
    }

    override fun byte(): Byte {
        requireSize(1)
        val r = buffer.getByte(absolutePosition)
        position++
        return r
    }

    override fun short(): Short {
        requireSize(2)
        val r = buffer.getShort(absolutePosition)
        position += 2
        return r
    }

    override fun int(): Int {
        requireSize(4)
        val r = buffer.getInt(absolutePosition)
        position += 4
        return r
    }

    override fun float(): Float {
        requireSize(4)
        val r = buffer.getFloat(absolutePosition)
        position += 4
        return r
    }

    override fun uByteArray(n: Int): UByteArray {
        requireSize(n)

        val array = UByteArray(n)

        for (i in 0 until n) {
            array[i] = buffer.getUByte(absolutePosition)
            position++
        }

        return array
    }

    override fun uShortArray(n: Int): UShortArray {
        requireSize(n, 2)

        val array = UShortArray(n)

        for (i in 0 until n) {
            array[i] = buffer.getUShort(absolutePosition)
            position += 2
        }

        return array
    }

    override fun uIntArray(n: Int): UIntArray {
        requireSize(n, 4)

        val array = UIntArray(n)

        for (i in 0 until n) {
            array[i] = buffer.getUInt(absolutePosition)
            position += 4
        }

        return array
    }

    override fun byteArray(n: Int): ByteArray {
        requireSize(n)

        val array = ByteArray(n)

        for (i in 0 until n) {
            array[i] = buffer.getByte(absolutePosition)
            position++
        }

        return array
    }

    override fun intArray(n: Int): IntArray {
        requireSize(n, 4)

        val array = IntArray(n)

        for (i in 0 until n) {
            array[i] = buffer.getInt(absolutePosition)
            position += 4
        }

        return array
    }

    override fun take(size: Int): Cursor {
        requireSize(size)
        val wrapper = BufferCursor(buffer, offset = absolutePosition, size)
        position += size
        return wrapper
    }

    override fun buffer(size: Int): Buffer {
        requireSize(size)
        val wrapper = buffer.slice(offset = absolutePosition, size)
        position += size
        return wrapper
    }

    override fun writeUByte(value: UByte): WritableCursor {
        ensureSpace(1)
        buffer.setUByte(absolutePosition, value)
        position++
        return this
    }

    override fun writeUShort(value: UShort): WritableCursor {
        ensureSpace(2)
        buffer.setUShort(absolutePosition, value)
        position += 2
        return this
    }

    override fun writeUInt(value: UInt): WritableCursor {
        ensureSpace(4)
        buffer.setUInt(absolutePosition, value)
        position += 4
        return this
    }

    override fun writeByte(value: Byte): WritableCursor {
        ensureSpace(1)
        buffer.setByte(absolutePosition, value)
        position++
        return this
    }

    override fun writeShort(value: Short): WritableCursor {
        ensureSpace(2)
        buffer.setShort(absolutePosition, value)
        position += 2
        return this
    }

    override fun writeInt(value: Int): WritableCursor {
        ensureSpace(4)
        buffer.setInt(absolutePosition, value)
        position += 4
        return this
    }

    override fun writeFloat(value: Float): WritableCursor {
        ensureSpace(4)
        buffer.setFloat(absolutePosition, value)
        position += 4
        return this
    }

    override fun writeUByteArray(array: UByteArray): WritableCursor {
        ensureSpace(array.size)
        return super.writeUByteArray(array)
    }

    override fun writeUShortArray(array: UShortArray): WritableCursor {
        ensureSpace(array.size, 2)
        return super.writeUShortArray(array)
    }

    override fun writeUIntArray(array: UIntArray): WritableCursor {
        ensureSpace(array.size, 4)
        return super.writeUIntArray(array)
    }

    override fun writeByteArray(array: ByteArray): WritableCursor {
        ensureSpace(array.size)
        return super.writeByteArray(array)
    }

    override fun writeIntArray(array: IntArray): WritableCursor {
        ensureSpace(array.size, 4)
        return super.writeIntArray(array)
    }

    override fun writeCursor(other: Cursor): WritableCursor {
        val size = other.size - other.position
        ensureSpace(size)
        return super.writeCursor(other)
    }

    override fun writeStringAscii(str: String, byteLength: Int): WritableCursor {
        ensureSpace(byteLength)
        return super.writeStringAscii(str, byteLength)
    }

    override fun writeStringUtf16(str: String, byteLength: Int): WritableCursor {
        ensureSpace(byteLength)
        return super.writeStringUtf16(str, byteLength)
    }

    private fun ensureSpace(size: Int, elementSize: Int = 1) {
        require(size >= 0 && size <= (Int.MAX_VALUE - absolutePosition) / elementSize) {
            "Size $size is out of bounds."
        }

        val endPosition = position + size * elementSize

        if (endPosition > _size) {
            this.size = endPosition
        }
    }
}

fun Buffer.cursor(offset: Int = 0, size: Int = this.size - offset): BufferCursor =
    BufferCursor(this, offset, size)
