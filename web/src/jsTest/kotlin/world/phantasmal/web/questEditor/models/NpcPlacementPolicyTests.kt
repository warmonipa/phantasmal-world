package world.phantasmal.web.questEditor.models

import world.phantasmal.psolib.Episode
import world.phantasmal.cell.observeNow
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.fileFormats.quest.NPC_BYTE_SIZE
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.QuestNpc
import world.phantasmal.web.core.euler
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.WebTestContext
import world.phantasmal.web.test.createQuestNpcModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NpcPlacementPolicyTests : WebTestSuite {
    @Test
    fun ui_store_exposes_the_policy_cell_as_its_single_source_of_truth() = test {
        assertSame(
            components.npcPlacementPolicy.spawnOnGround,
            components.questEditorUiStore.spawnMonstersOnGround,
        )

        components.questEditorUiStore.setSpawnMonstersOnGround(true)

        assertEquals(true, components.npcPlacementPolicy.spawnOnGround.value)
    }

    @Test
    fun policies_are_isolated_between_editor_instances() = test {
        val first = NpcPlacementPolicy()
        val second = NpcPlacementPolicy()
        val section = section(y = 7.0)
        val firstNpc = createQuestNpcModel(NpcType.Booma, Episode.I, placementPolicy = first)
        val secondNpc = createQuestNpcModel(NpcType.Booma, Episode.I, placementPolicy = second)
        firstNpc.setSection(section, keepRelativeTransform = true)
        secondNpc.setSection(section, keepRelativeTransform = true)

        first.installGroundHeightProvider { _, _, _ -> 41.0 }
        second.installGroundHeightProvider { _, _, _ -> 83.0 }
        first.setSpawnOnGround(true)
        second.setSpawnOnGround(true)

        assertEquals(41.0, firstNpc.worldPosition.value.y)
        assertEquals(83.0, secondNpc.worldPosition.value.y)
    }

    @Test
    fun provider_changes_invalidate_observed_world_positions() = test {
        val policy = NpcPlacementPolicy()
        val section = section(y = 12.0)
        val npc = createQuestNpcModel(NpcType.Booma, Episode.I, placementPolicy = policy)
        npc.setSection(section, keepRelativeTransform = true)
        val observedHeights = mutableListOf<Double>()
        disposer.add(npc.worldPosition.observeNow { observedHeights += it.y })
        var providerHeight = 64.0
        val installed = policy.installGroundHeightProvider { _, _, _ -> providerHeight }
        policy.setSpawnOnGround(true)
        assertEquals(64.0, observedHeights.last())

        providerHeight = 80.0
        policy.invalidateGroundHeights()
        assertEquals(80.0, observedHeights.last())

        installed.dispose()
        assertEquals(12.0, observedHeights.last())

        policy.installGroundHeightProvider { _, _, _ -> 96.0 }
        assertEquals(96.0, observedHeights.last())
    }

    @Test
    fun a_policy_has_one_explicit_ground_height_owner() = test {
        val policy = NpcPlacementPolicy()
        policy.installGroundHeightProvider { _, _, _ -> 0.0 }

        assertFailsWith<IllegalStateException> {
            policy.installGroundHeightProvider { _, _, _ -> 1.0 }
        }
    }

    @Test
    fun stage_npcs_keep_their_grounding_and_type_offset_behavior() = test {
        val policy = NpcPlacementPolicy()
        val data = Buffer.withSize(NPC_BYTE_SIZE).apply {
            setShort(0, 0x33)
            setInt(32, 9)
        }
        val npc = QuestNpc(Episode.II, floorId = 17, data = data).apply {
            mapAreaId = 17
        }
        val model = QuestNpcModel(npc, waveId = 0, policy)
        model.setSection(section(y = 5.0), keepRelativeTransform = true)
        policy.installGroundHeightProvider { _, _, _ -> 21.0 }

        assertEquals(NpcType.Epsilon, model.type)
        assertEquals(41.0, model.worldPosition.value.y)
    }

    private fun WebTestContext.section(y: Double) = SectionModel(
        id = 1,
        position = Vector3(0.0, y, 0.0),
        rotation = euler(0.0, 0.0, 0.0),
        areaVariant = components.areaStore.getVariant(Episode.I, 0, 0)!!,
    )
}
