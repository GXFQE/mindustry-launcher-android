# -*- coding: utf-8 -*-
"""F18 oracle：在本机独立复刻 Compat.probe 的全部判据，作为真机结果的对照真值。

口径必须与 app/src/io/mdt/launcher/Compat.java 一致：
  ① dex 里有没有 "mindustry/android/AndroidLauncher"（点号形式不是这个）
  ② lib/<abi>/ 下有没有 libarc.so / libarc-freetype.so
  ③ 包内 ABI ∩ 设备 SUPPORTED_ABIS（按设备优先级取第一个）
  ④ 该 ABI 下核心库的 PT_LOAD p_align 最大值 vs 设备页大小
  ⑤ dex 里有没有 "mindustry.data.dir"（软：决定是否回退改名交换）
"""
import os, re, struct, sys, zipfile

ENTRY = b"mindustry/android/AndroidLauncher"
PROP = b"mindustry.data.dir"
NATIVE_LIBS = ["libarc.so", "libarc-freetype.so"]
PAGE = 4096
DEVICE_ABIS = ["arm64-v8a"]   # adb: ro.product.cpu.abilist = arm64-v8a（纯 64 位机）


def elf_max_align(d):
    if d is None or len(d) < 64:
        return 0
    if d[0:4] != b"\x7fELF":
        return 0
    is64 = d[4] == 2
    if d[5] != 1:
        return 0
    if is64:
        phoff = struct.unpack_from("<Q", d, 0x20)[0]
        phentsize = struct.unpack_from("<H", d, 0x36)[0]
        phnum = struct.unpack_from("<H", d, 0x38)[0]
    else:
        phoff = struct.unpack_from("<I", d, 0x1C)[0]
        phentsize = struct.unpack_from("<H", d, 0x2A)[0]
        phnum = struct.unpack_from("<H", d, 0x2C)[0]
    if phentsize < 8 or phnum <= 0:
        return 0
    need = 0x38 if is64 else 0x20
    mx = 0
    for i in range(phnum):
        p = phoff + i * phentsize
        if p < 0 or p + need > len(d):
            break
        if struct.unpack_from("<I", d, p)[0] != 1:      # 只要 PT_LOAD
            continue
        al = struct.unpack_from("<Q", d, p + 0x30)[0] if is64 else \
             struct.unpack_from("<I", d, p + 0x1C)[0]
        mx = max(mx, al)
    return mx


def read_props(zf):
    for n in ("assets/version.properties", "version.properties"):
        try:
            t = zf.read(n).decode("utf-8", "replace")
        except KeyError:
            continue
        d = dict(re.findall(r"^\s*([A-Za-z0-9_.]+)\s*=\s*(.*)$", t, re.M))
        return n, d
    return None, {}


def probe(path):
    r = {"path": path, "size": os.path.getsize(path)}
    zf = zipfile.ZipFile(path)
    try:
        src, props = read_props(zf)
        r["num"] = props.get("number", "?")
        r["build"] = props.get("build", "?")
        r["modifier"] = props.get("modifier", "?")
        r["type"] = props.get("type", "?")

        abis, libs, dexs = set(), set(), []
        for n in zf.namelist():
            if n.startswith("lib/"):
                s = n.find("/", 4)
                if s > 4 and n.find("/", s + 1) < 0:
                    abis.add(n[4:s])
                    libs.add(n[s + 1:])
            elif n.endswith(".dex") and "/" not in n:
                dexs.append(n)
        r["abis"] = sorted(abis)
        r["libs"] = sorted(libs)
        r["dex"] = len(dexs)

        abi = next((a for a in DEVICE_ABIS if a in abis), None)
        r["abi"] = abi
        core = next((l for l in NATIVE_LIBS if l in libs), None)
        r["core"] = core

        align = 0
        if abi and core:
            with zf.open("lib/%s/%s" % (abi, core)) as f:
                align = elf_max_align(f.read(128 * 1024))
        r["align"] = align

        then, tprop = False, False
        for dn in dexs:
            d = zf.read(dn)
            if not then and ENTRY in d:
                then = True
            if not tprop and PROP in d:
                tprop = True
            if then and tprop:
                break
        r["entry"] = then
        r["prop"] = tprop

        align_ok = True if (align == 0 or PAGE == 0) else align >= PAGE
        r["ok"] = bool(then and abi and core and align_ok)
        if not then:
            r["why"] = "缺入口类"
        elif not abi:
            r["why"] = "ABI 无交集"
        elif not core:
            r["why"] = "缺 native 库（gdx 时代）"
        elif not align_ok:
            r["why"] = "页对齐不合格"
        else:
            r["why"] = ""
        return r
    finally:
        zf.close()


if __name__ == "__main__":
    print("%-14s %-6s %-7s %-7s %-5s %-17s %-5s %-6s %-6s %-4s %s" % (
        "样本", "num", "build", "mod", "entry", "core", "abi", "align", "prop", "判定", "原因"))
    for p in sys.argv[1:]:
        r = probe(p)
        print("%-14s %-6s %-7s %-7s %-5s %-17s %-5s %-6s %-6s %-4s %s" % (
            os.path.basename(p), r["num"], r["build"], r["modifier"],
            "有" if r["entry"] else "无",
            r["core"] or "无",
            r["abi"] or "-",
            str(r["align"] // 1024) + "K" if r["align"] else "?",
            "有" if r["prop"] else "无",
            "通过" if r["ok"] else "★拒绝", r["why"]))
        print("      abis=%s" % r["abis"])
        print("      libs=%s   dex=%d" % (r["libs"], r["dex"]))
