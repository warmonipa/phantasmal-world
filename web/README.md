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

## Quest Particle Previews

Quest-created particle previews use the native dimensions and per-frame scale curve stored in the
game's particle effect data. Small effects are not enlarged solely to make them easier to inspect in
the full-map editor view.

### web:shared

Contains code used by web, web:assembly-worker and web:assets-generation.
