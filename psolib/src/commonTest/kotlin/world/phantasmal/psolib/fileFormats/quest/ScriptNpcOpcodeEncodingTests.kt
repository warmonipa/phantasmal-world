package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.asm.BytecodeStringEncoding
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.asm.IntArg
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.assertDeepEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptNpcOpcodeEncodingTests : LibTestSuite {
    @Test
    fun v2_npc_creation_opcodes_parse_and_write_byte_exactly_on_dc_and_pc() {
        val bytecode = Buffer.fromByteArray(ubyteArrayOf(
            0x66u, 0x05u, 0x44u, 0x33u, 0x22u, 0x11u,
            0x79u, 0x06u, 0x00u, 0x00u, 0x00u,
            0x7Cu, 0x07u, 0x00u, 0x00u, 0x00u, 0x88u, 0x77u, 0x66u, 0x55u,
            0x7Du, 0x08u, 0x00u, 0x00u, 0x00u, 0xCCu, 0xBBu, 0xAAu, 0x99u,
            0x7Fu, 0x09u, 0x00u, 0x00u, 0x00u, 0x04u, 0x03u, 0x02u, 0x01u,
            0xCEu, 0x0Au, 0x00u, 0x00u, 0x00u, 0x0Du, 0x0Cu, 0x0Bu, 0x0Au,
            0x01u,
        ).toByteArray())
        val expected = listOf(
            "npc_crp" to listOf(5, 0x11223344),
            "npc_talk_pl" to listOf(6),
            "npc_crppk" to listOf(7, 0x55667788),
            "npc_crptalk" to listOf(8, 0x99AABBCC.toInt()),
            "npc_crp_id" to listOf(9, 0x01020304),
            "npc_crptalk_id" to listOf(10, 0x0A0B0C0D),
        )

        assertByteExactRoundTrip(bytecode, Version.DC_V2, BytecodeStringEncoding.ASCII, expected)
        assertByteExactRoundTrip(bytecode, Version.PC_V2, BytecodeStringEncoding.UTF16, expected)
    }

    @Test
    fun v3_and_v4_npc_creation_opcodes_parse_and_write_byte_exactly() {
        val bytecode = Buffer.fromByteArray(ubyteArrayOf(
            0x66u, 0x05u,
            0x79u, 0x06u,
            0x7Cu, 0x07u,
            0x7Du, 0x08u,
            0x7Fu, 0x09u,
            0xCEu, 0x0Au,
            0x01u,
        ).toByteArray())
        val expected = listOf(
            "npc_crp_v3" to listOf(5),
            "npc_talk_pl_v3" to listOf(6),
            "npc_crppk_v3" to listOf(7),
            "npc_crptalk_v3" to listOf(8),
            "npc_crp_id_v3" to listOf(9),
            "npc_crptalk_id_v3" to listOf(10),
        )

        assertByteExactRoundTrip(bytecode, Version.GC_V3, BytecodeStringEncoding.ASCII, expected)
        assertByteExactRoundTrip(bytecode, Version.BB_V4, BytecodeStringEncoding.UTF16, expected)
    }

    private fun assertByteExactRoundTrip(
        bytecode: Buffer,
        version: Version,
        encoding: BytecodeStringEncoding,
        expected: List<Pair<String, List<Int>>>,
    ) {
        val result = parseBytecode(
            bytecode.copy(),
            labelOffsets = intArrayOf(0),
            entryLabels = setOf(0),
            stringEncoding = encoding,
            lenient = false,
            version = version,
        )
        assertTrue(result is Success, "Failed to parse $version: $result")
        assertTrue(result.problems.isEmpty(), "Unexpected $version parse problems: ${result.problems}")

        val segment = result.value.segments.single() as InstructionSegment
        assertEquals(expected.size + 1, segment.instructions.size)
        expected.forEachIndexed { index, (mnemonic, args) ->
            val instruction = segment.instructions[index]
            assertEquals(mnemonic, instruction.opcode.mnemonic, "$version instruction $index")
            assertEquals(args, instruction.args.map { (it as IntArg).value }, "$version $mnemonic arguments")
        }
        assertEquals("ret", segment.instructions.last().opcode.mnemonic)
        assertEquals(bytecode.size, segment.size(encoding, version), "$version encoded size")

        val written = writeBytecode(result.value, encoding, version).bytecode
        assertDeepEquals(bytecode, written)
    }
}
