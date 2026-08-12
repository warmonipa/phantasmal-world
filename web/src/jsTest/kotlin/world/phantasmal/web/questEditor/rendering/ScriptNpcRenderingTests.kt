package world.phantasmal.web.questEditor.rendering

import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcSpawn
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcCreationOpcode
import world.phantasmal.psolib.asm.dataFlowAnalysis.SCRIPT_NPC_TEMPLATES
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.cell.list.ListChange
import world.phantasmal.cell.list.ListChangeEvent
import world.phantasmal.web.questEditor.models.QuestNpcModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptNpcRenderingTests : WebTestSuite {
    @Test
    fun magnitude_of_metal_templates_become_read_only_preview_models() = test {
        val quest = createQuestModel()
        val spawns = listOf(
            ScriptNpcSpawn(
                ScriptNpcCreationOpcode.NpcCrptalk,
                x = 113,
                y = 0,
                z = 64,
                angle = 60,
                templateIndex = 0x1B,
                npcSlot = 3,
                state = 0,
                executionFloorIds = setOf(0),
            ),
            ScriptNpcSpawn(
                ScriptNpcCreationOpcode.NpcCrp,
                x = 24,
                y = 23,
                z = -22,
                angle = 330,
                templateIndex = 0x0F,
                ownerSlot = 0,
                executionFloorIds = setOf(1),
            ),
        )

        val pioneer2 = scriptNpcPreviewModels(spawns, setOf(0), quest).single()
        assertEquals(NpcType.NpcRAmar, pioneer2.type)
        assertEquals(113.0, pioneer2.worldPosition.value.x)
        assertTrue(pioneer2.isScriptCreated)
        assertTrue(pioneer2.sectionInitialized.value)

        val forest = scriptNpcPreviewModels(spawns, setOf(1), quest).single()
        assertEquals(NpcType.NpcRAcaseal, forest.type)
        assertEquals(-22.0, forest.worldPosition.value.z)
        assertTrue(forest.isScriptCreated)
    }

    @Test
    fun previews_are_filtered_by_reachable_floor() = test {
        val quest = createQuestModel()
        val spawn = ScriptNpcSpawn(
            ScriptNpcCreationOpcode.NpcCrp,
            x = 1,
            y = 2,
            z = 3,
            angle = 4,
            templateIndex = 0x0F,
            executionFloorIds = setOf(7),
        )

        assertEquals(emptyList(), scriptNpcPreviewModels(listOf(spawn), setOf(6), quest))
        assertEquals(1, scriptNpcPreviewModels(listOf(spawn), setOf(7), quest).size)
    }

    @Test
    fun every_script_template_class_maps_to_the_correct_rendered_npc_type() = test {
        val quest = createQuestModel()
        val templates = SCRIPT_NPC_TEMPLATES.distinctBy { it.characterClass }
        val spawns = templates.mapIndexed { index, template ->
            ScriptNpcSpawn(
                ScriptNpcCreationOpcode.NpcCrp,
                x = index,
                y = index + 1,
                z = index + 2,
                angle = index + 3,
                templateIndex = template.index,
                executionFloorIds = setOf(0),
            )
        }

        assertEquals(
            listOf(
                NpcType.NpcHUnewearl,
                NpcType.NpcFOmarl,
                NpcType.NpcHUmar,
                NpcType.NpcFOnewearl,
                NpcType.NpcRAcaseal,
                NpcType.NpcRAmar,
                NpcType.NpcHUcast,
                NpcType.NpcRAcast,
                NpcType.NpcFOnewm,
            ),
            scriptNpcPreviewModels(spawns, setOf(0), quest).map { it.type },
        )
    }

    @Test
    fun previews_convert_degree_angle_expand_per_floor_and_use_floor_episode() = test {
        val quest = createQuestModel(
            episode = Episode.I,
            floorMappings = listOf(
                FloorMapping(1, 0x12, 0, 0, Episode.II),
                FloorMapping(2, 0x12, 0, 0, Episode.IV),
            ),
        )
        val spawn = ScriptNpcSpawn(
            ScriptNpcCreationOpcode.NpcCrp,
            x = 1,
            y = 2,
            z = 3,
            angle = 175,
            templateIndex = 0,
            executionFloorIds = setOf(1, 2),
        )

        val previews = scriptNpcPreviewModels(listOf(spawn), setOf(1, 2), quest)
            .sortedBy { it.floorId }
        assertEquals(listOf(1, 2), previews.map { it.floorId })
        assertEquals(listOf(Episode.II, Episode.IV), previews.map { it.entity.episode })
        assertTrue(previews.all { it.entity.data.getInt(36) == 31_857 })
        assertTrue(previews.all { it.isScriptCreated && it.sectionInitialized.value })
    }

    @Test
    fun unknown_templates_never_create_render_models() = test {
        val quest = createQuestModel()
        val spawn = ScriptNpcSpawn(
            ScriptNpcCreationOpcode.NpcCrp,
            x = 1,
            y = 2,
            z = 3,
            angle = 4,
            templateIndex = 64,
            executionFloorIds = setOf(0),
        )

        assertTrue(scriptNpcPreviewModels(listOf(spawn), setOf(0), quest).isEmpty())
    }

    @Test
    fun recomputing_script_npc_previews_rebinds_or_clears_selection() = test {
        val store = components.questEditorStore
        val quest = createQuestModel()
        val spawn = ScriptNpcSpawn(
            ScriptNpcCreationOpcode.NpcCrp,
            x = 1,
            y = 2,
            z = 3,
            angle = 4,
            templateIndex = 0,
            executionFloorIds = setOf(0),
        )
        val oldPreview = scriptNpcPreviewModels(listOf(spawn), setOf(0), quest).single()
        val equivalentReplacement = scriptNpcPreviewModels(
            listOf(spawn),
            setOf(0),
            quest,
        ).single()

        store.setSelectedEntity(oldPreview)
        store.setHighlightedEntity(oldPreview)
        reconcileDetachedScriptNpcSelection(
            store,
            replacementEvent(oldPreview, equivalentReplacement),
        )

        assertEquals(equivalentReplacement, store.selectedEntity.value)
        assertEquals(equivalentReplacement, store.highlightedEntity.value)

        val changedReplacement = scriptNpcPreviewModels(
            listOf(spawn.copy(x = 10)),
            setOf(0),
            quest,
        ).single()
        reconcileDetachedScriptNpcSelection(
            store,
            replacementEvent(equivalentReplacement, changedReplacement),
        )

        assertNull(store.selectedEntity.value)
        assertNull(store.highlightedEntity.value)
    }

    private fun replacementEvent(
        oldPreview: QuestNpcModel,
        newPreview: QuestNpcModel,
    ): ListChangeEvent<QuestNpcModel> = ListChangeEvent(
        value = listOf(newPreview),
        changes = listOf(
            ListChange(
                index = 0,
                prevSize = 1,
                removed = listOf(oldPreview),
                inserted = listOf(newPreview),
            )
        ),
    )
}
