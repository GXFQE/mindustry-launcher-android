package io.mdt.launcher;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文案取值的**唯一入口**（2026-10-07 收口；同日接上"用户自带翻译文件"）。
 *
 * ── 它解决什么 ───────────────────────────────────────────────────────────
 *
 * 本工程的文案有**两套来源**，而它们不能同时生效：
 *
 *   ① **系统资源**（`app/res/values` 与 `values-zh` 下的 `strings.xml`）—— 编译进 `resources.arsc`，
 *      跟系统语言走（见 {@link LocaleMode}）。**这是默认那条。**
 *   ② **用户自带的翻译文件**（`<私有目录>/lang.properties`）—— 丢一份文件进来就能改文案，
 *      不用重新发版。设计与成本见 `docs/i18n-user-bundles.md`。
 *
 * 全仓库 **932 处**调用点都走这里，所以接 ② 只需改这一个文件。
 *
 * ── 🔴 四条硬规矩（都是踩出来的，动这个文件前先读）──────────────────────
 *
 * 1. **顺序 = 用户包优先 → 英文包兜底**。
 *    ⚠️ 兜底用**英文**（`values/` 的默认资源），**不是**当前语言：
 *    否则只翻了一半的包会出现"这半页中文、那半页英文"的**混排**。
 *    （`values/` 按定义总是齐的 —— 它是唯一真源，门禁 `RES-01/07` 钉着。）
 *
 * 2. **占位符的序号与类型必须与英文包逐条相同** —— `%2$d` 收到字符串会**直接崩主进程**
 *    （第 112 轮真踩过，见 §五 第 6 条）⇒ {@link #parse} 里逐条比对，**不匹配的那一条不进包**
 *    （回落英文），原因记进 {@link Pack#rejected}。这是**装包时的门禁**，不是运行期兜底。
 *
 * 3. **键名反查要缓存**：{@link #keyOf} 是热路径（每次取值都走），所以装了
 *    **R.string 字段表**（反射一次，见 {@link #known}）⇒ 反查是纯 Map 查，不碰资源系统。
 *
 * 4. **绝不许为了取英文而碰 `Locale.setDefault()`**（见 {@link LocaleMode} 类注释的红线）——
 *    这里要英文文本时用**一次性的** {@code createConfigurationContext}（{@link #template}），
 *    只影响那一个 Context 实例。
 *
 * ── 认不出来的地方会怎样（四层，都不会崩）───────────────────────────────
 *
 * ```
 * 用户包有 key + 占位符校验通过  → 用户串（带参数时由我们 String.format，包 try/catch）
 * 用户包没有 key                → 当前语言的系统资源（用户没翻 ⇒ 跟界面语言走，合理）
 * key 不在 R.string 字段表里     → ctx.getString(resId)
 * 格式化抛异常（按理不该）        → 当前语言的系统资源
 * ```
 */
public final class Trans {

    private static final String TAG = "MDTLauncher";

    /** 用户包文件名（固定；落 {@link Paths#privateDir}，与本工程其它"用户丢进来"的东西同处） */
    public static final String FILE = "lang.properties";

    /** 占位符：`%1$s` / `%2$d` / `%s` / `%d`；捕获"位置"与"类型"。`%%` 单独处理。 */
    private static final Pattern PH = Pattern.compile("%(\\d+\\$)?([sdfxXeEgGcbo])");

    private Trans() {}

    // ── 状态 ────────────────────────────────────────────────────────────────

    private static volatile Pack sPack;              // 已装上的用户包（null = 没装）
    private static Context sTemplate;                // 英文模板 Context（兜底 + 校验）
    private static Context sChinese;                 // 中文 Context（只给导出模板的注释用）
    private static Map<String, Integer> sKnown;      // 键名 -> resId（反射 Lazy 建，一次）
    private static final Map<Integer, String> sKeyOf = new HashMap<>();   // resId -> 键名（热路径缓存）
    private static volatile long sLoadedLen = -2;    // 上次读盘时的文件长度（-2 = 还没读过）
    /**
     * 正在装包（见 {@link #maybeReload}）。
     * 🔴 必须挡重入：{@link #parse} 里要取"占位符不符"的**提示文案**，而那也是
     *    {@code Trans.get(...)} ⇒ 不挡的话会再进 {@code maybeReload}（那时 {@code sLoadedLen}
     *    还没更新）⇒ **无限递归**（真踩过：logcat 瞬间刷了几千行 "lang pack loaded"）。
     */
    private static volatile boolean sLoading;

    /** 一个已解析的用户包 */
    public static final class Pack {
        public final File file;
        /** key → 值（**只含占位符校验通过**的条目） */
        public final Map<String, String> entries = new HashMap<>();
        /** 我们有这个 key、但用户包没给（会走系统资源） */
        public final List<String> missing = new ArrayList<>();
        /** 用户包给了、但我们**没有**这个 key（打错字 / 版本对不上） */
        public final List<String> unknown = new ArrayList<>();
        /** 🔴 占位符与英文包不一致 ⇒ **拒绝使用**：key → 给用户看的原因 */
        public final Map<String, String> rejected = new HashMap<>();

        Pack(File f) { this.file = f; }

        public int used() { return entries.size(); }
        public boolean ok() { return rejected.isEmpty(); }
        public boolean empty() { return entries.isEmpty() && unknown.isEmpty() && rejected.isEmpty(); }

        /** 一行摘要（落盘报告 / 界面提示用） */
        public String brief() {
            return "used=" + entries.size() + " missing=" + missing.size()
                    + " unknown=" + unknown.size() + " rejected=" + rejected.size();
        }
    }

    // ── 取值（932 处调用点的入口）──────────────────────────────────────────

    /**
     * 取一条文案。顺序见类注释。
     *
     * ⚠️ 传 null `ctx` 时返回空串而不是崩 —— 本工程有"拿不到内容就把那块收掉"的纪律
     * （用户 2026-10-04 定案），文案取不到也不该把页面搞崩。
     */
    public static String get(Context ctx, int resId) {
        if (ctx == null) return "";
        maybeReload(ctx);
        Pack p = sPack;
        if (p != null) {
            String key = keyOf(ctx, resId);
            if (key != null) {
                String v = p.entries.get(key);
                if (v != null) return v;
            }
        }
        return ctx.getString(resId);
    }

    /**
     * 带占位符的那 435 条走这个（`%1$s` / `%2$d` …）。
     *
     * ★ 用户包命中时**由我们自己 format**（用户串要按调用方给的实参展开）。
     *   格式串已在装包时校验过（占位符序号与类型都与英文一致、也没有裸 `%`），
     *   这里仍然 `try/catch`：宁可退回系统资源，也不要因为一条译文把界面搞崩。
     */
    public static String get(Context ctx, int resId, Object... args) {
        if (ctx == null) return "";
        maybeReload(ctx);
        Pack p = sPack;
        if (p != null) {
            String key = keyOf(ctx, resId);
            if (key != null) {
                String v = p.entries.get(key);
                if (v != null) {
                    try {
                        return String.format(Locale.getDefault(), v, args);
                    } catch (Throwable t) {
                        Log.w(TAG, "lang pack format failed for '" + key + "', falling back: " + t);
                    }
                }
            }
        }
        return ctx.getString(resId, args);
    }

    /**
     * 把一个控件上的**静态**文案从布局 XML 搬到 Java（本函数只用于"布局里写死的文案"）。
     *
     * 为什么需要它：布局里的 `android:text="@string/xxx"` 由 **Android 自己解析**，
     * 我们（以及用户包）**够不到** ⇒ 那 25 处必须搬进 Java 才能跟随。
     * 用法：`Trans.bind(mTitle, R.string.log_game_title);`
     */
    public static void bind(TextView v, int resId) {
        if (v == null) return;
        v.setText(get(v.getContext(), resId));
    }

    /**
     * 从布局里取控件**并**给它绑文案（省掉"先 findViewById 再 setText"两行）。
     *
     * ⚠️ 参数收 {@link android.view.View} 而不是 TextView：布局里带文案的还有
     *   `CheckBox` / `RadioButton` / `EditText`（hint），它们在 Java 里**没有共同父类**能 setText
     *   ⇒ 这里按"是什么控件"分别处理。
     *
     * 🔴 **`EditText` 必须落到 `setHint`，绝不能 `setText`**（2026-10-07 第 122 轮踩的，
     *   当天引入、当天发现）：搜索框上挂着 `TextWatcher`，把提示语 `setText` 进去等于
     *   **立刻拿提示语当关键词搜了一遍** ⇒ 列表被筛空（模组 / 地图 / 蓝图 / 存档四页全中），
     *   而界面**不报错**：看着就是"搜索框里已经有一行字、结果一条都没有"。
     *   ⇒ 判据由自检 ㉛ 钉着（`Trans.bind` 之后 `getText()` 必须为空、`getHint()` 必须非空）。
     */
    public static void bind(android.view.View root, int viewId, int resId) {
        if (root == null) return;
        android.view.View v = root.findViewById(viewId);
        if (v instanceof android.widget.EditText) {
            ((android.widget.EditText) v).setHint(get(v.getContext(), resId));
        } else if (v instanceof TextView) {
            bind((TextView) v, resId);
        }
    }

    // ── 装包 / 卸包 / 查询 ──────────────────────────────────────────────────

    /** 用户包文件（`<私有目录>/lang.properties`）。不存在 = 没装。 */
    public static File file(Context ctx) {
        return new File(Paths.privateDir(ctx.getApplicationContext()), FILE);
    }

    /** 现在装着用户包吗 */
    public static boolean installed(Context ctx) {
        maybeReload(ctx);
        return sPack != null;
    }

    /** 当前包（可能 null）。给设置页 / 报告看状态用。 */
    public static Pack pack(Context ctx) {
        maybeReload(ctx);
        return sPack;
    }

    /** 卸掉用户包（删文件 + 清状态）。 */
    public static void uninstall(Context ctx) throws IOException {
        File f = file(ctx);
        if (f.exists() && !f.delete()) throw new IOException("cannot delete " + f);
        synchronized (Trans.class) {
            sPack = null;
            sLoadedLen = -2;
        }
    }

    /**
     * 解析 + 校验一份用户包（**不落盘、不安装**）。装包流程：先 `parse` 看报告，满意再 {@link #install}。
     *
     * ⚠️ 语义：**占位符不符的那一条**不进 {@link Pack#entries}（它走系统资源），其余照常生效
     *    ⇒ "部分可用"是允许的，但 {@link Pack#rejected} 非空时界面必须**明说**。
     */
    public static Pack parse(Context ctx, File src) throws IOException {
        Context app = ctx.getApplicationContext();
        Context en = template(app);
        Map<String, Integer> known = known(app);

        Properties props = new Properties();
        InputStream in = new FileInputStream(src);
        try {
            props.load(new InputStreamReader(in, "UTF-8"));
        } finally {
            in.close();
        }

        Pack p = new Pack(src);
        TreeSet<String> given = new TreeSet<>();
        for (String k : props.stringPropertyNames()) {
            String v = props.getProperty(k);
            if (v == null) continue;
            Integer resId = known.get(k);
            if (resId == null) resId = resOf(app, k);        // 兜底：字段表没有的（理论上不该有）
            if (resId == null) { p.unknown.add(k); continue; }
            given.add(k);

            String tmpl = en.getString(resId);               // 英文原文（模板）
            String why = placeholderMismatch(en, tmpl, v);
            if (why != null) { p.rejected.put(k, why); continue; }
            p.entries.put(k, v);
        }
        for (String k : known.keySet()) {
            if (!given.contains(k)) p.missing.add(k);
        }
        return p;
    }

    /**
     * 装上（内部用；`src` 必须已经在 {@link #file} 那个位置）。
     * 调用方通常是"先把用户选的文件复制过去、再 install"。
     */
    public static Pack install(Context ctx, File src) throws IOException {
        Pack p = parse(ctx, src);
        synchronized (Trans.class) {
            sPack = p;
            sLoadedLen = src.exists() ? src.length() : -1;
        }
        Log.i(TAG, "lang pack installed: " + p.brief());
        return p;
    }

    /** 直接读 {@link #file} 并装上（用户已经在正确位置放好了文件时用）。 */
    public static Pack installFromFile(Context ctx) throws IOException {
        return install(ctx, file(ctx));
    }

    /** 全部键名（已排序）。给"导出模板"与报告用。 */
    public static java.util.List<String> keys(Context ctx) {
        java.util.List<String> out = new ArrayList<>(known(ctx.getApplicationContext()).keySet());
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * 导出**翻译模板**：一份含全部键名 + 英文原文的 `key=value` 文本，译者拿它当底稿。
     *
     * ★ 为什么必须带英文原文（而不是空值）：译者要的是"这个名字在界面上是什么意思"，
     *   只给键名等于让他去读 `strings.xml`（那正是这个功能要省掉的事）。
     *   注释块里也附上中文原文，方便对照 —— 但**注释行不属于键值对**，装包时被 `Properties` 忽略。
     *
     * 🔴 自证：写完立刻用 {@link #parse} 读一遍，断言 `unknown` 与 `rejected` **都是 0**
     *   —— 否则我们导出的是"一份装不上/会被拒的模板"，比不导出更坏。
     *   （被 `<xliff:g>` 之类吃掉、或键名在资源表里查不到的条目会被跳过，不会进文件。）
     *
     * @return 写出的键数（不含注释行）
     */
    public static int writeTemplate(Context ctx, File dst, String note) throws IOException {
        StringBuilder sb = buildTemplate(ctx, note);
        Context app = ctx.getApplicationContext();
        FileOutputStream out = new FileOutputStream(dst);
        try {
            out.write(sb.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }

        // 🔴 自证：导出的模板必须能被自己装上（unknown / rejected 都为 0）
        Pack back = parse(app, dst);
        if (!back.unknown.isEmpty() || !back.rejected.isEmpty()) {
            throw new IOException("template self-check failed: unknown=" + back.unknown.size()
                    + " rejected=" + back.rejected.size() + " first=" + back.rejected.keySet()
                    + " unknownFirst=" + back.unknown.subList(0, Math.min(8, back.unknown.size())));
        }
        return countEntries(sb);
    }

    /** 拼模板正文（拆出来是为了**能被断言**：自检直接逐行检查它的形状，不必先落盘） */
    static StringBuilder buildTemplate(Context ctx, String note) {
        Context app = ctx.getApplicationContext();
        Context en = template(app);
        Context zh = chinese(app);
        Map<String, Integer> k = known(app);

        java.util.List<String> keys = new ArrayList<>(k.keySet());
        java.util.Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        sb.append("# MDT launcher — translation template\n");
        sb.append("#\n");
        sb.append("# ").append(oneLine(tplHint(app, R.string.lang_tpl_how))).append('\n');
        sb.append("# ").append(oneLine(tplHint(app, R.string.lang_tpl_ph_warn))).append('\n');
        sb.append("#\n");
        if (note != null && !note.isEmpty()) {
            sb.append("# ").append(oneLine(note)).append('\n');
            sb.append("#\n");
        }

        String lastPrefix = "";
        for (String key : keys) {
            Integer id = k.get(key);
            if (id == null) continue;                     // 理论上不会
            String value;
            try {
                value = en.getString(id);
            } catch (Throwable t) {
                continue;                                 // 英文资源里取不到 ⇒ 跳过（别导出装不上的行）
            }
            // 键名前缀分组（一屏一片，译者好找）
            int dot = key.indexOf('_');
            String prefix = dot > 0 ? key.substring(0, dot) : key;
            if (!prefix.equals(lastPrefix)) {
                sb.append('\n').append("# ── ").append(prefix).append(" ──\n");
                lastPrefix = prefix;
            }
            // 🔴 注释行**必须单行**：中文原文里有换行，第二行起没有 `#`
            //    ⇒ `Properties.load` 会把它当成键值对（真踩过：模板装回来多出 122 个"认不出的键"）。
            sb.append("# ").append(oneLine(zhOf(zh, id))).append('\n');
            sb.append(key).append('=').append(escape(value)).append('\n');
        }
        return sb;
    }

    /** 数一数正文里有几个"真·键值对"（非注释、非空、含 `=`） */
    static int countEntries(CharSequence body) {
        int n = 0;
        for (String line : body.toString().split("\n", -1)) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (s.indexOf('=') > 0) n++;
        }
        return n;
    }

    /** 压成单行（注释用）：换行 → 空格，制表符 → 空格 */
    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    /** 中文模板（拿不到就返回空串，不抛） */
    private static String zhOf(Context zh, int resId) {
        if (zh == null) return "-";
        try {
            return zh.getString(resId);
        } catch (Throwable t) {
            return "-";
        }
    }

    /**
     * 导出模板里用的提示行（走资源，所以它们能跟随界面语言）。
     * 单独一个方法是为了让 {@link #writeTemplate} 读起来还是"拼文本"的形状。
     */
    private static String tplHint(Context ctx, int resId) {
        return Trans.get(ctx, resId);
    }

    /** properties 值里的换行要转义 —— 否则装回来会被截断（模板自证会当场发现）。 */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "");
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 懒加载 + 变更检测：没读过、或文件长度变了（换包 / 删包），就重读一次。
     * ⚠️ 只看**长度与存在性**（不比 mtime：精度与时钟问题更容易误判）。
     */
    private static void maybeReload(Context ctx) {
        if (sLoading) return;                    // 🔴 装包过程中取值 = 用系统资源，别再进来（见 sLoading）
        Context app;
        try {
            app = ctx.getApplicationContext();
            if (app == null) return;
        } catch (Throwable t) {
            return;
        }
        long len;
        try {
            File f = new File(Paths.privateDir(app), FILE);
            len = f.exists() ? f.length() : -1L;
        } catch (Throwable t) {
            return;
        }
        if (len == sLoadedLen) return;
        synchronized (Trans.class) {
            if (len == sLoadedLen || sLoading) return;
            sLoading = true;
            try {
                File f = file(app);
                if (!f.exists() || len <= 0) {
                    sPack = null;
                } else {
                    sPack = parse(app, f);
                    Log.i(TAG, "lang pack loaded: " + sPack.brief());
                }
                sLoadedLen = len;
            } catch (Throwable t) {
                Log.w(TAG, "lang pack unreadable, ignoring: " + t);
                sPack = null;
                sLoadedLen = len;
            } finally {
                sLoading = false;
            }
        }
    }

    /** 英文模板 Context（**只改这一份 Configuration**，不碰 `Locale.setDefault`，见类注释第 4 条）。 */
    private static Context template(Context app) {
        Context t = sTemplate;
        if (t != null) return t;
        Configuration cfg = new Configuration(app.getResources().getConfiguration());
        cfg.setLocale(Locale.ENGLISH);
        t = app.createConfigurationContext(cfg);
        sTemplate = t;
        return t;
    }

    /**
     * 中文 Context（只给"导出模板"的注释做对照用）。
     * 同样是**一次性** `createConfigurationContext`，不碰 `Locale.setDefault`。
     * ⚠️ 拿不到简体中文资源（例如将来只带别的语言）时返回 null，调用方要给兜底。
     */
    private static Context chinese(Context app) {
        if (sChinese != null) return sChinese;
        try {
            Configuration cfg = new Configuration(app.getResources().getConfiguration());
            cfg.setLocale(Locale.SIMPLIFIED_CHINESE);
            sChinese = app.createConfigurationContext(cfg);
        } catch (Throwable t) {
            return null;
        }
        return sChinese;
    }

    /**
     * 键名 → resId 的全表（反射 `R.string` 的字段，**一次**）。
     *
     * ★ 为什么用反射而不是 `Resources.getIdentifier(name,…)`：后者是"按名字查资源"的
     *   **线性查找**、且已标记 `@Deprecated`（官方建议反过来用 `getResourceEntryName`）。
     *   我们要的是"全表 + 双向"，反射正好一次拿全 1076 条。
     */
    private static Map<String, Integer> known(Context ctx) {
        Map<String, Integer> m = sKnown;
        if (m != null) return m;
        synchronized (Trans.class) {
            if (sKnown != null) return sKnown;
            Map<String, Integer> out = new HashMap<>();
            try {
                for (Field f : R.string.class.getFields()) {
                    out.put(f.getName(), f.getInt(null));
                }
            } catch (Throwable t) {
                Log.w(TAG, "cannot enumerate R.string: " + t);
            }
            sKnown = out;
            return out;
        }
    }

    /** resId → 键名（带缓存；非 string 资源返回 null） */
    private static String keyOf(Context ctx, int resId) {
        String cached = sKeyOf.get(resId);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String name = null;
        Map<String, Integer> known = known(ctx);
        for (Map.Entry<String, Integer> e : known.entrySet()) {   // 只在缓存未命中时走
            if (e.getValue() == resId) { name = e.getKey(); break; }
        }
        if (name == null) {
            try {
                name = ctx.getResources().getResourceEntryName(resId);
            } catch (Throwable ignored) { }
        }
        sKeyOf.put(resId, name == null ? "" : name);
        return name;
    }

    /** 键名 → resId（只在已知表里没有时兜底用） */
    private static Integer resOf(Context ctx, String key) {
        try {
            Field f = R.string.class.getField(key);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 占位符形态比对：返回 null = 一致；否则返回**给用户看的原因**（走资源，能跟随界面语言）。
     *
     * 比什么：① 个数 ② 每个的**位置序号**（`%1$s` 的 1）③ 每个的**类型字符** ④ 有没有**裸 `%`**
     * （`%` 后面不是合法转换符 ⇒ `String.format` 会抛）。
     *
     * ⚠️ 提示文案放 `res/values` 与 `values-zh` 的 strings.xml 里而不是写在这里：门禁 `SRC-02`
     *   管着 Java 里的中文字面量（要搬进资源，或在台账里登记），而这些本来就是**给用户看的**。
     *   🔴 注意这里**不能**写 "values*" 后面紧跟斜杠 —— 那个组合会提前结束块注释（真踩过）。
     */
    static String placeholderMismatch(Context ctx, String tmpl, String user) {
        String bare = barePercent(user);
        if (bare != null) return Trans.get(ctx, R.string.lang_err_bare_percent, bare);
        List<String> a = placeholders(tmpl), b = placeholders(user);
        if (a.size() != b.size()) {
            return Trans.get(ctx, R.string.lang_err_ph_count,
                    a.size(), b.size(), a.toString(), b.toString());
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                return Trans.get(ctx, R.string.lang_err_ph_index, i + 1, a.get(i), b.get(i));
            }
        }
        return null;
    }

    /** 抽出"位置序号 + 类型"（`%1$s` ⇒ `1s`；`%d` ⇒ `?d`；`%%` 跳过） */
    private static List<String> placeholders(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        Matcher m = PH.matcher(s);
        while (m.find()) {
            String pos = m.group(1);
            out.add((pos == null || pos.isEmpty() ? "?" : pos.substring(0, pos.length() - 1)) + m.group(2));
        }
        return out;
    }

    /** 找出裸 `%`（返回它附近的片段，便于用户定位；没有则 null） */
    static String barePercent(String s) {
        if (s == null) return null;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '%') continue;
            if (i + 1 < s.length() && s.charAt(i + 1) == '%') { i++; continue; }   // %% 合法
            if (PH.matcher(s.substring(i)).lookingAt()) continue;                  // 合法占位符
            int end = Math.min(s.length(), i + 6);
            return "…" + s.substring(Math.max(0, i - 4), end) + "…";
        }
        return null;
    }
}
