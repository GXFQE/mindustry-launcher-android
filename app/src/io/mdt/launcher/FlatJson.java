package io.mdt.launcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 JSON：只认**扁平对象**里的字符串 / 数字键值。
 *
 * <p>🔴 **纯逻辑：本文件不许 import `android.*`** —— 它有两个用途：
 * 插件自述（`assets/plugin.json`，注入前按 zip 读）与更新清单（`update.json`）。
 * 两者都小、都扁平，不值得把游戏那套 Hjson 拖进来；但**必须经得起脏输入**：
 * 嵌套对象 / 数组跳过、缺引号当值结束、多写几个键无所谓 —— **一律不抛异常**
 * （返回能认出来的那部分）。
 *
 * <p>★ 之所以独立成类（而不是留在 {@code PluginMeta} 里）：`PluginMeta` 引用了
 * {@code android.*}，而更新清单要在 **PC 上直接跑**（不装设备、不开模拟器）——
 * 依赖一个带 android 的类就编不过去了。搬家时判据**一个字没改**。
 *
 * <p>★ 纯函数，`SelfTest` 直接喂字符串钉它（嵌套 / 裸值 / 没闭合 / 不是对象）。
 */
final class FlatJson {

    private FlatJson() {}

    /**
     * 把 {@code {"a":"1","b":2}} 这种扁平对象解成表；不是对象 / 全是空白 ⇒ null。
     *
     * <p>⚠️ 深度 1 上遇到容器（`{` / `[`）会把**当前键丢掉**：不清的话，嵌套结束后
     * 那个键会跟**后面第一个串**配成一对（自检当场抓到过：
     * `{"a":{"b":"c"},"entry":"d.E"}` 解出 `a=entry`、`entry` 反而丢了）。
     */
    static Map<String, String> parse(String text) {
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
                if (depth == 1) key = null;     // 见方法注释最后一段
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
}
