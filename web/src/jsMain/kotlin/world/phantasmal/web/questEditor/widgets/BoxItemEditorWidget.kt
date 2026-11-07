package world.phantasmal.web.questEditor.widgets

import org.w3c.dom.Node
import world.phantasmal.cell.Cell
import world.phantasmal.cell.cell
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.cell.mutateDeferred
import world.phantasmal.cell.observeNow
import world.phantasmal.core.disposable.Disposer
import world.phantasmal.psolib.fileFormats.quest.BoxItemField
import world.phantasmal.web.questEditor.controllers.BoxItemCategory
import world.phantasmal.web.questEditor.controllers.BoxItemEditorModel
import world.phantasmal.webui.dom.*
import world.phantasmal.webui.widgets.*

class BoxItemEditorWidget(private val model: BoxItemEditorModel, private val editing: Cell<Boolean>) : Widget() {
    private val itemFilter = mutableCell("")
    private val filteredChoices = world.phantasmal.cell.map(model.choices, itemFilter) { items, filter ->
        items.filter { it.toString().contains(filter.trim(), ignoreCase = true) }
    }
    private val statusMessage = world.phantasmal.cell.map(model.summary, model.active, model.isExact) { summary, active, exact ->
        if (active && exact) "" else summary
    }
    init {
        var category = model.category.value
        observe(model.category) { next ->
            if (next != category) {
                category = next
                mutateDeferred { itemFilter.value = "" }
            }
        }
    }
    override fun Node.createElement() = div {
        className = "pw-box-item-editor"
        p {
            hidden(statusMessage.map { it.isEmpty() })
            text(statusMessage)
        }
        div {
            hidden(model.active.map { !it })
            table {
                tr {
                    val control = Select(
                        label = "Drop category:", enabled = editing,
                        items = cell(BoxItemCategory.values().toList()), selected = model.category,
                        onSelect = model::setCategory,
                    )
                    th { addChild(control.label!!) }
                    td { addChild(control) }
                }
                tr {
                    hidden(model.isExact.map { !it })
                    val filter = TextInput(label = "Find item:", enabled = editing,
                        value = itemFilter, onChange = { itemFilter.value = it })
                    th { addChild(filter.label!!) }
                    td { addChild(filter) }
                }
                tr {
                    hidden(model.isExact.map { !it })
                    val control = Select(
                        label = "Item:", enabled = editing, items = filteredChoices,
                        selected = model.selectedItem, onSelect = model::setItem,
                        tooltip = model.selectedItem.map { it?.toString() },
                    )
                    th { addChild(control.label!!) }
                    td { addChild(control) }
                }
            }
            // The binding owns all children: keep fixed selectors in their own table.
            table {
                bindDisposableChildrenTo(model.fields) { field, _ ->
                    val disposer = Disposer()
                    val row = tr {
                        title = when (field) {
                            BoxItemField.Attribute1, BoxItemField.Attribute2, BoxItemField.Attribute3 ->
                                "0=None, 1=Native, 2=A.Beast, 3=Machine, 4=Dark, 5=Hit"
                            else -> field.label
                        }
                        th { textContent = "${field.label}:" }
                        td { input {
                            type = "number"
                            className = "pw-input-inner"
                            setAttribute("aria-label", field.label)
                            min = field.min.toString()
                            step = field.step.toString()
                            disposer.add(model.definition.observeNow { def ->
                                max = (if (field == BoxItemField.TechniqueLevel) def.maxTechniqueLevel else field.max).toString()
                                value = def.read(field).toString()
                                setCustomValidity("")
                            })
                            disposer.add(editing.observeNow { disabled = !it })
                            onchange = {
                                val parsed = value.toIntOrNull()
                                val valid = checkValidity() && parsed != null && model.setField(field, parsed)
                                setCustomValidity(if (valid) "" else "Enter a value in the displayed range and step.")
                                if (!valid) reportValidity()
                            }
                            oninput = { setCustomValidity("") }
                        } }
                    }
                    Pair(row, disposer)
                }
            }
            p { text(model.note) }
        }
    }

    companion object {
        init {
            style("""
                .pw-box-item-editor table { table-layout: fixed; }
                .pw-box-item-editor p { margin: 8px 2px; overflow-wrap: anywhere; }
                .pw-box-item-editor .pw-text-input, .pw-box-item-editor .pw-select { width: 100%; }
                .pw-box-item-editor input { box-sizing: border-box; min-width: 0; width: 100%; }
            """.trimIndent())
        }
    }
}
