package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
 *
 * ★ 2026-11-23（用户「所有设置把关闭放最右侧」）：本页所有弹窗的**关窗按钮坐最右**。
 *   Android 的 `AlertDialog` 按钮从右往左是 positive → negative → neutral ⇒ 关窗 / 取消
 *   一律用 `setPositiveButton`；带动作的按钮（开关、保存、装上）退到 negative，破坏性的再往左（neutral）。
 *   与 {@link CardStack} 的关窗位置（最后一个按钮 = 最右）对齐；别再写出"关闭在最左边"。
 */
public class SettingsActivity extends BaseActivity {

    /** 第 160 轮：设置页底部单独一块「插件」卡（不混进设置项）。 */
    @Override protected String pluginPageId() { return Extensions.PAGE_SETTINGS; }

    private static final int LOG_MIN = 1;
    private static final int LOG_MAX = 999;
    /** 用户语言包的文件选择请求码 */
    private static final int REQ_LANG_PACK = 62;
    /** 导出翻译模板的"存到哪"请求码 */
    private static final int REQ_LANG_TPL = 63;
    /** 第 415 轮：导入信任证书的请求码 */
    private static final int REQ_SIG_CERT = 64;
    /** 模板先落这里，用户选完落点再整份写过去（取消就删掉） */
    private File sTemplateTmp;
    private int sTemplateCount;
    /** 第 177 轮（杀后台 P0）：模板待办的磁盘表示（进程被杀后靠它恢复） */
    private static final String PEND_TPL = "settings.langtpl";

    /** 深浅色 / 屏幕方向 / 更新模式的候选（下标与下拉项一一对应）。 */
    private static final int[] THEMES = {ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK};
    private static final int[] ORIENTS = {OrientationMode.SYSTEM, OrientationMode.PORTRAIT,
            OrientationMode.LANDSCAPE};

    private View mRoot;
    private TextView mThemeSub;
    private TextView mLangSub;
    /** 第 275 轮：屏幕方向那一行的副标题（当前：跟随系统 / 强制竖屏 / 强制横屏） */
    private TextView mOrientSub;
    private TextView mSlotSub;
    private TextView mCasSub;
    /** 第 309 轮：检查更新的副标题（当前版本号，动态）。 */
    private TextView mUpdateSub;
    /** 第 313 轮：更新模式（三档）的副标题 —— 它同时回答"要不要自动查"和"查哪个通道"。 */
    private TextView mUpdateModeSub;
    /** 第 336 轮：更新线路（自动 / 直连 / 四个镜像）的副标题。 */
    private TextView mUpdateLineSub;
    /** 第 415 轮：游戏包签名校验的副标题（当前：<三档之一>）。 */
    private TextView mSigSub;
    /** 第 415 轮：信任的证书的副标题（内置 2 个 · 自定义 N 个）。 */
    private TextView mTrustSub;
    /** 第 415 轮：信任列表卡片与它的正文容器（导入证书后就地重建，不另开一张卡）。 */
    private CardStack mTrustCard;
    private LinearLayout mTrustBody;

    /** 四个开关行：只用来显示状态，点击一律走整行（开关自己不接收点击）。 */
    private android.widget.Switch mCasSwitch;
    private android.widget.Switch mCrashSwitch;
    private android.widget.Switch mAutoCleanSwitch;
    private android.widget.Switch mPluginSwitch;
    /** CAS 正在跑（进度卡挂着）—— 防连点，也防收尾前被回读改写。 */
    private boolean mCasBusy;
    /** CAS 确认框挂着 —— 期间再点行不重复开窗。 */
    private boolean mCasConfirming;
    /** 六个行内输入框的提交动作（onPause 兜底提交一遍）。 */
    private final List<Runnable> mInputCommits = new ArrayList<>();

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

    @Override
    protected void onPause() {
        // 兜底提交：输入框还聚焦着用户就按了返回 / 切到别的页面时，失焦回调不一定来。
        for (Runnable r : mInputCommits) r.run();
        super.onPause();
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void buildUi() {
        mRoot = getLayoutInflater().inflate(R.layout.activity_settings, null);
        View root = mRoot;

        // F15：深浅色。放在最上面 —— 纯外观项，也是用户最常回来改的那一项。
        Util.bindDropdownRow(root, R.id.row_theme, R.drawable.ic_theme,
                R.string.set_theme_title, new Util.DropdownSource() {
                    @Override public Util.DropdownSpec get() { return themeSpec(); }
                }, new Util.OnPick() {
                    @Override public void onPick(int index) {
                        Config.get().setThemeMode(THEMES[index]);
                        recreate();
                    }
                });
        mThemeSub = subOf(root, R.id.row_theme);

        // P2：界面语言。与深浅色相邻 —— 两者都在 BaseActivity 的同一个 Configuration 上生效。
        // ★ 2026-11-27：末项「翻译文件…」并进同一个下拉 —— 同一件事不再分两行。
        Util.bindDropdownRow(root, R.id.row_language, R.drawable.ic_language,
                R.string.set_lang_title, new Util.DropdownSource() {
                    @Override public Util.DropdownSpec get() { return langSpec(); }
                }, new Util.OnPick() {
                    @Override public void onPick(int index) {
                        String[] tags = LocaleMode.tags(SettingsActivity.this);
                        if (index >= tags.length) { pickLangPack(); return; }
                        Config.get().setAppLanguage("system".equals(tags[index])
                                ? LocaleMode.SYSTEM : tags[index]);
                        recreate();
                    }
                });
        mLangSub = subOf(root, R.id.row_language);

        // 第 275 轮：屏幕方向（跟随系统 / 强制竖屏 / 强制横屏）。放在外观卡末尾 ——
        // 它和深浅色、语言一样是"界面长什么样"的一类；只管管理器自己的页面（游戏不受影响）。
        Util.bindDropdownRow(root, R.id.row_orientation, R.drawable.ic_orientation,
                R.string.set_orientation_title, new Util.DropdownSource() {
                    @Override public Util.DropdownSpec get() { return orientSpec(); }
                }, new Util.OnPick() {
                    @Override public void onPick(int index) {
                        Config.get().setOrientationMode(ORIENTS[index]);
                        recreate();
                    }
                });
        mOrientSub = subOf(root, R.id.row_orientation);

        Util.bindDropdownRow(root, R.id.row_def_slot, R.drawable.ic_folder,
                R.string.set_slot_title, new Util.DropdownSource() {
                    @Override public Util.DropdownSpec get() { return slotSpec(); }
                }, new Util.OnPick() {
                    @Override public void onPick(int index) {
                        List<Data.Slot> slots = liveSlots();
                        if (index < 0 || index >= slots.size()) return;
                        Config.get().setDefaultSlot(slots.get(index).name);
                        fillValues();
                    }
                });
        mSlotSub = subOf(root, R.id.row_def_slot);

        // 第 174 轮：崩溃提示总开关（开关行）。放在日志份数上面 —— 两项都属于「通知与日志」，
        // 一个管"要不要主动说"，一个管"留几份报告"。
        mCrashSwitch = Util.bindSwitchRow(root, R.id.row_crash_alert, R.drawable.ic_terminal,
                R.string.set_crash_alert_title, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean target = !Config.get().crashAlertEnabled();
                        Config.get().setCrashAlertEnabled(target);
                        mCrashSwitch.setChecked(target);
                    }
                });
        setSub(root, R.id.row_crash_alert, R.string.set_crash_alert_why);

