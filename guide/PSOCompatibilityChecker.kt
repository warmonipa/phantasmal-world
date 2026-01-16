package com.pso.quest.compatibility

/**
 * PSO Quest 兼容性检查器 - Kotlin实现
 *
 * 基于PSO Quest Editor的兼容性检查逻辑
 * 用于验证任务文件是否与不同PSO版本兼容
 */

// ============================================================================
// 1. 枚举和常量定义
// ============================================================================

/**
 * PSO版本枚举
 */
enum class PSOVersion(val verId: Int, val displayName: String) {
    DC_V1(0, "Phantasy Star Online DC V1"),
    DC_V2(1, "Phantasy Star Online DC V2"),
    PC(2, "Phantasy Star Online PC"),
    GC_EP12(3, "Phantasy Star Online GC Ep1&2"),
    BLUE_BURST(4, "Phantasy Star Online Blue Burst");

    companion object {
        fun fromId(id: Int) = values().firstOrNull { it.verId == id }
    }
}

/**
 * 参数类型常量
 */
object ArgType {
    const val T_NONE = 0        // 无参数
    const val T_IMED = 1        // 立即数
    const val T_ARGS = 2        // 参数模式
    const val T_PUSH = 3        // 压栈
    const val T_VASTART = 4     // 可变参数开始
    const val T_VAEND = 5       // 可变参数结束
    const val T_DC = 6          // DC专用

    const val T_REG = 7         // 寄存器
    const val T_BYTE = 8        // 字节值
    const val T_WORD = 9        // 字值
    const val T_DWORD = 10      // 双字值
    const val T_FLOAT = 11      // 浮点数
    const val T_STR = 12        // 字符串

    const val T_RREG = 13       // 寄存器引用
    const val T_FUNC = 14       // 函数标签
    const val T_FUNC2 = 15      // 函数标签2
    const val T_SWITCH = 16     // Switch语句
    const val T_SWITCH2B = 17   // Switch 2字节
    const val T_PFLAG = 18      // 标志

    const val T_STRDATA = 19    // 字符串数据
    const val T_DATA = 20       // 数据标签
    const val T_HEX = 21        // 十六进制数据
    const val T_STRHEX = 22     // 十六进制字符串
}

/**
 * 特殊操作码常量
 */
object OpcodeConstants {
    // 转换指令 (可能在V1中有问题)
    val CONVERT_OPCODES = setOf(
        0x66, 0x6D, 0x79, 0x7C, 0x7D, 0x7F,
        0x84, 0x87, 0xA8, 0xC0, 0xCD, 0xCE
    )

    const val SET_EPISODE = 0xF8BC      // set_episode
    const val SPECIAL_WARNING = 0xF8EE  // 可能导致问题的操作码
    const val SKIP_VERSION_1 = 0xD9     // 跳过版本检查1
    const val SKIP_VERSION_2 = 0xEF     // 跳过版本检查2

    const val GET_NPC_DATA = 0xF841         // get_npc_data
    const val GET_PHYSICAL_DATA = 0xF892    // get_physical_data
    const val GET_RESIST_DATA = 0xF894      // get_resist_data
    const val GET_ATTACK_DATA = 0xF893      // get_attack_data
    const val GET_MOVEMENT_DATA = 0xF895    // get_movement_data
}

/**
 * 默认标签常量
 */
object DefaultLabels {
    // Episode 1/4 默认标签
    val EP1_BASE = listOf(
        100, 90, 120, 130, 80, 70, 60, 140, 110, 30, 50, 1, 20  // 0-12
    )
    val EP1_V3_EXTRA = listOf(
        850, 800, 830, 820, 810, 860, 870, 840, 880  // 13-21
    )

    // Episode 2 默认标签
    val EP2_BASE = listOf(
        720, 660, 620, 600, 501, 520, 560, 540, 580, 680  // 0-9
    )
    val EP2_V3_EXTRA = listOf(
        950, 900, 930, 920, 910, 960, 970, 940, 980  // 10-18
    )

    // 敌人ID列表 (58个)
    val ENEMY_IDS = setOf(
        68, 67, 64, 65, 128, 129, 131, 133, 163, 97, 99, 98, 96,
        168, 166, 165, 160, 162, 164, 192, 197, 193, 194, 200,
        66, 132, 130, 100, 101, 161, 167, 223, 213, 212, 215,
        217, 218, 214, 222, 221, 225, 224, 216, 219, 220, 202,
        201, 203, 204, 273, 277, 276, 272, 278, 274, 275, 281, 249
    )
}

