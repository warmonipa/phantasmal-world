# Feature 分支拆分操作指南

本指南说明如何将 `release/1.0.0` 分支（59个文件改动）拆分成 5 个独立的 feature 分支，以便于代码审查和合并。

## 总体策略

```
release/1.0.0 (59 files)
    ↓ 拆分为 5 个 feature 分支
    ├─ feature/infrastructure-and-assets (13 files)
    ├─ feature/area-and-npc-system (4 files)
    ├─ feature/multi-floor-quest-system (21 files) ⚠️ 核心
    ├─ feature/rendering-visualization-system (7 files)
    └─ feature/quest-editor-ui-system (13 files)
```

## 前置要求

1. 确保本地 `master` 分支是最新的
2. 确保 `release/1.0.0` 分支存在且包含所有改动
3. 已经安装 Git 并配置好权限

## 操作步骤

### 步骤 1: 赋予脚本执行权限

```bash
chmod +x create-feature-*.sh
```

### 步骤 2: 按顺序执行脚本创建分支

#### Feature #1: 基础配置和资源更新（第一个提交）

```bash
./create-feature-1.sh

# 检查改动
git diff --staged

# 提交
git commit -m "feat: infrastructure and assets update

- Update webpack config and build settings
- Add default quests for Episode II and IV
- Fix Dimenian NPC models visual glitch
- Update asset loaders for new resources

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"

# 推送
git push -u origin feature/infrastructure-and-assets
```

**PR 标题**: `feat: Infrastructure and assets update`
**PR 描述**:
```markdown
## Summary
- Update webpack config and build settings
- Add default quests for Episode II and IV
- Fix Dimenian NPC models visual glitch
- Update asset loaders for new resources

## Changes
- 13 files changed
- Build configuration updates
- New default quest files for EP2 and EP4
- Fixed visual glitch in LaDimenian and SoDimenian models

## Test Plan
- [ ] Verify webpack builds successfully
- [ ] Load default quests for EP2 and EP4
- [ ] Check Dimenian NPCs render correctly
```

---

#### Feature #2: 区域和 NPC 系统（等 #1 合并后）

```bash
# 确保 Feature #1 已经合并到 master
git checkout master
git pull origin master

# 创建 Feature #2
./create-feature-2.sh

# 检查改动
git diff --staged

# 提交
git commit -m "feat: area and NPC system enhancements

- Add bossArea field to Areas with helper functions
- Add new areas: Lobby, BA Spaceship, BA Palace
- Add boss and minion classification to NPC types (50+ NPCs)
- Fix NPC type detection for DelLily and Tower areas
- Implement NPC ground spawning with terrain height calculation
- Add Y-axis offset for specific NPCs (Epsilon +20, GiGue +25)

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"

# 推送
git push -u origin feature/area-and-npc-system
```

**PR 标题**: `feat: Area and NPC system enhancements`
**PR 描述**:
```markdown
## Summary
Complete overhaul of the area and NPC systems with new classifications,
ground spawning, and improved type detection.

## Changes
- 4 files changed
- Added `bossArea` field to Areas
- Added 3 new areas (Lobby, BA Spaceship, BA Palace)
- 50+ NPCs marked as boss or minion
- NPC ground spawning with terrain calculation
- Specific NPC Y-axis offsets

## Test Plan
- [ ] Verify boss areas are correctly identified
- [ ] Test NPC type detection in Tower areas
- [ ] Check NPC ground spawning works correctly
- [ ] Verify Epsilon and GiGue render at correct heights
```

---

#### Feature #3: 多地板副本系统（等 #2 合并后）⚠️

**这是核心 Feature，包含 21 个文件的改动**

```bash
# 确保 Feature #2 已经合并到 master
git checkout master
git pull origin master

# 创建 Feature #3
./create-feature-3.sh

# 检查改动
git diff --staged

# 提交（使用脚本中提供的完整 commit message）
git commit -m "feat: multi-floor quest system

This is a major feature enabling quests with multiple floors/maps.

Core components:
- FloorMapping data structure (floorId, mapId, areaId, variantId)
- GameArea enum with 35 game area mappings
- Data flow analysis for bb_map_designate instruction
- Quest model support for floor mappings and multi-variant
- QuestNpc.gameAreaId field for proper NPC type detection
- QuestEditorStore multi-floor event filtering and area switching
- Auto area/variant switching on entity selection
- Backward compatible with traditional single-area quests

Technical stack:
- Data flow analysis layer (FloorMappings.kt, GameArea.kt)
- Bytecode support (opcodes.yml, Bytecode.kt)
- Quest data model (Quest.kt, QuestNpc.kt, tests)
- Assembly worker integration
- Web model layer (QuestModel, AreaModel, etc.)
- QuestEditorStore layer (326 lines refactor)
- Controller adaptations

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"

# 推送
git push -u origin feature/multi-floor-quest-system
```

