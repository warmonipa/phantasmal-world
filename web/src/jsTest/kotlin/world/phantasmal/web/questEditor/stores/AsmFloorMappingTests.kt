package world.phantasmal.web.questEditor.stores

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import world.phantasmal.cell.observeNow
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.dataFlowAnalysis.ControlFlowGraph
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.asm.dataFlowAnalysis.getFloorMappings
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.web.questEditor.commands.CreateEntityCommand
import world.phantasmal.web.questEditor.commands.CreateEventCommand
import world.phantasmal.web.questEditor.commands.DeleteEntityCommand
import world.phantasmal.web.questEditor.commands.DeleteEventCommand
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.WebTestContext
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestObjectModel
import world.phantasmal.web.test.createQuestNpcModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AsmFloorMappingTests : WebTestSuite {
    @Test
    fun adding_dat_objects_updates_floor_context_without_editing_the_script() = testAsync {
        val store = components.questEditorStore
        val asm = components.asmStore
        val quest = createQuestModel(bytecodeIr = assemble(listOf("620:", "ret"), Version.BB_V4).unwrap())
        store.setCurrentQuest(quest)
        awaitLabel(asm, 620)
        val model = asm.textModel.value!!
        val modelVersion = model.getAlternativeVersionId()
        val obj = createQuestObjectModel(ObjectType.Probe, floorId = 7)
        val mappings = datFloor7Mappings()

        store.executeAction(CreateEntityCommand(store, quest, obj))
        awaitMappings(quest, mappings)
        store.undo()
        awaitMappings(quest, emptyList())
        store.redo()
        awaitMappings(quest, mappings)

        assertSame(model, asm.textModel.value)
        assertEquals(modelVersion, model.getAlternativeVersionId())
    }

    @Test
    fun deleting_the_last_npc_on_a_floor_updates_context_and_undo_restores_it() = testAsync {
        val store = components.questEditorStore
        val asm = components.asmStore
        val npc = createQuestNpcModel(NpcType.Booma, Episode.I, floorId = 7)
        val mappings = datFloor7Mappings()
        val quest = createQuestModel(
            bytecodeIr = assemble(listOf("621:", "ret"), Version.BB_V4).unwrap(),
            npcs = listOf(npc), floorMappings = mappings,
        )
        store.setCurrentQuest(quest)
        awaitLabel(asm, 621)

        store.executeAction(DeleteEntityCommand(store, quest, npc))
        awaitMappings(quest, emptyList())
        store.undo()
        awaitMappings(quest, mappings)
        store.redo()
        awaitMappings(quest, emptyList())
    }

    @Test
    fun event_creation_and_deletion_update_floor_context_without_script_edits() = testAsync {
        val store = components.questEditorStore
        val asm = components.asmStore
        val quest = createQuestModel(bytecodeIr = assemble(listOf("622:", "ret"), Version.BB_V4).unwrap())
        store.setCurrentQuest(quest)
        awaitLabel(asm, 622)
        val event = QuestEventModel(1, 7, 0, 0, 0, 0, mutableListOf())
        val mappings = datFloor7Mappings()

        store.executeAction(CreateEventCommand(store, quest, 0, event))
        awaitMappings(quest, mappings)
        store.executeAction(DeleteEventCommand(store, quest, 0, event))
        awaitMappings(quest, emptyList())
        store.undo()
        awaitMappings(quest, mappings)
    }

    @Test
    fun stale_analysis_cannot_change_a_replacement_document() = testAsync {
        val store = components.questEditorStore
        val old = createQuestModel()
        store.setCurrentQuest(old)
        val current = createQuestModel(floorMappings = listOf(FloorMapping(0, 0, 0, 0)))
        store.setCurrentQuest(current)

        store.setFloorMappings(old, listOf(FloorMapping(7, 7, 7, 0)))

        assertEquals(listOf(FloorMapping(0, 0, 0, 0)), current.floorMappings)
    }

    @Test
    fun loader_owned_mapping_is_preserved_independently_of_the_area_type() = testAsync {
        val store = components.questEditorStore
        val template = createQuestModel(floorMappings = listOf(FloorMapping(7, 7, 7, 1)))
        val externallyMapped = convertQuestToModel(
            convertQuestFromModel(template), components.areaStore::getVariant,
            components.npcPlacementPolicy, floorMappingsFromScript = false,
        )
        store.setCurrentQuest(externallyMapped)

        store.setFloorMappings(externallyMapped, emptyList())

        assertEquals(template.floorMappings, externallyMapped.floorMappings)
    }

    @Test
    fun analysing_script_edits_cannot_replace_externally_selected_map_variants() = testAsync {
        val asm = components.asmStore
        primeDifferentMapping(asm)
        val quest = components.questEditorStore.getLobbyQuest(26)
        val expected = quest.floorMappings
        components.questEditorStore.setCurrentQuest(quest)
        // A new label provides a deterministic acknowledgement of this worker analysis.
        asm.textModel.value!!.setValue("999:\n    ret")
        awaitLabel(asm, 999)
        assertEquals(expected, quest.floorMappings)
        assertEquals(26, components.questEditorStore.currentAreaVariant.value?.id)
    }

    @Test
    fun worker_analysis_keeps_floors_used_only_by_dat_entities() = testAsync {
        val asm = components.asmStore
        primeDifferentMapping(asm)
        val bytecode = assemble(listOf("0:", "ret"), Version.BB_V4).unwrap()
        val expected = getFloorMappings(bytecode.instructionSegments(), usedFloorIds = setOf(7)) {
            ControlFlowGraph.create(bytecode)
        }
        val quest = createQuestModel(
            bytecodeIr = bytecode,
            objects = listOf(createQuestObjectModel(ObjectType.Probe, floorId = 7)),
            floorMappings = expected,
        )
        components.questEditorStore.setCurrentQuest(quest)
        awaitLabel(asm, 0)
        assertEquals(expected, quest.floorMappings)
    }

    @Test
    fun worker_analysis_uses_the_loaded_quest_version_for_map_designations() = testAsync {
        val asm = components.asmStore
        val bytecode = assemble(listOf(
            "0:", "set_episode 0", "leti r10, 1", "leti r11, 2", "leti r12, 0",
            "leti r13, 9", "map_designate r10", "ret",
        ), Version.BB_V4).unwrap()
        val expected = getFloorMappings(bytecode.instructionSegments(), version = Version.DC_V2) {
            ControlFlowGraph.create(bytecode)
        }
        val quest = createQuestModel(
            bytecodeIr = bytecode, version = Version.DC_V2, floorMappings = expected,
        )
        components.questEditorStore.setCurrentQuest(quest)
        awaitLabel(asm, 0)
        assertEquals(9, quest.floorMappings.single().objectSetVariation)
        assertEquals(expected, quest.floorMappings)
    }

    private suspend fun awaitLabel(asm: AsmStore, label: Int) {
        val reached = CompletableDeferred<Unit>()
        val observer = asm.labels.observeNow { labels ->
            if (labels.any { it.name == label }) reached.complete(Unit)
        }
        try {
            withTimeout(5000) { reached.await() }
            // Floor mappings are delivered before labels; allow their store coroutine to run.
            yield()
        } finally {
            observer.dispose()
        }
    }

    private suspend fun awaitMappings(quest: QuestModel, expected: List<FloorMapping>) {
        val reached = CompletableDeferred<Unit>()
        val observer = quest.floorMappingRevision.observeNow {
            if (quest.floorMappings == expected) reached.complete(Unit)
        }
        try {
            withTimeoutOrNull(5000) { reached.await() }
            assertEquals(expected, quest.floorMappings)
        } finally {
            observer.dispose()
        }
    }

    private fun datFloor7Mappings(): List<FloorMapping> =
        getFloorMappings(emptyList(), usedFloorIds = setOf(7)) {
            error("DAT-only mappings do not require a control flow graph.")
        }

    private suspend fun WebTestContext.primeDifferentMapping(asm: AsmStore) {
        components.questEditorStore.setCurrentQuest(createQuestModel(
            bytecodeIr = assemble(
                listOf("314:", "bb_map_designate 6, 6, 0, 1, 0", "ret"), Version.BB_V4,
            ).unwrap(),
        ))
        awaitLabel(asm, 314)
    }
}
