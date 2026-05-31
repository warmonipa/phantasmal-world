package world.phantasmal.web.viewer.loading

import world.phantasmal.core.Success
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.buffer.Buffer
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

class ItemAssetLoader(private val assetLoader: AssetLoader) : DisposableContainer() {
    private val modelAfsCache: LoadingCache<Unit, List<Buffer>> =
        addDisposable(LoadingCache({ loadAfs("/items/ItemModelEp4.afs") }) {})

    private val textureAfsCache: LoadingCache<Unit, List<Buffer>> =
        addDisposable(LoadingCache({ loadAfs("/items/ItemTextureEp4.afs") }) {})

    suspend fun loadNinjaObject(index: Int): NinjaObject<*, *> {
        val buffer = modelAfsCache.get(Unit).getOrNull(index)
            ?: throw IllegalArgumentException("Invalid item model index: $index")

        val cursor = buffer.cursor()
        val njResult = parseNj(cursor)

        if (njResult is Success) {
            return njResult.value.first()
        }

        return parseXj(buffer.cursor()).unwrap().first()
    }

    suspend fun loadXvrTextures(index: Int): List<XvrTexture> {
        val buffer = textureAfsCache.get(Unit).getOrNull(index)
            ?: return emptyList()

        return parseXvm(buffer.cursor()).unwrap().textures
    }

    private suspend fun loadAfs(path: String): List<Buffer> {
        val buffer = assetLoader.loadArrayBuffer(path)
        return parseAfs(buffer.cursor(Endianness.Little)).unwrap()
    }
}
