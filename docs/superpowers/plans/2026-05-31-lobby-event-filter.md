# Lobby Event Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the quest editor from rendering every seasonal Pioneer 2 decoration at once; add a "Lobby Event" dropdown that defaults to a clean city and lets the user preview one event's decorations (or all).

**Architecture:** Render-layer filter only — objects stay in the editable model. A psolib `LobbyEvent` enum tags each `TObjCity_Season_*` object type; a web `LobbyEventFilter` UI state drives a `filteredCell` clause in `QuestEditorMeshManager`. A dynamic toolbar `Select` lists only the events present in the current area. Mirrors the existing NPC event filter (`matchesSelectedEvent`) and free-roam `Select` pattern.

**Tech Stack:** Kotlin Multiplatform (psolib commonMain), Kotlin/JS (web jsMain), `world.phantasmal.cell` reactive cells, Three.js rendering, Gradle.

**Spec:** `docs/superpowers/specs/2026-05-31-lobby-event-filter-design.md`

---

## File Structure

- **Create** `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/LobbyEvent.kt` — the `LobbyEvent` enum.
- **Modify** `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/ObjectType.kt` — add `lobbyEvent` constructor param + annotate 8 season types.
- **Create** `psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/ObjectTypeTests.kt` — mapping test.
- **Create** `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/LobbyEventFilter.kt` — `LobbyEventFilter` sealed interface, `lobbyEventSeasonOk()` predicate, `lobbyEventFilterLabel()`.
- **Create** `web/src/jsTest/kotlin/world/phantasmal/web/questEditor/models/LobbyEventFilterTests.kt` — predicate test.
- **Modify** `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/stores/QuestEditorUiStore.kt` — `selectedLobbyEvent` cell + setter.
- **Modify** `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/QuestEditorMeshManager.kt` — extend objects `filteredCell` + observe `selectedLobbyEvent`.
- **Modify** `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/QuestEditorToolbarController.kt` — dynamic options, selected, visibility, reset observer.
- **Modify** `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/QuestEditorToolbarWidget.kt` — "Lobby Event:" `Select`.
- **Delete** `psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/InspectCityObjects.kt` — throwaway debug scaffolding.

---

## Task 1: psolib `LobbyEvent` enum + `ObjectType.lobbyEvent` metadata

**Files:**
- Create: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/LobbyEvent.kt`
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/ObjectType.kt`
- Test: `psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/ObjectTypeTests.kt`

- [ ] **Step 1: Write the failing test**

Create `psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/ObjectTypeTests.kt`:

```kotlin
package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.test.LibTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObjectTypeTests : LibTestSuite {
    @Test
    fun season_object_types_map_to_their_lobby_event() {
        assertEquals(LobbyEvent.Christmas, ObjectType.ChristmasTree.lobbyEvent)
        assertEquals(LobbyEvent.Christmas, ObjectType.ChristmasWreath.lobbyEvent)
        assertEquals(LobbyEvent.Valentine, ObjectType.ValentinesHeart.lobbyEvent)
        assertEquals(LobbyEvent.Easter, ObjectType.EasterEgg.lobbyEvent)
        assertEquals(LobbyEvent.Halloween, ObjectType.HalloweenPumpkin.lobbyEvent)
        assertEquals(LobbyEvent.Sonic, ObjectType.Sonic.lobbyEvent)
        assertEquals(LobbyEvent.NewYear, ObjectType.TwentyFirstCentury.lobbyEvent)
        assertEquals(LobbyEvent.NewYear, ObjectType.Firework.lobbyEvent)
    }

    @Test
    fun welcome_board_and_non_season_types_have_no_lobby_event() {
        // WelcomeBoard (Season_Board) is intentionally always-visible.
        assertNull(ObjectType.WelcomeBoard.lobbyEvent)
        // Sampling of ordinary types.
        assertNull(ObjectType.PlayerSet.lobbyEvent)
        assertNull(ObjectType.Teleporter.lobbyEvent)
        assertNull(ObjectType.Unknown.lobbyEvent)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.ObjectTypeTests"`
