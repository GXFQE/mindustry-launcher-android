package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
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
 * M3：存档槽分配（长按详情 → 存档槽）+ 入口进 SavesActivity（备份/恢复/.msav/体检）。
 */
public class MainActivity extends BaseActivity {

    private static final int REQ_IMPORT = 41;

    private List<Versions.Entry> mEntries;

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
     * 开发直通口：am start --es dev_xxx ... 直接驱动某条管线，
     * 供 adb 端到端验证（SAF 选择器 / 弹窗流程无法自动化）。
     * ★ 仅 debuggable 构建生效；发布形态（debuggable=false）里整段直接跳过。
     */
    private void checkDevIntent(Intent intent) {
        if (intent == null) return;
        boolean isDev = intent.hasExtra("dev_import_path")
                || intent.hasExtra("dev_msav_path")
                || intent.hasExtra("dev_m3_selftest")
                || intent.hasExtra("dev_saves")
                || intent.hasExtra("dev_launch_key")
                || intent.hasExtra("dev_health")
                || intent.hasExtra("dev_new_slot")
                || intent.hasExtra("dev_assign_key")
                || intent.hasExtra("dev_settings")
                || intent.hasExtra("dev_setting_dump")
                || intent.hasExtra("dev_default_slot")
                || intent.hasExtra("dev_max_logs")
                || intent.hasExtra("dev_theme")
                || intent.hasExtra("dev_policy_set")
                || intent.hasExtra("dev_autobackup_test")
                || intent.hasExtra("dev_zip_list")
                || intent.hasExtra("dev_zip_import")
                || intent.hasExtra("dev_zip_confirm")
                || intent.hasExtra("dev_zip_export")
                || intent.hasExtra("dev_cas_stats")
                || intent.hasExtra("dev_cas_gc")
                || intent.hasExtra("dev_cas_migrate")
                || intent.hasExtra("dev_compat")
                || intent.hasExtra("dev_backup_restore")
                || intent.hasExtra("dev_logs_export")
                || intent.hasExtra("dev_mods_scan")
                || intent.hasExtra("dev_mods_toggle")
                || intent.hasExtra("dev_mods_import")
                || intent.hasExtra("dev_mods_copy")
                || intent.hasExtra("dev_mods_conflict")
                || intent.hasExtra("dev_maps_slot")
                || intent.hasExtra("dev_map_import")
                || intent.hasExtra("dev_maps_page")
                || intent.hasExtra("dev_mapstats");
        if (!isDev) return;
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Toast.makeText(this, R.string.dev_blocked_toast, Toast.LENGTH_SHORT).show();
            return;
        }

        String devPath = intent.getStringExtra("dev_import_path");
        if (devPath != null && !devPath.isEmpty()) {
            File f = new File(devPath);
            startImport(Uri.fromFile(f), f.getName());
            return;
        }

        // F18：对任意 APK 只跑兼容性探测（不导入、不启动）—— 给"按能力探测"留可复查的证据
        String compatPath = intent.getStringExtra("dev_compat");
        if (compatPath != null && !compatPath.isEmpty()) {
            devCompat(compatPath);
            return;
        }

        String msavPath = intent.getStringExtra("dev_msav_path");
        if (msavPath != null && !msavPath.isEmpty()) {
            String slot = intent.getStringExtra("dev_msav_slot");
            runMsavPipeline(new File(msavPath), (slot == null || slot.isEmpty())
                    ? Data.currentSlot(this) : slot);
            return;
        }

        if (intent.hasExtra("dev_saves")) {
            startActivity(new Intent(this, SavesActivity.class));
            return;
        }

        // F2：槽分配的自动化回归口（SAF/弹窗无法自动点，只能靠 extra 驱动）
        String newSlot = intent.getStringExtra("dev_new_slot");
        if (newSlot != null && !newSlot.isEmpty()) {
            String err = Data.createSlot(this, newSlot);
            alert(err == null ? getString(R.string.dev_new_slot_ok_fmt, newSlot)
                              : getString(R.string.dev_new_slot_fail), err == null ? newSlot : err);
            rescan();
            return;
        }
        String asKey = intent.getStringExtra("dev_assign_key");
        if (asKey != null && !asKey.isEmpty()) {
            String s = intent.getStringExtra("dev_assign_slot");
            if (s == null) s = "";
            Config.get().setSlotOf(asKey, s);
            rescan();
            alert(getString(R.string.dev_assign_title),
                    getString(R.string.dev_assign_fmt, asKey, s.isEmpty() ? Data.SLOT_DEFAULT : s));
            return;
        }

        // 走**与点击列表完全相同的路径**（含 slotFor 的配置解析），
        // 用于验证「版本 → 槽」分配真的生效，而不是只验证 GameSlot 能跑。
        String launchKey = intent.getStringExtra("dev_launch_key");
        if (launchKey != null && !launchKey.isEmpty()) {
            rescan();
            Versions.Entry hit = null;
            if (mEntries != null) {
                for (Versions.Entry e : mEntries) {
                    if (launchKey.equals(e.key())) { hit = e; break; }
                }
            }
            if (hit == null) {
                alert(getString(R.string.dev_launch_miss_title),
                        getString(R.string.dev_launch_miss_fmt, launchKey, keysOf()));
                return;
            }
            startVersion(hit);
            return;
        }

        if (intent.hasExtra("dev_m3_selftest")) {
            runSelftest();
            return;
        }

        if (intent.hasExtra("dev_health")) {
            boolean clean = "1".equals(intent.getStringExtra("dev_health"));
            runHealth(clean);
            return;
        }

        // ── F3：设置页 / 设置项 ────────────────────────────────────────────
        if (intent.hasExtra("dev_settings")) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        String dumpTag = intent.getStringExtra("dev_setting_dump");
        if (dumpTag != null && !dumpTag.isEmpty()) {
            devDumpSettings(dumpTag);
            return;
        }
        String defSlot = intent.getStringExtra("dev_default_slot");
        if (defSlot != null) {
            Config.get().setDefaultSlot(defSlot);
            String back = Config.get().defaultSlot();
            rescan();
            alert("dev_default_slot",
                    "写入 \"" + defSlot + "\" → 读回 \"" + back + "\""
                    + "\n（空串 = 回落内置 default）");
            return;
        }
        String logsN = intent.getStringExtra("dev_max_logs");
        if (logsN != null && !logsN.isEmpty()) {
            Config.get().setMaxLogFiles(intExtra(intent, "dev_max_logs", Config.DEF_LOG_FILES));
            alert("dev_max_logs",
                    "写入 " + logsN + " → 读回 " + Config.get().maxLogFiles()
                    + "\n（非数字 / 越界会被 Config.intOf 退回默认并打 WARNING 点名键名）");
            return;
        }

