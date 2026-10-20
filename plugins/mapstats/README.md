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
  `MsavPatches` / `MapStatsExport` / `Hjson` + 三张表）；只走 `PluginApi`：`pick` / `open` /
  `runBackground` / `progress` / `theme` / `shareText` / `export` / `closePage`。
- ✅ 数字与宿主对得上（真机 0.msav：地矿 2,595 / 可采地板 51,314 / 加成地板 118,873 / 可建造 31,083）。
- ⏳ 译名：现在用 `MapStats.pretty()` 的内部名兜底（界面上是 `Scrap` / `Sand Floor`），
  下一轮把宿主 `BundleNames` + 版本 APK / 模组 bundle 那条路搬进来（要先确认插件怎么拿版本 APK 的字节）。
- ⏳ 模组叠加：`core` 只用了原版定义表；`MapStatsMods` 的"扫模组包 / 打补丁"还没搬。
- ⏳ 星球：宿主从 `Maps.Item.meta.planet` 读（决定最小核心 3×3 / 4×4），插件现在没有 meta 可读 ⇒ 按塞普罗算。
- ⏳ 宿主那一份（`MapStats*` / `MapDetailActivity` 统计段 / `MapsActivity` 导出统计 / `StatsExporter`）
  等插件补到 parity 再删；删的时候自检里那 1,000+ 行统计断言要一起迁到插件侧。
