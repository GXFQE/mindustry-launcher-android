# Spike：`mindustry.data.dir` 能否免掉 M3 的「目录改名交换」

**日期**：2026-10-01
**设备**：`<设备序列号已隐去>`（API 36），游戏 = `official-159.apk`（Mindustry 159.7）
**结论**：✅ **成立**。注入 `System.setProperty("mindustry.data.dir", <绝对路径>)` 后，
游戏把**全部**数据写进指定目录；原数据根 `files/` 一个字都没动。
⇒ M3 的槽模型可以从「改名交换」升级为**真隔离**。

---

## 一、源码链（4 处已核实，全部来自官方源码包 `Mindustry-160.4` / `Arc-160.4`）

| # | 位置 | 事实 |
|---|------|------|
| 1 | `Mindustry-160.4/core/src/mindustry/ClientLauncher.java:42~45` | `setup()` 的**第一条语句**读属性：`String dataDir = System.getProperty("mindustry.data.dir", OS.env("MINDUSTRY_DATA_DIR")); if(dataDir != null) Core.settings.setDataDirectory(files.absolute(dataDir));` |
| 2 | `Mindustry-160.4/android/.../AndroidLauncher.java:58` | `initialize(new ClientLauncher(){...})` ⇒ **安卓跑的就是同一份 `ClientLauncher`**，不是副本 ⇒ 上面那段在安卓上真的会执行 |
| 3 | `Arc-160.4/arc-core/src/arc/Settings.java:292` | `setDataDirectory(Fi)` 只是 `this.dataDirectory = file;`（无副作用、无校验） |
| 4 | `Mindustry-160.4/core/src/mindustry/Vars.java:337` | `dataDirectory = settings.getDataDirectory();` 之后 `saves/maps/previews/tmp/mods/assetCache/schematics/screenshots` **全部由它派生** ⇒ 一个属性改掉，整套跟着走 |

### 时序（关键，实测证实）
```
:game 进程创建
  └─ Application.onCreate            ← 我们注入属性的地方（最早）
       └─ GameSlot.onCreate          ← M3 现在在这里做 swapTo
            └─ startActivity(mindustry.android.AndroidLauncher)
                 └─ AndroidLauncher.onCreate (主线程)
                      ├─ initialize(new ClientLauncher())   → GL 线程起壳
                      ├─ setDataDirectory(getExternalFilesDir(null))   ← 主线程，先跑
                      └─ deleteDirectory("cache")
                 └─ GL 线程: ClientLauncher.setup()
                      └─ ★ setDataDirectory(files.absolute(注入值))     ← 晚跑 ⇒ 覆盖上行
                 └─ GL 线程: Vars.loadAsync() → init()
                      └─ dataDirectory = settings.getDataDirectory() = 注入值
```

### 第三处 `setDataDirectory` 已排除
`Vars.java:496` 的那一行在 `if(steam || Version.isSteam)` 里 ⇒ **安卓不参与竞争**。

---

## 二、五个"疑似不跟随"的逐一判定

| 嫌疑 | 源码位置 | 判定 |
|------|----------|------|
| `files_moved` 一次性复制 | `AndroidLauncher.java:244~261` | ⚠️ **半跟随**。判定条件 `Core.files.local("files_moved")` 用的是 `getLocalStoragePath()` = **内部** `getFilesDir()`，**不受注入影响**（好事：标记不会失效）。但复制目标是循环外捕获的局部变量 `data`（= 外部默认目录），**不跟随**。只在标记缺失时跑一次 ⇒ 实测 `files_moved_103` mtime 未变，**没触发**。 |
| `cache/` 删除 | `AndroidLauncher.java:235` | ✅ 跟随（用的是 `getDataDirectory()`）。实测两个目录都没有 `cache/`，无危害。 |
| 权限申请 | `AndroidLauncher.java:129~207` | ✅ 无关（走 `getContentResolver()` / SAF，与路径无关）。 |
| FileChooser | 同上 | ✅ 无关。 |
| **MDT 自己的 `Data.dataRoot`** | `Data.java:67` → `Paths.gameDataRoot()` → `getExternalFilesDir(null)` | ❌ **唯一真正不跟随的**。这是 MDT 自己的代码，必须自己改。 |

---

## 三、实测记录

### 基线（实验前）
```
/sdcard/Android/data/io.mdt.launcher/files/
  settings.bin      470B  10-01 11:26   md5 42af7af009393341a7166acba98be105
  last_log.txt      960B  10-01 11:26   md5 fa8ea5f8954433d4e29b281880cc8bad
  files_moved_103    17B  09-30 23:23
  mods/ previews/ tmp/ server_list.json settings_backup.bin settings_backups/
  （无 saves/、无 schematics/）
```

### 实验：开关 = `/sdcard/Android/data/io.mdt.launcher/spike-datadir`
```bash
echo '<路径>' > files/spike_datadir.txt
am start -n io.mdt.launcher/.GameSlot --es apk .../official-159.apk
```
`spike-result.txt`：
```
spike: set mindustry.data.dir=/sdcard/Android/data/io.mdt.launcher/spike-datadir
  dir exists/created = true
  process = io.mdt.launcher:game      ← 确认只在 :game 生效
```

**注入目录被写满**（13:23）：
```
spike-datadir/
  last_log.txt        381B  (内容含 "Total time to load: 2423ms" / "Fetched 259 community servers")
  settings.bin        390B
  server_list.json  11326B
  mods/  previews/  settings_backups/
```

**原数据根 md5 与基线逐字节相同** ⇒ 完全隔离：
```
42af7af009393341a7166acba98be105  files/settings.bin
fa8ea5f8954433d4e29b281880cc8bad  files/last_log.txt
```

