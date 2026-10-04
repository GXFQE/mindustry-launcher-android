#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
i18n-check.py -- MDT 启动器「国际化构建期门禁」（研究文档 docs/i18n-feasibility.md 的 P0）

两类检查，互相独立：

  A) 资源层  app/res/
     RES-01  语言目录独有的 key（默认目录里没有）—— aapt2 会**静默移除**它，只打一行 warn
     RES-02  每条 key 的 %n$ 参数索引集合必须与默认目录一致（语序/重复不限）
     RES-03  不许出现裸 %s / %d（必须 %1$s 这种带位置的形式）
     RES-04  值不许有**真的**前导/尾随空白（aapt2 会剥掉）—— 要留白用 \\u0020
     RES-05  \\u0020 白名单（留白是"碎片式文案"的补丁，必须是有意识的决定）
     RES-06  同一个语言目录里同名资源重复
     RES-07  Java 里引用的 R.string.* 必须在默认目录里存在
     RES-08  android:label 引用的串不许含占位符（系统不会去 format 它）
     RES-09  每个 `·` 的两侧都必须是空格（与 SelfTest.sepsOk 同一条规则，但作用于全部语言）
     RES-10  语言名单（R.array/app_languages）必须与 values-xx/ 目录**双向**一一对应
     RES-11  values*/ 的 XML 注释里不许出现「星号+斜杠」（会被搬进 R.java 当 Javadoc ⇒ 打炸 javac）
     RES-12  值里不许有**裸双引号**（aapt2 会静默删掉；要显示引号写 \\"）
     RES-13  值里不许有**没转义的撇号**（奇数次 ⇒ aapt2 报 `file failed to compile.` **不给行号**；
             偶数次 ⇒ 被当成「引起来的一段」**悄无声息**；要写 \\' 或改写措辞绕开）
     RES-00  （INFO）默认目录里定义了却没有任何 Java 引用的 key

  B) 源码层  app/src/
     SRC-01  不许用「文案串」当判据：.contains("…") / .startsWith("…") / NAME.equals(…) …
             命中条件 = 判据的实参是 ① 含中文 ② 纯状态符号（✅/❌/⚠ 这类 Unicode 分类 So）
             ③ 一个「值本身是文案」的常量（如 MapStats.SRC_MOD = "模组"）。
             ⚠️ 一翻译就**静默改行为**，不报错不崩溃 —— 这是本门禁最重要的一条。
     SRC-02  Java 里「含中文的字符串字面量」**只许降**（每个文件的上限记在台账里）。
             实测 > 台账 ⇒ 新加了中文，搬进资源；实测 < 台账 ⇒ 把台账改小（那一列就是 P3 的进度）；
             有中文却不在台账里 ⇒ 也报。
             ⚠️ 它数的是**全部**含中文字面量，不区分给谁看 —— `SelfTest` 的断言消息、
             `dev_*` 直通口、`report-*.txt` 那几类**本来就该留中文**，台账里那些大数字是
             "已知且有意保留"，判据只保证**不再增加**。

 已知缺陷台账：tools/i18n-known.txt
      里面记的条目**必须还在复现**；已经修好的条目会让本脚本报 STALE 并失败
      （判据对 ≠ 清单全，两个方向都要跑）。

 Java 中文字面量台账：tools/i18n-java-budget.txt
      **每个文件一行「文件名 = 上限」**，由本脚本的同一把尺子生成
      （`tools/../.tmp-i18n/gen-java-budget.py` 那种脚本 import 本模块来写，
       **别手写数字** —— 手写迟早与判据口径漂移）。改它 = 一次有意识的决定。

用法:
    python tools/i18n-check.py                  # 检查（构建期调用；根目录由脚本位置推断）
    python tools/i18n-check.py --root <工程根>
    python tools/i18n-check.py --selftest       # 元断言：拿已知坏的输入喂自己，要求判死
    python tools/i18n-check.py --utf8           # 输出不做 ASCII 转义（人看的终端用）
    python tools/i18n-check.py --verbose        # 连 INFO 明细一起打

退出码: 0 通过 / 1 有 ERROR 或 STALE / 2 用法或环境错误 / 3 **元断言失败（这把尺子本身坏了）**

设计纪律（与工程一致，别改）:
  * 输出默认 **纯 ASCII** —— build.sh 的 echo 全是 ASCII，因为 shell 输出会被按 GBK 解码；
    非 ASCII 内容一律转成 \\uXXXX，既不乱码又能直接复制。
  * 规则消息一律 ASCII；只有"引用的原文"可能是非 ASCII。
  * 行号只在"报告给人看"时用；台账的匹配**不看行号**（改一行代码不该让台账失效），
    只看 <规则id> | <文件> | <指纹>。