// ============================================================================
// 2. 数据结构定义
// ============================================================================

/**
 * 兼容性检查结果
 */
data class CompatibilityResult(
    val errors: MutableList<String> = mutableListOf(),
    val warnings: MutableList<String> = mutableListOf()
) {
    val isFullyCompatible: Boolean
        get() = errors.isEmpty() && warnings.isEmpty()

    fun addError(message: String) = errors.add(message)
    fun addWarning(message: String) = warnings.add(message)
}

/**
 * 操作码定义
 */
data class Opcode(
    val functionCode: Int,          // 操作码ID
    val name: String,               // 操作码名称
    val order: Int,                 // 指令类型
    val minVersion: Int,            // 最低支持版本
    val argTypes: List<Int>         // 参数类型列表
)

/**
 * 标签数据
 */
data class LabelData(
    val dataLabels: Set<String> = emptySet(),       // D_xxx
    val stringLabels: Set<String> = emptySet(),     // S_xxx
    val functionLabels: Set<String> = emptySet()    // F_xxx
) {
    fun contains(labelName: String): Boolean {
        return "D_$labelName" in dataLabels ||
               "S_$labelName" in stringLabels ||
               "F_$labelName" in functionLabels
    }
}

/**
 * 怪物数据
 */
data class Monster(
    val skin: Int,              // 外观ID
    val action: Float,          // 动作标签ID
    val unknow7: Int = 0        // 子类型 (Skin 51使用)
    // 可以添加其他字段：位置、旋转等
)

/**
 * 对象数据
 */
data class FloorObject(
    val skin: Int,              // 对象外观ID
    val action: Int = 0         // 动作标签ID
)

/**
 * Floor数据
 */
data class Floor(
    val floorId: Int,               // Floor ID (0-45)
    val monsters: List<Monster> = emptyList(),
    val objects: List<FloorObject> = emptyList()
)

/**
 * Quest数据容器
 */
data class QuestData(
    val scriptLines: List<String>,      // 脚本行
    val labels: LabelData,              // 标签数据
    val floors: List<Floor>,            // Floor数据
    val opcodes: List<Opcode>,          // 操作码定义
    val questFiles: List<String>,       // 任务文件列表
    val episode: Int                    // Episode (0=EP1, 1=EP2, 2=EP4)
)

// ============================================================================
// 3. 主检查类
// ============================================================================

/**
 * PSO兼容性检查器
 */
