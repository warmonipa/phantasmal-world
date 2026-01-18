# PSO Quest 兼容性检查逻辑文档

## 概述
此文档详细描述PSO Quest Editor的兼容性检查算法，用于验证任务文件是否与不同PSO版本兼容。

**源代码位置**: `main.pas` 第9053-9312行 (`TestCompatibility` 过程)

---

## 1. 版本定义

```pascal
// 版本参数 ver 的取值:
// 0 = DC V1    (Dreamcast V1)
// 1 = DC V2    (Dreamcast V2)
// 2 = PC       (PC版本)
// 3 = GC EP1&2 (GameCube Episode 1&2)

// 注意: Blue Burst 使用 DC V2 (ver=1) 的结果
// 见 Compatibilitycheck1Click: form27.er[2] := form27.er[1]
```

**版本显示顺序** (Form27.ListBox1):
| 索引 | 版本名称 | ver参数 |
|------|----------|---------|
| 0 | DC V1 | 0 |
| 1 | DC V2 | 1 |
| 2 | Blue Burst | (复制DC V2结果) |
| 3 | PC | 2 |
| 4 | GC EP1&2 | 3 |

---

## 2. 常量定义

### 2.1 默认NPC动作标签 (main.pas:9055-9058)

```pascal
// Episode 1/4 默认标签 (22个)
// 前13个 (0-12) 用于所有版本
// 后9个 (13-21) 仅 ver=3 (GC) 可用
DefaultLabel: array [0..21] of integer = (
  100, 90, 120, 130, 80, 70, 60, 140, 110, 30, 50, 1, 20,  // 基础 [0-12]
  850, 800, 830, 820, 810, 860, 870, 840, 880              // V3扩展 [13-21]
);

// Episode 2 默认标签 (19个)
// 前10个 (0-9) 用于所有版本
// 后9个 (10-18) 仅 ver=3 (GC) 可用
DefaultLabel2: array [0..18] of integer = (
  720, 660, 620, 600, 501, 520, 560, 540, 580, 680,  // 基础 [0-9]
  950, 900, 930, 920, 910, 960, 970, 940, 980        // V3扩展 [10-18]
);
```

### 2.2 敌人ID列表 (main.pas:47-49)

```pascal
// 58个敌人Skin ID - 用于区分敌人和NPC
EnemyID: array [0..57] of integer = (
  68, 67, 64, 65, 128, 129, 131, 133, 163, 97, 99, 98, 96,
  168, 166, 165, 160, 162, 164, 192, 197, 193, 194, 200,
  66, 132, 130, 100, 101, 161, 167, 223, 213, 212, 215,
  217, 218, 214, 222, 221, 225, 224, 216, 219, 220, 202,
  201, 203, 204, 273, 277, 276, 272, 278, 274, 275, 281, 249
);
```

### 2.3 转换指令操作码 (main.pas:9084-9086)

```pascal
// 这些操作码在V1版本中可能有参数转换问题
ConvertOpcodes = [$66, $6D, $79, $7C, $7D, $7F, $84, $87, $A8, $C0, $CD, $CE]
```

### 2.4 参数类型常量 (Unit1.pas)

```pascal
T_NONE    = 0   // 无参数
T_IMED    = 1   // 立即数
T_ARGS    = 2   // 参数模式
T_PUSH    = 3   // 压栈
T_VASTART = 4   // 可变参数开始
T_VAEND   = 5   // 可变参数结束
T_DC      = 6   // DC专用

T_REG     = 7   // 寄存器 (R0-R255)
T_BYTE    = 8   // 字节值
T_WORD    = 9   // 字值
T_DWORD   = 10  // 双字值
T_FLOAT   = 11  // 浮点数
T_STR     = 12  // 字符串

T_RREG    = 13  // 寄存器引用
T_FUNC    = 14  // 函数标签
T_FUNC2   = 15  // 函数标签2
T_SWITCH  = 16  // Switch语句
T_SWITCH2B= 17  // Switch 2字节
T_PFLAG   = 18  // 标志

T_STRDATA = 19  // 字符串数据
T_DATA    = 20  // 数据标签
T_HEX     = 21  // 十六进制数据
T_STRHEX  = 22  // 十六进制字符串
```

