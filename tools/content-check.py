#!/usr/bin/env python3
"""content.json gate -- 第 300 轮。

`app/assets/content.json` 是**致谢与彩蛋的文案 + 阈值**（第 300 轮从 Java / strings.xml
搬出来的）。它随 APK 发放，坏掉的表现是"关于页那块静默消失 / 彩蛋不响" —— 编译器、
aapt2、i18n 门禁**都不会报**。所以在构建最前面加一道尺子。

检查项（每一条都能算出 false）：
  1. 结构：version / credits / eggs 三段齐全，类型对；
  2. 双语：每条文案 zh 与 en 都非空、长度 ≤ 500；
  3. 占位符：zh 与 en 的 `%n$X` 集合必须相同（否则一种语言能跑、另一种运行期炸）；
  4. 必填占位符：tapsLeft / tapsLine 要有 `%1$d`，带 arg 的段子要有对应参数；
  5. 档位：unlockAt ≥ 1、at > unlockAt、严格递增、条数 ≤ 50；
  6. 行数：致谢 zh 与 en **行数相同**（含空行 —— 空行是"两组分开"的排版约定）；
  7. 关键词：非空、≤ 32 字符、不含空白。

`--selftest`：先证明这把尺子有牙 —— 拿一份最小合法文件喂它（必须过），再逐条变异
（必须被判死）。元断言不过 ⇒ 构建直接停，别拿一把坏尺子说"通过"。

用法：
    python tools/content-check.py [--root DIR] [--selftest]
退出码：0 = 通过；1 = 有错（或尺子本身坏了）。
"""
import json
import os
import re
import sys

MAX_TEXT = 500
MAX_LINES = 100
MAX_MILESTONES = 50
PLACEHOLDER = re.compile(r"%(\d+)\$[sdfx]")
# 裸 %（不是 %n$X，也不是 %%）：String.format 会在运行期抛
BARE_PERCENT = re.compile(r"%(?!%|\d+\$[sdfx])")


def add(errors, where, msg):
    errors.append("%s: %s" % (where, msg))


def text(errors, where, obj, must_have=None):
    """读一条 {zh,en}：两边都非空、长度合规、占位符集合相同、可选必填占位符。"""
    if not isinstance(obj, dict):
        add(errors, where, "not an object")
        return
    langs = {}
    for lang in ("zh", "en"):
        v = obj.get(lang)
        if not isinstance(v, str) or not v.strip():
            add(errors, where, "missing/empty '%s'" % lang)
            continue
        if len(v) > MAX_TEXT:
            add(errors, where, "'%s' too long (%d > %d)" % (lang, len(v), MAX_TEXT))
        if BARE_PERCENT.search(v):
            add(errors, where, "'%s' has a bare %%" % lang)
        langs[lang] = v
    if len(langs) != 2:
        return
    for lang, v in langs.items():
        if must_have and must_have not in v:
            add(errors, where, "'%s' must contain %s" % (lang, must_have))
    z = set(PLACEHOLDER.findall(langs["zh"]))
    e = set(PLACEHOLDER.findall(langs["en"]))
    if z != e:
        add(errors, where, "placeholder mismatch zh=%s en=%s" % (sorted(z), sorted(e)))


def lines(errors, where, obj):
    if not isinstance(obj, dict):
        add(errors, where, "not an object")
        return
    lens = {}
    for lang in ("zh", "en"):
        arr = obj.get(lang)
        if not isinstance(arr, list) or not arr:
            add(errors, where, "missing/empty '%s' list" % lang)
            continue
        if len(arr) > MAX_LINES:
            add(errors, where, "'%s' too many lines (%d)" % (lang, len(arr)))
        for i, s in enumerate(arr):
            if not isinstance(s, str):
                add(errors, "%s.%s[%d]" % (where, lang, i), "not a string")
            elif len(s) > MAX_TEXT:
                add(errors, "%s.%s[%d]" % (where, lang, i), "too long")
        lens[lang] = len(arr)
    if len(lens) == 2 and lens["zh"] != lens["en"]:
        add(errors, where, "line count differs zh=%d en=%d" % (lens["zh"], lens["en"]))


