# 16 · 三族边界与 `Patches:` 段的裁决（2026-10-08，F23 收尾）

> 这一片回答的是 F23 剩下的三个"判据未定"：**行星/渲染类**、**数据损坏族**、**`Patches:` 段**。
> 结论一句话：**前两族里只有"内存不足"这一支值得做**（它确实会产生报告，且安卓有真实输入），
> 其余**刻意不做** —— 不是"没来得及"，而是**结构上没有输入**或**结构上不能点名**。
> 逐字命令、行号级源码摘录、真机日志与清场记录都在 **`evidence/f23-boundary-src.txt`**。

## 1. 裁决表（每条都有一句可复查的理由）

| 族 | 做不做 | 为什么 |
|---|---|---|
| **内存不足** `OutOfMemoryError` | ✅ **做**（`CrashAnalysis.CAUSE_OOM`） | 有安卓真实报告（`03-蓝图.md` §3.1，`Android API level: 31`，崩在**界面构建**）；逃逸路径清楚：它不在读档链路的 try 里 ⇒ 逃到默认未捕获处理器 ⇒ 真写了报告 |
| **图形 / 显存** `Frame buffer couldn't be constructed` / `GL_OUT_OF_MEMORY` | ❌ 不做 | 语料里只有**桌面**样本（`05-环境与图形.md` §5.2）；用户 2026-10-07 已定为**本机显卡驱动/环境问题**、"不建议为此设计归因"；安卓 12 份真机报告里图形类签名 **0 处** |
| **行星渲染** | ❌ 不做 | 语料里 `Planet` **一份都没有**（连桌面样本都没有） |
| **数据文件损坏**（蓝图 / 地图 / 存档） | ⚠️ **分两种**（见 §7，2026-10-08 真机实验改判） | **抛 Exception 的坏法**（截断/位翻转/坏头）⇒ 被 `catch(Throwable)` 接住、**没有报告**；**抛 `OutOfMemoryError` 的坏法**（声明尺寸荒谬）⇒ OOM 本身被接住，但 `world.resize` 里 `Tiles` 分配失败**留下 length=0 的瓦片表**，下一帧渲染器索引它 ⇒ **渲染线程上未捕获 ⇒ 真报告 + 进程死**（报告顶层是 `ArrayIndexOutOfBoundsException … FloorRenderer.drawFloor`，**不是** OOM 字样） |
| **`Patches:` 段** | ❌ 不做归因、也不加展示 | 报告只打印**补丁正文**，`PatchAsset` **没有任何 mod 归属字段**；正文里的 `name:"…"` 在源码注释里写明了是"patchset 自己的名字，忽略"（见 §3）。安卓侧真实输入 **0 份** |

★ 这一片的判据来源是**源码 + 真机**，不是"测了几种坏法就下结论"——
   因为 `catch(Throwable)` 这种边界**测不完**（换一种坏法、换一个版本都可能不同），
   而"谁接住了异常"在源码里是确定的。

## 2. 数据损坏族为什么**不可能**有报告（源码级，行号可复查）

> ⚠️ **2026-10-08 更正（同日真机实验推翻了下面对"OOM 型"的外推）**：这一节说明的是
> **"异常会被谁接住"** —— 这对抛 `Exception` 的坏法成立（实测 6 种坏法全无报告）。
> 但**"被接住"不等于"这条路不会崩"**：接住之后留下的**半初始化世界**是另一条因果链，
> 它在下一帧渲染时爆在渲染线程上 ⇒ **OOM 型坏法真的会产生报告**。
> 逐字报告与机制见 **§7**；`ref/32` 的 **§85.18.6** 是同一条更正。

```java
World.java:344~362   loadMap(...)
  351:  try{
  352:      SaveIO.load(map.file, new FilterContext(map));
  353:  }catch(Throwable e){          ← 连 OutOfMemoryError 一起收
  354:      Log.err(e);
  356:      ui.showErrorMessage("@map.invalid");        ← 就是那句「地图文件损坏或无效」
  357:      Core.app.post(() -> state.set(State.menu));
  361:      return;

SaveIO.java:163~180  load(...)
  172:      ver.read(stream, counter, new SaveReadState(context));   ← 瓦片分配在这一行之内
  174:  }catch(Throwable e){
  175:      throw new SaveException(e);                              ← OOM 先被包成 SaveException

LoadDialog.java:256~260
  256:  }catch(SaveException e){
  259:      ui.showErrorMessage("@save.corrupted");                  ← 「存档文件损坏或无效！」
```java