        bindNumberRow(root, R.id.row_logs, R.drawable.ic_log, R.string.set_logs_title,
                R.string.set_logs_why, new IntGetter() {
                    @Override public int get() { return Config.get().maxLogFiles(); }
                }, new IntSetter() {
                    @Override public void set(int v) { Config.get().setMaxLogFiles(v); }
                }, LOG_MIN, LOG_MAX, R.string.set_logs_bad);

        // 第 173 轮：中转站保留策略。2026-11-27 拆成三行（普通条目 / 整槽·版本 / 保留天数）——
        // 三个数塞一个弹窗里最难改的是"第二个数"。
        bindNumberRow(root, R.id.row_trash_items, R.drawable.ic_trash,
                R.string.set_trash_items_title, R.string.set_trash_items_why, new IntGetter() {
                    @Override public int get() { return Config.get().trashKeep(); }
                }, new IntSetter() {
                    @Override public void set(int v) { Config.get().setTrashKeep(v); }
                }, 1, 999, R.string.set_trash_bad);

        bindNumberRow(root, R.id.row_trash_slots, R.drawable.ic_trash,
                R.string.set_trash_slots_title, R.string.set_trash_slots_why, new IntGetter() {
                    @Override public int get() { return Config.get().trashKeepSlots(); }
                }, new IntSetter() {
                    @Override public void set(int v) { Config.get().setTrashKeepSlots(v); }
                }, 1, 999, R.string.set_trash_bad);

        bindNumberRow(root, R.id.row_trash_days, R.drawable.ic_trash,
                R.string.set_trash_days_title, R.string.set_trash_days_why, new IntGetter() {
                    @Override public int get() { return Config.get().trashKeepDays(); }
                }, new IntSetter() {
                    @Override public void set(int v) { Config.get().setTrashKeepDays(v); }
                }, 1, 3650, R.string.set_trash_days_bad);

        // 第 426 轮：临时文件（散落文件）体积上限 —— 与中转站保留策略相邻，都是"超了就丢旧的"。
        bindNumberRow(root, R.id.row_tmp_cap, R.drawable.ic_trash,
                R.string.set_tmp_cap_title, R.string.set_tmp_cap_why, new IntGetter() {
                    @Override public int get() { return Config.get().hubTmpCapMb(); }
                }, new IntSetter() {
                    @Override public void set(int v) { Config.get().setHubTmpCapMb(v); }
                }, 1, 10240, R.string.set_tmp_cap_bad);

        // 第 415 轮：安全 —— 游戏包签名校验（三档单选）+ 信任的证书（内置 原版 / MindustryX，
        // 外加用户自己导入的证书）。两行都在本页就地改变量，不做"点开是另一个页面"。
        Util.bindDropdownRow(root, R.id.row_sig_check, R.drawable.ic_verify,
                R.string.set_sig_check_title, new Util.DropdownSource() {
                    @Override public Util.DropdownSpec get() { return sigSpec(); }
                }, new Util.OnPick() {
                    @Override public void onPick(int index) {
                        SigMode.set(SettingsActivity.this, SigMode.ALL[index]);
                        fillValues();
                    }
                });
        mSigSub = subOf(root, R.id.row_sig_check);

        View rowTrust = Util.bindActionValue(root, R.id.row_sig_trust, R.drawable.ic_verify,
                R.string.set_sig_trust_title, new Runnable() {
                    @Override public void run() { showSigTrust(); }
                });
        mTrustSub = (TextView) rowTrust.findViewById(R.id.act_sub);

        // F7：运行日志入口 —— ★ 2026-10-08（第 123 轮）**搬去主界面的工具卡**了
        //   （用户点单：「中转站和运行日志不应该放在设置里」）。本页从此只放"能被改的项"，
        //   不再放"点开是另一个页面"的行。日志份数（下面那行）留在这里 —— 那是一个**设置值**。

        // ★ 2026-10-06（一档③+⑦）：存储占用 —— 备份省了多少 + 各类数据各占多少。
        //   只读：一个字节都不写。数字在后台算完再填进弹窗（见 showStorage）。
        Util.bindAction(root, R.id.row_storage, R.drawable.ic_storage,
                R.string.set_storage_title, R.string.set_storage_sub, new Runnable() {
                    @Override public void run() {
                        showStorage();
                    }
                });

        // ★ 2026-11-22：游戏包去重（CAS）——**默认关**，开了才改变存放方式（删原包）。
        //   2026-11-27 从「启动与存储」换到「实验性功能」卡（它动的是游戏包本体）。
        //   ★ 开关先翻、再弹确认；取消 / 返回就从 Config 回读翻回去（回弹）。
        mCasSwitch = Util.bindSwitchRow(root, R.id.row_cas_game, R.drawable.ic_storage,
                R.string.set_cas_title, new View.OnClickListener() {
                    @Override public void onClick(View v) { onCasToggled(); }
                });
        mCasSub = subOf(root, R.id.row_cas_game);

