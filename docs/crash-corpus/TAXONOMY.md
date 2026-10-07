# 异常分类总表（忽略来源）

> **口径**：不看异常来自哪儿（游戏 / 启动器 / 作业 / 工具），只按**异常本身的族**归类。
> **数据**：2538 个历史会话 + 40 份崩溃/日志文件（桌面临成报告 + 安卓设备报告 + 实验台样本）。
> 异常类名去重 **413** 种。

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
| **链接 / 类加载** | 33 | 373 | 16 | ★ 最高 —— **异常消息里直接带缺失符号**（类型/方法/字段），拿它查模组包内类名即可（L3）；也是「版本更新后接口变了」的主形态 |
| **内存 / 资源** | 0 | 44 | 4 | ⚠️ 要看子类：Java 堆 OOM 归因难（多半是数量问题，非某个模组）；显存 OOM（GL 1285）归因难；原生 hs_err 无报告 |
| **空值** | 0 | 708 | 4 | ⚠️ 中等 —— 栈帧若落在模组包里可归因（L2a/L2b）；落在游戏/引擎包里基本无解 |
| **越界 / 下标** | 0 | 245 | 7 | ⚠️ 中等偏低 —— 常见于「模组加了内容但游戏表没同步」，栈帧常在游戏侧 |
| **类型 / 转换** | 0 | 3173 | 4 | ⚠️ 中等 —— 跨版本类型不匹配时栈帧多在模组侧 |
| **状态 / 参数非法** | 0 | 666 | 6 | ⚠️ 中等 —— 常见于调用顺序/环境问题，需看栈 |
| **IO / 文件 / 网络** | 12 | 1604 | 19 | ❌ 低 —— 多为环境/网络/路径问题（镜像 403、超时、权限），**不是模组引起** |
| **解析 / 语法** | 0 | 146 | 5 | ⚠️ 中等 —— 数据损坏（存档/地图/蓝图）或模组 JSON 写坏；被游戏 catch 的居多 |
| **反射 / 调用包装** | 0 | 9 | 3 | ⚠️ 低 —— 只是**包装层**，真正原因在 `Caused by:` 里（必须追链） |
| **脚本 / 引擎** | 3 | 142 | 4 | ✅ 较高 —— Rhino 帧名形如 `<模组名>/main.js`，且生成类名里内嵌模组名 |
| **断言 / 测试** | 0 | 20 | 1 | ❌ 与应用无关（开发期自检） |
| **数值 / 科学计算** | 0 | 287 | 5 | ❌ 与应用无关（作业/仿真） |
| **UI / 图形 / 线程** | 23 | 107 | 3 | ⚠️ 混合 —— `UI should be created in main Thread` 这类能追到模组构造器；GL/驱动类不能 |
| **其它 Error** | 0 | 2039 | 124 | ⚠️ 需逐个看 |
| **其它 Exception** | 0 | 351 | 208 | ⚠️ 需逐个看（含大量包装类型） |

## 各族明细

⚠️ 每族内**按「真实日志」次数降序**（真实日志为 0 的排在后面 —— 那些是只在会话里出现过的）。

