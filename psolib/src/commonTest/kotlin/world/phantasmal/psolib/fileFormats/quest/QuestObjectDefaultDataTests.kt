package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.test.LibTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals

class QuestObjectDefaultDataTests : LibTestSuite {
    @Test
    fun forest_door_defaults_to_switch_flag_and_display_digit_zero() {
        val door = QuestObject(ObjectType.ForestDoor, floorId = 1)

        assertEquals(0, door.data.getInt(52))
        assertEquals(0, door.forestDoorDigit)
    }

    @Test
    fun forest_door_display_digit_uses_second_lowest_byte_modulo_ten() {
        val door = QuestObject(ObjectType.ForestDoor, floorId = 1)

        door.data.setInt(52, 0x0000097F)
        assertEquals(9, door.forestDoorDigit)

        door.data.setInt(52, 0x0000137F)
        assertEquals(9, door.forestDoorDigit)
    }

}
