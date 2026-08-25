package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.cell.observeNow
import world.phantasmal.psolib.Episode
import world.phantasmal.web.externals.three.Group
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.test.WebTestSuite
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class AreaMeshManagerTests : WebTestSuite {
    @Test
    fun collision_publication_commits_the_complete_geometry_pair() = testAsync {
        val context = disposer.add(
            QuestRenderContext(
                document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                PerspectiveCamera(),
            )
        )
        val areaVariant = components.areaStore.getVariant(Episode.IV, 8, 0)!!
        val collisionGeometry = Group()
        val renderGeometry = Group()
        val manager = manager(context, collisionGeometry, renderGeometry)
        var observedRenderGeometry: Group? = null
        disposer.add(context.collisionGeometryObject.observeNow { collision ->
            if (collision != null) {
                observedRenderGeometry = context.renderGeometry as Group
            }
        })

        manager.load(Episode.IV, areaVariant, ultimate = false)

        assertSame(renderGeometry, observedRenderGeometry)
        assertSame(collisionGeometry, context.collisionGeometry)
    }

    @Test
    fun cancelled_load_does_not_publish_returned_geometry() = testAsync {
        val context = disposer.add(
            QuestRenderContext(
                document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                PerspectiveCamera(),
            )
        )
        val areaVariant = components.areaStore.getVariant(Episode.IV, 8, 0)!!
        val initialRenderGeometry = context.renderGeometry
        val collisionGeometry = Group()
        val renderGeometry = Group()
        val manager = AreaMeshManager(
            context,
            loadCollisionGeometry = { _, _, _ -> collisionGeometry },
            loadRenderGeometry = { _, _, _ ->
                currentCoroutineContext().cancel()
                renderGeometry
            },
        )

        coroutineScope {
            launch {
                manager.load(Episode.IV, areaVariant, ultimate = false)
            }.join()
        }

        assertNull(context.collisionGeometryObject.value)
        assertSame(initialRenderGeometry, context.renderGeometry)
    }

    private fun manager(
        context: QuestRenderContext,
        collisionGeometry: Group,
        renderGeometry: Group,
    ) = AreaMeshManager(
        context,
        loadCollisionGeometry = { _, _, _ -> collisionGeometry },
        loadRenderGeometry = { _, _, _ -> renderGeometry },
    )
}
