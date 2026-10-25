# 插件接口（PluginApi）—— 给插件开发者看的那一份

> 面向写插件的人；宿主内部的实现细节看 `docs/flows/` 与 `.dsh/memory/ref/`。
> 文档里的版本号必须等于 `PluginHost.API`，自检有一条元断言钉着（`PluginApi.DOC_VERSION`）。

## 1. 版本与兼容（v20 冻结基线）

- 宿主接受**区间**：插件 `api()` 落在 `[API_MIN, API]` 就加载。当前是 **20..28**。
  （v20 是冻结基线；v21 = 游戏设置面；v22 = 后台任务 + 游戏事件；v23 = 探针；v24 = 分析器；
  v25 = 结论挂行 + 筛选/排序注入 + HJSON；v26 = 逐项判定；v27 = 详情页的门 + 槽级结论进摘要卡；
  v28 = 原版文案键。）
- 插件 `api()` 报的是「我需要的最低宿主接口版本」。用不到的新能力**不要**报高 ——
  报高了只会在老宿主上被拒，换不来任何东西。
- **不要用 try/catch 去兼容老宿主**：报什么版本就只会跑在什么版本上。`api()` 报 20，
  宿主就保证 20 的接口面都在。
- 以后的变化规则：**加法**（多一个方法 / 多一个回调字段）只抬上限 `API`，下限 `API_MIN` 不动，
  老插件照旧装得进；**破坏性**（改语义 / 删方法）才抬下限。
- 报的版本**高于**本机 ⇒ 拒绝并说明「宿主太旧」；低于 `API_MIN` ⇒ 说明「插件太旧」。
  v1~v19 的老写法（`createView(Context)` 等）从 v20 起**不再回落**。

## 2. 插件包结构

```
你的插件.apk
├── classes.dex                 # 入口类 + 你用到的那份 io.mdt.launcher.api.*
└── assets/plugin.json          # 自述与扩展声明（org.json 解析）
```

编译时的 API 桩**直接取宿主的** `app/src/io/mdt/launcher/api/*.java`（仓库里的官方插件就是这么编的，
`plugins/*/build.sh` 里有现成写法）。**不要**再往插件工程里放一份副本：副本一定会漂。

`assets/plugin.json`：

```json
{
  "entry": "com.example.MyPlugin",       // 入口类全名
  "name": "myplugin",                    // 稳定 id（安装目录名）
  "version": "1",
  "author": "you",
  "description": "一句话说明",
  "access": ["slots"],                   // 声明用途（给人看，不是权限门）
  "extensions": [
    { "id": "my-row", "title": "做点什么…", "subtitle": "副标题",
      "entry": "com.example.MyPlugin", "slot": "content", "page": "maps" }
  ]
}
```

`slot` 三种落点：

| slot | 出现在哪 | 点了之后 |
|---|---|---|
| `content` | 某一页的动作区（一行） | 打开**插件自己的页面**（`createView`） |
| `section` | 宿主页面里的一块 | 直接画进宿主页（`createView`，`api.page()` 知道在哪） |
| `action` | 某一页的动作区（一行） | **不换页**，直接调 `run(PluginApi)` |

`page` 取值：`main` / `slots` / `slot` / `saves` / `maps` / `mods` / `blueprints` / `map-detail` / `blueprint-detail` / `settings` / `logs` / `trash`。

⚠️ 三类插槽现在**所有页面都挂齐了**（2026-10-24 第 226 轮统一）：

| slot | 已接的 page |
|---|---|
| `content` | 全部 |
| `section` | 全部（画在「插件」卡里） |
| `action` | 全部（同一张卡里，点了在原页跑） |

## 3. 入口类约定

```java
public static int api();                        // 必需：你需要的接口版本（当前 28；用不到新能力就报 20）
public static View createView(PluginApi api);   // content / section 用
public static void run(PluginApi api);          // action 用（在原页跑，不换页）
public static void unload();                    // 可选：收尾（也认 unload(Context)）
```

- 入口类**一个插件一份**，方法都是 `public static`，由宿主反射调用。方法名拼错**不会报错**，
  只会安静地不被调用 —— 自检/冒烟都看不出来，写完先确认一次真的跑到了。