### 链接 / 类加载

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `NoClassDefFoundError` | 8 | 113 | `java.lang.NoClassDefFoundError` | arc/files (wrong name: arc/Files) |
| `ClassNotFoundException` | 8 | 43 | `ClassNotFoundException` | mindustry.type.AmmoType |
| `IllegalAccessError` | 8 | 12 | `java.lang.IllegalAccessError` | Field 'WallBuild.hit' is inaccessible to class 'mi2u.graphics.RendererExt'` |
| `NoSuchFieldError` | 7 | 13 | `java.lang.NoSuchFieldError` | patcher |
| `NoSuchMethodError` | 2 | 29 | `NoSuchMethodError` | 'void mindustry.ui.dialogs.ModsDialog.githubImportMod(java.lang.String, boolean, java.lang |
| `ImportError` | 0 | 65 | `ImportError` | pass |
| `NoSuchFieldException` | 0 | 23 | `java.lang.NoSuchFieldException` | textureCache |
| `UnsatisfiedLinkError` | 0 | 21 | `java.lang.UnsatisfiedLinkError` | no util in java.library.path: /usr/java/packages/li<path> |
| `ModuleNotFoundError` | 0 | 18 | `ModuleNotFoundError` | No module named 'fenics' |
| `ExceptionInInitializerError` | 0 | 16 | `java.lang.ExceptionInInitializerError` | Exception java.lang.IllegalArgumentException: The region "white" does not exist! [in threa |
| `LinkageError` | 0 | 7 | `LinkageError` |  |
| `UnsupportedClassVersionError` | 0 | 4 | `java.lang.UnsupportedClassVersionError` | cf/wayzer/scriptAgent/mindustry/Loader has been compiled by a more recent version of the J |
| `IncompatibleClassChangeError` | 0 | 3 | `IncompatibleClassChangeError` |  |
| `AbstractMethodError` | 0 | 3 | `java.lang.AbstractMethodError` |  |
| `NoSuchMethodException` | 0 | 2 | `NoSuchMethodException` |  |
| `TypeNotPresentException` | 0 | 1 | `java.lang.TypeNotPresentException` |  |

### 内存 / 资源

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `InternalError` | 0 | 30 | `java.lang.InternalError` | Error loading java.security file [in thread "main"] |
| `OutOfMemoryError` | 0 | 8 | `OutOfMemoryError` | Direct buffer memory`。很多游戏框架在处理纹理、VBO 时用到直接内存，这些一般受 `-XX:MaxDirectMemorySize` 控制，默认等于 `-Xm |
| `MemoryError` | 0 | 5 | `MemoryError` |  |
| `StackOverflowError` | 0 | 1 | `java.lang.StackOverflowError` |  |

### 空值

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `NameError` | 0 | 240 | `NameError` | 'puts'未定义） |
| `AttributeError` | 0 | 172 | `AttributeError` | self.undo_btn.config(state=tk.DISABLED) |
| `UnboundLocalError` | 0 | 165 | `UnboundLocalError` | cannot access local variable 'language' where it is not associated with a value |
| `NullPointerException` | 0 | 131 | `NullPointerException` | Cannot invoke "mindustry.io.SaveVersion.region(String, java.io.DataInput, arc.util.io.Coun |

### 越界 / 下标

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `IndexError` | 0 | 120 | `IndexError` | # 超出索引，忽略本次合成 |
| `KeyError` | 0 | 108 | `KeyError` | # 处理可能的键不存在的情况？ |
| `ArrayIndexOutOfBoundsException` | 0 | 12 | `ArrayIndexOutOfBoundsException` | Index 0 out of bounds for length 0 |
| `IndexOutOfBoundsException` | 0 | 2 | `IndexOutOfBoundsException` |  |
| `ArrayStoreException` | 0 | 1 | `java.lang.ArrayStoreException` |  |
| `NegativeArraySizeException` | 0 | 1 | `java.lang.NegativeArraySizeException` |  |
| `StringIndexOutOfBoundsException` | 0 | 1 | `java.lang.StringIndexOutOfBoundsException` |  |

### 类型 / 转换

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `ValueError` | 0 | 2176 | `ValueError` | messagebox.showinfo("Info", "No more steps to undo!") |
| `TypeError` | 0 | 973 | `TypeError` | # 如果self.game.last是一个矩阵，np.isnan会抛出TypeError，说明存在上一步 |
| `ClassCastException` | 0 | 17 | `ClassCastException` | class java.lang.String cannot be cast to class [B (java.lang.String and [B are in module j |
| `NumberFormatException` | 0 | 7 | `NumberFormatException` |  |

### 状态 / 参数非法

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `RuntimeError` | 0 | 560 | `RuntimeError` | messagebox.showerror('加载失败','存档的版本或时间存在异常') |
| `IllegalArgumentException` | 0 | 82 | `IllegalArgumentException` | The region "white" does not exist! |
| `IllegalStateException` | 0 | 20 | `java.lang.IllegalStateException` | 本脚本依赖MindustryX v143.102 或更新版本 |
| `ConcurrentModificationException` | 0 | 2 | `java.util.ConcurrentModificationException` |  |
| `IllegalMonitorStateException` | 0 | 1 | `java.lang.IllegalMonitorStateException` |  |
| `IllegalThreadStateException` | 0 | 1 | `java.lang.IllegalThreadStateException` |  |

### IO / 文件 / 网络

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `FileNotFoundException` | 12 | 97 | `FileNotFoundException` | 完整Java路径\lib\security\cacerts (系统找不到指定的路径。) |
| `FileNotFoundError` | 0 | 894 | `FileNotFoundError` | self.highest=0 |
| `PermissionError` | 0 | 202 | `PermissionError` | messagebox.showerror('保存失败','文件操作权限不足') |
| `OSError` | 0 | 185 | `OSError` | pass |
| `IOException` | 0 | 96 | `IOException` | keystore password was incorrect |
| `UnicodeDecodeError` | 0 | 46 | `UnicodeDecodeError` | print("解码错误") |
| `SocketTimeoutException` | 0 | 15 | `java.net.SocketTimeoutException` | Connect timed out |
| `IOError` | 0 | 14 | `IOError` | 在Python3中，IOError已经被合并到OSError中，但为了兼容，仍然存在，但实际是OSError的别名。 |
| `ConnectException` | 0 | 11 | `java.net.ConnectException` |  |
| `UnicodeEncodeError` | 0 | 9 | `UnicodeEncodeError` |  |
| `ZipException` | 0 | 8 | `java.util.zip.ZipException` | error in opening zip file`** |
| `SSLHandshakeException` | 0 | 8 | `javax.net.ssl.SSLHandshakeException` | Remote host terminated the handshake”。 |
| `ClosedChannelException` | 0 | 6 | `java.nio.channels.ClosedChannelException` |  |
| `EOFException` | 0 | 4 | `java.io.EOFException` | Unexpected end of ZLIB input stream |
| `IsADirectoryError` | 0 | 2 | `IsADirectoryError` |  |
| `NotADirectoryError` | 0 | 2 | `NotADirectoryError` |  |
| `URISyntaxException` | 0 | 2 | `java.net.URISyntaxException` | Illegal character in path at index 15: fil<path> |
| `UnknownHostException` | 0 | 2 | `java.net.UnknownHostException` |  |
| `SocketException` | 0 | 1 | `java.net.SocketException` |  |

