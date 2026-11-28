package world.phantasmal.web.questEditor.asm

import world.phantasmal.core.Severity
import world.phantasmal.core.Success
import world.phantasmal.psolib.Episode
import world.phantasmal.psolib.asm.*
import world.phantasmal.psolib.asm.dataFlowAnalysis.*
import world.phantasmal.psolib.fileFormats.quest.Version
import world.phantasmal.psolib.fileFormats.quest.findAreaByMapId
import world.phantasmal.psolib.fileFormats.quest.getMapId
import kotlin.math.min

data class AsmTextEdit(
    val startLineNumber: Int,
    val startColumn: Int,
    val endLineNumber: Int,
    val endColumn: Int,
    val text: String,
)

sealed class RegisterEditStrategy {
    /** Edit immediate arg of `leti` / `letb` / `letw` in place. */
    data class ReplaceImmediate(
        val lineNo: Int,
        val argCol: Int,
        val argLen: Int,
        val isHex: Boolean,
    ) : RegisterEditStrategy()

    /** Replace `clear rX` or `set rX` with `leti rX, <newVariant>`. */
    data class ReplaceClearOrSet(
        val lineNo: Int,
        val startCol: Int,
        val endCol: Int,
    ) : RegisterEditStrategy()

    /**
     * Insert `leti rX, <newVariant>` immediately before `map_designate(_ex)` and, when
     * [restoreOldValue] is true, restore `leti rX, <oldVariant>` immediately after so shared
     * register values used by subsequent instructions remain untouched.
     */
    data class WrapDesignateLine(
        val designateLineNo: Int,
        val instCol: Int,
        val restoreOldValue: Boolean,
    ) : RegisterEditStrategy()
}

sealed class FloorDesignationStatus {
    data class EditableDefault(
        val mapId: Int,
        val currentVariant: Int = 0,
        /** Non-null for V1/V2/V3 quests where insertion emits register-based `map_designate(_ex)`. */
        val scratchBaseReg: Int? = null,
        /** True for DC NTE / DC V1 which only support legacy `map_designate` (0xC4). */
        val useLegacyMapDesignate: Boolean = false,
    ) : FloorDesignationStatus()

    data class EditableBbDesignate(
        val mapId: Int,
        val currentVariant: Int,
        val lineNo: Int,
        val argCol: Int,
        val argLen: Int,
        val isHex: Boolean,
    ) : FloorDesignationStatus()

    data class EditableRegisterDesignate(
        val mapId: Int,
        val currentVariant: Int,
        val designateLineNo: Int,
        val variationReg: Int,
        val strategy: RegisterEditStrategy,
    ) : FloorDesignationStatus()

    data class ReadOnly(
        val reason: String,
        val jumpLineNo: Int? = null,
    ) : FloorDesignationStatus()
}

data class FloorDesignationSnapshot(
    val lines: List<String>,
    val version: Version,
    val isLossy: Boolean,
    val usedFloorIds: Set<Int>,
    val datEntryFloorIds: Map<Int, Set<Int>>,
    val scriptEpisode: Episode,
    val floorMappings: List<FloorMapping>,
    val hasAssemblyErrors: Boolean,
    val firstErrorLineNo: Int?,
    internal val ir: BytecodeIr?,
    internal val cfg: ControlFlowGraph?,
    internal val reachableInstructions: Map<Instruction, Set<Int>>,
    private val statusCache: MutableMap<Int, FloorDesignationStatus> = mutableMapOf(),
) {
    fun statusForFloor(floorId: Int): FloorDesignationStatus =
        statusCache.getOrPut(floorId) {
            FloorDesignationEditor.computeStatus(this, floorId)
        }
}

sealed class PrepareFloorVariantResult {
    /** Floor is already on the requested variant; switch viewport without editing script. */
    data class NoOp(val scriptEpisode: Episode) : PrepareFloorVariantResult()

    /** Pre-validated script edit ready to apply atomically to the Monaco model. */
    data class Ready(
        val edit: AsmTextEdit,
        val candidateIr: BytecodeIr,
        val candidateMappings: List<FloorMapping>,
        val scriptEpisode: Episode,
    ) : PrepareFloorVariantResult()

    /** Cannot safely edit script (stale menu, read-only, newly introduced ambiguity, etc.). */
    data class Rejected(val reason: String, val jumpLineNo: Int? = null) : PrepareFloorVariantResult()
}

/**
 * Pure, single-snapshot inspector and safe editor for floor map variations across all quest
 * versions:
 * - Blue Burst (V4): `bb_map_designate` (and existing register-based `map_designate(_ex)`).
 * - DC V2 / PC V2 / GC V3: `map_designate_ex` / `map_designate`.
 * - DC NTE / DC V1: legacy `map_designate`.
 *
 * All line/column coordinates come directly from assembling the live script text so this works
 * identically right after loading a binary `.qst`/`.bin+.dat` (where `quest.bytecodeIr` has
 * `srcLoc == null`) and after live edits in the Script tab.
 */
object FloorDesignationEditor {
    private fun validMapCount(version: Version): Int = when (version) {
        Version.DC_NTE,
        Version.DC_V1,
        Version.DC_V2,
        Version.PC_NTE,
        Version.PC_V2 -> 0x12

        Version.GC_NTE,
        Version.GC_V3 -> 0x24

        Version.BB_V4 -> 0x2F
    }

    private fun supportsMapDesignateEx(version: Version): Boolean = when (version) {
        Version.DC_NTE,
        Version.DC_V1 -> false

        Version.DC_V2,
        Version.PC_NTE,
        Version.PC_V2,
        Version.GC_NTE,
        Version.GC_V3,
        Version.BB_V4 -> true
    }

