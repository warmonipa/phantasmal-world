# Asset completeness audit — 2026-10-25

Status: executable inventory and source comparison recorded; unresolved bindings are
listed explicitly in the [reviewed repair plan](asset-completeness-repair-plan.md).
This is not a claim that the asset collection or rendering is complete. The PW4
terminal (`547-3.xj`) remains fixed; this audit found additional independent gaps.

## Current disposition after final review

BML/Vol Opt extraction and loading defects are fixed: nine damaged files replaced and
one missing shared texture added. The 6,737-case post-repair audit has no Vol Opt
failures and retains [79 observations for the other batches](asset-completeness-findings-after-bml.tsv).
Final automated validation: 495 parser tests, 6 extractor tests, 440 browser tests,
production distribution and source/distribution hash checks. A fresh extraction produces
81 files byte-identical to the installed assets, with zero mapping failures.
The user authorized committing and pushing this batch after final review. Deployment,
game-screen visual comparison and the remaining repair batches are not closed.

The historical sections below describe the **pre-repair baseline**; their failure counts
and statements that production assets were unchanged apply to that baseline only.

## Reproduction and scope

Run `rtk proxy python3 tools/asset-audit/run.py`. See the
[runner documentation](../tools/asset-audit/README.md) for prerequisites and boundaries.
Baseline: `1a064e2d`, branch `feat/quest-flow-overlay`. No production assets or loaders
were modified by this audit.

The run recorded 2,984 source asset files with hashes, parsed all 189 bundled QSTs and
30 lobby object-set DATs, and inventoried 56,521 DAT object records. QSTs use psolib's
QST/PRS/DAT parsers; lobby object sets use their raw 68-byte records, not quest DAT
headers. These are placements, not proof that every placement executes in gameplay.

54 diagnostic browser tests completed successfully, recording 4,833 cases and 2,156
distinct HTTP resource paths. Diagnostic success means all cases were recorded; errors
inside individual cases are findings, not passing loading checks.

| Surface | Cases | Result |
| --- | ---: | --- |
| Editor objects, defaults + observed variants + LittleCryotube 0–3 | 307 | 219 loaded; 6 errors; 82 classified exclusions |
| Editor NPCs, normal and Ultimate | 392 | 378 loaded; 10 errors; 4 excluded cases for Unknown/NpcEnemy |
| Viewer objects | 199 | 5 swallow texture 404s and render without the referenced textures |
| Viewer NPCs, including declared Ultimate skins | 114 | 113 loaded; VolOptPart1 fails |
| Viewer item model/texture pairs | 414 | Geometry loaded; 33 have unresolved texture-slot references |
| Declared character head/hair combinations | 100 | Geometry loaded; 34 have unresolved texture-slot references |
| Character body/section-ID texture selections | 2,440 | Selection completed; nullable slots are retained, not counted as decoded textures |
| Special NPC player models | 7 | Loaded and converted |
| Viewer NPC animation paths | 582 | Parsed; motion/bone compatibility and playback not established |
| Area variants, including Episode I Ultimate | 230 | 229 loaded render + collision geometry; 1 path fails |
| Map particle tables | 47 | Loaded, with shared global table and texture conversion |
| Fog table | 1 | Parsed |

No Ultimate-skin HTTP fallback was observed in these cases. The five Viewer object
texture failures were swallowed by `ObjectAssetLoader`, while `EntityAssetLoader`
rejects the same objects. Full raw results and occurrence evidence remain under
`tmp/asset-audit/`; the compact [findings TSV](asset-completeness-findings.tsv) records
90 observations. These are not 90 independent bugs: repeated entry points and
normal/Ultimate cases share causes.

## Confirmed missing request paths

| Priority | Entry / parameters | Requested file | Reproduction and classification |
| --- | --- | --- | --- |
| P1 | LittleCryotube / 547 / model 2 | `objects/547-2.xj` | 22 placements across `quest1321_e.qst` and `The Military Strikes Back.qst`; floor 17, including sections 10, 20 and 30. File absent; original variant-to-model mapping remains unverified. Do not substitute variant 0/1. |
| P1 | LobbyPigeon / 387 | `objects/387.xvm` | 338 bundled placements; e.g. `map_cardlobby01o.dat`, object 46. Geometry references texture slots 20 and 21. |
| P1 | ButterflyLobby / 388 | `objects/388.xvm` | 34 placements; e.g. `map_lobby_02o.dat`, object 5. Slots 1, 5, 9, 13, 14. |
| P1 | LobbyEventObjectRedAndWhiteCurtain / 392 | `objects/392.xvm` | 44 placements; e.g. `map_cardlobby01o.dat`, object 35. Slots 1, 12. |
| P1 | WeddingLobby / 393 | `objects/393.xvm` | 8 placements; e.g. `map_cardlobby01o.dat`, object 34. Slots 1, 3, 14, 23. |
| P1 | TreeLobby / 394 | `objects/394.xvm` | 12 placements; e.g. `map_lobby_10o.dat`, object 41. Slots 4, 10, 12, 15, 33. |
| P2 | Episode II area 16 / variant 1 | `areas/map_jungle06_01n.rel` | Declared Seaside Area at Night variant fails in `AreaAssetLoader`. First request fails, so later dependencies are not validated. Whether the variant declaration/path is wrong or a map is missing remains open. |

