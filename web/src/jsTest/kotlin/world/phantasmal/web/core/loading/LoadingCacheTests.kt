package world.phantasmal.web.core.loading

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LoadingCacheTests : WebTestSuite {
    @Test
    fun concurrent_requests_share_one_in_flight_load() = testAsync {
        var attempts = 0
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val cache = LoadingCache<Int, String>(
            loadValue = {
                attempts++
                loadStarted.complete(Unit)
                releaseLoad.await()
                "value-$it"
            },
            disposeValue = {},
        )

        coroutineScope {
            val first = async { cache.get(1) }
            loadStarted.await()
            val second = async { cache.get(1) }
            yield()

            assertEquals(1, attempts)
            releaseLoad.complete(Unit)
            assertEquals("value-1", first.await())
            assertEquals("value-1", second.await())
        }

        cache.dispose()
    }

    @Test
    fun disposal_cancels_in_flight_load_without_disposing_a_missing_value() = testAsync {
        val loadStarted = CompletableDeferred<Unit>()
        val loadCancelled = CompletableDeferred<Unit>()
        var disposedValues = 0
        val cache = LoadingCache<Int, String>(
            loadValue = {
                loadStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    loadCancelled.complete(Unit)
                }
            },
            disposeValue = { disposedValues++ },
        )

        coroutineScope {
            val request = async { runCatching { cache.get(1) } }
            loadStarted.await()

            cache.dispose()
            loadCancelled.await()

            assertIs<CancellationException>(request.await().exceptionOrNull())
        }
        assertEquals(0, disposedValues)
        assertFailsWith<IllegalStateException> { cache.get(2) }
    }

    @Test
    fun failed_loads_are_not_exposed_or_disposed_as_values() = testAsync {
        var disposedValues = 0
        val cache = LoadingCache<Int, String>(
            loadValue = { error("Load failed.") },
            disposeValue = { disposedValues++ },
        )

        assertFailsWith<IllegalStateException> { cache.get(1) }
        assertEquals(emptyList(), cache.loadedValues)
        assertEquals(null, cache.getIfPresentNow(1))

        cache.dispose()
        assertEquals(0, disposedValues)
    }

    @Test
    fun a_failed_load_can_be_retried() = testAsync {
        var attempts = 0
        val cache = LoadingCache<Int, String>(
            loadValue = {
                attempts++
                if (attempts == 1) error("Transient failure.")
                "value-$it"
            },
            disposeValue = {},
        )

        assertFailsWith<IllegalStateException> { cache.get(1) }
        assertEquals("value-1", cache.get(1))
        assertEquals(2, attempts)

        cache.dispose()
    }

    @Test
    fun successful_loads_are_exposed_and_disposed_once() = testAsync {
        val disposedValues = mutableListOf<String>()
        val cache = LoadingCache<Int, String>(
            loadValue = { "value-$it" },
            disposeValue = disposedValues::add,
        )

        assertEquals("value-1", cache.get(1))
        assertEquals(listOf("value-1"), cache.loadedValues)

        cache.dispose()
        assertEquals(listOf("value-1"), disposedValues)
    }
}
