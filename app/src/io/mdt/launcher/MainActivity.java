package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主界面（主进程 = UI 进程；游戏退出带走的是 :game 进程，这里不受影响）。
 *
 * M0：已装版本扫描 + 手填包名。
 * M1：SAF 导入 APK（ACTION_OPEN_DOCUMENT → Importer 流式拷贝 + 预热预检）+ 导入项删除。
 * M2：点击版本 → GameSlot 六步加载配方。
 * M3：存档槽分配（长按详情 → 存档槽）+ 入口进 SlotsActivity（备份/恢复/.msav/体检）。
 */
    // ★ 下面这一批是**包内可见**（不是 private）：`app/src-dev/io/mdt/launcher/DevTools.java`
    //   （dev 直通口，**只在 debuggable 构建里编进包**）要调它们 —— 见 docs/DEVELOPING.md「dev 源集」。
    //   产品版没有 DevTools 的真实现，所以这些成员照样只有本类能碰。
public class MainActivity extends BaseActivity {

    private static final int REQ_IMPORT = 41;

    List<Versions.Entry> mEntries;

    // F3b：版本列表容器（LinearLayout，条目代码挂载）+ 空态
    private ViewGroup mListContainer;
    private TextView mEmpty;

    // F1b：顶部状态卡（摘要行 + 可展开的完整路径）与主按钮「继续上次」
    private TextView mStatusSummary;
    private TextView mStatusDetail;
    private View mBtnContinue;
    private TextView mContinueText;

    /**
     * 一个版本的「同槽共用」情况。由 `rescan()` → {@link #computeConflicts()} 重算。
     *
     * ★ 为什么把"提示"和"拦截"塞进**同一个对象**、在**同一个循环**里算出来：
     *   它们是同一件事的两个面（"有别人和我共用这个槽" ⟹ 要不要拦住启动），
     *   拆成两张表就会出现两套判据 —— 而不一致时**没有任何症状**，
     *   只会在某天变成"徽标亮着但启动不拦"这种谁都说不清的状态（REF §35.11）。
     */
    private static final class Share {
        /** 对手描述（带书名号 / 组内 3+ 报总数），行内徽标与启动弹窗共用这一份 */
        final String peer;
        /** 启动前是否要拦（见 {@link #computeConflicts()} 的口径说明） */
        final boolean block;

        Share(String peer, boolean block) {
            this.peer = peer;
            this.block = block;
        }
    }

    /**
     * ★★ **启动流程的互斥标志**（2026-10-04 修）—— 防"连点启动"。
     *
     * 为什么必须有：{@link #startVersionConfirmed} 从按下到真正拉起 `GameSlot` 之间要
     * ① 等自动备份收尾（上限 8 s）、② 读一次 APK 做兼容性复检。这段时间原来界面上
     * **没有任何变化**，用户会以为没点上而再点一次 —— 每次点击都会**再起一条线程**，
     * 而 `GameSlot` 在清单里没写 `launchMode`（默认 standard）⇒ 第二个实例会把整条
     * 注入管线在同一个 `:game` 进程里**重跑一遍**。
     *
     * ⚠️ 用 **static** 而不是实例字段：转屏会重建 Activity，实例字段挡不住"重建后那一份"。
     *   同 {@link SlotIo} 的待办目标（那边也是 static，理由同类）。
     * ⚠️ 但光有 static 会让"点过一次就再也点不动"（线程走完没人清）⇒
     *   **同时在 {@link #onResume()} 里清**：回到主界面就说明上一次启动流程已经结束了。
     */
    private static boolean sLaunching;

    /** 版本 key → 同槽共用情况（没有共用就不在此表）。 */
    private final HashMap<String, Share> mConflict = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.i("MDTLauncher", "MainActivity onCreate");
        buildUi();
        checkDevIntent(getIntent());
        // F16：旧格式备份自动转 CAS（后台、幂等、静默）。
        // 放在这里而不是存档页 —— 启动器一打开就维护，用户不必"先去看一眼备份"。
        Backup.autoMigrateAsync(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        android.util.Log.i("MDTLauncher", "MainActivity onNewIntent");
        setIntent(intent);
        checkDevIntent(intent);
    }
    /**
     * dev 直通口的入口（`am start --es dev_xxx …`）。
     * ★ 实现整块在 {@link DevTools} —— 那 1200 行**只在 debuggable 构建里编进包**
     *   （产品版换成 `app/src-release` 的同名空壳），这里刻意只留一行，好让产品包干净。
     */
    private void checkDevIntent(Intent intent) {
        DevTools.check(this, intent);
    }

