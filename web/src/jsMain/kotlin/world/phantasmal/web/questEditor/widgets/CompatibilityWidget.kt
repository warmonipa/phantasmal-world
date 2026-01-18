package world.phantasmal.web.questEditor.widgets

import org.w3c.dom.Node
import world.phantasmal.cell.map
import world.phantasmal.psolib.compatibility.ProblemType
import world.phantasmal.web.core.widgets.UnavailableWidget
import world.phantasmal.web.questEditor.controllers.CompatibilityController
import world.phantasmal.web.questEditor.controllers.CompatibilityStatus
import world.phantasmal.webui.dom.*
import world.phantasmal.webui.widgets.Button
import world.phantasmal.webui.widgets.Widget

class CompatibilityWidget(private val ctrl: CompatibilityController) : Widget() {
    override fun Node.createElement() =
        div {
            className = "pw-quest-editor-compatibility"

            div {
                className = "pw-quest-editor-compatibility-header"

                addChild(Button(
                    text = "Check All Versions",
                    enabled = ctrl.unavailable.map { !it },
                    onClick = { ctrl.checkAllVersions() }
                ))

                addChild(Button(
                    text = "Clear",
                    enabled = ctrl.results.map { it.isNotEmpty() },
                    onClick = { ctrl.clearResults() }
                ))
            }

            div {
                className = "pw-quest-editor-compatibility-versions"
                hidden(ctrl.unavailable)

                observe(ctrl.versionSummaries) { summaries ->
                    innerHTML = ""
                    summaries.forEach { summary ->
                        div {
                            className = "pw-quest-editor-compatibility-version"
                            classList.toggle("compatible", summary.status == CompatibilityStatus.COMPATIBLE)
                            classList.toggle("warning", summary.status == CompatibilityStatus.WARNING)
                            classList.toggle("incompatible", summary.status == CompatibilityStatus.INCOMPATIBLE)
                            classList.toggle("not-checked", summary.status == CompatibilityStatus.NOT_CHECKED)

                            onclick = { ctrl.selectVersion(summary.version) }

                            span {
                                className = "pw-quest-editor-compatibility-version-name"
                                textContent = summary.version.displayName
                            }

                            span {
                                className = "pw-quest-editor-compatibility-version-status"
                                textContent = when (summary.status) {
                                    CompatibilityStatus.NOT_CHECKED -> "Not checked"
                                    CompatibilityStatus.COMPATIBLE -> "Compatible"
                                    CompatibilityStatus.WARNING -> "${summary.warningCount} warning(s)"
                                    CompatibilityStatus.INCOMPATIBLE -> "${summary.errorCount} error(s), ${summary.warningCount} warning(s)"
                                }
                            }
                        }
                    }
                }
            }

            div {
                className = "pw-quest-editor-compatibility-details"

                observe(ctrl.selectedResult) { result ->
                    innerHTML = ""

                    if (result == null) {
                        span {
                            className = "pw-quest-editor-compatibility-no-selection"
                            textContent = "Select a version to view details"
                        }
                    } else {
                        h4 { textContent = result.version.displayName }

                        if (result.errors.isEmpty() && result.warnings.isEmpty()) {
                            p {
                                className = "pw-quest-editor-compatibility-success"
                                textContent = "Fully compatible!"
                            }
                        } else {
                            if (result.errors.isNotEmpty()) {
                                h5 { textContent = "Errors (${result.errors.size})" }
                                ul {
                                    className = "pw-quest-editor-compatibility-errors"
                                    result.errors.forEach { error ->
                                        li {
                                            span {
                                                className = "pw-problem-type"
                                                textContent = formatProblemType(error.type)
                                            }
                                            span { textContent = error.message }
                                        }
                                    }
                                }
                            }

                            if (result.warnings.isNotEmpty()) {
                                h5 { textContent = "Warnings (${result.warnings.size})" }
                                ul {
                                    className = "pw-quest-editor-compatibility-warnings"
                                    result.warnings.forEach { warning ->
                                        li {
                                            span {
                                                className = "pw-problem-type"
                                                textContent = formatProblemType(warning.type)
                                            }
                                            span { textContent = warning.message }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            addChild(UnavailableWidget(
                visible = ctrl.unavailable,
                message = "No quest loaded."
            ))
        }

    private fun formatProblemType(type: ProblemType): String = when (type) {
        ProblemType.MISSING_LABEL_0 -> "[Script]"
        ProblemType.UNKNOWN_OPCODE -> "[Script]"
        ProblemType.OPCODE_VERSION_MISMATCH -> "[Script]"
        ProblemType.EPISODE_NOT_SUPPORTED -> "[Episode]"
        ProblemType.INVALID_ARGUMENT -> "[Script]"
        ProblemType.LABEL_NOT_FOUND -> "[Script]"
        ProblemType.SWITCH_ARRAY_MISMATCH -> "[Script]"
        ProblemType.CONVERSION_WARNING -> "[Script]"
        ProblemType.SPECIAL_OPCODE_WARNING -> "[Script]"
        ProblemType.NPC_ACTION_LABEL_NOT_FOUND -> "[NPC]"
        ProblemType.NPC_SCRIPT_LABEL_NOT_SUPPORTED -> "[NPC]"
        ProblemType.SKIN_NOT_SUPPORTED -> "[NPC]"
        ProblemType.SKIN_51_INVALID_SUBTYPE -> "[NPC]"
        ProblemType.MONSTER_FLOOR_MISMATCH -> "[Monster]"
        ProblemType.TOO_MANY_MONSTERS -> "[Monster]"
        ProblemType.OBJECT_FLOOR_MISMATCH -> "[Object]"
        ProblemType.TOO_MANY_OBJECTS -> "[Object]"
        ProblemType.UNUSED_DATA_LABEL -> "[Data]"
        ProblemType.MISSING_BIN_FILE -> "[File]"
        ProblemType.MISSING_DAT_FILE -> "[File]"
        ProblemType.PVR_NOT_SUPPORTED -> "[File]"
    }

    companion object {
        init {
            @Suppress("CssUnusedSymbol", "CssUnresolvedCustomProperty")
            // language=css
            style("""
                .pw-quest-editor-compatibility {
                    box-sizing: border-box;
                    padding: 8px;
                    overflow: auto;
                    display: flex;
                    flex-direction: column;
                    gap: 8px;
                }

                .pw-quest-editor-compatibility-header {
                    display: flex;
                    gap: 8px;
                }

                .pw-quest-editor-compatibility-versions {
                    display: flex;
                    flex-direction: column;
                    gap: 4px;
                }

                .pw-quest-editor-compatibility-version {
                    display: flex;
                    justify-content: space-between;
                    padding: 6px 8px;
                    border-radius: 4px;
                    cursor: pointer;
                    background-color: var(--pw-control-bg-color);
                    border: 1px solid var(--pw-control-border-color);
                }

                .pw-quest-editor-compatibility-version:hover {
                    background-color: var(--pw-control-bg-color-hover);
                }

                .pw-quest-editor-compatibility-version.compatible {
                    border-left: 3px solid hsl(120, 60%, 40%);
                }

                .pw-quest-editor-compatibility-version.warning {
                    border-left: 3px solid hsl(45, 80%, 50%);
                }

                .pw-quest-editor-compatibility-version.incompatible {
                    border-left: 3px solid hsl(0, 70%, 50%);
                }

                .pw-quest-editor-compatibility-version.not-checked {
                    border-left: 3px solid hsl(0, 0%, 50%);
                }

                .pw-quest-editor-compatibility-version-name {
                    font-weight: bold;
                }

                .pw-quest-editor-compatibility-version-status {
                    color: var(--pw-text-color-secondary);
                    font-size: 12px;
                }

                .pw-quest-editor-compatibility-details {
                    flex: 1;
                    overflow: auto;
                    background-color: var(--pw-bg-color);
                    border: 1px solid var(--pw-control-border-color);
                    border-radius: 4px;
                    padding: 8px;
                }

                .pw-quest-editor-compatibility-details h4 {
                    margin: 0 0 8px 0;
                }

                .pw-quest-editor-compatibility-details h5 {
                    margin: 12px 0 4px 0;
                }

                .pw-quest-editor-compatibility-no-selection {
                    color: var(--pw-text-color-secondary);
                    font-style: italic;
                }

                .pw-quest-editor-compatibility-success {
                    color: hsl(120, 60%, 50%);
                    font-weight: bold;
                }

                .pw-quest-editor-compatibility-errors li,
                .pw-quest-editor-compatibility-warnings li {
                    margin: 4px 0;
                    font-size: 12px;
                }

                .pw-quest-editor-compatibility-errors {
                    color: hsl(0, 70%, 60%);
                }

                .pw-quest-editor-compatibility-warnings {
                    color: hsl(45, 80%, 60%);
                }

                .pw-problem-type {
                    font-weight: bold;
                    margin-right: 4px;
                    color: var(--pw-text-color-secondary);
                }
            """.trimIndent())
        }
    }
}