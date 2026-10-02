package world.phantasmal.web.core.undo

import world.phantasmal.cell.observeNow
import world.phantasmal.web.core.commands.Command
import world.phantasmal.web.test.WebTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UndoStackTests : WebTestSuite {
    @Test
    fun reset_at_the_first_position_clears_the_observed_redo_command() = test {
        val stack = UndoStack(UndoManager())
        var firstRedo: Command? = null
        disposer.add(stack.firstRedo.observeNow { firstRedo = it })
        val command = DummyCommand()
        stack.push(command)
        stack.undo()
        assertSame(command, firstRedo)

        stack.reset()

        assertNull(firstRedo)
        assertFalse(stack.canRedo.value)
    }

    @Test
    fun finishing_a_save_marks_its_snapshot_instead_of_subsequent_edits() = test {
        val stack = UndoStack(UndoManager())
        stack.push(DummyCommand())
        val finishSave = stack.captureSavePoint()
        stack.push(DummyCommand())
        finishSave()
        assertFalse(stack.atSavePoint.value)
        stack.undo()
        assertTrue(stack.atSavePoint.value)
        stack.redo()
        assertFalse(stack.atSavePoint.value)
    }

    @Test
    fun finishing_a_save_of_a_discarded_branch_does_not_clean_its_replacement() = test {
        val stack = UndoStack(UndoManager())
        stack.push(DummyCommand())
        val finishSave = stack.captureSavePoint()
        stack.undo()
        stack.push(DummyCommand())
        finishSave()
        assertFalse(stack.atSavePoint.value)
        stack.undo()
        assertFalse(stack.atSavePoint.value)
        stack.redo()
        assertFalse(stack.atSavePoint.value)
    }

    @Test
    fun a_direct_stack_reset_invalidates_pending_save_callbacks_and_redo() = test {
        val stack = UndoStack(UndoManager())
        stack.push(DummyCommand())
        val finishSave = stack.captureSavePoint()
        stack.undo()
        stack.reset()
        finishSave()
        assertTrue(stack.atSavePoint.value)
        assertNull(stack.firstUndo.value)
        assertNull(stack.firstRedo.value)
        stack.push(DummyCommand())
        finishSave()
        assertFalse(stack.atSavePoint.value)
    }

    @Test
    fun manager_save_completion_captures_all_histories_and_honors_document_reset() = test {
        val manager = UndoManager()
        val first = UndoStack(manager)
        val second = UndoStack(manager)
        first.push(DummyCommand())
        second.push(DummyCommand())
        val finishSave = manager.captureSavePoint()
        first.push(DummyCommand())
        finishSave()
        assertFalse(first.atSavePoint.value)
        assertTrue(second.atSavePoint.value)
        assertFalse(manager.allAtSavePoint.value)
        first.undo()
        assertTrue(manager.allAtSavePoint.value)

        manager.reset()
        finishSave()
        assertTrue(manager.allAtSavePoint.value)
        second.push(DummyCommand())
        finishSave()
        assertFalse(manager.allAtSavePoint.value)
        second.undo()
        assertTrue(manager.allAtSavePoint.value)
    }

    @Test
    fun a_new_branch_cannot_reuse_a_discarded_save_point() = test {
        val manager = UndoManager()
        val stack = UndoStack(manager)
        stack.push(DummyCommand())
        manager.savePoint()
        stack.undo()
        stack.push(DummyCommand())
        assertFalse(stack.atSavePoint.value)
        assertFalse(manager.allAtSavePoint.value)
        stack.undo()
        stack.redo()
        assertFalse(stack.atSavePoint.value)
    }

    @Test
    fun branching_after_a_retained_save_point_can_undo_back_to_it() = test {
        val stack = UndoStack(UndoManager())
        stack.push(DummyCommand())
        stack.savePoint()
        stack.push(DummyCommand())
        stack.undo()
        stack.push(DummyCommand())
        assertFalse(stack.atSavePoint.value)
        stack.undo()
        assertTrue(stack.atSavePoint.value)
        stack.redo()
        assertFalse(stack.atSavePoint.value)
    }

    @Test
    fun simple_properties_and_invariants() = test {
        val stack = UndoStack(UndoManager())

        assertFalse(stack.canUndo.value)
        assertFalse(stack.canRedo.value)

        stack.push(DummyCommand())
        stack.push(DummyCommand())
        stack.push(DummyCommand())

        assertTrue(stack.canUndo.value)
        assertFalse(stack.canRedo.value)

        stack.undo()

        assertTrue(stack.canUndo.value)
        assertTrue(stack.canRedo.value)

        stack.undo()
        stack.undo()

        assertFalse(stack.canUndo.value)
        assertTrue(stack.canRedo.value)
    }

    @Test
    fun undo() = test {
        val stack = UndoStack(UndoManager())

        var value = 3

        stack.push(DummyCommand(execute = { value = 7 }, undo = { value = 3 })).execute()
        stack.push(DummyCommand(execute = { value = 13 }, undo = { value = 7 })).execute()

        assertEquals(13, value)

        assertTrue(stack.undo())
        assertEquals(7, value)

        assertTrue(stack.undo())
        assertEquals(3, value)

        assertFalse(stack.undo())
        assertEquals(3, value)
    }

    @Test
    fun redo() = test {
        val stack = UndoStack(UndoManager())

        var value = 3

        stack.push(DummyCommand(execute = { value = 7 }, undo = { value = 3 })).execute()
        stack.push(DummyCommand(execute = { value = 13 }, undo = { value = 7 })).execute()

        stack.undo()
        stack.undo()

        assertEquals(3, value)

        assertTrue(stack.redo())
        assertEquals(7, value)

        assertTrue(stack.redo())
        assertEquals(13, value)

        assertFalse(stack.redo())
        assertEquals(13, value)
    }

    @Test
    fun push_then_undo_then_push_again() = test {
        val stack = UndoStack(UndoManager())

        var value = 3

        stack.push(DummyCommand(execute = { value = 7 }, undo = { value = 3 })).execute()

        stack.undo()

        assertEquals(3, value)

        stack.push(DummyCommand(execute = { value = 13 }, undo = { value = 7 })).execute()

        assertEquals(13, value)
    }

    private class DummyCommand(
        private val execute: () -> Unit = {},
        private val undo: () -> Unit = {},
    ) : Command {
        override val description: String = "Dummy command"

        override fun execute() {
            execute.invoke()
        }

        override fun undo() {
            undo.invoke()
        }
    }
}
