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

## 1. 逐字报告（`slot-crashlab/crashes/crash_1791434741685.txt`，1701 B）

> 现场：槽 `crashlab`（实验用完已删）、游戏 = 导入的 `official-159.apk`（**159.7**）、
> mods 目录里只有这一个探针包 `crashtest.zip`（1045 B）。

```
{report}
```

## 2. 同一次启动的 `last_log.txt`（逐字）

```
{log}
```

★ 三行是"模组真的跑起来了"的判据：`MDTDEV: Boom class loaded`（类被加载）、
`Loading mod: crashtest`（游戏认了它）、`MDTDEV: init ran`（`init()` 真的进了）
—— 最后一行之后立刻就是链接期错误。

## 3. 这次实验钉死的六条事实

| # | 事实 | 判据 |
|---|---|---|
| 1 | **报告里没有 `Likely Cause:`** | 全文没有这一行（桌面 §2.1 的结论，安卓同款）—— 载入期崩游戏自己归因不了 |
| 2 | **ART 措辞进了真报告** | `Caused by: java.lang.NoSuchFieldError: No field definitelyNotAField of type I in class Lmindustry/core/GameState; …` |
| 3 | 那句 `appears in <路径>` 里的路径是**游戏本体 APK**（`app_hub/import/official-159.apk`），**不是**模组包 | ⚠️ 所以**不能**拿它当"这是哪个模组的证据"（13 片里已经警告过） |
| 4 | 模组自己的栈帧带**行号**：`at boom.Boom.init(Boom.java:21)` | 与 Rhino 那种 `(Unknown Source:47)` 不同；两种都被 FRAME 正则接住 |
| 5 | 合成帧形如 `…$$ExternalSyntheticLambda0.get(R8$$SyntheticClass:134)` | dex 被 R8 处理过的痕迹；判据里对它**不特殊处理**（它落在游戏命名空间，权重 1） |
| 6 | **多出来的类名不会误伤**：`Mods:` 行只有 `crashtest:1.0` | 归因只看这一行 + 报告里的符号/帧（L1b 一行就点名了） |

## 4. 探针模组（源码逐字）

★ 造法与 `.dsh/research/2026-10-07-crash-attribution-lab.md` §8 同源，但这里是**给安卓用的 dex 版**：

```java
{stub}
```

```java
{boom}
```

```hjson
{hjson}
```

## 5. 配方（命令级，PC 侧）

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

## 6. 产品代码在**这份真报告**上的三层结论（同一天在同一台设备上跑出来的）

把这份报告放回槽里，用 dev 口 `dev_crash_analyze <槽>` 跑我们的 `CrashAnalysis`：

| 喂进去的报告 | 结论（`CrashAnalysis.text` 的逐字输出） | 命中层 | 耗时 |
|---|---|---|---|
| 原文 | 是「MDT Crash Probe」引起的 —— 报告里写着「Error loading mod crashtest」 | **L1b**（w3） | 6 ms |
| 把 `Error loading mod crashtest` 改掉 | 很可能与「MDT Crash Probe」有关 —— 异常栈里有它的类 boom.Boom | **L2a**（w3） | 7 ms |
| 再把 `at boom.Boom.init(Boom.java:21)` 删掉 | 很可能与「MDT Crash Probe」有关 —— 它的 classes.dex 里有 definitelyNotAField | **L3**（w3，扫了 1 个 dex） | 9 ms |

★ 三层**都能**在这份真报告上出结论（逐层把更便宜的判据拿掉，下一层就接手）；
★ 模组显示名「MDT Crash Probe」是从**槽里的 `mod.hjson`** 查出来的（不是报告里的 `crashtest`）。

## 7. 踩到的两个坑（都与"判据/操作"有关）

1. 🔴 **`minGameVersion` 必须 ≥ 154**：Java 模组在安卓上走 `Vars.minModGameVersion = 154` 那道门，
   写 `146`（桌面实验台一直用的值）⇒ 状态 `unsupported` ⇒ **模组根本不加载，也就不会崩**
   （日志里照样打 `Loading mod:`，极具误导性 —— 与 `evidence/accept-crash-dump.txt` 里那条同源）。
2. ⚠️ **dev 口的 key 是 `import:<文件名>.apk`**，不是界面上那个短名：传 `official-159` 会命中
   `dev_launch_key 未命中`（弹窗里会把可用 key 列出来）。另：`am start --es k ""` **不接受空值**
   （要传空串得整条丢给设备侧 shell，用 `''`）。
