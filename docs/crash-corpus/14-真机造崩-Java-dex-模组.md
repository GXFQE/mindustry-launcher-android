# 崩溃语料 · 真机造崩：安卓上的 **Java dex 模组**（2026-10-08）

> **这是什么**：在**真机**（HONOR ELP-AN00 / Android 16）上，用一个**自造的 Java dex 模组**
> 精确制造「版本更新后接口变了 ⇒ 启动不了」这种最常见的崩溃，然后把**游戏进程在 ART 上写出来的
> 报告**逐字收进来。用途有两个：
>  ① 它是 F23 归因判据的**端到端夹具**（真报告 + 真模组包，不是手写样例）；
>  ② 它把两件此前只有"桌面证据"的事**在安卓上钉死**了：
>     · 载入期崩（`Mod.init()` 里抛）**游戏自己给不出 `Likely Cause`**（桌面早就实测过，这次是安卓）；
>     · ART 的措辞（`No field x of type I in class La/b/C;`）**真的会出现在游戏报告里**
>       —— 见 [13 片](13-ART-措辞-安卓运行期.md)（那一版是拿 `dalvikvm` 单独测的）。
> ⚠️ 语料来源 = 我们自己造的探针，**不含任何用户数据**；模组包里也没有游戏本体代码。

## 1. 主探针（`crashtest`）：**游戏类里没有那个成员**（1701 B）

> 现场：槽 `crashlab`（实验用完已删）、游戏 = 导入的 `official-159.apk`（**159.7**）、
> mods 目录里只有这一个探针包 `crashtest.zip`（1045 B）。

```
{report}
```

## 1a. 同一次启动的 `last_log.txt`（逐字）

```
{log}
```

★ 三行是"模组真的跑起来了"的判据：`MDTDEV: Boom class loaded`（类被加载）、
`Loading mod: crashtest`（游戏认了它）、`MDTDEV: init ran`（`init()` 真的进了）
—— 最后一行之后立刻就是链接期错误。

## 1b. 变体 A：**缺依赖**（`depuser`，2231 B）

同一天、同一槽、同一套步骤，只把探针换成 **`depuser`**（`init()` 里去调一个"编译期有、运行期没有"
的类 `depmod.Dep`）⇒ 复现"装了 A、没装它依赖的 B"这种最常见的缺依赖崩溃：

```
Mindustry has crashed. How unfortunate.
Version: release build 159.7 (Built July 19, 2026 17:39 PM)
Date: 十月 8, 2026 14:55:41 下午
OS: Linux x (aarch64)
GL Version: GLES 3.2.0 / Qualcomm / Adreno (TM) 735 / OpenGL ES 3.2 V@0762.46 (GIT@6275e17561, If3c5f88bad, 1769153819) (Date:01/23/26)
Android API level: 36
Java Version: 0
Runtime Available Memory: 512mb
Cores: 8
Mods: depuser:1.0


java.lang.RuntimeException: Error loading mod depuser
	at mindustry.mod.Mods.contextRun(Mods.java:18)
	at mindustry.mod.Mods.lambda$eachClass$38(Mods.java:7)
	at mindustry.mod.Mods.$r8$lambda$HyIUhXyWSolB9jGn61XETjxz3gQ(Mods.java:1)
	at mindustry.mod.Mods$$ExternalSyntheticLambda0.get(R8$$SyntheticClass:16)
	at arc.struct.Seq.each(Seq.java:1)
	at mindustry.mod.Mods.eachClass(Mods.java:18)
	at mindustry.ClientLauncher.update(ClientLauncher.java:127)
	at arc.backend.android.AndroidGraphics.onDrawFrame(AndroidGraphics.java:132)
	at android.opengl.GLSurfaceView$GLThread.guardedRun(GLSurfaceView.java:1586)
	at android.opengl.GLSurfaceView$GLThread.run(GLSurfaceView.java:1283)
Caused by: java.lang.NoClassDefFoundError: Failed resolution of: Ldepmod/Dep;
	at depuser.Dep.init(Dep.java:14)
	at arc.net.ArcNet$$ExternalSyntheticLambda0.get(R8$$SyntheticClass:134)
	at mindustry.mod.Mods.lambda$eachClass$37(Mods.java:3)
	at mindustry.mod.Mods.$r8$lambda$ITAXCMUJcXkSOcT8L-NDfdYF990(Mods.java:1)
	at mindustry.mod.Mods$$ExternalSyntheticLambda14.run(R8$$SyntheticClass:22)
	at mindustry.mod.Mods.contextRun(Mods.java:1)
	... 9 more
Caused by: java.lang.ClassNotFoundException: depmod.Dep
	at mindustry.mod.ModClassLoader.findClass(ModClassLoader.java:81)
	at java.lang.ClassLoader.loadClass(ClassLoader.java:637)
	at java.lang.ClassLoader.loadClass(ClassLoader.java:573)
	at mindustry.android.AndroidLauncher$1$1.loadClass(AndroidLauncher.java:14)
	at java.lang.ClassLoader.loadClass(ClassLoader.java:573)
	at mindustry.mod.ModClassLoader.findClass(ModClassLoader.java:38)
	at java.lang.ClassLoader.loadClass(ClassLoader.java:637)
	at java.lang.ClassLoader.loadClass(ClassLoader.java:573)
	at mindustry.android.AndroidLauncher$1$1.loadClass(AndroidLauncher.java:14)
	at java.lang.ClassLoader.loadClass(ClassLoader.java:573)
	... 15 more
```

