第 384 轮证据：自动备份的「这一局跑了多久」= :game 的检查点
============================================================

用户定案（2026-11-17，三句）
--------------------------
1. 「统一+20s 不就完了」→ 判定统一加 20 秒容差（落盘最多丢一拍）。
2. 「倒是可以」→ 没有检查点就跳过，不回退到 `now`。
3. 「宿主不记录文件，处理完就删（就是判断用不用备份而已），记录由插件负责」
   → `.time` 用完即删；不做 7 天清扫；历史账归 `usagestats`。

实现（文件）
------------
- 新增 `SessionClock`（:game）：每 20 秒 + pause/resume 写
  `app_hub/instances/<started>-<pid>.time`；游戏 activity 真结束（isFinishing 的 destroy）停表；
  daemon 线程。
- 新增 `SessionTime`：写 / 读最新 / `since` 过滤 / 用完即删 / 孤儿清扫（目录级实现可注入，自检不碰真目录）。
- `AutoBackup`：判定 = `lastSeen + 20 秒 - session.at`；没有检查点 ⇒ 跳过；读完即删；
  报告同时写「观测 / 判定（+20 秒）」；`sessionClock` 自检 6 条。
- `GameSlot`：`Instances.publish` 之后启动时钟；监听器**一次挂好**同时喂探针与时钟（防单槽被顶掉）。
- `GameActivityWatcher`：新增 `Finished`（isFinishing 的 destroy）回调。
- `PluginEvents`：`game-exit` 加 `started/ended/activeMs/wallMs/key`；settle 用内存
  `noteExitStats` 交接（防 settle 先跑、事件后到）。
- `usagestats` 插件（v7）：删 `PlaytimeProbe`；`plugin.json` 加 `game-exit` 事件条目；
  `UsageStats.onEvent` 把 `base + activeMs` 写回账本 / `total.txt`。
- `SelfTest#sessionClock` ①~⑥；`DevTools.devAutoBackupTest` 改成造检查点 + 轮询备份数。

自检
----
`selftest-384.txt` = **2314 项通过 / 0 失败**（第 383 轮基线 2308，+6 为 sessionClock ①~⑥）。
其中 ⑤ 的两条夹具坑当场被抓：
  · 第一次 sweptKeep=0 断言恒假 —— 第一轮清扫把 keep 文件也删了（同一目录复用）；
  · 第二次 sweptLive=1 —— keep 文件在第三轮被当死件删掉；
  修法：每个子用例只留一个文件（扫完即删前一个），并把 `swept/sweptKeep/sweptLive` 写进断言文本。

真机验收（HONOR ELP-AN00 / Android 16）
---------------------------------------
1) 检查点真的在走（`mindustry.android.AndroidLauncher` 前台）：
   `{"v":1,"pid":20333,"proc":"io.mdt.launcher:game","vkey":"io.anuke.mindustry",
     "slot":"测试专用","started":1794917455590,"lastSeen":1794917495591,"activeMs":39754}`
   25 秒后 lastSeen 前进、activeMs ≈ 40 秒；`started` 不变。
2) Home ⇒ pause 立即写："after HOME" lastSeen=1794917503441、activeMs=47604（冻结）。
   从最近任务回游戏后 activeMs 继续涨（81391、lastSeen=1794917555591）。
3) 整包 force-stop（模拟杀后台）⇒ `.time` 仍在；重启启动器：
   `autobackup-real.txt`：结果「跳过：不够门槛」、观测 1 分钟、判定 2 分钟（+20 秒）、
   前台累计 81391；`instances/` 只剩 `reserved`（检查点已删）。
   见 `autobackup-real.txt`（从设备抄回的原文）。
4) 插件账本：`total.txt` 6430472 → **6511863**（+81,391 = 上面的 activeMs，分毫不差）；
   `ledger.tsv` 行 `测试专用 / io.anuke.mindustry / 159.7 / 81391`（tag/title/raw 来自
   `versions()` 的 extra，`key` 来自检查点）。
5) dev 回归（专用一次性槽，跑完删）：
   `devtool.txt` = `现在备份数=1（结算前 0）`、`· 11-17 20:19:14 · 自动备份 1 个文件 11 B`；
   `autobackup-dev.txt` = 结果「完成」、判定 2 分钟、快照 11 B。
   （修 DevTools 之前的版本会报「备份数=0」——settle 的后台线程还没开始 beginWork，
     `awaitIdle` 立刻返回；现在改成轮询到备份数真的涨。）

现场还原
--------
- 一次性槽 `m3-clock-e2e` 与其备份目录已删；`测试专用` 只为跑时钟验收启动过约 1 分钟
  （未手动改其内容；游戏是被 force-stop 的，未走正常退出写设置那条路）。
- 用户 `config.json` / `mdt-slot.txt` 已还原到 20:09 基线（UTF-8 无 BOM，4041 B），
  当前槽 = `patchlab`，`last_version` = `v1607.apk`。

⚠️ 本轮真踩（AGENTS 已有这条，复述一次）
---------------------------------------
用 PowerShell `>` 抓设备 `config.json`（`adb exec-out ... > file`）得到的是 **UTF-16 + BOM**；
把它 `cp` 回设备 ⇒ `Config.load` 解析失败、回落到默认值 ⇒ **自检 2300/7**：
  · 导入版本全不见（主界面从 8 版本变 2 版本）；
  · `配色表 / spritePreview / 版本附加绑定` 一整族「拿不到 APK」。
修法：`[System.IO.File]::ReadAllText(utf16)` + `WriteAllText(..., UTF8Encoding($false))`
转成 UTF-8 无 BOM 再推回去；判据 = 设备上 `head -c 1 app_hub/config.json` 是 `{`（0x7B）。
⇒ **抓/回写设备文件只用 `adb pull` / `base64` / `run-as cp+mv`，别用 pwsh `>`。**