配套的两条（用来**排除**"是不是 arc 把异常吞了"这种解释）：

- `AndroidLauncher.java:39~49`：安卓上写报告的地方**只有**默认未捕获处理器
  （`Thread.setDefaultUncaughtExceptionHandler(...) → CrashHandler.log(error)`）。
  ⇒ **报告 = 异常逃到线程外**；被 catch 住的永远不会变成报告。
- `AndroidGraphics.java:351~387`（arc）：整个 `onDrawFrame` **没有 try/catch**
  ⇒ 渲染线程**不**吞异常。所以 `crash-corpus/15` §2 里"渲染线程上崩、照样被接住"，
  接住它的是**游戏自己的 `World.loadMap`**，不是 arc。
- 顺带纠正一句老话：`UI.java:292~302` 的 `loadAnd(...)` **自己也没有 catch**
  （只是 `Time.runTask(7f, () -> call.run())`）—— "被 loadAnd 接住"是**错的**说法。
- 地图导入/编辑器那条路同理：`Maps.java:297~306` `tryCatchMapError` 也是 `catch(Throwable)`。

## 3. `Patches:` 段为什么**不能**当旁证（源码级）

```java
CrashHandler.java:45
  ... state.data.getPatches().toString("\n---\n", p -> p.patch) ...
                                        ↑ 打印的是 PatchAsset.patch = **补丁正文**

PatchAsset.java   字段只有：patch（原始文本）/ json / error / warnings
                  （基类 DataAsset 的 path 有，但**报告里不打印**）
                  ⚠️ 构造注释还写着 path 是随机 UUID（"temporary measure"）

DataPatcher.java:251~252
  set.name = value.getString("name", "");
  value.remove("name"); //patchsets can have a name, ignore it if present
                            ↑ 正文里那个 name:"…" = **补丁集自己的名字**，不是模组名
```java

语料里的实例（`08-其它游戏侧异常.md` §8.14）：

```
Patches:
name:"世处ipt提高上限"block.world-processor.maxInstructionsPerTick:100000
---
```

**输入厚度**：全部语料 **4 份**带 `Patches:` 行，其中 **3 份是空段**，唯一非空那份是
**桌面 Windows + MindustryX** ⇒ **安卓 0 份**。⇒ 按"别给没有真实输入的地方加判据"**不加展示**。

## 4. 内存不足这一支（做了，而且**不参与点名**）

- 判据：异常节的类型以 `OutOfMemoryError` 结尾，或消息里出现
  `OutOfMemoryError` / `until OOM` / `Failed to allocate`（包装型）。
- 落点：`CrashAnalysis.CAUSE_OOM` + 一条文案 `crash_verdict_none_oom`，
  **只补在「认不出」那一种结论后面**，并且**取代**原来那句通用三选一
  （那句里也列着"内存"，两句并排等于同一件事说两遍 —— REF §85.16.2）。
- 🔴 **有模组证据时一律以模组为准**：`OOM + Error loading mod x` ⇒ 只报模组；
  `OOM + Mods: none (vanilla)` ⇒ 报原版崩。自检里各有一条组合夹具钉着。
- 语料里的两份（全量回归实测 `by cause: OOM=2`）：
  `03-001`（安卓那份）与 `09c-015`（桌面 `Java heap space`）。

## 5. 真机实测之一：Bad 蓝图 / 预览路径（实验槽 `datalab2`，不碰用户槽）

夹具（`.dsh/research/_craft-oom-map.py` 可复跑）：底座 = APK 里的 `glacier.msav`（v7），
造 `ok.msav`（对照）/ `oom.msav`（地图区 w,h→32767）+ 蓝图 `s-ok` / `s-badhead`（改头）/ `s-cut`（截断）。

`slot-datalab2/last_log.txt` 逐字（**`crashes/` 始终为空**）：

```
[E] Failed to read schematic from file '.../s-badhead.msch'
[E] java.io.IOException: Not a schematic file (missing header).
      at mindustry.game.Schematics.read(Schematics.java:7)
      at mindustry.game.Schematics.loadFile(Schematics.java:16)
      ... at arc.backend.android.AndroidGraphics.onDrawFrame(AndroidGraphics.java:132)   ← 渲染线程上
[E] Failed to read schematic from file '.../s-cut.msch'
[E] java.lang.RuntimeException: java.io.EOFException: Unexpected end of ZLIB input stream
[E] Failed to generate preview!: java.io.IOException: Error reading region "preview_map".
      Caused by: java.lang.ClassCastException: mindustry.content.Blocks$55 cannot be cast to
                 mindustry.world.blocks.environment.Floor   (MapIO.colorFor)
```