"""

from __future__ import annotations

import os
import re
import shutil
import sys
import unicodedata
from xml.parsers import expat

# ---------------------------------------------------------------------------
# 常量：规则 id / 目录名 / 白名单
# ---------------------------------------------------------------------------

RES_DIR = os.path.join("app", "res")
SRC_DIR = os.path.join("app", "src")
MANIFEST = os.path.join("app", "AndroidManifest.xml")
KNOWN_FILE = os.path.join("tools", "i18n-known.txt")
# SRC-02 的台账：**每个 Java 文件里"含中文的字符串字面量"条数的上限**。
#   ★ 它的作用是把 P3（把中文搬进资源）变成一个**只许降不许升**的数字：
#     · 某文件**超出**台账 ⇒ 你新加了用户可能看到的中文，搬进资源去；
#     · 某文件**低于**台账 ⇒ 你已经搬走了几条，**把台账那一行改小**（这一行就是 P3 的进度）。
#   ⚠️ 它数的是"含 CJK 的字符串字面量"，**不区分**给用户看的还是给维护者看的
#     （`SelfTest` 的断言消息、`dev_*` 口、落盘报告那几类**本来就该留中文**）。
#     所以台账里那些大数字不是"欠债"，是"已知且有意保留"；判据只保证**不再增加**。
BUDGET_FILE = os.path.join("tools", "i18n-java-budget.txt")
# 不纳入 SRC-02 预算的文件。
#   `SelfTest.java` = **自检**：它的中文是**断言期望值**（自检语言钉死在 zh、那些中文本来就不该搬），
#   而且每加一条断言就会变 ⇒ 计入预算只会"天天红"，最后大家闭着眼把数字改大 ——
#   那正是台账变消音器的路。**排除它比"每次加断言都改数字"诚实**。
BUDGET_SKIP = {"SelfTest.java"}
# 一个字符串字面量的内容（不跨行；Java 里跨行是相邻字面量相加 ⇒ 每个片段各算一条）
LITERAL_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')

# RES-05：「允许用 \u0020 留白」的 key 白名单。
#   这几条都是**贴在别的串前后的碎片**，必须有前导空格，而 aapt2 会把字面空格剥掉。
#   ⚠️ 首选做法是把它改成**整句资源 + %1$s**；这份白名单是"暂时留着、并且要看得见"的意思。
#   ★ 加一条进白名单 = 一次**有意识的决定**，不是消音器：没列进来的 key 用了 \u0020 就报 ERROR。
U0020_WHITELIST = {
    "slot_current_suffix",      # "\u0020\u0020[当前]"      贴在槽名后面
    "mods_kind_s_plus_res",     # "\u0020+ 资源"            贴在模组类型后面
    "row_upstream_fmt",         # "\u0020(based on %1$s)"   贴在版本号后面（P1 加：中文用全角（）
                                #   自带视觉间距，英文的 ( 不加空格会跟版本号粘在一起。
                                #   ⚠️ 迟早该改成整句资源，那时这条白名单要撤掉。）
    "list_join_sep",            # ",\u0020" / "、"           **列表分隔符**（P3 加）
                                #   它天生就是"夹在两段之间的碎片"，没法改成整句
                                #   （中文用顿号、英文必须逗号+空格）⇒ 这一条属于
                                #   白名单文档里说的"确有必要"那一类。
}

# SRC-01：判据类调用。这些方法的**实参**如果是一段文案，就说明"中文在当判据用"。
JUDGE_METHODS = (
    "contains", "startsWith", "endsWith", "equals", "equalsIgnoreCase",
    "indexOf", "lastIndexOf", "matches", "split", "replace", "replaceAll",
)

# 判据实参里允许出现的"技术性"内容 —— 用来把误报挡掉（路径符号、扩展名、ASCII 键名…）。
# 判定顺序：先看是不是 CJK / 状态符号，都不是就放过；这个集合只是给"看着可疑但要放过"留个口子。
TECH_ARG_EXACT = {".", "..", "/", "\\", ":", "|", "-", "_", "", " ", "  "}

# ---------------------------------------------------------------------------
# 结果
# ---------------------------------------------------------------------------


class Finding:
    __slots__ = ("rule", "severity", "rel", "line", "key", "msg")

    def __init__(self, rule, severity, rel, line, key, msg):
        self.rule = rule          # "RES-01" / "SRC-01" / ...
        self.severity = severity  # "ERROR" / "WARN" / "INFO"
        self.rel = rel.replace("\\", "/")
        self.line = line or 0
        self.key = key or ""      # 台账指纹（不含行号）
        self.msg = msg

    @property
    def where(self):
        return "%s:%d" % (self.rel, self.line) if self.line else self.rel


def ascii_safe(s):
    """把非 ASCII 转成 \\uXXXX / \\UXXXXXXXX，保证在 GBK 控制台上不乱码。"""
    out = []
    for ch in s:
        o = ord(ch)
        if o < 128:
            out.append(ch)
        elif o < 0x10000:
            out.append("\\u%04x" % o)
        else:
            out.append("\\U%08x" % o)
    return "".join(out)


def emit(s, utf8=False):
    sys.stdout.write(s if utf8 else ascii_safe(s))
    sys.stdout.write("\n")


def warn_line(s, utf8=False):
    emit("    " + s, utf8)


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------

CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


def has_cjk(s):
    return bool(CJK_RE.search(s or ""))


def is_status_symbol(s):
    """✅ / ❌ / ⚠ 这类"状态哨兵"：短，且含 Unicode 分类 So（Symbol, other）。

    ⚠️ 只认 So，**不认** Sm/Sk/Sc/Po —— 否则 .matches("^\\d+$") 里的 $ ^ | 会被误判。
    """
    if not s or len(s) > 4:
        return False
    return any(unicodedata.category(ch) == "So" for ch in s)


def is_textual(s):
    """这个字面量算不算"文案"（会被翻译的东西）。"""
    if s is None:
        return False
    if s in TECH_ARG_EXACT:
        return False
    return has_cjk(s) or is_status_symbol(s)


def read_text(path):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


# ---------------------------------------------------------------------------
# XML 资源解析（用 expat：要行号，ET 不给）
# ---------------------------------------------------------------------------

RES_TAGS = ("string", "string-array", "plurals", "integer-array", "array", "item")

# Android 资源限定符：只有第一段是"语言"时才当语言目录。
#   与 aapt2 自己的语法一致：2~3 个小写字母 = 语言；b+… = BCP47。
#   ⚠️ 由此 `values-night`(5 字母) 会被正确排除，而理论上 `values-car` 会被当语言 ——
#      这是 Android 语法本身的歧义，不是本脚本的锅；所有识别到的语言目录都会打出来，看得见。
LANG_RE = re.compile(r"^[a-z]{2,3}$")


def classify_values_dir(name):
    """'values' -> 'default'；'values-zh-rCN' -> 'locale'；'values-night' -> 'other'；其余 -> None"""
    if name == "values":
        return "default"
    if not name.startswith("values-"):
        return None
    q0 = name[len("values-"):].split("-")[0]
    if q0.startswith("b+"):
        return "locale"
    if LANG_RE.match(q0):
        return "locale"
    return "other"


class _StringsXml:
    """把一个 strings.xml 解析成 {name: {...}}，并带上行号与重复项。"""

    def __init__(self, rel):
        self.rel = rel.replace("\\", "/")
        self.items = {}
        self.dups = []
        self._depth = 0
        self._cur = None       # 当前正在收集的具名条目
        self._cur_depth = 0
        self._buf = []
        self._item_buf = None  # 正在收集的 <item>（string-array / plurals 用）
        self._err = None
        self._line = 0

    def _start(self, tag, attrs):
        self._depth += 1
        if self._depth == 2 and tag in RES_TAGS:
            name = dict(attrs).get("name")
            if name is not None:
                self._flush()
                self._cur = {
                    "name": name,
                    "kind": tag,
                    "line": self._line,
                    "translatable": dict(attrs).get("translatable", "true") != "false",
                }
                self._cur_depth = self._depth
                self._buf = []
                return
        if self._cur is not None and tag == "item" and self._depth == self._cur_depth + 1:
            # 数组项：**单独收集**（RES-10 要按项比较，不能只看拼起来的一整段）
            self._item_buf = []
            return
        if self._cur is not None and self._depth > self._cur_depth:
            # 条目内部的标记（<b> / <xliff:g>）：当分隔符，别把两段粘起来
            self._buf.append("\n")

    def _end(self, tag):
        if self._item_buf is not None and tag == "item":
            self._cur.setdefault("items", []).append("".join(self._item_buf))
            self._item_buf = None
            self._depth -= 1
            return
        if self._cur is not None and self._depth == self._cur_depth:
            self._flush()
        self._depth -= 1

    def _chars(self, data):
        if self._item_buf is not None:
            self._item_buf.append(data)
        elif self._cur is not None:
            self._buf.append(data)

    def _flush(self):
        if self._cur is None:
            return
        value = "".join(self._buf)
        name = self._cur["name"]
        if name in self.items:
            self.dups.append((name, self._cur["line"], self.items[name]["line"]))
        else:
            self._cur["value"] = value
            self.items[name] = self._cur
        self._cur = None
        self._buf = []

    def _parser_line(self):
        return self._line


def parse_strings_xml(path, rel):
    """带行号的解析：expat 的 CurrentLineNumber 要在 handler 里取，所以单独包一层。"""
    box = _StringsXml(rel)
    p = expat.ParserCreate()

    def start(tag, attrs):
        box._line = p.CurrentLineNumber
        box._start(tag, attrs)

    def end(tag):
        box._line = p.CurrentLineNumber
        box._end(tag)

    p.StartElementHandler = start
    p.EndElementHandler = end
    p.CharacterDataHandler = box._chars
    try:
        with open(path, "rb") as f:
            p.ParseFile(f)
    except Exception as e:                                        # noqa: BLE001
        box._err = "%s: %s" % (type(e).__name__, e)
    return box


def collect_values_dirs(root):
    """-> (default_dir_name, [locale_dir_name...], [other_qualifier_dirs...], errors)"""
    res = os.path.join(root, RES_DIR)
    if not os.path.isdir(res):
        return None, [], [], []
    default, locales, others = None, [], []
    for name in sorted(os.listdir(res)):
        d = os.path.join(res, name)
        if not os.path.isdir(d):
            continue
        kind = classify_values_dir(name)
        if kind == "default":
            default = name
        elif kind == "locale":
            locales.append(name)
        elif kind == "other":
            others.append(name)
    return default, locales, others, []


def load_dir(root, dirname):
    """把一个 values*/ 目录下所有 .xml 的具名条目读进 {name: item}；item 带 rel/line。"""
    d = os.path.join(root, RES_DIR, dirname)
    out, dups, errs = {}, [], []
    if not os.path.isdir(d):
        return out, dups, errs
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".xml"):
            continue
        path = os.path.join(d, fn)
        rel = os.path.join(RES_DIR, dirname, fn)
        box = parse_strings_xml(path, rel)
        if box._err:
            errs.append((rel, box._err))
            continue
        for name, it in box.items.items():
            it["rel"] = rel
            if name in out:
                dups.append((name, rel, it["line"], out[name]["rel"], out[name]["line"]))
            else:
                out[name] = it
        for (name, line, first) in box.dups:
            dups.append((name, rel, line, rel, first))
    return out, dups, errs


# ---------------------------------------------------------------------------
# format specifier
# ---------------------------------------------------------------------------

# %[arg$][flags][width][.precision]conv
SPEC_RE = re.compile(r"%(\d+\$)?([-+ 0,(#]*)(\d+)?(?:\.(\d+))?([a-zA-Z%])")

# 裸双引号（前面没有反斜杠的那个）—— aapt2 会把成对引号当「引起来的一段」，**只取内容、不留引号**。
# 实测（2026-10-04）：`mod "%1$s"` 装到机器上显示成 `mod X`；要真的显示引号必须写 `\"`。
# ⚠️ 这条**没有任何其它环节能发现**：门禁之外的构建步骤全绿，只有人眼看界面才会发现。
BARE_QUOTE = re.compile(r'(?<!\\)"')

# 没转义的撇号（`'`，前面没有反斜杠）。Android 资源里它有两种下场，**都不好**：
#   · 出现**奇数次** ⇒ aapt2 直接报 `strings.xml: error: file failed to compile.`
#     —— **不给行号**（2026-10-05 实测：两个 `This slot's` 让我多跑了一轮构建才定位到）；
#   · 出现**偶数次** ⇒ aapt2 把它们当"引起来的一段"，**引号连同内容照收、悄无声息**。
# ⇒ 判据：值里只要出现没转义的 `'` 就报。要么写 `\'`，要么改写措辞绕开（首选后者，英文更干净）。
BARE_APOSTROPHE = re.compile(r"(?<!\\)'")


def spec_info(value):
    """-> (index_set, bare_list, conv_multiset)"""
    idx, bare, convs = set(), [], []
    for m in SPEC_RE.finditer(value or ""):
        arg, _flags, _w, _p, c = m.groups()
        if c == "%":
            continue
        if arg is None:
            bare.append(m.group(0))
        else:
            idx.add(int(arg[:-1]))
        convs.append(c)
    return idx, bare, convs


# ---------------------------------------------------------------------------
# Java 源码：去注释（保留字符串字面量）
# ---------------------------------------------------------------------------


def java_code_lines(text):
    """逐行返回"去掉注释但保留字符串"的代码。逐字符扫描，避免字符串里的 // 被误伤。"""
    out, cur = [], []
    i, n = 0, len(text)
    in_line, in_block = False, False
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "\n":
            out.append("".join(cur))
            cur = []
            in_line = False
            i += 1
            continue
        if in_line:
            i += 1
            continue
        if in_block:
            if ch == "*" and nxt == "/":
                in_block = False
                i += 2
            else:
                i += 1
            continue
        if ch == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if ch in ("\"", "'"):
            quote = ch
            cur.append(ch)
            i += 1
            while i < n:
                c2 = text[i]
                cur.append(c2)
                if c2 == "\\" and i + 1 < n:
                    cur.append(text[i + 1])
                    i += 2
                    continue
                i += 1
                if c2 == quote:
                    break
            continue
        cur.append(ch)
        i += 1
    out.append("".join(cur))
    return out


