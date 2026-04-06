package world.phantasmal.web.questEditor.asm

import world.phantasmal.psolib.asm.*

enum class DataLabelType {
    NpcData,
    PhysicalData,
    AttackData,
    ResistData,
    MovementData,
}

/**
 * Scans all instruction segments in the bytecodeIr and maps dlabel arguments of known
 * data-referencing opcodes to their [DataLabelType].
 */
fun analyzeDataLabels(bytecodeIr: BytecodeIr): Map<Int, DataLabelType> {
    val opcodeNpcData = codeToOpcode(0xF841)       // get_npc_data
    val opcodePhysicalData = codeToOpcode(0xF892)   // get_physical_data
    val opcodeAttackData = codeToOpcode(0xF893)     // get_attack_data
    val opcodeResistData = codeToOpcode(0xF894)     // get_resist_data
    val opcodeMovementData = codeToOpcode(0xF895)   // get_movement_data

    val result = mutableMapOf<Int, DataLabelType>()

    for (segment in bytecodeIr.segments) {
        if (segment !is InstructionSegment) continue

        for (instruction in segment.instructions) {
            val type = when (instruction.opcode) {
                opcodeNpcData -> DataLabelType.NpcData
                opcodePhysicalData -> DataLabelType.PhysicalData
                opcodeAttackData -> DataLabelType.AttackData
                opcodeResistData -> DataLabelType.ResistData
                opcodeMovementData -> DataLabelType.MovementData
                else -> continue
            }

            // The first (and only) argument is the dlabel reference.
            val labelId = instruction.args.firstOrNull()?.coerceInt() ?: continue
            result[labelId] = type
        }
    }

    return result
}
