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
- **第三方探针不许进 `:game` 进程**：走独立进程 `:probe`，只能读 `/proc/<游戏pid>/…`（同 uid 可读）；
  Java 级指标（堆/GC/线程栈）只给官方插件。⇒ 下一步先验 `:probe` 能否读 `:game` 的 `/proc`。
- 告知：**安装不弹**；**启用时**（第三方 + `inProcess`）弹风险告知；**游戏崩溃时**报告顶部标注
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
