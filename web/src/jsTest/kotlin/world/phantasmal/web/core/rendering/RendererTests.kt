package world.phantasmal.web.core.rendering

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.web.externals.three.PerspectiveCamera
import world.phantasmal.web.externals.three.WebGLRenderer
import world.phantasmal.web.test.NopRenderer
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.js.unsafeCast

class RendererTests : WebTestSuite {
    @Test
    fun restore_waits_until_the_context_lost_event_arrives() = test {
        var lossCount = 0
        var restoreCount = 0
        val contextManager = WebGlContextManager(
            forceContextLoss = { lossCount++ },
            forceContextRestore = { restoreCount++ },
        )

        contextManager.releaseContext()
        assertEquals(1, lossCount)

        // The user makes the renderer visible again before webglcontextlost is delivered.
        contextManager.restoreContext()
        assertEquals(0, restoreCount)

        // Restoration is requested only after the asynchronous loss event actually arrives.
        contextManager.onContextLost()
        assertEquals(1, restoreCount)

        contextManager.onContextRestored()
        contextManager.restoreContext()
        assertEquals(1, restoreCount)
    }

    @Test
    fun a_renderer_hidden_while_restore_is_pending_is_released_again() = test {
        var lossCount = 0
        var restoreCount = 0
        val contextManager = WebGlContextManager(
            forceContextLoss = { lossCount++ },
            forceContextRestore = { restoreCount++ },
        )

        contextManager.releaseContext()
        contextManager.onContextLost()
        contextManager.restoreContext()
        assertEquals(1, restoreCount)

        contextManager.releaseContext()
        contextManager.onContextRestored()
        assertEquals(2, lossCount)
    }

    @Test
    fun hidden_renderers_release_and_restore_their_webgl_context() = test {
        val rendererResource = TestThreeRenderer()
        val renderer = disposer.add(TestRenderer(rendererResource))

        // A renderer can be hidden before its first animation frame (for example, a hidden dialog).
        renderer.stopRendering()
        assertEquals(1, rendererResource.releaseCount)
        assertTrue(rendererResource.contextReleased)

        renderer.startRendering()
        renderer.startRendering()
        assertEquals(1, rendererResource.restoreCount)
        assertFalse(rendererResource.contextReleased)

        renderer.stopRendering()
        renderer.stopRendering()
        assertEquals(2, rendererResource.releaseCount)
        assertTrue(rendererResource.contextReleased)
    }

    private class TestRenderer(
        override val disposableThreeRenderer: DisposableThreeRenderer,
    ) : Renderer() {
        override val context = addDisposable(
            RenderContext(
                document.createElement("canvas") as HTMLCanvasElement,
                PerspectiveCamera(),
            )
        )
        override val inputManager = object : InputManager {
            override fun setSize(width: Int, height: Int) {}
            override fun resetCamera() {}
            override fun beforeRender() {}
        }

        init {
            addDisposable(disposableThreeRenderer)
        }
    }

    private class TestThreeRenderer : DisposableThreeRenderer {
        override val renderer = NopRenderer().unsafeCast<WebGLRenderer>()
        var contextReleased = false
            private set
        var releaseCount = 0
            private set
        var restoreCount = 0
            private set

        override fun releaseContext() {
            if (!contextReleased) {
                contextReleased = true
                releaseCount++
            }
        }

        override fun restoreContext() {
            if (contextReleased) {
                contextReleased = false
                restoreCount++
            }
        }

        override fun dispose() {}
    }
}
