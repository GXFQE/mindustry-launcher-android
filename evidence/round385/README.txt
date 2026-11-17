第 385 轮证据：插件接口 v44 + 自动更新检查宿主版本
====================================================

用户
----
1. 「usagestats目前正常？」→ 确认（第 384 轮真机：total +81,391 = activeMs）。
2. 「话说插件接口的版本升了吗」→ 没升；解释后：「升吧，等着和宿主一起发更新」。
3. 「我觉得我们非常需要一个插件自动更新检查宿主版本需求的功能」→ 本轮做掉。

代码
----
- 抬到 v44：`PluginHost.API = 44`、`PluginApi.DOC_VERSION = 44`、`docs/PLUGIN-API.md` 20..44 +
  v44 逐版记录、`SelfTest` 里 `== 43` 的元断言改 44、`UsageStats.api()` 31 → 44、
  `plugins/usagestats/assets/plugin.json` 加 `"api": 44`。
- `plugin.json` 的 `api` 以前是死字段 ⇒ 现在：
  · `PluginMeta.apiOf(File)` 读 APK 自述；
  · `PluginHost` 加载时取 `max(入口 api(), plugin.json api)`（防"类里写低版本绕过门"）；
  · `PluginUpdates` 下载前 / 装之前拿它跟宿主比。
- 自动更新查宿主版本（三道门）：
  · 清单层：`PluginUpdateManifest.parse(json, PluginHost.API)` 的 `E_HOST_OLD` ⇒ **不下载**；
  · APK 层：下载后读 `plugin.json.api`，超了 ⇒ **删掉下载件、不装**（防发布脚本漏写 `minHostApi`）；
  · `PluginUpdates.install` 落盘前再判一次（防以后新增入口漏判）。
  · 状态 `host-old`（`PluginUpdateState.HOST_OLD`）：**不算 failed**、`pending=false`、没有「更新」按钮；
    插件页行显示「有新版 X · 需要更新的启动器」，信息页与手动检查结果卡也有对应文案；
    自动检查不弹 Toast（用户当下只能更新启动器）。
- `tools/make-plugin-update.py`：从 APK 的 `assets/plugin.json` 读 `api` → 清单写 `minHostApi`；
  `--min-host-api` 可临时覆盖；`--check` 校验正整数；没写 `api` 时提示。

验证
----
- 自检 **2316 / 0**（上轮 2314；+2 = `host-too-old` 仍带字段 + 状态表 `host-old` 往返）。
- 真机（loopback 夹具，`adb reverse`）：
  ① 清单 `minHostApi=999`：不下载（`app_hub/plugins-update` 整个目录都没建），
     `update-state.txt` = `usagestats host-old 000…ff 999.0 … minHostApi=999 host=44`；
  ② 清单不写 `minHostApi`、但 APK 自述 `api=999`：下载后**删掉**，state =
     `usagestats host-old f3e7ea45… 999.1 … apk api=999 host=44`；
     `report-plugin-update.txt` 的 summary = `hostOld=1 failed=0`。
  ③ 插件页可见 `usagestats / 生效中 / 有新版 999.1 · 需要更新的启动器`，且**没有**「更新」按钮。
- 收尾：`update-state.txt` 已按测试前备份还原；`plugins-update` 为空；loopback 夹具与 HTTP 服务已删 / 已停。

生成清单实测
------------
```
python tools/make-plugin-update.py --apk usagestats.apk --version 7 --out usagestats.json
  minHostApi = 44
python tools/make-plugin-update.py --check usagestats.json
  OK  plugin=usagestats version=7 size=25043 minHostApi=44
```
