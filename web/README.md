# web

This is the main Phantasmal World web application. It consists of several tools, each in their own
package. Beside these packages there's also an application, core and externals package.

## Main Packages

### application

The application package contains the main application view that provides navigation between the
different tools. The application view lazily loads and initializes the necessary tools.

### core

Contains code that is reused throughout the web project.

### externals

External declarations for NPM dependencies.

### huntOptimizer, questEditor, viewer

One main package per tool. Each tool is encapsulated in a PwTool implementation.

## Common Structure

The main packages all follow the same structure except for the externals package.

### widgets

The widgets package contains views with minimal logic. They simply display the models their
controller provides and forward user input to their controller. Their only dependency is the DOM and
a single controller.

Keeping logic out of the views makes the UI easier to test. We don't really need to have unit tests
for the views as they don't contain complex code, just having unit tests from controller layer down
and manually smoke testing the GUI layer gives us enough confidence that everything works.

### controllers

The controllers package contains the controllers on which views depend. Usually the view-controller
relationship is one-to-one, sometimes it's many-to-one
(e.g. when a view has many subviews that work with the same data). A controller usually extracts
data from a shared store and transforms it into a format which the view can easily consume. A
controller has no knowledge of the GUI layer.

### models

The models package contains observable model objects. Models expose read-only cell properties
and allow their properties to be changed via setters which validate their inputs.

### stores

The stores package contains shared data stores. Stores ensure that data is loaded when necessary and
that the data is deduplicated. Stores also contain ephemeral shared state such as the currently
selected entity in the quest editor.

### rendering

`core/rendering/conversion` turns Ninja models (XJ/NJ) and XVR textures into three.js meshes and
textures for both the Viewer and the Quest Editor: vertex colors, material diffuse, Ninja blend
factors, opaque/transparent strip tables, back-face culling and mipmaps. What this reproduces from
PSO and what it deliberately leaves out is documented in
[docs/rendering-fidelity.md](../docs/rendering-fidelity.md).

Quest Editor rendering components consume focused capability interfaces from `QuestEditorState.kt`
instead of the concrete `QuestEditorStore`. Cross-cutting editor invariants remain atomic inside the
store without making that concrete store the rendering API. Event selection and viewport entity
selection are separate capabilities. Renderer-backed capabilities such as NPC ground placement are
scoped to one Quest Editor instance and have a single explicit lifecycle owner.
Mesh consumers depend on the `EntityMeshLoader` capability rather than the asset-cache
implementation. `EntityAssetLoader` keeps cached mesh prototypes and gives each consumer a clone
with independently disposable geometry, materials, and textures. Cloned textures are explicitly
marked for their first GPU upload because Three.js does not preserve that state when cloning;
materials that share a source texture continue sharing one owned clone. Failed prototype loads are
evicted so transient asset errors can be retried instead of caching a fallback mesh.

`QuestMeshManager` is the rendering composition root. It owns one manager each for labels, selection
visualizations, Challenge previews, and NPC grounding; `EntityMeshManager` is limited to instanced
entity meshes and their per-instance indicators. Selection helpers consume the same visible-object
list as object meshes, and NPC grounding is invalidated whenever collision geometry changes. Each
manager removes its owned scene nodes and disposes their resources when its lifecycle ends.

`EntityMeshManager` places each entity in the instanced mesh for its render key: entity type, object
model, Ultimate skin, and texture variant. A texture variant is an object parameter that selects one
of the textures bundled in the object's XVM, following newserv's Map.cc: the Forest Door digit, the
blue or red warp lines (param6 of floor, quest, ending and EP2 warps), and the laser fence colors
(param1 of laser fences, param6 of their switch, param5 of the Ruins 4x2 fence and fence switch).
Editing the parameter moves the entity to the matching mesh. The Entity panel edits warp and fence
colors with a select box using newserv's color names; an out-of-range value shows the color the
client renders (any nonzero warp param6 is red, fence colors clamp to red..magenta). The Forest
door key switch keeps a numeric field because its param6 selects one of ten unnamed colors. The key
is re-read after the asynchronous mesh load, so a model change during the load cannot leave the
entity in a stale mesh. Instanced meshes have a fixed capacity; an entity that doesn't fit is logged
and skipped, and is added again once a render-key change selects another mesh. Disposing an instance
container also disposes its instances.

