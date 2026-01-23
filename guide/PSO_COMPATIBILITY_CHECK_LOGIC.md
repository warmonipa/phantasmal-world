# PSO Quest Compatibility Check Logic Document

## Overview

This document describes in detail the compatibility checking algorithm of the PSO Quest Editor, used to verify whether
quest files are compatible with different PSO versions.

**Source code location**: `main.pas` lines 9053-9312 (`TestCompatibility` procedure)

---

## 1. Version Definitions

```pascal
// Version parameter ver values:
// 0 = DC V1    (Dreamcast V1)
// 1 = DC V2    (Dreamcast V2)
// 2 = PC       (PC version)
// 3 = GC EP1&2 (GameCube Episode 1&2)

// Note: Blue Burst uses DC V2 (ver=1) results
// See Compatibilitycheck1Click: form27.er[2] := form27.er[1]
```

**Version display order** (Form27.ListBox1):
| Index | Version Name | ver Parameter |
|-------|--------------|---------------|
| 0 | DC V1 | 0 |
| 1 | DC V2 | 1 |
| 2 | Blue Burst | (copies DC V2 result) |
| 3 | PC | 2 |
| 4 | GC EP1&2 | 3 |

---

## 2. Constant Definitions

### 2.1 Default NPC Action Labels (main.pas:9055-9058)

```pascal
// Episode 1/4 default labels (22 entries)
// First 13 (0-12) used for all versions
// Last 9 (13-21) only available for ver=3 (GC)
DefaultLabel: array [0..21] of integer = (
  100, 90, 120, 130, 80, 70, 60, 140, 110, 30, 50, 1, 20,  // Base [0-12]
  850, 800, 830, 820, 810, 860, 870, 840, 880              // V3 extended [13-21]
);

// Episode 2 default labels (19 entries)
// First 10 (0-9) used for all versions
// Last 9 (10-18) only available for ver=3 (GC)
DefaultLabel2: array [0..18] of integer = (
  720, 660, 620, 600, 501, 520, 560, 540, 580, 680,  // Base [0-9]
  950, 900, 930, 920, 910, 960, 970, 940, 980        // V3 extended [10-18]
);
```

### 2.2 Enemy ID List (main.pas:47-49)

```pascal
// 58 enemy Skin IDs - used to distinguish enemies from NPCs
EnemyID: array [0..57] of integer = (
  68, 67, 64, 65, 128, 129, 131, 133, 163, 97, 99, 98, 96,
  168, 166, 165, 160, 162, 164, 192, 197, 193, 194, 200,
  66, 132, 130, 100, 101, 161, 167, 223, 213, 212, 215,
  217, 218, 214, 222, 221, 225, 224, 216, 219, 220, 202,
  201, 203, 204, 273, 277, 276, 272, 278, 274, 275, 281, 249
);
```

### 2.3 Conversion Instruction Opcodes (main.pas:9084-9086)

```pascal
// These opcodes may have parameter conversion issues in V1 version
ConvertOpcodes = [$66, $6D, $79, $7C, $7D, $7F, $84, $87, $A8, $C0, $CD, $CE]
```

### 2.4 Parameter Type Constants (Unit1.pas)

```pascal
T_NONE    = 0   // No parameter
T_IMED    = 1   // Immediate value
T_ARGS    = 2   // Argument mode
T_PUSH    = 3   // Push stack
T_VASTART = 4   // Variable argument start
T_VAEND   = 5   // Variable argument end
T_DC      = 6   // DC specific

T_REG     = 7   // Register (R0-R255)
T_BYTE    = 8   // Byte value
T_WORD    = 9   // Word value
T_DWORD   = 10  // Double word value
T_FLOAT   = 11  // Float
T_STR     = 12  // String

T_RREG    = 13  // Register reference
T_FUNC    = 14  // Function label
T_FUNC2   = 15  // Function label 2
T_SWITCH  = 16  // Switch statement
T_SWITCH2B= 17  // Switch 2 bytes
T_PFLAG   = 18  // Flag

T_STRDATA = 19  // String data
T_DATA    = 20  // Data label
T_HEX     = 21  // Hexadecimal data
T_STRHEX  = 22  // Hexadecimal string
```

---

## 3. Main Check Flow

```pascal
Procedure TestCompatibility(ver: integer; var errors, warn: tstringlist);
```