---

## 3. 主检查流程

```pascal
Procedure TestCompatibility(ver: integer; var errors, warn: tstringlist);
```

### 检查流程概述:
1. **脚本检查** (9067-9191)
   - Label 0 存在性检查
   - 操作码版本兼容性
   - 参数类型检查
   - 标签引用验证

2. **怪物/NPC检查** (9196-9265)
   - NPC动作标签验证
   - Skin 51 特殊检查
   - Floor特定怪物兼容性

3. **对象检查** (9266-9278)
   - Floor特定对象兼容性

4. **数量限制检查** (9279-9282)
   - 怪物数量 > 400
   - 对象数量 > 400

5. **数据标签检查** (9286-9290)
   - 未使用的数据标签

6. **文件检查** (9293-9310)
   - .bin文件必需
   - .dat文件推荐
   - .pvr文件限制

---

## 4. 详细检查逻辑

### 4.1 Label 0 检查 (9067-9068)

```pascal
// 任务入口点必须存在
if LookForLabel2('0') = 0 then
    errors.Add(GetLanguageString(86));  // "Label 0 does not exist"
```

**错误信息**: LanguageString[86]

---

### 4.2 操作码遍历检查 (9070-9191)

对于每一行脚本:

```pascal
// 解析脚本行格式: "label:  opcode args"
s := form4.ListBox1.Items.Strings[x];
delete(s, 1, 8);  // 跳过前8个字符(标签部分)
y := pos(' ', s);
if y > 0 then
    cmd := copy(s, 1, y - 1)  // 提取操作码
else
    cmd := s;
delete(s, 1, length(cmd) + 1);  // 剩余部分为参数
```

#### 4.2.1 转换指令警告 (9084-9091)

```pascal
// 条件: ver < 2 且 order <> T_DC
if (asmcode[i].fnc in [$66,$6D,$79,$7C,$7D,$7F,$84,$87,$A8,$C0,$CD,$CE]) then
begin
    if (ver < 2) and (asmcode[i].order <> T_DC) then
        warn.Add(GetLanguageString(89) + cmd + GetLanguageString(87) + ' ' + inttostr(x));
end
```

**警告信息**: LanguageString[89] + 操作码名 + LanguageString[87] + 行号

#### 4.2.2 Episode参数检查 (9094-9100)

```pascal
// 操作码 $F8BC = set_episode
if (asmcode[i].fnc = $F8BC) then
begin
    // V1/V2 只支持 Episode 1
    if (ver < 2) and (s <> '00000000') then
        errors.Add(GetLanguageString(90) + s + GetLanguageString(87) + ' ' + inttostr(x));
    // PC版本不支持 Episode 4
    if (ver = 2) and (s = '00000002') then
        errors.Add(GetLanguageString(90) + s + GetLanguageString(87) + ' ' + inttostr(x));
end
```

**Episode参数**:
- `00000000` = Episode 1
- `00000001` = Episode 2
- `00000002` = Episode 4

**错误信息**: LanguageString[90] + 参数值 + LanguageString[87] + 行号

#### 4.2.3 操作码版本检查 (9103-9105)

```pascal
// 排除特殊操作码 $D9 和 $EF
if (asmcode[i].fnc <> $D9) and (asmcode[i].fnc <> $EF) then
    if asmcode[i].ver > ver then
        errors.Add(GetLanguageString(91) + cmd + GetLanguageString(87) + ' ' + inttostr(x));
```

**错误信息**: LanguageString[91] + 操作码名 + LanguageString[87] + 行号

#### 4.2.4 特殊操作码警告 (9107-9108)

```pascal
// $F8EE = call_image_data
if (asmcode[i].fnc = $F8EE) then
    warn.Add(GetLanguageString(92));
```