    /** `名字×N · 名字×N`（dev 报告里压成一行） */
    static String brief(java.util.Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(e.getKey()).append('×').append(e.getValue());
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    /**
     * 一格配置的人话（dev 报告用）。
     * ★ `byte[]`（tag 14）**既可能是逻辑代码、也可能是 MindustryX `canvas` 的原始像素** ⇒
     *   这里只报"多少字节" + 可打印比例，**不猜**（按方块类型分派那条纪律）。
     */
    static String cfgText(Msch m, Msch.Cfg g) {
        if (g == null) return "null";
        String n = Msch.norm(g);
        if (g.tag == 5) {
            String nm = m.contentName(g.ctype, g.cid);
            n += nm == null ? "（认不出名字）" : "(" + nm + ")";
        } else if (g.tag == 14) {
            int printable = 0;
            for (byte b : g.bytes) {
                if (b == '\n' || b == '\r' || b == '\t' || (b >= 32 && b < 127)) printable++;
            }
            n += "  可打印 " + (g.bytes.length == 0 ? 0 : printable * 100 / g.bytes.length) + "%";
        }
        return n;
    }

    /**
     * dev 报告用：把一类的每一行拼成一行文字。
     *
     * 🔴 与界面**同一口径**（用户 2026-10-04 指出"沙子的数量怎么两个值"）：
     *   头号数字一律「能采 R」，总数与遮挡情况放括号里 —— 报告与截图对不上就没法当证据。
     */
    static String rowsOf(java.util.List<MapStats.Row> rows, boolean wallKind) {
        if (rows == null || rows.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (MapStats.Row r : rows) {
            if (n++ > 0) sb.append(" · ");
            sb.append(r.label);
            if (wallKind) {
                // 墙矿：**不报遮挡**（它本身就长在墙里；说"在墙下"是矛盾的措辞）—— 见 MapDetailActivity 的注释
                sb.append(" 共 ").append(r.total).append(" 格");
            } else {
                sb.append(" 能采 ").append(r.reachable()).append(" 格");
                if (r.total != r.reachable()) sb.append("（共 ").append(r.total);
                if (r.buried > 0) sb.append(" · 墙下 ").append(r.buried);
                if (r.loose > 0) sb.append(" · 建筑下 ").append(r.loose);
                if (r.unknown > 0) sb.append(" · 未知 ").append(r.unknown);
                if (r.total != r.reachable()) sb.append('）');
            }
            if (!r.drop.isEmpty()) sb.append("[掉落 ").append(r.drop).append(']');
        }
        return sb.toString();
    }

    static String blockersOf(MapStats.Result r) {
        if (r.blockers == null || r.blockers.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        for (MapStats.Row row : r.blockers) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(row.label).append(' ').append(row.total).append(" 格");
        }
        return sb.toString();
    }

    /** dev 口用：从扫描结果里取一个模组当前的启用态（取不到 ⇒ 按游戏默认 true） */
    static boolean currentEnabled(Mods.Scan s, String internalName) {
        for (Mods.Info m : s.mods) {
            if (internalName.equals(m.internalName)) return m.enabled;
        }
        return true;
    }

    /**
     * 扫一个槽里某一类的**全部**条目，做成批量导出的清单（dev 口用）。
     * ★ 与四个页面上的那份清单同源（同一个 `Maps.scan` / `Blueprints.scan` / `Mods.scan` /
     *   `saves/` 直读）—— 页面按筛选后的列表勾选，dev 口导全部。
     */
    static List<Exporter.Src> collectSrcs(Context ctx, String slot, int kind) {
        List<Exporter.Src> out = new ArrayList<>();
        if (kind == BatchIo.KIND_MAPS || kind == BatchIo.KIND_BLUEPRINTS) {
            String apk = null;
            try {
                apk = Mods.targetsFor(ctx, slot).apkPath;
            } catch (Throwable ignored) {
            }
            if (kind == BatchIo.KIND_MAPS) {
                for (Maps.Item it : Maps.scan(ctx, slot, apk)) {
                    if (it.file != null) out.add(Exporter.Src.ofFile(it.file, it.name()));
                    else if (it.container != null && it.entry != null) {
                        out.add(Exporter.Src.ofEntry(it.container, it.entry, it.name()));
                    }
                }
            } else {
                for (Blueprints.Item it : Blueprints.scan(ctx, slot)) {
                    if (it.file != null) out.add(Exporter.Src.ofFile(it.file, it.name()));
                    else if (it.container != null && it.entry != null) {
                        out.add(Exporter.Src.ofEntry(it.container, it.entry, it.name()));
                    }
                }
            }
            return out;
        }
        if (kind == BatchIo.KIND_SAVES) {
            File[] fs = new File(Data.dirOf(ctx, slot), "saves").listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile() && !f.getName().startsWith(".")) {
                        out.add(Exporter.Src.ofFile(f, f.getName()));
                    }
                }
            }
            return out;
        }
        Mods.Scan sc = Mods.scan(ctx, slot);
        for (Mods.Info m : sc.mods) {
            String name = m.file.getName();
            // ★ 目录形态的模组：导出成 `<目录名>.zip`（内容按目录根写，游戏认这个形状）
            if (m.directory) out.add(Exporter.Src.ofDir(m.file, name + ".zip"));
            else out.add(Exporter.Src.ofFile(m.file, name));
        }
        for (Mods.Info m : sc.broken) {
            if (!m.directory) out.add(Exporter.Src.ofFile(m.file, m.file.getName()));
        }
        return out;
    }

    /**
     * F18：启动前兼容性复检 —— 返回**探测结果**；探测不出来（IO 异常 / 包不存在）返回 null。
     *
     * ★ 返回整个 {@link Compat.Probe} 而不是"拒绝原因串"：F0 起它还要顺带回答第二个问题
     *   ——「该包支不支持 `mindustry.data.dir` 属性注入」（决定走真隔离还是改名交换）。
     *   一次探测回答两个问题；再单独探一遍等于把几十 MB 的 dex 白读一次。
     *
     * ⚠️ 探测**本身**失败（IO 异常、包不存在）一律**不拦** —— 与 {@link Compat} 的保守取向一致：
     *   宁可让下游 `System.load` / `Class.forName` 报它自己的错，也不误伤一个其实能跑的包。
     *   包不存在时也返回 null，交给 GameSlot 报"游戏包不存在或不可读"。
     *
     * ⚠️ 本方法会读 APK（打开 ZipFile + 扫 dex），**必须在后台线程调用**。
     */
    private Compat.Probe compatProbeOf(Versions.Entry e) {
        try {
            if (e == null || e.apkPath == null) return null;
            File apk = new File(e.apkPath);
            if (!apk.exists()) return null;
            return Compat.probe(apk);
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "compat recheck failed: " + t);
            return null;
        }
    }

    static int intExtra(Intent i, String key, int def) {
        if (i == null) return def;
        String s = i.getStringExtra(key);
        if (s == null || s.isEmpty()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** F15：dev_theme 的参数解析 —— system / light / dark（也接受 0 / 1 / 2；其余一律当 system） */
    static int themeFromArg(String s) {
        String v = s == null ? "" : s.trim().toLowerCase(java.util.Locale.US);
        if ("light".equals(v) || "1".equals(v)) return ThemeMode.LIGHT;
        if ("dark".equals(v) || "2".equals(v)) return ThemeMode.DARK;
        return ThemeMode.SYSTEM;
    }

    /** 开发路径：走与界面完全相同的 .msav 落盘逻辑（Msav.stage/commit） */
    void runMsavPipeline(final File src, final String slot) {
        final ProgressDialog pd = ProgressDialog.show(this, Trans.get(MainActivity.this, R.string.dev_msav_title),
                Trans.get(MainActivity.this, R.string.dev_msav_running_fmt, src.getName(), slot), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                Msav.Stage st = null;
                String dest = null;
                try {
                    InputStream in = new FileInputStream(src);
                    st = Msav.stage(MainActivity.this, in, src.getName(), slot);
                    dest = Msav.commit(MainActivity.this, st).getAbsolutePath();
                } catch (Exception e) {
                    err = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                    android.util.Log.w("MDTLauncher", "dev msav failed: " + err, e);
                }
                final String fe = err, fd = dest;
                final boolean gz = st != null && st.zlib;
                final long bytes = st == null ? 0 : st.bytes;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        alert(fe == null ? Trans.get(MainActivity.this, R.string.dev_msav_done)
                                          : Trans.get(MainActivity.this, R.string.dev_msav_failed),
                                fe == null
                                        ? Trans.get(MainActivity.this, R.string.dev_msav_done_fmt, fd,
                                                Util.formatSize(bytes), String.valueOf(gz))
                                        : fe);
                        rescan();
                    }
                });
            }
        }).start();
    }

    void runHealth(final boolean clean) {
        final ProgressDialog pd = ProgressDialog.show(this, Trans.get(MainActivity.this, R.string.health_title),
                getString(clean ? R.string.health_cleaning : R.string.health_scanning), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String r = SelfTest.runHealthReport(MainActivity.this, clean);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        alert(Trans.get(MainActivity.this, R.string.health_title), r);
                        rescan();
                    }
                });
            }
        }).start();
    }

    /** 全类弹窗的唯一入口 —— ★ 2026-10-04 起在这里挡"已销毁的 Activity"：
     *  导入 APK / 兼容性复检 / 启动失败都是**后台任务收尾**时弹的，落在转屏销毁的实例上
     *  会抛 `WindowManager$BadTokenException` 直接闪退（见 {@link Util#dead}）。 */
    void alert(String title, String msg) {
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    /** 当前可用版本 key 列表（dev 直通口报错时给出） */
    String keysOf() {
        if (mEntries == null || mEntries.isEmpty()) return Trans.get(MainActivity.this, R.string.keys_empty);
        StringBuilder sb = new StringBuilder();
        for (Versions.Entry e : mEntries) {
            sb.append("  ").append(e.key()).append('\n');
        }
        return sb.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ★ 回到主界面 ⇒ 上一次启动流程已经结束（游戏起来了 / 用户退出了 / 复检拒了），
        //   清掉启动互斥标志。少了这一句，"点过一次之后就再也点不动"（见 sLaunching）。
        //   ⚠️ 它同时是"线程半路死掉"那条路的兜底清理。
        sLaunching = false;
        // ★ 先同步盘上的配置，再画界面。
        //   `last_version` 是 :game 进程在**游戏启动成功后**才写的（GameSlot → Config.setLastVersion），
        //   而本进程内存里那份 Config 不会自己变（load 幂等）⇒ 不重读的话「继续上次」要
        //   重启启动器才更新（2026-10-01 复现：改盘后走两次 onResume 仍显示旧值）。
        //   放在 rescan() 之前：rescan 里的 lastEntry() 与列表都要拿新值。
        Config.get().reload(this);
        // ★ F0 同理，而且是**新加的跨进程键**：`mdt-legacy-owner.txt` 由 :game 在启动前写
        //   （标记"当前槽的本体被临时借住到 files/ 了"，见 Data.dirOf 第 ③ 条分支）。
        //   本进程内存里那份缓存不会自己变 ⇒ 不重读的话，远古包跑完之后这里会把
        //   当前槽算成"空的 slot-<名>"，而本体其实在 files/。
        Data.reloadLegacyOwner();
        rescan();
        // F5：回到启动器就结算一次自动备份（内部自己判 :game 是否已退出、这局够不够久）。
        // 放这里而不是放游戏进程：它就是"用户回到启动器"这一刻，天然覆盖
        // 「游戏退出→回启动器」与「划掉游戏→以后再开启动器」两条路径。
        // ⚠️ 必须在 reload 之后 —— 结算要读 session / backup_policy，拿旧值会误判。
        AutoBackup.settle(this);
        // ★ F23「崩了马上说一声」（2026-10-08 用户点单）：游戏崩了要**当场**知道，
        //   而不是等用户自己想到去翻「运行日志」。
        //   ① start = 常驻监视（游戏还在前台时靠 Toast，系统浮层看得见）；
        //   ② catchUp = 回到界面这一刻补一次（进程被系统杀过也能补上；判据 = 游戏的 launchid.dat）。
        //   ⚠️ 都放在 rescan/AutoBackup 之后：提示是"锦上添花"，不许挡住主界面自己的刷新。
        CrashAlert.start(this);
        CrashAlert.catchUp(this);
    }

    /**
     * F1b：界面由"代码手搓"改为 inflate 布局（卡片风）。
     * 逻辑（rescan / 冲突计算 / 槽分配 / dev 直通口）一行未动，只换了视图来源。
     */
    private void buildUi() {
        View root = getLayoutInflater().inflate(R.layout.activity_main, null);

        // 顶部状态卡：整卡可点，展开完整路径 —— 长路径不该一上来就糊满屏
        mStatusSummary = (TextView) root.findViewById(R.id.status_summary);
        mStatusDetail = (TextView) root.findViewById(R.id.status_detail);
        Util.bindExpandableCard(root, R.id.status_box, R.id.status_detail, R.id.status_chevron);

        mBtnContinue = root.findViewById(R.id.btn_continue);
        mContinueText = (TextView) root.findViewById(R.id.continue_text);

        // ★ 2026-10-08（第 123 轮，用户点单）：原来这里是**两行**（添加包名 / 导入 APK）。
        //   它们是同一件事的两条路 ⇒ 合成一行「添加游戏」，点开二选一（见 promptAddGame）。
        Util.bindAction(root, R.id.row_add_game, R.drawable.ic_add, R.string.act_add_game_title,
                R.string.act_add_game_sub, new Runnable() {
                    @Override public void run() { promptAddGame(); }
                });
        Util.bindAction(root, R.id.row_saves, R.drawable.ic_folder, R.string.act_saves_title,
                R.string.act_saves_sub, new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(MainActivity.this, SlotsActivity.class));
                    }
                });
        // ★ 导航重构（REF §56，用户 2026-10-03 定案）：主界面**不再有「模组」这一行** ——
        //   模组住在槽内部，入口统一收进「存档与备份 → 点一个槽 → 模组」那一页。
        //   （原来那行只是"拿当前槽进模组页"，而这正是槽二级页面要做的事。）
        // ★ 2026-10-08（第 123 轮第二批，用户：「这个设置功能能不能放右上角的那种？」）：
        //   「设置」那一行**也删了** —— 它现在是顶栏右上角的齿轮（见 onCreateOptionsMenu
        //   与 res/menu/main.xml）。全局 / 低频的入口占页内一行不划算，顶栏右上角才是惯例位置。

        // ★ 工具卡（2026-10-08，第 123 轮，用户点单）：中转站 / 运行日志从设置页搬来。
        //   判据：这两项都不是"设置" —— 一个是 per-hub 的回收站，一个是诊断用的日志页；
        //   设置页只留"能被改的项"（见 activity_main.xml / activity_settings.xml 里的注释）。
        Util.bindAction(root, R.id.row_trash, R.drawable.ic_trash,
                R.string.trash_title, R.string.trash_sub, new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(MainActivity.this, TrashActivity.class));
                    }
                });
        Util.bindAction(root, R.id.row_open_logs, R.drawable.ic_terminal,
                R.string.act_logs_title, R.string.act_logs_sub, new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(MainActivity.this, LogActivity.class));
                    }
                });

        // 版本列表：容器是 LinearLayout，条目在 rebuildList() 里全展开挂载。
        // ★ 为什么不用 ListView —— 见 rebuildList() 的注释（F3b 根因）。
        mListContainer = (ViewGroup) root.findViewById(R.id.ver_container);
        mEmpty = (TextView) root.findViewById(R.id.ver_empty);
        mEmpty.setText(Trans.get(MainActivity.this, R.string.empty_versions));

        Util.applySystemInsets(root);
        setContentView(root);
    }

    // ── 顶栏右上角：设置（第 123 轮第二批）───────────────────────────────────

    /**
     * 主界面顶栏右上角的齿轮 = 「设置」。
     *
     * ★ 为什么是菜单而不是页内一行（用户 2026-10-08：「这个设置功能能不能放右上角的那种？」）：
     *   设置是**全局的、低频的**，而主界面那两张卡是"把一个游戏跑起来"这条主线
     *   （添加游戏 / 存档与备份 / 中转站 / 运行日志）—— 它夹在里面本来就不合群。
     *   顶栏右上角是 Android 上"全局设置"的惯例位置，而本工程本来就有 ActionBar
     *   （AppTheme → android:actionBarStyle）。
     * ★ 菜单是 **per-Activity** 的 ⇒ 别的页面顶栏不会多出这个齿轮。
     * ⚠️ 图标用的是单独一份 `ic_settings_bar`（fillColor = @color/on_appbar）：
     *   **ActionBar 不会替菜单图标上色**，行内那份 `ic_settings` 画上去会看不见。
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        // ★ 标题走 Trans（文案唯一入口那条纪律）：菜单项标题虽然不在顶栏显示，
        //   但它是**无障碍标签**与溢出菜单的文字，用户语言包必须能改到它。
        MenuItem it = menu.findItem(R.id.action_settings);
        if (it != null) it.setTitle(Trans.get(MainActivity.this, R.string.act_settings_title));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    void rescan() {
        mEntries = Versions.scanAll(this);
        computeConflicts();
        rebuildList();
        if (mStatusSummary != null) {
            File dataRoot = Data.dataRoot(this);
            mStatusSummary.setText(Trans.get(MainActivity.this, R.string.main_summary_fmt,
                    mEntries.size(), Data.currentSlot(this)));
            mStatusDetail.setText(Trans.get(MainActivity.this, R.string.main_detail_fmt,
                    dataRoot == null ? "?" : dataRoot.getAbsolutePath(),
                    Data.hubDir(this).getAbsolutePath()));
        }
        updateContinue();
    }

    /**
     * 重建版本列表（F3b）。
     *
     * ★★ 为什么这里手工 addView 而不是用 ListView（2026-10-01 真机实测，别再改回去）：
     *   外层 ScrollView 给子级的高度约束是 **UNSPECIFIED**。ListView 在
     *   `heightMode == UNSPECIFIED` 分支下，AOSP onMeasure 只按
     *   「padding + 单个 item 高」估算整体高度，**不会展开全部条目**。
     *   实测：3 个版本只量到 342px（= 39px paddingBottom + 303px 一行），
     *   于是 ScrollView 的滚动范围少算了三分之二，列表后面的条目永远滚不出来
     *   ——用户看到的现象就是"拖不到最底下"。竖屏内容不满一屏时察觉不到，横屏必现。
     *
     *   而我们的列表本来就要求全展开（滚动交给外层），条目也就几个到十几个，
     *   AbsListView 的回收机制毫无用武之地 —— 换成 LinearLayout 反而更简单直白：
     *   高度天然 wrap_content，也不再把触摸事件掺进嵌套滚动里。
     */
    private void rebuildList() {
        if (mListContainer == null) return;
        mListContainer.removeAllViews();
        int n = (mEntries == null) ? 0 : mEntries.size();
        if (mEmpty != null) mEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        if (n == 0) return;

        LayoutInflater inf = getLayoutInflater();
        for (int i = 0; i < n; i++) {
            final Versions.Entry e = mEntries.get(i);
            View v = inf.inflate(R.layout.item_version, mListContainer, false);
            // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包
            Trans.bind(v, R.id.ver_slot_btn, R.string.row_slot_btn);
            bindVersionRow(v, e);
            mListContainer.addView(v);
        }
        // ★ 一档④：副标题里"这个槽有几份存档"那一段**后台补**（见 fillSlotSaveCounts）
        fillSlotSaveCounts(mEntries);
    }

    /**
     * ★ 2026-10-06（一档④）：把"这个版本的槽里有几份存档"补到行上。
     *
     * ★ 为什么后台 + 按槽去重：`bindVersionRow` 跑在 UI 线程上，而这里要**列目录**；
     *   版本一多就是 N 次遍历。同槽的版本共用一次结果（`map` 去重），整段在后台线程。
     * ★ 为什么只刷那一行：与存档列表那条同一个教训 —— 整片重排会让正在滚动的手感变差。
     * ⚠️ 收尾要比 `mEntries` 的**对象身份**：用户可能已经 re-scan 过（列表换了一批对象），
     *   拿旧的下标去写新列表就是"张冠李戴"。
     */
    private void fillSlotSaveCounts(final List<Versions.Entry> entries) {
        if (entries == null || entries.isEmpty() || mListContainer == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
                for (Versions.Entry e : entries) {
                    String s = Versions.slotFor(e);
                    if (s == null || s.isEmpty() || counts.containsKey(s)) continue;
                    counts.put(s, saveCountIn(s));
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(MainActivity.this) || mEntries != entries) return;
                        int rows = Math.min(mListContainer.getChildCount(), entries.size());
                        for (int i = 0; i < rows; i++) {
                            View v = mListContainer.getChildAt(i);
                            if (v == null) continue;
                            TextView sub = (TextView) v.findViewById(R.id.ver_sub);
                            if (sub == null) continue;
                            Integer c = counts.get(Versions.slotFor(entries.get(i)));
                            sub.setText(rowSubtitle(entries.get(i), c == null ? 0 : c));
                        }
                    }
                });
            }
        }, "slot-save-count").start();
    }

    /**
     * 一个槽里有几份存档 —— **只数文件**（不读 meta）。
     * ★ 与槽页那条"读每一份存档的元数据"刻意不同：槽页只需算一次、可以慢；
     *   这里要按版本行算，只数文件名就够了（".msav" 且不是隐藏文件）。
     */
    private int saveCountIn(String slot) {
        File dir = new File(Data.dirOf(this, slot), "saves");
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".msav")) continue;
            n++;
        }
        return n;
    }

    /**
     * 版本行副标题（**唯一实现**：绑定那一行与后台补"存档 N 份"都走它）。
     * ★ 零份时**不加那一段**（"存档 0 份"没有信息量，还会把行撑到两行）。
     */
    private String rowSubtitle(Versions.Entry e, int saves) {
        if (saves > 0) {
            return e.imported
                    ? Trans.get(MainActivity.this, R.string.row_imported_sub_saves_fmt,
                            e.importFile == null ? "" : e.importFile,
                            Util.formatSize(e.apkSize), saves)
                    : Trans.get(MainActivity.this, R.string.row_sub_saves_fmt, e.pkg,
                            Util.formatSize(e.apkSize), saves);
        }
        return e.imported
                ? Trans.get(MainActivity.this, R.string.row_imported_sub_fmt,
                        e.importFile == null ? "" : e.importFile, Util.formatSize(e.apkSize))
                : Trans.get(MainActivity.this, R.string.row_sub_fmt, e.pkg, Util.formatSize(e.apkSize));
    }

    /** 把一行「版本」填上数据并接上交互（原 Adapter.getView 的逻辑，一字未改）。 */
    private void bindVersionRow(View v, final Versions.Entry e) {
        TextView title = (TextView) v.findViewById(R.id.ver_title);
        TextView sub = (TextView) v.findViewById(R.id.ver_sub);
        TextView badge = (TextView) v.findViewById(R.id.ver_slot_badge);
        TextView conflict = (TextView) v.findViewById(R.id.ver_conflict);
        View slotBtn = v.findViewById(R.id.ver_slot_btn);

        title.setText(buildTitle(e));
        // ★ 副标题**整句走资源**（`row_sub_fmt` / `row_imported_sub_fmt`）——
        //   原来导入项是 `已导入 · ` 前缀串拼出来的，而 aapt2 会剥掉尾随空白
        //   ⇒ 真机上渲染成 `已导入 ·imported · 74.7 MB`（2026-10-04 真机 dump 抓到）。
        // ⚠️ 导入项的 `pkg` 是个**内部占位串**（`Versions` 里写死 "imported"），本来就不该给用户看；
        //   现在导入项显示的是**你导入时的那个文件名**（`importFile`）。
        // ★ 一档④：这里先按"0 份存档"绑一次（进页面立刻有内容），条数由后台补齐 ——
        //   口径**只有一处实现**（{@link #rowSubtitle}）。
        sub.setText(rowSubtitle(e, 0));

        // 槽徽标：一眼看出这个版本落在哪个槽（未分配也显式显示默认槽名，不留空白）
        badge.setText(Versions.slotFor(e));
        // ★ 徽标跟着 peer 走（= 只要有共用就提示），**不跟 block** ——
        //   "隐式共用"不拦启动，但仍要让用户看见"这两个会互相覆盖"；
        //   否则提示会随拦截口径一起消失，而它恰恰是唯一能提前告知的地方。
        Share share = mConflict.get(e.key());
        boolean clash = share != null;
        badge.setBackgroundResource(clash ? R.drawable.badge_slot_warn : R.drawable.badge_slot);
        badge.setTextColor(getResources().getColor(
                clash ? R.color.chip_fg_warn : R.color.chip_fg, getTheme()));

        if (clash) {
            conflict.setVisibility(View.VISIBLE);
            conflict.setText(Trans.get(MainActivity.this, R.string.row_conflict_fmt, share.peer));
        } else {
            conflict.setVisibility(View.GONE);
        }

        // 可见的改槽入口（**直接**弹槽列表，不套第二层）。
        // ★ 槽按钮自己消费点击（它是 clickable），不会冒到整行 —— 所以两个动作不冲突。
        // 🔴 2026-10-06：这里反复试过两版，形状是**用户定的** ——
        //   ① 行内加箭头开详情 ⇒ 宽度不够，版本号被挤成省略号（当场被用户抓到）；
        //   ② 按钮改成"动作表"（换槽 / 看详情）⇒ 用户：「**换槽要二级页面是不是有点不太方便**」
        //      —— 换槽是常用动作，不该多一层。
        //   ⇒ 最终：**按钮 = 直接换槽**；详情仍走长按整行（`showDetail`）。
        //   ⚠️ 想给详情加"看得见的入口"时注意：这一行的横向宽度**一点余量都没有**了
        //      （徽标 + 按钮 ≈ 460px/1224px，最长的标题第二行正好放满），
        //      可行方向只有"多一行"或"并进别的弹窗"，别再往行里塞控件。
        slotBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { pickSlot(e); }
        });

        // 整行：单击启动 / 长按详情。原先是 ListView 的 onItemClick / onItemLongClick，
        // 现在直接挂在行根上（行根变 clickable 后，按下状态会自动播给内部不可点击的子视图，
        // 卡片背景 card_bg_press 的按下高亮照旧生效）。
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { startVersion(e); }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View x) { showDetail(e); return true; }
        });
    }

    /**
     * 版本行主标题（F7）：`名称  版本号`，其中**版本号**降一档字色与字号。
     *
     * ★ 为什么需要这个方法：`Versions.Entry.displayName()` 只给拼接好的纯文本，
     *   而这一行在竖屏下只有 569px 可用宽（右边被「槽徽标 + 槽按钮」吃掉约 460px），
     *   长版本号（如 `8-official-2026.09.X37`）必然被 ellipsize 切掉 ——
     *   而切掉的**恰好是版本号本身**（用户报的「游戏的版本号被折叠了」）。
     *   两件事一起做才够：① item_version.xml 放开到 maxLines=2；
     *   ② 版本号弱化（muted 色 + 0.87 字号）—— 折行后第一行是名称、第二行是版本号，
     *   视觉上仍读作"一行标题"，而不是两行正文。
     *
     * ★ F17c：主标题**不再带「（导入）」**（用户：「那个（导入）看起来很奇怪，放下面去」）。
     *   旧写法 `e.title()` 在竖屏下把 `official-159  159.7（导入）` 折成
     *   `…159.7（导` + `入）`，而副标题本来就有 `已导入 · ` 前缀 ⇒ 上面两行喊同一件事。
     *   现在导入身份只由副标题（`bindVersionRow`）与弹窗标题/正文承担。
     *   ⚠️ 因此 `Entry.title()` 已被删除，这里与其余四处一律取 `displayName()`。
     *
     * ⚠️ 用 indexOf 定位版本号在整串里的位置，而不是拼 `label.length() + 2` ——
     *    displayName() 的拼接格式（两个空格分隔）将来一改，那种写法会静默错位、错染到名称上。
     *
     * 🔴 取值的唯一入口是 `Entry.displayVersion()`，**不能写成 `e.versionName`** ——
     *   那会把**带前缀的原文**拿去 `indexOf`，而 `displayName()` 里已经是剥过的短版本号，
     *   于是必然 `indexOf` 返回 -1 ⇒ 静默退回纯文本、**染色悄悄失效**（不崩、不报错，
     *   只是版本号不再是弱化色，很难注意到）。两处必须同源。
     *
     * ★ 手里**只有原始版本串、没有 Entry** 时（如导入完成 Toast），
     *   用 `Versions.displayVersionOf(raw)` —— 它和 `displayVersion()` 是同一段实现。
     *   ⚠️ 全工程显示版本号的代码只有这两条入口，别再写第三处 substring。
     */
    private CharSequence buildTitle(Versions.Entry e) {
        String plain = e.displayName();
        String ver = e.displayVersion();
        // ★ 第 41 轮：fork 的**基座版本**在这里就备注出来（用户：「可以在MDTX显示中备注
        //   对应MDT版本」）—— 它是"这个版本为什么和官方某一版不互相提示"的依据，
        //   而列表行是用户唯一会主动看的地方（详情弹窗要长按才知道）。
        //   ⚠️ "要不要显示"的判断在 `Entry.upstreamNote()`，与详情弹窗**共用**同一份；
        //      这里只管排版。官方本体返回 null ⇒ 整串与从前逐字相同。
        String note = e.upstreamNote();
        String full = (note == null) ? plain : plain + Trans.get(MainActivity.this, R.string.row_upstream_fmt, note);
        if (ver.isEmpty() || "?".equals(ver)) return full;
        // ⚠️ 在 `plain` 上定位、套用到 `full`（尾注只加在**后面** ⇒ 前缀位置不变）。
        //   反过来在 `full` 上 indexOf 也能跑，但一旦尾注里出现与版本号相同的子串
        //   （基座版本恰好等于显示版本时就可能）就会定位到错的区间，静默染错地方。
        int s = plain.indexOf(ver);
        int t = s + ver.length();
        if (s < 0 || t > plain.length()) return full;   // 定位失败：退回纯文本，绝不崩
        SpannableString ss = new SpannableString(full);
        int muted = getResources().getColor(R.color.fg_muted, getTheme());
        ss.setSpan(new ForegroundColorSpan(muted), s, t, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new RelativeSizeSpan(0.87f), s, t, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        // 尾注与版本号同样处理（弱化成副信息）—— "MindustryX" 是主体，其后都是它的注解。
        if (full.length() > plain.length()) {
            ss.setSpan(new ForegroundColorSpan(muted), t, full.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new RelativeSizeSpan(0.87f), t, full.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /**
     * 同槽冲突：只看**当前能看见的版本**（mEntries）之间的分配。
     * 已卸载版本残留在配置里的分配不算冲突 —— 界面上看不到它，报了用户也无从处理。
     *
     * ★★ F4①b（2026-10-02）：**判据是"组内存在不同的版本号"，不是"组内 ≥2 个条目"。**
     *   只按槽分组会把"同一个游戏的多个副本"（官方 APK 装了一份、又导入了一份 ⇒
     *   两个 key、同一个槽、连显示名都逐字相同）算成冲突，看着像"你跟你自己抢"。
     *   需求原文：「**同一个版本号共用一个槽挺正常的，没必要提示啊（反正也不会有兼容问题）**」。
     *   ⇒ 对手 = {@link Versions#conflictPeers}（格式版本不同的那些，同一版本号只算一个）。
     *   ⚠️ 判据本体在那个**纯函数**里、不在本方法里 —— 这样自检能直接喂输入验它（⑥d）；
     *      塞在这里就只能靠"读真机上的数"验，而那验不出任何错。
     *
     * ★★ 第 40 轮：那里的"版本号"改用 {@link Versions.Entry#formatVersion()}（**数据格式**版本），
     *   于是 **fork 按它声明的基座版本参与比较** —— MindustryX X37 的显示名是
     *   `MindustryX  2026.09.X37`，但基座是官方 `160.1`，所以"官方 160.1 + MindustryX X37"
     *   不算冲突（用户：「**使用MDTX对应版本的MDT也可以不弹提示**」）。
     *   ⚠️ 为此本方法要先给"真有共用"的组补一次基座版本（`resolveUpstreamVersions`，唯一一处 IO）。
     *
     * ★ F17c：原先写成 `l.get(i == 0 ? 1 : 0)` —— **只点名一个对手**。组内 3 个版本时，
     *   每一行都只报"另一个"，用户看到的是片面的（以为只有两个人抢，实际三个）。
     *   现在把"对手描述"交给 {@link Versions#conflictPeerDesc}：2 个点一个，
     *   3 个及以上说数量（`「A」等 2 个版本`）。
     *   ⚠️ 传进去的是**不同版本的总数**（含自己 = `peers.size() + 1`），由 `conflictPeerDesc`
     *      自己减 1 —— "我 + A 等 2 个 = 共 3 个"才自洽（F20 改的口径，见那里的注释）。
     *      ⚠️ 若图省事传 `l.size()`，同版本的副本数会被当成版本数 ⇒ 文案虚报（⑥d 的 ③ 钉着）。
     *   ⚠️ 别在这里手拼书名号 —— 那是 `conflictPeerDesc` 的职责（含"等 N 个版本"的闭合位置）。
     *
     * 值里的 `peer` 是**带书名号的对手描述**，`bindVersionRow` 直接塞进 `row_conflict_fmt`。
     *
     * ★★ `block`（启动前是否拦）的口径 —— **只在组内有人显式指定过槽时才拦**：
     *   {@link Versions#slotFor} 对**未分配**的版本会回落到 `Config.defaultSlot()`，于是
     *   "两个版本都没分配" 也会算出同一个槽名 ⇒ 若一律拦，**全新装机（导入 2 个版本、
     *   什么都没指定）点哪个都弹框**。而那种状态下用户还没做过任何选择：
     *   · 行内徽标本来就在持续提示"共用"，可见且可操作（槽按钮就在旁边）；
     *   · 一个每次启动都弹的框，只会被训练成一键关闭，反而把**真正该拦**的那些
     *     （用户显式把 A、B 都指定进了同一个槽，与他心里的预期不符）稀释掉。
     *   ⇒ 隐式共用只提示、不拦；只要组内有**任何一方**是显式分配的，整组的启动都拦。
     *   ⚠️ 这条口径**只在这里实现**：`startVersion` 只查 `block`、不自己重算组。
     */
    private void computeConflicts() {
        mConflict.clear();
        if (mEntries == null) return;
        // ★★ 第 41 轮改了这里的前提：原先**只在同槽 ≥2 时**才去读 APK 里的基座声明
        //   （那时这个值只有判据用，没有共用就不必知道）。现在**列表行要逐行备注基座版本**
        //   （用户：「可以在MDTX显示中备注对应MDT版本」）⇒ 无论有没有共用都得有值。
        //
        //   ⚠️ 代价要知道：`ZipFile` 只读中央目录 + 一个 ~170 B 条目，**实测 ~2 ms/个**
        //      （770 条目的包），所以 3~10 个版本 ≈ 6~20 ms，留在 UI 线程可接受；
        //      但它**不再是 0 次 IO** 了 —— 这是为"看得见"付的价。
        //      （对比 `Compat.probe` 要读 9.5 MB dex，那个仍然必须后台线程。）
        //   ⚠️ 仍然**不能**提到 `scanAll()` 里：那里是"扫描"，这里是"扫描完的加工"，
        //      而且 `upstreamChecked` 只能防同一批 Entry 内重复读。放这里刚好每次 rescan 一次。
        Versions.resolveUpstreamVersions(mEntries);
        Map<String, List<Versions.Entry>> bySlot = new HashMap<>();
        for (Versions.Entry e : mEntries) {
            String s = Versions.slotFor(e);
            List<Versions.Entry> l = bySlot.get(s);
            if (l == null) { l = new ArrayList<>(); bySlot.put(s, l); }
            l.add(e);
        }
        for (Map.Entry<String, List<Versions.Entry>> en : bySlot.entrySet()) {
            List<Versions.Entry> l = en.getValue();
            // 单条目组不可能有对手（`conflictPeers` 必然返回空），直接跳过省一次分组遍历。
            // ⚠️ 它**不再**承担"省一次 IO"的职责了 —— 基座版本已在方法开头为**全部**条目读过。
            if (l.size() < 2) continue;
            // 组内是否有人**显式**指定过槽（未分配不算）
            boolean anyExplicit = false;
            for (Versions.Entry e : l) {
                if (!Config.get().slotOf(e.key()).isEmpty()) { anyExplicit = true; break; }
            }
            for (Versions.Entry e : l) {
                // ★ 版本号不同的才算对手；同版本号的副本一律不计
                //   ⇒ 不进 mConflict ⇒ 徽标不染色、不提示、启动也不拦（三处同一份判据）
                List<Versions.Entry> peers = Versions.conflictPeers(e, l);
                if (peers.isEmpty()) continue;
                mConflict.put(e.key(), new Share(
                        Versions.conflictPeerDesc(MainActivity.this, peers.get(0).displayName(),
                                peers.size() + 1),
                        anyExplicit));
            }
        }
    }

    /**
     * 「继续上次」的目标版本。
     * ⚠️ `Config.lastVersion()` 存的是 GameSlot 里的**稳定键**（已装 = 包名，导入 = APK 文件名），
     *   **不是** `Entry.key()`（导入版是 "import:" + 文件名）⇒ 两种键都要试。
     * ★ 存的是键、显示的是名 —— 两者解耦，所以改显示文案（如剥掉 `8-official-` 前缀）
     *   不会影响这里的匹配，反之亦然。
     */
    private Versions.Entry lastEntry() {
        String lv = Config.get().lastVersion();
        if (lv == null || lv.isEmpty() || mEntries == null) return null;
        for (Versions.Entry e : mEntries) {
            if (lv.equals(e.key())) return e;
        }
        for (Versions.Entry e : mEntries) {
            if (e.imported && lv.equals(e.importFile)) return e;
        }
        return null;
    }

    /**
     * 「继续上次」按钮。
     * ★ 用 `Entry.displayName()`（名称 + **规范化后**的版本号），与列表行**逐字同一套规则** ——
     *   以前这里只给 `e.label`，于是同一个版本在列表里是「MindustryX  8-official-2026.09.X37」、
     *   在按钮里是「MindustryX」，两种叫法（用户：「继续上次也显示版本号」）。
     *   ⚠️ 别退回 `e.label + " " + e.versionName`：那会重新带上 `8-official-` 前缀。
     *   ⚠️ 版本号在**末尾**，单行按钮里最先被 ellipsize 切掉 —— 布局已放宽到 maxLines=2
     *      （见 activity_main.xml 的 continue_text）。
     */
    private void updateContinue() {
        if (mBtnContinue == null) return;
        final Versions.Entry e = lastEntry();
        if (e == null) {
            mBtnContinue.setVisibility(View.GONE);
            return;
        }
        mBtnContinue.setVisibility(View.VISIBLE);
        mContinueText.setText(Trans.get(MainActivity.this, R.string.main_continue_fmt, e.displayName()));
        mBtnContinue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startVersion(e); }
        });
    }

    // ---- M1: SAF 导入 -------------------------------------------------------

    private void pickApkViaSaf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.android.package-archive");
        startActivityForResult(Intent.createChooser(i, Trans.get(MainActivity.this, R.string.chooser_pick_apk)), REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        startImport(uri, queryDisplayName(uri));
    }

    private String queryDisplayName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
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
        return (p == null || p.isEmpty()) ? "import.apk" : p;
    }

    void startImport(final Uri uri, final String name) {
        final android.app.ProgressDialog pd = android.app.ProgressDialog.show(
                this, Trans.get(MainActivity.this, R.string.import_progress_title),
                name + Trans.get(MainActivity.this, R.string.import_progress_suffix), true, false);
        new Thread() {
            @Override public void run() {
                String err = null;
                org.json.JSONObject entry = null;
                try {
                    entry = Importer.importApk(MainActivity.this, uri, name);
                } catch (Exception e) {
                    err = e.getMessage();
                    if (err == null || err.isEmpty()) err = String.valueOf(e);
                    android.util.Log.w("MDTLauncher", "import failed: " + err, e);
                }
                final String ferr = err;
                final org.json.JSONObject fentry = entry;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (ferr == null) {
                            // ⚠️ version 字段是 Importer 从 APK 里读出来的**原文**
                            //    （形如 8-official-159.7）—— 这里只有裸 JSONObject，
                            //    拿不到 Entry，所以必须走 Versions.displayVersionOf()。
                            //    🔴 曾经写成 fentry.optString("version")，导致导入完成的那一刻
                            //    Toast 报「8-official-159.7」、而同一秒列表行报「159.7」，
                            //    同一个版本两种叫法（用户：「导入的这个版本号也处理下」）。
                            Toast.makeText(MainActivity.this,
                                    Trans.get(MainActivity.this, R.string.import_done_fmt,
                                            fentry.optString("label"),
                                            Versions.displayVersionOf(fentry.optString("version"))),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            // ★ 这里的失败框是 inline 建的（不是 alert()）⇒ 自己判一次：
                            //   导入一个几十 MB 的包时转屏，回调会落到已销毁的实例上
                            //   （见 {@link Util#dead}）。成功那条只出 Toast，不会崩。
                            if (Util.dead(MainActivity.this)) return;
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(R.string.import_failed)
                                    .setMessage(ferr)
                                    .setPositiveButton(R.string.close, null)
                                    .show();
                        }
                        rescan();
                    }
                });
            }
        }.start();
    }

    // ---- 详情 / 删除 ---------------------------------------------------------

    // ---- M2: 启动版本 -------------------------------------------------------

    /**
     * 拉起某个版本 —— **入口**（三个调用点共用：列表行 / 「继续上次」/ dev 直通口）。
     *
     * 只做一件事：F4① **同槽冲突预检**（见方法内注释）。确认后才调
     * {@link #startVersionConfirmed}。
     * ★ 之所以拆成两层而不是在 `startVersionConfirmed` 里判：那个方法的契约是
     *   "按下就一定会拉起游戏"，用户点「仍然启动」之后不该被再问一次；
     *   而且拆开之后"弹过窗的那条路"与"没弹窗的那条路"汇进**同一个**实际启动体，
     *   不会出现两份启动逻辑。
     */
    void startVersion(final Versions.Entry e) {
        // ★ F4①：**毫秒级**同槽冲突预检 —— 0 次 IO、0 个线程。
        //   结论就在 `mConflict` 里（`rescan()` → `computeConflicts()` 早算好了，这里只是查表）。
        //   所以它天然满足 FLOWS 对这条的三条要求（毫秒级 / 只读元数据 / 不卡启动路径）——
        //   而且比"起后台线程再弹"更好：按下到弹窗之间不会有空窗，用户看到的就是"立刻拦一下"。
        //   ⚠️ 也**不要**在这里重算一遍冲突：那会造出第二份判据，与列表行那个徽标
        //      （同一份 `mConflict`）不一致时**不会有任何症状** —— 本项目已多次栽在
        //      "同一显示规则两处实现"上（REF §35.11）。
        //   ⚠️ FLOWS 原步骤里还有一条「当前槽 `saves/` 里有解析失败的 `.msav` → 提示但不阻塞」，
        //      **本轮不做**：它依赖 F10（`.msav` 解析器），而 F10 还没做。硬用"文件大小是否
        //      为 0"之类的假判据去顶替，只会把好存档误报成坏的 —— 宁可先不做。
        final Share share = mConflict.get(e.key());
        if (share == null || !share.block) {
            startVersionConfirmed(e);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.launch_conflict_title)
                .setMessage(Trans.get(MainActivity.this, R.string.launch_conflict_msg_fmt,
                        e.displayName(), share.peer, Versions.slotFor(e)))
                .setPositiveButton(R.string.launch_conflict_go,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                startVersionConfirmed(e);
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 真正的启动流程（预检通过后）。
     *
     * ★ 切版本前**必须**确保 :game 进程已死：游戏 Activity 是 singleTask，
     *   不杀进程直接再 start 时进程被复用、Activity 不重建 —— 跑的还是旧版本，
     *   只是白做一次 dex 注入（探针 §1.8 实测）。正常情况下游戏退出会自己
     *   System.exit(0) 带走整个 :game 进程，所以这里只在"游戏还在跑"时才需要动手。
     */
    private void startVersionConfirmed(final Versions.Entry e) {
        // ★★ 防连点 + 即时反馈（2026-10-04 补，理由见 sLaunching 的注释）：
        //   先挡住第二次点击，再立刻把"正在准备"显示出来 —— 这段最长可能等 8 s。
        //   ⚠️ `ProgressDialog.show(..., true, false)` 的 `false` 是**故意**的：
        //      modal + 不可取消 ⇒ 用户点不到列表行，与 sLaunching 双保险。
        if (sLaunching) return;
        sLaunching = true;
        final ProgressDialog pd = ProgressDialog.show(this,
                Trans.get(MainActivity.this, R.string.launch_progress_title),
                Trans.get(MainActivity.this, R.string.launch_progress_msg_fmt, e.displayName()), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                // F5：先等自动备份收尾 —— 否则"备份正在读槽"会和"游戏起来在写槽"打架。
                // 8s 只是兜底（正常几秒内就结束）；真超时也照常启动，不让用户干等。
                AutoBackup.awaitIdle(8000);

                // ★ F18 兼容性复检（导入时已探过一次，这里再探一次兜底）。要覆盖导入拦不住的两种情形：
                //   ① 这个版本是在加这道拦截**之前**导入的（存量条目）；
                //   ② 系统侧变过（页大小 4 KB ↔ 16 KB 不同设备的槽数据迁移、OTA）。
                //   ⚠️ 复检不过就**不启动** —— 否则用户白等一次 :game 拉起，只看到一个看不懂的错。
                Compat.Probe cp = compatProbeOf(e);
                if (cp != null && !cp.runnable()) {
                    final String msg = e.displayName() + "\n\n" + Compat.rejectReason(MainActivity.this, cp);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // 这条出口也要收掉进度框 + 清标志，否则用户被永久挡住（点不动了）
                            pd.dismiss();
                            sLaunching = false;
                            alert(Trans.get(MainActivity.this, R.string.compat_reject_title), msg);
                        }
                    });
                    return;
                }

                boolean killed = ensureGameProcessDead();
                final Intent i = new Intent(MainActivity.this, GameSlot.class);
                if (e.imported) {
                    i.putExtra("apk", e.apkPath);
                } else {
                    i.putExtra("pkg", e.pkg);
                }
                final String slot = Versions.slotFor(e);
                i.putExtra("slot", slot);
                // ★ F0：顺带把上面那次探测的结论透传下去（该包支不支持属性注入），
                //   :game 就不必把同一个 APK 的 dex 再读一遍。
                //   ⚠️ 探不出来时**不要**放这个 extra —— 让 :game 自己探，
                //      "探测失败该怎么办"只有一处判据（GameSlot.needsLegacySlot）。
                if (cp != null) i.putExtra(GameSlot.EXTRA_LEGACY_SWAP, cp.needsRename());

                // F5：落账（**必须落盘**，且必须在 startActivity 之前）——
                // 游戏会带走 :game 进程、主进程也可能被回收，回来时要能知道这局几点开的。
                AutoBackup.noteStart(e.key(), slot);

                if (killed) {
                    // 给 AMS 一点时间回收，避免新实例与旧进程竞态
                    try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // 收起进度框再拉起游戏（不在这里清 sLaunching：这一局还在跑，
                        // 回到主界面时由 onResume 统一清 —— 见 sLaunching 的注释）
                        pd.dismiss();
                        startActivity(i);
                    }
                });
            }
        }).start();
    }

    /*
     * ★ F13：这里原来有一个私有的 `slotFor(Versions.Entry)`。它已**删除** ——
     *   模组页也要用"哪些版本落到本槽"这条规则，两处各写一份就会出现
     *   "主界面说用 A 槽、模组页按 B 槽算版本要求"这种**没有任何症状**的分叉。
     *   实现只有一处：{@link Versions#slotFor}（口径与注释也搬过去了）。
     */

    /** 给某个版本指定存档槽 */
    private void pickSlot(final Versions.Entry e) {
        final List<Data.Slot> slots = Data.allSlots(this);
        final String[] names = new String[slots.size()];
        String cur = Versions.slotFor(e);
        int checked = 0;
        for (int i = 0; i < slots.size(); i++) {
            Data.Slot s = slots.get(i);
            // ⚠️ 中间那个 "  " 是 Java 字面量（不受 aapt2 影响），而
            //    slot_current_suffix 里写的是 U+0020 的转义（本文件里不能真写它，见 REF §43）—— 见 strings.xml：
            //    **资源的前导空白会被 aapt2 剥掉**，写字面空格会让三项粘成
            //    `default[当前]52 文件 · 208.3 KB`。
            // ★ 统计段复用存档页那条 slot_entry_fmt（同一显示规则只留一处实现）：
            //   原先这里另有一条 slot_entry_suffix_fmt，两份内容只差空格 ⇒
            //   改了一边另一边静默不跟（本工程栽过多次的形态）。
            names[i] = s.name + (s.active ? Trans.get(MainActivity.this, R.string.slot_current_suffix) : "")
                    + "  " + Trans.get(MainActivity.this, R.string.slot_entry_fmt, s.files, Util.formatSize(s.bytes));
            if (s.name.equals(cur)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(Trans.get(MainActivity.this, R.string.slot_pick_title_fmt, e.label))
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Config.get().setSlotOf(e.key(), slots.get(w).name);
                        d.dismiss();
                        Toast.makeText(MainActivity.this,
                                Trans.get(MainActivity.this, R.string.slot_set_fmt, slots.get(w).name),
                                Toast.LENGTH_SHORT).show();
                        rescan();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                // ★ 2026-10-06（用户定案）：**详情并进这个弹窗** ——
                //   行内宽度已用尽（加 22dp 箭头会把版本号挤成省略号），而这里是"点一下就看见"的地方。
                //   原来这个位置的「管理槽…」让位（主页「存档与备份」里仍有管理槽的入口）。
                .setNeutralButton(R.string.row_detail_btn, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        showDetail(e);
                    }
                })
                .show();
    }

    /** 结束 :game 进程；返回是否确实动过手（用于决定要不要等一下） */
    private boolean ensureGameProcessDead() {
        if (!Data.gameAlive(this)) return false;
        Data.killGame(this);
        for (int i = 0; i < 30 && Data.gameAlive(this); i++) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
        }
        return true;
    }

    /**
     * 第 123 轮（2026-10-08，用户点单）：主界面「添加游戏」那一行的二选一。
     *
     * ★ 为什么合成一行：{@link #promptAddPackage()} 与 {@link #pickApkViaSaf()}
     *   是**同一件事的两条路**（把一个游戏版本交给启动器）—— 一条是"手机上已经装了，去扫出来"，
     *   一条是"手上有个 APK 文件，导进来"。并排占两行会被读成"要分两步走"，
     *   而主界面每一行都该是一件独立的事。
     *
     * ★ 为什么弹窗用两行 `row_action`（而不是 `setItems` 的一行一条纯文字）：
     *   两条路的区别恰恰在**副标题**里（走系统文件选择器 / 扫描已装应用），
     *   纯文字列表说不清；而这两行的排版与主界面完全同款 ⇒ 从"两行"变成"弹窗里两行"，
     *   用户看到的东西是连续的，只是不再占主界面的位置。
     *
     * ⚠️ 顺序 = 常用度：导入 APK 在前。⚠️ 与 `dialog_add_game.xml` 里的顺序必须一致。
     * ⚠️ 两个回调都要先 `dismiss()` 再干活：弹窗自己不会因为启动了下一个界面就关掉。
     */
    private void promptAddGame() {
        View box = getLayoutInflater().inflate(R.layout.dialog_add_game, null);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.act_add_game_title)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .create();
        Util.bindAction(box, R.id.row_pick_apk, R.drawable.ic_download,
                R.string.act_import_title, R.string.act_import_sub, new Runnable() {
                    @Override public void run() { dlg.dismiss(); pickApkViaSaf(); }
                });
        Util.bindAction(box, R.id.row_add_pkg, R.drawable.ic_add,
                R.string.act_add_title, R.string.act_add_sub, new Runnable() {
                    @Override public void run() { dlg.dismiss(); promptAddPackage(); }
                });
        dlg.show();
    }

    private void promptAddPackage() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.add_package_hint);
        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_package_title)
                .setMessage(R.string.add_package_msg)
                .setView(box)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Config.get().addKnownPackage(input.getText().toString());
                        rescan();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDetail(final Versions.Entry e) {
        String slotLine = Trans.get(MainActivity.this, R.string.detail_slot_line_fmt, Versions.slotFor(e))
                + Trans.get(MainActivity.this, R.string.detail_slot_hint);
        // ★ 标题/正文里显示的是**剥过构建前缀**的版本号 ⇒ 这里把原文还回来。
        //   剥前缀是有损显示，详情弹窗是唯一能对照"界面值从哪来"的地方。
        String rawLine = e.versionTrimmed()
                ? Trans.get(MainActivity.this, R.string.detail_raw_ver_fmt, e.rawVersion()) : "";
        // ★ 第 40 轮：fork 的"基座版本"必须**说出来**。
        //   它是"这个版本为什么和官方某一版不互相提示"的全部依据（判据用 formatVersion），
        //   不说的话用户只能看到"该提示的没提示"，却无从判断这到底对不对
        //   —— 本项目纪律：**去标记 ≠ 去信息**（§35.12 同款）。
        //   ⚠️ "要不要显示"的口径**不在这里**，在 `Entry.upstreamNote()` —— 列表行的尾注
        //      与这里共用同一份判断（两处各判一次的话，不一致时不会有任何症状，§35.11）。
        //      这里只是"先确保读过"（幂等，详情可能来自一个没进过列表的条目）。
        List<Versions.Entry> one = new ArrayList<>();
        one.add(e);
        Versions.resolveUpstreamVersions(one);
        String up = e.upstreamNote();
        String upLine = (up != null) ? Trans.get(MainActivity.this, R.string.detail_upstream_fmt, up) : "";
        // ★ F8：已装版本也给"只读事实"（构建号 / 架构 / 位置 / 大小），并**说明为什么不给删**。
        //   已装 = 系统里那一份，删它会连累别的用同一份安装的应用 ⇒ 只读是**设计**，不是漏做。
        final String head = e.subtitle(this) + rawLine + upLine + slotLine
                + getString(e.imported ? R.string.detail_imported_note : R.string.detail_installed_note);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(e.displayName())
                .setMessage(head + Trans.get(MainActivity.this, R.string.detail_probing))
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.slot_label, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        pickSlot(e);
                    }
                });
        if (e.imported) {
            b.setNegativeButton(R.string.delete, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    confirmDeleteImport(e);
                }
            });
        }
        final AlertDialog dlg = b.create();
        dlg.show();
        // ★ MD5 与"架构 / 构建号"都得**读整个包**（官方包 75 MB ⇒ MD5 要几百毫秒）——
        //   原来这一步是在 UI 线程现算的：长按之后要愣一下弹窗才出来。
        //   现在先把弹窗显示出来，算完再补上（信息一条不少，只是晚几百毫秒到位）。
        new Thread(new Runnable() {
            @Override public void run() {
                final String detail = probeDetail(e);
                // ★ 一档④：槽里几份存档 + 上次启动的时间 —— 都要碰磁盘/config，与"读包"同一趟后台
                final String facts = slotFacts(e);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing() && dlg.isShowing()) dlg.setMessage(head + facts + detail);
                    }
                });
            }
        }, "version-detail").start();
    }

    /**
     * 详情弹窗里"这个槽"那两行（**后台线程**调用；一档④，2026-10-06）：
     * <pre>
     *   这个槽里有 N 份存档        —— 只数文件（{@link #saveCountIn}），与列表行同一口径
     *   上次玩：10-05 21:30        —— 读 config 里那笔记账（{@link Config#lastPlayed}）
     * </pre>
     * ★ 时间文案复用 {@link Trash#timeText}（"MM-dd HH:mm"，跨年带年份）——
     *   那是全工程唯一的时间格式化实现（中转站列表在用），不再写第二份。
     */
    private String slotFacts(Versions.Entry e) {
        int n = saveCountIn(Versions.slotFor(e));
        StringBuilder sb = new StringBuilder();
        sb.append(n > 0 ? Trans.get(MainActivity.this, R.string.detail_saves_fmt, n)
                        : Trans.get(MainActivity.this, R.string.detail_saves_none));
        long at = Config.get().lastPlayed(e.key());
        sb.append(at > 0 ? Trans.get(MainActivity.this, R.string.detail_played_fmt, Trash.timeText(at))
                         : Trans.get(MainActivity.this, R.string.detail_played_none));
        return sb.toString();
    }

    /**
     * 详情弹窗里那几行"必须读包才知道"的事实（**后台线程**调用）。
     *
     * · MD5：导入项在导入时就记过（`config.json` 里那份），系统装的包没记过 ⇒ 现算；
     * · 架构 / 构建号：读包自己的 `lib/`（{@link Compat#probe}）与清单（`PackageManager`）。
     *
     * ⚠️ 全是**只读**；读不出来就说"读不出来"，不猜、也不拿别的数顶替。
     */
    private String probeDetail(Versions.Entry e) {
        StringBuilder sb = new StringBuilder();
        String md5 = (e.md5 != null && !e.md5.isEmpty()) ? e.md5 : Util.md5(new File(e.apkPath));
        sb.append(Trans.get(MainActivity.this, R.string.detail_md5_fmt, md5 == null || md5.isEmpty() ? "?" : md5));
        String abis = "";
        try {
            abis = TextUtils.join("、", Compat.probe(new File(e.apkPath)).apkAbis);
        } catch (Throwable ignored) {
        }
        sb.append(Trans.get(MainActivity.this, R.string.detail_abi_fmt,
                abis.isEmpty() ? Trans.get(MainActivity.this, R.string.detail_abi_unknown) : abis));
        int code = 0;
        try {
            if (e.imported) {
                android.content.pm.PackageInfo pi =
                        getPackageManager().getPackageArchiveInfo(e.apkPath, 0);
                if (pi != null) code = pi.versionCode;
            } else {
                code = getPackageManager().getPackageInfo(e.pkg, 0).versionCode;
            }
        } catch (Throwable ignored) {
        }
        if (code > 0) sb.append(Trans.get(MainActivity.this, R.string.detail_build_fmt, code));
        return sb.toString();
    }

    private void confirmDeleteImport(final Versions.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_import_title)
                .setMessage(Trans.get(MainActivity.this, R.string.delete_import_msg_fmt, e.displayName()))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Importer.removeImport(MainActivity.this, e.importFile);
                        rescan();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
