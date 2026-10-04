#!/usr/bin/env bash
# F18 真机取证：对每个样本跑一次 dev_compat，紧跟命令取回报告。
# ⚠️ report-devtool.txt 是所有 dev_* 口共用的同一个文件 ⇒ 必须「跑一个、取一个」。
export MSYS_NO_PATHCONV=1
ADB="${ANDROID_HOME:?请先设置 ANDROID_HOME 指向 Android SDK}/platform-tools/adb.exe"
DEV=/storage/emulated/0/Android/data/io.mdt.launcher
REPORT=$DEV/hub/report-devtool.txt
# ⚠️ adb 是 Windows 程序：MSYS_NO_PATHCONV=1 之下**本地路径也必须**写成 D:/... 形式
OUT="${OUT:-D:/tmp-compat/evidence}"
mkdir -p "$OUT"

run_one() {
  local name="$1"
  $ADB shell rm -f "$REPORT"
  $ADB shell am start -n io.mdt.launcher/.MainActivity \
      --es dev_compat "$DEV/hub/compat-samples/$name.apk" >/dev/null 2>&1
  local i
  for i in $(seq 1 24); do
    sleep 0.5
    if $ADB shell "[ -f $REPORT ]" 2>/dev/null; then break; fi
  done
  echo "============================================================"
  echo "样本: $name"
  $ADB shell cat "$REPORT"
  $ADB pull "$REPORT" "$OUT/report-$name.txt" >/dev/null 2>&1 || echo "  (取证文件拉取失败)"
}

for name in v104.10 v3.2.1 classic v105 v146; do
  run_one "$name"
done
