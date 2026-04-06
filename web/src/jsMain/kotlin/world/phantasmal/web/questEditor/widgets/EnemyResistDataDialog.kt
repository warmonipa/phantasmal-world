package world.phantasmal.web.questEditor.widgets

import org.w3c.dom.Node
import world.phantasmal.cell.Cell
import world.phantasmal.cell.cell
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.psolib.asm.EnemyResistData
import world.phantasmal.web.questEditor.asm.DataLabelType
import world.phantasmal.web.questEditor.controllers.DataEditorController
import world.phantasmal.web.questEditor.controllers.DataLabelEntry
import world.phantasmal.webui.dom.*
import world.phantasmal.webui.widgets.*

class EnemyResistDataDialog(
    visible: Cell<Boolean>,
    private val ctrl: DataEditorController,
    onDismiss: () -> Unit,
    private val initialLabelId: Cell<Int?> = cell(null),
) : Dialog(
    visible = visible,
    title = cell("Edit Enemy Resist Data"),
    description = cell("Edit ResistData (get_resist_data)"),
    content = {},
    footer = {},
    onDismiss = onDismiss,
) {
    private val labels = ctrl.labelsOfType(DataLabelType.ResistData)
    private val selectedLabel = mutableCell<DataLabelEntry?>(null)

    private val evpBonus = mutableCell(0)
    private val efr = mutableCell(0)
    private val eic = mutableCell(0)
    private val eth = mutableCell(0)
    private val elt = mutableCell(0)
    private val edk = mutableCell(0)
    private val dfpBonus = mutableCell(0)

    init {
        val bodyElement = dialogElement.querySelector(".pw-dialog-body")
        bodyElement?.let { body ->
            body.innerHTML = ""
            val contentWidget = addDisposable(Content())
            body.appendChild(contentWidget.element)
        }

        val footerElement = dialogElement.querySelector(".pw-dialog-footer")
        footerElement?.let { footer ->
            footer.innerHTML = ""
            val saveBtn = addDisposable(Button(
                text = "OK",
                enabled = map(ctrl.enabled, selectedLabel) { e, s -> e && s != null },
                onClick = { save(); onDismiss() },
            ))
            footer.appendChild(saveBtn.element)
            val cancelBtn = addDisposable(Button(text = "Cancel", onClick = { onDismiss() }))
            footer.appendChild(cancelBtn.element)
        }

        dialogElement.style.width = "400px"
        dialogElement.style.maxHeight = "400px"

        // Auto-select label when dialog opens.
        observeNow(visible) { vis ->
            if (vis) {
                val targetId = initialLabelId.value
                val entries = labels.value
                val entry = if (targetId != null) {
                    entries.find { it.labelId == targetId }
                } else {
                    entries.firstOrNull()
                }
                entry?.let(::loadLabel)
            }
        }
    }

    private fun loadLabel(entry: DataLabelEntry) {
        selectedLabel.value = entry
        val buf = ctrl.readSegmentData(entry.labelId) ?: return
        if (buf.size < EnemyResistData.SIZE) return
        val data = EnemyResistData.readFrom(buf)

        evpBonus.value = data.evpBonus.toInt()
        efr.value = data.efr.toInt()
        eic.value = data.eic.toInt()
        eth.value = data.eth.toInt()
        elt.value = data.elt.toInt()
        edk.value = data.edk.toInt()
        dfpBonus.value = data.dfpBonus
    }

    private fun save() {
        val entry = selectedLabel.value ?: return
        val buf = ctrl.readSegmentData(entry.labelId) ?: return
        if (buf.size < EnemyResistData.SIZE) return

        val data = EnemyResistData(
            evpBonus = evpBonus.value.toShort(),
            efr = efr.value.toUShort(),
            eic = eic.value.toUShort(),
            eth = eth.value.toUShort(),
            elt = elt.value.toUShort(),
            edk = edk.value.toUShort(),
            dfpBonus = dfpBonus.value,
        )
        data.writeTo(buf)
        ctrl.writeSegmentData(entry.labelId, buf)
    }

    private inner class Content : Widget() {
        override fun Node.createElement() =
            div {
                className = "pw-data-editor-content"

                div {
                    className = "pw-data-editor-label-row"
                    span { textContent = "Label:" }
                    addChild(Select(
                        items = labels,
                        itemToString = { "Label ${it.labelId}" },
                        selected = selectedLabel,
                        onSelect = ::loadLabel,
                    ))
                }

                table {
                    className = "pw-data-editor-table"

                    tbody {
                        fieldRow("EVP Bonus", IntInput(value = evpBonus,
                            onChange = { evpBonus.value = it }))
                        fieldRow("EFR (Fire)", IntInput(value = efr,
                            onChange = { efr.value = it }, min = 0, max = 65535))
                        fieldRow("EIC (Ice)", IntInput(value = eic,
                            onChange = { eic.value = it }, min = 0, max = 65535))
                        fieldRow("ETH (Thunder)", IntInput(value = eth,
                            onChange = { eth.value = it }, min = 0, max = 65535))
                        fieldRow("ELT (Light)", IntInput(value = elt,
                            onChange = { elt.value = it }, min = 0, max = 65535))
                        fieldRow("EDK (Dark)", IntInput(value = edk,
                            onChange = { edk.value = it }, min = 0, max = 65535))
                        fieldRow("DFP Bonus", IntInput(value = dfpBonus,
                            onChange = { dfpBonus.value = it }))
                    }
                }
            }

        private fun <T> Node.fieldRow(labelText: String, input: Input<T>) {
            val inputWidget = input
            tr {
                th {
                    val labelWidget = inputWidget.label
                    if (labelWidget != null) {
                        addChild(labelWidget)
                    } else {
                        textContent = labelText
                    }
                }
                td { addChild(inputWidget) }
            }
        }
    }
}
