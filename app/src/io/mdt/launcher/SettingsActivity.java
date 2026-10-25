package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import android.net.Uri;

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
 *
 * ★ 2026-10-08（第 123 轮，用户点单）补的第二条判据：本页只放**能被改的项**
 *   （深浅色 / 语言 / 用户语言包 / 默认槽 / 日志保留份数 / 中转站保留份数 /
 *   崩溃提示 / 自动清理 / 存储占用）。
 *   「中转站」「运行日志」是**页面入口**，已搬去主界面的工具卡 ——
 *   "去设置里找一个页面"本来就不该是用户的心智模型。
 *   ⚠️ 以后别再往这里加"点开是另一个页面"的行（判据见 SelfTest.pageSkeleton）。
 *
 * ★ 第 174 轮（用户「设置真的应该分个类」）：按**用途**分成三张卡 ——
 *   外观与语言 / 启动与存储 / 通知与日志；新增项放对应卡，别再往一张卡里堆。
 *   分区标题是 12sp 灰字，文字由 {@link Trans} 写入，不在 XML 写 android:text。
 */
public class SettingsActivity extends BaseActivity {

    /** 第 160 轮：设置页底部单独一块「插件」卡（不混进设置项）。 */
    @Override protected String pluginPageId() { return Extensions.PAGE_SETTINGS; }

    private static final int LOG_MIN = 1;
    private static final int LOG_MAX = 999;
    /** 用户语言包的文件选择请求码（本页唯一一个 `startActivityForResult`） */
    private static final int REQ_LANG_PACK = 62;
    /** 导出翻译模板的"存到哪"请求码 */
    private static final int REQ_LANG_TPL = 63;
    /** 模板先落这里，用户选完落点再整份写过去（取消就删掉） */
    private File sTemplateTmp;
    private int sTemplateCount;
    /** 第 177 轮（杀后台 P0）：模板待办的磁盘表示（进程被杀后靠它恢复） */
    private static final String PEND_TPL = "settings.langtpl";

    private TextView mThemeSub;
    private TextView mLangSub;
    private TextView mSlotSub;
    private TextView mLogsSub;
    /** 第 174 轮：崩溃提示开关那一行的副标题（已开启 / 已关闭） */
    private TextView mCrashSub;
    /** 第 173 轮：中转站保留份数那一行的副标题（普通 / 整槽） */
    private TextView mTrashSub;
    /** F20：自动清理残留那一行的副标题（已开 / 已关） */
    private TextView mAutoCleanSub;
    /** 用户语言包那一行的副标题（当前状态） */
    private TextView mLangPackSub;

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

        // 用户语言包（lang.properties）：紧挨「语言」—— 两者都是"界面文字从哪来"。
        View rowLangPack = Util.bindActionValue(root, R.id.row_lang_pack, R.drawable.ic_language,
                R.string.lang_row_title, new Runnable() {
                    @Override public void run() { pickLangPack(); }
                });
        mLangPackSub = (TextView) rowLangPack.findViewById(R.id.act_sub);

        View rowSlot = Util.bindActionValue(root, R.id.row_def_slot, R.drawable.ic_folder,
                R.string.set_slot_title, new Runnable() {
                    @Override public void run() { pickDefaultSlot(); }
                });
        mSlotSub = (TextView) rowSlot.findViewById(R.id.act_sub);

        // 第 174 轮：崩溃提示总开关。放在日志份数上面 —— 两项都属于「通知与日志」，
        // 一个管"要不要主动说"，一个管"留几份报告"。
        View rowCrash = Util.bindActionValue(root, R.id.row_crash_alert, R.drawable.ic_terminal,
                R.string.set_crash_alert_title, new Runnable() {
                    @Override public void run() { promptCrashAlert(); }
                });
        mCrashSub = (TextView) rowCrash.findViewById(R.id.act_sub);

