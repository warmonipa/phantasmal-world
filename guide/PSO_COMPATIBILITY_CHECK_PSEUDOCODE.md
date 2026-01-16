# PSO Quest 兼容性检查 - 伪代码

## 目录
1. [主入口函数](#1-主入口函数)
2. [脚本兼容性检查](#2-脚本兼容性检查)
3. [参数检查](#3-参数检查)
4. [怪物兼容性检查](#4-怪物兼容性检查)
5. [对象兼容性检查](#5-对象兼容性检查)
6. [数据标签检查](#6-数据标签检查)
7. [文件检查](#7-文件检查)
8. [辅助函数](#8-辅助函数)

---

## 1. 主入口函数

```
// 版本枚举
ENUM PSOVersion:
    DC_V1 = 0          // Dreamcast V1
    DC_V2 = 1          // Dreamcast V2
    PC = 2             // PC版本
    GC_EP12 = 3        // GameCube Episode 1&2
    BLUE_BURST = 4     // Blue Burst

// 参数类型常量
CONSTANTS:
    T_NONE = 0, T_IMED = 1, T_ARGS = 2, T_DC = 6
    T_REG = 7, T_DWORD = 10, T_STR = 12
    T_FUNC = 14, T_FUNC2 = 15, T_SWITCH = 16
    T_STRDATA = 19, T_DATA = 20, T_HEX = 21

// 结果结构
STRUCTURE CompatibilityResult:
    errors: List<String>        // 严重错误列表
    warnings: List<String>      // 警告列表

// 主检查函数
FUNCTION TestCompatibility(
    version: PSOVersion,           // 目标版本
    scriptLines: List<String>,     // 脚本行列表
    labels: LabelData,             // 标签数据
    floors: List<Floor>,           // Floor数据
    opcodes: List<Opcode>,         // 操作码定义
    questFiles: List<String>,      // 任务文件列表
    episode: Integer               // Episode (0=EP1, 1=EP2, 2=EP4)
) RETURNS CompatibilityResult:

    // 初始化结果
    result = NEW CompatibilityResult()
    result.errors = []
    result.warnings = []

    // 步骤1: 脚本兼容性检查
    CheckScriptCompatibility(version, scriptLines, labels, opcodes, result)

    // 步骤2: 怪物/NPC兼容性检查
    CheckMonsterCompatibility(version, floors, labels, episode, result)

    // 步骤3: 对象兼容性检查
    CheckObjectCompatibility(version, floors, result)

    // 步骤4: 未使用数据标签检查
    CheckUnusedDataLabels(scriptLines, labels, result)

    // 步骤5: 文件完整性检查
    CheckQuestFiles(version, questFiles, result)

    RETURN result
END FUNCTION
```

---

## 2. 脚本兼容性检查

```
FUNCTION CheckScriptCompatibility(
    version: PSOVersion,
    scriptLines: List<String>,
    labels: LabelData,
    opcodes: List<Opcode>,
    result: CompatibilityResult
):
    // ========================================
    // 检查1: 验证Label 0是否存在
    // ========================================
    IF NOT LabelExists("0", labels) THEN
        ADD "Label 0 does not exist (quest entry point required)" TO result.errors
    END IF

    // ========================================
    // 检查2: 遍历所有脚本行
    // ========================================
    FOR lineNum FROM 0 TO LENGTH(scriptLines) - 1 DO
        line = scriptLines[lineNum]

        // 跳过空行
        IF LENGTH(line) < 9 THEN
            CONTINUE
        END IF

        // 提取操作码部分 (跳过前8个字符的标签部分)
        // 格式: "0:      opcode args"
        //        ^^^^^^^^ 标签部分 (8字符)
        codeLine = SUBSTRING(line, 8, LENGTH(line))
        codeLine = TRIM(codeLine)

        IF codeLine IS EMPTY THEN
            CONTINUE
        END IF

        // ========================================
        // 步骤2.1: 解析操作码和参数
        // ========================================
        spacePos = FIND_FIRST(" ", codeLine)
        IF spacePos > 0 THEN
            opcodeName = SUBSTRING(codeLine, 0, spacePos)
            argsString = SUBSTRING(codeLine, spacePos + 1, LENGTH(codeLine))
        ELSE
            opcodeName = codeLine
            argsString = ""
        END IF

        // ========================================
        // 步骤2.2: 查找操作码定义
        // ========================================
        opcode = NULL
        FOR EACH op IN opcodes DO
            IF LOWERCASE(op.name) == LOWERCASE(opcodeName) THEN
                opcode = op
                BREAK
            END IF
        END FOR

        IF opcode IS NULL THEN
            ADD "Unknown opcode '" + opcodeName + "' at line " + lineNum TO result.errors
            CONTINUE
        END IF

        // ========================================
        // 检查2.3: 转换指令警告
        // ========================================
        // 这些操作码在V1版本中可能有转换问题
        convertOpcodes = [0x66, 0x6D, 0x79, 0x7C, 0x7D, 0x7F,
                         0x84, 0x87, 0xA8, 0xC0, 0xCD, 0xCE]

        IF opcode.functionCode IN convertOpcodes THEN
            IF version < DC_V2 AND opcode.order != T_DC THEN
                ADD "Opcode '" + opcodeName + "' may have conversion issues in V1 at line " + lineNum
                    TO result.warnings
            END IF
        END IF

        // ========================================
        // 检查2.4: Episode参数检查
        // ========================================
        // 0xF8BC = set_episode 操作码
        IF opcode.functionCode == 0xF8BC THEN
            episodeParam = TRIM(argsString)

            // V1版本只支持Episode 1 (参数必须是00000000)
            IF version < DC_V2 AND episodeParam != "00000000" THEN
                ADD "Episode parameter " + episodeParam + " not supported in V1 at line " + lineNum
                    TO result.errors
            END IF

            // PC版本不支持Episode 4 (参数不能是00000002)
            IF version == PC AND episodeParam == "00000002" THEN
                ADD "Episode 4 parameter not supported in PC version at line " + lineNum
                    TO result.errors
            END IF
        END IF

        // ========================================
        // 检查2.5: 操作码版本检查
        // ========================================
        // 跳过特殊操作码 0xD9 和 0xEF
        IF opcode.functionCode != 0xD9 AND opcode.functionCode != 0xEF THEN
            IF opcode.minVersion > version THEN
                ADD "Opcode '" + opcodeName + "' requires version " + opcode.minVersion +
                    " or higher at line " + lineNum TO result.errors
            END IF
        END IF

        // ========================================
        // 检查2.6: 特殊操作码警告
        // ========================================
        // 0xF8EE 可能导致问题
        IF opcode.functionCode == 0xF8EE THEN
            ADD "Opcode 0xF8EE detected - may cause compatibility issues" TO result.warnings
        END IF

        // ========================================
        // 检查2.7: 参数类型和引用检查
        // ========================================
        CheckOpcodeArguments(opcode, argsString, labels, lineNum, version, result)

    END FOR
END FUNCTION
```

---

## 3. 参数检查

```
FUNCTION CheckOpcodeArguments(
    opcode: Opcode,
    argsString: String,
    labels: LabelData,
    lineNum: Integer,
    version: PSOVersion,
    result: CompatibilityResult
):
    remainingArgs = TRIM(argsString)
    argIndex = 0

    // ========================================
    // 遍历所有参数定义
    // ========================================
    WHILE argIndex < LENGTH(opcode.argTypes) DO
        argType = opcode.argTypes[argIndex]

        // ========================================
        // 终止条件检查
        // ========================================
        IF argType == T_NONE OR
           argType == T_STR OR
           argType == T_HEX OR
           argType == T_STRDATA OR
           remainingArgs IS EMPTY THEN
            BREAK
        END IF

        // ========================================
        // 提取当前参数
        // ========================================
        commaPos = FIND_FIRST(",", remainingArgs)
        IF commaPos > 0 THEN
            currentArg = TRIM(SUBSTRING(remainingArgs, 0, commaPos))
            remainingArgs = TRIM(SUBSTRING(remainingArgs, commaPos + 1, LENGTH(remainingArgs)))
        ELSE
            currentArg = TRIM(remainingArgs)
            remainingArgs = ""
        END IF

        // ========================================
        // 检查3.1: V1版本的参数类型匹配
        // ========================================
        IF opcode.minVersion < 2 AND opcode.order == T_ARGS AND version < DC_V2 THEN
            // 期望寄存器但得到其他类型
            IF argType == T_REG AND NOT STARTS_WITH(currentArg, "R") THEN
                ADD "Expected register but got '" + currentArg + "' at line " + lineNum
                    TO result.errors
            END IF

            // 期望DWORD但得到寄存器
            IF argType == T_DWORD AND STARTS_WITH(currentArg, "R") THEN
                ADD "Expected DWORD but got register at line " + lineNum
                    TO result.errors
            END IF
        END IF

        // ========================================
        // 检查3.2: 函数/数据标签引用
        // ========================================
        IF argType == T_FUNC OR argType == T_FUNC2 OR argType == T_DATA THEN
            // 移除可能的冒号后缀
            labelName = REPLACE(currentArg, ":", "")
            labelName = TRIM(labelName)

            IF NOT LabelExists(labelName, labels) THEN
                ADD "Label '" + labelName + "' not found (referenced at line " + lineNum + ")"
                    TO result.warnings
            END IF
        END IF

        // ========================================
        // 检查3.3: Switch语句
        // ========================================
        IF argType == T_SWITCH THEN
            CheckSwitchStatement(currentArg, labels, lineNum, result)
        END IF

        argIndex = argIndex + 1
    END WHILE

    // ========================================
    // 检查3.4: 字符串参数
    // ========================================
    IF argIndex < LENGTH(opcode.argTypes) THEN
        finalArgType = opcode.argTypes[argIndex]

        IF finalArgType == T_STR OR finalArgType == T_STRDATA THEN
            CheckStringArgument(remainingArgs, lineNum, result)
        END IF
    END IF
END FUNCTION

// ========================================
// Switch语句检查
// ========================================
FUNCTION CheckSwitchStatement(
    switchArg: String,
    labels: LabelData,
    lineNum: Integer,
    result: CompatibilityResult
):
    // Switch格式: "count:label1:label2:label3..."
    // 例如: "3:func_100:func_200:func_300"

    parts = SPLIT(switchArg, ":")

    IF LENGTH(parts) == 0 THEN
        RETURN
    END IF

    // 第一部分是数量
    expectedCount = PARSE_INT(parts[0])

    // 剩余部分是标签列表
    labelList = SUBLIST(parts, 1, LENGTH(parts))
    actualCount = LENGTH(labelList)

    // 检查数量是否匹配
    IF actualCount < expectedCount THEN
        ADD "Switch array missing entries at line " + lineNum +
            " (expected " + expectedCount + ", got " + actualCount + ")"
            TO result.errors
    ELSE IF actualCount > expectedCount THEN
        ADD "Switch array has too many entries at line " + lineNum +
            " (expected " + expectedCount + ", got " + actualCount + ")"
            TO result.errors
    END IF

    // 检查每个标签是否存在
    FOR EACH label IN labelList DO
        labelName = TRIM(label)
        IF NOT LabelExists(labelName, labels) THEN
            ADD "Switch label '" + labelName + "' not found (at line " + lineNum + ")"
                TO result.warnings
        END IF
    END FOR
END FUNCTION

// ========================================
// 字符串参数检查
// ========================================
FUNCTION CheckStringArgument(
    strArg: String,
    lineNum: Integer,
    result: CompatibilityResult
):
    // 检查字符串中的 <> 标记是否成对匹配
    // < 用于开始特殊字符/颜色代码
    // > 用于结束

    openCount = 0

    FOR EACH char IN strArg DO
        IF char == '<' THEN
            openCount = openCount + 1
        ELSE IF char == '>' THEN
            openCount = openCount - 1
        END IF
    END FOR

    IF openCount != 0 THEN
        ADD "Unmatched string markers '<>' in string at line " + lineNum
            TO result.warnings
    END IF
END FUNCTION
```

---

## 4. 怪物兼容性检查

```
// ========================================
// 常量定义
// ========================================
CONSTANTS:
    // Episode 1/4 默认标签 (前13个为基础，后9个为V3新增)
    DEFAULT_LABELS_EP1 = [
        100, 90, 120, 130, 80, 70, 60, 140, 110, 30, 50, 1, 20,      // 0-12: 基础
        850, 800, 830, 820, 810, 860, 870, 840, 880                  // 13-21: V3+
    ]

    // Episode 2 默认标签 (前10个为基础，后9个为V3新增)
    DEFAULT_LABELS_EP2 = [
        720, 660, 620, 600, 501, 520, 560, 540, 580, 680,            // 0-9: 基础
        950, 900, 930, 920, 910, 960, 970, 940, 980                  // 10-18: V3+
    ]

    // 敌人ID列表 (58个标准敌人)
    ENEMY_IDS = [
        68, 67, 64, 65, 128, 129, 131, 133, 163, 97, 99, 98, 96,
        168, 166, 165, 160, 162, 164, 192, 197, 193, 194, 200,
        66, 132, 130, 100, 101, 161, 167, 223, 213, 212, 215,
        217, 218, 214, 222, 221, 225, 224, 216, 219, 220, 202,
        201, 203, 204, 273, 277, 276, 272, 278, 274, 275, 281, 249
    ]

FUNCTION CheckMonsterCompatibility(
    version: PSOVersion,
    floors: List<Floor>,
    labels: LabelData,
    episode: Integer,
    result: CompatibilityResult
):
    // ========================================
    // 遍历所有Floor
    // ========================================
    FOR EACH floor IN floors DO

        // ========================================
        // 遍历Floor中的所有怪物
        // ========================================
        FOR monsterIdx FROM 0 TO LENGTH(floor.monsters) - 1 DO
            monster = floor.monsters[monsterIdx]

            // ========================================
            // 检查4.1: NPC动作标签验证
            // ========================================
            // 如果不是标准敌人，则为NPC
            IF monster.skin NOT IN ENEMY_IDS THEN
                actionLabel = ROUND(monster.action)

                IF actionLabel > 0 THEN
                    isDefaultLabel = FALSE

                    // 根据Episode选择默认标签列表
                    IF episode == 1 THEN  // Episode 2
                        // 检查基础标签 (0-9)
                        FOR i FROM 0 TO 9 DO
                            IF DEFAULT_LABELS_EP2[i] == actionLabel THEN
                                isDefaultLabel = TRUE
                                BREAK
                            END IF
                        END FOR

                        // V3版本额外检查扩展标签 (10-18)
                        IF version >= GC_EP12 AND NOT isDefaultLabel THEN
                            FOR i FROM 10 TO 18 DO
                                IF DEFAULT_LABELS_EP2[i] == actionLabel THEN
                                    isDefaultLabel = TRUE
                                    BREAK
                                END IF
                            END FOR
                        END IF
                    ELSE  // Episode 1 或 4
                        // 检查基础标签 (0-12)
                        FOR i FROM 0 TO 12 DO
                            IF DEFAULT_LABELS_EP1[i] == actionLabel THEN
                                isDefaultLabel = TRUE
                                BREAK
                            END IF
                        END FOR

                        // V3版本额外检查扩展标签 (13-21)
                        IF version >= GC_EP12 AND NOT isDefaultLabel THEN
                            FOR i FROM 13 TO 21 DO
                                IF DEFAULT_LABELS_EP1[i] == actionLabel THEN
                                    isDefaultLabel = TRUE
                                    BREAK
                                END IF
                            END FOR
                        END IF
                    END IF

                    // 如果不是默认标签，检查自定义标签是否存在
                    IF NOT isDefaultLabel THEN
                        IF NOT LabelExists(TO_STRING(actionLabel), labels) THEN
                            ADD "NPC action label " + actionLabel + " not found " +
                                "(monster #" + monsterIdx + " on floor " + floor.floorId + ")"
                                TO result.warnings
                        END IF
                    END IF
                END IF
            END IF

            // ========================================
            // 检查4.2: Skin 51 特殊处理
            // ========================================
            // Skin 51 是特殊的NPC类型，有版本和子类型限制
            IF monster.skin == 51 THEN
                IF version < DC_V2 THEN
                    // V1版本不支持Skin 51
                    ADD "Skin 51 not supported in V1 " +
                        "(monster #" + monsterIdx + " on floor " + floor.floorId + ")"
                        TO result.warnings

                ELSE IF episode == 2 THEN
                    // Episode 2 中Skin 51可能不正常工作
                    ADD "Skin 51 may not work properly in EP2 " +
                        "(monster #" + monsterIdx + " on floor " + floor.floorId + ")"
                        TO result.warnings

                ELSE
                    // V2+ 且非EP2: 检查子类型有效性
                    subtype = monster.unknow7

                    IF subtype > 15 THEN
                        // 子类型超出范围
                        ADD "Skin 51 invalid subtype " +
                            "(monster #" + monsterIdx + " on floor " + floor.floorId + ")"
                            TO result.errors
                    ELSE
                        // 检查子类型是否在该Floor的NPC51表中定义
                        IF NOT IsValidNPC51(floor.floorId, subtype) THEN
                            ADD "Skin 51 subtype not valid for this floor " +
                                "(monster #" + monsterIdx + " on floor " + floor.floorId + ")"
                                TO result.errors
                        END IF
                    END IF
                END IF
            END IF

            // ========================================
            // 检查4.3: Floor特定怪物兼容性
            // ========================================
            IF floor.floorId < 50 THEN
                // 获取该Floor在该版本允许的怪物列表
                allowedMonsters = GetFloorMonsters(floor.floorId, version)

                IF LENGTH(allowedMonsters) > 0 THEN
                    IF monster.skin NOT IN allowedMonsters THEN
                        ADD "Monster skin " + monster.skin + " may not spawn correctly " +
                            "(monster #" + monsterIdx + " on floor " + floor.floorId + ")"
                            TO result.warnings
                    END IF
                END IF
            END IF
        END FOR

        // ========================================
        // 检查4.4: 怪物数量限制
        // ========================================
        IF LENGTH(floor.monsters) > 400 THEN
            ADD "Floor " + floor.floorId + " has too many monsters " +
                "(" + LENGTH(floor.monsters) + " > 400)"
                TO result.warnings
        END IF
    END FOR
END FUNCTION
```

---

## 5. 对象兼容性检查

```
FUNCTION CheckObjectCompatibility(
    version: PSOVersion,
    floors: List<Floor>,
    result: CompatibilityResult
):
    // ========================================
    // 遍历所有Floor
    // ========================================
    FOR EACH floor IN floors DO

        // ========================================
        // 遍历Floor中的所有对象
        // ========================================
        FOR objIdx FROM 0 TO LENGTH(floor.objects) - 1 DO
            obj = floor.objects[objIdx]

            // ========================================
            // 检查5.1: Floor特定对象兼容性
            // ========================================
            IF floor.floorId < 50 THEN
                // 获取该Floor在该版本允许的对象列表
                allowedObjects = GetFloorObjects(floor.floorId, version)

                IF LENGTH(allowedObjects) > 0 THEN
                    IF obj.skin NOT IN allowedObjects THEN
                        ADD "Object skin " + obj.skin + " may not work correctly " +
                            "(object #" + objIdx + " on floor " + floor.floorId + ")"
                            TO result.warnings
                    END IF
                END IF
            END IF
        END FOR

        // ========================================
        // 检查5.2: 对象数量限制
        // ========================================
        IF LENGTH(floor.objects) > 400 THEN
            ADD "Floor " + floor.floorId + " has too many objects " +
                "(" + LENGTH(floor.objects) + " > 400)"
                TO result.warnings
        END IF
    END FOR
END FUNCTION
```

---

## 6. 数据标签检查

```
FUNCTION CheckUnusedDataLabels(
    scriptLines: List<String>,
    labels: LabelData,
    result: CompatibilityResult
):
    // ========================================
    // 遍历所有数据标签
    // ========================================
    FOR EACH dataLabel IN labels.dataLabels DO
        // 提取标签数字 (D_123 -> 123)
        IF STARTS_WITH(dataLabel, "D_") THEN
            labelNumStr = SUBSTRING(dataLabel, 2, LENGTH(dataLabel))
            labelNum = PARSE_INT(labelNumStr)

            // 获取标签的引用类型
            refType = GetReferenceType(labelNum, scriptLines)

            // 引用类型为0表示未使用
            IF refType == 0 THEN
                ADD "Data label " + dataLabel + " is unused" TO result.warnings
            END IF
        END IF
    END FOR
END FUNCTION

// ========================================
// 获取数据标签的引用类型
// ========================================
FUNCTION GetReferenceType(
    labelNum: Integer,
    scriptLines: List<String>
) RETURNS Integer:
    // 返回值说明:
    // 0  = 未使用
    // 1  = NPC数据 (get_npc_data)
    // 2  = 代码 (一般HEX数据)
    // 3  = 图像数据
    // 4  = 字符串数据 (STR:)
    // 5  = 敌人物理数据 (get_physical_data / 0xF892)
    // 6  = 敌人抗性数据 (get_resist_data / 0xF894)
    // 7  = 敌人攻击数据 (get_attack_data / 0xF893)
    // 8  = 敌人移动数据 (get_movement_data / 0xF895)
    // 9  = 浮点数据
    // 10 = 向量数据

    labelPrefix = labelNum + ":"

    // ========================================
    // 遍历脚本行查找标签定义和引用
    // ========================================
    FOR EACH line IN scriptLines DO

        // ========================================
        // 检查标签定义行
        // ========================================
        IF STARTS_WITH(line, labelPrefix) THEN
            content = TRIM(SUBSTRING(line, 8, LENGTH(line)))

            // 字符串数据
            IF STARTS_WITH(content, "STR:") THEN
                RETURN 4
            END IF

            // HEX数据 (默认为代码)
            IF STARTS_WITH(content, "HEX:") THEN
                RETURN 2
            END IF
        END IF

        // ========================================
        // 检查操作码引用
        // ========================================
        content = TRIM(SUBSTRING(line, 8, LENGTH(line)))

        // NPC数据: get_npc_data (0xF841)
        IF CONTAINS(content, "get_npc_data " + labelNum) OR
           CONTAINS(content, "0xF841 " + labelNum) THEN
            RETURN 1
        END IF

        // 敌人物理数据: get_physical_data (0xF892)
        IF CONTAINS(content, "get_physical_data " + labelNum) OR
           CONTAINS(content, "0xF892 " + labelNum) THEN
            RETURN 5
        END IF

        // 敌人抗性数据: get_resist_data (0xF894)
        IF CONTAINS(content, "get_resist_data " + labelNum) OR
           CONTAINS(content, "0xF894 " + labelNum) THEN
            RETURN 6
        END IF

        // 敌人攻击数据: get_attack_data (0xF893)
        IF CONTAINS(content, "get_attack_data " + labelNum) OR
           CONTAINS(content, "0xF893 " + labelNum) THEN
            RETURN 7
        END IF

        // 敌人移动数据: get_movement_data (0xF895)
        IF CONTAINS(content, "get_movement_data " + labelNum) OR
           CONTAINS(content, "0xF895 " + labelNum) THEN
            RETURN 8
        END IF
    END FOR

    // 未找到引用
    RETURN 0
END FUNCTION
```

---

## 7. 文件检查

```
FUNCTION CheckQuestFiles(
    version: PSOVersion,
    questFiles: List<String>,
    result: CompatibilityResult
):
    // ========================================
    // 检查7.1: .bin文件 (必需)
    // ========================================
    hasBinFile = FALSE
    FOR EACH fileName IN questFiles DO
        IF ENDS_WITH(LOWERCASE(fileName), ".bin") THEN
            hasBinFile = TRUE
            BREAK
        END IF
    END FOR

    IF NOT hasBinFile THEN
        ADD "Quest must contain a .bin file" TO result.errors
    END IF

    // ========================================
    // 检查7.2: .dat文件 (推荐)
    // ========================================
    hasDatFile = FALSE
    FOR EACH fileName IN questFiles DO
        IF ENDS_WITH(LOWERCASE(fileName), ".dat") THEN
            hasDatFile = TRUE
            BREAK
        END IF
    END FOR

    IF NOT hasDatFile THEN
        ADD "Quest does not contain a .dat file (recommended)" TO result.warnings
    END IF

    // ========================================
    // 检查7.3: .pvr文件 (V2+不支持)
    // ========================================
    hasPvrFile = FALSE
    FOR EACH fileName IN questFiles DO
        IF ENDS_WITH(LOWERCASE(fileName), ".pvr") THEN
            hasPvrFile = TRUE
            BREAK
        END IF
    END FOR

    // V2及以后版本使用.xvm格式，不支持.pvr
    IF version > DC_V1 AND hasPvrFile THEN
        ADD ".pvr texture files not supported in V2 or later (use .xvm/.prs format)"
            TO result.errors
    END IF
END FUNCTION
```

---

## 8. 辅助函数

```
// ========================================
// 8.1 标签存在性检查
// ========================================
FUNCTION LabelExists(
    labelName: String,
    labels: LabelData
) RETURNS Boolean:
    // 检查三种类型的标签
    // D_xxx = 数据标签
    // S_xxx = 字符串标签
    // F_xxx = 函数标签

    IF ("D_" + labelName) IN labels.dataLabels THEN
        RETURN TRUE
    END IF

    IF ("S_" + labelName) IN labels.stringLabels THEN
        RETURN TRUE
    END IF

    IF ("F_" + labelName) IN labels.functionLabels THEN
        RETURN TRUE
    END IF

    RETURN FALSE
END FUNCTION

// ========================================
// 8.2 Episode检测
// ========================================
FUNCTION GetEpisode(
    scriptLines: List<String>
) RETURNS Integer:
    // 返回值:
    // 0 = Episode 1
    // 1 = Episode 2
    // 2 = Episode 4

    // 查找set_episode操作码 (0xF8BC)
    FOR EACH line IN scriptLines DO
        content = TRIM(SUBSTRING(line, 8, LENGTH(line)))

        IF CONTAINS(content, "set_episode") OR CONTAINS(content, "0xF8BC") THEN
            IF CONTAINS(content, "00000000") THEN
                RETURN 0  // Episode 1
            END IF
            IF CONTAINS(content, "00000001") THEN
                RETURN 1  // Episode 2
            END IF
            IF CONTAINS(content, "00000002") THEN
                RETURN 2  // Episode 4
            END IF
        END IF
    END FOR

    // 默认Episode 1
    RETURN 0
END FUNCTION

// ========================================
// 8.3 Floor怪物列表查询
// ========================================
FUNCTION GetFloorMonsters(
    floorId: Integer,
    version: Integer
) RETURNS List<Integer>:
    // 从FloorMonsterData表中查询
    // 这个数据需要从FloorSet.ini预加载

    IF floorId NOT IN FloorMonsterData THEN
        RETURN []
    END IF

    data = FloorMonsterData[floorId]
    count = data.count[version]

    IF count == 0 THEN
        RETURN []
    END IF

    // 返回该版本允许的怪物ID列表
    RETURN data.ids[version][0..count-1]
END FUNCTION

// ========================================
// 8.4 Floor对象列表查询
// ========================================
FUNCTION GetFloorObjects(
    floorId: Integer,
    version: Integer
) RETURNS List<Integer>:
    // 从FloorObjectData表中查询
    // 这个数据需要从FloorSet.ini预加载

    IF floorId NOT IN FloorObjectData THEN
        RETURN []
    END IF

    data = FloorObjectData[floorId]
    count = data.count[version]

    IF count == 0 THEN
        RETURN []
    END IF

    // 返回该版本允许的对象ID列表
    RETURN data.ids[version][0..count-1]
END FUNCTION

// ========================================
// 8.5 NPC51子类型验证
// ========================================
FUNCTION IsValidNPC51(
    floorId: Integer,
    subtype: Integer
) RETURNS Boolean:
    // 从NPC51Name表中验证
    // 这个数据来自MyConst.pas中的NPC51Name数组
    // 格式: NPC51Name[floorId][subtype] = name

    IF floorId NOT IN NPC51Names THEN
        RETURN FALSE
    END IF

    names = NPC51Names[floorId]

    IF subtype >= LENGTH(names) THEN
        RETURN FALSE
    END IF

    name = names[subtype]

    // 空名称或"CRASH"表示无效
    IF name IS EMPTY OR name == "CRASH" THEN
        RETURN FALSE
    END IF

    RETURN TRUE
END FUNCTION
```

---

## 9. 完整流程示例

```
// ========================================
// 主程序入口示例
// ========================================
PROCEDURE Main():
    // 加载任务数据
    questPath = "quest.qst"
    scriptLines = LoadScriptFromFile(questPath)
    labels = ParseLabels(scriptLines)
    floors = LoadFloorsFromFile(questPath)
    opcodes = LoadOpcodeDefinitions("Asm.txt")
    questFiles = GetQuestFileList(questPath)
    episode = GetEpisode(scriptLines)

    // 加载预设数据
    LoadFloorMonsterData("FloorSet.ini")
    LoadFloorObjectData("FloorSet.ini")
    LoadNPC51Names("MyConst.pas")

    // 对所有版本进行检查
    allVersions = [DC_V1, DC_V2, PC, GC_EP12, BLUE_BURST]

    FOR EACH version IN allVersions DO
        PRINT "========================================="
        PRINT "Checking: " + version.displayName
        PRINT "========================================="

        // 执行兼容性检查
        result = TestCompatibility(
            version,
            scriptLines,
            labels,
            floors,
            opcodes,
            questFiles,
            episode
        )

        // 输出结果
        IF LENGTH(result.errors) == 0 AND LENGTH(result.warnings) == 0 THEN
            PRINT "✓ Fully compatible"
        ELSE
            IF LENGTH(result.errors) > 0 THEN
                PRINT "Errors:"
                FOR EACH error IN result.errors DO
                    PRINT "  ✗ " + error
                END FOR
            END IF

            IF LENGTH(result.warnings) > 0 THEN
                PRINT "Warnings:"
                FOR EACH warning IN result.warnings DO
                    PRINT "  ⚠ " + warning
                END FOR
            END IF
        END IF

        PRINT ""
    END FOR
END PROCEDURE
```

---

## 10. 数据结构定义

```
// ========================================
// 标签数据结构
// ========================================
STRUCTURE LabelData:
    dataLabels: Set<String>         // D_xxx 数据标签
    stringLabels: Set<String>       // S_xxx 字符串标签
    functionLabels: Set<String>     // F_xxx 函数标签

// ========================================
// 操作码结构
// ========================================
STRUCTURE Opcode:
    functionCode: Integer           // 操作码ID (如 0xF8BC)
    name: String                    // 操作码名称 (如 "set_episode")
    order: Integer                  // 指令类型 (T_DC, T_ARGS等)
    minVersion: Integer             // 最低支持版本 (0-3)
    argTypes: List<Integer>         // 参数类型列表 [T_REG, T_DWORD, ...]

// ========================================
// 怪物结构
// ========================================
STRUCTURE Monster:
    skin: Integer                   // 外观ID
    action: Float                   // 动作标签ID
    unknow7: Integer                // 子类型 (Skin 51使用)
    // ... 其他字段 (位置、旋转等)

// ========================================
// 对象结构
// ========================================
STRUCTURE FloorObject:
    skin: Integer                   // 对象外观ID
    action: Integer                 // 动作标签ID
    // ... 其他字段

// ========================================
// Floor结构
// ========================================
STRUCTURE Floor:
    floorId: Integer                // Floor ID (0-45)
    monsters: List<Monster>         // 怪物列表
    objects: List<FloorObject>      // 对象列表

// ========================================
// Floor数据表结构
// ========================================
STRUCTURE FloorIDData:
    count: Array<Integer>           // [version] -> 允许的数量
    ids: Array<Array<Integer>>      // [version][index] -> ID列表

// ========================================
// 全局数据表
// ========================================
GLOBAL FloorMonsterData: Map<Integer, FloorIDData>
GLOBAL FloorObjectData: Map<Integer, FloorIDData>
GLOBAL NPC51Names: Map<Integer, Array<String>>
```

---

## 11. 关键注意事项

### 11.1 脚本行格式
```
格式: "标签    操作码 参数"
       ^^^^^^^^
       前8字符

示例:
"0:      set_episode 00000001"
 ^^^^^^^^ 标签部分
         ^^^^^^^^^^^^^^^^^^^ 代码部分 (从索引8开始)
```

### 11.2 标签命名规则
```
D_123   -> 数据标签 (HEX数据)
S_456   -> 字符串标签 (STR数据)
F_789   -> 函数标签 (代码入口点)
```

### 11.3 版本ID映射
```
0 = DC V1       (Dreamcast Version 1)
1 = DC V2       (Dreamcast Version 2)
2 = PC          (PC Version)
3 = GC EP1&2    (GameCube Episode 1&2)
4 = Blue Burst  (PC Blue Burst)
```

### 11.4 Episode映射
```
0 = Episode 1   (Pioneer 1 - Forest, Caves, Mines, Ruins)
1 = Episode 2   (Pioneer 2 - VR Temple, Spaceship, CCA)
2 = Episode 4   (Pioneer 2 - Crater, Desert)
```

### 11.5 操作码示例
```
0xF8BC  -> set_episode      (设置Episode)
0xF841  -> get_npc_data     (获取NPC数据)
0xF892  -> get_physical_data (获取敌人物理数据)
0xF893  -> get_attack_data   (获取敌人攻击数据)
0xF894  -> get_resist_data   (获取敌人抗性数据)
0xF895  -> get_movement_data (获取敌人移动数据)
0xF8EE  -> 特殊操作码 (可能导致兼容性问题)
```

---

## 12. 优化建议

### 12.1 性能优化
```
1. 标签查找优化
   - 使用HashSet而非List进行标签存储
   - 时间复杂度: O(1) vs O(n)

2. 操作码查找优化
   - 使用Map<String, Opcode>按名称索引
   - 避免每次线性搜索

3. Floor数据缓存
   - 预先构建Floor -> 允许ID的映射表
   - 避免重复查询文件
```

### 12.2 并行处理
```
1. 版本并行检查
   - 5个版本可以并行检查
   - 使用线程池或协程

2. Floor并行检查
   - 多个Floor的怪物/对象检查可并行
   - 需要线程安全的结果聚合
```

### 12.3 内存优化
```
1. 字符串intern
   - 重复的操作码名称、标签名共享内存

2. 懒加载
   - Floor数据按需加载
   - NPC51数据按需查询
```