- `createView` / `run` 在**主线程**调用；耗时活请用 `runBackground`。
- `unload` 只在**整页插件**（`content` 槽打开的插件页）关闭 / 换页 / Activity 销毁时调用一次；
  `section` / `action` 没有页面生命周期，**不会**调用。进程被杀也不会调用。
  `unload` 里抛异常只写 `plugin unload failed:` 日志，别在里面做必须成功的事。
- `api()` 缺失 / 不是 int ⇒ 加载阶段就拒（`plugin_err_entry`）。

## 4. 接口清单

### 环境

| 方法 | 说明 |
|---|---|
| `hostApi()` | 宿主支持的接口版本（判断能力用；当前 28） |
| `context()` | 插件上下文（**只用来取资源 / 主题 / 建 View**） |
| `page()` / `slot()` | 从哪一页来 / 当前槽名 |
| `theme()` | 主题色与 dp/sp（别自己写死颜色，见 `PluginTheme`） |

### 只读数据

| 方法 | 说明 |
|---|---|
| `selectedItem()` / `selectedItems()` | 详情页当前那一项 / 页面选中项快照 |
| `pageItems()` | 当前页面看到的数据快照 |
| `slots()` | 全部槽 |
| `contents(kind)` / `contents(slot, kind)` | 四类内容；`kind` = `saves`/`maps`/`mods`/`blueprints` |
| `logs()` / `trash()` / `versions()` | 日志与崩溃报告 / 中转站 / 已装版本 |
| `open(item)` | 打开条目的字节流（文件或容器条目统一） |
| `modsSkipped()` / `gameRunning()` | 这一轮会不会加载模组 / 游戏在不在跑 |
| `baseBundleKeys()`（v28） | 目标版本**基础语言包**的键集合（判"是不是原版文案"用；空集 = 读不到，不是"没有"） |
| `usageDir()` | 插件数据目录（见第 9 节） |
| `crashReportsDir()`（v22） | 当前槽的崩溃报告目录（只读；可能不存在） |

### 目录

`dataDir()` `slotDir()` `savesDir()` `modsDir()` `mapsDir()` `blueprintsDir()`

`dataDir()` 是**你自己的**可写目录（宿主保证存在）。

### 交互

| 方法 | 说明 |
|---|---|
| `toast(text)` / `log(text)` | 提示 / 调试日志（logcat tag `MDTPlugin`） |
| `choose(title, options, cb)` | 宿主原生多选一弹窗 |
| `confirm(title, msg, okText, cb)` / `prompt(...)` | 是/否 / 输入弹窗 |
| `pickContent(kind, cb)` | 用宿主那套勾选框选内容（**不是**选任意文件） |
| `pickFile(mime, cb)` | 系统选择器挑任意文件，宿主复制到你的 `cache/` 再给 `(name, File)` |
| `progressCard(title, msg, done, total[, cancelable, cb])` / `progressCardClose()` | 宿主进度框（4 参 = 不可取消） |

### 任务

| 方法 | 说明 |
|---|---|
| `runBackground(label, body)` | 页内长活：后台线程 + 前台服务通知；不给句柄、不发完成通知 |
| `submit(label, body)`（v22） | **后台任务**：返回 `Task`；页面关了照跑，完成/失败/取消各发一条通知 |
| `progress(done, total)` | 更新常驻通知上的进度 |

`Task` = `id()` / `running()` / `cancel()`（取消 = 置标记 + `interrupt()`，任务体自己配合）。

⚠️ 生命周期 = **本进程**：进程被系统杀就没了（没有 WorkManager）⇒ 下载要断点续传、
分析要把 checkpoint 落进 `dataDir()`。`unload()` 不会取消任务 —— 别在 `unload` 里杀任务。

### 事件（v22）

在 `plugin.json` 里用 `event` 声明（**不是** `slot`）：

```json
"extensions": [
  { "id": "ev-start", "entry": "com.example.MyPlugin", "event": "game-start" },
  { "id": "ev-exit",  "entry": "com.example.MyPlugin", "event": "game-exit"  },
  { "id": "ev-boot",  "entry": "com.example.MyPlugin", "event": "boot"       },
  { "id": "ev-fg",    "entry": "com.example.MyPlugin", "event": "app-foreground" }
]
```

