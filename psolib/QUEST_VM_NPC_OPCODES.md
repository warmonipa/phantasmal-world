# Quest VM NPC Opcodes (V2, V3, and V4)

This document describes the NPC-related quest opcodes used by Dreamcast/PC V2, GameCube V3, and
Blue Burst V4. Binary layouts and shared protocol facts come from newserv and exact deployed quest
files. Behavioral claims that are specific to V4 come from the exact BB client and are marked as
such; they must not be projected backward onto V2 or V3.

The primary V2/V3 evidence is newserv's versioned opcode table plus newserv's stock compressed BIN
files, independently disassembled with the matching `--dc-v2`, `--pc`, or `--gc` mode. The primary
V4 behavioral evidence is the exact BB client implementation. QEdit provides names and secondary
documentation.

## Evidence and scope

The client used for this analysis was:

- Executable: `/Users/wangzhen/study/bb-psov4/clients/ephinea/psobb.exe`
- SHA-256: `f4d4bd463c07fec2542452735deb5237641634100d9223d2d0f0ae4000315cc0`
- Ghidra archive: `/Users/wangzhen/study/bb-psov4/clients/ephinea/Psobb.exe-06-16-2026.gzf`

Supporting sources:

- `/Users/wangzhen/study/newserv/src/QuestScript.cc`
- `/Users/wangzhen/study/newserv/src/CommandFormats.hh`
- `/Users/wangzhen/study/original-psobb-client-source/src/Psobb.exe-05112026.c`
- [QEdit opcode index](https://qedit.info/index.php?title=OPCodes)
- Exact V4 Side Story QST files under
  `web/assets-generation/src/main/resources/ephinea/ship-config/quest`
- Exact V2/V3 golden BIN/DAT pairs under
  `psolib/src/commonTest/resources/quests/npc-opcodes-v2-v3`

## Versioned instruction encoding

V2 uses inline operands. V3 and V4 use the argument stack for opcodes marked `F_ARGS`, but these
six positioned creation opcodes still encode one base-register operand inline. All multi-byte
values are little-endian, including GameCube scripts.

| Opcode | V2 base register | V2 trailing operand | V3/V4 base register |
| --- | ---: | ---: | ---: |
| `0x66 npc_crp` | 1 byte | ignored `u32` | 1 byte |
| `0x79 npc_talk_pl` | `u32` | none | 1 byte |
| `0x7C npc_crppk` | `u32` | ignored `u32` | 1 byte |
| `0x7D npc_crptalk` | `u32` | ignored `u32` | 1 byte |
| `0x7F npc_crp_id` | `u32` | ignored `u32` | 1 byte |
| `0xCE npc_crptalk_id` | `u32` | ignored `u32` | 1 byte |

`npc_crp` is the important exception. For example, the V2 bytes `66 3C 00 00 00 06` mean base
register `r60` followed by ignored value `0x06000000`; they are not a 32-bit register operand.
Conversely, `7D 3C 00 00 00 06 00 00 00` is a 32-bit base register (`r60`) followed by ignored
value `6`.

The register groups have the same position/angle/template structure across these versions. The
control fields must remain version-aware: newserv describes V2/V3 network creation field 5 as an
initial state, while the examined V4 receiver proves that the corresponding network field is the
owner/follow player slot. Similarly, V4 `npc_talk_pl` uses its eighth register as a controller
token, whereas the V2/V3 description calls it a client ID.

The legacy `npc_crp` and `npc_crptalk` forms use implicit client/NPC slot `1`; their V4 forms use
dynamic network slot selection and fixed local slot `3`, respectively. Script angle registers are
degrees. The client converts them to its 16-bit full-turn rotation representation when constructing
the NPC (`90` degrees becomes `0x4000`).

The QEdit names ending in `_V3` are retained below where they identify established source syntax.
The detailed runtime discussion in the following sections is specifically V4 behavior.

## Runtime model

Script-created friendly NPCs are player-like entities occupying player/entity slots. They are not
ordinary enemy records from the quest DAT. The V4 VM has three distinct creation paths:

1. Networked follower NPCs are created by sending subcommand `6x69`, command `0`.
2. Networked attacker NPCs are created by sending subcommand `6x69`, command `3`.
3. Local talk NPCs are constructed directly by each client and do not use `6x69`.

This distinction is important for an editor or VM implementation. Supporting only one creation
path is not sufficient to display all scripted NPCs.

### The V4 `6x69` NPC control packet

The receiving BB client interprets the packet fields as follows:

| Offset | Type | Meaning |
| --- | --- | --- |
| `+0x04` | `u16` | Owner/follow player slot for commands `0`, `2`, and `3` |
| `+0x06` | `u16` | NPC entity/player slot for commands `0`, `1`, and `3` |
| `+0x08` | `u16` | Command |
| `+0x0A` | `u16` | NPC template index for commands `0` and `3` |

Commands:

| Command | Meaning |
| --- | --- |
| `0` | Create follower NPC |
| `1` | Stop the specified NPC |
| `2` | Resume NPC activity and follow the specified owner slot |
| `3` | Create attacker NPC |

The field at `+0x04` is frequently described as an initial alive/dead state. That description is
incorrect for these networked creation opcodes in the examined V4 client. The receiver uses it as
the owner/controller player slot and stores it as the NPC's follow owner.

The built-in template table contains 64 entries, indexed `0x00` through `0x3F`. Examples include
Elenor at `0x0F` and Dacci/Ducci at `0x1B`.

## Networked follower and attacker NPCs

### `0x60 npc_crt_V3`

```text
npc_crt_V3 owner_slot, template_index
```

Creates a networked follower NPC without an explicit position and sends `6x69` command `0`.
`owner_slot` is not an alive/dead state.

When the relevant multiplayer flag is clear, the client searches slots `0..3` for the first empty
slot. Otherwise it uses slot `1`. Therefore the common description "always creates client ID 1"
is only reliable for typical solo quest conditions, not as a general V4 semantic.

### `0x66 npc_crp_V3`

```text
npc_crp_V3 x, y, z, angle, owner_slot, template_index
```

Creates a networked follower NPC at the supplied position and sends `6x69` command `0`. Slot
selection follows the same rules as `npc_crt_V3`.

The fifth register is the owner/controller player slot. Existing QEdit and inherited project
documentation that call it `initial state` are incorrect for V4.

### `0x7F npc_crp_id_v3`

```text
npc_crp_id_v3 x, y, z, angle, owner_slot, npc_slot, template_index
```

The explicit-slot form of `npc_crp_V3`. It creates a networked follower NPC in `npc_slot` and
assigns `owner_slot` as its follow owner.

### `0x7B npc_crtpk_V3`

```text
npc_crtpk_V3 owner_slot, template_index
```

Creates a networked attacker NPC using `6x69` command `3`. The examined V4 handler selects NPC
slot `1`.

### `0x7C npc_crppk_V3`

```text
npc_crppk_V3 x, y, z, angle, owner_slot, template_index, npc_slot
```

Creates a networked attacker NPC at an explicit position and in an explicit slot. The fifth
register is the owner/controller slot, not an initial state.

### `0x61 npc_stop`

```text
npc_stop npc_slot
```

Sends `6x69` command `1`, stops the NPC in the specified slot, and clears its follow owner.

### `0x62 npc_play`

```text
npc_play owner_slot
```

Sends `6x69` command `2`. It resumes the applicable challenge-style NPC entities and makes them
follow `owner_slot`. The argument is an owner player slot, not an NPC ID. The common value `0`
means "follow player slot 0."

### `0x63 npc_kill`

```text
npc_kill npc_slot
```

Destroys the player-like NPC in the specified slot and clears the corresponding global tracking
state.

## Local talk NPCs

### `0x7D npc_crptalk_V3`

```text
npc_crptalk_V3 x, y, z, angle, state, template_index
```

Constructs a local type-6 player NPC directly, without sending `6x69`. The V4 handler uses slot
`3`.

`state == 1` installs a state object whose updater runs the dead/incapacitated input-state path and
sets the associated `0x8000` flag. No equivalent special branch for `state == 2` was found in the
examined V4 handler. The QEdit description of state `2` as an invisible named text box is therefore
not established for BB V4.

### `0xCE npc_crptalk_id_V3`

```text
npc_crptalk_id_V3 x, y, z, angle, state, template_index, npc_slot
```

The explicit-slot local talk NPC form. It otherwise follows the same construction and state rules
as `npc_crptalk_V3`.

### `0x79 npc_talk_pl_V3`

```text
npc_talk_pl_V3 x, y, z, visibility_radius, angle, template_index, state,
               controller_token
```

Creates a proximity controller rather than immediately creating an NPC. When the local player is
inside the radius, the controller creates a local type-6 player NPC in slot `3`. When the player
leaves the radius, it removes that NPC.

The eighth register is a controller selector/deletion token. It is not the spawned NPC's client
ID. This differs from the current QEdit and newserv descriptions. `state == 1` has the same special
dead/incapacitated handling described for `npc_crptalk_V3`.

### `0x7A npc_talk_kill`

```text
npc_talk_kill controller_token
```

Requests deletion of `npc_talk_pl_V3` controllers matching the token. The opcode writes a single
global selector. If it is executed multiple times during one frame, later calls overwrite earlier
ones, so only the final token is observed by controller updates.

## NPC behavior parameters

### `0xDF npc_param_V3`

```text
npc_param_V3 r0-r13, template_index
```

Writes a 40-byte behavior/stat record for one NPC template. The 14 input registers are packed as:

| Register | Stored form | Current interpretation |
| --- | --- | --- |
| `r0` | `u16` | NPC ID/unknown field |
| `r1` | `u16` | Base level before difficulty adjustment |
| `r2` | converted `u32` | Technique/behavior flags selector |
| `r3` | `float` | Enemy lock-on range |
| `r4` | `float` | Unknown range/value |
| `r5` | `float` | Maximum distance from owner |
| `r6` | `float` | Enemy unlock range |
| `r7` | `float` | Block range |
| `r8` | `float` | Attack range |
| `r9` | `u8` | Attack technique level |
| `r10` | `u8` | Support technique level |
| `r11` | `u8` | Attack probability |
| `r12` | `u8` | Attack-technique probability |
| `r13` | `float` | Additional distance/backoff parameter |

The V4 `r2` conversion is:

| Input | Stored flags |
| --- | --- |
| `0` | `0x40` |
| `1` | `0x04` |
| `2` | `0x08` |
| `3` | `0x10` |
| `10` | `0xC0` |
| `11` | `0x84` |
| `12` | `0x88` |
| `13` | `0x90` |

Any other selector leaves the local flags variable uninitialized before it is copied into the
record. In particular, input `3` maps to `0x10`; documentation that groups `3` with input `10` as
`0xC0` is incorrect for the examined V4 client.

The behavior names in the table are based on downstream use and existing quest documentation.
Fields still marked unknown should not be promoted to stable API names without further consumer
analysis.

## Custom NPC character data

### `0xF841 get_npc_data`

```text
get_npc_data data_label
```

Copies `0x70` bytes from the quest BIN data label into a staged character-data buffer.

### `0xF840 load_npc_data`

```text
load_npc_data
```

Enables one-shot use of the staged character data. The next applicable NPC creation consumes the
record and clears the flag.

Both operations must complete before the creation. Their relative order is not significant because
one writes the staged buffer and the other writes the consumption flag; neither consumes the other.
Deployed V4 Side Story scripts commonly use:

```text
load_npc_data
get_npc_data custom_character_record
npc_crp_id_v3 ...
```

The opposite order is also valid provided no applicable NPC creation occurs between the two setup
instructions.

The current `NpcVisualConfig` model in this repository is `0x50` bytes, but the V4 handler copies
`0x70` bytes. The model must not be treated as a complete representation of the VM record until
the remaining `0x20` bytes and their consumers are documented.

## Automatic NPC speech

### `0x64 npc_nont` and `0x65 npc_talk`

These opcodes set and clear bit `2` of a global NPC-language flag. Consumers of that bit gate
automatic contextual NPC chat bubbles.

They do not create or destroy NPCs and do not generally enable or disable entity collision. The
names are better understood as "automatic NPC talk off/on."

### `0xC1 npc_text`

```text
npc_text situation, text
```

Registers a global string for an NPC situation. The V4 handler accepts situation IDs through
`0x18` and copies at most `0x34` UTF-16 code units (52 characters).

### `0xF8DC npc_action_string`

```text
npc_action_string npc_slot_register, situation_register, string_data_label
```

Registers a situation string for a specific player/NPC slot. The slot must be below `4`. The
client maintains up to six situation-to-string assignments per slot.

### `0xCF npc_lang_clean`

Clears the 25 global NPC message slots and their associated state.

## NPC and party spatial controllers

These opcodes create asynchronous controller objects. They do not synchronously call the target
label at the point where the opcode executes.

### `0x8E col_npcin`

```text
col_npcin x, y, z, radius, callback_label
```

Starts the callback when an applicable network/challenge NPC enters the three-dimensional radius.
The V4 predicate scans entity/player indices `0..11`, requires the entity's relevant type flag to
equal `3`, and then checks its distance from the center.

This is not a generic "any NPC in radius" test. Local `npc_crptalk` entities do not automatically
satisfy the same predicate.

### `0x8F col_npcinr`

```text
col_npcinr x, y, z, event_radius, party_radius, callback_label
```

Despite the NPC-like name, this is a party-proximity controller. The local player must be inside
`event_radius`, and another entity/player must be within `party_radius` of the local player.

### `0x97 col_plinaw`

```text
col_plinaw check_x, check_y, check_z, check_radius, callback_label,
           allowed_player_distance, warp_x, warp_y, warp_z
```

Checks fixed NPC/player slot `1`. When the local player enters the check radius and slot `1` is
farther away than the allowed distance, the controller saves the warp destination and starts the
callback label.

### `0xC2 npc_chkwarp`

Warps the slot-1 NPC to the destination most recently staged by `col_plinaw`.

### Extended forms

| Opcode | Name | V4 behavior |
| --- | --- | --- |
| `0xF8E9` | `npc_coords_call_ex` / `walk_to_coord_call_ex` | Extended `col_npcin`; also returns the controller object ID |
| `0xF8EA` | `col_npcinr_ex` | Extended party-proximity controller |
| `0xF8EC` | `npc_check_straggle_ex` / `col_plinaw_ex` | Extended straggle controller |

## General player-slot opcodes that can affect NPCs

Because these NPCs occupy player/entity slots, several general player opcodes can also affect
them. They are not NPC creation opcodes and should be implemented in the general entity/player
layer:

| Opcode | Name | Relevant effect |
| --- | --- | --- |
| `0x6D` | `p_move_V3` | Move a player-like entity |
| `0x6E` | `p_look` | Make an entity look toward another player-like entity |
| `0x72` / `0x73` | `disable_movement1` / `enable_movement1` | Toggle movement for a slot |
| `0x76` | `p_setpos` | Set a slot's starting position |
| `0x7E` | `p_look_at` | Make one slot face another; sends `6x3E` |
| `0xF8E3` / `0xF8E4` | `restore_hp` / `restore_tp` | Restore a slot's HP or TP |
| `0xF8E5` | `close_chat_bubble` | Close a slot's open chat bubble |
| `0xF8ED` | `animation_check` | Test selected animations for a slot |

Support for these instructions alone does not create a scripted NPC. They operate on an entity
that must already exist through one of the creation paths above.

## V4 Side Story validation

The opcode model was checked against the exact V4 Side Story quest corpus used by the project: 26
Episode 1 quests, two Episode 2 quests, and six Episode 4 quests. Representative high-value cases
are listed below.

| Quest | Observed NPC mechanisms |
| --- | --- |
| Magnitude of Metal | `npc_crptalk_V3` for Pioneer 2 talk NPCs; `npc_crp_V3`, `npc_stop`, `npc_play`, and `npc_kill` for the companion |
| Gran Squall | `npc_talk_pl_V3` proximity-controlled local NPCs plus follower controls |
| The Retired Hunter | `npc_param_V3`, `npc_crp_id_v3`, `col_plinaw`, and `npc_chkwarp` for Donoph |
| Soul of Steel | Extensive explicit-slot follower NPCs, attacker NPCs, custom character data, and party proximity |
| From the Depths | Follower, attacker, and explicit-slot local talk NPC paths |
| Central Dome Fire Swirl | `get_npc_data` / `load_npc_data` custom appearances and multiple creation paths |
| Seat of the Heart | Extensive explicit-slot NPC control, custom appearances, and behavior parameters |
| Episode 4 Side Story | Explicit-slot followers/attackers, custom appearances, and `npc_param_V3` are used throughout the NPC-heavy quests |

### Magnitude of Metal example

Magnitude of Metal demonstrates why a renderer must distinguish local talk NPCs from networked
followers.

The Pioneer 2 NPC using template `0x1B` (Dacci/Ducci) is created with:

```text
npc_crptalk_V3 x, y, z, angle, 0, 0x1B
```

This is a local talk NPC in slot `3`.

The forest companion uses template `0x0F` (Elenor):

```text
npc_crp_V3 x, y, z, angle, 0, 0x0F
npc_stop 1
...
npc_play 0
...
npc_kill 1
```

This is a networked follower NPC controlled through `6x69`. If Dacci/Ducci is missing but Elenor
appears, the missing implementation is likely the local `npc_crptalk_V3` path. If Elenor is
missing, the `6x69` follower path is likely absent.

## Known documentation and project-model discrepancies

| Subject | Existing description | V4 client evidence |
| --- | --- | --- |
| `npc_crt` / `npc_crp` fifth value | Initial alive/dead state | Owner/controller player slot |
| `npc_crp_id` fifth value | Initial state | Owner/controller player slot |
| `npc_crppk` fifth value | Initial state | Owner/controller player slot |
| `npc_play` argument | NPC/client ID | Owner player slot to follow |
| `npc_talk_pl` eighth value | Spawned client ID | Proximity-controller token |
| `npc_crptalk` slot | Often documented as client ID 1 | Fixed local slot 3 in examined V4 handler |
| Talk-NPC state `2` | Invisible named text box | Not established by a special V4 handler branch |
| `npc_param` selector `3` | Sometimes documented as `0xC0` | Maps to `0x10` |
| Custom visual record | `0x50` bytes in current project model | V4 VM copies `0x70` bytes |
| Custom visual sequence | Often documented as one mandatory order | Either setup order works; both must precede the consuming creation |
| `col_npcin` | Any NPC enters radius | Only the network/challenge NPC class accepted by the V4 predicate |
| `npc_nont` / `npc_talk` | General ability to talk to NPCs | Gates automatic contextual NPC bubbles |

## Phantasmal World editor support

Phantasmal World now discovers positioned V2, V3, and V4 script NPCs with the same reachable-script and
floor-sensitive analysis used for script particle emitters. The editor resolves constant register
arguments for `npc_crp_V3`, `npc_crppk_V3`, `npc_crptalk_V3`, `npc_crp_id_v3`,
`npc_crptalk_id_V3`, and `npc_talk_pl_V3`, maps the stock `npcplayerchar.dat` template index to its
player class, and renders a read-only NPC preview on each reachable floor. Script positions are
already map/world coordinates and therefore do not receive DAT section transforms. All supported
episodes use the client's same 18 logical floor slots (`0` through `17`); episode map IDs are not
logical floor IDs.

The creation opcodes do not contain a dialogue label. The editor derives interaction navigation
separately from reachable spatial trigger opcodes: `set_obj_param` / `set_obj_param_ex` contribute
a **Target** label, and `at_coords_talk` / `at_coords_talk_ex` contribute a **Talk** label. A label
is attached to a preview only when the trigger sphere contains the NPC creation position and the
two instructions can execute on at least one common logical floor. A selected preview shows every
matching label and can jump to it in the assembly editor. If no trigger matches, the panel states
`None`; it does not invent a label from the NPC template or creation opcode.

This feature's product boundary is intentionally a static creation-position preview, not a
complete runtime VM simulation. Each reachable creation site remains visible at the position and
angle supplied to its creation opcode:

- `npc_crt_V3` and `npc_crtpk_V3` have no explicit position and are not rendered.
- Creations whose position, angle, or template cannot be resolved to one constant are not rendered;
  runtime-only control fields may remain unknown.
- `npc_talk_pl_V3` is shown at its possible spawn location; player-radius state is not simulated.
- Slot lifetime and movement opcodes such as `npc_stop`, `npc_play`, `npc_kill`, and `p_move_V3`
  do not follow, move, stop, or remove a preview.
- One-shot custom character data from `get_npc_data` / `load_npc_data` is not applied. Such NPCs
  currently use the stock class model for their template index.
- Behavior parameters, talk state, and attacker/follower AI are documented but not simulated.

The preview is selectable for inspection and script navigation, but every edit boundary remains
disabled or rejects it: transform handles, coordinate/type/wave/property inputs, deletion, and DAT
serialization. It is derived from bytecode and is never added to the quest's DAT NPC collection.
When bytecode or floor analysis is recomputed, selection and hover state move to an equivalent
replacement preview. If the selected creation site no longer resolves to the same preview, the
editor clears that derived selection instead of retaining a detached model and stale property data.

Only instructions present in the parsed, client-reachable IR are previewed. Pre-BB quests can use
`load_npc_data` / `get_npc_data` with script-like bytes stored in data labels; those dynamically
consumed records are not ordinary VM instruction segments and are currently outside the preview.

Within those boundaries, the editor covers the positioned stock-template creation paths needed to
show Dacci/Ducci and Elenor in Magnitude of Metal and corresponding static NPC locations in V2 and
V3 quests.

### Side Story golden corpus

The generic analysis is regression-tested against all 34 deployed Side Story QST resources under
`web/assets-generation/src/main/resources/ephinea/ship-config/quest`. The checked-in golden file is
`web/assets-generation/src/test/resources/golden/v4-side-story-script-npcs.tsv`.

Each golden row records the source resource and SHA-256, quest name, exact creation opcode,
creation kind, position, angle, stock template identity, resolved runtime control fields,
client-reachable logical floors, and associated Target/Talk labels. Quests with no resolved
positioned creation have an explicit `NONE` row, so dropping a whole quest from the corpus is
detectable.

The corpus currently contains 231 raw positioned creation instructions. These totals were counted
independently with newserv's BB/QEdit disassembler for the 33 QSTs it accepts and are asserted by
the golden generator. Ephinea's `quest3_e.qst` contains duplicate chunks that newserv rejects;
psolib applies its established last-chunk behavior and finds no positioned NPC creation in it.

| Opcode | Raw instructions | Resolved creation sites |
| --- | ---: | ---: |
| `npc_crp_V3` | 14 | 14 |
| `npc_crppk_V3` | 35 | 35 |
| `npc_crptalk_V3` | 15 | 15 |
| `npc_crp_id_v3` | 97 | 76 |
| `npc_crptalk_id_V3` | 67 | 67 |
| `npc_talk_pl_V3` | 3 | 2 |
| **Total** | **231** | **209** |

The difference consists of bytecode that is unreachable from a verified client entry point and
creations whose position/angle/template is runtime-dependent. Separate creation instructions
are preserved even when their resolved preview values are identical. Runtime owner, NPC-slot,
state, radius, and controller-token values may remain unresolved without suppressing a preview
because they are not required to place and select its model.

Run `:web:assets-generation:verifySideStoryNpcGolden` to verify the corpus. To intentionally update
the oracle, run `:web:assets-generation:generateSideStoryNpcGolden` with a temporary output path,
review the complete TSV diff and relevant newserv disassembly, then replace the checked-in file.

### V2/V3 golden fixtures

Two minimal stock newserv quests exercise the versioned parser and end-to-end preview:

| Version | File | SHA-256 | Independent newserv evidence | Resolved preview |
| --- | --- | --- | --- | --- |
| DC V2 | `q051-dc-e.bin` | `532c4aa14bab32c9f5dade88e8852689281807d62b97fb55c20351beab1088fc` | four creation opcodes in the full disassembly; reachable IR includes two `npc_crptalk` instructions with 32-bit register operands | DORONBO (`0x1D`) at `(364, 19, 440)`, floor 2 |
| PC V2 | `q051-pc-e.bin` | `e2cc01189aed5bf519cc6a73b805e8a2630e9f37ba9102f997e01aaa13afdf97` | four creation opcodes in the full disassembly; same V2 operand layout in a UTF-16 BIN | DORONBO (`0x1D`) at `(364, 19, 440)`, floor 2 |
| GC V3 | `q082-gc-e.bin` | `99c7195c8e7387e8470f327fe66f42e87d4f94834bf770e049fb0f9c9cecf158` | one `npc_crp_V3` | ALICIA (`0x10`) at `(246, 0, 357)`, floor 0 |

The tests assert the exact V2 operand widths, decoded inline operands, complete resolved spawn
records, the shared 18-slot logical-floor limit, and full BIN/DAT write-and-reparse preservation.
Synthetic byte-exact vectors additionally parse and write all six positioned creation opcodes on
DC V2, PC V2, GC V3, and BB V4. Semantic tests cover every creation kind and its version-dependent
control fields, all 64 template records, unsupported versions, invalid register ranges, unresolved
required values, unknown templates, entity entry points, cleared handlers, floor-scoped threads,
the Episode I/II/IV floor boundaries, and cross-version Target/Talk trigger association. Web tests
cover all nine rendered player classes,
per-floor expansion and episode mapping, degree-to-client-angle conversion, reactive V2/V3/V4
bytecode edits, selectable-but-non-manipulable previews, read-only mutation boundaries, interaction
label navigation, and exclusion of previews from DAT NPC output.

The V4 Side Story TSV remains the broad corpus; these fixtures and vectors are cross-version
sentinels for dialect correctness.

## Exact client handlers

The principal handler addresses in the analyzed executable are included for reproducibility:

| Address | Handler |
| --- | --- |
| `0x006B25F8` | `opcode_npc_crt_V3` |
| `0x006B2640` | `opcode_npc_param_V3` |
| `0x006B2768` | `opcode_npc_crtpk_V3` |
| `0x006B27B4` | `opcode_npc_crppk_V3` |
| `0x006B2868` | `opcode_npc_crptalk_v3` |
| `0x006B29F4` | `opcode_npc_crptalk_id_V3` |
| `0x006B2B90` | `opcode_npc_crp_V3` |
| `0x006B2C38` | `opcode_npc_crp_id_V3` |
| `0x006B2CEC` | `opcode_npc_play` |
| `0x006B2D58` | `opcode_npc_kill` |
| `0x006B2D68` | `opcode_npc_nont` |
| `0x006B2D70` | `opcode_npc_talk` |
| `0x006B3528` | `opcode_col_npcin` |
| `0x006B368C` | `opcode_col_plinaw` |
| `0x006B3744` | `opcode_npc_chkwarp` |
| `0x006B3898` | `opcode_npc_text` |
| `0x006B3918` | `opcode_npc_lang_clean` |
| `0x006B3954` | `opcode_npc_talk_pl_V3` |
| `0x006B39E0` | `opcode_npc_talk_kill` |
| `0x006B5014` | `opcode_get_npc_data` |
| `0x006B7688` | `opcode_NPC_action_string` |
| `0x006B781C` | `opcode_walk_to_coord_call_ex` |
| `0x006B79C4` | `opcode_col_plinaw_ex` |
