# PSO Compatibility Check Implementation Progress Report

This document tracks the implementation progress of the compatibility check feature, based on the
`PSO_COMPATIBILITY_CHECK_LOGIC.md` specification.

---

## Overall Architecture

| Component                   | Status            | File Location                                      |
|-----------------------------|-------------------|----------------------------------------------------|
| PSOVersion enum             | ✅ Complete        | `psolib/.../compatibility/PSOVersion.kt`           |
| CompatibilityResult         | ✅ Complete        | `psolib/.../compatibility/CompatibilityResult.kt`  |
| ProblemType enum            | ✅ Complete        | `psolib/.../compatibility/CompatibilityResult.kt`  |
| ProblemLocation             | ✅ Complete        | `psolib/.../compatibility/CompatibilityResult.kt`  |
| FloorDataProvider interface | ⚠️ Framework only | `psolib/.../compatibility/FloorDataProvider.kt`    |
| CompatibilityChecker        | ⚠️ Partial        | `psolib/.../compatibility/CompatibilityChecker.kt` |

---

## Script Checks (Section 4.1-4.3)

| Check Item                       | Guide Section | Status            | Notes                                           |
|----------------------------------|---------------|-------------------|-------------------------------------------------|
| Label 0 existence                | 4.1           | ✅ Complete        | Error: MISSING_LABEL_0                          |
| Conversion instruction warning   | 4.2.1         | ✅ Complete        | CONVERT_OPCODES set complete                    |
| set_episode parameter check      | 4.2.2         | ✅ Complete        | V1 only Ep1, PC no Ep4                          |
| Opcode version check             | 4.2.3         | ⚠️ Partial        | Only checks `bb_*` prefix, not using opcode.ver |
| Special opcode warning (0xF8EE)  | 4.2.4         | ✅ Complete        | SPECIAL_WARNING_OPCODE                          |
| Parameter type matching (T_ARGS) | 4.3.1         | ❌ Not implemented | T_REG vs T_DWORD check                          |
| Label reference check            | 4.3.2         | ✅ Complete        | LabelType parameter check                       |
| Switch statement check           | 4.3.3         | ❌ Not implemented | Array entry count validation                    |
| String marker check              | 4.3.4         | ❌ Not implemented | `<>` matching check                             |

---

## NPC/Monster Checks (Section 4.4-4.6)

| Check Item                  | Guide Section | Status       | Notes                                       |
|-----------------------------|---------------|--------------|---------------------------------------------|
| NPC action label validation | 4.4           | ✅ Complete   | DefaultLabels definition complete           |
| Skin 51 special check       | 4.5           | ✅ Complete   | V1 warning, Ep2 warning, subtype validation |
| Floor monster compatibility | 4.6           | ⚠️ Framework | FloorDataProvider returns null              |

---

## Object Checks (Section 4.7)

| Check Item                 | Guide Section | Status       | Notes                          |
|----------------------------|---------------|--------------|--------------------------------|
| Floor object compatibility | 4.7           | ⚠️ Framework | FloorDataProvider returns null |

---

## Quantity Limit Checks (Section 4.8)

| Check Item          | Guide Section | Status     | Notes             |
|---------------------|---------------|------------|-------------------|
| Monster count > 400 | 4.8           | ✅ Complete | TOO_MANY_MONSTERS |
| Object count > 400  | 4.8           | ✅ Complete | TOO_MANY_OBJECTS  |

---

## Data Label Checks (Section 4.9)

| Check Item         | Guide Section | Status            | Notes                  |
|--------------------|---------------|-------------------|------------------------|
| Unused data labels | 4.9           | ❌ Not implemented | GetReferenceType logic |

---

## File Checks (Section 4.10)

| Check Item            | Guide Section | Status            | Notes               |
|-----------------------|---------------|-------------------|---------------------|
| .bin file required    | 4.10          | ❌ Not implemented |                     |
| .dat file recommended | 4.10          | ❌ Not implemented |                     |
| .pvr file restriction | 4.10          | ❌ Not implemented | ver>1 not supported |

---