        // ── F15：深浅色 ─────────────────────────────────────────────────────
        String themeArg = intent.getStringExtra("dev_theme");
        if (themeArg != null && !themeArg.isEmpty()) {
            Config.get().setThemeMode(themeFromArg(themeArg));
            int night = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            writeDevReport("dev_theme 写入 \"" + themeArg + "\" → 读回 mode="
                    + Config.get().themeMode() + "（0=跟随系统 1=浅色 2=深色）\n"
                    + "重建前 uiMode night = "
                    + (night == android.content.res.Configuration.UI_MODE_NIGHT_YES ? "YES" : "NO")
                    + "\n（本页随即 recreate()；重建后应换成对应配色 —— 判据取背景像素）");
            // ★★ 必须先把 extra 摘掉再 recreate —— `recreate()` 会**重放原来的 intent**，
            //    而 onCreate 里就会跑 checkDevIntent(getIntent()) ⇒ 不清的话重建后又读到
            //    dev_theme、又重建…**无限重建**（屏幕一直黑、uiautomator 报
            //    "could not get idle state"，看着像"主题把界面搞崩了"）。
            //    这是"用 dev 口驱动重建"的通病，以后任何 dev 口要 recreate 都得照做。
            //    （onNewIntent 那条路里 setIntent 存的就是同一个对象，所以清一次就够。）
            intent.removeExtra("dev_theme");
            recreate();
            return;
        }

        // ── F5：自动备份 ───────────────────────────────────────────────────
        String polSlot = intent.getStringExtra("dev_policy_set");
        if (polSlot != null && !polSlot.isEmpty()) {
            boolean en = !"0".equals(intent.getStringExtra("dev_policy_enabled"));
            Config.get().setBackupPolicy(polSlot, en,
                    intExtra(intent, "dev_policy_min", Config.DEF_BACKUP_MIN_MINUTES),
                    intExtra(intent, "dev_policy_max", Config.DEF_BACKUP_MAX));
            Config.BackupPolicy p = Config.get().backupPolicy(polSlot);
            alert("dev_policy_set", polSlot + "\nenabled=" + p.enabled
                    + " min=" + p.minMinutes + " max=" + p.maxBackups);
            return;
        }
        String abSlot = intent.getStringExtra("dev_autobackup_test");
        if (abSlot != null && !abSlot.isEmpty()) {
            devAutoBackupTest(abSlot);
            return;
        }

        // ── F6c：整槽 zip 导入（SAF 选择器无法自动点，只能靠 extra 驱动） ──
        String zipList = intent.getStringExtra("dev_zip_list");
        if (zipList != null && !zipList.isEmpty()) {
            devZipList(new File(zipList));
            return;
        }
        String zipImp = intent.getStringExtra("dev_zip_import");
        if (zipImp != null && !zipImp.isEmpty()) {
            // 模式：`dev_zip_mode` 优先；老的 `dev_zip_wipe 1` 等价于"覆盖"（见 devZipImport 注释）
            String modeStr = intent.getStringExtra("dev_zip_mode");
            int mode = SlotWrite.UPDATE;
            if (modeStr != null && !modeStr.trim().isEmpty()) {
                try {
                    mode = Integer.parseInt(modeStr.trim());
                } catch (NumberFormatException ignored) {
                    mode = SlotWrite.UPDATE;
                }
            } else if ("1".equals(intent.getStringExtra("dev_zip_wipe"))) {
                mode = SlotWrite.REPLACE;
            }
            devZipImport(new File(zipImp), intent.getStringExtra("dev_zip_slot"), mode);
            return;
        }
        String zipConfirm = intent.getStringExtra("dev_zip_confirm");
        if (zipConfirm != null && !zipConfirm.isEmpty()) {
            // 整槽导入的**确认框**（带三个模式选择器）——SAF 选包那一步换成路径，其余与界面同路。
            SlotIo.devReadZip(this, new File(zipConfirm), intent.getStringExtra("dev_zip_slot"),
                    new SlotOps.Host() {
                        @Override public void onSlotChanged() { rescan(); }
                    });
            return;
        }
        String zipExp = intent.getStringExtra("dev_zip_export");
        if (zipExp != null && !zipExp.isEmpty()) {
            devZipExport(intent.getStringExtra("dev_zip_slot"), new File(zipExp));
            return;
        }

        // ── F16：CAS 对象池 ────────────────────────────────────────────────
        String restSlot = intent.getStringExtra("dev_backup_restore");
        if (restSlot != null && !restSlot.isEmpty()) {
            devBackupRestore(restSlot);
            return;
        }
        if (intent.hasExtra("dev_cas_stats")) {
            devCasReport(false, false);
            return;
        }
        if (intent.hasExtra("dev_cas_gc")) {
            devCasReport(true, false);
            return;
        }
        if (intent.hasExtra("dev_cas_migrate")) {
            devCasReport(false, true);
            return;
        }

        // ── F20：日志导出 ──────────────────────────────────────────────────
        String logsExp = intent.getStringExtra("dev_logs_export");
        if (logsExp != null && !logsExp.isEmpty()) {
            devLogsExport(new File(logsExp));
            return;
        }

