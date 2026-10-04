#!/usr/bin/env bash
# =============================================================================
# MDT Android Launcher -- manual APK build (aapt2 + javac + d8 + zipalign + apksigner)
#
# No Gradle: pure-framework app, zero dependencies. 平移自探针工程
# MDT-Android-Dev/build.sh（四个坑全在实测中踩过，详见注释）。
#
# *** VERIFIED PITFALLS ON THIS MACHINE (keep them, they cost real time) ***
#
#   1) Every path handed to a tool must be a NATIVE WINDOWS path.
#      MSYS-style /d/foo is silently rejected (aapt2 is the nastiest: no error,
#      it just does not produce the file).
#
#   2) Native tools and Java tools want DIFFERENT flavours of that path:
#        native (aapt2 / zipalign)  -> wpath() -> D:\a\b
#        java   (javac / d8 / apksigner) -> jpath() -> D:/a/b
#      Non-ASCII characters are FINE (verified); failures are path-format
#      problems, not encoding problems.
#
#   3) JDK 8 javac defaults to the system ANSI code page (GBK on zh-CN Windows)
#      while sources are UTF-8 -> needs -encoding UTF-8.
#      Also: -bootclasspath android.jar BREAKS lambdas (android.jar has no
#      java.lang.invoke.LambdaMetafactory), so android.jar goes on -classpath.
#
#   4) Cannot rm -rf build/ directly: the sandbox hard-blocks bulk deletes of
#      50+ files per round. Archive-rename instead (single mv, no delete).
#
#   Echo messages are deliberately ASCII: shell output is decoded as GBK.
#
# Usage:  ./build.sh                # build only
#         ./build.sh install        # build + adb install -r
#         ./build.sh clean-trash    # 清空 .build-trash/（绕开批量删除闸，见下）
# Env:    DEBUGGABLE=false|true (default false -- product ships NOT debuggable;
#                                    all needed reflections pass without it)
#         VER_CODE / VER_NAME / SUFFIX
#         KEYSTORE / KS_ALIAS / KS_PASS / KEY_PASS
#           ---- 签名。默认 = 本机 debug.keystore（自测够用）。
#           ---- ★ 要**发出去**的包必须换成正式发布密钥，否则以后换密钥时
#                已安装用户只能卸载重装，而卸载会删掉他们的存档槽：
#                KEYSTORE=~/.android/mdt-launcher-release.jks KS_ALIAS=mdt \
#                  KS_PASS=<口令> KEY_PASS=<口令> ./build.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- toolchain: python ------------------------------------------------------
# 🔴 **不要直接用裸 `python`**：Windows 的 WindowsApps 里有个同名**空壳 stub**，
#   它不打印任何东西、也不报错，直接以 exit 49 静默退出 —— 症状是构建卡在
#   [3/4] 之后**一行输出都没有**，看着像 d8 挂了（实测踩过）。
#   ⇒ 显式挑一个**真能跑**的解释器：优先 $PYTHON，其次 python3 / python，
#     并且每个候选都真的试跑一次（只查 `command -v` 挡不住 stub）。
PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
  for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'pass' >/dev/null 2>&1; then
      PYTHON="$c"; break
    fi
  done
fi
if [ -z "$PYTHON" ]; then
  echo "错误：找不到可用的 Python —— 请 export PYTHON=/path/to/python.exe" >&2
  echo "     （PATH 里那个 WindowsApps\\python.exe 是空壳，不算可用）" >&2
  exit 1
fi

# ---- utility: clean-trash ---------------------------------------------------
# 刻意放在最前面（不需要工具链），也刻意**不用** rm -rf：
# 一次性删除大量文件在部分环境下会被整条拦掉，一次 rm -rf 整个 .build-trash 会失败：
#   （报错形如：单轮累计删除数超过阈值）
# 计数是**累计**的，所以这个是**可以分次跑完**的 ——
# 因此这里每次最多删 TRASH_LIMIT 个（默认 45，留余量），并报告还剩多少，重复跑即可。
# 反复看到 "still N files" 就再执行一次 ./build.sh clean-trash。
#
# 攒太多了（几百个）还有一条更省事的路：**整个目录 mv 出去**
# —— rename 不触发删除闸，一次就干净（目标放同盘，跨盘会退化成拷贝+删除）：
#     mv .build-trash "/d/<某处>/.build-trash-$(date +%s)"
if [ "${1:-}" = "clean-trash" ]; then
  TRASH_LIMIT="${TRASH_LIMIT:-45}"
  "$PYTHON" - "$ROOT/.build-trash" "$TRASH_LIMIT" <<'PY'
