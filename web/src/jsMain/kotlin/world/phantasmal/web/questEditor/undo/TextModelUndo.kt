package world.phantasmal.web.questEditor.undo

import world.phantasmal.core.disposable.Disposable
import world.phantasmal.core.disposable.TrackedDisposable
import world.phantasmal.cell.Cell
import world.phantasmal.cell.MutableCell
import world.phantasmal.cell.eq
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.cell.observeNow
import world.phantasmal.cell.mutateDeferred
import world.phantasmal.web.core.commands.Command
import world.phantasmal.web.core.observable.Observable
import world.phantasmal.web.core.observable.emitter
import world.phantasmal.web.core.undo.Undo
import world.phantasmal.web.core.undo.UndoManager
import world.phantasmal.web.externals.monacoEditor.IDisposable
import world.phantasmal.web.externals.monacoEditor.ITextModel

class TextModelUndo(
    undoManager: UndoManager,
    private val description: String,
    model: Cell<ITextModel?>,
) : Undo, TrackedDisposable() {
    private val command = object : Command {
        override val description: String get() = this@TextModelUndo.description

        override fun execute() {
            _didRedo.emit(Unit)
        }

        override fun undo() {
            _didUndo.emit(Unit)
        }
    }

    private val modelObserver: Disposable
    private var modelChangeObserver: IDisposable? = null

    private val _canUndo: MutableCell<Boolean> = mutableCell(false)
    private val _canRedo: MutableCell<Boolean> = mutableCell(false)
    private val _didUndo = emitter<Unit>()
    private val _didRedo = emitter<Unit>()

    private val currentVersionId = mutableCell<Int?>(null)
    // Presentation edits have their own Monaco history entries but retain the document revision.
    private val documentVersions = mutableMapOf<Int, Int>()
    private var preservingDocumentVersion = false
    private var generation = 0
    val documentVersion: Cell<Int?> = currentVersionId
    private val savePointVersionId = mutableCell<Int?>(null)

    override val canUndo: Cell<Boolean> = _canUndo
    override val canRedo: Cell<Boolean> = _canRedo

    override val firstUndo: Cell<Command?> = canUndo.map { if (it) command else null }
    override val firstRedo: Cell<Command?> = canRedo.map { if (it) command else null }

    override val atSavePoint: Cell<Boolean> = savePointVersionId eq currentVersionId

    val didUndo: Observable<Unit> = _didUndo
    val didRedo: Observable<Unit> = _didRedo

    init {
        undoManager.addUndo(this)
        modelObserver = model.observeNow(::onModelChange)
    }

    override fun dispose() {
        modelChangeObserver?.dispose()
        modelObserver.dispose()
        super.dispose()
    }

    private fun onModelChange(model: ITextModel?) {
        mutateDeferred {
            if (disposed) return@mutateDeferred

            modelChangeObserver?.dispose()

            if (model == null) {
                reset()
                return@mutateDeferred
            }

            reset()
            var initialVersionId = model.getAlternativeVersionId()
            currentVersionId.value = initialVersionId
            savePointVersionId.value = initialVersionId
            documentVersions[initialVersionId] = initialVersionId
            var lastVersionId = initialVersionId

            modelChangeObserver = model.onDidChangeContent { event ->
                mutateDeferred {
                    val versionId = model.getAlternativeVersionId()
                    val documentVersion = if (preservingDocumentVersion) {
                        currentVersionId.value!!
                    } else {
                        documentVersions[versionId] ?: versionId
                    }
                    documentVersions[versionId] = documentVersion
                    if (event.isFlush) initialVersionId = versionId
                    if (!event.isUndoing && !event.isRedoing) lastVersionId = versionId
                    _canUndo.value = versionId != initialVersionId
                    _canRedo.value = versionId != lastVersionId
                    currentVersionId.value = documentVersion
                }
            }
        }
    }

    override fun undo(): Boolean =
        if (canUndo.value) {
            command.undo()
            true
        } else {
            false
        }

    override fun redo(): Boolean =
        if (canRedo.value) {
            command.execute()
            true
        } else {
            false
        }

    fun preserveDocumentVersion(edit: () -> Unit) {
        preservingDocumentVersion = true
        try {
            edit()
        } finally {
            preservingDocumentVersion = false
        }
    }

    override fun captureSavePoint(): () -> Unit {
        val capturedGeneration = generation
        val version = currentVersionId.value
        return {
            if (!disposed && generation == capturedGeneration) savePointVersionId.value = version
        }
    }

    override fun reset() {
        generation++
        documentVersions.clear()
        _canUndo.value = false
        _canRedo.value = false
        currentVersionId.value = null
        savePointVersionId.value = null
    }
}