        View rowLogs = Util.bindActionValue(root, R.id.row_logs, R.drawable.ic_log,
                R.string.set_logs_title, new Runnable() {
                    @Override public void run() { promptLogs(); }
                });
        mLogsSub = (TextView) rowLogs.findViewById(R.id.act_sub);

        // 第 173 轮：中转站保留份数（普通条目 / 整个槽分开）—— 与日志份数相邻，
        // 两项都是"保留多少份、超出丢最旧"的保留策略。
        View rowTrash = Util.bindActionValue(root, R.id.row_trash_keep, R.drawable.ic_trash,
                R.string.set_trash_title, new Runnable() {
                    @Override public void run() { promptTrashKeep(); }
                });
        mTrashSub = (TextView) rowTrash.findViewById(R.id.act_sub);

        // F7：运行日志入口 —— ★ 2026-10-08（第 123 轮）**搬去主界面的工具卡**了
        //   （用户点单：「中转站和运行日志不应该放在设置里」）。本页从此只放"能被改的项"，
        //   不再放"点开是另一个页面"的行。日志份数（下面那行）留在这里 —— 那是一个**设置值**。

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

        // 中转站（2026-10-05 第 102 轮）—— ★ 2026-10-08（第 123 轮）**搬去主界面的工具卡**了
        //   （用户点单）。它仍然不属于任何一个槽（所以没进槽页），只是不再住"设置"里。
        //   副标题历史上是固定一句、不显示份数/体积（那要递归扫盘），这一点没变。

