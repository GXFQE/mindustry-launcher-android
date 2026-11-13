# 插件接口（PluginApi）—— 给插件开发者看的那一份

> 面向写插件的人；宿主内部的实现细节看 `docs/flows/` 与 `.dsh/memory/ref/`。
> 文档里的版本号必须等于 `PluginHost.API`，自检有一条元断言钉着（`PluginApi.DOC_VERSION`）。

## 1. 版本与兼容（v20 冻结基线）

- 宿主接受**区间**：插件 `api()` 落在 `[API_MIN, API]` 就加载。当前是 **20..40**。
  （v20 是冻结基线；v21 = 游戏设置面；v22 = 后台任务 + 游戏事件；v23 = 探针；v24 = 分析器；
  v25 = 结论挂行 + 筛选/排序注入 + HJSON；v26 = 逐项判定；v27 = 详情页的门 + 槽级结论进摘要卡；
  v28 = 原版文案键；v29 = 游戏前后台事件；v30 = 探针知道版本 / 跨会话数据目录 + 摘要卡总时长；
  v31 = 探针拿到完整版本信息（包名会原地更新，光有 key 不够）；
  v32 = `pickContent(kind, title, head, cb)`：勾选那一屏的标题 / 说明由插件自己给；
  v33 = 筛选 / 排序的 `options()` + `choose(int)`：宿主先弹第二级选择卡，再把选中项交给插件；
  v34 = 跨槽设置读写 `gameSettings(slot)` / `writeGameSettings(slot, …)`，
  以及 `writeItem` 可写任意槽四类目录里的已存在文件；
  **v35 = `addItem(slot, kind, name, stream, done)`：新建存档 / 地图 / 模组 / 蓝图文件
  （只校验槽名 / 后缀 / 原子写，内容归插件；不覆盖同名）；**
  **v36 = 选项可以多选**：`static boolean multi(PluginApi)` + `static void choose(int[], PluginApi)`
  —— 宿主把第二级选择卡换成**勾选框列表**（与「来源 / 类型」同一套控件），勾几个就回传 `int[]`
  （升序、去重）；几个选项之间是"且"还是"或"由插件自己定；**
  **v37 = 多实例感知**：`boolean gameRunning(String slot)`（null / 空 = 当前槽）+
  `List<String> runningSlots()` —— 能精确回答"**这个槽**在不在跑"；老的 `gameRunning()` 保持
  全局语义不变（兼容）。判据与宿主写门禁同一份（`WriteGate`），只表示调用那一刻的快照。**
  **v38 = 插件与补丁清单**：`List<ItemInfo> plugins()`（装着哪些插件）+ `List<ItemInfo> patches()`
  / `patches(versionKey, slot)`（已导入的游戏补丁；给了启动项才判 `enabled` / `applies`）。
  数据仍走 `ItemInfo` 快照（与 `versions()` / `logs()` / `trash()` 同一套），新增两个 kind；
  补丁的匹配键仍是**目标 APK 的 sha256**，不是版本号区间。**
  **v39 = 网络 / 补丁写 / 实例与备份**：`fetchText` / `githubReleases` / `download`（复用宿主更新基建：
  https-only、正文 512 KB / 下载 1 GB 上限、GitHub API 10 分钟缓存 + 每小时 40 次、
  走用户选的镜像线路、`.part` + sha256）；`patchEnable` / `patchDisable` / `patchImport` /
  `patchDelete` / `patchConflicts`（复用补丁机制自己的门禁 / 校验 / 原子写 / 中转站；
  **插件启停仍归宿主**）；`List<ItemInfo> instances()`（运行实例，含启动占位）+
  `List<ItemInfo> backups(slot)`（备份元信息）。另有一批 **`extra` 加键**（不抬版本的增量）：
  `versions()` 的 `instanceKey` / `bound` / `patchCount` / `backupCount`，`plugins()` 的
  `updateState` / `updateVersion` / `updateNote`，`settings()` 的六个公开设置键。**
  **v40 = 插入能力**：`showCard(title, body, buttons, cb)`（宿主卡片，最多 3 个按钮；回调只来一次，
  点按钮给下标、关掉给 -1）+ `openPage(page, slot)`（跳宿主页面；slot 空 = 当前槽）；`plugin.json`
  的扩展条目多一个可选 `"place": "actions"`（把动作插进宿主操作卡，四个内容页有锚点，别的页回落
  「插件」卡）；三个新页面钩子 `version` / `patches` / `resources`。**不给换主题 / 改布局。**）
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
  "update": "https://…/myplugin.json",   // 可选：插件更新清单（第 338 轮，见第 10 节）
  "extensions": [
    { "id": "my-row", "title": "做点什么…", "subtitle": "副标题",
      "entry": "com.example.MyPlugin", "slot": "content", "page": "maps" }
    // v40：可选 `"place": "actions"` —— 插进宿主那一页的操作卡（见下面「插入位置」）
  ]
}
```

`slot` 三种落点：

| slot | 出现在哪 | 点了之后 |
|---|---|---|
| `content` | 某一页的动作区（一行） | 打开**插件自己的页面**（`createView`） |
| `section` | 宿主页面里的一块 | 直接画进宿主页（`createView`，`api.page()` 知道在哪） |
| `action` | 某一页的动作区（一行） | **不换页**，直接调 `run(PluginApi)` |

`page` 取值：`main` / `slots` / `slot` / `saves` / `maps` / `mods` / `blueprints` / `map-detail` / `blueprint-detail` / `settings` / `logs` / `trash` / **v40** `version` / `patches` / `resources`。

⚠️ 三类插槽现在**所有页面都挂齐了**（2026-10-24 第 226 轮统一；v40 起带 `place: "actions"` 的
`action` / `content` 条目会改插宿主操作卡，见下面「插入位置」）：

| slot | 已接的 page |
|---|---|
| `content` | 全部（默认画在「插件」卡里；`place: "actions"` 时插操作卡） |
| `section` | 全部（画在「插件」卡里；**不支持** `place`） |
| `action` | 全部（默认「插件」卡；`place: "actions"` 时插操作卡） |

`place` 对 `plugins` / `analyzer` / `filter` / `sort` 等其它 slot **不生效**（静默按原行为）。

#### 插入位置（v40）

默认三类插槽都画在页面底部的「插件」卡里；`"place": "actions"` 让 `action` / `content` 条目
**插进宿主那一页的操作卡**（"删除…"那一行之前），与宿主自己的动作混排 —— 入口位置、分割线、
卡片样式都跟宿主一致，用户不用在两个地方找（用户 2026-11-13：「插件和宿主的 UI 非要分离开就很别扭」）。

- 目前有稳定操作卡锚点的页：`maps` / `saves` / `mods` / `blueprints`。别的页写了 `"place": "actions"`
  **回落**「插件」卡（不会消失）—— 没有锚点时放错地方比放回原处更糟。
- `section` 不支持 `place`（它本来画的就是一整块，不是一行）。
- **v40 新增三个页面钩子**：`version` / `patches` / `resources` 的布局里有 `plugin_section`，
  三类插槽都可以声明到它们上面。`version` 页把这一份版本的 `ItemInfo` 作为 `selectedItem()` 并放进
  `pageItems()`；`resources` 页把"选中的那份 APK 对应哪个版本"放进 `pageItems()`（`selectedItem()`
  仍是 null —— 这一页不是"某一张地图 / 存档"那种选中项）。

## 3. 入口类约定

```java
public static int api();                        // 必需：你需要的接口版本（当前 40；用不到新能力就报 20）
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
| `hostApi()` | 宿主支持的接口版本（判断能力用；当前 40） |
| `context()` | 插件上下文（**只用来取资源 / 主题 / 建 View**） |
| `page()` / `slot()` | 从哪一页来 / 当前槽名 |
| `theme()` | 主题色与 dp/sp（别自己写死颜色，见 `PluginTheme`） |

