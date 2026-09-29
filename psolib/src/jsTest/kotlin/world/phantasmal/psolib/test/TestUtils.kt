package world.phantasmal.psolib.test

import kotlinx.browser.window
import kotlinx.coroutines.await
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.cursor.ArrayBufferCursor
import world.phantasmal.psolib.cursor.Cursor

actual suspend fun readFile(path: String): Cursor {
    // Resource names may contain URL-reserved characters such as '#'.
    val url = path.split('/').joinToString("/") { js("encodeURIComponent")(it).unsafeCast<String>() }
    return window.fetch(url)
        .then {
            require(it.ok) { """Couldn't load resource "$path".""" }
            it.arrayBuffer()
        }
        .then { ArrayBufferCursor(it, Endianness.Little) }
        .await()
}