class PSOCompatibilityChecker(
    private val floorDataProvider: FloorDataProvider? = null
) {

    /**
     * 执行完整的兼容性检查
     */
    fun checkCompatibility(
        version: PSOVersion,
        questData: QuestData
    ): CompatibilityResult {
        val result = CompatibilityResult()

        // 1. 脚本检查
        checkScriptCompatibility(version, questData, result)

        // 2. 怪物检查
        checkMonsterCompatibility(version, questData, result)

        // 3. 对象检查
        checkObjectCompatibility(version, questData, result)

        // 4. 未使用数据标签检查
        checkUnusedDataLabels(questData, result)

        // 5. 文件检查
        checkQuestFiles(version, questData.questFiles, result)

        return result
    }

    // ========================================================================
    // 3.1 脚本兼容性检查
    // ========================================================================

    private fun checkScriptCompatibility(
        version: PSOVersion,
        questData: QuestData,
        result: CompatibilityResult
    ) {
        // 检查Label 0是否存在
        if (!questData.labels.contains("0")) {
            result.addError("Label 0 does not exist (quest entry point required)")
        }

        // 遍历所有脚本行
        questData.scriptLines.forEachIndexed { lineNum, line ->
            if (line.length < 9) return@forEachIndexed

            // 提取操作码和参数
            val (opcodeName, argsString) = line.extractOpcodeAndArgs()
            if (opcodeName.isEmpty()) return@forEachIndexed

            // 查找操作码定义
            val opcode = questData.opcodes.find {
                it.name.equals(opcodeName, ignoreCase = true)
            }

            if (opcode == null) {
                result.addError("Unknown opcode '$opcodeName' at line $lineNum")
                return@forEachIndexed
            }

            // 转换指令警告
            if (opcode.functionCode in OpcodeConstants.CONVERT_OPCODES) {
                if (version.verId < 2 && opcode.order != ArgType.T_DC) {
                    result.addWarning(
                        "Opcode '$opcodeName' may have conversion issues in V1 at line $lineNum"
                    )
                }
            }

            // Episode参数检查
            if (opcode.functionCode == OpcodeConstants.SET_EPISODE) {
                checkEpisodeParameter(version, argsString.trim(), lineNum, result)
            }

            // 版本检查
            if (opcode.functionCode != OpcodeConstants.SKIP_VERSION_1 &&
                opcode.functionCode != OpcodeConstants.SKIP_VERSION_2
            ) {
                if (opcode.minVersion > version.verId) {
                    result.addError(
                        "Opcode '$opcodeName' requires version ${opcode.minVersion} " +
                        "or higher at line $lineNum"
                    )
                }
            }

            // 特殊操作码警告
            if (opcode.functionCode == OpcodeConstants.SPECIAL_WARNING) {
                result.addWarning("Opcode 0xF8EE detected - may cause compatibility issues")
            }

            // 参数检查
            checkOpcodeArguments(opcode, argsString, questData.labels, lineNum, version, result)
        }
    }

    private fun checkEpisodeParameter(
        version: PSOVersion,
        param: String,
        lineNum: Int,
        result: CompatibilityResult
    ) {
        when {
            version.verId < 2 && param != "00000000" -> {
                result.addError(
                    "Episode parameter $param not supported in V1 at line $lineNum"
                )
            }
            version == PSOVersion.PC && param == "00000002" -> {
                result.addError(
                    "Episode 4 parameter not supported in PC version at line $lineNum"
                )
            }
        }
    }

    private fun checkOpcodeArguments(
        opcode: Opcode,
        argsString: String,
        labels: LabelData,
        lineNum: Int,
        version: PSOVersion,
        result: CompatibilityResult
    ) {
        var remainingArgs = argsString.trim()
        var argIndex = 0

        while (argIndex < opcode.argTypes.size) {
            val argType = opcode.argTypes[argIndex]

            // 终止条件
            if (argType in listOf(ArgType.T_NONE, ArgType.T_STR, ArgType.T_HEX, ArgType.T_STRDATA) ||
                remainingArgs.isEmpty()
            ) {
                break
            }

            // 提取当前参数
            val (currentArg, nextArgs) = remainingArgs.splitFirstArg()
            remainingArgs = nextArgs

            // V1版本参数类型检查
            if (opcode.minVersion < 2 && opcode.order == ArgType.T_ARGS && version.verId < 2) {
                when {
                    argType == ArgType.T_REG && !currentArg.startsWith("R") -> {
                        result.addError(
                            "Expected register but got '$currentArg' at line $lineNum"
                        )
                    }
                    argType == ArgType.T_DWORD && currentArg.startsWith("R") -> {
                        result.addError("Expected DWORD but got register at line $lineNum")
                    }
                }
            }

            // 标签引用检查
            if (argType in listOf(ArgType.T_FUNC, ArgType.T_FUNC2, ArgType.T_DATA)) {
                val labelName = currentArg.removeSuffix(":").trim()
                if (!labels.contains(labelName)) {
                    result.addWarning(
                        "Label '$labelName' not found (referenced at line $lineNum)"
                    )
                }
            }

            // Switch语句检查
            if (argType == ArgType.T_SWITCH) {
                checkSwitchStatement(currentArg, labels, lineNum, result)
            }

            argIndex++
        }

        // 字符串参数检查
        if (argIndex < opcode.argTypes.size) {
            val finalArgType = opcode.argTypes[argIndex]
            if (finalArgType in listOf(ArgType.T_STR, ArgType.T_STRDATA)) {
                checkStringArgument(remainingArgs, lineNum, result)
            }
        }
    }

    private fun checkSwitchStatement(
        switchArg: String,
        labels: LabelData,
        lineNum: Int,
        result: CompatibilityResult
    ) {
        val parts = switchArg.split(":")
        if (parts.isEmpty()) return

        val expectedCount = parts[0].toIntOrNull() ?: 0
        val labelList = parts.drop(1)
        val actualCount = labelList.size

        when {
            actualCount < expectedCount -> {
                result.addError(
                    "Switch array missing entries at line $lineNum " +
                    "(expected $expectedCount, got $actualCount)"
                )
            }
            actualCount > expectedCount -> {
                result.addError(
                    "Switch array has too many entries at line $lineNum " +
                    "(expected $expectedCount, got $actualCount)"
                )
            }
        }

        // 检查每个标签
        labelList.forEach { label ->
            val labelName = label.trim()
            if (labelName.isNotEmpty() && !labels.contains(labelName)) {
                result.addWarning(
                    "Switch label '$labelName' not found (at line $lineNum)"
                )
            }
        }
    }

    private fun checkStringArgument(
        strArg: String,
        lineNum: Int,
        result: CompatibilityResult
    ) {
        var openCount = 0
        strArg.forEach { char ->
            when (char) {
                '<' -> openCount++
                '>' -> openCount--
            }
        }

        if (openCount != 0) {
            result.addWarning("Unmatched string markers '<>' at line $lineNum")
        }
    }

    // ========================================================================
    // 3.2 怪物兼容性检查
    // ========================================================================

    private fun checkMonsterCompatibility(
        version: PSOVersion,
        questData: QuestData,
        result: CompatibilityResult
    ) {
        questData.floors.forEach { floor ->
            floor.monsters.forEachIndexed { monsterIdx, monster ->

                // NPC动作标签检查
                if (monster.skin !in DefaultLabels.ENEMY_IDS) {
                    checkNPCActionLabel(
                        monster, floor, monsterIdx,
                        version, questData.episode, questData.labels, result
                    )
                }

                // Skin 51特殊检查
                if (monster.skin == 51) {
                    checkSkin51(monster, floor, monsterIdx, version, questData.episode, result)
                }

                // Floor特定怪物检查
                if (floor.floorId < 50) {
                    checkFloorMonster(monster, floor, monsterIdx, version, result)
                }
            }

            // 数量限制
            if (floor.monsters.size > 400) {
                result.addWarning(
                    "Floor ${floor.floorId} has too many monsters " +
                    "(${floor.monsters.size} > 400)"
                )
            }
        }
    }

    private fun checkNPCActionLabel(
        monster: Monster,
        floor: Floor,
        monsterIdx: Int,
        version: PSOVersion,
        episode: Int,
        labels: LabelData,
        result: CompatibilityResult
    ) {
        val actionLabel = monster.action.toInt()
        if (actionLabel <= 0) return

        val isDefaultLabel = when (episode) {
            1 -> {
                // Episode 2
                val baseLabels = DefaultLabels.EP2_BASE
                val extraLabels = if (version.verId >= 3) DefaultLabels.EP2_V3_EXTRA else emptyList()
                actionLabel in (baseLabels + extraLabels)
            }
            else -> {
                // Episode 1/4
                val baseLabels = DefaultLabels.EP1_BASE
                val extraLabels = if (version.verId >= 3) DefaultLabels.EP1_V3_EXTRA else emptyList()
                actionLabel in (baseLabels + extraLabels)
            }
        }

        if (!isDefaultLabel && !labels.contains(actionLabel.toString())) {
            result.addWarning(
                "NPC action label $actionLabel not found " +
                "(monster #$monsterIdx on floor ${floor.floorId})"
            )
        }
    }

    private fun checkSkin51(
        monster: Monster,
        floor: Floor,
        monsterIdx: Int,
        version: PSOVersion,
        episode: Int,
        result: CompatibilityResult
    ) {
        when {
            version.verId < 2 -> {
                result.addWarning(
                    "Skin 51 not supported in V1 " +
                    "(monster #$monsterIdx on floor ${floor.floorId})"
                )
            }
            episode == 2 -> {
                result.addWarning(
                    "Skin 51 may not work properly in EP2 " +
                    "(monster #$monsterIdx on floor ${floor.floorId})"
                )
            }
            else -> {
                val subtype = monster.unknow7
                when {
                    subtype > 15 -> {
                        result.addError(
                            "Skin 51 invalid subtype " +
                            "(monster #$monsterIdx on floor ${floor.floorId})"
                        )
                    }
                    floorDataProvider != null &&
                    !floorDataProvider.isValidNPC51(floor.floorId, subtype) -> {
                        result.addError(
                            "Skin 51 subtype not valid for this floor " +
                            "(monster #$monsterIdx on floor ${floor.floorId})"
                        )
                    }
                }
            }
        }
    }

    private fun checkFloorMonster(
        monster: Monster,
        floor: Floor,
        monsterIdx: Int,
        version: PSOVersion,
        result: CompatibilityResult
    ) {
        val allowedMonsters = floorDataProvider?.getFloorMonsters(floor.floorId, version.verId)
        if (allowedMonsters != null && allowedMonsters.isNotEmpty()) {
            if (monster.skin !in allowedMonsters) {
                result.addWarning(
                    "Monster skin ${monster.skin} may not spawn correctly " +
                    "(monster #$monsterIdx on floor ${floor.floorId})"
                )
            }
        }
    }

    // ========================================================================
    // 3.3 对象兼容性检查
    // ========================================================================

    private fun checkObjectCompatibility(
        version: PSOVersion,
        questData: QuestData,
        result: CompatibilityResult
    ) {
        questData.floors.forEach { floor ->
            floor.objects.forEachIndexed { objIdx, obj ->
                if (floor.floorId < 50) {
                    val allowedObjects = floorDataProvider?.getFloorObjects(floor.floorId, version.verId)
                    if (allowedObjects != null && allowedObjects.isNotEmpty()) {
                        if (obj.skin !in allowedObjects) {
                            result.addWarning(
                                "Object skin ${obj.skin} may not work correctly " +
                                "(object #$objIdx on floor ${floor.floorId})"
                            )
                        }
                    }
                }
            }

            if (floor.objects.size > 400) {
                result.addWarning(
                    "Floor ${floor.floorId} has too many objects " +
                    "(${floor.objects.size} > 400)"
                )
            }
        }
    }

    // ========================================================================
    // 3.4 未使用数据标签检查
    // ========================================================================

    private fun checkUnusedDataLabels(
        questData: QuestData,
        result: CompatibilityResult
    ) {
        questData.labels.dataLabels.forEach { dataLabel ->
            if (dataLabel.startsWith("D_")) {
                val labelNum = dataLabel.substring(2).toIntOrNull() ?: return@forEach
                val refType = getReferenceType(labelNum, questData.scriptLines)

                if (refType == 0) {
                    result.addWarning("Data label $dataLabel is unused")
                }
            }
        }
    }

    private fun getReferenceType(labelNum: Int, scriptLines: List<String>): Int {
        val labelPrefix = "$labelNum:"

        scriptLines.forEach { line ->
            if (line.startsWith(labelPrefix)) {
                val content = line.substring(8).trim()
                when {
                    content.startsWith("STR:") -> return 4
                    content.startsWith("HEX:") -> return 2
                }
            }

            val content = line.substring(8).trim()
            when {
                content.contains("get_npc_data $labelNum") ||
                content.contains("0xF841 $labelNum") -> return 1

                content.contains("get_physical_data $labelNum") ||
                content.contains("0xF892 $labelNum") -> return 5

                content.contains("get_resist_data $labelNum") ||
                content.contains("0xF894 $labelNum") -> return 6

                content.contains("get_attack_data $labelNum") ||
                content.contains("0xF893 $labelNum") -> return 7

                content.contains("get_movement_data $labelNum") ||
                content.contains("0xF895 $labelNum") -> return 8
            }
        }

        return 0
    }

    // ========================================================================
    // 3.5 文件检查
    // ========================================================================

    private fun checkQuestFiles(
        version: PSOVersion,
        questFiles: List<String>,
        result: CompatibilityResult
    ) {
        val hasBinFile = questFiles.any { it.endsWith(".bin", ignoreCase = true) }
        if (!hasBinFile) {
            result.addError("Quest must contain a .bin file")
        }

        val hasDatFile = questFiles.any { it.endsWith(".dat", ignoreCase = true) }
        if (!hasDatFile) {
            result.addWarning("Quest does not contain a .dat file (recommended)")
        }

        val hasPvrFile = questFiles.any { it.endsWith(".pvr", ignoreCase = true) }
        if (version.verId > 1 && hasPvrFile) {
            result.addError(
                ".pvr texture files not supported in V2 or later (use .xvm/.prs format)"
            )
        }
    }
}

