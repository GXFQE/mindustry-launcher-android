# 模组 IDE —— 详细定案（1/2）：契约与模型（**冻结层**）

> 📦 **本稿已被 `模组IDE-模型结构.md` 取代（2026-10-08 整理）** —— 内容已并入那份。
> **请读那一份**；本稿只留作追溯（"当时怎么一步步改过来的"）。
>
> **状态：分析稿（未碰产品代码）。** 落点 `docs/plans/2026-10-08-模组IDE/`（2026-11-02 从仓库根搬入）。
> 🔴 **先读 `模组IDE-整体结构.md`**：本稿默认"**工程 = 槽里的那个模组（就地编辑）**"，
> 若结构定为**丙（独立工坊 + 双向搬运）**，则**定位参数 / 入口 / 写入边界都要改**（那份的 §8 已列清单）。
> 本稿 = **可以照着写代码的接口级规格**。凡写"【待定】"的，都是**必须你拍板**的；其余一律**已定**。
> 配套：`（2/2）界面与实施`。上游：`形态定案` · `嵌入与难度` · `像素画编辑器-*`（4 份）。
> - 日期：2026-10-08 · 现场核实过所有被引用的类型（`ModSprites.Hit` / `SlotContent` / `Mods.Info` / `Hjson` / `Trans`）

---

## 0. 冻结原则（为什么这么切）

| 层 | 内容 | 允许改动吗 |
|---|---|---|
| **冻结层**（本稿） | 数据类型 · 公开 API 签名 · **判据算法** · 写入流程 · 不变量 · 门禁规则 | 🔴 **改动 = 一次显式决策 + 自检同步** |
| **免责层**（2/2） | 页面布局 · 画布实现 · 文本视图实现 · 工具集 · 文案措辞 | ✅ 随便换，**不算返工** |

★ 判据：**"改这里会不会牵动调用点？"** 会 ⇒ 冻结层；不会 ⇒ 免责层。

---

## 1. 模块划分与依赖方向

```
        ┌──────────────────────── 视图层（免责层）────────────────────────┐
        │  工程视图        贴图视图         文本视图        预览视图        │
        │  (只读)        (读写)          (只读→读写)     (只读)          │
        └───────┬───────────┬───────────────┬──────────────┬────────────┘
                │  只吃模型（不许自己算注册名 / 尺寸 / 路径）
                ▼
        ┌──────────────────────────────────────────────────────────────┐
        │  ModProject（冻结层 · 只读快照 + 一致性判据 issues()）          │
        │  ModWriter （冻结层 · 唯一写入口：写文本 / 写 PNG）             │
        └───────┬──────────────────────────────────────────────────────┘
                │  复用（不重写）
                ▼
   ModSprites(注册名↔PNG / dims) · MapStatsMods.contentFor(内容表) · Hjson(宽容解析)
   Mods(internalNameOf / 扫描 / 打包) · Data(dirOf / gameAlive) · Trash(move) · Util(atomicWriteText)
```

**硬规矩**：`ModProject` / `ModWriter` **不许 import 任何 `android.view.*` / `android.widget.*`**（不碰 UI）。
⇒ 它们能在 PC 上**单独编译 + 单测**（与 `SettingsBin` / `Msch` 同一条纪律）。

---

## 2. 数据模型（精确定义）

### 2.1 `ModProject`（不可变快照）

| 字段 | 类型 | 语义 | 来源 |
|---|---|---|---|
| `slot` | `String` | 槽名 | 入参 |
| `info` | `Mods.Info` | 模组条目（**只读引用**） | 入参 |
| `root` | `File` | **仅目录形态**有值（= 模组根目录）；zip 形态为 `null` | 见 §3.2 |
| `writable` | `boolean` | `info.directory && root 可写`（**zip 恒 false**） | 见 §3.2 |
| `meta` | `Meta` | 元数据（见 2.2） | `mod.hjson` / `mod.json` |
| `contents` | `List<Content>` | 内容清单（有序：按 `dir` 再按 `name`） | `content/**` |
| `sprites` | `List<Sprite>` | 贴图清单（有序：按 `region`） | `ModSprites` |
| `issues` | `List<Issue>` | 一致性判据结果（有序：ERROR 在前，再按 `code`） | §4 |
| `notes` | `List<String>` | 只给人看的说明（如"这是个 zip，只能看不能改"）——**不进 issues** | —— |

