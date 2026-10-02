package world.phantasmal.psoserv.servers

import world.phantasmal.psolib.buffer.Buffer
import java.net.Socket

fun Socket.write(buffer: Buffer, offset: Int, size: Int) {
    getOutputStream().write(buffer.byteArray, offset, size)
}
