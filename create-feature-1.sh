#!/bin/bash
# Feature #1: 基础配置和资源更新

set -e

echo "创建 Feature #1 分支: feature/infrastructure-and-assets"

# 从 master 创建新分支
git checkout master
git checkout -b feature/infrastructure-and-assets

# 从 release/1.0.0 提取文件
echo "提取配置文件..."
git checkout release/1.0.0 -- \
  web/webpack.config.d/webpack.config.js \
  cell/src/commonMain/kotlin/world/phantasmal/cell/list/ListCells.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/application/Application.kt \
  web/shared/src/commonMain/kotlin/world/phantasmal/web/shared/messages/Messages.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/core/stores/UiStore.kt \
  webui/src/jsMain/kotlin/world/phantasmal/webui/widgets/Menu.kt

echo "提取资源文件..."
git checkout release/1.0.0 -- \
  web/src/jsMain/resources/assets/quests/defaults/default_ep_2.qst \
  web/src/jsMain/resources/assets/quests/defaults/default_ep_4.qst \
  web/src/jsMain/resources/assets/npcs/LaDimenian.nj \
  web/src/jsMain/resources/assets/npcs/LaDimenian.xvm \
  web/src/jsMain/resources/assets/npcs/SoDimenian.nj \
  web/src/jsMain/resources/assets/npcs/SoDimenian.xvm

echo "提取加载器..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/loading/QuestLoader.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/loading/EntityAssetLoader.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/loading/AreaAssetLoader.kt

# 查看状态
git status

echo ""
echo "✅ Feature #1 文件已提取完成"
echo ""
echo "下一步操作:"
echo "1. 检查改动: git diff --staged"
echo "2. 提交改动: git commit -m 'feat: infrastructure and assets update"
echo ""
echo "- Update webpack config and build settings"
echo "- Add default quests for Episode II and IV"
echo "- Fix Dimenian NPC models visual glitch"
echo "- Update asset loaders for new resources"
echo ""
echo "🤖 Generated with Claude Code"
echo ""
echo "Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>'"
echo "3. 推送分支: git push -u origin feature/infrastructure-and-assets"