    fun inspect(
        lines: List<String>,
        version: Version,
        isLossy: Boolean,
        usedFloorIds: Set<Int>,
        datEntryFloorIds: Map<Int, Set<Int>>,
        fallbackEpisode: Episode = Episode.I,
    ): FloorDesignationSnapshot {
        val asmResult = assemble(lines, version)
        val firstError = asmResult.problems.firstOrNull { it.severity == Severity.Error }
        val hasErrors = asmResult !is Success || firstError != null
        val ir = (asmResult as? Success)?.value
        val segments = ir?.instructionSegments().orEmpty()
        val scriptEpisode = if (segments.any { 0 in it.labels }) {
            getScriptEpisode(segments)
        } else {
            fallbackEpisode
        }
        val cfg = if (ir != null && !hasErrors) ControlFlowGraph.create(ir) else null
        val floorMappings = if (ir != null && !hasErrors && cfg != null) {
            getFloorMappings(
                instructionSegments = segments,
                usedFloorIds = usedFloorIds,
                version = version,
                datEntryFloorIds = datEntryFloorIds,
            ) { cfg }
        } else {
            emptyList()
        }
        val reachable = if (ir != null && !hasErrors && cfg != null) {
            getReachableScriptInstructions(cfg, segments, datEntryFloorIds)
        } else {
            emptyMap()
        }

        return FloorDesignationSnapshot(
            lines = lines,
            version = version,
            isLossy = isLossy,
            usedFloorIds = usedFloorIds,
            datEntryFloorIds = datEntryFloorIds,
            scriptEpisode = scriptEpisode,
            floorMappings = floorMappings,
            hasAssemblyErrors = hasErrors,
            firstErrorLineNo = (firstError as? AssemblyProblem)?.lineNo,
            ir = ir,
            cfg = cfg,
            reachableInstructions = reachable,
        )
    }

    internal fun computeStatus(
        snapshot: FloorDesignationSnapshot,
        floorId: Int,
    ): FloorDesignationStatus {
        if (snapshot.isLossy) {
            return FloorDesignationStatus.ReadOnly(
                "Disable 'Hide NOPs' in the Script toolbar before editing map variations.",
            )
        }
        if (snapshot.hasAssemblyErrors || snapshot.ir == null || snapshot.cfg == null) {
            return FloorDesignationStatus.ReadOnly(
                "Fix script assembly errors before editing map variations.",
                jumpLineNo = snapshot.firstErrorLineNo,
            )
        }

        val mapCount = validMapCount(snapshot.version)
        val segments = snapshot.ir.instructionSegments()
        val cfg = snapshot.cfg
        val bbForFloor = mutableListOf<Instruction>()
        val regForFloor = mutableListOf<Instruction>()
        var unknownRegBlockerLine: Int? = null

        for (segment in segments) {
            for (inst in segment.instructions) {
                when (inst.opcode) {
                    OP_BB_MAP_DESIGNATE -> {
                        val instFloor = (inst.args.getOrNull(0) as? IntArg)?.value
                        if (instFloor == floorId) {
                            bbForFloor.add(inst)
                        }
                    }

                    OP_MAP_DESIGNATE,
                    OP_MAP_DESIGNATE_EX -> {
                        val baseReg = (inst.args.firstOrNull() as? IntArg)?.value
                        if (baseReg == null || baseReg !in 0..255) {
                            if (unknownRegBlockerLine == null) {
                                unknownRegBlockerLine = inst.srcLoc?.mnemonic?.lineNo
                            }
                        } else {
                            val values = getRegisterValue(cfg, inst, baseReg)
                            if (values.size == 1L && values[0] != null) {
                                if (values[0] == floorId) {
                                    regForFloor.add(inst)
                                }
                            } else if (unknownRegBlockerLine == null) {
                                unknownRegBlockerLine = inst.srcLoc?.mnemonic?.lineNo
                            }
                        }
                    }
                }
            }
        }

        val resolvedMapping = snapshot.floorMappings.find { it.floorId == floorId }
        if (resolvedMapping?.runtimeAmbiguous == true) {
            val jumpLine = bbForFloor.firstOrNull()?.srcLoc?.mnemonic?.lineNo
                ?: regForFloor.firstOrNull()?.srcLoc?.mnemonic?.lineNo
                ?: unknownRegBlockerLine
            return FloorDesignationStatus.ReadOnly(
                "This floor's map is controlled by conditional script branches.",
                jumpLineNo = jumpLine,
            )
        }

        if (unknownRegBlockerLine != null) {
            return FloorDesignationStatus.ReadOnly(
                "This floor may be modified by a dynamic register-based map_designate instruction.",
                jumpLineNo = unknownRegBlockerLine,
            )
        }

        val totalDesignations = bbForFloor.size + regForFloor.size
        if (totalDesignations > 1) {
            val jumpLine = (bbForFloor + regForFloor).last().srcLoc?.mnemonic?.lineNo
            return FloorDesignationStatus.ReadOnly(
                "Multiple map designation instructions target Floor $floorId.",
                jumpLineNo = jumpLine,
            )
        }

        if (bbForFloor.size == 1) {
            return computeBbDesignateStatus(snapshot, bbForFloor[0], mapCount)
        }

        if (regForFloor.size == 1) {
            return computeRegisterDesignateStatus(snapshot, regForFloor[0], floorId, mapCount)
        }

        // 0 explicit designations: check whether we can safely insert into label 0.
        val label0Segment = segments.find { 0 in it.labels }
            ?: return FloorDesignationStatus.ReadOnly("Script has no label 0 entry point.")

        if (label0Segment.labels.size > 1) {
            val label0Line = findLabel0Line(snapshot.lines)
            return FloorDesignationStatus.ReadOnly(
                "Label 0 shares an entry point with another label.",
                jumpLineNo = label0Line,
            )
        }

        val defaultMapId = resolvedMapping?.mapId
            ?: getMapId(snapshot.scriptEpisode, floorId)
            ?: return FloorDesignationStatus.ReadOnly("No default map ID for Floor $floorId.")

        if (defaultMapId !in 0 until mapCount) {
            return FloorDesignationStatus.ReadOnly("Default map ID $defaultMapId is out of range.")
        }

        if (snapshot.version == Version.BB_V4) {
            if (findLabel0InsertionEdit(snapshot.lines, label0Segment, listOf("bb_map_designate 0, 0, 0, 0, 0")) == null) {
                return FloorDesignationStatus.ReadOnly(
                    "Could not find a safe insertion point in label 0.",
                    jumpLineNo = findLabel0Line(snapshot.lines),
                )
            }
            return FloorDesignationStatus.EditableDefault(
                mapId = defaultMapId,
                currentVariant = 0,
            )
        }

        // V1 / V2 / V3: insert register-based map_designate_ex (V2/V3) or map_designate (DC V1/NTE).
        val useLegacy = !supportsMapDesignateEx(snapshot.version)
        if (useLegacy && defaultMapId != floorId) {
            return FloorDesignationStatus.ReadOnly(
                "Legacy map_designate on ${snapshot.version} cannot remap Floor $floorId to map $defaultMapId.",
            )
        }
        val neededRegs = if (useLegacy) 4 else 5
        val scratchBaseReg = findFreeScratchRegisters(segments, neededRegs)
            ?: return FloorDesignationStatus.ReadOnly(
                "Could not find $neededRegs free scratch registers to insert map designation in label 0.",
                jumpLineNo = findLabel0Line(snapshot.lines),
            )

        if (findLabel0InsertionEdit(snapshot.lines, label0Segment, listOf("map_designate r$scratchBaseReg")) == null) {
            return FloorDesignationStatus.ReadOnly(
                "Could not find a safe insertion point in label 0.",
                jumpLineNo = findLabel0Line(snapshot.lines),
            )
        }

        return FloorDesignationStatus.EditableDefault(
            mapId = defaultMapId,
            currentVariant = 0,
            scratchBaseReg = scratchBaseReg,
            useLegacyMapDesignate = useLegacy,
        )
    }

