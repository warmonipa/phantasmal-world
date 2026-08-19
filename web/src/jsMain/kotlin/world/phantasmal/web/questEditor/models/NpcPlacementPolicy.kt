package world.phantasmal.web.questEditor.models

import world.phantasmal.cell.Cell
import world.phantasmal.cell.mutateDeferred
import world.phantasmal.cell.mutableCell
import world.phantasmal.core.disposable.Disposable

internal fun interface GroundHeightProvider {
    fun heightAt(x: Double, z: Double, section: SectionModel): Double
}

/**
 * Instance-scoped display policy for NPC placement.
 *
 * Quest models and the renderer share one policy owned by a single Quest Editor instance. This
 * keeps display coordinates reactive without leaking renderer state across editor instances.
 */
class NpcPlacementPolicy {
    private val _spawnOnGround = mutableCell(false)
    private val _groundHeightRevision = mutableCell(0)
    private var groundHeightProvider: GroundHeightProvider? = null

    val spawnOnGround: Cell<Boolean> = _spawnOnGround
    internal val groundHeightRevision: Cell<Int> = _groundHeightRevision

    fun setSpawnOnGround(value: Boolean) {
        _spawnOnGround.value = value
    }

    internal fun installGroundHeightProvider(provider: GroundHeightProvider): Disposable {
        check(groundHeightProvider == null) { "A ground height provider is already installed." }
        groundHeightProvider = provider
        invalidateGroundHeights()

        return object : Disposable {
            private var disposed = false

            override fun dispose() {
                if (!disposed) {
                    disposed = true
                    check(groundHeightProvider === provider) {
                        "The installed ground height provider changed before disposal."
                    }
                    groundHeightProvider = null
                    invalidateGroundHeights()
                }
            }
        }
    }

    internal fun invalidateGroundHeights() {
        mutateDeferred {
            _groundHeightRevision.value++
        }
    }

    internal fun groundHeight(x: Double, z: Double, section: SectionModel): Double =
        groundHeightProvider?.heightAt(x, z, section) ?: section.position.y
}
