package world.phantasmal.web.questEditor.controllers

import world.phantasmal.cell.Cell
import world.phantasmal.cell.map
import world.phantasmal.psolib.asm.*
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.web.externals.monacoEditor.ITextModel
import world.phantasmal.web.externals.monacoEditor.Range
import world.phantasmal.web.questEditor.asm.DataLabelType
import world.phantasmal.web.questEditor.asm.analyzeDataLabels
import world.phantasmal.web.questEditor.stores.AsmStore
import world.phantasmal.web.questEditor.stores.QuestEditorStore
import world.phantasmal.webui.controllers.Controller
import world.phantasmal.webui.obj

class DataLabelEntry(
    val labelId: Int,
    val type: DataLabelType,
)

class DataEditorController(
    private val questEditorStore: QuestEditorStore,
    private val asmStore: AsmStore,
) : Controller() {
    val enabled: Cell<Boolean> = asmStore.editingEnabled

    /**
     * All typed data labels in the current quest's bytecodeIr.
     */
    val dataLabels: Cell<List<DataLabelEntry>> =
        questEditorStore.currentQuest.map { quest ->
            if (quest == null) return@map emptyList()
            val labelTypes = analyzeDataLabels(quest.bytecodeIr)
            labelTypes.map { (labelId, type) -> DataLabelEntry(labelId, type) }
                .sortedBy { it.labelId }
        }

    /**
     * Returns data labels filtered by [type].
     */
    fun labelsOfType(type: DataLabelType): Cell<List<DataLabelEntry>> =
        dataLabels.map { labels -> labels.filter { it.type == type } }

    /**
     * Reads the raw [Buffer] for a data segment identified by [labelId].
     * Returns null if the quest or segment is not found.
     */
    fun readSegmentData(labelId: Int): Buffer? {
        val quest = questEditorStore.currentQuest.value ?: return null
        val segment = quest.bytecodeIr.segments
            .filterIsInstance<DataSegment>()
            .find { labelId in it.labels }
        return segment?.data
    }

    /**
     * Writes modified [buffer] back to the Monaco text model by replacing the HEX lines
     * for the given [labelId]. This triggers the normal reassembly pipeline.
     */
    fun writeSegmentData(labelId: Int, buffer: Buffer): Boolean {
        val textModel = asmStore.textModel.value ?: return false
        val lineRange = findDataLineRange(textModel, labelId) ?: return false

        val newHexText = formatBufferAsHex(buffer)

        // Replace the old data lines with new ones.
        val edit = obj<world.phantasmal.web.externals.monacoEditor.IIdentifiedSingleEditOperation> {
            range = obj {
                startLineNumber = lineRange.first
                startColumn = 1
                endLineNumber = lineRange.second
                endColumn = textModel.getLineMaxColumn(lineRange.second).toInt()
            }
            text = newHexText
        }

        textModel.pushEditOperations(
            beforeCursorState = null,
            editOperations = arrayOf(edit),
            cursorStateComputer = js("function() { return null }").unsafeCast<world.phantasmal.web.externals.monacoEditor.ICursorStateComputer>(),
        )

        return true
    }

    /**
     * Determines which data label the cursor is on (label line or its data lines).
     * Returns the [DataLabelEntry] or null if the cursor is not on a known data segment.
     */
    fun dataLabelAtLine(lineNo: Int): DataLabelEntry? {
        val model = asmStore.textModel.value ?: return null
        val lineCount = model.getLineCount().toInt()
        if (lineNo < 1 || lineNo > lineCount) return null

        val content = model.getLineContent(lineNo).trimStart()

        // Check if this line IS a label line (e.g. "444:")
        val labelId = parseLabelId(content)
        if (labelId != null) {
            return dataLabels.value.find { it.labelId == labelId }
        }

        // Check if this is a data line (starts with 0x). Walk backwards to find the label.
        if (content.startsWith("0x")) {
            for (scanLine in (lineNo - 1) downTo 1) {
                val scanContent = model.getLineContent(scanLine).trimStart()
                if (scanContent.startsWith("0x")) continue // another data line
                val foundLabelId = parseLabelId(scanContent)
                if (foundLabelId != null) {
                    return dataLabels.value.find { it.labelId == foundLabelId }
                }
                break // hit something else (section marker, code, etc.)
            }
        }

        return null
    }

    private fun parseLabelId(trimmedLine: String): Int? {
        if (!trimmedLine.endsWith(":")) return null
        return trimmedLine.dropLast(1).toIntOrNull()
    }

    /**
     * Finds the line range (startLine..endLine, inclusive) of the data bytes for [labelId]
     * in the text model. The data lines are the indented hex lines immediately following the
     * label declaration.
     */
    private fun findDataLineRange(model: ITextModel, labelId: Int): Pair<Int, Int>? {
        val lineCount = model.getLineCount().toInt()
        val labelPrefix = "$labelId:"

        // Find the label line.
        var labelLine = -1
        for (lineNo in 1..lineCount) {
            val content = model.getLineContent(lineNo).trimStart()
            if (content == labelPrefix || content.startsWith("$labelPrefix ")) {
                labelLine = lineNo
                break
            }
        }
        if (labelLine == -1) return null

        // Data lines start right after the label and are indented (start with spaces + 0x).
        var startLine = -1
        var endLine = -1

        for (lineNo in (labelLine + 1)..lineCount) {
            val content = model.getLineContent(lineNo)
            if (content.startsWith("    0x") || content.startsWith("\t0x")) {
                if (startLine == -1) startLine = lineNo
                endLine = lineNo
            } else {
                // Also handle indented-only empty line for zero-size segments.
                if (startLine == -1 && content.isBlank() && content.length >= 4) {
                    startLine = lineNo
                    endLine = lineNo
                }
                break
            }
        }

        return if (startLine != -1) Pair(startLine, endLine) else null
    }

    companion object {
        private const val INDENT = "    "

        /**
         * Formats a [Buffer] as HEX text matching the disassembly format:
         * 4-space indent, `0x` prefix per byte, uppercase, 16 bytes per line.
         */
        fun formatBufferAsHex(buffer: Buffer): String {
            if (buffer.size == 0) return INDENT

            val sb = StringBuilder()

            for (i in 0 until buffer.size) {
                if (i % 16 == 0) {
                    if (i > 0) sb.append("\n")
                    sb.append(INDENT)
                } else {
                    sb.append(" ")
                }

                sb.append("0x")
                sb.append(buffer.getUByte(i).toString(16).uppercase().padStart(2, '0'))
            }

            return sb.toString()
        }
    }
}
