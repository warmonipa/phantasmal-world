package world.phantasmal.web.questEditor.controllers

import org.w3c.files.File
import world.phantasmal.cell.map
import world.phantasmal.cell.observeNow
import world.phantasmal.core.Failure
import world.phantasmal.core.Severity
import world.phantasmal.core.Success
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.fileFormats.quest.OBJECT_BYTE_SIZE
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.psolib.fileFormats.quest.getAreasForEpisode
import world.phantasmal.psolib.fileFormats.quest.writeQuestToBinDat
import world.phantasmal.web.core.commands.Command
import world.phantasmal.web.questEditor.loading.getLobbyVariant
import world.phantasmal.web.questEditor.loading.parseLobbyDatFilename
import world.phantasmal.web.questEditor.models.QuestEventModel
import world.phantasmal.web.questEditor.stores.convertQuestFromModel
import world.phantasmal.web.questEditor.stores.WalkthroughPlayer
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestNpcModel
import world.phantasmal.web.test.createQuestObjectModel
import world.phantasmal.webui.files.FileHandle
import kotlin.test.*

class QuestEditorToolbarControllerTests : WebTestSuite {
    @Test
    fun walkthrough_route_is_disabled_by_default_and_can_be_enabled_for_a_player() = test {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        assertEquals(WalkthroughPlayer.Off, ctrl.walkthroughPlayer.value)
        assertEquals(WalkthroughPlayer.entries, ctrl.walkthroughPlayers.value)

        ctrl.setWalkthroughPlayer(WalkthroughPlayer.Blue)

        assertEquals(WalkthroughPlayer.Blue, ctrl.walkthroughPlayer.value)
        assertEquals(WalkthroughPlayer.Blue, components.questEditorUiStore.walkthroughPlayer.value)
    }

