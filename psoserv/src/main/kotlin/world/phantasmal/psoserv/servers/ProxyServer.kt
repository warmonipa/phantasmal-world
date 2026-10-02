package world.phantasmal.psoserv.servers

import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psoserv.encryption.Cipher
import world.phantasmal.psoserv.messages.*
import java.net.Socket
import java.net.SocketException

class ProxyServer(
    name: String,
    bindPair: Inet4Pair,
    private val remotePair: Inet4Pair,
    private val messageDescriptor: MessageDescriptor<Message>,
    private val createCipher: (key: ByteArray) -> Cipher,
    private val redirectMap: Map<Inet4Pair, Inet4Pair> = emptyMap(),
) : Server(name, bindPair) {

    override fun clientConnected(connection: Connection) {
        val serverSocket = connection.connect(remotePair)
        logger.info {
            "Connected to server ${serverSocket.inetAddress}:${serverSocket.port}."
        }

        val handler = ServerHandler(serverSocket, connection.clientSocket)
        handler.startClientListener()
        handler.listen()
    }

    private inner class ServerHandler(
        serverSocket: Socket,
        private val clientSocket: Socket,
    ) : ProxySocketHandler("${name}_server", serverSocket) {

        private var clientThread: Thread? = null

        @Volatile
        var clientCiphers: Pair<Cipher, Cipher>? = null
            private set

        // The first message sent by the server is always unencrypted and initializes the
        // encryption. The client reader detects EOF immediately and rejects data sent before
        // these keys are available. Keys are published before the handshake reaches the client.
        override var readDecryptCipher: Cipher? = null
        override var readEncryptCipher: Cipher? = null
        override val writeEncryptCipher: Cipher? = null

        fun startClientListener() {
            val clientListener = ClientHandler(clientSocket, this)
            val thread = Thread(clientListener::listen)
            thread.name = "${name}_client"
            clientThread = thread
            thread.start()
        }

        override fun processMessage(message: Message): ProcessResult {
            when (message) {
                is InitEncryptionMessage -> if (readDecryptCipher == null) {
                    readDecryptCipher = createCipher(message.serverKey)
                    readEncryptCipher = createCipher(message.serverKey)

                    val clientDecryptCipher = createCipher(message.clientKey)
                    val clientEncryptCipher = createCipher(message.clientKey)

                    clientCiphers = clientDecryptCipher to clientEncryptCipher
                    logger.info { "Encryption initialized." }
                }

                is RedirectMessage -> {
                    val oldAddress = Inet4Pair(message.ipAddress, message.port.toInt())

                    redirectMap[oldAddress]?.let { newAddress ->
                        logger.debug {
                            "Rewriting redirect from $oldAddress to $newAddress."
                        }

                        message.ipAddress = newAddress.address.address
                        message.port = newAddress.port.toUShort()

                        return ProcessResult.Changed
                    }
                }
            }

            return ProcessResult.Ok
        }

        override fun processRawBytes(buffer: Buffer, offset: Int, size: Int) {
            clientSocket.write(buffer, offset, size)
        }

        override fun socketClosed() {
            // The peer must close even if the upstream disconnects before its handshake.
            clientSocket.close()
            clientThread?.join()
            clientThread = null
        }
    }

    private inner class ClientHandler(
        clientSocket: Socket,
        private val serverHandler: ServerHandler,
    ) : ProxySocketHandler("${name}_client", clientSocket) {

        private val ciphers: Pair<Cipher, Cipher>
            get() = checkNotNull(serverHandler.clientCiphers) {
                "Client sent data before the encryption handshake."
            }

        override val readDecryptCipher: Cipher get() = ciphers.first
        override val readEncryptCipher: Cipher get() = ciphers.second
        override val writeEncryptCipher: Cipher? = null

        override fun processMessage(message: Message): ProcessResult = ProcessResult.Ok

        override fun processRawBytes(buffer: Buffer, offset: Int, size: Int) {
            serverHandler.writeBytes(buffer, offset, size)
        }

        override fun socketClosed() {
            serverHandler.stop()
        }
    }

    private abstract inner class ProxySocketHandler(name: String, socket: Socket) :
        SocketHandler<Message>(name, socket) {

        override val messageDescriptor = this@ProxyServer.messageDescriptor

        override fun logMessageTooLarge(code: Int, size: Int, flags: Int) {
            logger.warn {
                val message = messageString(code, size, flags)
                "Sending $message with size ${size}B. Skipping because it's too large."
            }
        }

        override fun logMessageReceived(message: Message) {
            logger.trace { "Sent $message." }
        }

        override fun logUnexpectedSocketException(e: SocketException) {
            // Do nothing, we expect both server and client to close connections.
        }
    }
}
