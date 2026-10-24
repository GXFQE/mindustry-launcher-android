# conflict —— 官方插件（模组冲突检查）

第 229 轮从宿主搬出来的**第二个官方插件**（第一个是 `mapstats`）。宿主原来那行
「查模组冲突」整块删掉，判据搬到这里；界面由宿主的 **analyzer** 扩展点渲染
（插件只给 `Finding`，不自己画卡）。

## 它做什么

模组页的「插件」卡里给结论（**自动**，不用点）：

| 层 | 严重度 | 判据 |
|---|---|---|
| 内置检测 | 问题 | A 的 `classes.dex` 里出现 B 的**主类描述符** ⇒ A 把 B 的代码打包了 |
| 共同全局钩子 | 警告 | 两个以上模组都引用 `mindustry/logic/LCanvas` ⇒ 都想接管逻辑编辑器 |
| 简介提及 | 信息 | A 的简介里出现 B 的名字（线索，可能只是配套说明） |
| 游戏日志 | 信息 | 上次启动的 `last_log.txt` 里提到这些模组的行（"谁真的接管了"） |

再加一条「检查了 N 个带代码的模组（X MB）」—— 让"没找到"和"没扫"分得开。

## 接口

- `api() = 24`（analyzer 是 v24 的扩展点）。
- 声明：`{"slot":"analyzer","page":"mods"}`；入口类实现
  `public static List<Finding> analyze(ItemInfo item, PluginApi api)`。
- 只用 `contents(mods)` / `modsDir()` / `slotDir()` / `context()` / `log()`；**不碰宿主的类**
  （判据在 `core/Conflicts.java`，纯 Java）。

### ⚠️ analyzer 是逐项回调，结论是整槽的

宿主对模组页**每一项**调用一次 `analyze`，而 `Finding` 没有 item 字段（结论画成一张卡）。
⇒ 本插件只在 **`contents(mods)` 的第一项**那一次返回结论，其余返回空表。
第一项 = 宿主的 `pageItems()` 第一项（同一个 `PluginData.scanMods`、同一种排序），
**不是**"回调顺序"。

### 为什么有缓存

原来那行是"按需"跑的（要读几 MB 的 dex）；搬到 analyzer 之后**每次进模组页都会跑**。
⇒ 按 `mods/` 一级条目的 `名字:大小:mtime` 签名缓存三层判据的结果：
包没变就直接复用（毫秒级），变了才重扫。日志摘录**不缓存**（每次启动游戏都会变，
读一份文本很便宜）。

## 构建 / 自检 / 装到设备

```bash
ANDROID_HOME=D:/AndroidSDK bash plugins/conflict/build.sh          # -> %TEMP%/mdt-conflict/conflict.apk
ANDROID_HOME=D:/AndroidSDK bash plugins/conflict/build.sh check    # PC 侧判据自检（不需要设备）
```

装（dev 变体的直通口，产品版不认 `dev_*`）：

```bash
adb push %TEMP%/mdt-conflict/conflict.apk /sdcard/Android/data/io.mdt.launcher/hub/tmp/conflict.apk
adb shell am force-stop io.mdt.launcher
adb shell am start -n io.mdt.launcher/io.mdt.launcher.MainActivity \
  --es dev_plugininstall /sdcard/Android/data/io.mdt.launcher/hub/tmp/conflict.apk \
  --es dev_pluginver <唯一版本号> --es dev_pluginnow true
```

⚠️ 插件 **id = APK 文件名**（缺省）⇒ 这份必须叫 `conflict.apk`。
⚠️ `now=true` 只是写生效登记；**类要下次启动才注进宿主 CL**（`injected.txt`）——
也就是说"注入那条路"必须**冷启动一次**才算验过（见宿主 `PluginAnalysis` 的 `isInjected`）。
