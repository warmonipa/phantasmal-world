package world.phantasmal.web.questEditor.rendering.input.state

import kotlin.test.Test
import kotlin.test.assertEquals

class CreationStateTests {
    @Test
    fun single_selected_logical_floor_is_used_for_new_entities() {
        assertEquals(
            expected = 16,
            actual = creationFloorId(currentFloorIds = setOf(16), mapAreaId = 17),
        )
    }

    @Test
    fun missing_or_ambiguous_floor_selection_falls_back_to_map_area() {
        val selections = listOf<Set<Int>?>(
            null,
            emptySet(),
            setOf(16, 17),
        )

        selections.forEach { selection ->
            assertEquals(
                expected = 5,
                actual = creationFloorId(currentFloorIds = selection, mapAreaId = 5),
                message = "Selection $selection should not identify one logical floor.",
            )
        }
    }
}
