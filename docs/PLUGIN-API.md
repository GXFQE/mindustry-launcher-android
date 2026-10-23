# 插件接口（PluginApi）—— 给插件开发者看的那一份

> 面向写插件的人；宿主内部的实现细节看 `docs/flows/` 与 `.dsh/memory/ref/`。
> 文档里的版本号必须等于 `PluginHost.API`，自检有一条元断言钉着（`PluginApi.DOC_VERSION`）。

## 1. 版本与兼容（v20 冻结基线）

- 宿主接受**区间**：插件 `api()` 落在 `[API_MIN, API]` 就加载。当前是 **20..20**。
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

## 3. 入口类约定

```java
public static int api();                        // 必需：你需要的接口版本（当前 20）
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
| `hostApi()` | 宿主支持的接口版本（判断能力用；当前 20） |
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
| `usageDir()` | 会话记录目录（见第 9 节） |

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

`runBackground(label, body)` + `progress(done, total)`：后台线程跑活，宿主负责常驻通知与进度。

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

宿主在 **`:game` 进程**里记"游戏在前台"的事实（插件只在页面打开时活着，看不到这些），插件只读。

```java
java.io.File usageDir();   // <hub>/usage/
```

目录里**两条流分开**：

**`launch.tsv` —— 启动事实，写完不可变、永远完整**

| 行 | 含义 |
|---|---|
| `L <ts> <版本> <槽> <apk>` | 点"启动"那一刻的事实（宿主在起游戏之前写） |

**`run.tsv` —— 运行期事实，进程被杀就会断**

| 行 | 含义 |
|---|---|
| `S <ts>` | 会话开始（游戏 Activity resumed；槽/版本在 `launch.tsv`，按时间就近 join） |
| `B <ts>` | 心跳，每 30 秒一次 |
| `E <ts>` | 会话结束（paused） |
| `C <ts> <异常类>` | 崩溃（先写这条，再交回原处理器；不改变崩溃行为） |
| `SC off|on <ts>` | 熄屏 / 亮屏（让"前台时长"能区分熄屏挂着） |

读侧约定：
- 没有 `E` 的会话按 `min(now, lastB + 30s)` 收口 —— **只少不多**
- 单条会话上限 12 小时
- 「游戏前台时长」：含主菜单 / 暂停 / 地图编辑器；不含切到别的 App

真机产物：

```
launch.tsv: L 1792686560273 official-159 预览验收 /data/user/0/io.mdt.launcher/app_hub/import/official-159.apk
run.tsv:    S 1792686560665
            B 1792686565669  (30 秒一个)
            E 1792686572423
            SC off 1792686882692
            SC on  1792686885360
```

为什么分开：运行记录会断（被杀），启动记录不会 —— 启动次数 / 版本分布这类统计不该因为一次被杀就少一次。

## 10. 历史

v1~v19 的逐版记录留在 git 历史与 `.dsh/memory/next/` 的轮次记录里（37/38/39 等分片）。
v20（2026-10-23）把逐年长出来的接口整理了一遍：删掉 `selectedFile` / `settings` / `hostIcon`、
`pick` 改名 `pickContent`、`Done`/`After` 换成 `Result`、重载收成默认方法、
删掉 v1~v6 的老加载路径。之后只按第 1 节的规则演进。