| 事件 | 什么时候 |
|---|---|
| `boot` | 本次进程起来后**第一次**回到界面（每个进程一次） |
| `app-foreground` | 每次从后台回到前台（含 `boot` 那一次） |
| `game-start` | 点"启动"、兼容性复检通过之后，`startActivity` 之前 |
| `game-exit` | 回到启动器、且上一件事是启动游戏 |

入口方法：

```java
public static void onEvent(PluginApi api, String name, Map<String, String> data);
```

- `data` 目前有 `name` / `slot` / `version`（拿不到就不放这个键）；
  `game-exit` 若是启动器被杀后**补报**的，多一个 `backfill=1`。
- 回调在 **UI 线程**（可以用 `confirm` / `toast`）；异常只进 `MDTPlugin` 日志，不影响别的插件。
- 每次派发会 `load` 一份插件、跑完 `unload`：适合"启动前 / 退出后做一件事"，
  **不适合当常驻监听** —— 常驻请用 `submit()`。
- ⚠️ `game-exit` 的交付：启动器在游戏期间被系统杀 / 最近任务划掉 ⇒ 重启后靠落盘标记补报；
  如果重启时**游戏还在跑**，不补报（标记留着，等下一次回到前台且游戏已退出）。

### 探针（v23）

插件在 `plugin.json` 里声明（**不走** `extensions`）：

```json
"telemetry": {
  "probes": [
    { "class": "com.example.HeapProbe", "sec": 5,  "inProcess": true  },
    { "class": "com.example.ProcProbe", "sec": 10, "inProcess": false }
  ]
}
```

```java
public interface Probe {
    String name();
    void reset(ProbeEnv env);
    void sample();
    default void summary() { }
}
```

- `inProcess: true` ⇒ 跑在 `:game`（能反射游戏类、拿 Java 堆 / GC / 帧时间；崩了是游戏崩）
- `inProcess: false` ⇒ 跑在 `:probe` 隔离进程（只能读 `/proc/<游戏 pid>`，崩了不连带游戏）
- `ProbeEnv`：`dir()`（本会话给这个插件的目录）/ `pid()` / `inProcess()` / `slot()`
- **数据由插件自己写**（格式自定）；宿主不解析、不聚合、不展示
- 调度：每 `sec` 秒一次（1..3600，缺省 5）；`sample()` 在后台线程，单次 >200 ms 记失败；
  连续 3 次失败 ⇒ 停用该探针并写 `probe-fail.txt`
- 目录：`<hub>/usage/telemetry/<会话>/<插件 id>/`
- ⚠️ 探针 APK 必须住在**内部存储且只读**（Android 14+ 拒绝加载可写 dex）——宿主按这条存插件
- `summary()` 只在进程正常收尾时调用（进程被杀不会）
- ⚠️ **第三方 + `inProcess`**：在插件页启用时会弹一次风险告知（官方 / 纯隔离进程不弹）
- 崩溃弹窗**和日志页的崩溃报告**都会加一句「本次会话有插件参与：…」（标官方/第三方、进程内/隔离进程）；
  报告页按**这份报告的时间**挑会话（12 小时内才算，昨天的崩溃不贴今天的探针）

### 分析器（v24）

插件给结论，宿主画。在自述里声明（**入口类里再加一个静态方法**）：

```json
{ "id": "mods-health", "title": "模组体检", "entry": "com.example.ModTool",
  "slot": "analyzer", "page": "mods" }
```

```java
public static List<Finding> analyze(ItemInfo item, PluginApi api);
```

```java
public final class Finding {
    public static final int INFO = 0, WARN = 1, ERROR = 2;
    public Finding(String title, String detail, int severity, String... evidence);
    // title / detail / severity / evidence 只读字段
}
```

