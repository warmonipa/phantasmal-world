package world.phantasmal.web.questEditor.rendering

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.cell.observeNow
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.externals.three.PlaneGeometry
import world.phantasmal.web.test.WebTestSuite
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuestRenderContextTests : WebTestSuite {
    @Test
    fun collision_object_is_published_after_its_bounding_box() = test {
        val context = disposer.add(
            QuestRenderContext(
                document.createElement("canvas").unsafeCast<HTMLCanvasElement>(),
                PerspectiveCamera(),
            )
        )
        var boundingBoxWasReady = false
        disposer.add(context.collisionGeometryObject.observeNow { geometry ->
            if (geometry != null) {
                assertNotNull(context.collisionGeometryBoundingBox.value)
                boundingBoxWasReady = true
            }
        })

        context.collisionGeometry = Mesh(PlaneGeometry(10.0, 10.0), MeshBasicMaterial())

        assertTrue(boundingBoxWasReady)
    }
}
