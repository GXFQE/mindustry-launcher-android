# 游戏侧异常分类总表（来源不限）

> **范围**：只统计**来源于游戏**的异常 —— Mindustry / arc / MindustryX / SaveIO / Rhino 脚本。
> **判定**：异常位置前后 ±420 字里必须出现游戏/arc 标记（`mindustry`·`arc.`·`rhino`·`msav`·`SaveIO`·`ClientLauncher`…）；
> 被这条闸门滤掉的（个人作业 / 其它项目 / 方法名误报）**只计数、不列**：本次滤掉 **8797** 次。
> **来源不限**：桌面 / 安卓 / 历史会话 / 实验台一视同仁，不按来源分列。
> **数据**：2538 个历史会话 + 40 份崩溃/日志文件。

## 🔴 再分两层：谁抛的

| 层 | 含义 | 种数 | 真实日志 | 会话提及 |
|---|---|---|---|---|
| **甲 · 游戏进程内** | Java / arc / Rhino（游戏或模组在游戏进程里抛的） | 58 | 71 | 507 |
| **乙 · 启动器与工具链** | Python / 系统 API（**不是游戏抛的**） | 13 | 0 | 610 |

⇒ **下面所有分族与明细都只针对「甲」**；「乙」单独列在文末（免得把启动器自己的 Python 报错混进游戏崩溃）。

## 🔴 先看计数口径（很重要）

| 列 | 含义 | 能不能当「崩了多少次」 |
|---|---|---|
| **真实日志** | 出现在 `crashes/*.txt`、`last_log.txt`、实验台报告这些**真日志文件**里的次数 | ✅ 可以 —— 每次都是真写下来的 |
| **会话提及** | 出现在历史会话正文里的次数（**含代码块里的异常类定义**、讨论、粘贴的日志） | ❌ **不能** —— 例如 `MatrixValueError` 是当年自己代码里定义的类，被提及 215 次，**一次都没崩过** |

⇒ 所以**判断「这个异常真遇到过几次」只看「真实日志」那一列**；
「会话提及」列的价值是**它出现过，就说明当时关心过**（可能讨论过怎么修）。

## 为什么这么分族

分类的**用途是「能不能归因」**（本项目要做的东西）：同样是崩，
`NoSuchFieldError` 能一句话点到模组，`NullPointerException` 可能连是不是模组引起的都看不出来。
所以每族都标了「**归因可行性**」——对应 `.dsh/research/2026-10-07-crash-attribution-lab.md` 的 L1/L2/L3 判据。

| 族 | 真实日志 | 会话提及 | 种数 | 归因可行性 |
|---|---|---|---|---|
| **链接 / 类加载** | 33 | 176 | 12 | ★ 最高 —— **异常消息里直接带缺失符号**（类型/方法/字段），拿它查模组包内类名即可（L3）；也是「版本更新后接口变了」的主形态 |
| **内存 / 资源** | 0 | 14 | 2 | ⚠️ 要看子类：Java 堆 OOM 归因难（多半是数量问题，非某个模组）；显存 OOM（GL 1285）归因难；原生 hs_err 无报告 |
| **空值** | 0 | 40 | 1 | ⚠️ 中等 —— 栈帧若落在模组包里可归因（L2a/L2b）；落在游戏/引擎包里基本无解 |
| **越界 / 下标** | 0 | 12 | 2 | ⚠️ 中等偏低 —— 常见于「模组加了内容但游戏表没同步」，栈帧常在游戏侧 |
| **类型 / 转换** | 0 | 14 | 2 | ⚠️ 中等 —— 跨版本类型不匹配时栈帧多在模组侧 |
| **状态 / 参数非法** | 0 | 28 | 2 | ⚠️ 中等 —— 常见于调用顺序/环境问题，需看栈 |
| **IO / 文件 / 网络** | 12 | 121 | 8 | ❌ 低 —— 多为环境/网络/路径问题（镜像 403、超时、权限），**不是模组引起** |
| **解析 / 语法** | 0 | 9 | 2 | ⚠️ 中等 —— 数据损坏（存档/地图/蓝图）或模组 JSON 写坏；被游戏 catch 的居多 |
| **反射 / 调用包装** | 0 | 4 | 1 | ⚠️ 低 —— 只是**包装层**，真正原因在 `Caused by:` 里（必须追链） |
| **脚本 / 引擎** | 3 | 0 | 1 | ✅ 较高 —— Rhino 帧名形如 `<模组名>/main.js`，且生成类名里内嵌模组名 |
| **UI / 图形 / 线程** | 23 | 37 | 2 | ⚠️ 混合 —— `UI should be created in main Thread` 这类能追到模组构造器；GL/驱动类不能 |
| **其它 Error** | 0 | 7 | 6 | ⚠️ 需逐个看 |
| **其它 Exception** | 0 | 45 | 17 | ⚠️ 需逐个看（含大量包装类型） |

## 各族明细

