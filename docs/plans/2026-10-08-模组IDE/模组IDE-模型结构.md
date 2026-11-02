# 模组 IDE —— **模型结构**（主稿 · 唯一规格）

> **状态：分析稿（未碰产品代码）。** 落点 `docs/plans/2026-10-08-模组IDE/`（2026-11-02 从仓库根搬入）。
> 本稿是**模型层（冻结层）的唯一真相**，把此前 4 份稿子里的模型内容**并成一份**；
> 界面细节、调研依据分别留在各自的稿子里（见 §0.1 的文档地图）。
> - 日期：2026-10-08 · 结构决定：**丙（独立工坊 + 双向搬运）** —— 用户已拍板
> - 被本稿吸收：`整体结构`（层级/分叉/文件视图）· `详细定案-1-契约与模型`（全部）· `形态定案`（分层与建设顺序）

---

## 0. 一页看懂

### 0.1 文档地图（**以后只看这张表**）

| 稿子 | 角色 | 状态 |
|---|---|---|
| ★ **`模组IDE-模型结构.md`**（本稿） | **规格**：模型层全部 | ✅ **唯一真相** |
| `模组IDE-详细定案-2-界面与实施.md` | **规格**：视图层（布局/文案/自检/DoD） | ✅ 仍是唯一真相（模型改动要同步它） |
| `模组IDE-整体结构.md` | 结构推导过程（甲/乙/丙对比、为什么这么选） | 📦 **已并入本稿 §1/§6**；保留作"为什么"的追溯 |
| `模组IDE-详细定案-1-契约与模型.md` | 模型层旧稿 | 📦 **已被本稿取代** |
| `模组IDE-形态定案.md` | 思路稿（冻结层 vs 免责层、六步顺序） | 📦 已并入本稿 §0.2/§7 |
| `像素画编辑器评估稿.md` | **依据**：Android PNG → arc 解码实测、原版尺寸普查 | 📎 证据（不下规格） |
| `像素画编辑器-面板功能清单.md` | **依据**：2201 张原版贴图的画风实测 ⇒ 画板要哪些功能 | 📎 证据 |
| `像素画编辑器-开源可抄清单.md` | **依据**：可抄哪一层（`com.ansdoship` GPL-3.0） | 📎 证据 |
| `像素画编辑器-嵌入与难度.md` | **依据**：难度分解、为什么不该"互相嵌入" | 📎 证据 |
| `模组编辑器评估稿.md` / `模组编辑器定案稿.md` | **依据**：hjson 文本侧（内核移植/悬浮/schema 体积） | 📎 证据（文本视图用） |

### 0.2 冻结 vs 免责（**判据：改这里会不会牵动调用点/用户数据？**）

| 层 | 内容 | 改动代价 |
|---|---|---|
| **冻结**（本稿） | 类型 · 公开 API · 判据算法 · 写路径 · 不变量 · 门禁 · 用户数据格式 | 🔴 显式决策 + 自检同步 |
| **免责**（界面稿） | 页面布局 · 入口落点 · 画布/文本视图实现 · 工具集 · 文案措辞 | 🟢 随便换，不算返工 |

### 0.3 分层与数据流

```
入口层   主页[工具卡:模组工坊] → ProjectsActivity → 工程        模组页[复刻到工坊…]
                            │
模型层   ┌───────────────────┴────────────────────────────────────┐
(冻结)   │ ProjectStore   工程的"家"：hub/projects/<名>/             │
         │   create / list / importFromSlot / installToSlot / delete │
         │ ModProject     只读快照（两种来源）：元数据 / 文件树 /      │
         │                内容清单 / 贴图清单 / issues()             │
         │ ModWriter      唯一写入口（Result 码；不弹窗）             │
         │ FileOp         工程内文件增删改（走 ModWriter 那套纪律）    │
         └───────────────────┬────────────────────────────────────┘
                            │ 复用（不重写）
复用层   ModSprites(注册名/尺寸) · MapStatsMods(内容表) · Hjson(宽容解析)
         Mods(internalNameOf/扫描/importPackage/copyMods) · Data · Trash · Util
                            │
视图层   ①文件（首页）②概览(内容/贴图/问题) ③文本 ④贴图 ⑤预览
(免责)   五者**只吃 ModProject**，不许自己算注册名/尺寸/路径
```

