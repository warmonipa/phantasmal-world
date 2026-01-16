# PSO Quest 兼容性检查 - Kotlin集成指南

## 目录
1. [快速开始](#1-快速开始)
2. [完整使用示例](#2-完整使用示例)
3. [数据加载](#3-数据加载)
4. [高级用法](#4-高级用法)
5. [性能优化](#5-性能优化)
6. [错误处理](#6-错误处理)

---

## 1. 快速开始

### 1.1 基础依赖

```kotlin
// 无需额外依赖，使用Kotlin标准库即可
```

### 1.2 最简使用

```kotlin
import com.pso.quest.compatibility.*

fun main() {
    // 1. 准备脚本数据
    val scriptLines = listOf(
        "0:      set_episode 00000001",
        "        leti R0, 00000001"
    )

    // 2. 解析标签
    val labels = parseLabels(scriptLines)

    // 3. 创建Quest数据
    val questData = QuestData(
        scriptLines = scriptLines,
        labels = labels,
        floors = emptyList(),
        opcodes = emptyList(),
        questFiles = listOf("quest.bin"),
        episode = detectEpisode(scriptLines)
    )

    // 4. 执行检查
    val checker = PSOCompatibilityChecker()
    val result = checker.checkCompatibility(PSOVersion.BLUE_BURST, questData)

    // 5. 输出结果
    if (result.isFullyCompatible) {
        println("✓ Fully compatible")
    } else {
        result.errors.forEach { println("✗ $it") }
        result.warnings.forEach { println("⚠ $it") }
    }
}
```

---

## 2. 完整使用示例

### 2.1 从文件加载Quest

```kotlin
class QuestLoader {

    /**
     * 从.qst文件加载Quest数据
     */
    fun loadQuest(filePath: String): QuestData {
        val file = File(filePath)

        // 1. 读取脚本部分
        val scriptLines = loadScriptLines(file)

        // 2. 解析标签
        val labels = parseLabels(scriptLines)

        // 3. 加载Floor数据
        val floors = loadFloors(file)

        // 4. 加载操作码定义
        val opcodes = loadOpcodes("Asm.txt")

        // 5. 获取文件列表
        val questFiles = getQuestFiles(file)

        // 6. 检测Episode
        val episode = detectEpisode(scriptLines)

        return QuestData(
            scriptLines = scriptLines,
            labels = labels,
            floors = floors,
            opcodes = opcodes,
            questFiles = questFiles,
            episode = episode
        )
    }

    private fun loadScriptLines(file: File): List<String> {
        // TODO: 实现从.qst文件读取脚本
        // 脚本通常以某种二进制格式存储，需要解析
        return emptyList()
    }

    private fun loadFloors(file: File): List<Floor> {
        // TODO: 实现从.qst文件读取Floor数据
        return emptyList()
    }

    private fun loadOpcodes(asmFile: String): List<Opcode> {
        // 从Asm.txt加载操作码定义
        val opcodes = mutableListOf<Opcode>()

        File(asmFile).forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size >= 4) {
                val opcode = Opcode(
                    functionCode = parts[0].toIntOrNull(16) ?: 0,
                    name = parts[1],
                    order = parts[2].toIntOrNull() ?: 0,
                    minVersion = parts[3].toIntOrNull() ?: 0,
                    argTypes = parseArgTypes(parts.getOrNull(4) ?: "")
                )
                opcodes.add(opcode)
            }
        }

        return opcodes
    }

    private fun parseArgTypes(argString: String): List<Int> {
        // 解析参数类型字符串
        // 例如: "REG,DWORD,FUNC" -> [T_REG, T_DWORD, T_FUNC]
        return argString.split(',')
            .mapNotNull { mapArgType(it.trim()) }
    }

    private fun mapArgType(type: String): Int? {
        return when (type.uppercase()) {
            "NONE" -> ArgType.T_NONE
            "REG" -> ArgType.T_REG
            "DWORD" -> ArgType.T_DWORD
            "FUNC" -> ArgType.T_FUNC
            "FUNC2" -> ArgType.T_FUNC2
            "DATA" -> ArgType.T_DATA
            "STR" -> ArgType.T_STR
            "STRDATA" -> ArgType.T_STRDATA
            "SWITCH" -> ArgType.T_SWITCH
            else -> null
        }
    }

    private fun getQuestFiles(file: File): List<String> {
        // 获取Quest包含的所有文件
        return listOf("${file.nameWithoutExtension}.bin")
    }
}
```

### 2.2 批量检查多个版本

```kotlin
class BatchCompatibilityChecker {

    fun checkAllVersions(questData: QuestData): Map<PSOVersion, CompatibilityResult> {
        val checker = PSOCompatibilityChecker(
            floorDataProvider = FloorDataProviderImpl()
        )

        return PSOVersion.values().associateWith { version ->
            checker.checkCompatibility(version, questData)
        }
    }

    fun printResults(results: Map<PSOVersion, CompatibilityResult>) {
        results.forEach { (version, result) ->
            println("=" .repeat(60))
            println("Version: ${version.displayName}")
            println("=" .repeat(60))

            when {
                result.isFullyCompatible -> {
                    println("✓ Fully compatible")
                }
                else -> {
                    if (result.errors.isNotEmpty()) {
                        println("\n❌ Errors (${result.errors.size}):")
                        result.errors.forEachIndexed { i, error ->
                            println("  ${i + 1}. $error")
                        }
                    }

                    if (result.warnings.isNotEmpty()) {
                        println("\n⚠️  Warnings (${result.warnings.size}):")
                        result.warnings.forEachIndexed { i, warning ->
                            println("  ${i + 1}. $warning")
                        }
                    }
                }
            }
            println()
        }
    }

    fun generateReport(results: Map<PSOVersion, CompatibilityResult>): String {
        val sb = StringBuilder()

        sb.appendLine("# PSO Quest Compatibility Report")
        sb.appendLine()
        sb.appendLine("Generated: ${java.time.LocalDateTime.now()}")
        sb.appendLine()

        results.forEach { (version, result) ->
            sb.appendLine("## ${version.displayName}")
            sb.appendLine()

            if (result.isFullyCompatible) {
                sb.appendLine("✓ **Fully compatible**")
            } else {
                if (result.errors.isNotEmpty()) {
                    sb.appendLine("### Errors")
                    result.errors.forEach { error ->
                        sb.appendLine("- ❌ $error")
                    }
                    sb.appendLine()
                }

                if (result.warnings.isNotEmpty()) {
                    sb.appendLine("### Warnings")
                    result.warnings.forEach { warning ->
                        sb.appendLine("- ⚠️ $warning")
                    }
                    sb.appendLine()
                }
            }
            sb.appendLine()
        }

        return sb.toString()
    }
}
```

---

## 3. 数据加载

### 3.1 加载操作码定义

```kotlin
/**
 * 操作码加载器
 */
object OpcodeLoader {

    /**
     * 从Asm.txt加载操作码定义
     *
     * 文件格式示例:
     * 0x01    nop         T_NONE      0
     * 0xF8BC  set_episode T_ARGS      0   DWORD
     */
    fun loadFromFile(filePath: String): List<Opcode> {
        val opcodes = mutableListOf<Opcode>()

        File(filePath).useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    return@forEach
                }

                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 4) {
                    val opcode = Opcode(
                        functionCode = parseHex(parts[0]),
                        name = parts[1],
                        order = mapOrderType(parts[2]),
                        minVersion = parts[3].toIntOrNull() ?: 0,
                        argTypes = if (parts.size > 4) {
                            parseArgTypes(parts.drop(4))
                        } else {
                            emptyList()
                        }
                    )
                    opcodes.add(opcode)
                }
            }
        }

        return opcodes
    }

    private fun parseHex(hex: String): Int {
        return hex.removePrefix("0x").toIntOrNull(16) ?: 0
    }

    private fun mapOrderType(type: String): Int {
        return when (type) {
            "T_NONE" -> ArgType.T_NONE
            "T_IMED" -> ArgType.T_IMED
            "T_ARGS" -> ArgType.T_ARGS
            "T_DC" -> ArgType.T_DC
            else -> ArgType.T_NONE
        }
    }

    private fun parseArgTypes(types: List<String>): List<Int> {
        return types.mapNotNull { type ->
            when (type) {
                "NONE" -> ArgType.T_NONE
                "REG" -> ArgType.T_REG
                "DWORD" -> ArgType.T_DWORD
                "FUNC" -> ArgType.T_FUNC
                "FUNC2" -> ArgType.T_FUNC2
                "DATA" -> ArgType.T_DATA
                "STR" -> ArgType.T_STR
                "STRDATA" -> ArgType.T_STRDATA
                "SWITCH" -> ArgType.T_SWITCH
                else -> null
            }
        }
    }
}
```

### 3.2 加载Floor数据

```kotlin
/**
 * Floor数据加载器
 */
object FloorDataLoader {

    /**
     * 从FloorSet.ini加载
     */
    fun loadFloorData(filePath: String): Pair<
        Map<Int, Map<Int, List<Int>>>,  // Monster data
        Map<Int, Map<Int, List<Int>>>   // Object data
    > {
        val monsterData = mutableMapOf<Int, MutableMap<Int, List<Int>>>()
        val objectData = mutableMapOf<Int, MutableMap<Int, List<Int>>>()

        var currentFloor = -1
        var currentType = ""

        File(filePath).forEachLine { line ->
            val trimmed = line.trim()

            // 解析section header: [Floor0_Monsters]
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val section = trimmed.substring(1, trimmed.length - 1)
                val parts = section.split("_")

                if (parts.size == 2) {
                    currentFloor = parts[0].removePrefix("Floor").toIntOrNull() ?: -1
                    currentType = parts[1]
                }
                return@forEachLine
            }

            // 解析数据行: V0=64,65,67,68
            if (trimmed.contains("=") && currentFloor >= 0) {
                val (version, idsStr) = trimmed.split("=", limit = 2)
                val versionNum = version.removePrefix("V").toIntOrNull() ?: return@forEachLine
                val ids = idsStr.split(",")
                    .mapNotNull { it.trim().toIntOrNull() }

                when (currentType) {
                    "Monsters" -> {
                        monsterData.getOrPut(currentFloor) { mutableMapOf() }[versionNum] = ids
                    }
                    "Objects" -> {
                        objectData.getOrPut(currentFloor) { mutableMapOf() }[versionNum] = ids
                    }
                }
            }
        }

        return monsterData to objectData
    }
}
```

---

## 4. 高级用法

### 4.1 自定义FloorDataProvider

```kotlin
class CustomFloorDataProvider(
    private val monsterData: Map<Int, Map<Int, List<Int>>>,
    private val objectData: Map<Int, Map<Int, List<Int>>>,
    private val npc51Data: Map<Int, Map<Int, String>>
) : FloorDataProvider {

    override fun getFloorMonsters(floorId: Int, version: Int): List<Int>? {
        return monsterData[floorId]?.get(version)
    }

    override fun getFloorObjects(floorId: Int, version: Int): List<Int>? {
        return objectData[floorId]?.get(version)
    }

    override fun isValidNPC51(floorId: Int, subtype: Int): Boolean {
        val names = npc51Data[floorId] ?: return false
        val name = names[subtype] ?: return false
        return name.isNotEmpty() && name != "CRASH"
    }

    companion object {
        fun fromFiles(
            floorSetPath: String,
            npc51Path: String
        ): CustomFloorDataProvider {
            val (monsterData, objectData) = FloorDataLoader.loadFloorData(floorSetPath)
            val npc51Data = loadNPC51Data(npc51Path)

            return CustomFloorDataProvider(monsterData, objectData, npc51Data)
        }

        private fun loadNPC51Data(path: String): Map<Int, Map<Int, String>> {
            // TODO: 从文件加载NPC51数据
            return emptyMap()
        }
    }
}
```

### 4.2 并发检查

```kotlin
import kotlinx.coroutines.*

class ConcurrentCompatibilityChecker(
    private val floorDataProvider: FloorDataProvider? = null
) {

    /**
     * 并发检查所有版本
     */
    suspend fun checkAllVersionsConcurrent(
        questData: QuestData
    ): Map<PSOVersion, CompatibilityResult> = coroutineScope {
        val checker = PSOCompatibilityChecker(floorDataProvider)

        PSOVersion.values().associateWith { version ->
            async(Dispatchers.Default) {
                checker.checkCompatibility(version, questData)
            }
        }.mapValues { it.value.await() }
    }

    /**
     * 并发检查多个Quest
     */
    suspend fun checkMultipleQuests(
        quests: List<QuestData>
    ): List<Map<PSOVersion, CompatibilityResult>> = coroutineScope {
        quests.map { quest ->
            async(Dispatchers.Default) {
                checkAllVersionsConcurrent(quest)
            }
        }.awaitAll()
    }
}

// 使用示例
suspend fun main() {
    val checker = ConcurrentCompatibilityChecker()
    val questData = loadQuestData()

    // 并发检查所有版本
    val results = checker.checkAllVersionsConcurrent(questData)

    results.forEach { (version, result) ->
        println("${version.displayName}: ${if (result.isFullyCompatible) "✓" else "✗"}")
    }
}
```

### 4.3 结果过滤和分组

```kotlin
/**
 * 兼容性结果分析器
 */
class CompatibilityAnalyzer {

    /**
     * 获取完全兼容的版本
     */
    fun getCompatibleVersions(
        results: Map<PSOVersion, CompatibilityResult>
    ): List<PSOVersion> {
        return results.filter { it.value.isFullyCompatible }.keys.toList()
    }

    /**
     * 获取有错误的版本
     */
    fun getVersionsWithErrors(
        results: Map<PSOVersion, CompatibilityResult>
    ): Map<PSOVersion, List<String>> {
        return results
            .filter { it.value.errors.isNotEmpty() }
            .mapValues { it.value.errors }
    }

    /**
     * 按错误类型分组
     */
    fun groupErrorsByType(
        results: Map<PSOVersion, CompatibilityResult>
    ): Map<String, List<PSOVersion>> {
        val errorGroups = mutableMapOf<String, MutableList<PSOVersion>>()

        results.forEach { (version, result) ->
            result.errors.forEach { error ->
                val errorType = extractErrorType(error)
                errorGroups.getOrPut(errorType) { mutableListOf() }.add(version)
            }
        }

        return errorGroups
    }

    private fun extractErrorType(error: String): String {
        return when {
            error.contains("Label") -> "Label Error"
            error.contains("Opcode") -> "Opcode Error"
            error.contains("Episode") -> "Episode Error"
            error.contains("Skin") -> "Monster/Object Error"
            else -> "Other Error"
        }
    }

    /**
     * 生成统计信息
     */
    fun generateStatistics(
        results: Map<PSOVersion, CompatibilityResult>
    ): CompatibilityStatistics {
        return CompatibilityStatistics(
            totalVersions = results.size,
            compatibleVersions = results.count { it.value.isFullyCompatible },
            totalErrors = results.values.sumOf { it.errors.size },
            totalWarnings = results.values.sumOf { it.warnings.size },
            errorsByVersion = results.mapValues { it.value.errors.size },
            warningsByVersion = results.mapValues { it.value.warnings.size }
        )
    }
}

data class CompatibilityStatistics(
    val totalVersions: Int,
    val compatibleVersions: Int,
    val totalErrors: Int,
    val totalWarnings: Int,
    val errorsByVersion: Map<PSOVersion, Int>,
    val warningsByVersion: Map<PSOVersion, Int>
)
```

---

## 5. 性能优化

### 5.1 缓存操作码查找

```kotlin
class CachedPSOCompatibilityChecker(
    floorDataProvider: FloorDataProvider? = null
) : PSOCompatibilityChecker(floorDataProvider) {

    // 缓存操作码名称 -> 操作码对象的映射
    private val opcodeCache = mutableMapOf<String, Opcode>()

    override fun checkCompatibility(
        version: PSOVersion,
        questData: QuestData
    ): CompatibilityResult {
        // 构建缓存
        if (opcodeCache.isEmpty()) {
            questData.opcodes.forEach { opcode ->
                opcodeCache[opcode.name.lowercase()] = opcode
            }
        }

        return super.checkCompatibility(version, questData)
    }
}
```

### 5.2 懒加载Floor数据

```kotlin
class LazyFloorDataProvider(
    private val dataPath: String
) : FloorDataProvider {

    private val monsterDataCache by lazy {
        FloorDataLoader.loadFloorData("$dataPath/monsters.ini").first
    }

    private val objectDataCache by lazy {
        FloorDataLoader.loadFloorData("$dataPath/objects.ini").second
    }

    private val npc51Cache by lazy {
        loadNPC51Data("$dataPath/npc51.json")
    }

    override fun getFloorMonsters(floorId: Int, version: Int): List<Int>? {
        return monsterDataCache[floorId]?.get(version)
    }

    override fun getFloorObjects(floorId: Int, version: Int): List<Int>? {
        return objectDataCache[floorId]?.get(version)
    }

    override fun isValidNPC51(floorId: Int, subtype: Int): Boolean {
        val names = npc51Cache[floorId] ?: return false
        val name = names[subtype] ?: return false
        return name.isNotEmpty() && name != "CRASH"
    }

    private fun loadNPC51Data(path: String): Map<Int, Map<Int, String>> {
        // 实现加载逻辑
        return emptyMap()
    }
}
```

---

## 6. 错误处理

### 6.1 异常安全的检查

```kotlin
class SafeCompatibilityChecker(
    floorDataProvider: FloorDataProvider? = null
) {

    private val checker = PSOCompatibilityChecker(floorDataProvider)

    fun checkCompatibilitySafe(
        version: PSOVersion,
        questData: QuestData
    ): Result<CompatibilityResult> {
        return runCatching {
            checker.checkCompatibility(version, questData)
        }
    }

    fun checkAllVersionsSafe(
        questData: QuestData
    ): Map<PSOVersion, Result<CompatibilityResult>> {
        return PSOVersion.values().associateWith { version ->
            checkCompatibilitySafe(version, questData)
        }
    }
}

// 使用示例
fun main() {
    val checker = SafeCompatibilityChecker()
    val questData = loadQuestData()

    checker.checkAllVersionsSafe(questData).forEach { (version, result) ->
        result.fold(
            onSuccess = { compatResult ->
                println("${version.displayName}: ${compatResult.errors.size} errors")
            },
            onFailure = { error ->
                println("${version.displayName}: Check failed - ${error.message}")
            }
        )
    }
}
```

---

## 7. 完整应用示例

```kotlin
import com.pso.quest.compatibility.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 1. 加载数据
    println("Loading quest data...")
    val questLoader = QuestLoader()
    val questData = questLoader.loadQuest("quest.qst")

    // 2. 创建FloorDataProvider
    val floorDataProvider = CustomFloorDataProvider.fromFiles(
        floorSetPath = "FloorSet.ini",
        npc51Path = "npc51.json"
    )

    // 3. 创建检查器
    val checker = ConcurrentCompatibilityChecker(floorDataProvider)

    // 4. 执行并发检查
    println("Checking compatibility...")
    val results = checker.checkAllVersionsConcurrent(questData)

    // 5. 分析结果
    val analyzer = CompatibilityAnalyzer()
    val statistics = analyzer.generateStatistics(results)

    // 6. 输出报告
    println("\n" + "=".repeat(60))
    println("COMPATIBILITY CHECK REPORT")
    println("=".repeat(60))
    println("Total Versions Checked: ${statistics.totalVersions}")
    println("Compatible Versions: ${statistics.compatibleVersions}")
    println("Total Errors: ${statistics.totalErrors}")
    println("Total Warnings: ${statistics.totalWarnings}")
    println()

    // 7. 详细结果
    val batchChecker = BatchCompatibilityChecker()
    batchChecker.printResults(results)

    // 8. 生成Markdown报告
    val report = batchChecker.generateReport(results)
    File("compatibility_report.md").writeText(report)
    println("Report saved to: compatibility_report.md")
}
```

---

## 8. 测试示例

```kotlin
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PSOCompatibilityCheckerTest {

    @Test
    fun `test label 0 missing error`() {
        val questData = QuestData(
            scriptLines = listOf("1:      nop"),
            labels = LabelData(),
            floors = emptyList(),
            opcodes = emptyList(),
            questFiles = listOf("quest.bin"),
            episode = 0
        )

        val checker = PSOCompatibilityChecker()
        val result = checker.checkCompatibility(PSOVersion.BLUE_BURST, questData)

        assertTrue(result.errors.any { it.contains("Label 0") })
    }

    @Test
    fun `test episode parameter check`() {
        val questData = QuestData(
            scriptLines = listOf(
                "0:      set_episode 00000002"
            ),
            labels = LabelData(functionLabels = setOf("F_0")),
            floors = emptyList(),
            opcodes = listOf(
                Opcode(
                    functionCode = 0xF8BC,
                    name = "set_episode",
                    order = ArgType.T_ARGS,
                    minVersion = 0,
                    argTypes = listOf(ArgType.T_DWORD)
                )
            ),
            questFiles = listOf("quest.bin"),
            episode = 2
        )

        val checker = PSOCompatibilityChecker()
        val result = checker.checkCompatibility(PSOVersion.PC, questData)

        assertTrue(result.errors.any { it.contains("Episode 4") })
    }

    @Test
    fun `test fully compatible quest`() {
        val questData = QuestData(
            scriptLines = listOf("0:      nop"),
            labels = LabelData(functionLabels = setOf("F_0")),
            floors = emptyList(),
            opcodes = emptyList(),
            questFiles = listOf("quest.bin"),
            episode = 0
        )

        val checker = PSOCompatibilityChecker()
        val result = checker.checkCompatibility(PSOVersion.BLUE_BURST, questData)

        assertTrue(result.isFullyCompatible)
    }
}
```

---

## 9. 常见问题

### Q1: 如何加载自定义的操作码定义？
A: 使用`OpcodeLoader.loadFromFile()`或实现自定义加载逻辑。

### Q2: 如何跳过Floor限制检查？
A: 不传递`FloorDataProvider`，或使用`SimpleFloorDataProvider`。

### Q3: 如何提高检查性能？
A: 使用并发检查、缓存操作码、懒加载Floor数据。

### Q4: 如何自定义错误消息？
A: 继承`PSOCompatibilityChecker`并重写相关方法。

---

## 10. 后续步骤

1. 实现Quest文件的二进制解析器
2. 完善Floor数据加载
3. 添加更多单元测试
4. 集成到GUI应用
5. 添加性能监控