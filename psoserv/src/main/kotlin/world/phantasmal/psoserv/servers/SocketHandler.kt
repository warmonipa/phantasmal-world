package world.phantasmal.psoserv.servers

import mu.KLogger
import mu.KotlinLogging
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psoserv.alignToWidth
import world.phantasmal.psoserv.encryption.Cipher
import world.phantasmal.psoserv.messages.Message
import world.phantasmal.psoserv.messages.MessageDescriptor
import world.phantasmal.psoserv.messages.messageString
import java.io.EOFException
import java.net.Socket
import java.net.SocketException

abstract class SocketHandler<MessageType : Message>(
    protected val name: String,
    private val socket: Socket,
) {
    private val sockName: String = "${socket.remoteSocketAddress}"
    private val headerSize: Int get() = messageDescriptor.headerSize

    @Volatile
    private var running = false

    protected val logger: KLogger = KotlinLogging.logger(name)
    protected abstract val messageDescriptor: MessageDescriptor<MessageType>
    protected abstract val readDecryptCipher: Cipher?

    /**
     * Used by proxy servers to re-encrypt changed messages before sending them to the client.
     */
    protected abstract val readEncryptCipher: Cipher?

    protected abstract val writeEncryptCipher: Cipher?

    fun listen() {
        logger.info { "Listening to $name ($sockName)." }
        running = true

        try {
            val input = socket.getInputStream().buffered()
            val headerBuffer = Buffer.withSize(headerSize, Endianness.Little)

            while (true) {
                val headerBytes = input.readNBytes(headerBuffer.byteArray, 0, headerSize)
                if (headerBytes == 0) break
                if (headerBytes != headerSize) throw EOFException("Incomplete message header.")

                // A header consumes cipher state exactly once, even if the body arrives later.
                // Processing the handshake may replace these ciphers for the next frame.
                val decryptCipher = readDecryptCipher
                val encryptCipher = readEncryptCipher
                val decryptedHeader = headerBuffer.copy()
                decryptCipher?.let {
                    check(it.blockSize == headerSize)
                    it.decrypt(decryptedHeader)
                }

                val (code, size, flags) = messageDescriptor.readHeader(decryptedHeader)
                require(size in headerSize..0xffff) { "Invalid message size: $size." }
                val encryptedSize = alignToWidth(size, decryptCipher?.blockSize ?: 1)
                val rawBuffer = Buffer.withSize(encryptedSize, Endianness.Little)
                headerBuffer.copyInto(rawBuffer)
                val bodySize = encryptedSize - headerSize
                if (input.readNBytes(rawBuffer.byteArray, headerSize, bodySize) != bodySize) {
                    throw EOFException("Incomplete message body.")
                }

                if (encryptedSize > MAX_PARSED_MESSAGE_SIZE) {
                    // Preserve the proxy's passthrough boundary for large messages. Both ciphers
                    // must still consume exactly the blocks belonging to this frame.
                    logMessageTooLarge(code, size, flags)
                    decryptCipher?.advance(bodySize / decryptCipher.blockSize)
                    encryptCipher?.advance(encryptedSize / encryptCipher.blockSize)
                    processRawBytes(rawBuffer, 0, encryptedSize)
                    continue
                }

                val messageBuffer = rawBuffer.copy()
                decryptedHeader.copyInto(messageBuffer)
                decryptCipher?.decrypt(
                    messageBuffer,
                    offset = headerSize,
                    blocks = bodySize / decryptCipher.blockSize,
                )

                val message = messageDescriptor.readMessage(messageBuffer)
                logMessageReceived(message)
                val forwarded = when (processMessage(message)) {
                    ProcessResult.Ok -> {
                        encryptCipher?.advance(encryptedSize / encryptCipher.blockSize)
                        rawBuffer
                    }
                    ProcessResult.Changed -> {
                        // Encryption must not mutate the parsed message retained by its handler.
                        messageBuffer.copy().also { encryptCipher?.encrypt(it) }
                    }
                    ProcessResult.Done -> break
                }

                // Only complete frames are forwarded; no TCP read is forwarded a second time.
                processRawBytes(forwarded, 0, encryptedSize)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()

            logger.error(e) {
                "Interrupted while listening to $name ($sockName), closing connection."
            }
        } catch (e: SocketException) {
            // Don't log if we're not running anymore because that means this exception was probably
            // generated by a socket.close() call.
            if (running) {
                logUnexpectedSocketException(e)
            }
        } catch (e: Throwable) {
            logger.error(e) { "Error while listening to $name ($sockName), closing connection." }
        } finally {
            running = false

            try {
                logger.info { "Closing connection to $name ($sockName)." }
                socket.close()
            } finally {
                socketClosed()
            }
        }
    }

    fun writeBytes(buffer: Buffer, offset: Int, size: Int) {
        socket.write(buffer, offset, size)
    }

    fun stop() {
        running = false
        socket.close()
    }

    fun sendMessage(message: Message, encrypt: Boolean) {
        logger.trace {
            "Sending $message${if (encrypt) "" else " (unencrypted)"}."
        }

        val cipher = writeEncryptCipher
        val buffer: Buffer
        val expectedMaxSize: Int

        if (encrypt) {
            checkNotNull(cipher)
            // Pad buffer before encrypting.
            val initialSize = message.buffer.size
            buffer = message.buffer.copy(
                size = alignToWidth(initialSize, cipher.blockSize)
            )
            cipher.encrypt(buffer)
            expectedMaxSize = alignToWidth(message.size, cipher.blockSize)
        } else {
            buffer = message.buffer
            expectedMaxSize = message.size
        }

        // Message buffer can be padded for encryption in advance.
        if (message.buffer.size !in message.size..expectedMaxSize) {
            logger.warn {
                "Message size of $message is ${message.size}B, but wrote ${message.buffer.size} bytes."
            }
        }

        socket.write(buffer, 0, buffer.size)
    }

    protected fun isSockedClosed(): Boolean =
        socket.isClosed

    protected abstract fun processMessage(message: MessageType): ProcessResult

    protected open fun processRawBytes(buffer: Buffer, offset: Int, size: Int) {
        // Do nothing.
    }

    protected open fun socketClosed() {
        // Do nothing.
    }

    protected open fun logMessageTooLarge(code: Int, size: Int, flags: Int) {
        logger.warn {
            val message = messageString(code, size, flags)
            "Receiving $message with size ${size}B. Skipping because it's too large."
        }
    }

    protected open fun logMessageReceived(message: Message) {
        logger.trace { "Received $message." }
    }

    protected open fun logUnexpectedSocketException(e: SocketException) {
        logger.error(e) {
            "Error while listening to $name ($sockName), closing connection."
        }
    }

    protected enum class ProcessResult {
        Ok, Changed, Done
    }

    companion object {
        private const val MAX_PARSED_MESSAGE_SIZE: Int = 32768
    }
}
