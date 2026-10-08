## 附录 A：每个功能都要挂的 `dev_*` 口（清单）

| 功能 | 建议的 dev 口 |
|---|---|
| F1 | 无（构建层） |
| F2 | `--es dev_new_slot <名>` 建槽；`--es dev_assign_key <key> [--es dev_assign_slot <槽>]` 指派（不带 slot = 清除，回落默认槽）；启动回归仍复用 `dev_launch_key` |
| F3 | ✅ `--ez dev_settings true`（进设置页）；`--es dev_setting_dump <tag>`（把当前配置 dump 到 `hub/report-settings-<tag>.txt`）；`--es dev_default_slot <槽 \| 空>`；`--es dev_max_logs <n>` |
| F4 | 复用 `dev_health` |
| F5 | ✅ `--es dev_policy_set <slot> [--es dev_policy_arg <enabled,min,max>]`（写策略）；`--es dev_autobackup_test <slot>`（立即跑一次备份并写 report） |
| F6 | ✅ UI 已完成（第二十轮）；**未留 dev 口** —— 导出必须由用户在系统文件选择器里挑落点，这个交互没法用 intent 参数代替，留 dev 口没有意义。自动化回归的做法：预先把存档推进槽的 `saves/`，再用 `input tap` 走 UI（见 `docs/history/13-F6-验证记录.md`） |
| F6c | ✅ `--es dev_zip_list <zip>`（只读清单）；`--es dev_zip_import <zip> --es dev_zip_slot <槽> [--es dev_zip_wipe 1]`；`--es dev_zip_export <目标.zip> [--es dev_zip_slot <槽>]`。三者都把结果写 `hub/report-devtool.txt`。<br>★ 与 F6 的区别：导出**能**留 dev 口，是因为我们只是把落点从 SAF 收到一个**绝对路径**，代码路径仍是 `Data.contentRoots`（F19 改名，原 `exportRoots`）+ `Exporter.zipTo` 那一份 |
| F7 | ✅ UI 已完成（第十八轮）；`--ez dev_logs true` **未做** —— 日志页是纯只读文件视图，没有需要驱动才能到达的状态，故没留 dev 口。若将来要自动化回归本页，再补 |
| F15 | ✅ `--es dev_theme <system\|light\|dark>`（写 `theme_mode` 后**自己 recreate**，结果写 `hub/report-devtool.txt`）。★ 唯一一个"改完要重建"的口，因此用**只落盘不弹窗**的报告、且**必须先 `intent.removeExtra`**（否则 recreate 重放 intent ⇒ 无限重建，见 F15 坑） |
| F16 | ✅ `--ez dev_cas_stats true`（池体检）；`--ez dev_cas_gc true`（立刻回收）；`--ez dev_cas_migrate true`（立刻迁旧格式）；`--es dev_backup_restore <槽>` / `--es dev_restore_pick <序号 0=最新>`（驱动恢复）。前三个写 `hub/report-devtool.txt`，且**先报统计再执行动作**（一份报告里能看到前后对比） |
| F10 | `--es dev_msav_meta <path>` |
| F11 | `--es dev_map_download <id>` |
| F12 | `--es dev_web <url>` |
| F13 | `--ez dev_mods_scan true` |
| F0 | 复用 `dev_m3_selftest` |

> ⚠️ **本表停在 F13 / F0**（后来没再逐行补）。**F21 之后的 dev 口请看各自分片**：
> F21 = `dev_mapstats`（[08 片](08-阶段-4-.msav-解析器.md)）· F22 = `dev_msch`（[15 片](15-阶段-9-蓝图.md)）·
> **F23 = `dev_crash_analyze <槽>` 与 `dev_crash_corpus <目录>`**（[16 片](16-阶段-10-崩溃分析.md)）。
> 🔴 新增 dev 口时**别忘了** `checkDevIntent` 开头那个 `isDev` **白名单**（漏了 ⇒ 命令静默无反应，2026-10-08 真踩过）。

**统一纪律**：`dev_*` 只在 debuggable 构建生效（`checkDevIntent` 已经在做），
每个 dev 口把结果**写进 `hub/report-*.txt`**（本 ROM 滤 logcat，只能靠文件）。

---

