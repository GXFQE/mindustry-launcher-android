"""全量扫样本：对每个真 APK 判断「我们的 override.dex 能不能顶替它的 AndroidFi」。

判据（三条全过才算 eligible）：
  a) 该版本有 Larc/backend/android/AndroidFi;，且父类是 Larc/files/Fi;
  b) 该版本 AndroidFi 声明的方法集 ⊆ override.dex 里 AndroidFi 声明的方法集
     （多了就说明有方法我们没覆盖，运行时会静默落到 Fi 的文件实现）
  c) override.dex 引用的每个 Fi 方法，该版本 Fi 都得有（少了就 NoSuchMethodError）
"""
import os
import re
import struct
import sys
import traceback
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexabi import Dex, scan_apk  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
# 样本目录 / override.dex 都可用环境变量覆盖；默认值是本机布局
ROOT = os.environ.get("MDT_SAMPLES") or r"D:\临时文件\文件\game\新版MDT\素材\01-游戏本体"
OVERRIDE = os.environ.get("MDT_OVERRIDE") or os.path.normpath(
    os.path.join(HERE, "..", "poolspike", "out", "override.dex"))

AFI = "Larc/backend/android/AndroidFi;"
AFS = "Larc/backend/android/AndroidFiles;"
FI = "Larc/files/Fi;"
GDX = "Lcom/badlogic/gdx/backends/android/AndroidFileHandle;"


def version_of(name):
    m = re.search(r"[vV](\d+(?:\.\d+)?)", name)
    if m:
        return m.group(1)
    m = re.search(r"[Xx](\d+)", name)
    if m:
        return "X" + m.group(1)
    m = re.search(r"[Bb](\d+)", name)
    if m:
        return "B" + m.group(1)
    return name


def sort_key(v):
    if v.startswith("X"):
        return (2, float(v[1:]))
    if v.startswith("B"):
        return (3, float(v[1:]))
    try:
        return (1, float(v))
    except ValueError:
        return (1, 0.0)


def declared(path):
    """返回 {cls: (super, [sig...])}，只关心三个目标类 + 顺带看 libgdx。"""
    out = {}
    with zipfile.ZipFile(path) as z:
        names = sorted(n for n in z.namelist() if re.match(r"classes\d*\.dex$", n))
        for name in names:
            dex = Dex(z.read(name))
            for i in range(dex.class_defs_size):
                off = dex.class_defs_off + i * 32
                desc = dex.type_name(dex.u32(off))
                if desc not in (AFI, AFS, FI, GDX):
                    continue
                sup_idx = dex.u32(off + 8)
                sup = dex.type_name(sup_idx) if sup_idx != 0xFFFFFFFF else "-"
                data_off = dex.u32(off + 24)
                sigs = [dex.method_sig(m[1]) for m in dex.class_data(data_off)]
                out[desc] = (sup, sigs, name)
    return out


def main():
    with open(OVERRIDE, "rb") as f:
        ov = Dex(f.read())
    ov_afi = []
    ov_fi_refs = set()
    for i in range(ov.class_defs_size):
        off = ov.class_defs_off + i * 32
        desc = ov.type_name(ov.u32(off))
        if desc == AFI:
            ov_afi = [ov.method_sig(m[1]) for m in ov.class_data(ov.u32(off + 24))]
    for i in range(ov.u32(0x58)):
        off = ov.method_ids_off + i * 8
        if ov.type_name(ov.u16(off)) == FI:
            ov_fi_refs.add(ov.string(ov.u32(off + 4)) + ov.proto(ov.u16(off + 2)))

    print("override.dex: AndroidFi 声明 %d 个方法，引用 Fi %d 个方法"
          % (len(ov_afi), len(ov_fi_refs)))
    print()

    rows = []
    for dirpath, _, files in os.walk(ROOT):
        for fn in files:
            if not fn.lower().endswith(".apk"):
                continue
            p = os.path.join(dirpath, fn)
            size = os.path.getsize(p)
            if size < 15 * 1024 * 1024:
                continue
            ver = version_of(fn)
            try:
                d = declared(p)
            except Exception:
                rows.append((ver, size, "PARSE-ERR", "", "", "", "", "ERR", [], [], []))
                traceback.print_exc()
                continue
            afi = d.get(AFI)
            fi = d.get(FI)
            gdx = GDX in d
            if not afi:
                rows.append((ver, size, "NO-AndroidFi" + ("+gdx" if gdx else ""), "", "", "", "",
                             "N/A", [], [], []))
                continue
            sup = afi[0]
            afi_sigs = set(afi[1])
            fi_sigs = set(fi[1]) if fi else set()
            not_ov = sorted(afi_sigs - set(ov_afi))
            miss_fi = sorted(ov_fi_refs - fi_sigs)
            # 未覆盖的方法，只要该版本 Fi 里有同名同签名，就还能链接（退化成文件系统语义）
            fall_dies = sorted(s for s in not_ov if s not in fi_sigs)
            ok = (sup == FI and not fall_dies and not miss_fi)
            rows.append((ver, size, "AndroidFi" + ("+gdx" if gdx else ""), sup,
                         len(afi_sigs), len(not_ov), len(miss_fi),
                         "OK" if ok else "REJECT", not_ov, miss_fi, fall_dies))

    rows.sort(key=lambda r: sort_key(r[0]))
    print("%-9s %10s  %-14s %-6s %5s %7s %6s  %s" %
          ("version", "size(MB)", "shape", "super", "AFi", "not-ovr", "missFi", "verdict"))
    for r in rows:
        ver, size, kind, sup, n_afi, n_not, n_miss = r[:7]
        verdict = r[7]
        print("%-9s %10.1f  %-14s %-6s %5s %7s %6s  %s" %
              (ver, size / 1048576.0, kind,
               sup.replace("Larc/files/", "").replace(";", "") if sup else "",
               n_afi, n_not, n_miss, verdict))
        if len(r) > 10:
            if r[8]:
                tail = "  (Fi has it -> fallthrough)" if not r[10] else ""
                print("          not overridden:", r[8], tail)
            if r[9]:
                print("          Fi MISSING:", r[9])
            if r[10]:
                print("          NO LINK:", r[10])


if __name__ == "__main__":
    main()