### 2.2 `Meta`

| 字段 | 类型 | 语义 |
|---|---|---|
| `fileName` | `String` | 实际命中的那个名字（`mod.hjson` / `mod.json` / `plugin.hjson` / `plugin.json`；**按下标顺序取首个存在的**，`Mods.java:999`） |
| `raw` | `String` | 原文（UTF-8）。**读不出来就是 null**（不编造） |
| `json` | `String` | HJSON → 标准 JSON 文本（`Hjson.toJsonText(raw, Hjson.ARC_160)`） |
| `parseOk` | `boolean` | `false` 时 `json` 为 null、`error` 有值 |
| `error` | `String` | 原始诊断（**给维护者**，界面走 `metaErrCode`） |
| `internalName` | `String` | 🔴 **等于 `Mods.internalNameOf`**（小写化 + 空格换 `-`）——**不许另算一遍** |
| `displayName` / `version` / `minGameVersion` / `author` / `description` / `main` | `String` | 直取 `Mods.Info` 的对应字段（**Info 已经解析过**，不重复解析） |

### 2.3 `Content`（一条内容）

| 字段 | 类型 | 语义 | 边界 |
|---|---|---|---|
| `dir` | `String` | 内容目录名（**复数**：`items` `blocks` `liquids` `units` `status` `sectors` `weathers`） | 单数目录**不入清单**（游戏不读） |
| `ref` | `Object`（不透明） | **定位引用**：目录 ⇒ `File`；zip ⇒ 条目名。**视图层不许解它**，只用来回传给 `rawOf` | 见 §3.2 |
| `stem` | `String` | 文件名主干（去扩展名） | 🔴 **贴图注册名用的是它，不是第一个 `.` 之前那段**（REF §81.2） |
| `type` | `String` | JSON 的 `"type"`（类名）；缺省 = null | HJSON 宽容解析后取；解不出来 ⇒ null |
| `size` | `int` | 方块的 `size`；**非方块恒为 0** | 缺失/非法 ⇒ `0` + 一条 Issue（C4） |
| `rotate` | `Boolean` | 会不会转：`ModSprites.rotates(region)`（查 `ClassRotateTable`） | 查不到 ⇒ `false`（保守，§81.4） |
| `raw` | `String` | 原文 | 只读模式下用来显示 |

### 2.4 `Sprite`（一张贴图）

| 字段 | 类型 | 语义 |
|---|---|---|
| `region` | `String` | 注册名 = `<internalName>-<stem>`（由 `ModSprites` 给出，**我们不算**） |
| `hit` | `ModSprites.Hit` | `{pack, entry, directory}` —— 取字节/尺寸都用它 |
| `w` / `h` | `int` | PNG 尺寸（`ModSprites.dims(hit)`） |
| `tiles` | `int` | `w / 32`（`ModSprites.tilesOf(w,h)`）；⚠️ 非正方形时取 `min`，并在 Issue 里报"不是正方形" |

### 2.5 `Issue`（一个问题）——**码 + 参数**，不带成品文案

```java
public static final class Issue {
    public static final int LEVEL_ERROR = 2, LEVEL_WARN = 1;
    public final int level;      // ERROR / WARN
    public final int code;       // 见下表（资源 key 由 ModIdeText 映射）
    public final String where;   // 定位：内容 stem 或贴图 region（可空）
    public final Object[] args;  // 参数（数字/字符串），交给 Trans
}
```

| code | level | 触发条件 | args |
|---|---|---|---|
| `I_NO_SPRITE` | ERROR | 内容找不到贴图（C1） | `stem` |
| `I_SIZE_MISMATCH` | ERROR | 方块贴图尺寸 ≠ `size×32`（C2） | `stem`, `size*32`, `w`, `h` |
| `I_SIZE_MISSING` | WARN | 方块 JSON 没有 `size`（C4） | `stem` |
| `I_SPRITE_NOT_SQUARE` | WARN | 贴图非正方形 | `region`, `w`, `h` |
| `I_META_UNREADABLE` | ERROR | `meta.parseOk == false` | （无） |
| `I_CONTENT_UNREADABLE` | WARN | 某条内容 JSON 解不出来 | `stem`, `error`(诊断) |