### Check Flow Overview:

1. **Script checks** (9067-9191)
   - Label 0 existence check
   - Opcode version compatibility
   - Parameter type check
   - Label reference verification

2. **Monster/NPC checks** (9196-9265)
   - NPC action label verification
   - Skin 51 special check
   - Floor-specific monster compatibility

3. **Object checks** (9266-9278)
   - Floor-specific object compatibility

4. **Quantity limit checks** (9279-9282)
   - Monster count > 400
   - Object count > 400

5. **Data label checks** (9286-9290)
   - Unused data labels

6. **File checks** (9293-9310)
   - .bin file required
   - .dat file recommended
   - .pvr file restrictions

---

## 4. Detailed Check Logic

### 4.1 Label 0 Check (9067-9068)

```pascal
// Quest entry point must exist
if LookForLabel2('0') = 0 then
    errors.Add(GetLanguageString(86));  // "Label 0 does not exist"
```

**Error message**: LanguageString[86]

---

### 4.2 Opcode Iteration Check (9070-9191)

For each script line:

```pascal
// Parse script line format: "label:  opcode args"
s := form4.ListBox1.Items.Strings[x];
delete(s, 1, 8);  // Skip first 8 characters (label part)
y := pos(' ', s);
if y > 0 then
    cmd := copy(s, 1, y - 1)  // Extract opcode
else
    cmd := s;
delete(s, 1, length(cmd) + 1);  // Remaining part is arguments
```

#### 4.2.1 Conversion Instruction Warning (9084-9091)

```pascal
// Condition: ver < 2 and order <> T_DC
if (asmcode[i].fnc in [$66,$6D,$79,$7C,$7D,$7F,$84,$87,$A8,$C0,$CD,$CE]) then
begin
    if (ver < 2) and (asmcode[i].order <> T_DC) then
        warn.Add(GetLanguageString(89) + cmd + GetLanguageString(87) + ' ' + inttostr(x));
end
```

**Warning message**: LanguageString[89] + opcode name + LanguageString[87] + line number

#### 4.2.2 Episode Parameter Check (9094-9100)

```pascal
// Opcode $F8BC = set_episode
if (asmcode[i].fnc = $F8BC) then
begin
    // V1/V2 only supports Episode 1
    if (ver < 2) and (s <> '00000000') then
        errors.Add(GetLanguageString(90) + s + GetLanguageString(87) + ' ' + inttostr(x));
    // PC version does not support Episode 4
    if (ver = 2) and (s = '00000002') then
        errors.Add(GetLanguageString(90) + s + GetLanguageString(87) + ' ' + inttostr(x));
end
```

**Episode parameters**:

- `00000000` = Episode 1
- `00000001` = Episode 2
- `00000002` = Episode 4

**Error message**: LanguageString[90] + parameter value + LanguageString[87] + line number

#### 4.2.3 Opcode Version Check (9103-9105)

```pascal
// Exclude special opcodes $D9 and $EF
if (asmcode[i].fnc <> $D9) and (asmcode[i].fnc <> $EF) then
    if asmcode[i].ver > ver then
        errors.Add(GetLanguageString(91) + cmd + GetLanguageString(87) + ' ' + inttostr(x));
```

**Error message**: LanguageString[91] + opcode name + LanguageString[87] + line number

#### 4.2.4 Special Opcode Warning (9107-9108)

```pascal
// $F8EE = call_image_data
if (asmcode[i].fnc = $F8EE) then
    warn.Add(GetLanguageString(92));
```

**Warning message**: LanguageString[92]

---

### 4.3 Parameter Check (9113-9189)

#### 4.3.1 Parameter Type Matching (9117-9119)

```pascal
// Only check: ver < 2 and opcode.ver < 2 and order = T_ARGS
if (asmcode[i].ver < 2) and (asmcode[i].order = T_ARGS) and (ver < 2) then
    if ((asmcode[i].arg[c] = T_REG) and (s[1] <> 'R')) or
       ((asmcode[i].arg[c] = T_DWORD) and (s[1] = 'R')) then
        errors.Add(GetLanguageString(93) + cmd + GetLanguageString(87) + ' ' + inttostr(x));
```

**Error message**: LanguageString[93] + opcode name + LanguageString[87] + line number