**PR 标题**: `feat: Multi-floor quest system`
**PR 描述**:
```markdown
## Summary
Major feature enabling quests with multiple floors/maps (e.g., Phantasmal World #4).

This is the largest change in this release, involving 21 files across the entire
technical stack from data flow analysis to UI Store.

## Core Features
✅ Support quests with multiple floors/maps
✅ Floor-to-area multi-variant mapping
✅ Auto-extract floor mappings from bytecode
✅ NPC type detection works correctly in multi-floor quests
✅ Events filtered by floor/area/variant
✅ Auto area/variant switching on entity selection
✅ Backward compatible with traditional single-area quests

## Technical Changes
- **Data flow analysis**: FloorMappings.kt (317 lines), GameArea.kt (99 lines)
- **Bytecode**: Support for `bb_map_designate` instruction
- **Quest model**: `floorMappings` list, `mapDesignations` now `Map<Int, Set<Int>>`
- **NPC model**: `gameAreaId` field for correct type detection
- **QuestEditorStore**: 326-line refactor for multi-floor support
- **Controllers**: Adapted for multi-floor logic

## Test Plan
- [ ] Load multi-floor quest (e.g., Phantasmal World #4)
- [ ] Verify floor mappings are correctly extracted
- [ ] Verify NPC type detection works in Tower areas
- [ ] Verify events show in correct area/variant
- [ ] Verify selecting entity switches to correct area/variant
- [ ] Verify traditional quests still work correctly

## Breaking Changes
None - fully backward compatible with existing quests.
```

---

#### Feature #4: 渲染可视化系统（等 #3 合并后）

```bash
# 确保 Feature #3 已经合并到 master
git checkout master
git pull origin master

# 创建 Feature #4
./create-feature-4.sh

# 检查改动
git diff --staged

# 提交
git commit -m "feat: rendering visualization system

New renderers:
- OriginPointRenderer: World origin (0,0,0) with tri-color axes
- SectionIdRenderer: Section ID labels with transparent outlines
- RangeCircleRenderer: Range visualization for EventCollision/ScriptCollision

EntityMeshManager integration:
- Range circle display/hide
- Section ID labels display/hide
- Origin point display/hide
- Ground height calculator integration
- SCL_TAMA circles support

Display controls (from QuestEditorStore):
- showSectionIds
- spawnMonstersOnGround
- showOriginPoint

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"

# 推送
git push -u origin feature/rendering-visualization-system
```

**PR 标题**: `feat: Rendering visualization system`
**PR 描述**:
```markdown
## Summary
Complete rendering visualization system with 3 new renderers and integrated
display management.

## New Renderers
- **OriginPointRenderer**: Shows world origin (0,0,0) with RGB axes
- **SectionIdRenderer**: Section ID labels with transparent outlines
- **RangeCircleRenderer**: Range circles for collision objects

## EntityMeshManager Integration
- Integrated all renderers into mesh management
- Display controls from Store
- Ground height calculator for NPC spawning
- SCL_TAMA circles support

## Changes
- 7 files changed
- 3 new renderer files (~787 lines)
- 4 mesh manager files updated

## Test Plan
- [ ] Toggle "Show Origin Point" and verify origin displays
- [ ] Toggle "Show Section IDs" and verify labels display
- [ ] Select EventCollision/ScriptCollision and verify range circles
- [ ] Toggle "Spawn on Ground" and verify NPCs on terrain
```

---

#### Feature #5: Quest Editor UI 系统（等 #4 合并后）

```bash
# 确保 Feature #4 已经合并到 master
git checkout master
git pull origin master

# 创建 Feature #5
./create-feature-5.sh

# 检查改动
git diff --staged

# 提交
git commit -m "feat: Quest Editor UI system

Toolbar features:
- Area/variant selector with multi-floor quest support
- Section navigation dropdown and jump functionality
- Display control toggles (Show Section IDs, Spawn on Ground, Show Origin)
- Entity count display

Entity list features:
- Omnispawn toggle (conditional visibility for non-Pioneer2/Lab/Boss areas)
- Reactive integration with Store

Event list features:
- Multi-select events with Ctrl+Click
- Multi-select visual feedback with CSS classes
- selectedEvents and selectedEventsSectionWaves support

Camera navigation system:
- Preserve user viewpoint when navigating between Sections
- Reset viewpoint on floor transitions
- User camera preference tracking (userTargetOffset)
- Target camera position navigation
- Mouse world position tracking

Store UI features:
- selectedSection: current selected Section
- currentAreaSections: Section list for current area variant
- targetCameraPosition: camera target for navigation
- mouseWorldPosition: mouse position in world space
- _selectedEvents: multi-select events collection

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"

# 推送
git push -u origin feature/quest-editor-ui-system
```