def java_files(root, include_selftest=False):
    base = os.path.join(root, SRC_DIR)
    for dirpath, _dirs, files in os.walk(base):
        for fn in sorted(files):
            if not fn.endswith(".java"):
                continue
            if not include_selftest and fn == "SelfTest.java":
                continue
            full = os.path.join(dirpath, fn)
            yield os.path.relpath(full, root), full


# ---------------------------------------------------------------------------
# 检查 A：资源层
# ---------------------------------------------------------------------------


def dot_sep_bad(value):
    """'·' 当**分隔符**用时两侧必须有空格；当**行首项目符号**时放行。

    ⚠️ 工程自己的 `SelfTest.sepsOk` 只判"两侧都是空格"，**照搬到全部 559 条资源上会误杀项目符号**：
       `restore_confirm_msg_fmt` 里是 `\\n\\n· 只覆盖备份里有的文件…\\n· 写之前先检查…` —— 那是列表符号，
       左边只能是换行。⇒ 本函数比 `sepsOk` 精确一点：**行首（串首 / 真换行 / 转义 `\\n` 之后）+ 右边是空格**
       才算项目符号。
    ⚠️ 注意：资源里的换行通常写成**两个字符** `\\` + `n`（aapt2 前不换行），所以两种都要认。
    """
    for i, ch in enumerate(value):
        if ch != "·":
            continue
        left_space = i > 0 and value[i - 1] == " "
        right_space = i + 1 < len(value) and value[i + 1] == " "
        if left_space and right_space:
            continue
        before = value[:i]
        at_line_start = (i == 0) or value[i - 1] == "\n" or before.endswith("\\n")
        if at_line_start and right_space:
            continue
        return True
    return False


