package world.phantasmal.psoserv.servers

import world.phantasmal.psoserv.encryption.Cipher
import world.phantasmal.psoserv.encryption.PcCipher
import world.phantasmal.psoserv.messages.PcMessage
import world.phantasmal.psoserv.messages.PcMessageDescriptor
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameServerTests {
    @Test
    fun shared_session_owner_preserves_game_handshake_dispatch_and_shutdown() {
        val loopback = inet4Address("127.0.0.1")
        val port = ServerSocket(0, 10, loopback).use { it.localPort }
        val key = byteArrayOf(1, 2, 3, 4)
        val processed = CountDownLatch(2)
        val closed = CountDownLatch(2)
        val server = object : GameServer<PcMessage>("game_server_test", Inet4Pair(loopback, port)) {
            override val messageDescriptor = PcMessageDescriptor
            override fun createCipher(): Cipher = PcCipher(key)

            override fun createClientReceiver(
                ctx: ClientContext<PcMessage>,
                serverCipher: Cipher,
                clientCipher: Cipher,
            ): ClientReceiver<PcMessage> = object : ClientReceiver<PcMessage> {
                override fun process(message: PcMessage): Boolean {
                    if (message is PcMessage.Login) processed.countDown()
                    return true
                }

                override fun connectionClosed() {
                    closed.countDown()
                }
            }
        }
        server.start()
        try {
            Socket(loopback, port).use { first ->
                Socket(loopback, port).use { second ->
                    for (client in listOf(first, second)) {
                        client.soTimeout = 2000
                        assertEquals(76, client.getInputStream().readNBytes(76).size)
                        val login = PcMessage.Login().buffer
                        PcCipher(key).encrypt(login)
                        client.getOutputStream().write(login.byteArray, 0, login.size)
                    }
                    assertTrue(processed.await(2, TimeUnit.SECONDS))
                    server.stop()
                    assertEquals(-1, first.getInputStream().read())
                    assertEquals(-1, second.getInputStream().read())
                    assertTrue(closed.await(2, TimeUnit.SECONDS))
                }
            }
        } finally {
            server.stop()
        }
    }
}
