package com.pso.quest.compatibility

/**
 * Floor数据提供者实现
 *
 * 用于提供Floor特定的怪物/对象列表和NPC51验证
 * 数据来源：FloorSet.ini 和 MyConst.pas
 */
class FloorDataProviderImpl : FloorDataProvider {

    // Floor怪物数据表
    // 格式: floorId -> (version -> 允许的怪物ID列表)
    private val floorMonsterData: Map<Int, Map<Int, List<Int>>> = loadFloorMonsterData()

    // Floor对象数据表
    // 格式: floorId -> (version -> 允许的对象ID列表)
    private val floorObjectData: Map<Int, Map<Int, List<Int>>> = loadFloorObjectData()

    // NPC51名称表
    // 格式: floorId -> (subtype -> name)
    // 来自MyConst.pas的NPC51Name数组
    private val npc51Names: Map<Int, Map<Int, String>> = loadNPC51Names()

    override fun getFloorMonsters(floorId: Int, version: Int): List<Int>? {
        return floorMonsterData[floorId]?.get(version)
    }

    override fun getFloorObjects(floorId: Int, version: Int): List<Int>? {
        return floorObjectData[floorId]?.get(version)
    }

    override fun isValidNPC51(floorId: Int, subtype: Int): Boolean {
        val names = npc51Names[floorId] ?: return false
        val name = names[subtype] ?: return false
        return name.isNotEmpty() && name != "CRASH"
    }

    companion object {
        /**
         * 从配置文件加载Floor怪物数据
         * 实际项目中应该从FloorSet.ini或数据库加载
         */
        private fun loadFloorMonsterData(): Map<Int, Map<Int, List<Int>>> {
            // 示例数据 - 实际应该从文件读取
            return mapOf(
                // Floor 0 (Forest 1) - 不同版本允许的怪物
                0 to mapOf(
                    0 to listOf(64, 65, 67, 68, 96, 97, 98, 99, 128, 129, 131, 133), // DC V1
                    1 to listOf(64, 65, 67, 68, 96, 97, 98, 99, 128, 129, 131, 133), // DC V2
                    2 to listOf(64, 65, 67, 68, 96, 97, 98, 99, 128, 129, 131, 133), // PC
                    3 to listOf(64, 65, 67, 68, 96, 97, 98, 99, 128, 129, 131, 133), // GC
                    4 to listOf(64, 65, 67, 68, 96, 97, 98, 99, 128, 129, 131, 133)  // BB
                ),
                // Floor 1 (Forest 2)
                1 to mapOf(
                    0 to listOf(64, 65, 67, 68, 96, 97, 98, 99, 128, 129, 131, 133),
                    1 to listOf(64, 65, 67, 68, 96, 97, 98, 99, 128, 129, 131, 133),
                    2 to listOf(64, 65, 67, 68, 96, 97, 98, 99, 128, 129, 131, 133),
                    3 to listOf(64, 65, 67, 68, 96, 97, 98, 99, 128, 129, 131, 133),
                    4 to listOf(64, 65, 67, 68, 96, 97, 98, 99, 128, 129, 131, 133)
                )
                // ... 更多Floor数据
            )
        }

        /**
         * 从配置文件加载Floor对象数据
         */
        private fun loadFloorObjectData(): Map<Int, Map<Int, List<Int>>> {
            // 示例数据 - 实际应该从文件读取
            return mapOf(
                0 to mapOf(
                    0 to listOf(135, 136, 137, 138, 139, 140, 192, 193, 194),
                    1 to listOf(135, 136, 137, 138, 139, 140, 192, 193, 194),
                    2 to listOf(135, 136, 137, 138, 139, 140, 192, 193, 194),
                    3 to listOf(135, 136, 137, 138, 139, 140, 192, 193, 194),
                    4 to listOf(135, 136, 137, 138, 139, 140, 192, 193, 194)
                ),
                1 to mapOf(
                    0 to listOf(135, 136, 137, 138, 139, 140, 192, 193, 194),
                    1 to listOf(135, 136, 137, 138, 139, 140, 192, 193, 194),
                    2 to listOf(135, 136, 137, 138, 139, 140, 192, 193, 194),
                    3 to listOf(135, 136, 137, 138, 139, 140, 192, 193, 194),
                    4 to listOf(135, 136, 137, 138, 139, 140, 192, 193, 194)
                )
                // ... 更多Floor数据
            )
        }

        /**
         * 从MyConst.pas加载NPC51名称表
         */
        private fun loadNPC51Names(): Map<Int, Map<Int, String>> {
            // 来自MyConst.pas的NPC51Name数组
            // NPC51Name: array[0..45, 0..15] of string
            return mapOf(
                // Floor 0
                0 to mapOf(
                    0 to "", 1 to "", 2 to "", 3 to "", 4 to "",
                    5 to "", 6 to "", 7 to "", 8 to "", 9 to "",
                    10 to "", 11 to "", 12 to "", 13 to "", 14 to "", 15 to ""
                ),
                // Floor 1 (Forest 1)
                1 to mapOf(
                    0 to "Forest Box",
                    1 to "Booma",
                    2 to "Gigobooma",
                    3 to "Gobooma",
                    4 to "Rag Rappy",
                    5 to "Al Rappy",
                    6 to "Mothmant",
                    7 to "Monest",
                    8 to "Barbarous Wolf",
                    9 to "Savage Wolf",
                    10 to "Chao npc",
                    11 to "Crashed Probe",
                    12 to "Crashed Probe on side",
                    13 to "CRASH",
                    14 to "CRASH",
                    15 to "CRASH"
                ),
                // Floor 2 (Forest 2)
                2 to mapOf(
                    0 to "Forest Box",
                    1 to "Booma",
                    2 to "Gigobooma",
                    3 to "Gobooma",
                    4 to "Rag Rappy",
                    5 to "Al Rappy",
                    6 to "Mothmant",
                    7 to "Monest",
                    8 to "Barbarous Wolf",
                    9 to "Savage Wolf",
                    10 to "Chao npc",
                    11 to "Mini Hidebear",
                    12 to "Hidebear",
                    13 to "Hildeblue",
                    14 to "Crashed Probe",
                    15 to "Crashed Probe on side"
                ),
                // Floor 3 (Caves 1)
                3 to mapOf(
                    0 to "Caves Box",
                    1 to "Nano Dragon",
                    2 to "Pan Arms",
                    3 to "Hidoom",
                    4 to "Migium",
                    5 to "Pal Shark",
                    6 to "Guil Shark",
                    7 to "Evil Shark",
                    8 to "Grass Assasin",
                    9 to "Mini Grass Assasin",
                    10 to "Poison Lilly",
                    11 to "CRASH",
                    12 to "CRASH",
                    13 to "CRASH",
                    14 to "CRASH",
                    15 to "CRASH"
                )
                // ... 更多Floor的NPC51数据
            )
        }
    }
}

