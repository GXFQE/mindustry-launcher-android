package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 槽的**导入 / 导出**（走 SAF）—— 从 {@link SavesActivity} 搬出来的第二块
 * （第一块是对话框类的 {@link SlotOps}）。
 *
 * 🔴 为什么搬（2026-10-03 用户：「也搬过去」）：这些操作原来只实现在存档页，
 *   槽二级页面靠"带 extra 派发 + `REORDER_FRONT`"复用 ⇒ 用户在槽页面点「导出存档」
 *   会**先跳到存档页**，选完文件还停在那一页。搬到这里之后，槽页面**自己发起、自己收结果**，
 *   全程不跳页（实现仍然只有一份）。
 *
 * ★ 用法（调用方页面）：
 * ```java
 * SlotIo.importSave(this, slot);                       // 发起
 * // 自己的 onActivityResult 里：
 * if (SlotIo.onActivityResult(this, req, res, data, host)) return;
 * ```
 * ⚠️ SAF 的回调**必然回到发起它的那个 Activity**，所以必须由调用方转发 —— 这也是
 *    `onActivityResult` 做成静态方法而不是自带 Activity 的原因。
 *
 * ★ 跨 `onActivityResult` 的"待办目标"（选完文件才知道要落到哪）存在**静态字段**里：
 *   与原来 SavesActivity 的实例字段是同一套语义（都只在内存里、进程被杀就丢），
 *   ⚠️ 但**每次发起前必须重设**、**回调进来先清空**（`REQ_*` 三条分支都这么做）——
 *   否则用户取消后残留的目标会被下一次选择"接上"，文件落进上一个槽。
 */
final class SlotIo {

    static final int REQ_MSAV = 51;
    /** 导出：单文件与整槽共用一个请求码，靠 {@link #sExportKind} 分派 */
    static final int REQ_EXPORT = 52;
    static final int REQ_ZIP = 53;

    private static final int EXPORT_NONE = 0;
    private static final int EXPORT_FILE = 1;
    private static final int EXPORT_SLOT = 2;

    /** 调用方页面：导入/导出完成后刷新自己 */
        /** `saves/` 下只认文件（目录会被游戏用来放别的，不列） */
    private static final FileFilter ONLY_FILES = new FileFilter() {
        @Override public boolean accept(File f) {
            return f.isFile() && !f.getName().startsWith(".");
        }
    };

    /** 存档列表的排序（名字升序、忽略大小写）—— 导出列表与「看每份存档」**共用一份** */
    private static final Comparator<File> NAME_ORDER = new Comparator<File>() {
        @Override public int compare(File x, File y) {
            return x.getName().compareToIgnoreCase(y.getName());
        }
    };

    // 待办目标（跨 onActivityResult 存活；每次发起前重设、回调进来先清）
    private static String sMsavTarget;
    private static String sZipSlot;
    private static File sZipTmp;
    private static SlotZip.Info sZipInfo;
    private static int sExportKind = EXPORT_NONE;
    private static File sExportSrc;
    private static String sExportSlot;
    private static List<File> sExportRoots;

    private SlotIo() {}

    /**
     * 转发 `onActivityResult`。返回 true = 这个请求码是我们的、已经处理（调用方直接 return）。
     */
    static boolean onActivityResult(Activity a, int req, int res, Intent data, SlotOps.Host h) {
        if (req == REQ_MSAV) {
            // ★ 与 REQ_ZIP / REQ_EXPORT 同一条纪律：无论成败**先清目标槽**。
            //   否则用户取消后残留的槽会被下一次选择"接上"，文件落进上一个槽里。
            final String slot = sMsavTarget;
            sMsavTarget = null;
            if (res != Activity.RESULT_OK || data == null || data.getData() == null
                    || slot == null) {
                return true;
            }
            Uri uri = data.getData();
            importMsav(a, uri, queryDisplayName(a, uri), slot, h);
            return true;
        }
        if (req == REQ_ZIP) {
            final String slot = sZipSlot;
            sZipSlot = null;
            if (res != Activity.RESULT_OK || data == null || data.getData() == null
                    || slot == null) {
                return true;
            }
            readZipThenConfirm(a, data.getData(), queryDisplayName(a, data.getData()), slot, h);
            return true;
        }
        if (req == REQ_EXPORT) {
            // ★ 无论成败都先清状态：用户取消之后残留的导出目标会被下一次选择"接上"，
            //   变成导出了上一个槽的东西。
            final int kind = sExportKind;
            final File src = sExportSrc;
            final String slot = sExportSlot;
            final List<File> roots = sExportRoots;
            sExportKind = EXPORT_NONE;
            sExportSrc = null;
            sExportSlot = null;
            sExportRoots = null;
            if (res != Activity.RESULT_OK || data == null || data.getData() == null) return true;
            Uri uri = data.getData();
            if (kind == EXPORT_FILE && src != null) doExportFile(a, uri, src);
            else if (kind == EXPORT_SLOT && slot != null) doExportSlot(a, uri, slot, roots);
            return true;
        }
        return false;
    }


    // ── 导出到共享存储（F6） ──────────────────────────────────────────────