    private fun computeBbDesignateStatus(
        snapshot: FloorDesignationSnapshot,
        inst: Instruction,
        mapCount: Int,
    ): FloorDesignationStatus {
        val lineNo = inst.srcLoc?.mnemonic?.lineNo
        if (snapshot.version != Version.BB_V4) {
            return FloorDesignationStatus.ReadOnly(
                "bb_map_designate is only valid in Blue Burst (V4) quests.",
                jumpLineNo = lineNo,
            )
        }
        val mapId = (inst.args.getOrNull(1) as? IntArg)?.value
        val type = (inst.args.getOrNull(2) as? IntArg)?.value
        val variation = (inst.args.getOrNull(3) as? IntArg)?.value

        if (mapId == null || mapId !in 0 until mapCount) {
            return FloorDesignationStatus.ReadOnly(
                "Invalid map ID in bb_map_designate.",
                jumpLineNo = lineNo,
            )
        }
        if (type == null || (type != 0 && type != 2)) {
            return FloorDesignationStatus.ReadOnly(
                "Designation type ${type ?: "?"} ignores map variation.",
                jumpLineNo = lineNo,
            )
        }
        if (variation == null) {
            return FloorDesignationStatus.ReadOnly(
                "Could not read map variation argument.",
                jumpLineNo = lineNo,
            )
        }
        if (inst !in snapshot.reachableInstructions) {
            return FloorDesignationStatus.ReadOnly(
                "This floor's bb_map_designate is unreachable dead code.",
                jumpLineNo = lineNo,
            )
        }
        // Assembly.kt normalizes both 4-arg legacy and 5-arg bb_map_designate so
        // mapVariation is always at args[3].precise.
        val argLoc = inst.srcLoc?.args?.getOrNull(3)?.precise
            ?: return FloorDesignationStatus.ReadOnly(
                "Could not locate map variation argument in script.",
                jumpLineNo = lineNo,
            )
        val lineText = snapshot.lines.getOrNull(argLoc.lineNo - 1).orEmpty()
        val tokenText = lineText.substring(
            (argLoc.col - 1).coerceIn(0, lineText.length),
            (argLoc.col - 1 + argLoc.len).coerceIn(0, lineText.length),
        )
        val isHex = tokenText.startsWith("0x", ignoreCase = true)

        return FloorDesignationStatus.EditableBbDesignate(
            mapId = mapId,
            currentVariant = variation,
            lineNo = argLoc.lineNo,
            argCol = argLoc.col,
            argLen = argLoc.len,
            isHex = isHex,
        )
    }