def check_res(root, verbose=False):
    findings = []
    notes = []
    default_name, locales, others, _ = collect_values_dirs(root)

    if default_name is None:
        notes.append(("res", "no app/res/values -- resource checks skipped"))
        return findings, notes, None

    default, ddups, derrs = load_dir(root, default_name)
    notes.append(("res", "default  %s  ->  %d keys" % (default_name, len(default))))
    if locales:
        notes.append(("res", "locales  %s" % ", ".join(locales)))
    else:
        notes.append(("res", "locales  (none)  -- 还没有任何 values-<locale>/ 目录"))
    if others:
        notes.append(("res", "skipped  %s  (不是语言限定符)" % ", ".join(others)))
    for rel, err in derrs:
        findings.append(Finding("RES-XML", "ERROR", rel, 0, "", "XML 解析失败: " + err))

    # RES-06 默认目录内的重复
    for (name, rel, line, rel0, line0) in ddups:
        findings.append(Finding(
            "RES-06", "ERROR", rel, line, name,
            "同一个目录里 'string/%s' 重复定义（另一处在 %s:%d）" % (name, rel0, line0)))

    # RES-03 / RES-04 / RES-05 / RES-09：默认目录自己也要过
    all_dirs = [(default_name, default)] + [(d, load_dir(root, d)[0]) for d in locales]
    for dirname, table in all_dirs:
        for name, it in sorted(table.items()):
            value = it.get("value", "")
            rel, line = it["rel"], it["line"]
            is_str = it["kind"] == "string"

            # ★ 逐条文本：`<string>` 就是整个值；数组/复数条目**按 <item> 分开**判 ——
            #   数组的"值"是我们把各项用 \n 拼起来的**合成物**，里面必然带 XML 排版换行，
            #   拿它去判"前后空白"会全是误报（这是 RES-10 落地时踩到的）。
            pieces = [value] if is_str else [x for x in (it.get("items") or [])]

            if is_str and it["translatable"]:
                # RES-03 只判 <string>：**只有字符串资源会被 String.format**，
                # 数组项里的 % 一律是字面量（判它会误报）。
                _idx, bare, _convs = spec_info(value)
                for b in bare:
                    findings.append(Finding(
                        "RES-03", "ERROR", rel, line, name + "|" + b,
                        "key '%s' 用了裸 %s —— 必须写成 %%1$s 这种带位置参数的形式" % (name, b)))
            for t in pieces:
                if t and t != t.strip():
                    findings.append(Finding(
                        "RES-04", "ERROR", rel, line, name,
                        "key '%s' 的值有真的前导/尾随空白（aapt2 会剥掉，装机后一个不剩）"
                        " —— 要留白请写 \\u0020%s"
                        % (name, "" if is_str else "（出在某个 <item> 上）")))
                    break
            if any("\\u0020" in t for t in pieces) and name not in U0020_WHITELIST:
                findings.append(Finding(
                    "RES-05", "ERROR", rel, line, name,
                    "key '%s' 用了 \\u0020 但不在白名单里 —— 首选改成整句资源 + %%1$s；"
                    "确有必要再把它加进 tools/i18n-check.py 的 U0020_WHITELIST" % name))
            for t in pieces:
                if BARE_QUOTE.search(t):
                    findings.append(Finding(
                        "RES-12", "ERROR", rel, line, name,
                        "key '%s' 的值里有**裸双引号** —— aapt2 会把它**静默删掉**"
                        "（它把成对引号当成「引起来的一段」，只取内容、不留引号）⇒ 界面上根本看不到。"
                        " 要显示双引号请写成 \\\" " % name))
                    break
            for t in pieces:
                if BARE_APOSTROPHE.search(t):
                    findings.append(Finding(
                        "RES-13", "ERROR", rel, line, name,
                        "key '%s' 的值里有**没转义的撇号** —— 出现奇数次会让 aapt2 直接报"
                        " `file failed to compile.`（**不给行号**，极难定位）；偶数次更糟："
                        " 那对撇号会被当成「引起来的一段」**悄无声息**。写成 \\' 或改写措辞绕开"
                        % name))
                    break
            for t in pieces:
                if "·" in t and dot_sep_bad(t):
                    findings.append(Finding(
                        "RES-09", "ERROR", rel, line, name,
                        "key '%s' 的 '·' 当分隔符用时两侧必须都是空格（行首项目符号除外）"
                        % name))
                    break

    # RES-11：values*/ 的 XML 注释里不许出现「注释结束符」与连续两个减号
    #
    # 🔴 为什么：aapt2 **会把 values* 里的 XML 注释原样搬进生成的 R.java 当 Javadoc**
    #    （实测：R.java 里 764 个资源条目、只有 77 个带注释，正好是 strings.xml 那批；
    #      drawable/layout 的注释**不会**被搬 —— 所以只扫 values*）。
    #    注释里一旦出现那两字符，**注释提前结束**，后面整段变成 Java 代码 ⇒
    #    报出来的是**几十上百个 cascading error、而且行号指向 R.java**，极难定位。
    #    （2026-10-04 踩过：注释里写了 `values-*` 紧跟一个斜杠，javac 报了 96 个错。）
    #    「连续两个减号」同理：expat 会判 not well-formed，但**行号显示 0**（已知坑）。
    for dirname in [default_name] + locales:
        d = os.path.join(root, RES_DIR, dirname)
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".xml"):
                continue
            rel = os.path.join(RES_DIR, dirname, fn)
            raw = read_text(os.path.join(d, fn))
            for m in re.finditer(r"<!--(.*?)-->", raw, re.S):
                body = m.group(1)
                ln = raw.count("\n", 0, m.start()) + 1
                if "*/" in body:
                    findings.append(Finding(
                        "RES-11", "ERROR", rel, ln, "*/",
                        "XML 注释里出现了「星号+斜杠」—— aapt2 会把它搬进 R.java 当 Javadoc，"
                        "注释就此提前结束，后面会报一大片 cascading error 且行号指向 R.java。"
                        " 改写措辞（例如 values-xx/）即可"))
                if "--" in body:
                    findings.append(Finding(
                        "RES-11", "ERROR", rel, ln, "--",
                        "XML 注释里出现了连续两个减号 —— expat 会判 not well-formed，"
                        "而且**行号显示 0**。改写措辞即可"))

    # RES-01 / RES-02：语言目录 vs 默认目录
    for dirname in locales:
        table, dups, errs = load_dir(root, dirname)
        for rel, err in errs:
            findings.append(Finding("RES-XML", "ERROR", rel, 0, "", "XML 解析失败: " + err))
        for (name, rel, line, rel0, line0) in dups:
            findings.append(Finding(
                "RES-06", "ERROR", rel, line, name,
                "同一个目录里 'string/%s' 重复定义（另一处在 %s:%d）" % (name, rel0, line0)))
        for name, it in sorted(table.items()):
            rel, line = it["rel"], it["line"]
            if name not in default:
                findings.append(Finding(
                    "RES-01", "ERROR", rel, line, name,
                    "'%s' 只在 %s 里有、默认目录 %s 里没有 —— aapt2 会**静默移除**它"
                    "（只打一行 warn，退出码仍是 0）。默认目录必须含全部 key。"
                    % (name, dirname, default_name)))
                continue
            if it["kind"] != "string" or not it["translatable"]:
                continue
            d = default[name]
            if d["kind"] != "string" or not d["translatable"]:
                continue
            idx_d, _b, conv_d = spec_info(d.get("value", ""))
            idx_l, bare_l, conv_l = spec_info(it.get("value", ""))
            if idx_l != idx_d:
                findings.append(Finding(
                    "RES-02", "ERROR", rel, line, name,
                    "key '%s' 的参数索引集合与默认目录不一致：默认 %s，本语言 %s"
                    "（缺参数 ⇒ 运行期 MissingFormatArgumentException 直接崩）"
                    % (name, sorted(idx_d) or "{}", sorted(idx_l) or "{}")))
            elif sorted(conv_d) != sorted(conv_l):
                findings.append(Finding(
                    "RES-02W", "WARN", rel, line, name,
                    "key '%s' 的转换字母与默认目录不同：默认 %s，本语言 %s"
                    % (name, conv_d, conv_l)))

    # RES-07 / RES-00：Java 引用 vs 默认目录
    refs = {}
    for rel, full in java_files(root, include_selftest=True):
        for i, line in enumerate(java_code_lines(read_text(full)), 1):
            for m in re.finditer(r"R\.string\.(\w+)", line):
                refs.setdefault(m.group(1), []).append((rel, i))
    # ★ 清单里引用的也算"有人用"：否则只给 android:label / activity 用的串会被误报成孤儿。
    #   （`RES-08` 只管"带不带占位符"，不管"有没有人用" —— 两件事。）
    if os.path.isfile(os.path.join(root, MANIFEST)):
        for i, line in enumerate(read_text(os.path.join(root, MANIFEST)).splitlines(), 1):
            for m in re.finditer(r"@string/(\w+)", line):
                refs.setdefault(m.group(1), []).append((MANIFEST, i))
    missing = sorted(k for k in refs if k not in default)
    for k in missing:
        rel, line = refs[k][0]
        findings.append(Finding(
            "RES-07", "ERROR", rel, line, k,
            "Java 引用了 R.string.%s，但默认目录 %s 里没有这个 key"
            "（十有八九是它只写在某个语言目录里，被 aapt2 移除了）" % (k, default_name)))
    orphans = sorted(k for k in default if k not in refs)
    if orphans:
        findings.append(Finding(
            "RES-00", "INFO", os.path.join(RES_DIR, default_name, "strings.xml"), 0, "",
            "%d 个 key 定义了但没有任何 Java 引用（P1 之前过一遍）" % len(orphans)))
        if verbose:
            for k in orphans:
                it = default[k]
                findings.append(Finding("RES-00", "INFO", it["rel"], it["line"], k,
                                        "孤儿 key: %s" % k))

    # RES-10：语言选择器的名单必须与 values-<locale>/ 目录一一对应
    #
    # ★ 为什么要有这条：`R.array.app_languages`（设置页那份可选项）与 `app/res/values-*/`
    #   是**两份名单**，任何一边加了语言而另一边忘了，都是"不报错、只是少了一项/选不了"。
    #   这正是本工程反复出现的那类"名单漏了不会有症状"的坑。
    arr = default.get("app_languages")
    if arr is None or not arr.get("items"):
        if locales:
            notes.append(("res", "RES-10 跳过：默认目录里没有 R.array/app_languages（没有语言选择器）"))
    else:
        declared = set(i.strip() for i in arr["items"]
                       if i.strip() and i.strip() != "system")
        actual = set()
        for d in locales:
            tag = d[len("values-"):]
            tag = re.sub(r"-r([A-Za-z]{2})\b", r"-\1", tag)   # zh-rCN -> zh-CN
            actual.add(tag)
        # ★ 默认目录（values/）本身也是一门语言，但目录名里没有这个信息
        #   ⇒ 靠 `app_default_language` 显式声明（没有它就没法判"名单里的 en 到底有没有对应资源"）。
        dl = default.get("app_default_language")
        default_lang = (dl or {}).get("value", "").strip()
        if default_lang:
            actual.add(default_lang)
        else:
            notes.append(("res", "RES-10 反向跳过：默认目录里没有 app_default_language"
                                 "（判不了「名单里的默认语言有没有对应资源」）"))
        for tag in sorted(actual - declared):
            findings.append(Finding(
                "RES-10", "ERROR", arr["rel"], arr["line"], "app_languages|" + tag,
                "有 %s 这门语言的资源，但 R.array/app_languages 里没有 '%s'"
                " ⇒ 用户在选择器里**看不到也选不了**这门语言"
                % (("values-" + tag + "/") if tag != default_lang else "默认目录 values/", tag)))
        for tag in sorted(declared - actual):
            findings.append(Finding(
                "RES-10", "ERROR", arr["rel"], arr["line"], "app_languages|" + tag,
                "R.array/app_languages 里有 '%s'，但**没有任何资源提供它**"
                " ⇒ 选了它会静默回落默认语言（看着像「没生效」）" % tag))
        notes.append(("res", "RES-10 名单: %s  实际: %s（默认语言=%s）"
                      % (sorted(declared) or "[]", sorted(actual) or "[]",
                         default_lang or "?")))

    # RES-08：android:label 引用的串不许含占位符
    mf = os.path.join(root, MANIFEST)
    if os.path.isfile(mf):
        lines = read_text(mf).splitlines()
        for i, line in enumerate(lines, 1):
            for m in re.finditer(r'android:label\s*=\s*"@string/(\w+)"', line):
                key = m.group(1)
                it = default.get(key)
                if it is None:
                    findings.append(Finding(
                        "RES-07", "ERROR", MANIFEST, i, key,
                        "AndroidManifest.xml 引用了 @string/%s，但默认目录里没有" % key))
                    continue
                val = it.get("value", "")
                idx, bare, _c = spec_info(val)
                if idx or bare:
                    findings.append(Finding(
                        "RES-08", "ERROR", MANIFEST, i, key,
                        "android:label 引用了带占位符的 @string/%s —— 系统不会去 format 它，"
                        "最近任务里会显示字面的 %s" % (key, (sorted(idx) and "%%n$" or "%s"))))
        for i, line in enumerate(lines, 1):
            if "supportsRtl" in line:
                notes.append(("res", "manifest 有 android:supportsRtl（第 %d 行）" % i))
    else:
        notes.append(("res", "no AndroidManifest.xml -- RES-08 skipped"))

    return findings, notes, default_name


