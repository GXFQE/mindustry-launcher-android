# mapstats —— 官方插件（地图资源统计）

第 188 轮起从宿主 F21 搬出来的**第一个官方插件**。目标是把宿主那套"重解析器 + 页面"整块搬成插件，
顺手用真实功能给插件接口当活体用例。**v20（第 219 轮）起报 20**：插件编译时直接编宿主那份
`app/src/io/mdt/launcher/api`（工程里不再放 API 副本），也不再写兼容老宿主的 try/catch。

## 构建

```bash
ANDROID_HOME=D:/AndroidSDK bash plugins/mapstats/build.sh
# 产物：%TEMP%/mdt-mapstats/mapstats.apk（默认 debug 签名 = 与本地宿主同一把钥匙 ⇒ 判「官方」）
```

## 装到设备（dev 变体的直通口）

```bash
adb push %TEMP%/mdt-mapstats/mapstats.apk /sdcard/Android/data/io.mdt.launcher/hub/tmp/mapstats.apk
adb shell am force-stop io.mdt.launcher
adb shell am start -n io.mdt.launcher/io.mdt.launcher.MainActivity \
  --es dev_plugininstall /sdcard/Android/data/io.mdt.launcher/hub/tmp/mapstats.apk \
  --es dev_pluginver <唯一版本号> --es dev_pluginnow true
```

插件只声明一条**地图页动作行**（`page: maps`）：槽 → 地图 页里排在「导出地图…」下面、
「删除…」上面（第 157 轮定的顺序）。点进去 → 选图 → 后台统计 → 页面上给四段数字 + 可建造 / 核心区。

## 现在做什么 / 还没做什么

- ✅ 自带 `.msav` 解析（`core/` 是宿主那几个纯 Java 类的拷贝：`MapStats` / `MsavTiles` /
  `MsavPatches` / `MapStatsExport` / `Hjson` / `BundleNames` / `Colors` + 三张表）；只走 `PluginApi`：
  `pickContent` / `open` / `runBackground` / `progress` / `progressCard` / `theme` / `choose` /
  `shareText` / `export`（`After` 收 `Result`）/ `closePage` / `modsSkipped`。
- ✅ 数字与宿主对得上（真机 0.msav：地矿 2,595 / 可采地板 51,314 / 加成地板 118,873 / 可建造 31,083；
  埃里克尔槽：地矿 5 种 1,232 / 墙矿 4 种 846 / 加成 8 种 40,657 / 可建造 89,480 / 核心区 195·8 个 4×4）。
- ✅ **译名**：版本 APK 的 `bundles/` + 本槽模组的 `*-bundles/`（与宿主同源，`MapStats.readBundles`）；
  🔴 **只认本槽指向的版本 APK**（没有就按内部名，和宿主一致 —— 拿别的版本兜底会把矿层墙矿与地矿
  翻成同名、被合并且少两行）。
- ✅ **模组叠加**：只用宿主给的事实（`ItemInfo.extra` 的 `enabled` / `willFailJavaLoad` / `rootPrefix`
  + `api.modsSkipped()`，v8），调 `MapStats.overlayMods` 建有效内容表；判据不重写。
- ✅ **星球**：地图条目自带 `extra["planet"]`（宿主从 `tags.rules` 抠的）⇒ `analyze(..., planet)`，
  核心区边长按星球（塞普罗 3×3 / 埃里克尔 4×4），依据那行与宿主同一句话。
- ✅ **导出**：单张 = `.json`（`jsonDoc`）、多张 = `.jsonl`（`jsonlBatch`，一行一张图）、
  文件名 `mdt-stats-<名>-<时间>.json[l]`、mime 跟着走、声明里有槽 / 图名 / sha256 / 星球 /
  版本 APK / 模组数 / 内容表行数；真机两张导出 = 恰好 2 行。
  ★ **简洁模式**的 `buildable` 写 **`{"count": N}` = 能放一般建筑的格数**（与界面上「能放一般建筑 N 格」
  同一个数，`0.msav` 真机 = **31,083**）；**不是**六桶之和（那个数 == 整张图的格数，350×350 ⇒ 122,500，
  只在**详细**模式里当 `sum` 写）。2026-10-27 用户抓到过这个错：「简洁的可建造面积数据是不是导出成了总面积」。
- ✅ **宿主那份 F21 已删**（第 192 轮）：详情页统计段、地图页「导出统计…」、`StatsExporter`、
  `MapStatsExport` 全没了；`MapStats*` 与三张表**留在宿主**（蓝图那条线在用，不是 F21 专属）。
