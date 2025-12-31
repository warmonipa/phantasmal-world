# Feature Branch Split Guide

This guide explains how to split the `release/1.0.0` branch (59 file changes) into 6 highly cohesive feature branches for easier code review and merging.

## Overall Strategy

```
release/1.0.0 (59 files)
    ↓ Split into 6 feature branches (cohesion-optimized)
    ├─ feature/infrastructure-and-assets (10 files)
    ├─ feature/area-and-npc-system (5 files)
    ├─ feature/multi-floor-quest-system (22 files) ⚠️ Core
    ├─ feature/rendering-visualization-system (7 files)
    ├─ feature/quest-editor-ui-system (13 files)
    └─ feature/general-improvements (3 files)
```

## Prerequisites

1. Ensure local `master` branch is up to date
2. Ensure `release/1.0.0` branch exists and contains all changes
3. Git is installed and permissions configured

## Steps

### Step 1: Grant execute permissions to scripts

```bash
chmod +x create-feature-*.sh
```

### Step 2: Execute scripts in order to create branches

#### Feature #1: Infrastructure and Assets Update (First PR)

```bash
./create-feature-1.sh

# Review changes
git diff --staged

# Commit
git commit -m "feat: infrastructure and assets update

- Update webpack config and build settings
- Add default quests for Episode II and IV
- Fix Dimenian NPC models visual glitch
- Update asset loaders for new resources"

# Push
git push -u origin feature/infrastructure-and-assets
```

**PR Title**: `feat: Infrastructure and assets update`
**PR Description**:
```markdown
## Summary
- Update webpack config and build settings
- Add default quests for Episode II and IV
- Fix Dimenian NPC models visual glitch
- Update asset loaders for new resources

## Changes
- 10 files changed
- Build configuration updates
- New default quest files for EP2 and EP4
- Fixed visual glitch in LaDimenian and SoDimenian models

## Test Plan
- [ ] Verify webpack builds successfully
- [ ] Load default quests for EP2 and EP4
- [ ] Check Dimenian NPCs render correctly
```

---

#### Feature #2: Area and NPC System (After #1 merged)

```bash
# Ensure Feature #1 has been merged to master
git checkout master
git pull origin master

# Create Feature #2
./create-feature-2.sh

# Review changes
git diff --staged

# Commit
git commit -m "feat: area and NPC system enhancements

- Add bossArea field to Areas with helper functions
- Add new areas: Lobby, BA Spaceship, BA Palace
- Add boss and minion classification to NPC types (50+ NPCs)
- Fix NPC type detection for DelLily and Tower areas
- Implement NPC ground spawning with terrain height calculation
- Add Y-axis offset for specific NPCs (Epsilon +20, GiGue +25)"

# Push
git push -u origin feature/area-and-npc-system
```

**PR Title**: `feat: Area and NPC system enhancements`
**PR Description**:
```markdown
## Summary
Complete overhaul of the area and NPC systems with new classifications,
ground spawning, and improved type detection.

## Changes
- 5 files changed
- Added `bossArea` field to Areas
- Added 3 new areas (Lobby, BA Spaceship, BA Palace)
- 50+ NPCs marked as boss or minion
- NPC ground spawning with terrain calculation
- Specific NPC Y-axis offsets

## Test Plan
- [ ] Verify boss areas are correctly identified
- [ ] Test NPC type detection in Tower areas
- [ ] Check NPC ground spawning works correctly
- [ ] Verify Epsilon and GiGue render at correct heights
```

---

#### Feature #3: Multi-Floor Quest System (After #2 merged) ⚠️

**This is the core Feature with 22 file changes**