# ---------------------------------------------------------------------------
# 检查 B：源码层的"文案当判据"
# ---------------------------------------------------------------------------

CONST_RE = re.compile(r"\bstatic\s+final\s+String\s+(\w+)\s*=\s*\"((?:[^\"\\]|\\.)*)\"")
JUDGE_ARG_RE = re.compile(
    r"\.(%s)\s*\(\s*\"((?:[^\"\\]|\\.)*)\"" % "|".join(JUDGE_METHODS))


def check_src(root, include_selftest=False, verbose=False):
    findings = []
    notes = []

    files = list(java_files(root, include_selftest=include_selftest))
    if not files:
        notes.append(("src", "no java sources under %s -- SRC checks skipped" % SRC_DIR))
        return findings, notes

    # 第一遍：收集"值本身是文案"的常量
    text_consts = {}   # name -> (rel, line, value)
    for rel, full in files:
        for i, line in enumerate(java_code_lines(read_text(full)), 1):
            for m in CONST_RE.finditer(line):
                if is_textual(m.group(2)):
                    text_consts[m.group(1)] = (rel, i, m.group(2))

    if text_consts:
        notes.append(("src", "值为文案的常量: " + ", ".join(
            "%s=%s" % (k, ascii_safe(v[2])) for k, v in sorted(text_consts.items()))))

    # 第二遍：找判据
    names_alt = "|".join(re.escape(k) for k in text_consts) if text_consts else None
    cjk_hits = sym_hits = const_hits = 0

    for rel, full in files:
        for i, line in enumerate(java_code_lines(read_text(full)), 1):
            if "i18n-ok" in line:
                continue

            for m in JUDGE_ARG_RE.finditer(line):
                method, arg = m.group(1), m.group(2)
                if not is_textual(arg):
                    continue
                if has_cjk(arg):
                    cjk_hits += 1
                else:
                    sym_hits += 1
                findings.append(Finding(
                    "SRC-01", "ERROR", rel, i, arg,
                    "用文案串当判据：.%s(\"%s\") —— 这段文案一翻译，这里的行为就**静默改变**"
                    "（不报错、不崩溃）。改成枚举 / 布尔标志 / 错误码。"
                    % (method, ascii_safe(arg))))

            if names_alt:
                for m in re.finditer(
                        r"\.(%s)\s*\(\s*(?:\w+\.)?(%s)\b" % ("|".join(JUDGE_METHODS), names_alt),
                        line):
                    name = m.group(2)
                    rel0, line0, value = text_consts[name]
                    const_hits += 1
                    findings.append(Finding(
                        "SRC-01", "ERROR", rel, i, name,
                        "用「值为文案的常量」当判据：.%s(%s)，而 %s = \"%s\"（%s:%d）"
                        " —— 把它换成枚举" % (m.group(1), name, name, ascii_safe(value), rel0, line0)))
                for m in re.finditer(
                        r"\b(%s)\.(%s)\s*\(" % (names_alt, "|".join(JUDGE_METHODS)), line):
                    name = m.group(1)
                    rel0, line0, value = text_consts[name]
                    const_hits += 1
                    findings.append(Finding(
                        "SRC-01", "ERROR", rel, i, name,
                        "用「值为文案的常量」当判据：%s.%s(...)，而 %s = \"%s\"（%s:%d）"
                        " —— 把它换成枚举" % (name, m.group(2), name, ascii_safe(value), rel0, line0)))

    notes.append(("src", "SRC-01 命中: 中文 %d 处 / 状态符号 %d 处 / 文案常量 %d 处"
                  % (cjk_hits, sym_hits, const_hits)))
    if not include_selftest:
        notes.append(("src", "（SelfTest.java 按约定跳过 —— 它那些中文是自造 fixture）"))
    return findings, notes


def count_cjk_literals(root):
    """每个 Java 文件里「含中文的字符串字面量」条数（注释已经剥掉）。

    ★ 为什么用这个当 P3 的尺子：它**完全机械**（不猜"这句是给谁看的"），
      所以能被别人独立复算；而"到底该不该搬"由**台账**那一行（人的决定）来记。
    ⚠️ **不纳入预算的文件见 BUDGET_SKIP** —— 自检的中文是**断言期望值**（它必须留中文，
      而且每加一条断言都会变）⇒ 计入只会天天红，把台账变成消音器。
    """
    out = {}
    for rel, full in java_files(root, include_selftest=True):
        name = os.path.basename(rel)
        if name in BUDGET_SKIP:
            continue
        for line in java_code_lines(read_text(full)):
            for m in LITERAL_RE.finditer(line):
                if has_cjk(m.group(1)):
                    out[name] = out.get(name, 0) + 1
    return out


def parse_budget(text):
    """台账格式：每行 `文件名.java = 数字`；`#` 之后是注释。"""
    out = {}
    for line in text.splitlines():
        s = line.split("#", 1)[0].strip()
        if not s or "=" not in s:
            continue
        k, v = s.split("=", 1)
        try:
            out[k.strip()] = int(v.strip())
        except ValueError:
            continue
    return out