## Web UI Layer

| Component               | Status     | File                                             |
|-------------------------|------------|--------------------------------------------------|
| CompatibilityController | ✅ Complete | `web/.../controllers/CompatibilityController.kt` |
| CompatibilityDialog     | ✅ Complete | `web/.../widgets/CompatibilityDialog.kt`         |
| CompatibilityWidget     | ✅ Complete | `web/.../widgets/CompatibilityWidget.kt`         |

---

## Test Coverage

| Test Item             | Status | File                           |
|-----------------------|--------|--------------------------------|
| Label 0 check         | ✅      | `CompatibilityCheckerTests.kt` |
| Episode compatibility | ✅      | `CompatibilityCheckerTests.kt` |
| Quantity limits       | ✅      | `CompatibilityCheckerTests.kt` |
| NPC label check       | ✅      | `CompatibilityCheckerTests.kt` |
| BB opcode check       | ✅      | `CompatibilityCheckerTests.kt` |
| Label reference check | ✅      | `CompatibilityCheckerTests.kt` |

---

## Completion Statistics

| Category           | Completed/Total | Percentage              |
|--------------------|-----------------|-------------------------|
| Script checks      | 5/9             | **56%**                 |
| NPC/Monster checks | 2/3             | **67%**                 |
| Object checks      | 0/1             | **0%** (framework only) |
| Quantity limits    | 2/2             | **100%**                |
| Data label checks  | 0/1             | **0%**                  |
| File checks        | 0/3             | **0%**                  |
| **Total**          | **9/19**        | **~47%**                |

---

## To-Do List

### High Priority

1. **Complete opcode version check**
   - Location: `CompatibilityChecker.kt:checkInstruction()`
   - Needed: Use `opcode.ver` property instead of just checking `bb_` prefix
   - Reference: Guide 4.2.3

2. **FloorDataProvider full implementation**
   - Location: Create new `FloorDataProviderImpl.kt`
   - Needed: Parse FloorSet.ini data
   - Affects: Floor monster compatibility (4.6) and Floor object compatibility (4.7)

### Medium Priority

3. **Parameter type matching check**
   - Location: `CompatibilityChecker.kt:checkInstruction()`
   - Needed: Check T_REG vs T_DWORD parameter types
   - Reference: Guide 4.3.1

4. **Switch statement check**
   - Location: `CompatibilityChecker.kt:checkInstruction()`
   - Needed: Validate switch array entry count
   - Reference: Guide 4.3.3

5. **String marker check**
   - Location: `CompatibilityChecker.kt:checkInstruction()`
   - Needed: Check `<>` marker matching
   - Reference: Guide 4.3.4

### Low Priority

6. **Unused data label check**
   - Location: `CompatibilityChecker.kt` new method
   - Needed: Implement GetReferenceType logic
   - Reference: Guide 4.9, 5.1

7. **File check**
   - Location: `CompatibilityChecker.kt` new method
   - Needed: Check .bin/.dat/.pvr files
   - Reference: Guide 4.10
   - Note: Requires Quest object to contain file list information

---

## Known Issues

1. **bb_map_designate only shows one error**
   - Symptom: Quest has 4 bb_map_designate, but only 1 error is shown
   - Possible cause: `textModel` is null or `assemble` fails and falls back to `quest.bytecodeIr`
   - Needs investigation: bytecodeIr retrieval logic in `CompatibilityController.checkAllVersions()`

---

## Changelog

| Date       | Update Content                                                                            |
|------------|-------------------------------------------------------------------------------------------|
| 2026-01-18 | Initial completion assessment                                                             |
| 2026-01-18 | Fix: NPC label check logic - extended labels from BB-only to GC-only (matches guide spec) |
| 2026-01-18 | Fix: BB version uses DC V2 check rules (effectiveVersion=1)                               |
| 2026-01-18 | Fix: Script definition check for all non-default labels                                   |
| 2026-01-18 | Rename: EP1_BB_EXTRA/EP2_BB_EXTRA -> EP1_GC_EXTRA/EP2_GC_EXTRA                            |