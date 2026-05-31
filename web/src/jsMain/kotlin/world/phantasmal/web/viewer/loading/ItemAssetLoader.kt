package world.phantasmal.web.viewer.loading

import world.phantasmal.core.Success
import mu.KotlinLogging
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.compression.prs.prsDecompress
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.ninja.NinjaObject
import world.phantasmal.psolib.fileFormats.ninja.XvrTexture
import world.phantasmal.psolib.fileFormats.ninja.parseNj
import world.phantasmal.psolib.fileFormats.ninja.parseXj
import world.phantasmal.psolib.fileFormats.ninja.parseXvm
import world.phantasmal.psolib.fileFormats.parseAfs
import world.phantasmal.web.core.loading.AssetLoader
import world.phantasmal.web.core.loading.LoadingCache
import world.phantasmal.webui.DisposableContainer

private val logger = KotlinLogging.logger {}

class ItemAssetLoader(private val assetLoader: AssetLoader) : DisposableContainer() {
    private val modelAfsCache: LoadingCache<Unit, List<Buffer>> =
        addDisposable(LoadingCache({ loadAfs("/items/ItemModelEp4.afs") }) {})

    private val textureAfsCache: LoadingCache<Unit, List<Buffer>> =
        addDisposable(LoadingCache({ loadAfs("/items/ItemTextureEp4.afs") }) {})

    suspend fun loadNinjaObject(index: Int): NinjaObject<*, *> {
        val buffer = modelAfsCache.get(Unit).getOrNull(index)
            ?: throw IllegalArgumentException("Invalid item model index: $index")

        val xjResult = runCatching { parseXj(decompress(buffer)) }.getOrNull()

        if (xjResult is Success && xjResult.value.isNotEmpty()) {
            return xjResult.value.first()
        }

        val njResult = runCatching { parseNj(decompress(buffer)) }.getOrNull()

        if (njResult is Success && njResult.value.isNotEmpty()) {
            return njResult.value.first()
        }

        throw IllegalArgumentException("Couldn't parse item model $index.")
    }

    suspend fun loadXvrTextures(index: Int): List<XvrTexture> {
        val buffer = textureAfsCache.get(Unit).getOrNull(index)
            ?: return emptyList()

        val result = parseXvm(decompress(buffer))
        return if (result is Success) {
            result.value.textures
        } else {
            logger.warn { "Couldn't parse textures for item model $index." }
            emptyList()
        }
    }

    private suspend fun loadAfs(path: String): List<Buffer> {
        val buffer = assetLoader.loadArrayBuffer(path)
        return parseAfs(buffer.cursor(Endianness.Little)).unwrap()
    }

    private fun decompress(buffer: Buffer) =
        prsDecompress(buffer.littleEndianCursor()).unwrap()

    private fun Buffer.littleEndianCursor() =
        apply { endianness = Endianness.Little }.cursor()
}
