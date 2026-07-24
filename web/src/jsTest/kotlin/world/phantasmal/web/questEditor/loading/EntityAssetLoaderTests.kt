package world.phantasmal.web.questEditor.loading

import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.QuestObject
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityAssetLoaderTests {
    @Test
    fun forest_door_digits_align_with_the_pso_world_font_texture_order() {
        assertEquals((0..9).toList(), (0..9).map(::forestDoorDigitTextureIndex))
        assertEquals(1, forestDoorDigitTextureIndex(11))
    }

    @Test
    fun stock_forest_door_params_select_the_expected_single_digit_texture() {
        val cases = listOf(
            // Packed param4, actual Door ID, displayed digit/texture slot.
            Triple(0x0602, 2, 6),
            Triple(0x0909, 9, 9),
            Triple(0x0408, 8, 4),
            Triple(0x0501, 1, 5),
            Triple(0x0803, 3, 8),
            Triple(0x0206, 6, 2),
            Triple(0x0307, 7, 3),
            Triple(0x073C, 60, 7),
            Triple(0x0B05, 5, 1),
        )

        for ((packedParam4, expectedDoorId, expectedDigitTexture) in cases) {
            val door = QuestObject(ObjectType.ForestDoor, floorId = 1)
            door.data.setInt(52, packedParam4)

            assertEquals(expectedDoorId, packedParam4 and 0xFF)
            assertEquals(expectedDigitTexture, door.forestDoorDigit)
            assertEquals(
                expectedDigitTexture,
                forestDoorDigitTextureIndex(door.forestDoorDigit),
            )
        }
    }
}
