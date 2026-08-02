package world.phantasmal.web.questEditor.models

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.fileFormats.quest.DatCmRandomSpawnEntry
import world.phantasmal.web.core.euler
import world.phantasmal.web.core.timesAssign
import world.phantasmal.web.externals.three.Quaternion
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.test.WebTestSuite
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class ChallengeMonsterSpawnModelTests : WebTestSuite {
    @Test
    fun preserves_room_coordinates_and_converts_all_three_angles() = test {
        val model = ChallengeMonsterSpawnModel(
            floorId = 7,
            roomId = 42,
            entry = spawnEntry(),
            section = null,
        )

        assertEquals(7, model.floorId)
        assertEquals(42, model.roomId)
        assertEquals(42, model.sectionId)
        assertEquals(1.0, model.position.value.x)
        assertEquals(2.0, model.position.value.y)
        assertEquals(3.0, model.position.value.z)
        assertEquals(PI / 2.0, model.rotation.value.x, 1e-12)
        assertEquals(PI, model.rotation.value.y, 1e-12)
        assertEquals(3.0 * PI / 2.0, model.rotation.value.z, 1e-12)
    }

    @Test
    fun applies_the_room_section_transform_to_spawn_coordinates() = test {
        val section = SectionModel(
            id = 42,
            position = Vector3(10.0, 20.0, 30.0),
            rotation = euler(.0, .0, .0),
            areaVariant = components.areaStore.getVariant(Episode.I, 0, 0)!!,
        )
        val model = ChallengeMonsterSpawnModel(
            floorId = 7,
            roomId = 42,
            entry = spawnEntry(),
            section = section,
        )

        assertEquals(11.0, model.position.value.x)
        assertEquals(22.0, model.position.value.y)
        assertEquals(33.0, model.position.value.z)
    }

    @Test
    fun applies_the_room_section_transform_to_spawn_rotation() = test {
        val section = SectionModel(
            id = 42,
            position = Vector3(),
            rotation = euler(0.0, PI / 2.0, 0.0),
            areaVariant = components.areaStore.getVariant(Episode.I, 0, 0)!!,
        )
        val model = ChallengeMonsterSpawnModel(
            floorId = 7,
            roomId = 42,
            entry = DatCmRandomSpawnEntry(0f, 0f, 0f, 0, 0, 0, 0, 0),
            section = section,
        )

        assertEquals(0.0, model.rotation.value.x, 1e-12)
        assertEquals(PI / 2.0, model.rotation.value.y, 1e-12)
        assertEquals(0.0, model.rotation.value.z, 1e-12)
    }

    @Test
    fun composes_non_commuting_room_and_spawn_rotations_in_world_order() = test {
        val sectionRotation = euler(PI / 3.0, 0.0, 0.0)
        val section = SectionModel(
            id = 42,
            position = Vector3(),
            rotation = sectionRotation,
            areaVariant = components.areaStore.getVariant(Episode.I, 0, 0)!!,
        )
        val entry = DatCmRandomSpawnEntry(
            0f, 0f, 0f,
            0, 16384, 0,
            0, 0,
        )
        val model = ChallengeMonsterSpawnModel(7, 42, entry, section)

        val expected = Quaternion().setFromEuler(sectionRotation)
        expected *= Quaternion().setFromEuler(euler(0.0, PI / 2.0, 0.0))
        val actualRotation = model.rotation.value
        val actual = Quaternion().setFromEuler(
            euler(actualRotation.x, actualRotation.y, actualRotation.z),
        )

        // q and -q encode the same orientation.
        val dot = kotlin.math.abs(
            expected.x * actual.x + expected.y * actual.y +
                    expected.z * actual.z + expected.w * actual.w,
        )
        assertEquals(1.0, dot, 1e-12)
    }

    private fun spawnEntry() = DatCmRandomSpawnEntry(
        x = 1f,
        y = 2f,
        z = 3f,
        angleX = 16384,
        angleY = 32768,
        angleZ = 49152,
        unknownA9 = 0,
        unknownA10 = 0,
    )
}