def check(doc):
    errors = []
    if not isinstance(doc, dict):
        return ["root: not an object"]
    if doc.get("version") != 1:
        add(errors, "version", "must be 1 (got %r)" % (doc.get("version"),))

    credits = doc.get("credits")
    if not isinstance(credits, dict):
        add(errors, "credits", "missing")
    else:
        text(errors, "credits.title", credits.get("title"))
        lines(errors, "credits.lines", credits.get("lines"))

    eggs = doc.get("eggs")
    if not isinstance(eggs, dict):
        add(errors, "eggs", "missing")
        return errors

    unlock = eggs.get("unlockAt")
    if not isinstance(unlock, int) or isinstance(unlock, bool) or unlock < 1:
        add(errors, "eggs.unlockAt", "must be an integer >= 1 (got %r)" % (unlock,))
        unlock = None

    token = eggs.get("searchToken")
    if not isinstance(token, str) or not token.strip():
        add(errors, "eggs.searchToken", "missing/empty")
    elif len(token) > 32:
        add(errors, "eggs.searchToken", "too long (%d)" % len(token))
    elif any(c.isspace() for c in token):
        add(errors, "eggs.searchToken", "must not contain whitespace")

    text(errors, "eggs.title", eggs.get("title"))
    text(errors, "eggs.unlocked", eggs.get("unlocked"))
    text(errors, "eggs.already", eggs.get("already"))
    text(errors, "eggs.tapsLeft", eggs.get("tapsLeft"), "%1$d")
    text(errors, "eggs.tapsLine", eggs.get("tapsLine"), "%1$d")
    text(errors, "eggs.searchToast", eggs.get("searchToast"), "%1$s")

    ms = eggs.get("milestones")
    if not isinstance(ms, list):
        add(errors, "eggs.milestones", "missing")
    else:
        if len(ms) > MAX_MILESTONES:
            add(errors, "eggs.milestones", "too many (%d > %d)" % (len(ms), MAX_MILESTONES))
        prev = unlock
        for i, m in enumerate(ms):
            where = "milestones[%d]" % i
            if not isinstance(m, dict):
                add(errors, where, "not an object")
                continue
            at = m.get("at")
            if not isinstance(at, int) or isinstance(at, bool) or at < 1:
                add(errors, where, "bad 'at' (%r)" % (at,))
                continue
            if unlock is not None and at <= unlock:
                add(errors, where, "'at' %d must be > unlockAt %d" % (at, unlock))
            if prev is not None and at <= prev:
                add(errors, where, "'at' %d not ascending (prev %d)" % (at, prev))
            prev = at
            arg = m.get("arg", "")
            if arg not in ("", "count", "date"):
                add(errors, where, "bad 'arg' %r (allowed: count / date)" % (arg,))
                arg = ""
            text(errors, where + ".text", m.get("text"),
                 "%1$d" if arg == "count" else ("%1$s" if arg == "date" else None))

    lst = eggs.get("list")
    if not isinstance(lst, dict):
        add(errors, "eggs.list", "missing")
    else:
        text(errors, "eggs.list.title", lst.get("title"))
        text(errors, "eggs.list.unlock", lst.get("unlock"), "%1$d")
        text(errors, "eggs.list.milestones", lst.get("milestones"), "%1$s")
        text(errors, "eggs.list.longPress", lst.get("longPress"))
        text(errors, "eggs.list.search", lst.get("search"), "%1$s")
    return errors


MINI = {
    "version": 1,
    "credits": {"title": {"zh": "T", "en": "T"},
                "lines": {"zh": ["c"], "en": ["c"]}},
    "eggs": {
        "unlockAt": 7,
        "searchToken": "42",
        "title": {"zh": "G", "en": "G"},
        "tapsLeft": {"zh": "还差 %1$d 次", "en": "%1$d"},
        "unlocked": {"zh": "u", "en": "u"},
        "already": {"zh": "a", "en": "a"},
        "tapsLine": {"zh": "%1$d", "en": "%1$d"},
        "searchToast": {"zh": "%1$s", "en": "%1$s"},
        "milestones": [{"at": 9, "arg": "count", "text": {"zh": "%1$d!", "en": "%1$d!"}}],
        "list": {"title": {"zh": "L", "en": "L"},
                 "unlock": {"zh": "%1$d", "en": "%1$d"},
                 "milestones": {"zh": "%1$s", "en": "%1$s"},
                 "longPress": {"zh": "p", "en": "p"},
                 "search": {"zh": "%1$s", "en": "%1$s"}},
    },
}


def clone(doc):
    return json.loads(json.dumps(doc))


def selftest():
    """先证明这把尺子有牙：一份好文件必须过，四条变异必须判死。"""
    failures = []

    def mutate(fn):
        d = clone(MINI)
        fn(d)
        return d

    cases = [
        ("valid", clone(MINI), False),
        ("version", mutate(lambda d: d.__setitem__("version", 9)), True),
        ("placeholder", mutate(lambda d: d["eggs"]["tapsLeft"].__setitem__("zh", "还差 次")), True),
        ("milestone-at", mutate(lambda d: d["eggs"]["milestones"][0].__setitem__("at", 7)), True),
        ("line-count", mutate(lambda d: d["credits"]["lines"]["zh"].append("x")), True),
    ]
    for name, doc, want_bad in cases:
        errs = check(doc)
        bad = bool(errs)
        if bad != want_bad:
            failures.append("%s: expected %s, got %s"
                            % (name, "reject" if want_bad else "accept", errs or "no error"))
    if failures:
        print("SELFTEST FAILED -- the ruler is broken:")
        for f in failures:
            print("  " + f)
        return 1
    print("selftest: 5/5 cases behave as expected")
    return 0


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if "--root" in argv:
        root = argv[argv.index("--root") + 1]
    if "--selftest" in argv:
        return selftest()

    path = os.path.join(root, "app", "assets", "content.json")
    if not os.path.isfile(path):
        print("FAIL: %s is missing (the credits/easter-egg content ships in the APK)" % path)
        return 1
    if os.path.getsize(path) > 256 * 1024:
        print("FAIL: %s is larger than 256 KB" % path)
        return 1
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
    except Exception as exc:                                # noqa: BLE001 - report, never crash
        print("FAIL: %s does not parse: %s" % (path, exc))
        return 1

    errors = check(doc)
    if errors:
        print("content check: %d problem(s)" % len(errors))
        for e in errors:
            print("  " + e)
        return 1
    eggs = doc["eggs"]
    print("content check: OK (unlockAt=%d, token=%s, milestones=%d, lines=%d)"
          % (eggs["unlockAt"], eggs["searchToken"], len(eggs.get("milestones", [])),
             len(doc["credits"]["lines"]["zh"])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
