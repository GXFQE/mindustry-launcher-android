package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.List;

/**
 * 全局设置（F3）。
 *
 * ★ 为什么只有两项 + 「关于」：本页刻意**只收"所有槽共用"的项**。
 *   桌面版把「存档分类设置」和「启动器设置」并在一张设置窗口里，是因为桌面只有
 *   一个"当前分类"。而 Android 这边槽是**按版本分配**的（同一时刻可以有多个槽在用），
 *   根本没有强"当前存档"概念 —— 把「自动备份」这类按槽生效的项塞进全局页，
 *   用户改完不知道改的是哪个槽。所以按 Android 语义拆开：
 *     · 按槽生效（自动备份三件套）→ 存档页的槽菜单，就近改（见 SlotsActivity）
 *     · 所有槽共用（默认槽 / 日志保留份数）→ 本页
 *
 * 交互与桌面版设置页刻意不同：桌面版是"改一堆、最后一个出口保存"（tkinter 没有即时绑定），
 * Android 惯例是**改一项立刻生效**，所以这里不做"保存并返回"，改完即落盘。
 *
 * 对齐桌面版的项见 BACKLOG「桌面版功能对照表」；Android 无对应物的项（Java 路径 /
 * JVM 参数 / GitHub 镜像 / 隐藏窗口 / 永久删除）一律不做。
 */
public class SettingsActivity extends BaseActivity {

    private static final int LOG_MIN = 1;
    private static final int LOG_MAX = 999;

