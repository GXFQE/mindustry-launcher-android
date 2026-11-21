import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexabi import scan_apk, TARGETS

apk_dir = sys.argv[1]
apks = sorted(f for f in os.listdir(apk_dir) if f.endswith(".apk"))
data = {f: scan_apk(os.path.join(apk_dir, f)) for f in apks}

base = apks[0]
for t in TARGETS:
    print("==", t)
    sigs = {}
    for f in apks:
        info = data[f].get(t)
        if not info:
            print("   %-22s MISSING" % f)
            continue
        sigs[f] = {"direct": sorted(m[1] for m in info["methods"] if m[0] == "direct"),
                   "virtual": sorted(m[1] for m in info["methods"] if m[0] == "virtual"),
                   "super": info["super"]}
    ref = sigs.get(base)
    if ref is None:
        print("   base missing, skip")
        continue
    for f in apks:
        s = sigs.get(f)
        if not s:
            continue
        d_add = [x for x in s["direct"] if x not in ref["direct"]]
        d_del = [x for x in ref["direct"] if x not in s["direct"]]
        v_add = [x for x in s["virtual"] if x not in ref["virtual"]]
        v_del = [x for x in ref["virtual"] if x not in s["virtual"]]
        same = not (d_add or d_del or v_add or v_del) and s["super"] == ref["super"]
        print("   %-22s %s super=%s direct=%d virtual=%d" % (
            f, "SAME" if same else "DIFF", s["super"], len(s["direct"]), len(s["virtual"])))
        for x in d_del: print("       -direct ", x)
        for x in d_add: print("       +direct ", x)
        for x in v_del: print("       -virtual", x)
        for x in v_add: print("       +virtual", x)
    print()
