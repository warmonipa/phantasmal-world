# Enemy Behavior in Episodes 1, 2, and 4

This document summarizes enemy behavior in *Phantasy Star Online Blue Burst*,
with the [Ephinea PSO Wiki](https://wiki.pioneer2.net/w/Monsters) as the primary
source. It focuses on behavior that affects combat decisions: target selection,
attack-state transitions, invulnerability, status interactions, multi-entity
coordination, and other exceptional rules.

The Ephinea Wiki's `Enemies` category currently contains 98 main-namespace enemy
pages. At the time of this review, 81 of them have a dedicated
`Behavior/Mechanics` section. A missing entry below therefore means that the
Wiki does not currently document enough behavior to state a reliable rule; it
does not mean that the enemy has no special behavior.

Per-difficulty stats, fixed-damage values, resistances, and drop tables are out
of scope. Follow the linked enemy pages for those values.

Source review date: 2026-08-18.

## Target selection

### General behavior

Most enemies have no documented preference for a character's profession,
gender, or species. Their usual behavior is to activate when a player enters an
aggro radius or attacks them, select a nearby player (often the nearest one),
retain that target while attacking or at melee range, and search again after
the current action ends or the target leaves.

Large size or high HP does not imply a special targeting rule. In particular,
Delbiter and Dorphon do not inherit the targeting or weapon-attribute behavior
of the six enemies conventionally called minibosses.

### Delsaber and Gibbles

[Delsaber](https://wiki.pioneer2.net/w/Delsaber#Targeting) and
[Gibbles](https://wiki.pioneer2.net/w/Gibbles#Targeting) share a configurable
target preference. A field in the enemy's quest initialization data selects
the preferred player type:

| Value | Preferred player type |
| ---: | --- |
| 0 | Male |
| 1 | Female |
| 2 | Human |
| 3 | Newman |
| 4 | Android |
| 5 | Hunter |
| 6 | Ranger |
| 7 | Force |

The rule is a preference, not an eligibility restriction. Target searches
consider players within 250 units. The enemy does not search while it is
attacking or already within melee range of a player.

The activation radius is smaller than the target-search radius. This can delay
the enemy's appearance until its preferred target moves close enough. The
actual value comes from each quest's enemy data; it is not an immutable property
of the enemy type. Qedit defaults to `7` for Delsaber and `0` for Gibbles, so
most conventionally authored Delsabers prefer Forces and most Gibbles prefer
male characters.

### Chaos Sorcerer

[Chaos Sorcerer](https://wiki.pioneer2.net/w/Chaos_Sorcerer#Spawn) normally
selects the nearest player within 250 units of its spawn point. If any Ranger is
within 400 units of that point, it instead prefers the nearest Ranger inside
that larger radius. This can cause a Sorcerer to select a Ranger across most of
a large Ruins room.

### Other exceptional target rules

| Enemy | Rule |
| --- | --- |
| Bulclaw | Moves toward and attempts to latch onto the nearest target. |
| Dragon | Walks toward the nearest target; the underground phase homes toward targets near its path. |
| Garanz | Moves toward the nearest target and fires homing missiles at its selected target. |
| Deldepth | On its initial disc-form spawn, selects the player nearest to its spawn point. Later disc forms do not necessarily repeat this initial selection. |
| Satellite Lizard / Yowie | Pursues the nearest player anywhere in the same room, regardless of distance. While confused, prefers the nearest enemy and ignores players unless no other enemy exists. |
| Ill Gill | Retains a player or enemy target and selects its next state from the target's type, distance, angle, and movement state, plus the Ill Gill's HP and quest parameters. |
| Epsilon | Tracks one player with a red laser, then attacks the tracked position. |

See [Deldepth](https://wiki.pioneer2.net/w/Deldepth#Behavior/Mechanics),
[Satellite Lizard](https://wiki.pioneer2.net/w/Satellite_Lizard#Behavior/Mechanics),
and [Ill Gill](https://wiki.pioneer2.net/w/Ill_Gill#Behavior/Mechanics) for the
detailed state conditions.

### Target preference versus status immunity

Species-dependent status results are not target preferences:

- Androids are naturally immune to Poison and Paralysis but can be Shocked.
- Humans and Newmans can be Poisoned and Paralyzed.
- One of Dark Falz's ground-slam effects Slows only non-Android players.
- Canadine electrical attacks are especially relevant to Androids, but
  Canadines do not preferentially select Android targets.

## Episode 1

### Forest

| Enemy family | Behavior summary |
| --- | --- |
| Booma / Gobooma / Gigobooma | Activates when approached or struck, walks directly toward its target, and attacks in melee. Its recovery can be fast enough to counter between the early hits of a player combo. |
| Rag Rappy / El Rappy / Al Rappy | Never dies normally. At zero HP it awards EXP and plays dead, then gets up and runs. Hitting it during the escape produces its item drop. A Rappy frightened from long range runs immediately and awards no EXP. |
| Savage Wolf / Barbarous Wolf | Circles behind its target before pouncing. A subordinate in the same group applies low-level Jellen and Zalure to itself when its leader dies. |
| Monest / Mothmant | Monest is stationary and has no attack; it repeatedly produces Mothmants. Monest takes about 380 frames after reaching zero HP to count as dead for quest progression. |
| Hildebear / Hildeblue | Uses a projectile technique or a leap at range and heavy melee attacks up close. The projectile changes with difficulty and variant. |

### Cave

| Enemy family | Behavior summary |
| --- | --- |
| Evil Shark / Pal Shark / Guil Shark | Standard aggro-radius activation followed by a direct melee approach. |
| Poison Lily / Nar Lily / Ob Lily / Mil Lily | Stationary ranged enemy. Uses a poison projectile before Ultimate and Megid on Ultimate. After recovering from a hit it has a 30% chance to scream; at close range it bites. A normal Lily below 25% HP can begin a self-destruct that creates a Paralysis cloud. The client shares a Lily Megid-level value, so spawning a Mil Lily can cause nearby Ob Lilies to use the rare variant's Megid level. |
| Grass Assassin / Crimson Assassin | Fires webbing at mid-range and attacks with its forelegs in melee. After enough damage it roars and enters a repeating charge pattern; it is invulnerable during the roar but not during the charge. |
| Nano Dragon | Tries to maintain range, takes off to reposition when approached, and uses homing projectiles or a beam. It can attack other monsters. Killing one makes it grow visually but does not increase its power. |
| Pan Arms | Starts underground, emerges when approached or after a delay, and uses two ranged attacks. After two attacks or enough damage it splits into Hidoom and Migium. It cannot split while frozen, against a wall, or in an attack animation. |
| Hidoom / Migium | The separated halves take turns charging and attacking, then recombine. Migium's attack applies Jellen and Zalure. |
| Pofuilly Slime / Pouilly Slime | Usually moves as an untargetable puddle that is immune to ordinary damage; a Damage Trap can still hit it. It rises near a player to attack. A qualifying third combo hit can split a slime instead of returning it to puddle form. |

### Mine

| Enemy family | Behavior summary |
| --- | --- |
| Gillchic / Gillchich | Stands when a target enters its aggro radius, then uses an arm laser or double punch. On Ultimate it has only a 20% chance to be knocked down by a hit; attacks that fail to knock it down barely interrupt it. |
| Dubchic / Dubchich | Uses Gillchic-style attacks but can collapse and rebuild at full HP. It permanently dies after five depleted lives or when the room's Dubwitch is destroyed. Client-local rebuild rolls can desynchronize in multiplayer. |
| Dubwitch | Starts airborne and is initially reachable only by physical ranged attacks. It descends when a Dubchic loses a life. Destroying it kills all currently alive Dubchics in the same room. Machine percentage does not increase damage against it. |
| Canadine | Either tracks a player with a harmless red laser and fires Zonde at the last tracked position, or approaches at low altitude and uses a melee electrical attack. It cannot be frozen while airborne. |
| Canane | Commands a ring of Canadines through ranged and melee phases, then teleports away. Killing the Canane early sends the remaining Canadines into a self-destruct pursuit. |
| Sinow Beat / Sinow Blue | Drops from the ceiling, leaps at distant targets, attacks twice in melee, then creates harmless solid-body copies. The real Sinow has different shoulder-light colors; hitting it removes the copies. |
| Sinow Gold / Sinow Red | Uses the same basic leap and melee pattern, then casts Resta on nearby enemies. The Ultimate variant also casts Shifta and Deband. |
| Garanz / Baranz | Slowly approaches the nearest target. Its homing missiles can damage players, other monsters, and itself. Missile count rises as HP falls, and a low-HP Garanz drops mines while moving. |

### Ruins

| Enemy family | Behavior summary |
| --- | --- |
| Dimenian / La Dimenian / So Dimenian | Standard aggro-radius activation followed by a direct melee approach. |
| Claw | Attempts to surround its target, attacks, retreats, and approaches again. Its altitude changes and attack transitions contain many untargetable frames. |
| Bulclaw / Bulk | Bulclaw attempts to latch onto the nearest target. After two failed attempts or one success it separates into four Claws and a Bulk. If all components survive, they eventually recombine. An attacked Bulk retaliates with an attack that reduces the target to 1 HP. |
| Delsaber | Uses a jump to close long distances and a three-hit sword combo in melee. It can block three close attacks before its shield light expires, then must hit a target to restore blocking. Its preferred player type comes from quest data. |
| Chaos Sorcerer / Gran Sorcerer | Cycles through two offensive techniques followed by Resta, teleporting between casts. One Bee attacks and the other heals. Destroying the active Bee interrupts its technique; lost Bees are eventually restored. It has a separate Ranger-priority target rule. |
| Dark Belra / Indi Belra | Advances slowly and fires a piercing arm projectile that can damage other monsters. It uses a heavy melee swipe at close range. Indi Belra remains in place and fires rapidly. Running around a Belra can disrupt its facing and temporarily stop its actions. |
| Dark Gunner / Death Gunner | One member of a group carries the red Death Gunner gem, which rotates after attack cycles. Glowing Gunners cannot be damaged physically unless frozen or confused. Interrupting a charging Death Gunner prevents the whole group from firing; interrupting an ordinary Gunner stops only that Gunner. |
| Chaos Bringer / Dark Bringer | Uses a long charge, sword swipe, and ranged beam. Repeated hits advance its photon color from blue to yellow to red, after which it prepares the beam. It drains nearby players' TP before firing, increasing beam damage if TP was stolen. On Ultimate its charge can unequip the player's weapon. |

### Bosses

| Boss | Behavior summary |
| --- | --- |
| Dragon / Sil Dragon | Walks toward the nearest target, damages with its feet, breathes a frontal cone, and fires volleys while airborne. Below roughly one-third HP it enters an underground homing phase. Its death animation can still damage and kill players. |
| De Rol Le / Dal Ra Lie | Has a main HP pool plus armored body segments. Damage to the unarmored head transfers fully to the main pool; damage to another unarmored segment transfers at half value. The boss follows a difficulty-dependent attack rotation. |
| Vol Opt | First form cycles through monitors and technique-casting pillars. Second-form attacks are associated with destructible nodes; destroying a node disables that attack. The second-form rotation is fixed before Ultimate and randomized on Ultimate. Both forms are immune to Jellen and Zalure. |
| Dark Falz | Uses phase-specific elemental attacks, fixed-damage attacks, movement between player positions, homing projectiles, life drain, and soul sharing. Some attacks deliberately change or rotate targets; the Wiki does not document a profession, gender, or species priority. |

## Episode 2

VR Temple and VR Spaceship reuse many Episode 1 enemy state machines. Stats,
resistances, technique choices, and Ultimate names can differ, but the special
Delsaber and Chaos Sorcerer target-selection rules still apply.

### Central Control Area

| Enemy family | Behavior summary |
| --- | --- |
| Merillia / Meriltas | Mobile plant-type melee enemies. The Wiki currently provides little state-machine detail. |
| Gee | Airborne enemy with movement and ranged/charge attacks. The Wiki currently provides little state-machine detail. |
| Ul Gibbon / Zol Gibbon | Fast-moving melee and technique users. A grouped Gibbon can apply low-level Shifta and Deband to itself after the relevant grouped Gibbon dies. Ultimate melee attacks can apply status effects. |
| Sinow Berill / Sinow Spigell | Sinow-style ambush and melee enemies with invisibility and variant-specific status or technique attacks. The second hit of Berill's melee attack has a one-in-six status chance when it damages or pushes the player. |
| Mericarol / Merikle / Mericus | Stationary ranged miniboss family with a close-range stampede. Their colored projectiles differ; on Ultimate the projectile is an instant-kill attack. Correct weapon Native/A.Beast percentages do not increase damage because of the miniboss attribute bug. |
| Gi Gue | Airborne miniboss using fireballs, bombs, and a technique attack. It cannot be frozen while flying between positions. Correct weapon Native percentage does not increase damage. |
| Gibbles | Uses jumps, ground pounds, and a wind-up punch. Its preferred player type is configured in quest data. A ground pound can produce a physical flinch before subsequent fixed-damage frames, preventing knockdown and allowing rapid consecutive damage. |

### Seabed

| Enemy family | Behavior summary |
| --- | --- |
| Dolmolm / Dolmdarl | Mobile melee enemy with tentacle and status-related attacks. The Wiki currently provides little state-machine detail. |
| Recobox / Recon | Recobox acts as the stationary source for mobile Recon units. Recon attacks with bombs and a saw. The Wiki currently provides little sequencing detail. |
| Sinow Zoa / Sinow Zele | Sinow-style ambush enemies with melee, invisibility, and variant-specific technique attacks. The second hit of Zoa's attack has a one-in-six status chance when it damages or pushes the player. |
| Morfos | Stationary beam enemy affected by a client AI bug: it cannot fire at a player while it is outside that player's camera view. This is per-player; it may still fire at another player, and its close attack still works. |
| Deldepth | Initially travels in disc form toward the player nearest its spawn point. It stands after a qualifying movement segment, hit, or Miss, then selects Barta or a Rabarta bomb; Ultimate replaces Barta with Megid. Disc collision checks occur every fifth frame and depend on facing angle. In One Person mode the configured attack angle is zero, so every disc attack is DFP-blocked. |
| Delbiter | Uses charge, beam, and melee attacks. Despite its size and threat level, it is not a mechanical miniboss and takes increased damage from A.Beast percentage normally. |

### Control Tower

| Enemy | Behavior summary |
| --- | --- |
| Del Lily | Uses Megid on every difficulty. After recovering from damage it has a 30% chance to scream, setting nearby players to 1 HP. Its countdown self-destruct deals physical damage. |
| Ill Gill | Evaluates the retained target's type, range, angle, and movement state to choose walking, scythe, root, or charge. Below 25% HP it always enters the charge state and attempts to dash to a valid point behind the player. Quest data can force permanent charging. Its scythe randomly uses Freeze, Shock, or instant kill. |
| Epsilon / Epsigard | Tracks one player with a red laser and casts at the tracked point four times, then extends its four Epsigards and becomes vulnerable. Epsigard color cycles through fire, ice, lightning, and dark attacks. |

### Bosses

| Boss | Documented behavior boundary |
| --- | --- |
| Barba Ray | The Wiki currently focuses on fixed damage rather than a complete action-state sequence. |
| Gol Dragon | Uses fire, ice, and lightning breath/spread attacks and is immune to Jellen and Zalure. |
| Gal Gryphon | Uses charges, airborne attacks, tornado/projectile attacks, thunderbolts, and shockwaves. |
| Olga Flow | Form 2 carries a special attribute flag that displays `????` and causes the same weapon-attribute comparison failure seen on minibosses. The current Wiki page is not a complete AI state-machine reference. |

## Episode 4

### Crater and Subterranean Desert

| Enemy family | Behavior summary |
| --- | --- |
| Boota / Ze Boota / Ba Boota | Basic mobile melee family; Ba Boota has an additional ranged attack. The Wiki currently does not provide a complete behavior section for this family. |
| Sand Rappy / Del Rappy | Uses the Rappy play-dead, escape, and escape-hit drop behavior. The current pages provide little additional Episode 4 behavior detail. |
| Satellite Lizard / Yowie | Pursues the nearest player anywhere in the same room. Its frontal shield blocks projectiles; triggering the shield makes it invisible and untargetable until it reaches its target. Techniques mostly bypass the shield, but cannot target it while it is untargetable. While confused it selects the nearest enemy and ignores players unless no other enemy exists. |
| Zu / Pazuzu | Always airborne and therefore cannot be frozen. Uses beam, dive, and technique attacks. Pazuzu is the rare variant. |
| Astark | The Wiki currently provides special-activation data but no complete behavior description. |
| Dorphon / Dorphon Eclair | Uses beam and charge attacks. It is immune to Freeze but susceptible to Paralysis. It is not a mechanical miniboss and takes increased damage from Native percentage normally. |
| Goran / Pyro Goran / Goran Detonator | The Wiki currently provides stats and special-activation data but no complete behavior state machine. |
| Merissa A / Merissa AA | Uses body-slam and technique attacks. Merissa AA is the rare variant; the Wiki currently provides limited sequencing detail. |
| Girtablulu | Uses three elemental arm/head projectile types and elemental claw attacks. Each arm fires a projectile, so both can hit together. The blue ice claw uniquely cannot knock the player down. Girtablulu is immune to Freeze and Confusion, and correct weapon Dark percentage does not increase damage. |

### Bosses

Saint-Milion, Shambertin, and Kondrieu share a multi-phase, multi-part battle
structure involving underground movement, emergence attacks, and independently
targetable body or neck-crystal parts. Their Wiki pages currently emphasize
part statistics, fixed damage, and strategy rather than a complete AI state
machine, so this document does not promote strategy observations to confirmed
selection rules.

## Miniboss terminology

`Miniboss` is a narrow mechanical/community term, not a visual classification.
The six conventional minibosses are Mericarol, Merikle, Mericus, Gibbles,
Gi Gue, and Girtablulu. Their enemy attribute combines a base race with a
`Miniboss` flag. Stock damage code compares the full value against the four base
race values, so the correct weapon percentage fails to match.

Olga Flow Form 2 has the same outcome through a different special flag. Delbiter
and Dorphon may look and behave like minibosses, but do not have this defining
weapon-attribute behavior.

## Conclusions

- Delsaber and Gibbles are the only enemies for which the Wiki documents a
  general quest-configurable preference covering gender, species, and
  profession.
- Chaos Sorcerer has a separate hard-coded Ranger preference.
- Most other selection behavior is based on distance, spawn position, room,
  current action, target angle, HP, and quest initialization parameters.
- Species-dependent status immunity must not be mistaken for aggro priority.
- Quest data can materially change behavior, so claims that an enemy always
  prefers one player type should include the relevant initialization parameter.
- Missing Wiki behavior text is an evidence gap, not evidence that the enemy is
  behaviorally simple.

## Primary sources

- [Monsters](https://wiki.pioneer2.net/w/Monsters)
- [Delsaber](https://wiki.pioneer2.net/w/Delsaber)
- [Gibbles](https://wiki.pioneer2.net/w/Gibbles)
- [Chaos Sorcerer](https://wiki.pioneer2.net/w/Chaos_Sorcerer)
- [Deldepth](https://wiki.pioneer2.net/w/Deldepth)
- [Ill Gill](https://wiki.pioneer2.net/w/Ill_Gill)
- [Satellite Lizard](https://wiki.pioneer2.net/w/Satellite_Lizard)
- [Dorphon](https://wiki.pioneer2.net/w/Dorphon)
- [Girtablulu](https://wiki.pioneer2.net/w/Girtablulu)
- [Game mechanics](https://wiki.pioneer2.net/w/Game_mechanics)
