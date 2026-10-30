#!/usr/bin/env python3
"""从插件 APK 生成**插件更新清单**（`<id>.json`）—— 2026-10-30 第 338 轮。

**为什么要脚本**：清单里的 `sha256` / `size` / `url` 手抄必错，而错了的后果是
"所有装了这个插件的人都更新失败"，不是"少个功能"。sha256 尤其是人抄不动的。

用法：

    # 1) 先把插件构建出来（plugins/<id>/build.sh），再生成清单
    python tools/make-plugin-update.py --apk "$TEMP/mdt-mapstats/mapstats.apk" \
           --version 6 --out mapstats.json

    # 2) 把 APK 和清单一起传到**滚动更新通道** release（不是启动器版本发布）
    gh release upload plugins-latest "$TEMP/mdt-mapstats/mapstats.apk" mapstats.json --clobber

    # 3) 校验一份已有清单（发版前 / CI）
    python tools/make-plugin-update.py --check mapstats.json

★ 宿主怎么用它：插件自己的 `assets/plugin.json` 里写 `"update": "<这份清单的地址>"`，
  宿主按**内容哈希**（不是版本号）判断有没有新版 —— 见 `app/src/io/mdt/launcher/PluginUpdateManifest.java`。

★ 下载地址里的 tag 是 `plugins-latest`（**滚动通道**，可以反复覆盖资产）：
  它不在 `v*` 命名空间里 ⇒ 不影响启动器的 `versionCode = v* tag 数 + 1` 那条算法。
  ⚠️ 那个 release **只放插件**，永远不要往里传启动器 APK。
"""
import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

DEFAULT_TAG = "plugins-latest"
DEFAULT_REPO = "GXFQE/mindustry-launcher-android"
HEX = re.compile(r"^[0-9a-f]{64}$")
ASSET = "assets/plugin.json"


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def plugin_meta(apk):
    """读 APK 里的 `assets/plugin.json`（不装包；与宿主 `PluginMeta.zipText` 同一位置）。"""
    try:
        with zipfile.ZipFile(apk) as z:
            if ASSET not in z.namelist():
                raise SystemExit("这个 APK 里没有 %s（不是插件？）" % ASSET)
            # utf-8-sig：编辑器可能给文件加 BOM
            return json.loads(z.read(ASSET).decode("utf-8-sig"))
    except zipfile.BadZipFile:
        raise SystemExit("不是合法 APK（zip）：%s" % apk)


def gen(args):
    apk = args.apk
    if not os.path.isfile(apk):
        raise SystemExit("APK 不在：%s" % apk)

    meta = plugin_meta(apk)
    pname = (meta.get("name") or "").strip()
    pid = (args.id or pname).strip()
    if not pid:
        raise SystemExit("读不到插件 id（assets/plugin.json 里没有 name），请用 --id 指定")
    if pname and args.id and pname != args.id:
        raise SystemExit("--id=%s 与 APK 自述里的 name=%s 不一致（装错包了？）" % (args.id, pname))

    size = os.path.getsize(apk)
    sha = sha256_file(apk)
    url = "https://github.com/%s/releases/download/%s/%s.apk" % (args.repo, args.tag, pid)
    version = (args.version or "").strip()

    doc = {
        "schema": 1,
        "plugin": pid,
        "version": version,
        "size": size,
        "sha256": sha,
        "url": url,
        "notes": args.notes or "",
    }
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("written: %s" % args.out)
    print("  plugin   = %s" % pid)
    print("  version  = %s" % (version or "(none)"))
    print("  size     = %d" % size)
    print("  sha256   = %s" % sha)
    print("  url      = %s" % url)
    print()
    print("下一步（两个资产一起传；缺清单的通道，插件那边的检查会一直失败）：")
    print("  gh release upload %s %s %s --clobber" % (args.tag, apk, args.out))
    print()
    print("★ 只有**内容变了**才要重新上传；同一份 APK 重复上传只会换掉清单里的 sha。")
    return 0


def check(path):
    """按宿主 `PluginUpdateManifest.parse` 的规则做静态校验（不联网）。"""
    try:
        doc = json.load(open(path, encoding="utf-8-sig"))
    except Exception as e:
        print("FAIL  读不出来：%s" % e)
        return 1
    errs = []
    if doc.get("schema", 1) != 1:
        errs.append("schema=%r（只认 1）" % doc.get("schema"))
    sha = doc.get("sha256")
    if not isinstance(sha, str) or not HEX.match(sha):
        errs.append("sha256=%r（要 64 位小写十六进制）" % sha)
    url = doc.get("url")
    if not isinstance(url, str) or not url.startswith("https://"):
        errs.append("url=%r（必须 https）" % url)
    pid = doc.get("plugin")
    if not isinstance(pid, str) or not pid.strip():
        errs.append("plugin=%r（要写插件 id —— 宿主用它防串包）" % pid)
    size = doc.get("size")
    if size is not None and (not isinstance(size, int) or size <= 0):
        errs.append("size=%r（要么省略，要么正整数）" % size)

    if errs:
        for e in errs:
            print("FAIL  " + e)
        return 1
    print("OK    %s  plugin=%s version=%s size=%d"
          % (path, pid, doc.get("version", ""), size or 0))
    return 0


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--apk")
    ap.add_argument("--id", default="")
    ap.add_argument("--version", default="")
    ap.add_argument("--notes", default="")
    ap.add_argument("--tag", default=DEFAULT_TAG)
    ap.add_argument("--repo", default=DEFAULT_REPO)
    ap.add_argument("--out", default="plugin-update.json")
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