    /**
     * F6 ①：单文件导出（把这个槽 `saves/` 里的某个存档拿出来）。
     *
     * ★ 文件名**用原始名**（`f.getName()`）—— 存档名就是玩家在游戏里看到的那个名字
     *   （见 {@link Msav#safeName} 的注释）；导出时绝不能"安全化"成 ASCII，
     *   否则用户在文件管理器里看到一堆拼音/下划线，根本认不出哪份是哪份。
     *
     * ⚠️ 选择列表用 setItems ⇒ **不能再 setMessage**（两者并存会让列表整体不渲染，见 slotOps 注释）。
     */
    static void exportSave(Activity a, final Data.Slot s) {
        // ★ 用 dirOf 手拼 saves/，**不要用 Data.savesDirOf** —— 后者会 mkdirs，
        //   于是"只是打开看看有没有存档"这个只读动作会把空目录造出来，
        //   进而把空槽的导出判定污染成"有内容"（本轮真机实测踩到）。
        File dir = new File(Data.dirOf(a, s.name), "saves");
        final File[] fs = dir.listFiles(ONLY_FILES);
        if (fs == null || fs.length == 0) {
            toast(a, a.getString(R.string.export_no_save_fmt, s.name));
            return;
        }
        Arrays.sort(fs, NAME_ORDER);
        // ★ 每条一个**卡片**：第一行 = 文件名，第二行 = 大小 ·「这是什么存档」
        final String[] titles = new String[fs.length];
        final String[] subs = new String[fs.length];
        for (int i = 0; i < fs.length; i++) {
            titles[i] = fs[i].getName();
            subs[i] = Util.formatSize(fs[i].length());
        }
        // ★★ 元数据**后台渐进填充**、**不设上限**（用户问过「存档太多会不会出问题」——
        //   会：原来全在 UI 线程读完才弹窗、且留了 60 份上限）
        View listView = a.getLayoutInflater().inflate(R.layout.dialog_msav_list, null);
        final android.widget.ListView lv =
                (android.widget.ListView) listView.findViewById(R.id.msav_list);
        final MsavListAdapter adapter = new MsavListAdapter(a, titles, subs);
        lv.setAdapter(adapter);
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(R.string.export_pick_title_fmt)
                .setView(listView)
                .setNegativeButton(R.string.cancel, null)
                .create();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= fs.length) return;
                startExportSave(a, fs[pos]);
                dlg.dismiss();
            }
        });
        dlg.show();
        final boolean[] alive = {true};
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                alive[0] = false;
            }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < fs.length && alive[0]; i++) {
                    final int idx = i;
                    final String line = msavLine(a, MsavMeta.read(fs[idx]));
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (!alive[0]) return;
                            String size = Util.formatSize(fs[idx].length());
                            adapter.setSub(idx, line.isEmpty() ? size : (size + " · " + line));
                            // ★ 2026-10-04：只刷新**这一行**（原来调 notifyDataSetChanged()，
                            //   整片列表每补一份就重排一次 —— 存档多的槽一边滚一边跳）。
                            //   口径与 MapsActivity 的缩略图那条一致，实现收口在适配器里。
                            adapter.refreshSub(lv, idx);
                        }
                    });
                }
            }
        }, "msav-meta").start();
    }

    /**
     * 只列「读不出来」的那几份存档 —— 槽页副标题那句「⚠ N 份读不出来」的落点。
     *
     * ★ 为什么要有它：副标题只能说"有几份"，用户下一步要知道**是哪几份、为什么**（原因在
     *   {@link MsavMeta#error}）。原来这件事只能靠点「导出存档」的列表顺带看到 ——
     *   用"导出"去查健康状态，路子不对（本仓的老毛病：功能对了、入口不对）。
     * ★ 判据与槽页副标题**同一个** {@link MsavMeta#summarize}，不是第二份实现。
     * ⚠️ 纯只读列表：**点行不做任何事**，也不提供删除 —— 存档是用户的东西，
     *   删只走游戏自己的界面或明确的操作卡。
     */
    static void showUnreadable(final Activity a, final String slotName) {
        final File dir = new File(Data.dirOf(a, slotName), "saves");
        final File[] fs = dir.listFiles();
        new Thread(new Runnable() {
            @Override public void run() {
                final MsavMeta.Saves sm = MsavMeta.summarize(fs);
                final List<MsavMeta.Bad> bad = sm.unreadable;
                final String[] titles = new String[bad.size()];
                final String[] subs = new String[bad.size()];
                for (int i = 0; i < bad.size(); i++) {
                    titles[i] = bad.get(i).file.getName();
                    subs[i] = msavLine(a, bad.get(i).meta);
                }
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // ⚠️ 只判 isFinishing() 挡不住转屏（那一刻它是 false）——
                        //    统一走 Util.dead()，见那里的注释。
                        if (Util.dead(a)) return;
                        if (titles.length == 0) {
                            // 扫盘之后发现其实都读得出来（用户刚在游戏里覆盖保存过）
                            toast(a, a.getString(R.string.msav_all_ok));
                            return;
                        }
                        View listView = a.getLayoutInflater().inflate(R.layout.dialog_msav_list, null);
                        android.widget.ListView lv =
                                (android.widget.ListView) listView.findViewById(R.id.msav_list);
                        lv.setAdapter(new MsavListAdapter(a, titles, subs));
                        new AlertDialog.Builder(a)
                                .setTitle(a.getString(R.string.msav_bad_list_title_fmt, titles.length))
                                .setView(listView)
                                .setPositiveButton(R.string.close, null)
                                .show();
                    }
                });
            }
        }, "msav-unreadable").start();
    }

    /**
     * ★ 2026-10-05：**只读的存档列表** —— 点一份看它是什么图、玩了多久。
     *
     * 起因：这两件事原来只有两条路能看见 ——
     *   ① 点「导出存档」列表的第二行（**用"导出"去查档案，路子不对**）；
     *   ② 地图详情页（那是**地图**，不是存档）。
     * ⇒ 补一个只读入口，点一份弹详情（{@link MsavText#detail} 那一份口径），
     *   详情里带「导出这一份…」出口 —— 出口是便利，不是这个列表的主题。
     *
     * ⚠️ 与 {@link #showUnreadable} 同一条纪律：**只读，不提供删除**
     *   （存档是用户的东西，删只走游戏自己的界面）。
     * ⚠️ 这份列表没有份数上限，副标题照样**后台渐进填充 + 只刷那一行**（见 exportSave 的注释）。
     */
    static void showSaves(final Activity a, final String slotName) {
        final File dir = new File(Data.dirOf(a, slotName), "saves");
        final File[] fs = dir.listFiles(ONLY_FILES);
        if (fs == null || fs.length == 0) {
            toast(a, a.getString(R.string.export_no_save_fmt, slotName));
            return;
        }
        Arrays.sort(fs, NAME_ORDER);
        final String[] titles = new String[fs.length];
        final String[] subs = new String[fs.length];
        for (int i = 0; i < fs.length; i++) {
            titles[i] = fs[i].getName();
            subs[i] = Util.formatSize(fs[i].length());
        }
        View listView = a.getLayoutInflater().inflate(R.layout.dialog_msav_list, null);
        TextView head = (TextView) listView.findViewById(R.id.msav_head);
        if (head != null) {
            head.setVisibility(View.VISIBLE);
            head.setText(a.getString(R.string.saves_list_head_fmt, fs.length));
        }
        final android.widget.ListView lv =
                (android.widget.ListView) listView.findViewById(R.id.msav_list);
        final MsavListAdapter adapter = new MsavListAdapter(a, titles, subs);
        lv.setAdapter(adapter);
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.saves_list_title_fmt, slotName))
                .setView(listView)
                .setNegativeButton(R.string.close, null)
                .create();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= fs.length) return;
                showSaveDetail(a, fs[pos]);
            }
        });
        dlg.show();
        final boolean[] alive = {true};
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                alive[0] = false;
            }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < fs.length && alive[0]; i++) {
                    final int idx = i;
                    final String line = msavLine(a, MsavMeta.read(fs[idx]));
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (!alive[0]) return;
                            String size = Util.formatSize(fs[idx].length());
                            adapter.setSub(idx, line.isEmpty() ? size : (size + " · " + line));
                            adapter.refreshSub(lv, idx);
                        }
                    });
                }
            }
        }, "msav-list").start();
    }

    /**
     * 一份存档的详情（**只读**）：文件名当标题，正文是「地图名 + 那几行」，
     * 正按钮给「导出这一份…」的直接出口。
     *
     * ★ 为什么正文自己拼一句"地图："：存档的 `displayName()` 是**它是从哪张图上存下来的**
     *   （战役存档就是当时的地区名），而 {@link MsavText#detail} 是地图页与存档页**共用**的
     *   那一段（地图页的标题里已经有真名，所以那边不能重复加）。⇒ 这里加、那边不加。
     * ⚠️ 读 meta 走后台线程（大的存档要开流），与列表那条同一个理由。
     */
    private static void showSaveDetail(final Activity a, final File f) {
        new Thread(new Runnable() {
            @Override public void run() {
                final MsavMeta m = MsavMeta.read(f);
                final String body = saveDetailText(a, m);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(a)) return;
                        new AlertDialog.Builder(a)
                                .setTitle(a.getString(R.string.save_detail_title_fmt, f.getName()))
                                .setMessage(body)
                                .setPositiveButton(R.string.save_detail_export,
                                        new DialogInterface.OnClickListener() {
                                            @Override public void onClick(DialogInterface d, int w) {
                                                startExportSave(a, f);
                                            }
                                        })
                                .setNegativeButton(R.string.close, null)
                                .show();
                    }
                });
            }
        }, "msav-detail").start();
    }

    /**
     * 存档详情的正文（**唯一实现**，自检直接喂它 ⇒ 与界面走同一份格式化）。
     * ⚠️ 色码要去掉（`[gold]foo` 在纯文本里是噪声）—— 判据复用 {@link Mods#stripColors}。
     * ⚠️ 参数是 {@link android.content.Context} 而不是 Activity：本方法只用 `getString`
     *   （自检手里只有 Context —— 它要是 Activity 就只能靠真机看，那样等于没有判据）。
     */
    static String saveDetailText(android.content.Context a, MsavMeta m) {
        StringBuilder sb = new StringBuilder();
        if (m != null && m.ok) {
            String n = Mods.stripColors(m.displayName()).trim();
            if (!n.isEmpty()) sb.append(a.getString(R.string.msav_lbl_map_fmt, n)).append('\n');
        }
        sb.append(MsavText.detail(a, m));
        return sb.toString();
    }

    /**
     * 单份存档的导出（**唯一实现**）：导出列表与存档详情两处共用。
     * ★ 待办目标（{@link #sExportSrc}）在这里写、在 `onActivityResult` 的 REQ_EXPORT 分支里清 ——
     *   "每次发起前重设、回调进来先清空"那条纪律只有这一处落点。
     */
    private static void startExportSave(Activity a, File f) {
        sExportKind = EXPORT_FILE;
        sExportSrc = f;
        sExportSlot = null;
        a.startActivityForResult(Intent.createChooser(
                Exporter.createDoc(f.getName(), "application/octet-stream"),
                a.getString(R.string.chooser_export)), REQ_EXPORT);
    }

    /** 存档行第二行（只此一处）：口径在 {@link MsavText#shortLine} */
    private static String msavLine(Activity a, MsavMeta m) {
        if (m == null || !m.ok) {
            // ★ 原因走 `MsavText.userReason()`（异常类名要翻成白话），不是原始的 `error`（那是给排查看的）
            return m == null ? "" : a.getString(R.string.msav_unreadable_fmt, MsavText.userReason(a, m));
        }
        return MsavText.shortLine(a, m, true);
    }

    /**
     * F6 ②：整槽导出（打包成 zip，换机迁移用）。
     *
     * 口径 = {@link Data#contentRoots}：**槽里除排除项以外的全部内容**
     * （排除名单见 {@link Data#SLOT_EXCLUDE} —— `config.json`/`import`/`natives`
     * 是 HUB 自己的残留，`tmp`/`cache` 是游戏每次启动都会重建的）。
     * ★ **F19 起这条口径与「备份」是同一份实现** —— 理由见 {@link Data#SLOT_EXCLUDE}
     *   的注释（模组会把用户数据放在数据根顶层，白名单式枚举必然漏）。
     *
     * ⚠️ 游戏正在跑时**只提示不拦截**：导出是纯只读，最坏情况是拿到一份半写状态的存档；
     *    而"必须先退游戏"会让只想备份一下的人平白多一步。
     *    （对比：备份 / 恢复是**写**操作，必须拦，那条纪律见类头注释。）
     */
    static void exportSlot(Activity a, final Data.Slot s) {
        final List<File> roots = Data.contentRoots(a, s.name);
        if (roots.isEmpty()) {
            // ★ 拦在 SAF **之前**。放到后面的话用户已经点了"保存"，
            //   系统会先建出一个 0 字节的 zip 我们才报错 —— 白留垃圾还让人以为导出过。
            //   文案刻意简短：Toast 只有两行，塞清单会被截断（实测）。
            toast(a, a.getString(R.string.export_slot_empty_fmt));
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.export_slot_title_fmt, s.name))
                .setMessage(a.getString(R.string.export_slot_msg_fmt,
                        Data.gameAlive(a) ? a.getString(R.string.export_slot_playing) : ""))
                .setPositiveButton(R.string.export_ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sExportKind = EXPORT_SLOT;
                        sExportSlot = s.name;
                        sExportRoots = roots;
                        sExportSrc = null;
                        a.startActivityForResult(Intent.createChooser(
                                Exporter.createDoc(suggestZipName(s.name), "application/zip"),
                                a.getString(R.string.chooser_export)), REQ_EXPORT);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 建议文件名：`mdt-<槽名>-20261001-1907.zip`（槽名保留原样；它已过 Data.sanitizeSlot） */
    private static String suggestZipName(String slot) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        String n = slot == null ? "" : slot.trim();
        // EXTRA_TITLE 支持非 ASCII，但把文件系统层面非法的字符先剔掉更稳
        n = n.replace('/', '_').replace('\\', '_').replace(':', '_')
             .replace('*', '_').replace('?', '_').replace('"', '_')
             .replace('<', '_').replace('>', '_').replace('|', '_').trim();
        if (n.isEmpty()) n = "slot";
        return "mdt-" + n + "-" + stamp + ".zip";
    }

    /**
     * ⚠️ F6c 起：要打包的条目清单**搬到了数据层** {@link Data#contentRoots}，
     * 口径 =「全部内容 − 排除名单」（{@link Data#SLOT_EXCLUDE}）。
     * ★ F19 起这份口径**备份也在用**（用户："备份槽不要用白名单，有的模组会乱放配置文件"）
     *   ⇒ 从此"导出有、备份没有"这类分叉在结构上不可能发生。
     * 这里不保留任何"本地口径"，否则两处早晚会分叉。
     */

    private static void doExportFile(final Activity a, final Uri uri, final File src) {
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                long n = 0;
                try {
                    n = Exporter.writeFile(a, uri, src);
                } catch (Exception e) {
                    err = Util.ioReason(a, e);
                    android.util.Log.w("MDTLauncher", "export file failed", e);
                }
                final String fe = err;
                final long fn = n;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (fe != null) {
                            alert(a, a.getString(R.string.export_failed), fe);
                            return;
                        }
                        toast(a, a.getString(R.string.export_done_fmt,
                                src.getName(), Util.formatSize(fn)));
                    }
                });
            }
        }, "export-file").start();
    }

    private static void doExportSlot(final Activity a, final Uri uri, final String slot, final List<File> roots) {
        if (roots == null || roots.isEmpty()) {
            alert(a, a.getString(R.string.export_failed),
                    a.getString(R.string.export_slot_empty_fmt));
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(a,
                a.getString(R.string.export_progress_title),
                a.getString(R.string.export_progress_msg_fmt, slot), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                Exporter.Result r = null;
                try {
                    r = Exporter.zipTo(a, uri,
                            Data.dirOf(a, slot), roots);
                } catch (Exception e) {
                    err = Util.ioReason(a, e);
                    android.util.Log.w("MDTLauncher", "export slot failed", e);
                }
                final String fe = err;
                final Exporter.Result fr = r;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (fe != null) {
                            alert(a, a.getString(R.string.export_failed), fe);
                            return;
                        }
                        toast(a, a.getString(R.string.export_slot_done_fmt,
                                fr.files, Util.formatSize(fr.outBytes)));
                    }
                });
            }
        }, "export-slot").start();
    }

    /*
     * ★ 2026-10-06（第 115 轮第五批）：这里原来有个 `msgOf(Exception)`（`getMessage()`，
     *   空则 `String.valueOf(e)`），五个调用点全都把它当**弹窗正文** ⇒ 用户会看到
     *   `java.io.IOException: write failed: ENOSPC (No space left on device)` 这种句子。
     *   ⇒ 已删掉，统一走 {@link Util#ioReason}（认识的 errno 翻白话、我们自己的文案原样透传）。
     */

    /** 目标槽是否正被游戏占用（占用了就不许往里写，除非是纯读的导出） */
    private static boolean blockedByGame(Activity a, Data.Slot s) {
        if (s.active && Data.gameAlive(a)) {
            alert(a, a.getString(R.string.game_busy_title),
                    a.getString(R.string.game_busy_msg_fmt, s.name));
            return true;
        }
        return false;
    }

    // ── .msav 迁移 ────────────────────────────────────────────────────────

    /**
     * F15：槽菜单入口 —— 目标槽就是用户刚点的那个，**跳过选槽页**直接挑文件。
     * 这是"导入导出入口同步"的核心：从槽进来时槽已经确定了，再问一次既多余又打断。
     *
     * ★ F15b：原先还有一个"存档页入口"（先选槽页 → 再挑文件）。它与槽菜单这条
     *   是同一件事，造成同一界面上两个入口 ⇒ 已随 UI 一起移除，见 activity_saves.xml。
     */
    static void importSave(Activity a, final Data.Slot s) {
        if (blockedByGame(a, s)) return;
        sMsavTarget = s.name;
        pickMsavFile(a);
    }

    /** 挑 .msav（两条入口共用；目标槽必须已经写进 sMsavTarget） */
    private static void pickMsavFile(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // */*：各家文件管理器对 .msav 的 MIME 报得五花八门，限死会让用户选不中自己的存档
        i.setType("*/*");
        a.startActivityForResult(
                Intent.createChooser(i, a.getString(R.string.chooser_pick_msav)), REQ_MSAV);
    }

    /**
     * 把选中的文件流拷进目标槽的 saves/（落盘与判据全在 {@link Msav}），
     * 然后弹**存档名确认框**。
     *
     * ★ F6c 起多了"改名"这一步，理由是硬事实：Mindustry 的存档名**就是文件名**
     *   （列表去掉扩展名显示，见 {@link Msav#safeName}）。导入别人给的存档时名字往往
     *   是 `save1.msav` / `困难模式 (1).msav`，直接落盘等于把别人的命名永久写进
     *   玩家的存档列表 —— 让他在这里改一次，比事后去文件管理器里找省事得多。
     *
     * 非 gzip 的文件**不**单独再弹一次确认，警告并进同一个对话框（少一次弹窗，
     * 而且用户看到警告时手边就是输入框和取消）。
     */
    private static void importMsav(final Activity a, final Uri uri, final String displayName, final String slot, final SlotOps.Host h) {
        final ProgressDialog pd = ProgressDialog.show(a,
                a.getString(R.string.msav_import_progress_title),
                a.getString(R.string.msav_import_progress_msg_fmt, displayName, slot), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                Msav.Stage st = null;
                try {
                    st = Msav.stage(a,
                            a.getContentResolver().openInputStream(uri), displayName, slot);
                } catch (Exception e) {
                    err = Util.ioReason(a, e);
                    android.util.Log.w("MDTLauncher", "msav stage failed: " + err, e);
                }
                final String fe = err;
                final Msav.Stage fst = st;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (fe != null) {
                            alert(a, a.getString(R.string.import_failed), fe);
                            return;
                        }
                        // ★ 接下来弹的是**改名框**（inline AlertDialog）⇒ 实例死了就必须停在这里，
                        //   否则 BadTokenException 闪退。⚠️ 但 `.part` 要收掉，别留垃圾。
                        if (Util.dead(a)) {
                            Msav.discard(fst);
                            return;
                        }
                        showMsavNameDialog(a, fst, slot, h);
                    }
                });
            }
        }).start();
    }

    /**
     * 存档名确认框（F6c）。预填**存档名（不含 `.msav`）** —— 默认什么都不改就是"和来源同名"，
     * 这最符合直觉；想改的人才去动它。（游戏列表里的名字本来就不带后缀，提示也写着"后缀会自动补上"。）
     *
     * ★ 校验不通过**不关窗**（接管 BUTTON_POSITIVE，见 promptPolicy 的注释）：
     *   否则名字填空了会静默丢弃，用户只觉得"点了没反应"。
     * ★ 取消（含返回键）要 `Msav.discard` 掉那个 `.part`，不然槽里留下垃圾。
     */
    private static void showMsavNameDialog(final Activity a, final Msav.Stage st, final String slot, final SlotOps.Host h) {
        View form = a.getLayoutInflater().inflate(R.layout.dialog_msav_name, null);
        final EditText name = (EditText) form.findViewById(R.id.msav_name);
        name.setText(Msav.baseName(st.base));
        name.setSelection(name.getText().length());
        if (!st.zlib) form.findViewById(R.id.msav_warn).setVisibility(View.VISIBLE);

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.msav_name_title_fmt, slot))
                .setView(form)
                .setPositiveButton(st.zlib ? R.string.import_btn : R.string.import_anyway, null)
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { Msav.discard(st); }
                })
                .create();
        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface d) { Msav.discard(st); }
        });
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String raw = name.getText().toString().trim();
                        if (raw.isEmpty()) {
                            toast(a, a.getString(R.string.msav_name_empty));
                            return;
                        }
                        st.base = Msav.safeName(raw);
                        // ★★ 同名覆盖**必须先问**（2026-10-04 修，P0）：`Msav.commit` 对同名是
                        //    "先 `delete()` 旧的、再改名"，而 `.msav` 既没有中转站也没有备份
                        //    ⇒ 不确认就是**静默删掉用户一份存档**（哪怕玩了几百小时），
                        //    而界面只报"已导入"。原来这里一个字的判断都没有。
                        //    ⚠️ 判据与地图导入那条对齐（`MapsActivity` 的 `map_overwrite_*`）：
                        //      同一个"同名要不要替换"的问题，两个入口只能有一种答法。
                        File dest = new File(st.savesDir, st.base);
                        if (dest.exists()) {
                            // 先收掉改名框再问（`dismiss()` 不触发 onCancel ⇒ 不会误 discard）；
                            // 留下来的 `.part` 由下面那个框的三个出口各自处置。
                            dlg.dismiss();
                            confirmMsavOverwrite(a, st, slot, h, dest.getName());
                            return;
                        }
                        dlg.dismiss();     // dismiss 不触发 onCancel ⇒ 上面的 discard 不会误跑
                        commitMsav(a, st, slot, h);
                    }
                });
            }
        });
        dlg.show();
    }

    /**
     * 「槽里已经有同名存档」的确认框（2026-10-04 修，P0）。
     *
     * ★ 为什么必须补：{@link Msav#commit} 对同名是**先 `delete()` 旧的、再改名**，
     *   而存档既没有中转站也没有备份 ⇒ 少这一问就是**静默丢一份存档**。
     *   地图导入早就有对应的一道（`MapsActivity` 的 `map_overwrite_*`），存档这条是漏做的。
     *
     * ★ 三个出口都要处置那个 `.part`（否则槽里留下垃圾，只能靠下一次
     *   {@link Msav#sweepParts} 收）：
     *   · 替换 —— 进 {@link #commitMsav}（改名成 `.msav`）；
     *   · 换个名字 —— 回到改名框（`st` 还活着，`.part` 还在，不会重下一次文件）；
     *   · 取消 / 返回键 —— {@link Msav#discard}。
     */
    private static void confirmMsavOverwrite(final Activity a, final Msav.Stage st,
                                             final String slot, final SlotOps.Host h,
                                             final String fileName) {
        new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.msav_overwrite_title))
                .setMessage(a.getString(R.string.msav_overwrite_msg_fmt, slot, fileName))
                .setPositiveButton(R.string.msav_overwrite_ok,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                commitMsav(a, st, slot, h);
                            }
                        })
                .setNeutralButton(R.string.msav_overwrite_rename,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                showMsavNameDialog(a, st, slot, h);
                            }
                        })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { Msav.discard(st); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) { Msav.discard(st); }
                })
                .show();
    }

    private static void commitMsav(final Activity a, Msav.Stage st, String slot, final SlotOps.Host h) {
        try {
            File dest = Msav.commit(a, st);
            Toast.makeText(a, a.getString(R.string.msav_imported_fmt, slot, dest.getName()),
                    Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Msav.discard(st);
            // ★ 2026-10-06：弹窗正文走 `Util.ioReason`（系统 errno 翻白话），原文进日志
            android.util.Log.w("MDTLauncher", "msav commit failed", e);
            alert(a, a.getString(R.string.import_failed), Util.ioReason(a, e));
        }
        h.onSlotChanged();
    }

    // ── 整槽 zip 导入（F6c） ──────────────────────────────────────────────

    /** F15：槽菜单入口 —— 目标槽已知，跳过选槽页直接选包（与 {@link #pickMsavForSlot} 对称）
     *  ★ F15b：与 .msav 同理，原先的"存档页入口"（先选槽再选包）已移除。 */
    static void importSlot(Activity a, final Data.Slot s) {
        if (blockedByGame(a, s)) return;
        sZipSlot = s.name;
        pickZipFile(a);
    }

    /** 挑 zip（两条入口共用；目标槽必须已经写进 sZipSlot） */
    private static void pickZipFile(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // 用 */* 而不是 application/zip：各家文件管理器 / 网盘对 zip 的 MIME
        // 报得五花八门（octet-stream、x-zip-compressed…），限死会让用户选不中自己的包。
        i.setType("*/*");
        a.startActivityForResult(
                Intent.createChooser(i, a.getString(R.string.chooser_pick_zip)), REQ_ZIP);
    }

    /**
     * **dev 口专用**：跳过 SAF 选包，直接从设备上的一个 zip 走到"读包 → 确认框"。
     *
     * ★ 为什么需要它：整槽导入的确认框是**弹窗流程**，而它前面那道 SAF 选择器
     *   **自动化点不了**（本 ROM 的「下载内容」只列应用下载过的文件，见 REF §52.5.1 那一串坑）。
     *   老 dev 口 `dev_zip_import` 只驱动数据层（`stage→inspect→extract`），**验不到弹窗**；
     *   而 2026-10-05（第 104 轮）给那个弹窗加了**三个模式**，模式选择器必须人眼过一遍。
     *   ⇒ 这一口把"选包"换成文件路径，后面的路与界面**逐字相同**
     *   （`readZipThenConfirm` → `confirmZipImport` → `doZipExtract`）。
     *
     * 用法：`--es dev_zip_confirm /path/to/x.zip [--es dev_zip_slot <槽>]`
     */
    static void devReadZip(final Activity a, final File zip, final String slotArg, final SlotOps.Host h) {
        if (zip == null || !zip.isFile()) {
            toast(a, a.getString(R.string.zip_import_failed) + "：" + zip);
            return;
        }
        final String slot = (slotArg == null || slotArg.trim().isEmpty())
                ? Data.currentSlot(a) : slotArg.trim();
        readZipThenConfirm(a, Uri.fromFile(zip), zip.getName(), slot, h);
    }

    /**
     * 读包 → 弹确认框。**这一步只能读**，任何写操作都要等用户在确认框里点下去。
     *
     * ★ 为什么先把包拷到内部临时文件（{@link SlotZip#stage}）：
     *   SAF 给的流通常只能读一次，而"先看清单再解包"必须读两遍（inspect → extract）。
     *   临时文件用完必删（成功、失败、取消三条路都删，见 {@link #dropZipTemp}）。
     */
    private static void readZipThenConfirm(final Activity a, final Uri uri, final String zipName, final String slot, final SlotOps.Host h) {
        final ProgressDialog pd = ProgressDialog.show(a,
                a.getString(R.string.zip_read_progress_title),
                a.getString(R.string.zip_read_progress_msg_fmt, zipName), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                File tmp = null;
                SlotZip.Info inf = null;
                try {
                    tmp = SlotZip.stage(a, uri);
                    inf = SlotZip.inspect(a, tmp);
                    if (inf.empty()) throw new IOException(a.getString(R.string.zip_nothing));
                } catch (Exception e) {
                    err = Util.ioReason(a, e);
                    SlotZip.unstage(tmp);
                    tmp = null;
                    android.util.Log.w("MDTLauncher", "zip inspect failed: " + err, e);
                }
                final String fe = err;
                final File ftmp = tmp;
                final SlotZip.Info finf = inf;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (fe != null) {
                            alert(a, a.getString(R.string.zip_read_failed), fe);
                            return;
                        }
                        // ★ 下面弹的是 inline 确认框 ⇒ 实例死了必须停，并**把临时包清掉**
                        //   （`dropZipTemp` 用的是那两个 static 字段，此处还没写进去 ⇒ 直接清 ftmp）。
                        if (Util.dead(a)) {
                            SlotZip.unstage(ftmp);
                            return;
                        }
                        sZipTmp = ftmp;
                        sZipInfo = finf;
                        confirmZipImport(a, slot, finf, h);
                    }
                });
            }
        }, "zip-read").start();
    }

    /**
     * 解包前的确认框：**把包里有什么摊开给用户看**，再让他选"怎么放进去"。
     *
     * ★ 2026-10-05（第 104 轮）：原来的「先清空」复选框换成了**三个模式**
     *   （更新 / 补齐 / 覆盖）—— 用户要的不只是"要不要清空"，
     *   还有"两边的同名文件谁说了算"（补齐就是为此存在的）。
     *   判据与清空实现都在 {@link SlotWrite}，与快照恢复**共用一份**。
     */
    private static void confirmZipImport(final Activity a, final String slot, final SlotZip.Info inf, final SlotOps.Host h) {
        View form = a.getLayoutInflater().inflate(R.layout.dialog_zip_import, null);
        TextView list = (TextView) form.findViewById(R.id.zip_list);
        TextView warn = (TextView) form.findViewById(R.id.zip_warn);
        final SlotModes modes = SlotModes.bind(a, form);      // 默认「更新」（= 老行为）

        StringBuilder sb = new StringBuilder();
        sb.append(a.getString(R.string.zip_confirm_head_fmt, inf.files,
                inf.sizeKnown ? Util.formatSize(inf.bytes) : a.getString(R.string.zip_size_unknown)));
        if (!inf.tops.isEmpty()) {
            sb.append(a.getString(R.string.zip_confirm_tops_fmt, joinList(inf.tops)));
        }
        if (!inf.strip.isEmpty()) {
            sb.append(a.getString(R.string.zip_confirm_strip_fmt,
                    inf.strip.substring(0, inf.strip.length() - 1)));
        }
        sb.append(inf.nativeFormat ? a.getString(R.string.zip_tag_native)
                                   : a.getString(R.string.zip_tag_custom));
        list.setText(sb.toString());

        StringBuilder wn = new StringBuilder();
        if (!inf.known) wn.append(a.getString(R.string.zip_warn_unknown));
        if (inf.skipped > 0) {
            if (wn.length() > 0) wn.append("\n\n");
            wn.append(a.getString(R.string.zip_warn_skipped_fmt, inf.skipped));
        }
        if (wn.length() > 0) {
            warn.setText(wn.toString());
            warn.setVisibility(View.VISIBLE);
        }

        new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.zip_confirm_title_fmt, slot))
                .setView(form)
                .setPositiveButton(R.string.import_btn, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        doZipExtract(a, slot, modes == null ? SlotWrite.UPDATE : modes.mode(), h);
                    }
                })
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { dropZipTemp(); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) { dropZipTemp(); }
                })
                .show();
    }

    private static void doZipExtract(final Activity a, final String slot, final int mode, final SlotOps.Host h) {
        final File zip = sZipTmp;
        final SlotZip.Info inf = sZipInfo;
        if (zip == null) {
            toast(a, a.getString(R.string.zip_import_failed));
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(a,
                a.getString(R.string.zip_extract_progress_title),
                a.getString(R.string.zip_extract_progress_msg_fmt, slot), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                SlotZip.Result r = null;
                Backup.Snapshot auto = null;
                try {
                    // ★★ 槽操作前的自动备份（用户 2026-10-05：「在槽操作前都自动备份吧」）。
                    //    放在**解包之前**、同一个后台线程里 —— 它就是给"这一下改坏了"兜底的。
                    auto = AutoBackup.beforeSlotOp(a, slot, a.getString(R.string.backup_auto_zip));
                    r = SlotZip.extract(a, zip, inf, slot, mode);
                } catch (Exception e) {
                    err = Util.ioReason(a, e);
                    android.util.Log.w("MDTLauncher", "zip extract failed: " + err, e);
                }
                SlotZip.unstage(zip);
                final String fe = err;
                final SlotZip.Result fr = r;
                final Backup.Snapshot fauto = auto;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        sZipTmp = null;
                        sZipInfo = null;
                        if (fe != null) {
                            alert(a, a.getString(R.string.zip_import_failed), fe);
                            h.onSlotChanged();
                            return;
                        }
                        StringBuilder ex = new StringBuilder();
                        if (fr.kept > 0) {
                            ex.append(a.getString(R.string.zip_import_kept_fmt, fr.kept));
                        }
                        if (!fr.wiped.isEmpty()) {
                            ex.append(a.getString(R.string.zip_import_wiped_fmt, joinList(fr.wiped)));
                        }
                        if (fr.skipped > 0) {
                            ex.append(a.getString(R.string.zip_import_skipped_fmt, fr.skipped));
                        }
                        if (fauto != null) {
                            ex.append(a.getString(R.string.slot_autobak_done_fmt, fauto.title()));
                        }
                        alert(a, a.getString(R.string.zip_import_done),
                                a.getString(R.string.zip_import_ok_fmt, slot, fr.files,
                                        Util.formatSize(fr.bytes), ex.toString()));
                        h.onSlotChanged();
                    }
                });
            }
        }, "zip-extract").start();
    }

    /** 用户取消/关窗时把临时包清掉（三条出口都走这里） */
    private static void dropZipTemp() {
        SlotZip.unstage(sZipTmp);
        sZipTmp = null;
        sZipInfo = null;
    }

    private static String joinList(List<String> a) {
        StringBuilder sb = new StringBuilder();
        if (a == null) return "";
        for (int i = 0; i < a.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(a.get(i));
        }
        return sb.toString();
    }

    private static String queryDisplayName(Activity a, Uri uri) {
        try {
            Cursor c = a.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0 && c.moveToFirst()) {
                        String n = c.getString(i);
                        if (n != null && !n.isEmpty()) return n;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        String p = uri.getLastPathSegment();
        return (p == null || p.isEmpty()) ? "imported.msav" : p;
    }

    /**
     * 全类弹窗的唯一入口 —— ★ 2026-10-04 起**在这里挡"已销毁的 Activity"**。
     *
     * 为什么挡在这一层而不是每个调用点：本类的弹窗绝大多数都是**后台任务收尾**时弹的
     * （导出 / 导入 / 解包…几十秒的任务），而"任务跑完时发起它的 Activity 已经被转屏销毁"
     * 会抛 `WindowManager$BadTokenException` **直接闪退**（见 {@link Util#dead}）。
     * 收口在这一处 ⇒ 以后新增的 alert 调用自动受保护，不必记得补判据。
     */
    private static void alert(Activity a, String title, String msg) {
        if (Util.dead(a)) return;
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }
}