Expected: FAIL — compilation error, `LobbyEvent` unresolved and `ObjectType.lobbyEvent` unresolved.

- [ ] **Step 3: Create the `LobbyEvent` enum**

Create `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/LobbyEvent.kt`:

```kotlin
package world.phantasmal.psolib.fileFormats.quest

/**
 * PSO lobby (Pioneer 2 / city) seasonal events. The game shows only the `TObjCity_Season_*`
 * decoration objects whose event matches the current lobby event (sent by the server via the
 * `DA` command). Values and numbering follow newserv `StaticGameData.cc`:
 * `1 xmas, 3 val, 4 easter, 5 hallo, 6 sonic, 7 newyear`. Only events that have at least one
 * decoration object type are represented here.
 */
enum class LobbyEvent {
    Christmas,
    Valentine,
    Easter,
    Halloween,
    Sonic,
    NewYear,
}
```

- [ ] **Step 4: Add the `lobbyEvent` constructor parameter to `ObjectType`**

In `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/ObjectType.kt`, modify the enum constructor (currently ends after `properties`). Change:

```kotlin
enum class ObjectType(
    override val uniqueName: String,
    /**
     * The valid area IDs per episode in which this object can appear.
     */
    val areaIds: Map<Episode, List<Int>>,
    val typeId: Short?,
    /**
     * Default object-specific properties.
     */
    override val properties: List<EntityProp> = emptyList(),
) : EntityType {
```

to:

```kotlin
enum class ObjectType(
    override val uniqueName: String,
    /**
     * The valid area IDs per episode in which this object can appear.
     */
    val areaIds: Map<Episode, List<Int>>,
    val typeId: Short?,
    /**
     * Default object-specific properties.
     */
    override val properties: List<EntityProp> = emptyList(),
    /**
     * For `TObjCity_Season_*` decoration objects, the lobby event during which the game shows
     * this object. `null` for ordinary objects (always shown) and for `WelcomeBoard`, whose
     * event is unknown so it is treated as always-visible.
     */
    val lobbyEvent: LobbyEvent? = null,
) : EntityType {
```

- [ ] **Step 5: Annotate the 8 season object types**

