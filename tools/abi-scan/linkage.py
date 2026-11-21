"""检查我们编出来的 override.dex 是否只依赖各版本都存在的 arc 符号。

办法：把 override.dex 的 method_ids / field_ids 里指向 Larc/files/Fi; 的条目全列出来，
再逐个版本比对「它自己声明的 Fi 成员表」，缺一个就是该版本链不上。
"""
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexabi import Dex, scan_apk


def refs(dex):
    """返回 (methods, fields)：指向 Fi 的方法/字段引用（名称+签名）。"""
    methods = []
    for i in range(dex.u32(0x58)):  # method_ids_size
        off = dex.method_ids_off + i * 8
        cls = dex.type_name(dex.u16(off))
        if cls != "Larc/files/Fi;":
            continue
        name = dex.string(dex.u32(off + 4))
        methods.append(name + dex.proto(dex.u16(off + 2)))
    fields = []
    field_ids_size = dex.u32(0x50)
    field_ids_off = dex.u32(0x54)
    for i in range(field_ids_size):
        off = field_ids_off + i * 8
        cls = dex.type_name(dex.u16(off))
        if cls != "Larc/files/Fi;":
            continue
        typ = dex.type_name(dex.u16(off + 2))
        name = dex.string(dex.u32(off + 4))
        fields.append(name + ":" + typ)
    return sorted(set(methods)), sorted(set(fields))


def declared(apk, cls="Larc/files/Fi;"):
    """该 APK 里 Fi 自己声明的成员。"""
    found = scan_apk(apk)
    info = found.get(cls)
    if not info:
        return None, None
    return sorted(m[1] for m in info["methods"]), info


if __name__ == "__main__":
    dex_path, apk_dir = sys.argv[1], sys.argv[2]
    with open(dex_path, "rb") as f:
        d = Dex(f.read())
    ms, fs = refs(d)
    print("override.dex 引用的 Fi 方法 %d 个:" % len(ms))
    for m in ms:
        print("   ", m)
    print("override.dex 引用的 Fi 字段 %d 个:" % len(fs))
    for x in fs:
        print("   ", x)
    print()
    for f in sorted(os.listdir(apk_dir)):
        if not f.endswith(".apk"):
            continue
        v, info = declared(os.path.join(apk_dir, f))
        if v is None:
            print("%-22s MISSING Fi" % f)
            continue
        miss = [m for m in ms if m not in v]
        print("%-22s 缺 %d 个方法 %s" % (f, len(miss), miss if miss else "OK"))