- 宿主对页面里**每一项**调用一次，收集 `Finding` 画成「插件」卡里的一段文字
- **严重度**：`INFO` 灰、`WARN` 琥珀、`ERROR` 红（深色主题另有亮一档的值），每条前缀「信息 / 警告 / 问题 · 」
- 超过 **6 条**：先显示 6 条 + 「显示全部（还有 N 条）」，点一下就地展开（不跳页）
- 插件**不画 UI**（没有宿主的卡片/主题，硬画不像）⇒ 风格天然一致
- **所有页面都支持**（`page` 与插件拿到的 `pageItems()` 是同一个数据源，2026-10-24 第 227 轮放开）
- 单条抛异常只丢这一条，不影响别的条目/页面

#### v25：`Finding.anchor` —— 挂到某一项上

```java
new Finding(title, detail, severity, evidence).anchor(item.path);
```

- 有 `anchor` ⇒ 宿主画在**那一行**（行上 `⚠ <title>`，`detail` 进详情弹窗的「插件结论」小节）；
- 没有 ⇒ 仍旧进底部「插件」卡（同一页可以混用）；
- 键是 `ItemInfo.path`（与 `contents()` 里的那一项**逐字相同**）；
- 分析器在**列表渲染之后**才回来 ⇒ 宿主拿到后会重画一次列表（模组页就是这么做的）。
- 它也是**导入体检**的数据源（第 233 轮，宿主侧 P5）：导入成功后宿主再跑一遍，只挑
  `anchorPath == 刚导入那一项` 的结论，单份塞进结果弹窗、批量补进报告；**插件没装则整段不显示**。
  ⇒ 想让「导入时」也说一句话，写在 analyzer 里就行，**不用另加接口**。

### 逐项判定（v26，可选）

```java
public static String state(ItemInfo item, PluginApi api);   // 返回下面词表里的一个 token
```

返回**固定词表**（宿主只做映射，不解释语义）：

| token | 宿主把它当成 |
|---|---|
| `enabled` | 会加载 |
| `disabled` | 已关闭 |
| `unsupported` | 不支持（版本不够 / 门槛太低 / 黑名单） |
| `missing-dep` | 缺依赖 |
| `incomplete-dep` | 依赖被关着 |
| `circular-dep` | 循环依赖 |
| `content-error` | 内容有错 |

- 宿主用它画**行徽标**、决定「只看有问题的」、写详情那句"结果" —— **三处同一个判定**，不会出现
  "徽标说 A、行上写 B"；
- 返回 `null`（或没实现这个方法）⇒ 宿主回落到它自己的判定。读不出说明文件的坏包就该走这条：
  那是**文件级错误**，归宿主；
- 词表是**闭集**：多写一个不认识的 token 等于没给（宿主回落）。要加新状态，先改宿主。

### 详情页的门（v27）

```java
public static List<Gate> gates(ItemInfo item, PluginApi api);   // 可选
```

```java
public final class Gate {
    public final String label;    // 给人看的一行（通过与不通过各一条文案）
    public final boolean pass;
    public final String note;     // 补充说明；通过时一般留空
}
```

- 宿主对页面里**每一项**调用一次；返回空表 ⇒ 那一项**不显示**门（宿主自己那份判据已删，别指望回落）；
- `pass == false` 时 `note` **必须非空**（「❌ + 没有原因」等于白说）；
- `label` 必须**跟着状态换**（❌ 后面不许跟肯定句），通过的标签与不通过的标签是两条不同文案；
- **槽级事实用槽级措辞**：整槽跳过不是"它崩了"；`-failed` 才是这个模组自己的记录；
- **不要猜默认值**：`extra.settingsKnown == false` 时「启用」只是默认值，要补一句说明。

### 槽级结论放摘要卡（v27）

```java
new Finding(title, detail, severity, evidence).summary();
```

- 只对**不挂行**的结论有效；`anchor()` 优先（挂行的仍画在那一行）；
- 落点是页面的**摘要卡**（"这个槽怎么了"），不是底部的「插件」卡 —— 用于整槽跳过这类槽级事实；
- 同一件事只说一次：槽级结论进了摘要卡，就不要再按行重复。

### 原版文案键（v28）

```java
Set<String> keys = api.baseBundleKeys();   // 目标版本 assets/bundles/bundle.properties 的键
```