#### 4.3.2 Label Reference Check (9121-9128)

```pascal
// Parameter types: T_FUNC, T_FUNC2, T_DATA
if (asmcode[i].arg[c] = T_FUNC) or (asmcode[i].arg[c] = T_FUNC2) or
   (asmcode[i].arg[c] = T_DATA) then
begin
    l := pos(' ', s) - 2;
    if l <= 0 then l := length(s);
    if LookForLabel2(copy(s, 1, l)) = 0 then
        warn.Add(GetLanguageString(94) + ' ' + copy(s, 1, l) +
                 GetLanguageString(88) + ' ' + inttostr(x));
end;
```

**Warning message**: LanguageString[94] + label name + LanguageString[88] + line number

#### 4.3.3 Switch Statement Check (9131-9158)

```pascal
// Parameter type: T_SWITCH
// Format: "count:label1:label2:..."
if (asmcode[i].arg[c] = T_SWITCH) then
begin
    // Extract count
    k := strtoint(copy(b, 1, l - 1));

    // Verify each label
    for d := 1 to k do
    begin
        if b = '' then
        begin
            errors.Add('Array of function is missing entrys at line ' + inttostr(x));
            break;
        end;
        // Check if label exists
        if LookForLabel2(copy(b, 1, l)) = 0 then
            warn.Add(GetLanguageString(94) + ' ' + copy(b, 1, l) +
                     GetLanguageString(88) + ' ' + inttostr(x));
    end;

    // Check for extra entries
    if b <> '' then
        errors.Add('Array of function contain too many entrys at line ' + inttostr(x));
end;
```

**Error messages**:

- "Array of function is missing entrys at line " + line number
- "Array of function contain too many entrys at line " + line number

#### 4.3.4 String Parameter Check (9167-9189)

```pascal
// Parameter types: T_STR, T_STRDATA
// Check if <> markers are matched
c := 0;
for l := 1 to length(s) do
begin
    if s[l] = '<' then inc(c);
    if s[l] = '>' then dec(c);
end;
if c <> 0 then
    warn.Add(GetLanguageString(95) + ' ' + inttostr(x));
```

**Warning message**: LanguageString[95] + line number

---

### 4.4 NPC Action Label Check (9196-9236)

```pascal
ep := GetEpisode;  // Get current Episode

for x := 0 to 20 do  // Iterate all Floors
    if Form1.CheckListBox1.Checked[x] then
    begin
        for y := 0 to Floor[x].MonsterCount - 1 do
        begin
            // Check if it's an enemy
            for i := 0 to 57 do
                if EnemyID[i] = Floor[x].Monster[y].Skin then break;

            if i = 58 then  // Not an enemy, it's an NPC
            begin
                if round(Floor[x].Monster[y].Action) > 0 then
                begin
                    c := 0;

                    if ep = 1 then  // Episode 2
                    begin
                        // Check base labels [0-9]
                        for l := 0 to 9 do
                            if DefaultLabel2[l] = round(Floor[x].Monster[y].Action) then
                                c := 1;
                        // V3 additionally checks extended labels [10-18]
                        if ver = 3 then
                            for l := 10 to 18 do
                                if DefaultLabel2[l] = round(Floor[x].Monster[y].Action) then
                                    c := 1;
                    end
                    else  // Episode 1 or 4
                    begin
                        // Check base labels [0-12]
                        for l := 0 to 12 do
                            if DefaultLabel[l] = round(Floor[x].Monster[y].Action) then
                                c := 1;
                        // V3 additionally checks extended labels [13-21]
                        if ver = 3 then
                            for l := 13 to 21 do
                                if DefaultLabel[l] = round(Floor[x].Monster[y].Action) then
                                    c := 1;
                    end;

                    // Not a default label, check custom label
                    if c = 0 then
                        if LookForLabel2(inttostr(round(Floor[x].Monster[y].Action))) = 0 then
                            warn.Add(GetLanguageString(96) + ' ' +
                                     inttostr(round(Floor[x].Monster[y].Action)) +
                                     GetLanguageString(97) + inttostr(y) +
                                     GetLanguageString(98) + ' ' + inttostr(x));
                end;
            end;
        end;
    end;
```

**Warning message**: LanguageString[96] + label value + LanguageString[97] + monster index + LanguageString[98] + Floor
index

