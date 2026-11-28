package world.phantasmal.web.questEditor.widgets

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.FocusEvent
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import world.phantasmal.cell.Cell
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.cell.nullCell
import world.phantasmal.cell.trueCell
import world.phantasmal.core.disposable.Disposable
import world.phantasmal.core.disposable.disposable
import world.phantasmal.web.questEditor.controllers.AreaAndLabel
import world.phantasmal.web.questEditor.controllers.FloorVariantBadge
import world.phantasmal.web.questEditor.controllers.FloorVariantOption
import world.phantasmal.webui.dom.Icon
import world.phantasmal.webui.dom.disposableListener
import world.phantasmal.webui.dom.div
import world.phantasmal.webui.dom.span
import world.phantasmal.webui.widgets.Button
import world.phantasmal.webui.widgets.Control
import kotlin.math.max
import kotlin.math.min

/**
 * Dedicated Floor dropdown for the Quest Editor toolbar.
 *
 * - Clicking a row's main area navigates to that floor without editing the script.
 * - Hovering or clicking/tapping a row's trailing `[ Map N ▸ ]` badge opens a cascading
 *   submenu of `Map 1..N` variants that directly edits `bb_map_designate` in the script.
 * - Read-only floors show a `[ 🔒 ↗ ]` button with tooltip and keyboard/click jump-to-script.
 */
