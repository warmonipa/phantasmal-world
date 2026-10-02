package world.phantasmal.psoserv.servers

import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psoserv.encryption.Cipher
import world.phantasmal.psoserv.encryption.BbCipher
import world.phantasmal.psoserv.encryption.PcCipher
import world.phantasmal.psoserv.messages.Message
import world.phantasmal.psoserv.messages.BbMessageDescriptor
import world.phantasmal.psoserv.messages.MessageDescriptor
import world.phantasmal.psoserv.messages.PcMessage
import world.phantasmal.psoserv.messages.PcMessageDescriptor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocketHandlerTests {
    private val key = byteArrayOf(1, 2, 3, 4)

    @Test
    fun fragmented_unencrypted_frame_is_forwarded_once_and_socket_is_closed_at_eof() {
        val frame = PcMessageDescriptor.createInitEncryption(key, key).buffer.bytes()
        val socket = ScriptedSocket(frame, listOf(2, 74))
        val handler = RecordingHandler(socket)

        handler.listen()

        assertContentEquals(frame, handler.forwarded.toByteArray())
        assertEquals(1, handler.messages.size)
        assertTrue(socket.isClosed)
        assertEquals(1, handler.closedCount)
    }

    @Test
    fun stateful_cipher_handles_split_headers_bodies_and_coalesced_frames() {
        val first = PcMessage.WelcomeMessage("first").buffer
        val redirect = PcMessage.Redirect(byteArrayOf(127, 0, 0, 1), 1234u).buffer
        val last = PcMessage.WelcomeMessage("last").buffer
        val sourceCipher = PcCipher(key)
        val source = listOf(first, redirect, last).flatMap { frame ->
            frame.copy().also { sourceCipher.encrypt(it) }.bytes().toList()
        }.toByteArray()

        // Exercise every possible split, including a complete encrypted header followed by a
        // partial body. Each stream also contains three adjacent frames.
        for (split in 1 until source.size) {
            val socket = ScriptedSocket(source, listOf(split, source.size - split))
            val handler = RecordingHandler(socket, encrypted = true, rewrite = true)
            handler.listen()

            assertEquals(3, handler.messages.size, "split=$split")
            assertEquals(listOf(0x13, 0x14, 0x13), handler.messages.map { it.code })
            val received = Buffer.fromByteArray(handler.forwarded.toByteArray())
            PcCipher(key).decrypt(received)
            val expectedRedirect = redirect.copy().also {
                PcMessage.Redirect(it).port = 4321u
            }
            assertContentEquals(
                first.bytes() + expectedRedirect.bytes() + last.bytes(), received.bytes(),
                "split=$split",
            )
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun oversized_passthrough_keeps_cipher_in_sync_for_the_next_rewritten_frame() {
        val oversized = Buffer.withSize(40000).setShort(0, 40000.toShort()).setByte(2, 0x7f)
        val redirect = PcMessage.Redirect(byteArrayOf(127, 0, 0, 1), 1234u).buffer
        val sourceCipher = PcCipher(key)
        val encrypted = Buffer.fromByteArray(oversized.bytes() + redirect.bytes())
        sourceCipher.encrypt(encrypted)
        val handler = RecordingHandler(
            ScriptedSocket(encrypted.bytes(), listOf(2, 10, 32768, 7232)),
            encrypted = true,
            rewrite = true,
        )

        handler.listen()

        val actual = Buffer.fromByteArray(handler.forwarded.toByteArray())
        PcCipher(key).decrypt(actual)
        PcMessage.Redirect(redirect).port = 4321u
        assertContentEquals(oversized.bytes() + redirect.bytes(), actual.bytes())
        assertEquals(listOf(0x14), handler.messages.map { it.code })
    }

    @Test
    fun bb_encrypted_header_body_and_padding_can_be_split_at_any_byte() {
        val bbKey = ByteArray(48) { it.toByte() }
        val first = Buffer.withSize(16).setShort(0, 9).setShort(2, 0x7fff).setByte(8, 42)
        val second = Buffer.withSize(8).setShort(0, 8).setShort(2, 0x7ffe)
        val encrypted = Buffer.fromByteArray(first.bytes() + second.bytes())
        BbCipher(bbKey).encrypt(encrypted)
        val source = encrypted.bytes()
        for (split in 1 until source.size) {
            val handler = RecordingHandler(
                ScriptedSocket(source, listOf(split, source.size - split)),
                encrypted = true,
                descriptor = BbMessageDescriptor,
                createCipher = { BbCipher(bbKey) },
            )
            handler.listen()
            assertEquals(listOf(0x7fff, 0x7ffe), handler.messages.map { it.code }, "split=$split")
            assertContentEquals(source, handler.forwarded.toByteArray(), "split=$split")
        }
    }

    @Test
    fun truncated_frame_is_not_forwarded_and_connection_is_closed() {
        val full = PcMessage.WelcomeMessage("truncated").buffer.bytes()
        for (size in 1 until full.size) {
            val socket = ScriptedSocket(full.copyOf(size), listOf(2, full.size))
            val handler = RecordingHandler(socket)
            handler.listen()
            assertEquals(0, handler.messages.size)
            assertEquals(0, handler.forwarded.size(), "size=$size")
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun processing_failure_closes_connection_before_a_later_frame_is_forwarded() {
        val frame = PcMessage.Login().buffer.bytes()
        val socket = ScriptedSocket(frame + frame, listOf(8))
        val handler = RecordingHandler(socket, failProcessing = true)
        handler.listen()
        assertEquals(1, handler.messages.size)
        assertEquals(0, handler.forwarded.size())
        assertTrue(socket.isClosed)
    }

    @Test
    fun undersized_message_header_closes_connection_without_processing() {
        for (size in 0 until PcMessageDescriptor.headerSize) {
            val bytes = Buffer.withSize(4).setShort(0, size.toShort()).bytes()
            val socket = ScriptedSocket(bytes, listOf(4))
            val handler = RecordingHandler(socket)
            handler.listen()
            assertEquals(0, handler.messages.size)
            assertEquals(0, handler.forwarded.size())
            assertTrue(socket.isClosed)
        }
    }

    private inner class RecordingHandler(
        socket: Socket,
        encrypted: Boolean = false,
        private val rewrite: Boolean = false,
        private val failProcessing: Boolean = false,
        descriptor: MessageDescriptor<Message> = PcMessageDescriptor,
        createCipher: () -> Cipher = { PcCipher(key) },
    ) : SocketHandler<Message>("socket_handler_test", socket) {
        override val messageDescriptor: MessageDescriptor<Message> = descriptor
        override val readDecryptCipher: Cipher? = if (encrypted) createCipher() else null
        override val readEncryptCipher: Cipher? = if (encrypted) createCipher() else null
        override val writeEncryptCipher: Cipher? = null
        val messages = mutableListOf<Message>()
        val forwarded = ByteArrayOutputStream()
        var closedCount = 0

        override fun processMessage(message: Message): ProcessResult {
            messages.add(message)
            check(!failProcessing) { "Test processing failure." }
            if (rewrite && message is PcMessage.Redirect) {
                message.port = 4321u
                return ProcessResult.Changed
            }
            return ProcessResult.Ok
        }

        override fun processRawBytes(buffer: Buffer, offset: Int, size: Int) {
            forwarded.write(buffer.byteArray, offset, size)
        }

        override fun socketClosed() {
            closedCount++
        }
    }

    private class ScriptedSocket(bytes: ByteArray, chunks: List<Int>) : Socket() {
        private val input = object : InputStream() {
            private var position = 0
            private var chunkIndex = 0
            private var chunkRemaining = chunks.first()

            override fun read(): Int =
                if (position == bytes.size) -1 else bytes[position++].toInt() and 0xff

            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (position == bytes.size) return -1
                if (length == 0) return 0
                if (chunkRemaining == 0) {
                    chunkIndex++
                    chunkRemaining = chunks.getOrElse(chunkIndex) { bytes.size }
                }
                val count = min(length, min(chunkRemaining, bytes.size - position))
                bytes.copyInto(target, offset, position, position + count)
                position += count
                chunkRemaining -= count
                return count
            }
        }

        override fun getInputStream(): InputStream = input
    }

    private fun Buffer.bytes(): ByteArray = byteArray.copyOf(size)
}