---

### 4.5 Skin 51 Special Check (9237-9254)

```pascal
if Floor[x].Monster[y].Skin = 51 then
begin
    if ver < 2 then
        // V1 version does not support Skin 51
        warn.Add(GetLanguageString(99) + ' ' + inttostr(Floor[x].Monster[y].Skin) +
                 GetLanguageString(100) + inttostr(y) +
                 GetLanguageString(98) + ' ' + inttostr(x))
    else if ep = 2 then
        // Skin 51 may have issues in Episode 4
        warn.Add(GetLanguageString(99) + ' ' + inttostr(Floor[x].Monster[y].Skin) +
                 GetLanguageString(100) + inttostr(y) +
                 GetLanguageString(98) + ' ' + inttostr(x))
    else
    begin
        // Verify subtype
        if Floor[x].Monster[y].unknow7 > 15 then
            errors.Add(GetLanguageString(101) + inttostr(y) +
                       GetLanguageString(98) + ' ' + inttostr(x))
        // Verify NPC51Name table
        else if (NPC51Name[Floor[x].floorid, Floor[x].Monster[y].unknow7] = 'CRASH') or
                (NPC51Name[Floor[x].floorid, Floor[x].Monster[y].unknow7] = '') then
            errors.Add(GetLanguageString(101) + inttostr(y) +
                       GetLanguageString(98) + ' ' + inttostr(x));
    end;
end;
```

**Skin 51 check rules**:
| Condition | Result |
|-----------|--------|
| ver < 2 | Warning |
| ep = 2 (Episode 4) | Warning |
| unknow7 > 15 | Error |
| NPC51Name is empty or "CRASH" | Error |

---

### 4.6 Floor-Specific Monster Check (9255-9264)

```pascal
if Floor[x].floorid < 50 then
begin
    for i := 0 to FloorMonsID[Floor[x].floorid].count[ver] - 1 do
        if FloorMonsID[Floor[x].floorid].ids[ver, i] = Floor[x].Monster[y].Skin then
            break;
    if (i = FloorMonsID[Floor[x].floorid].count[ver]) and
       (FloorMonsID[Floor[x].floorid].count[ver] <> 0) then
        warn.Add(GetLanguageString(99) + ' ' + inttostr(Floor[x].Monster[y].Skin) +
                 GetLanguageString(102) + inttostr(y) +
                 GetLanguageString(98) + ' ' + inttostr(x));
end;
```

**Warning message**: LanguageString[99] + Skin value + LanguageString[102] + monster index + LanguageString[98] + Floor
index

---

### 4.7 Object Check (9266-9278)

```pascal
for y := 0 to Floor[x].ObjCount - 1 do
begin
    if Floor[x].floorid < 50 then
    begin
        for i := 0 to FloorObjID[Floor[x].floorid].count[ver] - 1 do
            if FloorObjID[Floor[x].floorid].ids[ver, i] = Floor[x].Obj[y].Skin then
                break;
        if (i = FloorObjID[Floor[x].floorid].count[ver]) and
           (FloorObjID[Floor[x].floorid].count[ver] <> 0) then
            warn.Add(GetLanguageString(103) + ' ' + inttostr(Floor[x].Obj[y].Skin) +
                     GetLanguageString(102) + inttostr(y) +
                     GetLanguageString(98) + ' ' + inttostr(x));
    end;
end;
```

**Warning message**: LanguageString[103] + Skin value + LanguageString[102] + object index + LanguageString[98] + Floor
index

---

### 4.8 Quantity Limit Check (9279-9282)

```pascal
if Floor[x].ObjCount > 400 then
    warn.Add(GetLanguageString(104) + ' ' + inttostr(x) + GetLanguageString(105));
if Floor[x].MonsterCount > 400 then
    warn.Add(GetLanguageString(104) + ' ' + inttostr(x) + GetLanguageString(106));
```

**Warning messages**:

- Too many objects: LanguageString[104] + Floor index + LanguageString[105]
- Too many monsters: LanguageString[104] + Floor index + LanguageString[106]

---

### 4.9 Unused Data Label Check (9286-9290)

```pascal
for x := 0 to TsData.count - 1 do
begin
    if GetReferenceType(strtoint(copy(TsData.Strings[x], 3, length(TsData.Strings[x]) - 2))) = 0 then
        warn.Add(GetLanguageString(107) + ' ' + TsData.Strings[x]);
end;
```

