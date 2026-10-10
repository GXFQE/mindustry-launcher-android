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
 * dev 直通口（`am start --es dev_xxx …` 那一整套）—— **开发工具，产品版不编进包**。
 *
 * <h3>为什么单独一个文件（2026-10-10）</h3>
 * 这一千多行只被 {@code MainActivity.checkDevIntent} 调用，而它只在 debuggable 构建里真的跑
 * （产品版点下去只会弹「开发直通口在产品版不可用」）⇒ 编进产品包纯属白占 dex。
 * 产品构建用 {@code app/src-release/io/mdt/launcher/DevTools.java}（空壳）顶替，
 * 见 {@code build.sh} 的 {@code [2/4] javac} 与 {@code docs/DEVELOPING.md} 的「dev 源集」。
 *
 * <h3>形状（为什么是"持有宿主"而不是"继承")</h3>
 * 这些方法用到的 15 个 helper（{@code alert}/{@code rescan}/{@code startVersion}…）**产品代码也在用**
 * ⇒ 不能连它们一起搬走。所以这里只持有 {@link MainActivity} 实例，把裸引用改成 {@code a.xxx}：
 * 方法体其余部分**逐字未动**，方法之间的互相调用也一行没改（它们仍是本类的实例方法）。
 *
 * 🔴 **两条纪律**：
 * ① 本类**只在 debuggable 构建里编译** ⇒ 改它的公开面（{@code check} 的签名）要**两种构建都过**；
 * ② 搬回去/搬进来新方法时，别忘了把宿主成员写成 {@code a.xxx}（裸名在这里编译不过，javac 会拦住）。
 */
final class DevTools {

    private final MainActivity a;

    private DevTools(MainActivity a) {
        this.a = a;
    }

