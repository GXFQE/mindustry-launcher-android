package io.mdt.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.MessageDigest;

import android.content.Context;

/**
 * 基础工具（模块边界：最底层，不依赖任何其他模块 —— 对齐桌面版 utils 的位置）。
 *
 * ⚠️ 唯一的例外是 {@link #ioReason}：它要 `Context` 才能查资源（这是我们自己的双语资源），
 *   但它**不依赖任何业务类**，仍然算"最底层"。
 */
public final class Util {

    /**
     * ★★ 2026-10-06（第 115 轮第五批）：把**系统异常**翻成用户看得懂的一句话（**弹窗正文**专用）。
     *
     * <p>为什么需要它：磁盘满 / 没权限 / 文件被占用时，Java 抛出来的是
     * `java.io.IOException: write failed: ENOSPC (No space left on device)` 这种句子 ——
     * 直接当弹窗正文等于把维护者视角甩给用户（本工程 §五 三大纪律之一）。
     *
     * <p>🔴 判据只有一条 —— **认识的 errno 才翻译，其余原样透传**：
     * <ul>
     *   <li>认识的系统错误（`ENOSPC` / `EACCES` / `EBUSY` / `EROFS` / `ENOENT` …）⇒ 对应那一句白话；</li>
     *   <li>**我们自己抛的资源文案**（如 `bp_export_nosrc`）⇒ 原样返回 ——
     *       它们**本来就是要给用户看的**，翻译反而会把"没有可导出的文件"变成"读写失败"；</li>
     *   <li>消息为空 ⇒ `fallback`（没有就退回一句泛化的「读写失败」）。</li>
     * </ul>
     *
     * <p>⚠️ 原文**不许丢**：调用方照旧 `Log.w(...)`（报告 / 自检 / 排查看原文）——
     * 这个函数只管**第一层**那一句。
     */
    public static String ioReason(Context c, Throwable t) {
        return ioReason(c, t, null);
    }

    /** 见 {@link #ioReason(Context, Throwable)}；`fallback` = 消息为空时用的那句（可为 null） */
    public static String ioReason(Context c, Throwable t, String fallback) {
        String m = t == null ? null : t.getMessage();
        if (m == null || m.trim().isEmpty()) {
            if (fallback != null) return fallback;
            return c.getString(R.string.io_reason_failed);
        }
        String s = m;
        if (has(s, "ENOSPC") || has(s, "No space left")) return c.getString(R.string.io_reason_no_space);
        if (has(s, "EACCES") || has(s, "Permission denied") || has(s, "EPERM")
                || has(s, "Operation not permitted")) return c.getString(R.string.io_reason_denied);
        if (has(s, "EBUSY") || has(s, "Device or resource busy") || has(s, "Text file busy")
                || has(s, "EAGAIN")) return c.getString(R.string.io_reason_busy);
        if (has(s, "EROFS") || has(s, "Read-only file system")) return c.getString(R.string.io_reason_readonly);
        if (has(s, "ENOENT") || has(s, "No such file")) return c.getString(R.string.io_reason_missing);
        return s;      // 我们自己写的文案 / 不认识的错误：原样（信息不丢）
    }

    /** 大小写无关的子串判断（errno 在消息里可能大小写不一） */
    private static boolean has(String s, String needle) {
        return s.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * ★ **zlib 头判据（唯一实现）**：`78 01/5E/9C/DA` —— 与 arc 的 `writeCompressed` 同一条件。
     *
     * 🔴 来历（2026-10-03，F10）：`SettingsBin` 与 `Msav` 各写过一份；而且 `Msav` 那份是**错的** ——
     *   它按 **gzip** 魔数（`1f 8b`）判 `.msav`，可 `.msav` 是 **zlib**（真实文件头就是 `78 9C`，
     *   游戏的 `MapIO.createMap` 用的也是 `InflaterInputStream`）⇒ 每份正常存档都被误报成
     *   "不是 gzip"，而真正读不了的 gzip 文件反被放行。
     * ⇒ 判据收到这里一处，两边共用。
     */
    public static boolean isZlibHeader(int b0, int b1) {
        return b0 == 0x78 && (b1 == 0x01 || b1 == 0x5E || b1 == 0x9C || b1 == 0xDA);
    }

    /** 读文件前两字节判 zlib（读不动 ⇒ false） */
    public static boolean isZlib(File f) {
        if (f == null || !f.isFile()) return false;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                return isZlibHeader(in.read(), in.read());
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return false;
        }
    }
    private Util() {}