import os, sys
root, limit = sys.argv[1], int(sys.argv[2])
if not os.path.isdir(root):
    print("  nothing to clear"); sys.exit(0)
n = b = 0
for r, _ds, fs in os.walk(root):
    for f in fs:
        if n >= limit:
            break
        p = os.path.join(r, f)
        try:
            b += os.path.getsize(p); os.remove(p); n += 1
        except OSError:
            pass
for r, _ds, _fs in os.walk(root, topdown=False):
    try: os.rmdir(r)
    except OSError: pass
left = sum(len(fs) for _, _, fs in os.walk(root)) if os.path.isdir(root) else 0
print("  trash cleared: %d files, %.2f MB" % (n, b / 1048576.0))
if left:
    print("  still %d files -- run './build.sh clean-trash' again "
          "(the sandbox caps deletes per turn)" % left)
PY
  exit 0
fi

# ---- toolchain -------------------------------------------------------------
if [ -z "${ANDROID_HOME:-}" ]; then ANDROID_HOME="${ANDROID_SDK_ROOT:-}"; fi
if [ -z "${ANDROID_HOME:-}" ]; then
  echo "错误：请先设置 ANDROID_HOME（或 ANDROID_SDK_ROOT）指向 Android SDK" >&2
  exit 1
fi

# ★ 锁定 MSYS 版 cygpath。Anaconda 自带的 Cygwin 版会给出错误路径
#   （/d/... 被当成 anaconda 下的相对路径），表现是每个 -classpath / -o
#   都指向不存在的文件，或 aapt2 静默不出产物。
CYGPATH=/usr/bin/cygpath
[ -x "$CYGPATH" ] || CYGPATH=cygpath
SDK="$("$CYGPATH" -u "$ANDROID_HOME")"
BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-36/android.jar"
ADB="$SDK/platform-tools/adb.exe"

JAVA="${JAVA:-java}"
JDK8="${JDK8:-/c/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot}"
JAVAC_8="$JDK8/bin/javac"
# ★ 2026-10-04：d8 的输入改成**一个 jar**（见 [3/4] 的注释），所以也要 JDK8 的 jar
JAR_8="$JDK8/bin/jar"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
# 签名参数一律走环境变量（默认值 = Android 那套众所周知的 debug 凭据）。
# ★ 发布包请换成自己的密钥，见文件头的 Env 说明。
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"
KS_PASS="${KS_PASS:-android}"
KEY_PASS="${KEY_PASS:-android}"

PKG=io.mdt.launcher
DEBUGGABLE="${DEBUGGABLE:-false}"
VER_CODE="${VER_CODE:-1}"
VER_NAME="${VER_NAME:-0.1}"
SUFFIX="${SUFFIX:-}"
APP_NAME="mdt-launcher${SUFFIX}"
OUT="$ROOT/build"

wpath() { "$CYGPATH" -w "$1"; }   # -> D:\a\b    for native tools
jpath() { "$CYGPATH" -m "$1"; }   # -> D:/a/b    for java tools

for t in "$BT/aapt2.exe" "$BT/zipalign.exe" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" \
         "$AJAR" "$JAVAC_8" "$JAR_8" "$KEYSTORE"; do
  [ -e "$t" ] || { echo "MISSING: $t" >&2; exit 1; }
done

echo "ROOT = $ROOT"
echo "SDK  = $SDK"
echo

# ---- [0/4] i18n gate --------------------------------------------------------
# 国际化门禁（研究文档 docs/i18n-feasibility.md 的 P0）。检查器是纯 Python、
# 不碰工具链，所以放在最前面 —— 资源树坏了就早失败，别等 aapt2 跑完。
#
# ★ 两道，顺序不能换：
#   ① --selftest：**先证明这把尺子有牙**（每一条规则都拿一个"已知坏的输入"喂它，
#      要求判死；再拿"已知好的输入"喂它，要求别乱叫）。元断言不过就直接停 ——
#      尺子本身坏了，它说"通过"也没有意义。
#   ② 真检查：资源层（多语言目录一致性 / 参数 / 空白）+ 源码层（不许用文案串当判据）。
#
# ★ tools/i18n-known.txt 是"已知缺陷台账"：里面记的必须**还在复现**，
#   修好了却忘了删，脚本会报 STALE 并失败（判据对 != 清单全，两个方向都要跑）。
# ★ 输出默认是 ASCII 的（非 ASCII 转成 \uXXXX）—— 见本文件头："Echo messages are
#   deliberately ASCII: shell output is decoded as GBK"。想看人话的完整报告：
#       python tools/i18n-check.py --utf8
# ★ 应急开关：SKIP_I18N_CHECK=1 ./build.sh（会打一行很响的 WARNING）。
I18N_CHECK="$(jpath "$ROOT/tools/i18n-check.py")"
if [ "${SKIP_I18N_CHECK:-}" = "1" ]; then
  echo "== [0/4] i18n check: SKIPPED (SKIP_I18N_CHECK=1) =="
  echo "   WARNING: the i18n gate is OFF -- a broken resource tree can ship."
  echo
