package world.phantasmal.web.core.undo

import world.phantasmal.cell.Cell
import world.phantasmal.cell.eq
import world.phantasmal.cell.gt
import world.phantasmal.cell.list.mutableListCell
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.cell.mutate
import world.phantasmal.web.core.commands.Command

/**
 * Full-fledged linear undo/redo implementation.
 */
class UndoStack(manager: UndoManager) : Undo {
    private val stack = mutableListCell<Command>()

    /**
     * The index where new commands are inserted. If not equal to the [stack]'s size, points to the
     * command that will be redone when calling [redo].
     */
    private val index = mutableCell(0)
    // A stack position can be reused by a new branch; document states must have unique identities.
    private val stateIds = mutableListOf(0L)
    private var nextStateId = 0L
    private var resetGeneration = 0
    private val currentStateId = mutableCell(0L)
    private val savedStateId = mutableCell(0L)
    private var undoingOrRedoing = false

    override val canUndo: Cell<Boolean> = index gt 0

    override val canRedo: Cell<Boolean> = map(stack, index) { stack, index -> index < stack.size }

    override val firstUndo: Cell<Command?> = map(stack, index) { stack, index ->
        stack.getOrNull(index - 1)
    }

    override val firstRedo: Cell<Command?> = map(stack, index) { stack, index ->
        stack.getOrNull(index)
    }

    override val atSavePoint: Cell<Boolean> = currentStateId eq savedStateId

    init {
        manager.addUndo(this)
    }

    fun push(command: Command): Command {
        if (!undoingOrRedoing) {
            mutate {
                stateIds.subList(index.value + 1, stateIds.size).clear()
                stateIds.add(++nextStateId)
                stack.splice(index.value, stack.value.size - index.value, command)
                index.value++
                currentStateId.value = stateIds[index.value]
            }
        }

        return command
    }

    override fun undo(): Boolean {
        if (undoingOrRedoing || !canUndo.value) return false

        try {
            undoingOrRedoing = true
            mutate {
                index.value -= 1
                currentStateId.value = stateIds[index.value]
                stack[index.value].undo()
            }
        } finally {
            undoingOrRedoing = false
            return true
        }
    }

    override fun redo(): Boolean {
        if (undoingOrRedoing || !canRedo.value) return false

        try {
            undoingOrRedoing = true
            mutate {
                stack[index.value].execute()
                index.value += 1
                currentStateId.value = stateIds[index.value]
            }
        } finally {
            undoingOrRedoing = false
            return true
        }
    }

    override fun captureSavePoint(): () -> Unit {
        val generation = resetGeneration
        val stateId = currentStateId.value
        return {
            if (generation == resetGeneration) savedStateId.value = stateId
        }
    }

    override fun reset() {
        resetGeneration++
        mutate {
            stack.clear()
            index.value = 0
            val stateId = ++nextStateId
            stateIds.clear()
            stateIds.add(stateId)
            currentStateId.value = stateId
            savedStateId.value = stateId
        }
    }
}
