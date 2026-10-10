package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;

/**
 * **批量导入 / 导出**（第 127 轮）。模块边界：依赖 {@link Exporter} / {@link Trans} / {@link Util}，
 * 被四个内容页（模组 / 地图 / 存档 / 蓝图）调用。
 *
 * <h3>一条判据贯穿全部四个内容页</h3>
 * <pre>
 *   导出：**勾 1 份 = 老样子**（直接给那个文件，文件名就是原文件名）；
 *         **勾 ≥2 份 = 一个 zip**（外层 zip 里每一条 = 单份导出时用户会拿到的那个文件）。
 *   导入：**选 1 个文件 = 老样子**（存档那份还会问名字、同名还是逐个问）；
 *         **选 ≥2 个文件、或者选了 .zip = 批量**（按原文件名落位，同名**只问一次**）。
 * </pre>
 * ★ 为什么"多份"必须打成一个 zip：SAF 的 `ACTION_CREATE_DOCUMENT` **一次只能建一个文件**
 *   （要一次落多个文件就得改用 `ACTION_OPEN_DOCUMENT_TREE` 那套目录树 API，
 *   而我们已经有 {@link Exporter#zipTo} / 整槽 zip 这一条成熟的 zip 路）。
 *   顺带白送一个性质：**导出的 zip 能被同一套批量导入吃回去**（往返）。
 *
 * <h3>为什么"整包 zip"能被认出来（而不是要求用户选多个文件）</h3>
 * 判据 = {@link #bundleNames}：一个 zip 里**有**我们认识的后缀（`.msav` / `.msch` / `.jar`…），
 * 且**没有**包根上的模组说明文件（{@link Mods#META_FILES}）。
 * 后者是关键：模组包自己也是 zip，用户"选一个 .zip 当模组导入"是最常见的动作
 * —— 没有这条就会把一个正常的模组包当成"一整包"拆开。
 *
 * <h3>批量导入的交互被压成"一次问"</h3>
 * 单份导入有两条交互（存档改名框 / 同名替换框）。批量时逐份弹框是折磨，所以：
 * <ul>
 *   <li><b>名字</b>：按**源文件名**落位（批量导入的前提就是"这些名字是我的"）；</li>
 *   <li><b>同名</b>：写盘**之前**先把这一批的目标名算出来（{@link #plan}，纯函数），
 *       有同名就**只问一次**"其中 N 份与槽里同名，替换吗？"⇒ 替换的走覆盖（旧的进中转站），
 *       不替换的那几份跳过并如实报出来。</li>
 * </ul>
 * ★ 为什么"先算名字再写盘"而不是"先写、撞了再回滚"：四个内容模块（{@link MapFiles} /
 *   {@link BlueprintFiles} / {@link Msav} / {@link Mods}）的同名判据都只是
 *   `dest.exists()` + 名字是**纯函数**（`safeName(name)`）⇒ 完全可以提前算。
 *   提前算还有一个好处：**同一个动作只问一次**（否则 20 份里撞了 5 份就会问 5 次）。
 *
 * <h3>跨 onActivityResult 的待办</h3>
 * 与 {@link SlotIo} 同一条纪律：导出的条目清单存在**静态字段**里（用户在系统选择器里
 * 待多久都有可能），**发起前重设、回调进来先清空**。
 */
final class BatchIo {

    private static final String TAG = "MDTLauncher";

    /**
     * 批量导出的落点选择（一个 zip）。
     * ⚠️ **导入没有共用请求码**：各页面自己那一条（`REQ_MAP` / `REQ_BP_IMPORT` / …）继续用，
     *   因为"选回来之后往哪落"是各页面自己的事（只有它们知道槽名与内容类型）。
     */
    static final int REQ_EXPORT = 72;

    /** 一批待导入的条目里，最多认这么多（防止用户手滑选了几千个把界面卡死） */
    private static final int MAX_ITEMS = 500;

    private BatchIo() {}

    // ══ 选文件（多选）═══════════════════════════════════════════════════════

