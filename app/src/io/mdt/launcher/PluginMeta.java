package io.mdt.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.io.File;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 插件的**自述元数据**（第 135 轮）：插件自己说"我是谁、我的入口类叫什么"。
 *
 * <h3>为什么必须有"入口类名"这一项</h3>
 * 第 134 轮真机定案：所有插件原先共用同一个入口 FQN（{@link PluginHost#DEFAULT_ENTRY}），
 * 而宿主 CL **对每个类名只定义一次** —— 于是装两个插件时，入口类归**第一个注入的那个**，
 * 后面那些插件点「打开」渲染的都是它的页。⇒ 入口类名必须**一插件一份**。
 *
 * <h3>取值顺序（越靠前越优先）</h3>
 * <ol>
 *   <li>{@code assets/plugin.json} 里的 {@code entry}（推荐：插件自带、不用改清单）；</li>
 *   <li>清单 {@code <meta-data android:name="io.mdt.plugin.entry">}（不想加 assets 时的退路）；</li>
 *   <li>都没有 ⇒ {@link PluginHost#DEFAULT_ENTRY}（**老插件 / S0 夹具照旧可用**）。</li>
 * </ol>
 * ★ 同样的顺序也适用于 {@code name} / {@code version} / {@code author} / {@code description}；
 * 全都没有 ⇒ 界面继续按老样子显示（id + 时间戳版本号），不编造。
 *
 * <p>⚠️ 读它**不许**用 {@code AssetManager}：那是"挂上插件资源之后"的事，而这里要在
 * **注入之前**就知道入口类名（自查和「打开」都靠它）。⇒ 直接按 zip 读那个小文件。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
final class PluginMeta {

    /** `assets/plugin.json` 里认这几个键；值都是字符串（数字也会被当字符串收下来）。 */
    static final String K_ENTRY = "entry", K_NAME = "name", K_VERSION = "version",
            K_AUTHOR = "author", K_DESC = "description";

    /** 清单里认这些 meta-data 名（与 json 的键一一对应）。 */
    static final String M_ENTRY = "io.mdt.plugin.entry", M_NAME = "io.mdt.plugin.name",
            M_VERSION = "io.mdt.plugin.version", M_AUTHOR = "io.mdt.plugin.author",
            M_DESC = "io.mdt.plugin.description";

    private PluginMeta() {}

    /** 一个插件的自述（缺的项为 null）。 */
    static final class Info {
        final String entry, name, version, author, description;

        Info(String entry, String name, String version, String author, String description) {
            this.entry = entry;
            this.name = name;
            this.version = version;
            this.author = author;
            this.description = description;
        }

        /** 入口类名（缺自述 ⇒ 默认入口；**永远不为空** —— 调用方不必再兜底）。 */
        String entryOr(String fallback) {
            return (entry == null || entry.isEmpty()) ? fallback : entry;
        }
    }

    /** 读一个 APK 的自述（**不装包、不挂资源**；坏包/没有自述 ⇒ 各项为 null）。 */
    static Info read(Context ctx, File apk) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        // ① assets/plugin.json（后读的覆盖先读的 ⇒ 放在最后读优先级最高的那一步）
        try {
            Map<String, String> j = flatJson(zipText(apk, "assets/plugin.json"));
            if (j != null) m.putAll(j);
        } catch (Throwable ignored) {
        }
        // ② 清单 meta-data（**它的优先级更高**）
        try {
            Bundle b = metaData(ctx, apk);
            if (b != null) {
                put(m, K_ENTRY, b.getString(M_ENTRY));
                put(m, K_NAME, b.getString(M_NAME));
                put(m, K_VERSION, b.getString(M_VERSION));
                put(m, K_AUTHOR, b.getString(M_AUTHOR));
                put(m, K_DESC, b.getString(M_DESC));
            }
        } catch (Throwable ignored) {
        }
        return new Info(trim(m.get(K_ENTRY)), trim(m.get(K_NAME)), trim(m.get(K_VERSION)),
                trim(m.get(K_AUTHOR)), trim(m.get(K_DESC)));
    }

    /** 只要入口类名（注入路径上最常用的那一个）。 */
    static String entryOf(Context ctx, File apk, String fallback) {
        try {
            return read(ctx, apk).entryOr(fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ---- 读 zip 里的一个小文件 -------------------------------------------------

    private static String zipText(File apk, String name) {
        if (apk == null || !apk.isFile()) return null;
        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            ZipEntry e = z.getEntry(name);
            if (e == null) return null;
            InputStream in = z.getInputStream(e);
            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (z != null) z.close(); } catch (Throwable ignored) { }
        }
    }

    private static Bundle metaData(Context ctx, File apk) {
        int flags = PackageManager.GET_META_DATA;
        PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        ApplicationInfo ai = pi == null ? null : pi.applicationInfo;
        return ai == null ? null : ai.metaData;
    }

    private static void put(Map<String, String> m, String key, String v) {
        String t = trim(v);
        if (t != null) m.put(key, t);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ---- 极简 JSON：只认**扁平对象**里的字符串/数字键值 -------------------------
    //
    // 插件的自述就这么大，不值得把游戏那套 Hjson 拖进来；但要**经得起脏输入**：
    // 嵌套对象/数组跳过、缺引号当值结束、多写几个键无所谓 —— 一律不抛异常（返回能认出来的那部分）。
    // ★ 纯函数，SelfTest 直接喂字符串钉它。

    /** 把 {@code {"a":"1","b":2}} 这种扁平对象解成表；不是对象 / 全是空白 ⇒ null。 */
    static Map<String, String> flatJson(String text) {
        if (text == null) return null;
        Map<String, String> m = new LinkedHashMap<String, String>();
        int i = 0, n = text.length();
        while (i < n && text.charAt(i) != '{') i++;
        if (i >= n) return null;               // 连对象都不是
        i++;                                    // 跳过 '{'
        int depth = 1;
        String key = null;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                int[] end = new int[1];
                String s = readString(text, i, end);
                if (s == null) break;           // 引号没闭合 ⇒ 到此为止
                i = end[0];
                if (depth == 1 && key == null) {
                    key = s;                    // 深度 1 的第一个串 = 键
                } else if (depth == 1) {
                    m.put(key, s);              // 深度 1 的第二个串 = 值
                    key = null;
                }
                continue;
            }
            if (c == '{' || c == '[') {
                // ★ 深度 1 上遇到容器 = "这个键的值是嵌套的" ⇒ 把键**丢掉**（嵌套里的东西一律不认）。
                //   不清 key 的话，嵌套结束后那个键会跟**后面第一个串**配成一对（自检当场抓到：
                //   `{"a":{"b":"c"},"entry":"d.E"}` 解出 `a=entry`、entry 反而丢了）。
                if (depth == 1) key = null;
                depth++;
                i++;
                continue;
            }
            if (c == '}' || c == ']') {
                depth--;
                if (depth <= 0) break;
                i++;
                continue;
            }
            if (c == ':' || c == ',') { i++; continue; }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { i++; continue; }
            // 裸值（数字 / true / false / null）—— 收到 ',' '}' ']' 为止
            int j = i;
            while (j < n && ",}]".indexOf(text.charAt(j)) < 0) j++;
            if (depth == 1 && key != null) { m.put(key, text.substring(i, j).trim()); key = null; }
            i = j;
        }
        return m;
    }

    /** 从 {@code text[from] == '"'} 起读一个 JSON 串（返回内容；{@code end[0]} = 下一个下标）。 */
    private static String readString(String text, int from, int[] end) {
        StringBuilder sb = new StringBuilder();
        int i = from + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char e = text.charAt(i + 1);
                sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                i += 2;
                continue;
            }
            if (c == '"') { end[0] = i + 1; return sb.toString(); }
            sb.append(c);
            i++;
        }
        return null;                            // 没闭合
    }

    /** 元数据里的 API 版本要求（可选）：写了就按它跟宿主比（缺 ⇒ null = 用类里的 {@code api()}）。 */
    static int apiFrom(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 设备/版本信息只用来写报告（哪台机器上读的）。 */
    static String env() {
        return "sdk=" + Build.VERSION.SDK_INT;
    }
}