⚠️ 每族内**按「真实日志」次数降序**（真实日志为 0 的排在后面 —— 那些是只在会话里出现过的）。

### 链接 / 类加载

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `NoClassDefFoundError` | 8 | 66 | `java.lang.NoClassDefFoundError` | arc/files (wrong name: arc/Files) |
| `ClassNotFoundException` | 8 | 30 | `java.lang.ClassNotFoundException` | mindustry.type.AmmoType |
| `IllegalAccessError` | 8 | 2 | `java.lang.IllegalAccessError` | Field 'WallBuild.hit' is inaccessible to class 'mi2u.graphics.RendererExt'` |
| `NoSuchFieldError` | 7 | 12 | `java.lang.NoSuchFieldError` | patcher |
| `NoSuchMethodError` | 2 | 17 | `NoSuchMethodError` | 'void mindustry.ui.dialogs.ModsDialog.githubImportMod(java.lang.String, boolean, java.lang |
| `NoSuchFieldException` | 0 | 22 | `java.lang.NoSuchFieldException` | textureCache |
| `ExceptionInInitializerError` | 0 | 11 | `java.lang.ExceptionInInitializerError` | Exception java.lang.IllegalArgumentException: The region "white" does not exist! [in threa |
| `UnsatisfiedLinkError` | 0 | 6 | `UnsatisfiedLinkError` | no util in java.library.path: /usr/java/packages/li<path> |
| `LinkageError` | 0 | 5 | `LinkageError` |  |
| `UnsupportedClassVersionError` | 0 | 3 | `java.lang.UnsupportedClassVersionError` | cf/wayzer/scriptAgent/mindustry/Loader has been compiled by a more recent version of the J |
| `NoSuchMethodException` | 0 | 1 | `NoSuchMethodException` |  |
| `AbstractMethodError` | 0 | 1 | `java.lang.AbstractMethodError` |  |

### 内存 / 资源

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `InternalError` | 0 | 12 | `java.lang.InternalError` | Error loading java.security file [in thread "main"] |
| `OutOfMemoryError` | 0 | 2 | `java.lang.OutOfMemoryError` | Failed to allocate a 256 byte allocation with 2148112 free bytes and 2097KB until OOM, tar |

### 空值

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `NullPointerException` | 0 | 40 | `NullPointerException` | Cannot invoke "mindustry.io.SaveVersion.region(String, java.io.DataInput, arc.util.io.Coun |

### 越界 / 下标

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `ArrayIndexOutOfBoundsException` | 0 | 11 | `ArrayIndexOutOfBoundsException` | Index 0 out of bounds for length 0 |
| `IndexOutOfBoundsException` | 0 | 1 | `IndexOutOfBoundsException` |  |

### 类型 / 转换

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `ClassCastException` | 0 | 13 | `ClassCastException` | class java.lang.String cannot be cast to class [B (java.lang.String and [B are in module j |
| `NumberFormatException` | 0 | 1 | `NumberFormatException` |  |

### 状态 / 参数非法

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `IllegalStateException` | 0 | 18 | `java.lang.IllegalStateException` | 本脚本依赖MindustryX v143.102 或更新版本 |
| `IllegalArgumentException` | 0 | 10 | `IllegalArgumentException` | The region "white" does not exist! |

### IO / 文件 / 网络

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `FileNotFoundException` | 12 | 63 | `FileNotFoundException` | 完整Java路径\lib\security\cacerts (系统找不到指定的路径。) |
| `IOException` | 0 | 35 | `java.io.IOException` | Error reading region "content". |
| `ZipException` | 0 | 7 | `java.util.zip.ZipException` | error in opening zip file`** |
| `ConnectException` | 0 | 7 | `java.net.ConnectException` |  |
| `EOFException` | 0 | 3 | `java.io.EOFException` | Unexpected end of ZLIB input stream |
| `SocketTimeoutException` | 0 | 3 | `java.net.SocketTimeoutException` | Connect timed out |
| `ClosedChannelException` | 0 | 2 | `ClosedChannelException` |  |
| `SSLHandshakeException` | 0 | 1 | `javax.net.ssl.SSLHandshakeException` | (certificate_unknown) PKIX path building failed: sun.security.provider.certpath.SunCertPat |

### 解析 / 语法

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `SaveException` | 0 | 8 | `SaveException` |  |
| `ParseException` | 0 | 1 | `ParseException` |  |

### 反射 / 调用包装

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `InvocationTargetException` | 0 | 4 | `java.lang.reflect.InvocationTargetException` |  |