★ **为什么用"码 + 参数"**：本工程既有纪律（§78 / §85.16）——**文案只有一份来源**，
且**原始诊断必须保留**给报告层。`Issue` 里**不放成品句子**。

---

## 3. `ModProject` 公开 API（签名级）

```java
public final class ModProject {

    /** 唯一的构造入口。只读；不弹窗、不写盘、不碰 UI。**两种来源**（结构定案 S1）。 */
    public static ModProject load(Context ctx, Source src);

    /** 来源：① 槽里的一个模组；② 工坊里的一个工程。读能力对两者一视同仁。 */
    public static final class Source {
        public static Source ofSlotMod(Context ctx, String slot, Mods.Info info);
        public static Source ofProject(Context ctx, File projectDir);
        public final boolean project;   // true = 工坊工程（可写）；false = 槽里的模组
    }

    public Source  source();
    public String  slot();              // 仅 ofSlotMod 有值；工程 ⇒ null
    public Mods.Info info();            // 仅 ofSlotMod 有值；工程 ⇒ null（元数据从工程里的 mod.hjson 读）
    public File    root();              // 目录形态 / 工程 ⇒ 有值；zip ⇒ null
    public boolean writable();          // ★ 只有"工坊工程"才 true —— 槽里的模组一律只读（S2）
    public boolean zip();               // 形态判断（视图要显示"只能看"时用这个，别自己判）
    public Meta    meta();
    public List<Content> contents();    // 不可修改视图
    public List<Sprite>  sprites();
    public List<Issue>   issues();
    public List<String>  notes();       // 给人看的说明（如 "这是个压缩包，只能看"）

    /** ★ 读字节的**唯一**入口（目录 / zip 都走这里；视图层不许自己开 zip / 拼路径） */
    public String rawOf(Content c);
    public byte[] bytesOf(Sprite s);    // = ModSprites.bytes(s.hit)

    /** ★ 派生量的**唯一**入口（视图层不许自己算） */
    public String expectedRegion(Content c);        // meta.internalName + "-" + c.stem
    public int    expectedSize(Content c);          // 方块 ⇒ size*32；否则 32
    public Sprite spriteFor(Content c);             // 命中即返回，否则 null
    public boolean isBlock(Content c);              // dir.equals("blocks")
}
```

### 3.1 `contents()` 的扫描规则（**逐条定死**）

1. 只看 `root/content/` 存在时的 **7 个复数目录**（`items` `blocks` `liquids` `units` `status` `sectors` `weathers`）；
2. 每个目录**只扫直接子文件**，扩展名 `json` / `hjson`（大小写不敏感）；
3. 文件名以 `.` 开头 ⇒ 跳过（与 `Data.contentSkipped` 同口径）；
4. 以 `.part` / `.tmp` 结尾 ⇒ 跳过（半成品）；
5. `dir` 不在白名单 ⇒ **整目录忽略**（并**不报 Issue**——那是模组自己的事，不是错误）。

### 3.2 ★ 两种形态**都不用解包**（`File` 不是这里该用的抽象）

🔴 **修正**：早期草稿把 `root()` 定成 `File`、zip 就"解到临时目录"——**这是错的**。
真机上 **23/24 个模组是 zip**，而**我们本来就在直接读 zip 条目**：`ModSprites.Hit = {pack, entry, directory}`
配 `bytes(Hit)` 就是从条目取字节。⇒ **清单与字节都走"条目引用"，不落临时目录**：

| 形态 | 清单怎么读 | 字节怎么读 | `writable` |
|---|---|---|---|
| **目录** | `root/…`（`File`，`Data.contentSkipped` 过滤） | `File` 读 | `true` |
| **zip / jar** | **`ZipFile` 逐个条目**（与 `ModSprites` 同一把 zip 句柄；`Mods.resolveRootPrefix` 处理"套一层目录"） | `ZipFile.getInputStream(entry)` | `false` |

⇒ 好处有三：① **zip 模组照样有内容/贴图清单与问题列表**（否则工程视图对 23/24 个模组是空的）；
② **不产生临时目录**（没有生命周期、没有磁盘占用、没有"删干净了吗"的问题）；
③ **待定项 Q2 直接消失**。

