第 373 轮 · 插件接口 v39 —— 网络 / 补丁写 / 实例与备份（2026-11-13）

设计：docs/plans/2026-11-13-插件平台第三批-网络-补丁写-插入能力.md（v39 已落地，v40 插入能力包待办）
接口文档：docs/PLUGIN-API.md（区间 20..39、「网络与下载（v39）」「补丁写（v39）」、v39 加键表与逐版记录）
补丁流程：docs/flows/21-阶段-15-补丁.md F27.18

一、自检 selftest-373.txt
  通过 2193 项，失败 0 项（第 3 行，跑于 2026-11-13 20:03:45，dev 变体 / 真机 ELP-AN00）；
  基线 2135（第 372 轮）→ +58 条（v39 首版 +44，评审修正 +14）。新增/加强的尺子：
  · v39 签名：instances()/backups() -> List；fetchText/githubReleases；download -> Task；
    patchEnable/Disable/Import/Delete/Conflicts；KIND_INSTANCE / KIND_BACKUP；
    GitHubRelease 快照（tag / prerelease / Asset.name / size / url）；DOC_VERSION == API == 39。
  · ① 网络纯逻辑（不打真网络，用假 Fetcher 注入）：
    URL 闸门（https / 127.0.0.1 / localhost 放行；http 非本机 / file / ftp / 空 / 超长全拒）；
    下载文件名清洗（最后一段 / 查询串丢弃 / 危险字符换下划线 / 空名兜底 / ≤80）；
    只有 api.github.com 计入配额；GitHub API 同 URL 10 分钟只真请求一次、过期重拉、
    非 GitHub 不缓存；40 次/小时后给 rate-limited；releases() 解析 tag/prerelease/asset 名/size/
    apiUrl 优先；repo 形状不对 ⇒ bad-url、返回不是 JSON 数组 ⇒ bad-data（不冒充"没有版本"）。
  · ② 下载真往返：在设备内起一次性本机 HTTP 服务当镜像 ⇒ .part → sha256 校验 → 改名成
    asset.bin（实际 sha 回报）；sha 不符 ⇒ status=SHA 且 .part / 成品都不留。
  · ③ 信息加键：settings() 六个公开设置键且值机器可读；versions() 的 instanceKey 与宿主
    Versions.instanceKey 逐字一致、bound 是 true|false、patchCount/backupCount 非负整数；
    instances() 结构（pid/vkey/slot/instanceKey/started/starting）；backups() 结构
    （created/files/bytes/legacy/slot）；plugins() 更新键（状态五值之一、与 version/note 同现）。
  · ④ trash() 漏项：Trash.Kind 每个值都有唯一类别名（新加 Kind 忘了映射 ⇒ 红）；
    中转站目录数 = Kind 数 - 1；在 versions-trash / patches-trash 里放两条夹具后
    trash() 真能列出（以前这两种整块漏掉）。
  · ⑨ 补丁写真调回路（在补丁组里，反射造真的 PluginHost$PContext；异步回调用 CountDownLatch
    等主线程）：临时绑一个槽让启动项真实存在 + 造一个指向该版本真实 APK 哈希的补丁包 ⇒
    patchImport（成功给 ref）→ patchConflicts（空表）→ patchEnable（ok，config 里真有引用，
    patches() 看到 enabled/applies=true）→ 再预检 = enabled（不是"跟自己冲突"）→
    patchDisable（引用消失）→ patchDelete（记录不在补丁库、进 patches-trash）。
    收尾无条件恢复临时槽绑定 / 配置引用 / 补丁记录 / 中转站夹具。
  基线 2135 → 2193 的数字与 evidence/round372/selftest-372.txt 对得上（+58 = v39 首版 44 + 评审修正 14）。

