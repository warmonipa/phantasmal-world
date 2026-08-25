package world.phantasmal.web.questEditor.rendering

import org.w3c.dom.HTMLCanvasElement
import world.phantasmal.cell.Cell
import world.phantasmal.cell.mutableCell
import world.phantasmal.web.core.rendering.RenderContext
import world.phantasmal.web.externals.three.Box3
import world.phantasmal.web.externals.three.Camera
import world.phantasmal.web.externals.three.Group
import world.phantasmal.web.externals.three.Object3D

class QuestRenderContext(
    canvas: HTMLCanvasElement,
    camera: Camera,
) : RenderContext(canvas, camera) {
    /**
     * Things that can be directly manipulated such as NPCs, objects, warp destinations,...
     */
    val entities: Object3D = Group().apply {
        name = "Entities"
        scene.add(this)
    }

    /**
     * Helper objects that can't be directly manipulated such as warp lines.
     */
    val helpers: Object3D = Group().apply {
        name = "Helpers"
        scene.add(this)
    }

    /**
     * Particle previews and fallback markers for fixed quest particle emitters. Pickable via
     * raycaster but not directly manipulable.
     */
    val particleMarkers: Object3D = Group().apply {
        name = "Particle Markers"
        scene.add(this)
    }

    var collisionGeometryVisible = true
        set(visible) {
            field = visible
            collisionGeometry.visible = visible
        }

    var renderGeometryVisible = false
        set(visible) {
            field = visible
            renderGeometry.visible = visible
        }

    private val _collisionGeometryBoundingBox = mutableCell<Box3?>(null)
    private val _collisionGeometryObject = mutableCell<Object3D?>(null)

    /**
     * Axis-aligned bounding box of the current collision geometry, recomputed when
     * [collisionGeometry] is assigned. `null` means no geometry is loaded.
     */
    val collisionGeometryBoundingBox: Cell<Box3?> = _collisionGeometryBoundingBox

    /** The currently loaded collision object, or `null` while no area is loaded. */
    val collisionGeometryObject: Cell<Object3D?> = _collisionGeometryObject

    var collisionGeometry: Object3D = DEFAULT_COLLISION_GEOMETRY
        set(geom) {
            scene.remove(field)
            geom.visible = collisionGeometryVisible
            field = geom
            scene.add(geom)
            _collisionGeometryBoundingBox.value = if (geom === DEFAULT_COLLISION_GEOMETRY) {
                null
            } else {
                Box3().setFromObject(geom).takeUnless { it.isEmpty() }
            }
            _collisionGeometryObject.value = geom.takeUnless { it === DEFAULT_COLLISION_GEOMETRY }
        }

    var renderGeometry: Object3D = DEFAULT_RENDER_GEOMETRY
        set(geom) {
            scene.remove(field)
            geom.visible = renderGeometryVisible
            field = geom
            scene.add(geom)
        }

    fun clearCollisionGeometry() {
        collisionGeometry = DEFAULT_COLLISION_GEOMETRY
    }

    fun clearRenderGeometry() {
        renderGeometry = DEFAULT_RENDER_GEOMETRY
    }

    companion object {
        private val DEFAULT_COLLISION_GEOMETRY = Group().apply {
            name = "Default Collision Geometry"
        }
        private val DEFAULT_RENDER_GEOMETRY = Group().apply {
            name = "Default Render Geometry"
        }
    }
}