**硬规矩**：模型层（`ModProject`/`ModWriter`/`ProjectStore`/`FileOp`）**不许 import `android.view.*` / `android.widget.*`**
⇒ 能在 PC 上单独编译 + 单测（与 `SettingsBin`/`Msch` 同一纪律）。

---

## 1. 结构决定（**已定**）

| # | 决定 | 值 | 可逆 |
|---|---|---|---|
| **S-A** | 工程的"家" | ✅ **丙**：独立工坊 + 双向搬运（**不碰正在玩的槽**） | 🔴 难 |
| **S-B** | 仓库位置 | **`hub/projects/<工程名>/`**（与 `backups/` `cas/` `previews/` 并列，用户看得见） | 🟡 中（已建工程要迁移） |
| **S-C** | 纳入备份/CAS | **一期不纳入**（工程是工作区，不是用户资产） | 🟢 易 |
| **S-D** | 入口 | 主页**工具卡**加一行「模组工坊」；模组页加「复刻到工坊…」 | 🟢 **免责层** |
| **S-E** | 视图载体 | **4 个 Activity**；`ModIdeActivity` = 工作台（［文件］/［概览］两个标签） | 🟢 易 |
| **S-F** | ★ **文件视图是工程首页** | 见 §2.5（语义清单只覆盖建模过的文件） | 🟢 易（但模型要支持 §3.5 的文件树） |

**★ 全结构最值钱的一条**：
> **`ModWriter` 只管"工程内部"；写槽只有一条路 =［安装到槽］。**
⇒ 一期**"改正在玩的槽"这件事根本不存在**，风险从"弄坏用户的槽和存档"降到"弄坏自己的一份工程"。

---

## 2. 类型清单与关系

```
Source ──▶ ModProject ──┬─▶ Meta            元数据
（两种来源）│            ├─▶ List<Entry>    文件树（原始真相）
                        ├─▶ List<Content>  内容清单（派生）
                        ├─▶ List<Sprite>   贴图清单（派生）
                        └─▶ List<Issue>    判据结果（派生）

ProjectStore ──(工程目录)──▶ ModProject        （工程来源）
Mods.Info    ──(槽里的模组)─▶ ModProject       （只读来源）

ModWriter ── 写单个文件（文本/PNG）
FileOp    ── 工程内增删改（内部调 ModWriter）
ProjectStore.installToSlot ── 唯一写槽路径
```

### 2.1 `Source`（来源）

```java
Source.ofSlotMod(Context, String slot, Mods.Info info)   // 槽里的模组（**只读**）
Source.ofProject(Context, File projectDir)               // 工坊里的工程（可写）
boolean project;                                          // 判定用这一个，别自己猜
```
★ **读能力对两种来源一视同仁**（S1）："只是看看这个模组有啥问题"**不该强制先复刻**。

### 2.2 `ModProject`（只读快照）

| 字段 | 类型 | 语义 / 边界 |
|---|---|---|
| `source` | `Source` | 来源 |
| `slot` / `info` | `String` / `Mods.Info` | **仅槽来源有值**；工程来源为 null（元数据从工程里的 `mod.hjson` 读） |
| `root` | `File` | 目录/工程 ⇒ 有值；**zip ⇒ null** |
| `writable` | `boolean` | ★ **只有"工坊工程"为 true**；槽里的模组一律只读 |
| `zip` | `boolean` | 形态判断（视图要显示"只能看"时用它，别自己判） |
| `meta` / `contents` / `sprites` / `issues` / `notes` | —— | 见 2.3~2.7 |