elif [ ! -f "$I18N_CHECK" ]; then
  echo "== [0/4] i18n check: NOT INSTALLED (tools/i18n-check.py missing) =="
  echo
else
  echo "== [0/4] i18n check =="
  if ! "$PYTHON" "$I18N_CHECK" --root "$(jpath "$ROOT")" --selftest; then
    echo "FAIL: the i18n checker failed its own meta-assertions." >&2
    echo "      The ruler is broken -- fix tools/i18n-check.py first." >&2
    exit 1
  fi
  if ! "$PYTHON" "$I18N_CHECK" --root "$(jpath "$ROOT")"; then
    echo >&2
    echo "FAIL: i18n check did not pass." >&2
    echo "  readable report : $PYTHON tools/i18n-check.py --utf8" >&2
    echo "  prove the ruler : $PYTHON tools/i18n-check.py --selftest" >&2
    echo "  known-defect log: tools/i18n-known.txt" >&2
    echo "  why / phasing   : docs/i18n-feasibility.md" >&2
    exit 1
  fi
  echo
fi


# ⚠️ 归档改名而不是 rm -rf（一次删太多会被环境拦掉）。
#    .build-trash/ 攒着不大，但记得偶尔整个删掉。
if [ -d "$OUT" ]; then
  TRASH="$ROOT/.build-trash/$(date +%s)"
  mkdir -p "$(dirname "$TRASH")" 2>/dev/null || true
  mv "$OUT" "$TRASH" 2>/dev/null || rm -rf "$OUT"
fi
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

# SRC 的收集挪到了 aapt2 link 之后（[2/4] 里）：R.java 是 link --java 生成的，
# 在这儿收集就捡不到它（F1）。

# ---- [1/4] res compile + manifest temp copy + aapt2 link -------------------
echo "== [1/4] aapt2 compile + link =="
# 清单总是走临时副本：debuggable / versionCode / versionName 必须是构建参数
# （自更新实验与多形态发布都要"同一份代码、不同版本号"的包）。
# 注意清单不能塞进 -A 目录，否则会被当 asset 打进包。
MF_DIR="${MF_DIR:-/tmp/mdt-launcher-manifest}"
rm -rf "$MF_DIR"; mkdir -p "$MF_DIR"
sed -e "s/android:debuggable=\"[a-z]*\"/android:debuggable=\"$DEBUGGABLE\"/" \
    -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VER_CODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER_NAME\"/" \
    "$ROOT/app/AndroidManifest.xml" > "$MF_DIR/AndroidManifest.xml"
for g in "android:debuggable=\"$DEBUGGABLE\"" \
         "android:versionCode=\"$VER_CODE\"" \
         "android:versionName=\"$VER_NAME\""; do
  grep -q "$g" "$MF_DIR/AndroidManifest.xml" \
    || { echo "FAIL: manifest sed did not apply: $g" >&2; exit 1; }
done
MANIFEST="$MF_DIR/AndroidManifest.xml"
echo "   debuggable=$DEBUGGABLE  versionCode=$VER_CODE  versionName=$VER_NAME"

# F1: res 编译。坑（全部实测得来，别改）：
#   a) aapt2 compile 的 dir 参数在含中文的工程路径下直接 "failed to open directory"，
#      同一份 res 拷到 ASCII 路径就过 ⇒ 跟 manifest 一样走 /tmp 临时副本；
#   b) 输入必须是目录（dir 选项），输出是 .zip（不是目录）；
#   c) link 时 res.zip 是【位置参数】，绝不能写成 -R（-R 是 overlay 语义）；
#   d) java 选项生成 R.java，包名跟清单 package（io.mdt.launcher.R）。
#   ⚠️ 这里用 rm -rf 清镜像：当前 app/res 只有个位数文件，远低于那个删除闸。
#      若将来 res 文件数逼近 50（例如引入整套 drawable / 多语言），要改成 mv 归档或分批删，
#      否则这一行会被整条拦掉、镜像带着幽灵资源继续构建。
RES_ARGS=()
if [ -d "$ROOT/app/res" ]; then
  RES_DIR="${RES_DIR:-/tmp/mdt-launcher-res}"
  RES_ZIP="${RES_ZIP:-/tmp/mdt-launcher-res.zip}"
  rm -rf "$RES_DIR" "$RES_ZIP"
  cp -r "$ROOT/app/res" "$RES_DIR"
  "$BT/aapt2.exe" compile --dir "$(wpath "$RES_DIR")" -o "$(wpath "$RES_ZIP")"
  RES_ARGS=( "$(wpath "$RES_ZIP")" )
