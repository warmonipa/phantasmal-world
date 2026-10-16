# Per-player walkthrough journey

## Intent

The Route overlay is the basis for per-quest walkthroughs: for a selected player (client 0–3) it
must show how that player plays the quest from start to completion. The editor draws only the
currently visible floor, and on that floor it draws exactly the part of the player's journey that
happens there: from where the player actually appears to the exit the player actually takes next.
Floors the player never visits show no route. Most quests give all four players the same journey;
quests that split players 2/2 or 1/1/1/1 give each player their own.

## Problem with the current planner

`planWalkthroughRoute` plans every visible floor independently:

1. The start is always the client's Player Set with Return flag 0, so arrivals from a later floor
   (Return flag 1 sets) are never used.
2. The endpoint is the farthest reachable exit of the highest priority, not the exit to the floor
   the player goes to next. In 2/2 split floors this can pick the other pair's teleporter.
3. There is no floor order: no notion of which floor follows which, no revisits (e.g. Pioneer 2 at
   the start and at the end), and a route appears on every floor that has a Player Set for the
   client, which is nearly every floor.

## Evidence from quest data

Measured on the test corpus (`psolib/src/commonTest/resources/quests`).

- Player Set fields: Slot ID (`float@40`; when a floor's sets for one Return flag use exactly
  slots 1–4, client c is slot c + 1) and Return flag (low 16 bits of `int@52`; 56 sets store
  65536, all slot 3 on Pioneer 2 in EP1 quests, so the full 32-bit value must not be compared). Return flag 0 sets are where
  a player appears when entering a floor forwards; Return flag 1 sets where they appear when coming
  back from a later floor. Pioneer 2 has Return flag 1 sets next to the Ragol teleporter, used when
  returning from Ragol. (`1-2 torrential woods`, `6-1 spaceship 1`, `fragments of a memory`.)
- Splits are expressed as per-slot Player Sets in different sections of the same floor: e.g.
  `6-1 spaceship 1` floor 3 puts slots 0/2 in section 1 and slots 1/3 in section 8; battle quests
  and Challenge `2c2` put each slot in its own section. All of them converge on shared exits.
- Return flag 1 sets often sit next to the exit used to go forward on that floor (Forest 1 → the
  teleporter to Forest 2; spaceship pairs → their own teleporter), but the rule is not general:
  in 179 parsed quests, 554 slot markers are near an exit and 931 are not. It can only break ties.
- Floor-level forks (a floor with teleporters to two or more later floors) are rare: 3 floors in
  `ma2`, `maximum attack 2 ver2` and `21 unsealed door`.
- `go_floor` in the corpus sends the executing player to a constant floor (Challenge retry → floor
  1, Challenge end → floor 0); it is not used for splits there, but it is client-scoped.
- Challenge quests' teleporter destinations do not describe their progression: `1c2` has floors
  3–7 with teleporters 3→4, 4→0, 5→4, 6→0, 7→0, which would make floors 5–7 unreachable.
- 22 of 179 quests have no `set_mainwarp`: all Challenge quests, Maximum Attack events,
  `seat of the heart`, `phantasmal world 4` and two EP4 solo quests. They do not start through
  the Pioneer 2 Ragol teleporter.
- Same-floor warps connect sections of one floor: split start rooms in `2c2` floor 3 reach the
  shared teleporter through them. `fragments of a memory` floor 8 stacks dozens of site-to-site
  warps with different destinations at the same three points; which one is active is decided at
  runtime.
- Automatic warps come in two forms. Insta-Warp objects (483 in 12 quests) teleport a player who
  steps on them and already carry destination coordinates. Scripted relocation moves a player with
  `p_setpos` / `p_move` / `p_move_v3` from an interaction's script: `seat of the heart` has 168,
  `soul of steel` 100, `wilderending` 67. The current planner ignores scripted relocation and walks
  the player where the game teleports them.