⇒ 对外只暴露**读字节**，不暴露 `File`：
```java
public String rawOf(Content c);     // 原文（目录 / zip 条目都走这里）
public byte[] bytesOf(Sprite s);    // = ModSprites.bytes(s.hit)
```

### 3.3 `ProjectStore`（★ 结构定案丙带来的新层：**工程的"家"**）

```java
public final class ProjectStore {
    public static File root(Context ctx);                  // = hub/projects/
    public static List<File> list(Context ctx);            // 按名字排序
    public static File dirOf(Context ctx, String name);    // 目录名 = 内部名口径（I-2）

    /** 新建空工程（写 mod.hjson 骨架 + .mdt-project.json）。重名**不静默覆盖**，追加 -2/-3。 */
    public static File create(Context ctx, String rawName) throws IOException;

    /** 从槽里的模组复刻成工程（zip **解包**；含 classes.dex 的**拒绝**并说明理由）。 */
    public static File importFromSlot(Context ctx, String slot, Mods.Info info) throws IOException;

    /** 安装到槽：复制成 slot-<槽>/mods/<名>/（目录形态）。同名先问（I-3）。 */
    public static ModWriter.Result installToSlot(Context ctx, File project, String slot,
                                                 boolean overwrite);

    /** 删除工程 ⇒ **Trash.move**（不硬删）；改名 ⇒ 目录 rename + .mdt-project.json 同步。 */
    public static ModWriter.Result delete(Context ctx, File project);
    public static ModWriter.Result rename(Context ctx, File project, String rawNewName);
}
```

⚠️ **`ProjectStore` 不碰 UI、不弹窗**（与 `ModWriter` 同一条纪律）；`installToSlot` 的同名先问走
"先返回 `NEED_CONFIRM` / 再由调用方带 `overwrite=true` 重调"的同一套。

🔴 **含 `classes.dex` 的模组拒绝复刻**的依据：真机 A/B 实测（Android 16 / 官方 159.7）——
目录形态的模组**加载不了** `classes.dex`（`ClassNotFoundException`），zip 形态可以
⇒ 解包 = **直接把这个模组的代码弄坏**（`.tmp-dex-probe/RESULT-目录形态能否加载-dex.md`）。
⇒ 拒绝时**说清理由**（"这个模组带代码，拆开就废了"），不是一句"不支持"。

### 3.4 工程元数据 `.mdt-project.json`（**不可逆格式 · I-1**）

```json
{ "schema": 1, "name": "<工程目录名>", "from": "slot:<槽名>|<null>", "created": <epoch ms> }
```
- 放工程根目录，**点开头** ⇒ 不会被当成模组内容（`Data.contentSkipped` 第一条就是点开头）；游戏也不读它。
- 🔴 **`schema` 字段只能加不能改**；以后要加字段一律**可选 + 缺省**（老工程照样能开）。

### 3.5 ★ 文件树能力（结构增补：**文件视图**要的东西）

**语义清单（`contents()` / `sprites()`）只覆盖我们建模过的文件**；模组里还有
`bundles/` `scripts/` `sprites-override/` `patches/` 音效音乐 `classes.dex`……
⇒ 模型必须再暴露一层**原始文件树**（两种来源都支持）：

```java
public static final class Entry {
    public final String path;   // 相对根的路径，**正斜杠**（zip 与目录统一）
    public final boolean dir;
    public final long size;     // 目录 ⇒ -1
}

/** 列**一层**（dirPath = "" ⇒ 根）。隐藏项（点开头 / .part / .tmp）默认不返回，除非 includeHidden。 */
public List<Entry> list(String dirPath, boolean includeHidden);

public String  readAt(String path);      // 文本；> 256 KB ⇒ 返回 null（视图据此说"太大，不开"）
public byte[]  bytesAt(String path);     // 任意条目
public boolean exists(String path);
```

★ 关系：`Content.ref` 与 `Sprite.hit` 都只是 `Entry` 的特例 ⇒ **视图层一律用 `list/readAt/bytesAt`**，
`rawOf` / `bytesOf` 退化成它们的语法糖（保留，因为语义视图用着顺手）。