一之二、独立评审后的修正（静态评审；无 BLOCKER，MAJOR 按重要性修）
  · M1 GitHub 配额绕过：`download` 原来不计配额 ⇒ 插件可用下载打光 60/h、把自更新搞停。
    修：新增 `PluginNet.noteApiRequest`（fetchText 与 download 共用同一份配额）；host 归一化
    （大小写 / 末尾点），缓存键也按规范化 URL（大写 URL 命中同一份缓存）。自检 +4 条：
    download 计数、41 次限频、大小写共享缓存、非 API host 不计。
  · M2 补丁写并发：`enable/disable/delete/importPackage` 的"读判据 → 写 config / 移目录"原来不是
    事务（并发 enable 会丢一条；delete 与 enable 竞争会留下指向中转站记录的启用表）。
    修：`Patches.OPS` 进程级写锁（enable/disable 只包"读 refs → 写回"段，WriteGate 判据仍在
    方法体内 —— 写入口门禁元断言要求）。自检 +1 条：并发启用同一条 ⇒ 恰好一次成功、引用只出现一次。
  · M3 并发下载同名互踩：`UpdateDownload` 的 .part / 成品名固定 ⇒ 两个 Task 互相删/覆盖。
    修：每次下载一个**独立子目录** `downloads/<一次性 id>/<文件名>`；失败/取消连空目录一起收。
  · M4 读失败当 0：`patchCount` / `backupCount` 在库读不出来时会被写成 "0"。
    修：`Patches.storeReadable` + 备份目录 listFiles 可读性先判，读不出来就不放键；
    自检新增"加键与宿主计数一致"，并让 `patchCount` 在启用后必须为 1（非零也有分辨力）。
  · M5 正文超限 code 不对：`fetchText` 把 body too big 折成 network。修：识别后给 `size`；自检 +1。
  · M6 导入/删除没有前台服务：`patchImport`（最多 256 MB）与 `patchDelete`（跨目录移动）现在
    `Work.begin/end` 包住；导入开头清上次残留的临时 zip（半路被杀不再攒垃圾）。
  · M7 读失败折成 not-found / record_gone / no_match：`hasLaunchItem` 改三态（null = 读失败 ⇒ io）；
    `apkShaOfVersion` 读失败 ⇒ null（空串只表示"没有这个版本"）；补丁库不可读 ⇒ io。
  · M8 自检假绿：实例清单用 `Instances.testSnapshot` 注入"真实例 + 启动占位"（两态都钉）；
    删除恒真断言；versions 加键与宿主数据交叉核对；补丁写补 7 条负例
    （not-found / record_gone / no_match / missing / game_alive / enabled / in_use）+ 并行用例。
  · M11 trash() 变慢：`Trash.list` 对目录形态条目会递归量体积 ⇒ 新增 `Trash.listLite` /
    `parseLite`，插件 `trash()` 不再递归量（体积/文件数给 0 = 这次没量，文档已写）。
  · M13 预检顺序与 enable 不完全一致：改成 gate → filesOk → APK 匹配 → 已启用 → 冲突
    （与 `Patches.enable` 对齐）。
  · M10/M16 文档：PLUGIN-API 的失败码表、download 落点与"大小边下边查"、`game_alive` 与旧
    `game-running` 同义、`backupCount` 读失败缺键、"当前 38"→39、Cancel 延迟说明已同步。

二、构建与冒烟
  · 产品版 `bash ./build.sh` ⇒ `== BUILD OK ==`
    （content gate OK / write-gate OK：36 entries gated, 9 global gameAlive, 12 deletion files,
    1 killBackgroundProcesses）。
  · 接口变更冒烟（项目规矩）：`bash tools/sample-smoke.sh` ⇒ `== SAMPLE SMOKE OK ==`
    （sample 插件装进 dev 宿主、重启后进程在、无 FATAL）；冒烟后已把 sample 插件从设备清掉
    （active/known/pending/levels/injected/trust 的对应行 + 插件目录），设备恢复 4 台原有插件。

三、真机状态
  · dev 变体（DEBUGGABLE=true SUFFIX=-dev）；当前槽仍是「预览验收」；无自检夹具残留；
    无 FATAL EXCEPTION；plugins 目录 = mapstats / mods-health / usagestats / probe（dev 夹具）。

四、没做的（按设计文档）
  · v40 插入能力包：place 锚点 / api.ui() 宿主组件 / showCard / openPage / version·patches·resources
    三个页面钩子。
  · 信息面 P1：slots() 统计 / storage() / patchProblems() / plugins() 逐版本列表。
  · 网络增强：自更新与插件共用"同一份请求记账"当前只做到"同一份缓存 + 插件侧限频"；
    真 GitHub 网络下载只在 dev 手验（自检里不拉真网络）。
