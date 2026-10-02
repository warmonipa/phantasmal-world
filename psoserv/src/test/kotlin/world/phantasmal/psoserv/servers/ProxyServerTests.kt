package world.phantasmal.psoserv.servers

import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psoserv.encryption.BbCipher
import world.phantasmal.psoserv.encryption.PcCipher
import world.phantasmal.psoserv.messages.BbMessageDescriptor
import world.phantasmal.psoserv.messages.Message
import world.phantasmal.psoserv.messages.MessageDescriptor
import world.phantasmal.psoserv.messages.PcMessage
import world.phantasmal.psoserv.messages.PcMessageDescriptor
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ProxyServerTests {
    private val loopback = inet4Address("127.0.0.1")
    private val key = byteArrayOf(1, 2, 3, 4)

    @Test
    fun accepts_multiple_connections_while_first_waits_for_handshake() {
        ServerSocket(0, 10, loopback).use { remote ->
            remote.soTimeout = 2000
            withProxy(remote.localPort) { _, port ->
                connect(port).use {
                    remote.accept().use {
                        connect(port).use {
                            remote.accept().use { secondUpstream ->
                                assertEquals(loopback, secondUpstream.inetAddress)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun upstream_connection_failure_closes_client_and_next_connection_can_recover() {
        val remotePort = unusedPort()
        withProxy(remotePort) { _, port ->
            connect(port).use { failedClient ->
                assertEquals(-1, failedClient.getInputStream().read())
            }
            ServerSocket(remotePort, 10, loopback).use { remote ->
                remote.soTimeout = 2000
                connect(port).use {
                    remote.accept().use { recovered ->
                        assertEquals(loopback, recovered.inetAddress)
                    }
                }
            }
        }
    }

    @Test
    fun stop_closes_both_sockets_before_and_after_handshake() {
        for (handshake in listOf(false, true)) {
            ServerSocket(0, 10, loopback).use { remote ->
                remote.soTimeout = 2000
                withProxy(remote.localPort) { proxy, port ->
                    connect(port).use { client ->
                        remote.accept().use { upstream ->
                            upstream.soTimeout = 2000
                            if (handshake) initialize(upstream, client)
                            proxy.stop()
                            assertEquals(-1, client.getInputStream().read())
                            assertEquals(-1, upstream.getInputStream().read())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun upstream_eof_before_handshake_closes_client() {
        ServerSocket(0, 10, loopback).use { remote ->
            remote.soTimeout = 2000
            withProxy(remote.localPort) { _, port ->
                connect(port).use { client ->
                    remote.accept().close()
                    assertEquals(-1, client.getInputStream().read())
                }
            }
        }
    }

    @Test
    fun client_eof_before_handshake_closes_upstream() {
        ServerSocket(0, 10, loopback).use { remote ->
            remote.soTimeout = 2000
            withProxy(remote.localPort) { _, port ->
                val client = connect(port)
                try {
                    remote.accept().use { upstream ->
                        upstream.soTimeout = 2000
                        client.close()
                        assertEquals(-1, upstream.getInputStream().read())
                    }
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun client_data_before_handshake_closes_session_with_or_without_client_eof() {
        for (clientEof in listOf(false, true)) {
            ServerSocket(0, 10, loopback).use { remote ->
                remote.soTimeout = 2000
                withProxy(remote.localPort) { _, port ->
                    connect(port).use { client ->
                        remote.accept().use { upstream ->
                            upstream.soTimeout = 2000
                            client.getOutputStream().write(byteArrayOf(1, 2, 3, 4))
                            if (clientEof) client.shutdownOutput()
                            assertClosed(client)
                            assertClosed(upstream)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun forwards_pc_handshake_and_bidirectional_encrypted_traffic_with_redirect_rewrite() {
        ServerSocket(0, 10, loopback).use { remote ->
            remote.soTimeout = 2000
            val original = Inet4Pair(loopback, 1234)
            val rewritten = Inet4Pair(loopback, 4321)
            withProxy(remote.localPort, mapOf(original to rewritten)) { _, port ->
                connect(port).use { client ->
                    remote.accept().use { upstream ->
                        upstream.soTimeout = 2000
                        initialize(upstream, client)
                        val messages = listOf(
                            PcMessage.WelcomeMessage("before"),
                            PcMessage.Redirect(loopback.address, 1234u),
                            PcMessage.WelcomeMessage("after"),
                        )
                        val sourceCipher = PcCipher(key)
                        val ciphertext = messages.flatMap {
                            it.buffer.copy().also { frame -> sourceCipher.encrypt(frame) }.bytes().toList()
                        }.toByteArray()
                        upstream.getOutputStream().write(ciphertext)
                        val received = Buffer.fromByteArray(client.getInputStream().readNBytes(ciphertext.size))
                        PcCipher(key).decrypt(received)
                        (messages[1] as PcMessage.Redirect).port = 4321u
                        assertContentEquals(messages.flatMap { it.buffer.bytes().toList() }.toByteArray(), received.bytes())

                        val reply = PcMessage.Login().buffer.copy().also { PcCipher(key).encrypt(it) }.bytes()
                        client.getOutputStream().write(reply)
                        assertContentEquals(reply, upstream.getInputStream().readNBytes(reply.size))
                    }
                }
            }
        }
    }

    @Test
    fun forwards_bb_handshake_and_padded_encrypted_unknown_frame() {
        ServerSocket(0, 10, loopback).use { remote ->
            remote.soTimeout = 2000
            withProxy(remote.localPort, descriptor = BbMessageDescriptor) { _, port ->
                connect(port).use { client ->
                    remote.accept().use { upstream ->
                        val bbKey = ByteArray(48) { it.toByte() }
                        val init = BbMessageDescriptor.createInitEncryption(bbKey, bbKey).buffer.bytes()
                        val frame = Buffer.withSize(16).setShort(0, 9).setShort(2, 0x7fff).setByte(8, 42)
                        BbCipher(bbKey).encrypt(frame)
                        // Coalesce the handshake and first encrypted frame to exercise cipher activation.
                        upstream.getOutputStream().write(init + frame.bytes())
                        assertContentEquals(init + frame.bytes(), client.getInputStream().readNBytes(init.size + frame.size))
                    }
                }
            }
        }
    }

    private fun initialize(upstream: Socket, client: Socket) {
        val init = PcMessageDescriptor.createInitEncryption(key, key).buffer.bytes()
        upstream.getOutputStream().write(init)
        assertContentEquals(init, client.getInputStream().readNBytes(init.size))
    }

    private fun connect(port: Int): Socket = Socket(loopback, port).also { it.soTimeout = 2000 }

    private fun assertClosed(socket: Socket) {
        try {
            assertEquals(-1, socket.getInputStream().read())
        } catch (_: SocketException) {
            // Closing a socket with unread incoming bytes can produce a TCP reset instead of FIN.
        }
    }

    private fun unusedPort(): Int = ServerSocket(0, 10, loopback).use { it.localPort }

    private fun withProxy(
        remotePort: Int,
        redirects: Map<Inet4Pair, Inet4Pair> = emptyMap(),
        descriptor: MessageDescriptor<Message> = PcMessageDescriptor,
        block: (ProxyServer, Int) -> Unit,
    ) {
        val port = unusedPort()
        val proxy = ProxyServer(
            "proxy_test", Inet4Pair(loopback, port), Inet4Pair(loopback, remotePort), descriptor,
            { if (descriptor === BbMessageDescriptor) BbCipher(it) else PcCipher(it) }, redirects,
        )
        proxy.start()
        try {
            block(proxy, port)
        } finally {
            proxy.stop()
        }
    }

    private fun Buffer.bytes(): ByteArray = byteArray.copyOf(size)
}
