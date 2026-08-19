package world.phantasmal.web.core.loading

import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LoadingCacheTests : WebTestSuite {
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