同一次启动的 `last_log.txt`：

```
[I] [GL] Version: GLES 3.2.0 / Qualcomm / Adreno (TM) 735 / OpenGL ES 3.2 V@0762.46 (GIT@6275e17561, If3c5f88bad, 1769153819) (Date:01/23/26)
[I] [GL] Max texture size: 16384
[I] [GL] Using OpenGL 3 API.
[I] [JAVA] Version: 0
[I] [ANDROID] API level: 36
[I] [RAM] Available: 512.0 MB
[I] [Mindustry] Version: 159.7
[I] Loading mod: depuser
[I] MDTDEV: depuser init ran (dependency is missing)
```

★ 三条值得记的：
1. **三层 `Caused by` 链**：`RuntimeException: Error loading mod depuser` →
   `NoClassDefFoundError: Failed resolution of: Ldepmod/Dep;` → `ClassNotFoundException: depmod.Dep`
   ⇒ L4（追链）必须能一路追到底，否则连"缺的是哪个类"都拿不到；
2. 缺的那个类在 **ART 里两种写法**（描述符 `Ldepmod/Dep;` 与点号 `depmod.Dep`）**各出现一次**
   ⇒ 抽针时它们是**同一根针**（去重后权重 3）；
3. 这个类名**就在出事的模组自己的 dex 里**（它引用了这个类）⇒ L3 扫 dex 能唯一命中它，
   即使报告里那句"Error loading mod"被人为拿掉。

## 1c. 变体 B：**访问权限变了**（`accessboom`，1695 B）

再把探针换成 **`accessboom`**（编译期那个成员是 public、运行期是 private）⇒
`IllegalAccessError`（"某个字段/方法后来改成私有的了"）：

```
Mindustry has crashed. How unfortunate.
Version: release build 159.7 (Built July 19, 2026 17:39 PM)
Date: 十月 8, 2026 14:55:57 下午
OS: Linux x (aarch64)
GL Version: GLES 3.2.0 / Qualcomm / Adreno (TM) 735 / OpenGL ES 3.2 V@0762.46 (GIT@6275e17561, If3c5f88bad, 1769153819) (Date:01/23/26)
Android API level: 36
Java Version: 0
Runtime Available Memory: 512mb
Cores: 8
Mods: accessboom:1.0


java.lang.RuntimeException: Error loading mod accessboom
	at mindustry.mod.Mods.contextRun(Mods.java:18)
	at mindustry.mod.Mods.lambda$eachClass$38(Mods.java:7)
	at mindustry.mod.Mods.$r8$lambda$HyIUhXyWSolB9jGn61XETjxz3gQ(Mods.java:1)
	at mindustry.mod.Mods$$ExternalSyntheticLambda0.get(R8$$SyntheticClass:16)
	at arc.struct.Seq.each(Seq.java:1)
	at mindustry.mod.Mods.eachClass(Mods.java:18)
	at mindustry.ClientLauncher.update(ClientLauncher.java:127)
	at arc.backend.android.AndroidGraphics.onDrawFrame(AndroidGraphics.java:132)
	at android.opengl.GLSurfaceView$GLThread.guardedRun(GLSurfaceView.java:1586)
	at android.opengl.GLSurfaceView$GLThread.run(GLSurfaceView.java:1283)
Caused by: java.lang.IllegalAccessError: Field 'mindustry.core.GameState.state' is inaccessible to class 'accessboom.Boom' (declaration of 'accessboom.Boom' appears in /data/user/0/io.mdt.launcher/cache/mods/accessboom/1a11a4a9d60.zip)
	at accessboom.Boom.init(Boom.java:13)
	at arc.net.ArcNet$$ExternalSyntheticLambda0.get(R8$$SyntheticClass:134)
	at mindustry.mod.Mods.lambda$eachClass$37(Mods.java:3)
	at mindustry.mod.Mods.$r8$lambda$ITAXCMUJcXkSOcT8L-NDfdYF990(Mods.java:1)
	at mindustry.mod.Mods$$ExternalSyntheticLambda14.run(R8$$SyntheticClass:22)
	at mindustry.mod.Mods.contextRun(Mods.java:1)
	... 9 more
```