⇒ 蓝图**两种坏法**都只进日志；`oom.msav` 进的是 **preview 生成**那条路，抛出的异常同样被接住。
"开局"那条路由 §2.3 的 `catch(Throwable)` 定案（上一轮 `15` §2 已在真机上点过同族一次）。

⚠️ 清场时踩到 AGENTS §85.11 记的那个坑：`dev_launch_key` 会把**当前槽**切到被启动版本的槽
⇒ 删掉 `slot-datalab2` 之后 `app_hub/mdt-slot.txt` 里**还写着 `datalab2`**。已还原（`模组演示`）
并复核（`evidence/f23-boundary-src.txt` §4）。

## 6. 诚实清单（这一轮**没做**的）

1. ~~**没有真的点进「自定义游戏 → 选 oomtest → 开始游戏」**~~ ✅ **2026-10-08 补做了**（§7）：
   第一次卡在"搜索框不吃 `adb shell input text`"；后来发现**列表按内部名排序** ⇒ 把夹具改名成
   `AAAoomt` / `AAokmap`（等长、排在 `Ancient Caldera` 之前）就**首屏可点**，再用"缩略图那块白
   「oh no」"做**像素级定位**，完全不依赖目测换算。⇒ 开局那条路已实测，结论见 §7。
2. 蓝图的 `Too many blocks` / `Too large` 这两种**校验型**失败没单独造（要摸块数/尺寸字段偏移）；
   本轮用"改头"与"截断"，都落在 `Schematics.read` 的同一条 catch 上。
3. "世界数据数值合法但语义荒谬"的坏法仍未试（`15` §5 也列着）。

## 7. 真机实测之二（2026-10-08，决定性）：**OOM 型数据损坏会产生报告**

夹具：`.dsh/research/_craft-oom-map.py` 造的 `oom.msav` —— 底座 = APK 里 `glacier.msav`（v7），
只把**地图区**的 w/h 改成 `32767×32767`（≈10.7 亿格）；**对照** `ok.msav` 同源、只改名。
实验槽 `datalab3`（不碰用户槽），走 UI：`自定义游戏 → 首屏第一张（AAAoomt）→ 开始游戏`。

**游戏自己写出来的报告（逐字，1388 B / `crashes/crash_1791471721413.txt`）**：

```
Mindustry has crashed. How unfortunate.
Report this at https://github.com/Anuken/Mindustry/issues/new?labels=bug&template=bug_report.md

Version: release build 159.7 (Built July 19, 2026 17:39 PM)
Date: 十月 8, 2026 23:02:01 下午
OS: Linux x (aarch64)
GL Version: GLES 3.2.0 / Qualcomm / Adreno (TM) 735 / OpenGL ES 3.2 V@0762.46 (GIT@6275e17561, If3c5f88bad, 1769153819) (Date:01/23/26)
Android API level: 36
Java Version: 0
Runtime Available Memory: 512mb
Cores: 8
Mods: none (vanilla)


java.lang.ArrayIndexOutOfBoundsException: length=0; index=0
	at mindustry.graphics.FloorRenderer.drawFloor(FloorRenderer.java:6)
	at mindustry.graphics.FloorRenderer.drawFloor(FloorRenderer.java:1)
	at mindustry.core.UI$$ExternalSyntheticLambda1.run(R8$$SyntheticClass:34)
	at arc.graphics.g2d.SpriteBatch.flushRequests(SpriteBatch.java:36)
	at arc.graphics.g2d.SpriteBatch.flush(SpriteBatch.java:9)
	at arc.graphics.g2d.Draw.flush(Draw.java:3)
	at mindustry.core.Renderer.draw(Renderer.java:588)
	at mindustry.core.Renderer.update(Renderer.java:448)
	at arc.ApplicationCore.update(ApplicationCore.java:9)
	at mindustry.ClientLauncher.update(ClientLauncher.java:193)
	at arc.backend.android.AndroidGraphics.onDrawFrame(AndroidGraphics.java:132)
	at android.opengl.GLSurfaceView$GLThread.guardedRun(GLSurfaceView.java:1586)
	at android.opengl.GLSurfaceView$GLThread.run(GLSurfaceView.java:1283)
```

**同一槽 `last_log.txt` 的尾部**（★ OOM 只在这里，**不在报告里**）：

