package io.mdt.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件更新检查的**结果表**（`app_hub/plugins/update-state.txt`）—— 纯逻辑、不 import `android.*`。
 *
 * <p>每行一条：`id \t 状态 \t 远端哈希 \t 显示的版本 \t 时刻(epoch 秒) \t 备注`。
 * 空行 / `#` 注释行跳过；段数不对、状态不认识的整行丢掉（宁可少一条记录，也不能拿半条去动作）。
 *
 * <p>五个状态（**界面只说人话，代号不上屏**）：
 * <ul>
 *   <li>{@link #OK} —— 查过了，本机就是最新的；</li>
 *   <li>{@link #AVAILABLE} —— 有新版，但自动更新关着（等用户点）；</li>
 *   <li>{@link #INSTALLED} —— 已经装成"待生效"（退出重开才加载）；</li>
 *   <li>{@link #CONFIRM} —— 包已经下好、哈希也对，但**签名和当前那一份不同** ⇒ 要用户确认；</li>
 *   <li>{@link #FAILED} —— 这次没查成 / 没下成（原因进备注，界面有它自己的说法）。</li>
 * </ul>
 *
 * <p>★ 为什么状态要落盘而不是只放内存：自动检查发生在**启动时**，而用户打开插件页是之后的事；
 * 不落盘就只能现场再联网查一次（于是"上次查过"这件事讲不出来）。
 *
 * <p>★ 本文件是纯函数，`SelfTest` / `PluginUpdateCheck` 直接喂字符串钉它。
 */
final class PluginUpdateState {

    static final String OK = "ok";
    static final String AVAILABLE = "available";
    static final String INSTALLED = "installed";
    static final String CONFIRM = "confirm";
    static final String FAILED = "failed";

    /** 备注（原始诊断）保留长度 —— 只给日志与信息页看，别让它把一行撑爆。 */
    static final int NOTE_MAX = 120;

    /** 一条记录（字段都可为 null，除 id）。 */
    static final class Item {
        final String id, state, sha, ver, note;
        final long at;

        Item(String id, String state, String sha, String ver, long at, String note) {
            this.id = id;
            this.state = state;
            this.sha = sha;
            this.ver = ver;
            this.at = at;
            this.note = note;
        }
    }

    private PluginUpdateState() {}

    static boolean valid(String state) {
        return OK.equals(state) || AVAILABLE.equals(state) || INSTALLED.equals(state)
                || CONFIRM.equals(state) || FAILED.equals(state);
    }

    /** 这个状态是不是"用户还得做点什么"（列表上要显示一行、信息页要说一句）。 */
    static boolean pending(String state) {
        return AVAILABLE.equals(state) || CONFIRM.equals(state);
    }

    /** 取某个 id 的记录；没有 / 脏行 ⇒ null。 */
    static Item get(String text, String id) {
        if (text == null || id == null || id.isEmpty()) return null;
        for (String line : lines(text)) {
            Item it = parseLine(line);
            if (it != null && it.id.equals(id)) return it;
        }
        return null;
    }

    /** 全部记录（文件顺序、同一 id 只留第一行）。 */
    static List<Item> all(String text) {
        List<Item> out = new ArrayList<Item>();
        if (text == null) return out;
        for (String line : lines(text)) {
            Item it = parseLine(line);
            if (it == null) continue;
            boolean dup = false;
            for (Item o : out) if (o.id.equals(it.id)) { dup = true; break; }
            if (!dup) out.add(it);
        }
        return out;
    }

    /** 写一条（同 id 只留一行、保序；其余行原样保留）。 */
    static String put(String text, String id, String state, String sha, String ver, long at,
                      String note) {
        if (id == null || id.isEmpty() || !valid(state)) return text == null ? "" : text;
        String line = join(id, state, sha, ver, at, note);
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        for (String l : lines(text == null ? "" : text)) {
            Item it = parseLine(l);
            if (it != null && it.id.equals(id)) {
                if (!replaced) { if (sb.length() > 0) sb.append('\n'); sb.append(line); replaced = true; }
                continue;
            }
            if (l.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        if (!replaced) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.length() == 0 ? "" : sb.append('\n').toString();
    }

    /** 删一条（不存在 ⇒ 内容等价）。 */
    static String remove(String text, String id) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String l : lines(text)) {
            Item it = parseLine(l);
            if (it != null && it.id.equals(id)) continue;
            if (l.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.length() == 0 ? "" : sb.append('\n').toString();
    }

    /**
     * 一行 → {@link Item}。**严格要求 6 段**（备注可为空），否则 null。
     * 备注里绝不允许出现制表符 / 换行 —— 那会让下一条记录错位。
     */
    static Item parseLine(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("#")) return null;
        // 🔴 不能拿 trim 后的串去 split：备注为空时这一行**以制表符结尾**，trim 会把它吃掉
        //    ⇒ 段数少一段 ⇒ 整行被当成脏数据（2026-10-30 自检当场抓到）。
        String[] p = line.split("\t", -1);
        if (p.length != 6) return null;
        String id = p[0].trim();
        String state = p[1].trim();
        if (id.isEmpty() || !valid(state)) return null;
        long at = 0;
        try {
            at = Long.parseLong(p[4].trim());
        } catch (Throwable ignored) {
        }
        return new Item(id, state, emptyToNull(p[2]), emptyToNull(p[3]), at, emptyToNull(p[5]));
    }

    /** 把自由文本压成能安全放进一行的备注（控制字符 → 空格、制表符 → 空格、截断）。 */
    static String cleanNote(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length() && sb.length() < NOTE_MAX; i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') { sb.append(' '); continue; }
            if (c < 0x20 || c == 0x7f) continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String join(String id, String state, String sha, String ver, long at, String note) {
        return id + "\t" + state + "\t" + nz(sha) + "\t" + nz(ver) + "\t" + at + "\t"
                + cleanNote(note);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String[] lines(String text) {
        return text == null ? new String[0] : text.split("\n", -1);
    }
}
