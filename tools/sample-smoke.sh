#!/usr/bin/env bash
# 样例插件冒烟（PLUGIN-API 契约的机器检查）：构建 plugins/sample → 装进 dev 宿主 → 断言装上且不崩。
#
# 用法：
#   bash tools/sample-smoke.sh                # 假设当前装的是 dev 变体
#   bash tools/sample-smoke.sh --dev-install  # 先构建/安装 dev 变体（约 1 分钟）
#
# 只做"机器能判"的部分；两个入口的肉眼检查在结尾打印。
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=io.mdt.launcher
ANDROID_HOME="${ANDROID_HOME:-D:/AndroidSDK}"
ADB_U="$(cygpath -u "$ANDROID_HOME")/platform-tools/adb.exe"
APK="$(cygpath -m "${TEMP:-/tmp}")/mdt-sample/mapstats.apk"
REMOTE=/sdcard/Android/data/$PKG/hub/tmp/sample.apk

# adb 的参数里有 /sdcard/...，Git Bash 会改写它 ⇒ 每次调用都关掉路径转换
adbx() { MSYS_NO_PATHCONV=1 "$ADB_U" "$@"; }

fail() { echo "[smoke][FAIL] $*" >&2; exit 1; }
say() { echo "[smoke] $*"; }

[ -x "$ADB_U" ] || fail "找不到 adb：$ADB_U"

if [ "${1:-}" = "--dev-install" ]; then
  say "构建并安装 dev 变体"
  ( cd "$ROOT" && DEBUGGABLE=true SUFFIX=-dev bash ./build.sh install ) >"${TEMP:-/tmp}/mdt-smoke-dev.log" 2>&1 \
    || fail "dev 构建失败（见 ${TEMP:-/tmp}/mdt-smoke-dev.log）"
fi

say "构建样例插件"
ANDROID_HOME="$ANDROID_HOME" bash "$ROOT/plugins/sample/build.sh" >"${TEMP:-/tmp}/mdt-smoke-sample.log" 2>&1 \
  || fail "样例构建失败（见 ${TEMP:-/tmp}/mdt-smoke-sample.log）"
[ -f "$APK" ] || fail "没找到产物 $APK"
say "产物 $APK"

say "推进设备并安装"
adbx push "$APK" "$REMOTE" >/dev/null 2>&1 || fail "push 失败"
adbx shell am force-stop "$PKG" >/dev/null
adbx shell logcat -c >/dev/null
adbx shell am start -n "$PKG"/.MainActivity --es dev_plugininstall "$REMOTE" \
  --es dev_pluginver "$(date +%s)" --es dev_pluginnow true >/dev/null
sleep 3
adbx shell logcat -d | tr -d '\r' | grep -q "installed id=sample" \
  || fail "日志里没有 installed id=sample"
say "安装结果 = installed id=sample"

say "重启并检查"
adbx shell am force-stop "$PKG" >/dev/null
adbx shell am start -n "$PKG"/.MainActivity >/dev/null
sleep 2
adbx shell ps -A | tr -d '\r' | grep -q "$PKG" || fail "启动后进程不在"
adbx shell logcat -d | tr -d '\r' | grep -q "FATAL EXCEPTION" && fail "logcat 有 FATAL EXCEPTION"
say "进程在、无崩溃"

echo
echo "== SAMPLE SMOKE OK =="
echo "再肉眼点两处："
echo "  1) 地图页「样例：导出清单…」→ 应弹宿主选择器"
echo "  2) 任一地图详情页底部 → 应有「Sample plugin」+ head 十六进制"