```java
public static ModProject load(Context ctx, Source src);   // 唯一构造入口：只读、不弹窗、不写盘、不碰 UI

public String  expectedRegion(Content c);   // ★ 派生量的唯一入口：internalName + "-" + c.stem
public int     expectedSize(Content c);     // 方块 ⇒ size*32；否则 32
public Sprite  spriteFor(Content c);        // 命中即返回，否则 null
public boolean isBlock(Content c);          // dir.equals("blocks")

public List<Entry> list(String dirPath, boolean includeHidden);   // §3.5 文件树
public String  readAt(String path);        // 文本；>256KB ⇒ null（视图说"太大"）
public byte[]  bytesAt(String path);
public boolean exists(String path);
public String  rawOf(Content c)  = readAt(...);      // 语法糖
public byte[]  bytesOf(Sprite s) = bytesAt(s.hit.entry);
```

### 2.3 `Meta`

| 字段 | 语义 |
|---|---|
| `fileName` | 实际命中的名字（`mod.hjson` / `mod.json` / `plugin.hjson` / `plugin.json`，**按下标取首个存在的**，`Mods.java:999`） |
| `raw` / `json` / `parseOk` / `error` | 原文 / HJSON→标准 JSON（`Hjson.toJsonText(raw, ARC_160)`）/ 是否解开 / **原始诊断**（给维护者） |
| `internalName` | 🔴 **等于 `Mods.internalNameOf`**（小写化 + 空格换 `-`）——**不许另算一遍** |
| `displayName` / `version` / `minGameVersion` / `author` / `description` / `main` | 直取 `Mods.Info`（**Info 已解析过**，不重复解析） |

### 2.4 `Content`（一条内容）

| 字段 | 语义 / 边界 |
|---|---|
| `dir` | 内容目录名（**复数**：`items` `blocks` `liquids` `units` `status` `sectors` `weathers`）；**单数目录不入清单**（游戏不读） |
| `ref` | 不透明定位引用（目录 ⇒ `File`；zip ⇒ 条目名）；**视图不许解它**，只用来回传 `rawOf` |
| `stem` | 文件名主干。🔴 **贴图注册名用的是它，不是第一个 `.` 之前那段**（REF §81.2，第一版就栽在这） |
| `type` | JSON 的 `"type"`（类名）；缺省/解不出 ⇒ null |
| `size` | 方块 `size`；非方块恒 0；缺失/非法 ⇒ 0 + 一条 Issue（C4） |
| `rotate` | 会不会转：`ModSprites.rotates(region)`；查不到 ⇒ `false`（保守，§81.4） |
| `raw` | 原文（只读显示用） |

### 2.5 `Sprite`（一张贴图）

| 字段 | 语义 |
|---|---|
| `region` | 注册名 = `<internalName>-<stem>`（**由 `ModSprites` 给出，我们不算**） |
| `hit` | `ModSprites.Hit{pack, entry, directory}`（取字节/尺寸都用它） |
| `w` / `h` | PNG 尺寸（`ModSprites.dims(hit)`） |
| `tiles` | `w/32`（`ModSprites.tilesOf`）；非正方形取 `min` + 报 `I_SPRITE_NOT_SQUARE` |

### 2.6 `Issue`（**码 + 参数，不放成品句子**）

```java
final int level;      // LEVEL_ERROR=2 / LEVEL_WARN=1
final int code;       // 下表
final String where;   // 定位：内容 stem / 贴图 region（可空）
final Object[] args;  // 交给 Trans
```