    private fun computeRegisterDesignateStatus(
        snapshot: FloorDesignationSnapshot,
        inst: Instruction,
        floorId: Int,
        mapCount: Int,
    ): FloorDesignationStatus {
        val cfg = snapshot.cfg ?: return FloorDesignationStatus.ReadOnly("Missing CFG.")
        val instLoc = inst.srcLoc?.mnemonic
            ?: return FloorDesignationStatus.ReadOnly("Could not locate ${inst.opcode.mnemonic} in script.")
        val lineNo = instLoc.lineNo

        if (inst !in snapshot.reachableInstructions) {
            return FloorDesignationStatus.ReadOnly(
                "This floor's ${inst.opcode.mnemonic} is unreachable dead code.",
                jumpLineNo = lineNo,
            )
        }

        val baseReg = (inst.args.firstOrNull() as? IntArg)?.value
            ?: return FloorDesignationStatus.ReadOnly(
                "Could not read base register for ${inst.opcode.mnemonic}.",
                jumpLineNo = lineNo,
            )
        val isEx = inst.opcode == OP_MAP_DESIGNATE_EX
        val typeReg = baseReg + if (isEx) 2 else 1
        val variationReg = baseReg + if (isEx) 3 else 2

        if (typeReg !in 0..255 || variationReg !in 0..255) {
            return FloorDesignationStatus.ReadOnly(
                "Register range for ${inst.opcode.mnemonic} r$baseReg is out of bounds.",
                jumpLineNo = lineNo,
            )
        }

        val typeVals = getRegisterValue(cfg, inst, typeReg)
        val type = if (typeVals.size == 1L) typeVals[0] else null
        if (type == null || (type != 0 && type != 2)) {
            return FloorDesignationStatus.ReadOnly(
                "Designation type ${type ?: "dynamic"} ignores map variation.",
                jumpLineNo = lineNo,
            )
        }

        val mapId = if (isEx) {
            val mapReg = baseReg + 1
            if (mapReg !in 0..255) {
                return FloorDesignationStatus.ReadOnly("Map register r$mapReg is out of bounds.", jumpLineNo = lineNo)
            }
            val mapVals = getRegisterValue(cfg, inst, mapReg)
            if (mapVals.size == 1L) mapVals[0] else null
        } else {
            floorId
        }
        if (mapId == null || mapId !in 0 until mapCount) {
            return FloorDesignationStatus.ReadOnly(
                "Could not statically determine a valid map ID for ${inst.opcode.mnemonic}.",
                jumpLineNo = lineNo,
            )
        }

        val variationVals = getRegisterValue(cfg, inst, variationReg)
        val currentVariant = if (variationVals.size == 1L) variationVals[0] else null
        if (currentVariant == null) {
            return FloorDesignationStatus.ReadOnly(
                "Map variation register r$variationReg is dynamically computed.",
                jumpLineNo = lineNo,
            )
        }

        val liveAfter = isRegisterLiveAfter(cfg, inst, variationReg)
        val strategy = if (!liveAfter) {
            findLocalImmediateDefStrategy(snapshot.lines, cfg, inst, variationReg)
                ?: RegisterEditStrategy.WrapDesignateLine(
                    designateLineNo = lineNo,
                    instCol = instLoc.col,
                    restoreOldValue = false,
                )
        } else {
            RegisterEditStrategy.WrapDesignateLine(
                designateLineNo = lineNo,
                instCol = instLoc.col,
                restoreOldValue = true,
            )
        }

        return FloorDesignationStatus.EditableRegisterDesignate(
            mapId = mapId,
            currentVariant = currentVariant,
            designateLineNo = lineNo,
            variationReg = variationReg,
            strategy = strategy,
        )
    }

    /**
     * Looks backward in [inst]'s basic block for an exclusive immediate write to [variationReg]
     * (`leti`, `letb`, `letw`, `clear`, `set`) with no intervening reads of [variationReg] or
     * `sync` barriers.
     */
    private fun findLocalImmediateDefStrategy(
        lines: List<String>,
        cfg: ControlFlowGraph,
        inst: Instruction,
        variationReg: Int,
    ): RegisterEditStrategy? {
        val block = cfg.getBlockForInstruction(inst)
        val instIdx = block.indexOfInstruction(inst)
        for (i in instIdx - 1 downTo block.start) {
            val cur = block.segment.instructions[i]
            if (cur.opcode == OP_SYNC) return null
            if (instructionReadsRegister(cur, variationReg)) return null

            when (cur.opcode.code) {
                OP_LETI.code,
                OP_LETB_V3_V4.code,
                OP_LETW.code -> {
                    if (cur.args.size >= 2 && (cur.args[0] as? IntArg)?.value == variationReg) {
                        val argLoc = cur.srcLoc?.args?.getOrNull(1)?.precise ?: return null
                        val lineText = lines.getOrNull(argLoc.lineNo - 1).orEmpty()
                        val tokenText = lineText.substring(
                            (argLoc.col - 1).coerceIn(0, lineText.length),
                            (argLoc.col - 1 + argLoc.len).coerceIn(0, lineText.length),
                        )
                        return RegisterEditStrategy.ReplaceImmediate(
                            lineNo = argLoc.lineNo,
                            argCol = argLoc.col,
                            argLen = argLoc.len,
                            isHex = tokenText.startsWith("0x", ignoreCase = true),
                        )
                    }
                }

                OP_CLEAR.code,
                OP_SET.code -> {
                    if (cur.args.isNotEmpty() && (cur.args[0] as? IntArg)?.value == variationReg) {
                        val mnemLoc = cur.srcLoc?.mnemonic ?: return null
                        val regLoc = cur.srcLoc?.args?.getOrNull(0)?.precise ?: return null
                        if (mnemLoc.lineNo != regLoc.lineNo) return null
                        return RegisterEditStrategy.ReplaceClearOrSet(
                            lineNo = mnemLoc.lineNo,
                            startCol = mnemLoc.col,
                            endCol = regLoc.col + regLoc.len,
                        )
                    }
                }

                else -> {
                    if (instructionWritesRegister(cur, variationReg)) {
                        return null
                    }
                }
            }
        }
        return null
    }

