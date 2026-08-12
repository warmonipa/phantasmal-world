package world.phantasmal.web.questEditor.controllers

import world.phantasmal.psolib.Episode
import world.phantasmal.core.Success
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcCreationOpcode
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcInteraction
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcInteractionKind
import world.phantasmal.psolib.asm.dataFlowAnalysis.ScriptNpcSpawn
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.fileFormats.Vec3
import world.phantasmal.psolib.fileFormats.quest.NPC_BYTE_SIZE
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.QuestNpc
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.testUtils.assertCloseTo
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.models.QuestNpcModel
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestNpcModel
import world.phantasmal.web.test.createQuestObjectModel
import kotlin.math.PI
import kotlin.test.*

class EntityInfoControllerTests : WebTestSuite {
    @Test
    fun forest_door_packed_param4_is_exposed_as_two_editable_properties() = testAsync {
        val ctrl = disposer.add(EntityInfoController(
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
            components.asmStore,
        ))
        val door = createQuestObjectModel(ObjectType.ForestDoor, floorId = 1)
        door.entity.data.setInt(52, 0x12340B05)
        components.questEditorStore.setCurrentQuest(createQuestModel(objects = listOf(door)))
        components.questEditorStore.setSelectedEntity(door)

        val doorId = assertIs<EntityInfoPropModel.I32>(
            ctrl.props.value.single { it.label == "Door ID:" },
        )
        val displayNumber = assertIs<EntityInfoPropModel.I32>(
            ctrl.props.value.single { it.label == "Door Display Number:" },
        )

        assertEquals(5, doorId.value.value)
        assertEquals(11, displayNumber.value.value)

        displayNumber.setValue(7)
        assertEquals(0x12340705, door.entity.data.getInt(52))
        assertEquals(5, doorId.value.value)
        assertEquals(7, displayNumber.value.value)

        doorId.setValue(60)
        assertEquals(0x1234073C, door.entity.data.getInt(52))
        assertEquals(60, doorId.value.value)
        assertEquals(7, displayNumber.value.value)

        components.questEditorStore.makeMainUndoCurrent()
        components.questEditorStore.undo()
        assertEquals(0x12340705, door.entity.data.getInt(52))
        assertEquals(5, doorId.value.value)
        assertEquals(7, displayNumber.value.value)

        components.questEditorStore.undo()
        assertEquals(0x12340B05, door.entity.data.getInt(52))
        assertEquals(5, doorId.value.value)
        assertEquals(11, displayNumber.value.value)

        components.questEditorStore.redo()
        components.questEditorStore.redo()
        assertEquals(0x1234073C, door.entity.data.getInt(52))
        assertEquals(60, doorId.value.value)
        assertEquals(7, displayNumber.value.value)
    }

    @Test
    fun test_unavailable_and_enabled() = testAsync {
        val ctrl =
            disposer.add(EntityInfoController(
                components.areaStore,
                components.questEditorStore,
                components.questEditorUiStore,
                components.asmStore,
            ))

        assertTrue(ctrl.unavailable.value)
        assertFalse(ctrl.enabled.value)

        val npc = createQuestNpcModel(NpcType.Principal, Episode.I)
        components.questEditorStore.setCurrentQuest(createQuestModel(npcs = listOf(npc)))

        assertTrue(ctrl.unavailable.value)
        assertTrue(ctrl.enabled.value)

        components.questEditorStore.setSelectedEntity(npc)

        assertFalse(ctrl.unavailable.value)
        assertTrue(ctrl.enabled.value)
    }

