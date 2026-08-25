package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import mu.KotlinLogging
import world.phantasmal.psolib.Episode
import world.phantasmal.web.questEditor.loading.AreaAssetLoader
import world.phantasmal.web.questEditor.models.AreaVariantModel
import world.phantasmal.web.externals.three.Object3D

private val logger = KotlinLogging.logger {}

class AreaMeshManager internal constructor(
    private val renderContext: QuestRenderContext,
    private val loadCollisionGeometry: suspend (Episode, AreaVariantModel, Boolean) -> Object3D,
    private val loadRenderGeometry: suspend (Episode, AreaVariantModel, Boolean) -> Object3D,
) {
    constructor(renderContext: QuestRenderContext, areaAssetLoader: AreaAssetLoader) : this(
        renderContext,
        areaAssetLoader::loadCollisionGeometry,
        areaAssetLoader::loadRenderGeometry,
    )

    suspend fun load(episode: Episode?, areaVariant: AreaVariantModel?, ultimate: Boolean) {
        renderContext.clearCollisionGeometry()
        renderContext.clearRenderGeometry()

        if (episode == null || areaVariant == null) {
            return
        }

        try {
            val collisionGeometry =
                loadCollisionGeometry(episode, areaVariant, ultimate)
            val renderGeometry =
                loadRenderGeometry(episode, areaVariant, ultimate)
            coroutineContext.ensureActive()
            renderContext.renderGeometry = renderGeometry
            renderContext.collisionGeometry = collisionGeometry
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) {
                "Couldn't load models for area ${areaVariant.area.id}, variant ${areaVariant.id}."
            }
        }
    }
}
