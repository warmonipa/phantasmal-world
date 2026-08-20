package world.phantasmal.web.questEditor.rendering

import world.phantasmal.cell.mutableCell
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.web.core.euler
import world.phantasmal.web.externals.three.Object3D
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestNpcModel
import world.phantasmal.web.test.createQuestObjectModel
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntityDirectionIndicatorContainerTests : WebTestSuite {
    @Test
    fun visibility_follows_the_shared_setting() = testAsync {
        val showDirections = mutableCell(false)
        val container = disposer.add(EntityDirectionIndicatorContainer(showDirections))

        assertFalse(container.mesh.visible)

        showDirections.value = true

        assertTrue(container.mesh.visible)
    }

    @Test
    fun supports_object_and_npc_instance_lifecycles() = testAsync {
        val container = disposer.add(EntityDirectionIndicatorContainer(mutableCell(true)))
        val obj = createQuestObjectModel(ObjectType.PlayerSet)
        val npc = createQuestNpcModel(NpcType.NpcHUmar, Episode.I)

        container.addInstance(obj)
        container.addInstance(npc)

        assertEquals(2, container.mesh.count)
        assertEquals(0, container.getInstance(obj)?.instanceIndex)
        assertEquals(1, container.getInstance(npc)?.instanceIndex)

        container.removeInstance(obj)

        assertEquals(1, container.mesh.count)
        assertEquals(0, container.getInstance(npc)?.instanceIndex)

        container.clearInstances()

        assertEquals(0, container.mesh.count)
    }

    @Test
    fun instance_follows_entity_world_position_and_rotation() = testAsync {
        val container = disposer.add(EntityDirectionIndicatorContainer(mutableCell(true)))
        val entity = createQuestObjectModel(ObjectType.PlayerSet)
        val instance = container.addInstance(entity)
        val follower = Object3D()
        instance.follower = follower

        entity.setWorldPosition(Vector3(12.0, 34.0, 56.0))
        entity.setWorldRotation(euler(0.25, PI / 2, 0.75))

        assertEquals(12.0, follower.position.x)
        assertEquals(34.0, follower.position.y)
        assertEquals(56.0, follower.position.z)
        assertEquals(0.25, follower.rotation.x)
        assertEquals(PI / 2, follower.rotation.y)
        assertEquals(0.75, follower.rotation.z)
    }

    @Test
    fun removed_instance_stops_following_entity_changes() = testAsync {
        val container = disposer.add(EntityDirectionIndicatorContainer(mutableCell(true)))
        val entity = createQuestObjectModel(ObjectType.PlayerSet)
        val instance = container.addInstance(entity)
        val follower = Object3D()
        instance.follower = follower

        container.removeInstance(entity)
        entity.setWorldPosition(Vector3(12.0, 34.0, 56.0))

        assertEquals(0.0, follower.position.x)
        assertEquals(0.0, follower.position.y)
        assertEquals(0.0, follower.position.z)
    }

    @Test
    fun arrow_geometry_points_along_local_positive_z() = testAsync {
        val geometry = createEntityDirectionArrowGeometry()
        try {
            val bounds = requireNotNull(geometry.boundingBox)

            assertTrue(bounds.min.z >= 0.0)
            assertTrue(bounds.max.z > 25.0)
            assertTrue(bounds.min.x < 0.0)
            assertTrue(bounds.max.x > 0.0)
        } finally {
            geometry.dispose()
        }
    }
}