    @Test
    fun area_selection_publishes_one_consistent_store_state() = test {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))
        val store = components.questEditorStore
        val states = mutableListOf<Triple<Set<Int>?, Int?, Int?>>()
        disposer.add(
            map(store.currentFloorIds, store.currentArea, store.currentAreaVariant) {
                    floorIds, area, variant ->
                Triple(floorIds, area?.id, variant?.area?.id)
            }.observeNow(states::add),
        )
        val area = components.areaStore.getArea(Episode.IV, 8)!!
        val variant = area.areaVariants.first()

        ctrl.setCurrentArea(AreaAndLabel(area, "Floor 8", variant, setOf(8)))

        assertEquals(
            listOf(Triple(null, null, null), Triple(setOf(8), 8, 8)),
            states,
        )
    }

    @Test
    fun entity_directions_are_disabled_by_default_and_share_the_ui_store_state() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        assertFalse(ctrl.showEntityDirections.value)

        ctrl.setShowEntityDirections(true)

        assertTrue(ctrl.showEntityDirections.value)
        assertTrue(components.questEditorUiStore.showEntityDirections.value)
    }

    @Test
    fun fog_boundaries_are_disabled_by_default_and_share_the_ui_store_state() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        assertFalse(ctrl.showFogBoundaries.value)

        ctrl.setShowFogBoundaries(true)

        assertTrue(ctrl.showFogBoundaries.value)
        assertTrue(components.questEditorUiStore.showFogBoundaries.value)
    }

    @Test
    fun can_create_a_new_quest() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        ctrl.createNewQuest(Episode.I)

        assertNotNull(components.questEditorStore.currentQuest.value)
    }

    @Test
    fun can_load_city_quest() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        ctrl.loadCityQuest(Episode.I)

        val quest = components.questEditorStore.currentQuest.value
        assertNotNull(quest)
        assertEquals(Episode.I, quest.episode)
    }

    @Test
    fun can_load_lobby_quest() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        ctrl.loadLobbyQuest(1)

        val quest = components.questEditorStore.currentQuest.value
        assertNotNull(quest)
        assertEquals(Episode.I, quest.episode)
    }

    @Test
    fun can_load_all_ephinea_lobby_categories() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        for (number in listOf(1, 11, 21, 30)) {
            ctrl.loadLobbyQuest(number)

            assertFalse(ctrl.result.value is Failure)
            assertEquals(SaveFormat.LOBBY_DAT, ctrl.saveFormat.value)
            assertEquals(listOf(SaveFormat.LOBBY_DAT), ctrl.availableSaveFormats.value)
            val datFileName = assertNotNull(getLobbyVariant(number)).datFileName
            assertEquals(datFileName.removeSuffix(".dat"), ctrl.filename.value)
            assertEquals(number, parseLobbyDatFilename(datFileName))
            val lobbyLabel = "Lobby ${number.toString().padStart(2, '0')}"
            assertTrue(ctrl.currentArea.value?.label?.contains(lobbyLabel) == true)
            assertEquals(15, components.questEditorStore.currentArea.value?.id)
            assertEquals(number, components.questEditorStore.currentAreaVariant.value?.id)
            assertTrue(
                components.questEditorStore.currentQuest.value?.objects?.value?.isNotEmpty() == true
            )
            val variant = assertNotNull(components.questEditorStore.currentAreaVariant.value)
            assertTrue(
                components.areaAssetLoader
                    .loadRenderGeometry(Episode.I, variant)
                    .children
                    .isNotEmpty()
            )
        }
    }

    @Test
    fun can_open_a_loose_lobby_dat() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))
        val objectData = Buffer.withSize(OBJECT_BYTE_SIZE, Endianness.Little)

        ctrl.openFiles(
            listOf(
                FileHandle.Simple(
                    File(arrayOf(objectData.arrayBuffer), "map_lobby_01o.dat")
                )
            )
        )

        assertFalse(ctrl.result.value is Failure)
        assertEquals(1, components.questEditorStore.currentQuest.value?.objects?.value?.size)
        assertEquals(15, components.questEditorStore.currentArea.value?.id)
        assertEquals(1, components.questEditorStore.currentAreaVariant.value?.id)
    }

    @Test
    fun city_map_toggle_switches_quest_type() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        // Start with default quest.
        ctrl.createNewQuest(Episode.I)
        assertFalse(ctrl.showCityMap.value)

        // Toggle city map on.
        ctrl.setShowCityMap(true)
        assertTrue(ctrl.showCityMap.value)
        assertNotNull(components.questEditorStore.currentQuest.value)

        // Toggle city map off.
        ctrl.setShowCityMap(false)
        assertFalse(ctrl.showCityMap.value)
        assertNotNull(components.questEditorStore.currentQuest.value)
    }

    @Test
    fun city_map_state_preserved_across_episode_switch() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        // Enable city map, then switch episode.
        ctrl.loadCityQuest(Episode.I)
        assertTrue(ctrl.showCityMap.value)

        ctrl.createNewQuest(Episode.II)
        assertTrue(ctrl.showCityMap.value, "City map state should be preserved")
        assertEquals(Episode.II, components.questEditorStore.currentQuest.value?.episode)
    }

    @Test
    fun a_failure_is_exposed_when_openFiles_fails() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        assertNull(ctrl.result.value)

        ctrl.openFiles(listOf(FileHandle.Simple(File(arrayOf(), "unknown.extension"))))

        val result = ctrl.result.value

        assertTrue(result is Failure)
        assertEquals(1, result.problems.size)
        assertEquals(Severity.Error, result.problems.first().severity)
        assertEquals(
            "Please select a .qst file, a .bin file, a .bin + .dat pair, or a free roam .dat file.",
            result.problems.first().uiMessage,
        )
    }

    @Test
    fun loading_bin_dat_preserves_the_detected_quest_version() = testAsync {
        val bytecode = assemble(
            asm = listOf(
                "0:",
                "particle r0, 0",
                "ret",
            ),
            version = Version.DC_V2,
        )
        assertTrue(bytecode is Success)

        val quest = convertQuestFromModel(createQuestModel(bytecodeIr = bytecode.value))
        val (bin, dat) = writeQuestToBinDat(quest, Version.DC_V2)
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        ctrl.openFiles(
            listOf(
                FileHandle.Simple(File(arrayOf(bin.arrayBuffer), "quest.bin")),
                FileHandle.Simple(File(arrayOf(dat.arrayBuffer), "quest.dat")),
            )
        )

        assertEquals(Version.DC_V2, ctrl.version.value)
    }

    @Test
    fun undo_state_changes_correctly() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))
        components.questEditorStore.makeMainUndoCurrent()
        val nothingToUndo = "Nothing to undo (Ctrl-Z)"
        val nothingToRedo = "Nothing to redo (Ctrl-Shift-Z)"

        // No quest loaded.

        assertEquals(nothingToUndo, ctrl.undoTooltip.value)
        assertFalse(ctrl.undoEnabled.value)

        assertEquals(nothingToRedo, ctrl.redoTooltip.value)
        assertFalse(ctrl.redoEnabled.value)

        // Load quest.
        val npc = createQuestNpcModel(NpcType.Scientist, Episode.I)
        val quest = createQuestModel(name = "Old Name", npcs = listOf(npc))
        components.questEditorStore.setCurrentQuest(quest)

        assertEquals(nothingToUndo, ctrl.undoTooltip.value)
        assertFalse(ctrl.undoEnabled.value)

        assertEquals(nothingToRedo, ctrl.redoTooltip.value)
        assertFalse(ctrl.redoEnabled.value)

        // Add a command to the undo stack.
        components.questEditorStore.executeAction(
            object : Command {
                override val description: String = "Do command"
                override fun execute() {}
                override fun undo() {}
            }
        )

        assertEquals("Undo \"Do command\" (Ctrl-Z)", ctrl.undoTooltip.value)
        assertTrue(ctrl.undoEnabled.value)

        assertEquals(nothingToRedo, ctrl.redoTooltip.value)
        assertFalse(ctrl.redoEnabled.value)

        // Undo the previous command.
        ctrl.undo()

        assertEquals(nothingToUndo, ctrl.undoTooltip.value)
        assertFalse(ctrl.undoEnabled.value)

        assertEquals("Redo \"Do command\" (Ctrl-Shift-Z)", ctrl.redoTooltip.value)
        assertTrue(ctrl.redoEnabled.value)
    }

    @Test
    fun state_changes_correctly_when_a_quest_is_loaded() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        // No quest loaded.

        // No current area and no areas to select.
        assertTrue(ctrl.areas.value.isEmpty())
        assertNull(ctrl.currentArea.value)
        assertFalse(ctrl.areaSelectEnabled.value)
        // Nothing to save.
        assertFalse(ctrl.saveAsEnabled.value)

        // Load quest.
        components.questEditorStore.setCurrentQuest(createQuestModel())

        // We have some areas and one area is selected at this point.
        assertTrue(ctrl.areas.value.isNotEmpty())
        assertNotNull(ctrl.currentArea.value)
        assertTrue(ctrl.areaSelectEnabled.value)
        // We can save the current quest.
        assertTrue(ctrl.saveAsEnabled.value)
    }

    @Test
    fun multi_floor_quest_shows_all_episode_areas() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        // Simulate PW4-like quest: EP2 with Lab (floor 0) and two Tower variants (floors 16, 17).
        // EP2 area 0 = Lab (mapId=0x12), area 17 = Tower (mapId=0x23)
        val quest = createQuestModel(
            episode = Episode.II,
            floorMappings = listOf(
                FloorMapping(floorId = 0, mapId = 0x12, mapAreaId = 0, mapVariation = 0),
                FloorMapping(floorId = 17, mapId = 0x23, mapAreaId = 17, mapVariation = 0),
                FloorMapping(floorId = 16, mapId = 0x23, mapAreaId = 17, mapVariation = 1),
            ),
        )
        components.questEditorStore.setCurrentQuest(quest)

        val areas = ctrl.areas.value
        val ep2Areas = getAreasForEpisode(Episode.II)

        // Mapped areas: Lab (1 entry) + Tower (2 variant entries) = 3.
        val labEntries = areas.filter { it.area.id == 0 }
        assertEquals(1, labEntries.size, "Lab should have 1 entry")
        assertTrue(labEntries.single().label.startsWith("Floor 0 — "))

        val towerEntries = areas.filter { it.area.id == 17 }
        assertEquals(2, towerEntries.size, "Tower should have 2 entries")
        assertNotNull(towerEntries[0].variant, "Tower entry should have a variant")
        assertNotNull(towerEntries[1].variant, "Tower entry should have a variant")
        assertNotEquals(
            towerEntries[0].variant!!.id,
            towerEntries[1].variant!!.id,
            "Tower entries should have different variant IDs",
        )
        assertTrue(towerEntries.any { it.label.startsWith("Floor 16 — ") })
        assertTrue(towerEntries.any { it.label.startsWith("Floor 17 — ") })

        // All unmapped logical floor slots should also be present (with no variant suffix).
        val mappedFloorIds = setOf(0, 16, 17)
        val unmappedEp2Areas = ep2Areas.filter { it.id !in mappedFloorIds }
        for (unmappedArea in unmappedEp2Areas) {
            val entry = areas.find { it.area.id == unmappedArea.id }
            assertNotNull(entry, "Unmapped area '${unmappedArea.name}' (id=${unmappedArea.id}) should be in list")
            assertNull(entry.variant, "Unmapped area '${unmappedArea.name}' should have no variant")
            assertFalse(entry.label.contains("Map"), "Unmapped area '${unmappedArea.name}' should have no Map suffix")
        }

        // Total = 3 mapped entries + all unmapped areas.
        assertEquals(3 + unmappedEp2Areas.size, areas.size)

        // Entries follow logical floor order; floors 16 and 17 both resolve to Tower.
        assertEquals(setOf(16), areas[16].floorIds)
        assertEquals(setOf(17), areas[17].floorIds)
        assertEquals("Tower", areas[16].area.name)
        assertEquals("Tower", areas[17].area.name)
    }

    @Test
    fun multi_floor_quest_includes_cross_episode_area_missing_from_quest_episode() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        val quest = createQuestModel(
            episode = Episode.IV,
            floorMappings = listOf(
                FloorMapping(
                    floorId = 0,
                    mapId = 0x23,
                    mapAreaId = 17,
                    mapVariation = 0,
                    mapEpisode = Episode.II,
                ),
            ),
        )
        components.questEditorStore.setCurrentQuest(quest)

        val tower = ctrl.areas.value.single {
            it.variant?.episode == Episode.II && it.area.id == 17
        }
        assertEquals("Tower", tower.area.name)
        assertEquals(setOf(0), tower.floorIds)
        assertTrue(tower.label.startsWith("Floor 0 — "))
    }

    @Test
    fun cross_episode_map_area_id_does_not_replace_another_logical_floor() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        val quest = createQuestModel(
            episode = Episode.IV,
            floorMappings = listOf(
                FloorMapping(
                    floorId = 5,
                    mapId = 0x13,
                    mapAreaId = 1,
                    mapVariation = 0,
                    mapEpisode = Episode.II,
                ),
            ),
        )
        components.questEditorStore.setCurrentQuest(quest)

        val floor1 = ctrl.areas.value.single { it.floorIds == null && it.area.id == 1 }
        val floor5 = ctrl.areas.value.single { it.floorIds == setOf(5) }

        assertEquals("Crater Route 1", floor1.area.name)
        assertNull(floor1.variant)
        assertEquals("VR Temple Alpha", floor5.area.name)
        assertEquals(Episode.II, floor5.variant?.episode)
    }

    @Test
    fun runtime_ambiguous_mapping_is_visible_in_area_label() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))
        val quest = createQuestModel(
            episode = Episode.II,
            floorMappings = listOf(
                FloorMapping(
                    floorId = 0,
                    mapId = 0x23,
                    mapAreaId = 17,
                    mapVariation = 0,
                    mapEpisode = Episode.II,
                    runtimeAmbiguous = true,
                ),
            ),
        )
        components.questEditorStore.setCurrentQuest(quest)

        val floor = ctrl.areas.value.single { it.floorIds == setOf(0) }
        assertTrue(floor.label.contains("[runtime-dependent]"))
    }

    @Test
    fun floor_mapping_change_refreshes_selected_variant_in_toolbar() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))
        val quest = createQuestModel(
            episode = Episode.II,
            floorMappings = listOf(
                FloorMapping(
                    floorId = 16,
                    mapId = 0x23,
                    mapAreaId = 17,
                    mapVariation = 0,
                    mapEpisode = Episode.II,
                ),
            ),
        )
        components.questEditorStore.setCurrentQuest(quest)
        ctrl.setCurrentArea(ctrl.areas.value.single { it.floorIds == setOf(16) })

        components.questEditorStore.setFloorMappings(
            listOf(
                FloorMapping(
                    floorId = 16,
                    mapId = 0x23,
                    mapAreaId = 17,
                    mapVariation = 1,
                    mapEpisode = Episode.II,
                ),
            ),
        )

        assertEquals(1, ctrl.currentArea.value?.variant?.id)
        assertTrue(ctrl.currentArea.value?.label?.contains("Map 2") == true)
    }

    @Test
    fun regular_quest_shows_all_episode_areas() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        // Regular quest with no floor mappings and only 1 NPC on area 1.
        val quest = createQuestModel(
            episode = Episode.I,
            npcs = listOf(createQuestNpcModel(NpcType.Boota, Episode.I, floorId = 1)),
        )
        components.questEditorStore.setCurrentQuest(quest)

        val areas = ctrl.areas.value
        val ep1Areas = getAreasForEpisode(Episode.I)

        // Every episode area should appear, even those with 0 entities.
        for (ep1Area in ep1Areas) {
            assertTrue(
                areas.any { it.area.id == ep1Area.id },
                "Area '${ep1Area.name}' (id=${ep1Area.id}) should be in list even with 0 entities",
            )
        }
    }

    @Test
    fun multi_floor_quest_shows_correct_entity_counts_per_variant() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        // EP2: Lab (floor 0), Tower v0 (floor 17), Tower v1 (floor 16)
        // Put 2 NPCs on floor 17 (Tower v0) and 1 object on floor 16 (Tower v1)
        val quest = createQuestModel(
            episode = Episode.II,
            floorMappings = listOf(
                FloorMapping(floorId = 0, mapId = 0x12, mapAreaId = 0, mapVariation = 0),
                FloorMapping(floorId = 17, mapId = 0x23, mapAreaId = 17, mapVariation = 0),
                FloorMapping(floorId = 16, mapId = 0x23, mapAreaId = 17, mapVariation = 1),
            ),
            npcs = listOf(
                createQuestNpcModel(NpcType.Boota, Episode.II, floorId = 17),
                createQuestNpcModel(NpcType.Boota, Episode.II, floorId = 17),
            ),
            objects = listOf(
                createQuestObjectModel(ObjectType.PlayerSet, floorId = 16),
            ),
        )
        components.questEditorStore.setCurrentQuest(quest)

        val areas = ctrl.areas.value
        val towerEntries = areas.filter { it.area.id == 17 }
        assertEquals(2, towerEntries.size)

        // Tower v0 (floor 17) should show 2 entities
        val towerV0 = towerEntries.find { it.variant?.id == 0 }
        assertNotNull(towerV0)
        assertTrue(towerV0.label.contains("(2)"), "Tower v0 should show 2 entities, got: ${towerV0.label}")

        // Tower v1 (floor 16) should show 1 entity
        val towerV1 = towerEntries.find { it.variant?.id == 1 }
        assertNotNull(towerV1)
        assertTrue(towerV1.label.contains("(1)"), "Tower v1 should show 1 entity, got: ${towerV1.label}")

        // Lab (floor 0) should show no entity count
        val lab = areas.find { it.area.id == 0 }
        assertNotNull(lab)
        assertFalse(lab.label.contains("("), "Lab should have no entity count, got: ${lab.label}")
    }

    @Test
    fun multi_floor_quest_area_selection_sets_correct_variant() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))
        val store = components.questEditorStore

        val quest = createQuestModel(
            episode = Episode.II,
            floorMappings = listOf(
                FloorMapping(floorId = 0, mapId = 0x12, mapAreaId = 0, mapVariation = 0),
                FloorMapping(floorId = 17, mapId = 0x23, mapAreaId = 17, mapVariation = 0),
                FloorMapping(floorId = 16, mapId = 0x23, mapAreaId = 17, mapVariation = 1),
            ),
        )
        store.setCurrentQuest(quest)

        val areas = ctrl.areas.value
        val towerEntries = areas.filter { it.area.id == 17 }

        // Select Tower variant 1
        val towerV1 = towerEntries.find { it.variant?.id == 1 }!!
        ctrl.setCurrentArea(towerV1)

        assertEquals(17, store.currentArea.value?.id)
        assertEquals(1, store.currentAreaVariant.value?.id)

        // Select Tower variant 0
        val towerV0 = towerEntries.find { it.variant?.id == 0 }!!
        ctrl.setCurrentArea(towerV0)

        assertEquals(17, store.currentArea.value?.id)
        assertEquals(0, store.currentAreaVariant.value?.id)

        // currentArea in controller should match
        assertNotNull(ctrl.currentArea.value)
        assertEquals(0, ctrl.currentArea.value?.variant?.id)
    }

    @Test
    fun multi_floor_variant_selection_clears_event_from_another_logical_floor() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))
        val store = components.questEditorStore
        val floor16Event = QuestEventModel(
            id = 1,
            floorId = 16,
            sectionId = 0,
            waveId = 0,
            delay = 0,
            unknown = 0,
            actions = mutableListOf(),
        )
        val quest = createQuestModel(
            episode = Episode.II,
            floorMappings = listOf(
                FloorMapping(floorId = 0, mapId = 0x12, mapAreaId = 0, mapVariation = 0),
                FloorMapping(floorId = 17, mapId = 0x23, mapAreaId = 17, mapVariation = 0),
                FloorMapping(floorId = 16, mapId = 0x23, mapAreaId = 17, mapVariation = 1),
            ),
            events = listOf(floor16Event),
        )
        store.setCurrentQuest(quest)
        store.setSelectedEvent(floor16Event)
        assertSame(floor16Event, store.selectedEvent.value)

        val towerVariation0 = ctrl.areas.value.single {
            it.area.id == 17 && it.variant?.id == 0
        }
        ctrl.setCurrentArea(towerVariation0)

        assertEquals(setOf(17), store.currentFloorIds.value)
        assertNull(store.selectedEvent.value)
    }

    // ---- Save As format selection tests ----

    @Test
    fun default_save_format_is_qst() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        assertEquals(SaveFormat.QST, ctrl.saveFormat.value)
    }

    @Test
    fun compressed_visible_only_for_bin_dat() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        // Default is QST — compressed checkbox hidden.
        ctrl.setSaveFormat(SaveFormat.QST)
        assertFalse(ctrl.compressedVisible.value, "Compressed should be hidden for QST")

        // BIN_DAT — compressed checkbox visible.
        ctrl.setSaveFormat(SaveFormat.BIN_DAT)
        assertTrue(ctrl.compressedVisible.value, "Compressed should be visible for BIN_DAT")

        // FREE_ROAM — compressed checkbox hidden.
        ctrl.setSaveFormat(SaveFormat.FREE_ROAM)
        assertFalse(ctrl.compressedVisible.value, "Compressed should be hidden for FREE_ROAM")
    }

    @Test
    fun available_formats_always_include_qst_and_bin_dat() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        assertTrue(ctrl.availableSaveFormats.value.contains(SaveFormat.QST))
        assertTrue(ctrl.availableSaveFormats.value.contains(SaveFormat.BIN_DAT))
    }

    @Test
    fun set_save_format_updates_cell() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore,
            components.areaStore,
            components.questEditorStore,
            components.questEditorUiStore,
        ))

        ctrl.setSaveFormat(SaveFormat.BIN_DAT)
        assertEquals(SaveFormat.BIN_DAT, ctrl.saveFormat.value)

        ctrl.setSaveFormat(SaveFormat.QST)
        assertEquals(SaveFormat.QST, ctrl.saveFormat.value)
    }
}