    /**
     * Conservative forward liveness check: returns `true` if [register] might be read after [inst]
     * (including across `sync` barriers or callees/callers) before being unconditionally
     * overwritten.
     */
    private fun isRegisterLiveAfter(
        cfg: ControlFlowGraph,
        inst: Instruction,
        register: Int,
    ): Boolean {
        val sequentialNext = mutableMapOf<BasicBlock, BasicBlock>()
        for (i in 0 until cfg.blocks.lastIndex) {
            sequentialNext[cfg.blocks[i]] = cfg.blocks[i + 1]
        }

        val startBlock = cfg.getBlockForInstruction(inst)
        val startIdx = startBlock.indexOfInstruction(inst) + 1
        val visited = mutableSetOf<BasicBlock>()
        val queue = ArrayDeque<Pair<BasicBlock, Int>>()
        queue.addLast(Pair(startBlock, startIdx))

        while (queue.isNotEmpty()) {
            val (block, fromIdx) = queue.removeFirst()
            var killed = false
            for (i in fromIdx until block.end) {
                val cur = block.segment.instructions[i]
                if (cur.opcode == OP_SYNC) return true
                if (instructionReadsRegister(cur, register)) return true
                if (instructionUnconditionallyOverwritesRegister(cur, register)) {
                    killed = true
                    break
                }
            }
            if (!killed) {
                for (succ in block.to) {
                    if (visited.add(succ)) {
                        queue.addLast(Pair(succ, succ.start))
                    }
                }
                // switch_call with an out-of-range index invokes zero callees and falls straight
                // through to the next block without executing any callee that might overwrite reg.
                val terminator = if (block.end > block.start) block.segment.instructions[block.end - 1] else null
                if (block.branchType == BranchType.Call &&
                    (terminator?.opcode?.code == OP_SWITCH_CALL.code || block.to.isEmpty())
                ) {
                    sequentialNext[block]?.let { next ->
                        if (visited.add(next)) {
                            queue.addLast(Pair(next, next.start))
                        }
                    }
                }
            }
        }
        return false
    }

    private fun instructionReadsRegister(inst: Instruction, register: Int): Boolean {
        val args = inst.args
        when (inst.opcode.code) {
            OP_LETI.code,
            OP_LETB_V3_V4.code,
            OP_LETW.code,
            OP_SET.code,
            OP_CLEAR.code -> return false

            OP_LET.code -> return (args.getOrNull(1) as? IntArg)?.value == register

            OP_STACK_PUSHM_V3_V4.code -> {
                val minReg = (args.getOrNull(0) as? IntArg)?.value ?: return true
                val count = (args.getOrNull(1) as? IntArg)?.value ?: return true
                return register in minReg until (minReg + count)
            }

            OP_STACK_POPM_V3_V4.code -> return false

            OP_VA_CALL_V3_V4.code -> return register in 1..7
        }

        val params = inst.opcode.params
        val argLen = min(args.size, params.size)
        for (j in 0 until argLen) {
            val param = params[j]
            when (val paramType = param.type) {
                is RegType -> {
                    val regRef = (args[j] as? IntArg)?.value ?: continue
                    val regParams = paramType.registers
                    if (regParams != null) {
                        for ((k, regParam) in regParams.withIndex()) {
                            if (regParam.read && regRef + k == register) {
                                return true
                            }
                        }
                    } else if (regRef == register) {
                        // Conservative: single register param without explicit read/write breakdown.
                        return true
                    }
                }

                is RegVarType -> {
                    for (k in j until args.size) {
                        if ((args[k] as? IntArg)?.value == register) {
                            return true
                        }
                    }
                }

                else -> {}
            }
        }
        // Stack-passed opcodes (e.g. `set_floor_handler r63, 200`) declare ValueType params
        // while still accepting a register reference operand (`IntArg.isRegRef == true`).
        for (j in args.indices) {
            val arg = args[j] as? IntArg ?: continue
            if (arg.isRegRef && arg.value == register) {
                val paramType = params.getOrNull(j)?.type
                if (paramType !is RegType && paramType !is RegVarType) {
                    return true
                }
            }
        }
        return false
    }

    private fun instructionWritesRegister(inst: Instruction, register: Int): Boolean {
        val args = inst.args
        when (inst.opcode.code) {
            OP_LETI.code,
            OP_LETB_V3_V4.code,
            OP_LETW.code,
            OP_SET.code,
            OP_CLEAR.code,
            OP_LET.code,
            OP_REV.code,
            OP_ADDI.code,
            OP_SUBI.code,
            OP_MULI.code,
            OP_DIVI.code,
            OP_SYNC_REGISTER_V3_V4.code -> return (args.getOrNull(0) as? IntArg)?.value == register

            OP_STACK_POPM_V3_V4.code -> {
                val minReg = (args.getOrNull(0) as? IntArg)?.value ?: return true
                val count = (args.getOrNull(1) as? IntArg)?.value ?: return true
                return register in minReg until (minReg + count)
            }
        }
        val params = inst.opcode.params
        val argLen = min(args.size, params.size)
        for (j in 0 until argLen) {
            val param = params[j]
            when (val paramType = param.type) {
                is RegType -> {
                    val regRef = (args[j] as? IntArg)?.value ?: continue
                    val regParams = paramType.registers
                    if (regParams != null) {
                        for ((k, regParam) in regParams.withIndex()) {
                            if (regParam.write && regRef + k == register) {
                                return true
                            }
                        }
                    } else if (regRef == register) {
                        return true
                    }
                }

                is RegVarType -> {
                    if (param.write) {
                        for (k in j until args.size) {
                            if ((args[k] as? IntArg)?.value == register) {
                                return true
                            }
                        }
                    }
                }

                else -> {}
            }
        }
        return false
    }

    private fun instructionUnconditionallyOverwritesRegister(inst: Instruction, register: Int): Boolean {
        val args = inst.args
        return when (inst.opcode.code) {
            OP_LETI.code,
            OP_LETB_V3_V4.code,
            OP_LETW.code,
            OP_SET.code,
            OP_CLEAR.code,
            OP_LET.code -> (args.getOrNull(0) as? IntArg)?.value == register

            OP_SYNC_REGISTER_V3_V4.code ->
                args.size >= 2 && (args[0] as? IntArg)?.value == register

            else -> false
        }
    }