**警告信息**: LanguageString[92]

---

### 4.3 参数检查 (9113-9189)

#### 4.3.1 参数类型匹配 (9117-9119)

```pascal
// 仅检查: ver < 2 且 opcode.ver < 2 且 order = T_ARGS
if (asmcode[i].ver < 2) and (asmcode[i].order = T_ARGS) and (ver < 2) then
    if ((asmcode[i].arg[c] = T_REG) and (s[1] <> 'R')) or
       ((asmcode[i].arg[c] = T_DWORD) and (s[1] = 'R')) then
        errors.Add(GetLanguageString(93) + cmd + GetLanguageString(87) + ' ' + inttostr(x));
```

**错误信息**: LanguageString[93] + 操作码名 + LanguageString[87] + 行号

#### 4.3.2 标签引用检查 (9121-9128)

```pascal
// 参数类型: T_FUNC, T_FUNC2, T_DATA
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

**警告信息**: LanguageString[94] + 标签名 + LanguageString[88] + 行号

#### 4.3.3 Switch语句检查 (9131-9158)

```pascal
// 参数类型: T_SWITCH
// 格式: "count:label1:label2:..."
if (asmcode[i].arg[c] = T_SWITCH) then
begin
    // 提取count
    k := strtoint(copy(b, 1, l - 1));

    // 验证每个标签
    for d := 1 to k do
    begin
        if b = '' then
        begin
            errors.Add('Array of function is missing entrys at line ' + inttostr(x));
            break;
        end;
        // 检查标签是否存在
        if LookForLabel2(copy(b, 1, l)) = 0 then
            warn.Add(GetLanguageString(94) + ' ' + copy(b, 1, l) +
                     GetLanguageString(88) + ' ' + inttostr(x));
    end;

    // 检查是否有多余条目
    if b <> '' then
        errors.Add('Array of function contain too many entrys at line ' + inttostr(x));
end;
```

**错误信息**:
- "Array of function is missing entrys at line " + 行号
- "Array of function contain too many entrys at line " + 行号

#### 4.3.4 字符串参数检查 (9167-9189)

```pascal
// 参数类型: T_STR, T_STRDATA
// 检查 <> 标记是否匹配
c := 0;
for l := 1 to length(s) do
begin
    if s[l] = '<' then inc(c);
    if s[l] = '>' then dec(c);
end;
if c <> 0 then
    warn.Add(GetLanguageString(95) + ' ' + inttostr(x));
```

**警告信息**: LanguageString[95] + 行号

---

### 4.4 NPC动作标签检查 (9196-9236)

```pascal
ep := GetEpisode;  // 获取当前Episode

for x := 0 to 20 do  // 遍历所有Floor
    if Form1.CheckListBox1.Checked[x] then
    begin
        for y := 0 to Floor[x].MonsterCount - 1 do
        begin
            // 检查是否为敌人
            for i := 0 to 57 do
                if EnemyID[i] = Floor[x].Monster[y].Skin then break;

            if i = 58 then  // 不是敌人，是NPC
            begin
                if round(Floor[x].Monster[y].Action) > 0 then
                begin
                    c := 0;

                    if ep = 1 then  // Episode 2
                    begin
                        // 检查基础标签 [0-9]
                        for l := 0 to 9 do
                            if DefaultLabel2[l] = round(Floor[x].Monster[y].Action) then
                                c := 1;
                        // V3额外检查扩展标签 [10-18]
                        if ver = 3 then
                            for l := 10 to 18 do
                                if DefaultLabel2[l] = round(Floor[x].Monster[y].Action) then
                                    c := 1;
                    end
                    else  // Episode 1 或 4
                    begin
                        // 检查基础标签 [0-12]
                        for l := 0 to 12 do
                            if DefaultLabel[l] = round(Floor[x].Monster[y].Action) then
                                c := 1;
                        // V3额外检查扩展标签 [13-21]
                        if ver = 3 then
                            for l := 13 to 21 do
                                if DefaultLabel[l] = round(Floor[x].Monster[y].Action) then
                                    c := 1;
                    end;

                    // 不是默认标签，检查自定义标签
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

