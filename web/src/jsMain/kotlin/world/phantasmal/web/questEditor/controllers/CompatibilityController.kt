package world.phantasmal.web.questEditor.controllers

import kotlinx.browser.window
import world.phantasmal.cell.Cell
import world.phantasmal.cell.isNull
import world.phantasmal.cell.list.ListCell
import world.phantasmal.cell.list.mutableListCell
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.psolib.asm.assemble
import world.phantasmal.psolib.compatibility.CompatibilityChecker
import world.phantasmal.psolib.compatibility.CompatibilityResult
import world.phantasmal.psolib.compatibility.PSOVersion
import world.phantasmal.web.questEditor.stores.AsmStore
import world.phantasmal.web.questEditor.stores.QuestEditorStore
import world.phantasmal.web.questEditor.stores.convertQuestFromModel
import world.phantasmal.webui.controllers.Controller

class CompatibilityController(
    private val store: QuestEditorStore,
    private val asmStore: AsmStore,
) : Controller() {
    private val checker = CompatibilityChecker()
    private val _results = mutableListCell<CompatibilityResult>()
    private val _isChecking = mutableCell(false)
    private val _selectedVersion = mutableCell<PSOVersion?>(null)

    val unavailable: Cell<Boolean> = store.currentQuest.isNull()

    init {
        // Clear results when quest changes (use setTimeout to avoid nested cell changes)
        observe(store.currentQuest) {
            window.setTimeout({ clearResults() }, 0)
        }
    }

    /**
     * All PSO versions available for compatibility checking.
     */
    val versions: List<PSOVersion> = PSOVersion.entries

    /**
     * Results for all versions.
     */
    val results: ListCell<CompatibilityResult> = _results

    /**
     * Whether a check is currently running.
     */
    val isChecking: Cell<Boolean> = _isChecking

    /**
     * Currently selected version for detailed view.
     */
    val selectedVersion: Cell<PSOVersion?> = _selectedVersion

    /**
     * Result for the selected version.
     */
    val selectedResult: Cell<CompatibilityResult?> = map(selectedVersion, results) { version, results ->
        version?.let { v -> results.find { it.version == v } }
    }

    /**
     * Summary of compatibility status for each version.
     */
    val versionSummaries: Cell<List<VersionSummary>> = results.map { resultList ->
        PSOVersion.entries.map { version ->
            val result = resultList.find { it.version == version }
            VersionSummary(
                version = version,
                status = when {
                    result == null -> CompatibilityStatus.NOT_CHECKED
                    result.hasErrors -> CompatibilityStatus.INCOMPATIBLE
                    result.hasWarnings -> CompatibilityStatus.WARNING
                    else -> CompatibilityStatus.COMPATIBLE
                },
                errorCount = result?.errors?.size ?: 0,
                warningCount = result?.warnings?.size ?: 0,
            )
        }
    }

    /**
     * Run compatibility check for all versions.
     */
    fun checkAllVersions() {
        val questModel = store.currentQuest.value ?: return

        _isChecking.value = true
        _results.clear()

        try {
            // Re-assemble from current text to get bytecodeIr with source locations
            val textModel = asmStore.textModel.value
            val bytecodeIr = if (textModel != null) {
                val lines = textModel.getLinesContent().toList()
                assemble(lines, asmStore.inlineStackArgs.value).getOrNull()
            } else {
                null
            }

            val quest = convertQuestFromModel(questModel, bytecodeIr)
            val checkResults = checker.checkAllVersions(quest)

            checkResults.values.forEach { result ->
                _results.add(result)
            }
        } finally {
            _isChecking.value = false
        }
    }

    /**
     * Run compatibility check for a specific version.
     */
    fun checkVersion(version: PSOVersion) {
        val questModel = store.currentQuest.value ?: return

        _isChecking.value = true

        try {
            // Re-assemble from current text to get bytecodeIr with source locations
            val textModel = asmStore.textModel.value
            val bytecodeIr = if (textModel != null) {
                val lines = textModel.getLinesContent().toList()
                assemble(lines, asmStore.inlineStackArgs.value).getOrNull()
            } else {
                null
            }

            val quest = convertQuestFromModel(questModel, bytecodeIr)
            val result = checker.checkCompatibility(version, quest)

            // Remove old result for this version if exists
            _results.value.find { it.version == version }?.let { _results.remove(it) }
            _results.add(result)
        } finally {
            _isChecking.value = false
        }
    }

    /**
     * Select a version to view detailed results.
     */
    fun selectVersion(version: PSOVersion?) {
        _selectedVersion.value = version
    }

    /**
     * Clear all results.
     */
    fun clearResults() {
        _results.clear()
        _selectedVersion.value = null
    }
}

/**
 * Summary of compatibility status for a single version.
 */
data class VersionSummary(
    val version: PSOVersion,
    val status: CompatibilityStatus,
    val errorCount: Int,
    val warningCount: Int,
)

/**
 * Overall compatibility status.
 */
enum class CompatibilityStatus {
    NOT_CHECKED,
    COMPATIBLE,
    WARNING,
    INCOMPATIBLE,
}