    /**
     * 让根视图避开状态栏 / 导航栏。
     *
     * ★ 为什么必须有：`targetSdk ≥ 35` 起系统**强制 edge-to-edge**，
     *   窗口内容会画到状态栏和导航栏下面 —— 表现是界面顶部几行字被状态栏盖住
     *   （M3 截图里"数据根""当前槽"两行就是这么消失的）。
     *   `setFitsSystemWindows(true)` 在这个模式下不可靠，所以直接按 insets 加 padding。
     *
     * ★ F3a 修正：**四边都要算**。旧版只处理 top / bottom，
     *   而横屏时三键导航栏会跑到**窗口右侧**（该机型 129px @520dpi，自 x=2571 起）。
     *   实测操作行右边界 2648 已经伸进导航栏区 —— 行尾箭头被压住。
     *   现在 left / right 一并叠加。
     *
     * ★ 四个方向的 base 都要先存档：insets 会**反复派发**（旋转 / 手势条显隐 /
     *   进出全屏），若用 `v.getPaddingLeft()` 现读现加，多次回调会把 padding 越加越大。
     *   一律 base + inset，保证幂等。
     *
     * 用 `OnApplyWindowInsetsListener` 而不是一次性读值：旋转 / 进出全屏 / 手势条
     * 显隐都会重新派发 insets，监听器能跟着走。
     *
     * ⚠️ 宿主带 ActionBar 时 top inset 通常已被 ActionBar 消费（实测传入 0，
     *   root 的 paddingTop 保持 0，内容正好接在标题栏下方）。这里不猜"是否已消费"，
     *   拿到什么就加什么。
     */
    public static void applySystemInsets(final android.view.View root) {
        if (root == null) return;
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(android.view.View v,
                                                                 android.view.WindowInsets insets) {
                v.setPadding(baseLeft + insets.getSystemWindowInsetLeft(),
                        baseTop + insets.getSystemWindowInsetTop(),
                        baseRight + insets.getSystemWindowInsetRight(),
                        baseBottom + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    /**
     * F1b：绑定一个「操作行」（layout/row_action.xml）。
     *
     * ⚠️ row_action 在同一张布局里被 include 了多次，展开后 act_icon / act_title / act_sub
     *    这些 id 在整棵树里**是重复的** ⇒ 必须**从行根开始** findViewById，
     *    直接从页面根找只会命中第一行（静默错绑，很难查）。
     */
    public static void bindAction(android.view.View root, int rowId, int iconRes,
                                  int titleRes, int subRes, final Runnable onClick) {
        android.view.View row = root.findViewById(rowId);
        ((android.widget.ImageView) row.findViewById(R.id.act_icon)).setImageResource(iconRes);
        ((android.widget.TextView) row.findViewById(R.id.act_title)).setText(titleRes);
        ((android.widget.TextView) row.findViewById(R.id.act_sub)).setText(subRes);
        row.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { onClick.run(); }
        });
    }

    /**
     * F3：操作行的变体 —— 副标题留空，由调用方填「当前值」（设置页用）。
     * 返回行根，调用方直接 `row.findViewById(R.id.act_sub).setText(...)`。
     * 与 bindAction 同样必须**从行根开始** findViewById（include 多次会重复 id）。
     */
    public static android.view.View bindActionValue(android.view.View root, int rowId,
                                                    int iconRes, int titleRes,
                                                    final Runnable onClick) {
        android.view.View row = root.findViewById(rowId);
        ((android.widget.ImageView) row.findViewById(R.id.act_icon)).setImageResource(iconRes);
        ((android.widget.TextView) row.findViewById(R.id.act_title)).setText(titleRes);
        row.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { onClick.run(); }
        });
        return row;
    }

    /**
     * F1b：把一张卡绑定成「点一下展开 / 收起详情」（箭头跟着转 90°）。
     * 用于页面顶部的状态卡：摘要常驻，长路径默认收起。
     */
    public static void bindExpandableCard(android.view.View root, int boxId,
                                          final int detailId, final int chevronId) {
        final android.view.View box = root.findViewById(boxId);
        box.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                android.view.View detail = box.findViewById(detailId);
                boolean show = detail.getVisibility() != android.view.View.VISIBLE;
                detail.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
                ((android.widget.ImageView) box.findViewById(chevronId))
                        .setRotation(show ? 90f : 0f);
            }
        });
    }

    /**
     * ★★ 这个 Activity 是不是已经"死了"（正在退出 **或** 已被销毁）。
     *
     * 用途只有一个：**后台任务完成 → `runOnUiThread` 里弹窗/更新 View 之前**先判它，
     * 为 true 就直接 `return`。
     *
     * 为什么必须有它（2026-10-04 修，P1）：那种收尾方式是**唯一会直接崩**的一种 ——
     * `new AlertDialog.Builder(已销毁的 Activity).show()` 会抛
     * `WindowManager$BadTokenException: ... is your activity running?`，主进程直接闪退
     * （而本工程又没有全局未捕获处理器，崩了不留任何文件）。
     *
     * 🔴 **两样都要判，缺一个都挡不住**：转屏走的是"销毁旧实例 + 建新实例"，
     *   那一刻用户并没有退出 ⇒ **`isFinishing()` 是 `false`**，只有 `isDestroyed()` 为 true。
     *   本工程原有的几处判据（MapsActivity / SlotActivity / SettingsActivity）恰好**只判了
     *   isFinishing** —— 对"更新一行文字"够用（`setText` 不会崩），对"弹新窗口"不够。
     *
     * ⚠️ 代价要知道：判死之后那句提示就**不显示了**（用户转屏期间任务跑完 ⇒ 看不到结果）。
     *   这比闪退好，但不是"把结果补给他" —— 要后者得另做一套"挂起消息"机制。
     */
    public static boolean dead(android.app.Activity a) {
        return a == null || a.isFinishing() || a.isDestroyed();
    }

    /**
     * ★ 当前进程是不是**主进程**（不是 `:game` 子进程）。
     *
     * 为什么需要它：`LauncherApp.onCreate` 在**两个进程各跑一次**（工程头注释就写着这条），
     * 而有些"只该在主进程做"的事（例如 {@link Crash} 装未捕获处理器 —— `:game` 里那是游戏的进程，
     * 它有自己的崩溃转储）必须先问一句"我是谁"。
     *
     * 判据：主进程的名字**就是包名本身**；`:game` 进程是 `包名:game`。
     * ⚠️ `Application.getProcessName()` 是 **API 28+**，minSdk 26 要在旧机上回落读
     *   `/proc/self/cmdline`；两者都拿不到时**当主进程**处理（宁可多装一次，也别漏装）。
     */
    public static boolean isMainProcess(android.content.Context ctx) {
        String p = null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                p = android.app.Application.getProcessName();
            }
        } catch (Throwable ignored) {
        }
        if (p == null || p.isEmpty()) {
            java.io.InputStream in = null;
            try {
                in = new java.io.FileInputStream("/proc/self/cmdline");
                byte[] b = new byte[160];
                int n = in.read(b);
                if (n > 0) {
                    int e = 0;
                    while (e < n && b[e] != 0) e++;
                    p = new String(b, 0, e, "UTF-8");
                }
            } catch (Throwable ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return p == null || p.isEmpty() || p.equals(ctx.getPackageName());
    }

    public static String readText(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        } finally {
            r.close();
        }
        return sb.toString();
    }

    /** 原子写：先写 .tmp 再改名（对齐桌面版 atomic_write_text，绝不让读者看到半个文件） */
    public static void atomicWriteText(File f, String text) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
        try {
            w.write(text);
        } finally {
            w.close();
        }
        if (!tmp.renameTo(f)) {
            // 目标已存在时部分文件系统拒绝覆盖式 rename：退化为删了再改
            if (f.exists() && !f.delete()) {
                throw new IOException("cannot replace " + f);
            }
            if (!tmp.renameTo(f)) {
                throw new IOException("rename failed: " + tmp + " -> " + f);
            }
        }
    }

    /**
     * 相对路径（"条目名"）：返回 `f` 相对基准目录 `base` 的路径，统一用 `/` 分隔。
     *
     * ★ 唯一实现 —— {@link Exporter} 的 zip 条目名与 {@link Backup} 的清单 rel
     *   都走这里。以前是两份（`Exporter.relOf` / `Backup.rel`）且**内容不一致**。
     *
     * ⚠️ 两条兜底都**别删**（F19 的由来）：
     *   · `f` 就是 `base` 本身 ⇒ 差集是空串 ⇒ 返回 `f.getName()`；
     *   · `f` 不在 `base` 之下 ⇒ **返回 `f.getName()` 而不是原样绝对路径**。
     *     少了第一条时 `Backup.rel` 会返回 **空串**，而 `new File(root, "")` 会被
     *     Java 规范化成 `root` **自己** ⇒ 清单里写出 `"settings.bin/"` 这种带尾斜杠的
     *     条目、恢复时试图把文件写到目录路径上 —— 靠"File 丢掉尾部分隔符"侥幸能用，
     *     **没有任何症状**，只会在换了 JDK / 改了调用方式后突然变成硬失败。
     *     第二条是防"绝对路径漏进清单"：清单里的 rel 会被 `new File(dstRoot, rel)` 直接
     *     拼成写入目标 ⇒ 绝对路径意味着**写到槽目录外面去**。宁可退化成重名，也不越界。
     */
    public static String relOf(File base, File f) {
        String root = base.getAbsolutePath();
        String p = f.getAbsolutePath();
        String s = p;
        if (p.startsWith(root)) {
            s = p.substring(root.length());
            while (s.startsWith(File.separator)) s = s.substring(1);
        } else {
            return f.getName();
        }
        s = s.replace(File.separatorChar, '/');
        return s.isEmpty() ? f.getName() : s;
    }

    /**
     * 把流整个搬进一个文件（**只搬字节**：临时名 / 改名 / 校验都由调用方决定）。
     *
     * ★ 为什么新代码一律走这里：本工程原先有 4 处各写一遍的流拷贝
     *   （`Importer` / `Msav` / `SlotZip` / `Backup.copyRecursive`），
     *   每一处的缓冲区大小、异常处理、计数口径都略有差别 —— 又一次"同一件事多份实现"。
     *   老的 4 处**这轮不动**（动它们要重跑各自的自检），但新增调用方只走这一处。
     *
     * @return 写入的字节数
     */
    public static long copyStream(java.io.InputStream in, File dst) throws IOException {
        File p = dst.getParentFile();
        if (p != null && !p.exists() && !p.mkdirs()) throw new IOException("建目录失败：" + p);
        FileOutputStream out = new FileOutputStream(dst);
        long total = 0;
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
        } finally {
            out.close();
        }
        return total;
    }

    /** 文件 → 文件（同上，只是省得调用方自己开流） */
    public static long copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        try {
            return copyStream(in, dst);
        } finally {
            in.close();
        }
    }

    public static String md5(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder(32);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB"};
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < units.length - 1);
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i]);
    }
}