- `set_mainwarp` takes an episode-absolute area number (EP2 from 18, EP4 from 36), not a quest
  floor ID: `lost son hopkins` uses 41 (EP4 area 5, Crater Interior) while its first Ragol floor
  is 6 (Desert 1). In `5-2 vr temple 2` and `6-2 spaceship 2` the target is the second floor of a
  teleporter chain (1 → 2, 3 → 4) whose first floor is otherwise unreachable.
- A throwaway prototype of these rules over the corpus (clients 0 and 1) produced 1530 visits;
  before the main-warp and `go_floor` rules, 232 floors were unreached. The implementation's
  corpus test leaves 9 quests with "order unknown" floors, each because nothing statically
  resolvable enters the floor (`4-1 the lost ruins` floors 8–9 are only linked to each other by
  `go_floor`; `7-5 isle of mutants` floors 8–9 only lead back to 5; `seat of the heart` floor 2;
  `sugoroku`, `respective tomorrow`, `the beak's cafe ver.2`, `wilderending`), and 13 arrivals
  in 7 quests whose floor lacks the Player Set with the expected Return flag.
- Some floors number Player Set slots 1–4 instead of 0–3 (`2c1_e`, `4-2 buried relics`,
  `4-3 hero & daughter`, `endless nightmare #4`, `1c8_e`, `5-3 vr temple 3`).
- Script analysis previously ran out of memory on `the beak's cafe ver.2` (34,582 register
  snapshots at one entry) and `ep2/shop/gallon` (21,217 call stacks): constant registers are now
  merged at join points and call contexts are bounded beyond a state budget.
- Quest success requires r255/r253 = 1 and talking to the Hunter's Guild counter on Pioneer 2,
  which is part of the city map, not quest data.

## Design

### 1. Journey planner (psolib, pure data)

New `psolib/.../fileFormats/quest/walkthrough/QuestJourney.kt`:

```kotlin
fun planQuestJourney(
    episode: Episode,
    objects: List<QuestObject>,
    bytecodeIr: BytecodeIr,
    isChallengeMode: Boolean,   // any DAT event carries the Challenge mode flag
    clientId: Int,
    scriptEntries: List<ScriptEntry>,  // label + floor of every script trigger, as collected today
): QuestJourney

data class QuestJourney(val visits: List<FloorVisit>, val diagnostics: List<String>)

data class FloorVisit(
    val floorId: Int,
    val arrival: QuestObject?,          // Player Set the player appears at; null if unknown
    val departure: FloorTransition?,    // null on the final visit
)

sealed class FloorTransition {
    abstract val toFloorId: Int
    /** An object on this floor that leads to another floor (teleporters, Quest Warp, boss). */
    data class Exit(val exits: List<QuestObject>, override val toFloorId: Int) : FloorTransition()
    /** The Pioneer 2 Ragol teleporter, enabled by set_mainwarp. */
    data class MainWarp(val teleporters: List<QuestObject>, override val toFloorId: Int) : FloorTransition()
    /** A client-scoped `go_floor` reached from a script entry label. */
    data class Script(val entryLabels: Set<Int>, override val toFloorId: Int) : FloorTransition()
}
```

It works on floors only and needs no geometry, so it can be tested on the whole corpus on the JVM
and later reused to export a written walkthrough.

**Floor graph for the client.** Edges `F → D`:

- objects on F with a destination floor D ≠ F: Teleporter, Teleporter (Ep. II), Ruins Teleporter,
  Quest Warp, Main Ragol Teleporter (battle, next area), Warp in Barba Ray room; Boss Teleporter
  via `getNormalBossTeleporterDestinationFloor`;
