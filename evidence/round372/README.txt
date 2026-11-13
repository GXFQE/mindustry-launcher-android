第 372 轮（2026-11-13）插件接口 v38（读插件清单 / 游戏补丁）—— 真机验收证据
================================================================================

设备：HONOR ELP-AN00 / Android 16（API 36），序列号 AJYMUT4522007370
构建：DEBUGGABLE=true SUFFIX=-dev ./build.sh install（dev 变体，自检用）
跑前当前槽：预览验收

一、自检 selftest-372.txt
  通过 2143 项，失败 0 项（第 3 行）；基线 2106（第 371 轮）→ +37 条。新增的 v38 组：
    · 版本：host plugin api >= 38；PluginApi.DOC_VERSION == PluginHost.API == 38；
    · 签名：plugins() / patches() / patches(String,String) → List；KIND_PLUGIN / KIND_PATCH；
    · 【v38 · 插件清单 / 游戏补丁清单】：
      ① 真表与 Plugins.installedIds 同源（4 / 4 台）、键齐全、state 三态、顺序 = id 字典序、
         activeVersion / pendingVersion / state 与登记表逐字一致；内置被认出来；
      ② 夹具（app/selftest-v38 假 APK）：off / pending / active / L1 四态都对，
         且它**出现在真表里**（"所有插件"包含没生效的）；**登记指向的 APK 不在**时
         entry / 自述 / access / extCount 一个都不出现（不把"没有 APK"造出默认入口）；
      ②b 只剩隔离件（quarantine/7.apk）⇒ version / entry / access / extCount 不出现、
         quarantined=true，且它仍算"装着"；
      ③ 反射造真 PluginHost$PContext 当插件调：plugins() 与数据层同尺寸、patches() 可用；
         有补丁时：给启动项每条都有 enabled、版本不存在时不写 applies；
      ④ 版本 key ⇒ 真实 APK sha256 = 64 hex；不存在的版本 ⇒ 空串；
      ⑤ 清场后清单回到与 installedIds 一致（夹具不在设备上留下"假的已装插件"）。
    · 补丁组（⑧）：patches() 里的 ref / 名称 / filesOk / 顺序（id、版本字典序）；target 事实
      （sha / gameVersion / classes=2 / assets=1 / bytes）；不带启动项不写 enabled / applies；
      同一补丁在 IKEY=true、IKEY2=false；`patches(vk, null)` 按**这一页的槽**判（两个不同页面槽
      的 PContext 对照）；版本不在已装列表 ⇒ 无 applies 但每条都有 enabled（夹具非空）；patchItem
      直喂匹配哈希 ⇒ applies/match=true、喂别的哈希 ⇒ false；缺文件记录仍列出（filesOk=false +
      error=missing-files）；note 的控制字符清成空格、换行保留。

二、外部插件真调（plugin-api-probe.txt / plugin-api-boot.txt）
  ★ 这条才是"接口"的端到端证据：不是宿主内部调 PluginData，而是**一个外部 APK 插件**
  （用 plugins/sample 的构建链、编译期 API 桩直接取宿主 app/src/io/mdt/launcher/api 的 v38 那份）
  声明 `slot: section / page: main`，被宿主渲染时调用 plugin-api 并把结果写进自己的 dataDir：
    hostApi=38
    plugins=5   （4 台内置 + 自己；state=active、trust=builtin/official、level=L1/L2、
                 version=sha 前 8 位、access / extCount 都对）
    patches=1   （mdtx-1607@2026.10.10.B509 / filesOk=true / target.0 7217 类 / 25 条目 / sha256 全在）
    withInstance=1 everyEnabled=true    （给启动项时每条都有 enabled）
  boot 报告里同一份 APK 的注入记录：
    v38probe=1 file=1.apk entry=v38probe.V38Probe INJECTED elems=6 ... entry=OK api=38
  说明：① 插件侧编译不依赖宿主实现（只依赖 api 包）；② 运行期经 PContext 真调到新方法；
  ③ APK 内的 api 桩与宿主 v38 接口二进制兼容。
  清理：验收后已从设备上删掉该夹具（active/pending/known/injected/trust/levels/retry 登记
  逐文件 sed 掉、`app_hub/plugins/v38probe` 删除、hub/tmp 里推上去的 APK 删除），
  复核 `active.txt` 回到验收前的 4 条（usagestats / mods-health / mapstats / probe）。
  夹具源码没有进仓库（临时工程，验收后已删）。

三、构建门禁
  产品版 `bash ./build.sh`：== BUILD OK ==（[0/4] content gate OK；write-gate selftest 6/6 +
  36 entries gated / 9 global gameAlive / 12 deletion files frozen / 1 killBackgroundProcesses）。
  插件侧编译：plugins/sample/build.sh 通过（API 桩直接取宿主那份，v38 编译期无破坏）；
  `bash tools/sample-smoke.sh` = SAMPLE SMOKE OK（装进 dev 宿主、重启进程在、无 FATAL；
  验收后已把 sample 插件从设备登记与目录里清掉，active.txt 回到 4 条）。

四、未验到（如实记）
  · 插件清单里的"已装版本列表 / 回滚目标"没有进 API（只给 effective version 与 active/pending）。
  · 补丁的"上次启动问题 / 崩溃后自动停用"没有进 API（现在只在补丁页）。
  · 多开（两个 :game 同时在跑）下插件调 patches(versionKey, slot) 的并发读没有单独造现场；
    读路径本来就不受写门禁限制（第 367 轮定案），判据与补丁页同一批函数。
