package world.phantasmal.psolib.compatibility

import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.*
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.fileFormats.quest.DatUnknown
import world.phantasmal.psolib.fileFormats.quest.Quest
import world.phantasmal.psolib.fileFormats.quest.QuestNpc
import world.phantasmal.psolib.fileFormats.quest.QuestObject
import world.phantasmal.psolib.test.LibTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompatibilityCheckerTests : LibTestSuite {
    private val checker = CompatibilityChecker()

    @Test
    fun quest_with_label_0_is_valid() {
        val quest = createQuest(
            bytecodeIr = BytecodeIr(
                listOf(
                    InstructionSegment(
                        labels = mutableListOf(0),
                        instructions = mutableListOf(
                            Instruction(
                                opcode = OP_SET_EPISODE,
                                args = listOf(IntArg(0)),
                                srcLoc = null,
                                valid = true,
                            ),
                            Instruction(
                                opcode = OP_RET,
                                args = emptyList(),
                                srcLoc = null,
                                valid = true,
                            ),
                        ),
                    )
                )
            )
        )

        val result = checker.checkCompatibility(PSOVersion.BLUE_BURST, quest)

        assertTrue(result.isFullyCompatible)
        assertEquals(0, result.errors.size)
        assertEquals(0, result.warnings.size)
    }

    @Test
    fun quest_without_label_0_has_error() {
        val quest = createQuest(
            bytecodeIr = BytecodeIr(
                listOf(
                    InstructionSegment(
                        labels = mutableListOf(1), // No label 0
                        instructions = mutableListOf(
                            Instruction(
                                opcode = OP_RET,
                                args = emptyList(),
                                srcLoc = null,
                                valid = true,
                            ),
                        ),
                    )
                )
            )
        )

        val result = checker.checkCompatibility(PSOVersion.BLUE_BURST, quest)

        assertFalse(result.isFullyCompatible)
        assertEquals(1, result.errors.size)
        assertEquals(ProblemType.MISSING_LABEL_0, result.errors[0].type)
    }

    @Test
    fun episode_2_not_supported_in_v1() {
        val quest = createQuest(
            episode = Episode.II,
            bytecodeIr = BytecodeIr(
                listOf(
                    InstructionSegment(
                        labels = mutableListOf(0),
                        instructions = mutableListOf(
                            Instruction(
                                opcode = OP_SET_EPISODE,
                                args = listOf(IntArg(1)), // Episode 2
                                srcLoc = null,
                                valid = true,
                            ),
                            Instruction(
                                opcode = OP_RET,
                                args = emptyList(),
                                srcLoc = null,
                                valid = true,
                            ),
                        ),
                    )
                )
            )
        )

        val result = checker.checkCompatibility(PSOVersion.DC_V1, quest)

        assertTrue(result.hasErrors)
        val episodeError = result.errors.find { it.type == ProblemType.EPISODE_NOT_SUPPORTED }
        assertTrue(episodeError != null)
    }

    @Test
    fun episode_4_not_supported_in_pc() {
        val quest = createQuest(
            episode = Episode.IV,
            bytecodeIr = BytecodeIr(
                listOf(
                    InstructionSegment(
                        labels = mutableListOf(0),
                        instructions = mutableListOf(
                            Instruction(
                                opcode = OP_SET_EPISODE,
                                args = listOf(IntArg(2)), // Episode 4
                                srcLoc = null,
                                valid = true,
                            ),
                            Instruction(
                                opcode = OP_RET,
                                args = emptyList(),
                                srcLoc = null,
                                valid = true,
                            ),
                        ),
                    )
                )
            )
        )

        val result = checker.checkCompatibility(PSOVersion.PC, quest)

        assertTrue(result.hasErrors)
        val episodeError = result.errors.find { it.type == ProblemType.EPISODE_NOT_SUPPORTED }
        assertTrue(episodeError != null)
    }

    @Test
    fun check_all_versions_returns_results_for_all() {
        val quest = createQuest()

        val results = checker.checkAllVersions(quest)

        assertEquals(PSOVersion.entries.size, results.size)
        PSOVersion.entries.forEach { version ->
            assertTrue(results.containsKey(version))
            assertEquals(version, results[version]!!.version)
        }
    }

    @Test
    fun too_many_npcs_per_area_generates_warning() {
        val npcs = mutableListOf<QuestNpc>()
        // Create 401 NPCs in area 0
        repeat(401) {
            npcs.add(createQuestNpc(areaId = 0))
        }

        val quest = createQuest(npcs = npcs)

        val result = checker.checkCompatibility(PSOVersion.BLUE_BURST, quest)

        assertTrue(result.hasWarnings)
        val tooManyWarning = result.warnings.find { it.type == ProblemType.TOO_MANY_MONSTERS }
        assertTrue(tooManyWarning != null)
    }

    @Test
    fun too_many_objects_per_area_generates_warning() {
        val objects = mutableListOf<QuestObject>()
        // Create 401 objects in area 0
        repeat(401) {
            objects.add(createQuestObject(areaId = 0))
        }

        val quest = createQuest(objects = objects)

        val result = checker.checkCompatibility(PSOVersion.BLUE_BURST, quest)

        assertTrue(result.hasWarnings)
        val tooManyWarning = result.warnings.find { it.type == ProblemType.TOO_MANY_OBJECTS }
        assertTrue(tooManyWarning != null)
    }

    @Test
    fun npc_undefined_custom_label_generates_warning() {
        // Create an NPC with a custom script label that doesn't exist in bytecode
        val npc = createQuestNpc(areaId = 0, scriptLabel = 999) // Custom label, not in bytecode
        val quest = createQuest(npcs = mutableListOf(npc))

        // For any version, if NPC uses a non-default label that doesn't exist in script,
        // it should generate a warning
        val result = checker.checkCompatibility(PSOVersion.GC_EP12, quest)

        assertTrue(result.hasWarnings)
        val undefinedLabelWarning = result.warnings.find { it.type == ProblemType.NPC_ACTION_LABEL_NOT_FOUND }
        assertTrue(undefinedLabelWarning != null)
        assertTrue(undefinedLabelWarning!!.message.contains("999"))
    }

    @Test
    fun npc_custom_label_defined_in_bytecode_no_warning() {
        // Create bytecode with the custom label defined
        val bytecodeIr = BytecodeIr(
            listOf(
                InstructionSegment(
                    labels = mutableListOf(0, 999),
                    instructions = mutableListOf(
                        Instruction(
                            opcode = OP_SET_EPISODE,
                            args = listOf(IntArg(0)),
                            srcLoc = null,
                            valid = true,
                        ),
                        Instruction(
                            opcode = OP_RET,
                            args = emptyList(),
                            srcLoc = null,
                            valid = true,
                        ),
                    ),
                )
            )
        )

        // Create an NPC with a custom script label that exists in bytecode
        val npc = createQuestNpc(areaId = 0, scriptLabel = 999)
        val quest = createQuest(bytecodeIr = bytecodeIr, npcs = mutableListOf(npc))

        // If the custom label is defined in script, no warning should be generated
        for (version in PSOVersion.entries) {
            val result = checker.checkCompatibility(version, quest)

            val labelWarning = result.warnings.find { it.type == ProblemType.NPC_ACTION_LABEL_NOT_FOUND }
            assertTrue(labelWarning == null, "Custom label defined in script should not generate warning in ${version.name}")
        }
    }

    @Test
    fun npc_undefined_label_warning_for_all_versions() {
        // Create an NPC with a non-existent custom script label
        val npc = createQuestNpc(areaId = 0, scriptLabel = 999)
        val quest = createQuest(npcs = mutableListOf(npc))

        // All versions should warn about undefined labels
        for (version in PSOVersion.entries) {
            val result = checker.checkCompatibility(version, quest)

            assertTrue(result.hasWarnings, "Version ${version.name} should warn about undefined label")
            val labelWarning = result.warnings.find { it.type == ProblemType.NPC_ACTION_LABEL_NOT_FOUND }
            assertTrue(labelWarning != null, "Version ${version.name} should have NPC_ACTION_LABEL_NOT_FOUND warning")
        }
    }

    @Test
    fun npc_default_label_works_in_all_versions() {
        // Create an NPC with a default menu label (100 is a default label for Episode 1)
        val npc = createQuestNpc(areaId = 0, scriptLabel = 100)
        val quest = createQuest(npcs = mutableListOf(npc))

        // Default labels should work in all versions without needing script definition
        for (version in PSOVersion.entries) {
            val result = checker.checkCompatibility(version, quest)

            // Should not have label not found warning
            val labelNotFoundWarning = result.warnings.find { it.type == ProblemType.NPC_ACTION_LABEL_NOT_FOUND }
            assertTrue(labelNotFoundWarning == null, "Default label should not generate warning in ${version.name}")
        }
    }

    @Test
    fun npc_gc_extended_label_works_only_in_gc() {
        // 850 is a GC-extended label for Episode 1
        val npc = createQuestNpc(areaId = 0, scriptLabel = 850)
        val quest = createQuest(npcs = mutableListOf(npc))

        // GC (ver=3) should recognize 850 as a built-in default label
        val gcResult = checker.checkCompatibility(PSOVersion.GC_EP12, quest)
        val gcWarning = gcResult.warnings.find { it.type == ProblemType.NPC_ACTION_LABEL_NOT_FOUND }
        assertTrue(gcWarning == null, "GC should recognize 850 as built-in label")

        // BB uses DC V2 rules, so 850 is NOT built-in (needs script definition)
        val bbResult = checker.checkCompatibility(PSOVersion.BLUE_BURST, quest)
        val bbWarning = bbResult.warnings.find { it.type == ProblemType.NPC_ACTION_LABEL_NOT_FOUND }
        assertTrue(bbWarning != null, "BB should require 850 to be defined in script")

        // DC V1/V2 should also require script definition
        val dcResult = checker.checkCompatibility(PSOVersion.DC_V2, quest)
        val dcWarning = dcResult.warnings.find { it.type == ProblemType.NPC_ACTION_LABEL_NOT_FOUND }
        assertTrue(dcWarning != null, "DC V2 should require 850 to be defined in script")
    }

    @Test
    fun script_label_reference_not_found_is_error() {
        // Create bytecode with a label reference to a non-existent label
        val bytecodeIr = BytecodeIr(
            listOf(
                InstructionSegment(
                    labels = mutableListOf(0),
                    instructions = mutableListOf(
                        Instruction(
                            opcode = OP_CALL,
                            args = listOf(IntArg(999)), // Label 999 doesn't exist
                            srcLoc = null,
                            valid = true,
                        ),
                        Instruction(
                            opcode = OP_RET,
                            args = emptyList(),
                            srcLoc = null,
                            valid = true,
                        ),
                    ),
                )
            )
        )

        val quest = createQuest(bytecodeIr = bytecodeIr)

        val result = checker.checkCompatibility(PSOVersion.BLUE_BURST, quest)

        assertTrue(result.hasErrors)
        val labelError = result.errors.find { it.type == ProblemType.LABEL_NOT_FOUND }
        assertTrue(labelError != null)
    }

    @Test
    fun bb_specific_opcode_not_supported_in_gc() {
        // Create bytecode with a BB-specific opcode (bb_map_designate)
        val bytecodeIr = BytecodeIr(
            listOf(
                InstructionSegment(
                    labels = mutableListOf(0),
                    instructions = mutableListOf(
                        Instruction(
                            opcode = OP_SET_EPISODE,
                            args = listOf(IntArg(0)),
                            srcLoc = null,
                            valid = true,
                        ),
                        Instruction(
                            opcode = OP_BB_MAP_DESIGNATE,
                            args = listOf(IntArg(0), IntArg(0), IntArg(0), IntArg(0)),
                            srcLoc = null,
                            valid = true,
                        ),
                        Instruction(
                            opcode = OP_RET,
                            args = emptyList(),
                            srcLoc = null,
                            valid = true,
                        ),
                    ),
                )
            )
        )

        val quest = createQuest(bytecodeIr = bytecodeIr)

        // GC doesn't support BB-specific opcodes
        val result = checker.checkCompatibility(PSOVersion.GC_EP12, quest)

        assertTrue(result.hasErrors)
        val opcodeError = result.errors.find { it.type == ProblemType.OPCODE_VERSION_MISMATCH }
        assertTrue(opcodeError != null)
        assertTrue(opcodeError!!.message.contains("bb_map_designate"))
    }

    @Test
    fun bb_specific_opcode_supported_in_bb() {
        // Create bytecode with a BB-specific opcode (bb_map_designate)
        val bytecodeIr = BytecodeIr(
            listOf(
                InstructionSegment(
                    labels = mutableListOf(0),
                    instructions = mutableListOf(
                        Instruction(
                            opcode = OP_SET_EPISODE,
                            args = listOf(IntArg(0)),
                            srcLoc = null,
                            valid = true,
                        ),
                        Instruction(
                            opcode = OP_BB_MAP_DESIGNATE,
                            args = listOf(IntArg(0), IntArg(0), IntArg(0), IntArg(0)),
                            srcLoc = null,
                            valid = true,
                        ),
                        Instruction(
                            opcode = OP_RET,
                            args = emptyList(),
                            srcLoc = null,
                            valid = true,
                        ),
                    ),
                )
            )
        )

        val quest = createQuest(bytecodeIr = bytecodeIr)

        // BB supports BB-specific opcodes
        val result = checker.checkCompatibility(PSOVersion.BLUE_BURST, quest)

        // Should not have opcode version mismatch error
        val opcodeError = result.errors.find { it.type == ProblemType.OPCODE_VERSION_MISMATCH }
        assertTrue(opcodeError == null)
    }

    private fun createQuest(
        episode: Episode = Episode.I,
        bytecodeIr: BytecodeIr = createDefaultBytecodeIr(),
        npcs: MutableList<QuestNpc> = mutableListOf(),
        objects: MutableList<QuestObject> = mutableListOf(),
    ): Quest = Quest(
        id = 1,
        language = 0,
        name = "Test Quest",
        shortDescription = "Test",
        longDescription = "Test Quest Description",
        episode = episode,
        objects = objects,
        npcs = npcs,
        events = mutableListOf(),
        datUnknowns = mutableListOf(),
        bytecodeIr = bytecodeIr,
        shopItems = UIntArray(0),
        mapDesignations = mutableMapOf(),
    )

    private fun createDefaultBytecodeIr(): BytecodeIr = BytecodeIr(
        listOf(
            InstructionSegment(
                labels = mutableListOf(0),
                instructions = mutableListOf(
                    Instruction(
                        opcode = OP_SET_EPISODE,
                        args = listOf(IntArg(0)),
                        srcLoc = null,
                        valid = true,
                    ),
                    Instruction(
                        opcode = OP_RET,
                        args = emptyList(),
                        srcLoc = null,
                        valid = true,
                    ),
                ),
            )
        )
    )

    private fun createQuestNpc(areaId: Int = 0, scriptLabel: Int = 0): QuestNpc {
        val data = Buffer.withSize(72)
        val npc = QuestNpc(Episode.I, areaId, data)
        if (scriptLabel > 0) {
            npc.scriptLabel = scriptLabel
        }
        return npc
    }

    private fun createQuestObject(areaId: Int = 0): QuestObject {
        val data = Buffer.withSize(68)
        return QuestObject(areaId, data)
    }
}