★ 与 13 片（`dalvikvm` 单独测）对得上：**ART 那句与桌面 HotSpot 不同款**，而其中
`Field 'X.y' is inaccessible to class 'Z'` 这一种**两种运行时同款**。这句里的 **Z = 访问方类**
（`accessboom.Boom`，就是模组自己的类）⇒ 在安卓上它同样是**能点名**的那根针（权重 3）；
而目标 owner（`mindustry.core.GameState`）与短成员名（`state`）都只有 1 分 ⇒ 不许靠它们点名。

## 2. 这次实验钉死的六条事实

| # | 事实 | 判据 |
|---|---|---|
| 1 | **报告里没有 `Likely Cause:`** | 全文没有这一行（桌面 §2.1 的结论，安卓同款）—— 载入期崩游戏自己归因不了 |
| 2 | **ART 措辞进了真报告** | `Caused by: java.lang.NoSuchFieldError: No field definitelyNotAField of type I in class Lmindustry/core/GameState; …` |
| 3 | 那句 `appears in <路径>` 里的路径**指向"声明类住在哪"**：第 1 份是**游戏本体 APK**（`app_hub/import/official-159.apk`，缺的是游戏类）、变体 B 是**游戏解压模组包的缓存目录**（`cache/mods/accessboom/<哈希>.zip`）⇒ ⚠️ 两种都不是"哪个模组"的判据（13 片里已警告过） |
| 4 | 模组自己的栈帧带**行号**：`at boom.Boom.init(Boom.java:21)` | 与 Rhino 那种 `(Unknown Source:47)` 不同；两种都被 FRAME 正则接住 |
| 5 | 合成帧形如 `…$$ExternalSyntheticLambda0.get(R8$$SyntheticClass:134)` | dex 被 R8 处理过的痕迹；判据里对它**不特殊处理**（它落在游戏命名空间，权重 1） |
| 6 | **多出来的类名不会误伤**：`Mods:` 行只有 `crashtest:1.0` | 归因只看这一行 + 报告里的符号/帧（L1b 一行就点名了） |

## 3. 探针模组（三份，源码逐字）

★ 造法与 `.dsh/research/2026-10-07-crash-attribution-lab.md` §8 同源，但这里是**给安卓用的 dex 版**：
第 1 份（`crashtest`）= 假的**游戏类成员**；变体 A/B 各自换了假类与入口类，配方完全一样。

```java
{stub}
```

```java
{boom}
```

```hjson
{hjson}
```

变体 A（`depuser`）的两个假类 + 入口：

```java
package depmod;

/** **只在编译期存在**的类（不给运行期）⇒ `NoClassDefFoundError: Failed resolution of: Ldepmod/Dep;`。 */
public class Dep {
    public static void hello() {
    }
}
```

```java
package depuser;

import mindustry.mod.Mod;

/**
 * F23 真机实验 · 变体 A：**缺依赖**（装了 A、没装它依赖的 B）。
 * 编译期有 `depmod.Dep`，运行期没有 ⇒ `NoClassDefFoundError: Failed resolution of: Ldepmod/Dep;`
 * 外面再被游戏包成 `RuntimeException: Error loading mod depuser`。
 */
public class Dep extends Mod {

    @Override public void init() {
        arc.util.Log.info("MDTDEV: depuser init ran (dependency is missing)");
        depmod.Dep.hello();
    }
}
```

变体 B（`accessboom`）：假类把真类里 **private** 的 `state` 写成 public，入口去写它：

```java
package mindustry.core;

/** **编译期**假类（不进 dex）：`state` 在真类里是 **private** ⇒ `IllegalAccessError`。 */
public class GameState {
    public State state;

    public static class State {
    }
}
```

