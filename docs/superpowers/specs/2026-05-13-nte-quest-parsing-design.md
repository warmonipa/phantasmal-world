# NTE / V0_V2 quest parsing — design

**Status:** draft (awaiting user review)
**Scope target:** full V0_V2 dialect family (DC_NTE, DC_V1, DC_V2, PC_NTE, PC_V2, GC_NTE), read + write round-trip, heuristic version detection (no UI).
**Reference implementation:** newserv `src/QuestScript.cc` — opcode flag bitmask + `opcodes_for_version(v)` dispatcher.
**Supersedes:** `docs/todo-nte-support.md` (which stays as a historical context note; this spec is the authoritative plan).

---

## 1. Architecture

Introduce a **version axis** that threads through `opcode table → bytecode parser → IR size → assembler/disassembler → save`.

`Version` enum expands from 4 values (`DC/GC/PC/BB`) to 8 values:
`DC_NTE, DC_V1, DC_V2, PC_NTE, PC_V2, GC_NTE, GC_V3, BB_V4`. This subset matches newserv's enum minus Ep3 / XB / DC_11_2000 (those are out of scope for this iteration).

Opcode data stays in a single source of truth (`psolib/srcGeneration/asm/opcodes.yml`) but the schema gains three pieces:

- `versions: <shortcut>` — one of `V0_V2 | V3_V4 | V0_V4 | V1_V2 | V1_V4 | V2 | V2_V4 | V3 | V4` (mirrors newserv's `F_*` shortcuts). Default `V0_V4`.
- `args: stack | inline | none` — only meaningful for opcodes that take arguments. Default: `stack` if `stack: pop`, else `inline` if the opcode has params, else `none`. This makes nearly every existing entry diff-free.
- Same `code` may appear in two YAML records, distinguished by `versions`. Used only for the ~61 dual-form opcodes newserv lists (e.g., `set_floor_handler` 0x95 with V0_V2 vs V3_V4 param shapes).

Code generation (`psolib/build.gradle.kts: generateOpcodes`) is rewritten to emit:

- `ALL_OPCODES: List<Opcode>` — every YAML entry, each carrying `versionMask: Int` and `argsMode: ArgsMode`.
- `fun opcodesFor(version: Version): OpcodeTables` — returns three `Array<Opcode?>` (0x00..FF, F8xx, F9xx) filtered by `versionMask`. Lazily memoized per version.

The `Version` parameter threads through `parseBinDatToQuest(..., version: Version?)`. When `null`, the auto-detect entry point narrows candidates by `bin.format`, strict-parses each, and picks the candidate with the fewest invalid instructions. `Quest` gains `var version: Version` (default `BB_V4`); `binFormat` stays for header layout but `version` is what drives opcode interpretation and save.

---

## 2. Components

### 2.1 `Version` enum (`psolib/.../quest/Version.kt`)

- Expand to `DC_NTE, DC_V1, DC_V2, PC_NTE, PC_V2, GC_NTE, GC_V3, BB_V4`.
- Add extension properties:
  - `Version.dialect: Dialect` (`V0_V2` or `V3_V4`) — `BB_V4` is in `V3_V4`.
  - `Version.bit: Int` — `1 shl ordinal` (used as the version mask bit, same shape as newserv's `v_flag`).
- All existing references to `Version.DC/GC/PC/BB` get rewritten to `DC_V2 / GC_V3 / PC_V2 / BB_V4` (conservative defaults; no compatibility shims).

### 2.2 `opcodes.yml` + schema (`psolib/srcGeneration/asm/`)

- JSON schema additions: `versions` (enum string, optional, default `V0_V4`), `args` (`stack|inline|none`, optional).
- YAML data: keep all current entries; add `versions:` only where it differs from default (`V0_V4`). Add second entries for the dual-form opcodes from newserv's table (≈61). Default `args` derived from `stack:` per the rules in §1; only override when behavior is irregular.
- Provenance note: the dual entries come straight from `src/QuestScript.cc` lines 549–3192. A side comment at each split entry references the newserv line, so future updates are traceable.

### 2.3 Codegen (`psolib/build.gradle.kts: generateOpcodes`)

- Replaces the three top-level `Array<Opcode?>` constants with `ALL_OPCODES: List<Opcode>` and `fun opcodesFor(version: Version): OpcodeTables`.
- `OpcodeTables` is a simple data class `(byCode: Array<Opcode?>, byCodeF8: Array<Opcode?>, byCodeF9: Array<Opcode?>)`.
- Codegen validates: for each `(version, code)` pair, at most one `Opcode`. Conflict → `IllegalStateException` at build time. No silent drops.

### 2.4 `Opcode` class (`psolib/.../asm/Opcode.kt`)

- New fields: `versionMask: Int`, `argsMode: ArgsMode` (`Stack | Inline | None`).
- `argsMode` describes V3_V4 behavior. On V0_V2 dialect, arguments are always read inline regardless of `argsMode`; the field only takes effect when `version.dialect == V3_V4` (parallel to newserv's `F_HAS_ARGS & v_flag(v)` gate on `F_ARGS`).
- `stack: StackInteraction?` stays. It now means "physical stack interaction of the running opcode" — orthogonal to where args come from. (Mirrors newserv: `F_ARGS` and `F_PUSH_ARG` are independent flags.)

### 2.5 Bytecode parser (`Bytecode.kt`)

- `parseBytecode(..., version: Version)`: picks opcode table via `opcodesFor(version)`.
- `parseInstructionArguments(cursor, opcode, version, stringEncoding)`:
  - Branch condition changes from `opcode.stack != StackInteraction.Pop` to `!(version.dialect == V3_V4 && opcode.argsMode == ArgsMode.Stack)`.
  - When the branch is taken (V0_V2, or V3_V4 inline-args opcode, or non-args opcode), parse params inline using existing per-`ParamType` logic.
  - When the branch is not taken (V3_V4 + stack-args), skip inline param read — args come from the push-prologue (existing path).
- Lenient heuristics (`MAX_UNKNOWN_OPCODE_RATIO`, `MAX_TOTAL_NOPS`, etc.) unchanged.

### 2.6 IR size (`BytecodeIr.kt::Instruction.getSize`)

- Signature becomes `getSize(stringEncoding: BytecodeStringEncoding, version: Version): Int`.
- The `opcode.stack === StackInteraction.Pop` short-circuit is replaced by `version.dialect == V3_V4 && opcode.argsMode == ArgsMode.Stack` — same condition as §2.5.
- The misleading comment ("All known PSO versions use push instructions for Pop opcodes") is deleted.

### 2.7 `Quest` (`Quest.kt`)

- New field `var version: Version = Version.BB_V4`.
- `parseBinDatToQuest(binCursor, datCursor, lenient, compressed, shiftJis, version: Version? = null)`. Returns `Quest` with `version` populated.
- `parseBinDatToQuestAutoDetect` (existing): unchanged outward contract. Internally:
  1. PRS decompress (existing).
  2. `parseBin` → `bin.format`.
  3. `candidates = versionsFor(bin.format)` (see table in §3).
  4. For each candidate: `parseBytecode(..., lenient = false, version = candidate)`, record `(version, invalidCount, unknownCount, totalNops, problems, ir, threw)`. Wrap exceptions; treat as "candidate failed strict".
  5. Filter to candidates that did not throw. Sort by `(invalidCount, unknownCount, totalNops, binFormatDefaultRank)` asc (the rank is the tie-breaker defined in §4.1). Pick first.
  6. If all candidates threw → pick the `bin.format`'s default sub-version (`DC_GC → GC_V3`, `PC → PC_V2`, `BB → BB_V4`) and re-run with `lenient = true`. Add `Severity.Warning` problem stating the chosen version + that no strict candidate succeeded.
  7. If multiple candidates score truly equally (all four sort keys including `binFormatDefaultRank` tied — only possible if two non-default candidates produce identical results), add `Severity.Info` recording the ambiguity.

### 2.8 Bin header (`Bin.kt`)

Not modified. `GC_NTE` reuses `BinFormat.DC_GC` (verified: NTE quest58 has `code_offset = 0x1D4 = 468 = DC_GC_OBJECT_CODE_OFFSET`). DC_NTE / PC_NTE header sub-field differences are explicitly out of scope; if discovered, they get their own `BinFormat` value in a follow-up.

### 2.9 Assembly / Disassembly (`Assembly.kt`, `Disassembly.kt`)

- Disassembler takes `version: Version`; picks opcode table; emits the same textual mnemonic + arg list regardless of dialect. No new ".v0v2" syntax.
- Assembler takes `version: Version`; for `argsMode == Stack && version.dialect == V3_V4`, emits a push-prologue then the opcode; for `Inline` or `V0_V2`, emits opcode followed by inline params.
- Both update their `getSize`-equivalent paths to pass `version`.

### 2.10 Data flow analysis (`dataFlowAnalysis/*`)

Not modified. CFG / FloorMapping / ParticleSpawn consume `BytecodeIr`. The IR is the same semantic shape across dialects — version only affects how bytes become IR, not the IR itself.

### 2.11 Test fixtures (`psolib/src/commonTest/resources/`)

Add the following from newserv's `system/quests/retrieval/`:

- `q058-d1-e.bin` + `q058-d1.dat` (DC_V1 EN)
- `q058-dc-e.bin` + `q058-dc.dat` (DC_V2 EN)
- `q058-pc-e.bin` + `q058-pc.dat` (PC_V2 EN)
- `quest58_j_nte.dat` — derived from `q058-gcn.dat` (which is a symlink to `q058-dc.dat` in newserv); paired with the already-committed `quest58_j_nte.bin`.

Existing `quest58_j.bin/.dat` (V3 GC J) stays as the regression anchor. BB fixtures under `tethealla_v0.143_quests/` stay.

---

## 3. Data flow

### 3.1 Load + detect

```
.qst / bin+dat bytes
       │
       ▼
parseBinDatToQuestAutoDetect(binCur, datCur, lenient, shiftJis, version=null)
       │
       ▼
[1] prsDecompress (if compressed)
       │
       ▼
[2] parseBin(...) → BinFile{ format: DC_GC | PC | BB, bytecode, labels, ... }
       │
       ▼
[3] candidates = versionsFor(bin.format)
       │    DC_GC → {DC_NTE, DC_V1, DC_V2, GC_NTE, GC_V3}
       │    PC    → {PC_NTE, PC_V2}
       │    BB    → {BB_V4}
       │
       ▼
[4] for v in candidates:
       parseBytecode(bytecode, labels, entryLabels, encoding, lenient=false, version=v)
       record (v, invalidCount, unknownCount, problems, ir, threw?)
       │
       ▼
[5] pick winner by (didn't throw, invalidCount asc, unknownCount asc, totalNops asc, then bin.format-default-rank)
       │   all threw → pick bin.format's default sub-version, rerun lenient=true, add Warning
       │
       ▼
[6] parseDat (unchanged)
       │
       ▼
[7] Quest{ ..., binFormat = bin.format, version = winner.v, bytecodeIr = winner.ir }
```

### 3.2 Save

```
Quest{ version, bytecodeIr, binFormat, ... }
       │
       ▼
writeQuest → writeBin
       │  encode opcodes per (version.dialect, opcode.argsMode)
       │  emit header per binFormat (DC_GC / PC / BB layouts)
       ▼
binBuf, datBuf → prsCompress → bytes
```

Save is "what loaded as, saves as". No cross-version upgrade/downgrade in this iteration.

### 3.3 Analysis

`getFloorMappings(...)`, `getParticleSpawns(...)`, `ControlFlowGraph.create(...)` consume `BytecodeIr` only. Untouched. The "quest58 V3 vs quest58 NTE produce equivalent floor/particle results" assertion is one of the round-trip oracles (see §5).

---

## 4. Error handling

| Failure mode | Behavior |
|---|---|
| PRS decompression fails | `Failure` returned (unchanged) |
| `bin.format` falls through known offsets | Existing warn + treat as `PC`; candidate set is then `{PC_NTE, PC_V2}` |
| All version candidates throw strict | Pick `bin.format`'s default sub-version (`DC_GC → GC_V3`, `PC → PC_V2`, `BB → BB_V4`), re-run with `lenient=true`; add `Severity.Warning` describing chosen version + that no strict candidate succeeded |
| Multiple candidates strict-OK, all `invalidCount==0` | Tie-break on `bin.format`'s default sub-version; only emit `Severity.Info` if even the default rank doesn't break the tie (i.e., two non-default candidates equal across all sort keys) |
| Caller passes explicit `version` incompatible with `bin.format` | Immediate `Failure`. No silent fallback — caller bug signal |
| YAML codegen finds `(version, code)` duplicate | `IllegalStateException` at build time. Build fails. No silent drop |
| Inline-arg parser hits cursor overflow on a V0_V2 candidate | Existing path: catch → mark `Instruction.valid = false`. Drives the §3 [5] ranking |
| Round-trip write encounters `argsMode == Stack` but IR lacks `arg_push*` prologue | `IllegalStateException` with mnemonic + segment labels. IR self-inconsistency — fail loud |
| Assembler called without `version` | Compile error (non-nullable param). No default |

### 4.1 Ranking detail (step [5])

Lexicographic on `(threw, invalidCount, unknownCount, totalNops, bin.format-default-rank)`:

1. `threw` — true is worse. Any candidate that threw is dropped; only "no-throw" candidates compete on the rest.
2. `invalidCount` — `bytecodeIr.instructionSegments().sumOf { seg -> seg.instructions.count { !it.valid } }`.
3. `unknownCount` — `... { instr -> instr.opcode.mnemonic.startsWith("unknown_") }`.
4. `totalNops` — already collected by the lenient path; used only as tie-breaker.
5. `bin.format-default-rank` — `bin.format`'s canonical sub-version sorts first (e.g. for `DC_GC`, `GC_V3` outranks `DC_V2` outranks `GC_NTE` outranks `DC_V1` outranks `DC_NTE`).

### 4.2 Problems flow

- Per-candidate problems accumulate during evaluation but only the **winner's** problems are passed through to the caller. Losing candidates' failures go to logger at `Trace` level — useful when diagnosing detection misfires, invisible to editor UI.

### 4.3 Out-of-scope error cases

- DC_NTE / PC_NTE header sub-field decoding. If actual NTE files put `questId` or `language` at different offsets, this iteration silently keeps the V1/V2 layout interpretation. A fixture-driven follow-up adds `BinFormat.DC_NTE / PC_NTE` if it actually matters in practice.

---

## 5. Testing

### 5.1 Fixture matrix

| File | Source | Version | Purpose |
|---|---|---|---|
| `quest58_j_nte.bin` (already committed) | repo | `GC_NTE` | strict parse + round-trip |
| `quest58_j_nte.dat` (new) | newserv `q058-gcn.dat` → `q058-dc.dat` | `GC_NTE` | NTE bin companion |
| `q058-d1-e.bin/.dat` (new) | newserv | `DC_V1` | strict parse |
| `q058-dc-e.bin/.dat` (new) | newserv | `DC_V2` | strict parse |
| `q058-pc-e.bin/.dat` (new) | newserv | `PC_V2` | strict parse |
| `quest58_j.bin/.dat` (existing V3) | repo | `GC_V3` | **regression** — V3 path stays strict |
| `tethealla_v0.143_quests/*` | existing | `BB_V4` | regression |

`DC_NTE` and `PC_NTE` are covered by code paths (the dialect handler) but not by a dedicated fixture in this iteration — newserv's archive doesn't include canonical NTE-era DC/PC versions of quest 58. Coverage is taken from the symmetric `DC_V1` / `PC_V2` fixtures (same dialect, same opcodes).

### 5.2 Tests

- **`InspectQuest58.kt`** (jvmTest): refactor `parseBinStrict` from println diagnostic into `assertSucceeds` shape against the NTE bin + NTE dat. Assert `problems.size == 0`, `version == GC_NTE`, `bytecodeIr.segments.isNotEmpty()`.
- **`QuestVersionAutoDetectTest.kt`** (new, commonTest): parameterized over the 5 V0_V2 fixture pairs. Asserts (a) auto-detect picks the correct `Version`, (b) `invalidCount == 0`, (c) at least one `InstructionSegment` produced.
- **`RoundTripV0V2Test.kt`** (new, commonTest): for each V0_V2 fixture, `parse → writeBin → parse`. Assert the two IRs are equivalent on `(opcode.mnemonic, args)` sequence per segment. Byte-for-byte round-trip is **not** required (label table padding etc.) — IR equivalence is the oracle.
- **`OpcodeTableCodegenTest.kt`** (new, commonTest): asserts `opcodesFor(GC_NTE)` and `opcodesFor(GC_V3)` differ on at least the known dual-form opcodes (e.g., 0x95 `set_floor_handler`). Asserts `ALL_OPCODES` contains no `(version-bit-overlap, code)` collision.
- **Analysis equivalence**: NTE quest58 vs V3 quest58 must yield equal `floorMappings` (as sets) and equal `particleSpawns` (as sets). Added as a case in `InspectQuest58.kt` or a small `QuestAnalysisEquivalenceTest.kt`.
- **Existing V3/BB tests**: zero regressions.

### 5.3 Acceptance checklist

1. `quest58_j_nte.bin` + `quest58_j_nte.dat` strict-parses with zero problems, `version == GC_NTE`.
2. All five V0_V2 fixtures strict-parse with `invalidCount == 0`.
3. All five V0_V2 fixtures IR-round-trip equivalent.
4. NTE-vs-V3 quest58 produce equal `floorMappings` / `particleSpawns`.
5. All existing V3 / BB tests pass.
6. `./gradlew psolib:check` green; `./gradlew psolib:generateOpcodes` build-fails on duplicate `(version, code)`.

### 5.4 Out of scope

- DC_NTE / PC_NTE BIN header sub-field exact decoding.
- Cross-version upgrade / downgrade (NTE → V3 or vice-versa).
- Ep3 / XB / DC_11_2000 opcode data.
- Editor UI (version picker, save-as-different-version). UI remains untouched.
