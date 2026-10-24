#!/usr/bin/env bash
# =============================================================================
# 官方插件 mods-health 的构建（第 229 轮）—— 与主构建同一条手工链：
#   aapt2 compile/link -> javac(8) -> d8 -> 注入 classes.dex + assets/plugin.json
#   -> zipalign -> apksigner
#
# 产出：${MODSHEALTH_OUT:-%TEMP%/mdt-mods-health}/mods-health.apk
# ★ 产物别放 build/ —— 主构建 build.sh 会把它整个删掉（见 AGENTS §六）。
# ★ 签名默认用 debug.keystore：与本地宿主同一把钥匙 ⇒ 宿主判「官方」。
# ★ 插件 id = APK 文件名（dev 口 `dev_plugininstall` 的缺省 id 取文件名）⇒ 这里固定叫 mods-health.apk。
#
# 用法：
#   ANDROID_HOME=D:/AndroidSDK bash plugins/mods-health/build.sh          # 构建 APK
#   ANDROID_HOME=D:/AndroidSDK bash plugins/mods-health/build.sh check    # 只在 PC 上跑判据自检
# =============================================================================
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC_DIR="$ROOT/plugins/mods-health"
OUT="${MODSHEALTH_OUT:-${TEMP:-/tmp}/mdt-mods-health}"
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

# ---- 插件侧自检（纯 Java，PC 上跑）：`plugins/mods-health/build.sh check` -------------
# ★ 为什么要有它：结构 / 依赖 / 冲突 / 六道门的判据都不在宿主里了（宿主 SelfTest 抓不到），
#   而"判据本身对不对"只能在夹具上验 —— 真机上只有一份现成的模组组合，验不出反方向。
if [ "${1:-}" = "check" ]; then
  echo "== mods-health check (PC) =="
  CHK="$OUT/check"; rm -rf "$CHK"; mkdir -p "$CHK"
  SRC=()
  while IFS= read -r f; do SRC+=("$f"); done < <( { find "$SRC_DIR/src/io/mdt/plugins/mods/core" -name '*.java'; echo "$SRC_DIR/tools/Check.java"; } | sort )
  if ! "$JAVAC_8" -encoding UTF-8 -source 8 -target 8 -nowarn \
        -classpath "$(wpath "$AJAR")" -d "$(wpath "$CHK")" "${SRC[@]}" > "$CHK/javac.log" 2>&1; then
    grep -v '^注:' "$CHK/javac.log" | tail -30
    echo "!! check 编译失败" >&2
    exit 1
  fi
  # -Dmh.root：文案门（六道门的 key 必须两种语言都在）要读插件 res/ —— 别让它按 cwd 猜。
  "$JDK8/bin/java" -Dmh.root="$(wpath "$SRC_DIR")" -cp "$(wpath "$CHK")" io.mdt.plugins.mods.core.Check
  exit $?
fi

rm -rf "$OUT"; mkdir -p "$OUT"

# 🔴 与主构建同一个坑：aapt2 在含中文的工程路径下会 "failed to open directory"
#    ⇒ res 与 manifest 先拷到 ASCII 路径（/tmp 镜像）再喂给它。
echo "== [1/4] aapt2 =="
RES_DIR="/tmp/mdt-mods-health-res"; MF_DIR="/tmp/mdt-mods-health-manifest"
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
test -f "$OUT/classes/io/mdt/plugins/mods/ModsHealth.class" || { echo "!! 入口类没编出来" >&2; exit 1; }
test -f "$OUT/classes/io/mdt/plugins/mods/core/Conflicts.class" || { echo "!! 判据类没编出来" >&2; exit 1; }

echo "== [3/4] d8 + inject =="
mkdir -p "$OUT/dex"
( cd "$OUT/classes" && "$JDK8/bin/jar" cf "$(wpath "$OUT/classes.jar")" . )
"$JAVA" -cp "$(jpath "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
  --min-api 26 --output "$(wpath "$OUT/dex")" "$(wpath "$OUT/classes.jar")"
"$PY" - "$(jpath "$OUT/base.apk")" "$(jpath "$OUT/dex/classes.dex")" \
  "$(jpath "$OUT/mods-health.apk")" "$(jpath "$SRC_DIR/assets/plugin.json")" <<'PY'
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
"$BT/zipalign.exe" -f 4 "$(wpath "$OUT/mods-health.apk")" "$(wpath "$OUT/mods-health-aligned.apk")"
mv "$OUT/mods-health-aligned.apk" "$OUT/mods-health.apk"
"$JAVA" -jar "$(jpath "$BT/lib/apksigner.jar")" sign \
  --ks "$(wpath "$KEYSTORE")" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" "$(wpath "$OUT/mods-health.apk")"

ls -l "$OUT/mods-health.apk"
"$PY" - "$(jpath "$OUT/mods-health.apk")" <<'PY'
import hashlib, sys
print("sha256 =", hashlib.sha256(open(sys.argv[1], 'rb').read()).hexdigest())
PY
echo "== MODS-HEALTH BUILD OK =="
