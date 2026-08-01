package world.phantasmal.web.questEditor.loading

import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.fog.FogEntry
import world.phantasmal.psolib.fileFormats.fog.parseFogEntryList
import world.phantasmal.web.core.loading.AssetLoader

/** Loads the stock PSOBB `fogentry.dat` table used by Fog Collision objects. */
class FogAssetLoader(private val assetLoader: AssetLoader) {
    private var entries: List<FogEntry>? = null

    suspend fun load(): List<FogEntry> = entries ?: parseFogEntryList(
        assetLoader.loadArrayBuffer("/fog/fogentry.dat").cursor(Endianness.Little),
    ).also { entries = it }
}