| code | level | 触发 | args |
|---|---|---|---|
| `I_NO_SPRITE` | ERROR | 内容找不到贴图（C1） | `stem` |
| `I_SIZE_MISMATCH` | ERROR | 方块贴图尺寸 ≠ `size×32`（C2） | `stem, size*32, w, h` |
| `I_SIZE_MISSING` | WARN | 方块没有 `size`（C4） | `stem` |
| `I_SPRITE_NOT_SQUARE` | WARN | 贴图非正方形 | `region, w, h` |
| `I_META_UNREADABLE` | ERROR | `meta.parseOk == false` | —— |
| `I_CONTENT_UNREADABLE` | WARN | 某条内容解不出来 | `stem, error`(诊断) |

★ **为什么"码 + 参数"**：本工程纪律（§78/§85.16）——**文案只有一份来源**、**原始诊断必须留给报告层**。

### 2.7 `Entry`（文件树的一格）

```java
final String path;    // 相对根，**正斜杠**（目录/zip 统一）
final boolean dir;
final long size;      // 目录 ⇒ -1
```
★ `Content.ref` 与 `Sprite.hit` 都只是 `Entry` 的特例 ⇒ **视图层一律用 `list/readAt/bytesAt`**。

---

## 3. 一致性判据（**只读，不碰盘**；视图一行都不许重算）

```java
// C1 缺贴图（ERROR）
for c in contents:
    if find(expectedRegion(c)) == null: issue(ERROR, I_NO_SPRITE, c.stem)

// C2 尺寸不符（ERROR，仅方块；缺图不重复报）
for c in contents where isBlock(c) and c.size > 0:
    s = spriteFor(c); if s == null: continue
    want = c.size * 32
    if s.w != want or s.h != want: issue(ERROR, I_SIZE_MISMATCH, c.stem, want, s.w, s.h)

// C4 size 缺失（WARN，仅方块）
for c in contents where isBlock(c) and c.size <= 0: issue(WARN, I_SIZE_MISSING, c.stem)
```

### 🔴 **明确不做 C3「孤立贴图」**（写下来免得反复争）

模组贴图里天然有大量**没有同名内容**的图：叠加层（`-top` `-glow` `-team` `-heat`，原版 `blocks/turrets/` 实测就有）、
UI 图标、多帧部件（`-cell` `-leg` `-blade`）。后缀**是开放集合**，枚举必漏（§45.9：清单对 ≠ 清单全）
⇒ **报了就是噪声，而噪声会淹掉真问题**（§85.16「冤枉无关项」的老账）。
⇒ 一期不报；将来要做也只能是"**信息**"级，且必须先有一份**可解释的后缀规则**。

### 3.1 一致性行为口径

**只报不改**（一期）。"一键修"= 又一条写用户数据的路径，风险不成比例。

---

## 4. 写路径（**只有两条，且分工明确**）

### 4.1 `ModWriter` —— 写**单个文件**（工程内部）

```java
OK=0 · NEED_CONFIRM=1 · BUSY=2 · IO=3 · VERIFY=4
Result { int code; String path; String error; }   // error = 原始诊断（给维护者）

writeText(File target, String text, boolean overwrite)
writePng (File target, Bitmap bmp, boolean overwrite)
```

| # | 步骤 | 失败 ⇒ |
|---|---|---|
| 1 | **门禁**：`Data.gameAlive` ⇒ `BUSY` | `BUSY` |
| 2 | 父目录不存在 ⇒ 建 | `IO` |
| 3 | 目标已存在 且 `!overwrite` ⇒ **立刻返回 `NEED_CONFIRM`（一个字节都不写）** | `NEED_CONFIRM` |
| 4 | 写同目录的 `<名>.part`（`.part` ⇒ 被 `Data.contentSkipped` 排除） | `IO`（清 .part） |
| 5 | **写后自证**：文本 ⇒ `Hjson.toJsonText` 解一遍；PNG ⇒ `BitmapFactory` 读回且宽高一致 | `VERIFY`（删 .part） |
| 6 | 覆盖路径：旧件 ⇒ **`Trash.move`**（不是裸 `renameTo`；跨卷会静默失败） | `IO`（**旧件仍在原地，新的不动**） |
| 7 | `.part` → 目标名（同目录 ⇒ 必然同卷） | `IO`（旧件已进站 ⇒ **如实报告"旧的在中转站"**） |
| 8 | 返回 `OK` + 实际路径 | —— |

