# Phantasmal World

Phantasmal World is a collection of software for Phantasy Star Online.
The [web application](https://www.phantasmal.world/) has a model viewer, quest editor and hunt
optimizer. There is also a work-in-progress [PSO server](psoserv/README.md).

## Developers

Phantasmal World is written in [Kotlin](https://kotlinlang.org/) and uses
the [Gradle](https://gradle.org/) build tool. Much of the code
is [multiplatform](https://kotlinlang.org/docs/multiplatform.html) and reusable as a library.

<a href="https://github.com/warmonipa/phantasmal-world/actions?query=workflow%3ATests">
<img alt="Tests status" src="https://github.com/warmonipa/phantasmal-world/workflows/Tests/badge.svg">
</a>

<a href="https://github.com/warmonipa/phantasmal-world/actions?query=workflow%3ADeploy">
<img alt="Deploy status" src="https://github.com/warmonipa/phantasmal-world/workflows/Deploy/badge.svg">
</a>

### Getting Started

1. Install Java 17 ([Temurin](https://adoptium.net/temurin/releases/?version=17&package=jdk) is
   recommended)
2. Ensure the JAVA_HOME environment variable is set to JDK's location

Then, for the web application:

1. `cd` to the project directory
2. Launch webpack server on [http://localhost:1623/](http://localhost:1623/)
   with `./gradlew :web:jsBrowserDevelopmentRun --continuous`
3. [web/src/jsMain/kotlin/world/phantasmal/web/Main.kt](web/src/jsMain/kotlin/world/phantasmal/web/Main.kt)
   is the application's entry point

For the PSO server:

1. `cd` to the project directory
2. Start the server with `./gradlew :psoserv:run`
3. [psoserv/src/main/kotlin/world/phantasmal/psoserv/Main.kt](psoserv/src/main/kotlin/world/phantasmal/psoserv/Main.kt)
   is the server's entry point

[IntelliJ IDEA](https://www.jetbrains.com/idea/download/) is recommended for development. IntelliJ
setup:

1. Use Ctrl-Alt-Shift-S to open the Project Structure window and select a JDK (you can let IntelliJ
   download a JDK if you don't have a compatible one installed)
2. Configure the Gradle run task for the web application:
    1. In the Gradle window, find and right click `web`'s `jsBrowserDevelopmentRun` task
    2. Click "Modify Run Configuration..."
    3. Add `--continuous` to the arguments field
    4. Click OK
    5. You can now start the webpack server from the main toolbar

### Exploring the Code Base

The code base is divided into ten Gradle subprojects. The web application runs in the browser,
with script analysis in a Web Worker. `psoserv` is a separate JVM PSO TCP server/proxy, not an HTTP
backend for the web application. The shared libraries support both applications; asset generation
is a build-time tool.

Reverse-engineering reference documentation:

- [Quest VM NPC opcodes (V2, V3, and V4)](psolib/QUEST_VM_NPC_OPCODES.md)

#### core

Core contains the basic utilities that all other subprojects directly or indirectly depend on.

#### psolib

Psolib contains PSO file format parsers, compression/decompression code, a PSO script
assembler/disassembler and a work-in-progress script engine/VM. It also has a model of the PSO
scripting bytecode and data flow analysis for it. This subproject can be used as a library in other
projects.

Binary cursors enforce the bounds of their own view, including nested views, independently of the
backing buffer's capacity. Reads cannot escape that view. `BufferCursor` can grow its backing
buffer for writes; `ArrayBufferCursor` remains bounded by its fixed backing `ArrayBuffer`.
Keep read-boundary checks aligned across the common and JS implementations.

#### cell

A full-fledged multiplatform implementation of the observer pattern.

#### test-utils

Test utilities used by the other subprojects.

#### [web](web/README.md)

The actual Phantasmal World web application.

Its nested subprojects are `web:assembly-worker` (script analysis), `web:shared` (shared messages
and data), and `web:assets-generation` (JVM asset generation and golden checks). Their ownership
boundaries are documented in the [web README](web/README.md).

#### webui

Web GUI toolkit used by Phantasmal World.

#### [psoserv](psoserv/README.md)

Work-in-progress PSO server and PSO proxy supporting PC and BB protocol formats.

### Unit Tests

Run the unit tests with `./gradlew check`. JS tests are run with Karma and Mocha, JVM tests with
Junit 5. Tests can also be run per project with e.g. `./gradlew :psolib:check`. The web project's
Mocha timeout is raised to 60 seconds in `web/karma.config.d/mocha-timeout.js` because some async
tests load and render many assets in sequence.

Async tests must return the result of `testAsync`, e.g. `fun foo() = testAsync { ... }`. On Kotlin/JS
it is the promise Mocha waits for; calling `testAsync` inside a block body discards it, so the test
passes before its assertions run and can disturb the leak tracking of later tests.

Before handing off changes, run `./gradlew check :web:jsBrowserDistribution` to validate all
projects, generated-asset golden checks, and the production bundles together. When filtering
Kotlin/JS tests, use a matching wildcard such as `--tests '*AsmDocumentTests*'` and confirm that
the XML reports under each project's `build/test-results` contain the intended cases; a successful
task with zero matching tests is not validation.

Regression coverage includes document/save/undo state, asynchronous loader and worker ownership,
binary cursor boundaries on JVM and JS, and proxy framing/lifecycle with loopback sockets.
Browser tests use controlled file-system boundaries and rendering doubles where appropriate;
real file-picker permissions, WebGL pixel output, and real PSO client/server compatibility require
separate integration checks. Test counts include executions on multiple platforms and do not
measure line or branch coverage.

Shared JVM dependency versions are maintained in
[`common.gradle.kts`](buildSrc/src/main/kotlin/world/phantasmal/common.gradle.kts). Check resolved
logging dependencies with `./gradlew :psoserv:dependencyInsight --dependency log4j-core
--configuration runtimeClasspath` when changing them.

### Code Style and Formatting

The Kotlin [coding conventions](https://kotlinlang.org/docs/coding-conventions.html) are used.

### Production Builds

#### Web Application

Create an optimized production build with `./gradlew :web:jsBrowserDistribution`.

Production deployment is performed by manually running the `Deploy` GitHub Actions workflow. The
workflow builds and deploys the selected branch or Git ref to the `gh-pages` branch, so verify the
selected ref before dispatching it.
The `Tests` workflow validates pushes and pull requests targeting `master`; it does not deploy.
Other branches rely on local validation unless the workflow configuration is changed.

#### PSO Server

Create a native production build with `./gradlew :psoserv:nativeBuild`. Cross-compilation is not
possible, this command has to be run on each platform.
