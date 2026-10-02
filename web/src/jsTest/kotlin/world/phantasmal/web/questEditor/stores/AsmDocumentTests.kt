package world.phantasmal.web.questEditor.stores

import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.khronos.webgl.ArrayBuffer
import world.phantasmal.core.Failure
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.asm.disassemble
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.psolib.fileFormats.quest.parseQstToQuest
import world.phantasmal.web.externals.monacoEditor.IIdentifiedSingleEditOperation
import world.phantasmal.web.externals.monacoEditor.ICursorStateComputer
import world.phantasmal.web.externals.monacoEditor.IRange
import world.phantasmal.web.externals.monacoEditor.ITextModel
import world.phantasmal.web.questEditor.controllers.QuestEditorToolbarController
import world.phantasmal.web.questEditor.controllers.SaveFormat
import world.phantasmal.web.questEditor.controllers.QuestInfoController
import world.phantasmal.psolib.fileFormats.quest.parseBinDatToQuest
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import world.phantasmal.webui.UserAgentFeatures
import world.phantasmal.webui.obj
import kotlin.js.Promise
import kotlin.test.*

class AsmDocumentTests : WebTestSuite {
    @Test
    fun undo_after_editing_a_hidden_nop_view_restores_the_saved_bytecode() = testAsync {
        val asm = components.asmStore
        val original = assemble(listOf("0:", "nop", "ret"), Version.BB_V4).unwrap()
        val quest = createQuestModel(bytecodeIr = original)
        components.questEditorStore.setCurrentQuest(quest)
        val model = asm.textModel.value!!

        asm.setHideNops(true)
        replace(model, "0:\n    leti r1, 12\n    ret")
        assertFalse(asm.commit() is Failure)
        model.asDynamic().undo()
        assertFalse(components.questEditorStore.canSaveChanges.value)
        assertFalse(asm.commit() is Failure)

        assertEquals(
            disassemble(original, Version.BB_V4),
            disassemble(quest.bytecodeIr, Version.BB_V4),
            "Undoing to the saved logical revision must recover its hidden instructions too.",
        )
    }

