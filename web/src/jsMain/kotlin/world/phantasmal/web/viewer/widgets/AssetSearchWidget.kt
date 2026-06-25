package world.phantasmal.web.viewer.widgets

import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Node
import world.phantasmal.core.disposable.Disposer
import world.phantasmal.cell.Cell
import world.phantasmal.cell.map
import world.phantasmal.web.viewer.models.ViewerModel
import world.phantasmal.webui.dom.bindDisposableChildrenTo
import world.phantasmal.webui.dom.div
import world.phantasmal.webui.dom.input
import world.phantasmal.webui.dom.li
import world.phantasmal.webui.dom.ul
import world.phantasmal.webui.widgets.Widget

class AssetSearchWidget(
    private val query: Cell<String>,
    private val suggestions: Cell<List<ViewerModel>>,
    private val ultimate: Cell<Boolean>,
    private val onChange: (String) -> Unit,
    private val onSelect: (ViewerModel) -> Unit,
) : Widget() {
    override fun Node.createElement() =
        div {
            className = "pw-viewer-asset-search"
            val root = this

            val searchInput = input {
                className = "pw-viewer-asset-search-input"
                type = "text"
                placeholder = "Search assets"
                value = query.value
                oninput = { onChange(value) }
                onfocus = { root.classList.add("pw-focused") }
                onblur = { root.classList.remove("pw-focused") }
            }

            val suggestionList = ul {
                className = "pw-viewer-asset-search-suggestions"
                hidden = suggestions.value.isEmpty()

                bindDisposableChildrenTo(suggestions) { suggestion, _ ->
                    val disposer = Disposer()
                    val nameCell = ultimate.map { suggestion.displayName(it) }

                    val node = li {
                        className = "pw-viewer-asset-search-suggestion"
                        textContent = nameCell.value
                        disposer.add(nameCell.observeChange { textContent = it.value })
                        onmousedown = { event ->
                            event.preventDefault()
                            onSelect(suggestion)
                            searchInput.blur()
                            root.classList.remove("pw-focused")
                        }
                    }

                    Pair(node, disposer)
                }
            }

            addDisposable(query.observeChange {
                if (searchInput.value != it.value) {
                    searchInput.value = it.value
                }
            })

            addDisposable(suggestions.observeChange {
                suggestionList.hidden = it.value.isEmpty()
            })
        }

    companion object {
        init {
            @Suppress("CssUnusedSymbol", "CssUnresolvedCustomProperty")
            // language=css
            style("""
                .pw-viewer-asset-search {
                    position: relative;
                    width: 100%;
                }

                .pw-viewer-asset-search-input {
                    box-sizing: border-box;
                    width: 100%;
                    height: 22px;
                    padding: 0 4px;
                    border: var(--pw-input-border);
                    background-color: var(--pw-input-bg-color);
                    color: var(--pw-input-text-color);
                    outline: none;
                    font-size: 12px;
                }

                .pw-viewer-asset-search-input:hover {
                    border: var(--pw-input-border-hover);
                }

                .pw-viewer-asset-search-input:focus {
                    border: var(--pw-input-border-focus);
                }

                .pw-viewer-asset-search-suggestions {
                    position: absolute;
                    z-index: 1;
                    top: 24px;
                    left: 0;
                    right: 0;
                    box-sizing: border-box;
                    max-height: 176px;
                    overflow: auto;
                    margin: 0;
                    padding: 2px 0;
                    list-style: none;
                    border: var(--pw-border);
                    background-color: hsl(0, 0%, 12%);
                    box-shadow: 0 4px 8px hsla(0, 0%, 0%, 0.35);
                }

                .pw-viewer-asset-search:not(.pw-focused) > .pw-viewer-asset-search-suggestions {
                    display: none;
                }

                .pw-viewer-asset-search-suggestion {
                    overflow: hidden;
                    padding: 4px 8px;
                    text-overflow: ellipsis;
                    white-space: nowrap;
                    cursor: pointer;
                }

                .pw-viewer-asset-search-suggestion:hover {
                    color: hsl(0, 0%, 90%);
                    background-color: hsl(0, 0%, 20%);
                }
            """.trimIndent())
        }
    }
}
