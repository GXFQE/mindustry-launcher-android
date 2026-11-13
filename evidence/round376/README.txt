第 376 轮 · 插件接口 v42 —— 信息 P1（槽统计 / 存储 / 补丁问题 / 插件逐版本）（2026-11-13）

设计：docs/plans/2026-11-13-插件平台第三批-网络-补丁写-插入能力.md §3.2（P1）
接口文档：docs/PLUGIN-API.md（区间 20..42、v42 逐版记录、第 4 节「只读数据」+「槽统计 / 存储 / 补丁问题 / 插件逐版本（v42）」）

一、自检 selftest-376.txt
  通过 2219 项，失败 0 项（第 3 行，跑于 2026-11-13 21:30:30，dev 变体 / 真机 ELP-AN00）；
  基线 2212（第 375 轮）→ +7 条。这一批全是"读"接口，真机判据 = 自检**在设备上真调**：
  · 签名：`storage() -> Map<String,Long>` / `patchProblems() -> List` / `pluginVersions(id) -> List`；
    `DOC_VERSION == PluginHost.API == 42`。
  · `SlotInfo`：老 4 参构造器保留（默认槽 false / 统计 0）；新构造器字段齐全。
  · `slots()` 统计：当前槽在最前；每个槽的 `files` / `bytes` 与 `Data.slotInfo` 同一份事实（不是只看
    字段在）；默认槽恰一个（与 `Config.defaultSlot()` 对得上）；统计全非负。
  · `storage()`：键齐全；各段非负；**`total == hub + internal + cache + slots`**（唯一口径，
    与 `Storage.Report.totalBytes()` 同一实现，不是另算一遍）。
  · `patchProblems()`：**写夹具报告再读** —— `hub/report-patch.txt` 一行 `missing|Example Patch`
    与崩溃记录的 `crash_disabled|Example Patch|ex@1.0`，断言 launch / crash 两种 source 的
    code + detail（+ crash 的 ref）都到；跑完**把原文件恢复/删掉**。
  · `pluginVersions()`：条数 == `Plugins.versionsOf`；`active` 恰一份且与登记表一致；
    未知 id ⇒ 空表；不串 id。
  · 设备侧无残留：跑完 `app_hub/report-patch*.txt` 都不存在（夹具已恢复），当前槽「预览验收」，
    4 台插件，logcat 无 FATAL。

二、范围
  · `slots()` 不再便宜：每个槽遍历目录（文件数 / 字节数 / 四类内容数）⇒ 文档写明放后台线程。
  · `storage()` 全盘递归 ⇒ 文档写明"只该后台调、读失败返回空表（无失败码）"。
  · `patchProblems()` **只给事实**（source / code / detail / ref），不给"是谁的错"的结论 —— 归因是
    宿主崩溃分析那条线的事；launch 来自 `report-patch.txt`，crash 来自补丁崩溃记录。
  · `pluginVersions()` 只给"装过哪些"；**切换 / 停用 / 删除仍归宿主界面**（不是写接口）。
  · 新增两个 `ItemInfo` kind：`plugin_version` / `patch_problem`；`SlotInfo` 新字段不抬 `API_MIN`
    （仍是 20..42 的加法）。

三、构建
  · 产品版 `bash ./build.sh` ⇒ `== BUILD OK ==`（content gate / write-gate 全过）。
  · 这一批没有界面改动，不跑 sample-smoke；样例插件未改、设备上未安装任何样例。