Challenge seed previews use each DAT room table's declared location count. The original client's
32-location implementation limit is not a file-format limit: patched Ephinea quests such as the AO
series can contain larger tables, which remain editable, serializable, and seed-materializable.
The same extended format uses the high byte of the Event2 wave field as metadata, while the low byte
remains the materialization wave limit. Event2 entries that generate zero enemies are control events
and do not require a random-location room table.

Dock layouts use GoldenLayout 1.5.9 with popouts disabled. `DockWidget` suppresses that version's
legacy window `unload`/`beforeunload` binding before initialization; widget size cells and explicit
disposal remain the owners of resize and teardown behavior.

## Document and loading ownership

Every quest save synchronously commits valid script text before serializing. Assembly diagnostics
with errors block the write; the analyser's partial IR is never treated as a savable script.
All output buffers are prepared before asynchronous file access begins. A successful write marks
only the captured document revision saved, so edits made during the write remain dirty. Replacing
the document invalidates callbacks for its old save and pending assembly.

Hexadecimal and Hide NOP display changes retain the same Monaco model and undo history. Their
history entries share the underlying document revision; initial and reformatted revisions retain
their original IR so undoing a hidden-NOP edit restores the original instructions. The main command
stack uses unique revision identities so a new branch cannot reuse a discarded save point.

Lobby DAT and Free Roam saves validate the portions of the quest that their output files cannot
store against the loaded baseline. Unsupported edits block saving and remain dirty. Empty object,
NPC, and event outputs overwrite existing files to persist deletions; absent output targets are
never interpreted as filenames.

Viewer geometry, textures, and animations have independent request revisions. Results and failures
may update only the outputs their request still owns; disposal invalidates all pending work.
Local imports also check the content revision so newer selections or imports supersede older file
reads. A texture-only replacement can coexist with an in-flight geometry request.

Regression suites cover these boundaries in `AsmDocumentTests`, `UndoStackTests`,
`QuestSaveCoverageTests`, `FreeRoamSaveTests`, `AsmFloorMappingTests`, and `ViewerLoadingTests`.
Save tests use controlled file-system handles and parse the resulting bytes; loader tests control
completion order and failures. Native file-picker permissions and actual WebGL pixel output still
require separate integration checks.

## Subprojects

### web:assembly-worker

Does analysis of the script assembly code and runs in a worker thread.

The worker batches client messages and reports analysis results as notifications. `SetAsm` and
`UpdateUsedFloorIds` carry an incrementing generation that the worker echoes in every notification;
the client drops notifications that do not match its current script or DAT-context generation.
Text and context changes are processed in the same queue. A new generation republishes current
outputs even when their values are unchanged, because the client may have discarded the previous
generation's notification.

Floor-map derivation uses the loaded quest version and floors used by DAT entities/events; the
assembly editor continues to use the BB_V4 dialect. Entity/event collection changes update the
floor context without replacing script text or undo history. Only script-owned map layouts accept
worker mapping updates; Lobby and Free Roam retain the layout explicitly selected by their loader.

### web:assets-generation

This code is manually run to generate various assets used by web such as item lists, drop tables,
quest lists, etc.

Run `./gradlew :web:generateAssets` to generate these assets and copy them into
`src/jsMain/resources/assets`, the resources directory consumed by the web build.

NPC models can be extracted from a PSO BB installation with:

```shell
./gradlew :web:assets-generation:extractNpcModels \
  --args="<pso-data-dir> web/src/jsMain/resources/assets/npcs"
```

The extraction includes post-processing for the Episode I Sinows. The normal texture bank contains
the original Gold and Beat textures, while the Ultimate bank contains the original Red and Blue
textures. The rare textures are remapped to the slots referenced by the shared model without color
conversion or texture re-encoding. All four variants use the retracted-blade bind pose by default.

Dubchic and Gilchic share the `me2` model. `bm_ene_dubchik.bml` entry 0 carries Dubchic's texture
and entry 1 a rusted variant; Gilchic's texture is the standalone `me2_y_me2_z.xvm`. The Ultimate
files follow the same layout: Dubchich (yellow) is `bm_ene_dubchik_a.bml` entry 0, and Gilchich
(white and pink) is that model with the standalone `me2_y_me2_z_a.xvm`.

## Model Viewer Assets

