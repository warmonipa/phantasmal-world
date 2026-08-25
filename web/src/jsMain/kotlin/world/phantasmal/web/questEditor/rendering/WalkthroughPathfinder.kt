package world.phantasmal.web.questEditor.rendering

import kotlin.math.abs
import kotlin.math.sqrt
import org.khronos.webgl.Float32Array
import world.phantasmal.web.externals.three.BufferGeometry
import world.phantasmal.web.externals.three.BufferGeometryUtils
import world.phantasmal.web.externals.three.Float32BufferAttribute
import world.phantasmal.web.externals.three.Matrix3
import world.phantasmal.web.externals.three.Mesh
import world.phantasmal.web.externals.three.Object3D
import world.phantasmal.web.externals.three.Raycaster
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.externals.threePathfinding.Pathfinding

internal fun interface WalkthroughPathfinder {
    /** Returns a walkable path including both endpoints, or null when they are disconnected. */
    fun findPath(from: WalkthroughPoint, to: WalkthroughPoint): List<WalkthroughPoint>?
}

/**
 * Finds paths on the area's filtered walkable collision triangles.
 *
 * Quest actions are deliberately absent here: script events describe state and causality, while
 * this class owns the separate question of where a player can physically walk.
 */
internal class CollisionWalkthroughPathfinder private constructor(
    private val collisionGeometry: Object3D,
    private val pathfinding: Pathfinding,
) : WalkthroughPathfinder {
    private val raycaster = Raycaster()
    private val rayOrigin = Vector3()
    private val down = Vector3(0.0, -1.0, 0.0)
    private val normalMatrix = Matrix3()

    override fun findPath(
        from: WalkthroughPoint,
        to: WalkthroughPoint,
    ): List<WalkthroughPoint>? {
        val start = projectToWalkableSurface(from) ?: return null
        val target = projectToWalkableSurface(to) ?: return null
        val startGroup = pathfinding.getGroup(ZONE_ID, start, checkPolygon = true) ?: return null
        val targetGroup = pathfinding.getGroup(ZONE_ID, target, checkPolygon = true) ?: return null
        if (startGroup != targetGroup) return null

        val path = pathfinding.findPath(start, target, ZONE_ID, startGroup) ?: return null
        return buildList {
            add(start.toPoint())
            for (point in path) {
                val routePoint = point.toPoint()
                if (last() != routePoint) add(routePoint)
            }
            if (last() != target.toPoint()) add(target.toPoint())
        }
    }

    private fun projectToWalkableSurface(point: WalkthroughPoint): Vector3? {
        rayOrigin.set(point.x, point.y + PROJECTION_HEIGHT, point.z)
        raycaster.set(rayOrigin, down)
        return raycaster.intersectObject(collisionGeometry, recursive = true)
            .asSequence()
            .filter { intersection ->
                val face = intersection.face ?: return@filter false
                abs(intersection.point.y - point.y) <= MAX_PROJECTION_DISTANCE &&
                    face.normal.clone().applyNormalMatrix(
                        normalMatrix.getNormalMatrix(intersection.`object`.matrixWorld),
                    ).y >= MIN_GROUND_NORMAL_Y
            }
            .minByOrNull { intersection -> abs(intersection.point.y - point.y) }
            ?.point
            ?.clone()
    }

    companion object {
        fun create(collisionGeometry: Object3D): CollisionWalkthroughPathfinder? {
            collisionGeometry.updateMatrixWorld(true)
            val geometries = mutableListOf<BufferGeometry>()

            fun collect(obj: Object3D) {
                if (obj is Mesh) {
                    obj.geometry.walkableTriangles(obj.matrixWorld)?.let(geometries::add)
                }
                for (child in obj.children) collect(child)
            }
            collect(collisionGeometry)
            if (geometries.isEmpty()) return null

            val merged = BufferGeometryUtils.mergeBufferGeometries(
                geometries.toTypedArray(), useGroups = false,
            )
            geometries.forEach(BufferGeometry::dispose)
            if (merged == null) return null

            return try {
                val pathfinding = Pathfinding()
                pathfinding.setZoneData(ZONE_ID, Pathfinding.createZone(merged, VERTEX_TOLERANCE))
                CollisionWalkthroughPathfinder(collisionGeometry, pathfinding)
            } finally {
                merged.dispose()
            }
        }

        private const val ZONE_ID = "quest-area"
        private const val VERTEX_TOLERANCE = 0.01
        private const val PROJECTION_HEIGHT = 25.0
        private const val MAX_PROJECTION_DISTANCE = 75.0
        private const val MIN_GROUND_NORMAL_Y = 0.25
    }
}

/** Removes walls and steep faces before three-pathfinding derives connectivity. */
private fun BufferGeometry.walkableTriangles(
    matrixWorld: world.phantasmal.web.externals.three.Matrix4,
): BufferGeometry? {
    val transformed = clone().applyMatrix4(matrixWorld)
    val nonIndexed = if (transformed.asDynamic().index == null) {
        transformed
    } else {
        transformed.toNonIndexed().also { transformed.dispose() }
    }
    val position = nonIndexed.asDynamic().attributes.position
        .unsafeCast<world.phantasmal.web.externals.three.BufferAttribute>()
    val vertices = mutableListOf<Float>()

    for (index in 0 until position.count step 3) {
        if (index + 2 >= position.count) break
        val ax = position.getX(index)
        val ay = position.getY(index)
        val az = position.getZ(index)
        val bx = position.getX(index + 1)
        val by = position.getY(index + 1)
        val bz = position.getZ(index + 1)
        val cx = position.getX(index + 2)
        val cy = position.getY(index + 2)
        val cz = position.getZ(index + 2)
        val abx = bx - ax
        val aby = by - ay
        val abz = bz - az
        val acx = cx - ax
        val acy = cy - ay
        val acz = cz - az
        val nx = aby * acz - abz * acy
        val ny = abz * acx - abx * acz
        val nz = abx * acy - aby * acx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length == 0.0 || abs(ny / length) < MIN_WALKABLE_NORMAL_Y) continue

        vertices += listOf(
            ax.toFloat(), ay.toFloat(), az.toFloat(),
            bx.toFloat(), by.toFloat(), bz.toFloat(),
            cx.toFloat(), cy.toFloat(), cz.toFloat(),
        )
    }
    nonIndexed.dispose()
    if (vertices.isEmpty()) return null

    return BufferGeometry().setAttribute(
        "position",
        Float32BufferAttribute(Float32Array(vertices.toTypedArray()), 3),
    )
}

private const val MIN_WALKABLE_NORMAL_Y = 0.25

private fun Vector3.toPoint() = WalkthroughPoint(x, y, z)