    @Test
    fun script_npc_exposes_read_only_source_template_and_interaction_labels() = testAsync {
        val ctrl = disposer.add(EntityInfoController(
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
            components.asmStore,
        ))
        val npc = QuestNpcModel(
            QuestNpc(NpcType.NpcRAmar, Episode.I, floorId = 0, wave = 0),
            waveId = 0,
            scriptSpawn = ScriptNpcSpawn(
                opcode = ScriptNpcCreationOpcode.NpcCrptalk,
                x = 113,
                y = 0,
                z = 64,
                angle = 60,
                templateIndex = 27,
                executionFloorIds = setOf(0),
                interactions = setOf(
                    ScriptNpcInteraction(0x141, ScriptNpcInteractionKind.Talk),
                    ScriptNpcInteraction(0x140, ScriptNpcInteractionKind.Target),
                ),
            ),
        )
        components.questEditorStore.setCurrentQuest(createQuestModel())
        components.questEditorStore.setSelectedEntity(npc)

        assertFalse(ctrl.scriptInfoHidden.value)
        assertFalse(ctrl.editingEnabled.value)
        assertEquals("NPC (Script)", ctrl.type.value)
        assertEquals("npc_crptalk_v3 (0x7D)", ctrl.scriptSource.value)
        assertEquals("DACCI (27)", ctrl.scriptTemplate.value)
        assertEquals(
            listOf(
                EntityInfoController.ScriptInteractionInfo(0x140, "Target"),
                EntityInfoController.ScriptInteractionInfo(0x141, "Talk"),
            ),
            ctrl.scriptInteractions.value,
        )
        assertFalse(ctrl.waveHidden.value)
        assertTrue(ctrl.props.value.isNotEmpty())
        val scriptLabel = assertIs<EntityInfoPropModel.F32>(
            ctrl.props.value.single { it.isScriptLabel },
        )
        assertEquals(0x140.toDouble(), scriptLabel.value.value)
        assertEquals(0x140, scriptLabel.scriptLabelId.value)

        ctrl.setPosX(999.0)
        ctrl.setRotY(90.0)
        ctrl.setWaveId(12)
        ctrl.setTypeId(123)

        assertEquals(0.0, npc.position.value.x)
        assertEquals(0.0, ctrl.rotY.value)
        assertEquals(0, npc.wave.value.id)
        assertEquals(NpcType.NpcRAmar.typeId, npc.typeId.value)
    }

    @Test
    fun can_read_regular_properties() = testAsync {
        val ctrl =
            disposer.add(EntityInfoController(
                components.areaStore,
                components.questEditorStore,
                components.questEditorUiStore,
                components.asmStore,
            ))

        val questNpc = QuestNpc(NpcType.Booma, Episode.I, floorId = 10, wave = 5)
        questNpc.sectionId = 7
        questNpc.position = Vec3(8f, 16f, 32f)
        questNpc.rotation = Vec3(PI.toFloat() / 4, PI.toFloat() / 2, PI.toFloat())
        val npc = QuestNpcModel(questNpc, 5)
        components.questEditorStore.setCurrentQuest(createQuestModel(npcs = listOf(npc)))
        components.questEditorStore.setSelectedEntity(npc)

        assertEquals("NPC", ctrl.type.value)
        assertEquals("Booma", ctrl.name.value)
        assertEquals(7, ctrl.sectionId.value)
        assertEquals(5, ctrl.waveId.value)
        assertFalse(ctrl.waveHidden.value)
        assertEquals(8.0, ctrl.posX.value)
        assertEquals(16.0, ctrl.posY.value)
        assertEquals(32.0, ctrl.posZ.value)
        assertCloseTo(45.0, ctrl.rotX.value)
        assertCloseTo(90.0, ctrl.rotY.value)
        assertCloseTo(180.0, ctrl.rotZ.value)
    }

    @Test
    fun floor_mapping_change_refreshes_resolved_npc_details() = testAsync {
        val ctrl = disposer.add(EntityInfoController(
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
            components.asmStore,
        ))
        val data = Buffer.withSize(NPC_BYTE_SIZE).apply {
            setShort(0, 0x33)
            setInt(32, 9)
        }
        val npc = QuestNpcModel(
            QuestNpc(Episode.II, floorId = 16, data = data).apply {
                mapAreaId = 6
            },
            waveId = 0,
        )
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(episode = Episode.II, npcs = listOf(npc)))
        store.setSelectedEntity(npc)

        assertNotEquals("Epsilon", ctrl.name.value)

        store.setFloorMappings(
            listOf(
                FloorMapping(
                    floorId = 16,
                    mapId = 0x23,
                    mapAreaId = 17,
                    mapVariation = 0,
                    mapEpisode = Episode.II,
                ),
            ),
        )

