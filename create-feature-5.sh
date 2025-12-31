#!/bin/bash
# Feature #5: Quest Editor UI 完整功能

set -e

echo "创建 Feature #5 分支: feature/quest-editor-ui-system"

# 从 master 创建新分支
git checkout master
git checkout -b feature/quest-editor-ui-system

# 从 release/1.0.0 提取文件
echo "提取工具栏..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/QuestEditorToolbarController.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/QuestEditorToolbarWidget.kt

echo "提取实体列表..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/EntityListWidget.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/EntityListController.kt

echo "提取事件列表..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/EventWidget.kt

echo "提取相机导航..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/core/rendering/OrbitalCameraInputManager.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/input/QuestInputManager.kt

echo "提取渲染器 Widget..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/QuestEditorRendererWidget.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/QuestRendererWidget.kt

echo "提取 Quest Editor 主入口..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/QuestEditor.kt

echo "提取其他 Widget..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/EntityDnd.kt

echo "提取测试..."
git checkout release/1.0.0 -- \
  web/src/jsTest/kotlin/world/phantasmal/web/questEditor/controllers/EventsControllerTests.kt

# 注意: QuestEditorStore.kt 的 UI 相关部分已经在 Feature #3 中提取了

# 查看状态
git status

echo ""
echo "✅ Feature #5 文件已提取完成（13 个文件）"
echo ""
echo "⚠️  依赖提示："
echo "   此 Feature 依赖 Feature #2, #3, #4 已经合并到 master"
echo "   确保所有前置 Features 已经合并后再创建此分支的 PR"
echo ""
echo "下一步操作:"
echo "1. 检查改动: git diff --staged"
echo "2. 提交改动: git commit -m 'feat: Quest Editor UI system"
echo ""
echo "Toolbar features:"
echo "- Area/variant selector with multi-floor quest support"
echo "- Section navigation dropdown and jump functionality"
echo "- Display control toggles (Show Section IDs, Spawn on Ground, Show Origin)"
echo "- Entity count display"
echo ""
echo "Entity list features:"
echo "- Omnispawn toggle (conditional visibility for non-Pioneer2/Lab/Boss areas)"
echo "- Reactive integration with Store"
echo ""
echo "Event list features:"
echo "- Multi-select events with Ctrl+Click"
echo "- Multi-select visual feedback with CSS classes"
echo "- selectedEvents and selectedEventsSectionWaves support"
echo ""
echo "Camera navigation system:"
echo "- Preserve user viewpoint when navigating between Sections"
echo "- Reset viewpoint on floor transitions"
echo "- User camera preference tracking (userTargetOffset)"
echo "- Target camera position navigation"
echo "- Mouse world position tracking"
echo ""
echo "Store UI features:"
echo "- selectedSection: current selected Section"
echo "- currentAreaSections: Section list for current area variant"
echo "- targetCameraPosition: camera target for navigation"
echo "- mouseWorldPosition: mouse position in world space"
echo "- _selectedEvents: multi-select events collection"
echo ""
echo "🤖 Generated with Claude Code"
echo ""
echo "Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>'"
echo "3. 推送分支: git push -u origin feature/quest-editor-ui-system"