### 3.6 文件**写**操作（都只在"工坊工程"里；一律走 `ModWriter` 那套纪律）

```java
public static final class FileOp {          // 全部返回 ModWriter.Result
    public static ModWriter.Result mkdir (File project, String relPath);
    public static ModWriter.Result writeTextFile(File project, String relPath, String text, boolean overwrite);
    public static ModWriter.Result fromTemplate(File project, String relPath, int template);
    public static ModWriter.Result rename(File project, String relPath, String newName); // 只改本层名
    public static ModWriter.Result copy  (File project, String relPath, String dstRelPath);
    /** ★ 删除 ⇒ **复用既有中转站**（`Trash.move`，新一类 `Kind.PROJECT_FILE`），不另造回收站（I-4） */
    public static ModWriter.Result delete(File project, String relPath);
}
```

| 模板常量 | 生成什么 | 为什么要模板 |
|---|---|---|
| `T_CONTENT_JSON` | `content/<类型>/<名字>.json` 的最小骨架（含 `type`、必要字段注释） | 从零造模组时，用户不知道要写什么 |
| `T_MOD_META` | `mod.hjson` 骨架（name/displayName/author/version/minGameVersion） | 同上；且**名字要与工程目录名一致** |
| `T_BUNDLE` | `bundles/bundle.properties` + 生成的 `item.<名>.name=` 键 | 没有它游戏里显示内部名（实测） |
| `T_EMPTY_TEXT` | 空文本文件 | —— |

🔴 **写操作的纪律与 `ModWriter` 完全一致**（`.part` → 自证 → 同名先问 → 就位）；
⚠️ 但**门禁不同**：工程在 `hub/projects/` 里，**游戏不会读它** ⇒ **不需要 `gameAlive` 门禁**（那是"写槽"才要的）。
★ 这条要写进注释，否则以后有人"顺手"加上门禁，会变成"游戏开着就不让编辑工程"的怪行为。

---

## 4. 一致性判据（**精确算法** —— 视图层一行都不许重算）

> 三条判据全部只读 `contents()` / `sprites()` 两份清单，**不碰盘**。

### C1 缺贴图（ERROR）

```
for c in contents:
    r = expectedRegion(c)                 # internalName + "-" + stem
    if sprites.find(r) == null:
        issue(ERROR, I_NO_SPRITE, c.stem)
```
⚠️ 边界：**`dir != "blocks"` 也照样判**（物品/液体/状态/单位都有贴图）。

### C2 尺寸不符（ERROR，仅方块）

```
for c in contents where isBlock(c) and c.size > 0:
    s = spriteFor(c)
    if s == null: continue                # 缺图已由 C1 报，不重复
    want = c.size * 32
    if s.w != want or s.h != want:
        issue(ERROR, I_SIZE_MISMATCH, c.stem, want, s.w, s.h)
```

### C4 `size` 缺失（WARN，仅方块）

```
for c in contents where isBlock(c) and c.size <= 0:
    issue(WARN, I_SIZE_MISSING, c.stem)
```

### 🔴 **明确不做：C3「孤立贴图」**（写下来免得以后反复争）

**理由**：模组贴图里天然有大量**没有同名内容**的图 —— 叠加层（`-top` `-glow` `-team` `-heat`，
2026-10-08 实测原版 `blocks/turrets/` 里就有）、UI 图标、多帧部件（`-cell` `-leg` `-blade`）。
后缀是**开放式**的，枚举必然漏（§45.9：清单对 ≠ 清单全）⇒ **报了就是噪声**，
而噪声会把真问题淹掉（本工程的老账：§85.16「冤枉无关项」）。
⇒ 一期**不报孤图**；将来要做，也只能是"**信息**"级且必须先有一份**可解释的后缀规则**。

---

## 5. `ModWriter` —— **唯一写入口**

```java
public final class ModWriter {

    public static final int OK = 0;
    public static final int NEED_CONFIRM = 1;   // 目标已存在 ⇒ 调用方去问用户
    public static final int BUSY = 2;           // 游戏在跑
    public static final int IO = 3;             // 读写失败
    public static final int VERIFY = 4;         // 写后自证不过（已回滚）

    public static final class Result {
        public final int code;
        public final String path;      // 实际落点（给界面显示）
        public final String error;     // 原始诊断（IO/VERIFY 时非空）—— **给维护者**
    }

    /** 写文本（JSON/HJSON）。overwrite=false 且目标存在 ⇒ NEED_CONFIRM。 */
    public static Result writeText(File target, String text, boolean overwrite);

    /** 写 PNG。同上。 */
    public static Result writePng(File target, Bitmap bmp, boolean overwrite);
}
```