def check_src02(root, verbose=False):
    """SRC-02：Java 里的中文字面量**只许降**（台账 = 每个文件的上限）。"""
    findings = []
    notes = []
    path = os.path.join(root, BUDGET_FILE)
    if not os.path.isfile(path):
        notes.append(("src", "SRC-02 跳过：没有 " + BUDGET_FILE.replace("\\", "/")))
        return findings, notes
    budget = parse_budget(read_text(path))
    actual = count_cjk_literals(root)

    for name in sorted(set(list(actual.keys()) + list(budget.keys()))):
        a = actual.get(name, 0)
        b = budget.get(name)
        if b is None:
            findings.append(Finding(
                "SRC-02", "ERROR", BUDGET_FILE, 0, name,
                "%s 里有 %d 条含中文的字符串字面量，台账里**没有这一行** —— 新出现的中文要么搬进"
                " res/values(-zh)/strings.xml，要么在 %s 里记一行「%s = %d」"
                "（记了就等于声明「这条我确认该留中文」）"
                % (name, a, BUDGET_FILE.replace("\\", "/"), name, a)))
        elif a > b:
            findings.append(Finding(
                "SRC-02", "ERROR", BUDGET_FILE, 0, name,
                "%s 的中文字面量 **%d 条 > 台账的 %d 条** —— 新加的中文请搬进 "
                "res/values(-zh)/strings.xml；确实该留中文（维护者视角）才把台账改成 %d"
                % (name, a, b, a)))
        elif a < b:
            findings.append(Finding(
                "SRC-02", "ERROR", BUDGET_FILE, 0, name,
                "%s 的中文字面量已经降到 **%d 条**（台账还写着 %d）—— 把台账那一行改成 %d。"
                "★ 这一行就是 P3 的进度：它只许降"
                % (name, a, b, a)))

    notes.append(("src", "SRC-02 中文字面量预算: 台账 %d 个文件 / 实测 %d 个文件；"
                  "合计 %d（台账）vs %d（实测）"
                  % (len(budget), len(actual), sum(budget.values()), sum(actual.values()))))
    changed = [n for n in sorted(actual) if budget.get(n) != actual[n]]
    if changed and verbose:
        notes.append(("src", "SRC-02 与台账不一致的文件: " + ", ".join(changed)))
    return findings, notes


# ---------------------------------------------------------------------------
# 已知缺陷台账
# ---------------------------------------------------------------------------

# 行格式: <规则id> | <相对路径> | <指纹> | <理由> [| <计划>]
def parse_known(text):
    entries = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        if len(parts) < 4:
            entries.append(("__PARSE__", line, "", "台账这一行的字段少于 4 个"))
            continue
        rule, rel, key, why = parts[0], parts[1], parts[2], parts[3]
        plan = parts[4] if len(parts) > 4 else ""
        entries.append((rule, rel.replace("\\", "/"), key, why + ("（计划：%s）" % plan if plan else "")))
    return entries


def apply_known(findings, entries):
    """-> (matched_findings, stale_entries, malformed_entries)"""
    matched, stale, bad = [], [], []
    used = [False] * len(entries)
    for f in findings:
        if f.severity != "ERROR":
            continue
        for i, (rule, rel, key, why) in enumerate(entries):
            if rule == "__PARSE__":
                continue
            if f.rule == rule and f.rel == rel and f.key == key:
                used[i] = True
                f.severity = "KNOWN"
                f.msg = f.msg + "   [台账] " + why
                matched.append(f)
                break
    for i, (rule, rel, key, why) in enumerate(entries):
        if rule == "__PARSE__":
            bad.append(why)
        elif not used[i]:
            stale.append((rule, rel, key, why))
    return matched, stale, bad


# ---------------------------------------------------------------------------
# 元断言：拿已知坏的输入喂自己，要求判死
# ---------------------------------------------------------------------------

_BASE_STRINGS = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">App</string>
    <string name="a_fmt">%1$s \u00b7 %2$s</string>
    <string name="plain">plain</string>
</resources>
"""

_BASE_JAVA = """package io.mdt.launcher;
class A {
    int f() { return R.string.app_name + R.string.a_fmt + R.string.plain; }
}
"""

_BASE_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.mdt.launcher">
    <application android:label="@string/app_name"/>
</manifest>
"""

# 只测资源层时用的最小伙伴文件：不引用任何 R.string，免得 RES-07 的噪音混进来
_MIN_JAVA = "package io.mdt.launcher;\nclass A {}\n"
_MIN_MANIFEST = ('<?xml version="1.0" encoding="utf-8"?>\n'
                 '<manifest xmlns:android="http://schemas.android.com/apk/res/android"'
                 ' package="io.mdt.launcher"><application/></manifest>\n')


_SCRATCH_OWNED = []   # 自检自己建出来的目录，收尾时删掉
_SCRATCH_SEQ = [0]


def scratch_root(root):
    """自检要一个**可写**的临时树。

    ★ 优先工程根下的 `.tmp-i18n-selftest/`（`.gitignore` 的 `/.tmp*` 已覆盖它），
      退路才是系统临时目录。
    ★★ **绝对不要用 `tempfile.mkdtemp()`** —— 它建的目录带 **0700 保护性 DACL**，
         在本机沙箱里**无法往下建子目录**（实测 WinError 5 Access denied；
         同一个位置用默认 mode / 0755 建就正常）。本脚本一律自己 `os.makedirs`。
    """
    cands = [os.path.join(root, ".tmp-i18n-selftest"), None]
    for c in cands:
        for mode in (None, 0o755):          # 万一某个环境的 umask 太紧，再试一次 0755
            try:
                if c is None:
                    base = os.environ.get("TEMP") or os.environ.get("TMP") or "."
                    d = os.path.join(base, "i18n-selftest")
                else:
                    d = c
                if mode is None:
                    os.makedirs(d, exist_ok=True)
                else:
                    os.makedirs(d, mode=mode, exist_ok=True)
                probe = os.path.join(d, "_w", "_p")
                os.makedirs(probe, exist_ok=True)
                shutil.rmtree(os.path.join(d, "_w"), ignore_errors=True)
                _SCRATCH_OWNED.append(d)
                return d
            except Exception:                                      # noqa: BLE001
                continue
    return None