### 脚本 / 引擎

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `JavaScriptException` | 3 | 0 | `rhino.JavaScriptException` | Error: mdtlab js server boom (jstest/main.js#4) |

### UI / 图形 / 线程

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `ArcRuntimeException` | 12 | 14 | `arc.util.ArcRuntimeException` | File not found: recommendMods.json (internal) |
| `RuntimeException` | 11 | 23 | `java.lang.RuntimeException` | UI should be created in main Thread |

### 其它 Error

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `VerifyError` | 0 | 2 | `VerifyError` |  |
| `SecurityError` | 0 | 1 | `SecurityError` | (:) []，PSSecurityException |
| `mod出现了NoClassDefFoundError` | 0 | 1 | `mod出现了NoClassDefFoundError` | mindustry/type/AmmoType。我将按照计划进行搜索。 |
| `ClassFormatError` | 0 | 1 | `ClassFormatError` |  |
| `RedirectStandardError` | 0 | 1 | `RedirectStandardError` |  |
| `ZipError` | 0 | 1 | `java.util.zip.ZipError` |  |

### 其它 Exception

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `SQLException` | 0 | 8 | `java.sql.SQLException` |  |
| `Http$HttpStatusException` | 0 | 8 | `arc.util.Http$HttpStatusException` | HTTP request failed with error: 403 (FORBIDDEN, URL = http<path> |
| `SecurityException` | 0 | 7 | `java.lang.SecurityException` | Shell does not have permission to access user 10 |
| `SaveIO$SaveException` | 0 | 6 | `mindustry.io.SaveIO$SaveException` | java.io.IOException: Error reading region "content". |
| `ExecutionException` | 0 | 2 | `java.util.concurrent.ExecutionException` | java.lang.NoClassDefFoundError: mindustry/type/AmmoType |
| `JsonSyntaxException` | 0 | 2 | `JsonSyntaxException` |  |
| `Mods$ModLoadException` | 0 | 2 | `mindustry.mod.Mods$ModLoadException` | Invalid file: No mod.json found.	at mindustry.mod.Mods.importMod(Mods.java:142)	at mindust |
| `PSSecurityException` | 0 | 1 | `PSSecurityException` |  |
| `CloneNotSupportedException` | 0 | 1 | `CloneNotSupportedException` |  |
| `AndrolibException` | 0 | 1 | `brut.androlib.AndrolibException` |  |
| `Resources$NotFoundException` | 0 | 1 | `Resources$NotFoundException` |  |
| `DiagnosticCoroutineContextException` | 0 | 1 | `kotlinx.coroutines.internal.DiagnosticCoroutineContextException` | [CoroutineName(SAScript-coreMindustry/scoreboard), StandaloneCoroutine{Cancelling}@3f84c25 |
| `UnsupportedOperationException` | 0 | 1 | `UnsupportedOperationException` |  |
| `UnsupportedJavaRuntimeException` | 0 | 1 | `org.gradle.internal.jvm.UnsupportedJavaRuntimeException` |  |
| `NoSuchElementException` | 0 | 1 | `NoSuchElementException` |  |
| `ResourceException` | 0 | 1 | `com.android.tools.r8.ResourceException` | com.android.tools.r8.internal.vc: I/O exception while reading '<path> |
| `SunCertPathBuilderException` | 0 | 1 | `sun.security.provider.certpath.SunCertPathBuilderException` | unable to find valid certification path to requested target |

## ★ 甲层里「真实日志」出现过的异常（9 种）—— 这才是真遇到过的

| # | 异常类 | 真实日志 | 会话提及 |
|---|---|---|---|
| 1 | `ArcRuntimeException` | 12 | 14 |
| 2 | `FileNotFoundException` | 12 | 63 |
| 3 | `RuntimeException` | 11 | 23 |
| 4 | `IllegalAccessError` | 8 | 2 |
| 5 | `NoClassDefFoundError` | 8 | 66 |
| 6 | `ClassNotFoundException` | 8 | 30 |
| 7 | `NoSuchFieldError` | 7 | 12 |
| 8 | `JavaScriptException` | 3 | 0 |
| 9 | `NoSuchMethodError` | 2 | 17 |

---

## 乙 · 启动器与工具链（Python / 系统 API）—— **不是游戏抛的**

> 这些异常来自我们自己写的启动器、脚本与工具链（13 种 / 610 次）。列在这里是为了**不把它们混进游戏崩溃**；做归因时应当直接排除这一层。

| 异常类 | 真实日志 | 会话提及 |
|---|---|---|
| `FileNotFoundError` | 0 | 422 |
| `ValueError` | 0 | 35 |
| `RuntimeError` | 0 | 28 |
| `TclError` | 0 | 24 |
| `WinError` | 0 | 21 |
| `JSONDecodeError` | 0 | 18 |
| `OSError` | 0 | 14 |
| `URLError` | 0 | 14 |
| `KeyError` | 0 | 11 |
| `HTTPError` | 0 | 10 |
| `ImportError` | 0 | 8 |
| `AttributeError` | 0 | 4 |
| `TypeError` | 0 | 1 |

## 完整清单

全部 71 种异常类名与两列计数落在本机 `%TEMP%\mdt-crashlab\taxonomy-full.txt`（**不进仓库**：里面含与项目无关的个人作业 / 工具异常）。
