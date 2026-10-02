package world.phantasmal.web.questEditor.stores

import kotlinx.browser.window
import mu.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import world.phantasmal.core.Severity
import world.phantasmal.core.PwResult
import world.phantasmal.core.Success
import world.phantasmal.core.Failure
import world.phantasmal.core.disposable.Disposer
import world.phantasmal.core.disposable.disposable
import world.phantasmal.cell.Cell
import world.phantasmal.cell.list.ListCell
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.cell.mutateDeferred
import world.phantasmal.cell.observe
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.IntFormat
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.asm.disassemble
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.web.core.observable.Emitter
import world.phantasmal.web.core.observable.Observable
import world.phantasmal.web.core.undo.UndoManager
import world.phantasmal.web.externals.monacoEditor.*
import world.phantasmal.web.questEditor.asm.AsmAnalyser
import world.phantasmal.web.questEditor.asm.monaco.*
import world.phantasmal.web.questEditor.models.QuestModel
import world.phantasmal.web.questEditor.undo.TextModelUndo
import world.phantasmal.web.shared.messages.AsmChange
import world.phantasmal.web.shared.messages.AsmRange
import world.phantasmal.web.shared.messages.AssemblyProblem
import world.phantasmal.web.shared.messages.Label
import world.phantasmal.web.shared.messages.RegisterInfo
import world.phantasmal.web.shared.messages.SegmentInfo
import world.phantasmal.webui.obj
import world.phantasmal.webui.stores.Store

private val logger = KotlinLogging.logger {}

/**
 * Depends on a global [AsmAnalyser], instantiate at most once.
 */