        // ── F13：模组只读扫描 ──────────────────────────────────────────────
        // 用法：`--ez dev_mods_scan true`（当前槽）或 `--es dev_mods_scan <槽名>`（指定槽）。
        // ★ 两个形态共用这一个口：`--ez` 传的是 boolean，getStringExtra 拿到 null ⇒ 回落当前槽。
        // ★ 结果只落盘（`hub/report-devtool.txt`）—— 报告可能几百行，弹窗看不全。
        if (intent.hasExtra("dev_mods_scan")) {
            String raw = intent.getStringExtra("dev_mods_scan");
            final String use = (raw == null || raw.trim().isEmpty() || "true".equals(raw.trim()))
                    ? Data.currentSlot(this) : raw.trim();
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        rep = ModsActivity.report(MainActivity.this, use);
                    } catch (Throwable t) {
                        rep = "dev_mods_scan 失败：" + t;
                    }
                    reportDev(rep, "dev_mods_scan · 槽 " + use);
                }
            }, "dev-mods-scan").start();
            return;
        }

        // ── F13 第二阶段：启停（写 settings.bin）────────────────────────────
        // 用法：`--es dev_mods_toggle <槽名> --es dev_mods_name <内部名> [--ez dev_mods_on true|false]`
        // 不带 dev_mods_on ⇒ 取反当前值。结果写 `hub/report-devtool.txt`（含备份路径与自检结论）。
        String tgSlot = intent.getStringExtra("dev_mods_toggle");
        String tgName = intent.getStringExtra("dev_mods_name");
        if (tgSlot != null && !tgSlot.isEmpty() && tgName != null && !tgName.isEmpty()) {
            final String useSlot = "true".equals(tgSlot) ? Data.currentSlot(this) : tgSlot;
            final String useName = tgName;
            final Boolean want = intent.hasExtra("dev_mods_on")
                    ? Boolean.valueOf("true".equals(intent.getStringExtra("dev_mods_on"))
                            || intent.getBooleanExtra("dev_mods_on", false))
                    : null;
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        Mods.Scan s = Mods.scan(MainActivity.this, useSlot);
                        boolean on = want != null ? want.booleanValue() : !currentEnabled(s, useName);
                        SettingsBin.Result r = Mods.setEnabled(MainActivity.this, useSlot, useName, on);
                        rep = "槽 = " + useSlot + "  内部名 = " + useName
                                + "  目标 = " + (on ? "启用" : "关闭") + "\n\n" + r.report();
                    } catch (Throwable t) {
                        rep = "dev_mods_toggle 失败：" + t;
                    }
                    reportDev(rep, "dev_mods_toggle");
                }
            }, "dev-mods-toggle").start();
            return;
        }

        // ── F13 第三阶段：模组包导入 / 跨槽复制 ─────────────────────────────
        // 导入：`--es dev_mods_import <源zip绝对路径> [--es dev_mods_slot <槽>] [--ez dev_mods_overwrite true]`
        // 复制：`--es dev_mods_copy <源槽> --es dev_mods_copy_to <目标槽> [--ez dev_mods_overwrite true]`
        String imp = intent.getStringExtra("dev_mods_import");
        if (imp != null && !imp.isEmpty()) {
            final File src = new File(imp);
            String s0 = intent.getStringExtra("dev_mods_slot");
            final String useSlot = (s0 == null || s0.trim().isEmpty()
                    || "true".equals(s0.trim())) ? Data.currentSlot(this) : s0.trim();
            final boolean ow = intent.getBooleanExtra("dev_mods_overwrite", false)
                    || "true".equals(intent.getStringExtra("dev_mods_overwrite"));
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        File dir = new File(Data.dirOf(MainActivity.this, useSlot), "mods");
                        Mods.PackResult r = Mods.importPackage(MainActivity.this, dir, src, ow,
                                Mods.trashDirOf(MainActivity.this));
                        rep = "槽 = " + useSlot + "  源 = " + src.getAbsolutePath()
                                + "  覆盖 = " + ow + "\n\n" + r.report(MainActivity.this);
                        if (r.ok && r.meta != null) {
                            Mods.Target t = Mods.targetsFor(MainActivity.this, useSlot);
                            rep += "指向本槽的版本 = " + (t.any() ? t.label : "（无）")
                                    + "；模组要求 " + r.meta.minGameVersion + " ⇒ "
                                    // ⚠️ 没有版本指向本槽时**不能**说"可加载" ——
                                    // `isAtLeast(0,0,…)` 按游戏的边界恒为 true，
                                    // 但那种情况下游戏根本不会去加载它（界面上也是这么说的）。
                                    + (!t.any() ? "没有版本指向这个槽，装进去也不会被加载"
                                            : (Mods.isAtLeast(t.build, t.revision, r.meta.minGameVersion)
                                                    ? "可加载" : "不会加载")) + "\n";
                        }
                    } catch (Throwable t) {
                        rep = "dev_mods_import 失败：" + t;
                    }
                    reportDev(rep, "dev_mods_import");
                }
            }, "dev-mods-import").start();
            return;
        }
        String cp = intent.getStringExtra("dev_mods_copy");
        String cpTo = intent.getStringExtra("dev_mods_copy_to");
        if (cp != null && !cp.isEmpty() && cpTo != null && !cpTo.isEmpty()) {
            final String fromSlot = cp.trim();
            final String toSlot = cpTo.trim();
            final boolean ow2 = intent.getBooleanExtra("dev_mods_overwrite", false)
                    || "true".equals(intent.getStringExtra("dev_mods_overwrite"));
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        File from = new File(Data.dirOf(MainActivity.this, fromSlot), "mods");
                        File to = new File(Data.dirOf(MainActivity.this, toSlot), "mods");
                        Mods.PackResult r = Mods.copyMods(MainActivity.this, from, to, ow2,
                                Mods.trashDirOf(MainActivity.this));
                        rep = "从 " + fromSlot + " 复制到 " + toSlot + "（覆盖 = " + ow2 + "）\n"
                                + from.getAbsolutePath() + "\n" + to.getAbsolutePath() + "\n\n"
                                + r.report(MainActivity.this);
                    } catch (Throwable t) {
                        rep = "dev_mods_copy 失败：" + t;
                    }
                    reportDev(rep, "dev_mods_copy");
                }
            }, "dev-mods-copy").start();
            return;
        }

        // ── 「存档视为地图」：直接打开某个槽的地图页 ──────────────────────────
        // 用法：`--es dev_maps_page <槽>`
        // ★ 为什么要它：地图页是槽页的子页面（点槽 → 地图），自动化点进去要穿两三层；
        //   而"本槽存档 → 地图"这条路的验收全靠那一页上的行（SAF 那条另有 dev_map_import）。
        String dmp = intent.getStringExtra("dev_maps_page");
        if (dmp != null && !dmp.trim().isEmpty()) {
            Intent mi = new Intent(this, MapsActivity.class);
            mi.putExtra(MapsActivity.EXTRA_SLOT, dmp.trim());
            startActivity(mi);
            finish();
            return;
        }

        // ── 「存档视为地图」：把"选文件"换成路径，直接进地图页那条导入路 ────────────
        // 用法：`--es dev_map_import /sdcard/xxx.msav [--es dev_map_slot <槽>]`
        // ★ 为什么要它：SAF 选择器**自动化不了**（第 104 轮 dev_zip_confirm 同一条理由），
        //   而"选到一份存档之后"的那条路（命名框 → 改写 → 落位 → 结果框）必须能真机验。
        //   ⇒ 后面的路与界面**逐字相同**，只是把"选包"换成了路径。
        String dmImp = intent.getStringExtra("dev_map_import");
        if (dmImp != null && !dmImp.isEmpty()) {
            String useSlot = intent.getStringExtra("dev_map_slot");
            if (useSlot == null || useSlot.trim().isEmpty()) useSlot = Data.currentSlot(this);
            Intent mi = new Intent(this, MapsActivity.class);
            mi.putExtra(MapsActivity.EXTRA_SLOT, useSlot.trim());
            mi.putExtra(MapsActivity.EXTRA_DEV_IMPORT, dmImp.trim());
            startActivity(mi);
            finish();
            return;
        }

        // ── F10：地图清点（本槽 / 游戏自带 / 模组自带）─────────────────────────
        // 用法：`--es dev_maps_slot <槽名>`（空/true = 当前槽）→ `hub/report-devtool.txt`
        // ★ 为什么要这个口：界面入口在"槽操作菜单"里，自动化点开菜单很脆
        //   （2026-10-03 就卡在这一步）⇒ 数据这条链用 dev 口验，界面另验。
        String dms = intent.getStringExtra("dev_maps_slot");
        if (dms != null && !dms.isEmpty()) {
            final String useSlot = ("true".equals(dms.trim()) || dms.trim().isEmpty())
                    ? Data.currentSlot(this) : dms.trim();
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        Mods.Target t = Mods.targetsFor(MainActivity.this, useSlot);
                        java.util.List<Maps.Item> items = Maps.scan(MainActivity.this, useSlot, t.apkPath);
                        StringBuilder sb = new StringBuilder();
                        sb.append("槽 = ").append(useSlot).append('\n');
                        sb.append("目标游戏 = ").append(t.label.isEmpty() ? "（没指到版本）" : t.label).append('\n');
                        sb.append("游戏 APK = ")
                          .append(t.apkPath == null || t.apkPath.isEmpty() ? "（没有）" : t.apkPath).append('\n');
                        sb.append("本槽 ").append(Maps.count(items, Maps.FROM_SLOT))
                          .append(" · 游戏自带 ").append(Maps.count(items, Maps.FROM_GAME))
                          .append(" · 模组自带 ").append(Maps.count(items, Maps.FROM_MOD))
                          .append("（共 ").append(items.size()).append("）\n\n");
                        for (Maps.Item it : items) {
                            sb.append("· [").append(it.sourceLabel(MainActivity.this)).append("] ")
                              .append(it.name())
                              .append("  |  ").append(it.line(MainActivity.this))
                              .append("  |  ").append(it.where).append('\n');
                        }
                        rep = sb.toString();
                    } catch (Throwable t2) {
                        rep = "dev_maps_slot 失败：" + t2;
                    }
                    reportDev(rep, "dev_maps_slot · 槽 " + useSlot);
                }
            }, "dev-maps").start();
            return;
        }

        // ── F13 第四阶段：模组间冲突体检 ────────────────────────────────────
        // 用法：`--es dev_mods_conflict <槽名>`（空/true = 当前槽）→ `hub/report-devtool.txt`
        String cfl = intent.getStringExtra("dev_mods_conflict");
        if (cfl != null && !cfl.isEmpty()) {
            final String useSlot = ("true".equals(cfl.trim()) || cfl.trim().isEmpty())
                    ? Data.currentSlot(this) : cfl.trim();
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        Mods.Scan s = Mods.scan(MainActivity.this, useSlot);
                        Mods.Conflict c = Mods.findConflicts(MainActivity.this, s.mods,
                                new File(Data.dirOf(MainActivity.this, useSlot), "last_log.txt"));
                        rep = "槽 = " + useSlot + "\n\n" + c.report(MainActivity.this);
                    } catch (Throwable t) {
                        rep = "dev_mods_conflict 失败：" + t;
                    }
                    reportDev(rep, "dev_mods_conflict · 槽 " + useSlot);
                }
            }, "dev-mods-conflict").start();
            return;
        }
        // ── F21：地图资源统计（真机计时 + 逐项数字）─────────────────────────────
        // 用法：`--es dev_mapstats <槽名>`（空/true = 当前槽）[--es dev_mapstats_file <某个 .msav>]
        // ★ 为什么要这个口：① 设备性能 F21 只在 PC 上量过（估计 20~40 ms/张）；
        //   ② 界面入口要点开"地图 → 某张图"，自动化很脆；③ 报告要能落盘慢慢看。
        String dstat = intent.getStringExtra("dev_mapstats");
        if (dstat != null && !dstat.isEmpty()) {
            devMapStats(dstat, intent.getStringExtra("dev_mapstats_file"));
            return;
        }
    }

    /** F21 的 dev 口：跑一遍统计并把**逐项数字 + 真机耗时**落到 `hub/report-devtool.txt` */
    private void devMapStats(final String slotArg, final String fileArg) {
        final String useSlot = ("true".equals(slotArg.trim()) || slotArg.trim().isEmpty())
                ? Data.currentSlot(this) : slotArg.trim();
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("槽 = ").append(useSlot).append('\n');
                long t0 = System.currentTimeMillis();
                try {
                    Mods.Target tgt = Mods.targetsFor(MainActivity.this, useSlot);
                    sb.append("目标游戏 = ").append(tgt.label.isEmpty() ? "（没指到版本）" : tgt.label)
                      .append('\n');
                    sb.append("游戏 APK = ")
                      .append(tgt.apkPath == null || tgt.apkPath.isEmpty() ? "（没有）" : tgt.apkPath)
                      .append('\n');
                    java.util.Map<String, String> labels = MapStatsMods.attrLabels(MainActivity.this);
                    java.util.List<Maps.Item> items = new java.util.ArrayList<>();
                    if (fileArg != null && !fileArg.trim().isEmpty()) {
                        Maps.Item one = new Maps.Item();
                        one.from = Maps.FROM_SLOT;
                        one.source = "dev";
                        one.file = new File(fileArg.trim());
                        one.where = one.file.getAbsolutePath();
                        one.bytes = one.file.length();
                        one.meta = Maps.metaOf(one);
                        items.add(one);
                    } else {
                        items.addAll(Maps.scan(MainActivity.this, useSlot, tgt.apkPath));
                    }
                    if (items.size() > 60) items = items.subList(0, 60);
                    long min = Long.MAX_VALUE, max = 0, sum = 0;
                    int ok = 0;
                    for (Maps.Item it : items) {
                        MapStatsMods.Built b =
                                MapStatsMods.run(MainActivity.this, useSlot, it, tgt.apkPath, labels);
                        if (b.error != null && !b.error.isEmpty()) {
                            sb.append("❌ ").append(it.name()).append("  ").append(b.error).append('\n');
                            continue;
                        }
                        MapStats.Result r = b.result;
                        ok++;
                        min = Math.min(min, r.millis);
                        max = Math.max(max, r.millis);
                        sum += r.millis;
                        sb.append("· ").append(it.name()).append("  ").append(r.width).append('x')
                          .append(r.height).append("  统计 ").append(r.millis).append(" ms\n");
                        // 两类矿的口径（第 84 轮）：**地矿**（地板上的矿，用地钻）用「能采」口径；
                        // **墙矿**（墙里的矿，要用墙钻）只报总格数、不报遮挡（见 MapDetailActivity）
                        sb.append("    地矿: ").append(rowsOf(r.ores, false)).append('\n');
                        if (!r.oreWalls.isEmpty()) {
                            sb.append("    墙矿: ").append(rowsOf(r.oreWalls, true)).append('\n');
                        }
                        sb.append("    可采地板: ").append(rowsOf(r.floors, false)).append('\n');
                        sb.append("    加成地板: ").append(rowsOf(r.bonuses, false)).append('\n');
                        sb.append("    挡住资源的: ").append(blockersOf(r)).append('\n');
                        if (r.unkOre + r.unkFloor + r.unkBonus > 0) {
                            sb.append("    认不出的格子: 矿 ").append(r.unkOre).append(" / 地板 ")
                              .append(r.unkFloor).append(" / 加成 ").append(r.unkBonus).append('\n');
                        }
                    }
                    sb.append("\n表 = ").append(MapStats.vanilla().size()).append(" 条原版");
                    sb.append("\n统计成功 ").append(ok).append('/').append(items.size()).append(" 张");
                    if (ok > 0) {
                        sb.append("；单张 最小 ").append(min).append(" ms / 平均 ")
                          .append(sum / ok).append(" ms / 最大 ").append(max).append(" ms");
                    }
                    sb.append("\n整口用时 ").append(System.currentTimeMillis() - t0).append(" ms\n");
                } catch (Throwable t) {
                    sb.append("dev_mapstats 失败：").append(t).append('\n');
                }
                reportDev(sb.toString(), "dev_mapstats · 槽 " + useSlot);
            }
        }, "dev-mapstats").start();
    }

    /**
     * dev 报告用：把一类的每一行拼成一行文字。
     *
     * 🔴 与界面**同一口径**（用户 2026-10-04 指出"沙子的数量怎么两个值"）：
     *   头号数字一律「能采 R」，总数与遮挡情况放括号里 —— 报告与截图对不上就没法当证据。
     */
    private static String rowsOf(java.util.List<MapStats.Row> rows, boolean wallKind) {
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

    private static String blockersOf(MapStats.Result r) {
        if (r.blockers == null || r.blockers.isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        for (MapStats.Row row : r.blockers) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(row.label).append(' ').append(row.total).append(" 格");
        }
        return sb.toString();
    }

    /** dev 口用：从扫描结果里取一个模组当前的启用态（取不到 ⇒ 按游戏默认 true） */
    private static boolean currentEnabled(Mods.Scan s, String internalName) {
        for (Mods.Info m : s.mods) {
            if (internalName.equals(m.internalName)) return m.enabled;
        }
        return true;
    }

    /**
     * F16：把某个槽**最新的一份**快照恢复回去（跳过界面直接驱动 {@link Backup#restore}）。
     * 用法：`--es dev_backup_restore &lt;槽名&gt; [--es dev_restore_pick &lt;序号 0=最新&gt;]`
     *
     * ★ 为什么单开一个口：`dev_m3_selftest` 只在几十字节的假数据上验过恢复，而
     *   "大文件 + 流式还原"正是这次改造回归风险最高的地方（旧实现 `copyRecursive`
     *   直接写目标；新的走 `tmp + rename` 且边写边算 sha256）。
     *   真机判据与 F6/F6c 一致：**md5 逐字节比对**。
     */
    private void devBackupRestore(final String slot) {
        final int pick = intExtra(getIntent(), "dev_restore_pick", 0);
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                try {
                    List<Backup.Snapshot> list = Backup.list(MainActivity.this, slot);
                    sb.append("dev_backup_restore 槽=\"").append(slot)
                      .append("\" 快照数=").append(list.size()).append('\n');
                    if (list.isEmpty()) {
                        sb.append("⚠ 没有快照\n");
                    } else {
                        int i = Math.max(0, Math.min(pick, list.size() - 1));
                        Backup.Snapshot ss = list.get(i);
                        sb.append("选中 v").append(ss.version).append(" 快照：")
                          .append(ss.title()).append("  ").append(ss.count)
                          .append(" 个文件 ").append(Util.formatSize(ss.bytes)).append('\n');
                        Backup.RestoreResult rr =
                                Backup.restore(MainActivity.this, slot, ss);
                        sb.append(rr.ok ? "OK" : "FAILED").append('\n')
                          .append(rr.report).append('\n');
                    }
                } catch (Throwable t) {
                    sb.append("FAILED: ").append(t).append('\n');
                }
                reportDev(sb.toString(), "dev_backup_restore");
            }
        }, "dev-backup-restore").start();
    }

    /**
     * F16：CAS 对象池的「体检 / 回收 / 旧格式迁移」三合一入口。
     * 用法：`--ez dev_cas_stats true` / `--ez dev_cas_gc true` / `--ez dev_cas_migrate true`
     * （`--es` 也行，本口只判 hasExtra）
     *
     * ★ 全在后台线程：扫池（3 万对象）+ 遍历旧格式 `files/`（几百 MB）都是 I/O 密集，
     *   放主线程会 ANR。报告走 {@link #reportDev}，同样落 `hub/report-devtool.txt`。
     * ★ 先报**统计**再执行动作 —— 这样一份报告里就能看到"动作前 vs 动作后"的差别。
     */
    private void devCasReport(final boolean doGc, final boolean doMigrate) {
        final long t0 = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                try {
                    sb.append("casBase = ")
                      .append(Backup.casBaseDir(MainActivity.this).getAbsolutePath()).append('\n');
                    sb.append("hasLegacy = ").append(Backup.hasLegacy(MainActivity.this)).append('\n');

                    long t1 = System.currentTimeMillis();
                    Backup.PoolStats st = Backup.poolStats(MainActivity.this);
                    sb.append("— 统计（").append(System.currentTimeMillis() - t1).append(" ms） —\n");
                    sb.append("快照 ").append(st.snapshots).append(" 份（其中旧格式 ")
                      .append(st.legacySnapshots).append("）\n");
                    sb.append("逻辑总量 ").append(Util.formatSize(st.logical)).append('\n');
                    sb.append("对象池 ").append(st.objects).append(" 个对象 / ")
                      .append(Util.formatSize(st.actual)).append('\n');
                    sb.append("孤儿 ").append(st.orphans).append(" 个 / ")
                      .append(Util.formatSize(st.orphanBytes)).append('\n');
                    sb.append("旧格式实际占用 ").append(Util.formatSize(st.legacyBytes)).append('\n');
                    sb.append("节省率 ").append(String.format(java.util.Locale.US, "%.1f%%",
                            st.saved() * 100)).append('\n');

                    if (doMigrate) {
                        long t2 = System.currentTimeMillis();
                        String rep = Backup.migrateLegacy(MainActivity.this);
                        sb.append("— 迁移（").append(System.currentTimeMillis() - t2).append(" ms） —\n");
                        sb.append(rep == null ? "无旧格式快照\n" : rep);
                    }
                    if (doGc) {
                        long t3 = System.currentTimeMillis();
                        int n = Backup.gcNow(MainActivity.this);
                        sb.append("— GC（").append(System.currentTimeMillis() - t3).append(" ms） —\n");
                        sb.append("删除孤儿对象 ").append(n).append(" 个\n");
                    }
                } catch (Throwable t) {
                    sb.append("FAILED: ").append(t).append('\n');
                }
                sb.append("总耗时 ").append(System.currentTimeMillis() - t0).append(" ms\n");
                reportDev(sb.toString(), "dev_cas");
            }
        }, "dev-cas").start();
    }

    /**
     * F6c：把「导出整槽」跑一遍写到**设备上的绝对路径**（跳过 SAF 选择器）。
     * 走的是与界面完全相同的 {@link Data#contentRoots} + {@link Exporter#zipTo}，
     * 目的只有一个：把"这次到底打包了哪些条目"变成可自动取证的产物。
     * 用法：`--es dev_zip_export <绝对路径.zip> [--es dev_zip_slot <槽>]`
     * ⚠️ 路径必须落在应用自己够得着的地方（`Android/data/io.mdt.launcher/…`）——
     *   targetSdk 36 下直接写 `/sdcard/Download` 会 EACCES。
     */
    private void devZipExport(final String slotArg, final File dest) {
        final String slot = (slotArg == null || slotArg.isEmpty())
                ? Data.currentSlot(this) : slotArg;
        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder sb = new StringBuilder();
                sb.append("dev_zip_export 槽=\"").append(slot).append("\" -> ")
                  .append(dest.getAbsolutePath()).append('\n');
                try {
                    List<File> roots = Data.contentRoots(MainActivity.this, slot);
                    sb.append("roots(").append(roots.size()).append(") = ");
                    for (File f : roots) sb.append(f.getName()).append(f.isDirectory() ? "/ " : " ");
                    sb.append('\n');
                    if (dest.exists() && !dest.delete()) {
                        sb.append("⚠ 旧文件删不掉，会接着写\n");
                    }
                    Exporter.Result r = Exporter.zipTo(MainActivity.this, Uri.fromFile(dest),
                            Data.dirOf(MainActivity.this, slot), roots);
                    sb.append("zip: files=").append(r.files).append(" raw=").append(r.rawBytes)
                      .append(" out=").append(r.outBytes).append('\n');
                    sb.append("落盘大小=").append(dest.length()).append('\n');
                } catch (Throwable t) {
                    sb.append("FAILED: ").append(t).append('\n');
                }
                reportDev(sb.toString(), "dev_zip_export");
            }
        }, "dev-zip-export").start();
    }

    /**
     * F6c：**只看包**（= {@link SlotZip#inspect}），不解包。
     * 用法：`--es dev_zip_list /sdcard/xxx.zip`
     * ★ 存在的意义：解包会改盘，而"这个包到底被认成什么格式"必须能单独验 ——
     *   否则判错了只能靠事后翻存档列表反推。
     */
    private void devZipList(final File src) {
        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder sb = new StringBuilder();
                sb.append("dev_zip_list ").append(src.getAbsolutePath()).append('\n');
                try {
                    File tmp = SlotZip.stage(MainActivity.this, Uri.fromFile(src));
                    try {
                        SlotZip.Info inf = SlotZip.inspect(MainActivity.this, tmp);
                        sb.append("files=").append(inf.files)
                          .append(" dirs=").append(inf.dirs)
                          .append(" bytes=").append(inf.bytes)
                          .append(" sizeKnown=").append(inf.sizeKnown).append('\n');
                        sb.append("nativeFormat=").append(inf.nativeFormat)
                          .append(" hasSaves=").append(inf.hasSaves)
                          .append(" known=").append(inf.known)
                          .append(" skipped=").append(inf.skipped).append('\n');
                        sb.append("strip=\"").append(inf.strip).append("\"\n");
                        sb.append("tops=").append(inf.tops).append('\n');
                    } finally {
                        SlotZip.unstage(tmp);
                    }
                } catch (Throwable t) {
                    sb.append("FAILED: ").append(t).append('\n');
                }
                reportDev(sb.toString(), "dev_zip_list");
            }
        }, "dev-zip-list").start();
    }

    /**
     * F6c：直接解包（跳过 SAF 选包），走与界面**完全相同**的
     * {@link SlotZip#stage} → {@link SlotZip#inspect} → {@link SlotZip#extract} 三步。
     *
     * 用法：`--es dev_zip_import /sdcard/xxx.zip [--es dev_zip_slot <槽>] [--es dev_zip_mode <0|1|2>]`
     *   mode：0 = 更新（默认，只覆盖同名）/ 1 = 补齐（同名保留槽里的）/ 2 = 覆盖（先清空）。
     *   ⚠️ 旧的 `--es dev_zip_wipe 1` 仍认，等价于 mode=2（2026-10-05 第 104 轮把
     *      "要不要清空"扩成了三个模式，老脚本不该因此失效）。
     */
    private void devZipImport(final File src, final String slotArg, final int modeArg) {
        final String slot = (slotArg == null || slotArg.isEmpty())
                ? Data.currentSlot(this) : slotArg;
        final int mode = SlotWrite.sane(modeArg);
        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder sb = new StringBuilder();
                sb.append("dev_zip_import ").append(src.getAbsolutePath())
                  .append(" -> 槽 \"").append(slot).append("\"  mode=").append(mode).append('\n');
                try {
                    File tmp = SlotZip.stage(MainActivity.this, Uri.fromFile(src));
                    try {
                        SlotZip.Info inf = SlotZip.inspect(MainActivity.this, tmp);
                        sb.append("inspect: files=").append(inf.files)
                          .append(" native=").append(inf.nativeFormat)
                          .append(" strip=\"").append(inf.strip).append("\"\n");
                        SlotZip.Result r = SlotZip.extract(MainActivity.this, tmp, inf, slot, mode);
                        sb.append("extract: files=").append(r.files)
                          .append(" bytes=").append(r.bytes)
                          .append(" skipped=").append(r.skipped)
                          .append(" kept=").append(r.kept)
                          .append(" wiped=").append(r.wiped).append('\n');
                        sb.append("槽目录=").append(Data.dirOf(MainActivity.this, slot)).append('\n');
                    } finally {
                        SlotZip.unstage(tmp);
                    }
                } catch (Throwable t) {
                    sb.append("FAILED: ").append(t).append('\n');
                }
                reportDev(sb.toString(), "dev_zip_import");
            }
        }, "dev-zip-import").start();
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

    /**
     * dev_compat：对任意 APK 跑一次 F18 兼容性探测并落盘（**不导入、不启动**）。
     *
     * ★ 为什么值得单独开一个口：判据是"**按能力**探测"而不是版本号 ⇒ 必须能对着**任意样本**
     *   取证（归档包里 30 个包、用户随时补进来的新包，还得能对照新旧两版）。
     *   弹窗截图没法自动化，报告可以 —— 落 `hub/report-devtool.txt`。
     */
    private void devCompat(final String path) {
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("=== dev_compat ").append(new java.util.Date()).append(" ===\n");
                sb.append("APK  = ").append(path).append('\n');
                File f = new File(path);
                sb.append("存在 = ").append(f.exists())
                        .append("   大小 = ").append(f.length()).append(" B\n\n");
                boolean ok = false;
                try {
                    Compat.Probe p = Compat.probe(f);
                    sb.append(Compat.describe(p));
                    ok = p.runnable();
                    if (!ok) {
                        sb.append("\n拒绝文案 = ")
                                .append(Compat.rejectReason(MainActivity.this, p).replace('\n', ' '))
                                .append('\n');
                    }
                } catch (Throwable t) {
                    sb.append("探测异常: ").append(t).append('\n');
                }
                reportDev(sb.toString(), ok ? "dev_compat: 可进管线" : "dev_compat: 拒绝");
            }
        }, "dev-compat").start();
    }

    /**
     * dev_logs_export：把"全部日志"导出成**一个文件**并落盘（F20）。
     *
     * 用法：`--es dev_logs_export &lt;绝对路径.txt&gt;`
     *
     * ★ 为什么必须有这个口：导出本身走 SAF 选择器（系统界面），**没法自动化点** ——
     *   而"导出的内容对不对"恰恰是唯一值得验的东西（漏一段不会崩、不会报错）。
     *   这个口走与 {@link LogActivity#compose} **同一个**拼装函数，只是把落点
     *   换成 adb 给的路径 ⇒ 取回来逐段比对即可。
     * ⚠️ 落点用 {@link Util#atomicWriteText}（先写 .tmp 再改名）：避免 adb 在
     *   写一半时把文件取走、拿到半截内容后误判成"导出有 bug"。
     */
    private void devLogsExport(final File dst) {
        new Thread(new Runnable() {
            @Override public void run() {
                String rep;
                try {
                    LogActivity.Snap s = LogActivity.readAll(MainActivity.this);
                    String text = LogActivity.compose(MainActivity.this, s,
                            LogActivity.exportHeader(MainActivity.this));
                    Util.atomicWriteText(dst, text);
                    rep = "dev_logs_export ok\n"
                            + "目标 = " + dst.getAbsolutePath() + "\n"
                            + "正文 = " + text.length() + " 字符 / " + text.getBytes("UTF-8").length + " B\n"
                            + "落盘 = " + dst.length() + " B\n"
                            + (LogActivity.hasAnyLog(s) ? "" : "⚠️ 三个源一个都不存在（导出的是空壳）\n");
                } catch (Throwable t) {
                    rep = "dev_logs_export failed: " + t;
                }
                final String f = rep;
                runOnUiThread(new Runnable() {
                    @Override public void run() { reportDev(f, "dev_logs_export"); }
                });
            }
        }, "dev-logs-export").start();
    }

    /** 开发口的统一收尾：写 hub/report-devtool.txt + 弹出来（用户看不到 logcat） */
    private void reportDev(final String text, final String title) {
        android.util.Log.i("MDTLauncher", title + ": " + text.replace("\n", " | "));
        String path = "(写报告失败)";
        try {
            File out = new File(Data.hubDir(MainActivity.this), "report-devtool.txt");
            Util.atomicWriteText(out, text);
            path = out.getAbsolutePath();
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "report write failed: " + t);
        }
        final String fp = path;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                alert(title, text + "\n" + fp);
                rescan();
            }
        });
    }

    /**
     * F15：**只落盘、不弹窗**的 dev 报告。
     *
     * ★ 为什么不能复用 {@link #reportDev}：给"调用后紧接着 recreate()"的口用 ——
     *   弹窗属于旧 Activity，重建时被销毁（白弹一次），更要命的是遮罩会**叠起来**：
     *   本轮实测连调三次 `dev_theme`，三层 AlertDialog 的 dim 相乘把整屏压成纯黑，
     *   `screencap` 出来一片 #000000、`uiautomator dump` 直接报 could not get idle state，
     *   看起来像"主题把界面搞崩了"，实际只是遮罩叠层（**差点误判成缺陷**）。
     */
    private void writeDevReport(String text) {
        android.util.Log.i("MDTLauncher", "dev report: " + text.replace("\n", " | "));
        try {
            File out = new File(Data.hubDir(MainActivity.this), "report-devtool.txt");
            Util.atomicWriteText(out, text);
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "report write failed: " + t);
        }
    }

    private static int intExtra(Intent i, String key, int def) {
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
    private static int themeFromArg(String s) {
        String v = s == null ? "" : s.trim().toLowerCase(java.util.Locale.US);
        if ("light".equals(v) || "1".equals(v)) return ThemeMode.LIGHT;
        if ("dark".equals(v) || "2".equals(v)) return ThemeMode.DARK;
        return ThemeMode.SYSTEM;
    }

    /**
     * F3：把设置与各槽策略写进 hub/report-settings.txt。
     * ★ 本 ROM 会滤掉应用自己的 main logcat ⇒ 运行期证据一律落文件（本工程固定纪律）。
     */
    private void devDumpSettings(String tag) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(tag).append("]\n");
            sb.append("default_slot=").append(Config.get().defaultSlot()).append('\n');
            sb.append("max_log_files=").append(Config.get().maxLogFiles()).append('\n');
            // F15：主题（0=跟随系统 1=浅色 2=深色）+ 本次实例实际的 night 位
            sb.append("theme_mode=").append(Config.get().themeMode()).append('\n');
            sb.append("session.slot=").append(Config.get().sessionSlot())
              .append("  session.at=").append(Config.get().sessionStartedAt()).append('\n');
            List<Data.Slot> slots = Data.allSlots(this);
            sb.append("slots=").append(slots.size()).append('\n');
            for (Data.Slot s : slots) {
                Config.BackupPolicy p = Config.get().backupPolicy(s.name);
                sb.append("  policy[").append(s.name).append("] enabled=").append(p.enabled)
                  .append(" min=").append(p.minMinutes)
                  .append(" max=").append(p.maxBackups).append('\n');
            }
            File dir = Data.hubDir(this);
            File out = new File(dir, "report-settings.txt");
            Util.atomicWriteText(out, sb.toString());
            alert("dev_setting_dump", out.getAbsolutePath() + "\n\n" + sb);
        } catch (Throwable t) {
            alert("dev_setting_dump failed", String.valueOf(t));
        }
    }

    /**
     * F5 的自动化回归：伪造一次启动记账 → 触发结算 → 读回结果。
     *
     * 用法：`--es dev_autobackup_test &lt;槽名&gt; [--es dev_ab_min &lt;门槛分钟&gt;]`
     * 默认把门槛设成 0 ⇒ playtime(≈0) >= 0 恒成立 ⇒ 必定走到快照那一步。
     * （真实门槛 20 分钟没法在自动化里等，所以这里改的是门槛而不是时钟。）
     */
    private void devAutoBackupTest(final String slot) {
        final int min = intExtra(getIntent(), "dev_ab_min", 0);
        Config.get().setBackupPolicy(slot, true, min,
                Config.get().backupPolicy(slot).maxBackups);
        AutoBackup.noteStart("dev:autobackup", slot);
        AutoBackup.settle(this);            // 游戏没跑 ⇒ 内部判 :game 已死成立

        new Thread(new Runnable() {
            @Override public void run() {
                AutoBackup.awaitIdle(15000);    // 等后台快照收尾
                final StringBuilder sb = new StringBuilder();
                sb.append("dev_autobackup_test slot=").append(slot)
                  .append(" min=").append(min).append('\n');
                sb.append("结算后 session.slot=\"").append(Config.get().sessionSlot())
                  .append("\"  at=").append(Config.get().sessionStartedAt())
                  .append("  （应为空串/0 = 已清账）\n");
                List<Backup.Snapshot> list = Backup.list(MainActivity.this, slot);
                sb.append("现在备份数=").append(list.size()).append('\n');
                for (Backup.Snapshot ss : list) {
                    sb.append("  · ").append(ss.title()).append("  ")
                      .append(ss.count).append(" 个文件 ")
                      .append(Util.formatSize(ss.bytes)).append('\n');
                }
                try {
                    Util.atomicWriteText(
                            new File(Data.hubDir(MainActivity.this), "report-devtool.txt"),
                            sb.toString());
                } catch (Throwable t) {
                    sb.append("写报告失败: ").append(t);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() { alert("dev_autobackup_test", sb.toString()); }
                });
            }
        }).start();
    }

    /** 开发路径：走与界面完全相同的 .msav 落盘逻辑（Msav.stage/commit） */
    private void runMsavPipeline(final File src, final String slot) {
        final ProgressDialog pd = ProgressDialog.show(this, getString(R.string.dev_msav_title),
                getString(R.string.dev_msav_running_fmt, src.getName(), slot), true, false);
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
                        alert(fe == null ? getString(R.string.dev_msav_done)
                                          : getString(R.string.dev_msav_failed),
                                fe == null
                                        ? getString(R.string.dev_msav_done_fmt, fd,
                                                Util.formatSize(bytes), String.valueOf(gz))
                                        : fe);
                        rescan();
                    }
                });
            }
        }).start();
    }

    private void runSelftest() {
        final ProgressDialog pd = ProgressDialog.show(this, getString(R.string.selftest_title),
                getString(R.string.selftest_running), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String r = SelfTest.runM3(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        // ★ F17c：结论同时放进**标题** —— AlertDialog 的标题不随正文滚动，
                        //   于是"过没过"在任何滚动位置都可见（一张截图即可判读）。
                        alert(getString(R.string.selftest_result) + " · " + SelfTest.summaryOf(r), r);
                        rescan();
                    }
                });
            }
        }).start();
    }

    private void runHealth(final boolean clean) {
        final ProgressDialog pd = ProgressDialog.show(this, getString(R.string.health_title),
                getString(clean ? R.string.health_cleaning : R.string.health_scanning), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String r = SelfTest.runHealthReport(MainActivity.this, clean);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        alert(getString(R.string.health_title), r);
                        rescan();
                    }
                });
            }
        }).start();
    }

    /** 全类弹窗的唯一入口 —— ★ 2026-10-04 起在这里挡"已销毁的 Activity"：
     *  导入 APK / 兼容性复检 / 启动失败都是**后台任务收尾**时弹的，落在转屏销毁的实例上
     *  会抛 `WindowManager$BadTokenException` 直接闪退（见 {@link Util#dead}）。 */
    private void alert(String title, String msg) {
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    /** 当前可用版本 key 列表（dev 直通口报错时给出） */
    private String keysOf() {
        if (mEntries == null || mEntries.isEmpty()) return getString(R.string.keys_empty);
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

        Util.bindAction(root, R.id.row_add, R.drawable.ic_add, R.string.act_add_title,
                R.string.act_add_sub, new Runnable() {
                    @Override public void run() { promptAddPackage(); }
                });
        Util.bindAction(root, R.id.row_import, R.drawable.ic_download, R.string.act_import_title,
                R.string.act_import_sub, new Runnable() {
                    @Override public void run() { pickApkViaSaf(); }
                });
        Util.bindAction(root, R.id.row_saves, R.drawable.ic_folder, R.string.act_saves_title,
                R.string.act_saves_sub, new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(MainActivity.this, SavesActivity.class));
                    }
                });
        // ★ 导航重构（REF §56，用户 2026-10-03 定案）：主界面**不再有「模组」这一行** ——
        //   模组住在槽内部，入口统一收进「存档与备份 → 点一个槽 → 模组」那一页。
        //   （原来那行只是"拿当前槽进模组页"，而这正是槽二级页面要做的事。）
        Util.bindAction(root, R.id.row_settings, R.drawable.ic_settings,
                R.string.act_settings_title, R.string.act_settings_sub, new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                });

        // 版本列表：容器是 LinearLayout，条目在 rebuildList() 里全展开挂载。
        // ★ 为什么不用 ListView —— 见 rebuildList() 的注释（F3b 根因）。
        mListContainer = (ViewGroup) root.findViewById(R.id.ver_container);
        mEmpty = (TextView) root.findViewById(R.id.ver_empty);
        mEmpty.setText(R.string.empty_versions);

        Util.applySystemInsets(root);
        setContentView(root);
    }

    private void rescan() {
        mEntries = Versions.scanAll(this);
        computeConflicts();
        rebuildList();
        if (mStatusSummary != null) {
            File dataRoot = Data.dataRoot(this);
            mStatusSummary.setText(getString(R.string.main_summary_fmt,
                    mEntries.size(), Data.currentSlot(this)));
            mStatusDetail.setText(getString(R.string.main_detail_fmt,
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
            bindVersionRow(v, e);
            mListContainer.addView(v);
        }
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
        sub.setText(e.imported
                ? getString(R.string.row_imported_sub_fmt,
                        e.importFile == null ? "" : e.importFile, Util.formatSize(e.apkSize))
                : getString(R.string.row_sub_fmt, e.pkg, Util.formatSize(e.apkSize)));

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
            conflict.setText(getString(R.string.row_conflict_fmt, share.peer));
        } else {
            conflict.setVisibility(View.GONE);
        }

        // 可见的改槽入口。长按详情里那条仍保留 —— 两条路都走 pickSlot，结果一致。
        // ★ 槽按钮自己消费点击（它是 clickable），不会冒到整行 —— 所以两个动作不冲突。
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
        String full = (note == null) ? plain : plain + getString(R.string.row_upstream_fmt, note);
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
        mContinueText.setText(getString(R.string.main_continue_fmt, e.displayName()));
        mBtnContinue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startVersion(e); }
        });
    }

    // ---- M1: SAF 导入 -------------------------------------------------------

    private void pickApkViaSaf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.android.package-archive");
        startActivityForResult(Intent.createChooser(i, getString(R.string.chooser_pick_apk)), REQ_IMPORT);
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

    private void startImport(final Uri uri, final String name) {
        final android.app.ProgressDialog pd = android.app.ProgressDialog.show(
                this, getString(R.string.import_progress_title),
                name + getString(R.string.import_progress_suffix), true, false);
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
                                    getString(R.string.import_done_fmt,
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
    private void startVersion(final Versions.Entry e) {
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
                .setMessage(getString(R.string.launch_conflict_msg_fmt,
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
                getString(R.string.launch_progress_title),
                getString(R.string.launch_progress_msg_fmt, e.displayName()), true, false);
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
                            alert(getString(R.string.compat_reject_title), msg);
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
            names[i] = s.name + (s.active ? getString(R.string.slot_current_suffix) : "")
                    + "  " + getString(R.string.slot_entry_fmt, s.files, Util.formatSize(s.bytes));
            if (s.name.equals(cur)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.slot_pick_title_fmt, e.label))
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Config.get().setSlotOf(e.key(), slots.get(w).name);
                        d.dismiss();
                        Toast.makeText(MainActivity.this,
                                getString(R.string.slot_set_fmt, slots.get(w).name),
                                Toast.LENGTH_SHORT).show();
                        rescan();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.slot_manage, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        startActivity(new Intent(MainActivity.this, SavesActivity.class));
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
        String slotLine = getString(R.string.detail_slot_line_fmt, Versions.slotFor(e))
                + getString(R.string.detail_slot_hint);
        // ★ 标题/正文里显示的是**剥过构建前缀**的版本号 ⇒ 这里把原文还回来。
        //   剥前缀是有损显示，详情弹窗是唯一能对照"界面值从哪来"的地方。
        String rawLine = e.versionTrimmed()
                ? getString(R.string.detail_raw_ver_fmt, e.rawVersion()) : "";
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
        String upLine = (up != null) ? getString(R.string.detail_upstream_fmt, up) : "";
        // ★ F8：已装版本也给"只读事实"（构建号 / 架构 / 位置 / 大小），并**说明为什么不给删**。
        //   已装 = 系统里那一份，删它会连累别的用同一份安装的应用 ⇒ 只读是**设计**，不是漏做。
        final String head = e.subtitle(this) + rawLine + upLine + slotLine
                + getString(e.imported ? R.string.detail_imported_note : R.string.detail_installed_note);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(e.displayName())
                .setMessage(head + getString(R.string.detail_probing))
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
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing() && dlg.isShowing()) dlg.setMessage(head + detail);
                    }
                });
            }
        }, "version-detail").start();
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
        sb.append(getString(R.string.detail_md5_fmt, md5 == null || md5.isEmpty() ? "?" : md5));
        String abis = "";
        try {
            abis = TextUtils.join("、", Compat.probe(new File(e.apkPath)).apkAbis);
        } catch (Throwable ignored) {
        }
        sb.append(getString(R.string.detail_abi_fmt,
                abis.isEmpty() ? getString(R.string.detail_abi_unknown) : abis));
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
        if (code > 0) sb.append(getString(R.string.detail_build_fmt, code));
        return sb.toString();
    }

    private void confirmDeleteImport(final Versions.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_import_title)
                .setMessage(getString(R.string.delete_import_msg_fmt, e.displayName()))
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
