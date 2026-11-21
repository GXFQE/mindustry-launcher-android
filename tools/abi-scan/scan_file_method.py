"""逐版本检查 AndroidFi.file() 的真实语义（我们没覆盖它，靠 Fi.file() 兜底）。

要确认两件事：
  1) 各版本 AndroidFi.file() 是否都长一个样：type==local 时 new File(getLocalStoragePath(), path)，
     其余走 super.file()；
  2) 我们的兜底（Fi.file()）会不会漏掉 local 的重定位 ⇒ 若漏，mods/saves 全走相对路径会炸。

做法：不解全指令，只把 file() 的 code 里出现的常量池下标（16-bit）和图谱比对——看
`Larc/Files;.getLocalStoragePath:()Ljava/lang/String;` 与 `Larc/files/Fi;.file:()Ljava/io/File;`
这两个 method_idx 有没有出现在该方法体内。
"""
import os
import re
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexabi import Dex, uleb  # noqa: E402

ROOT = os.environ.get("MDT_SAMPLES") or r"D:\临时文件\文件\game\新版MDT\素材\01-游戏本体"
AFI = "Larc/backend/android/AndroidFi;"
FI = "Larc/files/Fi;"


def class_methods(dex, want_cls):
    """返回 {sig: code_off}，sig = name+proto。"""
    out = {}
    for i in range(dex.class_defs_size):
        off = dex.class_defs_off + i * 32
        if dex.type_name(dex.u32(off)) != want_cls:
            continue
        data_off = dex.u32(off + 24)
        if data_off == 0:
            continue
        o = data_off
        sf, o = uleb(dex.d, o)
        inf, o = uleb(dex.d, o)
        dm, o = uleb(dex.d, o)
        vm, o = uleb(dex.d, o)
        for _ in range(sf + inf):
            _, o = uleb(dex.d, o)
            _, o = uleb(dex.d, o)
        for cnt in (dm, vm):
            idx = 0
            for _ in range(cnt):
                diff, o = uleb(dex.d, o)
                idx += diff
                _, o = uleb(dex.d, o)
                code_off, o = uleb(dex.d, o)
                if code_off:
                    off2 = dex.method_ids_off + idx * 8
                    name = dex.string(dex.u32(off2 + 4))
                    out[name + dex.proto(dex.u16(off2 + 2))] = code_off
    return out


def insns(dex, code_off):
    n = dex.u32(code_off + 12)
    return struct.unpack_from("<%dH" % n, dex.d, code_off + 16)


def method_idx(dex, cls, sig):
    """在 method_ids 里查 (cls, sig)；sig 形如 'file()Ljava/io/File;'。"""
    for i in range(dex.u32(0x58)):
        off = dex.method_ids_off + i * 8
        if dex.type_name(dex.u16(off)) != cls:
            continue
        name = dex.string(dex.u32(off + 4))
        if name + dex.proto(dex.u16(off + 2)) == sig:
            return i
    return None


def check(path):
    with zipfile.ZipFile(path) as z:
        names = sorted(n for n in z.namelist() if re.match(r"classes\d*\.dex$", n))
        for name in names:
            dex = Dex(z.read(name))
            ms = class_methods(dex, AFI)
            if "file()Ljava/io/File;" not in ms:
                continue
            body = insns(dex, ms["file()Ljava/io/File;"])
            lo = method_idx(dex, "Larc/Files;", "getLocalStoragePath()Ljava/lang/String;")
            hi = method_idx(dex, FI, "file()Ljava/io/File;")
            has_local = lo is not None and lo in body
            has_super = hi is not None and hi in body
            lm = "lastModified()J" in ms
            return has_local, has_super, lm, name
    return None


def main():
    rows = []
    for dirpath, _, files in os.walk(ROOT):
        for fn in files:
            if not fn.lower().endswith(".apk") or os.path.getsize(os.path.join(dirpath, fn)) < 15 << 20:
                continue
            p = os.path.join(dirpath, fn)
            try:
                r = check(p)
            except Exception as e:
                rows.append((fn, "ERR", str(e)[:40]))
                continue
            rows.append((fn, r))
    for fn, r in rows:
        if isinstance(r, tuple):
            has_local, has_super, lm, dexname = r
            print("%-46s local-rebase=%s  super.file=%s  lastModified=%s  (%s)" %
                  (fn[:46], "YES" if has_local else "no ", "YES" if has_super else "no ", lm, dexname))
        else:
            print("%-46s %s" % (fn[:46], r))


if __name__ == "__main__":
    main()
