package world.phantasmal.web.questEditor.controllers

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.Event
import world.phantasmal.cell.cell
import world.phantasmal.psolib.fileFormats.quest.BoxItemField
import world.phantasmal.psolib.fileFormats.quest.ObjectType
import world.phantasmal.psolib.fileFormats.quest.QuestObject
import world.phantasmal.web.questEditor.models.QuestObjectModel
import world.phantasmal.web.questEditor.widgets.BoxItemEditorWidget
import world.phantasmal.web.questEditor.widgets.EntityInfoWidget
import world.phantasmal.web.test.WebTestSuite
import world.phantasmal.web.test.createQuestModel
import kotlin.test.*

class BoxItemEditorTests : WebTestSuite {
    @Test
    fun item_filter_and_long_menu_preserve_panel_position_until_an_explicit_selection() = testAsync {
        val box = QuestObjectModel(QuestObject(ObjectType.FixedTypeBox, 3).apply {
            data.setFloat(40, 0f)
            data.setFloat(48, 1f)
            data.setInt(52, 0x030E1300) // Kit of Genesis, in the middle of the long tool list.
        })
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(objects = listOf(box)))
        val model = BoxItemEditorModel(store, box)
        val widget = disposer.add(BoxItemEditorWidget(model, cell(true)))
        val panel = document.createElement("div") as HTMLElement
        panel.style.cssText = "position: fixed; top: 100px; left: 0; width: 220px; height: 140px; overflow: auto"
        panel.append(widget.element)
        document.body!!.append(panel)
        try {
            val before = model.definition.value
            val button = widget.element.querySelectorAll("button").item(1) as HTMLElement
            button.focus()
            val scrollTop = panel.scrollTop
            val scrollLeft = panel.scrollLeft
            button.dispatchEvent(MouseEvent("mousedown", MouseEventInit(bubbles = true)))
            button.dispatchEvent(MouseEvent("mouseup", MouseEventInit(bubbles = true)))
            val menu = button.parentElement!!.querySelector(".pw-menu") as HTMLElement
            assertFalse(menu.hidden)
            assertEquals(scrollTop, panel.scrollTop)
            assertEquals(scrollLeft, panel.scrollLeft)
            assertEquals(before, model.definition.value)
            menu.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true)))
            assertTrue(menu.hidden)
            assertEquals(scrollTop, panel.scrollTop)
            assertEquals(scrollLeft, panel.scrollLeft)
            val filter = widget.element.querySelector("input[type=text]") as HTMLInputElement
            filter.value = "030212"
            filter.dispatchEvent(Event("change"))
            assertEquals(before, model.definition.value)
            button.dispatchEvent(MouseEvent("mousedown", MouseEventInit(bubbles = true)))
            button.dispatchEvent(MouseEvent("mouseup", MouseEventInit(bubbles = true)))
            val choices = menu.querySelectorAll("[data-index]")
            assertEquals(1, choices.length)
            val choice = choices.item(0) as HTMLElement
            assertEquals("Megid disk [030212]", choice.textContent)
            choice.dispatchEvent(MouseEvent("mouseup", MouseEventInit(bubbles = true)))
            assertTrue(menu.hidden)
            assertEquals(0x03020012, box.entity.data.getInt(52))
            assertEquals("030212", filter.value)
            store.makeMainUndoCurrent()
            store.undo()
            assertEquals(before, model.definition.value)
            model.setCategory(BoxItemCategory.Weapon)
            assertEquals("", filter.value)
            store.undo()
            assertEquals(before, model.definition.value)
        } finally {
            panel.remove()
        }
    }

    @Test
    fun reselecting_current_category_or_item_preserves_every_word_and_adds_no_undo() = testAsync {
        for (type in listOf(ObjectType.FixedTypeBox, ObjectType.FixedBoxTypeRuins,
            ObjectType.SpecialBoxCca, ObjectType.DesertFixedTypeBoxBreakableCrystals)) {
            for (word in listOf(0x000504C0, 0x01000204, 0x01010200, 0x01020300,
                0x02014D00, 0x03021D12, 0x04000123)) {
                val box = QuestObjectModel(QuestObject(type, 3).apply {
                    data.setFloat(40, if (type == ObjectType.DesertFixedTypeBoxBreakableCrystals) 2f else 0f)
                    data.setFloat(48, 1f)
                    data.setInt(52, word)
                    data.setInt(56, 0x0A00141E)
                    data.setInt(60, 0x011E0228)
                })
                val store = components.questEditorStore
                store.setCurrentQuest(createQuestModel(objects = listOf(box)))
                val model = BoxItemEditorModel(store, box)
                val before = model.definition.value
                model.setCategory(assertNotNull(model.category.value))
                assertEquals(before, model.definition.value)
                model.setItem(assertNotNull(model.selectedItem.value))
                assertEquals(before, model.definition.value)
                store.makeMainUndoCurrent()
                store.undo()
                assertEquals(before, model.definition.value)
            }
        }
    }

    @Test
    fun desert_contents_mode_uses_the_client_float_range_and_keeps_event_fields_out_of_item_mode() = testAsync {
        for (mode in listOf(-1f, 0f, 1f, 1.5f, 2f, 2.5f, 3f, 4f)) {
            val box = QuestObjectModel(QuestObject(ObjectType.DesertFixedTypeBoxBreakableCrystals, 3).apply {
                data.setFloat(40, mode)
                data.setFloat(48, 1f)
            })
            val store = components.questEditorStore
            store.setCurrentQuest(createQuestModel(objects = listOf(box)))
            store.setSelectedEntity(box)
            val controller = disposer.add(EntityInfoController(components.areaStore, store,
                components.questEditorUiStore, components.asmStore))
            assertEquals(mode >= 2f && mode < 3f, assertNotNull(controller.boxItemEditor.value).active.value)
            assertEquals(mode < 2f || mode >= 3f, controller.props.value.any { it.label == "Event ID:" })
        }
    }

    @Test
    fun challenge_box_has_named_controls_without_raw_words_or_redundant_summary() = testAsync {
        val box = QuestObjectModel(QuestObject(ObjectType.FixedTypeBox, 3).apply {
            data.setFloat(40, 0f)
            data.setFloat(48, 1f)
            data.setInt(52, 0)
            data.setInt(56, 0x0A000000)
            data.setInt(60, 0)
        })
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(objects = listOf(box)))
        store.setSelectedEntity(box)
        val controller = disposer.add(EntityInfoController(components.areaStore, store,
            components.questEditorUiStore, components.asmStore))
        val widget = disposer.add(EntityInfoWidget(controller))
        val editor = assertNotNull(controller.boxItemEditor.value)

        assertEquals("Saber +10", editor.summary.value)
        assertFalse("Saber +10" in widget.element.textContent.orEmpty())
        assertFalse("Item data" in widget.element.textContent.orEmpty())
        assertTrue("World Position:" in widget.element.textContent.orEmpty())
        assertTrue("Rotation:" in widget.element.textContent.orEmpty())
        assertTrue("Drop category:" in widget.element.textContent.orEmpty())
        assertTrue("Find item:" in widget.element.textContent.orEmpty())
        assertTrue("Saber [000100]" in widget.element.textContent.orEmpty())
        assertFalse("167772160" in widget.element.outerHTML)
        val grind = assertNotNull(widget.element.querySelector(
            "[aria-label='Grind']") as? HTMLInputElement)
        assertEquals("10", grind.value)
        grind.value = "15"
        grind.dispatchEvent(Event("change"))
        assertEquals("Saber +15", editor.summary.value)
        assertEquals(0x0F000000, box.entity.data.getInt(56))

        store.makeMainUndoCurrent()
        store.undo()
        assertEquals("Saber +10", editor.summary.value)
        assertEquals("10", (widget.element.querySelector("[aria-label='Grind']") as HTMLInputElement).value)
        store.redo()
        assertEquals("Saber +15", editor.summary.value)
        assertEquals("15", (widget.element.querySelector("[aria-label='Grind']") as HTMLInputElement).value)
    }

    @Test
    fun named_item_changes_encode_all_words_as_one_undo_action() = testAsync {
        for (type in listOf(ObjectType.FixedTypeBox, ObjectType.FixedBoxTypeRuins,
            ObjectType.SpecialBoxCca, ObjectType.DesertFixedTypeBoxBreakableCrystals)) {
            val box = QuestObjectModel(QuestObject(type, 3).apply {
                data.setFloat(40, if (type == ObjectType.DesertFixedTypeBoxBreakableCrystals) 2f else 0f)
                data.setFloat(48, 1f)
                data.setInt(52, 0)
                data.setInt(56, 0x0A000000)
                data.setInt(60, 0x011E0228)
            })
            val store = components.questEditorStore
            store.setCurrentQuest(createQuestModel(objects = listOf(box)))
            store.setSelectedEntity(box)
            val model = BoxItemEditorModel(store, box)
            val widget = disposer.add(BoxItemEditorWidget(model, cell(true)))
            model.setCategory(BoxItemCategory.Shield)
            val coreShield = model.choices.value.single { it.code == 0x010202 }
            assertEquals("Core Shield", coreShield.name)
            model.setItem(coreShield)
            assertEquals(0x01010200, box.entity.data.getInt(52))
            assertEquals(0, box.entity.data.getInt(56))
            assertEquals(0, box.entity.data.getInt(60))
            assertEquals("Core Shield", model.summary.value)
            assertTrue("Drop category:" in widget.element.textContent.orEmpty())
            assertTrue("Core Shield [010202]" in widget.element.textContent.orEmpty())
            store.makeMainUndoCurrent()
            store.undo() // Core Shield -> Barrier
            store.undo() // category change -> Saber +10, with original attributes
            assertEquals(0, box.entity.data.getInt(52))
            assertEquals(0x0A000000, box.entity.data.getInt(56))
            assertEquals(0x011E0228, box.entity.data.getInt(60))
            assertTrue("Saber [000100]" in widget.element.textContent.orEmpty())
        }
    }

    @Test
    fun random_mode_explains_generated_category_without_claiming_a_specific_item() = testAsync {
        val box = QuestObjectModel(QuestObject(ObjectType.FixedTypeBox, 3).apply {
            data.setFloat(40, 0f)
            data.setFloat(48, 0f)
            data.setInt(52, 0)
        })
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(objects = listOf(box)))
        store.setSelectedEntity(box)
        val model = BoxItemEditorModel(store, box)
        assertTrue(model.summary.value.startsWith("Random Weapon"))
        assertTrue(model.fields.value.isEmpty())
        val widget = disposer.add(BoxItemEditorWidget(model, cell(true)))
        assertTrue("Random Weapon" in widget.element.textContent.orEmpty())
        assertFalse("Item data" in widget.element.textContent.orEmpty())
    }

    @Test
    fun technique_and_meseta_controls_use_display_units_and_mag_has_no_fake_stat_controls() = testAsync {
        val box = QuestObjectModel(QuestObject(ObjectType.FixedTypeBox, 3).apply {
            data.setFloat(40, 0f)
            data.setFloat(48, 1f)
        })
        val store = components.questEditorStore
        store.setCurrentQuest(createQuestModel(objects = listOf(box)))
        store.setSelectedEntity(box)
        val model = BoxItemEditorModel(store, box)
        model.setCategory(BoxItemCategory.Tool)
        model.setItem(model.choices.value.single { it.code == 0x030212 })
        model.setField(BoxItemField.TechniqueLevel, 30)
        assertEquals("Megid disk Lv.30", model.summary.value)
        assertEquals(0x03021D12, box.entity.data.getInt(52))
        model.setCategory(BoxItemCategory.Meseta)
        model.setField(BoxItemField.Meseta, 1230)
        assertEquals("1230 Meseta", model.summary.value)
        assertEquals(0x007B0000, box.entity.data.getInt(56))
        model.setCategory(BoxItemCategory.Mag)
        model.setItem(model.choices.value.single { it.code == 0x020100 })
        assertEquals("Varuna", model.summary.value)
        assertTrue(model.fields.value.isEmpty())
        val widget = disposer.add(BoxItemEditorWidget(model, cell(false)))
        val input = widget.element.querySelector("input") as HTMLInputElement
        assertTrue(input.disabled)
    }
}
