#!/usr/bin/env bash
# =============================================================================
# 分享出口的「第三方接收」夹具构建（第 155 轮）—— **不是产品**，只用同一套手工工具链：
#   aapt2 link → javac → d8 → 注入 classes.dex → zipalign → apksigner
#
# 为什么要它：微信有应用锁（人脸/密码挡在分享面板之后）⇒ 「分享给微信」那条路自动化不了；
# 而这条功能唯一的未知数是「**另一个 UID 能不能通过我们给的授权读走字节**」。
# 本夹具用 debug 密钥签、装成独立包，收到的内容落到自己的外部目录：
#   /sdcard/Android/data/io.mdt.shareprobe/files/{received.bin,result.txt}
# `adb pull` 回来与启动器 `cache/share/<名字>` 比对 sha256（相同 = 授权 + provider 都对）。
#
# 产出：$SHAREPROBE_OUT（默认 $TEMP；Git Bash 里通常是 /tmp/mdt-shareprobe/shareprobe.apk）
#   ⚠️ 别放进 build/ —— 主 build.sh 会把它整个清掉（AGENTS §六 记过这个坑）。
#
# 用法：ANDROID_HOME=D:/AndroidSDK bash tools/shareprobe/build.sh
#       adb install -r "$TEMP/mdt-shareprobe/shareprobe.apk"
# =============================================================================
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC_DIR="$ROOT/tools/shareprobe"
OUT="${SHAREPROBE_OUT:-${TEMP:-/tmp}/mdt-shareprobe}"
PY="${PYTHON:-/c/ProgramData/anaconda3/python.exe}"
CYGPATH=/usr/bin/cygpath

SDK="${ANDROID_HOME:-}"
[ -n "$SDK" ] || { echo "请先设置 ANDROID_HOME（例：ANDROID_HOME=C:/AndroidSDK）" >&2; exit 1; }
SDK="$("$CYGPATH" -u "$SDK")"
BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-36/android.jar"
JAVA="${JAVA:-java}"
JDK8="${JDK8:-/c/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"; KS_PASS="${KS_PASS:-android}"; KEY_PASS="${KEY_PASS:-android}"

wpath() { "$CYGPATH" -w "$1"; }
jpath() { "$CYGPATH" -m "$1"; }

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# aapt2 在含中文的工程路径下会 "failed to open directory" ⇒ 清单先拷到 /tmp 镜像（与主构建同一个坑）
MF_DIR="/tmp/mdt-shareprobe-manifest"
rm -rf "$MF_DIR"; mkdir -p "$MF_DIR"
cp "$SRC_DIR/AndroidManifest.xml" "$MF_DIR/AndroidManifest.xml"

echo "== [1/4] aapt2 =="
"$BT/aapt2.exe" link -o "$(wpath "$OUT/base.apk")" -I "$(wpath "$AJAR")" \
  --manifest "$(wpath "$MF_DIR/AndroidManifest.xml")" \
  --min-sdk-version 26 --target-sdk-version 36 \
  --java "$(wpath "$OUT/gen")"

echo "== [2/4] javac =="
SRC=()
while IFS= read -r f; do SRC+=("$f"); done < <( { find "$SRC_DIR/src" -name '*.java'; find "$OUT/gen" -name '*.java'; } | sort )
if [ -x "$JDK8/bin/javac" ]; then
  "$JDK8/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -classpath "$(wpath "$AJAR")" -d "$(wpath "$OUT/classes")" "${SRC[@]}"
  JAR="$JDK8/bin/jar"
else
  # 没有 JDK8 时用当前 JDK 的 --release 8（d8 同样吃）
  javac --release 8 -encoding UTF-8 -nowarn \
    -classpath "$(wpath "$AJAR")" -d "$(wpath "$OUT/classes")" "${SRC[@]}"
  JAR="jar"
fi

echo "== [3/4] d8 + inject =="
( cd "$OUT/classes" && "$JAR" cf "$(wpath "$OUT/classes.jar")" . )
"$JAVA" -cp "$(jpath "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
  --min-api 26 --lib "$(wpath "$AJAR")" \
  --output "$(wpath "$OUT/dex")" "$(wpath "$OUT/classes.jar")"

"$PY" - "$(jpath "$OUT/base.apk")" "$(jpath "$OUT/dex/classes.dex")" "$(jpath "$OUT/probe.apk")" <<'PY'
import sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(base)
zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    if it.filename.startswith('classes') and it.filename.endswith('.dex'):
        continue
    zout.writestr(it, zin.read(it.filename))
zout.write(dex, 'classes.dex')
zout.close(); zin.close()
print("  classes.dex injected ->", out)
PY

echo "== [4/4] zipalign + sign =="
"$BT/zipalign.exe" -f 4 "$(wpath "$OUT/probe.apk")" "$(wpath "$OUT/probe-aligned.apk")"
"$JAVA" -jar "$(jpath "$BT/lib/apksigner.jar")" sign \
  --ks "$(wpath "$KEYSTORE")" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$(wpath "$OUT/shareprobe.apk")" "$(wpath "$OUT/probe-aligned.apk")"

echo
echo "== DONE =="
ls -l "$OUT/shareprobe.apk"
