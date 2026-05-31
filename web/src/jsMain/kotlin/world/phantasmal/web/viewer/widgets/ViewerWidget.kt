package world.phantasmal.web.viewer.widgets

import kotlinx.coroutines.launch
import org.w3c.dom.DragEvent
import org.w3c.dom.Node
import org.w3c.dom.asList
import world.phantasmal.web.viewer.controllers.ViewerController
import world.phantasmal.web.viewer.controllers.ViewerTab
import world.phantasmal.webui.dom.div
import world.phantasmal.webui.dom.disposableListener
import world.phantasmal.webui.files.FileHandle
import world.phantasmal.webui.widgets.TabContainer
import world.phantasmal.webui.widgets.TextInput
import world.phantasmal.webui.widgets.Widget

class ViewerWidget(
    private val ctrl: ViewerController,
    private val openFiles: suspend (List<FileHandle>?) -> Unit,
    private val createToolbar: () -> Widget,
    private val createCharacterClassOptionsWidget: () -> CharacterClassOptionsWidget,
    private val createMeshWidget: () -> Widget,
    private val createTextureWidget: () -> Widget,
) : Widget() {
    override fun Node.createElement() =
        div {
            className = "pw-viewer-viewer"

            addDisposables(
                disposableListener("dragenter", ::onDragEnter),
                disposableListener("dragover", ::onDragOver),
                disposableListener("dragleave", ::onDragLeave),
                disposableListener("drop", ::onDrop),
            )

            addChild(createToolbar())

            div {
                className = "pw-viewer-viewer-content"

                div {
                    className = "pw-viewer-asset-library"

                    addChild(TextInput(
                        value = ctrl.assetSearch,
                        onChange = ctrl::setAssetSearch,
                        placeholder = "Search assets",
                        extraClassName = "pw-viewer-asset-search",
                    ))
                    addChild(GroupedSelectionWidget(
                        groups = ctrl.modelGroups,
                        selected = ctrl.currentModel,
                        onSelect = { model ->
                            scope.launch { ctrl.setCurrentModel(model) }
                        },
                    ))
                }
                addChild(createCharacterClassOptionsWidget())
                addChild(TabContainer(ctrl = ctrl, createWidget = { tab ->
                    when (tab) {
                        ViewerTab.Mesh -> createMeshWidget()
                        ViewerTab.Texture -> createTextureWidget()
                    }
                }))
                addChild(SelectionWidget(
                    items = ctrl.animations,
                    selected = ctrl.currentAnimation,
                    onSelect = { animation ->
                        scope.launch { ctrl.setCurrentAnimation(animation) }
                    },
                    itemToString = { it.name },
                    borderLeft = true,
                ))
            }
        }

    private fun onDragEnter(e: DragEvent) {
        acceptDrop(e)
    }

    private fun onDragOver(e: DragEvent) {
        acceptDrop(e)
    }

    private fun onDragLeave(e: DragEvent) {
        val currentTarget = e.currentTarget.asDynamic()
        val relatedTarget = e.relatedTarget.asDynamic()

        if (relatedTarget == null || currentTarget.contains(relatedTarget) != true) {
            currentTarget.classList.remove("pw-drag-over")
        }
    }

    private fun onDrop(e: DragEvent) {
        acceptDrop(e)
        e.currentTarget.asDynamic().classList.remove("pw-drag-over")

        val files = e.dataTransfer?.files
            ?.asList()
            ?.map(FileHandle::Simple)
            ?.takeIf { it.isNotEmpty() }

        if (files != null) {
            scope.launch {
                openFiles(files)
            }
        }
    }

    private fun acceptDrop(e: DragEvent) {
        e.preventDefault()
        e.stopPropagation()
        e.currentTarget.asDynamic().classList.add("pw-drag-over")

        e.dataTransfer?.let {
            it.dropEffect = "copy"
        }
    }

    companion object {
        init {
            @Suppress("CssUnusedSymbol")
            // language=css
            style("""
                .pw-viewer-viewer {
                    display: flex;
                    flex-direction: column;
                    outline: 0 solid transparent;
                    outline-offset: -2px;
                    transition: outline-color 80ms ease-out;
                }

                .pw-viewer-viewer.pw-drag-over {
                    outline: 2px solid hsl(195, 70%, 55%);
                }

                .pw-viewer-viewer-content {
                    flex-grow: 1;
                    display: flex;
                    flex-direction: row;
                    overflow: hidden;
                }

                .pw-viewer-viewer-content > .pw-tab-container {
                    flex-grow: 1;
                }

                .pw-viewer-asset-library {
                    display: flex;
                    flex-direction: column;
                    min-height: 0;
                    width: 220px;
                    overflow: hidden;
                }

                .pw-viewer-asset-library > .pw-text-input {
                    padding: 4px;
                }

                .pw-viewer-asset-library > .pw-viewer-selection {
                    flex-grow: 1;
                    min-height: 0;
                }

                .pw-viewer-asset-search {
                    box-sizing: border-box;
                    width: 100%;
                }
            """.trimIndent())
        }
    }
}
