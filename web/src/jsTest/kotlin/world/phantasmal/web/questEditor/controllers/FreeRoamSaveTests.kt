package world.phantasmal.web.questEditor.controllers

import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.khronos.webgl.ArrayBuffer
import org.w3c.files.File
import world.phantasmal.core.Failure
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.asm.disassemble
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.quest.NpcType
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.Quest
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.psolib.fileFormats.quest.parseBinDatToQuest
import world.phantasmal.psolib.fileFormats.quest.writeQuestToBinDat
import world.phantasmal.web.externals.monacoEditor.ICursorStateComputer
import world.phantasmal.web.externals.monacoEditor.IIdentifiedSingleEditOperation
import world.phantasmal.web.externals.monacoEditor.IRange
import world.phantasmal.web.externals.monacoEditor.ITextModel
import world.phantasmal.web.externals.three.Vector3
import world.phantasmal.web.questEditor.commands.DeleteEntityCommand
import world.phantasmal.web.questEditor.commands.TranslateEntityCommand
import world.phantasmal.web.questEditor.loading.DatFloorSection
import world.phantasmal.web.questEditor.loading.extractRawEntityDataByFloor
import world.phantasmal.web.questEditor.loading.synthesizeDat
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.stores.convertQuestFromModel
import world.phantasmal.web.test.WebTestContext
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.web.test.createQuestNpcModel
import world.phantasmal.web.test.createQuestObjectModel
import world.phantasmal.webui.UserAgentFeatures
import world.phantasmal.webui.files.FileHandle
import world.phantasmal.webui.obj
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FreeRoamSaveTests : WebTestSuite {
    @Test
    fun save_current_directory_includes_the_latest_script_without_debounce() = testAsync {
        val controller = createController()
        val directory = createDirectory()
        try {
            directory.open(controller)
            replace(components.asmStore.textModel.value!!, "0:\n    nop\n    ret")

            controller.save()

            assertFalse(controller.result.value is Failure)
            assertTrue(directory.readQuest().hasInstruction("nop"))
            assertTrue(BIN_NAME in directory.writtenNames)
            assertFalse(components.questEditorStore.canSaveChanges.value)
        } finally {
            directory.close()
        }
    }

    @Test
    fun script_edits_during_directory_write_stay_dirty_and_do_not_change_the_saved_snapshot() = testAsync {
        val controller = createController()
        val directory = createDirectory()
        try {
            directory.open(controller)
            val model = components.asmStore.textModel.value!!
            replace(model, "0:\n    nop\n    ret")
            directory.onDataWrite = { name ->
                if (name == BIN_NAME) {
                    directory.onDataWrite = null
                    replace(model, "0:\n    leti r1, 27\n    ret")
                }
            }

            controller.save()

            assertFalse(controller.result.value is Failure)
            assertTrue(directory.readQuest().hasInstruction("nop"))
            assertTrue(model.getValue().contains("leti"))
            assertTrue(components.questEditorStore.canSaveChanges.value)
            delay(1100)
            assertTrue(components.questEditorStore.canSaveChanges.value)
        } finally {
            directory.close()
        }
    }

    @Test
    fun entity_edits_during_directory_write_stay_dirty_and_all_files_share_one_snapshot() = testAsync {
        val controller = createController()
        val directory = createDirectory()
        try {
            directory.open(controller)
            val quest = assertNotNull(components.questEditorStore.currentQuest.value)
            val entity = quest.objects.value.single()
            move(entity, 10.0)
            directory.onDataWrite = { name ->
                if (name == BIN_NAME) {
                    directory.onDataWrite = null
                    move(entity, 20.0)
                }
            }

            controller.save()

            assertFalse(controller.result.value is Failure)
            assertEquals(10f, directory.readQuest().objects.single().position.x)
            assertEquals(20.0, entity.position.value.x)
            assertTrue(components.questEditorStore.canSaveChanges.value)
        } finally {
            directory.close()
        }
    }

    @Test
    fun deleting_the_last_entities_writes_empty_object_and_npc_files() = testAsync {
        val controller = createController()
        val directory = createDirectory()
        try {
            directory.open(controller)
            val store = components.questEditorStore
            val quest = assertNotNull(store.currentQuest.value)
            store.executeAction(DeleteEntityCommand(store, quest, quest.objects.value.single()))
            store.executeAction(DeleteEntityCommand(store, quest, quest.npcs.value.single()))

            controller.save()

            assertFalse(controller.result.value is Failure)
            assertTrue(OBJECTS_NAME in directory.writtenNames)
            assertTrue(NPCS_NAME in directory.writtenNames)
            assertEquals(0, directory.files.getValue(OBJECTS_NAME).byteLength)
            assertEquals(0, directory.files.getValue(NPCS_NAME).byteLength)
            assertTrue(directory.readQuest().objects.isEmpty())
            assertTrue(directory.readQuest().npcs.isEmpty())
            assertFalse(store.canSaveChanges.value)
        } finally {
            directory.close()
        }
    }

    private fun WebTestContext.createController(): QuestEditorToolbarController =
        disposer.add(QuestEditorToolbarController(
            components.uiStore, components.areaStore, components.questEditorStore,
            components.questEditorUiStore, components.asmStore,
        ))

    private fun WebTestContext.createDirectory(): DirectoryCapture {
        val model = createQuestModel(
            objects = listOf(createQuestObjectModel(ObjectType.PlayerSet)),
            npcs = listOf(createQuestNpcModel(NpcType.FemaleBase, Episode.I)),
            bytecodeIr = assemble(listOf("0:", "ret"), Version.BB_V4).unwrap(),
        )
        val quest = convertQuestFromModel(model)
        val entities = extractRawEntityDataByFloor(quest).getValue(0)
        return DirectoryCapture(mapOf(
            BIN_NAME to writeQuestToBinDat(quest, Version.BB_V4).first,
            OBJECTS_NAME to entities.first,
            NPCS_NAME to entities.second,
        ))
    }

    private fun WebTestContext.move(entity: QuestObjectModel, x: Double) {
        components.questEditorStore.executeAction(TranslateEntityCommand(
            components.questEditorStore, entity, newSection = null, oldSection = null,
            newPosition = Vector3(x, 0.0, 0.0), oldPosition = entity.position.value.clone(),
            world = false,
        ))
    }

    private fun replace(model: ITextModel, text: String) {
        model.pushStackElement()
        model.pushEditOperations(null, arrayOf(obj<IIdentifiedSingleEditOperation> {
            range = model.getFullModelRange().unsafeCast<IRange>()
            this.text = text
        }), js("function() { return null }").unsafeCast<ICursorStateComputer>())
        model.pushStackElement()
    }

    private fun Quest.hasInstruction(instruction: String): Boolean =
        disassemble(bytecodeIr, Version.BB_V4).any { it.trim() == instruction }

    private class DirectoryCapture(initialFiles: Map<String, Buffer>) {
        private val originalPicker = window.asDynamic().showDirectoryPicker
        val files = initialFiles.mapValues { it.value.arrayBuffer }.toMutableMap()
        val writtenNames = mutableListOf<String>()
        var onDataWrite: ((String) -> Unit)? = null

        init {
            assertTrue(UserAgentFeatures.directoryPickerApi, "This regression runs in ChromeHeadless.")
            val dataDirectory = directory(files, captureWrites = true)
            val backupDirectory = directory(mutableMapOf(), captureWrites = false)
            val rootDirectory = obj<dynamic> {
                name = "test-game"
                getDirectoryHandle = { name: String, _: dynamic ->
                    when (name) {
                        "data" -> Promise.resolve<dynamic>(dataDirectory)
                        "backup" -> Promise.resolve<dynamic>(backupDirectory)
                        else -> Promise.reject(IllegalStateException("NotFoundError: $name"))
                    }
                }
            }
            window.asDynamic().showDirectoryPicker = { Promise.resolve<dynamic>(rootDirectory) }
        }

        suspend fun open(controller: QuestEditorToolbarController) {
            controller.openFiles(listOf(FileHandle.Simple(File(arrayOf(files.getValue(BIN_NAME)), BIN_NAME))))
            assertFalse(controller.result.value is Failure)
            assertEquals(SaveFormat.FREE_ROAM, controller.saveFormat.value)
        }

        fun readQuest(): Quest = parseBinDatToQuest(
            files.getValue(BIN_NAME).cursor(Endianness.Little),
            synthesizeDat(listOf(DatFloorSection(
                floorId = 0,
                objData = Buffer.fromArrayBuffer(files.getValue(OBJECTS_NAME), Endianness.Little),
                npcData = Buffer.fromArrayBuffer(files.getValue(NPCS_NAME), Endianness.Little),
            ))),
            compressed = false,
        ).unwrap()

        fun close() {
            window.asDynamic().showDirectoryPicker = originalPicker
        }

        private fun directory(contents: MutableMap<String, ArrayBuffer>, captureWrites: Boolean): dynamic =
            obj<dynamic> {
                name = if (captureWrites) "data" else "backup"
                getFileHandle = { filename: String, options: dynamic ->
                    if (filename.isEmpty()) {
                        Promise.reject(IllegalArgumentException("File name cannot be empty."))
                    } else if (filename !in contents && options?.create != true) {
                        Promise.reject(IllegalStateException("NotFoundError: $filename"))
                    } else {
                        val handle = obj<dynamic> {
                            name = filename
                            getFile = {
                                Promise.resolve(File(arrayOf(contents.getValue(filename)), filename))
                            }
                            createWritable = {
                                Promise.resolve<dynamic>(obj<dynamic> {
                                    write = { data: ArrayBuffer ->
                                        Promise<Unit> { resolve, reject ->
                                            // Yield during the write so edits exercise the asynchronous
                                            // save boundary, with separate native handles for each file.
                                            window.setTimeout({
                                                try {
                                                    contents[filename] = data.slice(0)
                                                    if (captureWrites) {
                                                        writtenNames.add(filename)
                                                        onDataWrite?.invoke(filename)
                                                    }
                                                    resolve(Unit)
                                                } catch (e: Throwable) {
                                                    reject(e)
                                                }
                                            }, 0)
                                        }
                                    }
                                    close = { Promise.resolve(Unit) }
                                })
                            }
                        }
                        Promise.resolve<dynamic>(handle)
                    }
                }
            }
    }

    companion object {
        private const val BIN_NAME = "map_city_on_e.bin"
        private const val OBJECTS_NAME = "map_city00_00o.dat"
        private const val NPCS_NAME = "map_city00_00e.dat"
    }
}