**Warning message**: LanguageString[107] + data label name

---

### 4.10 File Check (9293-9310)

```pascal
// Check .bin file (required)
for x := 0 to qstfilecount - 1 do
    if pos('.bin', lowercase(qstfile[x].name)) > 0 then break;
if x = qstfilecount then
    errors.Add(GetLanguageString(108));

// Check .dat file (recommended)
for x := 0 to qstfilecount - 1 do
    if pos('.dat', lowercase(qstfile[x].name)) > 0 then break;
if x = qstfilecount then
    warn.Add(GetLanguageString(109));

// Check .pvr file (V2+ not supported)
for x := 0 to qstfilecount - 1 do
    if pos('.pvr', lowercase(qstfile[x].name)) > 0 then break;
if ver > 1 then
    if x < qstfilecount then
        errors.Add(GetLanguageString(110));
```

**File check rules**:
| File Type | Condition | Result |
|-----------|-----------|--------|
| .bin | Does not exist | Error (LanguageString[108]) |
| .dat | Does not exist | Warning (LanguageString[109]) |
| .pvr | Exists and ver>1 | Error (LanguageString[110]) |

---

## 5. Helper Functions

### 5.1 GetReferenceType (FScrypt.pas:1201-1267)

Returns the reference type of a data label:

```pascal
Function GetReferenceType(x:integer):integer;
// Return values:
// 0  = Unused
// 1  = NPC data (get_npc_data / $F841)
// 2  = Code (label definition for non-HEX data)
// 3  = Image data (call_image_data / $F8EE)
// 4  = String data (STR:)
// 5  = Enemy physical data (get_physical_data / $F892)
// 6  = Enemy resistance data (get_resist_data / $F894)
// 7  = Enemy attack data (get_attack_data / $F893)
// 8  = Enemy movement data (get_movement_data / $F895)
// 10 = Vector data ($F8F2, $F8DB)
```

**Check order**:

1. Check label definition line (HEX: / STR:)
2. Check get_npc_data ($F841) reference
3. Check get_physical_data ($F892) reference
4. Check get_movement_data ($F895) reference
5. Check get_resist_data ($F894) reference
6. Check get_attack_data ($F893) reference
7. Check call_image_data ($F8EE) reference
8. Check vector data reference ($F8F2, $F8DB)

### 5.2 GetEpisode (FScrypt.pas:1269-1281)

```pascal
Function GetEpisode:integer;
// Find set_episode opcode ($F8BC)
// Return values:
// 0 = Episode 1 (parameter 00000000)
// 1 = Episode 2 (parameter 00000001)
// 2 = Episode 4 (parameter 00000002)
```

### 5.3 LookForLabel2 (main.pas)

```pascal
function LookForLabel2(s: string): integer;
// Check if label exists
// Return values:
// 0 = Does not exist
// >0 = Exists (returns line number)
```

---

## 6. Language String Mapping

| Index | Purpose                   | Example Content                           |
|-------|---------------------------|-------------------------------------------|
| 86    | Label 0 does not exist    | "Label 0 does not exist"                  |
| 87    | "at line"                 | " at line "                               |
| 88    | "in function"             | " in function"                            |
| 89    | Conversion opcode warning | "Conversion opcode warning: "             |
| 90    | Episode parameter error   | "Episode parameter "                      |
| 91    | Opcode version error      | "Opcode requires newer version: "         |
| 92    | Special opcode warning    | "Warning: 0xF8EE opcode detected"         |
| 93    | Parameter type mismatch   | "Argument type mismatch: "                |
| 94    | Label not found           | "Label not found: "                       |
| 95    | String marker mismatch    | "Unmatched string markers '<>'"           |
| 96    | NPC action label          | "NPC action label "                       |
| 97    | Monster number            | " monster #"                              |
| 98    | Floor number              | " on floor "                              |
| 99    | Skin version warning      | "Skin "                                   |
| 100   | "in monster"              | " in monster #"                           |
| 101   | Skin 51 invalid subtype   | "Skin 51 invalid subtype"                 |
| 102   | "may not spawn correctly" | " may not spawn correctly"                |
| 103   | Object Floor warning      | "Object "                                 |
| 104   | Floor number prefix       | "Floor "                                  |
| 105   | Too many objects          | " has too many objects"                   |
| 106   | Too many monsters         | " has too many monsters"                  |
| 107   | Unused data label         | "Unused data label: "                     |
| 108   | Missing .bin file         | "Quest must contain a .bin file"          |
| 109   | Missing .dat file         | "Quest does not contain a .dat file"      |
| 110   | .pvr not supported        | ".pvr texture files not supported in V2+" |

