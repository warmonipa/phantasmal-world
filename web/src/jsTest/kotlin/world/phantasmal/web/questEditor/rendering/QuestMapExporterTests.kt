package world.phantasmal.web.questEditor.rendering

import world.phantasmal.web.externals.three.Euler
import world.phantasmal.web.externals.three.Vector3
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class QuestMapExporterTests {
    private val eps = 1e-4

    @Test
    fun section_transform_translates_by_section_origin() {
        val w = sectionToWorld(Vector3(1.0, 0.0, 2.0), Vector3(10.0, 5.0, 20.0), Euler(0.0, 0.0, 0.0))
        assertEquals(11.0, w.x, eps)
        assertEquals(22.0, w.z, eps)
    }

    @Test
    fun section_transform_applies_yaw() {
        // 90 degrees about Y maps local (1,0,0) to world (0,0,-1).
        val w = sectionToWorld(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), Euler(0.0, PI / 2, 0.0))
        assertEquals(0.0, w.x, eps)
        assertEquals(-1.0, w.z, eps)
    }

    @Test
    fun section_transform_applies_full_euler_not_just_yaw() {
        // 90 degrees about X maps local (0,1,0) to world (0,0,1); its Z component becomes 1.
        // A yaw-only implementation would ignore the X rotation and leave Z at 0, so this
        // assertion guards against regressing to the earlier Y-only projection.
        val w = sectionToWorld(Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 0.0), Euler(PI / 2, 0.0, 0.0))
        assertEquals(0.0, w.x, eps)
        assertEquals(1.0, w.z, eps)
    }
}
