# 游戏监测与插件探针（设计备忘）

> 本文件只记**已敲定、还没实现**的设计。已实现的部分在 `docs/PLUGIN-API.md` 第 9 节，
> 这里不再复述；实现一项就从本文件移走。换方向前先读这里。

## 前置：已经能用的部分

- `UsageLog` 在 **`:game` 进程**记录启动 / 心跳 / 结束 / 崩溃 / 熄屏，只记"下界"；
- 两条流：`hub/usage/launch.tsv`（启动事实，写完不可变）与 `run.tsv`（运行期事实）；
- `PluginApi` v18 = `File usageDir()`；**v20（2026-10-23）= 整理后的冻结基线**（见 `docs/PLUGIN-API.md`）。
  字段、真机产物与读取约定见 `docs/PLUGIN-API.md` 第 9 节。

## 已定、未实现

### B0. ~~游戏设置面~~ ✅ 已实现（v21，第 220 轮）

`gameSettings()` / `writeGameSettings()` 已进接口面（`API = 21`、`API_MIN = 20`）：
宿主只给 `settings.bin` 的解析结果 + 带护栏的写（游戏在跑拒绝、备份、原子写、读回自检还原），
键的含义 / 缺省值 / 成对写归插件。契约与真机验证见 `docs/PLUGIN-API.md` §4「游戏设置」与 `next/42`。

### B1. 统计插件 `usagestats`
宿主只"记"，插件只"看"：读 `usageDir()` → 启动次数 / 版本分布 / 会话时长 / 崩溃 / 熄屏 → 柱状图 + CSV/PNG 导出。

### B2. 探针（性能数据）
- **A′（宿主持有探针表 + 声明式聚合）已放弃**：每次加指标都要改宿主发版，扩展性差。
- 改走 **D：插件自定义探针**，宿主只做机制（2026-10-23 定）：
  ```java
  interface Probe {
      String name();
      void reset(java.io.File dir);   // 宿主给的本会话 telemetry 目录
      void sample();
      void summary();                 // 可选：会话结束前收尾
  }
  ```
  插件 `plugin.json` 声明 `telemetry.probes: [{class, sec, inProcess}]`；宿主在对应进程里
  `DexClassLoader` 只加载探针类，定时 `sample()`，会话结束前 `summary()`。
  **数据由插件自己写**（写进 `dir`，格式插件定）—— 宿主管目录、会话边界、调度与失败隔离，
  不替插件定义指标格式（与设置面同一条分层：机制归宿主、语义归插件）。
  ★ **宿主不聚合、不展示**（2026-10-23 用户）：探针数据只有插件自己读；宿主不解析文件内容，
  所以"格式漂移"不是问题，也不需要统一 schema。
  宿主只额外写一条失败记录（`F …`，连续失败停用的依据）。
- 两种运行位置：
  - **进程内（`:game`）**：能拿 Java 级指标（堆 / GC / 线程 / 帧时间）和游戏状态；
    风险 = 探针代码住在游戏进程里，崩了是游戏崩。官方默认，第三方启用时弹告知。
  - **`:probe` 隔离进程**（保留，2026-10-23 用户确认）：同 UID 的独立进程，读
    `/proc/<游戏 pid>/…` 那类**进程级**指标（CPU / RSS / 线程数 / IO）；拿不到 Java 堆 / GC / 帧时间。
    风险低 ⇒ 适合"信息不强"的插件，也是默认更安全的选项。
    ⚠️ 待验证：Android 16 上同 UID 能否读 `/proc/<pid>/stat|status|io`（这一条先做）。
- 游戏状态（`Vars` / `World`）**不是宿主的事**：宿主不提供字段表、不替插件编译游戏类。
  插件真要看游戏状态，自己按需**反射**（字段缺失就降级/跳过）—— 硬编码与版本适配都是插件自己的成本，
  不要求按游戏版本各编一份；不做游戏状态探针的插件完全不用碰这些。
  ★ 2026-10-23 用户补充：游戏字段大多跨版本稳定，而且**插件本来就由插件作者自己维护更新**（跟游戏模组一样）
  ⇒ 宿主不做兼容层、不承诺字段稳定，插件自己适配。
  ⚠️ 进程内探针引用游戏类时的运行时前提仍是：`:game` 进程已加载那份游戏 dex。