★ **`ModWriter` 不弹窗**：同名先问由**调用方**拿到 `NEED_CONFIRM` 后弹，再带 `overwrite=true` **重调一次**
（与 §86.8"**弹之前必须再过一次闸门**"一致）。
⚠️ **只对"工坊工程"有意义**（zip/槽里的模组没有写入口）——调用方先看 `writable()`。

### 4.2 `FileOp` —— 工程内**增删改**（内部走 `ModWriter`）

```java
mkdir(project, relPath) · writeTextFile(project, relPath, text, overwrite)
fromTemplate(project, relPath, template) · rename(project, relPath, newName)   // 只改本层名
copy(project, relPath, dstRelPath) · delete(project, relPath)
```

| 模板 | 生成 | 为什么要 |
|---|---|---|
| `T_CONTENT_JSON` | `content/<类型>/<名>.json` 最小骨架 | 从零造时用户不知道写什么 |
| `T_MOD_META` | `mod.hjson` 骨架（名字与工程目录名一致） | 同上 |
| `T_BUNDLE` | `bundles/bundle.properties`（含 `item.<名>.name=` 键） | **没有它游戏里显示内部名**（实测） |
| `T_EMPTY_TEXT` | 空文本 | —— |

🔴 **两条纪律**：① 删除 ⇒ **复用既有中转站**（`Trash.move`，新一类 `Kind.PROJECT_FILE`），**不另造回收站**；
② ⚠️ **工程在 `hub/projects/` 里，游戏根本不读它 ⇒ 不需要 `gameAlive` 门禁**
（写进注释，否则以后有人"顺手"加上，就变成"游戏开着就不让编辑工程"的怪行为）。

### 4.3 `ProjectStore` —— 工程的"家"与**唯一的写槽路径**

```java
root(ctx) = hub/projects/    ·  list(ctx)    ·  dirOf(ctx, name)
create(ctx, rawName)                    // 建骨架 + .mdt-project.json；重名**不静默覆盖**（追加 -2/-3）
importFromSlot(ctx, slot, Mods.Info)    // 槽 → 工程；zip **解包**；含 classes.dex **拒绝**
installToSlot(ctx, project, slot, overwrite)   // ★ **唯一写槽路径**：复制成 slot-<槽>/mods/<名>/（目录形态）
delete(ctx, project) / rename(ctx, project, newName)   // 删除 ⇒ Trash.move
```

🔴 **含 `classes.dex` 的模组拒绝复刻**（依据 = 真机 A/B 实测，Android 16 / 官方 159.7）：
目录形态的模组**加载不了** `classes.dex`（`ClassNotFoundException`），zip 可以 ⇒ **解包 = 把代码弄坏**。
⇒ 拒绝时**说清理由**（「这个模组带代码，拆开就废了」），不是一句"不支持"。

### 4.4 `.mdt-project.json`（**不可逆格式**）

```json
{ "schema": 1, "name": "<工程目录名>", "from": "slot:<槽名>|null", "created": <epoch ms> }
```
放工程根，**点开头** ⇒ 不会被当成模组内容（`Data.contentSkipped` 第一条）；游戏也不读。
🔴 **`schema` 只加不改**；新字段一律**可选 + 缺省**（老工程照样能开）。

---

## 5. 不变量 + 门禁

