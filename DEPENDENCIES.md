# Feature 依赖关系详解

## 📊 完整依赖关系图

```
┌─────────────────────────────────────────────────────────────┐
│                     依赖关系总览                              │
└─────────────────────────────────────────────────────────────┘

Feature #1: infrastructure-and-assets (13 files)
    └─ 完全独立，无任何依赖
       ✅ 可以第一个提交

       ↓ （可选依赖）

Feature #2: area-and-npc-system (5 files) ← 修正：增加了 QuestNpc.kt
    ├─ Areas.kt → 定义 bossArea, isBossArea(), isPioneer2OrLab()
    ├─ NpcType.kt → 定义 boss, minion 字段
    ├─ QuestNpc.kt → 定义 gameAreaId 字段 ⭐
    ├─ NpcTypeFromData.kt → 使用 gameAreaId
    └─ QuestNpcModel.kt → 地面生成逻辑
    └─ 完全独立，或仅弱依赖 #1 的资源
       ✅ 可以在 #1 之后提交（或与 #1 并行）

       ↓ （强依赖）

Feature #3: multi-floor-quest-system (20 files) ← 修正：减少了 QuestNpc.kt
    ├─ 依赖 #2 的 Areas.kt:
    │  └─ QuestEditorStore 使用 isBossArea(), isPioneer2OrLab()
    │  └─ EntityListWidget 使用 isBossArea(), isPioneer2OrLab()
    ├─ 依赖 #2 的 QuestNpc.kt:
    │  └─ Quest.kt 设置 npc.gameAreaId = mapping.areaId
    └─ ⚠️  必须在 #2 合并后才能提交

       ↓ （强依赖）

Feature #4: rendering-visualization-system (7 files)
    ├─ 依赖 #2 的 QuestNpcModel:
    │  └─ EntityMeshManager 设置地面高度计算器
    ├─ 依赖 #3 的 QuestEditorStore:
    │  └─ 使用 showSectionIds, spawnMonstersOnGround, showOriginPoint
    └─ ⚠️  必须在 #2, #3 合并后才能提交

       ↓ （强依赖）

Feature #5: quest-editor-ui-system (13 files)
    ├─ 依赖 #2 的 Areas.kt:
    │  └─ EntityListWidget 使用 isBossArea(), isPioneer2OrLab()
    ├─ 依赖 #3 的 QuestEditorStore:
    │  └─ 工具栏、相机导航、事件列表都依赖 Store 的功能
    ├─ 依赖 #4 的渲染器:
    │  └─ 工具栏的显示控制开关需要渲染器支持
    └─ ⚠️  必须在 #2, #3, #4 合并后才能提交
```

## 🔍 详细依赖分析

### Feature #1 → Feature #2
**依赖类型**: 弱依赖（可选）
**原因**: Feature #2 可能会使用 Feature #1 加载的默认 Quest 文件进行测试
**是否可以跳过**: ✅ 是，Feature #2 可以独立开发和测试

---

### Feature #2 → Feature #3
**依赖类型**: 强依赖（必须）
**依赖详情**:

#### 1. Areas.kt 的函数被使用
```kotlin
// Feature #2 定义
// Areas.kt
fun isBossArea(episode: Int, areaId: Int): Boolean
fun isPioneer2OrLab(episode: Int, areaId: Int): Boolean

// Feature #3 使用
// QuestEditorStore.kt (部分代码)
val showOmnispawn = map(currentQuest, currentArea) { quest, area ->
    val isPioneer2OrLab = isPioneer2OrLab(quest.episode, area.id)
    val isBoss = isBossArea(quest.episode, area.id)
    !isPioneer2OrLab && !isBoss
}
```

#### 2. QuestNpc.gameAreaId 字段被使用
```kotlin
// Feature #2 定义
// QuestNpc.kt
class QuestNpc {
    var gameAreaId: Int = areaId  // 新增字段
}

// Feature #3 使用
// Quest.kt
if (floorMappings.isNotEmpty()) {
    for (npc in npcs) {
        val mapping = floorMappings.find { it.floorId == npc.areaId }
        if (mapping != null) {
            npc.gameAreaId = mapping.areaId  // 设置这个字段
        }
    }
}
```

**结论**: Feature #3 无法在没有 Feature #2 的情况下编译通过

---

### Feature #3 → Feature #4
**依赖类型**: 强依赖（必须）
**依赖详情**:

#### 1. QuestNpcModel 的地面生成功能
```kotlin
// Feature #2 定义
// QuestNpcModel.kt
object QuestNpcModel {
    private var _spawnOnGround = ...
    fun setGroundHeightCalculator(...)
}

// Feature #4 使用
// EntityMeshManager.kt
init {
    QuestNpcModel.setGroundHeightCalculator { x, z, section ->
        calculateGroundHeight(x, z)
    }
}
```

#### 2. QuestEditorStore 的显示控制
```kotlin
// Feature #3 定义
// QuestEditorStore.kt
val showSectionIds: Cell<Boolean>
val spawnMonstersOnGround: Cell<Boolean>
val showOriginPoint: Cell<Boolean>

// Feature #4 使用
// EntityMeshManager.kt
observe(store.showSectionIds) { show ->
    if (show) updateSectionIdLabels() else clearSectionIdLabels()
}
```

