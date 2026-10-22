# 插件接口（PluginApi）—— 给插件开发者看的那一份

> 面向写插件的人；宿主内部的实现细节看 `docs/flows/` 与 `.dsh/memory/ref/`。
> 文档里的版本号必须等于 `PluginHost.API`，自检有一条元断言钉着（`PluginApi.DOC_VERSION`）。

## 1. 版本策略

- 宿主暴露**兼容区间**：插件 `api()` 落在 `[API_MIN, API]` 就加载（当前 `1..17`）。
- 接口只加不改：老插件在新区间里继续能跑；要改语义就发新版本号，别偷偷改老方法的含义。
- 插件报的版本**高于**本机 ⇒ 拒绝加载并说明"宿主太旧"；低于 `API_MIN` ⇒ 说明"插件太旧"。

## 2. 插件包结构

```
你的插件.apk
├── classes.dex                 # 入口类 + 你用到的那份 io.mdt.launcher.api.*
└── assets/plugin.json          # 自述与扩展声明（org.json 解析）
```

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
public static int api();                        // 必需：你需要的接口版本
public static View createView(PluginApi api);   // content / section 用
public static void run(PluginApi api);          // action 用（在原页跑，不换页）
public static void unload();                    // 可选：收尾（也认 unload(Context)）
```

- 入口类**一个插件一份**，方法都是 `static`，由宿主反射调用。
- `createView` / `run` 在**主线程**调用；耗时活请自己起线程，并用 `runBackground` 报告进度。
- `unload` 只在"插件页关闭 / 换页 / Activity 销毁"时调用；**进程被杀不会调用**，关键数据请落盘在拿到结果时。
  `unload` 里抛异常**不会弹窗**（只写 `plugin unload failed:` 日志），别在里面做必须成功的事。

## 4. 接口清单

### 环境
| 方法 | 说明 |
|---|---|
| `hostApi()` | 宿主接口版本（判断能力用） |
| `context()` | 插件上下文（拿资源、字符串） |
| `page()` / `slot()` | 从哪一页来 / 当前槽名 |
| `theme()` | 主题色（别自己写死颜色） |
| `hostIcon(name)` | 取宿主图标（`ic_map`…），拿不到返回 null |

### 选中态与数据（只读）
| 方法 | 说明 |
|---|---|
| `selectedFile()` / `selectedItem()` | 详情页当前那一份 / 那一项 |
| `selectedItems()` / `pageItems()` | 列表页勾选/当前页条目快照（列表页没有持久多选） |
| `slots()` | 全部槽 |
| `contents(kind)` / `contents(slot, kind)` | 四类内容；`kind` = `saves`/`maps`/`mods`/`blueprints` |
| `logs()` / `trash()` / `versions()` | 日志 / 中转站 / 已装版本 |
| `open(item)` | 打开条目的字节流（文件或容器条目统一） |

### 目录
`slotDir()` `savesDir()` `modsDir()` `mapsDir()` `blueprintsDir()` `dataDir()`

`dataDir()` 是**你自己的**可写目录（宿主保证存在）。

### 交互
| 方法 | 说明 |
|---|---|
| `toast(text)` / `log(text)` | 提示 / 调试日志（logcat tag `MDTPlugin`） |
| `choose(title, options, cb)` | 宿主原生二选一/多选弹窗 |
| `confirm(title, msg, okText, cb)` | 是/否弹窗 |
| `prompt(title, hint, initial, okText, cb)` | 输入弹窗（取消回调 null） |
| `pickFile(mime, cb)` | 系统选择器挑任意文件，宿主复制到你的 `cache/` 再给 `(name, File)`；取消 `(null,null)` |
| `progressCard(title, msg, done, total[, cancelable, cb])` / `progressCardClose()` | 宿主进度框（可取消版会回调） |

### 任务
`runBackground(label, body)` + `progress(done, total)`：后台线程跑活，宿主负责系统通知与进度。

### 出口
| 方法 | 说明 |
|---|---|
| `export(name, mime, stream[, after])` | 系统保存框；`after.onAfter(ok)` 是**真的写完**才回调 |
| `share(name, stream)` / `shareText(name, text)` | 系统分享面板 |

### 写与整理
| 方法 | 说明 |
|---|---|
| `writeItem(item, stream, cb)` | 换掉**槽内已存在**文件的字节（同目录临时文件 + 原子替换；游戏在跑时拒绝） |
| `trashItem(item, cb)` | 挪进中转站（不是真删，规则与界面删除同一套） |
| `openItem(item)` | 跳详情页（地图/存档 → 地图详情；蓝图 → 蓝图详情；模组 → 模组页） |

### 页面
`closePage()`：关掉插件页（宿主负责返回导航）。

## 5. 线程与生命周期约定

- `createView` / `run` 在 UI 线程；**不要**在里面读大文件。
- `runBackground(label, body)` 的 `body` 在后台线程；**别碰 View**，要更新 UI 用 View 自己的 `post`。
- 回调（`Choose` / `After` / `Done` / `PickFile` / `Cancel`）**都在 UI 线程**回调。
- `export` / `share` / `writeItem` 传进去的 `InputStream` **由宿主关闭**；`pickFile` 给你的是文件，自己读。

## 6. 失败语义

| 场景 | 表现 |
|---|---|
| `export` 用户取消 | `after.onAfter(false)`（不是错误，别弹红字） |
| `export` 写失败 | `after.onAfter(false)`，宿主自己 toast 失败原因 |
| `pickFile` 取消 | `(null, null)` |
| `writeItem` 游戏在跑 / 路径不在槽内 / 写失败 | `cb.onDone(false)`（原文件不动） |
| `trashItem` 失败 | `cb.onDone(false)` |
| 插件页加载失败 / `createView` 抛异常 | 宿主显示兜底页（有原因文案），不会闪退 |

## 7. 版本表

| 版本 | 加了什么 |
|---|---|
| v3 | `PluginApi` 基础 + `content` 插槽 |
| v4 | 全部页面 + `selectedFile()` |
| v5 | 只读数据面（`slots` / `contents` / `pageItems` / `open`） |
| v6 | `logs` / `trash` / `versions` / `selectedItems` |
| v7 | 主题 / 后台任务+进度 / `closePage` / 分享·保存 / `pick` |
| v9 | `selectedItem()` + `section` 插槽 |
| v10 | `choose()` |
| v11 | `export(..., after)` 完成回调 |
| v12 | `action` 插槽 + `run()`（原页跑） |
| v13 | `progressCard()` |
| v14 | `dataDir` / `gameRunning` / `hostIcon` / `confirm` / `prompt` |
| v15 | `pickFile` / `openItem` / `trashItem` / 可取消进度 |
| v16 | `writeItem` |
| v17 | 入口钩子 `unload()` |

## 8. 边界（先看这几条再动手）

- **没有沙箱**：能读宿主能读的、能写你声明的地方；靠"约定 + 告知"，不是靠权限门。
- `writeItem` 只允许改**槽内四类目录里已存在的文件**（canonical 前缀校验），不能新建、不能写到别处。
- 插件的数据住在 `<插件>/data`（`dataDir()`），缓存住 `<插件>/cache`（`pickFile` 落点）。
- 界面文案请走 `api.context().getString(...)` 或插件自带资源；宿主的 `Trans` 只服务宿主自己。

## 9. 配套示例：`plugins/sample/`

仓库里的 `plugins/sample/` 是一个**按本文档从零写出来**的最小完整插件（约 150 行），用来当模板与冒烟测试：

- `section` 挂在 `map-detail`：显示当前地图的标题 / 大小，并读**前 8 字节**证明 `open()` 真的能读
- `action` 挂在 `maps`：勾选地图 → 选 json / csv → `export(..., after)`
- `unload()`：关页时写一行 `SAMPLE unload` 日志

构建（与 mapstats 同一条链）：

```bash
ANDROID_HOME=<sdk> bash plugins/sample/build.sh
# 产物：%TEMP%/mdt-sample/mapstats.apk
```

装到设备上：用插件页「装一个插件 APK…」，或开发口 `--es dev_plugininstall <apk>`。

### 冒烟脚本（改接口时必须跑）

```bash
bash tools/sample-smoke.sh                # 当前装的是 dev 变体
bash tools/sample-smoke.sh --dev-install  # 先构建/安装 dev 变体
```

它做三件事：构建样例 → 装进 dev 宿主 → 重启后断言"装上了、没崩"，最后打印两处**肉眼**检查点
（地图页的 action 行、地图详情页的 section）。改 `PluginApi` / 注入 / 槽位之后跑一遍，能挡住"文档写着但实际用不了"。
