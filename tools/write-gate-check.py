#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Write-gate meta-assertion (multi-instance plan §12.7 step 5 / §12.19).

The core invariant of multi-instance mode: a write that touches a *running*
instance's slot / version / instance key must be refused, while reads and
unrelated slots/versions are allowed.  Every write entry is supposed to ask
``WriteGate``; the behavioral SelfTest matrix only proves the entries we
exercise *today*.  This build-time ruler pins the structural side:

  1. ENTRY TABLE: each listed write entry's method body must contain
     ``WriteGate.``.  A missing method also fails (the table went stale).
  2. GLOBAL ``Data.gameAlive(`` budget is frozen: new callers must use
     ``WriteGate`` instead; ``gameAlive`` is only for migration / recovery /
     process-kill style global actions.
  3. Deletion surface: the number of ``deleteTree(`` calls per file is frozen;
     a new file or a new call site must be reviewed and added here.
  4. ``killBackgroundProcesses(`` is allowed only in ``Data.java``
     (the single "kill the game process" implementation).

Run ``--selftest`` first: the ruler must prove it can reject a known-bad input
and accept a known-good one.  Output is ASCII (build.sh decodes as GBK).

Exit codes: 0 = pass, 1 = fail.
"""
import argparse
import os
import re
import sys

CORE_REL = os.path.join("app", "src", "io", "mdt", "launcher")

# (file, method, why) -- write entries that touch instance data.
ENTRIES = [
    ("Backup.java", "restore0", "restore a slot snapshot"),
    ("BatchIo.java", "runImport", "batch import"),
    ("BatchIo.java", "importSync", "batch import (sync)"),
    ("BlueprintFiles.java", "importSchem", "blueprint import"),
    ("BlueprintFiles.java", "deleteToTrash", "blueprint delete"),
    ("Data.java", "renameSlot", "slot rename"),
    ("Data.java", "deleteSlotForever", "slot delete"),
    ("Importer.java", "importApk0", "game package import"),
    ("Importer.java", "trashVersion", "game version delete"),
    ("Importer.java", "restoreVersion", "game version restore"),
    ("MapFiles.java", "importMap", "map import"),
    ("MapFiles.java", "commitSaveAsMap", "save-as-map"),
    ("MapFiles.java", "deleteToTrash", "map delete"),
    ("Mods.java", "setEnabledAll", "mods enable/disable all"),
    ("Mods.java", "setEnabled", "mod toggle"),
    ("Mods.java", "importPackage", "mod package import"),
    ("Mods.java", "copyMods", "mods copy"),
    ("Mods.java", "deleteToTrash", "mods delete"),
    ("Msav.java", "stage", "save import (stage)"),
    ("Msav.java", "commit", "save import (commit)"),
    ("Patches.java", "enable", "patch enable"),
    ("Patches.java", "disable", "patch disable"),
    ("Patches.java", "pruneStale", "patch prune"),
    ("SettingsCopy.java", "apply", "cross-slot settings apply"),
    ("SlotIo.java", "deleteSaveToTrash", "save delete"),
    ("SlotOps.java", "deleteSlot", "slot delete (UI entry)"),
    ("SlotWrite.java", "wipeSlot", "slot wipe"),
    ("SlotZip.java", "importSlot", "whole-slot import"),
    ("SlotZip.java", "extract0", "whole-slot extract"),
    ("Trash.java", "trashSlot", "slot to trash"),
    ("Trash.java", "restoreFrom", "trash restore"),
    ("VersionOps.java", "delete", "version delete"),
    ("PluginHost.java", "writeSettingsNow", "plugin write game settings"),
    ("PluginHost.java", "writeItem", "plugin write item"),
    ("PluginHost.java", "addItemNow", "plugin add item"),
    ("PluginHost.java", "trashItem", "plugin trash item"),
]

# Budget for `Data.gameAlive(` call sites in app/src (non-test).
GLOBAL_GAMEALIVE_BUDGET = 9

# Frozen `deleteTree(` count per file (only files that currently have it).
# SelfTest is the ruler's own test data and is not frozen.
DELETION_BASELINE = {
    "Backup.java": 4,
    "BatchIo.java": 3,
    "BlueprintFiles.java": 3,
    "Data.java": 6,
    "Importer.java": 2,
    "MapFiles.java": 12,
    "Mods.java": 8,
    "Patches.java": 4,
    "Plugins.java": 2,
    "Recovery.java": 2,
    "SlotWrite.java": 1,
    "Trash.java": 8,
}
DELETION_EXCLUDE = {"SelfTest.java"}

KILL_BG_MAX = 1
KILL_BG_ALLOWED_FILE = "Data.java"


def core_sources(root):
    """name -> text for app/src/io/mdt/launcher/*.java"""
    d = os.path.join(root, CORE_REL)
    out = {}
    if not os.path.isdir(d):
        return out
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".java"):
            continue
        with open(os.path.join(d, fn), "r", encoding="utf-8", errors="replace") as f:
            out[fn] = f.read()
    return out


def find_methods(text, name):
    """Return a list of method body texts (each including its signature).

    Declaration-oriented: line starts with >=4 spaces, has at least one
    modifier keyword, and has no '(' / '=' before the method name (so a call
    with an assignment is not mistaken for a declaration).  The signature may
    wrap over several lines; brace matching starts at the first '{'.
    Overloads are all returned: one gated implementation is enough.
    """
    lines = text.split("\n")
    pat = re.compile(
        r"^\s{4,}(?:@\w+\s+)*"
        r"(?:(?:public|private|protected|static|final|synchronized|native|abstract)\s+)+"
        r"[^;{}(),=]*?\b" + re.escape(name) + r"\s*\(")
    out = []
    for i, line in enumerate(lines):
        if not pat.match(line.rstrip()):
            continue
        j = i
        while j < len(lines) and j < i + 14 and "{" not in lines[j]:
            j += 1
        if j >= len(lines) or j >= i + 14:
            continue
        depth = 0
        for k in range(j, len(lines)):
            depth += lines[k].count("{") - lines[k].count("}")
            if depth == 0:
                out.append("\n".join(lines[i:k + 1]))
                break
    return out


def count_needle(text, needle):
    return text.count(needle)


def check_entries(src):
    errs = []
    for fn, method, _why in ENTRIES:
        text = src.get(fn)
        if text is None:
            errs.append("entry file missing: %s (%s)" % (fn, method))
            continue
        bodies = find_methods(text, method)
        if not bodies:
            errs.append("entry method not found (stale table?): %s.%s"
                        % (fn, method))
            continue
        if not any("WriteGate." in b for b in bodies):
            errs.append("write entry has no WriteGate: %s.%s" % (fn, method))
    return errs


def check_global_gamealive(src):
    errs = []
    total = 0
    details = []
    for fn, text in src.items():
        n = count_needle(text, "Data.gameAlive(")
        if n:
            total += n
            details.append("%s=%d" % (fn, n))
    if total > GLOBAL_GAMEALIVE_BUDGET:
        errs.append(
            "Data.gameAlive( call sites %d > budget %d (%s); use WriteGate "
            "for instance-scoped decisions" % (total, GLOBAL_GAMEALIVE_BUDGET,
                                               ", ".join(details)))
    return errs, total


def check_deletion_surface(src):
    errs = []
    seen = {}
    for fn, text in src.items():
        n = count_needle(text, "deleteTree(")
        if not n:
            continue
        seen[fn] = n
        if fn in DELETION_EXCLUDE:
            continue
        if fn not in DELETION_BASELINE:
            errs.append("new deletion surface: %s has %d deleteTree( call(s); "
                        "review it and add to DELETION_BASELINE" % (fn, n))
        elif n > DELETION_BASELINE[fn]:
            errs.append("deletion surface grew: %s has %d > %d deleteTree( "
                        "call(s); review the new call site"
                        % (fn, n, DELETION_BASELINE[fn]))
    return errs, seen


def check_kill_bg(src):
    errs = []
    total = 0
    files = []
    for fn, text in src.items():
        n = count_needle(text, "killBackgroundProcesses(")
        if not n:
            continue
        total += n
        files.append(fn)
        if fn != KILL_BG_ALLOWED_FILE:
            errs.append("killBackgroundProcesses( outside %s: %s"
                        % (KILL_BG_ALLOWED_FILE, fn))
    if total > KILL_BG_MAX:
        errs.append("killBackgroundProcesses( count %d > %d" % (total, KILL_BG_MAX))
    return errs, total


def run_checks(src):
    e1 = check_entries(src)
    e2, gamealive = check_global_gamealive(src)
    e3, deletion = check_deletion_surface(src)
    e4, killbg = check_kill_bg(src)
    return e1 + e2 + e3 + e4, {
        "gamealive": gamealive,
        "deletion": deletion,
        "killbg": killbg,
    }


def mk_src(files):
    return dict(files)


def selftest():
    """Prove the ruler has teeth (reject known-bad, accept known-good)."""
    cases = []

    # 1. gated entry is accepted
    good = mk_src({"Data.java":
                   "    static boolean deleteSlotForever(Context ctx) {\n"
                   "        if (WriteGate.blockedSlot(ctx, \"s\")) return false;\n"
                   "        return true;\n"
                   "    }\n"})
    e, _ = run_checks(good)
    cases.append(("gated entry accepted", not any("write entry has no" in x for x in e)))

    # 2. ungated entry is rejected
    bad = mk_src({"Data.java":
                  "    static boolean deleteSlotForever(Context ctx) {\n"
                  "        return true;\n"
                  "    }\n"})
    e, _ = run_checks(bad)
    cases.append(("ungated entry rejected", any("write entry has no" in x for x in e)))

    # 3. gameAlive budget overflow is rejected
    over = {("F%d.java" % i): ("Data.gameAlive(ctx);\n" * 2) for i in range(5)}
    e, _ = run_checks(over)
    cases.append(("gameAlive budget rejected",
                  any("Data.gameAlive( call sites" in x for x in e)))

    # 4. unknown deletion file is rejected
    e, _ = run_checks({"NewThing.java": "void f() { deleteTree(x); }\n"})
    cases.append(("new deletion surface rejected",
                  any("new deletion surface" in x for x in e)))

    # 5. killBackgroundProcesses outside Data.java is rejected
    e, _ = run_checks({"Other.java": "void f() { am.killBackgroundProcesses(p); }\n"})
    cases.append(("stray killBackgroundProcesses rejected",
                  any("killBackgroundProcesses( outside" in x for x in e)))

    # 6. clean input passes: every entry file/method is gated, Data owns the
    #    deletion surface and the single killBackgroundProcesses call.
    clean = {}
    for fn, method, _why in ENTRIES:
        clean.setdefault(fn, "")
        clean[fn] += ("    static boolean %s() {\n"
                      "        WriteGate.check();\n"
                      "        return true;\n"
                      "    }\n" % method)
    clean["Data.java"] += ("    static boolean deleteSlotForever2() {\n"
                           "        deleteTree(x);\n"
                           "        return true;\n"
                           "    }\n"
                           "    static void killGame(Context ctx) {\n"
                           "        am.killBackgroundProcesses(pkg);\n"
                           "    }\n")
    e, _ = run_checks(clean)
    cases.append(("clean input accepted", not e))

    failed = [name for name, ok in cases if not ok]
    print("write-gate selftest: %d/%d" % (len(cases) - len(failed), len(cases)))
    for name in failed:
        print("  FAIL: " + name)
    return not failed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))))
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return 0 if selftest() else 1
    src = core_sources(args.root)
    if not src:
        print("FAIL: no java sources under %s" % os.path.join(args.root, CORE_REL))
        return 1
    errs, info = run_checks(src)
    if errs:
        print("write-gate check: FAIL (%d problem(s))" % len(errs))
        for e in errs:
            print("  - " + e)
        return 1
    print("write-gate check: OK (%d entries gated, %d global gameAlive, "
          "%d deletion files frozen, %d killBackgroundProcesses)"
          % (len(ENTRIES), info["gamealive"], len(DELETION_BASELINE),
             info["killbg"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
