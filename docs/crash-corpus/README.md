# 崩溃日志语料库（Mindustry）

> **这是什么**：从本人历史会话（DeepSeek 网页端导出）里**自动抽取 + 脱敏**出来的真实崩溃日志，
> 加上桌面端现成的崩溃报告。用途只有一个 —— **当崩溃归因/解析的回归语料与夹具**。
> **它不是文档**：只放日志本身 + 最小元信息（日期 / 来源会话 / 类别 / 签名）。
> ★ **谁在用**：`CrashAnalysis`（F23 崩溃分析，见 `docs/flows/16-…`）—— 自检里的夹具就是本库的
> **逐字原文**；[13 片](13-ART-措辞-安卓运行期.md) 是 2026-10-08 补的**安卓运行期**措辞（ART），
> [14 片](14-真机造崩-Java-dex-模组.md) 是同一天在真机上用**自造 Java dex 模组**造出来的完整报告
> —— 它们把"只照桌面语料写正则"这条坑钉死了；[15 片](15-客户端开局-数据损坏不产生报告.md) 是同日
> 「客户端真正开局 / 继续」那条路（地图、存档弄坏）的**边界实测**：**游戏自己接住、不产生报告**。
> [16 片](16-三族边界与Patches裁决.md) 是同日 F23 的**收尾裁决**：行星/渲染类、数据损坏族、
> `Patches:` 段**各自做不做**，以及"内存不足"这一支为什么例外（源码级 + 真机双证据）。

## 三类「内存类崩溃」别混为一谈

| 类型 | 标志 | 出现位置 | 有效手段 |
|---|---|---|---|
| **Java 堆 OOM** | `java.lang.OutOfMemoryError: … <1% of heap free after GC`（或 `Java heap space`） | 见 `03-蓝图.md` 3.1（安卓，开蓝图对话框爆） | 减对象数量 / 拆批 / 加 `-Xmx`；**与显存无关** |
| **显存 OOM** | `Frame buffer couldn't be constructed: unknown error **1285**`（=`GL_OUT_OF_MEMORY`）、`GL_OUT_OF_MEMORY` | 见 `05-环境与图形.md` 5.4（桌面，渲染途中爆） | 减贴图图集 / 渲染负载；**`-Xmx` 没用** |
| **原生崩溃** | `hs_err_pid*.log`（JVM 致命错误转储）、进程直接消失无报告 | 本机目前**没有**样本 | 只能看 `hs_err` / 系统日志 |

## 怎么用