### 只读数据

| 方法 | 说明 |
|---|---|
| `selectedItem()` / `selectedItems()` | 详情页当前那一项 / 页面选中项快照（**v40 起版本页也有**：这一行对应的版本条目；resources 页仍为 null） |
| `pageItems()` | 当前页面看到的数据快照（**v40 起 version / resources 页各给"这一份"那一条**） |
| `slots()` | 全部槽 |
| `contents(kind)` / `contents(slot, kind)` | 四类内容；`kind` = `saves`/`maps`/`mods`/`blueprints` |
| `logs()` / `trash()` / `versions()` | 日志与崩溃报告 / 中转站 / 已装版本（v39 起 `trash()` 的 `extra["category"]` 覆盖全部八类 —— 含以前整块漏掉的 `versions` / `patches`；另有 `slot` / `originalName` / `stamp` / `files` 键） |
| `open(item)` | 打开条目的字节流（文件或容器条目统一） |
| `modsSkipped()` / `gameRunning()` | 这一轮会不会加载模组 / **有没有任意**游戏在跑（⚠️ 全局判据；判"某个槽"用 v37 的下面两个） |
| `gameRunning(slot)`（v37） | **这个槽**有没有游戏实例在跑（null / 空 = 当前槽）；判据与宿主写门禁同一份，只表示调用那一刻的快照 |
| `runningSlots()`（v37） | 现在哪些槽上有实例在跑（快照；按启动顺序去重；空表 = 没有） |
| `baseBundleKeys()`（v28） | 目标版本**基础语言包**的键集合（判"是不是原版文案"用；空集 = 读不到，不是"没有"） |
| `plugins()`（v38） | 装着哪些插件（含停用 / 待生效 / 已隔离）—— id / 名字 / 状态 / 来源 / 版本 / 自述字段 / 声明的能力条目，见下文「插件与补丁清单」 |
| `patches()` / `patches(versionKey, slot)`（v38） | 已导入的游戏补丁；给了启动项才判 `enabled` / `applies`，见下文 |
| `instances()`（v39） | **正在跑**的游戏实例（含启动占位）—— `pid` / `vkey` / `slot` / `instanceKey` / `started` / `starting`；顺序 = 启动时间升序。⚠️ 读失败也返回空表（没有失败码）：空表只说明"这份快照里没有"，别当"一定没在跑"的硬证据 |
| `backups(slot)`（v39） | 某个槽的**备份快照**元信息（`created` / `label` / `source` / `files` / `bytes` / `legacy`）；`slot` 空 = 这一页的槽；恢复 / 删除不给插件做。⚠️ 清单读不出来也返回空表（同上） |
| `usageDir()` | 插件数据目录（见第 9 节） |
| `crashReportsDir()`（v22） | 当前槽的崩溃报告目录（只读；可能不存在） |

#### v39 给已有清单补的 `extra` 键（加键不抬版本）

| 方法 | 新键 |
|---|---|
| `versions()` | `instanceKey`（= 宿主实例键 `版本key + NUL + 槽`，**别自己拼**）/ `bound`（`"true"` = 附加绑定行，不是主行）/ `patchCount`（这个启动项启用的补丁数；补丁库读不出来就**没有这个键**）/ `backupCount`（这个槽的备份份数；备份目录读不出来就**没有这个键**） |
| `plugins()` | `updateState`（`ok` / `available` / `installed` / `confirm` / `failed`）/ `updateVersion` / `updateNote`（后两个可能是空串 = 更新记录里没写；**记录不存在 ⇒ 三个键都不出现**） |
| `settings()` | `plugin_auto_update` / `crash_alert` / `trash_keep` / `trash_keep_slots` / `update_mode` / `update_mirror`（都是"设置页里能被改的项"；`update_mirror` 空串 = 自动选线路） |

### 网络与下载（v39）

| 方法 | 说明 |
|---|---|
| `fetchText(url, cb)` | 拉一段文本（UTF-8）。**只放行 https**（`http://127.0.0.1` / `localhost` 给本机调试）；正文上限 **512 KB**；连接 15 s / 读 60 s；跟随 302 |
| `githubReleases(repo, cb)` | 列 `owner/name` 的 release（最新在前，最多 30 条）：`tag` / `prerelease` / `publishedAt` / `assets`（`name` / `size`（没给 = -1）/ `url`，`url` 可直接喂 `download`） |
| `download(url, expectedSha256, cb)`（返回 `Task`） | 下载到**本插件 cache** 的 `downloads/<一次性子目录>/<URL 文件名>`（每个 Task 一个子目录：并发下载同名文件不会互相删/覆盖）；先写 `.part`，sha256 过了才改名；**大小由"边下边查 1 GB 上限"兜底**（下载前不知道长度时不预判）；GitHub 地址自动套用户选的**更新线路**（镜像优先、直连兜底）；进度显示在宿主前台服务通知里（**别自己再发**） |

规则与边界：

- **GitHub API 配额是宿主与插件共用的**（未登录 60 次/小时）⇒ 宿主做了**同一 URL 10 分钟缓存**
  + **每小时 40 次**上限（超了 `rate-limited`）。**不要轮询**：插件打光配额会连带把启动器自更新搞停。
- 失败码（`Result.reason`）：`bad-url` / `network` / `size` / `sha` / `rate-limited` / `bad-data` /
  `invalid`（sha256 不是 64 位十六进制）/ `io` / `canceled`。文案由插件自己出。
- **不校验 sha256 = 信任中间人**：`expectedSha256` 能给就给（空串 / null = 不校验，回调里回实际值）。
- 落点只能在插件自己的 `cache/downloads/` 下；文件名由宿主清洗（只留 `A-Za-z0-9._-`，≤ 80），
  每次下载一个**独立子目录**（并发安全）。
- 下载任务可 `Task.cancel()`；取消后回调 `canceled`，通知栏会收掉进度（正在阻塞的 read 可能要
  等到读超时才走到回调，但**结果一定不会当成成功**）。
- ⚠️ **GitHub API 的下载地址（`assets[].url`）也计入 40 次/小时配额**：`download` 与 `fetchText`
  共用同一份记账，用下载绕配额是绕不过去的。

### 补丁写（v39）

**定案：补丁可以碰（它只是一个更强的模组），插件启停仍归宿主。**

