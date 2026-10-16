package world.phantasmal.web.core.rendering

import kotlinx.browser.document
import mu.KotlinLogging
import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.webui.DisposableContainer

private val logger = KotlinLogging.logger {}

abstract class Renderer : DisposableContainer() {
    protected abstract val context: RenderContext
    protected abstract val disposableThreeRenderer: DisposableThreeRenderer
    protected val threeRenderer get() = disposableThreeRenderer.renderer
    protected abstract val inputManager: InputManager

    val canvas: HTMLCanvasElement get() = context.canvas

    private var rendering = false

    fun startRendering() {
        if (!rendering) {
            logger.trace { "${this::class.simpleName} - start rendering." }

            disposableThreeRenderer.restoreContext()
            rendering = true
            renderingStarted()
            threeRenderer.setAnimationLoop { render() }
        }
    }

    /** Called when the render loop (re)starts, after any time without rendering. */
    protected open fun renderingStarted() {}

    fun stopRendering() {
        if (rendering) {
            logger.trace { "${this::class.simpleName} - stop rendering." }

            rendering = false
            threeRenderer.setAnimationLoop(null)
        }

        // Three.js renderers retain a scarce browser WebGL context even after their animation
        // loop has stopped. Hidden tools and tabs must return it to the browser.
        disposableThreeRenderer.releaseContext()
    }

    override fun dispose() {
        stopRendering()
        super.dispose()
    }

    open fun setSize(width: Int, height: Int) {
        if (width == 0 || height == 0) return

        context.width = width
        context.height = height
        context.canvas.width = width
        context.canvas.height = height

        threeRenderer.setSize(width.toDouble(), height.toDouble())

        inputManager.setSize(width, height)
    }

    protected open fun render() {
        inputManager.beforeRender()

        threeRenderer.render(context.scene, context.camera)
    }

    companion object {
        fun createCanvas(): HTMLCanvasElement =
            (document.createElement("CANVAS") as HTMLCanvasElement).apply {
                tabIndex = 0
                style.outline = "none"
            }
    }
}
