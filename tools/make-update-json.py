#!/usr/bin/env python3
"""从构建产物生成 `update.json`（发版用）—— 2026-10-29 第 310 轮。

**为什么要脚本**：清单里 `sha256` / `size` / `url` 三个字段手抄必错（sha256 尤其），
而错了的后果是"所有用户都下载失败"，不是"少个功能"。

用法：

    # 1) 发布构建（必须用正式密钥；`--allow-debug` 只给自测用）
    python tools/make-update-json.py --apk mdt-launcher.apk --tag v0.8.0 \\
           --notes "修了三个崩溃" --out update.json

    # 2) 跟 APK 一起传到**这一版自己的** release（没有单独的"通道 release"）
    gh release upload v0.8.0 mdt-launcher.apk update.json

    # 3) 校验一份已有的清单（CI / 发版前）
    python tools/make-update-json.py --check update.json

客户端读的是 `/repos/…/releases` 列表（见 `GitHubReleases`）：取最新一版、按 `prerelease`
筛、再从它的 assets 里找这个 `update.json`。所以**每一版都必须带它** ——
缺了这份清单的 release，对更新器等于不存在。
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys

DEFAULT_SDK = os.environ.get("ANDROID_SDK", r"D:\AndroidSDK")
HEX = re.compile(r"^[0-9a-f]{64}$")


def find_aapt2(sdk):
    bt = os.path.join(sdk, "build-tools")
    if not os.path.isdir(bt):
        raise SystemExit("找不到 build-tools：%s" % bt)
    for v in sorted(os.listdir(bt), reverse=True):
        p = os.path.join(bt, v, "aapt2.exe")
        if os.path.isfile(p):
            return p
    raise SystemExit("build-tools 里没有 aapt2.exe")


def badging(apk, aapt2):
    r = subprocess.run([aapt2, "dump", "badging", apk],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        raise SystemExit("aapt2 失败：\n" + (r.stderr or "")[:600])
    return r.stdout


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def gen(args):
    apk = args.apk
    if not os.path.isfile(apk):
        raise SystemExit("APK 不在：%s" % apk)
    if not args.tag:
        raise SystemExit("必须给 --tag（下载 URL 要用它）")

    text = badging(apk, find_aapt2(args.sdk))
    m = re.search(r"versionCode='(\d+)'", text)
    if not m:
        raise SystemExit("从 badging 里读不到 versionCode")
    version_code = int(m.group(1))
    m = re.search(r"versionName='([^']*)'", text)
    version_name = m.group(1) if m else ""

    if "application-debuggable" in text and not args.allow_debug:
        raise SystemExit(
            "这个包是 debuggable 的 —— 发布包不能这样。\n"
            "  （自测请加 --allow-debug；真要发布就重打 DEBUGGABLE=false 并用正式密钥）")
    if version_code <= 1 and not args.allow_debug:
        raise SystemExit(
            "versionCode=%d ⇒ 这多半不是发布包（build.sh 默认 1）。\n"
            "  发布要显式给 VER_CODE / VER_NAME / KEYSTORE。自测请加 --allow-debug。"
            % version_code)

    size = os.path.getsize(apk)
    sha = sha256_file(apk)
    url = "https://github.com/%s/releases/download/%s/mdt-launcher.apk" % (args.repo, args.tag)

    doc = {
        "schema": 1,
        "versionCode": version_code,
        "versionName": version_name,
        "size": size,
        "sha256": sha,
        "url": url,
        "publishedAt": datetime.date.today().isoformat(),
        "notes": args.notes or "",
    }
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("written: %s" % args.out)
    print("  versionCode = %d" % version_code)
    print("  versionName = %s" % version_name)
    print("  size        = %d" % size)
    print("  sha256      = %s" % sha)
    print("  url         = %s" % url)
    print()
    print("下一步（两个资产一起传，缺 update.json 的 release 对更新器等于不存在）：")
    print("  gh release upload %s mdt-launcher.apk %s" % (args.tag, args.out))
    return 0


def check(path):
    """按 UpdateManifest.parse 的规则校验一份清单（这里只做静态检查，不联网）。"""
    try:
        # utf-8-sig：Windows 上的编辑器可能给文件加 BOM，而这玩意是 JSON 解析器的头号杀手。
        # 产品侧（UpdateManifest）能容忍它（FlatJson 会跳到第一个 '{'），这里也保持一致。
        doc = json.load(open(path, encoding="utf-8-sig"))
    except Exception as e:
        print("FAIL  读不出来：%s" % e)
        return 1
    errs = []
    schema = doc.get("schema", 1)
    if schema != 1:
        errs.append("schema=%r（只认 1）" % schema)
    vc = doc.get("versionCode")
    if not isinstance(vc, int) or vc <= 0:
        errs.append("versionCode=%r（要正整数）" % vc)
    sha = doc.get("sha256")
    if not isinstance(sha, str) or not HEX.match(sha):
        errs.append("sha256=%r（要 64 位小写十六进制）" % sha)
    size = doc.get("size")
    if size is not None and (not isinstance(size, int) or size <= 0):
        errs.append("size=%r（要么省略，要么正整数）" % size)
    url = doc.get("url")
    if not isinstance(url, str) or not url.startswith("https://"):
        errs.append("url=%r（必须 https）" % url)

    if errs:
        for e in errs:
            print("FAIL  " + e)
        return 1
    print("OK    %s  versionCode=%d versionName=%s size=%d"
          % (path, vc, doc.get("versionName", ""), size or 0))
    return 0


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--apk")
    ap.add_argument("--tag")
    ap.add_argument("--notes", default="")
    ap.add_argument("--repo", default="GXFQE/mindustry-launcher-android")
    ap.add_argument("--out", default="update.json")
    ap.add_argument("--sdk", default=DEFAULT_SDK)
    ap.add_argument("--allow-debug", action="store_true")
    ap.add_argument("--check", metavar="FILE")
    a = ap.parse_args()
    if a.check:
        return check(a.check)
    if not a.apk:
        ap.print_help()
        return 2
    return gen(a)


if __name__ == "__main__":
    sys.exit(main())