| 方法 | 说明 |
|---|---|
| `patchEnable(versionKey, slot, ref, cb)` | 启用。`slot` 空 = 这一页的槽；`ref` = `patches()` 的 `extra["ref"]`（`id@version`） |
| `patchDisable(versionKey, slot, ref, cb)` | 停用（幂等：本来没启用 ⇒ 成功） |
| `patchImport(InputStream, cb)` | 导入补丁包（zip 流，**宿主关闭**）；成功回调给新 `ref`。完整校验沿用补丁机制（清单 / dex / 素材 / 体积 / 重复） |
| `patchDelete(ref, cb)` | 删补丁 = **挪进中转站**（可由宿主界面放回）；被启用着 ⇒ `in_use` |
| `patchConflicts(versionKey, slot, ref, cb)` | 启用前**预检**：回调里给冲突的已启用补丁显示名列表（空表 = 没冲突）；已启用 ⇒ `enabled`（不是"跟自己冲突"） |

- 宿主做全部门禁：该启动项实例在跑 ⇒ `game_alive`（**与旧写入口的 `game-running` 是同一件事**，
  补丁接口用前者）；文件缺失 ⇒ `missing`；不匹配这个 APK ⇒ `no_match`；已启用 ⇒ `enabled`；
  冲突 ⇒ `conflict`；`(版本key, 槽)` 不是存在的启动项 ⇒ `not-found`；补丁库 / 版本信息**读不出来**
  ⇒ `io`（不折成 `not-found` / `record_gone` / `no_match`）。
- 其余失败码沿用补丁机制：`bad_zip` / `bad_json` / `schema` / `field` / `no_targets` / `bad_hash` /
  `bad_path` / `missing_entry` / `bad_dex` / `bad_assets`（只在导入时出现）/ `dup` / `too_big` /
  `io` / `record_gone` / `invalid`。
- **启用只写记录，不碰正在跑的进程**：真正生效在下次启动；同一补丁在两个槽上各开各的（第 353 轮）。
- 插件自己负责"动手前问用户"（想和宿主风格一致就用宿主的 `confirm` / `choose`）。
- 这些方法都是异步：回调在 UI 线程；`patchImport` / `patchConflicts` 会读 dex，**别在主线程等结果**。

### 目录

`dataDir()` `slotDir()` `savesDir()` `modsDir()` `mapsDir()` `blueprintsDir()`

`dataDir()` 是**你自己的**可写目录（宿主保证存在）。

### 交互

| 方法 | 说明 |
|---|---|
| `toast(text)` / `log(text)` | 提示 / 调试日志（logcat tag `MDTPlugin`） |
| `choose(title, options, cb)` | 宿主原生多选一弹窗 |
| `confirm(title, msg, okText, cb)` / `prompt(...)` | 是/否 / 输入弹窗 |
| `pickContent(kind, cb)` | 用宿主那套勾选框选内容（**不是**选任意文件）；标题 / 说明用宿主默认那两句 |
| `pickContent(kind, title, head, cb)` | **v32**：同上，但那两句话由你给（null / 空 ⇒ 回落宿主默认；标题 ≤ 24 字、说明 ≤ 80 字，超长会截断，换行会压成一个空格） |
| `pickFile(mime, cb)` | 系统选择器挑任意文件，宿主复制到你的 `cache/` 再给 `(name, File)` |
| `progressCard(title, msg, done, total[, cancelable, cb])` / `progressCardClose()` | 宿主进度框（4 参 = 不可取消） |
| `showCard(title, body, buttons, cb)`（v40） | 宿主卡片弹一段信息 + 最多 3 个按钮；**回调只来一次**（点了给下标，返回/点外面给 -1） |
| `openPage(page, slot)`（v40） | 跳到宿主页面（只开放不需要额外上下文的页，见下文「插入能力」） |

### 插入能力（v40）

**目标：插件做的事和宿主做的事看起来是同一套界面**（用户 2026-11-13：「我们尽可能让用户装了插件以后
是无感的，而不是明显插件和宿主的割裂感」）。三件东西：

1. **`place: "actions"`**（`plugin.json`，不是接口）：把 `action` / `content` 条目插进宿主操作卡。
2. **`showCard`**：插件要弹信息时用宿主卡片，别自己 `new AlertDialog`（叠窗 + 主题不一致）。
   - `buttons` 最多画 3 个：数组里的空串 / null **跳过**（所以"前 3 个"指前 3 个**非空**的按钮，
  回调给的仍是它在**原数组里的下标**）；全是空串 / null ⇒ 只给一个「关闭」。
- 点「关闭」/ 返回 / 点卡片外面 ⇒ `onCard(-1)`。同一页已经开着一张插件卡片时，新卡片会先收掉旧的
  （同窗只留一张，别叠两个对话框）。
   - 按钮文案要短（两字的动作词），依据放 `body` —— Android 按钮一长就竖排。
   - 回调在 UI 线程；用户点按钮 ⇒ `onCard(按钮在入参数组里的下标)`；返回 / 点卡片外面 ⇒ `onCard(-1)`。
3. **`openPage(page, slot)`**：认得的 `page`：
   `main` / `slots` / `slot` / `saves` / `maps` / `mods` / `blueprints` / `settings` / `logs` /
   `trash` / `plugins`。`slot` 只对 `slot` / 四个内容页有效（空 = 当前槽）；其余页面忽略。
   - 认不出的名字 / 版本页 / 补丁页 ⇒ **什么也不做**（不崩、不弹窗）—— 那两页要「哪一份」的版本 key，
     本接口不带。插件自己的页面里要展示同类信息，用 `patches()` / `versions()` 数据自己画。

**边界（有意的）**：
- 不给插件"换宿主主题 / 改宿主布局"的能力 —— 那是割裂的另一种写法。
- `api.ui()`（宿主组件工具箱）/ 摘要卡与详情页的 `place` 锚点**还没做**，见 `docs/plans/` 的批次表。

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
- `ProbeEnv`：`dir()`（本会话给这个插件的目录）/ `pid()` / `inProcess()` / `slot()` /
  **`version()`**（v30：这一局的版本 key）/ **`dataDir()`**（v30：插件自己的数据目录，与
  `PluginApi.dataDir()` 同一个 —— 要写跨会话账本就写这里）/ **`versionInfo()`**（v31：完整版本信息 map，
  见下）/ `displayVersion()`（v31 便利方法）
- **数据由插件自己写**（格式自定）；宿主不解析账本、不聚合。唯一的展示口是下面那条
  **显式声明**的 `playtimeTotal`（主界面摘要卡一行）。
- **v29 新增**：`Probe.onForeground(boolean)` —— 游戏 activity 进/出台时回调（只对 `inProcess`；默认空实现）。
  宿主只报事件，记不记、怎么记由插件自己定（`usagestats` 就是拿它算前台时长）
- 调度：每 `sec` 秒一次（1..3600，缺省 5）；`sample()` 在后台线程，单次 >200 ms 记失败；
  连续 3 次失败 ⇒ 停用该探针并写 `probe-fail.txt`
- 目录：`<hub>/usage/telemetry/<会话>/<插件 id>/`；跨会话的写 `dataDir()`
- ⚠️ 探针 APK 必须住在**内部存储且只读**（Android 14+ 拒绝加载可写 dex）——宿主按这条存插件
- `summary()` 只在进程正常收尾时调用（进程被杀不会）
- ⚠️ **第三方 + `inProcess`**：在插件页启用时会弹一次风险告知（官方 / 纯隔离进程不弹）
- 崩溃弹窗**和日志页的崩溃报告**都会加一句「本次会话有插件参与：…」（标官方/第三方、进程内/隔离进程）；
  报告页按**这份报告的时间**挑会话（12 小时内才算，昨天的崩溃不贴今天的探针）

