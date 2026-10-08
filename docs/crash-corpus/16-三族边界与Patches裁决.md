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
| **数据文件损坏**（蓝图 / 地图 / 存档） | ❌ 不做 | 🔴 **结构上不可能产生报告** —— 游戏自己用 `catch(Throwable)` 把整条读档链路包住了（见 §2），连 `OutOfMemoryError` 一起收 ⇒ 归因器**永远没有输入** |
| **`Patches:` 段** | ❌ 不做归因、也不加展示 | 报告只打印**补丁正文**，`PatchAsset` **没有任何 mod 归属字段**；正文里的 `name:"…"` 在源码注释里写明了是"patchset 自己的名字，忽略"（见 §3）。安卓侧真实输入 **0 份** |

★ 这一片的判据来源是**源码 + 真机**，不是"测了几种坏法就下结论"——
   因为 `catch(Throwable)` 这种边界**测不完**（换一种坏法、换一个版本都可能不同），
   而"谁接住了异常"在源码里是确定的。

## 2. 数据损坏族为什么**不可能**有报告（源码级，行号可复查）

```
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
```

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

```
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
```

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

## 5. 真机实测（实验槽 `datalab2`，不碰用户槽）

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

1. **没有真的点进「自定义游戏 → 选 oomtest → 开始游戏」**：搜索框没吃进 `adb shell input text`
   （libGDX 的文本输入要 IME 连接），而列表有 114 张内置图 + 2 张夹具，滚动定位代价高。
   ⇒ 该路径改由 §2.3 的源码定案 + 上一轮 `15` §2 的真机同族实验（开局 → 弹框 → 无报告）。
2. 蓝图的 `Too many blocks` / `Too large` 这两种**校验型**失败没单独造（要摸块数/尺寸字段偏移）；
   本轮用"改头"与"截断"，都落在 `Schematics.read` 的同一条 catch 上。
3. "世界数据数值合法但语义荒谬"的坏法仍未试（`15` §5 也列着）。