### 解析 / 语法

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `SyntaxError` | 0 | 104 | `SyntaxError` | Non-ASCII character '\xe4' in file ... |
| `IndentationError` | 0 | 23 | `IndentationError` | expected an indented block |
| `SaveException` | 0 | 12 | `SaveException` |  |
| `TokenError` | 0 | 5 | `TokenError` | ('unexpected character after line continuation character', (1, 64)) |
| `ParseException` | 0 | 2 | `ParseException` |  |

### 反射 / 调用包装

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `InvocationTargetException` | 0 | 7 | `java.lang.reflect.InvocationTargetException` |  |
| `ReflectiveOperationException` | 0 | 1 | `java.lang.ReflectiveOperationException` |  |
| `UndeclaredThrowableException` | 0 | 1 | `java.lang.reflect.UndeclaredThrowableException` |  |

### 脚本 / 引擎

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `JavaScriptException` | 3 | 0 | `rhino.JavaScriptException` | Error: mdtlab js server boom (jstest/main.js#4) |
| `RecursionError` | 0 | 130 | `RecursionError` | print(f"递归上限: {sys.getrecursionlimit()}") |
| `ReferenceError` | 0 | 8 | `ReferenceError` | puts is not defined |
| `RangeError` | 0 | 4 | `RangeError` | Maximum call stack size exceeded`。 |

### 断言 / 测试

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `AssertionError` | 0 | 20 | `AssertionError` | 'bar.txt' != 'foo.txt' |

### 数值 / 科学计算

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `LinAlgError` | 0 | 102 | `np.linalg.LinAlgError` | # 如果求解失败，使用特征值分解 |
| `ZeroDivisionError` | 0 | 101 | `ZeroDivisionError` | # 如果self(x)==0，那么考虑指数other(x)是否大于1 |
| `OverflowError` | 0 | 74 | `OverflowError` | math range error（OverflowError: (34, 'Result too large')） |
| `FloatingPointError` | 0 | 8 | `FloatingPointError` | print(f"数值错误: {exc_val}") |
| `ArithmeticError` | 0 | 2 | `ArithmeticError` |  |

### UI / 图形 / 线程

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `ArcRuntimeException` | 12 | 14 | `arc.util.ArcRuntimeException` | File not found: recommendMods.json (internal) |
| `RuntimeException` | 11 | 44 | `java.lang.RuntimeException` | 密钥库加载: C:\Users\<user>\my.keystore (系统找不到指定的文件。) |
| `TclError` | 0 | 49 | `tk.TclError` | self._safe_cleanup() |

### 其它 Error

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `GameError` | 0 | 396 | `GameError` | pass |
| `MatrixValueError` | 0 | 215 | `MatrixValueError` | # If rank deficient, we break and use the diagonal of A |
| `MatrixTypeError` | 0 | 183 | `MatrixTypeError` | Not a matrix）。所以如果 other 是空矩阵，会导致这里构造 Matrix 失败，但会主动抛 MatrixTypeError。还行。 |
| `MatrixNotImplementedError` | 0 | 142 | `MatrixNotImplementedError` | If method is not supported |
| `JSONDecodeError` | 0 | 135 | `json.JSONDecodeError` | raise ValueError("存档文件格式错误") |
| `WinError` | 0 | 105 | `WinError` |  |
| `PyExc_TypeError` | 0 | 84 | `PyExc_TypeError` |  |
| `MatrixError` | 0 | 77 | `MatrixError` |  |
| `NotImplementedError` | 0 | 60 | `NotImplementedError` | if not can_be_called_with_fake_globals(c_a): |
| `MatrixPermissionError` | 0 | 59 | `MatrixPermissionError` |  |
| `reportError` | 0 | 48 | `com.sun.org.apache.xerces.internal.impl.XMLErrorReporter.reportError` |  |
| `MatrixIndexError` | 0 | 47 | `MatrixIndexError` | Non-existent element. |
| `PyExc_RuntimeError` | 0 | 44 | `PyExc_RuntimeError` |  |
| `EOFError` | 0 | 27 | `EOFError` | break |
| `URLError` | 0 | 25 | `urllib.error.URLError` |  |
| `PyExc_ValueError` | 0 | 24 | `PyExc_ValueError` |  |
| `MyLibError` | 0 | 24 | `MyLibError` | Invalid value: -1. Details: Internal error: negative value not allowed |
| `SystemError` | 0 | 20 | `SystemError` | bad local variable name 或类似错误。让我们想一下：如果删除所有局部变量，print(x) 的 x 是未定义的，会 NameError 吗？不，因为字节码使用 |
| `TabError` | 0 | 19 | `TabError` |  |
| `NoMethodError` | 0 | 17 | `NoMethodError` | undefined method `sprintf' for nil:NilClass |
| `HTTPError` | 0 | 15 | `urllib.error.HTTPError` |  |
| `GetLastError` | 0 | 13 | `GetLastError` |  |
| `PyExc_SystemError` | 0 | 12 | `PyExc_SystemError` |  |
| `reportSchemaError` | 0 | 12 | `com.sun.org.apache.xerces.internal.impl.xs.XMLSchemaValidator.reportSchemaError` |  |
| `CustomLibraryError` | 0 | 11 | `CustomLibraryError` | Invalid argument |
| … | | | | 还有 99 种（完整清单见本机 `%TEMP%\mdt-crashlab\taxonomy-full.txt`） |

### 其它 Exception

| 异常类 | 真实日志 | 会话提及 | 完整类名（最常见那支） | 样例消息 |
|---|---|---|---|---|
| `BaseException` | 0 | 49 | `BaseException` | exceptions[name] = obj |
| `SQLException` | 0 | 15 | `java.sql.SQLException` |  |
| `SAXParseException` | 0 | 12 | `org.xml.sax.SAXParseException` |  |
| `createSAXParseException` | 0 | 12 | `com.sun.org.apache.xerces.internal.util.ErrorHandlerWrapper.createSAXParseException` |  |
| `SecurityException` | 0 | 9 | `java.lang.SecurityException` | uid 1000 does not have android.permission.INTERNAL_SYSTEM_WINDOW. 这可能不是关键。 |
| `Http$HttpStatusException` | 0 | 8 | `arc.util.Http$HttpStatusException` | HTTP request failed with error: 403 (FORBIDDEN, URL = http<path> |
| `SaveIO$SaveException` | 0 | 6 | `mindustry.io.SaveIO$SaveException` | java.io.IOException: Error reading region "content". |
| `SunCertPathBuilderException` | 0 | 6 | `sun.security.provider.certpath.SunCertPathBuilderException` | unable to find valid certification path to requested target |
| `TimeoutException` | 0 | 4 | `TimeoutException` | raise ValueError("Evaluation took too long") |
| `GetHRForException` | 0 | 4 | `Marshal.GetHRForException` |  |
| `CommandNotFoundException` | 0 | 3 | `CommandNotFoundException` |  |
| `ExecutionException` | 0 | 3 | `java.util.concurrent.ExecutionException` | java.lang.NoClassDefFoundError: mindustry/type/AmmoType |
| `toConnectException` | 0 | 3 | `jdk.internal.net.http.common.Utils.toConnectException` |  |
| `InterruptedException` | 0 | 3 | `InterruptedException` |  |
| `InstantiationException` | 0 | 3 | `InstantiationException` |  |
| `IllegalAccessException` | 0 | 3 | `IllegalAccessException` |  |
| `CertPathValidatorException` | 0 | 3 | `java.security.cert.CertPathValidatorException` |  |
| `CloneNotSupportedException` | 0 | 2 | `CloneNotSupportedException` |  |
| `FrequentRequestException` | 0 | 2 | `FrequentRequestException` |  |
| `Resources$NotFoundException` | 0 | 2 | `Resources$NotFoundException` |  |
| `UnrecoverableKeyException` | 0 | 2 | `java.security.UnrecoverableKeyException` | failed to decrypt safe contents entry: javax.crypto.BadPaddingException: Given final block |
| `UnsupportedOperationException` | 0 | 2 | `UnsupportedOperationException` |  |
| `InaccessibleObjectException` | 0 | 2 | `InaccessibleObjectException` |  |
| `handleException` | 0 | 2 | `mindustryX.features.MetricCollector.handleException` |  |
| `JsonSyntaxException` | 0 | 2 | `JsonSyntaxException` |  |
| … | | | | 还有 183 种（完整清单见本机 `%TEMP%\mdt-crashlab\taxonomy-full.txt`） |

## ★ 只在「真实日志」里出现过的异常（9 种）—— 这才是真遇到过的

| # | 异常类 | 真实日志 | 会话提及 |
|---|---|---|---|
| 1 | `ArcRuntimeException` | 12 | 14 |
| 2 | `FileNotFoundException` | 12 | 97 |
| 3 | `RuntimeException` | 11 | 44 |
| 4 | `IllegalAccessError` | 8 | 12 |
| 5 | `NoClassDefFoundError` | 8 | 113 |
| 6 | `ClassNotFoundException` | 8 | 43 |
| 7 | `NoSuchFieldError` | 7 | 13 |
| 8 | `JavaScriptException` | 3 | 0 |
| 9 | `NoSuchMethodError` | 2 | 29 |

## 完整清单

全部 413 种异常类名与两列计数落在本机 `%TEMP%\mdt-crashlab\taxonomy-full.txt`（**不进仓库**：里面含与项目无关的个人作业 / 工具异常）。