#### v30：`ProbeEnv.version()` / `dataDir()`

```java
env.version();   // 已装 = 包名；导入 = "import:<文件名>"；拿不到 = ""
env.dataDir();   // <插件>/data（懒创建）
```

- `version()` 与 `PluginApi.versions()` 里 `ItemInfo.extra["key"]` **同一个值**（唯一来源
  `Versions.Entry.key()`）⇒ 探针按它分账、页面就能把数字挂到版本行上。
- `dataDir()` 让探针能写**跨会话**的累计（账本 / 总数）。同一份文件的多方写入由**插件自己**
  保证原子性：宿主既不知道格式，也不参与合并。

#### v31：`ProbeEnv.versionInfo()` —— 完整版本信息

```java
Map<String,String> info = env.versionInfo();
info.get("key");             // 同 version()：已装 = 包名；导入 = "import:<文件名>"
info.get("title");           // 显示名（含自定义名 / 类型 / 版本号）
info.get("versionName");     // 原始 versionName（数据层保真，如 8-official-2026.09.X37）
info.get("displayVersion");  // 人读版本号（如 2026.09.X37 / 159.7）
info.get("upstreamVersion"); // 上游版本号（fork 才有；官方可能没有这个键）
info.get("label");           // 游戏名（如 MindustryX）
info.get("pkg");             // 包名
info.get("realPkg");         // 导入包的真实包名
info.get("imported");        // "true" / "false"
info.get("importFile");      // 导入文件名
info.get("md5");             // 导入包 md5（已装通常为空）
info.get("customName");      // 用户给**这一项**（启动项）起的名字（没改过 = 空）
info.get("slot");            // 这一局的槽
```

- **为什么**：已安装的游戏会**原地更新**（包名不变、版本号变）。只记 `version()` 的话，
  更新前后的时间会混进同一行，而且旧行会被新版本号覆盖。⇒ 插件应把 **key 当身份、
  版本号 + `title` / `versionName` / `upstreamVersion` 当快照**一起记进自己的账本
  （`plugins/usagestats/` 就是这么做的：行身份 = 槽 + key + displayVersion）。
- 宿主以后可能**继续加键**（方法签名不变）；插件按"缺键 = 空"处理，别假设某个键一定在。
- `displayVersion()` 是 `versionInfo().get("displayVersion")` 的便利写法。

#### v30：`playtimeTotal` —— 主界面摘要卡那一行

`plugin.json` 顶层**显式声明**一个简单文件名：

```json
"playtimeTotal": "total.txt"
```

- 宿主读 `<插件>/data/total.txt`，**只认一个整数毫秒总数**（≤ 13 位、0 < ms ≤ 100 年），
  用它排版主界面摘要卡那行「游玩时长：…」；措辞归宿主（与存档行同一个时长资源）。
- 文件不存在 / 坏值 / 没声明 ⇒ **整行不显示**（不留「游玩时长：」空壳）。
- 为什么不给宿主一份账本：这一行在 `MainActivity.rescan()` 的 UI 线程上读，必须 O(1)；
  账本格式归插件，宿主一解析就会跟插件漂。**多台插件都声明时只认第一台**（没有"加起来"的语义）。
- 参考实现：`plugins/usagestats/`（探针把账本与 `total.txt` 一起原子写，页面读账本按槽/版本分列）。

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

### 插件与补丁清单（v38）

> 2026-11-13 第 372 轮（用户：「先给插件加个可以读取当前所有插件及其基本信息的接口，还有读取游戏补丁的接口」）。
> 目的：插件能**互相发现**、能看见"这台机器装了哪些插件 / 导入了哪些游戏补丁"，不用反射或读文件
> （那些不是契约，宿主一改就断）。

```java
List<ItemInfo> plugins();                                  // 全部插件（含停用 / 待生效 / 已隔离）
List<ItemInfo> patches();                                  // 全部已导入补丁（不判启用状态）
List<ItemInfo> patches(String versionKey, String slot);    // 同上 + enabled / applies（按某个启动项）
```

两者都是 **`ItemInfo` 快照**（与 `versions()` / `logs()` / `trash()` 同一套；**不可 `open`**，
`path` 为 null、`readable` 为 false —— 要的是元信息，不是字节）。事实全在 `extra` 里，
**表里没有的键 = 拿不到**（别当空 / 当 false）。

`plugins()`：`kind == ItemInfo.KIND_PLUGIN`（`"plugin"`），顺序 = 插件 id 字典序。

| `extra` 键 | 说明 |
|---|---|
| `id` / `name` | 插件 id（安装目录名）/ 显示名（自述 name，缺 = id） |
| `state` | `active`（这一程生效中）/ `pending`（待重启生效）/ `off`（没启用） |
| `version` | **这一行代表的那一份**：待生效 > 生效 > 已装版本里**文件名字典序最后一份**（⚠️ 令牌是内容哈希 / 时间戳，字典序**不代表新旧**）；只剩隔离件时没有这个键 |
| `activeVersion` / `pendingVersion` | 生效中 / 待重启生效的版本令牌；没有就不放这个键 |
| `builtin` / `trust` / `level` | 是否内置（随本体发的那份）/ `builtin`·`official`·`third` / 安全阀等级 `L1`·`L2`·`L3` |
| `quarantined` | 手上还有被隔离的那一份（`off` 时界面上显示"已隔离"） |
| `selfVersion` | 自述里的 `version`（作者自己写的版本号，**不是**上面那个令牌；自述没写就不放） |
| `author` / `description` / `entry` | 自述字段 / 入口类名（注入点）；自述里没有的那项**不放这个键** |
| `access` | 自述里声明的用途（逗号分隔；**没声明 = 空串**） |
| `extCount` + `ext.<i>.id/title/subtitle/slot/page/event` | 声明的能力条目（`extensions`；与插件页那几行同一个来源） |
| `size` / `modified` | 这一份 APK 的字节数 / 最后修改时间（毫秒）；没有 APK 时为 0 |

> ⚠️ `access` / `extCount` 分不出"没声明"和"自述读不出来"（两者都是空串 / 0 条）——
> 宿主没有为这个差别开键；要知道插件"坏没坏"，看 `trust` / 能不能加载它，别拿这两个判断。

- `ItemInfo` 的固定字段：`kind = KIND_PLUGIN`、`title = 显示名`、`subtitle = id`、`source = "plugins"`、
  `path = null`、`readable = false`；
- ⚠️ 没有 APK 的条目（只剩隔离件 / 登记指向的文件被删）**没有** `entry` / `selfVersion` / `author` /
  `description` / `access` / `extCount` / `ext.*` 这些键 —— "没读到"和"声明为空"是两件事，别混；
  `version` / `activeVersion` / `pendingVersion` 只看"登记表 + 自己装过哪些版本"，与 APK 在不在无关
  （只剩隔离件时 `version` 没有，`activeVersion` / `pendingVersion` 按登记照给）；
