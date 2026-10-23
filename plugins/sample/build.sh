#!/usr/bin/env bash
# =============================================================================
# 官方插件 mapstats 的构建（第 188 轮）—— 与主构建同一条手工链：
#   aapt2 compile/link -> javac(8) -> d8 -> 注入 classes.dex + assets/plugin.json
#   -> zipalign -> apksigner
#
# 产出：${MAPSTATS_OUT:-%TEMP%/mdt-sample}/mapstats.apk
# ★ 产物别放 build/ —— 主构建 build.sh 会把它整个删掉（见 AGENTS §六）。
# ★ 签名默认用 debug.keystore：与本地宿主同一把钥匙 ⇒ 宿主判「官方」。
# =============================================================================
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC_DIR="$ROOT/plugins/sample"
OUT="${MAPSTATS_OUT:-${TEMP:-/tmp}/mdt-sample}"
PY="${PYTHON:-/c/ProgramData/anaconda3/python.exe}"
CYGPATH=/usr/bin/cygpath

[ -n "${ANDROID_HOME:-}" ] || { echo "请先设置 ANDROID_HOME" >&2; exit 1; }
SDK="$("$CYGPATH" -u "$ANDROID_HOME")"
BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-36/android.jar"
JAVA="${JAVA:-java}"
JDK8="${JDK8:-/c/Program Files/Eclipse Adoptium/jdk-8.0.492.9-hotspot}"
JAVAC_8="$JDK8/bin/javac"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"; KS_PASS="${KS_PASS:-android}"; KEY_PASS="${KEY_PASS:-android}"

wpath() { "$CYGPATH" -w "$1"; }
jpath() { "$CYGPATH" -m "$1"; }

# ---- 插件侧自检（纯 Java，PC 上跑）：`plugins/sample/build.sh check` -------------
# ★ 为什么要有它：核心类是从宿主拷来的，宿主那份自检测的是宿主拷贝；插件改坏了它抓不到。
if [ "${1:-}" = "check" ]; then
  echo "== mapstats check (PC) =="
  CHK="$OUT/check"; rm -rf "$CHK"; mkdir -p "$CHK"
  SRC=()
  while IFS= read -r f; do SRC+=("$f"); done < <( { find "$SRC_DIR/src/io/mdt/plugins/sample/core" -name '*.java'; echo "$SRC_DIR/tools/Check.java"; } | sort )
  if ! "$JAVAC_8" -encoding UTF-8 -source 8 -target 8 -nowarn \
        -classpath "$(wpath "$AJAR")" -d "$(wpath "$CHK")" "${SRC[@]}" > "$CHK/javac.log" 2>&1; then
    grep -v '^注:' "$CHK/javac.log" | tail -30
    echo "!! check 编译失败" >&2
    exit 1
  fi
  "$JDK8/bin/java" -cp "$(wpath "$CHK")" io.mdt.plugins.mapstats.core.Check
  exit $?
fi

rm -rf "$OUT"; mkdir -p "$OUT"

# 🔴 与主构建同一个坑：aapt2 在含中文的工程路径下会 "failed to open directory"
#    ⇒ res 与 manifest 先拷到 ASCII 路径（/tmp 镜像）再喂给它。
echo "== [1/4] aapt2 =="
RES_DIR="/tmp/mdt-sample-res"; MF_DIR="/tmp/mdt-sample-manifest"
rm -rf "$RES_DIR" "$MF_DIR" "$OUT/res.zip"; mkdir -p "$RES_DIR" "$MF_DIR"
cp -r "$SRC_DIR/res/." "$RES_DIR/"
cp "$SRC_DIR/AndroidManifest.xml" "$MF_DIR/AndroidManifest.xml"
"$BT/aapt2.exe" compile --dir "$(wpath "$RES_DIR")" -o "$(wpath "$OUT/res.zip")"
"$BT/aapt2.exe" link -o "$(wpath "$OUT/base.apk")" -I "$(wpath "$AJAR")" \
  --manifest "$(wpath "$MF_DIR/AndroidManifest.xml")" \
  --min-sdk-version 26 --target-sdk-version 36 \
  "$(wpath "$OUT/res.zip")" --java "$(wpath "$OUT/gen")"

echo "== [2/4] javac =="
mkdir -p "$OUT/classes"
SRC=()
# v20：API 桩直接编宿主那份（插件工程里不再放副本 ⇒ 不会漂）
while IFS= read -r f; do SRC+=("$f"); done < <( { find "$SRC_DIR/src" -name '*.java'; find "$ROOT/app/src/io/mdt/launcher/api" -name '*.java'; find "$OUT/gen" -name '*.java'; } | sort )
# 🔴 javac 的失败**不许**被吞掉：`javac ... || true` 会把"少一个符号"变成"少一批 class"，
#    而入口类照样能编出来 ⇒ APK 看着正常、真机才 NoClassDefFound（第 188 轮踩过）。
if ! "$JAVAC_8" -encoding UTF-8 -source 8 -target 8 -nowarn \
      -classpath "$(wpath "$AJAR")" -d "$(wpath "$OUT/classes")" "${SRC[@]}" \
      > "$OUT/javac.log" 2>&1; then
  grep -v '^注:' "$OUT/javac.log" | tail -30
  echo "!! javac 失败（见上）" >&2
  exit 1
fi
grep -v '^注:' "$OUT/javac.log" | tail -3 || true
test -f "$OUT/classes/io/mdt/plugins/sample/SamplePlugin.class" || { echo "!! 入口类没编出来" >&2; exit 1; }

echo "== [3/4] d8 + inject =="
mkdir -p "$OUT/dex"
( cd "$OUT/classes" && "$JDK8/bin/jar" cf "$(wpath "$OUT/classes.jar")" . )
"$JAVA" -cp "$(jpath "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
  --min-api 26 --output "$(wpath "$OUT/dex")" "$(wpath "$OUT/classes.jar")"
"$PY" - "$(jpath "$OUT/base.apk")" "$(jpath "$OUT/dex/classes.dex")" \
  "$(jpath "$OUT/mapstats.apk")" "$(jpath "$SRC_DIR/assets/plugin.json")" <<'PY'
import sys, zipfile
base, dex, out, meta = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
zin = zipfile.ZipFile(base); zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    if it.filename == 'classes.dex' or (it.filename.startswith('classes') and it.filename.endswith('.dex')):
        continue
    zout.writestr(it, zin.read(it.filename))
zout.write(dex, 'classes.dex')
zout.writestr('assets/plugin.json', open(meta, 'rb').read())
zout.close(); zin.close()
PY

echo "== [4/4] zipalign + sign =="
"$BT/zipalign.exe" -f 4 "$(wpath "$OUT/mapstats.apk")" "$(wpath "$OUT/mapstats-aligned.apk")"
mv "$OUT/mapstats-aligned.apk" "$OUT/mapstats.apk"
"$JAVA" -jar "$(jpath "$BT/lib/apksigner.jar")" sign \
  --ks "$(wpath "$KEYSTORE")" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" "$(wpath "$OUT/mapstats.apk")"

ls -l "$OUT/mapstats.apk"
"$PY" - "$(jpath "$OUT/mapstats.apk")" <<'PY'
import hashlib, sys
print("sha256 =", hashlib.sha256(open(sys.argv[1], 'rb').read()).hexdigest())
PY
echo "== MAPSTATS BUILD OK =="