```
	at mindustry.io.SaveIO.load(SaveIO.java:3)
	... 11 more
Caused by: java.lang.OutOfMemoryError: Failed to allocate a 4294705168 byte allocation with 201310208 free bytes and 478MB until OOM, target footprint 236744056, growth limit 536870912
	at mindustry.world.Tiles.<init>(Tiles.java:6)
	at mindustry.core.World.resize(World.java:16)
	at mindustry.core.World$Context.resize(World.java:3)
	at mindustry.core.World$FilterContext.resize(World.java:1)
	at mindustry.io.versions.ShortChunkSaveVersion.readMap(ShortChunkSaveVersion.java:20)
	at mindustry.io.SaveVersion.lambda$read$2(SaveVersion.java:3)
	...
	at mindustry.io.SaveFileReader.readRegion(SaveFileReader.java:11)
	... 14 more
```

### 7.1 机制（两条因果链，缺一不可）

1. **第一链（被接住）**：`Control.playMap → World.loadMap` 的 try 里 ⇒ `Tiles.<init>` 申请
   **4.29 GB** ⇒ `OutOfMemoryError` ⇒ `SaveIO.load` 包成 `SaveException` ⇒ `World.loadMap`
   的 `catch(Throwable)` 收掉 ⇒ `Log.err`（**只进 `last_log`**）+ 弹「地图文件损坏或无效」+ 回菜单。
   ⇒ 这一层与 §2 的源码一致。
2. **第二链（逃逸）**：但 `World.resize` 已经把 `Tiles` 构造**开了一半**（分配失败 ⇒ 那张表
   **length = 0**），而 `catch` 只回滚了 state、**没有把 world 复原** ⇒ 渲染器下一帧
   `FloorRenderer.drawFloor` 直接索引它 ⇒ `ArrayIndexOutOfBoundsException: length=0; index=0`
   ⇒ **渲染线程（`onDrawFrame`）上未捕获**（arc 那里没有 try/catch，见 §2）⇒ 写报告 + 进程死。

🔴 **教训（已进 REF §85.18.6 / §五）**：**"这个异常会被接住" ≠ "这条路不会崩"** ——
接住之后留下的**半初始化状态**是独立的一条因果链，**只有真机能发现**。上一轮我用
"源码里有 `catch(Throwable)`" 外推出"结构上不可能产生报告"，正是被这条反例推翻的。

### 7.2 这对归因器意味着什么（实测，不是推演）

在同一台设备上跑 `dev_crash_analyze true`（我们的产品代码读这份真报告）：

```
-- crash_1791471721413.txt (1388 B) --
  kind=VANILLA version=release build 159.7 … mods=vanilla chain=1 frames=13 cause=none
  needles=10 top=java.lang.ArrayIndexOutOfBoundsException: length=0; index=0 hits=- dex=-1
  verdict: 不是模组的问题 —— 报告里写着这次没有加载任何模组（Mods: none (vanilla)）。
  took 2 ms
```

- ✅ **没冤枉模组**（`Mods: none (vanilla)` ⇒ VANILLA），2 ms、**不扫 dex**；
- ⚠️ **`cause=none` 是对的**：报告正文里**根本没有** `OutOfMemoryError` 字样（它只在 `last_log`）——
  ⇒ 现行 `CAUSE_OOM` 判据**刻意不去猜**"length=0 是不是分配失败"，因为**这份报告自身不足以判定**；
  真要判就得读同一槽的 `last_log.txt`（跨文件证据），这属于**产品决策**，本轮**不做**
  （判据只能从真输入长出来；手上一共只有 1 份这种自造样本）。
- ★ 同时它把**"崩了主动说一声"**端到端验了一遍：游戏进程一死，启动器回前台就弹
  「游戏崩了 / 不是模组的问题 —— 报告里写着这次没有加载任何模组（Mods: none (vanilla)）。」
  （截图 `evidence/` 本地留档）。

### 7.3 对「栈里看不到模组类」那一族的定案（本片要回答的第 ① 项）

这份报告就是**典型的渲染类崩溃、栈里只有游戏/arc 类**。我们**唯一**能从文本上"判定与模组无关"
的依据是**报告头里的模组段**（`Mods: none (vanilla)`）与 `Error loading mod …` 那一行 ——
**不是栈**。⇒ 定案：**"栈里看不到模组类"不构成"不是模组的锅"**（模组完全可以在游戏代码里
留下非法状态而不出现在栈里）；判据只按 §85.2 的权重走，够不着门槛就**说判不出来**
（「认不出是哪个模组」+ 建议），够得着才点名。行星渲染那一族连"游戏类栈"都没有样本
（语料 0 份）⇒ 仍然**不做**。