- ⚠️ `name` / `author` 这些是**插件自己写的**外部数据，别直接当可信 UI 文案（照常做长度/清洗）；
- ⚠️ 会读每个插件的 APK（自述 / 签名指纹）⇒ **放后台线程**；
- 想"只列当前生效的"就自己按 `state == "active"` 过滤 —— 宿主给的是**全部**（这正是"所有插件"）。

`patches()`：`kind == ItemInfo.KIND_PATCH`（`"patch"`），顺序 = 补丁 id、版本字典序。
补丁只对**某份 APK**（按 sha256）生效 ⇒ "适用不适用"是相对某个版本说的，那一份上下文由
`patches(versionKey, slot)` 的 `versionKey`（`versions()` 里 `extra["key"]`）给出；
`slot` 空 = 当前槽（`slot()`，即**这一页**的槽 —— 内容页 / 槽页传的就是它们自己那个槽）。

| `extra` 键 | 说明 |
|---|---|
| `id` / `version` / `ref` | 补丁 id / 补丁版本 / `id@version`（启用表里存的就是它） |
| `name` / `author` / `note` / `tool` | 清单里的显示名 / 作者 / 说明 / 生成工具（**空串 = 清单没写**，键本身总在） |
| `filesOk` | dex / 素材文件齐全吗（缺了仍列出来，让插件能显示"坏了"） |
| `bytes` | dex + 素材的实际字节数 |
| `targetCount` + `target.<i>.*` | 每个 target：`sha256`（目标 APK 哈希）、`gameVersion`（空串 = 清单没写）、`classes`（dex 里的类数）、`assets`（素材条目数）、`dexBytes` / `assetsBytes`、`match`（见下） |
| `enabled` | **只在给了 `versionKey` 时出现**：这个启动项现在启用了它吗（读启用记录；版本装没装不影响这一条） |
| `applies` | **只在给了 `versionKey` 且那个版本的 APK 读得到时出现**：某个 target 匹配这个版本吗；读不到 ⇒ 不放（别当 false） |

- ⚠️ **`patches()` 也不便宜**：每条记录都会读每个 target 的 dex 头（校验 + 数类）与素材 zip 目录
  ⇒ **放后台线程**；
- `ItemInfo` 的固定字段：`kind = KIND_PATCH`、`title = 补丁名`、`subtitle = ref`、`size = bytes`、
  `modified = 导入时间`（清单文件的 mtime）、`source = "patches"`、`path = null`、`readable = false`；
- `note` 是**唯一允许换行**的字段（控制字符在解析处已换成空格，换行保留）；
- 文件缺了的记录**仍会列出来**：`extra["filesOk"] = "false"`、`ItemInfo.error = "missing-files"`；
- `target.i.match` 也只在 APK 哈希算得出来时出现（`true` / `false`）；
- `classes` / `assets` 读不出来时**不放这个键**（不是 0）；target 没声明素材包时 `assets = 0` 是事实；
- ⚠️ `patches(versionKey, slot)` 会算一次版本 APK 的 sha256（几十 MB）⇒ **放后台线程**；
  缓存时注意：哈希 / `applies` 可缓存（同版本 APK 不变），**`enabled` 是活配置、每次现读**；
- 补丁的启用状态**按启动项存**（版本 key + 槽，第 353 轮）⇒ 同一个补丁在两个槽上可以一开一关；
- 补丁能不能用是**精确到 APK 哈希**的（见 `PatchManifest`）；用 `applies` 判断，别自己比版本号。

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
- `page` 可以是**四个列表页**：`mods` / `saves` / `maps` / `blueprints`（第 280 轮起统一；
  在那之前只有 `mods` 真正接了线，别的页写了声明也不会出现）。
- 条目的**身份**（宿主把结果贴回列表用的键）：槽内文件 = `ItemInfo.path`；
  包内条目（游戏自带图 / 模组自带图在 APK、zip 里）= `容器绝对路径 + "!/" + 条目名`。
  ⚠️ **不要假设 `path` 非空**：那类条目 `path == null`，要读内容一律走 `api.open(item)`。
  插件只回答"要不要 / 谁在前"，不必自己算身份。

**v33：先问用户，再跑**（例如"按某矿含量排序"要先选矿）：

```java
public static String[] options(PluginApi api);        // 可选：给宿主一张选择卡（≤24 项、每项 ≤24 字）
public static void choose(int index, PluginApi api);  // 可选：用户点了第几项（**同一份已加载实例上**）
```

- 两个都实现才生效（只给 `options` 不给 `choose`，宿主不弹卡 —— 选了也传不回去）。
- 顺序：加载插件 → `options()` → 宿主显示 `CardStack` 列表（标题 = 这条扩展的 `title`）→
  用户点了 → `choose(i)` → 再跑 `accept` / `compare`。**插件用静态字段记住选择**。
- 用户取消 / 返回键 ⇒ 宿主关掉插件会话，`accept`/`compare` **一次都不跑**，页面不动。
- 标签过闸门：去空白、按码点截 24 字、最多 24 项（防插件给 500 条或一行一万字撑爆卡片）。

#### 先选一项：`options()` + `choose(int)`（v33）；勾多项：`multi()` + `choose(int[])`（v36）

```java
public static String[] options(PluginApi api);          // 二级列表的标签（≤24 条，每条 ≤24 字）
public static void choose(int index, PluginApi api);    // v33：单选时回传

public static boolean multi(PluginApi api);             // v36：这一项允许勾多个 ⇒ 返回 true
public static void choose(int[] index, PluginApi api);  // v36：多选时回传（升序、去重）
```

- 宿主先显示 `options()`，用户点完再调 `choose(...)`，**同一个已加载实例**上继续跑 `accept` / `compare`
  （插件把选择记进静态字段）。
- **v36 多选**：声明 `multi()` 返回 true **且**有 `choose(int[])` ⇒ 宿主把那一屏换成**勾选框列表**
  （与界面里「来源 / 类型」同一套控件），勾几个就回传几个；**几个选项之间是"或"还是"且"由插件自己定**
  （mapstats 的「只看含某矿的图…」是"或"：含钨**或**含钍）。
- 多选那屏至少勾一个（取消 = 什么都不做）；单选那屏点一下就走。

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

> **多实例语义（2026-11-12 起）**：`GameSlot` 会把自己登记成一个**运行实例**（版本 + 槽）。
> 写类方法的 `game-running` 判据是「**目标槽**有实例在跑」，不是"任意游戏在跑" ——
> 别的槽的游戏不影响你写这个槽。读类方法一律放行。
> ⚠️ **File 出口不受写门禁保护**：`slotDir()`/`savesDir()`/… 与 `ItemInfo.path` 给的路径
> 可以直接写（本工程"无沙箱"是既定边界，见 `PluginApi` 头注释）。写门禁只拦
> `writeItem`/`addItem`/`writeGameSettings`/`trashItem` 这四个 API。