/**
 * 从文件加载Floor数据的实用工具类
 */
object FloorDataLoader {

    /**
     * 从FloorSet.ini文件加载Floor数据
     *
     * FloorSet.ini格式示例:
     * [Floor0_Monsters]
     * V1=64,65,67,68,96,97,98,99
     * V2=64,65,67,68,96,97,98,99
     * V3=64,65,67,68,96,97,98,99
     */
    fun loadFromIniFile(filePath: String): Pair<
        Map<Int, Map<Int, List<Int>>>,  // Monster data
        Map<Int, Map<Int, List<Int>>>   // Object data
    > {
        // TODO: 实现从INI文件读取
        // 这里返回空数据作为示例
        return emptyMap<Int, Map<Int, List<Int>>>() to emptyMap()
    }

    /**
     * 从JSON文件加载Floor数据
     *
     * JSON格式示例:
     * {
     *   "floors": {
     *     "0": {
     *       "monsters": {
     *         "0": [64, 65, 67, 68],
     *         "1": [64, 65, 67, 68]
     *       },
     *       "objects": {
     *         "0": [135, 136, 137],
     *         "1": [135, 136, 137]
     *       }
     *     }
     *   }
     * }
     */
    fun loadFromJsonFile(filePath: String): Pair<
        Map<Int, Map<Int, List<Int>>>,
        Map<Int, Map<Int, List<Int>>>
    > {
        // TODO: 实现从JSON文件读取
        return emptyMap<Int, Map<Int, List<Int>>>() to emptyMap()
    }
}

/**
 * 简化的Floor数据提供者（用于测试）
 */
class SimpleFloorDataProvider : FloorDataProvider {

    override fun getFloorMonsters(floorId: Int, version: Int): List<Int>? {
        // 返回null表示不检查Floor限制
        return null
    }

    override fun getFloorObjects(floorId: Int, version: Int): List<Int>? {
        // 返回null表示不检查Floor限制
        return null
    }

    override fun isValidNPC51(floorId: Int, subtype: Int): Boolean {
        // 简单验证：subtype <= 15即有效
        return subtype <= 15
    }
}