Indices above are zero-based. The 547 model range 0–3 is corroborated by newserv
`src/Map.cc`, entry `0x0223` (`TObjSeabedSuiso_CH`, param4 clamped to 0–3).

The five lobby geometries are byte-identical to original local BB `data.gsl` entries:

| Project geometry | BML | Entry |
| --- | --- | --- |
| `387.nj` | `bm_obj_lobby_pigeon.bml` | `lobby_obj_hato.nj` |
| `388.xj` | `bm_obj_lobby_main.bml` | `lobby_chou_nobana.xj` |
| `392.xj` | `bm_obj_lobby_curtain.bml` | `lobby_kouhaku_hashira.xj` |
| `393.xj` | `bm_obj_lobby_main.bml` | `lobby_obj_wday_main.xj` |
| `394.xj` | `bm_obj_lobby_tree.bml` | `lobby_yukieda_hachi.xj` |

These entries contain no embedded textures. `data.gsl/obj_lobby_main.xvm` is a strong
shared-texture candidate: it contains 37 slots and is byte-identical to bundled
`386.xvm`, `390.xvm`, `391.xvm`, and `395.xvm` (148,352 bytes;
SHA-256 `8271e0370faa1c063d7253fa7604b4d00a00790c8e1ebfa1980a4b46d8ea68e8`).
Matching index bounds alone does not establish the visual mapping. Verify the original
client binding or textured previews before adding files or sharing this path.
Geometry hashes are in [lobby provenance](asset-completeness-lobby-provenance.json).

## Confirmed bad extraction: Vol Opt

Affected project models: `VolOptPart1.nj`, `VolOptPart1Sub.nj`, `VolOptCore.nj`,
`VolOptMonitor.xj`, and `VolOptHiraisin.nj`. The first two throw during parsing; the
other three produce no usable parsed root and the Editor substitutes cylinders.
Viewer exposes VolOptPart1 and fails too.

The failure is upstream of rendering. `parseBml` uses
`max(0x800, 0x40 + count * 0x40)` as the data start. For the original
`bm_boss3_volopt.bml` (42 entries), that selects `0xAC0`; the actual aligned start is
`0x1000`. Every one of the five checked-in model files is **byte-identical to the raw
slice at the parser's wrong offset**, with the entry's compressed byte count.
`ExtractNpcModels.tryDecompress` then keeps raw bytes after a decompression failure,
allowing the bad extraction to look like a successful file export.

The original BML SHA-256 is
`3ea1a1ddd9ec02bf6eb0c608b29f0bbc8d4c52d3cc9ed8d9aa47b6f53ec4e60c`.
[Vol Opt provenance](asset-completeness-volopt-provenance.json) records wrong offsets,
byte equality, correct entry names, decoded sizes and decoded hashes. The corrected
entry sizes are respectively 10,016, 40,872, 65,208, 1,504 and 6,188 bytes.

Repair must address BML layout handling and extraction validation together, audit other
BML consumers/archives with the same layout, then regenerate and verify the assets.
Replacing only these five files leaves the generator able to recreate the defect.
No parser or asset repair was made during this inventory pass.

## Texture-slot and mapping findings

The Viewer converts geometry with `MeshBuilder`, which can use a default material
when a referenced texture slot is absent. The audit additionally traverses visible
model nodes with the same hidden/child flags and reports unresolved indices.

- **33 item model/texture pairs:** all are model indices 271 or higher; all selected
  AFS entries loaded. This is not evidence of missing archive files. The generic
  nonweapon catalog uses the same ordinal for geometry and texture; this mapping and
  runtime materials need checking against ItemPMT/client behavior. Model 271 already
  demonstrates a model reference to slot 1 with only one selected texture.
- **34 character head/hair combinations:** all geometry files loaded. For example,
  HUmar hair 6 adds an accessory referring to slot 9, while the selected texture list
  has 9 entries (0–8); HUmar's current accessory texture list is empty. Other classes
  intentionally contain nullable accessory slots. Establish the original material
  binding before treating these as absent assets or deleting the references.
