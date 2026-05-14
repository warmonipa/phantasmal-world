# Quest version support — follow-ups

Tracks remaining work after the quest-version-support branch
(`feat/quest-version-support`) lands. The main effort delivered:

- 8-value `Version` enum (`DC_NTE/V1/V2`, `PC_NTE/V2`, `GC_NTE/V3`, `BB_V4`) + `Dialect`
- Per-version opcode tables (`opcodesFor(version)`) with version-mask bitsets
- V0_V2 inline-args dialect parsed/written correctly
- Auto-detect with candidate ranking
- 1243/1243 (100%) newserv quest archive parses cleanly

Items below are not blockers — they fail safely or only matter for files our
current corpus doesn't exercise.

---

## Important — real risk to non-quest58 files

### I2. R_REG32_SET_FIXED parameter byte-width

About 6 V0_V2 dual-form entries in `psolib/srcGeneration/asm/opcodes.yml` model
4-byte register-set arguments as 1 byte each:

- `0x7C npc_crppk`
- `0x7D npc_crptalk`
- `0xCE npc_crptalk_id`
- `0xC0 particle`
- `0xCD particle_id`
- `0x87 pos_pipe`

Each has a doc string admitting "register number is 4 bytes in actual binary,
but psolib models as 1 byte". The newserv archive's V0_V2 quest 58 doesn't
exercise these opcodes, so the scan still PASSes — but any V0_V2 quest in the
wild that uses them will misalign at the opcode boundary.

Fix: introduce a `reg32` param type (or extend `reg` with a byte-width flag),
regenerate, audit affected entries.

### I3. V0_V2 particle static analysis

`psolib/src/commonMain/.../asm/dataFlowAnalysis/GetParticleSpawns.kt` matches
`OP_PARTICLE_V3_V3_V4.code` only — the V3_V4 form of the `particle_v3` opcode.

The V0_V2 dialect uses `particle 0xC0` (different code). On a V0_V2 quest that
uses `particle`, `Quest.particleSpawns` returns empty — the editor's particle
overlay would silently miss them.

quest58 has no particles so the NTE-vs-V3 equivalence test happens to pass.

Fix: match both opcode codes in `getParticleSpawns`, normalize args between
the two forms.

---

## Nice-to-have — quality / UX

### F1. q230-style label-points-into-data sub-segments

`docs/quest-version-followups.md` companion: see TODO comment in
`psolib/src/commonMain/.../fileFormats/quest/Bytecode.kt` ("TODO(future): build
a sub-segment at labelOffset…").

q230 "Blue Star Memories" (Episode 2 VR, num 486) has labels that resolve
into the middle of an instruction segment because the targeted bytes are
string data, not code. Newserv renders them as raw strings; we just downgrade
the warning to Info and lose the content.

Fix: when a label resolves to mid-segment with a clearly-data prefix (printable
ASCII, null-terminated), split the parent segment and create a
`StringSegment` or `DataSegment` at the label so the bytes are addressable
from the IR.

### F2. Explicit-version-incompatible-with-binFormat should fail loud

`Quest.kt::parseBinDatToQuestAutoDetect` currently treats a caller's explicit
`version` as a hard override and goes straight to that version. If the caller
passes `Version.BB_V4` against a `BinFormat.DC_GC` file (the BIN header says
DC/GC layout), the parse will likely produce high `invalidCount` but still
return `Success`. Per the spec §4, this should be an immediate `Failure`.

Fix: add a compatibility check at the top of the explicit-version branch.

---

## Out of scope (intentionally, per spec §5.4)

- DC_NTE / PC_NTE dedicated fixtures — newserv archive lacks canonical
  examples. Add when real-world NTE files surface.
- Ep3 / XB / DC_11_2000 sub-version opcode data.
- Cross-version upgrade/downgrade (NTE → V3 or vice-versa).
- UI exposure of `quest.version` — quest-editor dropdown still only shows
  `GC_V3` / `BB_V4`; new sub-versions are accessible via auto-detect but not
  user-selectable. Add when there's a concrete editor use case.

---

## External state (not a code task)

The branch `feat/quest-version-support` is stacked on
`feature/particle-spawn-markers`. PR against `master` is gated on the
particle branch landing upstream first.