- 异常全吞 + 单次超时（>200ms 记警告）+ 连续失败停用该探针（宿主写 `F` 记录）。
- **2026-10-23 真机验证（第 222 轮，dev 夹具）**：
  - `:probe` 隔离进程（清单 `android:process=":probe"`）能读 `:game` 的
    `/proc/<pid>/{stat,status,io,cmdline,maps}`（同 UID）—— `VmRSS` / `Threads` / `rchar` 都拿到了。
  - `:game` 注入游戏 dex 后，CL 能 `Class.forName("mindustry.Vars", false)`（136 个字段，`state` 在）。
  - 插件探针 APK 能在 `:game` 里 `DexClassLoader` 加载并实例化、自己写 telemetry 文件。
  - 🔴 **加载探针 dex 必须从内部存储的只读副本**：Android 14+ 直接加载 `/sdcard/...` 的可写 APK
    会抛 `SecurityException: Writable dex file ... is not allowed`；而且只读副本**不能原地覆盖**
    （先 `delete` 再复制）——与插件注入的 S0 规则同源。
  - 骨架已进仓库：`ProbeService`（`:probe`）、`ProbeLab`（dev 验证台，产品版空壳）、
    夹具 `ProbeFixture`；正式 `Probe` 接口与调度器还没做。
- **官方 / 第三方的区别 = 是否给提示，不是能不能用**（2026-10-23 用户订正）：
  - 第三方**同样可以**声明进程内探针（能力不设门槛）
  - **官方**：不额外提示；**第三方**：启用时弹风险告知 + 崩溃报告里标"第三方，进程内"
  - 隔离进程（`:probe`）不是"第三方专用"，而是**给不想承担风险的人一个省心选项**；
    Java 级指标（堆/GC/线程栈）只有进程内能做
  - ⇒ 目标进程内探针仍按 `:probe` 方案验证（能否读 `/proc/<游戏pid>`），但它不再是"限制"，而是"默认更安全的选项"
- 告知：**安装不弹**；**启用时**（第三方 + `inProcess`）弹风险告知（官方不弹）；**游戏崩溃时**报告顶部标注
  「本次会话有插件参与：x（第三方，进程内）」+ 每条崩溃带 `origin=plugin:<name>` / `origin=game` /
  推断（`probe.state=RUN`）+ `thirdparty=0|1`。
- 确认框不是安全边界（插件本来就能改文件）⇒ 动作**直接执行**，结果返回 `{ok, needsRestart, reason}`。

### B3. 插件动作注册表（替代"一功能一接口"）
```java
String[] actions();
Result perform(String action, Bundle args);   // Result = ok / needsRestart / reason
```
首批：`item.open` / `version.launch`（⚠️ **`mod.enable` / `mod.disable` 不再开**，2026-10-23 晚：设置面已能安全写，见 B0；再开一道门就是同一件事两份实现）。加动作 = 宿主注册一行，接口不变。
保留硬约束（游戏在跑时拒绝写 `settings.bin`），但**不强制**宿主确认框。

### B4. 崩溃归因 / 模组冲突检查插件化
- **UI 留宿主**（插件没有宿主的卡片/徽标/主题，硬画必然不像）⇒ 插件只给结构化结论：
  ```java
  interface Analyzer { List<Finding> analyze(ItemInfo item, PluginApi api); }
  // Finding = { title, detail, severity, evidence[] }
  ```
  新扩展类型 `slot: "analyzer"`，宿主用现有归因/模组页的样式渲染。
- 先行试水：冲突检查（数据结构简单）；崩溃归因随后。

## 已否决

- **playtime 求和**：语义错误（回档/删档/复制都会让数字乱跳）⇒ 不做汇总，存档行仍显示单份时长。
- **"游玩时长"**：`playtime` 只在上次保存时更新；上滑清掉会丢最后一段（≤ 自动存档间隔，默认 3 分钟）；
  编辑器无自动存档、地图 `playtime` 恒 0。
- **EasyTier 注入加载**：拿不到 `VpnService` 身份 ⇒ 走无图标组件 APK。
- **帧率探针**：未验证，优先级低。