- **5 lobby objects:** these are the confirmed missing texture paths above, appearing
  again through Viewer's permissive texture-loading path.

Exact combinations and unresolved slots are in the findings TSV. No unrelated PMT,
appearance, or material code was changed to make the diagnostics look clean.

## Open coverage and classification work

- The 12 `UnavailableModel` and 12 `Unverified` object types remain classified as
  unresolved, not counted as successful resource coverage. Unknown IDs, Battery,
  GBAStation, JungleDesign, test doors/plants, special boxes and the other explicit
  members require original-client evidence.
- The 36 invisible-logic types, 21 runtime-visual types and PlayerSet editor marker
  are intentional current boundaries; this audit does not certify those classifications
  against every client behavior.
- Probe, LaserDetect and BigBrownRock still have hard-coded geometry selection/TODOs.
  ButterflyLobby declares a model-number property but `QuestObject.modelOffset` does
  not expose it. Successful base loading does not verify all visual variants.
- Only LittleCryotube has an explicitly added client-supported range beyond corpus
  defaults. Other unobserved parameter ranges, script-created entities, local-client
  free-roam sets, all character skin/face combinations, character animation catalogs,
  effect playback, and in-game visual comparisons remain outside this first pass.
- The production distribution and live site were not changed or revalidated by this
  audit. New fixes need failing regressions, production build verification, and visual
  checks before their individual findings can close.

Recommended repair order: BML extraction integrity; lobby texture binding; original
547 variant-2 mapping; night-seaside variant declaration/source; character and item
texture mappings; remaining unsupported/unverified types and unobserved variants.
The separate [TODO](asset-completeness-todo.md) remains open.

## Source comparison addendum

The sections above preserve the initial 4,833-case baseline. The following evidence
extends and, where noted, supersedes its initial classifications. No production fix
was made. [Input provenance](asset-completeness-source-provenance.json) records exact
local source paths, sizes and hashes; the source comparison is reproducible with
`tools/asset-audit/source_evidence.py`.

The expanded browser run completed **57 diagnostic tests / 6,736 cases / 2,790
distinct HTTP paths**, split into three batches to avoid the toolchain's stdout
capture limit. The runner checks batch output, duplicate IDs and expected coverage
counts before accepting results. The first expanded attempts produced incomplete
logs; they were rejected and are not included in these totals.

| Added coverage | Cases | Result |
| --- | ---: | --- |
| Standalone XVM structural parsing | 528 | 524 nonempty; 4 Vol Opt files fail |
| All declared player animation paths | 572 | Parsed; playback not certified |
| Skin/face/head texture selection, body 0 / Viridia | 660 | Selection completed; existing nullable slots retained |
| Source-reference nonweapon model/texture pairs | 143 | Converted; no unresolved texture slots; visual identity and multipart completeness still open |

The updated [findings TSV](asset-completeness-findings.tsv) contains **94 diagnostic
observations** (22 errors and 72 texture-reference observations), not 94 independent
defects. The four additional errors are the independently parsed damaged Vol Opt
textures. The initial 90 observations remain reproducible. No production asset hash
changed during this run.

### Original-client corpus and unresolved types

The local BB source scan inventories **179 raw object sets / 27,342 records / 158
distinct type IDs**. These are raw-file occurrences, not proof of runtime reachability;
the selected SetData table, mode, quest and script still determine what executes.
Six SetData artifacts were read (including differently cased loose/archive names),
not six distinct game modes. All scanned map-ID 34 rows use layout 0, with
`map_jungle06_00` or its offline object set. This supports an excess declaration in
the project's night-seaside variants; it does not prove every client version lacks
a second layout. Resolve that baseline before deleting a declaration or adding files.

All **24 UnavailableModel/Unverified types** now have a
[per-type register](asset-completeness-unresolved-objects.tsv) with bundled and
raw-client occurrence counts. For example, JungleDesign has 55 bundled / 22 raw
placements; BreakableBrownRock has 17 / 3. Zero occurrences are not evidence that a
type is invisible or unsupported. Their client resource bindings remain unresolved.

The [variant review register](asset-completeness-variant-review.tsv) records 20
model-related parameter comments from newserv's V3/V4 or V4 object registry, with
observed raw-client values. It is a targeted evidence index, not an exhaustive proof
of all visual parameters. Notably, lobby pigeon param6 and butterfly param4 select
models, but neither appears in `QuestObject.modelOffset`; successful base-model
loading therefore does not certify variant fidelity.

### Extraction damage extends to textures