**警告信息**: LanguageString[96] + 标签值 + LanguageString[97] + 怪物索引 + LanguageString[98] + Floor索引

---

### 4.5 Skin 51 特殊检查 (9237-9254)

```pascal
if Floor[x].Monster[y].Skin = 51 then
begin
    if ver < 2 then
        // V1版本不支持Skin 51
        warn.Add(GetLanguageString(99) + ' ' + inttostr(Floor[x].Monster[y].Skin) +
                 GetLanguageString(100) + inttostr(y) +
                 GetLanguageString(98) + ' ' + inttostr(x))
    else if ep = 2 then
        // Episode 4 中Skin 51可能有问题
        warn.Add(GetLanguageString(99) + ' ' + inttostr(Floor[x].Monster[y].Skin) +
                 GetLanguageString(100) + inttostr(y) +
                 GetLanguageString(98) + ' ' + inttostr(x))
    else
    begin
        // 验证子类型
        if Floor[x].Monster[y].unknow7 > 15 then
            errors.Add(GetLanguageString(101) + inttostr(y) +
                       GetLanguageString(98) + ' ' + inttostr(x))
        // 验证NPC51Name表
        else if (NPC51Name[Floor[x].floorid, Floor[x].Monster[y].unknow7] = 'CRASH') or
                (NPC51Name[Floor[x].floorid, Floor[x].Monster[y].unknow7] = '') then
            errors.Add(GetLanguageString(101) + inttostr(y) +
                       GetLanguageString(98) + ' ' + inttostr(x));
    end;
end;
```

**Skin 51 检查规则**:
| 条件 | 结果 |
|------|------|
| ver < 2 | 警告 |
| ep = 2 (Episode 4) | 警告 |
| unknow7 > 15 | 错误 |
| NPC51Name为空或"CRASH" | 错误 |

---

### 4.6 Floor特定怪物检查 (9255-9264)

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

**警告信息**: LanguageString[99] + Skin值 + LanguageString[102] + 怪物索引 + LanguageString[98] + Floor索引

---

### 4.7 对象检查 (9266-9278)

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

**警告信息**: LanguageString[103] + Skin值 + LanguageString[102] + 对象索引 + LanguageString[98] + Floor索引

---

### 4.8 数量限制检查 (9279-9282)

```pascal
if Floor[x].ObjCount > 400 then
    warn.Add(GetLanguageString(104) + ' ' + inttostr(x) + GetLanguageString(105));
if Floor[x].MonsterCount > 400 then
    warn.Add(GetLanguageString(104) + ' ' + inttostr(x) + GetLanguageString(106));
```

**警告信息**:
- 对象过多: LanguageString[104] + Floor索引 + LanguageString[105]
- 怪物过多: LanguageString[104] + Floor索引 + LanguageString[106]

---

### 4.9 未使用数据标签检查 (9286-9290)

```pascal
for x := 0 to TsData.count - 1 do
begin
    if GetReferenceType(strtoint(copy(TsData.Strings[x], 3, length(TsData.Strings[x]) - 2))) = 0 then
        warn.Add(GetLanguageString(107) + ' ' + TsData.Strings[x]);
end;
```

**警告信息**: LanguageString[107] + 数据标签名

---

### 4.10 文件检查 (9293-9310)

```pascal
// 检查 .bin 文件 (必需)
for x := 0 to qstfilecount - 1 do
    if pos('.bin', lowercase(qstfile[x].name)) > 0 then break;
if x = qstfilecount then
    errors.Add(GetLanguageString(108));

// 检查 .dat 文件 (推荐)
for x := 0 to qstfilecount - 1 do
    if pos('.dat', lowercase(qstfile[x].name)) > 0 then break;
if x = qstfilecount then
    warn.Add(GetLanguageString(109));

// 检查 .pvr 文件 (V2+不支持)
for x := 0 to qstfilecount - 1 do
    if pos('.pvr', lowercase(qstfile[x].name)) > 0 then break;
if ver > 1 then
    if x < qstfilecount then
        errors.Add(GetLanguageString(110));
```

