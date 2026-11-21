"""扫 APK 里的 classes*.dex，抽出三个关键类的方法签名，用来对比历史版本的 arc 文件层 ABI。"""
import hashlib
import os
import struct
import sys
import zipfile

TARGETS = [
    "Larc/backend/android/AndroidFi;",
    "Larc/backend/android/AndroidFiles;",
    "Larc/files/Fi;",
]


def uleb(b, o):
    r = 0
    s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7F) << s
        if not (x & 0x80):
            return r, o
        s += 7


class Dex:
    def __init__(self, d):
        self.d = d
        if d[:4] != b"dex\n":
            raise ValueError("not dex")
        self.string_ids_off = self.u32(0x3C)
        self.type_ids_off = self.u32(0x44)
        self.proto_ids_off = self.u32(0x4C)
        self.method_ids_off = self.u32(0x5C)
        self.class_defs_off = self.u32(0x64)
        self.class_defs_size = self.u32(0x60)

    def u16(self, o):
        return struct.unpack_from("<H", self.d, o)[0]

    def u32(self, o):
        return struct.unpack_from("<I", self.d, o)[0]

    def string(self, i):
        off = self.u32(self.string_ids_off + i * 4)
        _, o = uleb(self.d, off)
        end = self.d.index(b"\x00", o)
        return self.d[o:end].decode("utf-8", "replace")

    def type_name(self, i):
        return self.string(self.u32(self.type_ids_off + i * 4))

    def proto(self, i):
        off = self.proto_ids_off + i * 12
        ret = self.type_name(self.u32(off + 4))
        params_off = self.u32(off + 8)
        if params_off == 0:
            return "()" + ret
        n = self.u32(params_off)
        parts = [self.type_name(self.u16(params_off + 4 + k * 2)) for k in range(n)]
        return "(" + "".join(parts) + ")" + ret

    def method_sig(self, i):
        off = self.method_ids_off + i * 8
        name = self.string(self.u32(off + 4))
        return name + self.proto(self.u16(off + 2))

    def class_data(self, off):
        """返回 [(kind, method_idx, has_code)]，kind 是 direct/virtual。"""
        if off == 0:
            return []
        o = off
        sf, o = uleb(self.d, o)
        inf, o = uleb(self.d, o)
        dm, o = uleb(self.d, o)
        vm, o = uleb(self.d, o)
        for _ in range(sf + inf):
            _, o = uleb(self.d, o)
            _, o = uleb(self.d, o)
        out = []
        for kind, cnt in (("direct", dm), ("virtual", vm)):
            idx = 0
            for _ in range(cnt):
                diff, o = uleb(self.d, o)
                idx += diff
                acc, o = uleb(self.d, o)
                code, o = uleb(self.d, o)
                out.append((kind, idx, code != 0))
        return out


def scan_apk(path):
    found = {}
    with zipfile.ZipFile(path) as z:
        names = sorted(n for n in z.namelist() if n.endswith(".dex") and n.startswith("classes"))
        for name in names:
            dex = Dex(z.read(name))
            for i in range(dex.class_defs_size):
                off = dex.class_defs_off + i * 32
                cls_idx = dex.u32(off)
                sup_idx = dex.u32(off + 8)
                data_off = dex.u32(off + 24)
                desc = dex.type_name(cls_idx)
                if desc not in TARGETS:
                    continue
                sup = dex.type_name(sup_idx) if sup_idx != 0xFFFFFFFF else "-"
                methods = []
                for kind, midx, has_code in dex.class_data(data_off):
                    methods.append((kind, dex.method_sig(midx), has_code))
                found.setdefault(desc, {"super": sup, "dex": name, "methods": []})
                found[desc]["methods"] = methods
    return found


def dump(path):
    base = os.path.basename(path)
    found = scan_apk(path)
    lines = ["== %s" % base]
    for t in TARGETS:
        info = found.get(t)
        if not info:
            lines.append("  %s: 未找到" % t)
            continue
        v = sorted(m[1] for m in info["methods"] if m[0] == "virtual")
        d = sorted(m[1] for m in info["methods"] if m[0] == "direct")
        lines.append("  %s  super=%s  dex=%s" % (t, info["super"], info["dex"]))
        lines.append("    direct(%d): %s" % (len(d), " ".join(d)))
        lines.append("    virtual(%d): %s" % (len(v), " ".join(v)))
    return "\n".join(lines)


def fingerprint(path):
    """只取 AndroidFi 的 virtual+direct 方法名集合，算一个短哈希。"""
    found = scan_apk(path)
    info = found.get(TARGETS[0])
    if not info:
        return "MISSING", 0
    sig = "\n".join(sorted(m[1] for m in info["methods"]))
    return hashlib.sha256(sig.encode()).hexdigest()[:12], len(info["methods"])


if __name__ == "__main__":
    apk_dir = sys.argv[1]
    out_path = sys.argv[2]
    apks = sorted(f for f in os.listdir(apk_dir) if f.endswith(".apk"))
    blocks = []
    for f in apks:
        p = os.path.join(apk_dir, f)
        fp, n = fingerprint(p)
        print("%-24s AndroidFi %s  methods=%d" % (f, fp, n))
        blocks.append(dump(p))
    with open(out_path, "w", encoding="utf-8") as out:
        out.write("\n\n".join(blocks))
    print("written", out_path)