    /** 唯一入口：MainActivity 的 onCreate / onNewIntent 各调一次。 */
    static void check(MainActivity a, Intent intent) {
        new DevTools(a).checkDevIntent(intent);
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
                || intent.hasExtra("dev_mods_page")
                || intent.hasExtra("dev_bp_page")
                || intent.hasExtra("dev_saves_page")
                || intent.hasExtra(BlueprintsActivity.EXTRA_DEV_IMPORT)
                || intent.hasExtra("dev_mapstats")
                || intent.hasExtra("dev_crash_analyze")
                || intent.hasExtra("dev_crash_corpus")
                || intent.hasExtra("dev_batch_export")
                || intent.hasExtra("dev_batch_import")
                || intent.hasExtra("dev_msch");
        if (!isDev) return;
        if ((a.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Toast.makeText(a, R.string.dev_blocked_toast, Toast.LENGTH_SHORT).show();
            return;
        }

        String devPath = intent.getStringExtra("dev_import_path");
        if (devPath != null && !devPath.isEmpty()) {
            File f = new File(devPath);
            a.startImport(Uri.fromFile(f), f.getName());
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
            a.runMsavPipeline(new File(msavPath), (slot == null || slot.isEmpty())
                    ? Data.currentSlot(a) : slot);
            return;
        }

        if (intent.hasExtra("dev_saves")) {
            a.startActivity(new Intent(a, SlotsActivity.class));
            return;
        }

        // F2：槽分配的自动化回归口（SAF/弹窗无法自动点，只能靠 extra 驱动）
        String newSlot = intent.getStringExtra("dev_new_slot");
        if (newSlot != null && !newSlot.isEmpty()) {
            String err = Data.createSlot(a, newSlot);
            a.alert(err == null ? Trans.get(a, R.string.dev_new_slot_ok_fmt, newSlot)
                              : Trans.get(a, R.string.dev_new_slot_fail), err == null ? newSlot : err);
            a.rescan();
            return;
        }
        String asKey = intent.getStringExtra("dev_assign_key");
        if (asKey != null && !asKey.isEmpty()) {
            String s = intent.getStringExtra("dev_assign_slot");
            if (s == null) s = "";
            Config.get().setSlotOf(asKey, s);
            a.rescan();
            a.alert(Trans.get(a, R.string.dev_assign_title),
                    Trans.get(a, R.string.dev_assign_fmt, asKey, s.isEmpty() ? Data.SLOT_DEFAULT : s));
            return;
        }

        // 走**与点击列表完全相同的路径**（含 slotFor 的配置解析），
        // 用于验证「版本 → 槽」分配真的生效，而不是只验证 GameSlot 能跑。
        String launchKey = intent.getStringExtra("dev_launch_key");
        if (launchKey != null && !launchKey.isEmpty()) {
            a.rescan();
            Versions.Entry hit = null;
            if (a.mEntries != null) {
                for (Versions.Entry e : a.mEntries) {
                    if (launchKey.equals(e.key())) { hit = e; break; }
                }
            }
            if (hit == null) {
                a.alert(Trans.get(a, R.string.dev_launch_miss_title),
                        Trans.get(a, R.string.dev_launch_miss_fmt, launchKey, a.keysOf()));
                return;
            }
            a.startVersion(hit);
            return;
        }

        if (intent.hasExtra("dev_m3_selftest")) {
            runSelftest();
            return;
        }

        if (intent.hasExtra("dev_health")) {
            boolean clean = "1".equals(intent.getStringExtra("dev_health"));
            a.runHealth(clean);
            return;
        }

        // ── F3：设置页 / 设置项 ────────────────────────────────────────────
        if (intent.hasExtra("dev_settings")) {
            a.startActivity(new Intent(a, SettingsActivity.class));
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
            a.rescan();
            a.alert("dev_default_slot",
                    "写入 \"" + defSlot + "\" → 读回 \"" + back + "\""
                    + "\n（空串 = 回落内置 default）");
            return;
        }
        String logsN = intent.getStringExtra("dev_max_logs");
        if (logsN != null && !logsN.isEmpty()) {
            Config.get().setMaxLogFiles(a.intExtra(intent, "dev_max_logs", Config.DEF_LOG_FILES));
            a.alert("dev_max_logs",
                    "写入 " + logsN + " → 读回 " + Config.get().maxLogFiles()
                    + "\n（非数字 / 越界会被 Config.intOf 退回默认并打 WARNING 点名键名）");
            return;
        }

        // ── F15：深浅色 ─────────────────────────────────────────────────────
        String themeArg = intent.getStringExtra("dev_theme");
        if (themeArg != null && !themeArg.isEmpty()) {
            Config.get().setThemeMode(a.themeFromArg(themeArg));
            int night = a.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            writeDevReport("dev_theme 写入 \"" + themeArg + "\" → 读回 mode="
                    + Config.get().themeMode() + "（0=跟随系统 1=浅色 2=深色）\n"
                    + "重建前 uiMode night = "
                    + (night == android.content.res.Configuration.UI_MODE_NIGHT_YES ? "YES" : "NO")
                    + "\n（本页随即 recreate()；重建后应换成对应配色 —— 判据取背景像素）");
            // ★★ 必须先把 extra 摘掉再 recreate —— `recreate()` 会**重放原来的 intent**，
            //    而 onCreate 里就会跑 checkDevIntent(a.getIntent()) ⇒ 不清的话重建后又读到
            //    dev_theme、又重建…**无限重建**（屏幕一直黑、uiautomator 报
            //    "could not get idle state"，看着像"主题把界面搞崩了"）。
            //    这是"用 dev 口驱动重建"的通病，以后任何 dev 口要 recreate 都得照做。
            //    （onNewIntent 那条路里 setIntent 存的就是同一个对象，所以清一次就够。）
            intent.removeExtra("dev_theme");
            a.recreate();
            return;
        }

        // ── F5：自动备份 ───────────────────────────────────────────────────
        String polSlot = intent.getStringExtra("dev_policy_set");
        if (polSlot != null && !polSlot.isEmpty()) {
            boolean en = !"0".equals(intent.getStringExtra("dev_policy_enabled"));
            Config.get().setBackupPolicy(polSlot, en,
                    a.intExtra(intent, "dev_policy_min", Config.DEF_BACKUP_MIN_MINUTES),
                    a.intExtra(intent, "dev_policy_max", Config.DEF_BACKUP_MAX));
            Config.BackupPolicy p = Config.get().backupPolicy(polSlot);
            a.alert("dev_policy_set", polSlot + "\nenabled=" + p.enabled
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
            SlotIo.devReadZip(a, new File(zipConfirm), intent.getStringExtra("dev_zip_slot"),
                    new SlotOps.Host() {
                        @Override public void onSlotChanged() { a.rescan(); }
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
                    ? Data.currentSlot(a) : raw.trim();
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        rep = ModsActivity.report(a, use);
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
            final String useSlot = "true".equals(tgSlot) ? Data.currentSlot(a) : tgSlot;
            final String useName = tgName;
            final Boolean want = intent.hasExtra("dev_mods_on")
                    ? Boolean.valueOf("true".equals(intent.getStringExtra("dev_mods_on"))
                            || intent.getBooleanExtra("dev_mods_on", false))
                    : null;
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        Mods.Scan s = Mods.scan(a, useSlot);
                        boolean on = want != null ? want.booleanValue() : !a.currentEnabled(s, useName);
                        SettingsBin.Result r = Mods.setEnabled(a, useSlot, useName, on);
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
                    || "true".equals(s0.trim())) ? Data.currentSlot(a) : s0.trim();
            final boolean ow = intent.getBooleanExtra("dev_mods_overwrite", false)
                    || "true".equals(intent.getStringExtra("dev_mods_overwrite"));
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        File dir = new File(Data.dirOf(a, useSlot), "mods");
                        Mods.PackResult r = Mods.importPackage(a, dir, src, ow,
                                Mods.trashDirOf(a));
                        rep = "槽 = " + useSlot + "  源 = " + src.getAbsolutePath()
                                + "  覆盖 = " + ow + "\n\n" + r.report(a);
                        if (r.ok && r.meta != null) {
                            Mods.Target t = Mods.targetsFor(a, useSlot);
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
                        File from = new File(Data.dirOf(a, fromSlot), "mods");
                        File to = new File(Data.dirOf(a, toSlot), "mods");
                        Mods.PackResult r = Mods.copyMods(a, from, to, ow2,
                                Mods.trashDirOf(a));
                        rep = "从 " + fromSlot + " 复制到 " + toSlot + "（覆盖 = " + ow2 + "）\n"
                                + from.getAbsolutePath() + "\n" + to.getAbsolutePath() + "\n\n"
                                + r.report(a);
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
            Intent mi = new Intent(a, MapsActivity.class);
            mi.putExtra(MapsActivity.EXTRA_SLOT, dmp.trim());
            a.startActivity(mi);
            a.finish();
            return;
        }

        // ── F13/F23：直接打开某个槽的模组页 ────────────────────────────────────
        // 用法：`--es dev_mods_page <槽>`（空 = 当前槽）
        // ★ 为什么要它：模组页那张摘要卡上有 F23 的「上次崩溃：……」一行，而"进这个槽的模组页"
        //   在界面上要三步导航（主界面 → 槽 → 模组）；自动化验收需要一条直路
        //   （与 dev_maps_page / dev_bp_page / dev_saves_page 同一族）。
        String dmpg = intent.getStringExtra("dev_mods_page");
        if (dmpg != null && !dmpg.trim().isEmpty()) {
            Intent mmi = new Intent(a, ModsActivity.class);
            mmi.putExtra(ModsActivity.EXTRA_SLOT, dmpg.trim());
            a.startActivity(mmi);
            a.finish();
            return;
        }

        // ── 蓝图（F22）：直接打开某个槽的蓝图页 ──────────────────────────────
        // 用法：`--es dev_bp_page <槽>`
        // ★ 理由与 dev_maps_page 一样：蓝图页是槽页的子页面（点槽 → 蓝图），
        //   自动化点进去要穿两层；而真机验收（列表 / 缺件段 / 技术细节）都发生在那两页上。
        String dbp = intent.getStringExtra("dev_bp_page");
        if (dbp != null && !dbp.trim().isEmpty()) {
            Intent bi = new Intent(a, BlueprintsActivity.class);
            bi.putExtra(BlueprintsActivity.EXTRA_SLOT, dbp.trim());
            // ★ 顺带把"选文件换成路径"那条也转过去（与 dev_map_import 同一个用法）：
            //   SAF 选择器自动化不了，而"选到一份 .msch 之后"的路（验 → 同名先问 → 落位 → 刷新）
            //   必须能真机验。⇒ 后面的路与界面**逐字相同**，只是把"选文件"换成了路径。
            String bpImp = intent.getStringExtra(BlueprintsActivity.EXTRA_DEV_IMPORT);
            if (bpImp != null && !bpImp.trim().isEmpty()) {
                bi.putExtra(BlueprintsActivity.EXTRA_DEV_IMPORT, bpImp.trim());
            }
            a.startActivity(bi);
            a.finish();
            return;
        }

        // ── 存档列表页（第 116 轮加）：直接打开某个槽的存档页 ────────────────────
        // 用法：`--es dev_saves_page <槽>`
        // ★ 理由与 dev_maps_page / dev_bp_page 一样：存档页也是槽页的子页面，
        //   而"缩略图有没有出来 / 点一行的大图对不对"只能在这一页上验收。
        String dsp = intent.getStringExtra("dev_saves_page");
        if (dsp != null && !dsp.trim().isEmpty()) {
            Intent si = new Intent(a, SavesActivity.class);
            si.putExtra(SavesActivity.EXTRA_SLOT, dsp.trim());
            a.startActivity(si);
            a.finish();
            return;
        }

        // ── 第 127 轮：批量导入 / 导出的 dev 口（SAF 选择器自动化不了）───────────────
        // 用法：`--es dev_batch_export <槽> --es dev_batch_kind <maps|saves|blueprints|mods>
        //        --es dev_batch_path <目标 zip 的绝对路径>`
        //       `--es dev_batch_import <槽> --es dev_batch_kind <...>
        //        --es dev_batch_path <一个文件 或 一个目录 的绝对路径>`
        // ★ 走的是与界面**同一份**实现：导出 = Exporter.zipSources、导入 = BatchIo.importSync
        //   （它与界面那条 runImport 共用 plan + applyAll），只是把"用 SAF 选文件"换成路径。
        // ⚠️ 路径必须落在应用够得着的地方（`Android/data/io.mdt.launcher/…`）——
        //   targetSdk 36 下直接读 `/sdcard/Download` 会 EACCES（REF §85.12）。
        String bExp = intent.getStringExtra("dev_batch_export");
        String bImp = intent.getStringExtra("dev_batch_import");
        boolean isExp = bExp != null && !bExp.trim().isEmpty();
        boolean isImp = bImp != null && !bImp.trim().isEmpty();
        if (isExp || isImp) {
            String path = intent.getStringExtra("dev_batch_path");
            String slot = isImp ? bImp.trim() : bExp.trim();
            if ("true".equals(slot)) slot = Data.currentSlot(a);
            if (path == null || path.trim().isEmpty()) {
                reportDev("dev_batch: 缺少 dev_batch_path\n", "dev_batch");
                return;
            }
            devBatch(slot, BatchIo.kindOfKey(intent.getStringExtra("dev_batch_kind")),
                    new File(path.trim()), isExp);
            return;
        }

        // ── 「存档视为地图」：把"选文件"换成路径，直接进地图页那条导入路 ────────────        // 用法：`--es dev_map_import /sdcard/xxx.msav [--es dev_map_slot <槽>]`
        // ★ 为什么要它：SAF 选择器**自动化不了**（第 104 轮 dev_zip_confirm 同一条理由），
        //   而"选到一份存档之后"的那条路（命名框 → 改写 → 落位 → 结果框）必须能真机验。
        //   ⇒ 后面的路与界面**逐字相同**，只是把"选包"换成了路径。
        String dmImp = intent.getStringExtra("dev_map_import");
        if (dmImp != null && !dmImp.isEmpty()) {
            String useSlot = intent.getStringExtra("dev_map_slot");
            if (useSlot == null || useSlot.trim().isEmpty()) useSlot = Data.currentSlot(a);
            Intent mi = new Intent(a, MapsActivity.class);
            mi.putExtra(MapsActivity.EXTRA_SLOT, useSlot.trim());
            mi.putExtra(MapsActivity.EXTRA_DEV_IMPORT, dmImp.trim());
            a.startActivity(mi);
            a.finish();
            return;
        }

        // ── F10：地图清点（本槽 / 游戏自带 / 模组自带）─────────────────────────
        // 用法：`--es dev_maps_slot <槽名>`（空/true = 当前槽）→ `hub/report-devtool.txt`
        // ★ 为什么要这个口：界面入口在"槽操作菜单"里，自动化点开菜单很脆
        //   （2026-10-03 就卡在这一步）⇒ 数据这条链用 dev 口验，界面另验。
        String dms = intent.getStringExtra("dev_maps_slot");
        if (dms != null && !dms.isEmpty()) {
            final String useSlot = ("true".equals(dms.trim()) || dms.trim().isEmpty())
                    ? Data.currentSlot(a) : dms.trim();
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        Mods.Target t = Mods.targetsFor(a, useSlot);
                        java.util.List<Maps.Item> items = Maps.scan(a, useSlot, t.apkPath);
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
                            sb.append("· [").append(it.sourceLabel(a)).append("] ")
                              .append(it.name())
                              .append("  |  ").append(it.line(a))
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
                    ? Data.currentSlot(a) : cfl.trim();
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        Mods.Scan s = Mods.scan(a, useSlot);
                        Mods.Conflict c = Mods.findConflicts(a, s.mods,
                                new File(Data.dirOf(a, useSlot), "last_log.txt"));
                        rep = "槽 = " + useSlot + "\n\n" + c.report(a);
                    } catch (Throwable t) {
                        rep = "dev_mods_conflict 失败：" + t;
                    }
                    reportDev(rep, "dev_mods_conflict · 槽 " + useSlot);
                }
            }, "dev-mods-conflict").start();
            return;
        }
        // ── F23：崩溃分析（把当前槽每一份崩溃报告的结论 + 证据 + 耗时落到报告）──────
        // 用法：`--es dev_crash_analyze 1`（或槽名）→ `hub/report-devtool.txt`
        // ★ 为什么要这个口：归因的判据要在**真机上的真报告**上过一遍（ART 的异常措辞
        //   与桌面 HotSpot 未必同款），而结论文案的排版与 dex 扫描的耗时也只有真机量得准。
        String can = intent.getStringExtra("dev_crash_analyze");
        if (can != null && !can.isEmpty()) {
            final String useSlot = ("true".equals(can.trim()) || can.trim().isEmpty())
                    ? Data.currentSlot(a) : can.trim();
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        rep = CrashAnalysis.devReport(a, useSlot);
                    } catch (Throwable t) {
                        rep = "dev_crash_analyze 失败：" + t;
                    }
                    reportDev(rep, "dev_crash_analyze · 槽 " + useSlot);
                }
            }, "dev-crash-analyze").start();
            return;
        }
        // ── F23：崩溃分析 · 全量语料回归（把一整个目录的语料逐份跑一遍）───────────────
        // 用法：`--es dev_crash_corpus /sdcard/mdt-corpus [--es dev_crash_corpus_slot <槽>]`
        // ★ 为什么要这个口：语料库会长（40 条签名 + 六十多份报告），只靠自检里那几份夹具
        //   覆盖不到全量；这一遍专门看"解析不抛 / 该点名的不许认不出 / 不该点名的不许点名"。
        String corpus = intent.getStringExtra("dev_crash_corpus");
        if (corpus != null && !corpus.isEmpty()) {
            final String dir = corpus.trim();
            String s0 = intent.getStringExtra("dev_crash_corpus_slot");
            final String useSlot = (s0 == null || s0.trim().isEmpty()) ? Data.currentSlot(a) : s0.trim();
            new Thread(new Runnable() {
                @Override public void run() {
                    String rep;
                    try {
                        rep = CrashAnalysis.corpusReport(a, dir, useSlot);
                    } catch (Throwable t) {
                        rep = "dev_crash_corpus 失败：" + t;
                    }
                    reportDev(rep, "dev_crash_corpus · " + dir + " · 槽 " + useSlot);
                }
            }, "dev-crash-corpus").start();
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
        // ── 蓝图（.msch）解析：拿真文件跑一遍，把**逐格配置 + 缺哪些方块**落到报告 ────
        // 用法：`--es dev_msch /sdcard/xxx.msch [--es dev_msch_slot <槽>]`
        // ★ 为什么要这个口：① 判据在 PC 上（`_lab/msch/` 拿游戏自己的读取器当神谕），
        //   设备这边要看的是**同一份内核在真机上跑真文件**；② 界面入口还没有，
        //   而"缺哪些方块"正是蓝图体检的价值所在（认不出的方块游戏**静默当空气**）。
        String mschPath = intent.getStringExtra("dev_msch");
        if (mschPath != null && !mschPath.isEmpty()) {
            devMsch(mschPath, intent.getStringExtra("dev_msch_slot"));
            return;
        }
    }


    /** 蓝图解析的 dev 口：解析报告 + 缺方块体检（判据 = 本槽已启用模组的**内容表**） */
    private void devMsch(final String path, final String slotArg) {
        final String useSlot = (slotArg == null || slotArg.trim().isEmpty() || "true".equals(slotArg.trim()))
                ? Data.currentSlot(a) : slotArg.trim();
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                long t0 = System.currentTimeMillis();
                try {
                    File f = new File(path);
                    sb.append("文件 = ").append(f.getAbsolutePath()).append("  ")
                      .append(f.length()).append(" 字节\n");
                    sb.append("槽 = ").append(useSlot).append("\n\n");
                    Msch m = Msch.read(f);
                    if (!m.ok) {
                        sb.append("❌ 读不出来（码 ").append(m.errCode).append("）：")
                          .append(m.error).append('\n');
                        sb.append("界面文案 = ").append(MschText.reason(a, m)).append('\n');
                    } else {
                        sb.append(m.report()).append('\n');
                        sb.append("名 = ").append(m.displayName()).append('\n');
                        sb.append("标签 = ").append(m.tags.keySet()).append('\n');
                        sb.append("分类 = ").append(m.labels)
                          .append(m.labelsBad ? "（解析失败，游戏同样忽略）" : "").append('\n');
                        sb.append("字典 = ").append(m.dict).append('\n');

                        // 缺方块体检：判据 = **本槽已启用模组**（+原版）的内容表
                        MapStats.Table tab = MapStatsMods.contentFor(a, useSlot).table;
                        java.util.LinkedHashMap<String, Integer> miss = new java.util.LinkedHashMap<>();
                        java.util.LinkedHashMap<String, Integer> used = new java.util.LinkedHashMap<>();
                        for (Msch.Tile t : m.tiles) {
                            String n = t.block == null ? "(空)" : t.block;
                            used.put(n, (used.containsKey(n) ? used.get(n) : 0) + 1);
                            if (tab == null || tab.row(n) == null) {
                                miss.put(n, (miss.containsKey(n) ? miss.get(n) : 0) + 1);
                            }
                        }
                        sb.append("方块种类 = ").append(used.size()).append("（内容表 ")
                          .append(tab == null ? "读不出来" : tab.size() + " 条").append("）\n");
                        sb.append("用到 = ").append(a.brief(used)).append('\n');
                        sb.append(miss.isEmpty() ? "缺方块 = 没有（这份蓝图在本槽能完整还原）\n"
                                                 : "缺方块 = " + a.brief(miss) + "   ← 游戏会**静默当空气**\n");

                        // 逐格配置（最多 60 行，够看出形状）
                        int n = 0;
                        for (Msch.Tile t : m.tiles) {
                            if (n++ >= 60) {
                                sb.append("… 还有 ").append(m.tiles.size() - 60).append(" 格\n");
                                break;
                            }
                            sb.append("  ").append(t.block).append(" @").append(t.x).append(',')
                              .append(t.y).append(" rot=").append(t.rotation).append("  ")
                              .append(a.cfgText(m, t.config)).append('\n');
                        }
                    }
                    sb.append("\n用时 ").append(System.currentTimeMillis() - t0).append(" ms\n");
                } catch (Throwable t) {
                    sb.append("dev_msch 失败：").append(t).append('\n');
                }
                reportDev(sb.toString(), "dev_msch · " + new File(path).getName());
            }
        }, "dev-msch").start();
    }


    /** F21 的 dev 口：跑一遍统计并把**逐项数字 + 真机耗时**落到 `hub/report-devtool.txt` */
    private void devMapStats(final String slotArg, final String fileArg) {
        final String useSlot = ("true".equals(slotArg.trim()) || slotArg.trim().isEmpty())
                ? Data.currentSlot(a) : slotArg.trim();
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("槽 = ").append(useSlot).append('\n');
                long t0 = System.currentTimeMillis();
                try {
                    Mods.Target tgt = Mods.targetsFor(a, useSlot);
                    sb.append("目标游戏 = ").append(tgt.label.isEmpty() ? "（没指到版本）" : tgt.label)
                      .append('\n');
                    sb.append("游戏 APK = ")
                      .append(tgt.apkPath == null || tgt.apkPath.isEmpty() ? "（没有）" : tgt.apkPath)
                      .append('\n');
                    java.util.Map<String, String> labels = MapStatsMods.attrLabels(a);
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
                        items.addAll(Maps.scan(a, useSlot, tgt.apkPath));
                    }
                    if (items.size() > 60) items = items.subList(0, 60);
                    long min = Long.MAX_VALUE, max = 0, sum = 0;
                    int ok = 0;
                    for (Maps.Item it : items) {
                        MapStatsMods.Built b =
                                MapStatsMods.run(a, useSlot, it, tgt.apkPath, labels);
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
                        sb.append("    地矿: ").append(a.rowsOf(r.ores, false)).append('\n');
                        if (!r.oreWalls.isEmpty()) {
                            sb.append("    墙矿: ").append(a.rowsOf(r.oreWalls, true)).append('\n');
                        }
                        sb.append("    可采地板: ").append(a.rowsOf(r.floors, false)).append('\n');
                        sb.append("    加成地板: ").append(a.rowsOf(r.bonuses, false)).append('\n');
                        sb.append("    挡住资源的: ").append(a.blockersOf(r)).append('\n');
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
     * F16：把某个槽**最新的一份**快照恢复回去（跳过界面直接驱动 {@link Backup#restore}）。
     * 用法：`--es dev_backup_restore &lt;槽名&gt; [--es dev_restore_pick &lt;序号 0=最新&gt;]`
     *
     * ★ 为什么单开一个口：`dev_m3_selftest` 只在几十字节的假数据上验过恢复，而
     *   "大文件 + 流式还原"正是这次改造回归风险最高的地方（旧实现 `copyRecursive`
     *   直接写目标；新的走 `tmp + rename` 且边写边算 sha256）。
     *   真机判据与 F6/F6c 一致：**md5 逐字节比对**。
     */
    private void devBackupRestore(final String slot) {
        final int pick = a.intExtra(a.getIntent(), "dev_restore_pick", 0);
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                try {
                    List<Backup.Snapshot> list = Backup.list(a, slot);
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
                                Backup.restore(a, slot, ss);
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
                      .append(Backup.casBaseDir(a).getAbsolutePath()).append('\n');
                    sb.append("hasLegacy = ").append(Backup.hasLegacy(a)).append('\n');

                    long t1 = System.currentTimeMillis();
                    Backup.PoolStats st = Backup.poolStats(a);
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
                        String rep = Backup.migrateLegacy(a);
                        sb.append("— 迁移（").append(System.currentTimeMillis() - t2).append(" ms） —\n");
                        sb.append(rep == null ? "无旧格式快照\n" : rep);
                    }
                    if (doGc) {
                        long t3 = System.currentTimeMillis();
                        int n = Backup.gcNow(a);
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
                ? Data.currentSlot(a) : slotArg;
        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder sb = new StringBuilder();
                sb.append("dev_zip_export 槽=\"").append(slot).append("\" -> ")
                  .append(dest.getAbsolutePath()).append('\n');
                try {
                    List<File> roots = Data.contentRoots(a, slot);
                    sb.append("roots(").append(roots.size()).append(") = ");
                    for (File f : roots) sb.append(f.getName()).append(f.isDirectory() ? "/ " : " ");
                    sb.append('\n');
                    if (dest.exists() && !dest.delete()) {
                        sb.append("⚠ 旧文件删不掉，会接着写\n");
                    }
                    Exporter.Result r = Exporter.zipTo(a, Uri.fromFile(dest),
                            Data.dirOf(a, slot), roots);
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
     * 第 127 轮：**批量导入 / 导出**跑一遍（跳过 SAF 选择器，直接对设备绝对路径干活）。
     *
     * <p>★ 为什么必须有它：SAF 的"选多个文件 / 选一个保存位置"在 adb 下无法驱动，
     * 而这一轮的两个承诺（"多份打成一个 zip"、"整包 zip 能解开逐份导回来"）
     * 只能靠**往返**取证：导出 → 看 zip → 导入到另一个槽 → 比 md5。
     * 走的实现与界面完全相同（{@link Exporter#zipSources} / {@link BatchIo#importSync}）。
     *
     * @param export true = 导出到 {@code path}（一个 zip）；false = 把 {@code path}
     *               （**一个文件** 或 **一个目录**里的全部文件）当成待导入的一批
     */
    private void devBatch(final String slot, final int kind, final File path, final boolean export) {
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append(export ? "dev_batch_export" : "dev_batch_import")
                  .append(" 槽=\"").append(slot).append("\" kind=").append(BatchIo.keyOf(kind))
                  .append(" 路径=").append(path.getAbsolutePath()).append('\n');
                try {
                    if (export) {
                        if (path.exists() && !path.delete()) sb.append("⚠ 旧文件删不掉，会接着写\n");
                        List<Exporter.Src> srcs = a.collectSrcs(a, slot, kind);
                        sb.append("扫到条目 ").append(srcs.size()).append(" 个\n");
                        Exporter.Result r = Exporter.zipSources(a,
                                Uri.fromFile(path), srcs);
                        sb.append("zip: files=").append(r.files).append(" raw=").append(r.rawBytes)
                          .append(" out=").append(r.outBytes).append('\n');
                        sb.append("落盘大小=").append(path.length()).append('\n');
                    } else {
                        List<File> fs = new ArrayList<>();
                        if (path.isFile()) {
                            fs.add(path);
                        } else {
                            File[] kids = path.listFiles();
                            if (kids != null) {
                                java.util.Arrays.sort(kids);
                                for (File f : kids) if (f.isFile()) fs.add(f);
                            }
                        }
                        List<BatchIo.Doc> docs = BatchIo.docsFromFiles(fs);
                        sb.append("待导入 ").append(docs.size()).append(" 个\n");
                        BatchIo.Report rep = BatchIo.importSync(a, slot, docs, kind, true);
                        sb.append(rep.text(a));
                    }
                } catch (Throwable t) {
                    sb.append("FAILED: ").append(t).append('\n');
                }
                reportDev(sb.toString(), export ? "dev_batch_export" : "dev_batch_import");
            }
        }, "dev-batch").start();
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
                    File tmp = SlotZip.stage(a, Uri.fromFile(src));
                    try {
                        SlotZip.Info inf = SlotZip.inspect(a, tmp);
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
                ? Data.currentSlot(a) : slotArg;
        final int mode = SlotWrite.sane(modeArg);
        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder sb = new StringBuilder();
                sb.append("dev_zip_import ").append(src.getAbsolutePath())
                  .append(" -> 槽 \"").append(slot).append("\"  mode=").append(mode).append('\n');
                try {
                    File tmp = SlotZip.stage(a, Uri.fromFile(src));
                    try {
                        SlotZip.Info inf = SlotZip.inspect(a, tmp);
                        sb.append("inspect: files=").append(inf.files)
                          .append(" native=").append(inf.nativeFormat)
                          .append(" strip=\"").append(inf.strip).append("\"\n");
                        SlotZip.Result r = SlotZip.extract(a, tmp, inf, slot, mode);
                        sb.append("extract: files=").append(r.files)
                          .append(" bytes=").append(r.bytes)
                          .append(" skipped=").append(r.skipped)
                          .append(" kept=").append(r.kept)
                          .append(" wiped=").append(r.wiped).append('\n');
                        sb.append("槽目录=").append(Data.dirOf(a, slot)).append('\n');
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
                                .append(Compat.rejectReason(a, p).replace('\n', ' '))
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
                    LogActivity.Snap s = LogActivity.readAll(a);
                    String text = LogActivity.compose(a, s,
                            LogActivity.exportHeader(a));
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
                a.runOnUiThread(new Runnable() {
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
            File out = new File(Data.hubDir(a), "report-devtool.txt");
            Util.atomicWriteText(out, text);
            path = out.getAbsolutePath();
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "report write failed: " + t);
        }
        final String fp = path;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                a.alert(title, text + "\n" + fp);
                a.rescan();
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
            File out = new File(Data.hubDir(a), "report-devtool.txt");
            Util.atomicWriteText(out, text);
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "report write failed: " + t);
        }
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
            List<Data.Slot> slots = Data.allSlots(a);
            sb.append("slots=").append(slots.size()).append('\n');
            for (Data.Slot s : slots) {
                Config.BackupPolicy p = Config.get().backupPolicy(s.name);
                sb.append("  policy[").append(s.name).append("] enabled=").append(p.enabled)
                  .append(" min=").append(p.minMinutes)
                  .append(" max=").append(p.maxBackups).append('\n');
            }
            File dir = Data.hubDir(a);
            File out = new File(dir, "report-settings.txt");
            Util.atomicWriteText(out, sb.toString());
            a.alert("dev_setting_dump", out.getAbsolutePath() + "\n\n" + sb);
        } catch (Throwable t) {
            a.alert("dev_setting_dump failed", String.valueOf(t));
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
        final int min = a.intExtra(a.getIntent(), "dev_ab_min", 0);
        Config.get().setBackupPolicy(slot, true, min,
                Config.get().backupPolicy(slot).maxBackups);
        AutoBackup.noteStart("dev:autobackup", slot);
        AutoBackup.settle(a);            // 游戏没跑 ⇒ 内部判 :game 已死成立

        new Thread(new Runnable() {
            @Override public void run() {
                AutoBackup.awaitIdle(15000);    // 等后台快照收尾
                final StringBuilder sb = new StringBuilder();
                sb.append("dev_autobackup_test slot=").append(slot)
                  .append(" min=").append(min).append('\n');
                sb.append("结算后 session.slot=\"").append(Config.get().sessionSlot())
                  .append("\"  at=").append(Config.get().sessionStartedAt())
                  .append("  （应为空串/0 = 已清账）\n");
                List<Backup.Snapshot> list = Backup.list(a, slot);
                sb.append("现在备份数=").append(list.size()).append('\n');
                for (Backup.Snapshot ss : list) {
                    sb.append("  · ").append(ss.title()).append("  ")
                      .append(ss.count).append(" 个文件 ")
                      .append(Util.formatSize(ss.bytes)).append('\n');
                }
                try {
                    Util.atomicWriteText(
                            new File(Data.hubDir(a), "report-devtool.txt"),
                            sb.toString());
                } catch (Throwable t) {
                    sb.append("写报告失败: ").append(t);
                }
                a.runOnUiThread(new Runnable() {
                    @Override public void run() { a.alert("dev_autobackup_test", sb.toString()); }
                });
            }
        }).start();
    }


    private void runSelftest() {
        final ProgressDialog pd = ProgressDialog.show(a, Trans.get(a, R.string.selftest_title),
                Trans.get(a, R.string.selftest_running), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final String r = SelfTest.runM3(a);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        // ★ F17c：结论同时放进**标题** —— AlertDialog 的标题不随正文滚动，
                        //   于是"过没过"在任何滚动位置都可见（一张截图即可判读）。
                        a.alert(Trans.get(a, R.string.selftest_result) + " · " + SelfTest.summaryOf(r), r);
                        a.rescan();
                    }
                });
            }
        }).start();
    }
}
