package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 运行日志（F7）—— 应用内看日志，不用 adb。
 *
 * ★★ 为什么这个页面非有不可（需求原文：「应该加一个可以查看日志的地方，要不然日志记录没用啊」）：
 *
 *   本 ROM **把应用自己的 main logcat 滤掉了**（`adb logcat -d | grep xxx` 零命中，
 *   见 REFERENCE §14）。所以从 M0 起，本项目所有的运行期证据都是**写文件**的：
 *     游戏侧：<数据根>/last_log.txt（截断覆盖 + 每行 flush ⇒ 崩了也在盘上）
 *             <数据根>/crashes/crash_<毫秒>.txt
 *     启动器侧：<HUB 外部>/report-launch.txt（六步管线耗时）/ report-autobackup.txt /
 *               report-settings.txt / report-health.txt / report-m3.txt / report-devtool.txt
 *
 *   在此之前这些文件**只有 adb 取得出来** —— 对普通用户等于不存在。
 *   本页就是它们的唯一出口。
 *
 * ★ 三个数据源缺一不可：只有游戏侧会看到"游戏为什么崩"，只有启动器侧会看到
 *   "六步管线卡在哪一步"，只有崩溃报告会给出完整堆栈。三张卡摊在同一页，
 *   是因为排障时这三件事要**对着看**。
 *
 * ★ 只读 + 可复制 + 可清理旧崩溃。**不做编辑、不做写入** —— 启动器的日志由各模块
 *   自己原子写（Util.atomicWriteText），这里改成可编辑只会引入"两份真相"。
 *
 * ⚠️ 游戏日志卡读的是 {@link Data#dataRoot}（= `dirOf(当前槽)`）⇒ 显示的
 *   永远是**当前槽**的 last_log.txt。切了槽，这里看到的就是另一个槽的日志。
 *   F0 之后它通常是 `…/<pkg>/slot-<槽名>/last_log.txt`；只有当远古包（`build ≤ 146`）
 *   正在／刚跑过、本体被临时借住 `files/` 时，才会回落成 `…/<pkg>/files/last_log.txt`
 *   （见 {@link Data#dirOf} 的第 ③ 条分支）。
 *
 * ★ F20：**一键导出全部日志**（需求原文：「加一个一键导出所以日志的功能」）。
 *   以前只有一个"复制"（进剪贴板），对"把日志发给别人看"这件事等于没法用 ——
 *   用户要在三个卡之间来回切、复制三次、再自己拼起来。现在页首一行就把三个源
 *   合成**一个 txt**，经 SAF（`ACTION_CREATE_DOCUMENT`）存到用户选的位置。
 *
 *   🔴 导出与页面显示**用的是两套读取**，这是有意的：
 *      页面为了"几千行日志别把界面拖卡"，{@link Doc#load()} 只留**尾部 400 行**；
 *      而导出是**要交给别人看/存档的**，截断等于悄悄丢掉最前面那段（往往是启动阶段
 *      的关键信息）⇒ 导出走 {@link Doc#fullText()}，**不截断**。
 *      把两者写成一个方法是很自然、也是很错的合并（"看着能用"）。
 */
public class LogActivity extends BaseActivity {

    private static final String TAG = "MDTLauncher";

    /** 正文最多显示多少行（超长文件只显示尾部，meta 里会标注） */
    private static final int MAX_LINES = 400;
    /** 超过这个大小就不整读，只读尾部这段字节（避免一局长跑把内存和耗时拖起来） */
    private static final int MAX_WHOLE_BYTES = 512 * 1024;
    /**
     * 导出时**单份**文件的字节上限。
     * ★ 为什么不设"无上限"：导出要把三源全文拼进一个 StringBuilder，若某个文件被
     *   写成了几百 MB（游戏死循环刷日志），无上限就是一次 OOM —— 而 OOM 发生在
     *   导出中途，用户看到的是"闪退"，不是"日志太大"。宁可**明确告诉他被截了**
     *   （`log_export_big_fmt`），也不要静默地崩或静默地丢。
     */
    private static final int EXPORT_MAX_BYTES = 8 * 1024 * 1024;
    /** SAF 导出请求码 */
    private static final int REQ_EXPORT = 4711;

    /**
     * 启动器侧报告：文件名 + 展示名。
     * 顺序 = 排障时的常用度（第一个默认选中）。
     * ⚠️ 这个清单要和 Injector / SelfTest / AutoBackup / MainActivity 里
     *    `atomicWriteText(new File(hub, "report-xxx.txt"))` 的名字保持一致。
     */
    private static final String[] HUB_FILES = {
            "report-launch.txt",
            "report-autobackup.txt",
            "report-settings.txt",
            "report-health.txt",
            "report-m3.txt",
            "report-devtool.txt",
    };
    private static final int[] HUB_LABELS = {
            R.string.log_hub_launch,
            R.string.log_hub_autobackup,
            R.string.log_hub_settings,
            R.string.log_hub_health,
            R.string.log_hub_m3,
            R.string.log_hub_devtool,
    };

    // ── 视图 ──────────────────────────────────────────────────────────────
    private TextView mGameMeta;
    private TextView mGameBody;
    private TextView mHubMeta;
    private TextView mHubBody;
    private LinearLayout mHubChips;
    private TextView mCrashMeta;
    private TextView mCrashBody;
    private TextView mCrashClean;
    private LinearLayout mCrashChips;
    private View mCrashChipScroll;

    // ── 状态 ──────────────────────────────────────────────────────────────
    private Doc[] mHubDocs = new Doc[0];
    private List<Doc> mCrashes = new ArrayList<Doc>();
    private Doc mShownGame;
    private Doc mShownHub;
    private Doc mShownCrash;
    private int mHubIndex = 0;
    private int mCrashIndex = 0;
    /** 每次 reload 自增；后台线程回来时对不上就丢弃（防旋转 / 连点造成乱序回填） */
    private int mLoadStamp = 0;
    /** 最近一次读到的快照 —— 导出就是它（点"导出"时不再重新读盘，免得与屏幕上看到的不一致） */
    private Snap mSnap;
    /**
     * 已经拼好、等着写出去的正文。
     * ⚠️ 存成字段而不是只放在局部变量里：SAF 选择器是**另一个应用的界面**，
     *   它有可能让本 Activity 被回收重建；重建后字段也没了 —— 所以
     *   {@link #onActivityResult} 里发现它是 null 会**重新拼一次**，而不是放弃导出。
     */
    private String mExportText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.i(TAG, "LogActivity onCreate");
        buildUi();
    }

    /**
     * 每次回到本页都重读。
     * ★ 这就够了，不用「刷新」按钮：游戏是全屏跑的，用户看到本页时游戏必然已退出，
     *   而退出 -> 回启动器必然走一次 onResume ⇒ 每次看到的都是最新落盘的那份。
     */
    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    // ── UI 骨架 ───────────────────────────────────────────────────────────

    private void buildUi() {
        View root = getLayoutInflater().inflate(R.layout.activity_log, null);

        mGameMeta = (TextView) root.findViewById(R.id.log_game_meta);
        mGameBody = (TextView) root.findViewById(R.id.log_game_body);
        mHubMeta = (TextView) root.findViewById(R.id.log_hub_meta);
        mHubBody = (TextView) root.findViewById(R.id.log_hub_body);
        mHubChips = (LinearLayout) root.findViewById(R.id.log_hub_chips);
        mCrashMeta = (TextView) root.findViewById(R.id.log_crash_meta);
        mCrashBody = (TextView) root.findViewById(R.id.log_crash_body);
        mCrashClean = (TextView) root.findViewById(R.id.log_crash_clean);
        mCrashChips = (LinearLayout) root.findViewById(R.id.log_crash_chips);
        mCrashChipScroll = root.findViewById(R.id.log_crash_chip_scroll);

        root.findViewById(R.id.log_game_copy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copy(Trans.get(LogActivity.this, R.string.log_game_title), mShownGame);
            }
        });
        root.findViewById(R.id.log_hub_copy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copy(Trans.get(LogActivity.this, R.string.log_hub_title), mShownHub);
            }
        });
        root.findViewById(R.id.log_crash_copy).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copy(Trans.get(LogActivity.this, R.string.log_crash_title), mShownCrash);
            }
        });

        // F20：导出全部日志。复用 row_action 范式（图标圈 + 标题/副标题 + 箭头），
        // ⚠️ bindAction 会**从行根**findViewById —— include 多次时 id 会重复（见 Util 注释）。
        Util.bindAction(root, R.id.log_row_export, R.drawable.ic_download,
                R.string.log_export, R.string.log_export_sub, new Runnable() {
                    @Override public void run() { onClickExport(); }
                });

        Util.applySystemInsets(root);
        setContentView(root);

        // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包
        Trans.bind(root, R.id.tx_log_game_title, R.string.log_game_title);
        Trans.bind(root, R.id.log_game_copy, R.string.log_copy);
        Trans.bind(root, R.id.tx_log_hub_title, R.string.log_hub_title);
        Trans.bind(root, R.id.log_hub_copy, R.string.log_copy);
        Trans.bind(root, R.id.tx_log_crash_title, R.string.log_crash_title);
        Trans.bind(root, R.id.log_crash_copy, R.string.log_copy);

    }

    // ── 读取（全部在后台线程） ────────────────────────────────────────────

    private void reload() {
        mGameMeta.setText(Trans.get(LogActivity.this, R.string.log_loading));
        mGameBody.setText("");
        mHubMeta.setText(Trans.get(LogActivity.this, R.string.log_loading));
        mHubBody.setText("");
        mHubChips.removeAllViews();
        mCrashMeta.setText(Trans.get(LogActivity.this, R.string.log_loading));
        mCrashBody.setText("");
        mCrashChips.removeAllViews();
        mCrashClean.setVisibility(View.GONE);

        final int stamp = ++mLoadStamp;
        new Thread(new Runnable() {
            @Override public void run() {
                final Snap snap = readAll();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (stamp != mLoadStamp) return;   // 过期结果：丢弃
                        apply(snap);
                    }
                });
            }
        }, "log-read").start();
    }

    /**
     * 一次读取的结果。
     * ⚠️ 包级可见（不是 private）：dev 口 `dev_logs_export` 与自检的导出用例
     *   都要自己拼一份快照喂给 {@link #compose}。字段也一律包级。
     */
    static final class Snap {
        Doc game;
        Doc[] hub = new Doc[0];
        List<Doc> crashes = new ArrayList<Doc>();
        boolean dataRootMissing;
    }

    private Snap readAll() { return readAll(this); }

    /**
     * 读三个源。
     * ★ 是 static 的（而不是只做实例方法）**有明确用途**：dev 口的
     *   `dev_logs_export` 与自检的"导出内容完整性"用例都要在**没有本 Activity**
     *   的情况下拿到同一份快照 —— 若各写一份"读日志"的逻辑，导出的内容与页面看到的
     *   就会慢慢分叉（本轮 F19/F20 反复吃过这个亏：同一件事必须只有一个实现）。
     */
    static Snap readAll(Context ctx) {
        Snap s = new Snap();

        File dataRoot = Data.dataRoot(ctx);
        if (dataRoot == null) {
            s.dataRootMissing = true;
            return s;
        }
        s.game = new Doc("last_log.txt", Trans.get(ctx, R.string.log_game_title),
                new File(dataRoot, "last_log.txt"));
        s.game.load();

        File hub = Data.hubDir(ctx);
        s.hub = new Doc[HUB_FILES.length];
        for (int i = 0; i < HUB_FILES.length; i++) {
            s.hub[i] = new Doc(HUB_FILES[i], Trans.get(ctx, HUB_LABELS[i]),
                    new File(hub, HUB_FILES[i]));
            s.hub[i].load();
        }

        // 崩溃报告：按文件名倒序（名字就是写入时的毫秒时间戳 ⇒ 字典序 = 时间序）
        File cdir = new File(dataRoot, "crashes");
        File[] fs = cdir.isDirectory() ? cdir.listFiles() : null;
        if (fs != null) {
            Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return b.getName().compareTo(a.getName());
                }
            });
            for (File f : fs) {
                if (!f.isFile()) continue;
                Doc d = new Doc(f.getName(), f);
                d.load();
                s.crashes.add(d);
            }
        }
        return s;
    }

    /** 三个源里至少有一个真的存在（全都不存在时没必要让用户白走一趟 SAF 选择器） */
    static boolean hasAnyLog(Snap s) {
        if (s == null) return false;
        if (s.game != null && s.game.file != null && s.game.file.isFile()) return true;
        if (s.hub != null) {
            for (Doc d : s.hub) if (d.file != null && d.file.isFile()) return true;
        }
        return s.crashes != null && !s.crashes.isEmpty();
    }

    // ── 回填 UI ───────────────────────────────────────────────────────────

    private void apply(Snap s) {
        mSnap = s;
        if (s.dataRootMissing) {
            mGameMeta.setText(Trans.get(LogActivity.this, R.string.log_no_storage));
            mHubMeta.setText("");
            mCrashMeta.setText("");
            mCrashChipScroll.setVisibility(View.GONE);
            return;
        }

        // ── 卡 1：游戏日志 ──
        mShownGame = s.game;
        mGameMeta.setText(metaOf(s.game, Trans.get(LogActivity.this, R.string.log_slot_fmt, Data.currentSlot(this))));
        mGameBody.setText(highlight(s.game.text, Trans.get(LogActivity.this, R.string.log_game_missing_hint)));

        // ── 卡 2：启动器日志 ──
        mHubDocs = s.hub;
        if (mHubIndex < 0 || mHubIndex >= mHubDocs.length) mHubIndex = 0;
        // 默认选中第一份「存在」的报告，省得进来先看到一句"还没生成"
        if (mHubDocs[mHubIndex].file == null || !mHubDocs[mHubIndex].file.isFile()) {
            for (int i = 0; i < mHubDocs.length; i++) {
                if (mHubDocs[i].file != null && mHubDocs[i].file.isFile()) { mHubIndex = i; break; }
            }
        }
        mountHubChips();
        showHub(mHubIndex);

        // ── 卡 3：崩溃报告 ──
        mCrashes = s.crashes;
        if (mCrashIndex >= mCrashes.size()) mCrashIndex = 0;
        mountCrashChips();
        showCrash(mCrashes.isEmpty() ? -1 : mCrashIndex);
        refreshCleanButton();
    }

    private void mountHubChips() {
        mHubChips.removeAllViews();
        for (int i = 0; i < mHubDocs.length; i++) {
            final int idx = i;
            boolean has = mHubDocs[i].file != null && mHubDocs[i].file.isFile();
            String label = Trans.get(LogActivity.this, HUB_LABELS[i]);
            if (!has) label = Trans.get(LogActivity.this, R.string.log_chip_absent_fmt, label);
            mHubChips.addView(makeChip(label, i == mHubIndex, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    mHubIndex = idx;
                    mountHubChips();
                    showHub(idx);
                }
            }));
        }
    }

    private void showHub(int i) {
        if (i < 0 || i >= mHubDocs.length) return;
        Doc d = mHubDocs[i];
        mShownHub = d;
        mHubMeta.setText(metaOf(d, null));
        mHubBody.setText(highlight(d.text, Trans.get(LogActivity.this, R.string.log_hub_missing_hint)));
    }

    private void mountCrashChips() {
        mCrashChips.removeAllViews();
        if (mCrashes.isEmpty()) {
            mCrashChipScroll.setVisibility(View.GONE);
            return;
        }
        mCrashChipScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < mCrashes.size(); i++) {
            final int idx = i;
            // 展示名 = 去掉扩展名的文件名（那就是毫秒时间戳），够短也够唯一
            String nm = mCrashes.get(i).label;
            if (nm.endsWith(".txt")) nm = nm.substring(0, nm.length() - 4);
            mCrashChips.addView(makeChip(nm, i == mCrashIndex, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    mCrashIndex = idx;
                    mountCrashChips();
                    showCrash(idx);
                }
            }));
        }
    }

    private void showCrash(int i) {
        if (i < 0 || i >= mCrashes.size()) {
            mShownCrash = null;
            mCrashMeta.setText(Trans.get(LogActivity.this, R.string.log_crash_none));
            mCrashBody.setText("");
            return;
        }
        Doc d = mCrashes.get(i);
        mShownCrash = d;
        int keep = Config.get().maxLogFiles();
        mCrashMeta.setText(Trans.get(LogActivity.this, R.string.log_crash_meta_fmt,
                mCrashes.size(), keep, fmtTime(d.mtime)));
        mCrashBody.setText(highlight(d.text, ""));
    }

    /** 超出「日志保留份数」时才出现清理入口 —— 让 F3 那个配置项在这里真正生效。 */
    private void refreshCleanButton() {
        final int keep = Config.get().maxLogFiles();
        final int over = mCrashes.size() - keep;
        if (over <= 0) {
            mCrashClean.setVisibility(View.GONE);
            mCrashClean.setOnClickListener(null);
            return;
        }
        mCrashClean.setVisibility(View.VISIBLE);
        mCrashClean.setText(Trans.get(LogActivity.this, R.string.log_crash_clean_fmt, over, keep));
        mCrashClean.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                new AlertDialog.Builder(LogActivity.this)
                        .setTitle(R.string.log_crash_clean_title)
                        .setMessage(Trans.get(LogActivity.this, R.string.log_crash_clean_msg_fmt, over, keep))
                        .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                doClean(over);
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });
    }

    /**
     * 删掉最旧的 over 份。
     * ⚠️ 这是**删用户数据** ⇒ 前面必有确认弹窗（列出份数 + 明确"无法恢复"）；
     *    这里只做删除本身，并把失败次数如实回报，绝不静默吞掉。
     *    mCrashes 已是「新 -> 旧」序，所以取尾部 over 个即可。
     */
    private void doClean(int over) {
        int failed = 0;
        for (int i = mCrashes.size() - over; i < mCrashes.size(); i++) {
            if (i < 0) continue;
            File f = mCrashes.get(i).file;
            if (f != null && f.isFile() && !f.delete()) failed++;
        }
        Toast.makeText(this, failed == 0
                        ? Trans.get(LogActivity.this, R.string.log_crash_clean_done_fmt, over)
                        : Trans.get(LogActivity.this, R.string.log_crash_clean_fail_fmt, failed),
                Toast.LENGTH_SHORT).show();
        reload();
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private TextView makeChip(String text, boolean on, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackgroundResource(on ? R.drawable.chip_tab_on : R.drawable.chip_tab_off);
        t.setTextColor(getResources().getColor(on ? R.color.accent : R.color.fg_muted, getTheme()));
        t.setClickable(true);
        t.setFocusable(false);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        t.setLayoutParams(lp);
        return t;
    }

    /** meta 行的统一格式：`文件名 · 大小 · 时间 更新（只显示最后 N 行）` [+ 尾巴] */
    private CharSequence metaOf(Doc d, String tail) {
        if (d == null || d.file == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!d.file.isFile()) {
            sb.append(Trans.get(LogActivity.this, R.string.log_not_generated_fmt, d.label));
        } else {
            sb.append(Trans.get(LogActivity.this, R.string.log_meta_fmt,
                    d.label, Util.formatSize(d.size), fmtTime(d.mtime)));
            if (d.truncated) {
                sb.append(Trans.get(LogActivity.this, R.string.log_meta_truncated, d.lines));
            }
        }
        if (tail != null) sb.append('\n').append(tail);
        return sb.toString();
    }

    /**
     * 正文渲染 + 异常行高亮。
     *
     * ★ 为什么值得做：日志的用法就是"扫一眼找异常"。`[E]` / `Exception` 行染成告警色后，
     *   几百行里哪几行要看是瞬间的事 —— 这比给每行加 grep 按钮便宜得多。
     * ⚠️ 逐行 subSequence().toString() 会有一次分配，400 行量级无感；真到几万行再加索引。
     */
    private CharSequence highlight(String text, String emptyHint) {
        if (TextUtils.isEmpty(text)) return emptyHint;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int warn = getResources().getColor(R.color.chip_fg_warn, getTheme());
        int start = 0;
        int n = text.length();
        while (start < n) {
            // ⚠️ 定位必须在**原始 String** 上做：SpannableStringBuilder 只实现 CharSequence，
            //    没有 indexOf（本轮编译期就踩了这个），但两者的下标是同一套，可混用。
            int end = text.indexOf('\n', start);
            if (end < 0) end = n;
            if (isErrorLine(text, start, end)) {
                sb.setSpan(new ForegroundColorSpan(warn), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end + 1;
        }
        return sb;
    }

    private boolean isErrorLine(String s, int a, int b) {
        String line = s.substring(a, b);
        return line.contains("[E]") || line.contains("Exception")
                || line.contains("Error") || line.contains("FAIL");
    }

    private void copy(String what, Doc d) {
        if (d == null || TextUtils.isEmpty(d.text)) {
            Toast.makeText(this, R.string.log_copy_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(what, d.text));
        Toast.makeText(this, Trans.get(LogActivity.this, R.string.log_copied_fmt, what), Toast.LENGTH_SHORT).show();
    }

    // ── F20：一键导出全部日志 ──────────────────────────────────────────────

    /**
     * 点"导出全部日志"。
     *
     * 顺序：**先把三源全文拼好，再叫出 SAF 选择器**。
     * ★ 为什么不是"先选位置、再读文件":若在读的时候才发现"一个源都没有"，用户已经
     *   白选了路径、还得再解释一遍为什么什么都没导出。先拼（很便宜：总量在几十 KB 级）
     *   就能在**动手前**给出"还没有任何日志可导出"。
     * ★ 拼装在后台线程：`fullText()` 会整读文件，几 MB 时放主线程就是一次卡顿。
     */
    private void onClickExport() {
        if (mSnap == null || !hasAnyLog(mSnap)) {
            Toast.makeText(this, R.string.log_export_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final Snap s = mSnap;
        new Thread(new Runnable() {
            @Override public void run() {
                final String text = compose(LogActivity.this, s, exportHeader(LogActivity.this));
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mExportText = text;
                        try {
                            startActivityForResult(
                                    Exporter.createDoc(exportFileName(), "text/plain"), REQ_EXPORT);
                        } catch (android.content.ActivityNotFoundException e) {
                            // 极简 ROM 上可能一个能收 ACTION_CREATE_DOCUMENT 的应用都没有
                            mExportText = null;
                            Toast.makeText(LogActivity.this, R.string.log_export_no_picker,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "log-export-build").start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT) return;
        // 用户取消（返回键 / 不选）⇒ 什么都不做，也不弹"失败"（那不是失败）
        if (res != RESULT_OK || data == null || data.getData() == null) {
            mExportText = null;
            return;
        }
        final android.net.Uri uri = data.getData();
        // 兼容被系统回收后重建的情况：正文丢了就重拼一份（读盘很便宜，比重导一次强）
        final String text = (mExportText != null)
                ? mExportText
                : compose(this, mSnap, exportHeader(this));
        mExportText = null;
        new Thread(new Runnable() {
            @Override public void run() {
                long n = 0;
                String err = null;
                try {
                    n = Exporter.writeText(LogActivity.this, uri, text);
                } catch (Throwable t) {
                    // ⚠️ 异常**必须说出来**：目标不可写、用户选的盘被拔了 —— 静默成功最坏
                    err = (t.getMessage() == null ? t.toString() : t.getMessage());
                }
                final String ferr = err;
                final long fn = n;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(LogActivity.this,
                                ferr == null
                                        ? Trans.get(LogActivity.this, R.string.log_export_ok_fmt, Util.formatSize(fn))
                                        : Trans.get(LogActivity.this, R.string.log_export_fail_fmt, ferr),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "log-export-write").start();
    }

    /** 建议文件名：全 ASCII + 时间戳（`EXTRA_TITLE` 只是建议，用户能改） */
    private static String exportFileName() {
        return "mdt-logs-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + ".txt";
    }

    /**
     * 导出文件的**表头**（环境信息）—— 与正文拼装分开，方便自检单独验。
     *
     * ★ 为什么要带环境：这份文件是要**离开这台设备**的（导出给别人看）。
     *   排障时"哪个版本、哪个槽、什么机器、什么 ABI"决定了日志怎么读：
     *   同一个堆栈在 arm64 与 armeabi-v7a 上、在 4K 页与 64K 页的机器上含义不同
     *   （本项目的 ELF 对齐判据就是按页大小算的）。
     */
    static String exportHeader(Context ctx) {
        File root = Data.dataRoot(ctx);
        File hub = Data.hubDir(ctx);
        String ver;
        try {
            ver = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            ver = "?";
        }
        long page = Compat.pageSize();
        return Trans.get(ctx, R.string.log_export_head_fmt,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()),
                ver,
                Data.currentSlot(ctx),
                root == null ? Trans.get(ctx, R.string.log_export_absent) : root.getAbsolutePath(),
                hub == null ? Trans.get(ctx, R.string.log_export_absent) : hub.getAbsolutePath(),
                android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                        + " · Android " + android.os.Build.VERSION.RELEASE
                        + " (API " + android.os.Build.VERSION.SDK_INT + ")",
                TextUtils.join(", ", android.os.Build.SUPPORTED_ABIS),
                page <= 0 ? "?" : (page + " B"));
    }

    /**
     * 把三个源拼成**一个**导出文件。
     *
     * ★ 是 static 纯拼装（不碰 UI、不弹窗）⇒ 自检可以直接喂一份**人造快照**进来，
     *   断言"每一段的具体内容都在"。这是"导出漏了一段"这类错误唯一的判据 ——
     *   它不崩、不报错、文件大小也像样，只有逐段比对内容才能抓到。
     * ⚠️ 用 {@link Doc#fullText()}（全文）而**不是** {@code d.text}（尾部 400 行）。
     */
    static String compose(Context ctx, Snap s, String header) {
        StringBuilder sb = new StringBuilder();
        sb.append(header).append("\n\n");

        // ① 游戏日志
        sb.append(Trans.get(ctx, R.string.log_export_s1)).append('\n');
        appendDoc(sb, ctx, s == null ? null : s.game);
        sb.append('\n');

        // ② 启动器日志（六份，缺的也列出来 —— "哪份没有"本身是排障信息）
        sb.append(Trans.get(ctx, R.string.log_export_s2)).append('\n');
        if (s == null || s.hub == null || s.hub.length == 0) {
            sb.append(Trans.get(ctx, R.string.log_export_absent)).append('\n');
        } else {
            for (Doc d : s.hub) appendDoc(sb, ctx, d);
        }
        sb.append('\n');

        // ③ 崩溃报告（已在 readAll 里按新 -> 旧排好，导出沿用同一顺序）
        int n = (s == null || s.crashes == null) ? 0 : s.crashes.size();
        sb.append(Trans.get(ctx, R.string.log_export_s3_fmt, n)).append('\n');
        if (n == 0) {
            sb.append(Trans.get(ctx, R.string.log_export_absent)).append('\n');
        } else {
            for (Doc d : s.crashes) appendDoc(sb, ctx, d);
        }
        return sb.toString();
    }

    /** 一段：`──────── 展示名（文件名）────────` + 全文（或"缺失"/"空文件"/"过大"说明） */
    private static void appendDoc(StringBuilder sb, Context ctx, Doc d) {
        // 展示名缺省退回文件名（崩溃报告就是靠这个 —— 它的文件名本身即时间戳，可读）
        String name = (d == null) ? "?"
                : (d.dispName != null ? d.dispName : (d.label != null ? d.label : "?"));
        String fn = (d == null || d.label == null) ? "?" : d.label;
        sb.append(Trans.get(ctx, R.string.log_export_sec_fmt, name, fn)).append('\n');
        if (d == null || d.file == null || !d.file.isFile()) {
            sb.append(Trans.get(ctx, R.string.log_export_absent)).append('\n');
            return;
        }
        String t = d.fullText();
        if (t == null || t.length() == 0) {
            sb.append(Trans.get(ctx, R.string.log_export_blank)).append('\n');
            return;
        }
        if (d.wholeTruncated) {
            sb.append(Trans.get(ctx, R.string.log_export_big_fmt,
                    Util.formatSize(d.file.length()), Util.formatSize(EXPORT_MAX_BYTES))).append('\n');
        }
        sb.append(t);
        if (!t.endsWith("\n")) sb.append('\n');
    }

    private static String fmtTime(long ms) {
        if (ms <= 0) return "?";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ── 一份日志 ──────────────────────────────────────────────────────────

    /**
     * 一个文件的「元数据 + 正文」。
     * ★ 读是**惰性且幂等**的：readAll() 在后台线程里对每个文件调一次 load()，
     *   之后 UI 线程只读缓存 —— 点击 chip 切换报告不会触发任何磁盘 IO。
     * ⚠️ 包级可见（不是 private）：dev 口与自检都要自己造实例（见 {@link Snap}）。
     */
    static final class Doc {
        /** 文件名（也是崩溃报告的展示名 —— 它就是毫秒时间戳，够短也够唯一） */
        final String label;
        /** 页面/导出的展示名（可空 ⇒ 退回用 label）。启动器那六份走 strings 里的中文名 */
        final String dispName;
        final File file;
        long size;
        long mtime;
        /** **页面用**的正文：只保留尾部 {@link #MAX_LINES} 行 */
        String text;
        int lines;
        boolean truncated;
        private boolean loaded;

        /** **导出用**的全文（不截断）；与 text 分开缓存 —— 两者用途、体量都不同 */
        private String whole;
        private boolean wholeLoaded;
        /** fullText() 因超上限被截过（导出时会写一行说明，不静默） */
        boolean wholeTruncated;

        Doc(String label, File file) { this(label, null, file); }

        Doc(String label, String dispName, File file) {
            this.label = label;
            this.dispName = dispName;
            this.file = file;
        }

        void load() {
            if (loaded) return;
            loaded = true;
            if (file == null || !file.isFile()) return;
            size = file.length();
            mtime = file.lastModified();
            try {
                String raw = (file.length() <= MAX_WHOLE_BYTES)
                        ? Util.readText(file)
                        : readTailBytes(file, MAX_WHOLE_BYTES);

                String[] all = raw.split("\n");
                int total = all.length;
                int from = Math.max(0, total - MAX_LINES);
                StringBuilder sb = new StringBuilder();
                for (int i = from; i < total; i++) {
                    if (sb.length() > 0) sb.append('\n');
                    // 兜掉 CRLF 的 \r，否则 monospace 下每行尾会多一个方块
                    String ln = all[i];
                    if (ln.endsWith("\r")) ln = ln.substring(0, ln.length() - 1);
                    sb.append(ln);
                }
                text = sb.toString();
                lines = total - from;
                truncated = from > 0;
            } catch (IOException e) {
                text = "read failed: " + e;
            } catch (OutOfMemoryError e) {
                text = "file too large: " + size + " B";
            }
        }

        /**
         * **全文**（导出用）——★ 与 {@link #load()} 的关键差别：**不截断到 400 行**。
         *
         * 🔴 为什么不复用 `text`：页面显示可以只给尾部（用户是站着看的，能接受"只显示最后
         *   400 行"的提示），而导出的文件是**交付物** —— 交给别人之后没人会去追问
         *   "最前面那几百行去哪了"。两者混用一次，就会在**没有任何征兆**的情况下
         *   丢掉启动阶段的关键日志。
         *
         * ⚠️ 超过 {@link #EXPORT_MAX_BYTES} 时读**尾部**并置 {@link #wholeTruncated}
         *   （宁可丢老内容也不 OOM；调用方会写明"只导出最后 N"）。
         */
        String fullText() {
            if (wholeLoaded) return whole;
            wholeLoaded = true;
            if (file == null || !file.isFile()) { whole = null; return whole; }
            try {
                if (file.length() <= EXPORT_MAX_BYTES) {
                    whole = Util.readText(file);          // 已按行读，\r 已被丢掉
                } else {
                    wholeTruncated = true;
                    whole = readTailBytes(file, EXPORT_MAX_BYTES);
                }
                // 尾部字节路径没有逐行处理过 ⇒ 这里统一 CRLF（导出的文件要么给人看、
                // 要么被人拿去 grep，混着 \r 是纯噪声）
                if (whole != null) whole = whole.replace("\r\n", "\n").replace('\r', '\n');
            } catch (IOException e) {
                whole = "read failed: " + e;
            } catch (OutOfMemoryError e) {
                whole = "file too large: " + file.length() + " B";
            }
            return whole;
        }
    }

    /**
     * 只读文件尾部的 n 字节。
     * ⚠️ 截断点可能落在 UTF-8 多字节序列中间 ⇒ 头部会出现一个 U+FFFD。
     *    这是有意的取舍：为了读一个几 MB 的日志去扫整个文件不值得，
     *    而"第一个字符是乱码"对排障毫无影响。
     */
    private static String readTailBytes(File f, int n) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            long len = raf.length();
            long start = Math.max(0, len - n);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            return new String(buf, "UTF-8");
        } finally {
            raf.close();
        }
    }
}
