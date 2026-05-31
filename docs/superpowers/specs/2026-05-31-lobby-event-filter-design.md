# Lobby Event Filter — Design

**Date:** 2026-05-31
**Branch:** `feat/quest-version-support`
**Status:** Approved design, ready for implementation plan

## Problem

When a city / Pioneer 2 area is opened in the quest editor, **all seasonal decorations
render stacked on top of each other at once** — Christmas trees, Halloween pumpkins, Easter
eggs, Valentine's hearts, fireworks, etc. all visible simultaneously.

### Root cause (verified)

PSO bakes every season's decoration objects into a single city object file. The real game
shows only the decorations matching the **current lobby event** (a value the server sends via
the `DA` command); each `TObjCity_Season_*` object self-gates on that event in its constructor.
The editor has no lobby-event concept, so it renders every object unconditionally.

Verified by parsing real data files (`InspectCityObjects` jvmTest, `D:/PSO`):

| File | Bytes | Objects | Season decorations |
|------|-------|---------|--------------------|
| GC V3 `map_city00d.dat` (all episodes) | 6528 | 96 | **47** |
| GC V3 `map_city00ad.dat` | 6528 | 96 | 47 (byte-identical to `d`) |
| BB EP4 `map_city02_00d.dat` | 6528 | 96 | **47** |
| BB EP1 `map_city00_00o.dat` | 2040 | 30 | **0** (base functional objects only) |
| BB EP1 `map_city00_00o_s.dat` (offline) | 1836 | 27 | 0 |

So the overlay problem affects **GC V3 cities (all episodes) and BB EP4 city02**; BB EP1 city
has no decorations and is unaffected.

## Goals

- Default to a clean Pioneer 2 — **no seasonal decorations shown** unless a lobby event is
  explicitly selected.
- Let the user pick a lobby event to preview that event's decorations (and an "All" option to
  see everything, the old behavior, now opt-in).
- **Never mutate the editable model** — objects stay in the quest; only rendering visibility
  changes, so saving the quest is byte-for-byte unaffected.
- Version-agnostic: one implementation, keyed on object type, that is a harmless no-op where
  no decorations exist.

## Non-goals

- No change to dat loading / parsing / saving.
- No per-event texture preview for `WelcomeBoard` (see below).
- No handling of the `forest01`/`forest02` `d`/`ad` decoration delta (out of scope; loader
  only reads `d.dat`).

## The lobby-event → object-type mapping (verified against newserv)

newserv `StaticGameData.cc` lobby-event indices (V3/BB):
`0 none, 1 xmas, 2 none, 3 val, 4 easter, 5 hallo, 6 sonic, 7 newyear, 8 summer, …`

newserv `Map.cc` season object types (`TObjCity_Season_*`, typeIds 0x4B–0x53):

| ObjectType (phantasmal) | typeId | newserv name | Lobby event |
|-------------------------|--------|--------------|-------------|
| `ChristmasTree`         | 77 | `TObjCity_Season_XmasTree`        | Christmas |
| `ChristmasWreath`       | 78 | `TObjCity_Season_XmasWreath`      | Christmas |
| `ValentinesHeart`       | 76 | `TObjCity_Season_ValentineHeart`  | Valentine |
| `EasterEgg`             | 75 | `TObjCity_Season_EasterEgg`       | Easter |
| `HalloweenPumpkin`      | 79 | `TObjCity_Season_HalloweenPumpkin`| Halloween |
| `Sonic`                 | 81 | `TObjCity_Season_SonicAdv2`       | Sonic |
| `TwentyFirstCentury`    | 80 | `TObjCity_Season_21_21`           | NewYear |
| `Firework`              | 83 | `TObjCity_Season_FireWorkCtrl`    | NewYear |
| `WelcomeBoard`          | 82 | `TObjCity_Season_Board`           | **none — see below** |

### WelcomeBoard decision

