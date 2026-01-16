# PSO Quest 兼容性检查逻辑文档

## 概述
此文档详细描述PSO Quest Editor的兼容性检查算法，用于验证任务文件是否与不同PSO版本兼容。

---

## 1. 版本定义

```kotlin
enum class PSOVersion(val verId: Int, val displayName: String) {
    DC_V1(0, "Phantasy Star Online DC V1"),
    DC_V2(1, "Phantasy Star Online DC V2"),
    PC(2, "Phantasy Star Online PC"),
    GC_EP12(3, "Phantasy Star Online GC Ep1&2"),
    BLUE_BURST(4, "Phantasy Star Online Blue Burst")
}
```

---

## 2. 数据结构

### 2.1 操作码（Opcode）结构

```kotlin
data class AsmOpcode(
    val fnc: Int,           // 操作码ID (如 0x66, 0xF8BC)
    val name: String,       // 操作码名称 (如 "set_episode", "leti")
    val order: Int,         // 指令类型 (T_DC, T_ARGS, T_IMED等)
    val ver: Int,           // 最低支持版本 (0-3)
    val args: List<Int>     // 参数类型列表 (T_REG, T_DWORD, T_FUNC等)
)
```

### 2.2 参数类型常量

```kotlin
object ArgType {
    const val T_NONE = 0        // 无参数
    const val T_IMED = 1        // 立即数
    const val T_ARGS = 2        // 参数模式
    const val T_PUSH = 3        // 压栈
    const val T_VASTART = 4     // 可变参数开始
    const val T_VAEND = 5       // 可变参数结束
    const val T_DC = 6          // DC专用

    const val T_REG = 7         // 寄存器 (R0-R255)
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
```

### 2.3 怪物/对象数据

```kotlin
data class Monster(
    val skin: Int,          // 外观ID
    val action: Float,      // 动作标签ID
    val unknow7: Int,       // 未知字段7 (NPC Skin 51使用)
    // ... 其他字段
)

data class FloorObject(
    val skin: Int,          // 对象外观ID
    val action: Int,        // 动作标签ID
    // ... 其他字段
)

data class Floor(
    val floorId: Int,           // Floor ID (0-45)
    val monsters: List<Monster>,
    val objects: List<FloorObject>
)
```

### 2.4 标签数据

```kotlin
data class LabelData(
    val dataLabels: Set<String>,    // D_xxx 数据标签
    val stringLabels: Set<String>,  // S_xxx 字符串标签
    val functionLabels: Set<String> // F_xxx 函数标签
)
```

---

## 3. 核心检查函数

### 3.1 主检查函数

```kotlin
data class CompatibilityResult(
    val errors: MutableList<String> = mutableListOf(),
    val warnings: MutableList<String> = mutableListOf()
)

fun testCompatibility(
    version: PSOVersion,
    scriptLines: List<String>,      // 脚本行
    labels: LabelData,               // 标签数据
    floors: List<Floor>,             // Floor数据
    opcodes: List<AsmOpcode>,        // 操作码定义
    questFiles: List<String>,        // 任务文件列表
    episode: Int                     // Episode (0=EP1, 1=EP2, 2=EP4)
): CompatibilityResult {
    val result = CompatibilityResult()

    // 1. 脚本检查
    checkScriptCompatibility(version, scriptLines, labels, opcodes, result)

    // 2. 怪物/NPC检查
    checkMonsterCompatibility(version, floors, labels, episode, result)

    // 3. 对象检查
    checkObjectCompatibility(version, floors, result)

    // 4. 数据标签检查
    checkUnusedDataLabels(scriptLines, labels, result)

    // 5. 文件检查
    checkQuestFiles(version, questFiles, result)

    return result
}
```

---

## 4. 详细检查逻辑

### 4.1 脚本兼容性检查

