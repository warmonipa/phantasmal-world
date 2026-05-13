# NTE / V0_V2 Quest Parsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `psolib` parse and write-back PSO quest files in the V0_V2 dialect family (DC_NTE, DC_V1, DC_V2, PC_NTE, PC_V2, GC_NTE) with heuristic version detection, so `quest58_j_nte.bin` strict-parses with zero problems and round-trips IR-equivalent.

**Architecture:** Add a version axis (8-value `Version` enum) that threads through `opcode table → bytecode parser → IR size → assembler/disassembler → save`. Opcode data stays in `opcodes.yml` with new `versions` and `args` fields plus dual entries (~61) for opcodes that differ between V0_V2 inline-args and V3_V4 stack-args dialects. Code generation emits `opcodesFor(version)`. Auto-detect tries each candidate strict-parse and picks the lowest `(invalidCount, unknownCount, totalNops, binFormatDefaultRank)`.

**Tech Stack:** Kotlin Multiplatform (commonMain + jvmTest + jsTest), Gradle (`build.gradle.kts` for codegen), SnakeYAML for opcodes.yml, JUnit/kotlin.test for tests. Reference data from newserv `src/QuestScript.cc`.

**Spec:** `docs/superpowers/specs/2026-05-13-nte-quest-parsing-design.md`

**Branch:** `feat/gc-nte`

**Test command:** `./gradlew psolib:check` (full); `./gradlew psolib:jvmTest --tests "<FQN>"` (single test); `./gradlew psolib:generateOpcodes` (codegen only).

---

## Phase 1 — Types and enums

### Task 1: Expand `Version` enum + add `Dialect`