```java
package accessboom;

import mindustry.mod.Mod;

/**
 * F23 真机实验 · 变体 B：**访问权限变了**（编译期那个成员是 public，运行期是 private）
 * ⇒ `IllegalAccessError: Field 'mindustry.core.GameState.state' is inaccessible to class …`。
 */
public class Boom extends Mod {

    @Override public void init() {
        arc.util.Log.info("MDTDEV: accessboom init ran (about to touch a now-private field)");
        new mindustry.core.GameState().state = null;
    }
}
```

## 4. 配方（命令级，PC 侧）

```powershell
$lab   = "<仓库>/.tmp-artlab/mod"
$game  = "<素材>\Mindustry本体\Mindustry-160.4.jar"   # 只为 mindustry.mod.Mod / arc.util.Log
$sdk   = "D:\AndroidSDK"
# ① 编译期假类（带一个游戏真类里**没有**的字段）——它**不进 dex**
javac --release 8 -d "$lab/out/stubs" "$lab/stubs/mindustry/core/GameState.java"
# ② 探针模组（classpath = 假类 + 游戏 jar）
javac --release 8 -cp "$lab/out/stubs;$game" -d "$lab/out/mod" "$lab/src/boom/Boom.java"
# ③ dex（只把 Boom.class 交给 d8；--lib 给 android.jar 就够，d8 不需要超类也能出 dex）
java -cp "$sdk/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 `
     --lib "$sdk/platforms/android-36/android.jar" --output "$lab/out/dex" "$lab/out/mod/boom/Boom.class"