In the same file, add `lobbyEvent = ...` as a named argument to each of these 8 entries (do NOT touch `WelcomeBoard`). Each is a one-line addition after `typeId = ...,` (or after the entry's `properties = ...,` if present). The exact edits:

`EasterEgg` (typeId = 75) — add `lobbyEvent = LobbyEvent.Easter,`:
```kotlin
    EasterEgg(
        uniqueName = "Easter Egg",
        areaIds = mapOf(
            Episode.I to listOf(0),
            Episode.II to listOf(0),
            Episode.IV to listOf(0),
        ),
        typeId = 75,
        lobbyEvent = LobbyEvent.Easter,
        properties = listOf(
            EntityProp(name = "Model index", offset = 52, type = EntityPropType.I32),
        ),
    ),
```

`ValentinesHeart` (76) — add `lobbyEvent = LobbyEvent.Valentine,`:
```kotlin
    ValentinesHeart(
        uniqueName = "Valentines Heart",
        areaIds = mapOf(
            Episode.I to listOf(0),
            Episode.II to listOf(0),
            Episode.IV to listOf(0),
        ),
        typeId = 76,
        lobbyEvent = LobbyEvent.Valentine,
    ),
```

`ChristmasTree` (77) — add `lobbyEvent = LobbyEvent.Christmas,`:
```kotlin
    ChristmasTree(
        uniqueName = "Christmas Tree",
        areaIds = mapOf(
            Episode.I to listOf(0),
            Episode.II to listOf(0),
            Episode.IV to listOf(0),
        ),
        typeId = 77,
        lobbyEvent = LobbyEvent.Christmas,
    ),
```

`ChristmasWreath` (78) — add `lobbyEvent = LobbyEvent.Christmas,`:
```kotlin
    ChristmasWreath(
        uniqueName = "Christmas Wreath",
        areaIds = mapOf(
            Episode.I to listOf(0),
            Episode.II to listOf(0),
            Episode.IV to listOf(0),
        ),
        typeId = 78,
        lobbyEvent = LobbyEvent.Christmas,
    ),
```

`HalloweenPumpkin` (79) — add `lobbyEvent = LobbyEvent.Halloween,`:
```kotlin
    HalloweenPumpkin(
        uniqueName = "Halloween Pumpkin",
        areaIds = mapOf(
            Episode.I to listOf(0),
            Episode.II to listOf(0),
            Episode.IV to listOf(0),
        ),
        typeId = 79,
        lobbyEvent = LobbyEvent.Halloween,
    ),
```

`TwentyFirstCentury` (80) — add `lobbyEvent = LobbyEvent.NewYear,`:
```kotlin
    TwentyFirstCentury(
        uniqueName = "21st Century",
        areaIds = mapOf(
            Episode.I to listOf(0),
            Episode.II to listOf(0),
            Episode.IV to listOf(0),
        ),
        typeId = 80,
        lobbyEvent = LobbyEvent.NewYear,
    ),
```

`Sonic` (81) — add `lobbyEvent = LobbyEvent.Sonic,`:
```kotlin
    Sonic(
        uniqueName = "Sonic",
        areaIds = mapOf(
            Episode.I to listOf(0),
            Episode.II to listOf(0),
            Episode.IV to listOf(0),
        ),
        typeId = 81,
        lobbyEvent = LobbyEvent.Sonic,
        properties = listOf(
            EntityProp(name = "Model", offset = 52, type = EntityPropType.I32),
        ),
    ),
```

`Firework` (83) — add `lobbyEvent = LobbyEvent.NewYear,` after its `typeId = 83,` line. (Locate the `Firework(` entry — `uniqueName = "Firework"`, `typeId = 83` — and insert the line immediately after `typeId = 83,`.)

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew.bat :psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.ObjectTypeTests"`
Expected: PASS (both tests).

- [ ] **Step 7: Commit**

```bash
git add psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/LobbyEvent.kt psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/ObjectType.kt psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/ObjectTypeTests.kt
git commit -m "feat(psolib): tag season object types with their LobbyEvent"
```

---

## Task 2: web `LobbyEventFilter` state + predicate + label

**Files:**
- Create: `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/LobbyEventFilter.kt`
- Test: `web/src/jsTest/kotlin/world/phantasmal/web/questEditor/models/LobbyEventFilterTests.kt`

- [ ] **Step 1: Write the failing test**

Create `web/src/jsTest/kotlin/world/phantasmal/web/questEditor/models/LobbyEventFilterTests.kt`:

```kotlin
package world.phantasmal.web.questEditor.models

import world.phantasmal.psolib.fileFormats.quest.LobbyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LobbyEventFilterTests {
    @Test
    fun none_hides_all_season_objects_but_keeps_non_season() {
        // Non-season object (lobbyEvent == null) is always shown.
        assertTrue(lobbyEventSeasonOk(null, LobbyEventFilter.None))
        // Any season object is hidden.
        assertFalse(lobbyEventSeasonOk(LobbyEvent.Christmas, LobbyEventFilter.None))
        assertFalse(lobbyEventSeasonOk(LobbyEvent.Halloween, LobbyEventFilter.None))
    }

    @Test
    fun all_shows_everything() {
        assertTrue(lobbyEventSeasonOk(null, LobbyEventFilter.All))
        assertTrue(lobbyEventSeasonOk(LobbyEvent.Christmas, LobbyEventFilter.All))
        assertTrue(lobbyEventSeasonOk(LobbyEvent.Sonic, LobbyEventFilter.All))
    }

    @Test
    fun event_shows_only_that_event_plus_non_season() {
        val xmas = LobbyEventFilter.Event(LobbyEvent.Christmas)
        assertTrue(lobbyEventSeasonOk(null, xmas))                 // non-season always
        assertTrue(lobbyEventSeasonOk(LobbyEvent.Christmas, xmas)) // matching event
        assertFalse(lobbyEventSeasonOk(LobbyEvent.Halloween, xmas))// other event hidden
    }

    @Test
    fun labels_are_human_readable() {
        assertEquals("None", lobbyEventFilterLabel(LobbyEventFilter.None))
        assertEquals("All", lobbyEventFilterLabel(LobbyEventFilter.All))
        assertEquals("Christmas", lobbyEventFilterLabel(LobbyEventFilter.Event(LobbyEvent.Christmas)))
        assertEquals("New Year", lobbyEventFilterLabel(LobbyEventFilter.Event(LobbyEvent.NewYear)))
    }
}
```

Note: web test classes in this module use **no base class** (e.g. `class FreeRoamLoadingTests {`), so `LobbyEventFilterTests` likewise has none.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :web:jsTest`
Expected: FAIL — compilation error: `LobbyEventFilter`, `lobbyEventSeasonOk`, `lobbyEventFilterLabel` unresolved.

- [ ] **Step 3: Create the implementation**

Create `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/LobbyEventFilter.kt`:

```kotlin
package world.phantasmal.web.questEditor.models

import world.phantasmal.psolib.fileFormats.quest.LobbyEvent

/**
 * UI selection for which Pioneer 2 / city seasonal decorations to render.
 *
 * - [None]: hide all seasonal decorations (default — a clean city).
 * - [All]: show every seasonal decoration at once (the raw data; opt-in).
 * - [Event]: show only the given event's decorations.
 *
 * Objects with no [LobbyEvent] (`ObjectType.lobbyEvent == null`) are always shown.
 */
sealed interface LobbyEventFilter {
    object None : LobbyEventFilter
    object All : LobbyEventFilter
    data class Event(val event: LobbyEvent) : LobbyEventFilter
}

/**
 * Whether an object with the given [objectEvent] (its `ObjectType.lobbyEvent`) should render
 * under the current [filter].
 */
fun lobbyEventSeasonOk(objectEvent: LobbyEvent?, filter: LobbyEventFilter): Boolean =
    when (filter) {
        LobbyEventFilter.None -> objectEvent == null
        LobbyEventFilter.All -> true
        is LobbyEventFilter.Event -> objectEvent == null || objectEvent == filter.event
    }

/** Human-readable label for the toolbar dropdown. */
fun lobbyEventFilterLabel(filter: LobbyEventFilter): String =
    when (filter) {
        LobbyEventFilter.None -> "None"
        LobbyEventFilter.All -> "All"
        is LobbyEventFilter.Event -> when (filter.event) {
            LobbyEvent.Christmas -> "Christmas"
            LobbyEvent.Valentine -> "Valentine"
            LobbyEvent.Easter -> "Easter"
            LobbyEvent.Halloween -> "Halloween"
            LobbyEvent.Sonic -> "Sonic"
            LobbyEvent.NewYear -> "New Year"
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :web:jsTest`
Expected: PASS (all `LobbyEventFilterTests`). Pre-existing unrelated failures noted in project memory (`UiStoreTests` URL tests) may still appear; the new test class must pass.

- [ ] **Step 5: Commit**

```bash
git add web/src/jsMain/kotlin/world/phantasmal/web/questEditor/models/LobbyEventFilter.kt web/src/jsTest/kotlin/world/phantasmal/web/questEditor/models/LobbyEventFilterTests.kt
git commit -m "feat(web): add LobbyEventFilter state and season-visibility predicate"
```

---

## Task 3: `QuestEditorUiStore.selectedLobbyEvent`

**Files:**
- Modify: `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/stores/QuestEditorUiStore.kt`

- [ ] **Step 1: Add the cell, accessor, and setter**

In `QuestEditorUiStore.kt`:

Add imports near the top (with the other imports):
```kotlin
import world.phantasmal.web.questEditor.models.LobbyEventFilter
```

In the private cell block (after `private val _showScriptParticles = mutableCell(false)`), add:
```kotlin
    private val _selectedLobbyEvent = mutableCell<LobbyEventFilter>(LobbyEventFilter.None)
```

In the public accessor block (after `val showScriptParticles: Cell<Boolean> = _showScriptParticles`), add:
```kotlin
    val selectedLobbyEvent: Cell<LobbyEventFilter> = _selectedLobbyEvent
```

In the setter block (after `setShowScriptParticles`), add:
```kotlin
    fun setSelectedLobbyEvent(filter: LobbyEventFilter) {
        _selectedLobbyEvent.value = filter
    }
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew.bat :web:compileDevelopmentExecutableKotlinJs`
Expected: BUILD SUCCESSFUL (no usages yet; this just type-checks the store change).

- [ ] **Step 3: Commit**

```bash
git add web/src/jsMain/kotlin/world/phantasmal/web/questEditor/stores/QuestEditorUiStore.kt
git commit -m "feat(web): add selectedLobbyEvent to QuestEditorUiStore"
```

---

## Task 4: Filter objects in `QuestEditorMeshManager`

**Files:**
- Modify: `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/QuestEditorMeshManager.kt` (the `loadObjectMeshes` observer, around lines 90–109)

- [ ] **Step 1: Add the import**

Near the other imports, add:
```kotlin
import world.phantasmal.web.questEditor.models.lobbyEventSeasonOk
```

- [ ] **Step 2: Replace the objects observer**

Find this block (the objects observer):
```kotlin
        observeNow(
            questEditorStore.currentQuest,
            questEditorStore.currentArea,
            questEditorStore.currentFloorIds,
        ) { quest, area, floorIds ->
            loadObjectMeshes(
                if (quest != null && area != null) {
                    quest.objects.filteredCell {
                        val areaMatch = if (floorIds != null) {
                            it.areaId in floorIds
                        } else {
                            it.areaId == area.id
                        }
                        it.sectionInitialized and areaMatch
                    }
                } else {
                    emptyListCell()
                }
            )
        }
```

Replace it with (adds the `selectedLobbyEvent` dependency and `seasonOk` clause):
```kotlin
        observeNow(
            questEditorStore.currentQuest,
            questEditorStore.currentArea,
            questEditorStore.currentFloorIds,
            questEditorUiStore.selectedLobbyEvent,
        ) { quest, area, floorIds, selectedLobbyEvent ->
            loadObjectMeshes(
                if (quest != null && area != null) {
                    quest.objects.filteredCell {
                        val areaMatch = if (floorIds != null) {
                            it.areaId in floorIds
                        } else {
                            it.areaId == area.id
                        }
                        val seasonOk = lobbyEventSeasonOk(it.type.lobbyEvent, selectedLobbyEvent)
                        it.sectionInitialized and (areaMatch && seasonOk)
                    }
                } else {
                    emptyListCell()
                }
            )
        }
```

Notes for the implementer:
- `it.type` is a plain `ObjectType` value (`QuestEntityModel.type get() = entity.type`); `it.type.lobbyEvent` is therefore a plain `LobbyEvent?`.
- `it.sectionInitialized` is a `Cell<Boolean>`; `Cell<Boolean> and Boolean` returns `Cell<Boolean>` (the existing `and` import already covers this).
- `questEditorUiStore` is an existing constructor parameter of `QuestEditorMeshManager`.

- [ ] **Step 3: Compile to verify**

Run: `./gradlew.bat :web:compileDevelopmentExecutableKotlinJs`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add web/src/jsMain/kotlin/world/phantasmal/web/questEditor/rendering/QuestEditorMeshManager.kt
git commit -m "feat(web): filter season objects by selected lobby event when rendering"
```

---

## Task 5: Controller — dynamic options, selection, visibility, reset

**Files:**
- Modify: `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/QuestEditorToolbarController.kt`

The controller already imports `world.phantasmal.cell.*` (gives `map`, `flatMap`, `cell`, `observe`), `world.phantasmal.cell.list.ListCell`, and `world.phantasmal.psolib.fileFormats.quest.*` (gives `LobbyEvent`). Add one import for the web filter type.

- [ ] **Step 1: Add the import**

Near the other `world.phantasmal.web.questEditor.models.*` imports, add:
```kotlin
import world.phantasmal.web.questEditor.models.LobbyEventFilter
```

- [ ] **Step 2: Add the derived properties**

After the existing display-toggle accessors (after `val showScriptParticles: Cell<Boolean> = questEditorUiStore.showScriptParticles`), add:

```kotlin
    // Lobby event (Pioneer 2 / city seasonal decoration) filter.
    //
    // Dynamic options: "None" is always first; then one entry per lobby event that actually has
    // decoration objects in the current area; then "All" if at least one event is present.
    val lobbyEventOptions: Cell<List<LobbyEventFilter>> =
        questEditorStore.currentQuest.flatMap { quest ->
            if (quest == null) {
                cell(listOf<LobbyEventFilter>(LobbyEventFilter.None))
            } else {
                map(
                    quest.objects,
                    questEditorStore.currentArea,
                    questEditorStore.currentFloorIds,
                ) { objects, area, floorIds ->
                    if (area == null) {
                        listOf(LobbyEventFilter.None)
                    } else {
                        val present = objects
                            .filter {
                                if (floorIds != null) it.areaId in floorIds else it.areaId == area.id
                            }
                            .mapNotNull { it.type.lobbyEvent }
                            .distinct()
                            .sortedBy { it.ordinal }
                        buildList {
                            add(LobbyEventFilter.None)
                            present.forEach { add(LobbyEventFilter.Event(it)) }
                            if (present.isNotEmpty()) add(LobbyEventFilter.All)
                        }
                    }
                }
            }
        }

    // Only show the dropdown when there is at least one event to choose (size > 1 means more
    // than just "None").
    val showLobbyEventSelect: Cell<Boolean> = lobbyEventOptions.map { it.size > 1 }

    // Cell<out T> is covariant, so a Cell<LobbyEventFilter> is usable as Cell<LobbyEventFilter?>.
    val selectedLobbyEvent: Cell<LobbyEventFilter> = questEditorUiStore.selectedLobbyEvent

    fun setSelectedLobbyEvent(filter: LobbyEventFilter) {
        questEditorUiStore.setSelectedLobbyEvent(filter)
    }
```

- [ ] **Step 3: Add the reset observer**

Find the controller's existing `init { ... }` block (around line 342). Inside it, add:

```kotlin
        // When the area (and thus its available events) changes, drop a selection that is no
        // longer valid so a stale event doesn't silently filter the new area.
        observe(lobbyEventOptions) { options ->
            if (questEditorUiStore.selectedLobbyEvent.value !in options) {
                questEditorUiStore.setSelectedLobbyEvent(LobbyEventFilter.None)
            }
        }
```

Note: `observe` is provided by the `Controller` base class. `LobbyEventFilter.None`/`All` are singleton `object`s and `Event` is a `data class`, so `!in options` uses correct equality.

- [ ] **Step 4: Compile to verify**

Run: `./gradlew.bat :web:compileDevelopmentExecutableKotlinJs`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add web/src/jsMain/kotlin/world/phantasmal/web/questEditor/controllers/QuestEditorToolbarController.kt
git commit -m "feat(web): expose dynamic lobby-event options from the toolbar controller"
```

---

## Task 6: Toolbar widget — "Lobby Event:" Select

**Files:**
- Modify: `web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/QuestEditorToolbarWidget.kt`

- [ ] **Step 1: Add the import**

Near the top imports, add:
```kotlin
import world.phantasmal.web.questEditor.models.lobbyEventFilterLabel
```

- [ ] **Step 2: Add the Select after the "Monsters:" select**

Find the `Monsters:` `Select` block:
```kotlin
                    Select(
                        className = "pw-free-roam-select",
                        label = "Monsters:",
                        visible = ctrl.showFreeRoamV2,
                        items = ctrl.freeRoamV2Options,
                        itemToString = { it.toString() },
                        selected = ctrl.freeRoamV2,
                        onSelect = { scope.launch { ctrl.setFreeRoamV2(it) } },
                    ),
```

Immediately after it (as the next sibling in the same list), insert:
```kotlin
                    Select(
                        className = "pw-free-roam-select",
                        label = "Lobby Event:",
                        visible = ctrl.showLobbyEventSelect,
                        items = ctrl.lobbyEventOptions,
                        itemToString = { lobbyEventFilterLabel(it) },
                        selected = ctrl.selectedLobbyEvent,
                        onSelect = { ctrl.setSelectedLobbyEvent(it) },
                    ),
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew.bat :web:compileDevelopmentExecutableKotlinJs`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add web/src/jsMain/kotlin/world/phantasmal/web/questEditor/widgets/QuestEditorToolbarWidget.kt
git commit -m "feat(web): add Lobby Event dropdown to the quest editor toolbar"
```

---

## Task 7: Manual verification in the running app

**Files:** none (the dev server `./gradlew.bat :web:jsBrowserDevelopmentRun --continuous` hot-reloads).

- [ ] **Step 1: Open a city/Pioneer 2 area**

Load a GC V3 city (e.g. `D:/PSO/jpppz/root`, EP1 city) or BB EP4 city02. Confirm:
- The city renders **without** stacked decorations by default (no Christmas trees + pumpkins + eggs at once).
- A "Lobby Event:" dropdown appears in the toolbar next to "Layout:" / "Monsters:".

- [ ] **Step 2: Exercise the dropdown**

- Select "Christmas" → only Christmas trees/wreaths appear (plus the Welcome Board and base scenery).
- Select "Halloween" → only pumpkins.
- Select "All" → every decoration appears (the old behavior).
- Select "None" → decorations disappear again; Welcome Board remains.
- Confirm the dropdown lists only events actually present in the area.

- [ ] **Step 3: Confirm no model mutation**

- Open a BB EP1 city (no decorations): the dropdown shows only "None" / is hidden, and the scene is unchanged.
- Make no edits; confirm switching events does not mark the quest dirty / does not change save state (the filter is render-only).

- [ ] **Step 4 (optional): full test suite**

Run: `./gradlew.bat :psolib:jvmTest :web:jsTest`
Expected: New `ObjectTypeTests` and `LobbyEventFilterTests` pass. Pre-existing unrelated failures documented in project memory (`UiStoreTests` URL tests, `DatTests.parse_cm_dat_and_write_dat`, `QuestTests.round_trip_test_with_all_tethealla_quests`) may still fail and are out of scope.

---

## Task 8: Remove throwaway debug scaffolding

**Files:**
- Delete: `psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/InspectCityObjects.kt`

- [ ] **Step 1: Delete the file**

```bash
git rm -f psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/InspectCityObjects.kt 2>/dev/null || rm -f psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/InspectCityObjects.kt
```

(`InspectGgetFlags.kt` and `InspectSetDataTableRel.kt` are the user's own untracked scaffolding from earlier work — leave them.)

- [ ] **Step 2: Verify psolib still compiles its tests**

Run: `./gradlew.bat :psolib:compileTestKotlinJvm`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add -A psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/InspectCityObjects.kt
git commit -m "chore(psolib): remove throwaway city-object inspection test"
```

---

## Self-Review Notes

- **Spec coverage:** §Mapping → Task 1; §Architecture.1 (psolib metadata) → Task 1; §2 (`LobbyEventFilter`, store) → Tasks 2–3; §3 (filter clause) → Task 4; §4 (dynamic dropdown, disabled/None when empty, reset on area change) → Tasks 5–6; §Testing → Tasks 1 & 2; §Cleanup → Task 8; §WelcomeBoard decision (null) → Task 1 Step 5 + test in Task 1 Step 1.
- **WelcomeBoard:** never annotated; asserted `null` in `ObjectTypeTests.welcome_board_and_non_season_types_have_no_lobby_event`.
- **Type consistency:** `LobbyEventFilter.{None,All,Event}`, `lobbyEventSeasonOk`, `lobbyEventFilterLabel`, `selectedLobbyEvent`/`setSelectedLobbyEvent`, `lobbyEventOptions`, `showLobbyEventSelect` are used with identical names across Tasks 2–6.
- **Visibility vs disabled:** the spec mentions "disabled" — implemented here via `visible = showLobbyEventSelect` to match the existing free-roam `Select` pattern (which uses `visible`, not `enabled`), so the control simply hides when no events exist. This is the closest match to established toolbar conventions; if a greyed-out (always-visible) control is later preferred, swap `visible` for `enabled`.