```bash
# Ensure Feature #2 has been merged to master
git checkout master
git pull origin master

# Create Feature #3
./create-feature-3.sh

# Review changes
git diff --staged

# Commit (use complete commit message from script)
git commit -m "feat: multi-floor quest system

This is a major feature enabling quests with multiple floors/maps.

Core components:
- FloorMapping data structure (floorId, mapId, areaId, variantId)
- GameArea enum with 35 game area mappings
- Data flow analysis for bb_map_designate instruction
- Quest model support for floor mappings and multi-variant
- QuestNpc.gameAreaId field for proper NPC type detection
- QuestEditorStore multi-floor event filtering and area switching
- Auto area/variant switching on entity selection
- Backward compatible with traditional single-area quests

Technical stack:
- Base library support (ListCells.kt 3-param flatMapToList, Messages.kt multi-mapping)
- Data flow analysis layer (FloorMappings.kt, GameArea.kt)
- Bytecode support (opcodes.yml, Bytecode.kt)
- Quest data model (Quest.kt, ObjectType.kt, tests)
- Assembly worker integration
- Web model layer (QuestModel, AreaModel, etc.)
- QuestEditorStore layer (326 lines refactor)
- Controller adaptations"

# Push
git push -u origin feature/multi-floor-quest-system
```

**PR Title**: `feat: Multi-floor quest system`
**PR Description**:
```markdown
## Summary
Major feature enabling quests with multiple floors/maps (e.g., Phantasmal World #4).

This is the largest change in this release, involving 22 files across the entire
technical stack from data flow analysis to UI Store.

## Core Features
✅ Support quests with multiple floors/maps
✅ Floor-to-area multi-variant mapping
✅ Auto-extract floor mappings from bytecode
✅ NPC type detection works correctly in multi-floor quests
✅ Events filtered by floor/area/variant
✅ Auto area/variant switching on entity selection
✅ Backward compatible with traditional single-area quests

## Technical Changes
- **Data flow analysis**: FloorMappings.kt (317 lines), GameArea.kt (99 lines)
- **Bytecode**: Support for `bb_map_designate` instruction
- **Quest model**: `floorMappings` list, `mapDesignations` now `Map<Int, Set<Int>>`
- **NPC model**: `gameAreaId` field for correct type detection
- **QuestEditorStore**: 326-line refactor for multi-floor support
- **Controllers**: Adapted for multi-floor logic

## Test Plan
- [ ] Load multi-floor quest (e.g., Phantasmal World #4)
- [ ] Verify floor mappings are correctly extracted
- [ ] Verify NPC type detection works in Tower areas
- [ ] Verify events show in correct area/variant
- [ ] Verify selecting entity switches to correct area/variant
- [ ] Verify traditional quests still work correctly

## Breaking Changes
None - fully backward compatible with existing quests.
```

---

#### Feature #4: Rendering Visualization System (After #3 merged)

```bash
# Ensure Feature #3 has been merged to master
git checkout master
git pull origin master

# Create Feature #4
./create-feature-4.sh

# Review changes
git diff --staged

# Commit
git commit -m "feat: rendering visualization system

New renderers:
- OriginPointRenderer: World origin (0,0,0) with tri-color axes
- SectionIdRenderer: Section ID labels with transparent outlines
- RangeCircleRenderer: Range visualization for EventCollision/ScriptCollision

EntityMeshManager integration:
- Range circle display/hide
- Section ID labels display/hide
- Origin point display/hide
- Ground height calculator integration
- SCL_TAMA circles support

Display controls (from QuestEditorStore):
- showSectionIds
- spawnMonstersOnGround
- showOriginPoint"

# Push
git push -u origin feature/rendering-visualization-system
```

**PR Title**: `feat: Rendering visualization system`
**PR Description**:
```markdown
## Summary
Complete rendering visualization system with 3 new renderers and integrated
display management.

## New Renderers
- **OriginPointRenderer**: Shows world origin (0,0,0) with RGB axes
- **SectionIdRenderer**: Section ID labels with transparent outlines
- **RangeCircleRenderer**: Range circles for collision objects

## EntityMeshManager Integration
- Integrated all renderers into mesh management
- Display controls from Store
- Ground height calculator for NPC spawning
- SCL_TAMA circles support

## Changes
- 7 files changed
- 3 new renderer files (~787 lines)
- 4 mesh manager files updated

## Test Plan
- [ ] Toggle "Show Origin Point" and verify origin displays
- [ ] Toggle "Show Section IDs" and verify labels display
- [ ] Select EventCollision/ScriptCollision and verify range circles
- [ ] Toggle "Spawn on Ground" and verify NPCs on terrain
```