    /**
     * Finds [count] consecutive registers in `r8..r249` that are never referenced anywhere in
     * [segments], preferring `r60..r249` to match typical PSO quest conventions.
     */
    private fun findFreeScratchRegisters(segments: List<InstructionSegment>, count: Int): Int? {
        val used = mutableSetOf<Int>()
        for (segment in segments) {
            for (inst in segment.instructions) {
                val args = inst.args
                if (inst.opcode == OP_STACK_PUSHM_V3_V4 || inst.opcode == OP_STACK_POPM_V3_V4) {
                    val base = (args.getOrNull(0) as? IntArg)?.value
                    val len = (args.getOrNull(1) as? IntArg)?.value
                    if (base != null && len != null) {
                        for (r in base until (base + len).coerceAtMost(256)) {
                            used.add(r)
                        }
                    }
                    continue
                }
                val params = inst.opcode.params
                val argLen = min(args.size, params.size)
                for (j in 0 until argLen) {
                    when (val paramType = params[j].type) {
                        is RegType -> {
                            val regRef = (args[j] as? IntArg)?.value ?: continue
                            val regParams = paramType.registers
                            if (regParams != null) {
                                for (k in regParams.indices) {
                                    used.add(regRef + k)
                                }
                            } else {
                                used.add(regRef)
                            }
                        }

                        is RegVarType -> {
                            for (k in j until args.size) {
                                (args[k] as? IntArg)?.value?.let(used::add)
                            }
                        }

                        else -> {}
                    }
                }
                for (arg in args) {
                    if (arg is IntArg && arg.isRegRef) {
                        used.add(arg.value)
                    }
                }
            }
        }

        fun scanRange(start: Int, endInclusive: Int): Int? {
            var base = start
            while (base + count - 1 <= endInclusive) {
                if ((0 until count).all { (base + it) !in used }) {
                    return base
                }
                base++
            }
            return null
        }

        return scanRange(60, 249) ?: scanRange(8, 59)
    }