**结论**: Feature #4 需要 Feature #2 的 NPC 模型和 Feature #3 的 Store 功能

---

### Feature #4 → Feature #5
**依赖类型**: 强依赖（必须）
**依赖详情**:

#### 1. 渲染器功能
```kotlin
// Feature #4 提供
// EntityMeshManager.kt
- OriginPointRenderer 集成
- SectionIdRenderer 集成
- RangeCircleRenderer 集成

// Feature #5 使用
// QuestEditorToolbarWidget.kt
toggleSwitch("Show Section IDs") { store.setShowSectionIds(it) }
toggleSwitch("Show Origin Point") { store.setShowOriginPoint(it) }
```

#### 2. Store 和 Areas 功能
```kotlin
// Feature #2 + #3 提供
// Areas.kt + QuestEditorStore.kt

// Feature #5 使用
// EntityListWidget.kt
val showOmnispawn = map(store.currentQuest, store.currentArea) { quest, area ->
    val isPioneer2OrLab = isPioneer2OrLab(quest.episode, area.id)  // 来自 Feature #2
    val isBoss = isBossArea(quest.episode, area.id)                // 来自 Feature #2
    !isPioneer2OrLab && !isBoss
}
```

**结论**: Feature #5 需要所有前置 Features 的功能

---

## ✅ 推荐的提交顺序

### 严格按照以下顺序提交 PR：

```bash
1️⃣  Feature #1: infrastructure-and-assets
    ├─ 文件数: 13
    ├─ 依赖: 无
    ├─ 审查时间: ~30 分钟
    └─ 提交后等待合并
        ↓
2️⃣  Feature #2: area-and-npc-system
    ├─ 文件数: 5 (修正后)
    ├─ 依赖: 无（或弱依赖 #1）
    ├─ 审查时间: ~1 小时
    └─ 提交后等待合并
        ↓
3️⃣  Feature #3: multi-floor-quest-system ⚠️  核心
    ├─ 文件数: 20 (修正后)
    ├─ 依赖: 强依赖 #2
    ├─ 审查时间: ~3-4 小时
    └─ ⚠️  必须等待 #2 合并后才能创建此分支
        ↓
4️⃣  Feature #4: rendering-visualization-system
    ├─ 文件数: 7
    ├─ 依赖: 强依赖 #2, #3
    ├─ 审查时间: ~1.5 小时
    └─ ⚠️  必须等待 #2, #3 合并后才能创建此分支
        ↓
5️⃣  Feature #5: quest-editor-ui-system
    ├─ 文件数: 13
    ├─ 依赖: 强依赖 #2, #3, #4
    ├─ 审查时间: ~2 小时
    └─ ⚠️  必须等待 #2, #3, #4 合并后才能创建此分支
```

### ⚠️  重要提示

1. **不能跳过顺序**: 由于存在强依赖关系，必须严格按照 1→2→3→4→5 的顺序
2. **每次基于最新 master**: 创建新分支前，确保 `git pull origin master` 获取最新代码
3. **等待 PR 合并**: 在创建下一个 Feature 分支前，确保前置 PR 已经合并到 master
4. **避免并行开发**: 不要同时开发多个有依赖关系的 Feature

## 🔧 如果打破顺序会发生什么？

### 场景 1: 在 #2 合并前创建 #3
```bash
# 错误操作
git checkout master  # master 还没有 Feature #2 的改动
git checkout -b feature/multi-floor-quest-system
git checkout release/1.0.0 -- <files>

# 结果：编译失败
❌ Error: Unresolved reference: isBossArea
❌ Error: Unresolved reference: isPioneer2OrLab
❌ Error: Unresolved reference: gameAreaId
```

### 场景 2: 尝试并行开发 #3 和 #4
```bash
# 即使都基于 release/1.0.0，也会在 PR 审查时造成困扰
# 因为 #4 依赖 #3 的 Store 功能

# PR #4 的审查者会看到：
❌ "这个 Feature 依赖的 showSectionIds 在哪里定义的？"
❌ "为什么 QuestEditorStore 没有这些字段？"
```

## 📋 修正总结

### 文件重新分配
- **QuestNpc.kt** 从 Feature #3 移到 Feature #2
- Feature #2: 4 files → **5 files**
- Feature #3: 21 files → **20 files**

### 为什么这样调整？
1. **解决循环依赖**: NpcTypeFromData.kt 需要 gameAreaId，所以 QuestNpc.kt 必须在同一个 Feature
2. **逻辑自洽**: QuestNpc.kt 是 NPC 数据模型，应该和其他 NPC 相关文件在一起
3. **依赖清晰**: Feature #3 单向依赖 Feature #2，没有循环依赖

## ✅ 验证依赖关系的方法

在创建每个 Feature 分支后，验证编译：

```bash
# 创建分支后
git checkout feature/xxx

# 尝试编译
./gradlew build

# 应该能成功编译（如果所有依赖都已合并）
# 如果失败，检查是否有未合并的前置 Feature
```