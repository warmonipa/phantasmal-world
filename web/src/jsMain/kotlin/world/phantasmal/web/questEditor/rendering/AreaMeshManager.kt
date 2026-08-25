package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import mu.KotlinLogging
import world.phantasmal.psolib.Episode
import world.phantasmal.web.questEditor.loading.AreaAssetLoader
import world.phantasmal.web.questEditor.models.AreaVariantModel

private val logger = KotlinLogging.logger {}

class AreaMeshManager(
    private val renderContext: QuestRenderContext,
    private val areaAssetLoader: AreaAssetLoader,
) {
    suspend fun load(episode: Episode?, areaVariant: AreaVariantModel?, ultimate: Boolean) {
        renderContext.clearCollisionGeometry()
        renderContext.clearRenderGeometry()

        if (episode == null || areaVariant == null) {
            return
        }

        try {
            val collisionGeometry =
                areaAssetLoader.loadCollisionGeometry(episode, areaVariant, ultimate)
            val renderGeometry =
                areaAssetLoader.loadRenderGeometry(episode, areaVariant, ultimate)
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
