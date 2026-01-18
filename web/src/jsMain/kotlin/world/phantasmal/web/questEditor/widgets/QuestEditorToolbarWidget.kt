package world.phantasmal.web.questEditor.widgets

import kotlinx.coroutines.launch
import org.w3c.dom.Node
import org.w3c.dom.events.KeyboardEvent
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.cell.cell
import world.phantasmal.cell.list.listCell
import world.phantasmal.cell.mutableCell
import world.phantasmal.web.questEditor.controllers.CompatibilityController
import world.phantasmal.web.questEditor.controllers.QuestEditorToolbarController
import world.phantasmal.webui.dom.Icon
import world.phantasmal.webui.dom.div
import world.phantasmal.webui.widgets.*

/**
 * Tool menu items.
 */
private enum class ToolMenuItem(val label: String) {
    COMPATIBILITY_CHECK("Compatibility Check"),
}

class QuestEditorToolbarWidget(
    private val ctrl: QuestEditorToolbarController,
    private val compatibilityCtrl: CompatibilityController,
) : Widget() {
    private val compatibilityDialogVisible = mutableCell(false)
    override fun Node.createElement() =
        div {
            className = "pw-quest-editor-toolbar"

            addChild(Toolbar(
                children = listOf(
                    Dropdown(
                        text = "New quest",
                        iconLeft = Icon.NewFile,
                        items = listCell(Episode.I),
                        itemToString = { "Episode $it" },
                        onSelect = { scope.launch { ctrl.createNewQuest(it) } },
                    ),
                    FileButton(
                        text = "Open file...",
                        tooltip = cell("Open a quest file (Ctrl-O)"),
                        iconLeft = Icon.File,
                        types = ctrl.supportedFileTypes,
                        multiple = true,
                        filesSelected = { files -> scope.launch { ctrl.openFiles(files) } },
                    ),
                    Button(
                        text = "Save",
                        iconLeft = Icon.Save,
                        enabled = ctrl.saveEnabled,
                        tooltip = ctrl.saveTooltip,
                        onClick = { scope.launch { ctrl.save() } },
                    ),
                    Button(
                        text = "Save as...",
                        iconLeft = Icon.Save,
                        enabled = ctrl.saveAsEnabled,
                        tooltip = cell("Save this quest to a new file (Ctrl-Shift-S)"),
                        onClick = { ctrl.saveAs() },
                    ),
                    Button(
                        text = "Undo",
                        iconLeft = Icon.Undo,
                        enabled = ctrl.undoEnabled,
                        tooltip = ctrl.undoTooltip,
                        onClick = { ctrl.undo() },
                    ),
                    Button(
                        text = "Redo",
                        iconLeft = Icon.Redo,
                        enabled = ctrl.redoEnabled,
                        tooltip = ctrl.redoTooltip,
                        onClick = { ctrl.redo() },
                    ),
                    Select(
                        enabled = ctrl.areaSelectEnabled,
                        items = ctrl.areas,
                        itemToString = { it.label },
                        selected = ctrl.currentArea,
                        onSelect = ctrl::setCurrentArea,
                    ),
                    Checkbox(
                        label = "Simple view",
                        tooltip = cell(
                            "Whether the collision or the render geometry should be shown",
                        ),
                        checked = ctrl.showCollisionGeometry,
                        onChange = ctrl::setShowCollisionGeometry,
                    ),
                    Dropdown(
                        text = "Tools",
                        items = listCell(*ToolMenuItem.entries.toTypedArray()),
                        itemToString = { it.label },
                        onSelect = { item ->
                            when (item) {
                                ToolMenuItem.COMPATIBILITY_CHECK -> {
                                    compatibilityDialogVisible.value = true
                                }
                            }
                        },
                    ),
                )
            ))

            val saveAsDialog = addDisposable(Dialog(
                visible = ctrl.saveAsDialogVisible,
                title = cell("Save As"),
                content = {
                    div {
                        className = "pw-quest-editor-toolbar-save-as"

                        if (ctrl.showSaveAsDialogNameField) {
                            val filenameInput = TextInput(
                                label = "File name:",
                                value = ctrl.filename,
                                onChange = ctrl::setFilename,
                            )
                            addWidget(filenameInput.label!!)
                            addWidget(filenameInput)
                        }

                        val versionSelect = Select(
                            label = "Version:",
                            items = listCell(Version.GC, Version.BB),
                            selected = ctrl.version,
                            itemToString = {
                                when (it) {
                                    Version.DC -> "Dreamcast"
                                    Version.GC -> "GameCube"
                                    Version.PC -> "PC"
                                    Version.BB -> "BlueBurst"
                                }
                            },
                            onSelect = ctrl::setVersion,
                        )
                        addWidget(versionSelect.label!!)
                        addWidget(versionSelect)
                    }
                },
                footer = {
                    addWidget(Button(
                        text = "Save",
                        onClick = { scope.launch { ctrl.saveAsDialogSave() } },
                    ))
                    addWidget(Button(
                        text = "Cancel",
                        onClick = { ctrl.dismissSaveAsDialog() },
                    ))
                },
                onDismiss = ctrl::dismissSaveAsDialog,
            ))

            saveAsDialog.dialogElement.addEventListener("keydown", { e ->
                if ((e as KeyboardEvent).key == "Enter") {
                    scope.launch { ctrl.saveAsDialogSave() }
                }
            })

            addDisposable(ResultDialog(
                visible = ctrl.resultDialogVisible,
                result = ctrl.result,
                onDismiss = ctrl::dismissResultDialog,
            ))

            addDisposable(CompatibilityDialog(
                visible = compatibilityDialogVisible,
                ctrl = compatibilityCtrl,
                onDismiss = { compatibilityDialogVisible.value = false },
            ))
        }

    companion object {
        init {
            @Suppress("CssUnusedSymbol")
            // language=css
            style("""
                .pw-quest-editor-toolbar-save-as {
                    display: grid;
                    grid-template-columns: 100px max-content;
                    grid-column-gap: 4px;
                    grid-row-gap: 4px;
                    align-items: center;
                }

                .pw-quest-editor-toolbar-save-as .pw-input {
                    margin: 1px;
                }
            """.trimIndent())
        }
    }
}