`TObjCity_Season_Board` has **no parameters and no event documented** in newserv ("Holiday event
decoration"). Its displayed content is a baked model texture, not data, and phantasmal currently
renders it as a placeholder (`EntityAssetLoader`: `WelcomeBoard -> listOf("")`, with a TODO).
Because we cannot confidently assign it to a specific event, **`WelcomeBoard` is treated as a
non-seasonal / always-visible object** (`lobbyEvent = null`). It is never filtered.

## Architecture

Render-layer filter driven by a reactive store cell. This mirrors the **existing precedent** for
NPCs: `QuestEditorMeshManager` already filters NPCs by event via `matchesSelectedEvent`
(`selectedEventsSectionWaves`).

```
QuestEditorUiStore.selectedLobbyEvent : Cell<LobbyEventFilter>   (default None)
        │  observed by
        ▼
QuestEditorMeshManager  →  quest.objects.filteredCell { … seasonOk … }
        │
        ▼
EntityMeshManager (add/remove instances)   ← objects added/removed reactively
```

### 1. psolib: `ObjectType.lobbyEvent` metadata

New enum in psolib (`fileFormats/quest`):

```kotlin
enum class LobbyEvent { Christmas, Valentine, Easter, Halloween, Sonic, NewYear }
```

Add a constructor parameter `lobbyEvent: LobbyEvent? = null` to the `ObjectType` enum (alongside
existing `uniqueName`, `areaIds`, `typeId`, `properties`). Annotate the 8 filtered season types
per the table above. `null` = always shown (all non-season types, plus `WelcomeBoard`).

Rationale: the season association is an intrinsic property of the object type (like `areaIds`),
so it belongs in psolib where it is unit-testable, rather than as a hardcoded table in the web
layer.

### 2. web: `QuestEditorUiStore.selectedLobbyEvent`

A render-only UI concern, so it lives in `QuestEditorUiStore` next to `showScriptParticles` etc.

```kotlin
// None      = hide all seasonal decorations (default, clean Pioneer 2)
// All       = show every seasonal decoration (old behavior, opt-in)
// Event(e)  = show only that event's decorations
sealed interface LobbyEventFilter {
    object None : LobbyEventFilter
    object All : LobbyEventFilter
    data class Event(val event: LobbyEvent) : LobbyEventFilter
}
private val _selectedLobbyEvent = mutableCell<LobbyEventFilter>(LobbyEventFilter.None)
val selectedLobbyEvent: Cell<LobbyEventFilter> = _selectedLobbyEvent
fun setSelectedLobbyEvent(v: LobbyEventFilter) { _selectedLobbyEvent.value = v }
```

`LobbyEventFilter` is a web-layer type (it composes UI states `None`/`All` with the psolib
`LobbyEvent`), so it lives in the web module, not psolib.

### 3. web: filter clause in `QuestEditorMeshManager`

Extend the existing `quest.objects.filteredCell { … }` (the `loadObjectMeshes` observer) to also
observe `selectedLobbyEvent` and apply:

```kotlin
val seasonOk = when (val sel = selectedLobbyEvent) {
    None      -> it.type.lobbyEvent == null     // only always-visible objects
    All       -> true
    is Event  -> it.type.lobbyEvent == null || it.type.lobbyEvent == sel.event
}
it.sectionInitialized and areaMatch and seasonOk
```

Add `questEditorUiStore.selectedLobbyEvent` to the `observeNow(...)` dependency list so the scene
re-filters reactively when the dropdown changes.

### 4. web: toolbar "Lobby Event" dropdown (dynamic)

A `Select` in `QuestEditorToolbarWidget`, placed next to the existing "Layout:" / "Monsters:"
selects, wired through `QuestEditorToolbarController`.

**Dynamic options:** the dropdown lists `None`, then only the events whose decoration types are
**actually present in the current area's objects**, then `All` (shown only if ≥1 event present).

- Compute from the current area's object list: `objects.mapNotNull { it.type.lobbyEvent }.distinct()`.
- In an area with no season objects (e.g. BB EP1 city, any non-city area) the dropdown shows only
  `None` and is **disabled** (greyed out), matching how the existing "Layout:" / "Monsters:"
  selects gate their `enabled` state. The filter is a harmless no-op there.
- Selecting an event/All updates `selectedLobbyEvent`; default selection is `None`.
- When the current area changes, if the previously selected event is not present in the new
  area, the selection resets to `None`.

## Data flow summary

1. User opens a city area → objects load into the quest model (unchanged).
2. `QuestEditorMeshManager` filters objects by `areaMatch and seasonOk`; with default `None`,
   all `lobbyEvent != null` season objects are filtered out → clean Pioneer 2.
3. Toolbar dropdown is populated dynamically from the area's present events.
4. User picks "Christmas" → `selectedLobbyEvent` cell updates → filteredCell re-evaluates →
   `EntityMeshManager` adds the Christmas objects, leaves others hidden.
5. The quest model is untouched throughout; saving produces identical bytes.

## Testing

- **psolib unit test** (`ObjectTypeTests` or new): assert `lobbyEvent` is correct for the 8
  filtered season types, `null` for `WelcomeBoard` and a sampling of non-season types.
- **web test** (`QuestEditorMeshManager`-level or a small filter helper, following the NPC
  `matchesSelectedEvent` test style if one exists): given a fixed object set, assert:
  - `None` → no season objects, `WelcomeBoard` + base retained.
  - `Event(Christmas)` → only Christmas season objects + base + `WelcomeBoard`.
  - `All` → everything.
- If the season-filter predicate is extracted into a pure function, unit-test it directly to
  avoid a heavy renderer harness.

## Cleanup (housekeeping, not part of the feature)

The throwaway `InspectCityObjects.kt` jvmTest (and the sibling `InspectGgetFlags.kt` /
`InspectSetDataTableRel.kt` scaffolding) used to verify these facts are debug-only and should
not be committed with the feature. Decide separately whether to delete or gitignore them.

## Version applicability

One ObjectType-based filter applies to all versions:
- GC V3 cities (all episodes) and BB EP4 city02 → decorations present, filter active.
- BB EP1 city and all non-city areas → no season objects, filter is a no-op, dropdown shows only
  `None`.
