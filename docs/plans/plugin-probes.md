# 游戏监测与插件探针（设计备忘）

> 本文件只记**已敲定、还没实现**的设计。已实现的部分在 `docs/PLUGIN-API.md` 第 10 节，
> 这里不再复述；实现一项就从本文件移走。换方向前先读这里。

## 前置：已经能用的部分

- `UsageLog` 在 **`:game` 进程**记录启动 / 心跳 / 结束 / 崩溃 / 熄屏，只记"下界"；
- 两条流：`hub/usage/launch.tsv`（启动事实，写完不可变）与 `run.tsv`（运行期事实）；
- `PluginApi` v18 = `File usageDir()`。
  字段、真机产物与读取约定见 `docs/PLUGIN-API.md` 第 10 节。

## 已定、未实现

### B1. 统计插件 `usagestats`
宿主只"记"，插件只"看"：读 `usageDir()` → 启动次数 / 版本分布 / 会话时长 / 崩溃 / 熄屏 → 柱状图 + CSV/PNG 导出。

### B2. 探针（性能数据）
- **A′（宿主持有探针表 + 声明式聚合）已放弃**：每次加指标都要改宿主发版，扩展性差。
- 改走 **D：插件自定义探针**，宿主只做机制：
  ```java
  interface Probe { String name(); void reset(); void sample(); String summary(); }
  ```
  插件 `plugin.json` 声明 `telemetry.probes: [{class, sec}]`；宿主在 `:game` 进程 `DexClassLoader` 只加载探针类，
  定时 `sample()`，会话结束 `summary()` → 写一行 `A <ts> <name> <k=v…>`；**不落原始样本**。
- 异常全吞 + 单次超时（>200ms 记警告）+ 连续失败停用该探针（写 `F <ts> probe <name> …`）。
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
首批：`mod.enable` / `mod.disable` / `item.open` / `version.launch`。加动作 = 宿主注册一行，接口不变。
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

截至 2026-10-23：除本笔提交外没有待推送；S302 不通时 `git push` 会挂起 / 被 reset，下次一并推。

---

## 待做：把「崩溃归因（含停用）」与「模组冲突检测」搬成插件

> 2026-10-23 敲定方向。目标不是"插件自己画一套 UI"，而是**插件给结论、宿主照原样渲染**，
> 否则界面必然和现在不一致（插件拿不到宿主的卡片/徽标/主题组件）。

### 现在缺的接口（按优先级）

1. **`settings()` / `modState(item)`（只读）**
   - 冲突检测要 `mod-<名>-enabled` / `-failed`；崩溃归因要"这个模组当时是不是开着"
   - 现在插件只能自己解析 `settings.bin` —— 语义（默认值、成对写、`-failed` 的真实含义）会漂
2. **动作注册表**
   ```java
   String[] actions();
   Result perform(String action, Bundle args);   // {ok, needsRestart, reason}
   ```
   - 首批动作：`mod.enable` / `mod.disable` / `item.open` / `version.launch`
   - "含禁用"这部分必须走它：直接写 `settings.bin` 会绕开"游戏在跑就拒绝"等硬约束
   - 不强制确认框（插件本来就能改文件），但**硬约束保留**
3. **`analyzer` 扩展（数据型，不画 UI）**
   ```java
   interface Analyzer { List<Finding> analyze(ItemInfo item, PluginApi api); }
   // Finding = { title, detail, severity, evidence[] }
   ```
   - 宿主用现有归因卡 / 模组行样式渲染 ⇒ 保住"和原来一样"
4. **崩溃报告的读取入口**
   - 明确 `crashes/` 的报告能被插件读到（`crashReports()`，或确认 `logs()` 覆盖它）
5. **插入点**
   - 崩溃页、模组页要能挂 `analyzer`（现在的 `section` 能否挂到这两页要现场确认）
6. **变更通知**
   - 插件改了设置后，宿主的模组页要重读（否则列表还是旧状态）

### 其他已确认、以后再加的能力

- **`event` 扩展**：`game-exit`（游戏退出时弹窗/提示）、`app-foreground`
  - 弹窗用宿主 `confirm()`（样式一致），不要插件自己 `AlertDialog`
  - 用户直接在最近任务清掉、不再回界面 ⇒ **不会触发**
- **`boot` 钩子**：插件开机跑一次（只有官方插件考虑；宿主在后台时会被杀，不能当常驻用）

### 顺序

1. `settings()` / `modState()`
2. 动作注册表
3. `analyzer` + 崩溃页/模组页插入点
4. 迁移冲突检测（简单）→ 再迁移崩溃归因


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
