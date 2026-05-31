package world.phantasmal.web.viewer.widgets

import kotlinx.coroutines.launch
import kotlinx.browser.document
import org.w3c.dom.DragEvent
import org.w3c.dom.Node
import org.w3c.dom.asList
import world.phantasmal.cell.cell
import world.phantasmal.web.viewer.controllers.ViewerController
import world.phantasmal.web.viewer.controllers.ViewerTab
import world.phantasmal.webui.dom.div
import world.phantasmal.webui.dom.disposableListener
import world.phantasmal.webui.files.FileHandle
import world.phantasmal.webui.dom.Icon
import world.phantasmal.webui.widgets.TabContainer
import world.phantasmal.webui.widgets.TextInput
import world.phantasmal.webui.widgets.Button
import world.phantasmal.webui.widgets.Widget

class ViewerWidget(
    private val ctrl: ViewerController,
    private val openFiles: suspend (List<FileHandle>?) -> Unit,
    private val createToolbar: () -> Widget,
    private val createCharacterClassOptionsWidget: () -> CharacterClassOptionsWidget,
    private val createMeshWidget: () -> Widget,
    private val createTextureWidget: () -> Widget,
) : Widget() {
    private var assetLibraryWidth = ASSET_LIBRARY_DEFAULT_WIDTH

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

                val assetLibrary = div {
                    className = "pw-viewer-asset-library"
                    style.width = "${assetLibraryWidth}px"

                    div {
                        className = "pw-viewer-asset-toolbar"

                        addChild(TextInput(
                            value = ctrl.assetSearch,
                            onChange = ctrl::setAssetSearch,
                            placeholder = "Search assets",
                            extraClassName = "pw-viewer-asset-search",
                        ))

                        addChild(Button(
                            tooltip = cell("Expand all asset groups"),
                            iconLeft = Icon.TriangleDown,
                            className = "pw-viewer-asset-tool-button",
                            onClick = { ctrl.expandAllAssetGroups() },
                        ))

                        addChild(Button(
                            tooltip = cell("Collapse all asset groups"),
                            iconLeft = Icon.ArrowRight,
                            className = "pw-viewer-asset-tool-button",
                            onClick = { ctrl.collapseAllAssetGroups() },
                        ))
                    }

                    addChild(GroupedSelectionWidget(
                        groups = ctrl.modelGroups,
                        expandedGroups = ctrl.expandedAssetGroups,
                        selected = ctrl.currentModel,
                        onToggleGroup = ctrl::toggleAssetGroup,
                        onSelect = { model ->
                            scope.launch { ctrl.setCurrentModel(model) }
                        },
                    ))
                }

                div {
                    className = "pw-viewer-asset-resizer"

                    onDrag(
                        onPointerDown = {
                            document.body?.classList?.add("pw-viewer-resizing")
                            classList.add("pw-active")
                            true
                        },
                        onPointerMove = { movedX, _, e ->
                            e.preventDefault()
                            assetLibraryWidth =
                                (assetLibraryWidth + movedX).coerceIn(
                                    ASSET_LIBRARY_MIN_WIDTH,
                                    ASSET_LIBRARY_MAX_WIDTH,
                                )
                            assetLibrary.style.width = "${assetLibraryWidth}px"
                            true
                        },
                        onPointerUp = {
                            document.body?.classList?.remove("pw-viewer-resizing")
                            classList.remove("pw-active")
                        },
                    )
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
        private const val ASSET_LIBRARY_DEFAULT_WIDTH = 220
        private const val ASSET_LIBRARY_MIN_WIDTH = 160
        private const val ASSET_LIBRARY_MAX_WIDTH = 420

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
                    flex: 0 0 auto;
                    overflow: hidden;
                }

                .pw-viewer-asset-resizer {
                    flex: 0 0 7px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    border-left: var(--pw-border);
                    cursor: col-resize;
                    background-color: hsl(0, 0%, 12%);
                }

                .pw-viewer-asset-resizer::after {
                    content: "";
                    width: 1px;
                    height: 32px;
                    background-color: hsl(0, 0%, 35%);
                }

                .pw-viewer-asset-resizer:hover,
                .pw-viewer-asset-resizer.pw-active {
                    background-color: hsl(195, 55%, 35%);
                }

                .pw-viewer-asset-resizer:hover::after,
                .pw-viewer-asset-resizer.pw-active::after {
                    background-color: hsl(195, 70%, 65%);
                }

                body.pw-viewer-resizing {
                    cursor: col-resize;
                    user-select: none;
                }

                .pw-viewer-asset-toolbar {
                    display: grid;
                    grid-template-columns: minmax(0, 1fr) 24px 24px;
                    gap: 4px;
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

                .pw-viewer-asset-tool-button {
                    width: 24px;
                }

                .pw-viewer-asset-tool-button .pw-button-inner {
                    justify-content: center;
                    padding-left: 0;
                    padding-right: 0;
                }
            """.trimIndent())
        }
    }
}