```kotlin
fun checkScriptCompatibility(
    version: PSOVersion,
    scriptLines: List<String>,
    labels: LabelData,
    opcodes: List<AsmOpcode>,
    result: CompatibilityResult
) {
    // 检查1: Label 0 必须存在
    if (!labelExists("0", labels)) {
        result.errors.add("Label 0 does not exist (quest entry point required)")
    }

    // 检查2: 遍历所有脚本行
    for ((lineNum, line) in scriptLines.withIndex()) {
        // 解析行: 跳过前8个字符 (label部分)
        val codeLine = line.substring(8).trim()
        if (codeLine.isEmpty()) continue

        // 提取操作码名称
        val parts = codeLine.split(" ", limit = 2)
        val opcodeName = parts[0]
        val args = if (parts.size > 1) parts[1] else ""

        // 查找操作码定义
        val opcode = opcodes.find { it.name.equals(opcodeName, ignoreCase = true) }
        if (opcode == null) {
            result.errors.add("Unknown opcode '$opcodeName' at line $lineNum")
            continue
        }

        // 检查2.1: 转换指令警告 (特定操作码)
        val convertOpcodes = setOf(0x66, 0x6D, 0x79, 0x7C, 0x7D, 0x7F,
                                   0x84, 0x87, 0xA8, 0xC0, 0xCD, 0xCE)
        if (opcode.fnc in convertOpcodes) {
            if (version.verId < 2 && opcode.order != ArgType.T_DC) {
                result.warnings.add("Opcode '$opcodeName' may have conversion issues in V1 at line $lineNum")
            }
        }

        // 检查2.2: Episode检查 (0xF8BC = set_episode)
        if (opcode.fnc == 0xF8BC) {
            val episodeParam = args.trim()
            if (version.verId < 2 && episodeParam != "00000000") {
                result.errors.add("Episode parameter $episodeParam not supported in V1 at line $lineNum")
            }
            if (version.verId == 2 && episodeParam == "00000002") {
                result.errors.add("Episode 2 (EP4) parameter not supported in PC version at line $lineNum")
            }
        }

        // 检查2.3: 版本检查
        if (opcode.fnc != 0xD9 && opcode.fnc != 0xEF) {
            if (opcode.ver > version.verId) {
                result.errors.add("Opcode '$opcodeName' requires version ${opcode.ver} or higher at line $lineNum")
            }
        }

        // 检查2.4: 特殊操作码警告 (0xF8EE)
        if (opcode.fnc == 0xF8EE) {
            result.warnings.add("Opcode 0xF8EE detected - may cause issues")
        }

        // 检查2.5: 参数检查
        checkOpcodeArguments(opcode, args, labels, lineNum, version, result)
    }
}
```

### 4.2 参数检查详细逻辑

```kotlin
fun checkOpcodeArguments(
    opcode: AsmOpcode,
    argsString: String,
    labels: LabelData,
    lineNum: Int,
    version: PSOVersion,
    result: CompatibilityResult
) {
    var remainingArgs = argsString.trim()
    var argIndex = 0

    while (argIndex < opcode.args.size) {
        val argType = opcode.args[argIndex]

        // 终止条件
        if (argType == ArgType.T_NONE ||
            argType == ArgType.T_STR ||
            argType == ArgType.T_HEX ||
            argType == ArgType.T_STRDATA ||
            remainingArgs.isEmpty()) {
            break
        }

        // 提取当前参数
        val parts = remainingArgs.split(",", limit = 2)
        val currentArg = parts[0].trim()
        remainingArgs = if (parts.size > 1) parts[1].trim() else ""

        // 检查参数类型匹配 (V1版本特殊检查)
        if (opcode.ver < 2 && opcode.order == ArgType.T_ARGS && version.verId < 2) {
            if (argType == ArgType.T_REG && !currentArg.startsWith("R")) {
                result.errors.add("Expected register but got '$currentArg' at line $lineNum")
            }
            if (argType == ArgType.T_DWORD && currentArg.startsWith("R")) {
                result.errors.add("Expected DWORD but got register at line $lineNum")
            }
        }

        // 函数/数据标签检查
        if (argType == ArgType.T_FUNC || argType == ArgType.T_FUNC2 || argType == ArgType.T_DATA) {
            val labelName = currentArg.removeSuffix(":").trim()
            if (!labelExists(labelName, labels)) {
                result.warnings.add("Label '$labelName' not found (referenced at line $lineNum)")
            }
        }

        // Switch语句检查
        if (argType == ArgType.T_SWITCH) {
            checkSwitchStatement(currentArg, labels, lineNum, result)
        }

        argIndex++
    }

    // 字符串参数检查
    if (argIndex < opcode.args.size) {
        val argType = opcode.args[argIndex]
        if (argType == ArgType.T_STR || argType == ArgType.T_STRDATA) {
            checkStringArgument(remainingArgs, lineNum, result)
        }
    }
}

fun checkSwitchStatement(
    switchArg: String,
    labels: LabelData,
    lineNum: Int,
    result: CompatibilityResult
) {
    // 格式: "count:label1:label2:label3..."
    val parts = switchArg.split(":")
    if (parts.isEmpty()) return

    val count = parts[0].toIntOrNull() ?: 0
    val labelList = parts.drop(1)

    if (labelList.size < count) {
        result.errors.add("Switch array missing entries at line $lineNum (expected $count, got ${labelList.size})")
    } else if (labelList.size > count) {
        result.errors.add("Switch array has too many entries at line $lineNum (expected $count, got ${labelList.size})")
    }

    // 检查每个标签是否存在
    for (label in labelList) {
        if (!labelExists(label.trim(), labels)) {
            result.warnings.add("Switch label '$label' not found (at line $lineNum)")
        }
    }
}

fun checkStringArgument(
    strArg: String,
    lineNum: Int,
    result: CompatibilityResult
) {
    // 检查<>标记是否匹配
    var openCount = 0
    for (char in strArg) {
        when (char) {
            '<' -> openCount++
            '>' -> openCount--
        }
    }
    if (openCount != 0) {
        result.warnings.add("Unmatched string markers '<>' at line $lineNum")
    }
}
```

