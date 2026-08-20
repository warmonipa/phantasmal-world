package world.phantasmal.web.questEditor.models

import kotlin.test.Test
import kotlin.test.assertEquals

class QuestEventModelTests {
    @Test
    fun challenge_wave_extension_is_preserved_when_editing_max_waves() {
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
        assertEquals(0x34, event.cmMaxWaves.value)
        assertEquals(0x12, event.cmWaveExtension.value)

        event.setCmMaxWaves(0xCD)
        assertEquals(0x12CD_0706, event.cmWaveSettings.value)

        event.setCmWaveExtension(0xAB)
        assertEquals(0xABCD_0706u.toInt(), event.cmWaveSettings.value)
    }
}