- ✅ **插件侧自检**：`bash plugins/mapstats/build.sh check`（纯 Java / PC 跑，20 条：内容表 / 遮挡口径 /
  核心区+星球 / 单张·多张·简洁导出 / 模组叠加 / pretty）。
  ★ 其中一条是「简洁模式的可建造 = 60（不是总面积 96）」—— 夹具刻意选 12×8 但只有 60 格能建，
  **两个数不同才有分辨力**（旧断言写的是 `sum == cells`，等于把 bug 钉成了预期）。


## 第 193 轮：把「点开地图就看见数字」还回来

用户 2026-10-20 的原话：「感觉体验比原来差好多了」「我是希望能和原来效果一样」——
统计搬成插件后变成了「在地图页点一行 → 跳插件页 → 选图 → 等」，比搬家前多两跳。

- 宿主加 **v9**：`PluginApi.selectedItem()`（详情页当前这一项，游戏 / 模组包里的也拿得到）
  + `section` 插槽（插件把一段界面**画进宿主页**，而不是另开一页）。
- 本插件声明两条：`maps` 页的**动作行**（批量统计 / 导出，仍在）+ `map-detail` 页的**段落**
  （详情页里直接出卡片，与搬家前同一个位置、同一种卡片）。
- 段落里**没有**标题 / 返回 / 分享按钮（与搬家前的详情页一致），只有「用时 N 毫秒」+ 卡片；
  图名不重复（顶栏已经写着）。
- 判据：槽内地图（`Editor Playtesting.msav`，586×586 · erekir）与游戏自带地图（`111.msav`，
  600×600）都在详情页里直接出「地矿 / 墙矿 / 加成地板 / 可建造 / 核心区 / 技术细节」六张卡，
  用时 310 ms / 10 ms；宿主自检 **1530 / 0**（新增 v9 段落插槽一条）。

## 第 281 轮：按统计结果**排序 / 筛选**（地图页，v25 注入）

用户最初的问题：「地图排序和筛选能不能按照统计结果排」。宿主那边第 280 轮已经把 `filter` / `sort`
的结果层统一（`PluginPicks`，四个列表页一套），这一轮插件侧接上：

| 声明 id | 类型 | 做什么 |
|---|---|---|
| `mapstats-sort-reach` | sort | 按**可采资源**（矿石 + 矿墙 + 可采地板三段的"拿得到"格数）多 → 少 |
| `mapstats-sort-build` | sort | 按**能建格数**（`Buildable.count()`，与详情页同一个数）多 → 少 |
| `mapstats-filter-core` | filter | 只看**有核心区**的图（`core-zone` 地板格数 > 0；`Core.skip` 不算） |
| `mapstats-filter-thorium` | filter | 只看**采得到钍**的图（内部名匹配 `thorium` / `ore-thorium` / `ore-wall-thorium`） |
| `mapstats-filter-tungsten` | filter | 只看**采得到钨**的图（埃里克尔顶级矿） |

- 入口类一插件一份（`SortReach` / `SortBuild` / `FilterCore` / `FilterThorium` / `FilterTungsten`），
  每个都 `api()` 报 32（宿主在被加载的那个类上判版本）；判据在 `core/StatsPick`（纯 Java，PC 可测）。
- 🔴 **必须自己缓存**：宿主排序会调 `compare()` O(n log n) 次、筛选每项一次 —— 每次重解一张图
  会被解上千遍。`StatsIndex` 按身份缓存，并写盘：真机 114 张图 **冷 2.8 s / 热 0.7-1.1 s**。
