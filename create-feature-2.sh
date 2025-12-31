#!/bin/bash
# Feature #2: 区域和 NPC 完整系统

set -e

echo "创建 Feature #2 分支: feature/area-and-npc-system"

# 从 master 创建新分支
git checkout master
git checkout -b feature/area-and-npc-system

# 从 release/1.0.0 提取文件
echo "提取区域和 NPC 系统文件..."
git checkout release/1.0.0 -- \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Areas.kt \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/NpcType.kt \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/NpcTypeFromData.kt \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/QuestNpc.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/QuestNpcModel.kt

# 查看状态
git status

echo ""
echo "✅ Feature #2 文件已提取完成"
echo ""
echo "下一步操作:"
echo "1. 检查改动: git diff --staged"
echo "2. 提交改动: git commit -m 'feat: area and NPC system enhancements"
echo ""
echo "- Add bossArea field to Areas with helper functions"
echo "- Add new areas: Lobby, BA Spaceship, BA Palace"
echo "- Add boss and minion classification to NPC types (50+ NPCs)"
echo "- Add gameAreaId field to QuestNpc for multi-floor quest support"
echo "- Fix NPC type detection for DelLily and Tower areas using gameAreaId"
echo "- Implement NPC ground spawning with terrain height calculation"
echo "- Add Y-axis offset for specific NPCs (Epsilon +20, GiGue +25)"
echo ""
echo "🤖 Generated with Claude Code"
echo ""
echo "Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>'"
echo "3. 推送分支: git push -u origin feature/area-and-npc-system"