        // F20（2026-10-03 用户：「我们是不是应该给一些功能加上开关」）：
        // ★ 全工程**唯一一个"静默删文件"**的动作就是它，所以只给它加了开关。
        //   第 383 轮从一个开关管三样：「自动清理缓存」= 游戏缓存 + 分享缓存 + 旧版本重复文件；
        //   调度在 Cleanup.autoCleanInBackground（冷启动、游戏没在跑、每 24h 最多一次）。
        //   手动清理并进「存储占用 → 清理」，这一行只留自动开关（动作跟着数字走）。
        //   ★ 用户 2026-11-15：这一行**放在「存储占用」下面**（先看占用，再决定要不要自动清）。
        mAutoCleanSwitch = Util.bindSwitchRow(root, R.id.row_auto_clean, R.drawable.ic_health,
                R.string.set_autoclean_title, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean target = !Config.get().autoCleanRedundant();
                        Config.get().setAutoCleanRedundant(target);
                        mAutoCleanSwitch.setChecked(target);
                    }
                });
        setSub(root, R.id.row_auto_clean, R.string.set_autoclean_row_why);

        // 中转站（2026-10-05 第 102 轮）—— ★ 2026-10-08（第 123 轮）**搬去主界面的工具卡**了
        //   （用户点单）。它仍然不属于任何一个槽（所以没进槽页），只是不再住"设置"里。
        //   副标题历史上是固定一句、不显示份数/体积（那要递归扫盘），这一点没变。

        // 第 309 轮：检查更新。与「关于」同卡相邻 —— 两者都属于"版本相关"。
        // ★ 判据：点下去发一个动作（查 → 弹卡片），不改变任何设置值；副标题给"当前版本"，
        //   它是**唯一**会被这个动作更新的东西（fillValues 每次回来重取）。
        View rowUpdate = Util.bindActionValue(root, R.id.row_update, R.drawable.ic_download,
                R.string.set_update_title, new Runnable() {
                    @Override public void run() {
                        UpdateUi.checkManual(SettingsActivity.this);
                    }
                });
        mUpdateSub = (TextView) rowUpdate.findViewById(R.id.act_sub);

        // 第 313 轮：**更新模式**（三档合并）。它同时回答两个问题：
        //   "要不要自动查" 与 "查哪个通道" —— 三选一，天然不会出现自相矛盾的组合。
        // 第 313 轮：**更新模式**（三档合并）。它同时回答两个问题：
        //   "要不要自动查" 与 "查哪个通道" —— 三选一，天然不会出现自相矛盾的组合。
        Util.bindDropdownRow(root, R.id.row_update_mode, R.drawable.ic_download,
                R.string.set_update_mode_title, new Util.DropdownSource() {
                    @Override public Util.DropdownSpec get() { return updateModeSpec(); }
                }, new Util.OnPick() {
                    @Override public void onPick(int index) {
                        UpdateMode.set(SettingsActivity.this, UpdateMode.ALL[index]);
                        fillValues();
                    }
                });
        mUpdateModeSub = subOf(root, R.id.row_update_mode);

        // 第 336 轮：更新线路（自动 / 直连 / 四个预设）。点进去是「测速 / 选一条 / 关闭」卡；
        // 正常下载不测速，只有点「测速」才在后台跑。
        View rowLine = Util.bindActionValue(root, R.id.row_update_line, R.drawable.ic_download,
                R.string.set_update_line_title, new Runnable() {
                    @Override public void run() {
                        UpdateLineUi.show(SettingsActivity.this, new Runnable() {
                            @Override public void run() { fillValues(); }
                        });
                    }
                });
        mUpdateLineSub = (TextView) rowLine.findViewById(R.id.act_sub);

        // 第 378 轮：下载限速（全局一份，0 = 不限）。放在「更新线路」下面 —— 两者都管"下载怎么走"。
        bindNumberRow(root, R.id.row_download_limit, R.drawable.ic_download,
                R.string.set_download_limit_title, R.string.set_download_limit_why,
                new IntGetter() {
                    @Override public int get() {
                        long cur = Config.get().downloadLimit();
                        if (cur <= 0) return 0;
                        return (int) Math.max(1, (cur + DownloadLimit.KB / 2) / DownloadLimit.KB);
                    }
                }, new IntSetter() {
                    @Override public void set(int v) {
                        long bps = v * DownloadLimit.KB;
                        Config.get().setDownloadLimit(bps);
                        Bandwidth.set(bps);
                    }
                }, 0, (int) (DownloadLimit.MAX / DownloadLimit.KB), R.string.set_download_limit_bad);

        // 第 338 轮：**插件自动更新**（开关）。它与上一行「更新模式」是两件事 ——
        //   那个管启动器自己，这个管插件。
        mPluginSwitch = Util.bindSwitchRow(root, R.id.row_plugin_update,
                R.drawable.ic_plugins_bar, R.string.plugin_update_auto_title,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        boolean target = !Config.get().pluginAutoUpdate();
                        Config.get().setPluginAutoUpdate(target);
                        mPluginSwitch.setChecked(target);
                        fillValues();
                    }
                });

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
        // 第 415 轮：安全（游戏包签名校验 / 信任的证书）
        Trans.bind(root, R.id.settings_section_security, R.string.settings_section_security);
        // 第 378 轮：下载单列（更新线路 / 下载限速）—— 不再往"版本与更新"那张卡下面堆。
        Trans.bind(root, R.id.settings_section_download, R.string.settings_section_download);
        Trans.bind(root, R.id.settings_section_version, R.string.settings_section_version);
        // 2026-11-27：实验性功能（CAS；后面的存档守护也归这张卡）。
        Trans.bind(root, R.id.settings_section_experimental,
                R.string.settings_section_experimental);
        Trans.bind(root, R.id.settings_section_experimental_hint,
                R.string.settings_section_experimental_hint);

    }

    /**
     * 每次回本页重取一次：默认槽可能在别处被删（存档页删槽后这里要跟着回落）。
     *
     * <p>★ 第 383 轮统一小字口径（用户：「整个设置页面的小字说法都统一」）：
     * <b>有当前值 / 状态的行一律「当前：&lt;值&gt;」</b>，需要补一句时用「 · &lt;补充&gt;」；
     * 纯导航行（存储占用、关于）一律「查看&lt;它能做什么&gt;」。</p>
     */
    private void fillValues() {
        if (mThemeSub != null) {
            mThemeSub.setText(Trans.get(SettingsActivity.this, R.string.set_theme_sub_fmt,
                    getString(ThemeMode.labelRes(Config.get().themeMode()))));
        }
        if (mOrientSub != null) {
            mOrientSub.setText(Trans.get(SettingsActivity.this, R.string.set_orientation_sub_fmt,
                    getString(OrientationMode.labelRes(OrientationMode.of(this)))));
        }
        if (mLangSub != null) {
            // ★ 语言名用它自己的语言写（English / 简体中文）—— 所以这里传的是**当前上下文的
            //   Resources**，但取的数组是 translatable="false" 的，任何语言下都是同一份。
            // ★ 2026-11-27：翻译包状态并进这一句（同一件事只说一次）。
            CharSequence cur = LocaleMode.label(this, LocaleMode.of(this));
            StringBuilder s = new StringBuilder(String.valueOf(Trans.get(SettingsActivity.this,
                    R.string.set_lang_sub_fmt, cur)));
            Trans.Pack p = Trans.installed(this) ? Trans.pack(this) : null;
            if (p == null) {
                s.append(" · ").append(Trans.get(this, R.string.lang_pack_none_short));
            } else {
                s.append(" · ").append(Trans.get(this, R.string.lang_pack_sum_fmt, p.used()));
                if (!p.ok()) {
                    s.append(" · ").append(Trans.get(this, R.string.lang_row_rejected_fmt,
                            p.rejected.size()));
                }
                if (!p.unknown.isEmpty()) {
                    s.append(" · ").append(Trans.get(this, R.string.lang_row_unknown_fmt,
                            p.unknown.size()));
                }
            }
            mLangSub.setText(s);
        }
        if (mSlotSub != null) {
            String s = Config.get().defaultSlot();
            mSlotSub.setText(Trans.get(SettingsActivity.this, R.string.set_slot_sub_fmt,
                    s.isEmpty() ? Data.SLOT_DEFAULT : s));
        }
        if (mCasSwitch != null) {
            boolean on = Config.get().casGameEnabled();
            mCasSwitch.setChecked(on);
            // 三种说法：开着 / 没版本可去重 / 关着。「开着」不报省了多少 —— 现在还没删原包，
            // 报了就是假数字（真省下来要等删包那一步）。
            int res;
            if (!on) {
                res = R.string.set_cas_why_off;
            } else if (PkgAdmin.versionCount(this) == 0) {
                res = R.string.set_cas_why_none;
            } else {
                res = R.string.set_cas_why_on;
            }
            if (mCasSub != null) mCasSub.setText(Trans.get(SettingsActivity.this, res));
        }
        if (mCrashSwitch != null) {
            mCrashSwitch.setChecked(Config.get().crashAlertEnabled());
        }
        if (mAutoCleanSwitch != null) {
            mAutoCleanSwitch.setChecked(Config.get().autoCleanRedundant());
        }
        if (mPluginSwitch != null) {
            boolean on = Config.get().pluginAutoUpdate();
            mPluginSwitch.setChecked(on);
            setSub(mRoot, R.id.row_plugin_update,
                    on ? R.string.plugin_update_auto_why_on
                       : R.string.plugin_update_auto_why_off);
        }
        // ★ 输入框：聚焦中的那个不覆盖（用户可能正打到一半，回读会把光标清掉）。
        setInput(R.id.row_logs, Config.get().maxLogFiles());
        setInput(R.id.row_tmp_cap, Config.get().hubTmpCapMb());
        setInput(R.id.row_trash_items, Config.get().trashKeep());
        setInput(R.id.row_trash_slots, Config.get().trashKeepSlots());
        setInput(R.id.row_trash_days, Config.get().trashKeepDays());
        setInput(R.id.row_download_limit, kbOf(Config.get().downloadLimit()));
        if (mUpdateSub != null) {
            // 副标题只说"现在是什么版本"—— 不报"有没有新版"（那要在启动时才发现，
            // 而这一行是"点下去查"，不是"结论位"）。
            mUpdateSub.setText(Trans.get(SettingsActivity.this,
                    R.string.update_check_sub_fmt, UpdateUi.versionName(this)));
        }
        if (mUpdateModeSub != null) {
            // 副标题 = 当前模式，统一带「当前：」（第 383 轮）。
            mUpdateModeSub.setText(Trans.get(SettingsActivity.this,
                    R.string.set_update_mode_sub_fmt,
                    getString(UpdateMode.labelRes(UpdateMode.of(this)))));
        }
        if (mUpdateLineSub != null) {
            mUpdateLineSub.setText(Trans.get(SettingsActivity.this,
                    R.string.set_update_line_sub_fmt,
                    UpdateLineUi.label(this, Config.get().updateMirror())));
        }
        if (mSigSub != null) {
            // 副标题 = 当前模式，统一带「当前：」。
            mSigSub.setText(Trans.get(SettingsActivity.this,
                    R.string.set_sig_check_sub_fmt,
                    getString(SigMode.labelRes(SigMode.of(this)))));
        }
        if (mTrustSub != null) {
            mTrustSub.setText(Trans.get(SettingsActivity.this,
                    R.string.set_sig_trust_sub_fmt, 2, SigTrust.customCount(this)));
        }
    }

    // ── 行内控件的公共件（2026-11-27：开关 / 下拉 / 数字输入）──────────────────

    private TextView subOf(View root, int rowId) {
        View row = root.findViewById(rowId);
        return row == null ? null : (TextView) row.findViewById(R.id.act_sub);
    }

    private void setSub(View root, int rowId, int textRes) {
        TextView sub = subOf(root, rowId);
        if (sub != null) sub.setText(Trans.get(this, textRes));
    }

    private Util.DropdownSpec themeSpec() {
        CharSequence[] labels = new CharSequence[THEMES.length];
        int cur = Config.get().themeMode();
        int checked = 0;
        for (int i = 0; i < THEMES.length; i++) {
            labels[i] = Trans.get(this, ThemeMode.labelRes(THEMES[i]));
            if (THEMES[i] == cur) checked = i;
        }
        return new Util.DropdownSpec(labels, checked);
    }

    private Util.DropdownSpec orientSpec() {
        CharSequence[] labels = new CharSequence[ORIENTS.length];
        int cur = OrientationMode.of(this);
        int checked = 0;
        for (int i = 0; i < ORIENTS.length; i++) {
            labels[i] = Trans.get(this, OrientationMode.labelRes(ORIENTS[i]));
            if (ORIENTS[i] == cur) checked = i;
        }
        return new Util.DropdownSpec(labels, checked);
    }

    private Util.DropdownSpec sigSpec() {
        CharSequence[] labels = new CharSequence[SigMode.ALL.length];
        int cur = SigMode.of(this);
        int checked = 0;
        for (int i = 0; i < SigMode.ALL.length; i++) {
            labels[i] = Trans.get(this, SigMode.labelRes(SigMode.ALL[i]));
            if (SigMode.ALL[i] == cur) checked = i;
        }
        return new Util.DropdownSpec(labels, checked);
    }

    private Util.DropdownSpec updateModeSpec() {
        CharSequence[] labels = new CharSequence[UpdateMode.ALL.length];
        int cur = UpdateMode.of(this);
        int checked = 0;
        for (int i = 0; i < UpdateMode.ALL.length; i++) {
            labels[i] = Trans.get(this, UpdateMode.labelRes(UpdateMode.ALL[i]));
            if (UpdateMode.ALL[i] == cur) checked = i;
        }
        return new Util.DropdownSpec(labels, checked);
    }

    /** 可选槽：归档的不算（它们只是备份，不该被选成"新发现的版本默认进这个槽"）。 */
    private List<Data.Slot> liveSlots() {
        List<Data.Slot> out = new ArrayList<Data.Slot>();
        List<Data.Slot> all = Data.allSlots(this);
        if (all != null) {
            for (Data.Slot s : all) {
                if (!SlotArchive.isArchived(this, s.name)) out.add(s);
            }
        }
        return out;
    }

    private Util.DropdownSpec slotSpec() {
        List<Data.Slot> slots = liveSlots();
        String cur = Config.get().defaultSlot();
        CharSequence[] labels = new CharSequence[slots.size()];
        int checked = 0;
        for (int i = 0; i < slots.size(); i++) {
            Data.Slot s = slots.get(i);
            labels[i] = s.active ? s.name + Trans.get(this, R.string.slot_current_suffix) : s.name;
            if (s.name.equals(cur)) checked = i;
        }
        return new Util.DropdownSpec(labels, checked);
    }

    /** 语言下拉：三个语言 + 末项「翻译文件…」（末项只分岔，不改语言）。 */
    private Util.DropdownSpec langSpec() {
        String[] tags = LocaleMode.tags(this);
        String cur = LocaleMode.of(this);
        CharSequence[] labels = new CharSequence[tags.length + 1];
        int checked = 0;
        for (int i = 0; i < tags.length; i++) {
            boolean sys = "system".equals(tags[i]);
            labels[i] = sys ? Trans.get(this, R.string.lang_system) : LocaleMode.label(this, tags[i]);
            if (sys ? LocaleMode.SYSTEM.equals(cur) : tags[i].equals(cur)) checked = i;
        }
        labels[tags.length] = packItemLabel(this);
        return new Util.DropdownSpec(labels, checked);
    }

    static String packItemLabel(android.content.Context c) {
        Trans.Pack p = Trans.installed(c) ? Trans.pack(c) : null;
        return p == null
                ? String.valueOf(Trans.get(c, R.string.set_lang_pack_item))
                : String.valueOf(Trans.get(c, R.string.set_lang_pack_item_fmt, p.used()));
    }

    private interface IntGetter { int get(); }

    private interface IntSetter { void set(int v); }

    /**
     * 数字设置行：行上直接放输入框，三处提交（失焦 / 键盘完成 / onPause 兜底），且幂等 ——
     * 值没变就不写盘。空串 = 放弃修改（回读还原，不弹 Toast）。
     */
    private void bindNumberRow(View root, final int rowId, int iconRes, int titleRes,
                               int subRes, final IntGetter getter, final IntSetter setter,
                               final int min, final int max, final int badRes) {
        final View row = Util.bindActionValue(root, rowId, iconRes, titleRes, new Runnable() {
            @Override public void run() { focusInput(rowId); }
        });
        TextView sub = (TextView) row.findViewById(R.id.act_sub);
        if (sub != null) sub.setText(Trans.get(this, subRes));
        final EditText in = (EditText) row.findViewById(R.id.act_input);
        if (in == null) return;
        in.setText(String.valueOf(getter.get()));
        final Runnable commit = new Runnable() {
            @Override public void run() { commitNumber(in, min, max, getter, setter, badRes); }
        };
        in.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (!has) commit.run();
            }
        });
        in.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    commit.run();
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(in.getWindowToken(), 0);
                    in.clearFocus();
                    return true;
                }
                return false;
            }
        });
        mInputCommits.add(commit);
    }

    private void focusInput(int rowId) {
        final EditText in = inputOf(rowId);
        if (in == null) return;
        in.requestFocus();
        in.setSelection(in.getText().length());
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(in, InputMethodManager.SHOW_IMPLICIT);
        if (!(mRoot instanceof android.widget.ScrollView)) return;
        // 键盘是另一个窗口：用"可见显示区"量它挡住了多少，只在真被挡住时补最小滚动。
        in.postDelayed(new Runnable() {
            @Override public void run() {
                if (!in.isFocused()) return;
                android.graphics.Rect frame = new android.graphics.Rect();
                getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
                int[] loc = new int[2];
                in.getLocationOnScreen(loc);
                int overlap = loc[1] + in.getHeight() - (frame.bottom - dp(12));
                if (overlap > 0) ((android.widget.ScrollView) mRoot).smoothScrollBy(0, overlap);
            }
        }, 450);
    }

    private EditText inputOf(int rowId) {
        if (mRoot == null) return null;
        View row = mRoot.findViewById(rowId);
        return row == null ? null : (EditText) row.findViewById(R.id.act_input);
    }

    private void setInput(int rowId, int v) {
        EditText in = inputOf(rowId);
        if (in != null && !in.hasFocus()) in.setText(String.valueOf(v));
    }

    private static int kbOf(long bps) {
        if (bps <= 0) return 0;
        return (int) Math.max(1, (bps + DownloadLimit.KB / 2) / DownloadLimit.KB);
    }

    private void commitNumber(EditText in, int min, int max, IntGetter getter, IntSetter setter,
                              int badRes) {
        if (in == null) return;
        String raw = in.getText().toString().trim();
        if (raw.isEmpty()) {                 // 空 = 放弃修改，不弹错
            in.setText(String.valueOf(getter.get()));
            return;
        }
        int v;
        try {
            v = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            v = Integer.MIN_VALUE;
        }
        if (v < min || v > max) {
            Toast.makeText(this, badRes, Toast.LENGTH_SHORT).show();
            in.setText(String.valueOf(getter.get()));
            return;
        }
        if (v != getter.get()) setter.set(v);
        in.setText(String.valueOf(v));
    }

    /**
     * 2026-11-22：游戏包去重（CAS）的开关。**默认关**。
     *
     * <p>开启 = 把 `import/` 里现有包全部收进对象池，**成功后删掉原始 APK**（P3）；
     * 关闭 = 先把每个版本从池里拼回 APK（{@link PkgRestore}），全成功才丢清单 + 丢池子。</p>
     *
     * <p>⚠️ 拼回来的 APK 与原件不是逐字节相同（不保签名、全 STORED），但能启动、能导出；
     * 每次开着去重启动都走池，删除原件不会影响玩。</p>
     *
     * <p>★ 2026-11-27：这一行改成开关（用户在 plan 里定的「回弹 + 确认」）—— 手指点下去开关
     * 立刻翻到目标态，但真正的动作只在确认之后；取消 / 返回 / 点窗外 ⇒ 从 Config 回读翻回去。
     * 开关表示的是**配置值**，不是"操作做完了"（开启失败时配置仍是 true，开关停在开）。</p>
     */
    private void onCasToggled() {
        if (mCasBusy || mCasConfirming) return;      // 防连点（弹窗还开着 / 正在跑）
        // 池子是全局资源 ⇒ 按「有没有实例在跑」拦（走 WriteGate，别直接调 Data.gameAlive：
        // 那个全局判据有调用点预算，只留给迁移/回收/杀进程）。拦到就不翻开关 —— 反正做不成。
        if (WriteGate.blockedSlotOrGlobal(this, null)) {
            Toast.makeText(this, R.string.cas_busy, Toast.LENGTH_LONG).show();
            return;
        }
        final boolean target = !Config.get().casGameEnabled();
        mCasSwitch.setChecked(target);               // 先翻（反馈）+ 取消时回弹
        mCasConfirming = true;
        new AlertDialog.Builder(this)
                .setTitle(target ? R.string.cas_on_confirm_title : R.string.cas_off_confirm_title)
                .setMessage(Trans.get(this, target ? R.string.cas_on_confirm_body
                                                   : R.string.cas_off_confirm_body))
                .setPositiveButton(R.string.cancel, null)
                .setNegativeButton(target ? R.string.cas_on_go : R.string.cas_off_go,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                mCasBusy = true;
                                mCasSwitch.setEnabled(false);
                                applyCasGame(target);
                            }
                        })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        mCasConfirming = false;
                        // 取消的那条路：开关回读到配置值（别停在没生效的位置）。
                        if (!mCasBusy) fillValues();
                    }
                })
                .show();
    }

    /** 真正干活：后台跑，弹一张不可取消的进度卡，回来刷新副标题。 */
    private void applyCasGame(final boolean on) {
        final AlertDialog prog = new AlertDialog.Builder(this)
                .setMessage(Trans.get(this, R.string.cas_working))
                .setCancelable(false)
                .show();
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                long saved = 0;
                int n = 0;
                try {
                    if (on) {
                        // ★ P3：**先进池模式再收包** —— enable 成功后会删掉原始 APK，
                        //   这期间万一有实例启动，也得只走池（否则删到一半读到半个包）。
                        Config.get().setCasGameEnabled(true);
                        PkgAdmin.Result r = PkgAdmin.enable(SettingsActivity.this);
                        if (r.error != null) {
                            err = r.error;
                        }
                        android.util.Log.i("MDTLauncher", "cas enable: " + r.describe());
                    } else {
                        long freed = PkgAdmin.disable(SettingsActivity.this);
                        if (freed < 0) {
                            // 有包没拼回来：池子原样保留，开关也还没动 —— 告诉用户重试
                            err = Trans.get(SettingsActivity.this, R.string.cas_rebuild_failed);
                        }
                        android.util.Log.i("MDTLauncher", "cas disable: freed " + freed + " B");
                    }
                    n = PkgAdmin.versionCount(SettingsActivity.this);
                    saved = PkgAdmin.savedBytes(SettingsActivity.this);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                    android.util.Log.w("MDTLauncher", "cas toggle failed", t);
                }
                final String ferr = err;
                final long fsaved = saved;
                final int fn = n;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!Util.dead(SettingsActivity.this) && prog.isShowing()) prog.dismiss();
                        // 收尾：开关重新可用 + 按配置回读（失败时别把开关停在"已生效"的样子）。
                        mCasBusy = false;
                        if (mCasSwitch != null) mCasSwitch.setEnabled(true);
                        String msg;
                        if (ferr != null) {
                            msg = Trans.get(SettingsActivity.this, R.string.cas_failed_fmt, ferr);
                        } else if (on) {
                            msg = Trans.get(SettingsActivity.this, R.string.cas_saved_fmt,
                                    PkgAdmin.fmt(fsaved), fn);
                        } else {
                            msg = Trans.get(SettingsActivity.this, R.string.set_cas_sub_off);
                        }
                        Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_LONG).show();
                        fillValues();
                    }
                });
            }
        }).start();
    }

    /**
     * ★ 2026-10-06（一档③+⑦）：「存储占用」——**只读**的一份账。
     *
     * 结构：先把弹窗摆出来（正文写"正在统计…"），后台算完再 `setMessage` ——
     * 与 {@link MainActivity} 详情弹窗里那条"先弹窗、算完补上"是同一套：
     * 遍历各槽 + 整个对象池 + 中转站要几百毫秒，放主线程就是 ANR。
     *
     * ⚠️ 收尾必须判 {@link Util#dead}（转屏时 `isFinishing()` 是 false ⇒ 弹窗会炸）。
     * ⚠️ 卡片正文本身**只读**；两个清理按钮各有确认框，实际删除由 {@link CacheClean} /
     *   {@link ShareOut#clear} 完成（运行中的实例一律拒绝，见 CacheClean 的类注释）。
     */
    private void showStorage() {
        // ★ 第 264 轮：先出壳、后台算完补正文（原来是 `dlg.setMessage`），
        //   「清理」的确认页也推进同一张卡 —— 原来是卡上再叠一个弹窗。
        // ★ 第 383 轮（用户：「反正这些都是可以自动清理的，不如合并到一起」）：
        //   原来的「清理游戏缓存 / 清理分享缓存」两个按钮 + 底部「可以清理的残留」合成**一个**「清理」，
        //   实现在 {@link Cleanup}（游戏缓存 + 分享缓存 + 旧版本重复文件）。
        final android.widget.TextView bodyView = new android.widget.TextView(this);
        bodyView.setTextSize(15);
        bodyView.setTextColor(getResources().getColor(R.color.fg));
        bodyView.setLineSpacing(Math.round(4 * getResources().getDisplayMetrics().density), 1f);
        bodyView.setText(Trans.get(this, R.string.storage_loading));
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(null, this, Trans.get(this, R.string.set_storage_title),
                CardStack.wrap(this, bodyView),
                new CardStack.Btn(Trans.get(this, R.string.storage_clean_btn),
                        new Runnable() {
                            @Override public void run() { confirmCleanAll(ref[0]); }
                        }),
                new CardStack.Btn(Trans.get(this, R.string.close), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }));
        loadStorageText(ref[0], bodyView);
    }

    /** 后台算「存储占用」并填进那张卡（首次打开 / 清完之后复用这一份） */
    private void loadStorageText(final CardStack card, final android.widget.TextView bodyView) {
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
                        if (Util.dead(SettingsActivity.this) || !card.isShowing()) return;
                        bodyView.setText(fErr == null ? fBody
                                : Trans.get(SettingsActivity.this, R.string.storage_failed));
                        card.remeasure();       // 正文换了 ⇒ 让卡片跟着长（否则挤在小窗里滚动）
                    }
                });
            }
        }, "storage-stats").start();
    }

    // ── 第 415 轮：信任的证书 ─────────────────────────────────────────────

    /**
     * 信任列表卡片：内置两条（原版 / MindustryX，不可删）+ 用户导入的证书 + 「导入证书文件」。
     *
     * <p>为什么不单开一个页面：设置页纪律只放「能被改的项」，不做「点开是另一个页面」的行
     * （见类注释与 SelfTest.pageSkeleton）。导入完成后就地在同一张卡里重建正文，不换窗。</p>
     */
    private void showSigTrust() {
        final CardStack card = new CardStack(this);
        mTrustCard = card;
        mTrustBody = new LinearLayout(this);
        mTrustBody.setOrientation(LinearLayout.VERTICAL);
        renderSigTrust();
        card.root(Trans.get(this, R.string.sig_trust_title),
                CardStack.wrap(this, mTrustBody),
                new CardStack.Btn(Trans.get(this, R.string.close), new Runnable() {
                    @Override public void run() { card.dismiss(); }
                }));
        card.show();
    }

    private void renderSigTrust() {
        final LinearLayout body = mTrustBody;
        if (body == null) return;
        body.removeAllViews();
        body.addView(trustText(Trans.get(this, R.string.sig_trust_intro), R.color.fg_muted));
        body.addView(trustRow(Trans.get(this, R.string.sig_trust_official),
                Trans.get(this, R.string.sig_trust_builtin) + " · " + PluginTrust.shortFp(SigTrust.OFFICIAL_FP),
                null));
        body.addView(trustRow(Trans.get(this, R.string.sig_trust_mdtx),
                Trans.get(this, R.string.sig_trust_builtin) + " · " + PluginTrust.shortFp(SigTrust.MDTX_FP),
                null));
        List<String[]> customs = SigTrust.custom(this);
        if (customs.isEmpty()) {
            body.addView(trustText(Trans.get(this, R.string.sig_trust_none), R.color.fg_muted));
        } else {
            for (String[] e : customs) {
                final String fp = e[0];
                String label = e.length > 1 && e[1] != null && !e[1].isEmpty() ? e[1] : fp;
                body.addView(trustRow(label,
                        PluginTrust.shortFp(fp) + " · " + Trans.get(this, R.string.sig_trust_tap_remove),
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { confirmRemoveCert(fp, label); }
                        }));
            }
        }
        body.addView(trustRow(Trans.get(this, R.string.sig_trust_add), null,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openCertPicker(); }
                }));
        if (mTrustCard != null && mTrustCard.isShowing()) mTrustCard.remeasure();
    }

    /** 一行「标题 +（可选）副标题」；给 onClick 才可点（可点行带系统涟漪）。 */
    private View trustRow(CharSequence title, CharSequence sub, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(getResources().getDisplayMetrics().density * 12);
        row.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(getResources().getColor(R.color.fg));
        row.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12);
            s.setTextColor(getResources().getColor(R.color.fg_muted));
            row.addView(s);
        }
        if (onClick != null) {
            row.setOnClickListener(onClick);
            TypedValue tv = new TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                row.setBackgroundResource(tv.resourceId);
            }
        }
        return row;
    }

    private TextView trustText(String text, int colorRes) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setTextColor(getResources().getColor(colorRes));
        int pad = Math.round(getResources().getDisplayMetrics().density * 12);
        v.setPadding(pad, pad, pad, pad);
        v.setLineSpacing(getResources().getDisplayMetrics().density * 4, 1f);
        return v;
    }

    private void confirmRemoveCert(final String fp, final String label) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.sig_trust_remove_title)
                // 点名是哪一张（只给短指纹，多张证书时用户分不出来）
                .setMessage(label + "\n" + PluginTrust.shortFp(fp))
                // 破坏性动作放 neutral（最左），不坐正按钮（REF §29 第 28 条）。
                .setNeutralButton(R.string.sig_trust_remove_yes, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        SigTrust.remove(SettingsActivity.this, fp);
                        renderSigTrust();
                        fillValues();
                        Toast.makeText(SettingsActivity.this, R.string.sig_trust_removed_toast,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton(R.string.cancel, null)
                .show();
    }

    private void openCertPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");     // ⚠️ 通配：各家文件管理器对 .pem / .crt 报的 MIME 不一致
        startActivityForResult(
                Intent.createChooser(i, Trans.get(this, R.string.sig_trust_picker)),
                REQ_SIG_CERT);
    }

    /** 选回来的证书：解析出指纹（DER 字节的 SHA-256）再加进受信任列表。 */
    private void importCert(final Uri uri) {
        final String label = certLabel(uri);
        new Thread(new Runnable() {
            @Override public void run() {
                final String fp;
                try {
                    fp = SigTrust.fingerprintOfCert(readCapped(SettingsActivity.this, uri));
                } catch (final Throwable e) {
                    android.util.Log.w("MDTLauncher", "cert parse failed: " + e);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(SettingsActivity.this)) return;
                            Toast.makeText(SettingsActivity.this, R.string.sig_trust_bad_cert,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                final boolean added = SigTrust.add(SettingsActivity.this, fp, label);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(SettingsActivity.this)) return;
                        Toast.makeText(SettingsActivity.this,
                                added ? Trans.get(SettingsActivity.this,
                                        R.string.sig_trust_added_toast, label)
                                      : Trans.get(SettingsActivity.this, R.string.sig_trust_dup),
                                Toast.LENGTH_LONG).show();
                        if (added) renderSigTrust();
                        fillValues();
                    }
                });
            }
        }, "cert-import").start();
    }

    /** 把用户选的文件整份读出来（上限见 {@link SigTrust#MAX_CERT_BYTES}）。 */
    private static byte[] readCapped(Activity a, Uri uri) throws IOException {
        InputStream in = a.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open " + uri);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > SigTrust.MAX_CERT_BYTES) throw new IOException("cert file too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private String certLabel(Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Throwable ignored) {
            // 拿不到名字就退回落路径名 —— 不值得弹窗
        } finally {
            if (c != null) c.close();
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        if (name == null) name = "cert";
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    /**
     * 第 383 轮：**一个**「清理」= 游戏缓存 + 分享缓存 + 旧版本重复文件。
     *
     * <p>三样都是可再生的 / 有正证据的（见 {@link Cleanup}）；存档、模组、地图、蓝图、导入的
     * 游戏包、启动器数据一个都不动。游戏在跑时游戏缓存会自动跳过（其余两样照清），
     * 结果里会明说这一点。</p>
     */
    private void confirmCleanAll(final CardStack stack) {
        stack.pushMessage(Trans.get(this, R.string.storage_clean_all_title),
                Trans.get(this, R.string.storage_clean_all_msg),
                Trans.get(this, R.string.storage_clean_btn), new Runnable() {
                    @Override public void run() {
                        stack.dismiss();
                        cleanAll();
                    }
                });
    }

    private void cleanAll() {
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Cleanup.Result r = null;
                try {
                    r = Cleanup.run(app);
                } catch (Throwable t) {
                    android.util.Log.w("MDTLauncher", "clean all failed: " + t);
                }
                final Cleanup.Result rr = r;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (rr == null || (!rr.ok && !rr.any())) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.storage_clean_all_fail, Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (!rr.any()) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.storage_clean_all_none, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String msg = Trans.get(SettingsActivity.this,
                                R.string.storage_clean_all_done_fmt,
                                Util.formatSize(rr.totalBytes()),
                                rr.cache == null ? 0 : rr.cache.entries,
                                rr.shareFiles,
                                rr.dup == null ? 0 : rr.dup.cleaned);
                        if (!rr.ok) {
                            msg += "\n" + Trans.get(SettingsActivity.this,
                                    R.string.storage_clean_all_partial);
                        }
                        if (rr.cacheSkipped()) {
                            msg += "\n" + Trans.get(SettingsActivity.this,
                                    R.string.storage_clean_all_skip);
                        }
                        Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "clean-all").start();
    }

    // ── 设置项 ────────────────────────────────────────────────────────────


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
                    .setPositiveButton(Trans.get(SettingsActivity.this, R.string.cancel), null)
                    .setNeutralButton(Trans.get(SettingsActivity.this, R.string.lang_tpl_export),
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    exportLangTemplate();
                                }
                            })
                    .setNegativeButton(Trans.get(SettingsActivity.this, R.string.lang_pack_pick),
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    openLangPackPicker();
                                }
                            })
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
                    // ★ 第 369 轮：不再自己开裸流 —— 交给 Exporter.writeFile：它先在缓存里做出完整件、
                    //   拷完再把目标**读回来逐字节比对**，不过就删目标并抛错。
                    //   裸拷贝那条老路的问题是 close 异常没人管，用户会看到"保存成功"却是坏文件。
                    Exporter.writeFile(SettingsActivity.this, uri, sTemplateTmp);
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
        if (requestCode == REQ_SIG_CERT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            importCert(data.getData());
            return;
        }
        if (requestCode != REQ_LANG_PACK) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override public void run() {
                // ★ 第 382 轮（阶段 0.1）：校验用的临时件住 `app_hub/tmp`（系统清 cache 不许打断导入）
                final File tmp = new File(
                        HubTmp.session(SettingsActivity.this, HubTmp.NS_LANG), "lang.properties");
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
                .setNegativeButton(Trans.get(SettingsActivity.this, R.string.lang_pack_ok),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                installLangPack(tmp);
                            }
                        })
                .setPositiveButton(Trans.get(SettingsActivity.this, R.string.cancel),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                HubTmp.drop(SettingsActivity.this, tmp);
                            }
                        })
                .show();
    }

    /**
     * 装上：临时文件 → 私有目录 → {@link Trans#installFromFile}。
     * ⚠️ 临时文件在 `app_hub/tmp`（第 382 轮起），与 `app_hub/lang` 同属内部存储，
     *   但仍然**用字节流复制**（不依赖"同分区"这个隐含前提；`renameTo` 失败时的坑更隐蔽）。
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
                HubTmp.drop(SettingsActivity.this, tmp);   // 成功失败都清掉（校验用的临时件没用了）
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


    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