| 方法 | 说明 |
|---|---|
| `writeItem(item, stream, cb)` | 换掉**任意槽**四类目录里**已存在**文件的字节（v34 起跨槽；原子替换；**该文件所在槽有实例在跑时**拒绝；**无备份**） |
| `addItem(slot, kind, name, stream, cb)` | **v35 新建**存档 / 地图 / 模组 / 蓝图；不覆盖同名；只校验槽名 / 后缀 / 原子写，**内容归插件** |
| `trashItem(item, cb)` | 挪进中转站（不是真删，规则与界面删除同一套；目标槽有实例在跑 ⇒ `game-running`） |
| `openItem(item)` | 跳详情页（地图/存档 → 地图详情；蓝图 → 蓝图详情；模组 → 模组页） |

**v35 `addItem` 的边界**：
- `kind` = `saves` / `maps` / `mods` / `blueprints`；分别落 `saves/` / `maps/` / `mods/` / `schematics/`。
- `name` 必须是**完整裸文件名**：`.msav`（saves / maps）、`.jar` / `.zip`（mods）、`.msch`（blueprints）；
  路径分隔符 / 控制字符 / 隐藏名 / `.` / `..` 一律 `invalid`。
- 同名已存在 ⇒ `exists`（要替换用 `writeItem`）；**目标槽有实例在跑** ⇒ `game-running`；槽不存在 ⇒ `not-found`。
- **宿主不做内容校验 / 不回滚**：写进去的 `.msav` 是否合法、mod zip 有没有 `mod.hjson`、
  `.msch` 能不能解析，全是插件责任；新文件没有旧版本可还原。

### 游戏设置（v21）

`settings.bin` 的**解析结果**与**安全写**：键的含义归你，格式与安全归宿主。

```java
Map<String, Object> gameSettings();                               // 当前槽（v21）
Map<String, Object> gameSettings(String slot);                    // 任意槽（v34；null/空 = 当前槽）
void writeGameSettings(Map<String, Object> changes, Done done);   // 当前槽（v21）
void writeGameSettings(String slot, Map<String, Object> changes, Done done); // 任意槽（v34）
```

- 值域 = 文件里的六种：`Boolean` / `Integer` / `Long` / `Float` / `String` / `byte[]`。
- `gameSettings(slot)`：槽不存在 / 名字非法 ⇒ **`null`**；文件不存在 ⇒ **空表**；
  读不出来 ⇒ **`null`**（别当"空设置"处理）。
  真实文件 5900+ 键 / ~500 KB ⇒ **别在主线程调**。
- `writeGameSettings(slot, …)`：后台线程读写，`Done.onDone(Result)` 回主线程；
  失败 reason = `not-found` / `game-running` / `unreadable` / `invalid` / `io`。
- **宿主保证的机制**（不是语义）：**目标槽有实例在跑** ⇒ 拒绝 · 写前备份到
  `<hub>/settings-backups/slot-<槽>/`（留 20 份）· 原子写 · 写后读回自检、不过自动还原 ·
  未知键与键顺序原样保留。备份落点是**目标槽**，不是当前槽。
- **插件负责的语义**：键名含义、缺省值、成对写、目标槽选择。例如 `mod-<名>-enabled` **不存在 = 启用**；
  改 `-enabled` 时要同时清 `-failed`，否则留下永远清不掉的陈旧记录。
- 写是**合并**：没给的键一个都不动；删键用 `changes.put(key, null)`。
- **v34 起跨槽是公开能力**：插件可以读源槽、写目标槽，自己实现"复制 / 批量改 / 三档合并"；
  宿主只保证路径合法、写安全与备份，不替插件判断"这个键该不该搬"。

### 页面

`closePage()`：关掉插件页（宿主负责返回导航）。

## 5. 线程与生命周期约定

- `createView` / `run` 在 UI 线程；**不要**在里面读大文件。
- `runBackground(label, body)` 的 `body` 在后台线程；**别碰 View**，要更新 UI 用 View 自己的 `post`。
- 回调（`Choose` / `Confirm` / `Prompt` / `PickFile` / `PickContent` / `After` / `Done` / `Cancel`，
  v39 起还有 `Fetch` / `Releases` / `Download` / `Imported` / `Conflicts`，v40 的 `Card`）
  **都在 UI 线程**回调（`showCard` 没有 Activity 时也会把 `onCard(-1)` 投回 UI 线程）。
- `export` / `share` / `writeItem` / `patchImport` 传进去的 `InputStream` **由宿主关闭**；
  `pickFile` / `download` 给你的是文件，自己读。
- v39 的网络 / 补丁写接口都是**异步**的：`fetchText` / `githubReleases` / 五个补丁写方法在后台线程跑、
  回调回 UI 线程；`download` 额外返回 `Task`（可取消，通知栏有进度）。
- v40 的 `showCard` / `openPage` **不返回 `Result`**：`showCard` 永远给一次 `onCard`（没有界面 ⇒ -1），
  `openPage` 认不出的页面**静默跳过**（不回调、不弹窗、不崩）。要判断"能不能去这一页"请按文档白名单。

## 6. 失败语义：`Result`

写 / 保存 / 挪这一层统一回调 `Result`（`io.mdt.launcher.api.Result`）：

```java
public final boolean ok;     // 成功？
public final String reason;  // 机器码；成功恒为 null，失败恒非空
```

| `reason` | 含义 |
|---|---|
| `game-running` | **目标槽**有游戏实例在跑，写操作被拒（先退出那个游戏） |
| `not-in-slot` | 条目不在槽内那四类目录里 |
| `not-found` | 条目不存在 / 没有路径 |
| `canceled` | 用户取消（**不是错误**，别弹红字） |
| `busy` | 同一时刻只允许一份（保存 / 分享排队） |
| `no-page` | 当下没有可用的界面 |
| `io` | 读写失败 |
| `invalid` | 参数不合法（null / 类型不对） |
| `unknown` | 宿主没给具体原因（兜底） |

**v39 网络**（`fetchText` / `githubReleases` / `download`）：

| `reason` | 含义 |
|---|---|
| `bad-url` | 不是 https（本机回环除外）/ 空 / 超长 / repo 形状不对 |
| `network` | 连不上 / 非 200 / 超时 |
| `size` | 正文或下载超过上限（512 KB / 1 GB） |
| `sha` | 下载内容的 sha256 与 `expectedSha256` 不符 |
| `rate-limited` | GitHub API 配额用尽（宿主缓存 + 每小时上限；别轮询） |
| `bad-data` | 返回的不是预期格式（镜像错误页 / 限流对象等） |

**v39 补丁写**（`patchEnable` / `patchDisable` / `patchImport` / `patchDelete` / `patchConflicts`）：
`not-found`（启动项不存在）/ `invalid`（参数空）/ `no_match`（补丁没有匹配这个 APK 的 target）/
`conflict`（与已启用的冲突）/ `missing`（补丁文件缺失）/ `enabled`（已启用）/ `in_use`（删除时还被启用着）/
`game_alive`（该实例在跑；**与旧写入口的 `game-running` 是同一件事**）/ `record_gone`（记录不存在）/
`bad_zip` / `bad_json` / `schema` / `field` / `no_targets` / `bad_hash` / `bad_path` / `missing_entry` /
`bad_dex` / `bad_assets`（只在导入时）/ `dup` / `too_big` / `io`（含"补丁库 / 版本信息读不出来"）。

⚠️ `reason` 是**给代码看的**，别直接上屏 —— 文案由插件自己出（每个插件有自己的语言包）。

