package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.Endianness
import world.phantasmal.psolib.asm.BytecodeIr
import world.phantasmal.psolib.asm.DataSegment
import world.phantasmal.psolib.asm.Instruction
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.asm.IntArg
import world.phantasmal.psolib.asm.codeToOpcode
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.symbolchat.SymbolChatColliTable
import world.phantasmal.psolib.test.LibTestSuite
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Generates a `.qst` for verifying the SymbolChatData label editor.
 *
 * Loads `default_ep_1.qst`, appends a fresh `DataSegment` whose payload is
 * the 24 built-in `SymbolChatT` records (60 bytes each) followed by 4
 * hand-crafted custom records, then writes the result into the user's
 * qedit drop folder so it can be opened in the editor.
 *
 * After running, open `symbol_chat_test.qst` in the quest editor, locate the
 * new data label (it has no opcode reference, so the user must mark it as
 * `SymbolChatData` manually) and verify the 28 entries render correctly.
 *
 * Run with: ./gradlew :psolib:jvmTest --tests "*.GenerateSymbolChatTestQst"
 */
class GenerateSymbolChatTestQst : LibTestSuite {

    private val projectRoot: File =
        File(System.getProperty("user.dir")).let {
            if (it.name == "psolib") it.parentFile else it
        }

    private val outputDir = File(projectRoot, "psolib/src/commonTest/resources")