**PR 标题**: `feat: Quest Editor UI system`
**PR 描述**:
```markdown
## Summary
Complete UI system for Quest Editor with enhanced toolbar, entity list,
event list, and camera navigation.

## Toolbar Features
- Area/variant selector with multi-floor support
- Section navigation with jump functionality
- Display control toggles
- Entity count display

## Entity List Features
- Omnispawn toggle (conditional visibility)
- Reactive Store integration

## Event List Features
- Multi-select with Ctrl+Click
- Visual feedback for selections
- Section/Wave information

## Camera Navigation
- Preserve viewpoint between Sections
- Reset on floor transitions
- User preference tracking
- Target position navigation

## Changes
- 13 files changed
- Complete UI interaction layer

## Test Plan
- [ ] Test area/variant selector with multi-floor quest
- [ ] Test Section navigation and jump
- [ ] Test display control toggles
- [ ] Test Omnispawn toggle visibility logic
- [ ] Test multi-select events with Ctrl+Click
- [ ] Test camera navigation between Sections
- [ ] Test camera reset on floor transitions
```

---

## PR 提交顺序和依赖关系

```
PR #1: feature/infrastructure-and-assets
  ├─ 13 files, ⭐⭐
  ├─ 无依赖
  └─ 预计审查时间: 30 分钟
     ↓ 合并后
PR #2: feature/area-and-npc-system
  ├─ 4 files, ⭐⭐⭐
  ├─ 依赖: PR #1
  └─ 预计审查时间: 1 小时
     ↓ 合并后
PR #3: feature/multi-floor-quest-system ⚠️
  ├─ 21 files, ⭐⭐⭐⭐⭐
  ├─ 依赖: PR #2
  └─ 预计审查时间: 3-4 小时（核心功能，需要仔细审查）
     ↓ 合并后
PR #4: feature/rendering-visualization-system
  ├─ 7 files, ⭐⭐⭐⭐
  ├─ 依赖: PR #2, PR #3
  └─ 预计审查时间: 1.5 小时
     ↓ 合并后
PR #5: feature/quest-editor-ui-system
  ├─ 13 files, ⭐⭐⭐⭐⭐
  ├─ 依赖: PR #2, PR #3, PR #4
  └─ 预计审查时间: 2 小时
```

## 注意事项

### 关于 QuestEditorStore.kt

`QuestEditorStore.kt` 是一个大文件（326行重构），在 Feature #3 中包含了：
- 多地板副本的核心逻辑
- 显示控制选项（showSectionIds, spawnMonstersOnGround, showOriginPoint）
- Section 导航相关
- 多选事件相关
- 相机控制相关

由于这个文件在多地板副本系统中是核心，所有的功能都在 Feature #3 中一次性提取。Feature #4 和 #5 依赖这些功能，但不需要再次修改这个文件。

### 如果遇到冲突

如果在后续 Feature 中遇到文件已经在之前的 Feature 中修改过的情况：

```bash
# 选项 1: 基于最新的 master 重新创建分支
git checkout master
git pull origin master
./create-feature-X.sh

# 选项 2: 如果文件已经包含在之前的 Feature 中，跳过该文件
# 手动编辑脚本，注释掉已经包含的文件
```

### 验证所有文件都已包含

在所有 Feature 分支创建后，验证没有遗漏文件：

```bash
# 获取 release/1.0.0 所有改动的文件
git diff master...release/1.0.0 --name-only | sort > /tmp/release-files.txt

# 获取所有 feature 分支改动的文件
(
  git diff master...feature/infrastructure-and-assets --name-only
  git diff master...feature/area-and-npc-system --name-only
  git diff master...feature/multi-floor-quest-system --name-only
  git diff master...feature/rendering-visualization-system --name-only
  git diff master...feature/quest-editor-ui-system --name-only
) | sort | uniq > /tmp/feature-files.txt

# 比较
diff /tmp/release-files.txt /tmp/feature-files.txt
```

应该没有差异，如果有差异说明有文件被遗漏了。

## 清理脚本

完成所有操作后，可以删除脚本文件：

```bash
rm create-feature-*.sh
rm FEATURE_BRANCH_GUIDE.md
```

## 总结

通过这个策略，我们将 59 个文件的大 PR 拆分成了 5 个较小的 PR：
- PR #1: 13 files (配置和资源)
- PR #2: 4 files (区域和 NPC)
- PR #3: 21 files (多地板核心) ⚠️
- PR #4: 7 files (渲染系统)
- PR #5: 13 files (UI 系统)

每个 PR 都是功能完整、逻辑自洽的独立模块，便于审查和合并。