## 待推送

截至 2026-10-23 晚：本地有未推送的文档提交（`9cdf607` + `a815863` + 本轮设计变更）；具体领先几笔**现查** `git status -sb`。S302 不通时 `git push` 会挂起 / 被 reset，下次一并推。

---

## 待做：把「崩溃归因（含停用）」与「模组冲突检测」搬成插件

> 2026-10-23 敲定方向。目标不是"插件自己画一套 UI"，而是**插件给结论、宿主照原样渲染**，
> 否则界面必然和现在不一致（插件拿不到宿主的卡片/徽标/主题组件）。

### 现在缺的接口（按优先级）

1. **动作注册表（缩到非设置动作）**
   ```java
   String[] actions();
   Result perform(String action, Bundle args);   // {ok, needsRestart, reason}
   ```
   - 首批只剩 `item.open` / `version.launch`
   - ~~`mod.enable` / `mod.disable`~~：改走 v21 的设置写入口（"游戏在跑就拒绝"的硬约束在那一层）
   - 不强制确认框（插件本来就能改文件），但**硬约束保留**
2. **`analyzer` 扩展（数据型，不画 UI）**
   ```java
   interface Analyzer { List<Finding> analyze(ItemInfo item, PluginApi api); }
   // Finding = { title, detail, severity, evidence[] }
   ```
   - 宿主用现有归因卡 / 模组行样式渲染 ⇒ 保住"和原来一样"
3. **崩溃报告的读取入口**：✅ 已实现（v22，`crashReportsDir()`）
4. **插入点**
   - 崩溃页、模组页要能挂 `analyzer`（现在的 `section` 能否挂到这两页要现场确认）
6. **变更通知**
   - ✅ 不需要新接口：`ModsActivity.onResume` → `rescan()` 每次回来都重读设置
     （插件写完设置、用户返回模组页即生效）

### 其他已确认、以后再加的能力

- **`event` 扩展**：✅ 已实现（v22，第 221 轮）—— `boot` / `app-foreground` / `game-start` /
  `game-exit`，入口 `onEvent(PluginApi, String, Map)`；`game-exit` 在启动器被杀后有"补报"
  （落盘标记 + 游戏进程判活，`backfill=1`）。
- **`boot` 钩子**：✅ 已实现（v22，作为 `boot` 事件；每个进程一次；仍不是真常驻）
- **任务接口**：✅ 已实现（v22，第 221 轮）—— `submit(label, body) → Task`（跨页面、完成通知、取消）。
- **下载原语**：⏸ 暂缓（2026-10-23 用户：启动器还没有联网功能）；插件暂时自己写 HTTP。

### 顺序

1. ~~B0 设置面~~ ✅ 已实现（v21）
2. ~~任务接口 + 游戏事件（含 boot / app-foreground / 补报）~~ ✅ 已实现（v22）
3. `analyzer` + 崩溃页/模组页插入点；动作注册表只剩 `item.open` / `version.launch`
4. 迁移冲突检测（简单）→ 再迁移崩溃归因
5. `:game` 探针（**待再定**，用户 2026-10-23：先不碰）


### 总原则（2026-10-23 定）

**能力不设门槛，只决定"要不要提示"。** 官方与第三方的差别在于：

| | 官方 | 第三方 |
|---|---|---|
| 能用什么能力 | 全部 | **全部**（包含进程内探针） |
| 启用时提示 | 不弹 | 特殊能力（`inProcess` 等）弹风险告知 |
| 崩溃报告标注 | **也标**（写明「官方」，但同样可能由插件引起） | 标「第三方」，并提示可能由插件引起 |
| 安装时提示 | 无 | **无**（用户要求：安装不弹） |

即：不做"某些功能只有官方能用"，只做"谁用了要告诉用户"。

⚠️ **崩溃标注对官方插件同样生效**（2026-10-23 用户补充）：不能假设官方插件一定没问题 ——
只要会话里有插件参与，报告顶部就写「本次会话有插件参与：<名字>（官方/第三方）」，每条崩溃仍带
`origin=plugin:<name>`。`thirdparty=0|1` 只为统计区分来源，**不改变"必须标注"这件事**。
