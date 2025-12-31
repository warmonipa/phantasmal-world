#!/bin/bash
# Feature #1: Infrastructure and Assets Update

set -e

echo "Creating Feature #1 branch: feature/infrastructure-and-assets"

# Create new branch from master
git checkout master
git checkout -b feature/infrastructure-and-assets

# Extract files from release/1.0.0
echo "Extracting configuration files..."
git checkout release/1.0.0 -- \
  web/webpack.config.d/webpack.config.js

echo "Extracting resource files..."
git checkout release/1.0.0 -- \
  web/src/jsMain/resources/assets/quests/defaults/default_ep_2.qst \
  web/src/jsMain/resources/assets/quests/defaults/default_ep_4.qst \
  web/src/jsMain/resources/assets/npcs/LaDimenian.nj \
  web/src/jsMain/resources/assets/npcs/LaDimenian.xvm \
  web/src/jsMain/resources/assets/npcs/SoDimenian.nj \
  web/src/jsMain/resources/assets/npcs/SoDimenian.xvm

echo "Extracting loaders..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/loading/QuestLoader.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/loading/EntityAssetLoader.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/loading/AreaAssetLoader.kt

# View status
git status

echo ""
echo "✅ Feature #1 files extracted successfully"
echo ""
echo "Next steps:"
echo "1. Review changes: git diff --staged"
echo "2. Commit changes: git commit -m 'feat: infrastructure and assets update"
echo ""
echo "- Update webpack config and build settings"
echo "- Add default quests for Episode II and IV"
echo "- Fix Dimenian NPC models visual glitch"
echo "- Update asset loaders for new resources'"
echo "3. Push branch: git push -u origin feature/infrastructure-and-assets"