| # | 不变量 | 谁保证 | 自检钉法 |
|---|---|---|---|
| **V1** | 注册名**只有一处实现**（`ModSprites`）；模型只拼装（`expectedRegion`） | 门禁 | 视图层 grep 不到 `internalNameOf` / `"-" +` |
| **V2** | 尺寸判据**只有一处**（`expectedSize`） | 门禁 | 视图层 grep 不到 `* 32` |
| **V3** | `internalName == Mods.internalNameOf(meta.name)`（逐字符） | 模型 | 拿**大小写混合 + 空格**的样本对（§111 的教训） |
| **V4** | 写入**只有一条路径** | 门禁 | 视图层 grep 不到 `.part` / `renameTo` / `compress(` |
| **V5** | `.part` 永不进清单 / 不进备份 | `Data.contentSkipped` | 写一半就中断 ⇒ 清单里没有它（正反两侧） |
| **V6** | 模型层**不 import UI 包** | 门禁 | 编译期判死 |
| **V7** | 判据**只读**：`load()` 前后目录清单 + md5 完全一致 | 自检 | 前后对拍 |
| **V8** | `Issue` 里**没有成品句子**（只有码 + 参数） | 门禁 | grep 构造点，第二参必须是常量 |
| **V9** | **写槽只有一条路**（`installToSlot`）；`ModWriter`/`FileOp` 只动工程 | 门禁 + 自检 | 视图层 grep 不到 `slot-` / `dirOf` 写入 |

**门禁 `tools/ide-check.py`（与 `i18n-check.py` 同族）**：

| 规则 | 判据 |
|---|---|
| IDE-01 | 模型层出现 `android.view` / `android.widget` ⇒ ERROR（V6） |
| IDE-02 | 视图层出现 `internalNameOf(` / `"-" +` / `* 32` / `.part` / `renameTo` / `compress(` ⇒ ERROR（V1/V2/V4） |
| IDE-03 | ★ **元断言**：故意在视图层塞一行 `x * 32`，要求 IDE-02 **当场判死** |
| IDE-04 | `Issue` 的 `code` ↔ `ModIdeText` 资源 key **双向**对账（漏一条 = 界面显示不出来） |

---

## 6. 决定清单（**全部已定**）

### 6.1 不可逆（"一有用户数据就得写迁移"）—— 5 条

| # | 定案 |
|---|---|
| **I-1** | 工程元数据 = 根目录 **`.mdt-project.json`**，`schema` 只加不改、新字段可选 |
| **I-2** | 工程目录名 = **模组内部名口径**；**重名不静默覆盖**（追加 `-2`/`-3`），界面显示**实际**名字 |
| **I-3** | ［安装到槽］同名 ⇒ **先问**（覆盖/换名/取消）；覆盖 ⇒ 旧件**完整进中转站** |
| **I-4** | **删除文件 ⇒ 复用既有中转站**（新类 `Kind.PROJECT_FILE`），不另造回收站 |
| **I-5** | 文件视图的**写能力只在工坊工程里存在**；槽里的模组（含 zip）**只读** |

### 6.2 可逆（**我自己定的，随便改**）

| 项 | 值 |
|---|---|
| 一致性行为 | **只报不改** |
| 缓存 | `ModProject` **不缓存**（每次 load 重扫） |
| `size` 缺失级别 | **WARN** |
| 入口措辞 | 工具卡那行 = **「模组工坊」**；模组页那行 = **「复刻到工坊…」** |
| 一期贴图视图 | **带**"新建空白贴图"（不需要写 JSON） |
| 画板默认缩放 | **适应屏幕** |
| zip 的读 | **不解包**（§2.2/§2.7：条目引用直读）⇒ zip 照样有文件树/清单/问题 |

---

## 7. 与既有零件的复用边界（**谁负责什么**）

