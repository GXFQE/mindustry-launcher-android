package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 「内容文件」—— 致谢与彩蛋的**文案与阈值**（第 300 轮）。
 *
 * <h3>为什么要有它</h3>
 * 用户 2026-10-29：「这些东西应该放到自带的配置文件中而不是硬编码到代码中（包括前面的彩蛋），
 * 要不然想加点文本还需要改代码比较折腾」。
 * ⇒ 这两类东西（致谢清单、彩蛋的段子与档位）都是**内容**，不是逻辑：改一句话、加一条致敬、
 * 加一档点击段子，都不该动 Java、也不该加资源键 + 过 i18n 门禁。
 *
 * <h3>从哪读</h3>
 * <ol>
 *   <li>{@code hub/content.json}（**外部** hub，`adb push` 就能改，改完重启生效）—— 覆盖用；</li>
 *   <li>APK 自带的 {@code assets/content.json} —— 出厂值。</li>
 * </ol>
 * 覆盖文件坏掉（解析失败）时**不静默降级成空**：打 WARNING 并**回退到自带那份**，
 * 这样"改坏了"不会把致谢弄没。
 *
 * <h3>三条纪律</h3>
 * <ul>
 *   <li><b>只收内容，不收逻辑</b>：这里没有"显示条件"、没有页面跳转，只有文案与阈值；</li>
 *   <li><b>解析失败绝不崩</b>：{@link #get(Context)} 返回 null，调用方把对应块收掉（并保证致谢
 *       至少还有自带那份兜底）；</li>
 *   <li><b>上限写死</b>（条数 / 单条字数）：覆盖文件是外来的，不能让一张卡片撑爆布局。</li>
 * </ul>
 */
final class Content {

    private static final String TAG = "MDTLauncher";

    /** 自带文件（assets 根） */
    static final String ASSET = "content.json";
    /** 覆盖文件（外部 hub 根） */
    static final String OVERRIDE = "content.json";

    /** 原始文件上限（外来的覆盖文件） */
    static final int MAX_BYTES = 256 * 1024;
    /** 致谢最多几行（含空行） */
    static final int MAX_LINES = 100;
    /** 单条文案上限（字符） */
    static final int MAX_TEXT = 500;
    /** 彩蛋档位上限 */
    static final int MAX_MILESTONES = 50;

    // ── 模型 ───────────────────────────────────────────────────────────────

    /** 一条双语文案。**某种语言空了就退回另一种**（而不是显示空行）。 */
    static final class Text {
        final String zh;
        final String en;

        Text(String zh, String en) {
            this.zh = zh == null ? "" : zh;
            this.en = en == null ? "" : en;
        }

        boolean isEmpty() {
            return zh.isEmpty() && en.isEmpty();
        }

        /** 按语言挑一条：`zh*` 走中文，其余走英文；缺哪边补哪边。 */
        String pick(String lang) {
            boolean wantZh = lang != null && lang.startsWith("zh");
            String first = wantZh ? zh : en;
            if (!first.isEmpty()) return first;
            return wantZh ? en : zh;
        }
    }

    /** 一档点击段子。 */
    static final class Milestone {
        final int at;
        final Text text;
        /** "" = 不带参数；"count" = 传点击次数；"date" = 传安装日期 */
        final String arg;

        Milestone(int at, Text text, String arg) {
            this.at = at;
            this.text = text;
            this.arg = arg;
        }
    }

    /** 彩蛋的全部内容（阈值 + 文案）。 */
    static final class Eggs {
        final int unlockAt;
        final String searchToken;
        final Text title;
        final Text tapsLeft;
        final Text unlocked;
        final Text already;
        final Text tapsLine;
        final Text searchToast;
        final Milestone[] milestones;
        final Text listTitle;
        final Text listUnlock;
        final Text listMilestones;
        final Text listLongPress;
        final Text listSearch;
        final String listAts;

        Eggs(int unlockAt, String searchToken, Text title, Text tapsLeft, Text unlocked, Text already,
             Text tapsLine, Text searchToast, Milestone[] milestones, Text listTitle, Text listUnlock,
             Text listMilestones, Text listLongPress, Text listSearch, String listAts) {
            this.unlockAt = unlockAt;
            this.searchToken = searchToken;
            this.title = title;
            this.tapsLeft = tapsLeft;
            this.unlocked = unlocked;
            this.already = already;
            this.tapsLine = tapsLine;
            this.searchToast = searchToast;
            this.milestones = milestones;
            this.listTitle = listTitle;
            this.listUnlock = listUnlock;
            this.listMilestones = listMilestones;
            this.listLongPress = listLongPress;
            this.listSearch = listSearch;
            this.listAts = listAts;
        }

        /** 恰好 {@code taps} 下命中的那一档（没有 ⇒ null）。 */
        Milestone at(int taps) {
            for (Milestone m : milestones) {
                if (m.at == taps) return m;
            }
            return null;
        }
    }

    // ── 单例 ───────────────────────────────────────────────────────────────

    private static Content sInstance;
    private static boolean sLoaded;

    final Text creditsTitle;
    final String[] creditsZh;
    final String[] creditsEn;
    final Eggs eggs;
    /** 这份内容是从哪读来的（报告 / 自检里用） */
    final String source;

    private Content(Text creditsTitle, String[] creditsZh, String[] creditsEn, Eggs eggs, String source) {
        this.creditsTitle = creditsTitle;
        this.creditsZh = creditsZh;
        this.creditsEn = creditsEn;
        this.eggs = eggs;
        this.source = source;
    }

    /** 进程内只读一次（文件改了要重启才生效 —— 与 config.json 一致）。解析失败 ⇒ null。 */
    static Content get(Context c) {
        if (sLoaded) return sInstance;
        synchronized (Content.class) {
            if (sLoaded) return sInstance;
            sLoaded = true;
            sInstance = load(c);
            return sInstance;
        }
    }

    /** 自检用：清掉缓存（改完覆盖文件可以重新读）。 */
    static void reset() {
        synchronized (Content.class) {
            sLoaded = false;
            sInstance = null;
        }
    }

    // ── 读文件 ─────────────────────────────────────────────────────────────

    private static Content load(Context c) {
        if (c == null) return null;
        Config.get().load(c);

        String assetText = null;
        try {
            assetText = read(c.getAssets().open(ASSET));
        } catch (Throwable t) {
            Log.w(TAG, "read assets " + ASSET + " failed: " + t);
        }
        String assetHash = assetText == null ? "" : sha256(assetText);

        File hubFile = hubFile(c);
        String raw = null;
        String source = null;
        if (hubFile != null) {
            try {
                if (hubFile.isFile()) {
                    String text = read(hubFile);
                    String hash = sha256(text);
                    // ① 出厂内容升级：hub 那份还是上次**原样拷贝**（没人改过）⇒ 换成新出厂值。
                    //    改过的（hash != 记录的指纹）一个字都不动 —— 用户的编辑优先。
                    if (assetText != null && isStaleCopy(Config.get().contentHash(), hash, assetHash)) {
                        if (write(hubFile, assetText)) {
                            text = assetText;
                            Log.i(TAG, "content.json refreshed to the shipped defaults");
                        }
                    }
                    raw = text;
                    source = hubFile.getAbsolutePath();
                } else if (assetText != null) {
                    // ② 第一次运行：把出厂内容**落到 hub**（用户 2026-10-29：「内置也打包一个 json」）——
                    //    之后直接改这个文件就行，不用 adb push 自己准备一份，也不用重新打包。
                    if (write(hubFile, assetText)) {
                        Log.i(TAG, "content.json seeded at " + hubFile.getAbsolutePath());
                    }
                    raw = assetText;
                    source = "assets/" + ASSET;
                }
            } catch (Throwable t) {
                Log.w(TAG, "handle hub " + OVERRIDE + " failed: " + t);
            }
        }
        if (raw == null) {
            if (assetText == null) return null;
            raw = assetText;
            source = "assets/" + ASSET;
        }

        Content out;
        try {
            out = parse(raw, source);
        } catch (Throwable t) {
            Log.w(TAG, "content.json invalid (" + source + "): " + t);
            // ★ 覆盖文件坏掉 ⇒ 回退自带那份（"改坏了"不该把致谢弄没）
            if (source != null && !source.startsWith("assets/") && assetText != null) {
                try {
                    out = parse(assetText, "assets/" + ASSET);
                } catch (Throwable t2) {
                    Log.w(TAG, "fallback assets " + ASSET + " also failed: " + t2);
                    return null;
                }
            } else {
                return null;
            }
        }
        if (assetText != null && !assetHash.equals(Config.get().contentHash())) {
            Config.get().setContentHash(assetHash);
        }
        return out;
    }

    /** hub 里的 `content.json`（目录不存在就建；拿不到目录 ⇒ null）。 */
    private static File hubFile(Context c) {
        try {
            File dir = Data.hubDir(c);
            if (dir == null) return null;
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            return new File(dir, OVERRIDE);
        } catch (Throwable t) {
            Log.w(TAG, "resolve hub dir failed: " + t);
            return null;
        }
    }

    /**
     * hub 里那份是不是"上次原样拷贝的出厂内容"（纯函数，自检喂三种情形）。
     *
     * @param known     config 里记的出厂指纹（空 = 没记过 ⇒ 一律不碰用户的文件）
     * @param hubHash   hub 那份的指纹
     * @param assetHash 这次出厂内容的指纹
     */
    static boolean isStaleCopy(String known, String hubHash, String assetHash) {
        return known != null && known.length() > 0 && known.equals(hubHash)
                && assetHash != null && assetHash.length() > 0 && !assetHash.equals(hubHash);
    }

    /** 内容指纹（SHA-256 十六进制；算不出来 ⇒ 空串，调用方按"没记过"处理）。 */
    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 写文件（UTF-8）；失败只打日志、返回 false —— 内容文件写不进去不该影响打开页面。 */
    private static boolean write(File f, String text) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "write " + f + " failed: " + t);
            return false;
        }
    }

    private static String read(File f) throws Exception {
        if (f.length() > MAX_BYTES) throw new IllegalArgumentException("too large: " + f.length());
        FileInputStream in = new FileInputStream(f);
        try {
            return read(in);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IllegalArgumentException("too large");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ── 解析（纯函数；自检直接喂字符串）────────────────────────────────────

    static Content parse(String raw, String source) throws Exception {
        JSONObject root = new JSONObject(raw);
        int version = root.getInt("version");
        if (version != 1) throw new IllegalArgumentException("unsupported version " + version);

        JSONObject credits = root.getJSONObject("credits");
        Text creditsTitle = text(credits.getJSONObject("title"), "credits.title");
        JSONObject lines = credits.getJSONObject("lines");
        String[] zh = lines(lines.getJSONArray("zh"), "credits.lines.zh");
        String[] en = lines(lines.getJSONArray("en"), "credits.lines.en");
        if (zh.length == 0 && en.length == 0) throw new IllegalArgumentException("credits.lines empty");

        JSONObject e = root.getJSONObject("eggs");
        int unlockAt = e.getInt("unlockAt");
        if (unlockAt < 1) throw new IllegalArgumentException("eggs.unlockAt < 1");
        String token = e.getString("searchToken").trim();
        if (token.isEmpty() || token.length() > 32) {
            throw new IllegalArgumentException("eggs.searchToken bad: '" + token + "'");
        }
        Text title = text(e.getJSONObject("title"), "eggs.title");
        Text tapsLeft = formatText(e.getJSONObject("tapsLeft"), "eggs.tapsLeft", "%1$d");
        Text unlocked = text(e.getJSONObject("unlocked"), "eggs.unlocked");
        Text already = text(e.getJSONObject("already"), "eggs.already");
        Text tapsLine = formatText(e.getJSONObject("tapsLine"), "eggs.tapsLine", "%1$d");
        Text searchToast = formatText(e.getJSONObject("searchToast"), "eggs.searchToast", "%1$s");

        JSONArray marr = e.getJSONArray("milestones");
        if (marr.length() > MAX_MILESTONES) {
            throw new IllegalArgumentException("too many milestones: " + marr.length());
        }
        List<Milestone> ms = new ArrayList<>();
        for (int i = 0; i < marr.length(); i++) {
            JSONObject m = marr.getJSONObject(i);
            int at = m.getInt("at");
            if (at <= unlockAt) {
                throw new IllegalArgumentException("milestone at " + at + " <= unlockAt " + unlockAt);
            }
            String arg = m.optString("arg", "");
            if (!"".equals(arg) && !"count".equals(arg) && !"date".equals(arg)) {
                throw new IllegalArgumentException("milestone " + at + " bad arg: " + arg);
            }
            Text t = "count".equals(arg)
                    ? formatText(m.getJSONObject("text"), "milestone " + at, "%1$d")
                    : "date".equals(arg)
                            ? formatText(m.getJSONObject("text"), "milestone " + at, "%1$s")
                            : text(m.getJSONObject("text"), "milestone " + at);
            ms.add(new Milestone(at, t, arg));
        }
        Collections.sort(ms, new Comparator<Milestone>() {
            @Override public int compare(Milestone a, Milestone b) {
                return Integer.compare(a.at, b.at);
            }
        });
        for (int i = 1; i < ms.size(); i++) {
            if (ms.get(i).at == ms.get(i - 1).at) {
                throw new IllegalArgumentException("duplicate milestone at " + ms.get(i).at);
            }
        }
        StringBuilder ats = new StringBuilder();
        for (Milestone m : ms) {
            if (ats.length() > 0) ats.append(" / ");
            ats.append(m.at);
        }

        JSONObject list = e.getJSONObject("list");
        Text listTitle = text(list.getJSONObject("title"), "eggs.list.title");
        Text listUnlock = formatText(list.getJSONObject("unlock"), "eggs.list.unlock", "%1$d");
        Text listMilestones = formatText(list.getJSONObject("milestones"), "eggs.list.milestones", "%1$s");
        Text listLongPress = text(list.getJSONObject("longPress"), "eggs.list.longPress");
        Text listSearch = formatText(list.getJSONObject("search"), "eggs.list.search", "%1$s");

        Eggs eggs = new Eggs(unlockAt, token, title, tapsLeft, unlocked, already, tapsLine, searchToast,
                ms.toArray(new Milestone[0]), listTitle, listUnlock, listMilestones, listLongPress,
                listSearch, ats.toString());
        return new Content(creditsTitle, zh, en, eggs, source);
    }

    /** 读一条 {zh,en}，要求至少一边非空；两边都空 ⇒ 抛（配置写坏了要当场知道）。 */
    private static Text text(JSONObject o, String where) throws Exception {
        String zh = o.optString("zh", "");
        String en = o.optString("en", "");
        check(zh, where + ".zh");
        check(en, where + ".en");
        if (zh.isEmpty() && en.isEmpty()) throw new IllegalArgumentException(where + " is empty");
        return new Text(zh, en);
    }

    /** 读一条带占位符的 {zh,en}：两边**都必须**含同一个参数序号，否则运行期 format 会炸。 */
    private static Text formatText(JSONObject o, String where, String arg) throws Exception {
        Text t = text(o, where);
        if (!t.zh.isEmpty() && !t.zh.contains(arg)) {
            throw new IllegalArgumentException(where + ".zh must contain " + arg);
        }
        if (!t.en.isEmpty() && !t.en.contains(arg)) {
            throw new IllegalArgumentException(where + ".en must contain " + arg);
        }
        return t;
    }

    private static String[] lines(JSONArray a, String where) throws Exception {
        if (a.length() > MAX_LINES) throw new IllegalArgumentException(where + " too long: " + a.length());
        String[] out = new String[a.length()];
        for (int i = 0; i < a.length(); i++) {
            String s = a.getString(i);
            check(s, where + "[" + i + "]");
            out[i] = s;
        }
        return out;
    }

    private static void check(String s, String where) {
        if (s != null && s.length() > MAX_TEXT) {
            throw new IllegalArgumentException(where + " too long (" + s.length() + " chars)");
        }
    }

    // ── 取用 ───────────────────────────────────────────────────────────────

    /** 当前语言（只看 Resources 的 Configuration —— 它就是应用内切语言后的那一份）。 */
    static String lang(Context c) {
        try {
            android.content.res.Configuration cfg = c.getResources().getConfiguration();
            android.os.LocaleList ls = cfg.getLocales();
            if (ls != null && ls.size() > 0) return ls.get(0).getLanguage();
        } catch (Throwable ignored) {
        }
        return "en";
    }

    /** 按当前语言取一条文案（取不到 ⇒ 空串，不崩）。 */
    String text(Context c, Text t) {
        return t == null ? "" : t.pick(lang(c));
    }

    /** 按当前语言取一条带参数的文案；**format 失败退回原文**（别为一条文案崩页面）。 */
    String format(Context c, Text t, Object arg) {
        String s = text(c, t);
        if (s.isEmpty()) return "";
        try {
            return String.format(Locale.getDefault(), s, arg);
        } catch (Throwable e) {
            Log.w(TAG, "content format failed: " + s);
            return s;
        }
    }

    /** 致谢标题（"致谢" / "Credits"）。 */
    String creditsTitle(Context c) {
        return text(c, creditsTitle);
    }

    /** 致谢正文（多行，含作者留的空行）；没有 ⇒ 空串。 */
    String credits(Context c) {
        String[] a = lang(c).startsWith("zh") ? creditsZh : creditsEn;
        if (a == null || a.length == 0) a = lang(c).startsWith("zh") ? creditsEn : creditsZh;
        if (a == null || a.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    /** 彩蛋清单正文（四段，空行分隔）。 */
    String eggListBody(Context c) {
        if (eggs == null) return "";
        return format(c, eggs.listUnlock, Integer.valueOf(eggs.unlockAt))
                + "\n\n" + format(c, eggs.listMilestones, eggs.listAts)
                + "\n\n" + text(c, eggs.listLongPress)
                + "\n\n" + format(c, eggs.listSearch, eggs.searchToken);
    }
}