# ④ 打包（mod.hjson 必须在根上；UTF-8 **无 BOM**）
python "$lab/pack.py" $lab
# ⑤ 放到**实验槽**里再启动（别往用户的槽里放）
adb shell "mkdir -p /storage/emulated/0/Android/data/io.mdt.launcher/slot-<实验槽>/mods"
adb push "$lab/stage/crashtest.zip" /storage/emulated/0/Android/data/io.mdt.launcher/slot-<实验槽>/mods/
adb shell am start -n io.mdt.launcher/.MainActivity --es dev_launch_key "import:official-159.apk"
```

## 5. 产品代码在这三份真报告上的三层结论（同一天在同一台设备上跑出来的）

把这三份报告放回槽里，用 dev 口 `dev_crash_analyze <槽>` 跑我们的 `CrashAnalysis`：

| 喂进去的报告 | 结论（`CrashAnalysis.text` 的逐字输出） | 命中层 | 耗时 |
|---|---|---|---|
| 原文 | 是「MDT Crash Probe」引起的 —— 报告里写着「Error loading mod crashtest」 | **L1b**（w3） | 6 ms |
| 把 `Error loading mod crashtest` 改掉 | 很可能与「MDT Crash Probe」有关 —— 异常栈里有它的类 boom.Boom | **L2a**（w3） | 7 ms |
| 再把 `at boom.Boom.init(Boom.java:21)` 删掉 | 很可能与「MDT Crash Probe」有关 —— 它的 classes.dex 里有 definitelyNotAField | **L3**（w3，扫了 1 个 dex） | 9 ms |
| 变体 A（缺依赖）原文 | 是「MDT Dep Probe」引起的 —— 报告里写着「Error loading mod depuser」 | **L1b**（w3） | 10 ms |
| 变体 A 去掉 L1b 句与模组帧 | 很可能与「MDT Dep Probe」有关 —— 它的 classes.dex 里有 depmod.Dep | **L3**（w3） | 19 ms |
| 变体 B（访问权限）原文 | 是「MDT Access Probe」引起的 —— 报告里写着「Error loading mod accessboom」 | **L1b**（w3） | 9 ms |
| 变体 B 去掉 L1b 句与模组帧 | 很可能与「MDT Access Probe」有关 —— 它的 classes.dex 里有 accessboom.Boom | **L3**（w3） | 16 ms |

★ 三层**都能**在这份真报告上出结论（逐层把更便宜的判据拿掉，下一层就接手）；
★ 模组显示名「MDT Crash Probe」是从**槽里的 `mod.hjson`** 查出来的（不是报告里的 `crashtest`）。

## 6. 踩到的两个坑（都与"判据/操作"有关）

1. 🔴 **`minGameVersion` 必须 ≥ 154**：Java 模组在安卓上走 `Vars.minModGameVersion = 154` 那道门，
   写 `146`（桌面实验台一直用的值）⇒ 状态 `unsupported` ⇒ **模组根本不加载，也就不会崩**
   （日志里照样打 `Loading mod:`，极具误导性 —— 与 `evidence/accept-crash-dump.txt` 里那条同源）。
2. ⚠️ **dev 口的 key 是 `import:<文件名>.apk`**，不是界面上那个短名：传 `official-159` 会命中
   `dev_launch_key 未命中`（弹窗里会把可用 key 列出来）。另：`am start --es k ""` **不接受空值**
   （要传空串得整条丢给设备侧 shell，用 `''`）。

## 7. 真实大模组：注入型归因 + L3 的成本（2026-10-08，同一台设备）

前面六节用的都是 **1 KB 的探针包**。这一节换成真家伙：**Neon-vB16.5.jar**
（22.7 MB 包 / `classes.dex` **4835540 B** / `main = bektools.BekToolsMod`）+ **stealth-path.zip**
（0.25 MB 包 / 0.16 MB dex），放进实验槽 `dexlab`，喂三份报告：

| 夹具 | 是什么 | L3 结论（逐字） | 耗时 |
|---|---|---|---|
| `neon-l3-1.txt` | 语料 `09a` 里那份 **Neon 真报告**，去掉**两处**便宜归因 | `Neon / 氖(L3/w2/mindustry.logic.SugarCanvas$SugarStatementElem)` | 75~94 ms |
| `neon-l3-2.txt` | 同上，另一份（v120004） | `Neon / 氖(L3/w2/mindustry.logic.SugarStatements$IfBeginStatement)` | 112~123 ms |
| `neon-worst.txt` | 人造最坏情况：唯一一根针 `bektools.profiler.NeonProfiler` 在 dex 的 **81.8%** 处 | `Neon / 氖(L3/w3/bektools.profiler.NeonProfiler)` | 550~1029 ms |

★ **"注入型"在真机真报告上成立**：模组把类注进 `mindustry.logic.*`，报告里只剩那句
`IllegalAccessError: class mindustry.logic.SugarCanvas$SugarStatementElem tried to access …`
⇒ 命中权重 **w2**（注入进游戏命名空间、带 `$`）就能点名，显示名从 `mod.hjson` 解析出来。
★ `dex=2` ⇒ 两个模组的 dex **都读过**（一个命中、一个不命中）—— 这就是耗时的来源。

★ **成本 A/B（改前/改后各构建一次、各跑 3 遍，原始数据 `evidence/f23-l3-perf.txt`）**：
`Mods.scanDex` 原来每读一块就 `new String(win, "ISO-8859-1")` + 每根针 `String.contains`，
现改为"复用窗口 + **逐字节**比较"：

| 报告 | 针数 | 旧 | 新 |
|---|---|---|---|
| `neon-l3-2` | 28 | 758 / 781 / 761 ms | **112 / 123 / 345 ms** |
| `neon-l3-1` | 15 | 489 / 496 / 491 ms | **75 / 79 / 94 ms** |
| `neon-worst` | 2 | 572 / 630 / 615 ms | 550 / 604 / 1029 ms（**没变**） |
| 合计 | | 1832 / 1904 / 1865 ms | 738 / 807 / 1471 ms |

🔴 **两条结论，只记前半条会得出错误结论**：① 逐字节省掉的是"每块一次 UTF-16 转换 + 每根针一趟
扫描" ⇒ **针越多越省**（真报告 15~28 根针，快约 6×）；② **针很少时开销根本不在搜索**，而在
"打开 22 MB 的包 + 解压出 4.6 MB dex"（首次冷读 0.5~1 s，同一份再来一次掉到几十~一百多 ms）
⇒ 再优化搜索对"第一次看到那份报告"**没有帮助**。
⚠️ 我最初凭单次、冷热混合的两次测量写成"638 ms → ~20 ms"，**受控 A/B 把它否掉了** ⇒ 按实测改口。

★ 两个操作坑（都害过我一次）：① 便宜层的归因其实有**两处**（桌面 `Likely Cause:` 行 **与**
游戏自己写的 `The mod '…' has caused Mindustry to crash.` 那句）—— 只去掉前者 ⇒ L1 直接命中、
**根本没走 L3**，一度以为"真实大模组判不出来"；② `report-devtool.txt` 是**上一轮**的产物，
轮询条件被旧文件满足 ⇒ 早退、拿旧数据当新数据（判据改成"**先删报告文件**再跑"）。
