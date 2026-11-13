第 375 轮 · 插件接口 v41 —— 插入能力包补齐（宿主组件工具箱 / 打开自己的页 / place=summary·detail）（2026-11-13）

设计：docs/plans/2026-11-13-插件平台第三批-网络-补丁写-插入能力.md（§4.2 插入能力包）
接口文档：docs/PLUGIN-API.md（区间 20..41、「插入能力（v40）」「宿主组件工具箱（v41）」、v41 逐版记录）

一、自检 selftest-375.txt
  通过 2212 项，失败 0 项（第 3 行，跑于 2026-11-13 20:58:58，dev 变体 / 真机 ELP-AN00）；
  基线 2205（第 374 轮）→ +7 条。新增的尺子：
  · v41 签名：`ui()` 返回 `PluginUi`（8 个零件方法逐个反射取 + 返回 View）、`openPluginPage()`；
    `DOC_VERSION == PluginHost.API == 41`。
  · `place` 解析：summary / detail / actions 三种都认，各进各的位置、不串页。
  · 锚点表：summary = 主界面 / 槽列表 / 四个内容页（main / slots / maps / saves / mods / blueprints）；
    detail = map-detail / blueprint-detail；其它页没有锚点 ⇒ 回落「插件」卡。
  · 锚点 View **真的在布局里**（inflate 六个页面断言 status_box / saves_summary / maps_head /
    save_head / mods_status_box / bp_head；再 inflate 两个详情页断言 detail_text / bp_detail_text）。
  · `PluginUiHost` **在主线程真造八个零件**：卡片竖排 / 行动作文字 / 键值两列 / 按钮文字 /
    小标题与正文 —— 不是只看类在不在。
  · 顺手修：`SelfTest.newPContext` 的反射夹具跟上 PContext 新签名（v41 加了插件身份三参数）。

二、真机验收（dev 变体；样例插件演示 v41；证据 = 本目录 ui-*.xml）
  · **place=summary**（ui-maps-summary.xml）：地图页 `maps_head`（页头）下面出现一张宿主卡片，
    里面一行「样例：摘要行…」；位置在页头与操作卡之间（y 456，操作卡从 647 开始），
    与「插件」卡里的旧式条目分得开。
  · **place=detail**（ui-map-detail.xml）：打开一张**游戏自带**地图（宿主的 `detail_actions`
    卡在这种项上是 GONE 的）—— 详情正文下面出现「样例：详情页动作…」自己的卡；
    这条用例正是"插进宿主删除卡"会踩的坑（见下「实现时改的判据」）。
  · **api.ui()**（ui-map-detail.xml）：同一页的 map-detail section 用宿主零件拼出来 ——
    「Sample plugin」小标题 + title / size / head 三行键值（值分别是 202.msav / 19063 B /
    789ccdbd69775c47），字号颜色与宿主卡片一致；底部一个宿主主按钮「Open plugin page」。
  · **openPluginPage()**（ui-open-plugin-page.xml）：点那个按钮 ⇒ topResumedActivity =
    PluginsActivity，页内「已加载 sample（接口版本 41）」+ 插件自己画的那一页。
  · 收尾清场：样例插件已从设备删除（目录 + 注册表行），当前槽仍是「预览验收」，4 台插件，
    无自检夹具残留、无 FATAL。

三、构建与冒烟
  · 产品版 `bash ./build.sh` ⇒ `== BUILD OK ==`（content gate / write-gate 全过）。
  · 接口变更冒烟：`bash tools/sample-smoke.sh` ⇒ `== SAMPLE SMOKE OK ==`；跑完已清场。

四、实现时改的判据（写进 PLUGIN-API.md，避免下轮误解）
  · `place: "detail"` **不插进宿主的 `detail_actions` 卡**：那张卡在"游戏自带 / 模组自带的地图"上是
    GONE 的（没有删除入口）⇒ 插件行会跟着消失，点开还会把空的删除行带出来。改成详情正文之后
    **自己一张卡**（对两种来源都成立）。「没有锚点就回落」这条总原则不变。
  · `place: "summary"` 的锚点 = 已有页头 / 摘要卡的**后面**（主界面 status_box、槽列表 saves_summary、
    四个内容页 *_head）；卡片左右留白按页的根容器有没有横向内边距分两档（有 ⇒ 不再加 14dp）。
  · `ui()` 的宿主 Context 取 **Activity（有页面时）/ application（没有时）**，绝不用插件的
    `PluginApi.context()` —— 那个带插件主题，造出来的零件又会跟宿主不一样。
  · `openPluginPage()` 的插件身份从 APK 路径反推（父目录名 = id、文件名 = 版本），不新增接口参数。
  · 只做"一行"形态的 place（action / content）；`section` 仍不支持（它画的是一整块）。
