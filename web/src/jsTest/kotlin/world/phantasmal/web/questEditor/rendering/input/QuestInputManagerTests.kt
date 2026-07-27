package world.phantasmal.web.questEditor.rendering.input

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleInteractionEvent
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawn
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnOpcode
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnOrigin
import world.phantasmal.psolib.asm.dataFlowAnalysis.ParticleSpawnSource
import world.phantasmal.web.questEditor.rendering.input.state.primaryInteractionEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class QuestInputManagerTests {
    private val towerMappings = listOf(
        FloorMapping(16, 0x23, 17, 0, Episode.II),
        FloorMapping(17, 0x23, 17, 0, Episode.II),
    )

    @Test
    fun selected_logical_floor_wins_when_mappings_have_identical_maps() {
        assertEquals(
            expected = 17,
            actual = resolve(currentFloorIds = setOf(17), floorMappings = towerMappings),
        )
    }

    @Test
    fun empty_or_ambiguous_selection_falls_through_to_mapping() {
        listOf<Set<Int>?>(emptySet(), setOf(16, 17)).forEach { selection ->
            assertEquals(
                expected = 16,
                actual = resolve(currentFloorIds = selection, floorMappings = towerMappings),
                message = "Selection $selection should not override map-based resolution.",
            )
        }
    }

    @Test
    fun mapping_match_includes_effective_episode() {
        val mappings = listOf(
            FloorMapping(1, 0x01, 1, 0, Episode.I),
            FloorMapping(5, 0x13, 1, 0, Episode.II),
        )

        assertEquals(
            expected = 5,
            actual = resolve(
                questEpisode = Episode.IV,
                mapEpisode = Episode.II,
                mapAreaId = 1,
                floorMappings = mappings,
            ),
        )
    }

    @Test
    fun mapping_without_episode_inherits_quest_episode() {
        val mappings = listOf(
            FloorMapping(5, 0x13, 1, 0, mapEpisode = null),
        )

        assertEquals(
            expected = 5,
            actual = resolve(
                questEpisode = Episode.II,
                mapEpisode = Episode.II,
                mapAreaId = 1,
                floorMappings = mappings,
            ),
        )
    }

    @Test
    fun mapping_match_includes_map_variation() {
        val mappings = listOf(
            FloorMapping(4, 0x13, 1, 0, Episode.II),
            FloorMapping(5, 0x13, 1, 1, Episode.II),
        )

        assertEquals(
            expected = 5,
            actual = resolve(
                mapEpisode = Episode.II,
                mapAreaId = 1,
                mapVariation = 1,
                floorMappings = mappings,
            ),
        )
    }

    @Test
    fun absent_mapping_falls_back_to_map_area() {
        assertEquals(
            expected = 6,
            actual = resolve(mapAreaId = 6, floorMappings = emptyList()),
        )
        assertEquals(
            expected = 6,
            actual = resolve(mapAreaId = 6, floorMappings = towerMappings),
        )
    }

    @Test
    fun particle_navigation_uses_the_lowest_associated_script_label() {
        val spawn = ParticleSpawn(
            origin = ParticleSpawnOrigin.WorldPosition(0, 0, 0),
            particleId = 349,
            lifetimeFrames = 30,
            source = ParticleSpawnSource.Opcode(ParticleSpawnOpcode.ParticleV3),
            hasExtendedDrawRange = false,
            interactionEvents = setOf(
                ParticleInteractionEvent(302, ParticleInteractionEvent.Kind.Talk),
                ParticleInteractionEvent(217, ParticleInteractionEvent.Kind.Talk),
            ),
        )

        assertEquals(217, spawn.primaryInteractionEvent()?.label)
    }

    private fun resolve(
        currentFloorIds: Set<Int>? = null,
        questEpisode: Episode = Episode.II,
        mapEpisode: Episode = Episode.II,
        mapAreaId: Int = 17,
        mapVariation: Int = 0,
        floorMappings: List<FloorMapping>,
    ): Int = resolveCurrentFloorId(
        currentFloorIds = currentFloorIds,
        questEpisode = questEpisode,
        mapEpisode = mapEpisode,
        mapAreaId = mapAreaId,
        mapVariation = mapVariation,
        floorMappings = floorMappings,
    )
}