- 🔴 **缓存键**：槽 + 「这次加载不加载模组」+ 每个模组的路径/大小/修改时间/**启用状态** + `FMT`；
  每条再带图的 `size` + `mtime` ⇒ 换槽 / 开关模组 / 换图都自然失效。
- 统计口径**复用** `MapStatsPage.prepare` + `MapStats.analyze`（与导出、详情页同一份内容表与同一条算法）
  —— 排序用的数与点开看到的数不会差。
- 真机（槽「预览验收」，本槽 0 张 + 游戏自带 114 张）：
  `plugin filter …-core @maps: 45/114`、`…-thorium: 70/114`、`…-tungsten: 16/114`；
  按可采资源排第一的是 **13.msav**，详情页 2,602（地矿）+ 25,261（可采地板）= 27,863 格。
  截图 `evidence/round281/`。
- 插件侧自检：`build.sh check` **20 → 31 条**（新增 11 条：可采资源口径 / 含某矿 / 核心区 /
  两个排序模式结论相反 / 读不出来的垫底 / 缓存里的短名反查）。

## 第 282 轮：二级选择 —— 「按某矿含量排 / 只看含某矿」（接口 v33）

用户看了上一版：「建议折叠一下选择」→ 追问后定案：「改成二级对话？先选类型（比如按照某矿是否包含筛选 /
某矿含量排序），然后选择具体的矿」。于是把写死的「含钍 / 含钨」两条换成**可选的某矿**：

| 声明 id | 类型 | 第一级 | 第二级 |
|---|---|---|---|
| `mapstats-sort-ore` | sort | 按某矿含量（多的在前）… | 选矿（铜 / 铅 / 钍 / 钨 / 铍 / 废料…） |
| `mapstats-filter-ore` | filter | 只看含某矿的图… | 同上 |

- **宿主新增接口 v33**（`docs/PLUGIN-API.md`）：入口类可选实现
  `static String[] options(PluginApi)` + `static void choose(int, PluginApi)`；宿主用 `CardStack`
  弹第二级列表，**在同一份已加载实例**上先 `choose` 再跑 `accept`/`compare`（插件用静态字段记选择）。
  取消 / 返回键 ⇒ 宿主关掉插件会话，一次都不跑。
- 选项从**有效内容表**推（`Def.isOre` 的行，按物品名取标签）⇒ 模组加的矿自然出现，不写死清单；
  标签过宿主闸门（按码点 24 字 / 最多 24 项）。
- 选中后会缀在标题上：`✓ 只看含某矿的图…（钍）` —— 打勾只说明"筛过"，得看得出筛的是哪一矿。
- 缓存字段随之升级（`FMT=2`）：`ores` 从"有没有"变成 `内部名:格数;…`，按某矿排 / 筛才有数可算。
- 真机判据（槽「预览验收」114 张）：选**钨** ⇒ `@maps: 16/114`、选**钍** ⇒ `70/114`（与上一版写死的
  两条结果逐字相同 ⇒ 说明"标签 → 内部名"的映射没漂）；按**钍**排首位 `atlas.msav`、按**钨**排首位
  `127.msav`、按可采资源排首位 `13.msav` —— 三个排序各自不同。
- 插件自检 **31 → 34 / 0**（每种矿各自格数 / 缓存的 `内部名:格数` 写法 / 按某矿排的两种结论 /
  选项列表来自内容表且标签与短名一一对应 / 没有表 ⇒ 空选项）。
- 宿主自检 **1678 → 1683 / 0**（v33 + 选项闸门四条：去空白保序 / 全空 ⇒ null / 裁 24 字 / 最多 24 项）。

## 第 285 轮：按**可建核心数**排序 + 两处修正

用户：「新增一个按照可建最小核心数量排序」。

- 新 sort 行 `mapstats-sort-cores`「按可建核心数（多的在前）」= 核心区里**最多能摆几个该星球最小的核心**
  （塞普罗 3×3 / 埃里克尔 4×4）。这个数就是 `MapStats.Core#max` —— 与详情页
  「核心区 223 格 · 最多能摆 14 个核心（3×3）」里的 **14** 同一个字段，没有另算一套。
  真机（槽「预览验收」114 张）：首位 `stronghold.msav`（14 个），其后 `245` / `27`（10 个）、
  `origin`（9）、`242`（8）—— 与缓存里算出来的数逐个对得上。
- 缓存跟着多一列 `cores`（`FMT` 2 → 3），老缓存整份作废。
- 🔴 **顺手修掉一个老 bug**：`MapStatsPage` 把空 planet 折成 `null` 再喂 `analyze`
  ⇒ 塞普罗（存档里没写 `planet` 键）的图在**详情页整张「核心区」卡都不出现**，而排序那条路
  （`StatsIndex` 传的是空串）算得出数 —— 同一张图两个答案。口径按 `MapStats` 里写的那样：
  **空串 = 塞普罗**、`null` = 调用方没线索（才是不适用）。插件自检加了正反两条钉子。
- 插件自检 **37 → 39 / 0**（多一条 `cores` 排序；`""` 与 `null` 各一条）。
- 宿主侧同一轮修的是**两个勾同时亮**（见 `next/82`）。

## 第 295 轮（插件 v5）：含某矿筛选支持**勾好几种**

- 用户要求：「让插件也用这种形式（或者改一下原来那个机制允许多选）」。
- 插件声明了 v36 的两个静态约定：
  ```java
  public static boolean multi(PluginApi api) { return true; }
  public static void choose(int[] idx, PluginApi api) { ... }   // 升序、去重
  ```
  宿主就把那一屏换成**勾选框列表**（与界面里「来源 / 类型」同一套控件），勾几个回传几个。
- 判据放在 core：`StatsPick.Snap#anyOre(Collection<String>)` —— **任一种采得到就算通过**（几种之间是"或"）。
  插件自检加了两条（`{copper,tungsten}` 有钨就过；空集合不通过），共 **41 / 0**。
- 真机（槽「预览验收」114 张）：勾「钍」+「钨」⇒ `plugin filter … 79/114`，与缓存离线算的
  `tungsten 16 ∪ thorium 70 = 79` **逐个吻合**；行上显示「只看含某矿的图…（钍、钨）」。
- `version` 4 → 5。