def _mk_tree(files, scratch):
    _SCRATCH_SEQ[0] += 1
    root = os.path.join(scratch, "case-%02d" % _SCRATCH_SEQ[0])
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root, exist_ok=True)                 # 默认 mode，见 scratch_root 的注释
    _SCRATCH_OWNED.append(root)
    for rel, content in files.items():
        full = os.path.join(root, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
    return root


def _base(scratch, locale_file=None, locale_name="values-en", java=None, manifest=None):
    files = {
        "app/res/values/strings.xml": _BASE_STRINGS,
        "app/src/io/mdt/launcher/A.java": java if java is not None else _BASE_JAVA,
        "app/AndroidManifest.xml": manifest if manifest is not None else _BASE_MANIFEST,
    }
    if locale_file is not None:
        files["app/res/%s/strings.xml" % locale_name] = locale_file
    return _mk_tree(files, scratch)


def _run(root, include_selftest=False):
    """★ 这里必须与 `main` 的检查清单**逐条对应** —— 漏一条的后果是：
    那条规则的元断言会全红（响亮地失败），而不是"悄悄没人测它"。加规则时两边一起加。"""
    f1, _n1, _d = check_res(root)
    f2, _n2 = check_src(root, include_selftest=include_selftest)
    f3, _n3 = check_src02(root)
    return f1 + f2 + f3


def selftest(root, verbose=False):
    """每条断言 = 「喂一个已知坏/已知好的输入，要求这唯一一条规则响或不响」。"""
    scratch = scratch_root(root)
    if scratch is None:
        emit("环境错误: 找不到可写的临时目录，元断言跑不了")
        return 2

    cases = []   # (名字, root, 期望必然出现的规则id, 期望必然不出现的规则id集合)

    # ── 阴性对照：干净树必须一条 ERROR 都没有（否则这把尺子只会乱叫） ──
    cases.append(("clean tree 必须零 ERROR", _base(scratch), None, None))

    # ── 阳性：每条规则各喂一个已知坏的输入 ──
    cases.append((
        "RES-01 语言目录独有的 key",
        _base(scratch, '<resources><string name="app_name">App</string>'
                       '<string name="only_here">X</string></resources>'),
        "RES-01", None))
    cases.append((
        "RES-02 参数索引集合不一致",
        _base(scratch, '<resources><string name="app_name">App</string>'
                       '<string name="a_fmt">%1$s</string>'
                       '<string name="plain">plain</string></resources>'),
        "RES-02", None))
    cases.append((
        "RES-03 裸 %s",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="bad">%s X</string></resources>',
            "app/src/io/mdt/launcher/A.java": _BASE_JAVA,
            "app/AndroidManifest.xml": _BASE_MANIFEST,
        }, scratch),
        "RES-03", None))
    cases.append((
        "RES-04 真的前导空白",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="bad"> X</string></resources>',
            "app/src/io/mdt/launcher/A.java": _BASE_JAVA,
            "app/AndroidManifest.xml": _BASE_MANIFEST,
        }, scratch),
        "RES-04", None))
    cases.append((
        "RES-05 \\u0020 不在白名单",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="bad">a\\u0020b</string></resources>',
            "app/src/io/mdt/launcher/A.java": _BASE_JAVA,
            "app/AndroidManifest.xml": _BASE_MANIFEST,
        }, scratch),
        "RES-05", None))
    cases.append((
        "RES-06 同目录同名重复",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="dup">a</string><string name="dup">b</string></resources>',
            "app/src/io/mdt/launcher/A.java": _BASE_JAVA,
            "app/AndroidManifest.xml": _BASE_MANIFEST,
        }, scratch),
        "RES-06", None))
    cases.append((
        "RES-07 Java 引用了不存在的 key",
        _base(scratch, java='package io.mdt.launcher;\nclass A { int f() { return R.string.nope; } }'),
        "RES-07", None))
    cases.append((
        "RES-08 android:label 带占位符",
        _base(scratch, manifest='<?xml version="1.0" encoding="utf-8"?>\n'
                                '<manifest xmlns:android="http://schemas.android.com/apk/res/android"'
                                ' package="io.mdt.launcher">\n'
                                '    <application android:label="@string/a_fmt"/>\n</manifest>\n'),
        "RES-08", None))
    cases.append((
        "RES-09 `·` 被粘住",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="bad">a\u00b7b</string></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        "RES-09", None))
    cases.append((
        "RES-12 值里有裸双引号（aapt2 会静默删掉）",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="q">slot "%1$s" here</string></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        "RES-12", None))
    cases.append((
        "RES-12 转义过的引号不许响",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="q">slot \\"%1$s\\" here</string></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        None, {"RES-12"}))
    # ── SRC-02：Java 里的中文字面量只许降（台账 = 每个文件的上限）──
    _CJKJAVA = ('package io.mdt.launcher;\nclass A {\n'
                '  String f() { return "\u4e2d\u6587\u4e00"; }\n'
                '  String g() { return "\u4e2d\u6587\u4e8c"; }\n}\n')
    cases.append((
        "RES-13 值里有没转义的撇号（aapt2 报错不给行号）",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="a">cannot reach this slot\'s folder</string></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        "RES-13", None))
    cases.append((
        "RES-13 转义过的撇号不许响",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="a">cannot reach this slot\\\'s folder</string></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        None, {"RES-13"}))
    cases.append((
        "SRC-02 中文字面量超出台账（新加了中文）",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="a">A</string></resources>',
            "app/src/io/mdt/launcher/A.java": _CJKJAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
            "tools/i18n-java-budget.txt": "A.java = 1\n",
        }, scratch),
        "SRC-02", None))
    cases.append((
        "SRC-02 台账偏大（已经搬走了却没更新台账）也要报",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="a">A</string></resources>',
            "app/src/io/mdt/launcher/A.java": _CJKJAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
            "tools/i18n-java-budget.txt": "A.java = 5\n",
        }, scratch),
        "SRC-02", None))
    cases.append((
        "SRC-02 有中文但台账里没有这一行",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="a">A</string></resources>',
            "app/src/io/mdt/launcher/A.java": _CJKJAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
            "tools/i18n-java-budget.txt": "# \u7a7a\u53f0\u8d26\n",
        }, scratch),
        "SRC-02", None))
    cases.append((
        "SRC-02 与台账完全一致时不许响（也不许被别的规则误伤）",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="a">A</string></resources>',
            "app/src/io/mdt/launcher/A.java": _CJKJAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
            "tools/i18n-java-budget.txt": "A.java = 2\n",
        }, scratch),
        None, {"SRC-02"}))
    cases.append((
        "RES-11 注释里出现「星号+斜杠」（会把 R.java 的 Javadoc 提前结束）",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><!-- 见 values-xx*/ 目录 --><string name="a">A</string></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        "RES-11", None))
    cases.append((
        "RES-11 注释正常（写成 values-xx/）时不许响",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><!-- 见 values-xx/ 目录 --><string name="a">A</string></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        None, {"RES-11"}))
    cases.append((
        "RES-10 有 values-zh/ 但语言名单里没有 zh",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources>'
                '<string name="app_default_language" translatable="false">en</string>'
                '<string-array name="app_languages">'
                '<item>system</item><item>en</item></string-array></resources>',
            "app/res/values-zh/strings.xml": '<resources></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        "RES-10", None))
    cases.append((
        "RES-10 名单里有 ru 但没有 values-ru/（反向）",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources>'
                '<string name="app_default_language" translatable="false">en</string>'
                '<string-array name="app_languages">'
                '<item>system</item><item>en</item><item>ru</item></string-array></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        "RES-10", None))
    cases.append((
        "SRC-01 用中文串当判据",
        _base(scratch, java='package io.mdt.launcher;\nclass A {\n'
                            '  boolean f(String e) { return e.contains("\u5df2\u7ecf\u6709"); }\n}\n'),
        "SRC-01", None))
    cases.append((
        "SRC-01 用状态符号当判据",
        _base(scratch, java='package io.mdt.launcher;\nclass A {\n'
                            '  boolean f(String r) { return r.startsWith("\u2705"); }\n}\n'),
        "SRC-01", None))
    cases.append((
        "SRC-01 用「值为文案的常量」当判据",
        _base(scratch, java='package io.mdt.launcher;\nclass A {\n'
                            '  static final String SRC_MOD = "\u6a21\u7ec4";\n'
                            '  boolean f(String s) { return s.startsWith(SRC_MOD); }\n}\n'),
        "SRC-01", None))

    # ── 阴性：规则不能太严（这几条**必须不响**，否则译者没法干活） ──
    cases.append((
        "参数**换序**是合法的，RES-02 不许响",
        _base(scratch, '<resources><string name="app_name">App</string>'
                       '<string name="a_fmt">%2$s \u00b7 %1$s</string>'
                       '<string name="plain">plain</string></resources>'),
        None, {"RES-02"}))
    cases.append((
        "参数**重复**是合法的，RES-02 不许响",
        _base(scratch, '<resources><string name="app_name">App</string>'
                       '<string name="a_fmt">%1$s \u00b7 %1$s \u00b7 %2$s</string>'
                       '<string name="plain">plain</string></resources>'),
        None, {"RES-02"}))
    cases.append((
        "技术性判据（路径符号 / ASCII 键名 / 正则）不许响 SRC-01",
        _base(scratch, java='package io.mdt.launcher;\nclass A {\n'
                            '  boolean f(String n, String k) {\n'
                            '    return n.startsWith(".") || n.endsWith(".msav")\n'
                            '        || k.equals("default") || n.contains("/")'
                            ' || n.matches("^\\\\d+$");\n'
                            '  }\n}\n'),
        None, {"SRC-01"}))
    cases.append((
        "注释里的中文判据不许响 SRC-01",
        _base(scratch, java='package io.mdt.launcher;\nclass A {\n'
                            '  // boolean f(String e) { return e.contains("\u5df2\u7ecf\u6709"); }\n'
                            '  boolean g(String e) { return e.isEmpty(); }\n}\n'),
        None, {"SRC-01"}))
    cases.append((
        "RES-10 名单与目录一一对应时不许响",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources>'
                '<string name="app_default_language" translatable="false">en</string>'
                '<string-array name="app_languages">'
                '<item>system</item><item>en</item><item>zh</item></string-array></resources>',
            "app/res/values-zh/strings.xml": '<resources></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        None, {"RES-10"}))
    cases.append((
        "行首项目符号 '·' 不许响 RES-09（工程自己的 sepsOk 会误杀它）",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="b">head:\\n\\n\u00b7 one\\n\u00b7 two</string>'
                '</resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        None, {"RES-09"}))
    cases.append((
        "换行后带空格的 '· ' 不许响 RES-09（那是正常的分隔符接在行尾）",
        _mk_tree({
            "app/res/values/strings.xml":
                '<resources><string name="b">a \\n\u00b7 b</string></resources>',
            "app/src/io/mdt/launcher/A.java": _MIN_JAVA,
            "app/AndroidManifest.xml": _MIN_MANIFEST,
        }, scratch),
        None, {"RES-09"}))
    cases.append((
        "台账命中后不再算 ERROR",
        _base(scratch, java='package io.mdt.launcher;\nclass A {\n'
                            '  boolean f(String e) { return e.contains("\u5df2\u7ecf\u6709"); }\n}\n'),
        "__KNOWN_OK__", None))

    fails = []
    for name, root_, expect, forbid in cases:
        try:
            fs = _run(root_)
            if expect == "__KNOWN_OK__":
                entries = [("SRC-01", "app/src/io/mdt/launcher/A.java", "\u5df2\u7ecf\u6709", "测试用")]
                matched, stale, _bad = apply_known(fs, entries)
                errs = [f for f in fs if f.severity == "ERROR"]
                if not matched or errs or stale:
                    fails.append((name, "matched=%d errs=%d stale=%d"
                                  % (len(matched), len(errs), len(stale))))
                continue
            hit = {f.rule for f in fs}
            if expect and expect not in hit:
                fails.append((name, "期望 %s，实际命中 %s" % (expect, sorted(hit) or "无")))
            if forbid and (hit & forbid):
                fails.append((name, "不该命中 %s，实际命中 %s"
                              % (sorted(forbid), sorted(hit & forbid))))
            if expect is None and forbid is None:
                errs = [f for f in fs if f.severity == "ERROR"]
                if errs:
                    fails.append((name, "干净树出现了 %d 条 ERROR: %s"
                                  % (len(errs), [(f.rule, f.where) for f in errs[:3]])))
        finally:
            shutil.rmtree(root_, ignore_errors=True)

    # ── 台账失效检测 ──
    stale_cases = [
        ("台账里已修好的条目必须报 STALE",
         [("RES-08", "app/AndroidManifest.xml", "no_such_key", "假条目")]),
        ("台账字段不足必须报错", [("__PARSE__", "x", "", "坏行")]),
    ]
    for name, entries in stale_cases:
        _m, stale, bad = apply_known([], entries)
        if not stale and not bad:
            fails.append((name, "没有报 STALE/坏行"))

    # 收尾：把本次自检建出来的临时树删掉（先删 case-*，再删 scratch 根）
    for d in reversed(_SCRATCH_OWNED):
        shutil.rmtree(d, ignore_errors=True)

    n_pos = sum(1 for _n, _r, e, _f in cases if e not in (None, "__KNOWN_OK__"))
    n_neg = sum(1 for _n, _r, _e, f in cases if f)
    n_special = len(cases) - n_pos - n_neg

    emit("== i18n-check --selftest (元断言) ==")
    emit("   用例 %d 条：阳性（要求判死）%d / 阴性（要求放过）%d / 特殊 %d / 台账 %d"
         % (len(cases), n_pos, n_neg, n_special, len(stale_cases)))
    if fails:
        for name, why in fails:
            emit("   [FAIL] " + name + "  ->  " + why)
        emit("   ==> 元断言失败：这把尺子本身坏了，先修脚本再信它的结论")
        return 3
    emit("   [ok] 全部用例通过 —— 每条规则都拿一个已知坏的输入验过会判死，")
    emit("        且换序/重复参数、技术性判据、注释里的中文都不会被误杀")
    return 0


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


def main(argv):
    args = list(argv[1:])
    utf8 = "--utf8" in args
    verbose = "--verbose" in args or "-v" in args
    do_selftest = "--selftest" in args
    include_selftest = "--include-selftest" in args

    root = None
    if "--root" in args:
        i = args.index("--root")
        if i + 1 >= len(args):
            emit("用法错误: --root 后面要跟一个路径")
            return 2
        root = os.path.abspath(args[i + 1])
        args = args[:i] + args[i + 2:]
    if root is None:
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    if do_selftest:
        return selftest(root, verbose=verbose)

    if not os.path.isdir(root):
        emit("环境错误: 工程根不存在: " + root)
        return 2

    emit("== i18n check (P0) ==")
    emit("   root = " + root.replace("\\", "/"))

    res_findings, res_notes, default_name = check_res(root, verbose=verbose)
    src_findings, src_notes = check_src(root, include_selftest=include_selftest, verbose=verbose)
    src02_findings, src02_notes = check_src02(root, verbose=verbose)
    findings = res_findings + src_findings + src02_findings

    for tag, msg in res_notes + src_notes + src02_notes:
        emit("   [%s] %s" % (tag, msg))

    # 台账
    known_path = os.path.join(root, KNOWN_FILE)
    if os.path.isfile(known_path):
        entries = parse_known(read_text(known_path))
    else:
        entries = []
        emit("   [known] 没有 %s —— 没有已知缺陷要豁免" % KNOWN_FILE.replace("\\", "/"))
    matched, stale, bad = apply_known(findings, entries)

    # 报告
    errs = [f for f in findings if f.severity == "ERROR"]
    warns = [f for f in findings if f.severity == "WARN"]
    infos = [f for f in findings if f.severity == "INFO"]
    kns = [f for f in findings if f.severity == "KNOWN"]

    emit("")
    if errs:
        emit("--- ERROR (%d) ---" % len(errs))
        for f in errs:
            emit("  %-7s %-24s %s" % (f.rule, f.where, f.msg))
    if warns and verbose:
        emit("--- WARN (%d) ---" % len(warns))
        for f in warns:
            emit("  %-7s %-24s %s" % (f.rule, f.where, f.msg))
    if infos and verbose:
        # ⚠️ INFO 只在 --verbose 下打：汇总那一条（如孤儿 key 的**条数**）是有用的，
        #   但逐条明细动辄几十行，不该出现在每次构建的输出里。
        emit("--- INFO (%d)（只在 --verbose 下打）---" % len(infos))
        for f in infos:
            emit("  %-7s %-24s %s" % (f.rule, f.where, f.msg))
    if kns:
        emit("--- KNOWN (%d) 已知缺陷，台账里豁免 ---" % len(kns))
        for f in kns:
            emit("  %-7s %-24s %s" % (f.rule, f.where, f.msg))
    if stale:
        emit("--- STALE (%d) 台账里这些条目**已经不再复现**，请删掉 ---" % len(stale))
        for rule, rel, key, why in stale:
            emit("  %-7s %-24s %s   [%s]" % (rule, rel, ascii_safe(key), why))
    if bad:
        emit("--- 台账格式错误 (%d) ---" % len(bad))
        for b in bad:
            emit("  " + b)

    emit("")
    emit("   error=%d  warn=%d  known=%d  stale=%d"
         % (len(errs), len(warns), len(kns), len(stale)))
    if errs or stale or bad:
        emit("   ==> FAIL")
        return 1
    emit("   ==> OK")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except BrokenPipeError:
        sys.exit(0)
    except SystemExit:
        raise
    except BaseException:                                          # noqa: BLE001
        # 未预期的异常：**也要 ASCII 化**再打，否则 GBK 控制台上是一屏乱码、
        # 根本看不出是脚本的 bug 还是工程的问题。
        import traceback
        sys.stdout.write(ascii_safe(
            "i18n-check 内部异常（这是脚本自己的 bug，不是工程的问题）:\n"
            + traceback.format_exc()) + "\n")
        sys.exit(2)
