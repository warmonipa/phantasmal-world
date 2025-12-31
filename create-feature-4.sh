#!/bin/bash
# Feature #4: Rendering Visualization System

set -e

echo "Creating Feature #4 branch: feature/rendering-visualization-system"

# Create new branch from master
git checkout master
git checkout -b feature/rendering-visualization-system

# Extract files from release/1.0.0
echo "Extracting new renderers..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/OriginPointRenderer.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/SectionIdRenderer.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/RangeCircleRenderer.kt

echo "Extracting rendering manager integration..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/EntityMeshManager.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/QuestEditorMeshManager.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/QuestMeshManager.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/QuestRenderer.kt

# Note: Display control parts of QuestEditorStore.kt were already extracted in Feature #3
# We don't need to extract the entire file again here

# View status
git status

echo ""
echo "✅ Feature #4 files extracted successfully (7 files)"
echo ""
echo "⚠️  Dependency notice:"
echo "   This feature depends on Feature #2 (NPC system) and Feature #3 (Multi-floor system)"
echo "   Make sure Feature #2 and #3 have been merged to master before creating this branch PR"
echo ""
echo "Next steps:"
echo "1. Review changes: git diff --staged"
echo "2. Commit changes: git commit -m 'feat: rendering visualization system"
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
echo "- showOriginPoint'"
echo "3. Push branch: git push -u origin feature/rendering-visualization-system"