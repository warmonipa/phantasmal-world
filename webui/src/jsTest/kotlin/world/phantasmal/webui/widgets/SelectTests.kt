package world.phantasmal.webui.widgets

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import world.phantasmal.cell.cell
import world.phantasmal.webui.test.WebuiTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SelectTests : WebuiTestSuite {
    @Test
    fun opening_a_select_at_the_bottom_of_a_scroll_container_keeps_the_container_still() = test {
        val selected = mutableListOf<String>()
        val select = disposer.add(
            Select(
                items = cell(listOf("Red", "Cyan", "Green", "Magenta")),
                selected = cell("Cyan"),
                onSelect = { selected.add(it) },
            )
        )
        // The select sits at the bottom edge of a scrollable panel, so its open menu overflows.
        val panel = document.createElement("div") as HTMLElement
        panel.style.cssText = "position: fixed; top: 0; left: 0; width: 200px; height: 100px; overflow: auto"
        val spacer = document.createElement("div") as HTMLElement
        spacer.style.height = "76px"
        panel.append(spacer, select.element)
        document.body!!.append(panel)

        try {
            val button = select.element.querySelector("button") as HTMLElement
            button.dispatchEvent(MouseEvent("mousedown", MouseEventInit(bubbles = true)))

            assertEquals(0.0, panel.scrollTop)

            // Releasing the button where it was pressed must leave the menu open.
            button.dispatchEvent(MouseEvent("mouseup", MouseEventInit(bubbles = true)))
            val menu = select.element.querySelector(".pw-menu") as HTMLElement
            assertFalse(menu.hidden)
            assertEquals(emptyList(), selected)
        } finally {
            panel.remove()
        }
    }
}