The viewer weapon catalog is generated from ItemPMT and Unitxt. Separate items can share a geometry
model and select a different texture from ItemPMT. For example, Agito (1975, 1977, 1980, 1983,
1991, and 2001) uses model 15 and texture 271 for every year. Orotiagito shares model 15 but uses
texture 15.

Viewer URLs always contain the selected `model` when one is selected. The `section_id` and `body`
parameters apply only to character models and are removed from NPC and item model URLs.

Item model archives can contain more than one XJ or NJ root. The viewer preserves the original
behavior of rendering only the first root unless a model has a verified multi-component catalog
presentation. Currently, only model 54 (`Wok of Akiko's Shop`) combines its two XJ roots (the wok
and ladle) and enables UV-less texture environment mapping. These are model-specific exceptions;
they must not be inferred globally from root count, missing UVs, or the PMT weapon kind because
those properties are also used by unrelated weapon geometry and effects.

Weapon catalog rotations and multi-item layouts are likewise explicit model-index presentation
rules. When adjusting a named weapon, retain the established defaults for every unverified model
and add regression coverage for both the named model and an unaffected neighboring/default model.

## Quest Particle Previews

Quest-created particle previews use the native dimensions and per-frame scale curve stored in the
game's particle effect data. Small effects are not enlarged solely to make them easier to inspect in
the full-map editor view.

## Quest Fog Boundaries

View > Fog Boundaries draws the fog triggers of the current floor as defined in newserv's object
table (`src/Map.cc`):

- `0x0007` TObjFogCollision: param1 radius, param4 fog entry number, param5 transition (1 =
  instant).
- `0x0018` TObjFogCollisionSwitch: as `0x0007`, plus param3 (1 or more: fog is on while the switch
  flag is on, otherwise while it is off) and param6 switch flag.
- `0x0160` TObjFogCollisionPoison: as `0x0018`, plus param2 poison power. On the Episode I Vol Opt
  floor `0x0160` is the TObjWarpBoss03 exit and is not drawn.
- `0x0391` Heat: same parameters as `0x0160`.

A fog entry number lower than the current fog number does nothing; a number of `0x1000` or more is
compared as is and then has `0x1000` subtracted, so it can override a later fog. Labels show both
forms. Boundaries and their labels use the entry's colour from `fogentry.dat` (the client's table,
identical to newserv's JP BB resource) and are sized in world units, so they zoom with the map.
Overlapping triggers with the same label are labelled once, and triggers stacked at one spot that
differ only in their switch flag are drawn once with all their flags listed.

Selecting a fog trigger previews its entry as scene fog: linear distance fog from the entry's start
to its end distance with the clear colour set to the fog colour. The preview does not animate
pulsing (type 2) entries or apply a floor's default fog, which newserv does not document. Three.js
blends fog with a smoothstep curve rather than D3D's linear ramp.

## Quest Walkthrough Routes

The Quest Editor 3D view draws the walkthrough of one selected player (Red, Green, Yellow and Blue
map to client IDs 0 through 3); it is derived from quest data, not authored Guide content. Planning
has two layers.

**Journey** (`psolib/.../fileFormats/quest/walkthrough/QuestJourney.kt`, no geometry). The floors
a client plays, in order. A floor with Player Sets for other clients but none for this one is not
part of its journey and shows no route; this is how quests split players 2/2 or 1/1/1/1. Floors
without any Player Set are played by everyone. Floors connect through floor exit objects
(teleporters, Quest Warps, boss teleporters), the Pioneer 2 Ragol warp enabled by a reachable
`set_mainwarp` (matched to the client map a floor is designated with, otherwise converted to an
episode area), and client-scoped `go_floor`. The journey starts on the script's `initial_floor`
(or Pioneer 2), moves to the nearest unvisited floor by hops while leaving dead ends for last, and
returns to Pioneer 2 when Pioneer 2 is part of the client's data. Equally near floors are ordered
by the complete journey they start (fewest floor transitions, including the return to Pioneer 2),
so in Penumbral Surge #6 the tower whose top sends players on to the other tower is played first. A Ragol warp to the end of a
chain lands on the chain's head only when that head is the target's single otherwise-unreachable
predecessor.

