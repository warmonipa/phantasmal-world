#!/bin/bash
# Feature #4: 渲染可视化完整系统

set -e

echo "创建 Feature #4 分支: feature/rendering-visualization-system"

# 从 master 创建新分支
git checkout master
git checkout -b feature/rendering-visualization-system

# 从 release/1.0.0 提取文件
echo "提取新增渲染器..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/OriginPointRenderer.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/SectionIdRenderer.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/RangeCircleRenderer.kt

echo "提取渲染管理器集成..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/EntityMeshManager.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/QuestEditorMeshManager.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/QuestMeshManager.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/QuestRenderer.kt

# 注意: QuestEditorStore.kt 的显示控制部分已经在 Feature #3 中提取了
# 这里我们不需要再次提取整个文件，只需要确保 Feature #3 已经合并

# 查看状态
git status

echo ""
echo "✅ Feature #4 文件已提取完成（7 个文件）"
echo ""
echo "⚠️  依赖提示："
echo "   此 Feature 依赖 Feature #2 (NPC system) 和 Feature #3 (Multi-floor system)"
echo "   确保 Feature #2 和 #3 已经合并到 master 后再创建此分支的 PR"
echo ""
echo "下一步操作:"
echo "1. 检查改动: git diff --staged"
echo "2. 提交改动: git commit -m 'feat: rendering visualization system"
echo ""
echo "New renderers:"
echo "- OriginPointRenderer: World origin (0,0,0) with tri-color axes"
echo "- SectionIdRenderer: Section ID labels with transparent outlines"
echo "- RangeCircleRenderer: Range visualization for EventCollision/ScriptCollision"
echo ""
echo "EntityMeshManager integration:"
echo "- Range circle display/hide"
echo "- Section ID labels display/hide"
echo "- Origin point display/hide"
echo "- Ground height calculator integration"
echo "- SCL_TAMA circles support"
echo ""
echo "Display controls (from QuestEditorStore):"
echo "- showSectionIds"
echo "- spawnMonstersOnGround"
echo "- showOriginPoint"
echo ""
echo "🤖 Generated with Claude Code"
echo ""
echo "Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>'"
echo "3. 推送分支: git push -u origin feature/rendering-visualization-system"