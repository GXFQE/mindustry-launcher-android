# poolspike —— 同名类替换 + 对象池（spike）

验证一个方向：**不物化 APK，直接让游戏的资产读取走对象池**。

## 为什么是这一个类

Mindustry 安卓端走 arc 的 `arc.backend.android.*`（APK 里没有 `com.badlogic.gdx.*` 类）。
整个 dex 里只有两个类持有 `AssetManager`：

- `arc.backend.android.AndroidFiles` —— `get(path, type)` 里 `internal` 一律 `new AndroidFi(assets, path, internal)`；
- `arc.backend.android.AndroidFi` —— 真正做 `open / openFd / list / map` 的那个。

所以只覆盖 `AndroidFi` 一个类即可；`AndroidFiles` 走的是**运行时按名解析**，游戏自己的代码
`new` 出来的仍然是我们的实现（spike 的 ⑫ 号测试就是验这一点）。

注入方式不需要 `injectDex`，只要在构造 classloader 时把 `override.dex` 排在游戏 APK 前面：

```java
new DexClassLoader(overrideDex + File.pathSeparator + gameApk,
                   ctx.getCodeCacheDir(), null, parent)
```

`dexPath` 顺序即优先级；同名类由靠前的 dex 胜出。

## 文件

- `arc/backend/android/AndroidFi.java` —— 覆盖实现：`internal` 先查静态池映射，非 internal 一律 `super.xxx()`；
  `file()` 单独覆盖（`type == local` 要按 `Core.files.getLocalStoragePath()` 重定位，否则 mods / 存档会落到进程 cwd）
- `out/` —— 构建产物（不入库，用下面的命令重建）

## 构建

```bash
JAVAC="/c/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot/bin/javac"
GAME_JAR="Mindustry-shared-campaign-1604-e564f37.jar"      # 只为拿 arc.Fi 的编译期签名
ANDROID_JAR="D:/AndroidSDK/platforms/android-36/android.jar"

"$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn \
    -cp "$GAME_JAR;$ANDROID_JAR" \
    -d tools/poolspike/out/classes \
    tools/poolspike/arc/backend/android/AndroidFi.java

cd tools/poolspike/out
cmd //c "d8.bat --lib D:\AndroidSDK\platforms\android-36\android.jar --min-api 26 \
         --output . classes\arc\backend\android\AndroidFi.class"
mv classes.dex override.dex        # 当前产物 6,440 B
```

## 推到设备并运行

```bash
adb push tools/poolspike/out/override.dex /data/local/tmp/
adb shell "run-as io.mdt.launcher sh -c 'mkdir -p app_hub/poolspike \
  && rm -f app_hub/poolspike/override.dex \
  && cp /data/local/tmp/override.dex app_hub/poolspike/override.dex \
  && chmod 444 app_hub/poolspike/override.dex'"
adb shell am start -n io.mdt.launcher/.MainActivity --es dev_poolspike 1
adb shell "cat /storage/emulated/0/Android/data/io.mdt.launcher/hub/report-devtool.txt"
```

（设备上那份是 444 只读，重推必须先 `rm -f`）

## 实测（HONOR ELP-AN00 / Android 16 / API 36，v160.apk 74.95 MB）

```
① ingest           742 条目 / 83.24 MB / 681 路径 / 904 ms
③ classloader      dexPath = override.dex + 池内 classes.dex，AndroidFi 来自我们的 loader
⑤ read(cursors)    336 B 字节一致
⑥ read(sprites)    4.10 MB sha 一致
⑦ list(sprites)    17 项
⑧ map / openFd     4,303,916 B，直接作用于对象文件
⑨ local 重定位     app_hub/poolspike/localstore/...，isAbsolute=true，read 正常
⑫ 集成             AndroidFiles.get(internal) 返回的就是我们的类
```

结论：**不需要物化包**。资产读取变成普通文件读，`map()` 直接 mmap 对象文件（还能跨实例共享页缓存）。
存储从「池 + 83 MB 物化」降到「池 + 6 KB」；代价是与 arc 的 ABI 绑定，见 `tools/abi-scan/`。

## 兼容性

`override.dex` 只引用 `arc.Fi` 的 12 个方法、声明 24 个 `AndroidFi` 方法；
`AndroidFi` 的 ABI 指纹 `1c2914d5ac42`。34 个真 APK 样本（v104.10 ~ v160.7、X28~X37、B497~B509）全部通过，
只有 libGDX 时代的 `Mindustry_41汉化` 没有 arc。