**文件检查规则**:
| 文件类型 | 条件 | 结果 |
|----------|------|------|
| .bin | 不存在 | 错误 (LanguageString[108]) |
| .dat | 不存在 | 警告 (LanguageString[109]) |
| .pvr | 存在且ver>1 | 错误 (LanguageString[110]) |

---

## 5. 辅助函数

### 5.1 GetReferenceType (FScrypt.pas:1201-1267)

返回数据标签的引用类型:

```pascal
Function GetReferenceType(x:integer):integer;
// 返回值:
// 0  = 未使用
// 1  = NPC数据 (get_npc_data / $F841)
// 2  = 代码 (非HEX数据的标签定义)
// 3  = 图像数据 (call_image_data / $F8EE)
// 4  = 字符串数据 (STR:)
// 5  = 敌人物理数据 (get_physical_data / $F892)
// 6  = 敌人抗性数据 (get_resist_data / $F894)
// 7  = 敌人攻击数据 (get_attack_data / $F893)
// 8  = 敌人移动数据 (get_movement_data / $F895)
// 10 = 向量数据 ($F8F2, $F8DB)
```

**检查顺序**:
1. 检查标签定义行 (HEX: / STR:)
2. 检查 get_npc_data ($F841) 引用
3. 检查 get_physical_data ($F892) 引用
4. 检查 get_movement_data ($F895) 引用
5. 检查 get_resist_data ($F894) 引用
6. 检查 get_attack_data ($F893) 引用
7. 检查 call_image_data ($F8EE) 引用
8. 检查向量数据引用 ($F8F2, $F8DB)

### 5.2 GetEpisode (FScrypt.pas:1269-1281)

```pascal
Function GetEpisode:integer;
// 查找 set_episode 操作码 ($F8BC)
// 返回值:
// 0 = Episode 1 (参数 00000000)
// 1 = Episode 2 (参数 00000001)
// 2 = Episode 4 (参数 00000002)
```

### 5.3 LookForLabel2 (main.pas)

```pascal
function LookForLabel2(s: string): integer;
// 查找标签是否存在
// 返回值:
// 0 = 不存在
// >0 = 存在 (返回行号)
```

---

## 6. 语言字符串映射

| 索引 | 用途 | 示例内容 |
|------|------|----------|
| 86 | Label 0不存在 | "Label 0 does not exist" |
| 87 | "在行" | " at line " |
| 88 | "在函数中" | " in function" |
| 89 | 转换指令警告 | "Conversion opcode warning: " |
| 90 | Episode参数错误 | "Episode parameter " |
| 91 | 操作码版本错误 | "Opcode requires newer version: " |
| 92 | 特殊操作码警告 | "Warning: 0xF8EE opcode detected" |
| 93 | 参数类型不匹配 | "Argument type mismatch: " |
| 94 | 标签未找到 | "Label not found: " |
| 95 | 字符串标记不匹配 | "Unmatched string markers '<>'" |
| 96 | NPC动作标签 | "NPC action label " |
| 97 | 怪物编号 | " monster #" |
| 98 | Floor编号 | " on floor " |
| 99 | Skin版本警告 | "Skin " |
| 100 | "在怪物中" | " in monster #" |
| 101 | Skin51无效子类型 | "Skin 51 invalid subtype" |
| 102 | "可能无法生成" | " may not spawn correctly" |
| 103 | 对象Floor警告 | "Object " |
| 104 | Floor编号前缀 | "Floor " |
| 105 | 对象过多 | " has too many objects" |
| 106 | 怪物过多 | " has too many monsters" |
| 107 | 未使用数据标签 | "Unused data label: " |
| 108 | 缺少.bin文件 | "Quest must contain a .bin file" |
| 109 | 缺少.dat文件 | "Quest does not contain a .dat file" |
| 110 | .pvr不支持 | ".pvr texture files not supported in V2+" |