        assertEquals("Enemy", ctrl.type.value)
        assertEquals("Epsilon", ctrl.name.value)
        assertEquals(
            NpcType.Epsilon.properties.map { "${it.name}:" },
            ctrl.props.value.map { it.label },
        )
    }

    @Test
    fun can_set_regular_properties_undo_and_redo() = testAsync {
        val ctrl =
            disposer.add(EntityInfoController(
                components.areaStore,
                components.questEditorStore,
                components.questEditorUiStore,
                components.asmStore,
            ))

        val npc = createQuestNpcModel(NpcType.Principal, Episode.I)
        components.questEditorStore.setCurrentQuest(createQuestModel(npcs = listOf(npc)))
        components.questEditorStore.setSelectedEntity(npc)

        ctrl.setPosX(3.15)
        ctrl.setPosY(4.15)
        ctrl.setPosZ(5.15)

        ctrl.setRotX(50.0)
        ctrl.setRotY(25.4)
        ctrl.setRotZ(12.5)

        assertEquals(3.15, ctrl.posX.value)
        assertEquals(4.15, ctrl.posY.value)
        assertEquals(5.15, ctrl.posZ.value)

        assertCloseTo(50.0, ctrl.rotX.value)
        assertCloseTo(25.4, ctrl.rotY.value)
        assertCloseTo(12.5, ctrl.rotZ.value)

        components.questEditorStore.makeMainUndoCurrent()
        components.questEditorStore.undo()
        components.questEditorStore.undo()
        components.questEditorStore.undo()
        components.questEditorStore.undo()
        components.questEditorStore.undo()
        components.questEditorStore.undo()

        assertEquals(0.0, ctrl.posX.value)
        assertEquals(0.0, ctrl.posY.value)
        assertEquals(0.0, ctrl.posZ.value)

        assertEquals(0.0, ctrl.rotX.value)
        assertEquals(0.0, ctrl.rotY.value)
        assertEquals(0.0, ctrl.rotZ.value)

        components.questEditorStore.redo()
        components.questEditorStore.redo()
        components.questEditorStore.redo()
        components.questEditorStore.redo()
        components.questEditorStore.redo()
        components.questEditorStore.redo()

        assertEquals(3.15, ctrl.posX.value)
        assertEquals(4.15, ctrl.posY.value)
        assertEquals(5.15, ctrl.posZ.value)

        assertCloseTo(50.0, ctrl.rotX.value)
        assertCloseTo(25.4, ctrl.rotY.value)
        assertCloseTo(12.5, ctrl.rotZ.value)
    }

    @Test
    fun regular_npc_keeps_and_navigates_to_its_script_label() = testAsync {
        val bytecode = assemble(listOf("0:", "ret", "310:", "ret"), Version.BB_V4)
        assertTrue(bytecode is Success)
        val ctrl = disposer.add(EntityInfoController(
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
            components.asmStore,
        ))
        val npc = createQuestNpcModel(NpcType.Principal, Episode.I)
        components.questEditorStore.setCurrentQuest(createQuestModel(
            npcs = listOf(npc),
            bytecodeIr = bytecode.value,
        ))
        components.questEditorStore.setSelectedEntity(npc)
        val scriptLabel = assertIs<EntityInfoPropModel.F32>(
            ctrl.props.value.single { it.isScriptLabel },
        )
        scriptLabel.setValue(310.0001220703125)

        assertEquals(310.0001220703125, scriptLabel.value.value)
        assertEquals(310, scriptLabel.scriptLabelId.value)
        assertTrue(scriptLabel.canGoToScriptLabel.value)
    }

    @Test
    fun when_focused_main_undo_becomes_current_undo() = testAsync {
        val store = components.questEditorStore
        val ctrl = disposer.add(EntityInfoController(
            components.areaStore,
            store,
            components.questEditorUiStore,
            components.asmStore,
        ))

        // Put something on the undo stack.
        val npc = createQuestNpcModel(NpcType.Principal, Episode.I)
        store.setCurrentQuest(createQuestModel(npcs = listOf(npc)))
        store.setSelectedEntity(npc)

        ctrl.setWaveId(99)

        components.undoManager.makeNopCurrent()

        // After focusing, the main undo stack becomes the current undo and we can undo.
        ctrl.focused()

        assertTrue(store.canUndo.value)
    }

    @Test
    fun go_to_event() = testAsync {
        val store = components.questEditorStore
        var activationCount = 0
        val ctrl = disposer.add(EntityInfoController(
            components.areaStore,
            store,
            components.questEditorUiStore,
            components.asmStore,
            onActivateEventsWidget = {
                // The Events tab must become visible before selection triggers card scrolling.
                assertNull(store.selectedEvent.value)
                activationCount++
            },
        ))

        val obj = createQuestObjectModel(ObjectType.EventCollision)
        val event = QuestEventModel(id = 100, 0, 0, 0, 0, 0, mutableListOf())
        store.setCurrentQuest(createQuestModel(objects = listOf(obj), events = listOf(event)))
        store.setSelectedEntity(obj)

        // The EventCollision object has an "Event ID" property.
        val eventProp = ctrl.props.value
            .filterIsInstance<EntityInfoPropModel.I32>()
            .find { it.showGoToEvent }

        assertNotNull(eventProp)

        // Since the default value is 0 and there's no event 0, "Go to event" should be disabled.
        assertFalse(eventProp.canGoToEvent.value)
        eventProp.goToEvent()
        assertNull(store.selectedEvent.value)
        assertEquals(0, activationCount)

        // Set the value to 100 to enable.
        eventProp.setValue(100)
        assertTrue(eventProp.canGoToEvent.value)
        eventProp.goToEvent()
        assertEquals(event, store.selectedEvent.value)
        assertEquals(1, activationCount)
    }

    @Test
    fun all_script_objects_expose_a_script_navigation_property() = testAsync {
        val ctrl = disposer.add(EntityInfoController(
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
            components.asmStore,
        ))
        val objects = listOf(
            ObjectType.ScriptCollision,
            ObjectType.ScriptCollisionA,
            ObjectType.TargetableObject,
            ObjectType.ChatSensor,
            ObjectType.ForestConsole,
            ObjectType.RicoMessagePod,
            ObjectType.ComputerLikeCalus,
            ObjectType.RuinsCrystal,
            ObjectType.VRLink,
            ObjectType.GBAStation,
            ObjectType.TalkLinkToSupport,
            ObjectType.LabInvisibleObject,
        ).map(::createQuestObjectModel)
        components.questEditorStore.setCurrentQuest(createQuestModel(objects = objects))

        for ((index, obj) in objects.withIndex()) {
            components.questEditorStore.setSelectedEntity(obj)
            if (obj.type == ObjectType.TalkLinkToSupport ||
                obj.type == ObjectType.LabInvisibleObject
            ) {
                assertIs<EntityInfoPropModel.I32>(
                    ctrl.props.value.single { it.label == "Activator:" },
                ).setValue(0)
            }
            val scriptProp = assertIs<EntityInfoPropModel.I32>(
                ctrl.props.value.single { it.isScriptLabel },
            )
            scriptProp.setValue(100 + index)
            assertEquals(100 + index, scriptProp.scriptLabelId.value, obj.type.uniqueName)
        }
    }

    @Test
    fun conditional_script_navigation_updates_when_object_mode_changes() = testAsync {
        val bytecode = assemble(listOf("0:", "ret", "123:", "ret"), Version.BB_V4)
        assertTrue(bytecode is Success)
        val ctrl = disposer.add(EntityInfoController(
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
            components.asmStore,
        ))
        val obj = createQuestObjectModel(ObjectType.TalkLinkToSupport)
        components.questEditorStore.setCurrentQuest(createQuestModel(
            objects = listOf(obj),
            bytecodeIr = bytecode.value,
        ))
        components.questEditorStore.setSelectedEntity(obj)

        val scriptProp = assertIs<EntityInfoPropModel.I32>(
            ctrl.props.value.single { it.isScriptLabel },
        )
        val activatorProp = assertIs<EntityInfoPropModel.I32>(
            ctrl.props.value.single { it.label == "Activator:" },
        )
        scriptProp.setValue(123)
        activatorProp.setValue(1)
        assertFalse(scriptProp.canGoToScriptLabel.value)

        activatorProp.setValue(0)

        assertTrue(scriptProp.canGoToScriptLabel.value)
        assertEquals(123, scriptProp.scriptLabelId.value)
    }
}
