# TODO: NTE / V0_V2 inline-args quest support

## Status

Not supported. `parseBinDatToQuestAutoDetect(lenient=false)` fails on any quest
whose bytecode uses the V0_V2 inline-args encoding (DC NTE, DC v1, DC v2, PC
NTE, PC v2, GC NTE). Lenient mode does not "support" these either — it merely
suppresses the read-past-end exception, leaving most instructions decoded
incorrectly against the V3 opcode table.

Concrete reproducer: SEGA quest 58 GC_NTE JP. Decompressed bin is 6804 bytes,
header has `code_offset=0x1D4` (DC_GC format), but bytecode after the header
uses inline args. `parseBinStrict` throws
`IllegalArgumentException: 324 Bytes required but only 262 available`
at `Bytecode.kt:638` inside a misinterpreted vararg opcode.

## Root cause

Three structural assumptions baked into the codebase:

1. `Version` enum (`psolib/.../fileFormats/quest/Version.kt`) collapses every
   release of a hardware platform into a single value: `DC | GC | PC | BB`.
   There is no way to express DC v1 vs DC v2, PC NTE vs PC v2, or GC NTE vs
   GC v3.

2. `Opcodes.kt` is generated once from a single source list (see
   `psolib/srcGeneration/`). F_ARGS-class opcodes are emitted with
   `stack = StackInteraction.Pop` only — no inline-args alternative exists.
   newserv, by contrast, stores both forms per opcode (see
   `QuestScript.cc` lines 549 and 828–829 for `message` and
   `set_floor_handler`: separate `F_V0_V2` and `F_V3_V4 | F_ARGS`
   definitions).

3. `BytecodeIr.kt:183` carries an explicit comment encoding this assumption:
   ```kotlin
   if (opcode.stack === StackInteraction.Pop) {
       // All known PSO versions use push instructions for Pop opcodes in binary format.
       ...
   }
   ```
   The comment is wrong for V0_V2. `parseInstructionArguments`
   (`Bytecode.kt:609`) likewise has no version branch — it reads or skips
   based purely on `opcode.stack`.

`parseBytecode` is called without a `Version` parameter
(`Quest.kt:115`), and `BinFormat` (set from `code_offset`) only distinguishes
header layout, not opcode dialect. A GC_NTE file has the same `code_offset`
as a GC_V3 file, so auto-detection sends both down the V3 opcode path.

## Required changes

1. Expand `Version` enum to include sub-versions: at minimum `DC_NTE, DC_V1,
   DC_V2, PC_NTE, PC_V2, GC_NTE, GC_V3, GC_EP3_NTE, GC_EP3, XB_V3, BB_V4`.
   Mirror newserv's `Version` enum to keep terminology aligned.

2. Add a header-based or content-based version-narrowing step. `BinFormat`
   alone is insufficient. Possible signals:
   - Trial bin/dat sizes and field layouts (NTE variants have distinct
     header sub-fields).
   - Probing the bytecode under both V0_V2 and V3 opcode tables, picking the
     one that yields fewer invalid/varargs-overflow instructions. This is
     also a reasonable fallback when the header is ambiguous.

3. Make `Opcodes.kt` version-aware. Two viable shapes:
   - **Multi-entry table**: store a per-version param list and stack mode
     per opcode (matches newserv).
   - **Per-version generated opcode set**: regenerate `Opcodes.kt` once per
     supported version and dispatch at parse time.
   Either way, the opcode source list in `psolib/srcGeneration/` needs the
   version axis.

4. Thread `Version` (or the per-version opcode set) through:
   `parseBinDatToQuest` → `parseBytecode` → `parseInstructionsSegment` →
   `parseInstructionArguments`. Branch on stack mode + opcode signature
   per version.

5. Update `BytecodeIr.getSize` and the assembler/disassembler
   (`Disassembly.kt`, `Assembly.kt`) to emit and consume the right form per
   version. Round-trip tests should cover at least one quest per
   sub-version.

## Workaround until then

Use newserv's `disassemble-quest-script --gc-nte --reassembly` + manual
`.version` retarget + `assemble-quest-script --decompressed` to upgrade
V0_V2 quests to V3 form. This is what produced the V3 `quest58_j.bin`
referenced by `psolib/src/jvmTest/.../quest/InspectQuest58.kt`.

## References

- newserv `src/QuestScript.cc` lines 195–250 (version-flag tables),
  549 (`message` dual definition), 828–829 (`set_floor_handler` dual
  definition), 3192 (`version_has_args` switch).
- This repo: `psolib/src/jvmTest/.../quest/InspectQuest58.kt` — kept as a
  regression target. Currently passes only because `quests/quest58_j.bin`
  in the companion `pso-quest-master` repo has been upgraded to V3.
  If/when NTE support lands, that file should be restored to its original
  GC_NTE encoding and the test should pass strict against the NTE bytes.