- from floor 0 (the Pioneer 2 Ragol warp) to every floor enabled by a `set_mainwarp` reachable for
  the client, whether or not floor 0 has a Main Ragol Teleporter object. The operand is normalized
  to an area (subtract 36 when ≥ 36, 18 when ≥ 18) and mapped to the lowest logical floor with that
  map area in the quest's floor mappings (or the floor with that ID when there are no mappings);
  if no quest floor has that area, the lowest quest floor with a higher area is used. If the
  resulting floor is the end of a chain of floors that are not otherwise reachable from floor 0
  (a floor F' with an edge into it), the warp lands on the head of that chain instead;
- `go_floor` instructions reachable for the client whose player operand resolves to this client
  and whose floor operand resolves to a constant, from the floor the instruction executes on
  (`go_floor` operands are a logical floor ID).

Script reachability reuses `analyzeWalkthroughScript` with the same entry labels as today (label 0,
object/NPC scripts, script spatial interactions, script NPC interactions). The analysis gains
`mainWarpFloorIds` (from `set_mainwarp`) and `goFloors` (resolved `go_floor` targets).

Edges into a floor with no Player Set for the client are dropped, except edges into floor 0.

**Start floor.** Floor 0 if it has a Return flag 0 Player Set for the client and an outgoing edge;
otherwise the lowest non-zero floor with a Return flag 0 Player Set for the client.

If the client has no Player Set at all (single-player quests only place slot 0), the journey is
empty with the diagnostic "No Player Set for client N.".

**Challenge quests** (any DAT event carries the Challenge mode flag, or the script uses a `chl_*` /
`disp_chl_*` opcode; EP2 Challenge DAT events lack the flag) skip the graph: the journey visits every non-zero floor with a Player Set for
the client in ascending floor order, ending on the last one, and each departure is an `Exit`
listing all floor-exit objects of that floor (their stored destination floors are ignored).

**Required floors.** Every floor that has a Player Set for the client. A floor without one is not
part of this client's journey and shows no route: that is how quests encode floors a player does
not visit.

**Order.** From the current floor, go to the nearest unvisited required floor by hop count (ties:
lowest floor ID), recording every intermediate floor as a visit, until all required floors are
visited or none is reachable from the current floor. Then, if floor 0 has a Player Set for the client and the journey did not end there, append the shortest
path back to floor 0 (the quest is reported at the Hunter's Guild counter). Required floors that
were never reached are appended in ascending order as visits with their Return flag 0 arrival and
no departure, each with the diagnostic "Floor F: order unknown for client N." so that no floor the
player plays is silently missing from a walkthrough.

**Arrival.** For a hop S → D: a Quest Warp uses the client's Player Set whose Return flag equals the
warp's Player set ID; otherwise Return flag 1 when coming back (D = 0, or S > D with S ≠ 0) and
Return flag 0 when going forward. If that set is missing, fall back to the other flag and add a
diagnostic. The start visit arrives at the client's Return flag 0 set.

### 2. Floor route (web, geometry)

Same-floor warps stay inside floor planning, exactly as today: every object with an intra-floor
destination (Warp, Principal warp, Ruins site-to-site warp, InstaWarp, Lab map warp, and a Quest
Warp whose destination floor is the current floor) is a directed, non-rendered edge between
walkable regions. A same-floor Quest Warp lands on the client's Player Set whose Return flag equals
its Player set ID (16-bit Return flag). `warp_off`/`warp_on` and door gating are unchanged. The
journey never treats a same-floor warp as a floor transition. Insta-Warps are covered by this rule.

**Scripted relocation (automatic warps).** The script analysis records `relocations`: every
reachable `p_setpos` whose slot is this client, and every `p_move` / `p_move_v3` whose client-ID
register resolves to this client, with x/y/z resolved from constant registers, on the floor the
instruction executes on. Unresolvable coordinates or slots are skipped with a diagnostic count. In
floor planning, an interaction node (object, NPC, script spatial interaction, script NPC) whose
script relocates the client on this floor gets a directed, non-rendered edge to the relocation
point, like a warp source. Relocations reachable only from the quest's start label are not used
for the start position. Before relying on the coordinates, the implementation verifies on the
corpus that relocation points are world coordinates lying on the floor's walkable collision.

`planWalkthroughRoute` calls `planQuestJourney` and plans each visit whose floor is visible;
visits of other floors produce nothing. For floors the client never visits it emits a diagnostic
("Client N does not visit floor F."). `planFloor` takes the visit instead of finding an entrance:

- **Entrance** = the visit's arrival Player Set (world position); no route if it is null.
- **Terminal** for a visit with a departure:
  - `Exit`: only the listed exit objects are Exit nodes (other floor exits are ignored);
  - `MainWarp`: the listed Ragol teleporters are Exit nodes; objectives whose script enables the
    main warp to `toFloorId` are required before them (same mechanism as door/warp gating). If
    floor 0 has no Ragol teleporter object, the leg ends at the last required objective;
  - `Script`: interaction nodes whose script reaches the `go_floor` are the terminal candidates.
  If no terminal is reachable from the entrance on the walkable collision, the visit draws no route
  and adds a diagnostic. Among reachable terminals, prefer the one nearest (by path) to the client's Return flag 1 Player
  Set on this floor when that set lies within 100 units of a terminal; otherwise keep the current
  rule (farthest by path from the entrance).
- **Final visit** (no departure) and **order-unknown visit** (appended unreached floor): on
  floor 0 no route is drawn (the Hunter's Guild counter is not in quest data); on other floors the
  current goal selection applies, including floor exits (an exit to a floor the client does not
  visit still marks where that floor ends), i.e. today's behaviour for that floor.
- Completion gating (doors, `warp_off`/`warp_on`, event collisions) is unchanged.
- A visible floor visited several times draws every visit.

### 3. Scope limits

- Physical reachability is known only for the visible floor. The floor graph assumes a client can
  reach every exit on a floor, so forks (3 floors in the corpus) take the nearest unvisited floor.
- Battle quests use the general rules; Challenge quests use the linear rule above.
- Stacked same-floor warps with runtime-selected destinations are all treated as usable; the
  route takes the shortest one, which may not be the active one.
- Quests without `set_mainwarp` start on their lowest Ragol floor, so no Pioneer 2 leg is drawn.
- A relocation inside a script that also does many other things (cut scenes, several relocations
  in a row) is treated as reachable from the triggering interaction; when several relocations are
  reachable from one interaction, each is an edge and the route uses the shortest.
- No change to the Route selector, rendering, debounce or caching.

## Testing

- psolib unit tests with synthetic quests: forward chain P2 → F1 → F2 → P2 with Return flag
  arrivals; Quest Warp arrival; client-scoped `go_floor` making a floor unreachable for other
  clients; main warp from P2 including a revisit of P2; Return flag 65536 read as 0; Challenge
  quests visiting floors in ascending order; a quest without `set_mainwarp` starting on Ragol;
  `p_setpos` / `p_move_v3` relocations resolved for the matching client only.
- psolib corpus check: relocation points of `seat of the heart` and `soul of steel` lie on their
  floors' walkable collision (loaded from `web/src/jsMain/resources/assets/areas`).
- psolib corpus test (JVM): for every test quest and client, planning does not throw; across the
  corpus at most 13 visits lack the expected Return flag arrival and only the 9 quests listed above
  have "order unknown" floors; golden floor sequences for client 0: `1-2 torrential woods`
  0,1,2,0; `6-1 spaceship 1` 0,3,0; `5-2 vr temple 2` 0,1,2,0; `6-2 spaceship 2` 0,3,4,0;
  `lost son hopkins` 0,6,7,8,9,0; `2-1 infernal cavern` 0,3,4,0; `1c2_e` 3,4,5,6,7.
- web tests: `WalkthroughRoutePlannerTests` updated for entrance/terminal selection; new cases for
  the 2/2 split exit choice, unvisited floors drawing nothing, and main-warp gating on Pioneer 2.
- Visual check in the editor on `1-2 torrential woods` and `6-1 spaceship 1` for two clients.

## Documentation

Rewrite "Quest Walkthrough Routes" in `web/README.md` around the journey model and its limits.

## Revisions after implementation

- Ragol-warp chain heads: only a single unreachable predecessor forms a chain (`7-5 isle of
  mutants` has a hub, floor 5, that floors 6–9 return to).
- Fallback transitions: when floors stay unreached, `go_floor`/`set_mainwarp` instructions that no
  known script entry reaches (e.g. threads waiting on an event flag in `7-5`, `seat of the heart`)
  are added: `go_floor` from any floor, `set_mainwarp` from Pioneer 2. Such floors carry the
  diagnostic "entered through a script the analysis cannot trace" and their order relative to
  other floors is a guess. Remaining "order unknown" quests: `4-1 the lost ruins` (Ruins entered
  through the Ragol warp unlocked by defeating Vol Opt, not in quest data), `respective tomorrow`,
  `sugoroku`, `the beak's cafe ver.2`, `wilderending`.
- Missing Return flag 1 sets are expected in quests that disable telepipes (`p_disablewarp`).
- Relocation coordinates are world coordinates (all relocations of 25 quests lie on collision);
  relocations from floor handlers with a runtime floor are attributed to every floor, and the
  off-floor copies are dead ends because their points are not walkable there.
- Routes are continuous except at non-rendered warps and relocations; every route-planner test
  asserts this.
- Visited floors: only floors with other clients' Player Sets but none for this client are
  skipped; floors without any Player Set (`respective tomorrow` floors 1, 9, 11) are played, and
  the client arrives at the floor's exit back to its source floor.
- Start: `initial_floor` when the script states it (`respective tomorrow` has only return sets on
  Pioneer 2).
- `set_mainwarp` operands first match `bb_map_designate` client map numbers (EP4: 36 = Crater
  East, 45 = Pioneer 2), otherwise EP4 operands count Ragol areas from 36 (operand − 35).
- Default Ragol warp: region entries (EP1 1/3/6/8, EP2 1/3/5/10, EP4 1–4/6) unless cleared by
  `clear_mainwarp`, used only for floors still unplaced (`4-1` Ruins, `sugoroku` Forest 1,
  `wilderending` Crater West). The EP2/EP4 region tables come from game knowledge, not quest data.
- Order: one-floor lookahead so dead ends wait (`respective tomorrow` visits 17 before boss 13).
- Fallback go_floor targets include visited floors (returns to Pioneer 2). The only quest left with
  an unplaced floor is `aol cup -maximum attack-`, whose floor 6 is the other course of a menu
  choice ("alternative branch").
- Review fix: a visit with a departure but no reachable exit (untraced script `go_floor`, Ragol
  warp without a teleporter object, unreachable exits) stops after the required objectives with
  "no reachable exit to floor M"; it no longer falls back to the farthest objective.
- Order ties: equally near floors are ordered by the shortest complete journey they start
  (Penumbral Surge #6: 0, 17, 16, 15, 0 rather than visiting 16 first and ending on 17's entrance
  teleporter). The one-floor lookahead still decides first, so `aol cup -maximum attack-` keeps
  floor 6 as the alternative course.
- Doors and fences block routes (2026-10-16): every door and laser fence is a gate open only when
  its switch flags are turned on by a reachable DAT event chain, script (`unlock_door2`,
  `switch_on`, `sw_send`, `setevt`), switch object, or the start script; `masterkey_off` no longer
  counts as unlocking every door (newserv: it only removes `masterkey_on`'s lock). Paths crossing a
  closed gate need its setters first; doorway waypoints allow detours; a gate nothing opens is passed
  only when it is the sole way to the exit. Door sides skip floor islands inside the door frame.
- Warps and relocations are drawn as dashed arcs; objects just past the walkable edge snap onto it.
- Collision fidelity (2026-10-16, Ember in the Dust / Soul of Dust / Lost SHOCK GUNGNIR): seams
  join collision pieces that meet without shared vertices (seam hash cells padded so edges on a cell
  boundary match); stacked surfaces are tried in turn; walkability needs a containing polygon.
  Laser fences block along their local Z axis. Same-effect triggers are alternatives; collision
  triggers fire within their radius. Object 0x160 in Vol Opt's arena is the cross-floor warp
  TObjWarpBoss03. Floor-handler callback extraction no longer drops the live branch next to a
  yielding guard.
- Known limits: breakable walls are not modeled; boss-phase stage transfers (Vol Opt, Barba Ray
  docking) are not modeled; floors whose only way back is a telepipe (Soul of Dust 8 Lower after
  its one-way warp, Soul of Dust 9 Tower) report "exit unreachable after gated objectives".