    fun prepareSetFloorVariantEdit(
        snapshot: FloorDesignationSnapshot,
        floorId: Int,
        expectedMapId: Int?,
        newVariant: Int,
        preferHex: Boolean,
    ): PrepareFloorVariantResult {
        val status = snapshot.statusForFloor(floorId)
        if (status is FloorDesignationStatus.ReadOnly) {
            return PrepareFloorVariantResult.Rejected(status.reason, status.jumpLineNo)
        }

        val effectiveMapId = when (status) {
            is FloorDesignationStatus.EditableBbDesignate -> status.mapId
            is FloorDesignationStatus.EditableRegisterDesignate -> status.mapId
            is FloorDesignationStatus.EditableDefault -> status.mapId
            is FloorDesignationStatus.ReadOnly ->
                return PrepareFloorVariantResult.Rejected(status.reason, status.jumpLineNo)
        }

        if (expectedMapId != null && effectiveMapId != expectedMapId) {
            return PrepareFloorVariantResult.Rejected(
                "Floor $floorId's map changed in the script; reopen the menu and try again.",
            )
        }

        val area = findAreaByMapId(effectiveMapId)
        if (area == null || area.bossArea || area.name == "Lobby") {
            return PrepareFloorVariantResult.Rejected("This map does not support variant selection.")
        }
        if (newVariant !in 0 until area.areaVariants.size) {
            return PrepareFloorVariantResult.Rejected(
                "Variant $newVariant is out of range for ${area.name}.",
            )
        }

        val edit: AsmTextEdit = when (status) {
            is FloorDesignationStatus.EditableDefault -> {
                if (newVariant == 0) {
                    return PrepareFloorVariantResult.NoOp(snapshot.scriptEpisode)
                }
                val label0Segment = snapshot.ir?.instructionSegments()?.find { 0 in it.labels }
                    ?: return PrepareFloorVariantResult.Rejected("Script has no label 0 entry point.")
                val instructionLines = if (status.scratchBaseReg == null) {
                    listOf(
                        formatBbMapDesignate(
                            floorId = floorId,
                            mapId = effectiveMapId,
                            type = 0,
                            mapVariation = newVariant,
                            objectSetVariation = 0,
                            hex = preferHex,
                        )
                    )
                } else if (status.useLegacyMapDesignate) {
                    val r = status.scratchBaseReg
                    listOf(
                        "leti r$r, ${formatInt(floorId, preferHex)}",
                        "leti r${r + 1}, ${formatInt(0, preferHex)}",
                        "leti r${r + 2}, ${formatInt(newVariant, preferHex)}",
                        "map_designate r$r",
                    )
                } else {
                    val r = status.scratchBaseReg
                    listOf(
                        "leti r$r, ${formatInt(floorId, preferHex)}",
                        "leti r${r + 1}, ${formatInt(effectiveMapId, preferHex)}",
                        "leti r${r + 2}, ${formatInt(0, preferHex)}",
                        "leti r${r + 3}, ${formatInt(newVariant, preferHex)}",
                        "map_designate_ex r$r",
                    )
                }
                findLabel0InsertionEdit(snapshot.lines, label0Segment, instructionLines)
                    ?: return PrepareFloorVariantResult.Rejected(
                        "Could not find a safe insertion point in label 0.",
                        jumpLineNo = findLabel0Line(snapshot.lines),
                    )
            }

            is FloorDesignationStatus.EditableBbDesignate -> {
                if (status.currentVariant == newVariant) {
                    return PrepareFloorVariantResult.NoOp(snapshot.scriptEpisode)
                }
                val replacement = formatInt(newVariant, status.isHex)
                AsmTextEdit(
                    startLineNumber = status.lineNo,
                    startColumn = status.argCol,
                    endLineNumber = status.lineNo,
                    endColumn = status.argCol + status.argLen,
                    text = replacement,
                )
            }

            is FloorDesignationStatus.EditableRegisterDesignate -> {
                if (status.currentVariant == newVariant) {
                    return PrepareFloorVariantResult.NoOp(snapshot.scriptEpisode)
                }
                when (val strategy = status.strategy) {
                    is RegisterEditStrategy.ReplaceImmediate -> AsmTextEdit(
                        startLineNumber = strategy.lineNo,
                        startColumn = strategy.argCol,
                        endLineNumber = strategy.lineNo,
                        endColumn = strategy.argCol + strategy.argLen,
                        text = formatInt(newVariant, strategy.isHex),
                    )

                    is RegisterEditStrategy.ReplaceClearOrSet -> AsmTextEdit(
                        startLineNumber = strategy.lineNo,
                        startColumn = strategy.startCol,
                        endLineNumber = strategy.lineNo,
                        endColumn = strategy.endCol,
                        text = "leti r${status.variationReg}, ${formatInt(newVariant, preferHex)}",
                    )

                    is RegisterEditStrategy.WrapDesignateLine -> {
                        val lineText = snapshot.lines.getOrNull(strategy.designateLineNo - 1)
                            ?: return PrepareFloorVariantResult.Rejected(
                                "Could not locate map_designate line in script.",
                                jumpLineNo = strategy.designateLineNo,
                            )
                        val beforeInst = lineText.substring(0, (strategy.instCol - 1).coerceIn(0, lineText.length))
                        val hasInlinePrefix = beforeInst.any { it != ' ' && it != '\t' }
                        val indent = if (hasInlinePrefix) "    " else extractIndent(lineText, "    ")
                        val setNew = "leti r${status.variationReg}, ${formatInt(newVariant, preferHex)}"
                        val restoreOld = "leti r${status.variationReg}, ${formatInt(status.currentVariant, preferHex)}"
                        val wrappedText = if (hasInlinePrefix) {
                            val suffix = lineText.substring((strategy.instCol - 1).coerceIn(0, lineText.length))
                            buildString {
                                append(beforeInst.trimEnd())
                                append("\n")
                                append(indent).append(setNew).append("\n")
                                append(indent).append(suffix)
                                if (strategy.restoreOldValue) {
                                    append("\n").append(indent).append(restoreOld)
                                }
                            }
                        } else {
                            buildString {
                                append(indent).append(setNew).append("\n")
                                append(lineText)
                                if (strategy.restoreOldValue) {
                                    append("\n").append(indent).append(restoreOld)
                                }
                            }
                        }
                        AsmTextEdit(
                            startLineNumber = strategy.designateLineNo,
                            startColumn = 1,
                            endLineNumber = strategy.designateLineNo,
                            endColumn = lineText.length + 1,
                            text = wrappedText,
                        )
                    }
                }
            }

            is FloorDesignationStatus.ReadOnly ->
                return PrepareFloorVariantResult.Rejected(status.reason, status.jumpLineNo)
        }

        // Full candidate pre-validation before mutating Monaco.
        val candidateLines = applyTextEdit(snapshot.lines, edit)
        val candidateAsm = assemble(candidateLines, snapshot.version)
        if (candidateAsm !is Success || candidateAsm.problems.any { it.severity == Severity.Error }) {
            return PrepareFloorVariantResult.Rejected(
                "Applying this change would produce invalid assembly.",
            )
        }

        val candidateIr = candidateAsm.value
        val candidateSegments = candidateIr.instructionSegments()
        val candidateCfg = ControlFlowGraph.create(candidateIr)
        val candidateReachable = getReachableScriptInstructions(
            candidateCfg,
            candidateSegments,
            snapshot.datEntryFloorIds,
        )
        val candidateTargetDesignations = candidateSegments
            .flatMap { it.instructions }
            .filter { inst ->
                when (inst.opcode) {
                    OP_BB_MAP_DESIGNATE ->
                        (inst.args.getOrNull(0) as? IntArg)?.value == floorId

                    OP_MAP_DESIGNATE,
                    OP_MAP_DESIGNATE_EX -> {
                        val baseReg = (inst.args.firstOrNull() as? IntArg)?.value
                        if (baseReg != null && baseReg in 0..255) {
                            val vals = getRegisterValue(candidateCfg, inst, baseReg)
                            vals.size == 1L && vals[0] == floorId
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
        if (candidateTargetDesignations.size != 1 || candidateTargetDesignations[0] !in candidateReachable) {
            return PrepareFloorVariantResult.Rejected(
                "Resulting map designation would not be reachable at runtime.",
                jumpLineNo = candidateTargetDesignations.firstOrNull()?.srcLoc?.mnemonic?.lineNo,
            )
        }

        val candidateMappings = getFloorMappings(
            instructionSegments = candidateSegments,
            usedFloorIds = snapshot.usedFloorIds,
            version = snapshot.version,
            datEntryFloorIds = snapshot.datEntryFloorIds,
        ) { candidateCfg }

        val candidateMapping = candidateMappings.find { it.floorId == floorId }
        if (candidateMapping == null ||
            candidateMapping.mapId != effectiveMapId ||
            candidateMapping.mapVariation != newVariant
        ) {
            return PrepareFloorVariantResult.Rejected(
                "Script control flow prevents the selected map variation from taking effect.",
                jumpLineNo = candidateTargetDesignations[0].srcLoc?.mnemonic?.lineNo,
            )
        }
        if (candidateMapping.runtimeAmbiguous) {
            return PrepareFloorVariantResult.Rejected(
                "Changing this floor would introduce a conditional branch ambiguity.",
                jumpLineNo = candidateTargetDesignations[0].srcLoc?.mnemonic?.lineNo,
            )
        }

        // Verify no other floor's resolved mapping was accidentally altered by shared registers.
        val otherBefore = snapshot.floorMappings.filter { it.floorId != floorId }
        val otherAfter = candidateMappings.filter { it.floorId != floorId }
        if (otherBefore != otherAfter) {
            return PrepareFloorVariantResult.Rejected(
                "Changing this floor would unexpectedly affect another floor's map designation.",
                jumpLineNo = candidateTargetDesignations[0].srcLoc?.mnemonic?.lineNo,
            )
        }

        val candidateEpisode = if (candidateSegments.any { 0 in it.labels }) {
            getScriptEpisode(candidateSegments)
        } else {
            snapshot.scriptEpisode
        }

        return PrepareFloorVariantResult.Ready(
            edit = edit,
            candidateIr = candidateIr,
            candidateMappings = candidateMappings,
            scriptEpisode = candidateEpisode,
        )
    }

    internal fun applyTextEdit(lines: List<String>, edit: AsmTextEdit): List<String> {
        val mutable = lines.toMutableList()
        if (mutable.isEmpty()) mutable.add("")
        val startIdx = (edit.startLineNumber - 1).coerceIn(0, mutable.lastIndex)
        val endIdx = (edit.endLineNumber - 1).coerceIn(startIdx, mutable.lastIndex)
        val startLine = mutable[startIdx]
        val endLine = mutable[endIdx]
        val prefix = startLine.substring(0, (edit.startColumn - 1).coerceIn(0, startLine.length))
        val suffix = endLine.substring((edit.endColumn - 1).coerceIn(0, endLine.length))
        val replacementLines = (prefix + edit.text + suffix).split("\n")
        for (i in endIdx downTo startIdx) {
            mutable.removeAt(i)
        }
        mutable.addAll(startIdx, replacementLines)
        return mutable
    }

    private fun findLabel0InsertionEdit(
        lines: List<String>,
        label0Segment: InstructionSegment,
        instructionLines: List<String>,
    ): AsmTextEdit? {
        if (instructionLines.isEmpty()) return null
        val label0LineNo = findLabel0Line(lines) ?: return null
        val straightLinePrefix = mutableListOf<Instruction>()
        for (inst in label0Segment.instructions) {
            if (isControlFlowBarrier(inst.opcode)) break
            straightLinePrefix.add(inst)
        }

        // Priority 1: immediately after the last map designation in 0:'s straight-line prefix.
        val anchor = straightLinePrefix.lastOrNull {
            it.opcode == OP_BB_MAP_DESIGNATE ||
                it.opcode == OP_MAP_DESIGNATE ||
                it.opcode == OP_MAP_DESIGNATE_EX
        } ?: straightLinePrefix.lastOrNull { it.opcode == OP_SET_EPISODE_V3_V4 }

        if (anchor != null) {
            val anchorLineNo = anchor.srcLoc?.mnemonic?.lineNo ?: return null
            val anchorLine = lines.getOrNull(anchorLineNo - 1) ?: return null
            val indent = extractIndent(anchorLine, defaultIndent = "    ")
            val block = instructionLines.joinToString("\n") { "$indent$it" }
            return AsmTextEdit(
                startLineNumber = anchorLineNo,
                startColumn = anchorLine.length + 1,
                endLineNumber = anchorLineNo,
                endColumn = anchorLine.length + 1,
                text = "\n$block",
            )
        }

        // Priority 3: before the first instruction of 0:, handling inline instructions like `0: ret`.
        val firstInst = label0Segment.instructions.firstOrNull()
        val firstInstLoc = firstInst?.srcLoc?.mnemonic
        val label0Line = lines.getOrNull(label0LineNo - 1) ?: return null

        if (firstInstLoc != null && firstInstLoc.lineNo == label0LineNo) {
            val col = (firstInstLoc.col - 1).coerceIn(0, label0Line.length)
            val block = instructionLines.joinToString("\n") { "    $it" }
            return AsmTextEdit(
                startLineNumber = label0LineNo,
                startColumn = col + 1,
                endLineNumber = label0LineNo,
                endColumn = col + 1,
                text = "\n$block\n    ",
            )
        }

        val indent = firstInstLoc?.lineNo?.let { lines.getOrNull(it - 1) }
            ?.let { extractIndent(it, defaultIndent = "    ") }
            ?: "    "
        val block = instructionLines.joinToString("\n") { "$indent$it" }
        return AsmTextEdit(
            startLineNumber = label0LineNo,
            startColumn = label0Line.length + 1,
            endLineNumber = label0LineNo,
            endColumn = label0Line.length + 1,
            text = "\n$block",
        )
    }

    private fun isControlFlowBarrier(opcode: Opcode): Boolean =
        opcode == OP_RET ||
            opcode == OP_SYNC ||
            opcode == OP_CALL ||
            opcode == OP_JMP ||
            opcode == OP_SWITCH_CALL ||
            opcode == OP_SWITCH_JMP ||
            opcode.mnemonic.startsWith("jmp")

    private fun findLabel0Line(lines: List<String>): Int? {
        val index = lines.indexOfFirst { line ->
            val trimmed = line.trimStart()
            trimmed == "0:" ||
                trimmed.startsWith("0: ") ||
                trimmed.startsWith("0:\t") ||
                trimmed.startsWith("0://")
        }
        return index.takeIf { it >= 0 }?.plus(1)
    }

    private fun extractIndent(line: String, defaultIndent: String): String {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        return indent.ifEmpty { defaultIndent }
    }

    private fun formatBbMapDesignate(
        floorId: Int,
        mapId: Int,
        type: Int,
        mapVariation: Int,
        objectSetVariation: Int,
        hex: Boolean,
    ): String =
        "bb_map_designate ${formatInt(floorId, hex)}, ${formatInt(mapId, hex)}, " +
            "${formatInt(type, hex)}, ${formatInt(mapVariation, hex)}, ${formatInt(objectSetVariation, hex)}"

    private fun formatInt(value: Int, hex: Boolean): String =
        if (hex) "0x${value.toString(16).uppercase()}" else value.toString()
}
