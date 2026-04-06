package world.phantasmal.web.questEditor.widgets

import mu.KotlinLogging
import org.w3c.dom.Node
import world.phantasmal.core.disposable.disposable
import world.phantasmal.cell.mutateDeferred
import world.phantasmal.web.externals.monacoEditor.*
import world.phantasmal.web.questEditor.asm.DataLabelType
import world.phantasmal.web.questEditor.asm.monaco.EditorHistory
import world.phantasmal.web.questEditor.controllers.AsmEditorController
import world.phantasmal.web.questEditor.controllers.DataEditorController
import world.phantasmal.web.questEditor.controllers.DataLabelEntry
import world.phantasmal.webui.dom.div
import world.phantasmal.webui.obj
import world.phantasmal.webui.widgets.Widget

private val logger = KotlinLogging.logger {}

class AsmEditorWidget(
    private val ctrl: AsmEditorController,
    private val dataEditorCtrl: DataEditorController,
    private val asmWidget: AsmWidget,
) : Widget() {
    private lateinit var editor: IStandaloneCodeEditor

    override fun Node.createElement() =
        div {
            className = "pw-quest-editor-asm-editor"

            editor = create(this, obj {
                theme = "phantasmal-world"
                scrollBeyondLastLine = false
                autoIndent = "full"
                fontSize = 13
                wordWrap = "on"
                wrappingIndent = "indent"
                renderIndentGuides = false
                folding = false
                wordBasedSuggestions = false
                occurrencesHighlight = true
                fixedOverflowWidgets = true
            })

            addDisposable(disposable { editor.dispose() })

            observeNow(ctrl.textModel) { editor.setModel(it) }

            observeNow(ctrl.readOnly) { editor.updateOptions(obj { readOnly = it }) }

            addDisposable(size.observeChange { (size) ->
                if (size.width > .0 && size.height > .0) {
                    editor.layout(obj {
                        width = size.width
                        height = size.height
                    })
                }
            })

            // Add VSCode keybinding for command palette.
            val quickCommand = editor.getAction("editor.action.quickCommand")

            editor.addAction(object : IActionDescriptor {
                override var id = "editor.action.quickCommand"
                override var label = "Command Palette"
                override var keybindings =
                    arrayOf(KeyMod.CtrlCmd or KeyMod.Shift or KeyCode.KEY_P)

                override fun run(editor: ICodeEditor, vararg args: dynamic) {
                    quickCommand.run()
                }
            })

            // "Edit Data..." context menu action — auto-detects data type at cursor.
            val editDataDescriptor = object : IActionDescriptor {
                override var id = "pw.editData"
                override var label = "Edit Data..."
                override var keybindings = emptyArray<Int>()

                override fun run(editor: ICodeEditor, vararg args: dynamic) {
                    val lineNo = editor.getPosition()?.lineNumber?.toInt() ?: return
                    val entry = dataEditorCtrl.dataLabelAtLine(lineNo) ?: return
                    openDataDialog(entry)
                }
            }
            editDataDescriptor.asDynamic().contextMenuGroupId = "pw-data"
            editDataDescriptor.asDynamic().contextMenuOrder = 1.0
            val editDataAction = editor.addAction(editDataDescriptor)
            addDisposable(disposable { editDataAction.dispose() })

            // Undo/redo.
            addDisposable(ctrl.didUndo.observe {
                editor.focus()

                mutateDeferred {
                    if (!disposed) {
                        editor.trigger(
                            source = AsmEditorWidget::class.simpleName,
                            handlerId = "undo",
                            payload = undefined,
                        )
                    }
                }
            })

            addDisposable(ctrl.didRedo.observe {
                editor.focus()

                mutateDeferred {
                    if (!disposed) {
                        editor.trigger(
                            source = AsmEditorWidget::class.simpleName,
                            handlerId = "redo",
                            payload = undefined,
                        )
                    }
                }
            })

            editor.onDidFocusEditorWidget(ctrl::makeUndoCurrent)

            // Navigate to a label position when triggered from entity info panel.
            addDisposable(ctrl.goToLabel.observe { range ->
                logger.info { "goToLabel observer fired: line=${range.startLineNo}, col=${range.startCol}" }
                val pos: IPosition = obj { lineNumber = range.startLineNo; column = range.startCol }
                editor.setPosition(pos)
                editor.revealPositionInCenter(pos)
                editor.focus()
            })

            addDisposable(EditorHistory(editor))
        }

    private fun openDataDialog(entry: DataLabelEntry) {
        asmWidget.initialLabelId.value = entry.labelId
        when (entry.type) {
            DataLabelType.NpcData -> asmWidget.npcDataDialogVisible.value = true
            DataLabelType.PhysicalData -> asmWidget.physicalDataDialogVisible.value = true
            DataLabelType.AttackData -> asmWidget.attackDataDialogVisible.value = true
            DataLabelType.ResistData -> asmWidget.resistDataDialogVisible.value = true
            DataLabelType.MovementData -> asmWidget.movementDataDialogVisible.value = true
        }
    }

    override fun focus() {
        editor.focus()
    }

    companion object {
        init {
            defineTheme("phantasmal-world", obj {
                base = "vs-dark"
                inherit = true
                rules = arrayOf(
                    obj { token = ""; foreground = "E0E0E0"; background = "#181818" },
                    obj { token = "tag"; foreground = "99BBFF" },
                    obj { token = "keyword"; foreground = "D0A0FF"; fontStyle = "bold" },
                    obj { token = "predefined"; foreground = "BBFFBB" },
                    obj { token = "number"; foreground = "FFFFAA" },
                    obj { token = "number.hex"; foreground = "FFFFAA" },
                    obj { token = "string"; foreground = "88FFFF" },
                    obj { token = "string.escape"; foreground = "8888FF" },
                )
                colors = obj {
                    this["editor.background"] = "#181818"
                    this["editor.lineHighlightBackground"] = "#202020"
                }
            })

            @Suppress("CssUnusedSymbol")
            // language=css
            style(
                """
                .pw-quest-editor-asm-editor {
                    flex-grow: 1;
                }
                .pw-quest-editor-asm-editor .editor-widget {
                    z-index: 30;
                }
            """.trimIndent()
            )
        }
    }
}
