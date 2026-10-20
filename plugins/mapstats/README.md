# mapstats —— 官方插件（地图资源统计）

第 188 轮起从宿主 F21 搬出来的**第一个官方插件**。目标是把宿主那套"重解析器 + 页面"整块搬成插件，
顺手用真实功能给插件接口（v7）当活体用例。

## 构建

```bash
ANDROID_HOME=D:/AndroidSDK bash plugins/mapstats/build.sh
# 产物：%TEMP%/mdt-mapstats/mapstats.apk（默认 debug 签名 = 与本地宿主同一把钥匙 ⇒ 判「官方」）
```

## 装到设备（dev 变体的直通口）

```bash
adb push %TEMP%/mdt-mapstats/mapstats.apk /sdcard/Android/data/io.mdt.launcher/hub/tmp/mapstats.apk
adb shell am force-stop io.mdt.launcher
adb shell am start -n io.mdt.launcher/io.mdt.launcher.MainActivity \
  --es dev_plugininstall /sdcard/Android/data/io.mdt.launcher/hub/tmp/mapstats.apk \
  --es dev_pluginver <唯一版本号> --es dev_pluginnow true
```

插件只声明一条**地图页动作行**（`page: maps`）：槽 → 地图 页里排在「导出地图…」下面、
「删除…」上面（第 157 轮定的顺序）。点进去 → 选图 → 后台统计 → 页面上给四段数字 + 可建造 / 核心区。

## 现在做什么 / 还没做什么

- ✅ 自带 `.msav` 解析（`core/` 是宿主那几个纯 Java 类的拷贝：`MapStats` / `MsavTiles` /
  `MsavPatches` / `MapStatsExport` / `Hjson` / `BundleNames` / `Colors` + 三张表）；只走 `PluginApi`：
  `pick` / `open` / `runBackground` / `progress` / `theme` / `shareText` / `export` / `closePage` /
  `modsSkipped`。
- ✅ 数字与宿主对得上（真机 0.msav：地矿 2,595 / 可采地板 51,314 / 加成地板 118,873 / 可建造 31,083；
  埃里克尔槽：地矿 5 种 1,232 / 墙矿 4 种 846 / 加成 8 种 40,657 / 可建造 89,480 / 核心区 195·8 个 4×4）。
- ✅ **译名**：版本 APK 的 `bundles/` + 本槽模组的 `*-bundles/`（与宿主同源，`MapStats.readBundles`）；
  🔴 **只认本槽指向的版本 APK**（没有就按内部名，和宿主一致 —— 拿别的版本兜底会把矿层墙矿与地矿
  翻成同名、被合并且少两行）。
- ✅ **模组叠加**：只用宿主给的事实（`ItemInfo.extra` 的 `enabled` / `willFailJavaLoad` / `rootPrefix`
  + `api.modsSkipped()`，v8），调 `MapStats.overlayMods` 建有效内容表；判据不重写。
- ✅ **星球**：地图条目自带 `extra["planet"]`（宿主从 `tags.rules` 抠的）⇒ `analyze(..., planet)`，
  核心区边长按星球（塞普罗 3×3 / 埃里克尔 4×4），依据那行与宿主同一句话。
- ✅ **导出**：单张 = `.json`（`jsonDoc`）、多张 = `.jsonl`（`jsonlBatch`，一行一张图）、
  文件名 `mdt-stats-<名>-<时间>.json[l]`、mime 跟着走、声明里有槽 / 图名 / sha256 / 星球 /
  版本 APK / 模组数 / 内容表行数；真机两张导出 = 恰好 2 行。
- ✅ **宿主那份 F21 已删**（第 192 轮）：详情页统计段、地图页「导出统计…」、`StatsExporter`、
  `MapStatsExport` 全没了；`MapStats*` 与三张表**留在宿主**（蓝图那条线在用，不是 F21 专属）。
- ✅ **插件侧自检**：`bash plugins/mapstats/build.sh check`（纯 Java / PC 跑，19 条：内容表 / 遮挡口径 /
  核心区+星球 / 单张·多张·简洁导出 / 模组叠加 / pretty）。