### 5.1 逐步流程（**两个写方法共用**，逐步定死）

| # | 步骤 | 失败 ⇒ |
|---|---|---|
| 1 | **门禁**：`Data.gameAlive(ctx)` ⇒ 直接返回 `BUSY`（**不弹窗**，由调用方说） | `BUSY` |
| 2 | 目标父目录不存在 ⇒ 建（`mkdirs`） | `IO` |
| 3 | 目标已存在 且 `!overwrite` ⇒ **立刻返回 `NEED_CONFIRM`**（**什么都不写**） | `NEED_CONFIRM` |
| 4 | 写 `<目标名>.part`（**同目录**，名字以 `.part` 结尾 ⇒ 被 `Data.contentSkipped` 排除） | `IO`（清理 .part） |
| 5 | **写后自证**：文本 ⇒ 用 `Hjson.toJsonText` 解一遍；PNG ⇒ `BitmapFactory.decodeFile` 回来且宽高一致 | `VERIFY`（删 .part） |
| 6 | 目标已存在（走 overwrite 那条）⇒ 旧件 **`Trash.move`**（不是裸 `renameTo`，跨卷会静默失败） | `IO`（**旧件仍在原地，新的不动**） |
| 7 | `.part` → 目标名（**`renameTo`，同目录 ⇒ 必然同卷**） | `IO`（旧件已进站 ⇒ 如实报告"旧的在中转站"） |
| 8 | 返回 `OK` + 实际路径 | —— |

★ **`ModWriter` 不弹窗**（它不碰 UI）——"同名先问"由**调用方**（视图）拿到 `NEED_CONFIRM` 后弹，
再带 `overwrite=true` **重调一次**。这与本工程"**弹之前必须再过一次闸门**"（§86.8）一致。

⚠️ **`writeText` / `writePng` 只对"目录形态"有意义**（`ModProject.writable()`）——
zip 形态**没有写入口**（重写整个 zip 已否决）；调用方必须先看 `writable()`，别指望 Writer 兜底。

---

## 6. 不变量（Invariants）—— 每条都要有一条自检钉着

| # | 不变量 | 谁保证 | 自检钉法 |
|---|---|---|---|
| **V1** | 注册名**只有一处实现**（`ModSprites`），`ModProject` 只做拼装且用 `expectedRegion` | 门禁 §7 | 视图层 grep 不到 `internalNameOf` / `"-"` 拼接 |
| **V2** | 尺寸判据**只有一处**（`expectedSize`） | 门禁 §7 | 视图层 grep 不到 `* 32` |
| **V3** | `internalName` == `Mods.internalNameOf(meta.name)`（逐字符） | `ModProject` | 单测：拿**大小写混合 + 带空格**的 meta 名，与游戏口径对比（§111 的教训） |
| **V4** | 写入**只有一条路径**（`ModWriter`） | 门禁 §7 | 视图层 grep 不到 `.part` / `renameTo` / `compress(` |
| **V5** | `.part` 永远不进内容清单 / 不进备份 | `Data.contentSkipped` | 写完一半就中断 ⇒ 清单里没有它（正反两侧） |
| **V6** | `ModProject` / `ModWriter` **不 import UI 包** | 门禁 §7 | 编译期：两个文件出现 `android.view`/`android.widget` ⇒ 判死 |
| **V7** | 判据**只读**：`load()` 前后目标目录的 mtime/内容不变 | 单测 | load 前后对目录做一次清单+md5 比对 |
| **V8** | `Issue` 里**没有成品句子**（只有码 + 参数） | 门禁 §7 | grep `Issue` 构造点，第二个参数必须是常量 |

---

## 7. 门禁（建议做成 `tools/ide-check.py`，与 `i18n-check.py` 同族）