---

## 7. Data Structures

### 7.1 FloorIDData (main.pas:548)

```pascal
TFloorIDData = record
    count: array[0..3] of integer;      // Allowed count per version
    ids: array[0..3, 0..99] of integer; // Allowed ID list per version
end;

FloorMonsID, FloorObjID: array [0..50] of TFloorIDData;
```

### 7.2 Monster Structure

```pascal
TMonster = record
    Skin: integer;      // Appearance ID
    Action: single;     // Action label ID (float)
    unknow7: integer;   // Subtype (used by Skin 51)
    // ... other fields
end;
```

### 7.3 Floor Structure

```pascal
TFloor = record
    floorid: integer;
    MonsterCount: integer;
    Monster: array of TMonster;
    ObjCount: integer;
    Obj: array of TFloorObject;
end;
```

---

## 8. Check Flow Diagram

```
TestCompatibility(ver)
├── 1. Label 0 Check
│   └── Does not exist → Error
│
├── 2. Script Iteration
│   ├── 2.1 Conversion Instruction Check
│   │   └── ver<2 and order≠T_DC → Warning
│   ├── 2.2 Episode Parameter Check
│   │   ├── ver<2 and parameter≠00000000 → Error
│   │   └── ver=2 and parameter=00000002 → Error
│   ├── 2.3 Opcode Version Check
│   │   └── opcode.ver > ver → Error
│   ├── 2.4 Special Opcode Check
│   │   └── $F8EE → Warning
│   └── 2.5 Parameter Check
│       ├── Type mismatch → Error
│       ├── Label does not exist → Warning
│       ├── Switch array error → Error
│       └── String marker mismatch → Warning
│
├── 3. Floor Iteration
│   ├── 3.1 NPC Action Label Check
│   │   └── Label does not exist → Warning
│   ├── 3.2 Skin 51 Check
│   │   ├── ver<2 → Warning
│   │   ├── ep=2 → Warning
│   │   └── Invalid subtype → Error
│   ├── 3.3 Floor Monster Compatibility
│   │   └── Skin not in allowed list → Warning
│   ├── 3.4 Floor Object Compatibility
│   │   └── Skin not in allowed list → Warning
│   └── 3.5 Quantity Limits
│       ├── MonsterCount>400 → Warning
│       └── ObjCount>400 → Warning
│
├── 4. Data Label Check
│   └── Unused → Warning
│
└── 5. File Check
    ├── No .bin → Error
    ├── No .dat → Warning
    └── ver>1 and has .pvr → Error
```

---

## 9. Key Notes

1. **Script line format**: First 8 characters are the label part, code starts from the 9th character
2. **Case sensitivity**: Opcode name comparison uses `lowercase()` for case-insensitive matching
3. **Version parameters**:
    - 0 = DC V1, 1 = DC V2, 2 = PC, 3 = GC
   - Blue Burst reuses DC V2 check results
4. **Episode detection**: Determined by finding the `set_episode` opcode ($F8BC)
5. **Floor data**: Loaded from FloorSet.ini, stores allowed monster/object IDs per version
6. **NPC51Name**: Defined in MyConst.pas, stores Skin 51 subtype names

---

## 10. Version Compatibility Summary

| Check Item              | DC V1   | DC V2 | PC | GC |
|-------------------------|---------|-------|----|----|
| Episode 1               | ✓       | ✓     | ✓  | ✓  |
| Episode 2               | ✗       | ✓     | ✓  | ✓  |
| Episode 4               | ✗       | ✗     | ✗  | ✓  |
| Skin 51                 | ✗       | ✓     | ✓  | ✓  |
| Extended default labels | ✗       | ✗     | ✗  | ✓  |
| .pvr files              | ✓       | ✓     | ✗  | ✗  |
| Conversion instructions | Warning | ✓     | ✓  | ✓  |