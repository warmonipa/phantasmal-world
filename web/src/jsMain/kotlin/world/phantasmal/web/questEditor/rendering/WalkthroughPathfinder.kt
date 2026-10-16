package world.phantasmal.web.questEditor.rendering

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.khronos.webgl.Float32Array
import world.phantasmal.web.externals.three.BufferGeometry
import world.phantasmal.web.externals.three.mergeGeometries
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

    /**
     * Returns [point], or the nearest walkable point a few units away when objects such as warp
     * pads stand just past the edge of the walkable collision geometry. Null when nothing within
     * reach is walkable.
     */
    fun nearestWalkable(point: WalkthroughPoint): WalkthroughPoint? =
        point.takeIf { findPath(it, it) != null }

    /**
     * Pairs of points on either side of a seam where two pieces of walkable collision touch
     * without sharing vertices, so that navigation treats them as separate regions.
     */
    val seams: List<Pair<WalkthroughPoint, WalkthroughPoint>> get() = emptyList()
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
    triangles: FloatArray,
) : WalkthroughPathfinder {
    private val raycaster = Raycaster()
    private val rayOrigin = Vector3()
    private val down = Vector3(0.0, -1.0, 0.0)
    private val normalMatrix = Matrix3()

    override val seams: List<Pair<WalkthroughPoint, WalkthroughPoint>> = findSeams(triangles)

    /**
     * Collision meshes of neighbouring map sections often meet along edges whose vertices don't
     * coincide (T-junctions, tiny offsets), which three-pathfinding treats as separate groups.
     * Boundary edges of different groups lying on top of each other mark such a seam.
     */
    private fun findSeams(triangles: FloatArray): List<Pair<WalkthroughPoint, WalkthroughPoint>> {
        class Edge(
            val ax: Double, val ay: Double, val az: Double,
            val bx: Double, val by: Double, val bz: Double,
            val inward: WalkthroughPoint,
            val group: Int,
        )

        fun key(x: Float, y: Float, z: Float) =
            "${(x * 100).toInt()},${(y * 100).toInt()},${(z * 100).toInt()}"

        val edgeCounts = mutableMapOf<String, Int>()
        val triangleCount = triangles.size / 9
        fun edgeKey(t: Int, i: Int, j: Int): String {
            val a = key(triangles[t * 9 + i * 3], triangles[t * 9 + i * 3 + 1], triangles[t * 9 + i * 3 + 2])
            val b = key(triangles[t * 9 + j * 3], triangles[t * 9 + j * 3 + 1], triangles[t * 9 + j * 3 + 2])
            return if (a < b) "$a|$b" else "$b|$a"
        }
        val sides = listOf(0 to 1, 1 to 2, 2 to 0)
        // Collision geometry can contain the same triangle more than once (e.g. duplicated
        // section meshes); count each distinct triangle's edges once.
        val distinctTriangles = mutableSetOf<String>()
        for (t in 0 until triangleCount) {
            val triangleKey = (0..2).map { i ->
                key(triangles[t * 9 + i * 3], triangles[t * 9 + i * 3 + 1], triangles[t * 9 + i * 3 + 2])
            }.sorted().joinToString("|")
            if (!distinctTriangles.add(triangleKey)) continue
            for ((i, j) in sides) edgeCounts[edgeKey(t, i, j)] = (edgeCounts[edgeKey(t, i, j)] ?: 0) + 1
        }

        val edges = mutableListOf<Edge>()
        val probe = Vector3()
        for (t in 0 until triangleCount) {
            val cx = (triangles[t * 9] + triangles[t * 9 + 3] + triangles[t * 9 + 6]) / 3.0
            val cy = (triangles[t * 9 + 1] + triangles[t * 9 + 4] + triangles[t * 9 + 7]) / 3.0
            val cz = (triangles[t * 9 + 2] + triangles[t * 9 + 5] + triangles[t * 9 + 8]) / 3.0
            for ((i, j) in sides) {
                if (edgeCounts[edgeKey(t, i, j)] != 1) continue
                val ax = triangles[t * 9 + i * 3].toDouble()
                val ay = triangles[t * 9 + i * 3 + 1].toDouble()
                val az = triangles[t * 9 + i * 3 + 2].toDouble()
                val bx = triangles[t * 9 + j * 3].toDouble()
                val by = triangles[t * 9 + j * 3 + 1].toDouble()
                val bz = triangles[t * 9 + j * 3 + 2].toDouble()
                // A point just inside the triangle, next to the edge's midpoint.
                val mx = (ax + bx) / 2
                val my = (ay + by) / 2
                val mz = (az + bz) / 2
                val toCenter = sqrt((cx - mx) * (cx - mx) + (cz - mz) * (cz - mz))
                if (toCenter < 1e-6) continue
                val step = minOf(SEAM_INSET, toCenter / 2) / toCenter
                val inward = WalkthroughPoint(
                    mx + (cx - mx) * step, my + (cy - my) * step, mz + (cz - mz) * step,
                )
                probe.set(inward.x, inward.y, inward.z)
                val group = navigationGroup(probe) ?: continue
                edges += Edge(ax, ay, az, bx, by, bz, inward, group)
            }
        }

        // Spatial hash of boundary edges by the cells their bounding boxes cover. The boxes are
        // padded by the seam distance: two edges on top of each other can lie on either side of a
        // cell boundary (e.g. x = 959.99994 and x = 960 with 8-unit cells).
        val cells = mutableMapOf<Long, MutableList<Edge>>()
        fun cell(x: Double, z: Double) =
            (kotlin.math.floor(x / SEAM_CELL).toLong() shl 32) xor
                (kotlin.math.floor(z / SEAM_CELL).toLong() and 0xFFFFFFFFL)
        for (edge in edges) {
            val minX = kotlin.math.floor((minOf(edge.ax, edge.bx) - SEAM_DISTANCE) / SEAM_CELL).toLong()
            val maxX = kotlin.math.floor((maxOf(edge.ax, edge.bx) + SEAM_DISTANCE) / SEAM_CELL).toLong()
            val minZ = kotlin.math.floor((minOf(edge.az, edge.bz) - SEAM_DISTANCE) / SEAM_CELL).toLong()
            val maxZ = kotlin.math.floor((maxOf(edge.az, edge.bz) + SEAM_DISTANCE) / SEAM_CELL).toLong()
            for (x in minX..maxX) for (z in minZ..maxZ) {
                cells.getOrPut((x shl 32) xor (z and 0xFFFFFFFFL), ::mutableListOf) += edge
            }
        }

        fun distanceToEdge(px: Double, py: Double, pz: Double, e: Edge): Double? {
            val dx = e.bx - e.ax
            val dz = e.bz - e.az
            val lengthSquared = dx * dx + dz * dz
            val t = if (lengthSquared == 0.0) 0.0 else
                (((px - e.ax) * dx + (pz - e.az) * dz) / lengthSquared).coerceIn(0.0, 1.0)
            val y = e.ay + (e.by - e.ay) * t
            if (abs(py - y) > SEAM_MAX_HEIGHT) return null
            val x = e.ax + dx * t
            val z = e.az + dz * t
            return sqrt((px - x) * (px - x) + (pz - z) * (pz - z))
        }

        val seams = mutableListOf<Pair<WalkthroughPoint, WalkthroughPoint>>()
        val keptByGroups = mutableMapOf<Pair<Int, Int>, MutableList<WalkthroughPoint>>()
        for (edge in edges) {
            val mx = (edge.ax + edge.bx) / 2
            val my = (edge.ay + edge.by) / 2
            val mz = (edge.az + edge.bz) / 2
            val other = cells[cell(mx, mz)].orEmpty().firstOrNull { candidate ->
                candidate.group != edge.group &&
                    (distanceToEdge(mx, my, mz, candidate) ?: Double.MAX_VALUE) <= SEAM_DISTANCE
            } ?: continue
            val groups = minOf(edge.group, other.group) to maxOf(edge.group, other.group)
            val kept = keptByGroups.getOrPut(groups, ::mutableListOf)
            if (kept.any { horizontalDistance(it, edge.inward) < SEAM_SPACING }) continue
            kept += edge.inward
            seams += edge.inward to other.inward
        }
        return seams
    }

    override fun findPath(
        from: WalkthroughPoint,
        to: WalkthroughPoint,
    ): List<WalkthroughPoint>? {
        // Surfaces can be stacked a little apart (e.g. a switch's base on top of the floor), so
        // try every nearly-equally close surface at both ends, nearest first, for a shared group.
        val targets = walkableSurfacesAt(to)
        for ((start, group) in walkableSurfacesAt(from)) {
            for ((target, targetGroup) in targets) {
                if (targetGroup != group) continue
                pathOnSurface(start, target, group)?.let { return it }
            }
        }
        return null
    }

    private fun pathOnSurface(start: Vector3, target: Vector3, startGroup: Int): List<WalkthroughPoint>? {
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

    override fun nearestWalkable(point: WalkthroughPoint): WalkthroughPoint? {
        if (isOnNavMesh(point)) return point
        for (radius in SNAP_RADII) {
            for (step in 0 until SNAP_DIRECTIONS) {
                val angle = 2 * PI * step / SNAP_DIRECTIONS
                val candidate = WalkthroughPoint(
                    point.x + radius * cos(angle), point.y, point.z + radius * sin(angle),
                )
                if (isOnNavMesh(candidate)) return candidate
            }
        }
        return null
    }

    private fun isOnNavMesh(point: WalkthroughPoint): Boolean = walkableSurfacesAt(point).isNotEmpty()

    /**
     * The walkable surfaces below and above [point] that are on the navigation mesh, with their
     * group: the nearest one, and any other within [STACKED_SURFACE_TOLERANCE] of it.
     */
    private fun walkableSurfacesAt(point: WalkthroughPoint): List<Pair<Vector3, Int>> {
        rayOrigin.set(point.x, point.y + PROJECTION_HEIGHT, point.z)
        raycaster.set(rayOrigin, down)
        val hits = raycaster.intersectObject(collisionGeometry, recursive = true)
            .filter { intersection ->
                val face = intersection.face ?: return@filter false
                abs(intersection.point.y - point.y) <= MAX_PROJECTION_DISTANCE &&
                    face.normal.clone().applyNormalMatrix(
                        normalMatrix.getNormalMatrix(intersection.`object`.matrixWorld),
                    ).y >= MIN_GROUND_NORMAL_Y
            }
            .sortedBy { abs(it.point.y - point.y) }
        val nearest = hits.firstOrNull() ?: return emptyList()
        val limit = abs(nearest.point.y - point.y) + STACKED_SURFACE_TOLERANCE
        val surfaces = mutableListOf<Pair<Vector3, Int>>()
        for (hit in hits) {
            if (abs(hit.point.y - point.y) > limit) break
            val group = navigationGroup(hit.point) ?: continue
            if (surfaces.none { it.second == group }) surfaces += hit.point.clone() to group
        }
        return surfaces
    }

    /**
     * The group of the navigation polygon containing [position], or null when none does.
     * three-pathfinding's getGroup falls back to the group of the nearest polygon centroid within
     * 50 units, and its path search excludes points on a polygon's far edges (a Seabed warp pad on
     * the walkable border), so a group is only trusted once the search accepts the position.
     */
    private fun navigationGroup(position: Vector3): Int? {
        val group = pathfinding.getGroup(ZONE_ID, position, checkPolygon = true) ?: return null
        return group.takeIf { pathfinding.findPath(position, position, ZONE_ID, group) != null }
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

            val merged = mergeGeometries(
                geometries.toTypedArray(), useGroups = false,
            )
            geometries.forEach(BufferGeometry::dispose)
            if (merged == null) return null

            return try {
                val pathfinding = Pathfinding()
                pathfinding.setZoneData(ZONE_ID, Pathfinding.createZone(merged, VERTEX_TOLERANCE))
                val position = merged.asDynamic().attributes.position
                    .unsafeCast<world.phantasmal.web.externals.three.BufferAttribute>()
                val triangles = FloatArray(position.count * 3) { index ->
                    when (index % 3) {
                        0 -> position.getX(index / 3)
                        1 -> position.getY(index / 3)
                        else -> position.getZ(index / 3)
                    }.toFloat()
                }
                CollisionWalkthroughPathfinder(collisionGeometry, pathfinding, triangles)
            } finally {
                merged.dispose()
            }
        }

        private const val ZONE_ID = "quest-area"
        private const val VERTEX_TOLERANCE = 0.01
        private const val PROJECTION_HEIGHT = 25.0
        private const val MAX_PROJECTION_DISTANCE = 75.0
        private const val MIN_GROUND_NORMAL_Y = 0.25
        private val SNAP_RADII = listOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0)
        private const val SNAP_DIRECTIONS = 16
        private const val STACKED_SURFACE_TOLERANCE = 2.0
        private const val SEAM_INSET = 1.0
        private const val SEAM_DISTANCE = 1.0
        private const val SEAM_MAX_HEIGHT = 2.0
        private const val SEAM_CELL = 8.0
        private const val SEAM_SPACING = 40.0
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

private fun horizontalDistance(from: WalkthroughPoint, to: WalkthroughPoint): Double {
    val dx = from.x - to.x
    val dz = from.z - to.z
    return sqrt(dx * dx + dz * dz)
}