    private TextView mThemeSub;
    private TextView mLangSub;
    private TextView mSlotSub;
    private TextView mLogsSub;
    /** F20：自动清理残留那一行的副标题（已开 / 已关） */
    private TextView mAutoCleanSub;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.i("MDTLauncher", "SettingsActivity onCreate");
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        fillValues();
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void buildUi() {
        View root = getLayoutInflater().inflate(R.layout.activity_settings, null);

        // F15：深浅色。放在最上面 —— 纯外观项，也是用户最常回来改的那一项。
        View rowTheme = Util.bindActionValue(root, R.id.row_theme, R.drawable.ic_theme,
                R.string.set_theme_title, new Runnable() {
                    @Override public void run() { pickTheme(); }
                });
        mThemeSub = (TextView) rowTheme.findViewById(R.id.act_sub);

        // P2：界面语言。与深浅色相邻 —— 两者都在 BaseActivity 的同一个 Configuration 上生效。
        View rowLang = Util.bindActionValue(root, R.id.row_language, R.drawable.ic_language,
                R.string.set_lang_title, new Runnable() {
                    @Override public void run() { pickLanguage(); }
                });
        mLangSub = (TextView) rowLang.findViewById(R.id.act_sub);

        View rowSlot = Util.bindActionValue(root, R.id.row_def_slot, R.drawable.ic_folder,
                R.string.set_slot_title, new Runnable() {
                    @Override public void run() { pickDefaultSlot(); }
                });
        mSlotSub = (TextView) rowSlot.findViewById(R.id.act_sub);

        View rowLogs = Util.bindActionValue(root, R.id.row_logs, R.drawable.ic_log,
                R.string.set_logs_title, new Runnable() {
                    @Override public void run() { promptLogs(); }
                });
        mLogsSub = (TextView) rowLogs.findViewById(R.id.act_sub);

        // F7：运行日志入口。与「日志保留份数」相邻 —— 那项是"留多少份"，
        // 这项是"到哪看"，两者合起来才让日志真的可用。
        Util.bindAction(root, R.id.row_open_logs, R.drawable.ic_terminal,
                R.string.act_logs_title, R.string.act_logs_sub, new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(SettingsActivity.this, LogActivity.class));
                    }
                });

        // F20（2026-10-03 用户：「我们是不是应该给一些功能加上开关」）：
        // ★ 全工程**唯一一个"静默删文件"**的动作就是它（进存档页时清数据根残留），
        //   所以只给它加了开关。这一行**同时是手动清理的入口** —— 关掉自动之后还得能清，
        //   否则用户一关就再没有别的办法（第 57 轮把体检的可见入口删掉了）。
        View rowClean = Util.bindActionValue(root, R.id.row_auto_clean, R.drawable.ic_health,
                R.string.set_autoclean_title, new Runnable() {
                    @Override public void run() { promptAutoClean(); }
                });
        mAutoCleanSub = (TextView) rowClean.findViewById(R.id.act_sub);

        // ★ 2026-10-06（一档③+⑦）：存储占用 —— 备份省了多少 + 各类数据各占多少。
        //   只读：一个字节都不写。数字在后台算完再填进弹窗（见 showStorage）。
        Util.bindAction(root, R.id.row_storage, R.drawable.ic_storage,
                R.string.set_storage_title, R.string.set_storage_sub, new Runnable() {
                    @Override public void run() {
                        showStorage();
                    }
                });

        // 中转站（2026-10-05，第 102 轮）：地图/模组被覆盖或删除时挪进去的旧件在这里。
        // ★ 入口放全局设置页 —— 中转站不属于任何一个槽（见 activity_settings.xml 里那段注释）。
        // ★ 副标题是**固定的一句**，不显示份数/体积：那要扫盘（目录形态的模组可能不小），
        //   而本页每次 onResume 都会重填一遍 —— 为了一个副标题去递归算体积不值得。
        Util.bindAction(root, R.id.row_trash, R.drawable.ic_trash,
                R.string.trash_title, R.string.trash_sub, new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(SettingsActivity.this, TrashActivity.class));
                    }
                });

        // 「关于」全部取运行期事实，不写死版本 —— 免得出现"日志说 0.2、界面写 0.1"
        String verName = "?";
        int verCode = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            verName = pi.versionName;
            verCode = pi.versionCode;
        } catch (Exception e) {
            android.util.Log.w("MDTLauncher", "read PackageInfo failed: " + e);
        }
        File dataRoot = Data.dataRoot(this);
        ((TextView) root.findViewById(R.id.about_text)).setText(Trans.get(SettingsActivity.this, R.string.about_fmt,
                verName, verCode, getPackageName(),
                dataRoot == null ? "?" : dataRoot.getAbsolutePath(),
                Data.hubDir(this).getAbsolutePath()));

        Util.applySystemInsets(root);
        setContentView(root);

        // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包
        Trans.bind(root, R.id.tx_about_card_title, R.string.about_card_title);

    }

    /**
     * 每次回本页重取一次：默认槽可能在别处被删（存档页删槽后这里要跟着回落）。
     * 副标题就是"当前值"，和桌面版把当前值回填到输入框是同一个意思。
     */
    private void fillValues() {
        if (mThemeSub != null) {
            mThemeSub.setText(Trans.get(SettingsActivity.this, R.string.set_theme_sub_fmt,
                    getString(ThemeMode.labelRes(Config.get().themeMode()))));
        }
        if (mLangSub != null) {
            // ★ 语言名用它自己的语言写（English / 简体中文）—— 所以这里传的是**当前上下文的
            //   Resources**，但取的数组是 translatable="false" 的，任何语言下都是同一份。
            CharSequence cur = LocaleMode.label(this, LocaleMode.of(this));
            mLangSub.setText(Trans.get(SettingsActivity.this, R.string.set_lang_sub_fmt, cur));
        }
        if (mSlotSub != null) {
            String s = Config.get().defaultSlot();
            mSlotSub.setText(Trans.get(SettingsActivity.this, R.string.set_slot_sub_fmt,
                    s.isEmpty() ? Data.SLOT_DEFAULT : s));
        }
        if (mLogsSub != null) {
            mLogsSub.setText(Trans.get(SettingsActivity.this, R.string.set_logs_sub_fmt, Config.get().maxLogFiles()));
        }
        if (mAutoCleanSub != null) {
            mAutoCleanSub.setText(Config.get().autoCleanRedundant()
                    ? R.string.set_autoclean_on : R.string.set_autoclean_off);
        }
    }

    /**
     * F20：「自动清理残留」这一行。点击弹两件事 ——
     *   ① 立即清理一次（**关掉自动之后唯一的清理入口**，所以必须给）
     *   ② 关掉 / 开启自动清理
     *
     * ★ 为什么不像别处那样放一个开关控件：本页所有行都是"点一下弹选择"的样式
     *   （深浅色 / 默认槽 / 日志份数都这样），中间插一个 Switch 会让这一页看起来像两套交互。
     * ★ 清完只报**数量**、不报路径：那些是自己的残留副本，用户不必认识它们。
     */
    private void promptAutoClean() {
        final boolean on = Config.get().autoCleanRedundant();
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_autoclean_title)
                .setItems(new String[]{
                        Trans.get(SettingsActivity.this, R.string.set_autoclean_do),
                        getString(on ? R.string.set_autoclean_disable
                                     : R.string.set_autoclean_enable)},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                if (w == 0) { runAutoClean(); return; }
                                Config.get().setAutoCleanRedundant(!on);
                                fillValues();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * ★ 2026-10-06（一档③+⑦）：「存储占用」——**只读**的一份账。
     *
     * 结构：先把弹窗摆出来（正文写"正在统计…"），后台算完再 `setMessage` ——
     * 与 {@link MainActivity} 详情弹窗里那条"先弹窗、算完补上"是同一套：
     * 遍历各槽 + 整个对象池 + 中转站要几百毫秒，放主线程就是 ANR。
     *
     * ⚠️ 收尾必须判 {@link Util#dead}（转屏时 `isFinishing()` 是 false ⇒ 弹窗会炸）。
     * ⚠️ 本方法**只读**：不回收、不迁移、不清理（那些各有各的安排与门禁）。
     */
    private void showStorage() {
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.set_storage_title)
                .setMessage(R.string.storage_loading)
                .setPositiveButton(R.string.close, null)
                .create();
        dlg.show();
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                String body;
                String err = null;
                try {
                    body = Storage.text(app, Storage.collect(app));
                } catch (Throwable t) {
                    body = null;
                    err = String.valueOf(t);
                    android.util.Log.w("MDTLauncher", "storage stats failed: " + t);
                }
                final String fBody = body;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(SettingsActivity.this) || !dlg.isShowing()) return;
                        dlg.setMessage(fErr == null ? fBody
                                : Trans.get(SettingsActivity.this, R.string.storage_failed, fErr));
                    }
                });
            }
        }, "storage-stats").start();
    }

    /** 立即清理一次（后台线程 —— 要扫数据根；清完回主线程报数量） */
    private void runAutoClean() {
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                int n = 0;
                boolean ok = true;
                try {
                    n = Data.autoCleanRedundant(app).cleaned;
                } catch (Throwable e) {
                    ok = false;
                    android.util.Log.w("MDTLauncher", "manual auto-clean failed: " + e);
                }
                final int count = n;
                final boolean good = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (!good) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.set_autoclean_fail, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(SettingsActivity.this,
                                count > 0 ? Trans.get(SettingsActivity.this, R.string.set_autoclean_done_fmt, count)
                                          : Trans.get(SettingsActivity.this, R.string.set_autoclean_none),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "manual-health").start();
    }

    // ── 设置项 ────────────────────────────────────────────────────────────

    /**
     * F15：深浅色。三选一（跟随系统 / 浅色 / 深色），改完**立即生效**。
     *
     * ★ 生效方式见 {@link ThemeMode}：把 uiMode 的 night 位改掉再包一层 Context，
     *   所以 `values-night/` 那一整套颜色会自动顶上，不需要第二套布局。
     *
     * ★ 本页必须**自己** `recreate()`：
     *   改动就发生在当前 Activity 里，`onResume` 不会因为"配置变了"而重跑，
     *   不重建的话界面上什么都不变（用户会以为没生效，然后再点一次）。
     *   其它页面（主界面 / 存档页 / 日志页）从本页返回时会走
     *   {@link BaseActivity#onResume} 的自查，不需要在这里通知它们。
     *
     * ⚠️ 单选列表只能配 `setTitle`，**不能**配 `setMessage`（两者并存列表项不渲染，
     *    见 {@link SlotsActivity#slotOps} 的注释）；先 `dismiss()` 再重建，
     *    免得对话框的窗口在 Activity 被销毁后还挂着。
     */
    private void pickTheme() {
        final int[] modes = {ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK};
        String[] names = new String[modes.length];
        final int cur = Config.get().themeMode();
        int checked = -1;
        for (int i = 0; i < modes.length; i++) {
            names[i] = getString(ThemeMode.labelRes(modes[i]));
            if (modes[i] == cur) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_theme_pick_title)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        Config.get().setThemeMode(modes[w]);
                        recreate();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * P2：界面语言。列表第一项是「跟随系统」，其余是**应用真正带了的那些语言**
     * （名单 = `R.array.app_languages`，由构建期门禁 `RES-10` 保证与 `values-*` 一一对应）。
     *
     * ★ 语言名用**它自己的语言**写（English / 简体中文）—— 语言选择器的通行做法：
     *   用户在一个看不懂的界面里，唯一能认出来的就是母语自己的写法。
     *
     * ★ 与 {@link #pickTheme()} 同一套：
     *   · 改完必须**自己 `recreate()`** —— 改动就发生在当前页，`onResume` 不会重跑，
     *     不重建的话界面上什么都不变（用户会以为没生效，然后再点一次）；
     *   · 单选列表只能配 `setTitle`、**不能**配 `setMessage`（两者并存列表项不渲染）；
     *   · 先 `dismiss()` 再重建，免得对话框的窗口在 Activity 被销毁后还挂着。
     *
     * ⚠️ 这里**只写 config.json**，绝不调 `Locale.setDefault()` —— 理由见 {@link LocaleMode}
     *   类注释里那条红线（`:game` 进程与游戏同进程，动了它会把游戏的语言也带偏）。
     */
    private void pickLanguage() {
        final String[] tags = LocaleMode.tags(this);
        String[] names = new String[tags.length];
        final String cur = LocaleMode.of(this);
        int checked = -1;
        for (int i = 0; i < tags.length; i++) {
            names[i] = ("system".equals(tags[i]) ? Trans.get(SettingsActivity.this, R.string.lang_system)
                                                 : String.valueOf(LocaleMode.label(this, tags[i])));
            // 「跟随系统」在资源里存的是字面量 "system"，与 LocaleMode.SYSTEM（空串）是两回事
            boolean isCur = "system".equals(tags[i]) ? LocaleMode.SYSTEM.equals(cur)
                                                     : tags[i].equals(cur);
            if (isCur) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_lang_pick_title)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        Config.get().setAppLanguage("system".equals(tags[w])
                                ? LocaleMode.SYSTEM : tags[w]);
                        recreate();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 默认槽 = 新发现的版本自动落到哪个槽（此前是硬编码 default）。
     * ⚠️ 单选列表必须用 setSingleChoiceItems 配 setTitle —— 不能配 setMessage，
     *    否则列表项一个都不渲染（F3b 实测，见 SlotsActivity.slotOps 的注释）。
     */
    private void pickDefaultSlot() {
        final List<Data.Slot> slots = Data.allSlots(this);
        if (slots.isEmpty()) return;
        final String[] names = new String[slots.size()];
        String cur = Config.get().defaultSlot();
        int checked = -1;
        for (int i = 0; i < slots.size(); i++) {
            Data.Slot s = slots.get(i);
            names[i] = s.name + (s.active ? Trans.get(SettingsActivity.this, R.string.slot_current_suffix) : "");
            if (s.name.equals(cur)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_slot_pick_title)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Config.get().setDefaultSlot(slots.get(w).name);
                        d.dismiss();
                        fillValues();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void promptLogs() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.set_logs_hint);
        input.setText(String.valueOf(Config.get().maxLogFiles()));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_logs_dialog_title)
                .setView(box)
                .setPositiveButton(R.string.policy_save, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int n;
                        try {
                            n = Integer.parseInt(input.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            n = -1;
                        }
                        if (n < LOG_MIN || n > LOG_MAX) {
                            Toast.makeText(SettingsActivity.this, R.string.set_logs_bad,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Config.get().setMaxLogFiles(n);
                        fillValues();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