// ============================================================================
// 4. 扩展函数
// ============================================================================

/**
 * 从脚本行中提取操作码和参数
 */
private fun String.extractOpcodeAndArgs(): Pair<String, String> {
    if (length < 9) return "" to ""

    val codeLine = substring(8).trim()
    if (codeLine.isEmpty()) return "" to ""

    val spacePos = codeLine.indexOf(' ')
    return if (spacePos > 0) {
        codeLine.substring(0, spacePos) to codeLine.substring(spacePos + 1)
    } else {
        codeLine to ""
    }
}

/**
 * 分割第一个参数
 */
private fun String.splitFirstArg(): Pair<String, String> {
    val commaPos = indexOf(',')
    return if (commaPos > 0) {
        substring(0, commaPos).trim() to substring(commaPos + 1).trim()
    } else {
        trim() to ""
    }
}

// ============================================================================
// 5. Floor数据提供者接口
// ============================================================================

/**
 * Floor数据提供者接口
 * 用于查询Floor特定的怪物/对象列表和NPC51数据
 */
interface FloorDataProvider {
    /**
     * 获取Floor允许的怪物列表
     */
    fun getFloorMonsters(floorId: Int, version: Int): List<Int>?

    /**
     * 获取Floor允许的对象列表
     */
    fun getFloorObjects(floorId: Int, version: Int): List<Int>?

