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
import kotlin.test.assertTrue
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

class SelectTests : WebuiTestSuite {
    @Test
    fun opening_a_select_at_the_bottom_of_a_scroll_container_keeps_the_container_still() = test {
        val selected = mutableListOf<String>()
        val select = disposer.add(
            Select(
                items = cell((0..192).map { "Long item name $it with a code [031212]" }),
                selected = cell("Long item name 100 with a code [031212]"),
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
            button.focus()
            button.dispatchEvent(MouseEvent("mousedown", MouseEventInit(bubbles = true)))

            assertEquals(0.0, panel.scrollTop)

            // Releasing the button where it was pressed must leave the menu open.
            button.dispatchEvent(MouseEvent("mouseup", MouseEventInit(bubbles = true)))
            val menu = select.element.querySelector(".pw-menu") as HTMLElement
            assertFalse(menu.hidden)
            assertEquals(emptyList(), selected)
            assertEquals(0.0, panel.scrollTop, "Focusing the opened menu must not scroll its panel")
            assertEquals(0.0, panel.scrollLeft)
            val rect = menu.getBoundingClientRect()
            assertTrue(rect.top >= 0 && rect.bottom <= kotlinx.browser.window.innerHeight)
            assertTrue(rect.left >= 0 && rect.right <= kotlinx.browser.window.innerWidth)
            menu.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true)))
            assertTrue(menu.hidden)
            assertEquals(button, document.activeElement)
            assertEquals(0.0, panel.scrollTop, "Returning focus must not scroll the panel")
            assertEquals(0.0, panel.scrollLeft)

            // At the bottom-right edge, open upward and keep keyboard navigation in the list.
            panel.style.top = "${kotlinx.browser.window.innerHeight - 110}px"
            panel.style.left = "${kotlinx.browser.window.innerWidth - 210}px"
            button.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", bubbles = true)))
            val edge = menu.getBoundingClientRect()
            assertTrue(edge.top >= 0 && edge.bottom <= kotlinx.browser.window.innerHeight)
            assertTrue(edge.left >= 0 && edge.right <= kotlinx.browser.window.innerWidth)
            assertTrue(edge.bottom <= button.getBoundingClientRect().top)
            menu.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "ArrowDown", bubbles = true)))
            menu.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", bubbles = true)))
            assertEquals(listOf("Long item name 101 with a code [031212]"), selected)
            assertTrue(menu.hidden)
            assertEquals(0.0, panel.scrollTop)
            assertEquals(0.0, panel.scrollLeft)
        } finally {
            panel.remove()
        }
    }
}