class FloorSelectWidget(
    enabled: Cell<Boolean> = trueCell(),
    private val items: Cell<List<AreaAndLabel>>,
    private val selected: Cell<AreaAndLabel?> = nullCell(),
    private val getBadge: (AreaAndLabel) -> FloorVariantBadge?,
    private val onSelect: (AreaAndLabel) -> Unit,
    private val onSelectVariant: (AreaAndLabel, Int) -> Unit,
    private val onJumpToScript: (Int?) -> Unit,
) : Control(trueCell(), enabled, nullCell()) {
    private val menuVisible = mutableCell(false)
    private var justOpened = false

    private lateinit var buttonWidget: Button
    private lateinit var popupElement: HTMLElement
    private lateinit var innerElement: HTMLElement
    private lateinit var subMenuElement: HTMLElement

    private var onDocumentMouseDownListener: Disposable? = null
    private var closeSubMenuTimer: Int? = null

    private var highlightedIndex: Int? = null
    private var highlightedElement: HTMLElement? = null
    private var renderedBadges: List<FloorVariantBadge?> = emptyList()

    private var subMenuParentIndex: Int? = null
    private var subMenuOptions: List<FloorVariantOption> = emptyList()
    private var subMenuHighlightedIndex: Int? = null

    override fun Node.createElement() =
        div {
            className = "pw-select pw-floor-select"

            buttonWidget = addWidget(Button(
                enabled = enabled,
                textCell = selected.map { it?.label ?: " " },
                iconRight = Icon.TriangleDown,
                onMouseDown = ::onButtonMouseDown,
                onMouseUp = { onButtonMouseUp() },
                onKeyDown = ::onButtonKeyDown,
            ))

            popupElement = div {
                className = "pw-menu pw-floor-select-popup"
                tabIndex = -1
                hidden = true
                onkeydown = ::onPopupKeyDown
                addEventListener("focusout", { e ->
                    val fe = e as FocusEvent
                    val related = fe.relatedTarget as? Node
                    if (menuVisible.value && (related == null || !popupElement.contains(related))) {
                        closeMenu(restoreFocus = false)
                    }
                })

                innerElement = div {
                    className = "pw-menu-inner pw-floor-select-list"
                    addEventListener("scroll", {
                        closeSubMenuImmediately()
                    })
                }

                subMenuElement = div {
                    className = "pw-floor-select-submenu"
                    tabIndex = -1
                    hidden = true
                    onmouseenter = { cancelCloseSubMenuTimer() }
                    onmouseleave = { scheduleCloseSubMenu() }
                    onmousedown = { e -> e.preventDefault() }
                    onkeydown = ::onSubMenuKeyDown
                }
            }

            observeNow(menuVisible) { visible ->
                popupElement.hidden = !visible
                if (visible) {
                    renderRows()
                    positionPopup()
                    selected.value?.let { sel ->
                        val idx = items.value.indexOf(sel)
                        if (idx >= 0) highlightRowAt(idx)
                    }
                    onDocumentMouseDownListener =
                        document.disposableListener("mousedown", ::onDocumentMouseDown)
                } else {
                    onDocumentMouseDownListener?.dispose()
                    onDocumentMouseDownListener = null
                    closeSubMenuImmediately()
                    clearRowHighlight()
                }
            }

            observeNow(items) {
                if (menuVisible.value) {
                    closeSubMenuImmediately()
                    renderRows()
                    positionPopup()
                }
            }

            addDisposable(document.disposableListener<KeyboardEvent>("keydown", { e ->
                if (e.key == "Escape" && menuVisible.value) {
                    e.preventDefault()
                    if (!subMenuElement.hidden) {
                        closeSubMenuImmediately()
                        popupElement.asDynamic().focus(js("({preventScroll: true})"))
                    } else {
                        closeMenu(restoreFocus = true)
                    }
                }
            }))

            val dismissOnExternalScroll: (Event) -> Unit = { event ->
                val target = event.target
                if (menuVisible.value && (target !is Node || !popupElement.contains(target))) {
                    closeMenu(restoreFocus = false)
                }
            }
            document.addEventListener("scroll", dismissOnExternalScroll, true)
            addDisposable(disposable {
                document.removeEventListener("scroll", dismissOnExternalScroll, true)
            })
            addDisposable(window.disposableListener("resize", dismissOnExternalScroll))
        }

    override fun dispose() {
        cancelCloseSubMenuTimer()
        onDocumentMouseDownListener?.dispose()
        super.dispose()
    }

    private fun renderRows() {
        innerElement.innerHTML = ""
        clearRowHighlight()
        val currentItems = items.value
        val badges = ArrayList<FloorVariantBadge?>(currentItems.size)

        currentItems.forEachIndexed { index, item ->
            val badge = getBadge(item)
            badges.add(badge)

            val rowEl = document.createElement("div") as HTMLElement
            rowEl.className = "pw-floor-select-row"
            rowEl.dataset.asDynamic().index = index.toString()

            val mainSpan = document.createElement("span") as HTMLElement
            mainSpan.className = "pw-floor-select-row-label"
            mainSpan.textContent = if (badge is FloorVariantBadge.Editable) item.rowLabel else item.label
            rowEl.appendChild(mainSpan)

            rowEl.addEventListener("mouseenter", {
                highlightRowAt(index)
                if (subMenuParentIndex != null && subMenuParentIndex != index) {
                    scheduleCloseSubMenu()
                }
            })

            rowEl.addEventListener("mouseup", { e ->
                val target = e.target as? Node
                if (target != null && isInsideTrailingControl(target, rowEl)) {
                    return@addEventListener
                }
                e.stopPropagation()
                selectFloor(item)
            })

            when (badge) {
                is FloorVariantBadge.Editable -> {
                    val badgeEl = document.createElement("span") as HTMLElement
                    badgeEl.className = "pw-floor-select-badge"
                    badgeEl.textContent = "${badge.badgeText} ▸"
                    badgeEl.title = "Change map variation (updates script)"
                    badgeEl.addEventListener("mousedown", { e ->
                        e.preventDefault()
                        e.stopPropagation()
                    })
                    badgeEl.addEventListener("mouseenter", {
                        cancelCloseSubMenuTimer()
                        highlightRowAt(index)
                        openSubMenuForRow(index, rowEl, badge.options, focusSubMenu = false)
                    })
                    badgeEl.addEventListener("mouseleave", {
                        scheduleCloseSubMenu()
                    })
                    badgeEl.addEventListener("click", { e ->
                        e.stopPropagation()
                        if (!subMenuElement.hidden && subMenuParentIndex == index) {
                            closeSubMenuImmediately()
                        } else {
                            openSubMenuForRow(index, rowEl, badge.options, focusSubMenu = false)
                        }
                    })
                    rowEl.appendChild(badgeEl)
                }

                is FloorVariantBadge.ReadOnly -> {
                    val lockBtn = document.createElement("button") as HTMLElement
                    lockBtn.className = "pw-floor-select-lock"
                    lockBtn.setAttribute("type", "button")
                    lockBtn.tabIndex = 0
                    lockBtn.textContent = "🔒 ↗"
                    lockBtn.title = badge.reason
                    lockBtn.addEventListener("mousedown", { e ->
                        e.stopPropagation()
                    })
                    lockBtn.addEventListener("click", { e ->
                        e.stopPropagation()
                        closeMenu(restoreFocus = false)
                        onJumpToScript(badge.jumpLineNo)
                    })
                    lockBtn.addEventListener("keydown", { e ->
                        val ke = e as KeyboardEvent
                        when (ke.key) {
                            "Enter", " " -> {
                                ke.preventDefault()
                                ke.stopPropagation()
                                closeMenu(restoreFocus = false)
                                onJumpToScript(badge.jumpLineNo)
                            }
                            "ArrowLeft" -> {
                                ke.preventDefault()
                                ke.stopPropagation()
                                popupElement.asDynamic().focus(js("({preventScroll: true})"))
                            }
                            "Tab" -> {
                                if (ke.shiftKey) {
                                    ke.preventDefault()
                                    ke.stopPropagation()
                                    popupElement.asDynamic().focus(js("({preventScroll: true})"))
                                }
                            }
                            "Escape" -> {
                                ke.preventDefault()
                                ke.stopPropagation()
                                closeMenu(restoreFocus = true)
                            }
                        }
                    })
                    rowEl.appendChild(lockBtn)
                }

                null -> {}
            }

            innerElement.appendChild(rowEl)
        }

        renderedBadges = badges
    }

    private fun isInsideTrailingControl(target: Node, rowEl: HTMLElement): Boolean {
        var cur: Node? = target
        while (cur != null && cur != rowEl) {
            if (cur is HTMLElement &&
                (cur.classList.contains("pw-floor-select-badge") ||
                    cur.classList.contains("pw-floor-select-lock"))
            ) {
                return true
            }
            cur = cur.parentNode
        }
        return false
    }

    private fun openSubMenuForRow(
        rowIndex: Int,
        rowEl: HTMLElement,
        options: List<FloorVariantOption>,
        focusSubMenu: Boolean,
    ) {
        cancelCloseSubMenuTimer()
        subMenuParentIndex = rowIndex
        subMenuOptions = options
        subMenuHighlightedIndex = options.indexOfFirst { it.active }.takeIf { it >= 0 } ?: 0
        subMenuElement.innerHTML = ""

        val parentItem = items.value.getOrNull(rowIndex) ?: return

        options.forEachIndexed { optIdx, opt ->
            val optEl = document.createElement("div") as HTMLElement
            optEl.className = "pw-floor-select-submenu-item"
            if (optIdx == subMenuHighlightedIndex) {
                optEl.classList.add("pw-menu-highlighted")
            }
            optEl.dataset.asDynamic().optIndex = optIdx.toString()

            val checkSpan = document.createElement("span") as HTMLElement
            checkSpan.className = "pw-floor-select-submenu-check"
            checkSpan.textContent = if (opt.active) "✓" else ""
            optEl.appendChild(checkSpan)

            val textSpan = document.createElement("span") as HTMLElement
            textSpan.textContent = opt.label
            optEl.appendChild(textSpan)

            optEl.addEventListener("mouseenter", {
                highlightSubMenuOptionAt(optIdx)
            })
            optEl.addEventListener("mousedown", { e ->
                e.preventDefault()
                e.stopPropagation()
            })
            optEl.addEventListener("mouseup", { e ->
                e.stopPropagation()
                applySubMenuVariant(parentItem, opt.variantId)
            })

            subMenuElement.appendChild(optEl)
        }

        subMenuElement.hidden = false
        positionSubMenu(rowEl)
        if (focusSubMenu) {
            subMenuElement.asDynamic().focus(js("({preventScroll: true})"))
        }
    }

    private fun positionPopup() {
        val bounds = element.getBoundingClientRect()
        val margin = 4.0
        val availableWidth = max(0.0, window.innerWidth - 2 * margin)
        popupElement.style.apply {
            position = "fixed"
            left = "${margin}px"
            top = "${margin}px"
            minWidth = "${min(bounds.width, availableWidth)}px"
            maxWidth = "${availableWidth}px"
            width = "max-content"
        }
        val below = max(0.0, window.innerHeight - bounds.bottom - margin)
        val above = max(0.0, bounds.top - margin)
        val openAbove = below < min(500.0, innerElement.scrollHeight.toDouble()) && above > below
        innerElement.style.maxHeight =
            "${min(500.0, if (openAbove) above - 4 else below - 4).coerceAtLeast(0.0)}px"
        val popup = popupElement.getBoundingClientRect()
        popupElement.style.left =
            "${bounds.left.coerceIn(margin, max(margin, window.innerWidth - popup.width - margin))}px"
        popupElement.style.top =
            "${if (openAbove) max(margin, bounds.top - popup.height) else bounds.bottom}px"
    }

    private fun positionSubMenu(rowEl: HTMLElement) {
        val margin = 4.0
        val rowRect = rowEl.getBoundingClientRect()
        val popupRect = popupElement.getBoundingClientRect()
        subMenuElement.style.apply {
            position = "fixed"
            left = "${popupRect.right}px"
            top = "${rowRect.top}px"
        }
        val subRect = subMenuElement.getBoundingClientRect()
        val leftPos = if (popupRect.right + subRect.width + margin <= window.innerWidth) {
            popupRect.right
        } else {
            max(margin, popupRect.left - subRect.width)
        }
        val topPos = rowRect.top.coerceIn(
            margin,
            max(margin, window.innerHeight - subRect.height - margin),
        )
        subMenuElement.style.left = "${leftPos}px"
        subMenuElement.style.top = "${topPos}px"
    }

    private fun scheduleCloseSubMenu() {
        cancelCloseSubMenuTimer()
        closeSubMenuTimer = window.setTimeout({
            closeSubMenuTimer = null
            closeSubMenuImmediately()
        }, 120)
    }

    private fun cancelCloseSubMenuTimer() {
        closeSubMenuTimer?.let(window::clearTimeout)
        closeSubMenuTimer = null
    }

    private fun closeSubMenuImmediately() {
        cancelCloseSubMenuTimer()
        subMenuElement.hidden = true
        subMenuElement.innerHTML = ""
        subMenuParentIndex = null
        subMenuOptions = emptyList()
        subMenuHighlightedIndex = null
    }

    private fun closeMenu(restoreFocus: Boolean) {
        menuVisible.value = false
        if (restoreFocus) {
            buttonWidget.element.asDynamic().focus(js("({preventScroll: true})"))
        }
    }

    private fun selectFloor(item: AreaAndLabel) {
        closeMenu(restoreFocus = true)
        onSelect(item)
    }

    private fun applySubMenuVariant(parentItem: AreaAndLabel, variantId: Int) {
        closeMenu(restoreFocus = true)
        onSelectVariant(parentItem, variantId)
    }

    private fun onButtonMouseDown(e: MouseEvent) {
        e.stopPropagation()
        justOpened = !menuVisible.value
        menuVisible.value = true
    }

    private fun onButtonMouseUp() {
        if (justOpened) {
            popupElement.asDynamic().focus(js("({preventScroll: true})"))
        } else {
            menuVisible.value = false
        }
        justOpened = false
    }

    private fun onButtonKeyDown(e: KeyboardEvent) {
        when (e.key) {
            "ArrowDown" -> {
                val list = items.value
                if (list.isNotEmpty()) {
                    val cur = selected.value
                    val next = if (cur == null) list.first() else {
                        val idx = list.indexOf(cur) + 1
                        if (idx >= list.size) list.first() else list[idx]
                    }
                    onSelect(next)
                }
            }
            "ArrowUp" -> {
                val list = items.value
                if (list.isNotEmpty()) {
                    val cur = selected.value
                    val prev = if (cur == null) list.last() else {
                        val idx = list.indexOf(cur) - 1
                        if (idx < 0) list.last() else list[idx]
                    }
                    onSelect(prev)
                }
            }
            "Enter", " " -> {
                e.preventDefault()
                e.stopPropagation()
                justOpened = !menuVisible.value
                menuVisible.value = true
                popupElement.asDynamic().focus(js("({preventScroll: true})"))
            }
        }
    }

    private fun onPopupKeyDown(e: KeyboardEvent) {
        if (!subMenuElement.hidden && subMenuElement.contains(document.activeElement)) return
        when (e.key) {
            "ArrowDown" -> {
                e.preventDefault()
                val size = items.value.size
                if (size > 0) {
                    val next = when (val idx = highlightedIndex) {
                        null, size - 1 -> 0
                        else -> idx + 1
                    }
                    highlightRowAt(next)
                }
            }
            "ArrowUp" -> {
                e.preventDefault()
                val size = items.value.size
                if (size > 0) {
                    val prev = when (val idx = highlightedIndex) {
                        null, 0 -> size - 1
                        else -> idx - 1
                    }
                    highlightRowAt(prev)
                }
            }
            "Enter", " " -> {
                e.preventDefault()
                e.stopPropagation()
                highlightedIndex?.let { idx -> items.value.getOrNull(idx)?.let(::selectFloor) }
            }
            "ArrowRight" -> {
                val idx = highlightedIndex ?: return
                val rowEl = highlightedElement ?: return
                when (val badge = renderedBadges.getOrNull(idx)) {
                    is FloorVariantBadge.Editable -> {
                        e.preventDefault()
                        e.stopPropagation()
                        openSubMenuForRow(idx, rowEl, badge.options, focusSubMenu = true)
                    }
                    is FloorVariantBadge.ReadOnly -> {
                        e.preventDefault()
                        e.stopPropagation()
                        (rowEl.querySelector(".pw-floor-select-lock") as? HTMLElement)?.focus()
                    }
                    null -> {}
                }
            }
            "Tab" -> {
                val idx = highlightedIndex ?: return
                val rowEl = highlightedElement ?: return
                if (!e.shiftKey && renderedBadges.getOrNull(idx) is FloorVariantBadge.ReadOnly) {
                    e.preventDefault()
                    e.stopPropagation()
                    (rowEl.querySelector(".pw-floor-select-lock") as? HTMLElement)?.focus()
                }
            }
        }
    }

    private fun onSubMenuKeyDown(e: KeyboardEvent) {
        if (subMenuElement.hidden) return
        val parentIdx = subMenuParentIndex ?: return
        val parentItem = items.value.getOrNull(parentIdx) ?: return
        when (e.key) {
            "ArrowDown" -> {
                e.preventDefault()
                e.stopPropagation()
                val size = subMenuOptions.size
                if (size > 0) {
                    val next = when (val idx = subMenuHighlightedIndex) {
                        null, size - 1 -> 0
                        else -> idx + 1
                    }
                    highlightSubMenuOptionAt(next)
                }
            }
            "ArrowUp" -> {
                e.preventDefault()
                e.stopPropagation()
                val size = subMenuOptions.size
                if (size > 0) {
                    val prev = when (val idx = subMenuHighlightedIndex) {
                        null, 0 -> size - 1
                        else -> idx - 1
                    }
                    highlightSubMenuOptionAt(prev)
                }
            }
            "Enter", " " -> {
                e.preventDefault()
                e.stopPropagation()
                val opt = subMenuHighlightedIndex?.let { subMenuOptions.getOrNull(it) } ?: return
                applySubMenuVariant(parentItem, opt.variantId)
            }
            "ArrowLeft", "Escape" -> {
                e.preventDefault()
                e.stopPropagation()
                closeSubMenuImmediately()
                popupElement.asDynamic().focus(js("({preventScroll: true})"))
            }
        }
    }

    private fun onDocumentMouseDown(e: Event) {
        val target = e.target
        if (target !is Node || (!element.contains(target) && !popupElement.contains(target))) {
            closeMenu(restoreFocus = false)
        }
    }

    private fun clearRowHighlight() {
        highlightedElement?.classList?.remove("pw-menu-highlighted")
        highlightedIndex = null
        highlightedElement = null
    }

    private fun highlightRowAt(index: Int) {
        highlightedElement?.classList?.remove("pw-menu-highlighted")
        val row = innerElement.children.item(index) as? HTMLElement ?: return
        highlightedIndex = index
        highlightedElement = row
        row.classList.add("pw-menu-highlighted")
        scrollIntoInnerView(row)
    }

    private fun highlightSubMenuOptionAt(index: Int) {
        val prev = subMenuHighlightedIndex?.let { subMenuElement.children.item(it) as? HTMLElement }
        prev?.classList?.remove("pw-menu-highlighted")
        val next = subMenuElement.children.item(index) as? HTMLElement ?: return
        subMenuHighlightedIndex = index
        next.classList.add("pw-menu-highlighted")
    }

    private fun scrollIntoInnerView(item: Element) {
        val list = innerElement.getBoundingClientRect()
        val rect = item.getBoundingClientRect()
        if (rect.top < list.top) {
            innerElement.scrollTop -= list.top - rect.top
        } else if (rect.bottom > list.bottom) {
            innerElement.scrollTop += rect.bottom - list.bottom
        }
    }

    companion object {
        init {
            @Suppress("CssUnusedSymbol", "CssUnresolvedCustomProperty")
            // language=css
            style("""
                .pw-floor-select-row {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 10px;
                    cursor: pointer;
                    white-space: nowrap;
                }

                .pw-floor-select-row-label {
                    flex: 1;
                    overflow: hidden;
                    text-overflow: ellipsis;
                }

                .pw-floor-select-badge {
                    flex-shrink: 0;
                    padding: 1px 6px;
                    border-radius: 3px;
                    font-size: 0.9em;
                    background-color: hsla(0, 0%, 100%, 0.08);
                    border: 1px solid hsla(0, 0%, 100%, 0.14);
                    cursor: pointer;
                }

                .pw-floor-select-badge:hover {
                    background-color: hsla(0, 0%, 100%, 0.18);
                }

                .pw-floor-select-lock {
                    flex-shrink: 0;
                    padding: 1px 5px;
                    border-radius: 3px;
                    font-size: 0.85em;
                    color: inherit;
                    background-color: hsla(0, 0%, 100%, 0.06);
                    border: 1px solid hsla(0, 0%, 100%, 0.14);
                    cursor: pointer;
                }

                .pw-floor-select-lock:hover,
                .pw-floor-select-lock:focus {
                    background-color: hsla(0, 0%, 100%, 0.18);
                    outline: 1px solid var(--pw-control-text-color-hover);
                }

                .pw-floor-select-submenu {
                    z-index: 1002;
                    position: fixed;
                    min-width: 110px;
                    background-color: var(--pw-control-bg-color);
                    border: var(--pw-control-border);
                    outline: none;
                    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35);
                }

                .pw-floor-select-submenu-item {
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    padding: 3px 10px 3px 6px;
                    cursor: pointer;
                    white-space: nowrap;
                }

                .pw-floor-select-submenu-item.pw-menu-highlighted {
                    background-color: var(--pw-control-bg-color-hover);
                    color: var(--pw-control-text-color-hover);
                }

                .pw-floor-select-submenu-check {
                    display: inline-block;
                    width: 12px;
                    text-align: center;
                }
            """.trimIndent())
        }
    }
}
