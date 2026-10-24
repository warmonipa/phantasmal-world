# Asset completeness audit TODO

Status: pending. This tracks a separate audit; the PW4 terminal fix does not establish
that the rest of the asset collection is complete.

PW4 exposed a missing model variant: object `0x223` (`LittleCryotube`), parameter 3,
requested `objects/547-3.xj` while variants 0 and 1 and the shared texture were present.
See [rendering fidelity](rendering-fidelity.md#verification) for its source and regression.

## Inventory and evidence

- [ ] Inventory the actual resource paths requested by Quest Editor and Viewer loaders:
  object model variants and multipart models, NPCs and Ultimate skins, player parts,
  items, area variants, textures, and supported animation/effect resources.
- [ ] Compare loader-supported variants with the bundled quest/free-roam corpus and
  verified client parameter ranges. Include variants absent from the current corpus;
  do not assume a base model proves that every variant is available.
- [ ] Check `LittleCryotube` variant 2 first: the client supports model values 0–3,
  but the current collection has no `547-2.xj`. Establish the original mapping before
  adding an asset.
- [ ] Reassess `UnavailableModel` and `Unverified` classifications with original-resource
  evidence. Keep invisible logic, intentional editor markers, and unsupported runtime
  effects distinct from accidentally missing resources.
- [ ] Record each gap with entry point, object/type and parameters, reproducing quest or
  map, requested path, original resource/version, and status (missing file, wrong mapping,
  parser/conversion failure, or deliberately unsupported behavior).

## Repair and acceptance

- [ ] Extract confirmed missing assets from original resources; record archive entries and
  hashes. Reuse shared textures only after verifying their texture-index mapping.
- [ ] Exercise the real loaders and converters: check nonempty geometry, valid texture
  references and decoding, multipart completeness, and representative variant combinations.
  Inspect rendered output against original-resource previews or game references.
- [ ] Add regressions that fail for each confirmed missing-resource mechanism. Check both
  source assets and the production distribution; do not hide gaps with substitute models,
  silent fallbacks, or reclassification as invisible objects.
- [ ] Update the inventory and rendering documentation as fixes land. Run relevant tests
  and the production build; report unresolved gaps explicitly before closing this TODO.

The audit is complete when every supported resource path has verified assets and loading
coverage, or an explicit, evidence-backed unsupported boundary. File existence alone is
not sufficient.