The affected Vol Opt scope is **9 files**, superseding the initial model-only count:
the five models above plus `VolOptMonitor.xvm`, `VolOptHiraisin.xvm`,
`VolOptPart1.xvm`, and `VolOptCore.xvm`. All nine match wrong-offset raw slices.
The [BML scope register](asset-completeness-source-bml-layout-scope.json) records
10 archives whose directory/data boundary exceeds the parser's assumed start.
Only the Vol Opt archives yielded these raw-byte matches. Absence of a raw match
does not certify the other archives or their consumers: erroneous decompression,
unused entries and different alignment flags need separate checks.

### Nonweapon catalog binding evidence

The existing local BB PMT-derived reference maps mag geometry to `type + 271` and
textures to `skin + 295`; shields use `type + 354` and `skin + 378`. Project AFS
hashes are recorded in provenance. The candidate source register contains **143
distinct in-range pairs** (77 mag, 66 shield; 130 distinct geometry indices).
See [pair evidence](asset-completeness-source-item-pairs.tsv), including original
NJTL texture names and selected XVM texture counts.

The generic Viewer catalog instead assumes identical geometry/texture ordinals and
starts its shield category at 295. This affects both labels and texture selection:
model 295 is Durga, 335 is Sato, 348 is Agastya and 354 is Red Ring in the reference.
For model 271, NJTL names two textures; texture entry 271 has one slot, while the
reference's entry 295 has two. The 33 initial missing-slot findings thus undercount
the potential catalog errors: a wrong texture with enough slots will pass that check.
Candidate-pair loading is diagnostic only, not installation of a corrected catalog.
Multiple NJ roots and animated parts also require assembly verification.

### Character binding evidence

`CharacterClassAssetLoader.loadXvrTextures` accepts head, skin and face, but no hair
style. Its hair/accessory texture arrays therefore cannot express style-dependent
bindings. Local qedit `NPCBuild.pas` contains a per-style `HairOff` table and
per-class accessory selection; for example, HUmar style 6 selects accessory texture
103. That corroborates a mapping/assembly gap, rather than absence of a player AFS.
It is not sufficient to copy another editor's table without checking this BB baseline.
The 34 initial unresolved combinations remain open pending per-class material and
visual verification.

### Approval boundary

The [repair proposal](asset-completeness-repair-plan.md) separates confirmed root
causes from evidence-gathering prerequisites and gives acceptance criteria for each.
The audit does not close its TODO until unsupported classifications, variant bindings,
and visual comparisons are resolved or assigned evidence-backed boundaries.

## First implementation batch after plan review

The user authorized implementation after review of the plan. The
[review ledger](asset-completeness-review-ledger.md) records the plan corrections and
the first BML/Vol Opt batch. Earlier counts in this report describe the pre-repair
baseline and are retained as evidence, not current success claims.

The parser now aligns the complete directory to 0x800, selects the verified P/0 or
P/1 stream alignment and rejects corrupt/truncated entries instead of returning a
partial success. Extraction rejects PRS failures and decoded-size mismatches,
validates a model/texture pair before writing it, and exits with failure when a mapping
cannot be extracted. The original synthetic small-BML test fixture lacked format
flags; its header now states the P/1 layout its payload already used.

Nine damaged files were regenerated with the production extractor and compared
byte-for-byte with independent BML/PRS extraction. The next diagnostic pass exposed
the previously masked missing `VolOptPart1Sub.xvm`. The parts model and its texture
owner declare the identical ordered NJTL names `y512_vol2_01,y512_vol2_02`; the
generator now explicitly selects that owner's texture entry. Thus this batch changes
**9 existing files and adds 1 shared texture file**. All other files regenerated by
the extractor match existing project assets.

[Installed resource provenance](asset-completeness-volopt-repaired.json) records the
archive entries, sizes and hashes. These ten hashes also match the production
distribution. Automatic validation passed: 495 psolib JVM tests, 3 extraction tests,
440 web browser tests, and `:web:jsBrowserDistribution`. The new real-loader
regression failed with the damaged assets and passes after restoration, covering all
five Vol Opt model parts in normal and Ultimate selections, actual textured geometry
and absence of placeholder cylinders.

This is not a completion claim for the remaining catalog, character, lobby, variant,
map or unverified-object work, nor for game-screen visual equivalence. Nothing from
this implementation batch has been committed, pushed or deployed.

### Extraction validation re-review

A follow-up review reopened BML-02: valid PRS output with the expected byte count
could still be an invalid model/motion, and XVM parsing could return partial success
with a texture-count warning. The exporter now parses model and motion content,
requires usable geometry/motion data, and rejects incomplete or empty texture archives
before writing files. Three CLI-entry regressions failed before this correction and
pass afterwards (six extraction tests total). A fresh-directory extraction still
produces 81 files byte-identical to the installed assets, with zero mapping failures.
The earlier compression-only tests missed this decoded-content boundary.
