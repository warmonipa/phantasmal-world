#!/bin/bash
# Feature #2: Area and NPC System Enhancements

set -e

echo "Creating Feature #2 branch: feature/area-and-npc-system"
echo ""
echo "⚠️  This feature should be created after Feature #1 is merged to master"
echo "   Make sure Feature #1 has been merged upstream and you have pulled latest master"
echo ""
read -p "Have you updated master with latest changes? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Please update master first: git checkout master && git pull origin master"
    exit 1
fi

# Create new branch from latest master
git checkout master
git pull origin master
git checkout -b feature/area-and-npc-system

# Extract files from release/1.0.0
echo "Extracting area and NPC system files..."
git checkout release/1.0.0 -- \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Areas.kt \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/NpcType.kt \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/NpcTypeFromData.kt \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/QuestNpc.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/QuestNpcModel.kt

# View status
git status

echo ""
echo "✅ Feature #2 files extracted successfully"
echo ""
echo "Next steps:"
echo "1. Review changes: git diff --staged"
echo "2. Commit changes: git commit -m 'feat: area and NPC system enhancements"
echo ""
echo "- Add bossArea field to Areas with helper functions"
echo "- Add new areas: Lobby, BA Spaceship, BA Palace"
echo "- Add boss and minion classification to NPC types (50+ NPCs)"
echo "- Add gameAreaId field to QuestNpc for multi-floor quest support"
echo "- Fix NPC type detection for DelLily and Tower areas using gameAreaId"
echo "- Implement NPC ground spawning with terrain height calculation"
echo "- Add Y-axis offset for specific NPCs (Epsilon +20, GiGue +25)'"
echo "3. Push branch: git push -u origin feature/area-and-npc-system"