- 用途：判断"两个模组是不是都在改同一句**原版**文案"。模组的 `bundles/*.properties` 与版本 APK 的
  进**同一个 map**，后加载的覆盖先加载的；但**只有原版键**才算覆盖原版 —— 模组自己的键（`xxx.*`）
  被别的包也写一份，往往是翻译包 / fork（真机上 13 个模组有 4,437 条同名键，多数是这种）。
- **空集 = 没有目标版本 / 读不出来**（不是"原版没有这些键"）⇒ 别据此下结论。
- 第一次调用会打开版本 APK 读一份文本，之后按 APK 路径 + mtime 缓存（插件页每次重扫都会问）。
- 同一份键集还能推出**原版内容名**（`block.<name>.name` → block / 名字）：用来判"两个模组是不是都在给
  同一个原版内容改数值"（模组 `content/<类型>/**.json` **不带 `type`** 就是给同名原版内容打补丁）。

### 筛选 / 排序注入（v25）

插件可以往页面的「筛选与排序」菜单里加自己的一项（与 `extensions` 同一个数组）：

```json
{ "id": "conflict-filter", "title": "有冲突的模组", "entry": "com.example.ModTool",
  "slot": "filter", "page": "mods" }
{ "id": "risk-sort", "title": "按冲突风险", "entry": "com.example.ModTool",
  "slot": "sort", "page": "mods" }
```

入口类（**必须是声明了 `api()` 的那个类** —— 宿主 `PluginHost.open` 要拿它判版本）提供：

```java
public static boolean accept(ItemInfo item, PluginApi api);        // filter：要不要这一项
public static int compare(ItemInfo a, ItemInfo b, PluginApi api);  // sort：<0 靠前
```

- **只在用户选中那一项时才算一次**，结果由宿主缓存（不会在打字搜索时反复加载插件）；
- 计算在后台线程，算的时候弹一个「正在问插件…」的进度框；
- 插件坏了 / 删了 / 方法缺失 ⇒ 当没筛、按原序，页面不崩；
- 内置的搜索 / 排序先做，插件这一层最后做（同一份结果顺序就是最终顺序）。

### HJSON 解析（v25）

```java
String json = api.parseHjson(file);   // 标准 JSON 文本；失败/读不了 ⇒ null
```

宿主用自己读 `mod.hjson` 的同一条路（先 ARC 160 方言、再 159），插件拿 `org.json` 取字段 ——
**解析归宿主、用法归插件**。模组包自己的 meta 本来就在 `ItemInfo.extra` 里，不用再解析一次。

### 出口

| 方法 | 说明 |
|---|---|
| `export(name, mime, stream[, after])` | 系统保存框；`after.onAfter(Result)` 是**真的写完/取消/失败**才回调 |
| `share(name, stream)` / `shareText(name, text)` | 系统分享面板 |

### 写与整理

| 方法 | 说明 |
|---|---|
| `writeItem(item, stream, cb)` | 换掉**槽内四类目录里已存在**文件的字节（原子替换；游戏在跑时拒绝） |
| `trashItem(item, cb)` | 挪进中转站（不是真删，规则与界面删除同一套） |
| `openItem(item)` | 跳详情页（地图/存档 → 地图详情；蓝图 → 蓝图详情；模组 → 模组页） |

### 游戏设置（v21）

`settings.bin` 的**解析结果**与**安全写**：键的含义归你，格式与安全归宿主。

```java
Map<String, Object> gameSettings();                               // 只读快照（保序）
void writeGameSettings(Map<String, Object> changes, Done done);   // 只改给定键；null = 删键
```

- 值域 = 文件里的六种：`Boolean` / `Integer` / `Long` / `Float` / `String` / `byte[]`。
- `gameSettings()`：文件不存在 ⇒ **空表**；读不出来 ⇒ **`null`**（别当"空设置"处理）。
  真实文件 5900+ 键 / ~500 KB ⇒ **别在主线程调**。
- `writeGameSettings()`：后台线程读写，`Done.onDone(Result)` 回主线程；
  失败 reason = `game-running` / `unreadable` / `invalid` / `io`。
