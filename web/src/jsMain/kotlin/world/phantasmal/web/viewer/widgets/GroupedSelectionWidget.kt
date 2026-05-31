package world.phantasmal.web.viewer.widgets

import org.w3c.dom.Node
import world.phantasmal.core.disposable.Disposer
import world.phantasmal.cell.Cell
import world.phantasmal.cell.eq
import world.phantasmal.web.viewer.models.ViewerModel
import world.phantasmal.webui.dom.bindDisposableChildrenTo
import world.phantasmal.webui.dom.div
import world.phantasmal.webui.dom.Icon
import world.phantasmal.webui.dom.icon
import world.phantasmal.webui.dom.li
import world.phantasmal.webui.dom.span
import world.phantasmal.webui.dom.ul
import world.phantasmal.webui.widgets.Widget

class GroupedSelectionWidget(
    private val groups: Cell<List<ViewerModel.Group>>,
    private val selected: Cell<ViewerModel?>,
    private val onSelect: (ViewerModel) -> Unit,
) : Widget() {
    private val collapsedGroups = mutableSetOf<String>()

    override fun Node.createElement() =
        ul {
            className = "pw-viewer-selection"

            bindDisposableChildrenTo(groups) { group, _ ->
                val disposable = Disposer()
                val initiallyCollapsed = group.label in collapsedGroups

                val node = li {
                    className = "pw-viewer-selection-group"

                    val itemList = ul {
                        className = "pw-viewer-selection-group-items"
                        hidden = initiallyCollapsed
                    }

                    val header = div {
                        className = "pw-viewer-selection-group-header"
                        if (initiallyCollapsed) classList.add("pw-collapsed")

                        span {
                            className = "pw-viewer-selection-group-caret"
                            icon(Icon.TriangleDown)
                        }

                        span {
                            className = "pw-viewer-selection-group-label"
                            textContent = group.label
                        }

                        span {
                            className = "pw-viewer-selection-group-count"
                            textContent = group.items.size.toString()
                        }
                    }

                    header.onclick = {
                        val collapsed = !itemList.hidden
                        itemList.hidden = collapsed
                        header.classList.toggle("pw-collapsed", collapsed)

                        if (collapsed) {
                            collapsedGroups.add(group.label)
                        } else {
                            collapsedGroups.remove(group.label)
                        }
                    }

                    insertBefore(header, itemList)

                    for (item in group.items) {
                        itemList.li {
                            className = "pw-viewer-selection-item"
                            textContent = item.uiName

                            val activeCell = selected eq item
                            if (activeCell.value) classList.add("pw-active")
                            onclick = { onSelect(item) }

                            disposable.add(
                                activeCell.observeChange {
                                    if (it.value) classList.add("pw-active")
                                    else classList.remove("pw-active")
                                }
                            )
                        }
                    }
                }

                Pair(node, disposable)
            }
        }

    companion object {
        init {
            @Suppress("CssUnusedSymbol")
            // language=css
            style("""
                .pw-viewer-selection-group {
                    list-style: none;
                }

                .pw-viewer-selection-group-header {
                    display: grid;
                    grid-template-columns: 12px minmax(0, 1fr) auto;
                    align-items: center;
                    gap: 4px;
                    padding: 6px 8px 3px;
                    white-space: nowrap;
                    font-size: 11px;
                    font-weight: bold;
                    text-transform: uppercase;
                    color: hsl(0, 0%, 55%);
                    user-select: none;
                    cursor: pointer;
                }

                .pw-viewer-selection-group-header:hover {
                    color: hsl(0, 0%, 75%);
                    background-color: hsl(0, 0%, 14%);
                }

                .pw-viewer-selection-group-header.pw-collapsed > .pw-viewer-selection-group-caret {
                    transform: rotate(-90deg);
                }

                .pw-viewer-selection-group-label {
                    overflow: hidden;
                    text-overflow: ellipsis;
                }

                .pw-viewer-selection-group-count {
                    color: hsl(0, 0%, 42%);
                    font-weight: normal;
                    text-transform: none;
                }

                .pw-viewer-selection-group-items {
                    list-style: none;
                    padding: 0;
                    margin: 0;
                }
            """.trimIndent())
        }
    }
}