    /**
     * 验证NPC51子类型是否有效
     */
    fun isValidNPC51(floorId: Int, subtype: Int): Boolean
}

// ============================================================================
// 6. 辅助工具函数
// ============================================================================

/**
 * Episode检测
 */
fun detectEpisode(scriptLines: List<String>): Int {
    scriptLines.forEach { line ->
        if (line.length < 9) return@forEach
        val content = line.substring(8).trim()

        if (content.contains("set_episode") || content.contains("0xF8BC")) {
            return when {
                content.contains("00000000") -> 0  // Episode 1
                content.contains("00000001") -> 1  // Episode 2
                content.contains("00000002") -> 2  // Episode 4
                else -> 0
            }
        }
    }
    return 0  // 默认 Episode 1
}

/**
 * 解析标签
 */
fun parseLabels(scriptLines: List<String>): LabelData {
    val dataLabels = mutableSetOf<String>()
    val stringLabels = mutableSetOf<String>()
    val functionLabels = mutableSetOf<String>()

    scriptLines.forEach { line ->
        if (line.length < 9) return@forEach

        // 检查是否是标签定义行
        val colonPos = line.indexOf(':')
        if (colonPos > 0 && colonPos < 8) {
            val labelNum = line.substring(0, colonPos).trim()
            val content = line.substring(8).trim()

            when {
                content.startsWith("STR:") -> stringLabels.add("S_$labelNum")
                content.startsWith("HEX:") -> dataLabels.add("D_$labelNum")
                else -> functionLabels.add("F_$labelNum")
            }
        }
    }

    return LabelData(dataLabels, stringLabels, functionLabels)
}