- **宿主保证的机制**（不是语义）：游戏在跑 ⇒ 拒绝 · 写前备份到
  `<hub>/settings-backups/slot-<槽>/`（留 20 份）· 原子写 · 写后读回自检、不过自动还原 ·
  未知键与键顺序原样保留。
- **插件负责的语义**：键名含义、缺省值、成对写。例如 `mod-<名>-enabled` **不存在 = 启用**；
  改 `-enabled` 时要同时清 `-failed`，否则留下永远清不掉的陈旧记录。
- 写是**合并**：没给的键一个都不动；删键用 `changes.put(key, null)`。

### 页面

`closePage()`：关掉插件页（宿主负责返回导航）。

## 5. 线程与生命周期约定

- `createView` / `run` 在 UI 线程；**不要**在里面读大文件。
- `runBackground(label, body)` 的 `body` 在后台线程；**别碰 View**，要更新 UI 用 View 自己的 `post`。
- 回调（`Choose` / `Confirm` / `Prompt` / `PickFile` / `PickContent` / `After` / `Done` / `Cancel`）
  **都在 UI 线程**回调。
- `export` / `share` / `writeItem` 传进去的 `InputStream` **由宿主关闭**；`pickFile` 给你的是文件，自己读。

## 6. 失败语义：`Result`

写 / 保存 / 挪这一层统一回调 `Result`（`io.mdt.launcher.api.Result`）：

```java
public final boolean ok;     // 成功？
public final String reason;  // 机器码；成功恒为 null，失败恒非空
```

| `reason` | 含义 |
|---|---|
| `game-running` | 游戏在跑，写操作被拒（先退出游戏） |
| `not-in-slot` | 条目不在槽内那四类目录里 |
| `not-found` | 条目不存在 / 没有路径 |
| `canceled` | 用户取消（**不是错误**，别弹红字） |
| `busy` | 同一时刻只允许一份（保存 / 分享排队） |
| `no-page` | 当下没有可用的界面 |
| `io` | 读写失败 |
| `invalid` | 参数不合法（null / 类型不对） |
| `unknown` | 宿主没给具体原因（兜底） |

⚠️ `reason` 是**给代码看的**，别直接上屏 —— 文案由插件自己出（每个插件有自己的语言包）。

## 7. 边界（先看这几条再动手）

- **没有沙箱**：能读宿主能读的、能写你声明的地方；靠"约定 + 告知"，不是权限门。
- `writeItem` 只允许改**槽内四类目录里已存在的文件**（canonical 前缀校验），不能新建、不能写到别处。
- 插件的数据住在 `<插件>/data`（`dataDir()`），缓存住 `<插件>/cache`（`pickFile` 落点）。
- 界面文案请走 `api.context().getString(...)` 或插件自带资源；宿主的 `Trans` 只服务宿主自己。

## 8. 配套示例：`plugins/sample/`

仓库里的 `plugins/sample/` 是一个**按本文档从零写出来**的最小完整插件（约 150 行），用来当模板与冒烟测试：

- `section` 挂在 `map-detail`：显示当前地图的标题 / 大小，并读**前 8 字节**证明 `open()` 真的能读
- `action` 挂在 `maps`：勾选地图 → 选 json / csv → `export(..., after)`
- `unload()`：关页时写一行 `SAMPLE unload` 日志

构建（与 mapstats 同一条链，**API 桩直接取宿主那份**）：

```bash
bash plugins/sample/build.sh
# 产物：%TEMP%/mdt-sample/mapstats.apk
```

官方插件 `plugins/mapstats/` 是同一套写法的完整用例（约 1,100 行）。

### 冒烟脚本（改接口时必须跑）

```bash
bash tools/sample-smoke.sh            # 假设当前装的是 dev 变体
bash tools/sample-smoke.sh --dev-install
```

脚本会构建样例、装进 dev 宿主、断言"装上了、启动不崩"；两个入口的**肉眼检查**在结尾打印。

## 9. 会话记录：`usageDir()`

```java
java.io.File usageDir();   // <hub>/usage/
```

**这个目录归插件**：宿主只给目录，**不写任何会话流水、不聚合、不展示**
（2026-10-26 定案：注入的数据完全由插件自己写；旧的 `launch.tsv` / `run.tsv` 写入已删）。

