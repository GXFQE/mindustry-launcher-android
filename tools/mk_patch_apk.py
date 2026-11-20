# -*- coding: utf-8 -*-
"""把一份现成 APK 做成「整包补丁」（补丁 v2 容器），2026-11-20。

一个补丁 = 一个标准 APK（根上 `classes*.dex` / `lib/<abi>/` / `assets/**`）
+ **根上的 `patch.json`**（schema 2）。本工具只做一件事：给 APK **追加**根清单，
原条目一个字节不动 —— 所以它同时也能用来把任意 fork / 自己的构建当补丁。

用法：
  # 目标基座直接给 --base（自动算 sha256）
  python tools/mk_patch_apk.py --apk mdtx.apk --base official-1607.apk \
      --out mdtx.patch.apk --id mdtx-b509 --name MindustryX --version B509 --role game

  # 或者手给 --target-sha
  python tools/mk_patch_apk.py --apk mdtx.apk --target-sha <64 hex> --out ... --id ...

规则（与 PatchPackage / PatchManifest 的 schema 2 对齐）：
  · `patch.json` 必须在 **zip 根上**（不在 `assets/` 下，不会进游戏资产）；
  · 目标键 = **基座 APK 的 sha256**；一个补丁只对一个基座；
  · `role=game`（整端）才允许带 `lib/<abi>/*.so`，且独占启动项；
  · 补丁 APK 会失去原签名 —— 它只被 launcher 当补丁加载，**不要拿去安装**；
  · 默认**整包**（原 APK 全量）；想只带差分，先自己裁一个 APK 再喂进来。
"""
import argparse
import hashlib
import json
import os
import shutil
import zipfile

ROOT_MANIFEST = "patch.json"
ZIP_EPOCH = (2026, 1, 1, 0, 0, 0)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def count(zf, prefix, pred=None):
    n = 0
    for name in zf.namelist():
        if name.startswith(prefix) and (pred is None or pred(name)):
            n += 1
    return n


def main(argv=None):
    ap = argparse.ArgumentParser(description="append root patch.json to an APK (patch v2)")
    ap.add_argument("--apk", required=True, help="source APK (already has root classes.dex)")
    ap.add_argument("--out", required=True, help="output patch APK (must not exist)")
    ap.add_argument("--base", help="base APK; its sha256 is used as the target key")
    ap.add_argument("--target-sha", help="base APK sha256 (64 lowercase hex); overrides --base")
    ap.add_argument("--id", required=True, help="patch id (stable, e.g. mdtx-b509)")
    ap.add_argument("--name", required=True, help="display name")
    ap.add_argument("--version", default="1.0")
    ap.add_argument("--role", choices=["patch", "game"], default="patch")
    ap.add_argument("--game-version", default="", help="e.g. 160.7 (only informational)")
    ap.add_argument("--author", default="")
    ap.add_argument("--note", default="")
    ap.add_argument("--tool", default="tools/mk_patch_apk.py")
    args = ap.parse_args(argv)

    src = os.path.abspath(args.apk)
    out = os.path.abspath(args.out)
    if not os.path.isfile(src):
        raise SystemExit("no apk: " + src)
    if os.path.exists(out):
        raise SystemExit("out exists: " + out)

    if args.target_sha:
        sha = args.target_sha.strip().lower()
    elif args.base:
        base = os.path.abspath(args.base)
        if not os.path.isfile(base):
            raise SystemExit("no base apk: " + base)
        sha = sha256_file(base)
    else:
        raise SystemExit("need --target-sha or --base")
    if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
        raise SystemExit("target sha must be 64 lowercase hex")

    with zipfile.ZipFile(src) as z:
        names = z.namelist()
        if "classes.dex" not in names:
            raise SystemExit("not an APK layout: missing root classes.dex")
        if ROOT_MANIFEST in names:
            raise SystemExit("source already has root patch.json")
        dexes = [n for n in names
                 if n.startswith("classes") and n.endswith(".dex") and "/" not in n]
        libs = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
        assets = [n for n in names if n.startswith("assets/") and not n.endswith("/")]
    if args.role != "game" and libs:
        raise SystemExit("role=patch cannot carry lib/*.so (%d found); use --role game" % len(libs))

    manifest = {
        "schema": 2,
        "id": args.id,
        "name": args.name,
        "version": args.version,
        "role": args.role,
        "apkSha256": sha,
        "gameVersion": args.game_version,
        "author": args.author,
        "note": args.note,
        "tool": args.tool,
    }

    shutil.copyfile(src, out)
    with zipfile.ZipFile(out, "a", allowZip64=True) as z:
        zi = zipfile.ZipInfo(ROOT_MANIFEST, date_time=ZIP_EPOCH)
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        z.writestr(zi, json.dumps(manifest, ensure_ascii=False, indent=1).encode("utf-8"))

    # 复核：只多了一条根清单，别的条目名一个不差
    with zipfile.ZipFile(out) as z:
        names2 = z.namelist()
        assert ROOT_MANIFEST in names2 and "classes.dex" in names2
        assert len(names2) == len(names) + 1
        bad = [n for n in names2 if n != ROOT_MANIFEST and n not in set(names)]
        assert not bad, bad[:5]
        back = json.loads(z.read(ROOT_MANIFEST).decode("utf-8"))
        assert back.get("schema") == 2 and back.get("apkSha256") == sha

    print("ok   %s" % os.path.basename(src))
    print("  -> %s" % out)
    print("  size   = %d B (source %d B)" % (os.path.getsize(out), os.path.getsize(src)))
    print("  target = %s" % sha)
    print("  role=%s  dex=%d  lib=%d  assets=%d"
          % (args.role, len(dexes), len(libs), len(assets)))


if __name__ == "__main__":
    main()