class AsmStore(
    private val questEditorStore: QuestEditorStore,
    private val undoManager: UndoManager,
) : Store() {
    private val _hexFormat = mutableCell(false)
    private val _hideNops = mutableCell(false)
    private var _textModel = mutableCell<ITextModel?>(null)
    private var setBytecodeIrTimeout: Int? = null
    private var assembledVersion: Int? = null
    // Hidden NOPs are absent from the view. Restoring a previous revision must restore its IR,
    // not reassemble that revision's incomplete presentation.
    private val assembledVersions = mutableMapOf<Int, BytecodeIr>()
    private var updatingPresentation = false

    /**
     * Contains all model-related disposables. All contained disposables are disposed whenever a new
     * model is created.
     */
    private val modelDisposer = addDisposable(Disposer())

    private val undo = addDisposable(TextModelUndo(undoManager, "Script edits", _textModel))

    val hexFormat: Cell<Boolean> = _hexFormat
    val hideNops: Cell<Boolean> = _hideNops
    val labels: ListCell<Label> = asmAnalyser.labels
    val registers: ListCell<RegisterInfo> = asmAnalyser.registers
    val segments: ListCell<SegmentInfo> = asmAnalyser.segments

    val textModel: Cell<ITextModel?> = _textModel

    val editingEnabled: Cell<Boolean> = questEditorStore.questEditingEnabled

    val didUndo: Observable<Unit> = undo.didUndo
    val didRedo: Observable<Unit> = undo.didRedo

    private val _goToLabelEvent = Emitter<AsmRange>()
    val goToLabelEvent: Observable<AsmRange> = _goToLabelEvent
    private var pendingGoToLabelRange: AsmRange? = null

    val problems: ListCell<AssemblyProblem> = asmAnalyser.problems

    init {
        observeNow(questEditorStore.currentQuest) { quest ->
            setTextModel(quest)
        }

        observe(asmAnalyser.floorMappings) { mappings ->
            val quest = questEditorStore.currentQuest.value ?: return@observe
            scope.launch {
                if (!disposed) questEditorStore.setFloorMappings(quest, mappings)
            }
        }

        observeNow(problems) { problems ->
            textModel.value?.let { model ->
                val markers = Array<IMarkerData>(problems.size) {
                    val problem = problems[it]
                    obj {
                        severity = when (problem.severity) {
                            Severity.Trace, Severity.Debug -> MarkerSeverity.Hint
                            Severity.Info -> MarkerSeverity.Info
                            Severity.Warning -> MarkerSeverity.Warning
                            Severity.Error -> MarkerSeverity.Error
                        }
                        message = problem.message
                        startLineNumber = problem.lineNo
                        startColumn = problem.col
                        endLineNumber = problem.lineNo
                        endColumn = problem.col + problem.len

                        // Hack: because only one warning is generated at the moment, "Unnecessary
                        // section marker.", we can simply add the Unnecessary tag here.
                        if (problem.severity == Severity.Warning) {
                            tags = arrayOf(MarkerTag.Unnecessary)
                        }
                    }
                }
                // Not sure what the "owner" parameter is for.
                setModelMarkers(model, owner = ASM_LANG_ID, markers)
            }
        }
    }

    fun makeUndoCurrent() {
        undoManager.setCurrent(undo)
    }

    fun goToLabel(labelId: Int) {
        findLabelRange(labelId)?.let(::goToLabelRange)
    }

    fun findLabelRange(labelId: Int): AsmRange? {
        val analysedRange = labels.value.find { it.name == labelId }?.range
        val lines = textModel.value?.getLinesContent()
        val lineNo = lines?.let { findLabelLineNo(it, labelId) }
        return analysedRange ?: lineNo?.let { AsmRange(it, 1, it, 1) }
    }

    fun setHexFormat(hex: Boolean) {
        updatePresentation(hex, hideNops.value)
    }

    fun setHideNops(hide: Boolean) {
        updatePresentation(hexFormat.value, hide)
    }

    fun goToLabelRange(range: AsmRange) {
        pendingGoToLabelRange = range
        _goToLabelEvent.emit(range)
    }

    /**
     * Returns the latest unhandled navigation request. This makes navigation reliable when the
     * Script widget is activated and mounted after [goToLabelRange] emits its event.
     */
    fun takePendingGoToLabelRange(): AsmRange? {
        val range = pendingGoToLabelRange
        pendingGoToLabelRange = null
        return range
    }

    override fun dispose() {
        cancelPendingAssembly()
        super.dispose()
    }

    private fun cancelPendingAssembly() {
        setBytecodeIrTimeout?.let(window::clearTimeout)
        setBytecodeIrTimeout = null
    }

    private fun setTextModel(quest: QuestModel?) {
        mutateDeferred {
            cancelPendingAssembly()
            modelDisposer.disposeAll()
            _textModel.value = null
            assembledVersion = null
            assembledVersions.clear()
            if (quest == null) {
                asmAnalyser.setAsm(emptyList())
                return@mutateDeferred
            }

            val intFmt = if (hexFormat.value) IntFormat.HEX else IntFormat.DECIMAL
            val asm = disassemble(quest.bytecodeIr, Version.BB_V4, intFmt, hideNops.value)
            val usedFloorIds = map(quest.objects, quest.npcs, quest.events) { objects, npcs, events ->
                buildSet {
                    objects.forEach { add(it.floorId) }
                    npcs.forEach { add(it.floorId) }
                    events.forEach { add(it.floorId) }
                }
            }
            asmAnalyser.setAsm(
                asm,
                usedFloorIds = usedFloorIds.value,
                version = quest.version,
            )
            modelDisposer.add(usedFloorIds.observe { floorIds ->
                if (!disposed && questEditorStore.currentQuest.value === quest) {
                    asmAnalyser.updateUsedFloorIds(floorIds)
                }
            })

            _textModel.value = createModel(asm.joinToString("\n"), ASM_LANG_ID).also { model ->
                assembledVersion = model.getAlternativeVersionId()
                assembledVersions[model.getAlternativeVersionId()] = quest.bytecodeIr
                modelDisposer.add(disposable { model.dispose() })
                model.onDidChangeContent { e ->
                    asmAnalyser.updateAsm(e.changes.map {
                        AsmChange(
                            AsmRange(
                                it.range.startLineNumber,
                                it.range.startColumn,
                                it.range.endLineNumber,
                                it.range.endColumn,
                            ),
                            it.text,
                        )
                    })
                    if (!updatingPresentation) {
                        cancelPendingAssembly()
                        setBytecodeIrTimeout = window.setTimeout({ commit() }, 1000)
                    }
                    // TODO: Update breakpoints.
                }
            }
        }
    }

    /** Synchronizes the current document before serialization, regardless of the debounce timer. */
    fun commit(): PwResult<Unit> {
        cancelPendingAssembly()
        if (disposed) return Success(Unit)
        val quest = questEditorStore.currentQuest.value ?: return Success(Unit)
        val model = textModel.value ?: return Success(Unit)
        val version = undo.documentVersion.value ?: return Success(Unit)
        if (assembledVersion == version) return Success(Unit)

        val result = assembledVersions[version]?.let { Success(it) }
            ?: assemble(model.getLinesContent().toList(), Version.BB_V4)
        // The analyser deliberately returns partial IR with errors for editor assistance.
        // Persisting a document requires an error-free assembly.
        if (result is Failure || result.problems.any { it.severity == Severity.Error }) {
            return Failure(result.problems)
        }
        val ir = (result as Success).value
        quest.setBytecodeIr(ir)
        assembledVersion = version
        return Success(Unit, result.problems)
    }

    private fun updatePresentation(hex: Boolean, hideNops: Boolean) {
        if (hex == hexFormat.value && hideNops == this.hideNops.value) return
        if (commit() is Failure) return
        val quest = questEditorStore.currentQuest.value
        val model = textModel.value
        if (quest != null && model != null) {
            val text = disassemble(
                quest.bytecodeIr, Version.BB_V4,
                if (hex) IntFormat.HEX else IntFormat.DECIMAL, hideNops,
            ).joinToString("\n")
            if (text != model.getValue()) {
                undo.documentVersion.value?.let { assembledVersions[it] = quest.bytecodeIr }
                updatingPresentation = true
                try {
                    undo.preserveDocumentVersion {
                        model.pushStackElement()
                        model.pushEditOperations(null, arrayOf(obj<IIdentifiedSingleEditOperation> {
                            range = model.getFullModelRange().unsafeCast<IRange>()
                            this.text = text
                        }), js("function() { return null }").unsafeCast<ICursorStateComputer>())
                        model.pushStackElement()
                    }
                } finally {
                    updatingPresentation = false
                }
            }
        }
        mutateDeferred {
            _hexFormat.value = hex
            _hideNops.value = hideNops
        }
    }

    companion object {
        private val asmAnalyser = AsmAnalyser()
        // Page-lifetime scope for Monaco providers that need coroutines.
        private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        const val ASM_LANG_ID = "psoasm"

        init {
            register(obj { id = ASM_LANG_ID })
            setMonarchTokensProvider(ASM_LANG_ID, AsmMonarchLanguage)
            setLanguageConfiguration(ASM_LANG_ID, AsmLanguageConfiguration)
            registerCompletionItemProvider(ASM_LANG_ID, AsmCompletionItemProvider(asmAnalyser))
            registerSignatureHelpProvider(ASM_LANG_ID, AsmSignatureHelpProvider(asmAnalyser))
            registerHoverProvider(ASM_LANG_ID, AsmHoverProvider(asmAnalyser))
            registerDefinitionProvider(ASM_LANG_ID, AsmDefinitionProvider(asmAnalyser))
            registerDocumentSymbolProvider(ASM_LANG_ID, createDocumentSymbolProvider(providerScope, asmAnalyser))
            registerDocumentHighlightProvider(
                ASM_LANG_ID,
                AsmDocumentHighlightProvider(asmAnalyser)
            )
            // TODO: Add semantic highlighting with registerDocumentSemanticTokensProvider (or
            //  registerDocumentRangeSemanticTokensProvider?).
            //  Enable when calling editor.create with 'semanticHighlighting.enabled': true.
            //  See: https://github.com/microsoft/monaco-editor/issues/1833#issuecomment-588108427
        }
    }
}

internal fun findLabelLineNo(lines: Array<String>, labelId: Int): Int? {
    val declaration = "$labelId:"
    val index = lines.indexOfFirst { it.trimStart().startsWith(declaration) }
    return index.takeIf { it >= 0 }?.plus(1)
}
