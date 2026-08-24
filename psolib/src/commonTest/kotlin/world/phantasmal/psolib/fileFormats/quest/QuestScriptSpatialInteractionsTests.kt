package world.phantasmal.psolib.fileFormats.quest

import kotlin.test.Test
import kotlin.test.assertEquals
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnOrigin
import world.phantasmal.psolib.test.toInstructions

class QuestScriptSpatialInteractionsTests {
    @Test
    fun includes_non_particle_spatial_callback_families() {
        val bytecode = BytecodeIr(toInstructions("""
            0:
                set_floor_handler 7, 100
                ret
            100:
                leti r0, 10
                leti r1, 20
                leti r2, 30
                leti r3, 40
                leti r4, 200
                col_npcin r0
                ret
            200:
                ret
        """.trimIndent()))

        val interaction = getQuestScriptSpatialInteractions(
            bytecode,
            objects = emptyList(),
            npcs = emptyList(),
        ).single()

        assertEquals(ParticleSpawnOrigin.WorldPosition(10, 20, 30), interaction.origin)
        assertEquals(40, interaction.radius)
        assertEquals(200, interaction.event.label)
        assertEquals(setOf(7), interaction.executionFloorIds)
    }
}
