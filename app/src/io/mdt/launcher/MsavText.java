package io.mdt.launcher;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * `.msav` 元数据的**显示文案**（P3 第一片，2026-10-04）。
 *
 * ── 为什么要有这个类 ─────────────────────────────────────────────────────
 *
 * 这些行原来是 {@link MsavMeta} 里**用 Java 拼出来的中文**（`玩了 1 小时 2 分` /
 * `存档于 10-03 13:22` / `作者：…` / `格式 v4`，以及详情弹窗的各个标签）。
 * 默认语言翻成英文之后，存档行就变成
 * `208.3 KB · 我的地图 · 玩了 1 小时 2 分 · 存档于 10-03 13:22` 这种**中英混排**
 * —— 用户报的"存档显示部分翻译不全"就是它。
 *
 * 但 {@link MsavMeta} **刻意不碰 Android**（它要能在 PC 上单独编译、拿语料逐项对照），
 * 不能直接 `getString`。⇒ 分工：
 * <pre>
 *   MsavMeta  = **纯数据 + 解析**（不认识"玩了"这两个字）
 *   MsavText  = 显示文案（读资源，本类）
 * </pre>
 * 这与 {@link MapStatsMods#attrLabels} 那条"由有 Context 的调用方给文案"是同一套路，
 * 只是这里文案多、结构固定，所以收口成一个类而不是传 Map。
 *
 * ── 写法纪律（照抄工程那三条）────────────────────────────────────────────
 *
 * ★ **整句 + `%1$s`**，不拼"前缀 + 尾巴"。原来的 `" · ⚠ 像是写了一半"` 是**带前导空格的碎片**，
 *   搬进资源会被 aapt2 把空格剥掉 ⇒ 必然粘连。所以改成了整句
 *   `msav_half_fmt`（`%1$s · ⚠ 像是写了一半`）。
 * ★ 列表行**不放术语、不放类名**（用户看不到 `meta`/`classes.dex` 这类词）。
 * ★ 详情分两层：{@link MsavMeta#report()} 是给维护者的（落盘报告 + 自检看它），
 *   {@link #detail} 才是给用户的白话版 —— 两者的分工别混。
 */
public final class MsavText {

    private MsavText() {}

    // ── 时长 ──────────────────────────────────────────────────────────────

    /** 游玩时长文案（毫秒 ⇒ `3 小时 12 分`；<1 分钟说"不到 1 分钟"；无时长 ⇒ 空串） */
    public static String playtimeText(Context c, MsavMeta m) {
        if (m == null || m.playtime <= 0) return "";
        long sec = m.playtime / 1000L;
        long h = sec / 3600L, min = (sec % 3600L) / 60L;
        if (h > 0) return c.getString(R.string.msav_playtime_hm_fmt, h, min);
        if (min > 0) return c.getString(R.string.msav_playtime_min_fmt, min);
        return c.getString(R.string.msav_playtime_sec);
    }

    // ── 列表行 ────────────────────────────────────────────────────────────

    /** 总括一行（"写了一半"用整句包一层，不拼前导空格 —— 见类注释） */
    private static String withHalfWarning(Context c, String line, boolean truncated) {
        return truncated ? c.getString(R.string.msav_half_fmt, line) : line;
    }

    private static String join(Context c, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }
        return sb.toString();
    }

    /** 一行摘要（列表行副标题用；没有的项自动省掉）。⚠️ 目前只有自检在用 */
    public static String summary(Context c, MsavMeta m) {
        if (m == null) return "";
        List<String> parts = new ArrayList<>();
        String n = m.displayName();
        if (!n.isEmpty()) parts.add(n);
        String sz = m.sizeText();
        parts.add(sz.isEmpty() ? c.getString(R.string.msav_size_unknown) : sz);
        if (m.wave > 1) parts.add(c.getString(R.string.msav_wave_fmt, m.wave));
        String pt = playtimeText(c, m);
        if (!pt.isEmpty()) parts.add(c.getString(R.string.msav_played_fmt, pt));
        parts.add(c.getString(R.string.msav_format_fmt, m.version));
        return withHalfWarning(c, join(c, parts), m.truncated);
    }

    /**
     * ★ 列表行用的**短行**（区分度优先，用户 2026-10-03 要求）：
     *  · 存档：`地图名 · 玩了 X · 存档于 10-03 13:22`  ← 时间与时长才是"哪一份"的判据
     *  · 地图：`真名 · 586 × 586 · 作者：xxx`
     *  ⚠️ **格式版本不进这一行** —— 那是给维护者看的（读不出来时才在别处说），
     *    塞进来只会让长存档名溢出到第三行（真机截图里就溢出了）。
     *  · 缺什么就省什么；都没有时退回尺寸/格式，**不返回空串**（空行会让列表看不出区别）。
     *
     * @param isSave 由调用方按**文件所在目录**决定（`saves/` vs `maps/`）——
     *               ⚠️ 不要靠 tags 猜（老地图也带 `mapname`，见 {@link MsavMeta} 类注释）
     */
    public static String shortLine(Context c, MsavMeta m, boolean isSave) {
        if (m == null) return "";
        List<String> parts = new ArrayList<>();
        // ★ 地图真名里**带色码**（`[gold]Alloy-Sidestory [red]…`）—— 游戏会渲染成颜色，
        //   我们这里是纯文本列表 ⇒ 必须去色，否则一行里全是 `[gold]` 这种噪声（真机截图里就是）。
        //   判据复用 Mods 里那份 arc 等价实现（`Strings.stripColors`），不另写一份。
        String n = Mods.stripColors(m.displayName());
        if (!n.isEmpty()) parts.add(n);
        if (isSave) {
            String pt = playtimeText(c, m);
            if (!pt.isEmpty()) parts.add(c.getString(R.string.msav_played_fmt, pt));
            String t = m.savedText();
            if (!t.isEmpty()) parts.add(c.getString(R.string.msav_saved_at_fmt, t));
        } else {
            String sz = m.sizeText();
            if (!sz.isEmpty()) parts.add(sz);
            // ⚠️ 作者字段**同样可能带色码**（真机实测：`[#2E8E05]iq[lime]tik[green]123`）
            //   ⇒ 与真名同一套判据，别只处理名字那一处
            String au = Mods.stripColors(m.author == null ? "" : m.author).trim();
            if (!au.isEmpty()) parts.add(c.getString(R.string.msav_author_fmt, au));
        }
        if (parts.isEmpty()) {
            String sz = m.sizeText();
            if (!sz.isEmpty()) parts.add(sz);
            parts.add(c.getString(R.string.msav_format_fmt, m.version));
        }
        return withHalfWarning(c, join(c, parts), m.truncated);
    }

    // ── 详情 ──────────────────────────────────────────────────────────────

    /**
     * ★ **面向用户**的详情（第 60 轮：点开地图看详细信息）。
     *
     * 与 {@link MsavMeta#report()} 的分工要分清：
     *  · `report()` —— 给维护者的（写着「毫秒时间戳」「元数据项 N 条」这类话），
     *    用在落盘报告与自检里；
     *  · `detail()` —— 给用户看的：**白话、短、没有术语**（用户 2026-10-02 定的文案三条），
     *    色码去掉、时间写成人读的、只列"能用来判断这是哪张图"的字段。
     */
    public static String detail(Context c, MsavMeta m) {
        if (m == null) return c.getString(R.string.msav_unreadable) + "\n";
        StringBuilder sb = new StringBuilder();
        if (!m.ok) {
            sb.append(c.getString(R.string.msav_unreadable));
            if (m.error != null && !m.error.isEmpty()) {
                sb.append("：").append(userReason(c, m));
            }
            sb.append('\n');
            if (m.truncated) sb.append(c.getString(R.string.msav_half_broken)).append('\n');
            return sb.toString();
        }
        if (m.truncated) sb.append(c.getString(R.string.msav_half_banner)).append('\n');
        String sz = m.sizeText();
        if (!sz.isEmpty()) sb.append(c.getString(R.string.msav_lbl_size_fmt, sz)).append('\n');
        String au = Mods.stripColors(m.author == null ? "" : m.author).trim();
        if (!au.isEmpty()) sb.append(c.getString(R.string.msav_lbl_author_fmt, au)).append('\n');
        if (m.wave > 0) sb.append(c.getString(R.string.msav_lbl_wave_fmt, m.wave)).append('\n');
        String pt = playtimeText(c, m);
        if (!pt.isEmpty()) sb.append(c.getString(R.string.msav_lbl_played_fmt, pt)).append('\n');
        String t = m.savedText();
        if (!t.isEmpty()) sb.append(c.getString(R.string.msav_lbl_saved_fmt, t)).append('\n');
        sb.append(c.getString(R.string.msav_lbl_format_fmt, m.version)).append('\n');
        String d = Mods.stripColors(m.description == null ? "" : m.description).trim();
        if (!d.isEmpty()) {
            sb.append(c.getString(R.string.msav_lbl_desc_fmt,
                    d.length() > 160 ? d.substring(0, 160) + "…" : d)).append('\n');
        }
        return sb.toString();
    }

    // ── 失败原因 ──────────────────────────────────────────────────────────

    /**
     * **给用户看**的失败原因（列表行 / 详情页用）。
     *
     * ★ 为什么要有它：{@link MsavMeta#error} 里混着两类东西 ——
     *   ① 我们自己写的中文判断（`这不是 .msav：开头不是 MSAV`）—— 直接能用；
     *   ② Java 异常的 `类名: 消息`（`ZipException: incorrect header check`）—— 对用户是天书，
     *      而它在真实场景里**最常见**（随便找个文件把后缀改成 `.msav`）。
     *   ⇒ 只**翻译**第 ② 类，其余原样返回。
     * ⚠️ 不改判据、不改 {@link MsavMeta#error} 原文：排查时看的仍然是那个字段（`report()` 用的就是它）。
     *
     * ★ **2026-10-05（P3）补齐了原来那个"已知缺口"**：第 ① 类不再透传中文，而是先看
     *   {@link MsavMeta#errCode}（核心是纯 Java ⇒ 不能 `getString`，只给「码 + 参数」）。
     *   ⚠️ **加一个码就要在这里加一条**：`switch` 有 `default` 兜底（返回"原因不明"），
     *   所以漏掉映射**不崩**——正因为不崩，自检里才要**遍历所有码**过一遍（漏映射 = 界面静默空白）。
     */
    public static String userReason(Context c, MsavMeta m) {
        if (m != null && m.errCode != MsavMeta.E_NONE) {
            switch (m.errCode) {
                case MsavMeta.E_NULL_FILE:
                    return c.getString(R.string.msav_reason_null_file);
                case MsavMeta.E_MAGIC:
                    return c.getString(R.string.msav_reason_magic);
                case MsavMeta.E_VERSION:
                    return c.getString(R.string.msav_reason_version_fmt, m.errN1);
                case MsavMeta.E_META_LEN:
                    return c.getString(R.string.msav_reason_meta_len_fmt, m.errN1);
                case MsavMeta.E_TRUNC_HEAD:
                    return c.getString(R.string.msav_reason_trunc_head);
                case MsavMeta.E_TRUNC_AFTER:
                    return c.getString(R.string.msav_reason_trunc_after);
                case MsavMeta.E_META_SHORT:
                    return c.getString(R.string.msav_reason_meta_short_fmt, m.errN1, m.errN2);
                case MsavMeta.E_META_TAIL:
                    return c.getString(R.string.msav_reason_meta_tail_fmt, m.errN1);
                default:
                    // 新加了码却忘了在这里加映射 ⇒ 不崩，但界面会静默退化
                    return c.getString(R.string.msav_unknown_reason);
            }
        }
        String e = m == null || m.error == null ? "" : m.error.trim();
        if (e.isEmpty()) return c.getString(R.string.msav_unknown_reason);
        if (e.matches("^[A-Za-z_$][A-Za-z0-9_$]*(Exception|Error)\\b.*")) {
            if (e.startsWith("ZipException") || e.startsWith("EOFException")) {
                return c.getString(R.string.msav_reason_not_save);
            }
            return c.getString(R.string.msav_reason_read_error);
        }
        return e;
    }
}