**Files:**
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Version.kt`
- Create: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Dialect.kt`
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/compatibility/PSOVersion.kt`
- Modify: every file referencing `Version.DC`, `Version.GC`, `Version.PC`, `Version.BB` (run `grep -rn "Version\\.\\(DC\\|GC\\|PC\\|BB\\)\\b" psolib/src/` — 41 sites)
- Test: `psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/VersionTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
// psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/VersionTest.kt
package world.phantasmal.psolib.fileFormats.quest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VersionTest {
    @Test
    fun v0_v2_versions_have_v0_v2_dialect() {
        assertEquals(Dialect.V0_V2, Version.DC_NTE.dialect)
        assertEquals(Dialect.V0_V2, Version.DC_V1.dialect)
        assertEquals(Dialect.V0_V2, Version.DC_V2.dialect)
        assertEquals(Dialect.V0_V2, Version.PC_NTE.dialect)
        assertEquals(Dialect.V0_V2, Version.PC_V2.dialect)
        assertEquals(Dialect.V0_V2, Version.GC_NTE.dialect)
    }

    @Test
    fun v3_v4_versions_have_v3_v4_dialect() {
        assertEquals(Dialect.V3_V4, Version.GC_V3.dialect)
        assertEquals(Dialect.V3_V4, Version.BB_V4.dialect)
    }

    @Test
    fun version_bits_are_unique_powers_of_two() {
        val bits = Version.entries.map { it.bit }
        assertEquals(Version.entries.size, bits.toSet().size)
        bits.forEach { b -> assertEquals(0, b and (b - 1), "bit $b is not a power of two") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.VersionTest"`
Expected: FAIL with "unresolved reference: DC_NTE" or "unresolved reference: Dialect".

- [ ] **Step 3: Add `Dialect` enum**

```kotlin
// psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Dialect.kt
package world.phantasmal.psolib.fileFormats.quest

enum class Dialect {
    /** DC NTE / DC v1 / DC v2 / PC NTE / PC v2 / GC NTE: arguments encoded inline after the opcode. */
    V0_V2,
    /** GC v3 / Xbox / BB: arguments pushed onto an arg stack by `arg_push*` opcodes before the consumer opcode. */
    V3_V4,
}
```

- [ ] **Step 4: Expand `Version` enum**

Replace the entire body of `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Version.kt`:

```kotlin
package world.phantasmal.psolib.fileFormats.quest

enum class Version {
    DC_NTE,
    DC_V1,
    DC_V2,
    PC_NTE,
    PC_V2,
    GC_NTE,
    GC_V3,
    BB_V4,
    ;

    val dialect: Dialect
        get() = when (this) {
            DC_NTE, DC_V1, DC_V2, PC_NTE, PC_V2, GC_NTE -> Dialect.V0_V2
            GC_V3, BB_V4 -> Dialect.V3_V4
        }

    val bit: Int
        get() = 1 shl ordinal
}
```

- [ ] **Step 5: Update `PSOVersion.fromVersion`**

Replace the `when` block in `psolib/src/commonMain/kotlin/world/phantasmal/psolib/compatibility/PSOVersion.kt` (the `companion object` `fromVersion` method):

```kotlin
fun fromVersion(version: Version): PSOVersion = when (version) {
    Version.DC_NTE, Version.DC_V1 -> DC_V1
    Version.DC_V2 -> DC_V2
    Version.PC_NTE, Version.PC_V2 -> PC
    Version.GC_NTE, Version.GC_V3 -> GC_EP12
    Version.BB_V4 -> BLUE_BURST
}
```

Also update the doc comment to mention NTE collapsing.

- [ ] **Step 6: Update all `Version.DC/GC/PC/BB` references**

Run `grep -rn "Version\\.\\(DC\\|GC\\|PC\\|BB\\)\\b" psolib/src/` to enumerate (~41 sites).

Mechanical replacement rules (preserve semantics — `BB`/`GC` were always the "latest" variant):
- `Version.DC` → `Version.DC_V2`
- `Version.GC` → `Version.GC_V3`
- `Version.PC` → `Version.PC_V2`
- `Version.BB` → `Version.BB_V4`

Apply to every match. The list includes `Qst.kt`, `QuestTests.kt`, and `Quest.kt`.

**Special case** — `Quest.kt::writeQuestToBinDat` line 427 has a `when (version)` block that maps Version to BinFormat. After the rename the `when` is no longer exhaustive over the 8-value enum (Kotlin will error: "when expression must be exhaustive"). Replace the entire block with:

```kotlin
val binFormat = when (version) {
    Version.DC_NTE, Version.DC_V1, Version.DC_V2,
    Version.GC_NTE, Version.GC_V3 -> BinFormat.DC_GC
    Version.PC_NTE, Version.PC_V2 -> BinFormat.PC
    Version.BB_V4 -> BinFormat.BB
}
```

**Special case** — `Quest.kt::writeQuestToQst` line 465 has `take(if (version == Version.BB) 23 else 31)`. Replace `Version.BB` with `Version.BB_V4`.

- [ ] **Step 7: Run test to verify pass**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.VersionTest"`
Expected: PASS.

Run: `./gradlew psolib:check`
Expected: all existing tests still pass (no behavior change; `Version.BB` → `Version.BB_V4` etc. is a pure rename).

- [ ] **Step 8: Commit**

```bash
git add psolib/src
git commit -m "feat(psolib): expand Version enum to 8 sub-versions + add Dialect"
```

---

### Task 2: Add `ArgsMode` enum + extend `Opcode` class

**Files:**
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/Opcode.kt`
- Modify: `psolib/build.gradle.kts` (codegen call to `Opcode` constructor — temporary default values)
- Test: `psolib/src/commonTest/kotlin/world/phantasmal/psolib/asm/OpcodeTests.kt` (extend existing)

- [ ] **Step 1: Write the failing test**

Append to `psolib/src/commonTest/kotlin/world/phantasmal/psolib/asm/OpcodeTests.kt`:

```kotlin
@Test
fun opcode_carries_version_mask_and_args_mode() {
    val opcode = codeToOpcode(0x00) // nop
    // Default: nop is in all versions, has no args.
    assertEquals(ArgsMode.None, opcode.argsMode)
    // Mask should have at least BB_V4's bit set.
    assertEquals(Version.BB_V4.bit, opcode.versionMask and Version.BB_V4.bit)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.asm.OpcodeTests.opcode_carries_version_mask_and_args_mode"`
Expected: FAIL with "unresolved reference: argsMode" / "unresolved reference: ArgsMode".

- [ ] **Step 3: Add `ArgsMode` and extend `Opcode`**

In `psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/Opcode.kt`, add **above** the `Opcode` class:

```kotlin
enum class ArgsMode {
    /** Arguments pushed onto the arg stack by `arg_push*` opcodes; consumed by this opcode. (V3_V4 only.) */
    Stack,
    /** Arguments encoded inline immediately after the opcode byte. */
    Inline,
    /** Opcode takes no arguments. */
    None,
}
```

In the `Opcode` class constructor, add two parameters (preserve constructor signature order — `versionMask` and `argsMode` go at the end, both with no defaults; codegen will supply them):

```kotlin
class Opcode internal constructor(
    val code: Int,
    val mnemonic: String,
    val doc: String?,
    val params: List<Param>,
    val stack: StackInteraction?,
    val varargs: Boolean,
    val known: Boolean,
    val versionMask: Int,
    val argsMode: ArgsMode,
) {
    // existing body unchanged
}
```

- [ ] **Step 4: Update codegen to emit the two new fields with conservative defaults**

In `psolib/build.gradle.kts`, find `opcodeToCode(writer: PrintWriter, opcode: Map<String, Any>)`. Locate the line `writer.println("""...""")` that emits the `Opcode(...)` constructor call (around line 128). Add `0xFF.inv() shl 0 or 0xFF, ArgsMode.None` — but cleanly: change the emitted constructor call so the last two args are:

```kotlin
        |versionMask = 0xFF, // V0_V4 placeholder; real mask in Task 4
        |argsMode = ArgsMode.None, // placeholder; real value in Task 4
```

Pick the literal `0xFF` for now — a non-zero placeholder so existing code paths still find opcodes in any version. Real bitmask computation lands in T4.

Also add `import world.phantasmal.psolib.asm.ArgsMode` to the generated file's `package` block (the codegen emits `package world.phantasmal.psolib.asm` already; `ArgsMode` lives in the same package so no import needed).

- [ ] **Step 5: Regenerate and run test**

Run: `./gradlew psolib:generateOpcodes`
Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.asm.OpcodeTests"`
Expected: PASS.

Run: `./gradlew psolib:check`
Expected: all existing tests pass.

- [ ] **Step 6: Commit**

```bash
git add psolib/src psolib/build.gradle.kts
git commit -m "feat(psolib): add ArgsMode enum + Opcode.versionMask/argsMode (codegen placeholder values)"
```

---

## Phase 2 — Opcode codegen + lookup

### Task 3: Extend `opcodes.yml` schema

**Files:**
- Modify: `psolib/srcGeneration/asm/opcodes.schema.json`
- Test: schema validation passes on existing YAML (manual check; no formal schema runner in this repo, so verify by reading)

- [ ] **Step 1: Inspect current schema**

Run: `cat psolib/srcGeneration/asm/opcodes.schema.json`

Identify the per-opcode object schema. It currently allows `code, mnemonic, doc, params, stack`.

- [ ] **Step 2: Add `versions` and `args` fields**

Edit `psolib/srcGeneration/asm/opcodes.schema.json`. To the per-opcode object's `properties`, add:

```json
"versions": {
  "type": "string",
  "enum": ["V0_V2", "V3_V4", "V0_V4", "V1_V2", "V1_V4", "V2", "V2_V3", "V2_V4", "V3", "V4"],
  "default": "V0_V4",
  "description": "Which PSO version range this opcode definition applies to. Shortcuts mirror newserv F_* constants. Default V0_V4 (all versions)."
},
"args": {
  "type": "string",
  "enum": ["stack", "inline", "none"],
  "description": "Where arguments come from on V3_V4. If omitted, inferred: stack if `stack: pop` and params non-empty; inline if params non-empty and stack absent; none if params empty."
}
```

Do NOT add either to `required`.

- [ ] **Step 3: Verify schema doesn't reject existing YAML**

No formal validator runs in this repo. Manually verify by running `./gradlew psolib:generateOpcodes` — the build script reads YAML directly, so any YAML field it doesn't know about is silently ignored (which is what we want this task; T4 will start using them).

Run: `./gradlew psolib:generateOpcodes`
Expected: SUCCESS (no behavior change yet).

- [ ] **Step 4: Commit**

```bash
git add psolib/srcGeneration/asm/opcodes.schema.json
git commit -m "feat(psolib): extend opcodes.yml schema with versions + args fields"
```

---

### Task 4: Codegen emits real `versionMask` + `argsMode` + `opcodesFor()`

**Files:**
- Modify: `psolib/build.gradle.kts` (the `generateOpcodes` task)
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/Opcode.kt` (replace `MNEMONIC_TO_OPCODES` and `codeToOpcode`)
- Test: `psolib/src/commonTest/kotlin/world/phantasmal/psolib/asm/OpcodeTableCodegenTest.kt` (new)

- [ ] **Step 1: Write the failing test**

```kotlin
// psolib/src/commonTest/kotlin/world/phantasmal/psolib/asm/OpcodeTableCodegenTest.kt
package world.phantasmal.psolib.asm

import world.phantasmal.psolib.fileFormats.quest.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpcodeTableCodegenTest {
    @Test
    fun opcodes_for_each_version_is_nonempty() {
        for (v in Version.entries) {
            val tables = opcodesFor(v)
            val count = tables.byCode.count { it != null } +
                    tables.byCodeF8.count { it != null } +
                    tables.byCodeF9.count { it != null }
            assertTrue(count > 0, "opcodesFor($v) is empty")
        }
    }

    @Test
    fun bb_v4_has_set_floor_handler() {
        val tables = opcodesFor(Version.BB_V4)
        val op = tables.byCode[0x95]
        assertNotNull(op)
        assertEquals("set_floor_handler", op.mnemonic)
    }

    @Test
    fun version_mask_is_subset_of_all_versions() {
        val allBits = Version.entries.fold(0) { acc, v -> acc or v.bit }
        for (op in ALL_OPCODES) {
            assertEquals(op.versionMask, op.versionMask and allBits,
                "${op.mnemonic} has bits outside the Version enum")
            assertTrue(op.versionMask != 0, "${op.mnemonic} has empty versionMask")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.asm.OpcodeTableCodegenTest"`
Expected: FAIL with "unresolved reference: opcodesFor" / "unresolved reference: ALL_OPCODES".

- [ ] **Step 3: Rewrite codegen to emit `ALL_OPCODES` + `opcodesFor()`**

Replace the `doLast { ... }` block in `psolib/build.gradle.kts` (around lines 56–74) with:

```kotlin
    doLast {
        val root = Load(LoadSettings.builder().build())
            .loadFromInputStream(opcodesFile.inputStream()) as Map<String, Any>

        outputFile.printWriter()
            .use { writer ->
                writer.println("@file:Suppress(\"unused\", \"BooleanLiteralArgument\")")
                writer.println()
                writer.println("package $packageName")
                writer.println()
                writer.println("import world.phantasmal.psolib.fileFormats.quest.Version")
                writer.println()
                writer.println("class OpcodeTables(")
                writer.println("    val byCode: Array<Opcode?>,")
                writer.println("    val byCodeF8: Array<Opcode?>,")
                writer.println("    val byCodeF9: Array<Opcode?>,")
                writer.println(")")
                writer.println()
                @Suppress("UNCHECKED_CAST")
                val opcodes = root["opcodes"] as List<Map<String, Any>>
                writer.println("val ALL_OPCODES: List<Opcode> = listOf(")
                opcodes.forEachIndexed { idx, opcode ->
                    writeOpcodeListEntry(writer, opcode, idx == opcodes.lastIndex)
                }
                writer.println(")")
                writer.println()
                writer.println("private val OPCODES_FOR: MutableMap<Version, OpcodeTables> = mutableMapOf()")
                writer.println()
                writer.println("fun opcodesFor(version: Version): OpcodeTables = OPCODES_FOR.getOrPut(version) {")
                writer.println("    val byCode = arrayOfNulls<Opcode>(256)")
                writer.println("    val byCodeF8 = arrayOfNulls<Opcode>(256)")
                writer.println("    val byCodeF9 = arrayOfNulls<Opcode>(256)")
                writer.println("    val bit = version.bit")
                writer.println("    for (op in ALL_OPCODES) {")
                writer.println("        if (op.versionMask and bit == 0) continue")
                writer.println("        val idx = op.code and 0xFF")
                writer.println("        val target = when {")
                writer.println("            op.code <= 0xFF -> byCode")
                writer.println("            op.code <= 0xF8FF -> byCodeF8")
                writer.println("            else -> byCodeF9")
                writer.println("        }")
                writer.println("        check(target[idx] == null) {")
                writer.println("            \"duplicate (version=\$version, code=0x\${op.code.toString(16)}) for \${op.mnemonic}\"")
                writer.println("        }")
                writer.println("        target[idx] = op")
                writer.println("    }")
                writer.println("    OpcodeTables(byCode, byCodeF8, byCodeF9)")
                writer.println("}")
                writer.println()
                opcodes.forEach { opcode -> writeOpcodeConstant(writer, opcode) }
            }
    }
```

Also add two helper functions to `build.gradle.kts` (alongside the existing `opcodeToCode`):

```kotlin
// Mirror of newserv's F_* version shortcuts.
val VERSION_SHORTCUTS = mapOf(
    "V0_V2" to setOf("DC_NTE", "DC_V1", "DC_V2", "PC_NTE", "PC_V2", "GC_NTE"),
    "V3_V4" to setOf("GC_V3", "BB_V4"),
    "V0_V4" to setOf("DC_NTE", "DC_V1", "DC_V2", "PC_NTE", "PC_V2", "GC_NTE", "GC_V3", "BB_V4"),
    "V1_V2" to setOf("DC_V1", "DC_V2", "PC_NTE", "PC_V2", "GC_NTE"),
    "V1_V4" to setOf("DC_V1", "DC_V2", "PC_NTE", "PC_V2", "GC_NTE", "GC_V3", "BB_V4"),
    "V2"    to setOf("DC_V2", "PC_NTE", "PC_V2", "GC_NTE"),
    "V2_V3" to setOf("DC_V2", "PC_NTE", "PC_V2", "GC_NTE", "GC_V3"),
    "V2_V4" to setOf("DC_V2", "PC_NTE", "PC_V2", "GC_NTE", "GC_V3", "BB_V4"),
    "V3"    to setOf("GC_V3"),
    "V4"    to setOf("BB_V4"),
)
val VERSION_ORDINALS = listOf(
    "DC_NTE", "DC_V1", "DC_V2", "PC_NTE", "PC_V2", "GC_NTE", "GC_V3", "BB_V4"
)

fun versionMaskFor(shortcut: String): Int {
    val members = VERSION_SHORTCUTS[shortcut]
        ?: error("Unknown versions shortcut: $shortcut")
    return members.fold(0) { acc, name ->
        acc or (1 shl VERSION_ORDINALS.indexOf(name))
    }
}

fun argsModeFor(opcode: Map<String, Any>): String {
    val explicit = opcode["args"] as String?
    if (explicit != null) {
        return when (explicit) {
            "stack" -> "ArgsMode.Stack"
            "inline" -> "ArgsMode.Inline"
            "none" -> "ArgsMode.None"
            else -> error("Unknown args value: $explicit")
        }
    }
    @Suppress("UNCHECKED_CAST")
    val params = opcode["params"] as List<Map<String, Any>>
    val stack = opcode["stack"] as String?
    return when {
        params.isEmpty() -> "ArgsMode.None"
        stack == "pop" -> "ArgsMode.Stack"
        else -> "ArgsMode.Inline"
    }
}
```

Add a new emission helper:

```kotlin
fun writeOpcodeListEntry(writer: PrintWriter, opcode: Map<String, Any>, isLast: Boolean) {
    val code = (opcode["code"] as String).drop(2).toInt(16)
    val codeStr = code.toString(16).uppercase().padStart(2, '0')
    val mnemonic = opcode["mnemonic"] as String? ?: "unknown_${codeStr.lowercase()}"
    val valName = opcodeConstName(mnemonic)
    val suffix = if (isLast) "" else ","
    writer.println("    $valName$suffix")
}

fun opcodeConstName(mnemonic: String): String =
    "OP_" + mnemonic
        .replace("!=", "ne")
        .replace("=", "e")
        .replace("<", "l")
        .replace(">", "g")
        .uppercase()

fun writeOpcodeConstant(writer: PrintWriter, opcode: Map<String, Any>) {
    val code = (opcode["code"] as String).drop(2).toInt(16)
    val codeStr = code.toString(16).uppercase().padStart(2, '0')
    val mnemonic = opcode["mnemonic"] as String? ?: "unknown_${codeStr.lowercase()}"
    val doc = (opcode["doc"] as String?)?.let { "\"${escapeKotlinString(it)}\"" }
    val stack = opcode["stack"] as String?
    val stackInteraction = when (stack) {
        "push" -> "StackInteraction.Push"
        "pop" -> "StackInteraction.Pop"
        else -> "null"
    }
    @Suppress("UNCHECKED_CAST")
    val params = opcode["params"] as List<Map<String, Any>>
    val paramsStr = paramsToCode(params, 4)
    val lastParam = params.lastOrNull()
    val varargs = lastParam != null && when (lastParam["type"]) {
        null -> error("No type for last parameter of $mnemonic opcode.")
        "ilabel_var", "reg_var" -> true
        else -> false
    }
    val known = "mnemonic" in opcode
    val versionShortcut = (opcode["versions"] as String?) ?: "V0_V4"
    val versionMask = versionMaskFor(versionShortcut)
    val argsMode = argsModeFor(opcode)
    val valName = opcodeConstName(mnemonic)
    writer.println(
        """
        |val $valName = Opcode(
        |    code = 0x$codeStr,
        |    mnemonic = "$mnemonic",
        |    doc = $doc,
        |    params = $paramsStr,
        |    stack = $stackInteraction,
        |    varargs = $varargs,
        |    known = $known,
        |    versionMask = 0x${versionMask.toString(16).uppercase()},
        |    argsMode = $argsMode,
        |)
        """.trimMargin()
    )
}
```

Delete the old `opcodeToCode` function (replaced by `writeOpcodeConstant`).

Note that the same `code` key may appear in multiple YAML records (dual-form opcodes); each becomes its own `Opcode` constant. To make constant names unique for those cases, change `opcodeConstName` to append a `_V0_V2` / `_V3_V4` suffix when `versions` is explicitly set to one of those:

```kotlin
fun opcodeConstName(mnemonic: String, versionShortcut: String? = null): String {
    val suffix = when (versionShortcut) {
        "V0_V2" -> "_V0_V2"
        "V3_V4" -> "_V3_V4"
        else -> ""
    }
    return "OP_" + mnemonic
        .replace("!=", "ne")
        .replace("=", "e")
        .replace("<", "l")
        .replace(">", "g")
        .uppercase() + suffix
}
```

Update both `writeOpcodeListEntry` and `writeOpcodeConstant` to pass `versionShortcut`.

- [ ] **Step 4: Replace `MNEMONIC_TO_OPCODES` and `codeToOpcode` in `Opcode.kt`**

In `psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/Opcode.kt`, replace the top of the file (the `MNEMONIC_TO_OPCODES` definition, lines 1–14):

```kotlin
package world.phantasmal.psolib.asm

import world.phantasmal.core.unsafe.UnsafeMap
import world.phantasmal.psolib.fileFormats.quest.Version

private val MNEMONIC_TO_OPCODES_BY_VERSION: UnsafeMap<Version, UnsafeMap<String, Opcode>> by lazy {
    val outer = UnsafeMap<Version, UnsafeMap<String, Opcode>>()
    for (v in Version.entries) {
        val inner = UnsafeMap<String, Opcode>()
        val tables = opcodesFor(v)
        tables.byCode.forEach { if (it != null) inner.set(it.mnemonic, it) }
        tables.byCodeF8.forEach { if (it != null) inner.set(it.mnemonic, it) }
        tables.byCodeF9.forEach { if (it != null) inner.set(it.mnemonic, it) }
        outer.set(v, inner)
    }
    outer
}

private val UNKNOWN_OPCODE_MNEMONIC_REGEX = Regex("""^unknown_((f8|f9)?[0-9a-f]{2})$""")
```

Replace `codeToOpcode` and `mnemonicToOpcode` (lines 177–196) with:

```kotlin
fun codeToOpcode(code: Int, version: Version = Version.BB_V4): Opcode {
    val tables = opcodesFor(version)
    return when {
        code <= 0xFF -> getOpcode(code, code, tables.byCode)
        code <= 0xF8FF -> getOpcode(code, code and 0xFF, tables.byCodeF8)
        else -> getOpcode(code, code and 0xFF, tables.byCodeF9)
    }
}

fun mnemonicToOpcode(mnemonic: String, version: Version = Version.BB_V4): Opcode? {
    val map = MNEMONIC_TO_OPCODES_BY_VERSION.get(version) ?: return null
    var opcode = map.get(mnemonic)
    if (opcode == null) {
        UNKNOWN_OPCODE_MNEMONIC_REGEX.matchEntire(mnemonic)?.destructured?.let { (codeStr) ->
            val code = codeStr.toInt(16)
            opcode = codeToOpcode(code, version)
            map.set(mnemonic, opcode!!)
        }
    }
    return opcode
}
```

The default `Version.BB_V4` keeps every existing call site working without modification — they get the same opcode table as before.

- [ ] **Step 5: Regenerate and run tests**

Run: `./gradlew psolib:generateOpcodes`
Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.asm.OpcodeTableCodegenTest"`
Expected: PASS.

Run: `./gradlew psolib:check`
Expected: all existing tests pass.

- [ ] **Step 6: Commit**

```bash
git add psolib/build.gradle.kts psolib/src
git commit -m "feat(psolib): generate per-version opcode tables via opcodesFor()"
```

---

### Task 5: Thread `version` through `codeToOpcode` / `mnemonicToOpcode` callers

**Files:**
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Bytecode.kt:518`
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/Assembly.kt:355`
- Modify: `psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/SymbolChatAnalysisTest.kt`
- Modify: `psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/InspectSymbolChatQst.kt`
- Modify: `psolib/src/commonTest/kotlin/world/phantasmal/psolib/compatibility/CompatibilityCheckerTests.kt:424` (no change required — defaults to BB_V4)

- [ ] **Step 1: Inspect call sites**

Run: `grep -rn "codeToOpcode\\|mnemonicToOpcode" psolib/src --include="*.kt"`

For each non-Opcode.kt call site, verify whether passing `Version.BB_V4` (the new default) preserves prior behavior. Since the old single table is equivalent to `opcodesFor(BB_V4)` at this point (all opcodes have `versionMask` including BB_V4), the default keeps tests green.

- [ ] **Step 2: Run full test suite to verify no regression**

Run: `./gradlew psolib:check`
Expected: all tests pass.

- [ ] **Step 3: Commit**

If no edits were needed (defaults sufficient), no commit. Otherwise:

```bash
git add psolib/src
git commit -m "refactor(psolib): make explicit Version on opcode lookups"
```

---

## Phase 3 — Parser version threading

### Task 6: Thread `version` through `parseBytecode` + `parseInstructionArguments`

**Files:**
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Bytecode.kt`
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Quest.kt:115` (caller of `parseBytecode`)
- Test: `psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/QuestVersionAutoDetectTest.kt` (stub, expanded in T12)

- [ ] **Step 1: Write the failing test**

Create the stub test file (full version in T12):

```kotlin
// psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/QuestVersionAutoDetectTest.kt
package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.psolib.asm.BytecodeStringEncoding
import world.phantasmal.psolib.buffer.Buffer
import world.phantasmal.psolib.cursor.cursor
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.core.Success
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestVersionAutoDetectTest : LibTestSuite {
    @Test
    fun parseBytecode_with_v0_v2_version_parses_inline_args() = testAsync {
        // A 5-byte synthetic bytecode: opcode 0x50 (`message`, args F_V0_V4 | F_ARGS), 1 byte I32 prefix, no string.
        // On V3_V4 this would expect args on the stack (skipped here);
        // on V0_V2 it should attempt inline read.
        // We just assert that calling parseBytecode with V0_V2 doesn't throw with the same input
        // that V3_V4 trivially accepts (empty case).
        val emptyBytecode = Buffer.fromByteArray(byteArrayOf())
        val labels = intArrayOf(-1)
        val r = parseBytecode(
            emptyBytecode, labels, entryLabels = emptySet(),
            stringEncoding = BytecodeStringEncoding.ASCII,
            lenient = false,
            version = Version.GC_NTE,
        )
        assertTrue(r is Success)
        assertEquals(0, r.value.segments.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.QuestVersionAutoDetectTest"`
Expected: FAIL with "no value passed for parameter 'version'" or similar.

- [ ] **Step 3: Add `version` parameter to `parseBytecode`**

In `Bytecode.kt`, change the signature of `parseBytecode` (line 70):

```kotlin
fun parseBytecode(
    bytecode: Buffer,
    labelOffsets: IntArray,
    entryLabels: Set<Int>,
    stringEncoding: BytecodeStringEncoding,
    lenient: Boolean,
    version: Version = Version.BB_V4,
): PwResult<BytecodeIr> {
```

Thread `version` through:
- `findAndParseSegments(...)` — add `version: Version` parameter, pass to inner parsers.
- Each `parseInstructionsSegment` invocation — add `version: Version` parameter.
- `codeToOpcode(fullOpcode)` at `Bytecode.kt:518` becomes `codeToOpcode(fullOpcode, version)`.

- [ ] **Step 4: Update `parseInstructionArguments` branch condition**

Find `parseInstructionArguments` (line 609). Change signature:

```kotlin
private fun parseInstructionArguments(
    cursor: Cursor,
    opcode: Opcode,
    version: Version,
    stringEncoding: BytecodeStringEncoding,
): List<Arg> {
```

Change the entry branch from `if (opcode.stack != StackInteraction.Pop) {` to:

```kotlin
    val readInline = !(version.dialect == Dialect.V3_V4 && opcode.argsMode == ArgsMode.Stack)
    if (readInline) {
```

Add `import world.phantasmal.psolib.asm.ArgsMode` at the top of the file. (`Dialect` is already in the same package.)

Update every caller of `parseInstructionArguments` to pass `version`.

- [ ] **Step 5: Update `parseBinDatToQuest` to pass version**

In `Quest.kt:115`, the call site `parseBytecode(bin.bytecode, bin.labelOffsets, ...)` — for now, add a `version: Version = Version.BB_V4` parameter to `parseBinDatToQuest` and pass it through. Auto-detect logic comes in T9.

```kotlin
fun parseBinDatToQuest(
    binCursor: Cursor,
    datCursor: Cursor,
    lenient: Boolean = false,
    compressed: Boolean = true,
    shiftJis: Boolean = false,
    version: Version = Version.BB_V4,
): PwResult<Quest> {
    // ... existing body, but pass version to parseBytecode:
    val parseBytecodeResult = parseBytecode(
        bin.bytecode,
        bin.labelOffsets,
        extractScriptEntryPoints(objects, npcs),
        bin.format.stringEncoding,
        lenient,
        version,
    )
```

- [ ] **Step 6: Run tests**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.QuestVersionAutoDetectTest"`
Expected: PASS.

Run: `./gradlew psolib:check`
Expected: all existing tests pass (default `BB_V4` keeps prior behavior).

- [ ] **Step 7: Commit**

```bash
git add psolib/src
git commit -m "feat(psolib): thread version through parseBytecode + parseInstructionArguments"
```

---

### Task 7: Thread `version` through `BytecodeIr.Instruction.getSize`

**Files:**
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/BytecodeIr.kt`
- Modify: every call site of `getSize(stringEncoding)`

- [ ] **Step 1: Inspect call sites**

Run: `grep -rn "\\.getSize(" psolib/src --include="*.kt" | head -20`

Note all caller files.

- [ ] **Step 2: Update `Instruction.getSize` signature**

In `BytecodeIr.kt`, change the signature (line 179):

```kotlin
fun getSize(stringEncoding: BytecodeStringEncoding, version: Version): Int {
    var size = opcode.size

    if (version.dialect == Dialect.V3_V4 && opcode.argsMode == ArgsMode.Stack) {
        size += pushInstructionsSize(stringEncoding)
        return size
    }
    // ... rest of existing body unchanged
}
```

Add imports: `import world.phantasmal.psolib.fileFormats.quest.Dialect`, `import world.phantasmal.psolib.fileFormats.quest.Version`.

Delete the misleading comment line ("All known PSO versions use push instructions for Pop opcodes in binary format.").

- [ ] **Step 3: Update all `.getSize(...)` callers**

Each caller now needs to pass `version`. The major callers are inside `writeBytecode` (`Bytecode.kt:872`) and `Assembly.kt`'s assembler emission loop.

For T7, add a `version: Version = Version.BB_V4` parameter to `writeBytecode`. Update the single caller (`Quest.kt:writeQuestToBinDat`) to pass the function-scope `version` argument.

Other callers (Assembly.kt) likewise gain a `version: Version = Version.BB_V4` default. T14 will tighten the defaults.

- [ ] **Step 4: Run tests**

Run: `./gradlew psolib:check`
Expected: all existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add psolib/src
git commit -m "feat(psolib): thread version through BytecodeIr.getSize"
```

---

## Phase 4 — Quest API + auto-detect

### Task 8: Add `Quest.version` field

**Files:**
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Quest.kt`
- Test: `psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/QuestTests.kt` (extend)

- [ ] **Step 1: Write the failing test**

Append to `QuestTests.kt`:

```kotlin
@Test
fun quest_carries_version() = testAsync {
    val result = parseBinDatToQuest(
        readFile("/towards_the_future.bin"),
        readFile("/towards_the_future.dat"),
    )
    assertTrue(result is Success)
    // towards_the_future is BB; default detection should land on BB_V4.
    assertEquals(Version.BB_V4, result.value.version)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.QuestTests.quest_carries_version"`
Expected: FAIL with "unresolved reference: version" (on `result.value.version`).

- [ ] **Step 3: Add `Quest.version` field**

In `Quest.kt`, add to the `Quest` class constructor parameter list (right after `binFormat`):

```kotlin
class Quest(
    // ... existing fields ...
    var binFormat: BinFormat = BinFormat.BB,
    var version: Version = Version.BB_V4,
    val particleSpawns: List<ParticleSpawn> = emptyList(),
)
```

In `parseBinDatToQuest`, populate `quest.version = version`:

```kotlin
return result.success(Quest(
    // ... existing fields ...,
    binFormat = bin.format,
    version = version,
    // ...
))
```

(Locate the `Quest(...)` construction near the end of `parseBinDatToQuest`. If the function returns through a different path, update accordingly.)

- [ ] **Step 4: Run test**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.QuestTests"`
Expected: PASS.

Run: `./gradlew psolib:check`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add psolib/src
git commit -m "feat(psolib): add Quest.version field"
```

---

### Task 9: Auto-detect with candidate ranking

**Files:**
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Quest.kt`
- Test: `psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/QuestVersionAutoDetectTest.kt` (extend)

- [ ] **Step 1: Inspect `parseBinDatToQuestAutoDetect`**

Run: `grep -n "fun parseBinDatToQuestAutoDetect" psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Quest.kt`

Read the function. It currently calls `parseBinDatToQuest` once.

- [ ] **Step 2: Write the failing test**

Append to `QuestVersionAutoDetectTest.kt`:

```kotlin
@Test
fun auto_detect_picks_bb_v4_for_towards_the_future() = testAsync {
    val r = parseBinDatToQuestAutoDetect(
        readFile("/towards_the_future.bin"),
        readFile("/towards_the_future.dat"),
        lenient = false,
        shiftJis = false,
    )
    assertTrue(r is Success)
    assertEquals(Version.BB_V4, r.value.quest.version)
}
```

Add `import world.phantasmal.psolib.test.readFile` at top.

- [ ] **Step 3: Run test (will pass already if default works, fail otherwise)**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.QuestVersionAutoDetectTest"`

If PASS (T8's default sufficient), skip Step 4 and proceed to Step 5. If FAIL, proceed.

- [ ] **Step 4: Implement candidate-ranking auto-detect**

In `Quest.kt`, add:

```kotlin
private fun versionsFor(format: BinFormat): List<Version> = when (format) {
    BinFormat.DC_GC -> listOf(Version.GC_V3, Version.DC_V2, Version.GC_NTE, Version.DC_V1, Version.DC_NTE)
    BinFormat.PC -> listOf(Version.PC_V2, Version.PC_NTE)
    BinFormat.BB -> listOf(Version.BB_V4)
}
```

Order is the default-rank tie-breaker (most likely first).

Modify `parseBinDatToQuestAutoDetect`. Replace the body so that, when `version` is null (new param), it tries each candidate strict, ranks by `(threw, invalidCount, unknownCount, totalNops, indexInCandidateList)`, and uses the winner. When `version` is non-null, it goes straight through.

```kotlin
fun parseBinDatToQuestAutoDetect(
    binCursor: Cursor,
    datCursor: Cursor,
    lenient: Boolean = false,
    shiftJis: Boolean = false,
    version: Version? = null,
): PwResult<QuestParseResult> {
    val result = PwResult.build<QuestParseResult>(logger)

    // Step 1: decompress + parse bin header to get bin.format.
    val (binData, datData, compressedFlag) = decompressIfNeeded(binCursor, datCursor, result)
        ?: return result.failure()

    val bin = parseBin(binData, shiftJis)
    val candidates = if (version != null) listOf(version) else versionsFor(bin.format)

    // Step 2: try each candidate.
    data class CandidateResult(
        val version: Version,
        val threw: Boolean,
        val invalidCount: Int,
        val unknownCount: Int,
        val totalNops: Int,
        val rankIndex: Int,
        val parseResult: PwResult<Quest>?,
    )

    val outcomes = candidates.mapIndexed { idx, v ->
        try {
            val r = parseBinDatToQuestInternal(
                binData = binData,
                datData = datData,
                lenient = false,
                shiftJis = shiftJis,
                version = v,
                compressedFlag = compressedFlag,
            )
            val ir = (r as? Success)?.value?.bytecodeIr
            val invalid = ir?.instructionSegments()?.sumOf { seg ->
                seg.instructions.count { !it.valid }
            } ?: Int.MAX_VALUE
            val unknown = ir?.instructionSegments()?.sumOf { seg ->
                seg.instructions.count { it.opcode.mnemonic.startsWith("unknown_") }
            } ?: 0
            val nops = ir?.instructionSegments()?.sumOf { seg ->
                seg.instructions.count { it.opcode.mnemonic == "nop" }
            } ?: 0
            CandidateResult(v, threw = false, invalid, unknown, nops, idx, r)
        } catch (e: Throwable) {
            logger.trace(e) { "candidate $v threw" }
            CandidateResult(v, threw = true, Int.MAX_VALUE, 0, 0, idx, null)
        }
    }

    val winner = outcomes.minByOrNull {
        Quad(it.threw, it.invalidCount, it.unknownCount, it.totalNops to it.rankIndex)
    }!!

    if (winner.threw || winner.parseResult !is Success) {
        // All candidates threw — fall back to lenient on bin.format default.
        result.addProblem(
            Severity.Warning,
            "No version candidate strict-parsed; falling back to lenient ${candidates.first()}.",
        )
        val fallback = parseBinDatToQuestInternal(
            binData = binData,
            datData = datData,
            lenient = true,
            shiftJis = shiftJis,
            version = candidates.first(),
            compressedFlag = compressedFlag,
        )
        if (fallback !is Success) return result.failure()
        return result.success(QuestParseResult(fallback.value, compressedFlag))
    }

    return result.success(QuestParseResult(winner.parseResult.value, compressedFlag))
}
```

`Quad` is a tuple comparator helper — replace with idiomatic Kotlin (`Comparator`):

```kotlin
// Replace minByOrNull { Quad(...) } with:
val winner = outcomes.minWithOrNull(
    compareBy({ it.threw }, { it.invalidCount }, { it.unknownCount }, { it.totalNops }, { it.rankIndex })
)!!
```

Also factor out `parseBinDatToQuestInternal` — most of `parseBinDatToQuest`'s body moves into it. The public `parseBinDatToQuest` becomes a thin wrapper calling `parseBinDatToQuestInternal` once. Auto-detect calls it N times.

Define `QuestParseResult` if it doesn't already exist (look up the current return type of `parseBinDatToQuestAutoDetect` — adjust to match).

- [ ] **Step 5: Run tests**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.QuestVersionAutoDetectTest"`
Expected: PASS.

Run: `./gradlew psolib:check`
Expected: all existing tests pass (BB and V3 quests still detect correctly; default-rank lists `BB_V4` first for BB, `GC_V3` first for DC_GC).

- [ ] **Step 6: Commit**

```bash
git add psolib/src
git commit -m "feat(psolib): version auto-detect with candidate ranking"
```

---

## Phase 5 — First proof point: GC_NTE quest58

### Task 10: NTE quest58 strict-parses

**Files:**
- Create: `psolib/src/commonTest/resources/quest58_j_nte.dat` (derived from newserv `q058-gcn.dat` which is a symlink to `q058-dc.dat`)
- Modify: `psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/InspectQuest58.kt` (convert `parseBinStrict` from println to assertions)

- [ ] **Step 1: Copy the NTE dat fixture**

Run:
```bash
cp /Users/wangzhen/study/newserv/system/quests/retrieval/q058-dc.dat \
   psolib/src/commonTest/resources/quest58_j_nte.dat
```

Verify the file exists and is non-zero:
```bash
ls -la psolib/src/commonTest/resources/quest58_j_nte.dat
```

- [ ] **Step 2: Rewrite `InspectQuest58.parseBinStrict` as a proper test**

Replace the entire `parseBinStrict` function in `psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/InspectQuest58.kt` with:

```kotlin
@Test
fun parseBinStrict_gc_nte_quest58() = testAsync {
    val binBytes = this::class.java.classLoader
        .getResource("quest58_j_nte.bin")!!.readBytes()
    val datBytes = this::class.java.classLoader
        .getResource("quest58_j_nte.dat")!!.readBytes()

    val r = parseBinDatToQuestAutoDetect(
        Buffer.fromByteArray(binBytes).cursor(),
        Buffer.fromByteArray(datBytes).cursor(),
        lenient = false,
        shiftJis = true,
    )
    assertTrue(r is Success, "auto-detect failed: ${(r as? Failure)?.problems}")
    assertEquals(Version.GC_NTE, r.value.quest.version)
    val nonInfo = r.problems.filter { it.severity != Severity.Info }
    assertTrue(nonInfo.isEmpty(),
        "expected zero non-Info problems; got: ${nonInfo.joinToString { it.message }}")
    val invalid = r.value.quest.bytecodeIr.instructionSegments()
        .sumOf { seg -> seg.instructions.count { !it.valid } }
    assertEquals(0, invalid, "expected zero invalid instructions")
}
```

Replace existing imports as needed (e.g., add `import kotlin.test.assertEquals`, `import kotlin.test.assertTrue`).

You may also delete the old `parseBinStrict` test body (the println-style diagnostic). Keep `decompressNewservGcJ` if you want to retain the helper, otherwise delete.

- [ ] **Step 3: Run test**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.InspectQuest58"`
Expected: PASS — because Phase 3 already made V0_V2 dialect parse inline, and `quest58_j_nte.bin` happens to use only the F_ARGS opcodes that work correctly under the default param shapes.

If FAIL (some dual-form opcode is hit in quest58 NTE and has wrong param shape), don't paper over it — that's the signal that the specific quest needs T12's dual entries before passing. In that case, this task pauses here, T12 runs, and we re-run this test as part of T12.

- [ ] **Step 4: Commit**

```bash
git add psolib/src/commonTest/resources/quest58_j_nte.dat \
        psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/InspectQuest58.kt
git commit -m "test(psolib): assert GC_NTE quest58 strict-parses with version=GC_NTE"
```

---

## Phase 6 — V0_V2 family fixtures and dual-form opcode data

### Task 11: Copy V0_V2 fixtures from newserv

**Files (all new):**
- `psolib/src/commonTest/resources/q058-d1-e.bin` and `.dat` (DC_V1)
- `psolib/src/commonTest/resources/q058-dc-e.bin` and `.dat` (DC_V2)
- `psolib/src/commonTest/resources/q058-pc-e.bin` and `.dat` (PC_V2)
- `psolib/src/commonTest/resources/q058-gcn-e.bin` and `q058-gcn-e.dat` (GC_NTE EN — newserv symlinks to dc variants; resolve them)

- [ ] **Step 1: Copy + resolve symlinks**

```bash
SRC=/Users/wangzhen/study/newserv/system/quests/retrieval
DST=psolib/src/commonTest/resources

for f in q058-d1-e.bin q058-d1.dat q058-dc-e.bin q058-dc.dat q058-pc-e.bin q058-pc.dat; do
    cp -L "$SRC/$f" "$DST/$f"
done

# GC_NTE EN: gcn-e.bin -> dc-e.bin, gcn.dat -> dc.dat. Resolve and rename.
cp -L "$SRC/q058-gcn-e.bin" "$DST/q058-gcn-e.bin"
cp -L "$SRC/q058-gcn.dat"   "$DST/q058-gcn.dat"
```

- [ ] **Step 2: Verify file sizes**

Run: `ls -la psolib/src/commonTest/resources/q058-*`
Expected: 6 .bin files + 6 .dat files (since gcn-e symlinked to dc-e but we still want it under its own name; if `cp -L` from a symlink produces an identical-content file under both names, that's fine; we just need them present).

- [ ] **Step 3: Commit (fixtures only, no test wiring yet)**

```bash
git add psolib/src/commonTest/resources/q058-*
git commit -m "test(psolib): add V0_V2 quest58 fixtures (DC_V1, DC_V2, PC_V2, GC_NTE EN) from newserv"
```

---

### Task 12: Add ~61 dual-form opcode entries to `opcodes.yml`

**Files:**
- Modify: `psolib/srcGeneration/asm/opcodes.yml`

**Source data:** newserv `src/QuestScript.cc` lines 250–2625. Every opcode whose `code` appears twice in that range with different `F_V*` flags needs to become two YAML entries in our file.

- [ ] **Step 1: Extract dual-form pairs from newserv**

Run:
```bash
awk '/^    \{0x..../{
        gsub(/[ \t]/, "", $0);
        split($0, a, ",");
        print a[1];
    }' /Users/wangzhen/study/newserv/src/QuestScript.cc \
    | sort | uniq -c | awk '$1 > 1 {print $2}' \
    > /tmp/dual-form-opcodes.txt

wc -l /tmp/dual-form-opcodes.txt
```

Expected: ~61 unique opcode codes.

- [ ] **Step 2: For each dual-form opcode, identify the two newserv definitions**

For each code in `/tmp/dual-form-opcodes.txt`, find both lines:
```bash
while read code; do
    grep -n "^    {${code}," /Users/wangzhen/study/newserv/src/QuestScript.cc
done < /tmp/dual-form-opcodes.txt > /tmp/dual-form-lines.txt
```

Read `/tmp/dual-form-lines.txt` and produce, for each opcode, two YAML entries — one for V0_V2 form, one for V3_V4 form. Use the param shapes from each newserv definition.

The translation rules (newserv arg type → opcodes.yml `type:`):
- `LABEL16`, `LABEL32` → `ilabel` (or `dlabel` / `slabel` based on `data_type`)
- `LABEL16_SET` → `ilabel_var`
- `R_REG`, `R_REG32`, `W_REG`, `W_REG32` → `reg_ref` (single)
- `R_REG_SET` → `reg_var`
- `R_REG_SET_FIXED`, `W_REG_SET_FIXED` → `reg_ref` with a fixed count (see existing usages in `opcodes.yml` for shape)
- `I8` → `byte`
- `I16` → `short`
- `I32` → `int`
- `F32` → `float`
- `CSTRING` → `string`
- `FLOOR`, `CLIENT_ID`, `SCRIPT16`, `SCRIPT32` → `int` or `short`/`ilabel` as context dictates (read newserv comments)

Example, opcode 0x95 (`set_floor_handler`):

Current `opcodes.yml`:
```yaml
- code: 0x95
  mnemonic: set_floor_handler
  doc: "Causes the labelB to be called..."
  params:
    - type: int
      name: "floor"
      doc: Floor number.
    - type: ilabel
      doc: Handler function label.
  stack: pop
```

Replace with:
```yaml
- code: 0x95
  mnemonic: set_floor_handler
  doc: "Causes the labelB to be called on a new thread when the player warps to floorA. (V0_V2 inline form, newserv QuestScript.cc:828)"
  params:
    - type: int
      name: "floor"
      doc: Floor number.
    - type: int
      name: "handler"
      doc: Handler function label index (32-bit on V0_V2).
  versions: V0_V2

- code: 0x95
  mnemonic: set_floor_handler
  doc: "Causes the labelB to be called on a new thread when the player warps to floorA. (V3_V4 form, newserv QuestScript.cc:829)"
  params:
    - type: int
      name: "floor"
      doc: Floor number.
    - type: ilabel
      doc: Handler function label (16-bit on V3_V4, popped from arg stack).
  stack: pop
  versions: V3_V4
```

Repeat for every dual-form opcode in `/tmp/dual-form-opcodes.txt`. The full list with newserv line numbers (extracted from the survey above) — process each in order:

```
0x60  (lines 609-610: npc_crt V0_V2 vs V3_V4)
0x69  (lines 639-640: p_hpstat)
0x6A  (lines 643-644: p_dead)
0x95  (lines 828-829: set_floor_handler)
... [remaining ~57 codes from /tmp/dual-form-opcodes.txt]
```

For each, write two YAML records.

- [ ] **Step 3: Run codegen + tests**

Run: `./gradlew psolib:generateOpcodes`
Expected: SUCCESS. If it fails with `duplicate (version=X, code=0xYY)`, you have two entries that overlap on the same Version bit — adjust `versions:` shortcuts.

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.InspectQuest58"`
Expected: PASS (now also for quest58 NTE that previously failed on dual-form opcodes).

Run: `./gradlew psolib:check`
Expected: all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add psolib/srcGeneration/asm/opcodes.yml
git commit -m "feat(psolib): add V0_V2 dual-form opcode definitions from newserv"
```

---

### Task 13: Auto-detect test for all 5 V0_V2 fixtures

**Files:**
- Modify: `psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/QuestVersionAutoDetectTest.kt`

- [ ] **Step 1: Add parameterized cases**

Append:

```kotlin
@Test
fun auto_detect_dc_v1_quest58() = testAsync {
    val r = parseBinDatToQuestAutoDetect(
        readFile("/q058-d1-e.bin"), readFile("/q058-d1.dat"),
        lenient = false, shiftJis = false,
    )
    assertTrue(r is Success, "$r")
    assertEquals(Version.DC_V1, r.value.quest.version)
    assertNoInvalid(r.value.quest)
}

@Test
fun auto_detect_dc_v2_quest58() = testAsync {
    val r = parseBinDatToQuestAutoDetect(
        readFile("/q058-dc-e.bin"), readFile("/q058-dc.dat"),
        lenient = false, shiftJis = false,
    )
    assertTrue(r is Success, "$r")
    assertEquals(Version.DC_V2, r.value.quest.version)
    assertNoInvalid(r.value.quest)
}

@Test
fun auto_detect_pc_v2_quest58() = testAsync {
    val r = parseBinDatToQuestAutoDetect(
        readFile("/q058-pc-e.bin"), readFile("/q058-pc.dat"),
        lenient = false, shiftJis = false,
    )
    assertTrue(r is Success, "$r")
    assertEquals(Version.PC_V2, r.value.quest.version)
    assertNoInvalid(r.value.quest)
}

@Test
fun auto_detect_gc_nte_quest58_en() = testAsync {
    val r = parseBinDatToQuestAutoDetect(
        readFile("/q058-gcn-e.bin"), readFile("/q058-gcn.dat"),
        lenient = false, shiftJis = false,
    )
    assertTrue(r is Success, "$r")
    assertEquals(Version.GC_NTE, r.value.quest.version)
    assertNoInvalid(r.value.quest)
}

private fun assertNoInvalid(quest: Quest) {
    val invalid = quest.bytecodeIr.instructionSegments()
        .sumOf { seg -> seg.instructions.count { !it.valid } }
    assertEquals(0, invalid, "expected zero invalid instructions in ${quest.version} quest")
}
```

Note: DC_NTE and PC_NTE fixtures are **not** in this iteration (newserv archive lacks them). The dialect handler is exercised via DC_V1 + PC_V2 which share dialect=V0_V2.

- [ ] **Step 2: Run tests**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.QuestVersionAutoDetectTest"`
Expected: all 5 PASS.

If DC_V1 / DC_V2 misclassifies (e.g., gets detected as GC_NTE because both share dialect and DC_GC bin-format), inspect the ranking order in `versionsFor(BinFormat.DC_GC)` from T9. The tie-breaker (rankIndex) should resolve by putting GC_V3 first (most common), then DC_V2, then GC_NTE, then DC_V1, then DC_NTE. Verify and adjust order so each candidate ranks above its companion when both parse identically — currently both `q058-d1-e` (DC_V1) and `q058-dc-e` (DC_V2) are V0_V2 dialect so they both parse strict-OK as either; the **tie-breaker order** must be correct.

If you can't disambiguate DC_V1 from DC_V2 from quest 58 alone, that's an acceptable limitation: relax these two tests to assert dialect rather than specific sub-version, e.g.:
```kotlin
assertEquals(Dialect.V0_V2, r.value.quest.version.dialect)
```
Don't relax beyond that — GC_NTE vs DC_V2 must be distinguishable (different `BinFormat` candidate sets — actually wait, both are DC_GC bin format; this needs heuristic refinement which may be a follow-up).

If sub-version disambiguation within V0_V2 is needed, escalate to spec: this iteration may need to add NTE-specific bin-header markers (out of original scope but mentioned as future work in §2.8).

- [ ] **Step 3: Commit**

```bash
git add psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/QuestVersionAutoDetectTest.kt
git commit -m "test(psolib): assert auto-detect picks correct Version for V0_V2 fixtures"
```

---

## Phase 7 — Round-trip (write back)

### Task 14: Thread `version` through Assembly + Disassembly + writeBin

**Files:**
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/Assembly.kt`
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/Disassembly.kt`
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Bytecode.kt` (writeBytecode if exists)
- Modify: `psolib/src/commonMain/kotlin/world/phantasmal/psolib/fileFormats/quest/Bin.kt` (writeBin — pass version from Quest)

- [ ] **Step 1: Tighten `writeBytecode` to require `version`**

In `Bytecode.kt:872`, the temporary `version: Version = Version.BB_V4` default from T7 becomes a required parameter:

```kotlin
fun writeBytecode(
    bytecodeIr: BytecodeIr,
    stringEncoding: BytecodeStringEncoding,
    version: Version,
): BytecodeAndLabelOffsets {
```

Inside, every `.getSize(stringEncoding)` (added in T7 as `.getSize(stringEncoding, version)`) keeps working.

For each instruction emission, branch on dialect/argsMode: when `version.dialect == V3_V4 && opcode.argsMode == ArgsMode.Stack`, emit the push-prologue + opcode (existing path — confirm it exists; if the codebase currently relies on `opcode.stack == Pop` to trigger push-emission, replace that condition the same way as T6). Otherwise emit opcode + inline params (existing non-Pop path).

The condition mirror is critical — the parser branch (T6) and the emitter branch must use identical predicates, or round-trip will diverge.

- [ ] **Step 2: Update `writeQuestToBinDat` to pass `quest.version`**

In `Quest.kt:writeQuestToBinDat`, the function already takes `version: Version` as a parameter. The internal call `writeBytecode(quest.bytecodeIr, binFormat.stringEncoding)` becomes `writeBytecode(quest.bytecodeIr, binFormat.stringEncoding, version)`.

No caller of `writeQuestToBinDat` need change — they already pass a `Version`. (Editor-side callers pass `quest.version` if they have a Quest in hand; that's a quest-editor concern, out of scope for psolib.)

- [ ] **Step 3: Add `version` parameter to assembler + disassembler**

In `Assembly.kt`, add `version: Version` to the `assemble(...)` public function (find it via `grep -n "^fun assemble" psolib/src/commonMain/kotlin/world/phantasmal/psolib/asm/Assembly.kt`). Update the `mnemonicToOpcode(tokenizer.strValue)` call at line 355 to `mnemonicToOpcode(tokenizer.strValue, version)`. The emitter branch on dialect/argsMode mirrors Step 1.

In `Disassembly.kt`, add `version: Version` to `disassemble(...)`. Resolve opcode codes via `codeToOpcode(code, version)`. Disassembled mnemonic text is the same across dialects (no ".v0v2" syntax) — only the opcode lookup changes.

- [ ] **Step 5: Run tests**

Run: `./gradlew psolib:check`
Expected: existing tests pass.

- [ ] **Step 6: Commit**

```bash
git add psolib/src
git commit -m "feat(psolib): thread version through assemble/disassemble/writeBin"
```

---

### Task 15: Round-trip test for V0_V2 fixtures

**Files:**
- Create: `psolib/src/commonTest/kotlin/world/phantasmal/psolib/fileFormats/quest/RoundTripV0V2Test.kt`

- [ ] **Step 1: Write the test**

```kotlin
package world.phantasmal.psolib.fileFormats.quest

import world.phantasmal.core.Success
import world.phantasmal.psolib.asm.InstructionSegment
import world.phantasmal.psolib.test.LibTestSuite
import world.phantasmal.psolib.test.readFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoundTripV0V2Test : LibTestSuite {
    @Test fun dc_v1_round_trip() = roundTrip("/q058-d1-e.bin", "/q058-d1.dat")
    @Test fun dc_v2_round_trip() = roundTrip("/q058-dc-e.bin", "/q058-dc.dat")
    @Test fun pc_v2_round_trip() = roundTrip("/q058-pc-e.bin", "/q058-pc.dat")
    @Test fun gc_nte_round_trip() = roundTrip("/q058-gcn-e.bin", "/q058-gcn.dat")

    private fun roundTrip(binPath: String, datPath: String) = testAsync {
        val r1 = parseBinDatToQuestAutoDetect(
            readFile(binPath), readFile(datPath), lenient = false, shiftJis = false,
        )
        assertTrue(r1 is Success, "first parse failed: $r1")
        val quest1 = r1.value.quest

        val (rewrittenBin, rewrittenDat) = writeQuestToBinDat(quest1, quest1.version)

        val r2 = parseBinDatToQuestAutoDetect(
            rewrittenBin.cursor(), rewrittenDat.cursor(),
            lenient = false, shiftJis = false,
            version = quest1.version,
        )
        assertTrue(r2 is Success, "second parse failed: $r2")

        val ir1 = quest1.bytecodeIr.instructionSegments()
        val ir2 = r2.value.quest.bytecodeIr.instructionSegments()
        assertEquals(ir1.size, ir2.size, "segment count differs")
        for ((s1, s2) in ir1.zip(ir2)) {
            assertEquals(s1.labels, s2.labels, "segment labels differ")
            assertEquals(s1.instructions.size, s2.instructions.size, "instruction count differs")
            for ((i, pair) in s1.instructions.zip(s2.instructions).withIndex()) {
                val (a, b) = pair
                assertEquals(a.opcode.mnemonic, b.opcode.mnemonic, "instruction $i mnemonic differs")
                assertEquals(a.args.size, b.args.size, "instruction $i arg count differs")
                for ((j, argPair) in a.args.zip(b.args).withIndex()) {
                    assertEquals(argPair.first, argPair.second, "instruction $i arg $j differs")
                }
            }
        }
    }
}
```

Adjust `writeBin` / `writeDat` calls to match the actual API discovered in T14 (the wrapper may be `writeQuestBinDat(quest)` returning a pair).

- [ ] **Step 2: Run tests**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.RoundTripV0V2Test"`
Expected: all 4 PASS.

- [ ] **Step 3: Commit**

```bash
git add psolib/src
git commit -m "test(psolib): IR-equivalent round-trip for V0_V2 fixtures"
```

---

## Phase 8 — Final verification

### Task 16: NTE-vs-V3 analysis equivalence

**Files:**
- Modify: `psolib/src/jvmTest/kotlin/world/phantasmal/psolib/fileFormats/quest/InspectQuest58.kt` (add new test) — or create `QuestAnalysisEquivalenceTest.kt`

- [ ] **Step 1: Write the test**

Append to `InspectQuest58.kt`:

```kotlin
@Test
fun nte_and_v3_quest58_produce_equivalent_analysis() = testAsync {
    val nte = parseBinDatToQuestAutoDetect(
        Buffer.fromByteArray(this::class.java.classLoader
            .getResource("quest58_j_nte.bin")!!.readBytes()).cursor(),
        Buffer.fromByteArray(this::class.java.classLoader
            .getResource("quest58_j_nte.dat")!!.readBytes()).cursor(),
        lenient = false, shiftJis = true,
    )
    val v3 = parseBinDatToQuestAutoDetect(
        Buffer.fromByteArray(this::class.java.classLoader
            .getResource("quest58_j.bin")!!.readBytes()).cursor(),
        Buffer.fromByteArray(this::class.java.classLoader
            .getResource("quest58_j.dat")!!.readBytes()).cursor(),
        lenient = false, shiftJis = true,
    )
    assertTrue(nte is Success); assertTrue(v3 is Success)
    assertEquals(Version.GC_NTE, nte.value.quest.version)
    assertEquals(Version.GC_V3, v3.value.quest.version)

    // FloorMappings — set equality.
    assertEquals(
        nte.value.quest.floorMappings.toSet(),
        v3.value.quest.floorMappings.toSet(),
        "floorMappings differ between NTE and V3 quest58",
    )

    // ParticleSpawns — set equality (excluding bytecode offsets which will differ).
    fun particleKey(p: ParticleSpawn) = Triple(p.x, p.y, p.z) to p.type
    assertEquals(
        nte.value.quest.particleSpawns.map(::particleKey).toSet(),
        v3.value.quest.particleSpawns.map(::particleKey).toSet(),
        "particleSpawns differ between NTE and V3 quest58",
    )
}
```

Adjust `particleKey` to match the actual `ParticleSpawn` fields (the spec mentions it carries coordinates + bytecode location — exclude bytecode location since that differs by encoding).

- [ ] **Step 2: Run test**

Run: `./gradlew psolib:jvmTest --tests "world.phantasmal.psolib.fileFormats.quest.InspectQuest58.nte_and_v3_quest58_produce_equivalent_analysis"`
Expected: PASS.

- [ ] **Step 3: Run full check**

Run: `./gradlew psolib:check`
Expected: all tests pass — full V0_V2 round-trip + analysis equivalence + V3/BB regression.

- [ ] **Step 4: Final commit**

```bash
git add psolib/src
git commit -m "test(psolib): NTE and V3 quest58 produce equivalent floor + particle analyses"
```

---

## Acceptance summary

After all 16 tasks complete, the following should hold (matches spec §5.3):

1. ✅ `quest58_j_nte.bin` + `quest58_j_nte.dat` strict-parses with zero non-Info problems, `version == GC_NTE`. (T10, T12)
2. ✅ DC_V1 / DC_V2 / PC_V2 / GC_NTE fixtures strict-parse with `invalidCount == 0`. (T13)
3. ✅ All four V0_V2 fixtures IR-round-trip equivalent. (T15)
4. ✅ NTE-vs-V3 quest58 produce equal `floorMappings` / `particleSpawns`. (T16)
5. ✅ All existing V3 / BB tests pass (no regression). (every task runs `./gradlew psolib:check`)
6. ✅ `./gradlew psolib:check` green; `./gradlew psolib:generateOpcodes` build-fails on duplicate `(version, code)`. (T4 emits the `check` in `opcodesFor`)

DC_NTE / PC_NTE remain dialect-supported (V0_V2 path covers them) but lack dedicated fixtures in this iteration — newserv archive doesn't include canonical NTE-era DC/PC quest 58 files. Follow-up iteration if real NTE files surface.