// ============================================================================
// 7. 使用示例
// ============================================================================

/**
 * 使用示例
 */
fun main() {
    // 1. 加载任务数据
    val scriptLines = listOf(
        "0:      set_episode 00000001",
        "        leti R0, 00000001",
        "100:    HEX: 48656C6C6F"
        // ... 更多脚本行
    )

    // 2. 解析标签
    val labels = parseLabels(scriptLines)

    // 3. 创建Quest数据
    val questData = QuestData(
        scriptLines = scriptLines,
        labels = labels,
        floors = listOf(
            Floor(
                floorId = 0,
                monsters = listOf(Monster(skin = 68, action = 100f)),
                objects = listOf(FloorObject(skin = 192))
            )
        ),
        opcodes = emptyList(),  // 需要加载操作码定义
        questFiles = listOf("quest.bin", "quest.dat"),
        episode = detectEpisode(scriptLines)
    )

    // 4. 创建检查器
    val checker = PSOCompatibilityChecker()

    // 5. 对所有版本进行检查
    PSOVersion.values().forEach { version ->
        println("=" .repeat(50))
        println("Checking: ${version.displayName}")
        println("=" .repeat(50))

        val result = checker.checkCompatibility(version, questData)

        if (result.isFullyCompatible) {
            println("✓ Fully compatible")
        } else {
            if (result.errors.isNotEmpty()) {
                println("\nErrors:")
                result.errors.forEach { println("  ✗ $it") }
            }
            if (result.warnings.isNotEmpty()) {
                println("\nWarnings:")
                result.warnings.forEach { println("  ⚠ $it") }
            }
        }
        println()
    }
}