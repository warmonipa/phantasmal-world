package world.phantasmal.web.questEditor.models

import kotlin.test.Test
import kotlin.test.assertEquals

class QuestEventModelTests {
    @Test
    fun challenge_max_waves_uses_both_bytes() {
        val event = QuestEventModel(
            id = 1,
            floorId = 2,
            sectionId = 3,
            waveId = 4,
            delay = 5,
            unknown = 6,
            actions = mutableListOf(),
            cmWaveSettings = 0x1234_0706,
        )

        assertEquals(6, event.cmMinEnemies.value)
        assertEquals(7, event.cmMaxEnemies.value)
        assertEquals(0x1234, event.cmMaxWaves.value)

        event.setCmMaxWaves(0xABCD)
        assertEquals(0xABCD_0706u.toInt(), event.cmWaveSettings.value)
    }
}
