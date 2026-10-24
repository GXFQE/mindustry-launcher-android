# mods-health —— 官方插件（模组体检）

第 229 轮从宿主搬出来的**第二个官方插件**（第一个是 `mapstats`）。宿主原来那行
「查模组冲突」整块删掉，判据搬到这里；界面由宿主的 **analyzer** 扩展点渲染
（插件只给 `Finding`，不自己画卡）。v25 起还往「筛选与排序」里注入自己的一项。

> 前身叫 `conflict`（第 229 轮同日改名）：用户 2026-10-24 定的方向是
> 「所有关于模组存在问题相关的都放一个插件里」⇒ 名字要装得下结构 / 依赖 / 启停，不只冲突。

## 它做什么

模组页的「插件」卡里给结论（**自动**，不用点）：

| 层 | 严重度 | 判据 |
|---|---|---|
| 内置检测 | 问题 | A 的 `classes.dex` 里出现 B 的**主类描述符** ⇒ A 把 B 的代码打包了（**挂到 A 那一行**，v25） |
| 游戏日志 | 信息 | 上次启动的 `last_log.txt` 里提到这些模组的行（"谁真的接管了"） |

再加一条「检查了 N 个带代码的模组（X MB）」—— **只在没发现冲突时**出现
（有结论就说明扫过了；用户 2026-10-24：「感觉有点占地方啊」⇒ 卡片改成一件事一行，
解释只留"要动手"的那条，日志不再贴原文，长的依据归运行日志）。

### 注入的筛选 / 排序（v25）

| 菜单项 | 判据 |
|---|---|
| 有冲突的模组（筛选） | 参与「内置」结论的模组 |
| 按冲突风险（排序） | 参与的排前面，同档按标题 |

★ 与卡片里的结论**同源**（同一份按签名缓存的快照）—— 别另写一套，否则会漂。

> 🔴 第 229 轮删掉了原来那层「共同全局钩子」（`mindustry/logic/LCanvas` ⇒ 都想接管逻辑编辑器）：
> 它靠一张**写死的类名表**，而"引用一个类"≠"接管它"。用户 2026-10-24：「建议把这种硬编码还是
> 半成品的东西删掉」⇒ 整层撤掉。**宁可少说**。

## 接口

- `api() = 25`（v24 的 analyzer + v25 的 `Finding.anchor` 与 filter/sort 插槽）。
- 声明：analyzer 一条 + `slot: filter` / `slot: sort` 各一条（见 `assets/plugin.json`）。
- 入口类实现 `analyze(ItemInfo, PluginApi)` / `accept(...)` / `compare(...)`，
  **全部挂在同一个类上**（宿主 `PluginHost.open` 只认声明了 `api()` 的那个类 ——
  第 229 轮踩过：把 `accept` 放单独的 `Views` 类 ⇒ `NoSuchMethodException: Views.api`）。
- 只用 `contents(mods)` / `modsDir()` / `slotDir()` / `context()` / `log()`；**不碰宿主的类**
  （判据在 `core/Conflicts.java`，纯 Java）。

### ⚠️ analyzer 是逐项回调，结论是整槽的

宿主对模组页**每一项**调用一次 `analyze`，所以整槽结论只在 **`contents(mods)` 的第一项**
那一次返回，其余返回空表。挂到某一项上的结论（`Finding.anchor`）不受这条限制。

### 为什么有缓存

原来那行是"按需"跑的（要读几 MB 的 dex）；搬到 analyzer 之后**每次进模组页都会跑**，
筛选 / 排序也要同一份结果。⇒ 按 `mods/` 一级条目的 `名字:大小:mtime` 签名缓存：
包没变就直接复用（毫秒级），变了才重扫。日志摘录**不缓存**（每次启动游戏都会变，
读一份文本很便宜）。

## 构建 / 自检 / 装到设备

```bash
ANDROID_HOME=D:/AndroidSDK bash plugins/mods-health/build.sh          # -> %TEMP%/mdt-mods-health/mods-health.apk
ANDROID_HOME=D:/AndroidSDK bash plugins/mods-health/build.sh check    # PC 侧判据自检（不需要设备）
```

装（dev 变体的直通口，产品版不认 `dev_*`）：

```bash
adb push %TEMP%/mdt-mods-health/mods-health.apk /sdcard/Android/data/io.mdt.launcher/hub/tmp/mods-health.apk
adb shell am force-stop io.mdt.launcher
adb shell am start -n io.mdt.launcher/io.mdt.launcher.MainActivity \
  --es dev_plugininstall /sdcard/Android/data/io.mdt.launcher/hub/tmp/mods-health.apk \
  --es dev_pluginver <唯一版本号> --es dev_pluginnow true
```

⚠️ 插件 **id = APK 文件名**（缺省）⇒ 这份必须叫 `mods-health.apk`。
⚠️ `now=true` 只是写生效登记；**类要下次启动才注进宿主 CL**（`injected.txt`）——
也就是说"注入那条路"必须**冷启动一次**才算验过。
