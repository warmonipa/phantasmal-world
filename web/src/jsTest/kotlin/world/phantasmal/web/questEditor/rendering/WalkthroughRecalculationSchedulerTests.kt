package world.phantasmal.web.questEditor.rendering

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import world.phantasmal.core.disposable.DisposableSupervisedScope
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals

class WalkthroughRecalculationSchedulerTests : WebTestSuite {
    @Test
    fun only_the_latest_delayed_calculation_runs() = testAsync {
        val scope = disposer.add(
            DisposableSupervisedScope(this::class, Dispatchers.Default),
        )
        val delays = mutableListOf<CompletableDeferred<Unit>>()
        val calculations = mutableListOf<Int>()
        val scheduler = WalkthroughRecalculationScheduler(scope) {
            CompletableDeferred<Unit>().also(delays::add).await()
        }

        scheduler.schedule { calculations += 1 }
        scheduler.schedule { calculations += 2 }

        assertEquals(2, delays.size)
        delays[0].complete(Unit)
        yield()
        assertEquals(emptyList(), calculations)

        delays[1].complete(Unit)
        yield()
        assertEquals(listOf(2), calculations)
    }

    @Test
    fun cancellation_prevents_a_delayed_calculation() = testAsync {
        val scope = disposer.add(
            DisposableSupervisedScope(this::class, Dispatchers.Default),
        )
        val delay = CompletableDeferred<Unit>()
        var calculationCount = 0
        val scheduler = WalkthroughRecalculationScheduler(scope) { delay.await() }

        scheduler.schedule { calculationCount++ }
        scheduler.cancel()
        delay.complete(Unit)
        yield()

        assertEquals(0, calculationCount)
    }
}
