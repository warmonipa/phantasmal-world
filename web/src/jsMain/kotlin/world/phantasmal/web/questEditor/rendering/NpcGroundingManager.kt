package world.phantasmal.web.questEditor.rendering

import world.phantasmal.web.externals.three.Raycaster
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.models.GroundHeightProvider
import world.phantasmal.web.questEditor.models.NpcPlacementPolicy
import world.phantasmal.web.questEditor.models.SectionModel
import world.phantasmal.webui.DisposableContainer

/** Owns the renderer-backed ground-height capability for one Quest Editor instance. */
internal class NpcGroundingManager(
    private val placementPolicy: NpcPlacementPolicy,
    private val renderContext: QuestRenderContext,
) : DisposableContainer(), GroundHeightProvider {
    private val raycaster = Raycaster()
    private val rayOrigin = Vector3()
    private val rayDirection = Vector3(0.0, -1.0, 0.0)

    init {
        observe(renderContext.collisionGeometryBoundingBox) {
            placementPolicy.invalidateGroundHeights()
        }
        addDisposable(placementPolicy.installGroundHeightProvider(this))
    }

    override fun heightAt(
        x: Double,
        z: Double,
        section: SectionModel,
    ): Double {
        rayOrigin.set(x, 1000.0, z)
        raycaster.set(rayOrigin, rayDirection)

        return raycaster
            .intersectObject(renderContext.collisionGeometry, recursive = true)
            .firstOrNull { intersection ->
                intersection.face?.normal?.let { normal -> normal.y > 0.75 } ?: false
            }
            ?.point
            ?.y
            ?: section.position.y
    }

}
