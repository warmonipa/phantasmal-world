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
import world.phantasmal.cell.list.dependingOnElements
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.IntFormat
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.asm.dataFlowAnalysis.FloorMapping
import world.phantasmal.psolib.asm.disassemble
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.psolib.fileFormats.quest.getActiveDatScriptEntryFloorIds
import world.phantasmal.web.core.observable.Emitter
import world.phantasmal.web.core.observable.Observable
import world.phantasmal.web.core.undo.UndoManager
import world.phantasmal.web.externals.monacoEditor.*
import world.phantasmal.web.questEditor.asm.AsmAnalyser
import world.phantasmal.web.questEditor.asm.AsmTextEdit
import world.phantasmal.web.questEditor.asm.FloorMappingsUpdate
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
    /** Document versions whose cached IR was assembled from a lossy (NOP-stripped) text view. */
    private val lossyIrVersions = mutableSetOf<Int>()
    /** Exact Monaco alternativeVersionIds whose text omits NOPs (or descended from one). */
    private val lossyNopVersions = mutableSetOf<Int>()
    private var lastModelVersionId: Int? = null
    private var lastAppliedGeneration = 0
    private var lastEditGeneration = 0
    private var attachedEditorCount = 0
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

        addDisposable(asmAnalyser.floorMappingsUpdates.observe { update ->
            val quest = questEditorStore.currentQuest.value ?: return@observe
            applyFloorMappingsUpdate(quest, update)
        })

        // Model-level undo/redo fallback when Script tab (AsmEditorWidget) is not mounted.
        addDisposable(didUndo.observe {
            if (attachedEditorCount == 0 && editingEnabled.value) {
                textModel.value?.asDynamic()?.undo()
            }
        })
        addDisposable(didRedo.observe {
            if (attachedEditorCount == 0 && editingEnabled.value) {
                textModel.value?.asDynamic()?.redo()
            }
        })

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

    fun registerEditor(): world.phantasmal.core.disposable.Disposable {
        attachedEditorCount++
        return disposable { attachedEditorCount = (attachedEditorCount - 1).coerceAtLeast(0) }
    }

    /** True when the current Monaco text revision omits NOPs or descended from a NOP-stripped edit. */
    fun isCurrentModelLossy(): Boolean {
        val model = textModel.value ?: return false
        return model.getAlternativeVersionId() in lossyNopVersions
    }

    /**
     * Atomically applies a pre-validated programmatic script edit to Monaco, updates the quest's
     * bytecode IR and floor mappings synchronously, and switches active Undo to the script stack.
     */
    fun applyProgrammaticEdit(
        quest: QuestModel,
        edit: AsmTextEdit,
        candidateIr: BytecodeIr,
        candidateMappings: List<FloorMapping>,
        targetFloorId: Int?,
        scriptEpisode: Episode,
    ): Boolean {
        if (disposed || !editingEnabled.value || questEditorStore.currentQuest.value !== quest) {
            return false
        }
        val model = textModel.value ?: return false
        cancelPendingAssembly()

        model.pushStackElement()
        model.pushEditOperations(
            beforeCursorState = null,
            editOperations = arrayOf(obj<IIdentifiedSingleEditOperation> {
                range = obj {
                    startLineNumber = edit.startLineNumber
                    startColumn = edit.startColumn
                    endLineNumber = edit.endLineNumber
                    endColumn = edit.endColumn
                }
                text = edit.text
            }),
            cursorStateComputer = js("function() { return null }").unsafeCast<ICursorStateComputer>(),
        )
        model.pushStackElement()

        val newVersionId = model.getAlternativeVersionId()
        assembledVersion = newVersionId
        assembledVersions[newVersionId] = candidateIr
        quest.setBytecodeIr(candidateIr)
        makeUndoCurrent()

        applyFloorMappingsUpdate(
            quest = quest,
            update = FloorMappingsUpdate(lastEditGeneration, candidateMappings, scriptEpisode),
            targetFloorId = targetFloorId,
        )
        return true
    }

    private fun applyFloorMappingsUpdate(
        quest: QuestModel,
        update: FloorMappingsUpdate,
        targetFloorId: Int? = null,
    ) {
        if (disposed || questEditorStore.currentQuest.value !== quest) return
        if (update.generation <= lastAppliedGeneration) return
        lastAppliedGeneration = update.generation
        val revision = questEditorStore.commitFloorMappings(
            quest = quest,
            floorMappings = update.floorMappings,
            targetFloorId = targetFloorId,
            effectiveEpisode = update.scriptEpisode,
        )
        if (revision != null) {
            scope.launch {
                if (!disposed) questEditorStore.reloadEntitySections(quest, revision)
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
            lossyIrVersions.clear()
            lossyNopVersions.clear()
            lastModelVersionId = null
            lastAppliedGeneration = 0
            lastEditGeneration = 0
            if (quest == null) {
                asmAnalyser.setAsm(emptyList())
                return@mutateDeferred
            }

            val intFmt = if (hexFormat.value) IntFormat.HEX else IntFormat.DECIMAL
            val asm = disassemble(quest.bytecodeIr, quest.version, intFmt, hideNops.value)
            val datContext = map(
                quest.objects.dependingOnElements { obj ->
                    obj.properties.value.map { it.value }.toTypedArray()
                },
                quest.npcs.dependingOnElements { npc ->
                    npc.properties.value.map { it.value }.toTypedArray()
                },
                quest.events,
            ) { objects, npcs, events ->
                val floors = buildSet {
                    objects.forEach { add(it.floorId) }
                    npcs.forEach { add(it.floorId) }
                    events.forEach { add(it.floorId) }
                }
                val entryFloors = getActiveDatScriptEntryFloorIds(
                    objects.map { it.entity },
                    npcs.map { it.entity },
                )
                Pair(floors, entryFloors)
            }
            val (initialFloors, initialEntryFloors) = datContext.value
            val setGen = asmAnalyser.setAsm(
                asm,
                usedFloorIds = initialFloors,
                version = quest.version,
                datEntryFloorIds = initialEntryFloors,
            )
            lastEditGeneration = setGen
            // Only suppress the initial empty-script analysis when a synthetic quest has no
            // bytecode segments (e.g. pre-populated mappings without script). Real quests with
            // bytecode still apply the initial worker analysis.
            lastAppliedGeneration = if (quest.bytecodeIr.segments.isEmpty()) setGen else 0
            modelDisposer.add(datContext.observe { (floorIds, entryFloorIds) ->
                if (!disposed && questEditorStore.currentQuest.value === quest) {
                    asmAnalyser.updateUsedFloorIds(floorIds, entryFloorIds)
                }
            })

            _textModel.value = createModel(asm.joinToString("\n"), ASM_LANG_ID).also { model ->
                val initialVersion = model.getAlternativeVersionId()
                assembledVersion = initialVersion
                assembledVersions[initialVersion] = quest.bytecodeIr
                lastModelVersionId = initialVersion
                if (hideNops.value) {
                    lossyNopVersions.add(initialVersion)
                }
                modelDisposer.add(disposable { model.dispose() })
                model.onDidChangeContent { e ->
                    val newVersion = model.getAlternativeVersionId()
                    if (!updatingPresentation) {
                        if (!e.isUndoing && !e.isRedoing && lastModelVersionId in lossyNopVersions) {
                            lossyNopVersions.add(newVersion)
                        }
                        lastModelVersionId = newVersion
                    }
                    lastEditGeneration = asmAnalyser.updateAsm(e.changes.map {
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
        val rawVersion = model.getAlternativeVersionId()
        val version = undo.resolveDocumentVersion(rawVersion)
        if (assembledVersion == version) return Success(Unit)

        val cached = assembledVersions[version]
        val result = cached?.let { Success(it) }
            ?: assemble(model.getLinesContent().toList(), quest.version)
        // The analyser deliberately returns partial IR with errors for editor assistance.
        // Persisting a document requires an error-free assembly.
        if (result is Failure || result.problems.any { it.severity == Severity.Error }) {
            return Failure(result.problems)
        }
        val ir = (result as Success).value
        if (cached == null) {
            assembledVersions[version] = ir
            if (rawVersion in lossyNopVersions) {
                lossyIrVersions.add(version)
            }
        }
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
            val currentDocVersion = undo.resolveDocumentVersion(model.getAlternativeVersionId())
            val irIsLossy = currentDocVersion in lossyIrVersions
            val text = disassemble(
                quest.bytecodeIr, quest.version,
                if (hex) IntFormat.HEX else IntFormat.DECIMAL, hideNops,
            ).joinToString("\n")
            if (text != model.getValue()) {
                assembledVersions[currentDocVersion] = quest.bytecodeIr
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
                val newVersion = model.getAlternativeVersionId()
                lastModelVersionId = newVersion
                if (hideNops || irIsLossy) {
                    lossyNopVersions.add(newVersion)
                } else {
                    lossyNopVersions.remove(newVersion)
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
