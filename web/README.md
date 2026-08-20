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

Challenge seed previews use each DAT room table's declared location count. The original client's
32-location implementation limit is not a file-format limit: patched Ephinea quests such as the AO
series can contain larger tables, which remain editable, serializable, and seed-materializable.

## Subprojects

### web:assembly-worker

Does analysis of the script assembly code and runs in a worker thread.

### web:assets-generation

This code is manually run to generate various assets used by web such as item lists, drop tables,
quest lists, etc.

NPC models can be extracted from a PSO BB installation with:

```shell
./gradlew :web:assets-generation:extractNpcModels \
  --args="<pso-data-dir> web/src/jsMain/resources/assets/npcs"
```

The extraction includes post-processing for the Episode I Sinows. The normal texture bank contains
the original Gold and Beat textures, while the Ultimate bank contains the original Red and Blue
textures. The rare textures are remapped to the slots referenced by the shared model without color
conversion or texture re-encoding. All four variants use the retracted-blade bind pose by default.

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

## Quest Editor Entity Directions

The quest editor can display cyan facing-direction arrows for every object and NPC in the current
area. The shared **Object & NPC Directions** setting is available from both the **View** menu and the
3D view context menu, and is disabled by default.

PSO entities face local negative Z before rotation. Each arrow uses the entity's world position and
world rotation, so it shows the final facing direction after section-relative transforms are
applied. The arrows are rendered as instanced helper geometry and update when entities move, rotate,
are added, or are removed. Keep the direction geometry, visibility binding, and transform/lifecycle
coverage together in `EntityDirectionIndicatorContainer` and its tests.

### web:shared

Contains code used by web, web:assembly-worker and web:assets-generation.
