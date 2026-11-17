第 386 轮证据：接口版本不够 ⇒ 主动报错 + 自动回退旧版
====================================================

用户
----
「还有我们不能让他静默忽略，能不能主动报错让他自动回退旧版本」

改动
----
- `Plugins.injectEarly` 以前遇到 API 不匹配只 `dropFromActive`（**静默卸载**、报告里一行）。
  现在：
  1. **injectDex 之前**先读 `plugin.json` 的 `api`（便宜门）——要求更高宿主就不把 dex 塞进宿主；
  2. 入口类 `api()` 与 `plugin.json api` 取较高者；仍不匹配 ⇒ `apiRejectOrRollback`：
     有 `known.txt` 的已知可用版本且它自己吃得住当前宿主 ⇒ **active 回退到那一版**；
     没有 ⇒ 停用（active 摘掉）；同时撤掉同一版的 pending（防下次又搬上来）；
  3. 写 `plugins/api-reject.txt`（`id / 旧版 / 回退到 / 要求接口 / 当前宿主 / rollback|disable`）；
  4. `MainActivity.onResume` 每进程 **Toast 一次**；插件页新增持久警告卡
     「有插件被回退到旧版本」+ 每个插件一行（要求多少 / 当前多少 / 退到哪一版）。
- 自动更新那侧也不再静默：
  - `PluginUpdates.checkAll` 的 `host-old` 分支现在会 `Plugins.dropIncompatiblePending`（撤掉待生效的
    不兼容版）并把"从非 host-old → host-old"记进 `Summary.hostOldNew`；
  - `checkDue` 有 hostOldNew 时**主动 Toast**「有 N 个插件的新版本需要更新的启动器（已保持旧版）」；
  - 插件页行/信息句改成「需要更新的启动器 · 已保持旧版」。
- 文案在 `strings-plugin.xml` 中英双语；`api-reject.txt` 解析是纯函数，自检钉了往返 + 脏行。

真机夹具（一次性 id `zzapitest`）
--------------------------------
1. `new999.apk` = usagestats APK 改 `assets/plugin.json` 的 `"api": 999`；
   `old1.apk` = 空文件；`known.txt` 写 `zzapitest=old1`，`active.txt` 写 `zzapitest=new999`。
2. 冷启动 → 启动注入：
   `active.txt` 变成 `zzapitest=old1`（**自动回退**）；
   `api-reject.txt` = `zzapitest new999 old1 999 44 rollback`；
   插件页顶部出现：
   ```
   有插件被回退到旧版本
   zzapitest：已安装那份 要求启动器接口 999（当前 44），已回退到 已安装那份
   ```
   （夹具版本没有可读的显示版本，所以两处都是「已安装那份」；真插件会显示 `<sha8>` / 自述版本。）
3. 收尾：`active/known` 已按备份还原、`zzapitest` 目录与 `api-reject.txt` 已删、临时文件已清。

自检
----
`selftest-386.txt` = **2318 / 0**（第 385 轮 2316；+2 = `api-reject` 记录往返 + 脏行）。