---

## 7. 数据结构

### 7.1 FloorIDData (main.pas:548)

```pascal
TFloorIDData = record
    count: array[0..3] of integer;      // 每个版本允许的数量
    ids: array[0..3, 0..99] of integer; // 每个版本允许的ID列表
end;

FloorMonsID, FloorObjID: array [0..50] of TFloorIDData;
```

### 7.2 Monster结构

```pascal
TMonster = record
    Skin: integer;      // 外观ID
    Action: single;     // 动作标签ID (浮点数)
    unknow7: integer;   // 子类型 (Skin 51使用)
    // ... 其他字段
end;
```

### 7.3 Floor结构

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

## 8. 检查流程图

```
TestCompatibility(ver)
├── 1. Label 0 检查
│   └── 不存在 → 错误
│
├── 2. 脚本遍历
│   ├── 2.1 转换指令检查
│   │   └── ver<2 且 order≠T_DC → 警告
│   ├── 2.2 Episode参数检查
│   │   ├── ver<2 且 参数≠00000000 → 错误
│   │   └── ver=2 且 参数=00000002 → 错误
│   ├── 2.3 操作码版本检查
│   │   └── opcode.ver > ver → 错误
│   ├── 2.4 特殊操作码检查
│   │   └── $F8EE → 警告
│   └── 2.5 参数检查
│       ├── 类型不匹配 → 错误
│       ├── 标签不存在 → 警告
│       ├── Switch数组错误 → 错误
│       └── 字符串标记不匹配 → 警告
│
├── 3. Floor遍历
│   ├── 3.1 NPC动作标签检查
│   │   └── 标签不存在 → 警告
│   ├── 3.2 Skin 51检查
│   │   ├── ver<2 → 警告
│   │   ├── ep=2 → 警告
│   │   └── 无效子类型 → 错误
│   ├── 3.3 Floor怪物兼容性
│   │   └── Skin不在允许列表 → 警告
│   ├── 3.4 Floor对象兼容性
│   │   └── Skin不在允许列表 → 警告
│   └── 3.5 数量限制
│       ├── MonsterCount>400 → 警告
│       └── ObjCount>400 → 警告
│
├── 4. 数据标签检查
│   └── 未使用 → 警告
│
└── 5. 文件检查
    ├── 无.bin → 错误
    ├── 无.dat → 警告
    └── ver>1 且 有.pvr → 错误
```

---

## 9. 关键注意事项

1. **脚本行格式**: 前8个字符是标签部分，从第9个字符开始是代码
2. **大小写**: 操作码名称比较时使用 `lowercase()` 不区分大小写
3. **版本参数**:
   - 0 = DC V1, 1 = DC V2, 2 = PC, 3 = GC
   - Blue Burst 复用 DC V2 的检查结果
4. **Episode检测**: 通过查找 `set_episode` 操作码 ($F8BC) 确定
5. **Floor数据**: 从 FloorSet.ini 加载，存储每个版本允许的怪物/对象ID
6. **NPC51Name**: 从 MyConst.pas 定义，存储Skin 51的子类型名称

---

## 10. 版本兼容性汇总

| 检查项 | DC V1 | DC V2 | PC | GC |
|--------|-------|-------|----|----|
| Episode 1 | ✓ | ✓ | ✓ | ✓ |
| Episode 2 | ✗ | ✓ | ✓ | ✓ |
| Episode 4 | ✗ | ✗ | ✗ | ✓ |
| Skin 51 | ✗ | ✓ | ✓ | ✓ |
| 扩展默认标签 | ✗ | ✗ | ✗ | ✓ |
| .pvr文件 | ✓ | ✓ | ✗ | ✗ |
| 转换指令 | 警告 | ✓ | ✓ | ✓ |