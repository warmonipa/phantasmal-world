@file:JsModule("three-pathfinding")
@file:JsNonModule
@file:Suppress("unused")

package world.phantasmal.web.externals.threePathfinding

import world.phantasmal.web.externals.three.BufferGeometry
import world.phantasmal.web.externals.three.Vector3

external class Pathfinding {
    fun setZoneData(zoneId: String, zone: dynamic)
    fun getGroup(zoneId: String, position: Vector3, checkPolygon: Boolean = definedExternally): Int?
    fun findPath(
        startPosition: Vector3,
        targetPosition: Vector3,
        zoneId: String,
        groupId: Int,
    ): Array<Vector3>?

    companion object {
        fun createZone(geometry: BufferGeometry, tolerance: Double = definedExternally): dynamic
    }
}
