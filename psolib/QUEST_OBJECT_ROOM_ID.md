# Obj Room ID parameters

`Obj Room ID` is DAT object type `0x000E`, named `TObjRoomId` in newserv.
It is an invisible trigger that updates the **player's current room ID**
according to proximity and the player's position relative to an oriented
divider. It does not move the player, resize a room, or reveal a minimap mesh.

## Parameter contract

Offsets are relative to the start of the DAT object record. Each field below
occupies four bytes. UI labels describe newserv's comments; they are not
original Sega member names.

| Parameter | Offset | Disk type | Editor label | Meaning |
| --- | --- | --- | --- | --- |
| `param1` | `0x28` / 40 | F32 | `Radius / 10` | Formerly `SCL_TAMA`; active radius = value × 10 |
| `param2` | `0x2C` / 44 | F32 | `Positive-side room ID` | Room selected when the position dot product is positive |
| `param3` | `0x30` / 48 | F32 | `Nonpositive-side room ID` | Room selected when the dot product is zero or negative |
| `param4` | `0x34` / 52 | I32 | Not exposed | No documented meaning; unused by the inspected room-update paths |
| `param5` | `0x38` / 56 | I32 | `Direction angle` | Y-axis direction, stored as a PSO angle: `0x10000` = 360° |
| `param6` | `0x3C` / 60 | I32 | `Room update mode` | Normal versus special player-room write path |

The editor displays `param5` through the existing angle control in degrees.
Room values remain floats on disk; the client converts the selected value to
an integer. Label changes do not alter raw offsets, defaults, or serialization.

## Radius and side selection

`SCL_TAMA` is the old QEdit label for `param1`. Its name plausibly combines
"scale" and Japanese "tama" (ball), but no original-author explanation was
found. That etymology is not a specification of the collision shape.

For positive values, 4 means radius 40 and 5 means radius 50 in game-coordinate
units. The inspected BB handler uses **XZ distance squared**, strictly less
than `(param1 * 10)^2`. It is not a three-dimensional sphere test. An outer
early-rejection check adds 30 units; those extra units do not enlarge the
actual room-write radius. Use positive radii when authoring; zero/negative
values are not documented here as supported disable modes.

Within range, the handler rotates `(0, 0, -1)` about Y by `param5`, then takes
the dot product with the XZ vector from the object to the player:

- Positive: choose `param2`.
- Zero or negative, including the dividing line: choose `param3`.

This depends on player **position**, not facing direction or movement history.
The old labels `Next Room` and `Previous Room` did not represent a remembered
room sequence. `param5` is independent of the object's generic Rotation Y.

## Room update mode

newserv documents `param6` as ignored on v2 and earlier. On v3 and later:

- `65536` (`0x00010000`) writes only the first player-room field and sets player
  flag `0x02000000`.
- Other values call the normal room setter, which also updates the second
  player-room field on special maps.

In the inspected BB player update loop, `0x02000000` skips one automatic
nearest-section overwrite of the first field, then is cleared. It is not a
persistent mode. newserv also describes effects on `TObjRaderCol`; the traced
update loop alone does not establish all downstream radar behavior. Do not
interpret this as a permanent minimap-disable switch or treat other values as
independent combinable flags.

## Why Forest and CCA need explicit room transitions

The player's current room ID, geometry section IDs, and minimap room IDs are
different concepts. "Gameplay room" is only shorthand for the player's room
number used by game logic, not an original Sega field name.

Ordinary position-based section detection updates player-room field 1. The
normal room getter uses field 2 instead on Forest 1/2, CCA, Jungle East/North,
Mountain, Seaside, Seaside Night, Tower, and the De Rol Le/Barba Ray maps.
Explicit setters maintain that second field. Pioneer 2 also receives the
second-field write, although its room getter returns zero.

This map-specific rule explains why a **square CCA room still needs the
appropriate transition**: the client does not choose its room-update method
from the visible shape of the walls. It also does not mean every special map
requires newly authored door triggers. Preserve the stock map's conventions.
Incorrect room assignment can affect room-dependent interactions; community
authors report visible but untargetable enemies in Forest/CCA.

The community explanation that Forest has a 4×4 spatial grid not matching its
visible rooms is useful context. It is not proof that CCA uses the same grid,
or evidence of Sega's original design rationale.

## Authoring and editor boundaries

1. Start from stock object data for the exact map and variation.
2. Place the trigger in a required doorway or passage; set both side IDs and
   the direction to match the intended rooms.
3. Cover the traversable passage with a positive radius. A small radius can
   be bypassed; an excessive one can reach unrelated paths or overlap another
   divider. Do not depend on overlapping trigger update order.
4. Cover spawn/warp arrivals. For an arrival trigger that always assigns one
   room, set both side IDs to that room.
5. Test both directions, edge paths, warp arrival, enemy targeting, and relevant
   doors/events in the client.

For example, with `param5 = 0x4000` the normal points along +X: the east side
selects `param2`, and the west side or divider selects `param3`, while in range.

The editor's `RoomIdRange` helper displays the effective radius in the XZ
plane. It is a range aid, not a simulation of room selection or client update
order. It preserves the existing `param1 * 10` visualization formula.

## Unknown parameter and evidence limits

newserv skips `param4` in its object-specific comments. The inspected BB
update/debug paths and local GC `TObjRoomId::run_task` reconstruction do not
use it for room selection. Sylverant only supplies a generic data slot:
`map_object_t.dword[2]` at `0x34` (its array starts at `0x2C`). QEdit Wiki labels
the slot `-`. Preserve its bytes rather than inventing a meaning or clearing it.
This is not proof of universal non-use across every client version.

Sources checked on 2026-10-27:

- [newserv Map.cc](https://github.com/fuzziqersoftware/newserv/blob/master/src/Map.cc)
  (`TObjRoomId`) and [Map.hh](https://github.com/fuzziqersoftware/newserv/blob/master/src/Map.hh)
  (`ObjectSetEntry`): parameter semantics, version notes, and record layout.
  Local revision: `056b9834d4bda79cbecfc1bbdbaab07d981f8997`; upstream also checked.
- [Sylverant mapdata.h](https://github.com/Sylverant/ship_server/blob/master/src/mapdata.h):
  generic storage, not an object-specific `param4` definition.
- [QEdit Wiki Itemsname.ini](https://qedit.info/index.php?title=Itemsname.ini):
  historical labels, retrieved from indexed text; direct retrieval returned 403.
- [Matt's room-ID explanation](https://www.pioneer2.net/community/threads/qedit-help.7358/)
  and [tofuman's placement advice](https://www.pioneer2.net/community/threads/qedit-help%EF%BC%81.10865/):
  first-hand community guidance, not official Sega specifications.
- Local BB decompilation `Psobb.exe-05112026.c`:
  `SinowberillMapObjectUpdateHandler_0066f044` (`0x0066F044`),
  `SinowberillChallengePlayer_UpdateLoop_00689790` (`0x00689790`),
  `set_player_room_id`, and `get_player_room_id`. Recovered names are not
  original symbols; the "Sinowberill" name does not identify the owning enemy.
- Local GC reconstruction: `pso-decomp/src/pso/object/TObjRoomId.cpp`.

Project validation passed psolib JVM tests, the 13 selected property-controller
and selection-visualization browser tests, and the production distribution.
These checks validate the editor change, not client runtime behavior. This
alignment did not include a new in-game test or verification of every version.
