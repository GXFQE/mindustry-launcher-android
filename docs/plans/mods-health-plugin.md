# 模组体检全部搬进一个插件（宿主给事实 / 解析 / 插槽）

> 2026-10-24 第 229 轮。用户两轮口径：
> ① 「把所有关于模组存在问题（含模组冲突，结构不对，文件错误，是否开关等各种情况）相关的东西都放
> 一个插件里，宿主只给接口」；
> ② 「筛选排序需要允许插件注入自己的排序和筛选方式」/「设置类的读写和改动应该已经有了」/
> 「HJSON 解析功能需要宿主实现，但具体使用可以交给插件」/「模组分类留在宿主内也不是不行
> （这样 mod.hjson/mod.json 也可以给插件接口），但文件错误（zip 损坏、不是游戏模组）纯宿主也会报，
> 插件负责深度检测以及启停控制」。
>
> 状态：**P0~P6 已落地（2026-10-24，接口 v25~v28）**。
> **已归档（2026-10-25）**：插件本体在 `plugins/mods-health/`，接口见 `docs/PLUGIN-API.md`；本文件只作设计留档。
> 同日补（第 230 轮）：六道门的判据搬进插件 `core/Gates.java` 并补了 PC 侧尺子（插件 check 41 → 69），
> 见 `next/47` §十九。第 232 轮把接口常量收口到 **`PluginHost.API = 27`**（插件 `api()` 同步 27，
> 区间 **20..27**）。

## 一、分工（用户第二轮定的边界）

| 归宿主（事实 / 解析 / 渲染骨架 / 写盘） | 归插件（深度判断 / 文案 / 视图 / 启停决策） |
|---|---|
| 包发现、**分类**（Java / JS / 数据 / 目录 / 非模组） | **深度检测**：依赖、能不能加载、脚本是否生效、冲突、结构问题 |
| **文件级错误**：zip 损坏、不是游戏模组、没有 meta | 这些错误"对用户意味着什么、怎么修" |
| **HJSON / JSON 解析器**（插件通过接口用，不自己实现） | 拿解析结果干什么（判据全在插件） |
| 排序 / 筛选的**框架**（列表、菜单、状态） | **注入自己的筛选与排序**（判据 + 标题） |
| `settings.bin` 读写（v21 已有：`gameSettings` / `writeGameSettings`）+ 安全门限 | **启停决策**（该关谁、连锁关哪些、什么时候建议） |
| 写盘：备份 / 原子写 / 读回自检 / 失败还原（**永不外放**） | —— |
| 导入 / 导出 / 删除 / 中转站 / 页面骨架 | —— |

★ **备份类接口暂不给**（用户：「除非备份」）。

## 二、接口草案（v25）

### 2.1 行上结论：`Finding.anchor`

```java
public final class Finding {
    public Finding(String title, String detail, int severity, String... evidence);
    /** 挂到某一项上；null（默认）= 进「插件」卡（今天的 analyzer 行为）。 */
    public Finding anchor(String itemPath);
}
```
宿主渲染规则：
- `anchor != null` ⇒ 挂到那一行的徽标 / 详情「问题」小节；同一项多条 ⇒ 行上只显示最严重那条，
  其余进详情；
- `anchor == null` ⇒ 进「插件」卡（整槽结论沿用"第一项那一次回调"的约定）。

### 2.2 注入筛选 / 排序

声明（`plugin.json`，与 `extensions` 并列）：

```json
"views": [
  {"slot":"filter","page":"mods","id":"conflict","title":"有冲突"},
  {"slot":"sort","page":"mods","id":"risk","title":"按风险"}
]
```

回调（`entry` 指向的类里的静态方法，宿主按 `slot` 找）：

```java
public static boolean accept(ItemInfo item, PluginApi api);         // filter
public static int compare(ItemInfo a, ItemInfo b, PluginApi api);   // sort（<0 靠前）
```
宿主侧：筛选菜单里多出一段「插件」；排序菜单同理。选中后才调用（**不选不跑**），
并且**结果顺序就是最终顺序**（宿主的搜索/其他筛选先做，插件筛/排最后做）。

### 2.3 解析器给插件用

寄生在 `PluginApi`（宿主实现，插件只调）：

```java
/** 把一个 .hjson/.json 文件解析成普通 Map/List（失败返回 null，不抛）。 */
public Map<String,Object> parseHjson(File file);
```
★ 模组包里那份 meta（`mod.hjson` / `mod.json`）**已经在 `ItemInfo.extra` 里**（宿主解析好的），
插件优先用 extra；`parseHjson` 是给"要看包里别的 JSON/HJSON"的深度检测用的。

### 2.4 启停

不新增接口：`gameSettings()` / `writeGameSettings()`（v21）已经能读改 `mod-<名>-enabled`
（宿主的备份 / 原子写 / 读回自检 / 失败还原都在这条路上）。插件负责**决定**改哪些，
宿主负责**怎么写**。

### 2.5 `extra` 要补的事实（逐项核过再定）

`dependencies` / `softDependencies` 目前是**拼好的字符串** ⇒ 改成 `deps.0` / `deps.1`…（或给 JSON 数组
风格的 `deps` 串）；另加 `blacklisted`、`metaErrorCode`（码，不给原文）、`duplicated`、
`ignored`（游戏不加载）、`failedRecord`（`-failed`）、`launchIdExists`（槽级）。

## 三、现状（要搬的判断在宿主哪）

