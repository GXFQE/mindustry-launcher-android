package io.mdt.launcher;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;

import java.io.File;

/**
 * **SAF 待办的磁盘日志**（第 177 轮，杀后台 P0）。
 *
 * <p>起因（2026-10-19 真机实测）：所有"选完文件再动手"的动作都把目标存在
 * Activity 的静态字段里（`ModsActivity.sImportSlot`、`SlotIo.sZipSlot`、
 * `BatchIo.sSrcs`、`StatsExporter.sText` …）。系统在文件选择器打开时把应用杀了，
 * 字段就没了；用户选完文件回来，`onActivityResult` 看到 null **静默 return** ——
 * 界面上是"我选完了，什么都没发生"。日志脱敏那条更糟：重建后 `mExportScrub=false`，
 * 用户点的是脱敏，导出的却是原件。
 *
 * <p>修法：**发起选择器之前**把"这次要干什么"写进内部存储；回调进来先
 * {@link #take} 再动手。这不是 `onSaveInstanceState` 的替代品，而是比它更强的一层：
 * 进程被 force-stop / 崩溃也一样能读到（savedInstanceState 只在系统回收时有）。
 *
 * <p>文件布局：`app_hub/pending/<kind>.json`（+ 可选的 `<kind>.txt` 正文）。
 * 每个动作一个 kind，单一写者；发起前覆盖、回调时取走并删除。
 * `kind` 只允许 `[a-z0-9._-]`（防路径注入；常量都在调用方）。
 *
 * <p>⚠️ **写失败不阻塞动作**：调用方仍可继续（目标还在内存里，正常路径不受影响）；
 *   只是"进程被杀后还能接上"这条保障没了 —— 回调会发现待办缺失并提示
 *   `pending_lost`，**不会静默 no-op**。日志脱敏那条更严：待办缺失 ⇒ 拒绝导出。
 *   自检里有"写→取→再取为空"的判据。
 */
final class Pending {
    private static final String TAG = "MDTLauncher";
    private static final String DIR = "pending";

    private Pending() {}

    /** 写一份待办（覆盖同名）。失败返回 false（正常路径仍可继续，只是少了死后续接）。 */
    static boolean put(Context ctx, String kind, JSONObject meta) {
        if (!valid(kind)) return false;
        try {
            Util.atomicWriteText(jsonFile(ctx, kind), meta == null ? "{}" : meta.toString());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "pending put failed: " + kind, t);
            return false;
        }
    }

    /** 连正文一起写（统计导出那种"内容已经在内存里、但可能几 MB"的待办）。 */
    static boolean putText(Context ctx, String kind, String text) {
        if (!valid(kind)) return false;
        try {
            Util.atomicWriteText(textFile(ctx, kind), text == null ? "" : text);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "pending text put failed: " + kind, t);
            return false;
        }
    }

    /**
     * 取走待办（读完就删）。没有 / 读坏了返回 null。
     * ★ 调用方**无论结果码是什么**都要先调它 —— 用户取消之后残留的目标会被下一次
     *   选择"接上"，那是本工程反复强调的纪律（见 SlotIo 头注释）。
     */
    static JSONObject take(Context ctx, String kind) {
        if (!valid(kind)) return null;
        File f = jsonFile(ctx, kind);
        String s;
        try {
            s = Util.readText(f);
        } catch (Throwable t) {
            return null;                 // 不存在 = 没有待办，不是错误
        }
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            Log.w(TAG, "pending json broken: " + kind, t);
            return null;
        } finally {
            delete(f);
        }
    }

    /** 取走正文（与 {@link #take} 配对；读不到返回 null）。 */
    static String takeText(Context ctx, String kind) {
        if (!valid(kind)) return null;
        File f = textFile(ctx, kind);
        try {
            return Util.readText(f);
        } catch (Throwable t) {
            return null;
        } finally {
            delete(f);
        }
    }

    /** 丢弃一份待办（正文一起）。 */
    static void clear(Context ctx, String kind) {
        if (!valid(kind)) return;
        delete(jsonFile(ctx, kind));
        delete(textFile(ctx, kind));
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private static boolean valid(String kind) {
        if (kind == null || kind.isEmpty() || kind.length() > 64) return false;
        for (int i = 0; i < kind.length(); i++) {
            char c = kind.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private static File dir(Context ctx) {
        File d = new File(Paths.privateDir(ctx), DIR);
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
            Log.w(TAG, "pending dir not writable: " + d);
        }
        return d;
    }

    private static File jsonFile(Context ctx, String kind) {
        return new File(dir(ctx), kind + ".json");
    }

    private static File textFile(Context ctx, String kind) {
        return new File(dir(ctx), kind + ".txt");
    }

    private static void delete(File f) {
        if (f != null && f.exists() && !f.delete()) {
            Log.w(TAG, "pending delete failed: " + f);
        }
    }
}