---

#### Feature #5: Quest Editor UI System (After #4 merged)

```bash
# Ensure Feature #4 has been merged to master
git checkout master
git pull origin master

# Create Feature #5
./create-feature-5.sh

# Review changes
git diff --staged

# Commit
git commit -m "feat: Quest Editor UI system

Toolbar features:
- Area/variant selector with multi-floor quest support
- Section navigation dropdown and jump functionality
- Display control toggles (Show Section IDs, Spawn on Ground, Show Origin)
- Entity count display

Entity list features:
- Omnispawn toggle (conditional visibility for non-Pioneer2/Lab/Boss areas)
- Reactive integration with Store

Event list features:
- Multi-select events with Ctrl+Click
- Multi-select visual feedback with CSS classes
- selectedEvents and selectedEventsSectionWaves support

Camera navigation system:
- Preserve user viewpoint when navigating between Sections
- Reset viewpoint on floor transitions
- User camera preference tracking (userTargetOffset)
- Target camera position navigation
- Mouse world position tracking

Store UI features:
- selectedSection: current selected Section
- currentAreaSections: Section list for current area variant
- targetCameraPosition: camera target for navigation
- mouseWorldPosition: mouse position in world space
- _selectedEvents: multi-select events collection"

# Push
git push -u origin feature/quest-editor-ui-system
```

**PR Title**: `feat: Quest Editor UI system`
**PR Description**:
```markdown
## Summary
Complete UI system for Quest Editor with enhanced toolbar, entity list,
event list, and camera navigation.

## Toolbar Features
- Area/variant selector with multi-floor support
- Section navigation with jump functionality
- Display control toggles
- Entity count display

## Entity List Features
- Omnispawn toggle (conditional visibility)
- Reactive Store integration

## Event List Features
- Multi-select with Ctrl+Click
- Visual feedback for selections
- Section/Wave information

## Camera Navigation
- Preserve viewpoint between Sections
- Reset on floor transitions
- User preference tracking
- Target position navigation

## Changes
- 13 files changed
- Complete UI interaction layer

## Test Plan
- [ ] Test area/variant selector with multi-floor quest
- [ ] Test Section navigation and jump
- [ ] Test display control toggles
- [ ] Test Omnispawn toggle visibility logic
- [ ] Test multi-select events with Ctrl+Click
- [ ] Test camera navigation between Sections
- [ ] Test camera reset on floor transitions
```

---

#### Feature #6: General Improvements (Can be submitted anytime)

```bash
# Can be executed at any time, recommended after Feature #5
git checkout master
git pull origin master

# Create Feature #6
./create-feature-6.sh

# Review changes
git diff --staged

# Commit
git commit -m "feat: general improvements

Keyboard compatibility:
- Support macOS Cmd key (metaKey) as Ctrl modifier
- Apply to global keybindings (undo/redo, shortcuts)
- Improve cross-platform user experience

UI fixes:
- Increase Menu z-index from 1000 to 1001
- Fix menu overlay issues with other UI components

Files changed:
- Application.kt: Add metaKey support for preventDefault
- UiStore.kt: Map metaKey to Ctrl in keybinding dispatcher
- Menu.kt: Increase z-index to fix layering issues"

# Push
git push -u origin feature/general-improvements
```

**PR Title**: `feat: General improvements`
**PR Description**:
```markdown
## Summary
General improvements for cross-platform compatibility and UI fixes.

## Keyboard Compatibility
- Support macOS Cmd key (metaKey) as Ctrl modifier
- Improve undo/redo shortcuts on macOS
- Better cross-platform keyboard handling

## UI Fixes
- Increase Menu z-index to fix overlay issues
- Resolve conflicts with other UI components

## Changes
- 3 files changed
- Platform-agnostic keyboard handling
- UI layering improvements

## Test Plan
- [ ] Test Cmd+Z (undo) on macOS
- [ ] Test Ctrl+Z (undo) on Windows/Linux
- [ ] Verify menu displays above all other UI elements
- [ ] Test keyboard shortcuts work consistently across platforms
```