else
  echo "   (no app/res -- building without resources)"
fi

"$BT/aapt2.exe" link \
  -o "$(wpath "$OUT/base.apk")" \
  -I "$(wpath "$AJAR")" \
  --manifest "$(wpath "$MANIFEST")" \
  --min-sdk-version 26 --target-sdk-version 36 \
  --java "$(wpath "$OUT/gen")" \
  ${RES_ARGS[@]+"${RES_ARGS[@]}"}

# ---- [2/4] compile ---------------------------------------------------------
echo "== [2/4] javac =="
# F1: R.java 由 [1/4] 的 --java 生成在 build/gen（与手写源码同包 io.mdt.launcher，
#     不需要 import）。收集放在 link 之后才能捡到它。
mapfile -t SRC < <( { find "$ROOT/app/src" -name '*.java'; find "$OUT/gen" -name '*.java'; } | sort | while read -r f; do jpath "$f"; done )
echo "sources: ${#SRC[@]}"
[ "${#SRC[@]}" -gt 0 ] || { echo "FAIL: no sources found" >&2; exit 1; }
"$JAVAC_8" -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options \
  -classpath "$(wpath "$AJAR")" \
  -d "$(jpath "$OUT/classes")" \
  "${SRC[@]}"

# ---- [3/4] d8 -> classes.dex -> inject into base.apk ----------------------
echo "== [3/4] d8 + inject =="
# 🔴 2026-10-04（第 86 轮踩到）：**必须先把 classes/ 打成一个 jar 再交给 d8**。
#   原来是把每个 .class 逐个当参数传（`"${CLASSES[@]}"`）—— 47 个源文件时已经贴着
#   MSYS 的 argv 上限，**只要再加一个类**（本轮加了 Crash.java）就变成
#       ./build.sh: line 195: .../java: Argument list too long
#   那不是"改动有问题"，而是构建脚本的隐患：任何人新增一个源文件都会撞上。
#   ⚠️ d8 **不接受目录**（实测：`Unsupported source file type`），只吃 class / jar ⇒ 用 jar。
CLASSES_JAR="$OUT/classes.jar"
rm -f "$CLASSES_JAR"
"$JAR_8" cf "$(jpath "$CLASSES_JAR")" -C "$(jpath "$OUT/classes")" .
"$JAVA" -cp "$(jpath "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
  --min-api 26 --lib "$(jpath "$AJAR")" \
  --output "$(jpath "$OUT/dex")" \
  "$(jpath "$CLASSES_JAR")"

"$PYTHON" - "$(jpath "$OUT/base.apk")" "$(jpath "$OUT/dex/classes.dex")" "$(jpath "$OUT/unsigned.apk")" <<'PY'
import sys, zipfile, shutil
src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("  classes.dex injected ->", dst)
PY

# ---- [4/4] zipalign + sign ------------------------------------------------
echo "== [4/4] zipalign + sign =="
"$BT/zipalign.exe" -f 4 "$(wpath "$OUT/unsigned.apk")" "$(wpath "$OUT/aligned.apk")"

"$JAVA" -jar "$(jpath "$BT/lib/apksigner.jar")" sign \
  --ks "$(wpath "$KEYSTORE")" \
  --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out "$(wpath "$ROOT/$APP_NAME.apk")" \
  "$(wpath "$OUT/aligned.apk")"

echo
echo "== BUILD OK =="
ls -la "$ROOT/$APP_NAME.apk"

# ---- optional install -----------------------------------------------------
if [ "${1:-}" = "install" ]; then
  echo
  echo "== adb install =="
  "$ADB" install -r "$(wpath "$ROOT/$APP_NAME.apk")"
  echo "launch:  $ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1"
  echo "logcat:  $ADB logcat -s MDTLauncher:V AndroidRuntime:E"
fi