### 4.3 怪物兼容性检查

```kotlin
// 默认标签常量
val DEFAULT_LABELS = listOf(
    100, 90, 120, 130, 80, 70, 60, 140, 110, 30, 50, 1, 20,  // 前13个
    850, 800, 830, 820, 810, 860, 870, 840, 880              // V3新增 (索引13-21)
)

val DEFAULT_LABELS_EP2 = listOf(
    720, 660, 620, 600, 501, 520, 560, 540, 580, 680,  // 前10个
    950, 900, 930, 920, 910, 960, 970, 940, 980         // V3新增 (索引10-18)
)

// 敌人ID列表 (58个)
val ENEMY_IDS = listOf(
    68, 67, 64, 65, 128, 129, 131, 133, 163, 97, 99, 98, 96, 168, 166, 165,
    160, 162, 164, 192, 197, 193, 194, 200, 66, 132, 130, 100, 101, 161, 167,
    223, 213, 212, 215, 217, 218, 214, 222, 221, 225, 224, 216, 219, 220, 202,
    201, 203, 204, 273, 277, 276, 272, 278, 274, 275, 281, 249
)

fun checkMonsterCompatibility(
    version: PSOVersion,
    floors: List<Floor>,
    labels: LabelData,
    episode: Int,
    result: CompatibilityResult
) {
    for (floor in floors) {
        for ((monsterIdx, monster) in floor.monsters.withIndex()) {

            // 检查1: NPC (非敌人) 的动作标签
            if (monster.skin !in ENEMY_IDS) {
                val actionLabel = monster.action.toInt()
                if (actionLabel > 0) {
                    // 检查是否在默认标签列表中
                    val isDefaultLabel = if (episode == 1) {
                        // Episode 2
                        val baseRange = DEFAULT_LABELS_EP2.take(10)
                        val v3Range = if (version.verId == 3) DEFAULT_LABELS_EP2.drop(10) else emptyList()
                        actionLabel in (baseRange + v3Range)
                    } else {
                        // Episode 1/4
                        val baseRange = DEFAULT_LABELS.take(13)
                        val v3Range = if (version.verId == 3) DEFAULT_LABELS.drop(13) else emptyList()
                        actionLabel in (baseRange + v3Range)
                    }

                    // 如果不在默认列表，检查是否存在自定义标签
                    if (!isDefaultLabel && !labelExists(actionLabel.toString(), labels)) {
                        result.warnings.add(
                            "NPC action label $actionLabel not found " +
                            "(monster #$monsterIdx on floor ${floor.floorId})"
                        )
                    }
                }
            }

            // 检查2: Skin 51 特殊检查
            if (monster.skin == 51) {
                when {
                    version.verId < 2 -> {
                        result.warnings.add(
                            "Skin 51 not supported in V1 " +
                            "(monster #$monsterIdx on floor ${floor.floorId})"
                        )
                    }
                    episode == 2 -> {
                        result.warnings.add(
                            "Skin 51 may not work properly in EP2 " +
                            "(monster #$monsterIdx on floor ${floor.floorId})"
                        )
                    }
                    else -> {
                        // V2+ 和 非EP2
                        if (monster.unknow7 > 15) {
                            result.errors.add(
                                "Skin 51 invalid subtype (monster #$monsterIdx on floor ${floor.floorId})"
                            )
                        } else {
                            // 检查NPC51Name表 (需要预加载)
                            if (!isValidNPC51(floor.floorId, monster.unknow7)) {
                                result.errors.add(
                                    "Skin 51 subtype not valid for this floor " +
                                    "(monster #$monsterIdx on floor ${floor.floorId})"
                                )
                            }
                        }
                    }
                }
            }

            // 检查3: Floor特定的怪物兼容性
            if (floor.floorId < 50) {
                val allowedMonsters = getFloorMonsters(floor.floorId, version.verId)
                if (allowedMonsters.isNotEmpty() && monster.skin !in allowedMonsters) {
                    result.warnings.add(
                        "Monster skin ${monster.skin} may not spawn correctly on this floor " +
                        "(monster #$monsterIdx on floor ${floor.floorId})"
                    )
                }
            }
        }

        // 检查4: 数量限制
        if (floor.monsters.size > 400) {
            result.warnings.add("Floor ${floor.floorId} has too many monsters (${floor.monsters.size} > 400)")
        }
    }
}
```

