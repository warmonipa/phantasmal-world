package world.phantasmal.web.viewer.widgets

import org.w3c.dom.Node
import world.phantasmal.cell.Cell
import world.phantasmal.cell.eq
import world.phantasmal.web.viewer.models.ViewerModel
import world.phantasmal.webui.dom.bindDisposableChildrenTo
import world.phantasmal.webui.dom.li
import world.phantasmal.webui.dom.span
import world.phantasmal.webui.dom.ul
import world.phantasmal.webui.widgets.Widget

class AssetCategorySelectionWidget(
    private val categories: Cell<List<ViewerModel.Category>>,
    private val selected: Cell<ViewerModel.Category>,
    private val onSelect: (ViewerModel.Category) -> Unit,
) : Widget() {
    override fun Node.createElement() =
        ul {
            className = "pw-viewer-asset-categories"

            bindDisposableChildrenTo(categories) { category, _ ->
                val activeCell = selected eq category

                val node = li {
                    className = "pw-viewer-asset-category"
                    if (activeCell.value) classList.add("pw-active")

                    span {
                        className = "pw-viewer-asset-category-label"
                        textContent = category.label
                    }

                    span {
                        className = "pw-viewer-asset-category-count"
                        textContent = category.count.toString()
                    }

                    onclick = { onSelect(category) }
                }

                val disposable = activeCell.observeChange {
                    if (it.value) node.classList.add("pw-active")
                    else node.classList.remove("pw-active")
                }

                Pair(node, disposable)
            }
        }

    companion object {
        init {
            @Suppress("CssUnusedSymbol")
            // language=css
            style("""
                .pw-viewer-asset-categories {
                    display: grid;
                    grid-template-columns: minmax(0, 1fr);
                    gap: 1px;
                    margin: 0;
                    padding: 4px;
                    list-style: none;
                    border-bottom: var(--pw-border);
                }

                .pw-viewer-asset-category {
                    display: grid;
                    grid-template-columns: minmax(0, 1fr) auto;
                    align-items: center;
                    gap: 8px;
                    min-width: 0;
                    padding: 5px 8px 5px 10px;
                    border-left: 2px solid transparent;
                    color: hsl(0, 0%, 64%);
                    cursor: pointer;
                    user-select: none;
                }

                .pw-viewer-asset-category:hover {
                    color: hsl(0, 0%, 86%);
                    background-color: hsl(0, 0%, 15%);
                }

                .pw-viewer-asset-category.pw-active {
                    border-left-color: hsl(195, 65%, 55%);
                    background-color: hsl(195, 38%, 18%);
                    color: hsl(0, 0%, 90%);
                }

                .pw-viewer-asset-category-label {
                    min-width: 0;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                    font-weight: bold;
                }

                .pw-viewer-asset-category-count {
                    color: hsl(0, 0%, 46%);
                    font-size: 11px;
                }
            """.trimIndent())
        }
    }
}
