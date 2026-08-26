package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Runs only the latest submitted calculation after its delay has completed. */
internal class WalkthroughRecalculationScheduler(
    private val scope: CoroutineScope,
    private val awaitDelay: suspend () -> Unit,
) {
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun schedule(calculate: suspend CoroutineScope.() -> Unit) {
        cancel()
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            awaitDelay()
            coroutineContext.ensureActive()
            calculate()
        }
    }
}
