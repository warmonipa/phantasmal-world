package world.phantasmal.web.core.rendering

import world.phantasmal.core.disposable.Disposable
import world.phantasmal.web.externals.three.WebGLRenderer

interface DisposableThreeRenderer : Disposable {
    val renderer: WebGLRenderer

    /** Release the underlying WebGL context while this renderer is not visible. */
    fun releaseContext() {}

    /** Restore a WebGL context that was released by [releaseContext]. */
    fun restoreContext() {}
}

/**
 * Coordinates the asynchronous WEBGL_lose_context lifecycle.
 *
 * Calling restoreContext before the browser has delivered webglcontextlost is invalid, so desired
 * visibility and the browser's actual context state have to be tracked separately.
 */
internal class WebGlContextManager(
    private val forceContextLoss: () -> Unit,
    private val forceContextRestore: () -> Unit,
) {
    private var releaseRequested = false
    private var lossPending = false
    private var contextLost = false
    private var deliberatelyLost = false
    private var restorePending = false

    fun releaseContext() {
        releaseRequested = true
        requestLoss()
    }

    fun restoreContext() {
        releaseRequested = false
        requestRestore()
    }

    fun onContextLost() {
        contextLost = true
        deliberatelyLost = lossPending
        lossPending = false
        restorePending = false

        if (!releaseRequested) {
            requestRestore()
        }
    }

    fun onContextRestored() {
        contextLost = false
        deliberatelyLost = false
        restorePending = false

        if (releaseRequested) {
            requestLoss()
        }
    }

    private fun requestLoss() {
        if (!contextLost && !lossPending) {
            lossPending = true
            forceContextLoss()
        }
    }

    private fun requestRestore() {
        // WEBGL_lose_context can only restore a context that this extension deliberately lost.
        if (contextLost && deliberatelyLost && !restorePending) {
            restorePending = true
            forceContextRestore()
        }
    }
}