Floors still unreached are then tried with `go_floor`/`set_mainwarp` found elsewhere in the
script, such as threads that wait for an event before opening a floor ("entered through a script
the analysis cannot trace"; their position relative to other floors is a guess), and finally with
the Ragol warp destinations that game progress opens (the first floor of each region, unless the
quest runs `clear_mainwarp` on it). A floor whose entrance the journey passed but could not return
to is reported as an alternative branch (e.g. a menu choice between courses).

A client arrives on a floor at its Player Set with Return flag 0 going forward and Return flag 1
coming back from a later floor (Quest Warps name their Player Set; quests that disable telepipes
often omit Return flag 1 sets, in which case the other flag is used); Return flags use the low 16
bits and floors that number slots 1–4 map client c to slot c + 1. On a floor without Player Sets
the client appears at the floor's exit back to where it came from. Challenge quests visit their
floors in ascending order.

**Floor route** (`WalkthroughRoutePlanner`, walkable collision of the visible floor). Each visit
to a visible floor is planned from its arrival to the exit leading to the journey's next floor;
the final Pioneer 2 visit draws nothing because the quest is reported at the Hunter's Guild
counter, outside quest data. Among usable exits, the one next to the client's Return flag 1 Player
Set (within 100 units) is preferred, otherwise the farthest. Interactions whose script enables the
Ragol warp to the next floor are visited before the Ragol teleporter. When a visit's exit cannot be
reached or placed (an untraced script `go_floor`, a Ragol warp without a teleporter object, exits
unreachable from the arrival), the route stops after the required objectives and reports "no
reachable exit" instead of guessing an endpoint; only final and order-unknown visits fall back to
the farthest objective.

Doors and laser fences block the route until their switch flags are on, following newserv's
`Map.cc`: a negative flag (or 0x100) is always open, a flag above 0x100 is always locked, and
multi-switch doors need every flag of their range (Caves/Mines doors store 4 minus the count,
CCA/Seabed/Temple/Spaceship doors the count). A flag is turned on by a DAT event chain that unlocks
it (from an Event Collision or an enemy box), by a script reachable from an interaction
(`unlock_door2`, `switch_on`, `sw_send`, or a `setevt` whose event unlocks it), or by a switch
object a player uses: Forest switches, laser fence switches, generic switches, Caves/Mines/Ruins
floor buttons, Ruins switches, invisible touch plates, item-detecting switches, sensors and
attackable collisions. Flags the start script turns on (floor handlers and the threads they start)
need nothing. `masterkey_on`/`masterkey_off` never open doors: the first locks every door and the
second only removes that override. A path is blocked when it crosses a closed door or fence across
its width, at its height (paths that only clip a fence's end, such as a square fence's corner, walk
around it); doors whose collision splits the walkable ground are crossed between their two sides.
Waypoints on both sides of every doorway let a route go around a closed door or fence through
another doorway. Laser fences string their lasers along their local Z axis and are crossed along local X;
doors are the other way round.

Several Event Collisions of one event, or switches of one flag, are alternatives: reaching one of
them is enough. A collision trigger (Event Collision, script collision) fires anywhere within its
radius, so a trigger whose radius reaches past a door or fence also gets a stand-in node on the
far side, and one whose center isn't on walkable ground gets stand-ins inside its radius.

The objectives that open a door or fence are visited before the exit, like interactions which
enable the Ragol warp; warp-gated floors (reachable bytecode disables and later enables map warps)
also visit their reachable DAT event collisions. Candidate order keeps all remaining objectives and
the terminal exit reachable, so one-way same-floor warps are not taken too early. A door or fence
whose flag nothing known turns on is reported ("needs switch flag N, which nothing on this floor
sets") and stays closed, unless it is the only way to the floor's exit: then the quest must open it
in a way the planner doesn't model, and the route passes it with a diagnostic.

Paths are computed on walkable collision triangles; walls and steep collision faces are excluded
before navigation connectivity is built. Every object that exposes an intra-map destination,
including ordinary, site-to-site, forced, Insta-Warp and Lab/city map warps, provides a directed,
non-rendered transition between otherwise disconnected walkable regions; so does an interaction
whose script moves the client with `p_setpos`/`p_move` to resolvable coordinates. These jumps are
drawn as dashed arcs from the warp to where it lands. Objects that stand just past the edge of the
walkable ground (e.g. Insta-Warp pads at the end of each Control Tower floor) snap to the nearest
walkable point within 8 units. A point counts as walkable only when a navigation polygon contains it:
three-pathfinding's group lookup falls back to the nearest polygon within 50 units, and its path
search excludes points on a polygon's far edges. Where neighbouring collision pieces meet without
sharing vertices (T-junctions, or vertices a hair apart on either side of the vertex-merge
rounding), seams join them; where two surfaces are stacked a little apart (a switch's base on the
floor), paths try each surface within 2 units of the nearest one. No section IDs,
event anchors, door counts, or straight-line guesses manufacture connectivity. Rendered route
ribbons follow navigation corners and repeatedly sample the local collision surface, keeping a
small clearance above uneven or vertically stacked ground.

Limits: physical reachability is only known for the visible floor, so floor-level forks take the
nearest unvisited floor; stacked same-floor warps with runtime-selected destinations are all
treated as usable; and quests without `set_mainwarp` start on their lowest Ragol floor. The script
analysis merges constant registers at join points and stops re-exploring callees in new call
contexts beyond a state budget, so large scripts stay bounded.

The Route selector is off by default, and route-only quest analysis and collision pathfinding remain
inactive in that state. Changing the selection replans the selected client's journey without
changing the editor's entity selection.
Direct client/floor dispatch is pruned for that route, while unresolved runtime branches remain
conservative. Route meshes live in `QuestRenderContext.helpers`, do not participate in picking, and
own their geometry/material lifecycle in `WalkthroughRouteRenderer`.

Logical floors, map areas, and variants are published as one editor-state transaction. Area geometry
loads asynchronously: the previous collision/render pair is cleared immediately, canceled loads
cannot publish stale or partial geometry, and a route is planned only after the matching collision
object and its bounding box are both ready. When route display is enabled, any relevant edit clears
the old route and restarts a fixed 500 ms delay. The route and its cached collision pathfinder are
computed once after inputs remain unchanged for that delay; continuous edits do not trigger live
replanning. Planning cooperatively yields between floors, all-pairs pathfinding chunks, and longer
shortest-path scans so newer input can cancel an in-flight calculation. Entity section readiness
without a position change must not trigger another plan.

## Quest Editor Object Visual Classes

An object without a static model is not necessarily invisible. `ObjectVisualClass` distinguishes
static models, editor-only markers, invisible logic, runtime-generated visuals, known unavailable
models, and unverified object types. The Quest Editor asset loader and standalone Viewer share this
classification so model availability cannot drift between them. The yellow cylinder remains the
generic fallback representation for the non-static classes until each class receives a dedicated
editor marker.

Object IDs, names, and visual semantics follow newserv's `Map.cc` definitions as the authoritative
cross-version reference; qedit labels are reference material only. In particular, `0x0180` is the
Lobby Game Menu Collision, `0x0212` is Seagull, `0x0213` is Jungle Design, and `0x02BA` is Quest
Collision 2.

The 82 object types that intentionally do not load a static model are distributed as follows. These
counts describe object types, not the number or spatial distribution of instances on a particular
quest floor.

| Visual class | Object types | Meaning | Representative objects |
| --- | ---: | --- | --- |
| `EditorMarker` | 1 | Editor-only position marker | Player Set |
| `InvisibleLogic` | 36 | Non-rendered triggers, collisions, controllers, and forced transitions | Event Collision, Quest Collision 2, Insta Warp, Lobby Game Menu Collision |
| `RuntimeVisual` | 21 | Client- or map-specific visuals without a standalone editor object model | Particle, Lens Flare, Seagull, Barba Ray Teleporter |
| `UnavailableModel` | 12 | Visible or model-backed objects for which the editor has no usable model mapping | Jungle Design, Ruins enemy boxes, Solid Desert Plant, GBA Station |
| `Unverified` | 12 | Object types whose visual semantics have not been confirmed | Unknown object types, Battery |
| **Total** | **82** | All explicitly classified non-static object types | |

`StaticModel` objects normally render their loaded asset and are not included in the table. A
non-static class, or a static asset that loads but contains no usable geometry, falls back to the
yellow cylinder. A missing asset or load failure remains an error instead of silently becoming a
marker. Consequently, a yellow cylinder means "no usable static model was rendered"; it does not by
itself mean that the game object is invisible. The number and placement of yellow cylinders in a
scene depend on the object instances in the loaded quest and floor.

## Quest Editor Entity Directions

The quest editor can display cyan facing-direction arrows for every object and NPC in the current
area, including materialized Challenge Mode monsters after applying a seed. The shared
**Object & NPC Directions** setting is available from both the **View** menu and the
3D view context menu, and is disabled by default.

PSO entities face local positive Z before rotation. Each arrow uses the entity's world position and
world rotation, so it shows the final facing direction after section-relative transforms are
applied. The arrows are rendered as instanced helper geometry and update when entities move, rotate,
are added, or are removed. Keep the direction geometry, visibility binding, and transform/lifecycle
coverage together in `EntityDirectionIndicatorContainer` and its tests.

## Quest Editor 2D Map Export

**File -> Export 2D Maps...** writes one top-down PNG per quest area and downloads them as a single
zip (`QuestMapExporter`). The export is a top-down orthographic "slice" of the same 3D scene, not a
separately drawn schematic.

- **Background** is always the flat "Simple View" collision geometry
  (`AreaAssetLoader.loadCollisionGeometry`), regardless of the editor's current Simple View toggle.
  It reads cleaner from above than the textured render geometry (no roofs). Bounds come from the
  collision geometry, so each image is framed to the walkable floor.
- **Markers** are objects and bosses only, drawn as flat three.js `Sprite`s. Classification lives in
  `MapMarkerIcon.kt`: `MapMarkerIcons.categoryForObject` / `categoryForNpc` map to a
  `MapMarkerCategory`. Regular enemies, minions, and friendly NPCs, plus logical/invisible objects
  (sound emitters, collisions, cameras, particles, spawn points, and any unmapped type), are not
  drawn. `MapMarkerCategory` is the single source of truth: both the on-map marker texture and the
  legend swatch are drawn by the same routine, so changing a symbol updates both.
- **Only areas with entity data are exported.** Pioneer 2 / Lab (area id 0) is always kept even when
  it has no entities.
- **Legend placement.** The map is rendered on its own, then composited onto a wider 2D canvas with
  the legend in a right-hand gutter (`composeWithLegend`), so the legend never overlaps the map. The
  export renderer uses `pixelRatio = 1` to keep that composition predictable.
- **Marker positions** use `sectionToWorld` (section-relative position transformed by the section's
  full Euler rotation plus origin, mirroring `QuestEntityModel`); only world X/Z are used, so height
  differences are intentionally flattened.

Maintenance boundaries and interop pitfalls that already caused runtime-only failures:

- JSZip is a CommonJS default export, so `externals/jszip/jszip.kt` binds it with a
  declaration-level `@JsModule`/`@JsNonModule`, not `@file:JsModule` (a file-level module would look
  for a nonexistent named `JSZip` export and fail with "JSZip is not a constructor").
- Pass JSZip options with the typed `obj<JSZipFileOptions>` / `obj<JSZipGenerateOptions>` helper, not
  `js("{ ... }")` object literals (which evaluate to `undefined` at runtime).
- These layers are not exercisable through the stubbed test renderer, so they have dedicated
  headless-browser regression tests (`QuestMapExporterZipTests` runs real `JSZip` + canvas
  `toDataURL`); icon classification and the world-position transform are covered by
  `MapMarkerIconTests` and `QuestMapExporterTests`. The actual WebGL pixel output is not verified in
  tests.

## Quest Editor Free-Roam Lobbies

The Quest Editor's built-in free-roam menu mirrors Ephinea's 30-lobby layout: Lobby 1–10 use the
standard Section ID themes, Lobby 11–20 use the Version 2 color reskins, Lobby 21–25 use the
Episode III card lobbies, and Lobby 26–30 use the Go Ball maps. Lobby quests use floor/Section ID
15 (`0x0F`). Their render assets and default object DAT files are bundled under
`src/jsMain/resources/assets/areas` and `src/jsMain/resources/assets/quests/lobby/data`.

A loose lobby object DAT can be opened directly when its filename matches a supported lobby asset,
including the legacy Episode III soccer aliases. Because these files contain objects only, a
built-in or loose lobby is saved as a `.dat`; it is not offered as a synthetic `.bin` or `.qst`.

Keep lobby numbers, asset basenames, and DAT filenames together in `FreeRoamAreaDefs.kt` via
`LOBBY_VARIANTS`. Loading, menu construction, and asset lookup consume that table so the 30-entry
layout stays synchronized. Legacy quest variant 0 remains outside the numbered layout: it uses
`map_lobby_00` geometry with the lobby 1 texture because no `map_lobby_00.xvm` exists.

### web:shared

Contains code used by web, web:assembly-worker and web:assets-generation.