---

## PR Submission Order and Dependencies

```
PR #1: feature/infrastructure-and-assets
  ├─ 10 files, ⭐⭐
  ├─ No dependencies
  └─ Estimated review time: 20 minutes
     ↓ After merge
PR #2: feature/area-and-npc-system
  ├─ 5 files, ⭐⭐⭐
  ├─ Dependencies: PR #1 (optional)
  └─ Estimated review time: 1 hour
     ↓ After merge
PR #3: feature/multi-floor-quest-system ⚠️
  ├─ 22 files, ⭐⭐⭐⭐⭐
  ├─ Dependencies: PR #2 (required)
  └─ Estimated review time: 3-4 hours (core functionality, needs careful review)
     ↓ After merge
PR #4: feature/rendering-visualization-system
  ├─ 7 files, ⭐⭐⭐⭐
  ├─ Dependencies: PR #2, PR #3 (required)
  └─ Estimated review time: 1.5 hours
     ↓ After merge
PR #5: feature/quest-editor-ui-system
  ├─ 13 files, ⭐⭐⭐⭐⭐
  ├─ Dependencies: PR #2, PR #3, PR #4 (required)
  └─ Estimated review time: 2 hours
     ↓ (optional)
PR #6: feature/general-improvements 💡
  ├─ 3 files, ⭐
  ├─ No dependencies (can be submitted anytime)
  └─ Estimated review time: 15 minutes
```

## Notes

### About QuestEditorStore.kt

`QuestEditorStore.kt` is a large file (326-line refactor) included in Feature #3:
- Multi-floor quest core logic
- Display control options (showSectionIds, spawnMonstersOnGround, showOriginPoint)
- Section navigation related
- Multi-select events related
- Camera control related

Since this file is core to the multi-floor quest system, all functionality is extracted at once in Feature #3. Features #4 and #5 depend on this functionality but don't need to modify this file again.

### If Conflicts Occur

If a file has already been modified in a previous Feature:

```bash
# Option 1: Recreate branch based on latest master
git checkout master
git pull origin master
./create-feature-X.sh

# Option 2: If file is already included in previous Feature, skip it
# Manually edit script, comment out already included files
```

### Verify All Files Are Included

After creating all Feature branches, verify no files are missing:

```bash
# Get all changed files from release/1.0.0
git diff master...release/1.0.0 --name-only | sort > /tmp/release-files.txt

# Get all changed files from feature branches
(
  git diff master...feature/infrastructure-and-assets --name-only
  git diff master...feature/area-and-npc-system --name-only
  git diff master...feature/multi-floor-quest-system --name-only
  git diff master...feature/rendering-visualization-system --name-only
  git diff master...feature/quest-editor-ui-system --name-only
  git diff master...feature/general-improvements --name-only
) | sort | uniq > /tmp/feature-files.txt

# Compare
diff /tmp/release-files.txt /tmp/feature-files.txt
```

There should be no differences. If there are, files have been missed.

## Cleanup Scripts

After completing all operations, scripts can be deleted:

```bash
rm create-feature-*.sh
rm FEATURE_BRANCH_GUIDE.md
```

## Summary

Through cohesion optimization, we split the 59-file large PR into **6 highly cohesive PRs**:

- **PR #1**: 10 files (configuration and resources) - Infrastructure
- **PR #2**: 5 files (areas and NPCs) - NPC system enhancement
- **PR #3**: 22 files (multi-floor core) ⚠️ - Core feature
- **PR #4**: 7 files (rendering system) - Visualization enhancement
- **PR #5**: 13 files (UI system) - Interaction layer completion
- **PR #6**: 3 files (general improvements) 💡 - Cross-platform compatibility

**Cohesion Principles**:
- Each PR only contains directly related changes
- Easy to understand and review
- Clear dependencies
- Feature #6 can be submitted independently at any time

**Total**: 60 files (including 1 deleted file)