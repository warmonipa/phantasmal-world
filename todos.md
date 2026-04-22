# Symbol Chat Feature TODOs

## Context

Quest Editor currently supports two mechanisms for placing symbol chats in quests:

1. **SymbolChatObject** (dat object type 0x21) — static entities placed in the map, referencing one of 24 built-in symbol chat presets from `symbolchatcolli.prs`. The editor provides a 2D HTML overlay (HUD) in the 3D viewport's bottom-right corner for previewing and picking presets.

2. **`set_symbol_chat_collision`** (opcode 0xF8A6, qedit calls it `symbol_chat_create`) — runtime ASM opcode that creates up to 3 lock-gated symbol chat triggers at a world position. Each trigger references a custom HEX data label containing face/sound/corners/parts data. The editor now auto-detects these via `DataLabelAnalysis` back-tracing `leti` writes to R+7..R+9.

### What's Done

- SEGA palette aligned with qedit source (`FSymbolChat.pas:83`)
- Alpha rendering aligned with qedit (`> 0x7FFFFFFF` threshold, no blending)
- Overlay moved inside 3D viewport container (`QuestRendererWidget`)
- Overlay only appears when a SymbolChatObject with valid SC data is selected
- Edit Symbol Chat dialog: viewport-centered, body scrollable, footer always visible, reparent to `<body>` on show
- `set_symbol_chat_collision` opcode signature corrected to 10-register set (4 floats + 6 ints)
- `DataLabelType.SymbolChatHexData` added with automatic detection from opcode back-trace
- Right-click context menu dynamically shows type-specific labels ("Edit NPC Data...", "Edit Symbol Chat...", etc.); hidden for untyped labels
- Test coverage: `SymbolChatAnalysisTest` (4 tests), `SymbolChatColliTableTest` (5 tests)
- Test fixture: `symbol_chat_test.qst` with 24 builtins + 8 custom variants + 2 trigger segments

---

## TODO 1: SymbolChatHexData Dialog (64-byte layout)

**Priority**: High

**Problem**: `set_symbol_chat_collision` references data labels whose layout differs from the 60-byte `SymbolChatT` used by `symbolchatcolli.prs`. The HEX layout (from qedit.info docs) starts with face type / face color / sound effect bytes, adding approximately 4 bytes of header before the standard SymbolChatT fields. Currently the editor reuses the 60-byte `SymbolChatDialog` for these labels, causing field offsets to be misaligned.

**Requirements**:
- Determine the exact byte layout of the HEX symbol chat format by examining real quest data or qedit's serialization code
- Either extend `SymbolChatDialog` to detect and handle both 60-byte and 64-byte layouts, or create a dedicated `SymbolChatHexDialog`
- Add a "Sound Effect" field to the dialog UI when editing HEX records
- Handle GC endian flip: the first 12 bytes need byte-swapping for GameCube version quests (BB/Xbox are little-endian, GC is big-endian). See qedit.info `Symbol_chat_create` NOTE#2 for the exact swap pattern

**Files involved**:
- `web/src/jsMain/kotlin/.../widgets/SymbolChatDialog.kt`
- `web/src/jsMain/kotlin/.../widgets/SymbolChatRenderer.kt` (renderBuffer may need an offset parameter)
- `web/src/jsMain/kotlin/.../asm/DataLabelAnalysis.kt` (SymbolChatHexData already defined)

---

## TODO 2: 3D Viewport Trigger Visualization

**Priority**: Medium

**Problem**: `set_symbol_chat_collision` places symbol chat triggers at specific world coordinates (R+0..R+2 = X/Y/Z) with a trigger radius (R+3). These positions are invisible in the 3D viewport — the user has no way to see where triggers will appear in the game world without running the quest.

**Requirements**:
- Extract X/Y/Z/radius from the `leti` sequence preceding each `set_symbol_chat_collision` call (same back-trace logic as `DataLabelAnalysis`, but also reading R+0..R+3 as float bits)
- Render each trigger as a semi-transparent circle/ring on the floor plane at the extracted position, with the ring radius matching R+3
- Use a distinct visual style (e.g., cyan ring with "SC" label) to differentiate from NPC/object markers
- Clicking a trigger marker in the viewport should select it and:
  - Scroll the ASM editor to the corresponding `set_symbol_chat_collision` instruction
  - Show the symbol chat preview overlay for the first (active) SC of that trigger
- Optionally show lock state labels (Lock 1/2/3 IDs) as tooltip on hover

**Files involved**:
- `web/src/jsMain/kotlin/.../rendering/QuestRenderer.kt` (or new `SymbolChatTriggerRenderer`)
- `web/src/jsMain/kotlin/.../asm/DataLabelAnalysis.kt` (extend to also return position/radius data)
- `web/src/jsMain/kotlin/.../stores/QuestEditorStore.kt` (new model for trigger markers)
- `web/src/jsMain/kotlin/.../controllers/EntityInfoController.kt` (overlay linkage)

---

## TODO 3: ASM Label Selection -> Overlay Preview Linkage

**Priority**: Medium

**Problem**: The floating overlay preview currently only activates when a `SymbolChatObject` entity is selected in the 3D viewport. When the user navigates to a `SymbolChatHexData` label in the ASM editor (e.g., clicks on label 9000), the overlay does not show a preview. This breaks the workflow for editing `set_symbol_chat_collision`-based symbol chats.

**Requirements**:
- When the cursor in the ASM editor moves to a line belonging to a `SymbolChatHexData` (or `SymbolChatData`) label, read the data segment's bytes and show the symbol chat preview in the overlay
- The overlay should switch between entity-driven (SymbolChatObject selected) and ASM-driven (cursor on SC data label) modes seamlessly — whichever was activated most recently wins
- When the cursor leaves the SC data label region, the overlay should hide (unless a SymbolChatObject is still selected)

**Files involved**:
- `web/src/jsMain/kotlin/.../controllers/EntityInfoController.kt` (symbolChatPreviewBuf / symbolChatPreviewHidden)
- `web/src/jsMain/kotlin/.../controllers/DataEditorController.kt` (expose "cursor is on SC label" + buffer)
- `web/src/jsMain/kotlin/.../widgets/SymbolChatOverlayWidget.kt`

---

## TODO 4: Symbol Chat Preview as 3D Billboard (Future Enhancement)

**Priority**: Low

**Problem**: The current symbol chat preview is a 2D HTML overlay (HUD) positioned at a fixed corner of the 3D viewport. It does not follow the SymbolChatObject's world position, does not respond to camera movement, and is not visually associated with the object in the scene.

**Requirements**:
- Render the symbol chat preview as a Three.js `Sprite` or `CSS2DObject` billboard attached to the SymbolChatObject's 3D world position
- The billboard should always face the camera (billboard behavior)
- Scale appropriately with camera distance (or use fixed screen-size with distance attenuation)
- Clicking the billboard should open the symbol chat picker (same as current overlay click)
- Consider performance: only render billboards for objects in the current area/section, lazy-create textures

**Files involved**:
- `web/src/jsMain/kotlin/.../rendering/QuestRenderer.kt`
- `web/src/jsMain/kotlin/.../widgets/SymbolChatRenderer.kt` (render to offscreen canvas -> texture)
- `web/src/jsMain/kotlin/.../widgets/SymbolChatOverlayWidget.kt` (may be replaced or demoted to fallback)