### 4.4 对象兼容性检查

```kotlin
fun checkObjectCompatibility(
    version: PSOVersion,
    floors: List<Floor>,
    result: CompatibilityResult
) {
    for (floor in floors) {
        for ((objIdx, obj) in floor.objects.withIndex()) {
            // Floor特定的对象兼容性
            if (floor.floorId < 50) {
                val allowedObjects = getFloorObjects(floor.floorId, version.verId)
                if (allowedObjects.isNotEmpty() && obj.skin !in allowedObjects) {
                    result.warnings.add(
                        "Object skin ${obj.skin} may not work correctly on this floor " +
                        "(object #$objIdx on floor ${floor.floorId})"
                    )
                }
            }
        }

        // 数量限制
        if (floor.objects.size > 400) {
            result.warnings.add("Floor ${floor.floorId} has too many objects (${floor.objects.size} > 400)")
        }
    }
}
```

### 4.5 未使用数据标签检查

```kotlin
fun checkUnusedDataLabels(
    scriptLines: List<String>,
    labels: LabelData,
    result: CompatibilityResult
) {
    for (dataLabel in labels.dataLabels) {
        // 提取标签数字 (D_123 -> 123)
        val labelNum = dataLabel.removePrefix("D_").toIntOrNull() ?: continue
        val refType = getReferenceType(labelNum, scriptLines)

        if (refType == 0) {
            result.warnings.add("Data label $dataLabel is unused")
        }
    }
}

fun getReferenceType(labelNum: Int, scriptLines: List<String>): Int {
    // 返回值:
    // 0 = 未使用
    // 1 = NPC数据 (get_npc_data)
    // 2 = 代码 (其他HEX数据)
    // 3 = 图像数据
    // 4 = 字符串数据 (STR:)
    // 5 = 敌人物理数据 (get_physical_data)
    // 6 = 敌人抗性数据 (get_resist_data)
    // 7 = 敌人攻击数据 (get_attack_data)
    // 8 = 敌人移动数据 (get_movement_data)
    // 9 = 浮点数据
    // 10 = 向量数据

    val labelPrefix = "$labelNum:"

    for (line in scriptLines) {
        if (line.startsWith(labelPrefix)) {
            // 检查数据类型
            val content = line.substring(8).trim()
            if (content.startsWith("STR:")) return 4
            if (content.startsWith("HEX:")) {
                // 默认为代码，需进一步检查
                return 2
            }
        }

        // 检查是否被特定操作码引用
        // 0xF841 = get_npc_data
        if (line.contains("get_npc_data $labelNum") || line.contains("0xF841 $labelNum")) {
            return 1
        }
        // 0xF892 = get_physical_data
        if (line.contains("get_physical_data $labelNum") || line.contains("0xF892 $labelNum")) {
            return 5
        }
        // 0xF894 = get_resist_data
        if (line.contains("get_resist_data $labelNum") || line.contains("0xF894 $labelNum")) {
            return 6
        }
        // 0xF893 = get_attack_data
        if (line.contains("get_attack_data $labelNum") || line.contains("0xF893 $labelNum")) {
            return 7
        }
        // 0xF895 = get_movement_data
        if (line.contains("get_movement_data $labelNum") || line.contains("0xF895 $labelNum")) {
            return 8
        }
    }

    return 0
}
```

