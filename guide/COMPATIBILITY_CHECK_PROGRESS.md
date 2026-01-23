# PSO Compatibility Check 实现完成度报告

本文档跟踪 compatibility check 功能的实现进度，基于 `PSO_COMPATIBILITY_CHECK_LOGIC.md` 规范。

---

## 整体架构

| 组件                   | 状态      | 文件位置                                               |
|----------------------|---------|----------------------------------------------------|
| PSOVersion 枚举        | ✅ 完成    | `psolib/.../compatibility/PSOVersion.kt`           |
| CompatibilityResult  | ✅ 完成    | `psolib/.../compatibility/CompatibilityResult.kt`  |
| ProblemType 枚举       | ✅ 完成    | `psolib/.../compatibility/CompatibilityResult.kt`  |
| ProblemLocation      | ✅ 完成    | `psolib/.../compatibility/CompatibilityResult.kt`  |
| FloorDataProvider 接口 | ⚠️ 仅框架  | `psolib/.../compatibility/FloorDataProvider.kt`    |
| CompatibilityChecker | ⚠️ 部分完成 | `psolib/.../compatibility/CompatibilityChecker.kt` |

---

## 脚本检查 (Section 4.1-4.3)

| 检查项              | guide规范 | 实现状态  | 备注                           |
|------------------|---------|-------|------------------------------|
| Label 0 存在性      | 4.1     | ✅ 完成  | 错误: MISSING_LABEL_0          |
| 转换指令警告           | 4.2.1   | ✅ 完成  | CONVERT_OPCODES 集合完整         |
| set_episode 参数检查 | 4.2.2   | ✅ 完成  | V1仅Ep1, PC不支持Ep4             |
| 操作码版本检查          | 4.2.3   | ⚠️ 部分 | 仅检查 `bb_*` 前缀，未使用 opcode.ver |
| 特殊操作码警告 (0xF8EE) | 4.2.4   | ✅ 完成  | SPECIAL_WARNING_OPCODE       |
| 参数类型匹配 (T_ARGS)  | 4.3.1   | ❌ 未实现 | T_REG vs T_DWORD 检查          |
| 标签引用检查           | 4.3.2   | ✅ 完成  | LabelType 参数检查               |
| Switch语句检查       | 4.3.3   | ❌ 未实现 | 数组条目数量验证                     |
| 字符串标记检查          | 4.3.4   | ❌ 未实现 | `<>` 匹配检查                    |

---

## NPC/怪物检查 (Section 4.4-4.6)

| 检查项          | guide规范 | 实现状态  | 备注                        |
|--------------|---------|-------|---------------------------|
| NPC动作标签验证    | 4.4     | ✅ 完成  | DefaultLabels 定义完整        |
| Skin 51 特殊检查 | 4.5     | ✅ 完成  | V1警告, Ep2警告, subtype验证    |
| Floor怪物兼容性   | 4.6     | ⚠️ 框架 | FloorDataProvider 返回 null |

---

## 对象检查 (Section 4.7)

| 检查项        | guide规范 | 实现状态  | 备注                        |
|------------|---------|-------|---------------------------|
| Floor对象兼容性 | 4.7     | ⚠️ 框架 | FloorDataProvider 返回 null |

---

## 数量限制检查 (Section 4.8)

| 检查项        | guide规范 | 实现状态 | 备注                |
|------------|---------|------|-------------------|
| 怪物数量 > 400 | 4.8     | ✅ 完成 | TOO_MANY_MONSTERS |
| 对象数量 > 400 | 4.8     | ✅ 完成 | TOO_MANY_OBJECTS  |

---

## 数据标签检查 (Section 4.9)

| 检查项     | guide规范 | 实现状态  | 备注                  |
|---------|---------|-------|---------------------|
| 未使用数据标签 | 4.9     | ❌ 未实现 | GetReferenceType 逻辑 |

---

## 文件检查 (Section 4.10)

| 检查项       | guide规范 | 实现状态  | 备注        |
|-----------|---------|-------|-----------|
| .bin 文件必需 | 4.10    | ❌ 未实现 |           |
| .dat 文件推荐 | 4.10    | ❌ 未实现 |           |
| .pvr 文件限制 | 4.10    | ❌ 未实现 | ver>1 不支持 |

---

## Web UI 层