    @Test
    fun lobby_dat_save_rejects_script_changes_that_the_format_cannot_store() = testAsync {
        val asm = components.asmStore
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore, components.areaStore,
            components.questEditorStore, components.questEditorUiStore, asm,
        ))
        ctrl.loadLobbyQuest(1)
        val capture = SaveCapture()
        try {
            replace(asm.textModel.value!!, "0:\n    nop\n    ret")
            assertTrue(components.questEditorStore.canSaveChanges.value)

            ctrl.saveAsDialogSave()

            assertNull(capture.bytes, "An object-only DAT cannot persist script edits.")
            assertIs<Failure>(ctrl.result.value)
            assertTrue(components.questEditorStore.canSaveChanges.value)
        } finally {
            capture.close()
        }
    }

    @Test
    fun save_as_includes_script_edits_without_waiting_for_debounce() = testAsync {
        val asm = components.asmStore
        val quest = createQuestModel(bytecodeIr = assemble(listOf("0:", "ret"), Version.BB_V4).unwrap())
        components.questEditorStore.setCurrentQuest(quest)
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore, components.areaStore,
            components.questEditorStore, components.questEditorUiStore, components.asmStore,
        ))
        val capture = SaveCapture()
        try {
            replace(asm.textModel.value!!, "0:\n    nop\n    ret")
            ctrl.saveAsDialogSave()
            assertNotNull(capture.bytes)
            val saved = parseQstToQuest(capture.bytes!!.cursor(Endianness.Little)).unwrap().quest
            assertTrue(disassemble(saved.bytecodeIr, Version.BB_V4).any { it.trim() == "nop" })
            assertFalse(components.questEditorStore.canSaveChanges.value)
        } finally {
            capture.close()
        }
    }

    @Test
    fun invalid_script_is_not_saved_or_marked_clean() = testAsync {
        val asm = components.asmStore
        components.questEditorStore.setCurrentQuest(createQuestModel())
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore, components.areaStore,
            components.questEditorStore, components.questEditorUiStore, components.asmStore,
        ))
        val capture = SaveCapture()
        try {
            replace(asm.textModel.value!!, "0:\n    invalid_opcode")
            ctrl.saveAsDialogSave()
            assertNull(capture.bytes)
            assertIs<Failure>(ctrl.result.value)
            assertTrue(components.questEditorStore.canSaveChanges.value)
        } finally {
            capture.close()
        }
    }

    @Test
    fun formatting_preserves_model_dirty_state_and_edit_history() = testAsync {
        val asm = components.asmStore
        for (hideNops in listOf(false, true)) {
            components.questEditorStore.setCurrentQuest(createQuestModel(
                bytecodeIr = assemble(listOf("0:", "nop", "ret"), Version.BB_V4).unwrap(),
            ))
            val model = asm.textModel.value!!
            replace(model, "0:\n    nop\n    leti r1, 12\n    ret")
            asm.makeUndoCurrent()
            assertTrue(components.undoManager.canUndo.value)
            if (hideNops) asm.setHideNops(true) else asm.setHexFormat(true)
            assertSame(model, asm.textModel.value)
            assertTrue(components.questEditorStore.canSaveChanges.value)
            assertTrue(components.undoManager.canUndo.value)
            // Monaco owns the actual text undo; the real editor normally invokes this.
            model.asDynamic().undo()
            model.asDynamic().undo()
            assertFalse(model.getValue().contains("leti"))
            assertFalse(components.questEditorStore.canSaveChanges.value)
        }
    }

    @Test
    fun formatting_a_saved_document_remains_clean_in_both_undo_directions() = testAsync {
        val asm = components.asmStore
        components.questEditorStore.setCurrentQuest(createQuestModel(
            bytecodeIr = assemble(listOf("0:", "leti r1, 12", "ret"), Version.BB_V4).unwrap(),
        ))
        val model = asm.textModel.value!!
        asm.setHexFormat(true)
        assertSame(model, asm.textModel.value)
        assertFalse(components.questEditorStore.canSaveChanges.value)
        model.asDynamic().undo()
        assertFalse(components.questEditorStore.canSaveChanges.value)
        model.asDynamic().redo()
        assertFalse(components.questEditorStore.canSaveChanges.value)
    }

    @Test
    fun edits_made_while_file_is_writing_remain_unsaved() = testAsync {
        val asm = components.asmStore
        components.questEditorStore.setCurrentQuest(createQuestModel())
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore, components.areaStore,
            components.questEditorStore, components.questEditorUiStore, components.asmStore,
        ))
        val model = asm.textModel.value!!
        val capture = SaveCapture { replace(model, "0:\n    nop\n    ret") }
        try {
            replace(model, "0:\n    ret")
            ctrl.saveAsDialogSave()
            assertNotNull(capture.bytes)
            assertTrue(components.questEditorStore.canSaveChanges.value)
            delay(1100)
            assertTrue(components.questEditorStore.canSaveChanges.value)
        } finally {
            capture.close()
        }
    }

    @Test
    fun bin_dat_and_existing_file_saves_include_pending_script() = testAsync {
        val asm = components.asmStore
        components.questEditorStore.setCurrentQuest(createQuestModel())
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore, components.areaStore,
            components.questEditorStore, components.questEditorUiStore, asm,
        ))
        ctrl.setSaveFormat(SaveFormat.BIN_DAT)
        ctrl.setCompressed(false)
        val capture = SaveCapture()
        try {
            replace(asm.textModel.value!!, "0:\n    nop\n    ret")
            ctrl.saveAsDialogSave()
            assertEquals(2, capture.writes.size)
            replace(asm.textModel.value!!, "0:\n    nop\n    nop\n    ret")
            ctrl.save()
            assertEquals(4, capture.writes.size)
            val quest = parseBinDatToQuest(
                capture.writes[2].cursor(Endianness.Little),
                capture.writes[3].cursor(Endianness.Little),
                compressed = false,
            ).unwrap()
            assertEquals(2, disassemble(quest.bytecodeIr, Version.BB_V4).count { it.trim() == "nop" })
            assertFalse(components.questEditorStore.canSaveChanges.value)
        } finally {
            capture.close()
        }
    }

    @Test
    fun completing_a_save_cannot_mark_a_replacement_document_saved() = testAsync {
        coroutineScope {
            val asm = components.asmStore
            components.questEditorStore.setCurrentQuest(createQuestModel())
            val ctrl = disposer.add(QuestEditorToolbarController(
                components.uiStore, components.areaStore,
                components.questEditorStore, components.questEditorUiStore, asm,
            ))
            replace(asm.textModel.value!!, "0:\n    ret")
            val started = CompletableDeferred<Unit>()
            lateinit var finish: () -> Unit
            val writeFinished = Promise<Unit> { resolve, _ -> finish = { resolve(Unit) } }
            val capture = SaveCapture(writeFinished) { started.complete(Unit) }
            try {
                val saving = async { ctrl.saveAsDialogSave() }
                started.await()
                components.questEditorStore.setCurrentQuest(createQuestModel())
                replace(asm.textModel.value!!, "0:\n    nop\n    ret")
                finish()
                saving.await()
                assertTrue(components.questEditorStore.canSaveChanges.value)
                asm.textModel.value!!.asDynamic().undo()
                assertFalse(components.questEditorStore.canSaveChanges.value)
            } finally {
                finish()
                capture.close()
            }
        }
    }

    @Test
    fun invalid_script_survives_formatting_and_a_failed_write_stays_dirty() = testAsync {
        val asm = components.asmStore
        val quest = createQuestModel()
        components.questEditorStore.setCurrentQuest(quest)
        val originalIr = quest.bytecodeIr
        val model = asm.textModel.value!!
        replace(model, "0:\n    invalid_opcode")
        asm.setHexFormat(true)
        asm.setHideNops(true)
        assertFalse(asm.hexFormat.value)
        assertFalse(asm.hideNops.value)
        assertTrue(model.getValue().contains("invalid_opcode"))
        delay(1100)
        assertSame(originalIr, quest.bytecodeIr)
        assertTrue(components.questEditorStore.canSaveChanges.value)

        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore, components.areaStore,
            components.questEditorStore, components.questEditorUiStore, asm,
        ))
        val capture = SaveCapture { error("Write failed") }
        try {
            replace(model, "0:\n    ret")
            ctrl.saveAsDialogSave()
            assertIs<Failure>(ctrl.result.value)
            assertTrue(components.questEditorStore.canSaveChanges.value)
        } finally {
            capture.close()
        }
    }

    @Test
    fun partial_format_validation_errors_are_reported_without_clearing_edits() = testAsync {
        val ctrl = disposer.add(QuestEditorToolbarController(
            components.uiStore, components.areaStore, components.questEditorStore,
            components.questEditorUiStore, components.asmStore,
        ))
        ctrl.loadLobbyQuest(1)
        val info = disposer.add(QuestInfoController(components.questEditorStore))
        info.setShortDescription("x".repeat(128))
        val capture = SaveCapture()
        try {
            ctrl.saveAsDialogSave()
            assertNull(capture.bytes)
            assertIs<Failure>(ctrl.result.value)
            assertTrue(components.questEditorStore.canSaveChanges.value)
        } finally {
            capture.close()
        }
    }

    private fun replace(model: ITextModel, text: String) {
        model.pushStackElement()
        model.pushEditOperations(null, arrayOf(obj<IIdentifiedSingleEditOperation> {
            range = model.getFullModelRange().unsafeCast<IRange>()
            this.text = text
        }), js("function() { return null }").unsafeCast<ICursorStateComputer>())
        model.pushStackElement()
    }

    private class SaveCapture(
        private val writeFinished: Promise<Unit>? = null,
        onWrite: () -> Unit = {},
    ) {
        private val originalPicker = window.asDynamic().showSaveFilePicker
        var bytes: ArrayBuffer? = null
        val writes = mutableListOf<ArrayBuffer>()

        init {
            assertTrue(UserAgentFeatures.fileSystemApi, "This regression runs in ChromeHeadless.")
            val stream = obj<dynamic> {
                write = { data: ArrayBuffer ->
                    bytes = data
                    writes.add(data)
                    onWrite()
                    writeFinished ?: Promise.resolve(Unit)
                }
                close = { Promise.resolve(Unit) }
            }
            val handle = obj<dynamic> {
                name = "test.qst"
                createWritable = { Promise.resolve<dynamic>(stream) }
            }
            window.asDynamic().showSaveFilePicker = { _: dynamic -> Promise.resolve<dynamic>(handle) }
        }

        fun close() {
            window.asDynamic().showSaveFilePicker = originalPicker
        }
    }
}