## 7. 边界（先看这几条再动手）

- **没有沙箱**：能读宿主能读的、能写你声明的地方；靠"约定 + 告知"，不是权限门。
- `writeItem` 只允许改**槽内四类目录里已存在的文件**（canonical 前缀校验），不能新建、不能写到别处。
- 插件的数据住在 `<插件>/data`（`dataDir()`），缓存住 `<插件>/cache`（`pickFile` / `download` 落点）。
- **网络走宿主这条路，不自己开 socket**（不是不让你开，是不让每个插件各踩一遍 302 / 镜像 / 半截文件）：
  https-only、体积上限、镜像只对 GitHub、GitHub API 由宿主统一缓存与限频。
- **补丁可以写，插件不能停用自己/别的插件**：补丁写接口复用补丁机制的全部门禁与原子写；
  插件启停是宿主的安全锚（信任模型 + 安全阀），永远只归宿主界面。
- 插件只负责"什么时候让用户点"；`patchEnable` 只写启用记录，**不会**碰正在跑的进程（下次启动才生效）。
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

**这个目录归插件**：宿主只给目录，**不写任何会话流水、不聚合**；旧实现（v18~v28 期间宿主写的
`launch.tsv` / `run.tsv`）已删，**现在不再产生**（2026-10-26 定案）。

- 探针自己的样本写 `ProbeEnv.dir()`（`<hub>/usage/telemetry/<会话>/<插件 id>/`，见第 8 节）；
  跨会话的账本写 `ProbeEnv.dataDir()`（= `<插件>/data`）；
- `usageDir()` 留给"跨会话、跨插件"的数据，格式插件自定，宿主不解析；
- ⚠️ **唯一的例外**是 `plugin.json` 里显式声明的 `playtimeTotal`（v30，见第 8 节）：宿主只读
  那**一个整数**并排版进主界面摘要卡，不读账本、不按槽/版本算；插件不声明就一行都不出现。

## 10. 插件自动更新（第 338 轮，2026-10-30）

> **地址由插件自己提供**（用户 2026-10-30 定：「由插件负责提供对应的更新地址」）——
> 宿主不内置插件名单、不做商店；谁发的插件，谁告诉宿主去哪儿看新版。

### 10.1 插件这边要做两件事

1. 在 `assets/plugin.json` 里加一行**更新清单地址**（可空；空 = 不参与自动更新）：

   ```json
   "update": "https://example.com/mdt/myplugin.json"
   ```

   ⚠️ **只认 https**（回环 `http://127.0.0.1:…` 只给本地夹具）。明文 http 一律当没声明。

2. 在该地址放一份清单（**几百字节**）：

   ```json
   {
     "schema": 1,
     "plugin": "myplugin",
     "version": "1.2",
     "size": 61234,
     "sha256": "…64 位小写十六进制…",
     "url": "https://example.com/mdt/myplugin-1.2.apk",
     "notes": "这一版改了什么"
   }
   ```

| 字段 | 必需 | 说明 |
|---|---|---|
| `schema` | 否 | 缺省按 1；**别的值整份不认**（将来改格式，老宿主不能因此失去更新能力） |
| `sha256` | **是** | 远端 APK 的**内容哈希**；宿主的唯一比对依据 |
| `url` | **是** | APK 的下载地址（https / 回环） |
| `size` | 否 | 字节数；给了就边下边校（早失败），不给就下完按实际长度校 sha256 |
| `version` | 否 | **只用于显示**（"有新版 1.2"）。不参与任何比较 |
| `notes` | 否 | 一行说明（≤160 字符，换行会压成空格） |
| `plugin` | 否 | 清单自称的插件 id；**填了就必须和装的那个 id 一致**（防串包），不填不拦 |
| `minHostApi` | 否 | 宿主接口版本低于它就不提示（新插件需要新 API，装了也用不了） |

生成清单别手抄：

```bash
python tools/make-plugin-update.py --apk myplugin.apk --version 1.2 --out myplugin.json
python tools/make-plugin-update.py --check myplugin.json     # 发之前先验一遍
```

### 10.2 宿主怎么判"有新版"：**只比内容，不比版本号**

用户原话：「插件这东西鬼知道版本号咋写的」。所以：

```
本机那一份 APK 的 sha256  !=  清单里的 sha256   ⇒  有更新
```

- 不比 semver、不解析版本号、不依赖作者写没写 `version`；
- 作者**回滚**也能用：把清单指回旧包，那也是一次"更新"（内容不同）；
- 清单里的 `version` 只在界面上显示；没有就显示 sha 前 8 位。

### 10.3 宿主会做什么（自动化程度）

| 步骤 | 行为 |
|---|---|
| 何时查 | **启动时**静默查一次，24 h 内不重复；设置页「插件自动更新」关掉后**一次请求都不发** |
| 落后了怎么办 | 手动「检查插件更新」（插件页顶栏 ⋮）随时可查 |
| 下载 | 只在真发现新版时下；镜像/直连按「更新线路」那条设置走 |
| 校验 | ① 长度（有 `size` 时）② **sha256** ③ 至少是个能解析的 APK（有清单 + classes.dex） |
| 装不装 | **签名和当前那一份相同 ⇒ 自动装**（同一个作者的更新，不打扰）；**不同 ⇒ 不装**，插件页标「新版待确认」，用户点一下才装 |
| 生效 | 与手动安装完全一样：**装成"待生效"，退出重开才加载**（S0 硬事实：已加载的类换不掉） |
| 失败 | 只写日志与 `report-plugin-update.txt`，界面不弹窗（下次启动再试） |

### 10.4 三条给作者的红线

1. **清单地址要稳定**：宿主是拿**当前装着的那一份**的 `plugin.json` 去读地址的；
   地址变了要等新版装上之后才生效（第一版必须走手动安装）。
2. **URL 指向的必须真的是那个 APK**：sha256 对不上 ⇒ 这次更新放弃（不会装半截或不匹配的东西）。
3. **换签名 = 用户要确认一次**：如果你换了签名钥匙（或换了作者），自动更新不会静默生效 ——
   这是有意的（防"插件被换包"）。

### 10.5 内置插件也在线更新（官方三件套）

`mapstats` / `mods-health` / `usagestats` 三份内置插件同样在自己的 `plugin.json` 里声明了
`plugins-latest` 通道的清单地址。它们被在线升级之后：

- 下一次启动**不会被本体 `assets/plugins/` 里那份顶掉**（`Plugins.online.txt` 记着"在线版是
  在内置版 X 的基础上升的"；本体内容一变 ⇒ 以本体为准）；
- 仍然算"内置"（界面显示内置、不给删除入口、失败处置封顶 L2）。

发一次插件更新（**不是启动器发版**）：

```bash
bash plugins/mapstats/build.sh
python tools/make-plugin-update.py --apk "$TEMP/mdt-mapstats/mapstats.apk" --version 6 --out mapstats.json
gh release upload plugins-latest "$TEMP/mdt-mapstats/mapstats.apk" mapstats.json --clobber
```

> ⚠️ `plugins-latest` 是**滚动更新通道**，不是版本发布：它不在 `v*` 命名空间里，
> 不影响 `versionCode = v* tag 数 + 1`；**永远不要**往里传启动器 APK。

