#!/usr/bin/env bash
# =============================================================================
# 插件夹具构建（S0 可行性实验）—— 不为产品发版服务，只用同样那套手工工具链
#   aapt2 compile/link → javac → d8 → zipalign → apksigner
#
# 产出（放进 build/plugtest/）：
#   plugtest-ok.apk     接口版本匹配、类与资源都正常   ⇒ 壳必须**加载成功**
#   plugtest-badapi.apk 接口版本 = 99（壳只认 1）      ⇒ 壳必须**拒绝**并说明原因
#   plugtest-noclass.apk 没有 PlugPage 这个类           ⇒ 壳必须**拒绝**（缺类）
#   plugtest-v1.apk / plugtest-v2.apk 同名类（PluginProbe）两份不同 tag ⇒ shadowing 实验
# ★ **不给 d8 喂旧 class**：每个变体各自 jam 一遍（否则热替换测试会被上一版污染）。
# =============================================================================
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC_DIR="$ROOT/tools/plugtest"
# ⚠️ 别放进 build/ —— 主构建 build.sh 会把它整个删掉（AGENTS §六 记过这个坑）。
OUT="${PLUGTEST_OUT:-${TEMP:-/tmp}/mdt-plugtest}"
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

rm -rf "$OUT"; mkdir -p "$OUT"

# --- 一次 aapt2：资源 + 清单（所有变体共用同一份资源树） ----------------------
# 🔴 与主构建同一个坑：**aapt2 在含中文的工程路径下 "failed to open directory"**
#    ⇒ res 与 manifest 都要先拷到 ASCII 路径（/tmp 镜像）再喂给它。
echo "== [1] aapt2 =="
RES_DIR="/tmp/mdt-plugtest-res"; MF_DIR="/tmp/mdt-plugtest-manifest"
rm -rf "$RES_DIR" "$MF_DIR" "$OUT/res.zip"; mkdir -p "$RES_DIR" "$MF_DIR"
cp -r "$SRC_DIR/res/." "$RES_DIR/"
cp "$SRC_DIR/AndroidManifest.xml" "$MF_DIR/AndroidManifest.xml"
"$BT/aapt2.exe" compile --dir "$(wpath "$RES_DIR")" -o "$(wpath "$OUT/res.zip")"
"$BT/aapt2.exe" link -o "$(wpath "$OUT/base.apk")" -I "$(wpath "$AJAR")" \
  --manifest "$(wpath "$MF_DIR/AndroidManifest.xml")" \
  --min-sdk-version 26 --target-sdk-version 36 \
  "$(wpath "$OUT/res.zip")" --java "$(wpath "$OUT/gen")"

