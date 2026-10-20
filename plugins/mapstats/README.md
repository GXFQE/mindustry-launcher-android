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

插件只声明一条**地图页动作行**（`page: maps`）：槽 → 地图 页里排在「导出统计…」下面、
「删除…」上面（第 157 轮定的顺序）。点进去 → 选图 → 后台统计 → 页面上给四段数字 + 可建造 / 核心区。

## 现在做什么 / 还没做什么

- ✅ 自带 `.msav` 解析（`core/` 是宿主那几个纯 Java 类的拷贝：`MapStats` / `MsavTiles` /
  `MsavPatches` / `MapStatsExport` / `Hjson` / `BundleNames` / `Colors` + 三张表）；只走 `PluginApi`：
  `pick` / `open` / `runBackground` / `progress` / `theme` / `shareText` / `export` / `closePage` /
  `modsSkipped`。
- ✅ 数字与宿主对得上（真机 0.msav：地矿 2,595 / 可采地板 51,314 / 加成地板 118,873 / 可建造 31,083）。
- ✅ **译名**：版本 APK 的 `bundles/` + 本槽模组的 `*-bundles/`（与宿主同源，`MapStats.readBundles`）；
  真机中文界面显示「废料 / 铅 / 铜 / 煤炭 / 钛 / 钍」「沙子 / 盐碱地 / 深水 / 水 / 浅滩 / 页岩地 / 石油」。
- ✅ **模组叠加**：只用宿主给的事实（`ItemInfo.extra` 的 `enabled` / `willFailJavaLoad` / `rootPrefix`
  + `api.modsSkipped()`，v8），调 `MapStats.overlayMods` 建有效内容表；判据不重写。
- ⏳ 星球：宿主从 `Maps.Item.meta.planet` 读（决定最小核心 3×3 / 4×4），插件没有 meta ⇒ 按塞普罗算。
- ⏳ JSON 的声明（版本 APK / 模组数 / 补丁数 / 星球）还没进插件那份。
- ⏳ 宿主那一份（`MapStats*` / `MapDetailActivity` 统计段 / `MapsActivity` 导出统计 / `StatsExporter`）
  等上面两条补完再删；删的时候自检里那 1,000+ 行统计断言要一起迁到插件侧（插件工程目前**没有自检**）。