## 11. 历史

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
v29（2026-10-26 第 254 轮）：`Probe.onForeground(boolean)` —— 游戏 activity 进/出台事件（只对进程内探针）；
官方 `usagestats` 插件的时长统计靠它（宿主不写流水）；`API_MIN` 仍不动。
v30（2026-10-26 第 255 轮）：`ProbeEnv.version()`（这一局的版本 key）+ `ProbeEnv.dataDir()`（跨会话数据目录）+
`playtimeTotal`（自述里声明一个整数文件，宿主把它排版进主界面摘要卡）—— 游玩时长搬出插件页，
落到**槽页**（按版本分列）与**主界面摘要卡**（总时长）；`API_MIN` 仍不动（区间 20..30）。
v31（2026-10-26 第 256 轮）：`ProbeEnv.versionInfo()`（完整版本信息 map：title / versionName /
displayVersion / upstreamVersion / label / pkg / imported / md5 / …）+ `displayVersion()` 便利方法；
已安装游戏会原地更新，插件据此把"更新前后"分成两行；`API_MIN` 仍不动（区间 20..31）。
v32（2026-10-27 第 277 轮）：`pickContent(kind, title, head, cb)` —— 勾选那一屏的标题 / 说明由插件给。
宿主原来硬编码「选择条目 / 选中后交给插件」，用户看不出"选了之后会怎样"（用户原话：
「这个页面标题能不能不是『选中后交给插件』啊」⇒「改成插件可以自行设置文本」）。
null / 空串 ⇒ 回落宿主默认那两句；标题 ≤ 24 字、说明 ≤ 80 字，换行压成空格、按码点截断；
正按钮仍是宿主的「完成（N）」；`API_MIN` 仍不动（区间 20..32）。
v33（2026-10-27 第 282 轮）：`options(PluginApi)` + `choose(int, PluginApi)` —— 筛选 / 排序可先问用户
（例如"按某矿含量排序"先选矿）；`API_MIN` 仍不动（区间 20..33）。
v34（2026-10-28 第 290 轮）：`gameSettings(String slot)` / `writeGameSettings(String slot, …)` ——
跨槽设置读写；`writeItem` 也放开到**任意槽**四类目录里的已存在文件。宿主仍负责槽名合法性、
游戏运行门禁、备份 / 原子写 / 读回自检；`API_MIN` 仍不动（区间 20..34）。
v35（2026-10-28 第 291 轮）：`addItem(slot, kind, name, stream, done)` —— 新建存档 / 地图 / 模组 / 蓝图；
只做槽名 / 后缀 / 原子写，同名不覆盖，内容校验归插件；`API_MIN` 仍不动（区间 20..35）。
v36（2026-10-29 第 295 轮）：选项可以多选 —— `multi(PluginApi)` + `choose(int[], PluginApi)`；
`API_MIN` 仍不动（区间 20..36）。
v37（2026-11-12 第 363 轮）：多实例感知 —— `gameRunning(String slot)`（null / 空 = 当前槽）+
`runningSlots()`；老的 `gameRunning()` 保持全局语义（兼容，不改）。判据与宿主写门禁同一份
（第 359/362 轮的 `WriteGate` + `Instances`），只表示调用那一刻的快照；`API_MIN` 仍不动（区间 20..37）。
v38（2026-11-13 第 372 轮）：**插件与补丁清单** —— `plugins()`（装着哪些插件：id / 名字 / 状态 /
来源 / 版本 / 自述字段 / 声明的能力条目）+ `patches()` / `patches(versionKey, slot)`（已导入的
游戏补丁；给了启动项才判 `enabled` / `applies`）。两者沿用 `ItemInfo` 快照（新增
`KIND_PLUGIN` / `KIND_PATCH`），读不到的量**不放键**而不是给默认值；补丁匹配仍是目标 APK 的
sha256。`API_MIN` 仍不动（区间 20..38）。
v39（2026-11-13 第 373 轮）：**网络 / 补丁写 / 实例与备份**（用户：「我们甚至没有 GitHub 下载接口」
「不能碰插件启停，但补丁可以碰（这东西只是个更强的模组而已）」）——
`fetchText` / `githubReleases` / `download`：复用宿主更新基建（超时 / 302 / Accept / 镜像线路 /
`.part` + sha256 / 前台服务进度），https-only、正文 512 KB、下载 1 GB、GitHub API 同 URL 10 分钟
缓存 + 每小时 40 次（与自更新共用配额）；`patchEnable` / `patchDisable` / `patchImport` /
`patchDelete` / `patchConflicts`：复用补丁机制自己的门禁 / 清单校验 / 原子写 / 删除进中转站，
**插件启停仍归宿主**；`instances()`（含启动占位）与 `backups(slot)`（只读元信息）；
`versions()` / `plugins()` / `settings()` 各补一批 `extra` 键（加键不抬版本）。
顺带修了 `trash()` 漏 `versions-trash` / `patches-trash` 的老 bug（改走 `Trash.list`，
类别由 `Trash.Kind` 唯一映射）。`API_MIN` 仍不动（区间 20..39）。
v40（2026-11-13 第 374 轮）：**插入能力**（用户：「插件和宿主的 UI 非要分离开就很别扭」
「尽可能让用户装了插件以后是无感的」）—— `showCard(title, body, buttons, cb)`（宿主卡片，
最多 3 个按钮，回调只来一次）+ `openPage(page, slot)`（跳宿主页面；只开放不需要额外上下文的页）；
`plugin.json` 的条目多一个可选 `"place": "actions"`（把动作插进宿主操作卡，四个内容页有锚点，
别的页回落「插件」卡）；三个新页面钩子 `version` / `patches` / `resources`（布局里有
`plugin_section`，三类插槽都能挂）。`API_MIN` 仍不动（区间 20..40）。
**有意的边界**：不给插件"换宿主主题 / 改宿主布局"的能力；`api.ui()`（宿主组件工具箱）与
摘要卡 / 详情页的 `place` 锚点还没做（见 `docs/plans/` 的批次表）。

**官方体检插件**：`plugins/mods-health`（第 229 轮，前身叫 `conflict`）。宿主原来的那行
「查模组冲突」整块删掉，判据搬进插件的 `core/Conflicts.java` 与 `core/Gates.java`；
界面只剩宿主的 `analyzer` 卡、行徽标、筛选/排序注入与详情页的门。三个注意点（都在那个工程里踩过）：
- `analyzer` 是**逐项**回调，而 `Finding` 没有 item 字段 ⇒ 整槽结论只在「页面第一项」那一次给，
  否则同一份结论会在卡片里重复 N 遍（插件按 `contents(mods).get(0)` 判，**不是**按回调顺序）；
- analyzer 每次进页面都会跑 ⇒ 重活要按 `mods/` 签名缓存（那个插件按「名字:大小:mtime」缓存判据，
  日志摘录不缓存 —— 它每次启动游戏都会变）；
- 门的**判据与文案分家**：判据（哪道门过、用哪条文案 key、要什么参数）必须放在插件 core 里，
  入口类只做 `key → R.string` 映射 —— 否则 `build.sh check` 编不到它，宿主一删断言就一条尺子都没有。
