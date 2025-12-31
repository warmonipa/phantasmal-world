#!/bin/bash
# Feature #5: Quest Editor UI System

set -e

echo "Creating Feature #5 branch: feature/quest-editor-ui-system"

# Create new branch from master
git checkout master
git checkout -b feature/quest-editor-ui-system

# Extract files from release/1.0.0
echo "Extracting toolbar..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/QuestEditorToolbarController.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/QuestEditorToolbarWidget.kt

echo "Extracting entity list..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/EntityListWidget.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/EntityListController.kt

echo "Extracting event list..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/EventWidget.kt

echo "Extracting camera navigation..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/core/rendering/OrbitalCameraInputManager.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/input/QuestInputManager.kt

echo "Extracting renderer widgets..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/QuestEditorRendererWidget.kt \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/QuestRendererWidget.kt

echo "Extracting Quest Editor main entry..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/QuestEditor.kt

echo "Extracting other widgets..."
git checkout release/1.0.0 -- \
  web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/EntityDnd.kt

echo "Extracting tests..."
git checkout release/1.0.0 -- \
  web/src/jsTest/kotlin/world/phantasmal/web/questEditor/controllers/EventsControllerTests.kt

# Note: UI-related parts of QuestEditorStore.kt were already extracted in Feature #3

# View status
git status

echo ""
echo "✅ Feature #5 files extracted successfully (13 files)"
echo ""
echo "⚠️  Dependency notice:"
echo "   This feature depends on Feature #2, #3, #4 being merged to master"
echo "   Make sure all prerequisite features have been merged before creating this branch PR"
echo ""
echo "Next steps:"
echo "1. Review changes: git diff --staged"
echo "2. Commit changes: git commit -m 'feat: Quest Editor UI system"
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
echo "- _selectedEvents: multi-select events collection'"
echo "3. Push branch: git push -u origin feature/quest-editor-ui-system"