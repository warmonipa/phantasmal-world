package world.phantasmal.web.questEditor.widgets

import org.w3c.dom.Node
import world.phantasmal.cell.Cell
import world.phantasmal.cell.cell
import world.phantasmal.cell.map
import world.phantasmal.cell.mutableCell
import world.phantasmal.psolib.asm.EnemyAttackData
import world.phantasmal.web.questEditor.asm.DataLabelType
import world.phantasmal.web.questEditor.controllers.DataEditorController
import world.phantasmal.web.questEditor.controllers.DataLabelEntry
import world.phantasmal.webui.dom.*
import world.phantasmal.webui.widgets.*

class EnemyAttackDataDialog(
    visible: Cell<Boolean>,
    private val ctrl: DataEditorController,
    onDismiss: () -> Unit,
    private val initialLabelId: Cell<Int?> = cell(null),
) : Dialog(
    visible = visible,
    title = cell("Edit Enemy Attack Data"),
    description = cell("Edit AttackData (get_attack_data)"),
    content = {},
    footer = {},
    onDismiss = onDismiss,
) {
    private val labels = ctrl.labelsOfType(DataLabelType.AttackData)
    private val selectedLabel = mutableCell<DataLabelEntry?>(null)

    private val minAtp = mutableCell(0)
    private val maxAtp = mutableCell(0)
    private val minAta = mutableCell(0)
    private val maxAta = mutableCell(0)
    private val distanceX = mutableCell(0.0)
    private val angle = mutableCell(0)
    private val distanceY = mutableCell(0.0)

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
        if (buf.size < EnemyAttackData.SIZE) return
        val data = EnemyAttackData.readFrom(buf)

        minAtp.value = data.minAtp.toInt()
        maxAtp.value = data.maxAtp.toInt()
        minAta.value = data.minAta.toInt()
        maxAta.value = data.maxAta.toInt()
        distanceX.value = data.distanceX.toDouble()
        angle.value = data.angle.toInt()
        distanceY.value = data.distanceY.toDouble()
    }

    private fun save() {
        val entry = selectedLabel.value ?: return
        val buf = ctrl.readSegmentData(entry.labelId) ?: return
        if (buf.size < EnemyAttackData.SIZE) return

        val data = EnemyAttackData(
            minAtp = minAtp.value.toShort(),
            maxAtp = maxAtp.value.toShort(),
            minAta = minAta.value.toShort(),
            maxAta = maxAta.value.toShort(),
            distanceX = distanceX.value.toFloat(),
            angle = angle.value.toUInt(),
            distanceY = distanceY.value.toFloat(),
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
                        fieldRow("Min ATP", IntInput(value = minAtp,
                            onChange = { minAtp.value = it }))
                        fieldRow("Max ATP", IntInput(value = maxAtp,
                            onChange = { maxAtp.value = it }))
                        fieldRow("Min ATA", IntInput(value = minAta,
                            onChange = { minAta.value = it }))
                        fieldRow("Max ATA", IntInput(value = maxAta,
                            onChange = { maxAta.value = it }))
                        fieldRow("Distance X", DoubleInput(value = distanceX,
                            onChange = { distanceX.value = it }, roundTo = 4))
                        fieldRow("Angle", IntInput(value = angle,
                            onChange = { angle.value = it }, min = 0))
                        fieldRow("Distance Y", DoubleInput(value = distanceY,
                            onChange = { distanceY.value = it }, roundTo = 4))
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
