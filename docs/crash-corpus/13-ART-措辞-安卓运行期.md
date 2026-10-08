# 崩溃语料 · ART 措辞（安卓运行期实测，2026-10-08）

> **这是什么**：**同一个** APK 在**安卓的 ART** 上抛链接期错误时，异常消息的**逐字原文**。
> 用途：崩溃归因的 L3（"缺失符号 vs 包内类名"）判据要按这几种措辞抽针 —— 而
> **ART 与桌面 HotSpot 的措辞完全不同**（`No field x of type I in class La/b/C;` vs
> `Class a.b.C does not have member field 'int x'`）⇒ 只照桌面语料写正则，在手机上**必然瞎**。
>
> **来源**：设备自带 `dalvikvm`（与游戏同一个 ART 运行时），**不碰游戏、不碰用户数据**。
> 造法：把"编译期假类"和"运行期假类"分开编译（同一个类名、成员不同），只把运行期那份打进
> dex ⇒ 精确制造四种链接期错误。配方与源码见 `.dsh/`（一次性实验，不进仓库）。

## 0. 一句话结论

| 错误 | 桌面 HotSpot（语料 01~09 片里那些） | **安卓 ART（本片）** | 抽针能不能复用 |
|---|---|---|---|
| `NoSuchFieldError` | `Class mindustry.core.GameState does not have member field 'int definitelyNotAField'` | `No field definitelyNotAField of type I in class Lmindustry/core/GameState; or its superclasses (declaration of 'mindustry.core.GameState' appears in /data/local/tmp/boom.jar)` | ❌ **两套正则都要写** |
| `NoSuchMethodError` | `'void mindustry.ui.dialogs.ModsDialog.githubImportMod(java.lang.String, …)'` | `No static method definitelyNotAMethod()V in class Lmindustry/core/GameState; or its super classes (declaration of … appears in …)` | ❌ 同上 |
| `IllegalAccessError` | `class X tried to access private field Y.member (… loader …)` / `Field 'WallBuild.hit' is inaccessible to class 'mi2u.graphics.RendererExt'` | `Field 'mindustry.core.GameState.privateAccessField' is inaccessible to class 'boom.Boom' (declaration of 'boom.Boom' appears in …)` | ⚠️ 第二种形态**两种运行时同款** |
| `NoClassDefFoundError` | `mindustry/logic/NotThere`（斜杠） | `Failed resolution of: Lmindustry/logic/NotThere;`（**描述符带 `L`/`;`**） | ❌ 要额外认 `Failed resolution of:` |
| `Caused by: ClassNotFoundException` | `mindustry.logic.NotThere`（点号） | 同款（点号） | ✅ |

★ **顺带两条对判据有用的观察**：
1. ART 会**把"声明在哪个文件"写进消息**（`appears in /data/local/tmp/boom.jar`）—— 游戏里就是
   那个模组包的路径。⚠️ 但**别拿它当归因判据**：桌面没有这段，而且路径里可能只有乱码/私有目录。
2. ART 的消息里**类名是描述符形态**（`Lmindustry/core/GameState;`），而 `(declaration of 'mindustry.core.GameState' …)`
   那段又是**点号**形态 —— 同一句话里两种写法并存（与桌面那条 `NoClassDefFoundError`/`ClassNotFoundException`
   混用斜杠与点号是同一类现象）。

## 1. 探针源码（四分法）

```java
// 编译期假类（src-stubs）：带上运行期**没有**的成员
package mindustry.core;
public class GameState {
    public static int definitelyNotAField = 0;        // → NoSuchFieldError
    public static int privateAccessField = 0;          // 运行期是 private → IllegalAccessError
    public static void definitelyNotAMethod() {}       // → NoSuchMethodError
}
// src-stubs/mindustry/logic/NotThere.java → 只在编译期存在 ⇒ NoClassDefFoundError

// 运行期版（src-run）：同名的"真"类，把上面三个成员拿掉/改私
package mindustry.core;
public class GameState {
    private static int privateAccessField = 0;
    public static int realField = 0;
}

// 探针本体（src-boom）
package boom;
public class Boom {
    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "field";
        if ("field".equals(mode))        mindustry.core.GameState.definitelyNotAField = 1;
        else if ("method".equals(mode))  mindustry.core.GameState.definitelyNotAMethod();
        else if ("access".equals(mode))  mindustry.core.GameState.privateAccessField = 2;
        else if ("noclass".equals(mode)) new mindustry.logic.NotThere();
        System.out.println("NO CRASH (" + mode + ")");
    }
}
```

复现（`--release 8` 是必须的：d8 **读不了** Java 25 的 class 文件，报
`Unsupported class file major version 69`）：

```powershell
javac --release 8 -d out/stubs src-stubs/mindustry/core/GameState.java src-stubs/mindustry/logic/NotThere.java
javac --release 8 -cp out/stubs -d out/boom src-boom/boom/Boom.java
javac --release 8 -d out/run  src-run/mindustry/core/GameState.java
java -cp $SDK/build-tools/36.0.0/lib/d8.jar com.android.tools.r8.D8 --min-api 26 `
     --output out/dex out/boom/boom/Boom.class out/run/mindustry/core/GameState.class
python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'w',zipfile.ZIP_STORED); z.write(sys.argv[2],'classes.dex'); z.close()" out/boom.jar out/dex/classes.dex
adb push out/boom.jar /data/local/tmp/boom.jar
adb shell "dalvikvm -cp /data/local/tmp/boom.jar boom.Boom field"
```

## 2. 四次实测的逐字输出（stderr / stdout 原样）

### 2.1 `field`

```
Exception in thread "main" java.lang.NoSuchFieldError: No field definitelyNotAField of type I in class Lmindustry/core/GameState; or its superclasses (declaration of 'mindustry.core.GameState' appears in /data/local/tmp/boom.jar)
	at boom.Boom.main(Boom.java:8)
```

### 2.2 `method`

```
Exception in thread "main" java.lang.NoSuchMethodError: No static method definitelyNotAMethod()V in class Lmindustry/core/GameState; or its super classes (declaration of 'mindustry.core.GameState' appears in /data/local/tmp/boom.jar)
	at boom.Boom.main(Boom.java:10)
```

### 2.3 `access`

```
Exception in thread "main" java.lang.IllegalAccessError: Field 'mindustry.core.GameState.privateAccessField' is inaccessible to class 'boom.Boom' (declaration of 'boom.Boom' appears in /data/local/tmp/boom.jar)
	at boom.Boom.main(Boom.java:12)
```

### 2.4 `noclass`

```
Exception in thread "main" java.lang.NoClassDefFoundError: Failed resolution of: Lmindustry/logic/NotThere;
	at boom.Boom.main(Boom.java:14)
Caused by: java.lang.ClassNotFoundException: mindustry.logic.NotThere
	... 1 more
```

## 3. 对判据的影响（已落进代码与自检）

* `CrashAnalysis` 的抽针**两套措辞都认**（ART 的 `No field …` / `No static method …` /
  `Failed resolution of: L…;` / `Field '…' is inaccessible to class '…'`，以及 HotSpot 的
  `Class … does not have member field '…'` / 引号方法签名 / 裸类名）。
* 自检 **F23** 里有一条夹具**逐字**用本片 §2.1/§2.4 的原文（ART 形态），并配一条
  "HotSpot 形态仍要认"的对照 —— 两种运行时的措辞各钉一遍。
* ⚠️ ART 里 `I`（`of type I`）是**类型描述符**，不是类名 ⇒ 别把它当针。
