package world.phantasmal.web.questEditor.stores

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AsmStoreNavigationTests {
    @Test
    fun finds_decimal_label_without_waiting_for_the_analyser() {
        assertEquals(
            3,
            findLabelLineNo(
                arrayOf(
                    "0:",
                    "    ret",
                    "    217:",
                    "    window_msg \"Begin investigation?\"",
                ),
                217,
            ),
        )
    }

    @Test
    fun does_not_match_a_longer_label_with_the_same_prefix() {
        assertNull(findLabelLineNo(arrayOf("2170:", "    ret"), 217))
    }
}