- 探针自己的样本写 `ProbeEnv.dir()`（`<hub>/usage/telemetry/<会话>/<插件 id>/`，见第 8 节）；
- `usageDir()` 留给"跨会话、跨插件"的数据，格式插件自定，宿主不解析；
- ⚠️ 早期版本（v18~v28 期间）宿主写过 `launch.tsv` / `run.tsv`，**现在不再产生** —— 别依赖它们存在。

## 10. 历史

v1~v19 的逐版记录留在 git 历史与 `.dsh/memory/next/` 的轮次记录里（37/38/39 等分片）。
v20（2026-10-23）把逐年长出来的接口整理了一遍：删掉 `selectedFile` / `settings` / `hostIcon`、
`pick` 改名 `pickContent`、`Done`/`After` 换成 `Result`、重载收成默认方法、
删掉 v1~v6 的老加载路径。之后只按第 1 节的规则演进。
v21（2026-10-23 第 220 轮）**第一个加法**：`gameSettings()` / `writeGameSettings()`（游戏设置面），
`API_MIN` 不动 ⇒ 报 20 的插件照旧装得进。
v22（2026-10-23 第 221 轮）：`submit()` / `Task`（后台任务，完成/失败/取消通知）+
`event` 扩展（`boot` / `app-foreground` / `game-start` / `game-exit`，含被杀后的补报）+
`crashReportsDir()`（归因插件读原料）；`API_MIN` 仍不动。
v23（2026-10-23 第 223 轮）：`Probe` / `ProbeEnv` + `telemetry.probes`（`:game` 进程内 /
`:probe` 隔离进程两种探针）；数据由插件自己写，宿主只调度与隔离。
v24（2026-10-23 第 224 轮）：`Finding` + `slot: analyzer`（`page: mods`）—— 插件给结论、宿主渲染；`API_MIN` 仍不动。
v25（2026-10-24 第 229 轮）：`Finding.anchor`（结论挂到某一行）+ `slot: filter` / `slot: sort`
（插件注入自己的筛选与排序）+ `parseHjson(File)`（宿主解析、插件用）；`API_MIN` 仍不动。
v26（同日）：可选 `state(ItemInfo, PluginApi)` —— 插件给**逐项判定**（固定词表），宿主拿它画徽标 /
筛选 / 详情，并回落到自己的判定。
v27（同日第 229 轮 P4；第 232 轮抬版本）：`api/Gate` + 可选 `gates(ItemInfo, PluginApi)`（详情页六道加载门）+
`Finding.summary()`（不挂行的槽级结论进摘要卡）；`API_MIN` 仍不动（区间 20..27）。
v28（同日第 234 轮）：`baseBundleKeys()` —— 目标版本基础语言包的键集合（"两个模组是不是都在改同一句
原版文案"的事实来源；判据仍在插件）；`API_MIN` 仍不动（区间 20..28）。

**官方体检插件**：`plugins/mods-health`（第 229 轮，前身叫 `conflict`）。宿主原来的那行
「查模组冲突」整块删掉，判据搬进插件的 `core/Conflicts.java` 与 `core/Gates.java`；
界面只剩宿主的 `analyzer` 卡、行徽标、筛选/排序注入与详情页的门。三个注意点（都在那个工程里踩过）：
- `analyzer` 是**逐项**回调，而 `Finding` 没有 item 字段 ⇒ 整槽结论只在「页面第一项」那一次给，
  否则同一份结论会在卡片里重复 N 遍（插件按 `contents(mods).get(0)` 判，**不是**按回调顺序）；
- analyzer 每次进页面都会跑 ⇒ 重活要按 `mods/` 签名缓存（那个插件按「名字:大小:mtime」缓存判据，
  日志摘录不缓存 —— 它每次启动游戏都会变）；
- 门的**判据与文案分家**：判据（哪道门过、用哪条文案 key、要什么参数）必须放在插件 core 里，
  入口类只做 `key → R.string` 映射 —— 否则 `build.sh check` 编不到它，宿主一删断言就一条尺子都没有。