| 用途 | 怎么用 |
|---|---|
| 写解析器 | 每条都给「日期 + 签名 + 原文」，可直接当输入断言 |
| 判断覆盖面 | 看每条的「类别」与是否带 `Likely Cause:`，就知道哪类形态还没覆盖 |
| 造新夹具 | 看 `.dsh/research/2026-10-07-crash-attribution-lab.md` 里的造法（stub 编译 + headless/客户端） |
| **加样本后回归一遍** | dev 口 `dev_crash_corpus <目录>`（`--es dev_crash_corpus_slot <槽>` 可指定槽）：把围栏块抽成 `.txt` 放进去，它会逐份跑 `CrashAnalysis` 并给出 `threw / missed / false positive` 与 `by kind / by cause` 计数。★ **夹具怎么抽是脚本化的**：`.dsh/research/extract-corpus-fixtures.py`（把各片的 ``` 围栏块逐个落成 `<片号>-NNN.txt`）⇒ 2026-10-08 重抽得 **119 份**、实测 **0 抛 / 0 漏 / 0 误报**（`evidence/f23-corpus-sweep.txt`；更早那一轮是 120 份，多出来的那份是当时手工加的变体）。⚠️ 夹具要放在**我们自己的外部目录**（`hub/<子目录>`），app 读不了 `/sdcard` 下的普通目录；`adb push <目录>` 会**套一层**，推完先 `ls` 一眼 |

## 脱敏规则（进仓库前已逐条跑过）

| 规则 | 替换为 |
|---|---|
| `C:\Users\<名字>\…` | `C:\Users\<user>` |
| 其它任意盘符路径（本机目录、Steam 库、临时目录） | `<path>` |
| IPv4 地址 | `<ip>` |
| 邮箱 | `<email>` |
| ≥15 位连续数字（机器码/账号类） | `<id>` |

⚠️ **同一个 zip 里的 `user.json` 含邮箱/手机号/微信资料，从未被读取，也不会进仓库。**

## 索引（共 40 条签名）

| # | 类别 | 首现日期 | 出现 | 签名 | `Likely Cause` |
|---|---|---|---|---|---|
| 1.1 | 模组与加载期 | 2026-07-07 | 2 次 | `java.lang.RuntimeException: UI should be created in main Thread` | — |
| 1.2 | 模组与加载期 | 2026-07-07 | 1 次 | `java.lang.NoClassDefFoundError: Could not initialize class mindustryX.` | — |
| 1.3 | 模组与加载期 | 2026-07-07 | 1 次 | `java.lang.NoClassDefFoundError: java/sql/SQLException` | — |
| 1.4 | 模组与加载期 | 2026-07-12 | 2 次 | `arc.util.ArcRuntimeException: File not found: mod.hjson (internal)` | — |
| 2.1 | 地图与存档 | 2025-10-01 | 1 次 | `java.lang.NullPointerException: Cannot invoke "mindustry.io.SaveVersio` | — |
| 2.2 | 地图与存档 | 2026-03-18 | 1 次 | `mindustry.io.SaveIO$SaveException: java.io.IOException: Error reading ` | — |
| 2.3 | 地图与存档 | 2026-06-22 | 2 次 | `java.net.SocketTimeoutException: Connect timed out` | — |
| 2.4 | 地图与存档 | 2026-07-07 | 1 次 | `java.io.IOException: Unknown save version: 13. Are you trying to load ` | — |
| 3.1 | 蓝图 | 2026-06-06 | 1 次 | `java.lang.OutOfMemoryError: Failed to allocate a 256 byte allocation w` | — |
| 3.2 | 蓝图 | 2026-06-24 | 2 次 | `java.io.IOException: Invalid schematic: Too many blocks.` | — |
| 4.1 | UI 与线程 | 2026-07-07 | 1 次 | `java.lang.ClassCastException: class java.lang.String cannot be cast to` | — |
| 4.2 | UI 与线程 | 2026-07-12 | 2 次 | `java.lang.NoSuchMethodError: 'void mindustry.ui.dialogs.ModsDialog.git` | ✅ |
| 4.3 | UI 与线程 | 2026-08-08 | 1 次 | `java.lang.NullPointerException: Cannot assign field "config" because "` | — |
| 4.4 | UI 与线程 | 2026-08-08 | 1 次 | `java.lang.ArrayIndexOutOfBoundsException: Index 22 out of bounds for l` | — |
| 5.1 | 环境与图形 | 2026-04-20 | 2 次 | `java.lang.NoClassDefFoundError: arc/files (wrong name: arc/Files)` | — |
| 5.2 | 环境与图形 | 2026-06-24 | 1 次 | `java.lang.IllegalStateException: Frame buffer couldn't be constructed:` | — |
| 5.3 | 环境与图形 | 2026-07-31 | 2 次 | `java.lang.NoClassDefFoundError: Could not initialize class sun.securit` | — |
| 6.1 | 网络与镜像 | 2026-06-01 | 2 次 | `java.io.IOException: HTTP 403 from htt` | — |
| 6.2 | 网络与镜像 | 2026-06-24 | 2 次 | `java.net.SocketTimeoutException: Read timed out` | — |
| 6.3 | 网络与镜像 | 2026-07-12 | 2 次 | `arc.util.Http$HttpStatusException: HTTP request failed with error: 403` | — |
| 6.4 | 网络与镜像 | 2026-08-01 | 1 次 | `java.net.ConnectException` | — |
| 6.5 | 网络与镜像 | 2026-08-08 | 1 次 | `java.lang.NullPointerException: Cannot invoke "arc.struct.Seq.sortComp` | — |
| 7.1 | 启动器自身 | 2025-11-24 | 1 次 | `AttributeError: 'MindustryLauncher' object has no attribute 'config'` | — |
| 7.2 | 启动器自身 | 2026-07-21 | 1 次 | `TypeError: '<' not supported between instances of 'int' and 'tuple'` | — |
| 8.1 | 其它游戏侧异常 | 2026-03-18 | 4 次 | `java.lang.reflect.InvocationTargetException` | — |
| 8.2 | 其它游戏侧异常 | 2026-03-18 | 1 次 | `java.lang.IllegalStateException: 本脚本依赖MindustryX v143.102 或更新版本` | — |
| 8.3 | 其它游戏侧异常 | 2026-04-10 | 1 次 | `arc.util.ArcRuntimeException: File not found: recommendMods.json (inte` | ✅ |
| 8.4 | 其它游戏侧异常 | 2026-05-24 | 1 次 | `arc.util.ArcRuntimeException: java.lang.NoClassDefFoundError: mindustr` | — |
| 8.5 | 其它游戏侧异常 | 2026-05-24 | 1 次 | `java.lang.NoClassDefFoundError: mindustry/type/AmmoType` | — |
| 8.6 | 其它游戏侧异常 | 2026-05-24 | 2 次 | `arc.util.ArcRuntimeException: arc.util.ArcRuntimeException: Couldn't l` | — |
| 8.7 | 其它游戏侧异常 | 2026-05-29 | 1 次 | `java.lang.ClassCastException: com.sun.tools.javac.code.Symtab$4 cannot` | — |
| 8.8 | 其它游戏侧异常 | 2026-06-22 | 1 次 | `java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for le` | — |
| 8.9 | 其它游戏侧异常 | 2026-06-24 | 1 次 | `arc.util.ArcRuntimeException: File not found: saves\mods\config\PatchE` | — |
| 8.10 | 其它游戏侧异常 | 2026-07-07 | 1 次 | `java.lang.ClassCastException: class java.lang.String cannot be cast to` | — |
| 8.11 | 其它游戏侧异常 | 2026-07-12 | 1 次 | `Error` | — |
| 8.12 | 其它游戏侧异常 | 2026-07-12 | 1 次 | `java.lang.RuntimeException: java.lang.NoSuchFieldException: textureCac` | ✅ |
| 8.13 | 其它游戏侧异常 | 2026-07-12 | 1 次 | `java.lang.NoSuchFieldException: textureCache` | — |
| 8.14 | 其它游戏侧异常 | 2026-08-08 | 1 次 | `（无异常签名）` | — |
| 8.15 | 其它游戏侧异常 | 2026-08-14 | 1 次 | `java.lang.ClassNotFoundException: arc.math.geom.Mat3D` | — |
| 8.16 | 其它游戏侧异常 | 2026-08-21 | 1 次 | `java.lang.NoSuchFieldError: Class mindustry.ai.UnitCommand does not ha` | — |

