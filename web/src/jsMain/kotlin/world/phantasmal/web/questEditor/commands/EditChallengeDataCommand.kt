package world.phantasmal.web.questEditor.commands

import world.phantasmal.web.core.commands.Command

/** A reversible structural edit that only retains the objects touched by the edit. */
class EditChallengeDataCommand(
    override val description: String,
    private val executeEdit: () -> Unit,
    private val undoEdit: () -> Unit,
) : Command {
    override fun execute() {
        executeEdit()
    }

    override fun undo() {
        undoEdit()
    }
}

/** A field edit that stores only the state of the edited entry. */
class EditChallengeValueCommand<T>(
    override val description: String,
    private val capture: () -> T,
    private val restore: (T) -> Unit,
    private val edit: () -> Unit,
) : Command {
    private val before = capture()
    private var after: T? = null

    override fun execute() {
        val savedAfter = after
        if (savedAfter == null) {
            edit()
            after = capture()
        } else {
            restore(savedAfter)
        }
    }

    override fun undo() {
        restore(before)
    }
}
