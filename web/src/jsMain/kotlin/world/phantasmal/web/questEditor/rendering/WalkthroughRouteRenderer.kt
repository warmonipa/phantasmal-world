package world.phantasmal.web.questEditor.rendering

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import world.phantasmal.web.externals.three.Color
import world.phantasmal.web.externals.three.ConeGeometry
import world.phantasmal.web.externals.three.CylinderGeometry
import world.phantasmal.web.externals.three.Group
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.MeshBasicMaterial
import world.phantasmal.web.externals.three.Matrix3
import world.phantasmal.web.externals.three.Raycaster
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.webui.DisposableContainer
import world.phantasmal.webui.obj

private const val ROUTE_CLEARANCE = 2.0

/** Ground-following, non-pickable directional routes in the Quest Editor helper layer. */
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
        opacity = 0.78
    }).apply {
        asDynamic().depthWrite = false
    }
    private val raycaster = Raycaster()
    private val rayOrigin = Vector3()
    private val rayDirection = Vector3(0.0, -1.0, 0.0)
    private val normalMatrix = Matrix3()
    private var route = WalkthroughRoute(emptyList(), emptyList())

    init {
        observe(renderContext.collisionGeometryBoundingBox) {
            renderRoute()
        }
    }

    fun setRoute(route: WalkthroughRoute, color: Int) {
        this.route = route
        material.color.set(color)
        renderRoute()
    }

    private fun renderRoute() {
        group.clear()
        for (segment in route.segments) addSegment(segment)
    }

    private fun addSegment(segment: WalkthroughSegment) {
        val points = sampleWalkthroughSegment(segment, SAMPLE_LENGTH, ::groundHeightAt)
            .map { it.vector() }
        for (index in 0 until points.lastIndex) {
            addShaft(points[index], points[index + 1])
        }
        if (segment.endsLeg && points.size >= 2) {
            addHead(points[points.lastIndex - 1], points.last())
        }
    }

    private fun addShaft(from: Vector3, to: Vector3) {
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
        group.add(shaft)
    }

    private fun addHead(from: Vector3, to: Vector3) {
        val direction = Vector3().subVectors(to, from)
        if (direction.length() < MIN_LENGTH) return
        direction.normalize()

        val head = Mesh(headGeometry, material).apply {
            name = "Walkthrough Direction"
            position.copy(to).sub(direction.clone().multiplyScalar(HEAD_LENGTH / 2))
            lookAt(to)
            frustumCulled = false
            renderOrder = 9997
            asDynamic().raycast = { _: dynamic, _: dynamic -> }
        }
        group.add(head)
    }

    private fun groundHeightAt(x: Double, expectedY: Double, z: Double): Double? {
        if (renderContext.collisionGeometryBoundingBox.value == null) return null
        rayOrigin.set(x, expectedY + RAYCAST_MARGIN, z)
        raycaster.set(rayOrigin, rayDirection)

        return raycaster
            .intersectObject(renderContext.collisionGeometry, recursive = true)
            .asSequence()
            .filter { intersection ->
                val face = intersection.face ?: return@filter false
                abs(intersection.point.y - expectedY) <= MAX_GROUND_DISTANCE &&
                    face.normal.clone().applyNormalMatrix(
                        normalMatrix.getNormalMatrix(intersection.`object`.matrixWorld),
                    ).y > MIN_GROUND_NORMAL_Y
            }
            .minByOrNull { intersection -> abs(intersection.point.y - expectedY) }
            ?.point
            ?.y
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
        private const val SAMPLE_LENGTH = 12.0
        private const val RAYCAST_MARGIN = 25.0
        private const val MAX_GROUND_DISTANCE = 75.0
        private const val MIN_GROUND_NORMAL_Y = 0.75
        private const val SHAFT_RADIUS = 0.8
        private const val HEAD_RADIUS = 2.75
        private const val HEAD_LENGTH = 7.0
        private const val MIN_LENGTH = 0.01
    }
}

internal fun sampleWalkthroughSegment(
    segment: WalkthroughSegment,
    maxSampleLength: Double,
    groundHeightAt: (x: Double, expectedY: Double, z: Double) -> Double?,
): List<WalkthroughPoint> {
    require(maxSampleLength > 0.0)
    val dx = segment.to.x - segment.from.x
    val dy = segment.to.y - segment.from.y
    val dz = segment.to.z - segment.from.z
    val horizontalLength = sqrt(dx * dx + dz * dz)
    val steps = maxOf(1, ceil(horizontalLength / maxSampleLength).toInt())

    return (0..steps).map { step ->
        val progress = step.toDouble() / steps
        val x = segment.from.x + dx * progress
        val rawY = segment.from.y + dy * progress
        val z = segment.from.z + dz * progress
        WalkthroughPoint(
            x,
            (groundHeightAt(x, rawY, z) ?: rawY) + ROUTE_CLEARANCE,
            z,
        )
    }
}
