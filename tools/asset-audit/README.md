# Resource completeness diagnostics

Run from the repository root:

```sh
rtk proxy python3 -B tools/asset-audit/source_evidence.py \
  --psoarc-tools /Users/wangzhen/study/bb-psov4/tools \
  --client-data '/Applications/EphineaPSO.app/Contents/SharedSupport/prefix/drive_c/EphineaPSO/data' \
  --item-map /Users/wangzhen/study/bb-psov4/ref/custom_item_assets/reference/ephinea-item-model-texture.tsv
rtk proxy python3 tools/asset-audit/run.py
```

Requires the same Gradle/JDK/Chrome setup as `:web:jsBrowserTest` and Python 3
(standard library plus the existing local `psoarc` readers for source evidence).
Source evidence must run first; it supplies the candidate item pairs. The paths above
identify the audited local baseline, and can be replaced with another explicitly
identified baseline. Do not run another web test/build concurrently: the runner
temporarily installs its diagnostic test source and removes it in `finally`.
It refuses to overwrite an existing test file at that path.

The runner inventories bundled QST object parameters with psolib, builds cases from
the actual editor enums and Viewer catalog, and exercises the production browser
loaders and converters. It records HTTP failures even if a loader swallows them.
Model checks detect default cylinders, empty position buffers and unresolved texture
slots in Viewer models, including the renderer's hidden/child traversal rules.

Results are written to `tmp/asset-audit/`:

- `asset-manifest.tsv`: source resource sizes and SHA-256 hashes.
- `corpus.tsv`, `object-occurrences.tsv`: corpus parsing status and every DAT object,
  with source, type, parameter, floor, section and zero-based object index.
- `browser.json`: each diagnostic case, outcome and actual network requests.
- `findings.tsv`: errors and unresolved texture references, including swallowed 404s.
- `corpus.log`, `browser-0.log` through `browser-2.log`: build and diagnostic logs.
  Browser tests run in three bounded batches; missing output or coverage fails the runner.
- `source/`: original-client object placements, SetData references, candidate PMT item
  pairs, BML layout comparisons and input hashes. Candidates do not alter production mappings.

**A successful diagnostic run means the inventory completed, not that assets are
complete.** Inspect `findings.tsv`, corpus errors and excluded cases in `browser.json`.
Texture-reference findings are candidates for analysis, not proof that a file must be
added: runtime colors/materials and incorrect index mappings can produce the same symptom.

Coverage limits:

- DAT object variants are inventoried; script-created objects and runtime parameter
  changes are not inferred. Source evidence scans 179 original raw object sets separately;
  these are not all proven reachable through the current client's selected SetData table.
- LittleCryotube 0–3 is explicitly covered; other unobserved client parameter ranges
  still require client evidence. Existing loader choices that ignore a variant are not
  automatically diagnosed by successful loading.
- Character meshes cover declared head/hair styles with body/skin/face 0. Texture
  selection covers every declared body and section ID with default skin/face/head;
  skin/face/head selection is also checked with body 0 and Viridia. This is not the
  Cartesian product of every appearance and does not certify hair/accessory bindings.
- NPC and all 572 character animation paths are parsed; motion/bone compatibility
  and actual playback are not checked.
- Standalone XVM files receive independent structural checks, including files whose
  model fails before a normal loader can request their textures. This is not pixel QA.
- Particle tables cover map IDs 0–46; animation/effect playback and visual equivalence
  to the game are not established by parsing.
- Editor models and areas are converted, but this harness does not inspect all their
  raw texture-slot references. Viewer raw models do receive that extra check.
- These are source-asset checks, not deployed-site checks or production archive checks.

The findings and remaining acceptance work are tracked in
[`docs/asset-completeness-audit.md`](../../docs/asset-completeness-audit.md) and
[`docs/asset-completeness-todo.md`](../../docs/asset-completeness-todo.md).