        // 第 175 轮：「关于」搬成独立二级页（版本 / 包名 / 数据路径 / 开源地址 / 许可证）。
        // 页面只读；入口是设置页底部单独一行（不混进三类设置）。
        Util.bindAction(root, R.id.row_about, R.drawable.ic_app,
                R.string.about_card_title, R.string.about_row_sub, new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
                    }
                });

        Util.applySystemInsets(root);
        setContentView(root);

        // 布局里那些静态文案已搬到 Java（见 Trans）：布局够不到用户语言包
        // 第 174 轮：设置页分区标题（设置项变多之后靠它们找）
        Trans.bind(root, R.id.settings_section_appearance, R.string.settings_section_appearance);
        Trans.bind(root, R.id.settings_section_startup, R.string.settings_section_startup);
        Trans.bind(root, R.id.settings_section_alerts, R.string.settings_section_alerts);

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
        if (mCrashSub != null) {
            mCrashSub.setText(Trans.get(SettingsActivity.this,
                    Config.get().crashAlertEnabled() ? R.string.set_crash_alert_on
                            : R.string.set_crash_alert_off));
        }
        if (mTrashSub != null) {
            mTrashSub.setText(Trans.get(SettingsActivity.this, R.string.set_trash_sub_fmt,
                    Config.get().trashKeep(), Config.get().trashKeepSlots()));
        }
        if (mAutoCleanSub != null) {
            mAutoCleanSub.setText(Config.get().autoCleanRedundant()
                    ? R.string.set_autoclean_on : R.string.set_autoclean_off);
        }
        if (mLangPackSub != null) {
            Trans.Pack p = Trans.installed(SettingsActivity.this)
                    ? Trans.pack(SettingsActivity.this) : null;
            if (p == null) {
                mLangPackSub.setText(Trans.get(SettingsActivity.this, R.string.lang_row_none));
            } else {
                String s = Trans.get(SettingsActivity.this, R.string.lang_stat_used_fmt, p.used());
                if (!p.ok()) {
                    s += "　" + Trans.get(SettingsActivity.this, R.string.lang_rejected_fmt,
                            p.rejected.size());
                }
                if (!p.unknown.isEmpty()) {
                    s += "　" + Trans.get(SettingsActivity.this, R.string.lang_unknown_fmt,
                            p.unknown.size());
                }
                mLangPackSub.setText(s);
            }
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
                // 第 174 轮：临时分享缓存的手动清理。文案短（三按钮排版），细节在确认框里。
                .setNeutralButton(R.string.storage_clear_cache, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        confirmClearShareCache();
                    }
                })
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
                    // ★ 2026-10-15：`err` 现在只当"失败"的标记 —— 原文（`String.valueOf(t)`）
                    //   原来会被拼进弹窗正文，用户看到的是 `java.lang.IllegalStateException: …`。
                    err = "failed";
                    android.util.Log.w("MDTLauncher", "storage stats failed: " + t);
                }
                final String fBody = body;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(SettingsActivity.this) || !dlg.isShowing()) return;
                        dlg.setMessage(fErr == null ? fBody
                                : Trans.get(SettingsActivity.this, R.string.storage_failed));
                    }
                });
            }
        }, "storage-stats").start();
    }

    /**
     * 第 174 轮：清掉 `cache/share/` 里的临时分享 / 统计缓存。
     *
     * <p>为什么只清这一层：`getCacheDir()` 里还有地图缩略图、游戏运行时临时件等，
     * 那些删了会重新生成、也可能正在被用；这里只动「直接分享」留下的那一份。
     * 正在被接收方读取的文件可能失效，所以先确认。
     */
    private void confirmClearShareCache() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.storage_clear_title)
                .setMessage(R.string.storage_clear_msg)
                .setPositiveButton(R.string.storage_clear_btn, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        clearShareCache();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void clearShareCache() {
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                int n = 0;
                boolean ok = true;
                try {
                    n = ShareOut.clear(app);
                } catch (Throwable t) {
                    ok = false;
                    android.util.Log.w("MDTLauncher", "clear share cache failed: " + t);
                }
                final int count = n;
                final boolean good = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (!good) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.storage_clear_fail, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(SettingsActivity.this,
                                count > 0 ? Trans.get(SettingsActivity.this,
                                        R.string.storage_clear_done_fmt, count)
                                        : Trans.get(SettingsActivity.this, R.string.storage_clear_none),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "clear-share-cache").start();
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

    // ── 用户语言包（lang.properties）────────────────────────────────────────

    /**
     * 用户语言包那一行。已装 = 先弹"当前状态 + 换一份 / 导出模板 / 移除"，没装 = "选一份 / 导出模板 / 取消"。
     *
     * ★ 范式与别处一致：**先在临时文件上校验，用户看到报告再决定装不装** ——
     *   一份占位符写错的包会让若干条文案静默回落（甚至崩主进程，见 {@link Trans}），
     *   所以"装之前把 rejected 摆给用户看"是这条功能的必要部分，不是可选项。
     *
     * ★★ 2026-10-12（第 140 轮）：**这一屏的按钮一度长到竖着排**（真机实测）——
     *   文案是「选择 .PROPERTIES 文件 / 导出一份模板 / 取消」这种长短句，
     *   三个加起来超过对话框宽度，`AlertController` 就退化成**竖排**，
     *   于是「正 / 中 / 负」三个按钮从下往上摞成三行、顺序看着毫无规律。
     *   ⇒ 两条纪律：**按钮文案要短**（动作词，判断依据放正文里）；
     *     以及**破坏性动作不许坐在正按钮上**（原来「移除翻译文件」是 positive = 强调位）——
     *     现在与 {@link TrashActivity} 同款放在 neutral（最左）。
     *   ⚠️ 没装包那一屏原来的正文是**复读副标题**（「用内置翻译（跟随系统语言）」），
     *     等于一个字的信息都没有，现在换成为这一步说清楚"能做什么"。
     */
    private void pickLangPack() {
        final Trans.Pack cur = Trans.installed(this) ? Trans.pack(this) : null;
        if (cur == null) {
            // 没装包：也要能导出模板（译者第一步就是拿模板），所以给一个二选一
            new AlertDialog.Builder(this)
                    .setTitle(Trans.get(SettingsActivity.this, R.string.lang_dialog_title))
                    .setMessage(Trans.get(SettingsActivity.this, R.string.lang_dialog_none_msg))
                    .setPositiveButton(Trans.get(SettingsActivity.this, R.string.lang_pack_pick),
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    openLangPackPicker();
                                }
                            })
                    .setNeutralButton(Trans.get(SettingsActivity.this, R.string.lang_tpl_export),
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    exportLangTemplate();
                                }
                            })
                    .setNegativeButton(Trans.get(SettingsActivity.this, R.string.cancel), null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(Trans.get(SettingsActivity.this, R.string.lang_dialog_title))
                .setMessage(Trans.get(SettingsActivity.this, R.string.lang_dialog_msg_fmt,
                        cur.file.getName(), cur.used(), cur.missing.size(),
                        cur.unknown.size(), cur.rejected.size()))
                .setPositiveButton(Trans.get(SettingsActivity.this, R.string.lang_pack_pick),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                openLangPackPicker();
                            }
                        })
                .setNeutralButton(Trans.get(SettingsActivity.this, R.string.lang_pack_remove),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { removeLangPack(); }
                        })
                .setNegativeButton(Trans.get(SettingsActivity.this, R.string.lang_pack_share),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                shareLangPack();
                            }
                        })
                .show();
    }

    // ── 导出翻译模板（给译者一份带全部键名 + 英文原文的底稿）──────────────────

    /**
     * 分享**当前正在用的**翻译文件（第 239 轮）—— 译者可以直接把自己的译文发出去，
     * 不用先导出到文件、再自己去聊天软件里发。
     *
     * <p>★ 为什么放在「已装」弹窗的第三个按钮：`AlertDialog` 最多三个按钮，
     *   而「重选 / 移除」必须留着；模板导出仍可在**未装**那一屏用（译者拿到模板才去翻）。
     *   走 {@link ShareOut} —— 与四个内容页、分享包**同一个**出口。
     */
    private void shareLangPack() {
        final File f = Trans.file(this);
        if (f == null || !f.isFile()) {
            android.widget.Toast.makeText(this,
                    Trans.get(this, R.string.lang_pack_share_missing),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                android.net.Uri uri = null;
                String err = null;
                try {
                    uri = ShareOut.stageCopy(SettingsActivity.this, "lang.properties", f);
                } catch (Throwable t) {
                    err = Util.ioReason(SettingsActivity.this, t);
                    android.util.Log.w("MDTLauncher", "lang share failed", t);
                }
                final android.net.Uri fu = uri;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(SettingsActivity.this)) return;
                        if (fe != null) {
                            new AlertDialog.Builder(SettingsActivity.this)
                                    .setTitle(R.string.lang_pack_share_failed)
                                    .setMessage(fe)
                                    .setPositiveButton(R.string.close, null)
                                    .show();
                            return;
                        }
                        ShareOut.launch(SettingsActivity.this, fu);
                    }
                });
            }
        }, "lang-share").start();
    }

    private void exportLangTemplate() {
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                int n = 0;
                final File tmp = new File(Trans.file(SettingsActivity.this).getParentFile(),
                        "lang-template.properties");
                try {
                    n = Trans.writeTemplate(SettingsActivity.this, tmp, null);
                } catch (Throwable t) {
                    // ★ 2026-10-15：原来是 `t.toString()` ⇒ 「模板写不出来：java.io.IOException: …
                    //   No space left on device」；现在走 `Util.ioReason`（认识的 errno 才翻）。
                    err = Util.ioReason(SettingsActivity.this, t,
                            Trans.get(SettingsActivity.this, R.string.io_reason_failed));
                    android.util.Log.w("MDTLauncher", "template write failed: " + t);
                }
                final String ferr = err;
                final int fn = n;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ferr != null) {
                            Toast.makeText(SettingsActivity.this,
                                    Trans.get(SettingsActivity.this, R.string.lang_tpl_fail_fmt, ferr),
                                    Toast.LENGTH_LONG).show();
                            tmp.delete();
                            return;
                        }
                        sTemplateTmp = tmp;
                        sTemplateCount = fn;
                        // ★ 第 177 轮（杀后台 P0）：模板路径落盘 —— 进程在选择器打开时被杀，
                        //   回来仍能写出去（旧代码字段一空就静默放弃，还留一个 tmp 在缓存里）。
                        JSONObject meta = new JSONObject();
                        try {
                            meta.put("tmp", tmp.getAbsolutePath());
                            meta.put("count", fn);
                        } catch (Throwable ignored) {
                        }
                        Pending.put(SettingsActivity.this, PEND_TPL, meta);
                        // ★ 走 SAF 的"存到哪"（与导出一份存档同一条路：不需要任何存储权限）
                        startActivityForResult(
                                Intent.createChooser(
                                        Exporter.createDoc("lang.properties", "text/plain"),
                                        Trans.get(SettingsActivity.this, R.string.lang_tpl_save_title)),
                                REQ_LANG_TPL);
                    }
                });
            }
        }).start();
    }

    /** 把模板整份写进用户选的落点（流式，与 Exporter 同一条纪律：绝不全量进内存）。 */
    private void saveTemplateTo(final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    InputStream in = new java.io.FileInputStream(sTemplateTmp);
                    java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out == null) throw new IOException("cannot write " + uri);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int k;
                        while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                    } finally {
                        out.close();
                        in.close();
                    }
                } catch (Throwable t) {
                    err = Util.ioReason(SettingsActivity.this, t,
                            Trans.get(SettingsActivity.this, R.string.io_reason_failed));
                    android.util.Log.w("MDTLauncher", "template save failed: " + t);
                }
                if (sTemplateTmp != null) sTemplateTmp.delete();
                sTemplateTmp = null;
                Pending.clear(SettingsActivity.this, PEND_TPL);
                final String ferr = err;
                final int n = sTemplateCount;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        String msg = ferr != null
                                ? Trans.get(SettingsActivity.this, R.string.lang_tpl_fail_fmt, ferr)
                                : Trans.get(SettingsActivity.this, R.string.lang_tpl_done_fmt, n);
                        Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void openLangPackPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");     // ⚠️ 通配：各家文件管理器对 .properties 报的 MIME 不一致（同模组导入那条）
        startActivityForResult(
                Intent.createChooser(i, Trans.get(SettingsActivity.this, R.string.lang_chooser_title)),
                REQ_LANG_PACK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LANG_TPL) {
            // ★ 第 177 轮：先取走磁盘待办（成败都取）
            final JSONObject pend = Pending.take(this, PEND_TPL);
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                if (sTemplateTmp == null && pend != null) {
                    String p = pend.optString("tmp", "");
                    if (!p.isEmpty()) sTemplateTmp = new File(p);
                    sTemplateCount = pend.optInt("count", 0);
                }
                if (sTemplateTmp == null || !sTemplateTmp.isFile()) {
                    // 进程被杀过、磁盘待办也没了 ⇒ 不假装保存成功
                    Toast.makeText(this, R.string.pending_lost, Toast.LENGTH_LONG).show();
                    return;
                }
                saveTemplateTo(data.getData());
            } else {
                // 用户在系统选择器里取消了（或文件已经没了）⇒ 删掉临时模板
                if (sTemplateTmp != null) {
                    sTemplateTmp.delete();
                    sTemplateTmp = null;
                }
                if (pend != null) {
                    String p = pend.optString("tmp", "");
                    if (!p.isEmpty()) new File(p).delete();
                }
            }
            return;
        }
        if (requestCode != REQ_LANG_PACK) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override public void run() {
                final File tmp = new File(getCacheDir(), "lang.properties.tmp");
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("cannot open " + uri);
                    try {
                        copyToFile(in, tmp);
                    } finally {
                        in.close();
                    }
                    final Trans.Pack p = Trans.parse(SettingsActivity.this, tmp);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showLangPackReport(p, uri, tmp); }
                    });
                } catch (final Throwable e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // ★ 按纪律：第一层只说"这件事没成"，原始异常进日志（见 §五"系统异常不许当弹窗正文"）
                            //   🔴 2026-10-15：这里原来下一行就是 `e.toString()`（注释与代码相反，
                            //     用户拿到的是 `java.io.FileNotFoundException: /data/user/0/…`）。
                            android.util.Log.w("MDTLauncher", "lang pack read failed: " + e);
                            Toast.makeText(SettingsActivity.this,
                                    Util.ioReason(SettingsActivity.this, e,
                                            Trans.get(SettingsActivity.this,
                                                    R.string.lang_pack_read_failed)),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /** 把用户选的文件整份读出来（临时文件用于校验；真正装的时候再拷到私有目录）。 */
    private static void copyToFile(InputStream in, File dst) throws IOException {
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            out.close();
        }
    }

    /**
     * 装之前的报告。★ 只有用户点了「装上」才写入私有目录 —— 取消的话那份临时文件直接删掉，
     * 用户不会因为"点错一次"就把界面文案换掉。
     */
    private void showLangPackReport(final Trans.Pack p, final Uri uri, final File tmp) {
        String msg = Trans.get(SettingsActivity.this, R.string.lang_dialog_msg_fmt,
                uri.getLastPathSegment(), p.used(), p.missing.size(),
                p.unknown.size(), p.rejected.size());
        if (p.used() == 0) {
            // 一条都用不上：说清"为什么白忙"（最常见就是键名对不上）
            msg += "\n\n" + Trans.get(SettingsActivity.this, R.string.lang_pack_none_used);
        } else if (!p.ok()) {
            msg += "\n\n" + Trans.get(SettingsActivity.this, R.string.lang_rejected_fmt,
                    p.rejected.size());
        }
        new AlertDialog.Builder(this)
                .setTitle(Trans.get(SettingsActivity.this, R.string.lang_dialog_title))
                .setMessage(msg)
                .setPositiveButton(Trans.get(SettingsActivity.this, R.string.lang_pack_ok),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                installLangPack(tmp);
                            }
                        })
                .setNegativeButton(Trans.get(SettingsActivity.this, R.string.cancel),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                tmp.delete();
                            }
                        })
                .show();
    }

    /**
     * 装上：临时文件 → 私有目录 → {@link Trans#installFromFile}。
     * ⚠️ 临时文件在 `getCacheDir()`，与私有目录**可能不同文件系统** ⇒ 必须字节流复制（不能 `renameTo`）。
     */
    private void installLangPack(final File tmp) {
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    File dst = Trans.file(SettingsActivity.this);
                    InputStream in = new java.io.FileInputStream(tmp);
                    try {
                        copyToFile(in, dst);
                    } finally {
                        in.close();
                    }
                    Trans.installFromFile(SettingsActivity.this);
                } catch (Throwable t) {
                    // ★ 2026-10-15：原来是 `t.toString()`（异常原文当 Toast 正文）
                    err = Util.ioReason(SettingsActivity.this, t,
                            Trans.get(SettingsActivity.this, R.string.lang_pack_install_failed));
                    android.util.Log.w("MDTLauncher", "lang pack install failed: " + t);
                }
                tmp.delete();      // 成功失败都清掉（校验用的临时件没用了）
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ferr != null) {
                            Toast.makeText(SettingsActivity.this, ferr, Toast.LENGTH_LONG).show();
                        }
                        // ★ 与深浅色 / 语言同一条：改的是"界面文字从哪来"，必须自己 recreate()
                        //   —— 否则用户看到的是旧文字，会以为没生效（见 pickTheme 的注释）。
                        recreate();
                    }
                });
            }
        }).start();
    }

    private void removeLangPack() {
        try {
            Trans.uninstall(SettingsActivity.this);
        } catch (Throwable t) {
            android.util.Log.w("MDTLauncher", "lang pack uninstall failed: " + t);
            Toast.makeText(SettingsActivity.this,
                    Util.ioReason(SettingsActivity.this, t,
                            Trans.get(SettingsActivity.this, R.string.lang_pack_remove_failed)),
                    Toast.LENGTH_LONG).show();
        }
        recreate();
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

    /**
     * 第 174 轮：崩溃提示开关（默认开）。
     *
     * <p>关掉只影响 {@link CrashAlert} 的 Toast / 弹窗；报告仍然照常落盘、照常进崩溃分析。
     * 选项用单选列表（与深浅色同款），不另造 Switch —— 本页所有行都是"点一下弹选择"。
     */
    private void promptCrashAlert() {
        final boolean on = Config.get().crashAlertEnabled();
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_crash_alert_pick_title)
                .setSingleChoiceItems(new String[]{
                                Trans.get(SettingsActivity.this, R.string.set_crash_alert_enable),
                                Trans.get(SettingsActivity.this, R.string.set_crash_alert_disable)},
                        on ? 0 : 1, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                d.dismiss();
                                Config.get().setCrashAlertEnabled(w == 0);
                                fillValues();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 第 173 轮：中转站保留份数 —— 普通条目与整个槽分开填。
     *
     * <p>为什么用两个输入框而不是一个：一个槽可能几十 MB，普通条目是 KB 级；
     * 合成一个数字就只能在"小文件留太少"和"大槽占太多"之间二选一。
     * 默认值来自 {@link Config#DEF_TRASH_KEEP} / {@link Config#DEF_TRASH_KEEP_SLOTS}。
     */
    private void promptTrashKeep() {
        final EditText items = new EditText(this);
        items.setInputType(InputType.TYPE_CLASS_NUMBER);
        items.setSingleLine(true);
        items.setHint(R.string.set_trash_hint);
        items.setText(String.valueOf(Config.get().trashKeep()));

        final EditText slots = new EditText(this);
        slots.setInputType(InputType.TYPE_CLASS_NUMBER);
        slots.setSingleLine(true);
        slots.setHint(R.string.set_trash_slots_hint);
        slots.setText(String.valueOf(Config.get().trashKeepSlots()));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);

        TextView itemsLabel = new TextView(this);
        itemsLabel.setText(Trans.get(SettingsActivity.this, R.string.set_trash_items_label));
        itemsLabel.setTextSize(13);
        itemsLabel.setTextColor(getResources().getColor(R.color.fg_muted, getTheme()));
        itemsLabel.setLineSpacing(dp(2), 1f);
        itemsLabel.setPadding(0, 0, 0, dp(4));
        box.addView(itemsLabel);
        box.addView(items, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView slotsLabel = new TextView(this);
        slotsLabel.setText(Trans.get(SettingsActivity.this, R.string.set_trash_slots_label));
        slotsLabel.setTextSize(13);
        slotsLabel.setTextColor(getResources().getColor(R.color.fg_muted, getTheme()));
        slotsLabel.setPadding(0, dp(10), 0, dp(4));
        box.addView(slotsLabel);
        box.addView(slots, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.set_trash_dialog_title)
                .setView(box)
                .setPositiveButton(R.string.policy_save, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int n;
                        int s;
                        try {
                            n = Integer.parseInt(items.getText().toString().trim());
                            s = Integer.parseInt(slots.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            n = -1;
                            s = -1;
                        }
                        if (n < 1 || n > 999 || s < 1 || s > 999) {
                            Toast.makeText(SettingsActivity.this, R.string.set_trash_bad,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Config.get().setTrashKeep(n);
                        Config.get().setTrashKeepSlots(s);
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