    @Test
    fun generate() = testAsync {
        // 1. Load the default Episode I quest as the donor.
        val donorPath = "web/src/jsMain/resources/assets/quests/defaults/default_ep_1.qst"
        val donorFile = File(projectRoot, donorPath)
        assertTrue(donorFile.exists(), "Donor quest not found: ${donorFile.absolutePath}")
        val donorBuf = Buffer.fromByteArray(donorFile.readBytes())
        val parseResult = parseQstToQuest(donorBuf.cursor())
        assertTrue(parseResult is Success, "Failed to parse donor quest: ${parseResult.problems}")
        val quest = parseResult.value.quest
        println("Loaded donor: ${donorFile.name} — ${quest.bytecodeIr.segments.size} segments")

        // 2. Load the bundled symbolchatcolli (24 × 104B records).
        val colliPath = "web/src/jsMain/resources/assets/symbol_chat/symbolchatcolli.bin"
        val colliFile = File(projectRoot, colliPath)
        assertTrue(colliFile.exists(), "symbolchatcolli.bin not found: ${colliFile.absolutePath}")
        val colliTable = SymbolChatColliTable(Buffer.fromByteArray(colliFile.readBytes()))

        // 3. Build the data label payload: 24 builtins + 4 custom variants.
        //    SymbolChatT layout (60 bytes):
        //      0..3   face          int32, low 2 bits = shape, bits 2..4 = colour
        //      4..11  4 corners     (icon u8, param u8) ; param low 3 = colour
        //      12..59 12 parts      (id u8, x u8, y u8, mirror u8)
        val custom = listOf(
            // Basic face/color combos.
            buildSymbolChat(face = encodeFace(shape = 0, color = 1)),  // angry, deep blue
            buildSymbolChat(face = encodeFace(shape = 1, color = 4)),  // happy, green
            buildSymbolChat(face = encodeFace(shape = 2, color = 6)),  // sad, pink
            buildSymbolChat(face = encodeFace(shape = 3, color = 0)),  // surprised, grey
            // Edge cases.
            buildEmptySymbolChat(),                                     // all corners 0xFF, all parts 0xFF
            buildSymbolChat(face = encodeFace(shape = 0, color = 0)),  // face color at boundary (0)
            buildSymbolChat(face = encodeFace(shape = 3, color = 7)),  // face color at boundary (7)
            buildFullSymbolChat(),                                      // all 4 corners + all 12 parts filled
        )

        // 4. The Edit Symbol Chat dialog edits ONE 60-byte record per data
        //    label (it picks the label from a dropdown). So instead of one
        //    big segment with 28 records, emit one DataSegment per record so
        //    each shows up as its own entry in the dropdown.
        val records: List<Pair<String, Buffer>> = buildList {
            for (id in 0 until 24) add("builtin_$id" to colliTable.entry(id)!!.copy())
            custom.forEachIndexed { idx, buf -> add("custom_$idx" to buf) }
        }

        val usedLabels = quest.bytecodeIr.segments.flatMap { it.labels }.toMutableSet()
        var nextLabel = 9000
        val newSegments = quest.bytecodeIr.segments.toMutableList()
        for ((name, buf) in records) {
            while (nextLabel in usedLabels) nextLabel++
            newSegments.add(DataSegment(mutableListOf(nextLabel), buf))
            usedLabels.add(nextLabel)
            println("  label $nextLabel  $name  (${buf.size} bytes)")
            nextLabel++
        }
        // 4b. Add a new instruction segment with a fresh label that exercises
        //     `set_symbol_chat_collision` (0xF8A6). The new auto-detection in
        //     DataLabelAnalysis will reverse-walk the leti writes and tag the
        //     three label refs in R+7..R+9 as `SymbolChatHexData`.
        //     Lock ids and coordinates are placeholder values — we only care
        //     that the static analysis picks the dlabels up correctly.
        while (nextLabel in usedLabels) nextLabel++
        val triggerLabel = nextLabel
        usedLabels.add(triggerLabel)
        nextLabel++

        val baseReg = 50  // arbitrary base register; not used elsewhere
        val sc1Label = 9000      // builtin #0  (matches the first DataSegment we just emitted)
        val sc2Label = 9000 + 24 // first custom variant
        val sc3Label = 9000 + 25 // second custom variant

        val opLeti = codeToOpcode(0x09)
        val opCall = codeToOpcode(0xF8A6)

        fun letiInst(reg: Int, value: Int) =
            Instruction(opLeti, listOf(IntArg(reg, isRegRef = true), IntArg(value)), valid = true, srcLoc = null)

        val triggerInsts = mutableListOf<Instruction>().apply {
            // R+0..R+2 — X / Y / Z (float bits, all zeros)
            add(letiInst(baseReg + 0, 0))
            add(letiInst(baseReg + 1, 0))
            add(letiInst(baseReg + 2, 0))
            // R+3 — radius (kept 0 for readability; runtime engine treats it
            // as a float, so a real quest would use the float bits of e.g.
            // 100.0f = 0x42C80000. Static analysis only cares about R+7..R+9.)
            add(letiInst(baseReg + 3, 0))
            // R+4..R+6 — locks (id 0, never unlocked)
            add(letiInst(baseReg + 4, 0))
            add(letiInst(baseReg + 5, 0))
            add(letiInst(baseReg + 6, 0))
            // R+7..R+9 — three SC HEX data labels
            add(letiInst(baseReg + 7, sc1Label))
            add(letiInst(baseReg + 8, sc2Label))
            add(letiInst(baseReg + 9, sc3Label))
            // The opcode itself, taking baseReg as its single arg.
            add(Instruction(opCall, listOf(IntArg(baseReg, isRegRef = true)), valid = true, srcLoc = null))
        }
        newSegments.add(InstructionSegment(mutableListOf(triggerLabel), triggerInsts))
        println("  label $triggerLabel  trigger #1 with set_symbol_chat_collision (refs: $sc1Label, $sc2Label, $sc3Label)")

        // 4c. Second set_symbol_chat_collision call referencing edge-case labels.
        while (nextLabel in usedLabels) nextLabel++
        val triggerLabel2 = nextLabel
        usedLabels.add(triggerLabel2)
        nextLabel++

        val baseReg2 = 70
        val sc4Label = 9000 + 28  // empty SC (all 0xFF)
        val sc5Label = 9000 + 31  // full SC (all slots filled)
        val sc6Label = 9000 + 26  // face color boundary (0)

        val triggerInsts2 = mutableListOf<Instruction>().apply {
            for (i in 0..6) add(letiInst(baseReg2 + i, 0))
            add(letiInst(baseReg2 + 7, sc4Label))
            add(letiInst(baseReg2 + 8, sc5Label))
            add(letiInst(baseReg2 + 9, sc6Label))
            add(Instruction(opCall, listOf(IntArg(baseReg2, isRegRef = true)), valid = true, srcLoc = null))
        }
        newSegments.add(InstructionSegment(mutableListOf(triggerLabel2), triggerInsts2))
        println("  label $triggerLabel2  trigger #2 with set_symbol_chat_collision (refs: $sc4Label, $sc5Label, $sc6Label)")

        quest.bytecodeIr = BytecodeIr(newSegments)

        // 5. Write the result.
        outputDir.mkdirs()
        val outName = "symbol_chat_test"
        val qstBuf = writeQuestToQst(quest, "$outName.qst", Version.BB, online = true)
        val outFile = File(outputDir, "$outName.qst")
        outFile.writeBytes(qstBuf.byteArray.copyOf(qstBuf.size))
        println("Wrote ${outFile.absolutePath} (${outFile.length()} bytes)")
        println("Open the quest, mark labels 9000..${nextLabel - 1} as SymbolChatData and verify each one in the dropdown.")
    }

