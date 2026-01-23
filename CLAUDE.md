# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# Run all tests (JS tests use Karma/Mocha, JVM tests use JUnit 5)
./gradlew check

# Run tests for a specific subproject
./gradlew :psolib:check
./gradlew :cell:check
./gradlew :web:check

# Web application - launch dev server at http://localhost:1623/
./gradlew :web:jsBrowserDevelopmentRun --continuous

# Web application - production build
./gradlew :web:jsBrowserDistribution

# PSO server - run
./gradlew :psoserv:run

# PSO server - native build (requires GraalVM)
./gradlew :psoserv:nativeBuild
```

## Project Architecture

This is a Kotlin multiplatform project for Phantasy Star Online tools. Code is organized into Gradle subprojects with
shared code in `commonMain` source sets.

### Subproject Dependencies

```
core                    # Base utilities (disposables, math, assertions)
  ↑
cell                    # Observer pattern implementation (reactive cells)
  ↑
psolib                  # PSO file formats, compression, script assembler/disassembler
  ↑
test-utils              # Test utilities for all subprojects
  ↑
webui                   # Web GUI toolkit
  ↑
web                     # Main web application (model viewer, quest editor, hunt optimizer)
  ├── web:shared        # Code shared with workers
  ├── web:assembly-worker # Script analysis worker
  └── web:assets-generation # Asset generation (run manually)

psoserv                 # PSO server/proxy (JVM only)
```

### Key Patterns

**Cell System** (`cell/`): Reactive observable values. `Cell<T>` holds a value that can change and be observed.
`MutableCell<T>` allows setting values. `ListCell<T>` for observable lists.

**Disposable Pattern** (`core/`): Resources implement `Disposable`. Use `Disposer` to manage multiple disposables.
`TrackedDisposable` provides leak detection in debug mode.

**Web Architecture** (`web/`): Each tool (viewer, questEditor, huntOptimizer) follows this structure:

- `widgets/` - Views that display data and forward user input to controllers
- `controllers/` - Transform data from stores for view consumption
- `models/` - Observable model objects with cell properties
- `stores/` - Shared data stores, handle loading and state management

**Code Generation**: `psolib/build.gradle.kts` generates `Opcodes.kt` from `srcGeneration/asm/opcodes.yml`.

### Test Patterns

Tests use `*Tests` suffix (e.g., `DisassemblyTests.kt`). Common test suites extend from `test-utils` classes. Tests are
in `commonTest` (multiplatform) or platform-specific source sets like `jsTest`.

## Code Style

Follow Kotlin [coding conventions](https://kotlinlang.org/docs/coding-conventions.html). The project uses JDK 17.