| 规则 | 判据 | 为什么 |
|---|---|---|
| **IDE-01** | `ModProject.java` / `ModWriter.java` 里出现 `android.view` / `android.widget` ⇒ **ERROR** | V6（保证能在 PC 单测） |
| **IDE-02** | 视图层文件（`ModIde*Activity.java` + `view/` 下）出现 `internalNameOf(` / `"-" +` / `* 32` / `.part` / `renameTo` / `compress(` ⇒ **ERROR** | V1/V2/V4 |
| **IDE-03** | 元断言：故意在视图层塞一行 `x * 32`，要求 IDE-02 **判死** | 门禁自己有牙（本工程的老规矩） |
| **IDE-04** | `Issue` 的 `code` 必须全部在 `ModIdeText` 里有映射（**双向**：码表 ↔ 资源 key） | 漏一条就是"界面上显示不出来" |

---

## 8. 与既有零件的边界（**谁负责什么，避免重复实现**）

| 能力 | 归谁 | 我们**不**做什么 |
|---|---|---|
| 注册名算法（前缀 + 完整主干、目录层级不进名） | **`ModSprites`** | ❌ 不重写、不复制 |
| 贴图尺寸 | **`ModSprites.dims(Hit)`** | ❌ 不自己解 PNG 头 |
| 内容表（原版 + 模组 + patch 叠加、bundle 译名） | **`MapStatsMods.contentFor`** | ❌ 不另建一套内容表 |
| HJSON 宽容解析 | **`Hjson.toJsonText(text, ARC_160)`** | ❌ 不用严格 JSON 解析器 |
| 内部名 | **`Mods.internalNameOf`** | ❌ 不另写小写化 |
| 槽目录 | **`Data.dirOf(ctx, slot)`** | ❌ 不硬编码路径 |
| 游戏在跑？ | **`Data.gameAlive(ctx)`** | —— |
| 搬家（旧件进站） | **`Trash.move`** | ❌ 不裸 `renameTo` |
| 原子写文本 | **`Util.atomicWriteText`** | （`ModWriter` 内部可用它写 `.part` 之后的内容） |
| 像素级渲染/预览 | **`Msch*`**（§80） | ❌ 不另写渲染器 |

★ 这条表的作用：**"以后改起来不麻烦"** —— 改注册名规则只需改 `ModSprites` 一处，
IDE 三个视图自动跟着变。

---

## 9. 全部决定都已落定（**不再有待你拍板的"细节"**）

> 按用户 2026-10-08 的订正：「**入口放哪这种问题不随便改啊**」⇒ 判据 = **牵动调用点 / 用户数据才问，其余自己定。**

| 项 | 定案 | 可逆性 |
|---|---|---|
| **Q1** 一致性行为 | **只报不改**（"一键修"是另一条写用户数据的路径，风险不成比例） | 🟢 易（后加是加法） |
| ~~Q2~~ zip 解不解 | ✅ 已消失：**读不用解包**（§3.2）；**改**要走［复刻到工坊］ | —— |
| **Q3** 缓存 | **不缓存**（每次 `load` 重扫） | 🟢 易 |
| **Q4** `size` 缺失级别 | **WARN** | 🟢 易 |
| **Q5** 入口措辞 | **「模组工坊」**（主页工具卡里一行） | 🟢 **随便改**（免责层） |
| **Q6** 一期带"新建空白贴图" | **带** | 🟢 易 |
| **Q7** 画板默认缩放 | **适应屏幕** | 🟢 易 |

### 9.1 🔴 只有三条**真不可逆**（因为一有用户数据就得写迁移）—— 我按下面定，不同意再说

| # | 定案 | 若将来要改的代价 |
|---|---|---|
| **I-1** | 工程元数据 = 根目录的 **`.mdt-project.json`**，`schema` **只加不改**、新字段一律可选 | 老工程要写迁移脚本 |
| **I-2** | 工程目录名 = **模组内部名口径**（`Mods.internalNameOf`）；**重名不静默覆盖**，追加 `-2`/`-3`，界面显示**实际**工程名 | 目录名 = 工程身份，改名 = 用户可见的身份变化 |
| **I-3** | ［安装到槽］同名 ⇒ **先问**（覆盖 / 换名 / 取消）；覆盖 ⇒ 旧件**完整进中转站** | 直接决定用户槽里的数据 |
