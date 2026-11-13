第 373 轮 · 插件接口 v39 —— 网络 / 补丁写 / 实例与备份（2026-11-13）

设计：docs/plans/2026-11-13-插件平台第三批-网络-补丁写-插入能力.md（v39 已落地，v40 插入能力包待办）
接口文档：docs/PLUGIN-API.md（区间 20..39、「网络与下载（v39）」「补丁写（v39）」、v39 加键表与逐版记录）
补丁流程：docs/flows/21-阶段-15-补丁.md F27.18

一、自检 selftest-373.txt
  通过 2179 项，失败 0 项（第 3 行，跑于 2026-11-13 19:40:40，dev 变体 / 真机 ELP-AN00）；
  基线 2135（第 372 轮）→ +44 条。新增/加强的尺子：
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
  基线 2135 → 2179 的数字与 evidence/round372/selftest-372.txt 对得上（+44）。

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