| 组件                      | 状态   | 文件                                               |
|-------------------------|------|--------------------------------------------------|
| CompatibilityController | ✅ 完成 | `web/.../controllers/CompatibilityController.kt` |
| CompatibilityDialog     | ✅ 完成 | `web/.../widgets/CompatibilityDialog.kt`         |
| CompatibilityWidget     | ✅ 完成 | `web/.../widgets/CompatibilityWidget.kt`         |

---

## 测试覆盖

| 测试项          | 状态 | 文件                             |
|--------------|----|--------------------------------|
| Label 0 检查   | ✅  | `CompatibilityCheckerTests.kt` |
| Episode 兼容性  | ✅  | `CompatibilityCheckerTests.kt` |
| 数量限制         | ✅  | `CompatibilityCheckerTests.kt` |
| NPC 标签检查     | ✅  | `CompatibilityCheckerTests.kt` |
| BB opcode 检查 | ✅  | `CompatibilityCheckerTests.kt` |
| 标签引用检查       | ✅  | `CompatibilityCheckerTests.kt` |

---

## 完成度统计

| 类别       | 完成/总数    | 百分比          |
|----------|----------|--------------|
| 脚本检查     | 5/9      | **56%**      |
| NPC/怪物检查 | 2/3      | **67%**      |
| 对象检查     | 0/1      | **0%** (仅框架) |
| 数量限制     | 2/2      | **100%**     |
| 数据标签检查   | 0/1      | **0%**       |
| 文件检查     | 0/3      | **0%**       |
| **总计**   | **9/19** | **~47%**     |

---

## 待实现功能清单

### 高优先级

1. **完整的操作码版本检查**
    - 位置: `CompatibilityChecker.kt:checkInstruction()`
    - 需要: 使用 `opcode.ver` 属性而非仅检查 `bb_` 前缀
    - 参考: guide 4.2.3

2. **FloorDataProvider 完整实现**
    - 位置: 新建 `FloorDataProviderImpl.kt`
    - 需要: 解析 FloorSet.ini 数据
    - 影响: Floor怪物兼容性 (4.6) 和 Floor对象兼容性 (4.7)

### 中优先级

3. **参数类型匹配检查**
    - 位置: `CompatibilityChecker.kt:checkInstruction()`
    - 需要: 检查 T_REG vs T_DWORD 参数类型
    - 参考: guide 4.3.1

4. **Switch语句检查**
    - 位置: `CompatibilityChecker.kt:checkInstruction()`
    - 需要: 验证 switch 数组条目数量
    - 参考: guide 4.3.3

5. **字符串标记检查**
    - 位置: `CompatibilityChecker.kt:checkInstruction()`
    - 需要: 检查 `<>` 标记匹配
    - 参考: guide 4.3.4

### 低优先级

6. **未使用数据标签检查**
    - 位置: `CompatibilityChecker.kt` 新方法
    - 需要: 实现 GetReferenceType 逻辑
    - 参考: guide 4.9, 5.1

7. **文件检查**
    - 位置: `CompatibilityChecker.kt` 新方法
    - 需要: 检查 .bin/.dat/.pvr 文件
    - 参考: guide 4.10
    - 注意: 需要 Quest 对象包含文件列表信息

---

## 已知问题

1. **bb_map_designate 只显示一个错误**
    - 症状: 任务中有4个 bb_map_designate，但只显示1个错误
    - 可能原因: `textModel` 为 null 或 `assemble` 失败时回退到 `quest.bytecodeIr`
    - 需要调查: `CompatibilityController.checkAllVersions()` 中的 bytecodeIr 获取逻辑

---

## 更新日志

| 日期         | 更新内容                                                        |
|------------|-------------------------------------------------------------|
| 2026-01-18 | 初始完成度评估                                                     |
| 2026-01-18 | 修复: NPC标签检查逻辑 - 扩展标签从 BB-only 改为 GC-only (符合guide规范)        |
| 2026-01-18 | 修复: BB版本使用DC V2检查规则 (effectiveVersion=1)                    |
| 2026-01-18 | 修复: 对所有非默认标签进行脚本定义检查                                        |
| 2026-01-18 | 重命名: EP1_BB_EXTRA/EP2_BB_EXTRA -> EP1_GC_EXTRA/EP2_GC_EXTRA |