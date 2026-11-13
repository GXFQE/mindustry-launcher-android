第 374 轮 · 插件接口 v40 —— 插入能力（place=actions / showCard / openPage / 三个页面钩子）（2026-11-13）

设计：docs/plans/2026-11-13-插件平台第三批-网络-补丁写-插入能力.md（§4 插入能力的第一半）
接口文档：docs/PLUGIN-API.md（区间 20..40、「插入位置（v40）」「插入能力（v40）」、v40 逐版记录）

一、自检 selftest-374.txt
  通过 2205 项，失败 0 项（第 3 行，跑于 2026-11-13 20:43:29，dev 变体 / 真机 ELP-AN00）；
  基线 2193（第 373 轮）→ +12 条。新增的尺子：
  · v40 签名：`showCard(String, String, String[], Card)` / `openPage(String, String)`；
    `PluginApi.Card.onCard(int)`；`DOC_VERSION == PluginHost.API == 40`。
  · place 解析（Extensions.parse）：`"place": "actions"` 认；`"Actions"` 大小写归一；
    认不出的值回落空串；`section` 不参与"插操作卡"；`page` 不匹配的不进这一页。
  · 锚点判据：`PluginEntries.hasActionAnchor` 只认四个内容页（maps/saves/mods/blueprints），
    其它页（main/version…）没有锚点 ⇒ 回落「插件」卡（不会消失）；**并 inflate 四个布局、
    断言 `row_maps_del` / `row_save_del` / `row_mod_del` / `row_bp_del` 真的在**（评审 M1）。
  · 页面白名单：version / patches / resources 三个新 page id 认得了。
  · openPage 映射（`PluginPages.intent`）：**11 个白名单页全部逐一对到正确的 Activity** 与
    slot extra（评审 m7）；**slot 空 / null ⇒ 补成当前槽**（评审 B1）；
    patches / 认不出的名字 ⇒ null（静默跳过）。
  · showCard 按钮归一化（纯函数 `PluginHost.cardButtonIndices`）：空串/null 跳过、最多 3 个、
    回调下标仍按**原数组**（评审 M3/m7）。
  · showCard 无 Activity 上下文（自检是 application context）⇒ 回调 `onCard(-1)`（投回 UI 线程），
    不会永远等不到。
  · 版本页 / 资源页的 `selectedItem()` / `pageItems()` 能拿到"这一份"（评审 B2/M4）。

一之二、独立评审后的修正（静态评审；2 BLOCKER + 4 MAJOR + 7 MINOR，按重要性修）
  · **B1 openPage 空槽**：`PluginPages.intent` 原来把 null / 空原样写进 slot extra，四个内容页
    收到空槽会**打开即 finish**（只有模组页回落当前槽）⇒ 改成"空 ⇒ `Data.currentSlot`"，
    自检补一条。⚠️ 之前 dev 验收口自己补了当前槽，正好绕过了这条 —— 已记进 ref/43。
  · **B2 版本页 item 永远 null**：`VersionActivity.mEntry` 只在 onResume→refresh() 赋值，而插件区在
    **onPostCreate**（更早）就挂了。修：onCreate 里先 `findEntry()`；`pluginSelectedItem()` 改用
    `PluginData.versionItem(key, slot)`（只组装这一条，不再 UI 线程扫全表 + 补丁库 + 备份）。
    真机证据更新（ui-version5.xml）：样例那行从「Sample plugin · version hook」变成
    「Sample plugin · version hook · 滚木  原端  159.7」——item 真的到了。
  · **M1 placed 行"回落不消失"**：原来"有锚点就把条目从「插件」卡删掉"，运行期插不上就彻底消失。
    修：`PluginEntries` 记录**实际插入的 key**，`attachPage` 只过滤真的插上的；插入失败整批回滚
    （条目回落「插件」卡）。`attachPlacedActions` 变幂等（先摘旧行，模组页换槽会重挂）。
  · **M2 placed 行槽过期**：行创建时捕进来的槽在模组页页内换槽后是旧的。修：点击时从
    `BaseActivity.pluginSlot()` 取（四个内容页本来就是 mSlot）；模组页 `rescan()` 里重挂一次
    placed 行（幂等）。
  · **M3 showCard 崩溃路径**：包 try/catch（转屏 / Activity 已销毁 ⇒ BadTokenException 不崩宿主，
    回调 -1）；`isDestroyed()` 二次判；按钮 handler 加 `picked` 闸门（连点两个按钮也只回一次）；
    同一页已有插件卡片时先 dismiss 旧的（不叠窗）；无 Activity 的 -1 投回 UI 线程。
  · **M4 resources 页"给 APK 文件"是死承诺**：PluginApi 没有 File 返回值。修：`pageItems()`
    按选中的 APK 反查版本条目（`versionItemByApk`）；`VersionOps.openResources` 带槽 extra、
    resources 页 `pluginSlot()` 用这个槽（原来猜当前槽）；文档改成"给 pageItems，不给 selectedItem"。
  · m3 布局：patches 页 include 去掉左右 14dp（父容器已有 page_pad，避免卡片窄一圈）；
    resources 页 include 移到 `page_bottom_space` **之前**（不再孤零零贴在滚动最底）。
  · m5/m6/m7 文档与自检：删掉死常量 `PluginPages.PAGES`；§1 逐版能力、§5 回调清单、
    §6"showCard / openPage 不返回 Result"、`place` 对其它 slot 不生效、按钮"前 3 个**非空**、
    下标按原数组"、点「关闭」也给 -1 ——全部同步；自检补 11 页映射 / 空槽 / 按钮归一化 / 布局锚点。
  · n5 死代码：删掉 `PluginEntries.attachAction` / `attachSection` / `attach` / `insertIndex`
    （v24 之后的旧路径，已无调用点），类注释改成 v40 说法。

二、真机验收（dev 变体；样例插件演示 v40 三件事；证据 = 本目录 ui-*.xml）
  · **place=actions**（ui-maps2.xml）：地图页操作卡里出现两行样例动作
    （「样例：导出清单…」/「样例：宿主卡片…」），位置在「删除…」**之前**、与宿主动作同卡同线；
    mapstats 的旧式动作「导出统计…」（没有 place）仍在下面那张「插件」卡里 —— 两种落点同屏可分。
  · **页面钩子**（ui-version5.xml / ui-patches.xml / ui-resources2.xml）：三页的 `plugin_section`
    都出现；版本页正文带**这一份版本的名字**（B2 修复的直接证据），补丁 / 资源页各一条。
    资源页 `plugin_section` 现在 x=46..1178（与别的页一致）、在底部留白之前；
    补丁页 x=52..1172（与它那一页的 page_pad 一致，不再窄一圈）。
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
  · `place` 只对 `action` / `content` 生效（`section` 是一整块；`plugins` / `analyzer` /
    `filter` / `sort` 静默按原行为）。
  · 摘要卡 / 详情页的 `"summary"` / `"detail"` 锚点、`api.ui()` 宿主组件工具箱、
    声明式 UI / 虚拟组行**还没做**（plan §4 剩余部分）。
  · openPage 不开放版本页 / 补丁页（要版本 key，对外接口不带）；认不出的名字静默。
  · 不给插件"换宿主主题 / 改宿主布局"的能力（有意边界）。