    /** Pack a face byte: shape ∈ 0..3, colour ∈ 0..7. */
    private fun encodeFace(shape: Int, color: Int): Int =
        (shape and 3) or ((color and 7) shl 2)

    /**
     * Build a 60-byte SymbolChatT with the given face and a deterministic
     * but visually distinctive corner / part layout (one icon per corner,
     * one part in the middle) so each custom entry is easy to spot.
     */
    private fun buildSymbolChat(face: Int): Buffer {
        val b = Buffer.withSize(60, Endianness.Little)
        b.setInt(0, face)
        // 4 corners — colour cycles through palette so each is a different hue.
        val cornerIcons = intArrayOf(0, 8, 16, 24)
        val cornerColors = intArrayOf(2, 3, 4, 6)
        for (k in 0..3) {
            b.setUByte(4 + k * 2, cornerIcons[k].toUByte())
            b.setUByte(4 + k * 2 + 1, cornerColors[k].toUByte())
        }
        // 12 parts — slot 0 = a 16×16 part centred, slots 1..11 = empty (0xFF).
        for (k in 0 until 12) {
            val o = 12 + k * 4
            if (k == 0) {
                b.setUByte(o, 32u)            // partId
                b.setUByte(o + 1, 64u)        // x
                b.setUByte(o + 2, 32u)        // y
                b.setUByte(o + 3, 0u)         // mirror
            } else {
                b.setUByte(o, 0xFFu)
                b.setUByte(o + 1, 0u)
                b.setUByte(o + 2, 0u)
                b.setUByte(o + 3, 0u)
            }
        }
        return b
    }

    /** 60 bytes: all corners = 0xFF (none), all parts = 0xFF (none). */
    private fun buildEmptySymbolChat(): Buffer {
        val b = Buffer.withSize(60, Endianness.Little)
        b.setInt(0, encodeFace(shape = 0, color = 8)) // face only, white
        for (k in 0..3) {
            b.setUByte(4 + k * 2, 0xFFu)      // icon = none
            b.setUByte(4 + k * 2 + 1, 0u)
        }
        for (k in 0 until 12) {
            b.setUByte(12 + k * 4, 0xFFu)     // partId = none
        }
        return b
    }

    /** 60 bytes: all 4 corners filled + all 12 parts filled with varied ids. */
    private fun buildFullSymbolChat(): Buffer {
        val b = Buffer.withSize(60, Endianness.Little)
        b.setInt(0, encodeFace(shape = 2, color = 5))
        // 4 corners with different icons and colors.
        val icons = intArrayOf(1, 10, 18, 25)
        val colors = intArrayOf(0, 1, 2, 3)
        for (k in 0..3) {
            b.setUByte(4 + k * 2, icons[k].toUByte())
            b.setUByte(4 + k * 2 + 1, colors[k].toUByte())
        }
        // 12 parts: mix of 16×16 (id<48), 40×20 (48..59), 32×32 (60+).
        val partIds = intArrayOf(0, 5, 10, 20, 30, 40, 48, 50, 55, 60, 62, 65)
        for (k in 0 until 12) {
            val o = 12 + k * 4
            b.setUByte(o, partIds[k].toUByte())
            b.setUByte(o + 1, (30 + k * 5).coerceAtMost(255).toUByte()) // posX
            b.setUByte(o + 2, (20 + k * 3).coerceAtMost(255).toUByte()) // posY
            b.setUByte(o + 3, (k % 4).toUByte()) // mirror variants: 0, 1, 2, 3
        }
        return b
    }
}
