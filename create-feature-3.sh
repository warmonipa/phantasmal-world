#!/bin/bash
# Feature #3: 多地板副本完整系统

set -e

echo "创建 Feature #3 分支: feature/multi-floor-quest-system"

# 从 master 创建新分支
git checkout master
git checkout -b feature/multi-floor-quest-system

# 从 release/1.0.0 提取文件
echo "提取数据流分析层..."
git checkout release/1.0.0 -- \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/dataFlowAnalysis/FloorMappings.kt \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/dataFlowAnalysis/GameArea.kt

# 删除旧文件（如果存在）
if [ -f "psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/dataFlowAnalysis/GetMapDesignations.kt" ]; then
  git rm psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/dataFlowAnalysis/GetMapDesignations.kt
  echo "已删除 GetMapDesignations.kt"
fi

echo "提取字节码和 Opcode 支持..."
git checkout release/1.0.0 -- \
  psolib/srcGeneration/asm/opcodes.yml \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Bytecode.kt

echo "提取 Quest 数据模型..."
git checkout release/1.0.0 -- \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Quest.kt \
  psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/ObjectType.kt \
  psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/QuestTests.kt

echo "提取 Assembly Worker..."
git checkout release/1.0.0 -- \
  web/assembly-worker/src/jsMain/kotlin/world/phantasmal/web/assemblyWorker/AsmAnalyser.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/asm/AsmAnalyser.kt

echo "提取 Web 模型层..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/QuestModel.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/AreaModel.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/AreaVariantModel.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/QuestEntityModel.kt

echo "提取 Quest Editor Store 层..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/stores/QuestEditorStore.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/stores/AreaStore.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/stores/ModelConversion.kt

echo "提取 Controller 适配..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/EntityInfoController.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/EventsController.kt

echo "提取测试更新..."
git checkout release/1.0.0 -- \
  web/src/jsTest/kotlin/world/phantasmal/web/test/TestModels.kt

# 查看状态
git status

echo ""
echo "✅ Feature #3 文件已提取完成（20 个文件）"
echo ""
echo "⚠️  依赖提示："
echo "   此 Feature 依赖 Feature #2 (area-and-npc-system)"
echo "   确保 Feature #2 已经合并到 master 后再创建此分支"
echo ""
echo "下一步操作:"
echo "1. 检查改动: git diff --staged"
echo "2. 提交改动: git commit -m 'feat: multi-floor quest system"
echo ""
echo "This is a major feature enabling quests with multiple floors/maps."
echo ""
echo "Core components:"
echo "- FloorMapping data structure (floorId, mapId, areaId, variantId)"
echo "- GameArea enum with 35 game area mappings"
echo "- Data flow analysis for bb_map_designate instruction"
echo "- Quest model support for floor mappings and multi-variant"
echo "- Uses QuestNpc.gameAreaId (from Feature #2) for NPC handling"
echo "- QuestEditorStore multi-floor event filtering and area switching"
echo "- Auto area/variant switching on entity selection"
echo "- Backward compatible with traditional single-area quests"
echo ""
echo "Technical stack:"
echo "- Data flow analysis layer (FloorMappings.kt, GameArea.kt)"
echo "- Bytecode support (opcodes.yml, Bytecode.kt)"
echo "- Quest data model (Quest.kt, ObjectType.kt, tests)"
echo "- Assembly worker integration"
echo "- Web model layer (QuestModel, AreaModel, etc.)"
echo "- QuestEditorStore layer (326 lines refactor)"
echo "- Controller adaptations"
echo ""
echo "🤖 Generated with Claude Code"
echo ""
echo "Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>'"
echo "3. 推送分支: git push -u origin feature/multi-floor-quest-system"