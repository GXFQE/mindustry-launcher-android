第 374 轮 · 插件接口 v40 —— 插入能力（place=actions / showCard / openPage / 三个页面钩子）（2026-11-13）

设计：docs/plans/2026-11-13-插件平台第三批-网络-补丁写-插入能力.md（§4 插入能力的第一半）
接口文档：docs/PLUGIN-API.md（区间 20..40、「插入位置（v40）」「插入能力（v40）」、v40 逐版记录）

一、自检 selftest-374.txt
  通过 2201 项，失败 0 项（第 3 行，跑于 2026-11-13 20:26:01，dev 变体 / 真机 ELP-AN00）；
  基线 2193（第 373 轮）→ +8 条。新增的尺子：
  · v40 签名：`showCard(String, String, String[], Card)` / `openPage(String, String)`；
    `PluginApi.Card.onCard(int)`；`DOC_VERSION == PluginHost.API == 40`。
  · place 解析（Extensions.parse）：`"place": "actions"` 认；`"Actions"` 大小写归一；
    认不出的值回落空串；`section` 不参与"插操作卡"；`page` 不匹配的不进这一页。
  · 锚点判据：`PluginEntries.hasActionAnchor` 只认四个内容页（maps/saves/mods/blueprints），
    其它页（main/version…）没有锚点 ⇒ 回落「插件」卡（不会消失）。
  · 页面白名单：version / patches / resources 三个新 page id 认得了。
  · openPage 映射（`PluginPages.intent`）：maps → MapsActivity + slot extra；main 不带 slot；
    slot → SlotActivity + slot extra；patches / 认不出的名字 ⇒ null（静默跳过）。
  · showCard 无 Activity 上下文（自检是 application context）⇒ 回调 `onCard(-1)`，不会永远等不到。

二、真机验收（dev 变体；样例插件演示 v40 三件事；证据 = 本目录 ui-*.xml）
  · **place=actions**（ui-maps2.xml）：地图页操作卡里出现两行样例动作
    （「样例：导出清单…」/「样例：宿主卡片…」），位置在「删除…」**之前**、与宿主动作同卡同线；
    mapstats 的旧式动作「导出统计…」（没有 place）仍在下面那张「插件」卡里 —— 两种落点同屏可分。
  · **页面钩子**（ui-version4.xml / ui-patches.xml / ui-resources2.xml）：样例插件声明了
    version / patches / resources 三个 section；真机这三页的 `plugin_section` 都出现且标题/正文
    分别是「样例：版本页钩子」「Sample plugin · version hook」等（resources 页要滚到底）。
  · **showCard + openPage**（ui-card.xml + ui-logs.xml）：点地图页「样例：宿主卡片…」⇒ 宿主卡片
    （card_stack_title「宿主卡片样例」+ 正文 + 两个按钮，无叠窗）；点「打开日志页」⇒
    topResumedActivity = LogActivity（openPage 映射真跑通）。全程 logcat 无 FATAL。
  · 收尾清场：样例插件已从设备删除（目录 + 注册表行），当前槽仍是「预览验收」，4 台插件，
    无自检夹具残留、无 FATAL。

三、构建与冒烟
  · 产品版 `bash ./build.sh` ⇒ `== BUILD OK ==`（content gate / write-gate 全过）。
  · 接口变更冒烟：`bash tools/sample-smoke.sh` ⇒ `== SAMPLE SMOKE OK ==`
    （样例插件装了、重启后进程在、无 FATAL）；跑完已清场。

四、范围与边界（写进 PLUGIN-API.md，避免下轮误解）
  · 只开放四个内容页的操作卡锚点；其它页写 `place: "actions"` 回落「插件」卡。
  · `section` 不支持 place；摘要卡 / 详情页的 `"summary"` / `"detail"` 锚点、`api.ui()` 宿主组件
    工具箱、声明式 UI / 虚拟组行**还没做**（plan §4 剩余部分）。
  · openPage 不开放版本页 / 补丁页（要版本 key，对外接口不带）；认不出的名字静默。
  · 不给插件"换宿主主题 / 改宿主布局"的能力（有意边界）。