| 能力 | 归谁 | 我们**不**做 |
|---|---|---|
| 注册名算法（前缀 + 完整主干 / 目录层级不进名） | `ModSprites` | ❌ 不重写、不复制 |
| 贴图尺寸 | `ModSprites.dims(Hit)` | ❌ 不自己解 PNG 头 |
| 内容表（原版 + 模组 + patch + bundle 译名） | `MapStatsMods.contentFor` | ❌ 不另建内容表 |
| HJSON 宽容解析 | `Hjson.toJsonText(text, ARC_160)` | ❌ 不用严格 JSON |
| 内部名 | `Mods.internalNameOf` | ❌ 不另写小写化 |
| 槽目录 / 游戏在跑 | `Data.dirOf` / `Data.gameAlive` | ❌ 不硬编码路径 |
| 搬家（旧件进站） | `Trash.move` | ❌ 不裸 `renameTo` |
| 原子写文本 | `Util.atomicWriteText` | —— |
| 像素级渲染 / 预览 | `Msch*`（§80） | ❌ 不另写渲染器 |
| 打包/解包 mod 包 | `Mods.importPackage` / `copyMods` | ❌ 不另写 zip 逻辑 |

★ 这条表就是"**以后改起来不麻烦**"的保证：改注册名规则只需改 `ModSprites` 一处，五个视图自动跟着变。

---

## 8. 与既有功能的关系（**并列，不搬**）

- 现有"冲突体检 / 蓝图缺件 / 地图统计 / 模组类型"**继续待在原处**，**不搬进 IDE**。
- `ModProject` 是**同一层知识的另一种封装**；将来若要让它们共用，**只准把判据挪进模型层，不准把视图搬来搬去**。
- ★ `ModProject` **不许**重写"冲突体检"的判据（那是 `Mods.findConflicts` 的事）。

---

## 9. 建设顺序（**每步都是最终形态的一块，没有一步要扔**）

| 步 | 交付 | DoD | 碰用户数据 |
|---|---|---|---|
| **S1** | `ModProject`（两种来源 + 文件树）+ 判据 + `ModWriter` + `FileOp` + `ProjectStore`（**只做 create/list/delete**）+ 门禁 | 自检全绿；模型层能**在 PC 单独编译**；IDE-03 元断言当场判死 | ❌ 只读（`create` 只写自有工程目录） |
| **S2** | `ProjectsActivity`（工程列表）+ `ModIdeActivity`［文件］标签 | 真机：新建工程 → 建目录/文件（模板）→ 文件树正确；隐藏项默认藏 | ⚠️ 写工程 |
| **S3** | ［概览］标签（内容/贴图/问题）+ `ModSpriteActivity`（画板：从现有起稿 + 6 工具 + 保存） | **改一张真贴图 ⇒ 一键启动游戏看见变化**；覆盖路径旧件进站；转屏不丢 | ✅ |
| **S4** | `importFromSlot`（复刻：zip/目录/含 dex 三种）+ `installToSlot`（同名先问） | 拿一个**真 zip 模组**走完"复刻 → 改 → 安装 → 启动"；含 dex 的**被拒且说明理由** | ✅ |
| **S5** | `ModTextActivity`（只读：高亮/悬浮/诊断） | 打得开 `mod.hjson` 与 `content/**`；大文件不卡 | ❌ |
| **S6** | 文本可写（无损 AST）+ `ModPreviewActivity` | 改一个值 ⇒ 注释/未知字段/重复键**全在**；预览与游戏一致 | ✅ |

★ **S1/S2 之后就已经独立可用**（"这个模组有什么、哪里不对" + "从零起一个工程"）。

---

## 10. 还没解决的（**如实列，别当成已定**）

| # | 缺什么 | 影响 |
|---|---|---|
| A | **文本视图的内核**（原生移植 Anuken 那 2018 行 vs WebView）—— 仍是免责层，但工作量最大的一条 | S5/S6 的量 |
| B | **画板的手势手感**（单指画 / 双指平移缩放）—— 全工程唯一无先例处 | S3 的成败 |
| C | **`Scripts` 视图**（`scripts/*.js` 的编辑）—— 一期只在文件视图里当文本打开 | 后加是加法 |
| D | 工程的**导入/导出 zip**（把工程打成包给别人） | 后加是加法 |