1. `Mods.Scan.problems`（重复包）/ `ignored`（不加载）/ `orphanKeys`（孤儿开关）；
2. `Mods.Info` 派生：`willFailJavaLoad` / `jsCount`·`hasScripts` / `hidden` / `minGameVersion` 比较 / 依赖解析 `states`；
3. `ModsText`：`stateLabel` / `badgeSaysIt` / `cardNote` / `infoMetaReason` / 各种 warn 行；
4. `ModsActivity` 详情：六道门（**已搬** —— 插件 `core/Gates.java`，宿主只剩绘制）与依赖链文案；
5. 「只看有问题的」筛选（`Mods.isProblem`）；
6. 冲突检查（**已搬**，`plugins/mods-health`）。

**留下**：分类与文件级错误的**判据与文案**（用户明确说这两类宿主也会报）。

## 四、一个必须先解决的技术点

analyzer 今天是**列表渲染完之后异步跑、只往卡片里塞**。行徽标 / 筛选都要**渲染前**的结果 ⇒
P1 要把插件调用提到"扫描后、绑定前"，一份结果给三处（行、详情、筛选）用，**不许调三次**。
插件不在 / 崩了 ⇒ 这份结果为空，卡片与门**整块不显示**。
2026-10-25 用户定：**不做**「没装体检插件」提示（空态即静默）。

## 五、分步

| 步 | 内容 | 完成判据 |
|---|---|---|
| P0 ✅ | 单插件成形：`plugins/conflict` → `plugins/mods-health`（id 一起改），冲突检查并进去 | 真机冲突结论不变；插件页名字对得上 |
| P1 ✅ | v25 接口：`Finding.anchor` + filter/sort 注入 + `parseHjson` + extra 补字段；宿主渲染（行徽标 / 详情 / 筛选 / 排序） | 真机：Neon 行出现「⚠ 里已经带了 Logic Sugar」；筛选「有冲突的模组」= 2/13；排序「按冲突风险」把它俩排到最前；自检 1548/0 |
| P2 ✅ | 搬"能不能加载 / 脚本不生效"两条（**解析失败归宿主** —— 用户定的"文件级错误纯宿主也会报"） | 判据进 `Conflicts.scan`（PC 自检 19 条）；文案进插件、挂行上；宿主删掉那两句；真机两例都对 |
| P3a ✅ | 搬**依赖三态 / 重复包 / 黑名单**：宿主把 `depState`／`duplicated`／`blacklisted` 当**事实**给插件，文案与"缺哪一条/重的是哪份"在插件 | PC 自检 25/0；真机三例（重名两份、缺依赖、黑名单）都对 |
| P3b-槽级 ✅ | **孤儿开关**搬进插件（`Conflicts.orphanKeys`）；**整槽跳过也归插件**（v27 `Finding.summary()`，结论归插件、**位置在摘要卡**），宿主保留回落 | 插件 check 41/0；真机摘要卡优先用插件结论、空才用 `ModsText.cardNote`。**崩溃记录不归这个插件**（归因需要独立插件 + 插件间接口，用户 2026-10-24 定） |
| P3c-徽标 ✅ | 行徽标也交给插件（v26 `state()`：固定词表 token），宿主映射回 `Mods.State`；插件缺省才回落到宿主自己的判定 | 真机 `plugin verdicts: 13`；no-version 夹具徽标仍「版本不符」 |
| P4 ✅ | 详情页六道门结构化（插件给 `List<Gate{label,pass,note}>`，v27） | 六道门逐条与搬家前一致；判据在 `core/Gates.java`、PC 自检逐道验（第 230 轮补，插件 check 69/0）；真机「❌ 版本门槛太低」那组逐字验过（见 `next/47` §十九） |
| P5 ✅ | 导入时也用同一套判据（**只警告、不拦**；单份 + 批量一起）：单份在结果弹窗加「体检（插件）」段，批量在报告末尾补「体检 · 文件 · 结论」；**拦截**仍归宿主（文件级损坏 / 同名替换 / 写盘） | 判据**复用** `PluginAnalysis.anchoredByPath`（整页 analyzer 那一遍，按 `anchorPath` 挑这一项），宿主不再自己判依赖/结构/版本；插件在时**版本兼容那句由插件说**（避免两种说法）；插件没装 ⇒ 整段不显示（不是"没问题"）。验证见 `next/47` §二十二 |
| P6 ✅ | **两个不同模组都覆盖原版内容**（第 234/235 轮）：`sprites-override/` 同名路径 + `bundles/*.properties` 里**原版真的有的**键 + `content/<类型>/**.json` 里**不带 `type`** 的同名**内容补丁**（第 235 轮补，改数值最常见）；两边各一条 | 判据在 `core/Overrides.java`（纯 Java）；文案/内容的"原版"事实由宿主 v28 `baseBundleKeys()` 推；排除重名 / 内置；递归 + 新旧文件夹名 + `rootPrefix`；PC 自检 +11（含顶层 `type` 不算补丁、名字不在原版清单不算）。真机夹具：A/B 撞 1 内容补丁，C 另打一个 ⇒ 不报 |

## 六、不做什么

- 不把**列表页**整个交给插件（分类 / 文件错误 / 导入导出 / 删除 / 写盘的安全门限留在宿主）；
- 不让插件写 `settings.bin`（写侧永远是宿主；插件只决定改哪些键）；
- 不做"插件给类名、宿主按类名判语义"这类硬编码（`ref/43` 第 77 条）。
