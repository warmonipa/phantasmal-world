#!/bin/bash
# Feature #3: Multi-Floor Quest System

set -e

echo "Creating Feature #3 branch: feature/multi-floor-quest-system"
echo ""
echo "⚠️  This feature depends on Feature #2 (area-and-npc-system)"
echo "   Make sure Feature #2 has been merged upstream and you have pulled latest master"
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
git checkout -b feature/multi-floor-quest-system

# Extract files from release/1.0.0
echo "Extracting base library dependencies..."
git checkout release/1.0.0 -- \
  cell/src/commonMain/kotlin/world/phantasmal/cell/list/ListCells.kt \
  web/shared/src/commonMain/kotlin/world/phantasmal/web/shared/messages/Messages.kt

echo "Extracting data flow analysis layer..."
git checkout release/1.0.0 -- \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/dataFlowAnalysis/FloorMappings.kt

# Delete old file if exists
if [ -f "psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/dataFlowAnalysis/GetMapDesignations.kt" ]; then
  git rm psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/dataFlowAnalysis/GetMapDesignations.kt
  echo "Deleted GetMapDesignations.kt"
fi

echo "Extracting bytecode and opcode support..."
git checkout release/1.0.0 -- \
  psolib/srcGeneration/asm/opcodes.yml \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Bytecode.kt

echo "Extracting Quest data model..."
git checkout release/1.0.0 -- \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Quest.kt \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/ObjectType.kt \
  psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/QuestTests.kt

echo "Extracting Assembly Worker..."
git checkout release/1.0.0 -- \
  web/assembly-worker/src/jsMain/kotlin/world/phantasmal/web/assemblyWorker/AsmAnalyser.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/asm/AsmAnalyser.kt

echo "Extracting Web model layer..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/QuestModel.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/AreaModel.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/AreaVariantModel.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/QuestEntityModel.kt

echo "Extracting Quest Editor Store layer..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/stores/QuestEditorStore.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/stores/AreaStore.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/stores/ModelConversion.kt

echo "Extracting Controller adaptations..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/EntityInfoController.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/EventsController.kt

echo "Extracting test updates..."
git checkout release/1.0.0 -- \
  web/src/jsTest/kotlin/world/phantasmal/web/test/TestModels.kt

# View status
git status

echo ""
echo "✅ Feature #3 files extracted successfully (22 files)"
echo ""
echo "⚠️  Dependency notice:"
echo "   This feature depends on Feature #2 (area-and-npc-system)"
echo "   Make sure Feature #2 has been merged to master before creating this branch"
echo ""
echo "Next steps:"
echo "1. Review changes: git diff --staged"
echo "2. Commit changes: git commit -m 'feat: multi-floor quest system"
echo ""
echo "This is a major feature enabling quests with multiple floors/maps."
echo ""
echo "Core components:"
echo "- FloorMapping data structure (floorId, mapId, areaId, variantId)"
echo "- Area lookup functions with cached mappings (uses Areas.kt from Feature #2)"
echo "- Data flow analysis for bb_map_designate instruction"
echo "- Quest model support for floor mappings and multi-variant"
echo "- Uses QuestNpc.gameAreaId (from Feature #2) for NPC handling"
echo "- QuestEditorStore multi-floor event filtering and area switching"
echo "- Auto area/variant switching on entity selection"
echo "- Backward compatible with traditional single-area quests"
echo ""
echo "Technical stack:"
echo "- Base library support (ListCells.kt 3-param flatMapToList, Messages.kt multi-mapping)"
echo "- Data flow analysis layer (FloorMappings.kt + Areas.kt from Feature #2)"
echo "- Bytecode support (opcodes.yml, Bytecode.kt)"
echo "- Quest data model (Quest.kt, ObjectType.kt, tests)"
echo "- Assembly worker integration"
echo "- Web model layer (QuestModel, AreaModel, etc.)"
echo "- QuestEditorStore layer (326 lines refactor)"
echo "- Controller adaptations'"
echo "3. Push branch: git push -u origin feature/multi-floor-quest-system"