package world.phantasmal.web.questEditor.rendering

import kotlin.math.PI
import world.phantasmal.web.externals.three.Color
import world.phantasmal.web.externals.three.ConeGeometry
import world.phantasmal.web.externals.three.CylinderGeometry
import world.phantasmal.web.externals.three.Group
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.obj

/** Thick, non-pickable directional route ribbons in the Quest Editor helper layer. */
internal class WalkthroughRouteRenderer(
    private val renderContext: QuestRenderContext,
) : DisposableContainer() {
    private val group = Group().apply {
        name = "Walkthrough Route"
        renderContext.helpers.add(this)
    }
    private val shaftGeometry = CylinderGeometry(SHAFT_RADIUS, SHAFT_RADIUS, 1.0, 8).apply {
        rotateX(PI / 2)
    }
    private val headGeometry = ConeGeometry(HEAD_RADIUS, HEAD_LENGTH, 10).apply {
        rotateX(PI / 2)
    }
    private val material = MeshBasicMaterial(obj {
        color = Color(0xF04444)
        transparent = true
        opacity = 0.92
    }).apply {
        asDynamic().depthWrite = false
    }

    fun setRoute(route: WalkthroughRoute, color: Int) {
        group.clear()
        material.color.set(color)
        for (segment in route.segments) addSegment(segment)
    }

    private fun addSegment(segment: WalkthroughSegment) {
        val from = segment.from.vector().apply { y += HEIGHT }
        val to = segment.to.vector().apply { y += HEIGHT }
        val direction = Vector3().subVectors(to, from)
        val length = direction.length()
        if (length < MIN_LENGTH) return
        direction.normalize()

        val shaft = Mesh(shaftGeometry, material).apply {
            name = "Walkthrough Shaft"
            position.copy(from).add(direction.clone().multiplyScalar(length / 2))
            scale.z = length
            lookAt(to)
            frustumCulled = false
            renderOrder = 9997
            asDynamic().raycast = { _: dynamic, _: dynamic -> }
        }
        val head = Mesh(headGeometry, material).apply {
            name = "Walkthrough Direction"
            position.copy(to).sub(direction.clone().multiplyScalar(HEAD_LENGTH / 2))
            lookAt(to)
            frustumCulled = false
            renderOrder = 9997
            asDynamic().raycast = { _: dynamic, _: dynamic -> }
        }
        group.add(shaft, head)
    }

    override fun dispose() {
        renderContext.helpers.remove(group)
        group.clear()
        shaftGeometry.dispose()
        headGeometry.dispose()
        material.dispose()
        super.dispose()
    }

    private fun WalkthroughPoint.vector() = Vector3(x, y, z)

    companion object {
        private const val HEIGHT = 5.0
        private const val SHAFT_RADIUS = 1.25
        private const val HEAD_RADIUS = 4.0
        private const val HEAD_LENGTH = 10.0
        private const val MIN_LENGTH = 0.01
    }
}
