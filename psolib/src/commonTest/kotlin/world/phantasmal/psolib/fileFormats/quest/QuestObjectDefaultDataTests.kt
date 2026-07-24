package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.QEDIT_BB_QUESTS
import world.phantasmal.psolib.test.QUEST_RESOURCE_PREFIX
import world.phantasmal.psolib.test.TETHEALLA_QUESTS
import world.phantasmal.psolib.test.readFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun forest_door_digits_are_distinct_from_door_ids_in_stock_quests() = testAsync {
        val cases = listOf(
            "/quests/ep1/ext/endless nightmare #1.qst" to listOf(
                2 to 6,
                9 to 9,
                8 to 4,
                1 to 5,
                3 to 8,
                6 to 2,
                7 to 3,
                4 to 1,
                5 to 1,
                60 to 1,
                4 to 2,
                2 to 5,
                61 to 4,
                5 to 3,
                6 to 3,
                99 to 8,
                8 to 7,
                10 to 9,
                9 to 6,
            ),
            "/quests/ep1/ext/mop-up operation 1.qst" to listOf(
                2 to 6,
                9 to 9,
                8 to 4,
                1 to 5,
                3 to 8,
                6 to 2,
                7 to 3,
                60 to 7,
                5 to 1,
            ),
        )

        for ((path, expected) in cases) {
            val result = parseQstToQuest(readFile(path))
            assertTrue(result is Success)
            val actual = result.value.quest.objects
                .filter { it.type == ObjectType.ForestDoor }
                .map { door -> (door.data.getInt(52) and 0xFF) to door.forestDoorDigit }

            assertEquals(expected, actual, path)
        }
    }

    @Test
    fun all_stock_forest_door_display_values_are_single_digits() = testAsync {
        val paths = (TETHEALLA_QUESTS + QEDIT_BB_QUESTS)
            .distinct()
            .map { QUEST_RESOURCE_PREFIX + it }
        var forestQuestCount = 0
        var forestDoorCount = 0
        val rawDisplayValues = mutableSetOf<Int>()
        val rawDisplayValuesAboveNine = mutableSetOf<Int>()
        val displayDigits = mutableSetOf<Int>()

        for (path in paths) {
            val result = parseQstToQuest(readFile(path), lenient = true)
            assertTrue(result is Success, path)
            val doors = result.value.quest.objects.filter { it.type == ObjectType.ForestDoor }
            if (doors.isEmpty()) continue

            forestQuestCount++
            forestDoorCount += doors.size
            for (door in doors) {
                val rawDisplayValue = (door.data.getInt(52) ushr 8) and 0xFF
                rawDisplayValues += rawDisplayValue
                if (rawDisplayValue > 9) {
                    rawDisplayValuesAboveNine += rawDisplayValue
                }
                displayDigits += door.forestDoorDigit
                assertTrue(door.forestDoorDigit in 0..9, path)
            }
        }

        assertTrue(forestQuestCount > 0)
        assertTrue(forestDoorCount > 0)
        println(
            "forestQuests=$forestQuestCount forestDoors=$forestDoorCount " +
                    "rawDisplayValues=${rawDisplayValues.sorted()} " +
                    "rawDisplayValuesAboveNine=${rawDisplayValuesAboveNine.sorted()} " +
                    "displayDigits=${displayDigits.sorted()}"
        )
    }

}
