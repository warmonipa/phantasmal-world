# Feature Dependencies

## Complete Dependency Graph

```
┌─────────────────────────────────────────────────────────────┐
│                  Dependency Overview                         │
└─────────────────────────────────────────────────────────────┘

Feature #1: infrastructure-and-assets (10 files)
    └─ Completely independent, no dependencies
       ✅ Can be submitted first

       ↓ (optional dependency)

Feature #2: area-and-npc-system (5 files)
    ├─ Areas.kt → Defines bossArea, isBossArea(), isPioneer2OrLab()
    ├─ NpcType.kt → Defines boss, minion fields
    ├─ QuestNpc.kt → Defines gameAreaId field ⭐
    ├─ NpcTypeFromData.kt → Uses gameAreaId
    └─ QuestNpcModel.kt → Ground spawning logic
    └─ Completely independent, or only weak dependency on #1
       ✅ Can be submitted after #1 (or in parallel)

       ↓ (strong dependency)

Feature #3: multi-floor-quest-system (22 files)
    ├─ Depends on #2's Areas.kt:
    │  └─ QuestEditorStore uses isBossArea(), isPioneer2OrLab()
    ├─ Depends on #2's QuestNpc.kt:
    │  └─ Quest.kt sets npc.gameAreaId = mapping.areaId
    ├─ ListCells.kt → 3-param flatMapToList for multi-floor event filtering
    ├─ Messages.kt → MapDesignations supports one-to-many mapping
    └─ ⚠️  MUST be submitted after #2 is merged

       ↓ (strong dependency)

Feature #4: rendering-visualization-system (7 files)
    ├─ Depends on #2's QuestNpcModel:
    │  └─ EntityMeshManager sets ground height calculator
    ├─ Depends on #3's QuestEditorStore:
    │  └─ Uses showSectionIds, spawnMonstersOnGround, showOriginPoint
    └─ ⚠️  MUST be submitted after #2, #3 are merged

       ↓ (strong dependency)

Feature #5: quest-editor-ui-system (13 files)
    ├─ Depends on #2's Areas.kt:
    │  └─ EntityListWidget uses isBossArea(), isPioneer2OrLab()
    ├─ Depends on #3's QuestEditorStore:
    │  └─ Toolbar, camera navigation, event list all depend on Store
    ├─ Depends on #4's renderers:
    │  └─ Toolbar display control switches need renderer support
    └─ ⚠️  MUST be submitted after #2, #3, #4 are merged

       ↓ (optional)

Feature #6: general-improvements (3 files)
    ├─ Application.kt → macOS Cmd key support
    ├─ UiStore.kt → macOS Cmd key support
    └─ Menu.kt → z-index fix
    └─ Completely independent, can be submitted at any time
       ✅ Recommended as the last PR
```

## Detailed Dependency Analysis

### Feature #1 → Feature #2
**Dependency Type**: Weak (optional)
**Reason**: Feature #2 may use Feature #1's default Quest files for testing
**Can be skipped**: ✅ Yes, Feature #2 can be developed and tested independently

---

### Feature #2 → Feature #3
**Dependency Type**: Strong (required)
**Dependency Details**:

#### 1. Areas.kt functions are used
```kotlin
// Feature #2 defines
// Areas.kt
fun isBossArea(episode: Int, areaId: Int): Boolean
fun isPioneer2OrLab(episode: Int, areaId: Int): Boolean

// Feature #3 uses
// QuestEditorStore.kt
val showOmnispawn = map(currentQuest, currentArea) { quest, area ->
    val isPioneer2OrLab = isPioneer2OrLab(quest.episode, area.id)
    val isBoss = isBossArea(quest.episode, area.id)
    !isPioneer2OrLab && !isBoss
}
```

#### 2. QuestNpc.gameAreaId field is used
```kotlin
// Feature #2 defines
// QuestNpc.kt
class QuestNpc {
    var gameAreaId: Int = areaId  // New field
}

// Feature #3 uses
// Quest.kt
if (floorMappings.isNotEmpty()) {
    for (npc in npcs) {
        val mapping = floorMappings.find { it.floorId == npc.areaId }
        if (mapping != null) {
            npc.gameAreaId = mapping.areaId  // Sets this field
        }
    }
}
```

**Conclusion**: Feature #3 cannot compile without Feature #2

---

### Feature #3 → Feature #4
**Dependency Type**: Strong (required)
**Dependency Details**:

#### 1. QuestNpcModel ground spawning functionality
```kotlin
// Feature #2 defines
// QuestNpcModel.kt
object QuestNpcModel {
    private var _spawnOnGround = ...
    fun setGroundHeightCalculator(...)
}

// Feature #4 uses
// EntityMeshManager.kt
init {
    QuestNpcModel.setGroundHeightCalculator { x, z, section ->
        calculateGroundHeight(x, z)
    }
}
```

#### 2. QuestEditorStore display controls
```kotlin
// Feature #3 defines
// QuestEditorStore.kt
val showSectionIds: Cell<Boolean>
val spawnMonstersOnGround: Cell<Boolean>
val showOriginPoint: Cell<Boolean>

// Feature #4 uses
// EntityMeshManager.kt
observe(store.showSectionIds) { show ->
    if (show) updateSectionIdLabels() else clearSectionIdLabels()
}
```

**Conclusion**: Feature #4 needs Feature #2's NPC model and Feature #3's Store functionality

---

### Feature #4 → Feature #5
**Dependency Type**: Strong (required)
**Dependency Details**:

#### 1. Renderer functionality
```kotlin
// Feature #4 provides
// EntityMeshManager.kt
- OriginPointRenderer integration
- SectionIdRenderer integration
- RangeCircleRenderer integration

// Feature #5 uses
// QuestEditorToolbarWidget.kt
toggleSwitch("Show Section IDs") { store.setShowSectionIds(it) }
toggleSwitch("Show Origin Point") { store.setShowOriginPoint(it) }
```

#### 2. Store and Areas functionality
```kotlin
// Feature #2 + #3 provide
// Areas.kt + QuestEditorStore.kt

// Feature #5 uses
// EntityListWidget.kt
val showOmnispawn = map(store.currentQuest, store.currentArea) { quest, area ->
    val isPioneer2OrLab = isPioneer2OrLab(quest.episode, area.id)  // From Feature #2
    val isBoss = isBossArea(quest.episode, area.id)                // From Feature #2
    !isPioneer2OrLab && !isBoss
}
```

**Conclusion**: Feature #5 needs all prerequisite Features

---

## Recommended Submission Order

### Follow this strict order for PR submissions:

```bash
1️⃣  Feature #1: infrastructure-and-assets
    ├─ Files: 10
    ├─ Dependencies: None
    ├─ Review time: ~20 minutes
    └─ Wait for merge
        ↓
2️⃣  Feature #2: area-and-npc-system
    ├─ Files: 5
    ├─ Dependencies: None (or weak dependency on #1)
    ├─ Review time: ~1 hour
    └─ Wait for merge
        ↓
3️⃣  Feature #3: multi-floor-quest-system ⚠️  Core
    ├─ Files: 22
    ├─ Dependencies: Strong dependency on #2
    ├─ Review time: ~3-4 hours
    └─ ⚠️  MUST wait for #2 to merge before creating this branch
        ↓
4️⃣  Feature #4: rendering-visualization-system
    ├─ Files: 7
    ├─ Dependencies: Strong dependency on #2, #3
    ├─ Review time: ~1.5 hours
    └─ ⚠️  MUST wait for #2, #3 to merge before creating this branch
        ↓
5️⃣  Feature #5: quest-editor-ui-system
    ├─ Files: 13
    ├─ Dependencies: Strong dependency on #2, #3, #4
    ├─ Review time: ~2 hours
    └─ ⚠️  MUST wait for #2, #3, #4 to merge before creating this branch
        ↓
6️⃣  Feature #6: general-improvements (optional)
    ├─ Files: 3
    ├─ Dependencies: None
    ├─ Review time: ~15 minutes
    └─ 💡 Can be submitted at any time, recommended as last PR
```

### Important Notes

1. **Cannot skip order**: Due to strong dependencies, must strictly follow 1→2→3→4→5 order
2. **Always base on latest master**: Before creating new branch, ensure `git pull origin master` to get latest code
3. **Wait for PR merge**: Before creating next Feature branch, ensure prerequisite PR has been merged to master
4. **Avoid parallel development**: Don't develop multiple dependent Features simultaneously

## What Happens If Order Is Broken?

### Scenario 1: Creating #3 before #2 is merged
```bash
# Wrong operation
git checkout master  # master doesn't have Feature #2 changes yet
git checkout -b feature/multi-floor-quest-system
git checkout release/1.0.0 -- <files>

# Result: Compilation fails
❌ Error: Unresolved reference: isBossArea
❌ Error: Unresolved reference: isPioneer2OrLab
❌ Error: Unresolved reference: gameAreaId
```

### Scenario 2: Attempting parallel development of #3 and #4
```bash
# Even if both based on release/1.0.0, it will confuse PR reviewers
# Because #4 depends on #3's Store functionality

# PR #4 reviewer will see:
❌ "Where is the showSectionIds that this Feature depends on?"
❌ "Why doesn't QuestEditorStore have these fields?"
```

## Cohesion Optimization Summary

### File Reallocation (compared to initial version)

#### Feature #1: From 13 files → **10 files**
**Removed files** (for cohesion):
- ❌ ListCells.kt → Moved to Feature #3 (specifically serves multi-floor system)
- ❌ Messages.kt → Moved to Feature #3 (multi-floor data structure)
- ❌ Application.kt → Moved to Feature #6 (general keyboard compatibility)
- ❌ UiStore.kt → Moved to Feature #6 (general keyboard compatibility)
- ❌ Menu.kt → Moved to Feature #6 (general UI fix)

**Retained files** (highly cohesive):
- ✅ webpack.config.js - Build configuration
- ✅ Resource files (.qst, .nj, .xvm) - Episode 2/4 and NPC models
- ✅ Resource loaders - Directly related to resource files

#### Feature #2: Remains **5 files** (unchanged)
- ✅ All are area and NPC system related

#### Feature #3: From 20 files → **22 files**
**Added files** (enhanced cohesion):
- ➕ ListCells.kt - QuestEditorStore's multi-floor event filtering direct dependency
- ➕ Messages.kt - Multi-floor system core data structure

#### Feature #6: **3 files** (new)
**General improvements** (independent cohesion):
- ✅ Application.kt - macOS Cmd key support
- ✅ UiStore.kt - macOS Cmd key support
- ✅ Menu.kt - z-index fix

### Why These Adjustments?
1. **Cohesion principle**: Each Feature only contains directly related changes
2. **Easy to understand**: Reviewers can immediately understand each Feature's purpose
3. **Clear dependencies**: Inter-Feature dependencies are more explicit
4. **Independence**: Feature #6 can be submitted at any time

## Verifying Dependencies

After creating each Feature branch, verify compilation:

```bash
# After creating branch
git checkout feature/xxx

# Try to compile
./gradlew build

# Should compile successfully (if all dependencies have been merged)
# If it fails, check if prerequisite Features have been merged
```