### 4.6 文件检查

```kotlin
fun checkQuestFiles(
    version: PSOVersion,
    questFiles: List<String>,
    result: CompatibilityResult
) {
    // 检查1: .bin文件 (必需)
    val hasBinFile = questFiles.any { it.endsWith(".bin", ignoreCase = true) }
    if (!hasBinFile) {
        result.errors.add("Quest must contain a .bin file")
    }

    // 检查2: .dat文件 (警告)
    val hasDatFile = questFiles.any { it.endsWith(".dat", ignoreCase = true) }
    if (!hasDatFile) {
        result.warnings.add("Quest does not contain a .dat file")
    }

    // 检查3: .pvr文件 (V2+不允许)
    val hasPvrFile = questFiles.any { it.endsWith(".pvr", ignoreCase = true) }
    if (version.verId > 1 && hasPvrFile) {
        result.errors.add(".pvr texture files not supported in V2 or later (use .xvm/.prs)")
    }
}
```

---

## 5. 辅助函数

### 5.1 标签查找

```kotlin
fun labelExists(labelName: String, labels: LabelData): Boolean {
    return labels.dataLabels.contains("D_$labelName") ||
           labels.stringLabels.contains("S_$labelName") ||
           labels.functionLabels.contains("F_$labelName")
}
```

### 5.2 Episode检测

```kotlin
fun getEpisode(scriptLines: List<String>): Int {
    // 查找 set_episode 操作码 (0xF8BC)
    for (line in scriptLines) {
        val content = line.substring(8).trim()
        if (content.startsWith("set_episode") || content.contains("0xF8BC")) {
            when {
                content.contains("00000000") -> return 0  // Episode 1
                content.contains("00000001") -> return 1  // Episode 2
                content.contains("00000002") -> return 2  // Episode 4
            }
        }
    }
    return 0  // 默认 Episode 1
}
```

### 5.3 Floor数据查询 (需要预加载FloorSet数据)

```kotlin
// 需要从FloorSet.ini或数据库加载
data class FloorIDData(
    val count: IntArray,              // [version] -> count
    val ids: Array<IntArray>          // [version][index] -> id
)

val floorMonsterData: Map<Int, FloorIDData> = loadFloorMonsterData()
val floorObjectData: Map<Int, FloorIDData> = loadFloorObjectData()

fun getFloorMonsters(floorId: Int, version: Int): List<Int> {
    val data = floorMonsterData[floorId] ?: return emptyList()
    val count = data.count.getOrNull(version) ?: 0
    return data.ids.getOrNull(version)?.take(count) ?: emptyList()
}

fun getFloorObjects(floorId: Int, version: Int): List<Int> {
    val data = floorObjectData[floorId] ?: return emptyList()
    val count = data.count.getOrNull(version) ?: 0
    return data.ids.getOrNull(version)?.take(count) ?: emptyList()
}
```

### 5.4 NPC51验证 (需要MyConst.pas中的NPC51Name数据)

```kotlin
// 从MyConst.pas加载的数据
// NPC51Name: [floorId][subtype] -> name
val npc51Names: Map<Int, Array<String>> = loadNPC51Names()

fun isValidNPC51(floorId: Int, subtype: Int): Boolean {
    val names = npc51Names[floorId] ?: return false
    if (subtype >= names.size) return false
    val name = names[subtype]
    return name.isNotEmpty() && name != "CRASH"
}
```

---

## 6. 语言字符串映射