# --- 每个变体：sed 出源码 → javac → d8 → 注入 → 对齐 → 签名 -------------------
build_one() {
  local name="$1" api="$2" tag="$3" dropclass="$4" tag2="$5"
  # 第 6 个参数（可选）= 换一个入口模板（`plugtest-kill` 用它造"启动就死"的那一版，见 PlugPageKill.java）
  local tpl="${6:-$SRC_DIR/src/io/mdt/plugtest/PlugPage.java}"
  echo "== [$name] api=$api tag=$tag tag2=$tag2 dropclass=$dropclass tpl=$(basename "$tpl") =="
  local d="$OUT/$name"; mkdir -p "$d/src/io/mdt/plugtest" "$d/src/io/mdt/launcher" "$d/classes" "$d/dex"
  # 入口页也带上这一版的编译期 tag（`PlugPage.tag()`）——多插件下"谁渲染的页"靠它才看得见
  sed -e "s/__API__/$api/" -e "s/__TAGPAGE__/$tag/" "$tpl" > "$d/src/io/mdt/plugtest/PlugPage.java"
  sed "s/__TAG__/$tag/"     "$SRC_DIR/src/io/mdt/launcher/PluginProbe.java" > "$d/src/io/mdt/launcher/PluginProbe.java"
  sed "s/__TAG2__/$tag2/"   "$SRC_DIR/src/io/mdt/launcher/PluginProbe2.java" > "$d/src/io/mdt/launcher/PluginProbe2.java"
  # 缺类变体：把入口类删掉（壳必须在"预热预检"就拒掉，而不是等到点下去才炸）
  if [ "$dropclass" = "1" ]; then rm -f "$d/src/io/mdt/plugtest/PlugPage.java"; fi

  # 注意：R.java 由 aapt2 link 生成，位置在 $OUT/gen
  local SRC=()
  while IFS= read -r f; do SRC+=("$f"); done < <( { find "$d/src" -name '*.java'; find "$OUT/gen" -name '*.java'; } | sort )
  "$JAVAC_8" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -classpath "$(wpath "$AJAR")" -d "$(wpath "$d/classes")" "${SRC[@]}" 2>&1 | grep -v '^注:' || true
  # 打成 jar 再喂 d8（d8 不吃目录，见 build.sh 的同一条注释）
  ( cd "$d/classes" && "$JDK8/bin/jar" cf "$(wpath "$d/classes.jar")" . )
  "$JAVA" -cp "$(jpath "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --min-api 26 --output "$(wpath "$d/dex")" "$(wpath "$d/classes.jar")"
  # 把 classes.dex 注入 base.apk（与主构建同一种办法：python 改 zip）
  "$PY" - "$(jpath "$OUT/base.apk")" "$(jpath "$d/dex/classes.dex")" "$(jpath "$OUT/$name.apk")" <<'PY'
import sys, zipfile, shutil
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(base); zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    if it.filename in ('classes.dex',) or it.filename.startswith('classes') and it.filename.endswith('.dex'):
        continue
    zout.writestr(it, zin.read(it.filename))
zout.write(dex, 'classes.dex')
zout.close(); zin.close()
PY
  "$BT/zipalign.exe" -f 4 "$(wpath "$OUT/$name.apk")" "$(wpath "$OUT/$name-aligned.apk")"
  mv "$OUT/$name-aligned.apk" "$OUT/$name.apk"
  "$JAVA" -jar "$(jpath "$BT/lib/apksigner.jar")" sign \
    --ks "$(wpath "$KEYSTORE")" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" "$(wpath "$OUT/$name.apk")"
  ls -l "$OUT/$name.apk"
}

build_one plugtest-ok      1  PLUGIN-ok      0 PLUGIN-ok-2
build_one plugtest-badapi  99 PLUGIN-badapi  0 PLUGIN-badapi-2
build_one plugtest-noclass 1  PLUGIN-noclass 1 PLUGIN-noclass-2
build_one plugtest-v1      1  PLUGIN-v1      0 PLUGIN-v1-2
build_one plugtest-v2      1  PLUGIN-v2      0 PLUGIN-v2-2
# 第 132 轮：**带它就启动不了**的那一版（回滚要拿真实失败形态验，不能只靠手写 guard）
build_one plugtest-kill    1  PLUGIN-kill    0 PLUGIN-kill-2 "$SRC_DIR/src/io/mdt/plugtest/PlugPageKill.java"

# --- 第 133 轮：**第三方**对照样本（同一个插件，换另一把密钥签） -----------------
# 信任模型的判据是"签名指纹 == 本应用自己的"。夹具必须**两侧都有**：
#   plugtest-ok / v1 / v2 / kill 用 debug.keystore 签（与 dev 构建同一把 ⇒ 判「官方」）；
#   plugtest-thirdparty 用 TP_KEYSTORE 签（⇒ 必须判「第三方」）。
# 只用一侧的样本测不出这个判据是不是恒真（本工程那条"夹具要能分辨两种解释"的规矩）。
if [ -n "${TP_KEYSTORE:-}" ]; then
  KEYSTORE="$TP_KEYSTORE"
  KS_ALIAS="${TP_ALIAS:-tp}"; KS_PASS="${TP_PASS:-tp123456}"; KEY_PASS="$KS_PASS"
  build_one plugtest-thirdparty 1 PLUGIN-tp 0 PLUGIN-tp-2
  # 第三方 + **会崩**的那一版：用来验「第三方默认 L3（停用 + 隔离）」
  build_one plugtest-tp-kill 1 PLUGIN-tpk 0 PLUGIN-tpk-2 "$SRC_DIR/src/io/mdt/plugtest/PlugPageKill.java"
fi
echo "== DONE =="