### 回滚验证：删掉开关文件再启动
```
files/last_log.txt    13:24 ← 又写回原数据根
files/settings.bin    13:24 ← 同上
spike-datadir/*       仍是 13:23 ← 一个字节没动
```
⇒ 开关**双向都生效**，且默认关闭（不建开关文件 = 完全保持现有行为）。

---

## 四、改成真隔离要动什么（实施清单）

### 4.1 路径层（`Paths.java`，唯一真源）
现在 `gameDataRoot()` = `getExternalFilesDir(null)`，它**同时**充当三件事：
① 游戏数据根 ② 槽交换区 ③ HUB 冗余体检的扫描根。必须拆开：

```java
/** 外部根 = 槽的父目录（原来被当成数据根用的那个） */
static File externalRoot(Context ctx){ return ctx.getExternalFilesDir(null); }

/** 槽目录（数据根本体） —— 唯一规则：slot-<当前槽名> */
static File gameDataRoot(Context ctx){
    File p = externalRoot(ctx).getParentFile();
    return new File(p, "slot-" + readSlotName(ctx));
}
```
⚠️ `Paths` 是最底层、不能 import `Data`；槽名从 `slotRecord(ctx)` 直接读（现在 `Data.currentSlot()` 就是这么干的），把读逻辑下沉到 `Paths` 或允许 `Data` 覆盖 —— **二选一，别两边各存一份**。

### 4.2 注入层
- 注入点：`LauncherApp.onCreate()`（`:game` 进程 + 主进程各跑一次；只在 `:game` 里设）。
  备选：`GameSlot.handle()` 里 swap 那一步原地改成 `System.setProperty(...)`，**更贴近现有代码、改动更小**，且槽是那一刻才确定的。
- 必须 **`setProperty` 而不是 `-D`**：安卓没有 JVM 启动参数。

### 4.3 `Data.swapTo()` 降级
- 整个 rename 交换（① `files`→`slot-cur`、② `slot-tgt`→`files`）**全部删除**，只留 `rememberSlot(ctx, target)`。
- `slotDir()` / `dirOf()` 语义要改：现在 `dirOf(当前槽)` 特判返回 `dataRoot`（因为交换后 `slot-<当前名>` 不存在）—— 新模型下 `slot-<当前名>` **就是**数据根，特判可以删掉，逻辑反而变简单。
- 仍然要**保留"必须先退出 :game 才能备份/恢复"**的约束（那条与交换机制无关，是游戏在写文件）。

### 4.4 数据迁移（向上兼容，不能丢用户存档）
老用户的布局是「`files/` 就是当前槽」+ 若干 `slot-<名>/`。新布局要求当前槽也躺在 `slot-<当前名>/`。
⇒ 需要一次性迁移：`files/` → `slot-<currentSlot>`，**rename 不复制**（同分区瞬时）。
⚠️ 陷阱：`files/` 里混着 `files_moved` / `files_moved_103` / `spike-*` 这类**游戏/HUB 自己的标记**，
    不能当成存档带走；也不能把 `files/` 整个删掉（游戏会认为从没搬过、重新把内部 `files/` 复制一遍）。
⇒ 迁移后要让 `files/` 目录**存在且为空**（或只留标记文件）。
⚠️ 迁移必须**幂等**且**失败可回滚**（rename 失败就原地不动、宁可停在旧布局）。

### 4.5 需要一并核对的边角
- `Paths.externalHub()` = `getExternalFilesDir(null).getParentFile()/hub` —— **不受影响**，继续可用。
- 备份/恢复 `Backup.java`、`Msav.java`、`SavesActivity` 全部走 `Data.dirOf()` ⇒ 改完 `Paths.gameDataRoot` 自动跟随。
- **`Data.healthScan()`** 的 `REDUNDANT_NAMES`（`config.json`/`import`/`natives`）判定基于"数据根"，
  迁移后扫描根变成槽目录，历史残留可能仍留在**旧的 `files/`** 里 ⇒ 体检范围要加一个"旧 `files/`"。
- `slotNames()` 现在从 `slotParent()`（= `dataRoot.getParentFile()`）列举 —— 改完 `dataRoot` 后
  `getParentFile()` 会变成 `slot-x` 的父目录，仍正确，但**必须确认它等于 `externalRoot()`**。

### 4.6 风险 / 收益
| | 现在（改名交换） | 改后（真隔离） |
|---|---|---|
| 切换耗时 | 3~4 ms（与数据量无关） | ~0（只改一个属性） |
| 多版本并行 | ❌ 同一时刻只能一个槽在线；`files/` 被换走期间 HUB 自己也读不到 | ✅ 天然并行，目录各就各位 |
| 数据损坏面 | rename 失败要回滚，中途崩溃可能留下半交换 | ✅ 不动文件，没有中间态 |
| 对游戏的侵入 | 无 | 依赖 `ClientLauncher.setup()` 首行那 4 行（**MindustryX 的 `0027-H-misc.patch` 同样 patch 了这里** ⇒ 跨分支稳定；但第三方魔改包仍有风险） |
| 向上兼容 | — | 需要一次一次性迁移（见 4.4） |

**建议**：保留 rename 交换作为**兜底**（注入后校验一句"游戏真的写到新目录了吗"，否则回落到交换）。
判定方法现成：启动后看 `slot-<名>/last_log.txt` 的 mtime 是否变新。

---

## 五、本目录文件
- `LauncherApp-spike.java` —— 探针源码（**已从 `app/src/` 回滚**，仅作存档）
- `RESULT.md` —— 本文