    /**
     * 拉起系统文件选择器，**允许多选**。
     * ⚠️ MIME 一律 `*&#47;*`：各家文件管理器对 `.msav` / `.msch` / `.zip` 报的 MIME 五花八门，
     *   限死会让用户选不中自己的文件（这条在单份导入时已经栽过一次，见 `MapsActivity`）。
     */
    static void pickFiles(Activity a, int req, int chooserRes) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        a.startActivityForResult(Intent.createChooser(i, Trans.get(a, chooserRes)), req);
    }

    /** 待导入的一份（SAF 给的 uri + 显示名） */
    static final class Doc {
        final Uri uri;
        final String name;

        Doc(Uri uri, String name) {
            this.uri = uri;
            this.name = (name == null || name.trim().isEmpty()) ? "file" : name.trim();
        }

        InputStream open(Context ctx) throws IOException {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException(Trans.get(ctx, R.string.batch_err_open_fmt, name));
            return in;
        }
    }

    /**
     * 把 `onActivityResult` 的 data 拆成一批待导入的条目。
     * ★ 两条来源都要看：多选走 `getClipData()`，单选走 `getData()`（老 ROM 也有多选只给 data 的）。
     */
    static List<Doc> docsOf(Context ctx, Intent data) {
        List<Doc> out = new ArrayList<>();
        if (data == null) return out;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount() && out.size() < MAX_ITEMS; i++) {
                Uri u = clip.getItemAt(i) == null ? null : clip.getItemAt(i).getUri();
                if (u != null) out.add(new Doc(u, displayNameOf(ctx, u)));
            }
        }
        if (out.isEmpty() && data.getData() != null) {
            out.add(new Doc(data.getData(), displayNameOf(ctx, data.getData())));
        }
        return out;
    }

    /** SAF 的显示名（拿不到就退回路径末段，再退回 "file"） */
    static String displayNameOf(Context ctx, Uri uri) {
        try {
            Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) {
                        String n = c.getString(idx);
                        if (n != null && !n.trim().isEmpty()) return n.trim();
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri == null ? null : uri.getLastPathSegment();
        return (last == null || last.trim().isEmpty()) ? "file" : last.trim();
    }

    // ══ 导出侧 ═════════════════════════════════════════════════════════════

    /** 跨回调的待导出清单（发起前重设、回调进来先清 —— 同 {@link SlotIo} 的纪律） */
    private static List<Exporter.Src> sSrcs;

    /** 批量导出的建议 zip 名：`mdt-<类型>-20261009-1930.zip`（类型是 ASCII，不进资源） */
    static String suggestZipName(String kind) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        return "mdt-" + kind + "-" + stamp + ".zip";
    }

    /** 发起"存到哪"（一个 zip）—— 真正的写在 {@link #handleExportResult} 里 */
    static void startExport(Activity a, List<Exporter.Src> srcs, String zipName) {
        if (srcs == null || srcs.isEmpty()) {
            toast(a, Trans.get(a, R.string.batch_export_nothing));
            return;
        }
        sSrcs = srcs;
        a.startActivityForResult(Intent.createChooser(
                Exporter.createDoc(zipName, "application/zip"),
                Trans.get(a, R.string.chooser_export)), REQ_EXPORT);
    }

    /**
     * 转发 `onActivityResult`。返回 true = 这个请求码是我们的、已经处理（调用方直接 return）。
     * ★ 与 `SlotIo.onActivityResult` 同一条：**无论成败先清待办**，否则用户取消之后
     *   残留的清单会被下一次选择"接上"，导出成上一批东西。
     */
    static boolean handleExportResult(final Activity a, int req, int res, Intent data) {
        if (req != REQ_EXPORT) return false;
        final List<Exporter.Src> srcs = sSrcs;
        sSrcs = null;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null || srcs == null) {
            return true;
        }
        final Uri uri = data.getData();
        final ProgressDialog pd = ProgressDialog.show(a,
                Trans.get(a, R.string.batch_export_progress_title),
                Trans.get(a, R.string.batch_export_progress_fmt, srcs.size()), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                Exporter.Result r = null;
                try {
                    r = Exporter.zipSources(a, uri, srcs);
                } catch (Throwable t) {
                    err = Util.ioReason(a, t);
                    Log.w(TAG, "batch export failed", t);
                }
                final String fe = err;
                final Exporter.Result fr = r;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (Util.dead(a)) return;
                        if (fe != null) {
                            alert(a, Trans.get(a, R.string.export_failed), fe);
                            return;
                        }
                        toast(a, Trans.get(a, R.string.batch_export_zip_done_fmt,
                                fr.files, Util.formatSize(fr.outBytes)));
                    }
                });
            }
        }, "batch-export").start();
        return true;
    }

    // ══ 多选对话框（四个页面共用）═══════════════════════════════════════════

    /** 勾选回调：给的是**下标**（调用方自己按下标取条目 —— 与列表同一次扫描，不重扫） */
    interface OnPick {
        void onPick(List<Integer> indexes);
    }

    /**
     * 对话框显示之后、把副标题 / 缩略图**异步补上**的钩子（存档那条列表要它 ——
     * 名字 + 大小先出，"这存档是哪张图的"与预览图后台再补，见 {@link SlotIo#fillSavesInto}）。
     * `alive[0]` 在对话框关闭时被置 false ⇒ 后台线程自己停。
     */
    interface Filler {
        void fill(MsavListAdapter ad, ListView lv, boolean[] alive);
    }

    static void pickMulti(final Activity a, int titleRes, String head, String[] titles,
                          String[] subs, android.graphics.Bitmap[] thumbs, final OnPick cb) {
        pickMulti(a, titleRes, head, titles, subs, thumbs, null, cb);
    }

    /**
     * 「勾选要导出的那几份」对话框（四个内容页共用一份实现）。
     *
     * <p>交互三条（都来自"别让用户猜"）：
     * <ol>
     *   <li>点整行 = 勾 / 取消（行内那个 `CheckBox` 是**不可交互**的，触摸全归 ListView）；</li>
     *   <li>正按钮**带份数**（「导出（3 份）」）—— 份数是这一刻唯一的"我选了什么"的反馈；</li>
     *   <li>中按钮「全选 / 清空」**跟着状态换措辞**（已经是全选时它就该说"清空"，
     *       否则用户按下去没反应）。</li>
     * </ol>
     * ⚠️ 一份都没勾时**不关窗**，只提示一句（关掉等于把用户刚做的勾选扔了）。
     */
    static void pickMulti(final Activity a, int titleRes, String head, String[] titles,
                          String[] subs, android.graphics.Bitmap[] thumbs, final Filler filler,
                          final OnPick cb) {
        if (titles == null || titles.length == 0) return;
        View box = a.getLayoutInflater().inflate(R.layout.dialog_msav_list, null);
        TextView h = (TextView) box.findViewById(R.id.msav_head);
        h.setVisibility(View.VISIBLE);
        h.setText(head == null ? "" : head);
        final ListView lv = (ListView) box.findViewById(R.id.msav_list);
        final MsavListAdapter ad =
                new MsavListAdapter(a, titles, subs, thumbs, new boolean[titles.length]);
        lv.setAdapter(ad);
        final boolean[] alive = {true};

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(titleRes)
                .setView(box)
                .setPositiveButton(R.string.batch_export_btn_fmt, null)
                .setNeutralButton(R.string.batch_export_all, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                ad.setChecked(pos, !ad.isChecked(pos));
                ad.refreshCheck(lv, pos);
                syncPickButtons(dlg, a, ad);
            }
        });
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                syncPickButtons(dlg, a, ad);
                if (filler != null) {
                    // ★ 异步补内容的那条路（存档列表）：关了窗就让后台线程自己停
                    dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override public void onDismiss(DialogInterface d) {
                            alive[0] = false;
                        }
                    });
                    filler.fill(ad, lv, alive);
                }
                dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                List<Integer> idx = ad.checkedIndexes();
                                if (idx.isEmpty()) {
                                    toast(a, Trans.get(a, R.string.batch_export_nothing));
                                    return;                 // 不关窗：勾选还留着
                                }
                                dlg.dismiss();
                                cb.onPick(idx);
                            }
                        });
                dlg.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                boolean all = ad.checkedCount() == ad.getCount();
                                ad.setAll(!all);
                                lv.invalidateViews();   // 只重画可见行（够用：状态在适配器里）
                                syncPickButtons(dlg, a, ad);
                            }
                        });
            }
        });
        dlg.show();
    }

    /** 正按钮报份数、中按钮报"下一步会做什么"（见 {@link #pickMulti} 的三条） */
    private static void syncPickButtons(AlertDialog dlg, Context c, MsavListAdapter ad) {
        if (dlg == null || ad == null) return;
        android.widget.Button pos = dlg.getButton(DialogInterface.BUTTON_POSITIVE);
        if (pos != null) {
            pos.setText(Trans.get(c, R.string.batch_export_btn_fmt, ad.checkedCount()));
        }
        android.widget.Button neu = dlg.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (neu != null) {
            neu.setText(Trans.get(c,
                    ad.checkedCount() == ad.getCount() ? R.string.batch_export_none
                            : R.string.batch_export_all));
        }
    }

    // ══ 导入侧 ═════════════════════════════════════════════════════════════

    /**
     * 一份待导入的源：要么是 SAF 给的（`uri`），要么是我们**从整包 zip 里解出来**的临时文件。
     * 两条路对上层完全一样（都能开出一个 {@link InputStream}）。
     */
    static final class Item {
        final Uri uri;
        final File file;
        final String name;

        private Item(Uri uri, File file, String name) {
            this.uri = uri;
            this.file = file;
            this.name = name;
        }

        static Item ofUri(Uri uri, String name) {
            return new Item(uri, null, name);
        }

        static Item ofFile(File f) {
            return new Item(null, f, f.getName());
        }

        InputStream open(Context ctx) throws IOException {
            if (file != null) return new FileInputStream(file);
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException(Trans.get(ctx, R.string.batch_err_open_fmt, name));
            return in;
        }
    }

    /** 目标名规划里的一条：源 + 落位名 + "这个名字现在是不是已经被占了" */
    static final class Entry {
        final Item item;
        final String name;
        final boolean exists;

        Entry(Item item, String name, boolean exists) {
            this.item = item;
            this.name = name;
            this.exists = exists;
        }
    }

    /**
     * 一批导入的**目标名规划**（纯函数，自检直接喂它）。
     *
     * <p>两件事：
     * <ol>
     *   <li><b>批内去重</b>：同一批里两份东西落到同一个名字 ⇒ 后一份**必须撤掉** ——
     *       否则它会把自己刚导进去的那一份覆盖掉（用户拿到一份，却以为是两份）；</li>
     *   <li><b>与槽里比对</b>：`exists` 直接决定"要不要问替换"。</li>
     * </ol>
     */
    static final class Plan {
        final List<Entry> entries = new ArrayList<>();
        /** 批内重名（只记名字，报告里说清楚） */
        final List<String> dup = new ArrayList<>();

        int conflicts() {
            int n = 0;
            for (Entry e : entries) if (e.exists) n++;
            return n;
        }
    }

    /** 目标名怎么算 —— 四个内容模块各自的名字规则（`safeName`）都不一样，所以由调用方给 */
    interface Namer {
        String nameFor(String display);
    }

    /** 见 {@link Plan}（纯函数：不碰盘，只看 `dir` 里有没有同名） */
    static Plan plan(File dir, List<Item> items, Namer namer) {
        Plan p = new Plan();
        List<String> used = new ArrayList<>();
        if (items == null) return p;
        for (Item it : items) {
            if (it == null) continue;
            String name = namer.nameFor(it.name);
            if (name == null || name.trim().isEmpty()) {
                p.dup.add(it.name);
                continue;
            }
            name = name.trim();
            if (used.contains(name)) {
                p.dup.add(name);
                continue;
            }
            used.add(name);
            File dest = dir == null ? null : new File(dir, name);
            p.entries.add(new Entry(it, name, dest != null && dest.exists()));
        }
        return p;
    }

    /** 一份导入的结果（三个出口：成功 / 跳过 / 失败） */
    static final class Outcome {
        boolean ok;
        boolean skip;
        String reason;
        /** 可选的一句备注（如"不是标准存档"）—— 导入**成功**时也能带 */
        String warn;

        static Outcome good() {
            Outcome o = new Outcome();
            o.ok = true;
            return o;
        }

        static Outcome good(String warn) {
            Outcome o = good();
            o.warn = warn;
            return o;
        }

        static Outcome skip(String why) {
            Outcome o = new Outcome();
            o.skip = true;
            o.reason = why;
            return o;
        }

        static Outcome fail(String why) {
            Outcome o = new Outcome();
            o.reason = why;
            return o;
        }
    }

    /**
     * 真正把一份导进去 —— **四个内容页各自的实现**（每个 ~15 行，见各自 Activity）。
     * ⚠️ 实现里**不许**自己判"要不要问同名"（那件事已经由 {@link #plan} 一次性做完，
     *   传进来的 `overwrite` 就是答案）。
     */
    interface Importer {
        Outcome apply(Context ctx, String name, InputStream in, boolean overwrite);
    }

    /** 批量导入的报告（人读正文 + 四个计数） */
    static final class Report {
        int ok;
        int replaced;
        final List<String> skipped = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        int total() {
            return ok + replaced + skipped.size() + failed.size();
        }

        String text(Context c) {
            StringBuilder sb = new StringBuilder();
            sb.append(Trans.get(c, R.string.batch_report_head_fmt, total())).append('\n');
            if (ok > 0) sb.append(Trans.get(c, R.string.batch_report_ok_fmt, ok)).append('\n');
            if (replaced > 0) {
                sb.append(Trans.get(c, R.string.batch_report_replaced_fmt, replaced)).append('\n');
            }
            if (!skipped.isEmpty()) {
                sb.append(Trans.get(c, R.string.batch_report_skipped_fmt, skipped.size()))
                        .append('\n');
                for (String s : skipped) sb.append("  ").append(s).append('\n');
            }
            if (!failed.isEmpty()) {
                sb.append(Trans.get(c, R.string.batch_report_failed_fmt, failed.size()))
                        .append('\n');
                for (String s : failed) sb.append("  ").append(s).append('\n');
            }
            for (String n : notes) sb.append(n).append('\n');
            return sb.toString();
        }
    }

    private static String line(Context c, String name, String why) {
        return Trans.get(c, R.string.batch_report_line_fmt, name, why == null ? "" : why);
    }

    /** 一个槽最多塞多少份（前两个之外就别再往里灌了） */
    private static final int MAX_TOTAL = 2000;

    /**
     * 跑一次批量导入（**四个页面的唯一实现**）：规划 → （必要时）问一次同名 → 逐份导 → 弹报告。
     *
     * @param dir          落位目录（`<槽>/mods` / `maps` / `saves` / `schematics`）
     * @param exts         认哪些后缀的条目算是"这一整包里的东西"（如 `{".msav"}`）
     * @param rootMarkers  **包根上出现它就说明"这是个包本身、不是一整包"**（模组用
     *                     {@link Mods#META_FILES}；地图 / 存档 / 蓝图传 null）
     * @param namer        目标名规则（四个模块各自的 `safeName`）
     * @param imp          单份导入（四个页面各自实现）
     * @param slotLabel    进度框里那句"往哪个槽里导"（用户能认出来的名字）
     * @param onDone       全部导完之后（报告弹出**之前**）在 UI 线程调一次 ——
     *                     列表页拿它重扫自己（否则用户看到的是"导进去了但列表还是旧的"）
     */
    static void runImport(final Activity a, final File dir, final List<Doc> docs,
                          final String[] exts, final String[] rootMarkers,
                          final Namer namer, final Importer imp, final String slotLabel,
                          final Runnable onDone) {
        if (docs == null || docs.isEmpty()) {
            toast(a, Trans.get(a, R.string.batch_import_nothing));
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(a,
                Trans.get(a, R.string.batch_import_progress_title),
                Trans.get(a, R.string.batch_import_progress_fmt, docs.size(), slotLabel), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final File work = workDir(a);
                final Report rep = new Report();
                List<Item> items = new ArrayList<>();
                for (Doc d : docs) {
                    if (items.size() >= MAX_ITEMS) break;
                    if (isArchiveName(d.name)) {
                        String why = null;
                        List<File> got = null;
                        InputStream in = null;
                        try {
                            in = d.open(a);
                            // null = "这不是一整包"（可能是模组包自己，也可能根本不是 zip）
                            List<String> names = bundleNames(in, exts, rootMarkers);
                            closeQuietly(in);
                            in = null;
                            if (names != null) {
                                in = d.open(a);
                                got = extractBundle(in, exts, work);
                            }
                        } catch (Throwable t) {
                            // ⚠️ 这里**不报失败**：不是 zip / 读不动 ⇒ 就当普通文件往下走
                            //   （真正的判据在各自的 `import*` 里，它的话比这里准）
                            why = Util.ioReason(a, t);
                            got = null;
                        } finally {
                            closeQuietly(in);
                        }
                        if (got != null) {
                            for (File f : got) items.add(Item.ofFile(f));
                            rep.notes.add(Trans.get(a, R.string.batch_note_unpacked_fmt,
                                    got.size(), d.name));
                            continue;
                        }
                        if (why != null) Log.i(TAG, "bundle probe skipped: " + d.name + " / " + why);
                    }
                    items.add(Item.ofUri(d.uri, d.name));
                }

                final Plan plan = plan(dir, items, namer);
                for (String d : plan.dup) {
                    rep.skipped.add(line(a, d, Trans.get(a, R.string.batch_skip_dup)));
                }
                final List<Entry> todo = new ArrayList<>();
                for (Entry e : plan.entries) {
                    if (todo.size() >= MAX_TOTAL) {
                        rep.skipped.add(line(a, e.name, Trans.get(a, R.string.batch_skip_too_many)));
                        continue;
                    }
                    todo.add(e);
                }
                final int conflicts = plan.conflicts();

                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (Util.dead(a)) {
                            cleanup(work);
                            return;
                        }
                        if (todo.isEmpty()) {
                            if (onDone != null) onDone.run();
                            alert(a, Trans.get(a, R.string.batch_report_title), rep.text(a));
                            cleanup(work);
                            return;
                        }
                        if (conflicts == 0) {
                            phaseB(a, todo, imp, rep, work, true, onDone);
                            return;
                        }
                        // ★ 有同名 ⇒ **只问一次**（这一批里撞了多少份、一共多少份都说清楚）
                        AlertDialog.Builder b = new AlertDialog.Builder(a)
                                .setTitle(R.string.batch_overwrite_title)
                                .setMessage(Trans.get(a, R.string.batch_overwrite_msg_fmt,
                                        conflicts, todo.size()))
                                .setPositiveButton(R.string.batch_overwrite_ok,
                                        new DialogInterface.OnClickListener() {
                                            @Override public void onClick(DialogInterface d, int w) {
                                                phaseB(a, todo, imp, rep, work, true, onDone);
                                            }
                                        })
                                .setNegativeButton(R.string.cancel,
                                        new DialogInterface.OnClickListener() {
                                            @Override public void onClick(DialogInterface d, int w) {
                                                phaseB(a, todo, imp, rep, work, false, onDone);
                                            }
                                        });
                        // ⚠️ 返回键 = 与"取消"同一条路（**不能**只是关窗：不导入的话要收尾 + 报数）
                        b.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) {
                                phaseB(a, todo, imp, rep, work, false, onDone);
                            }
                        });
                        b.show();
                    }
                });
            }
        }, "batch-import-plan").start();
    }

    /** 第二阶段：逐份导（`replace` = 用户对"同名"那个问题的回答） */
    private static void phaseB(final Activity a, final List<Entry> todo, final Importer imp,
                               final Report rep, final File work, final boolean replace,
                               final Runnable onDone) {
        final ProgressDialog pd = ProgressDialog.show(a,
                Trans.get(a, R.string.batch_import_progress_title),
                Trans.get(a, R.string.batch_import_running_fmt, todo.size()), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                applyAll(a, todo, imp, rep, replace);
                cleanup(work);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (Util.dead(a)) return;
                        if (onDone != null) onDone.run();     // 先让列表页重扫，再弹报告
                        alert(a, Trans.get(a, R.string.batch_report_title), rep.text(a));
                    }
                });
            }
        }, "batch-import").start();
    }

    /**
     * 逐份导的**唯一循环**（界面那条与 {@link #importSync} 共用）：
     * 成功 / 替换 / 跳过 / 失败四个出口，**一条都不许静默吞掉**
     * （批量最常见的坑就是"20 份导进去 18 份，另外 2 份什么都没说"）。
     */
    private static void applyAll(Context ctx, List<Entry> todo, Importer imp, Report rep,
                                 boolean replace) {
        if (todo == null) return;
        for (Entry e : todo) {
            if (!replace && e.exists) {
                rep.skipped.add(line(ctx, e.name, Trans.get(ctx, R.string.batch_skip_exists)));
                continue;
            }
            InputStream in = null;
            try {
                in = e.item.open(ctx);
                Outcome o = imp.apply(ctx, e.name, in, replace && e.exists);
                if (o == null) {
                    rep.failed.add(line(ctx, e.name, Trans.get(ctx, R.string.batch_fail_unknown)));
                    continue;
                }
                if (o.ok) {
                    if (e.exists) rep.replaced++;
                    else rep.ok++;
                    if (o.warn != null) rep.notes.add(line(ctx, e.name, o.warn));
                } else if (o.skip) {
                    rep.skipped.add(line(ctx, e.name, o.reason));
                } else {
                    rep.failed.add(line(ctx, e.name, o.reason));
                }
            } catch (Throwable t) {
                Log.w(TAG, "batch import item failed: " + e.name, t);
                rep.failed.add(line(ctx, e.name, Util.ioReason(ctx, t)));
            } finally {
                closeQuietly(in);
            }
        }
    }

    // ══ 四个内容页各自的入口（页面 / dev 口 / 自检共用这一份）════════════════

    /** 四类内容的编号（dev 口用字符串 key，见 {@link Kind#key}） */
    static final int KIND_MAPS = 0;
    static final int KIND_SAVES = 1;
    static final int KIND_BLUEPRINTS = 2;
    static final int KIND_MODS = 3;

    /** dev 口的字符串 key（**ASCII，不进资源** —— 它是命令的一部分，不是给用户看的文案） */
    static int kindOfKey(String key) {
        if ("saves".equals(key)) return KIND_SAVES;
        if ("blueprints".equals(key)) return KIND_BLUEPRINTS;
        if ("mods".equals(key)) return KIND_MODS;
        return KIND_MAPS;
    }

    static String keyOf(int kind) {
        if (kind == KIND_SAVES) return "saves";
        if (kind == KIND_BLUEPRINTS) return "blueprints";
        if (kind == KIND_MODS) return "mods";
        return "maps";
    }

    /**
     * **一类内容在批量导入里需要的全部参数** —— 收成一个对象，是为了让"从哪里发起"
     * （页面 / dev 口 / 自检）与"哪一类内容"正交：驱动逻辑只有 {@link #runImport} 一份，
     * 页面只负责选文件、dev 口只负责把路径变成 {@link Doc}。
     */
    static final class Kind {
        final String key;
        final File dir;
        final String[] exts;
        final String[] rootMarkers;
        final Namer namer;
        final Importer imp;

        Kind(String key, File dir, String[] exts, String[] rootMarkers, Namer namer, Importer imp) {
            this.key = key;
            this.dir = dir;
            this.exts = exts;
            this.rootMarkers = rootMarkers;
            this.namer = namer;
            this.imp = imp;
        }
    }

    static Kind kindOf(final Context ctx, final String slot, int kind) {
        if (kind == KIND_MAPS) {
            final File dir = new File(Data.dirOf(ctx, slot), "maps");
            return new Kind("maps", dir, new String[]{".msav"}, null, new Namer() {
                @Override public String nameFor(String d) {
                    return MapFiles.safeName(d);
                }
            }, new Importer() {
                @Override public Outcome apply(Context c, String name, InputStream in,
                                               boolean overwrite) {
                    MapFiles.Result r = MapFiles.importMap(c, dir, name, in, overwrite,
                            MapFiles.trashDirOf(c));
                    if (r.ok) return Outcome.good();
                    // ★ 「这其实是一份存档」在批量里**不能弹命名框**（N 份弹 N 次）⇒ 跳过并说明
                    //   （单份那条路仍然会问名字，见 MapsActivity）
                    if (r.saveNoName) {
                        MapFiles.discard(r.part);
                        return Outcome.skip(Trans.get(c, R.string.batch_import_map_issave));
                    }
                    return Outcome.fail(r.error);
                }
            });
        }
        if (kind == KIND_SAVES) {
            final File dir = Data.savesDirOf(ctx, slot);
            return new Kind("saves", dir, new String[]{".msav"}, null, new Namer() {
                @Override public String nameFor(String d) {
                    return Msav.safeName(d);
                }
            }, new Importer() {
                @Override public Outcome apply(Context c, String name, InputStream in,
                                               boolean overwrite) {
                    Msav.Stage st = null;
                    try {
                        st = Msav.stage(c, in, name, slot);
                        String warn = st.zlib ? null
                                : Trans.get(c, R.string.batch_import_save_nonstandard);
                        Msav.commit(c, st);
                        return warn == null ? Outcome.good() : Outcome.good(warn);
                    } catch (Throwable t) {
                        if (st != null) Msav.discard(st);       // 别在 saves/ 里留孤儿 .part
                        return Outcome.fail(Util.ioReason(c, t));
                    }
                }
            });
        }
        if (kind == KIND_BLUEPRINTS) {
            final File dir = BlueprintFiles.dirOf(ctx, slot);
            return new Kind("blueprints", dir, new String[]{".msch"}, null, new Namer() {
                @Override public String nameFor(String d) {
                    return BlueprintFiles.safeName(d);
                }
            }, new Importer() {
                @Override public Outcome apply(Context c, String name, InputStream in,
                                               boolean overwrite) {
                    BlueprintFiles.Result r = BlueprintFiles.importSchem(c, dir, name, in, overwrite,
                            BlueprintFiles.trashDirOf(c));
                    if (r.ok) return Outcome.good();
                    // ⚠️ 同单份那条路：解析失败给**码 → 白话**（`Msch` 只给码）
                    String why = r.broken != null ? MschText.reason(c, r.broken) : r.error;
                    return Outcome.fail(why);
                }
            });
        }
        final File dir = new File(Data.dirOf(ctx, slot), "mods");
        return new Kind("mods", dir, new String[]{".jar", ".zip"}, Mods.META_FILES, new Namer() {
            @Override public String nameFor(String d) {
                return d;       // 模组包的名字就是它的文件名（规则在 Mods.checkPackName 里）
            }
        }, new Importer() {
            @Override public Outcome apply(Context c, String name, InputStream in,
                                           boolean overwrite) {
                Mods.PackResult pr = Mods.importPackage(c, dir, name, in, overwrite,
                        Mods.trashDirOf(c));
                if (pr.ok) return Outcome.good();
                return Outcome.fail(ModsText.packReason(c, pr));
            }
        });
    }

    /** 界面那条路：多选 + 一次性同名确认 + 报告（四个页面各一句） */
    static void runImport(Activity a, String slot, List<Doc> docs, int kind, Runnable onDone) {
        Kind k = kindOf(a, slot, kind);
        runImport(a, k.dir, docs, k.exts, k.rootMarkers, k.namer, k.imp, slot, onDone);
    }

    /**
     * **同步**跑一批（没有界面）：dev 口与自检用。
     * 与界面那条路的差别只有两点：不问（`replace` 由调用方定）、不弹报告（返回 {@link Report}）。
     * ⚠️ 必须在**后台线程**调（它会真的搬几十 MB）。
     */
    static Report importSync(Context ctx, String slot, List<Doc> docs, int kind, boolean replace) {
        Kind k = kindOf(ctx, slot, kind);
        Report rep = new Report();
        File work = workDir(ctx);
        try {
            List<Item> items = new ArrayList<>();
            for (Doc d : docs) {
                if (items.size() >= MAX_ITEMS) break;
                if (isArchiveName(d.name)) {
                    List<File> got = null;
                    InputStream in = null;
                    try {
                        in = d.open(ctx);
                        if (bundleNames(in, k.exts, k.rootMarkers) != null) {
                            closeQuietly(in);
                            in = d.open(ctx);
                            got = extractBundle(in, k.exts, work);
                        }
                    } catch (Throwable ignored) {
                        got = null;
                    } finally {
                        closeQuietly(in);
                    }
                    if (got != null) {
                        for (File f : got) items.add(Item.ofFile(f));
                        rep.notes.add(Trans.get(ctx, R.string.batch_note_unpacked_fmt,
                                got.size(), d.name));
                        continue;
                    }
                }
                items.add(Item.ofUri(d.uri, d.name));
            }
            Plan plan = plan(k.dir, items, k.namer);
            for (String d : plan.dup) {
                rep.skipped.add(line(ctx, d, Trans.get(ctx, R.string.batch_skip_dup)));
            }
            // ⚠️ 同名那几份的"跳过"由 {@link #applyAll} 记（`replace == false` 时它自己会跳）
            //   —— 这里**不许**再记一遍，否则报告里同一份会被数两次（自检当场抓过）
            applyAll(ctx, plan.entries, k.imp, rep, replace);
        } finally {
            cleanup(work);
        }
        return rep;
    }

    /** dev 口用：把一批**应用读得到的**路径变成待导入条目（`file://` 在 ContentResolver 上照样能开） */
    static List<Doc> docsFromFiles(List<File> fs) {
        List<Doc> out = new ArrayList<>();
        if (fs == null) return out;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            out.add(new Doc(Uri.fromFile(f), f.getName()));
        }
        return out;
    }

    // ══ 整包 zip（我们导出的那种）═══════════════════════════════════════════

    /** 认得出是"压缩包"的名字（`.zip` / `.jar`）；其余一律当普通文件走单份导入 */
    static boolean isArchiveName(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".jar");
    }

    /**
     * **这是不是"我们导出的一整包"**？返回包内命中的条目名（原样，含路径）；不是就返回 null。
     *
     * <p>判据两条（缺一不可）：
     * <ol>
     *   <li>至少有一条后缀命中的文件（`.msav` / `.msch` / `.jar`…）；</li>
     *   <li>包**根上**没有模组说明文件（{@link Mods#META_FILES}）—— 否则那是一个模组包本身
     *       （用户"选一个 zip 导入模组"是最常见的动作，判错就把模组拆成一堆碎片）。</li>
     * </ol>
     * ★ 一次顺序读（`ZipInputStream`），**不落地**：判完了要么重开流去解，要么当普通文件导。
     */
    static List<String> bundleNames(InputStream in, String[] exts, String[] rootMarkers) {
        if (in == null || exts == null || exts.length == 0) return null;
        java.util.zip.ZipInputStream zin = null;
        try {
            zin = new java.util.zip.ZipInputStream(in);
            List<String> hits = new ArrayList<>();
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String raw = e.getName() == null ? "" : e.getName();
                String flat = raw.replace('\\', '/');
                if (e.isDirectory() || flat.endsWith("/")) continue;
                int slash = flat.lastIndexOf('/');
                String base = slash < 0 ? flat : flat.substring(slash + 1);
                if (slash < 0 && rootMarkers != null) {
                    for (String m : rootMarkers) {
                        if (m != null && m.equalsIgnoreCase(base)) return null;   // 包本身，不是一整包
                    }
                }
                String lower = flat.toLowerCase(Locale.ROOT);
                for (String x : exts) {
                    if (x != null && lower.endsWith(x.toLowerCase(Locale.ROOT))) {
                        hits.add(flat);
                        break;
                    }
                }
            }
            return hits.isEmpty() ? null : hits;
        } catch (Throwable t) {
            return null;        // 不是 zip（存档就是 zlib 流）/ 坏了 ⇒ 交给单份导入去报准确的话
        } finally {
            closeQuietly(zin);
        }
    }

    /**
     * 把整包 zip 里**后缀命中的条目**解到 `workDir`，返回落盘的文件。
     *
     * 🔴 **只取条目名的最后一段**（`flat` 的 basename）：zip 里的 `../` / 绝对路径是经典的
     *   路径穿越（我们写完还要拿它当 `import*` 的输入），宁可把 `a/b/x.msav` 落成 `x.msav`。
     */
    static List<File> extractBundle(InputStream in, String[] exts, File workDir) throws IOException {
        List<File> out = new ArrayList<>();
        if (in == null || workDir == null) return out;
        if (!workDir.exists() && !workDir.mkdirs() && !workDir.isDirectory()) {
            throw new IOException("mkdir failed: " + workDir);
        }
        java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in);
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String raw = e.getName() == null ? "" : e.getName();
                String flat = raw.replace('\\', '/');
                if (e.isDirectory() || flat.endsWith("/")) continue;
                String lower = flat.toLowerCase(Locale.ROOT);
                boolean hit = false;
                for (String x : exts) {
                    if (x != null && lower.endsWith(x.toLowerCase(Locale.ROOT))) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) continue;
                int slash = flat.lastIndexOf('/');
                String base = Exporter.flatName(slash < 0 ? flat : flat.substring(slash + 1));
                File dst = uniqueIn(workDir, base);
                FileOutputStream fo = new FileOutputStream(dst);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = zin.read(buf)) > 0) fo.write(buf, 0, n);
                } finally {
                    fo.close();
                }
                out.add(dst);
            }
        } finally {
            closeQuietly(zin);
        }
        return out;
    }

    /** `dir` 下没被占用的名字（`x.msav` → `x (2).msav`），与 {@link Exporter#uniqueName} 同一套规则 */
    private static File uniqueIn(File dir, String base) {
        File f = new File(dir, base);
        if (!f.exists()) return f;
        int dot = base.lastIndexOf('.');
        String head = dot > 0 ? base.substring(0, dot) : base;
        String tail = dot > 0 ? base.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            f = new File(dir, head + " (" + i + ")" + tail);
            if (!f.exists()) return f;
        }
        return new File(dir, head + "-" + System.nanoTime() + tail);
    }

    /** 一次批量导入用的临时目录（内部缓存；用完 {@link #cleanup} 删掉） */
    static File workDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "batch-" + System.currentTimeMillis());
        d.mkdirs();
        return d;
    }

    static void cleanup(File work) {
        if (work != null) Data.deleteTree(work);
    }

    // ══ 小工具 ═════════════════════════════════════════════════════════════

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static void toast(Activity a, String msg) {
        if (Util.dead(a)) return;
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }

    private static void alert(Activity a, String title, String msg) {
        if (Util.dead(a)) return;
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