## 分片

- [模组与加载期](01-模组与加载期.md)
- [地图与存档](02-地图与存档.md)
- [蓝图](03-蓝图.md)
- [UI 与线程](04-UI-与线程.md)
- [环境与图形](05-环境与图形.md)
- [网络与镜像](06-网络与镜像.md)
- [启动器自身](07-启动器自身.md)
- [其它游戏侧异常](08-其它游戏侧异常.md)
- [桌面端现成报告 · 带 `Likely Cause`（游戏自己归因成功）（15 份）](09a-桌面报告-有归因.md)

- [桌面端现成报告 · 无 `Likely Cause` · **数据损坏类**（解压/解析/存档地图读坏）（20 份）](09b-桌面报告-数据损坏.md)

- [桌面端现成报告 · 无 `Likely Cause` · **其它**（加载期崩 / 包装异常 / 内存等）（17 份）](09c-桌面报告-无归因其它.md)

- [安卓设备现成报告（12 份 / 12 种）](11-安卓设备现成报告.md)

- [**ART 措辞（安卓运行期实测）**](13-ART-措辞-安卓运行期.md) —— ★ 与桌面 HotSpot **完全不同款**的
  四种链接期错误原文（`No field x of type I in class La/b/C;` / `Failed resolution of: L…;` …）。
  **写归因判据必须同时认这两套**：只照桌面语料写正则在手机上会一根针都抽不出来（F23 实测）。

- [**真机造崩：安卓上的 Java dex 模组**](14-真机造崩-Java-dex-模组.md) —— 自造探针模组在真机上
  制造的"接口变了启动不了"报告**逐字** + `last_log` + 模组源码 + 命令级配方；
  ★ 它同时钉死了"载入期崩**没有** `Likely Cause`"与"ART 措辞真的会出现在游戏报告里"，
  并附产品代码在那份真报告上跑出来的**三层结论**（L1b / L2a / L3 各自的逐字输出与耗时）；
  §1b/§1c 还有缺依赖（三层 `Caused by`）与 `IllegalAccessError` 两个变体，§7 是**真实大模组**
  （Neon 22.7 MB 包 / 4.61 MB dex）的注入型归因与 L3 成本受控 A/B。

- [**客户端真正开局 / 继续：数据损坏不产生崩溃报告**](15-客户端开局-数据损坏不产生报告.md) ——
  ★ 这是 F23 最后一类没被覆盖的输入，结论是**边界**：真机上把地图/存档弄坏（4+2 种坏法，
  含"合法流 + 内容截断"），游戏**自己接住**（弹「加载地图时出错。地图文件损坏或无效。」/
  「存档文件损坏或无效！」）并只在 `last_log.txt` 留 `[E]` 行 —— **一个崩溃报告都没有**
  ⇒ 归因器在这条路上**不可能冤枉模组**，**也不需要**为它加判据
  （桌面那族报告在 `09b`，安卓设备报告 `11` 里数据损坏签名 **0 处**）。附逐字日志指纹与
  "没测 OOM 型坏法"的诚实清单。

- [**三族边界与 `Patches:` 段的裁决**](16-三族边界与Patches裁决.md) —— ★ F23 收尾：把
  "行星/渲染类"、"数据损坏族"、"`Patches:` 段"三件**判据未定**的事各自定案，并说明
  **"内存不足"为什么例外**（有安卓真机输入 + 逃逸路径清楚 ⇒ 做成 `CAUSE_OOM`，只给"认不出"
  补一句原因、**不参与点名**）。★ 关键结论：数据损坏族**结构上不可能有报告** ——
  游戏自己用 `catch(Throwable)` 把读档链路包住了（`World.loadMap` / `SaveIO.load` /
  `LoadDialog`），连 `OutOfMemoryError` 一起收 ⇒ 那一支**不需要**判据，也不必再试 OOM 型坏法。
  源码行号、真机逐字日志、清场记录在 `evidence/f23-boundary-src.txt`。