```kotlin
object LanguageStrings {
    const val ERR_LABEL_0_MISSING = 86          // "Label 0 does not exist"
    const val ERR_CONVERSION_WARNING = 89       // "Conversion opcode warning"
    const val AT_LINE = 87                      // " at line "
    const val ERR_EPISODE_PARAM = 90            // "Episode parameter"
    const val ERR_OPCODE_VERSION = 91           // "Opcode requires newer version"
    const val WARN_SPECIAL_OPCODE = 92          // "Special opcode warning"
    const val ERR_ARG_MISMATCH = 93             // "Argument type mismatch"
    const val WARN_LABEL_NOT_FOUND = 94         // "Label not found"
    const val IN_FUNCTION = 88                  // " in function"
    const val WARN_STRING_MARKERS = 95          // "Unmatched string markers"
    const val WARN_NPC_ACTION = 96              // "NPC action label"
    const val MONSTER_NUM = 97                  // " monster #"
    const val ON_FLOOR = 98                     // " on floor "
    const val WARN_SKIN_VERSION = 99            // "Skin not supported in version"
    const val IN_MONSTER = 100                  // " in monster #"
    const val ERR_SKIN51_INVALID = 101          // "Skin 51 invalid subtype"
    const val MAY_NOT_SPAWN = 102               // " may not spawn correctly"
    const val WARN_OBJECT_FLOOR = 103           // "Object may not work on floor"
    const val FLOOR_NUM = 104                   // "Floor "
    const val TOO_MANY_MONSTERS = 106           // " has too many monsters"
    const val TOO_MANY_OBJECTS = 105            // " has too many objects"
    const val WARN_UNUSED_DATA = 107            // "Data label is unused"
    const val ERR_NO_BIN_FILE = 108             // "Quest must contain .bin file"
    const val WARN_NO_DAT_FILE = 109            // "No .dat file"
    const val ERR_PVR_IN_V2 = 110               // ".pvr not supported in V2+"
}
```

---

## 7. 使用示例

```kotlin
fun main() {
    // 加载任务数据
    val scriptLines = loadScriptFromFile("quest.qst")
    val labels = parseLabels(scriptLines)
    val floors = loadFloorsFromFile("quest.qst")
    val opcodes = loadOpcodeDefinitions("Asm.txt")
    val questFiles = listOf("quest.bin", "quest.dat")
    val episode = getEpisode(scriptLines)

    // 对所有版本进行检查
    for (version in PSOVersion.values()) {
        val result = testCompatibility(
            version = version,
            scriptLines = scriptLines,
            labels = labels,
            floors = floors,
            opcodes = opcodes,
            questFiles = questFiles,
            episode = episode
        )

        println("=== ${version.displayName} ===")

        if (result.errors.isEmpty() && result.warnings.isEmpty()) {
            println("✓ Fully compatible")
        } else {
            if (result.errors.isNotEmpty()) {
                println("Errors:")
                result.errors.forEach { println("  ✗ $it") }
            }
            if (result.warnings.isNotEmpty()) {
                println("Warnings:")
                result.warnings.forEach { println("  ⚠ $it") }
            }
        }
        println()
    }
}
```

---

## 8. 需要预加载的数据文件

1. **Asm.txt** - 操作码定义
2. **FloorSet.ini** - Floor允许的怪物/对象列表
3. **MyConst.pas中的NPC51Name** - NPC Skin 51的子类型名称
4. **语言文件** (Eng.txt, etc.) - 错误/警告消息

---

## 9. 关键注意事项

1. **脚本行格式**: 前8个字符是标签部分 (例如: `"0:      "`), 从第9个字符开始是实际代码
2. **大小写**: 操作码名称不区分大小写
3. **标签前缀**:
   - `D_` = 数据标签
   - `S_` = 字符串标签
   - `F_` = 函数标签
4. **Version ID映射**:
   - 0 = DC V1
   - 1 = DC V2
   - 2 = PC
   - 3 = GC Ep1&2
5. **Episode映射**:
   - 0 = Episode 1
   - 1 = Episode 2
   - 2 = Episode 4

---

## 10. 优化建议

对于Kotlin实现:

1. 使用协程并行检查不同版本
2. 缓存Floor数据的查找结果
3. 使用正则表达式优化参数解析
4. 构建标签索引加速查找
5. 使用序列(Sequence)处理大量脚本行

```kotlin
// 并行检查示例
suspend fun checkAllVersions(quest: QuestData): Map<PSOVersion, CompatibilityResult> =
    coroutineScope {
        PSOVersion.values().associate { version ->
            version to async {
                testCompatibility(version, quest)
            }
        }.mapValues { it.value.await() }
    }
```