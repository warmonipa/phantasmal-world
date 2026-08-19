package world.phantasmal.web.core.loading

import kotlinx.coroutines.*
import world.phantasmal.core.disposable.TrackedDisposable

/**
 * Shares in-flight and successful loads by key. Failed loads are evicted so a later request can
 * retry, while successfully loaded values remain owned by the cache until disposal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadingCache<K, V>(
    private val loadValue: suspend (K) -> V,
    private val disposeValue: (V) -> Unit,
) : TrackedDisposable() {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val map = mutableMapOf<K, Deferred<V>>()

    val loadedValues: List<V>
        get() = map.values.mapNotNull { deferred ->
            if (deferred.isCompleted && !deferred.isCancelled) {
                deferred.getCompleted()
            } else {
                null
            }
        }

    suspend fun get(key: K): V {
        check(!disposed) { "LoadingCache is disposed." }
        return (map[key] ?: startLoad(key)).await()
    }

    fun getIfPresentNow(key: K): V? =
        map[key]
            ?.takeIf { it.isCompleted && !it.isCancelled }
            ?.getCompleted()

    private fun startLoad(key: K): Deferred<V> {
        val deferred = scope.async(start = CoroutineStart.LAZY) { loadValue(key) }
        map[key] = deferred
        deferred.invokeOnCompletion { failure ->
            if (failure != null && map[key] === deferred) {
                map.remove(key)
            }
        }
        deferred.start()
        return deferred
    }

    override fun dispose() {
        val deferredValues = map.values.toList()
        map.clear()
        deferredValues.forEach { deferred ->
            if (deferred.isActive) {
                deferred.cancel()
            } else if (deferred.isCompleted && !deferred.isCancelled) {
                disposeValue(deferred.getCompleted())
            }
        }

        scope.cancel("LoadingCache disposed.")
        super.dispose()
    }
}
