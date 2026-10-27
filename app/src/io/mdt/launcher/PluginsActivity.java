package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * **插件页**（第 132 轮，2026-10-12）：装 / 打开 / 启用 / 停用 / 删除 + 「立即重启」。
 *
 * <h3>这个页面存在的理由（也是它唯一要说清的一句话）</h3>
 * 插件换版本**只能"退出重开"生效** —— S0 实测把天花板钉死了：**已经加载过的类换不掉**，
 * 只有宿主还没加载过的同名类才能被插件顶掉（见 {@link Plugins} 的类注释）。
 * ⇒ 所以本页每一处"启用 / 停用 / 装好了"的反馈都必须**同一句里点明**"退出重开生效"，
 * 并且给一个真的能重开的按钮（{@link Restart}）—— 否则用户会以为点了没反应。
 *
 * <h3>为什么是"独立 Activity"而不是往主界面塞一行</h3>
 * ① 设置页只放"能被改的项"（判据 = 点下去改变某个值，还是带你走），插件是**一整个页面**；
 * ② 主界面一行 = 一件独立的事，插件是**低频全局入口** ⇒ 顶栏右上角（ActionBar 菜单，
 *    与「设置」同一套规矩，见 {@code res/menu/main.xml}）。
 *
 * <h3>版式（第 180 轮改过）</h3>
 * 一列纯文字（「名字 · 状态（…装的那份）」+ 缩进的能力条目 + 满屏句子）读起来就是"文字堆积"。
 * 现在：状态进**徽标**、来源/处置进第二行小字、能力条目收进**同一张卡**里当续行、
 * 安装与重启各是一张 {@code row_action} 卡（放在列表**上面**，插件多了也够得着）、失败记录是一张警告色卡。
 * =⇒ 改版式时优先动 XML（{@code activity_plugins} / {@code item_plugin}），别又在这里堆 TextView。
 *
 * <h3>两条容易踩的</h3>
 * <ul>
 *   <li>「打开」走哪条路**取决于这个版本这程有没有被注入进宿主 CL**（{@link Plugins#isInjected}）：
 *       注入过 ⇒ {@link PluginHost#openInjected}（**不能**再建 DexClassLoader，否则 ART 报
 *       "multiple class loaders"）；没注入过（比如刚启用还没重开）⇒ {@link PluginHost#load}（自建 CL）。</li>
 *   <li>界面上的状态**只有一个来源**：{@link Plugins#rowState}（待重启生效优先于生效中）——
 *       别在这里另写一套判断，否则"装完新版"那一行会跟实际两样。</li>
 * </ul>
 */
public class PluginsActivity extends BaseActivity {

    /** 选插件 APK 的请求码（本页唯一一个 `startActivityForResult`）。 */
    private static final int REQ_PICK = 0x9B01;

    // ---- 第 159 轮：从内容页直接打开某个条目（复用本页，不另建 Activity） ----
    static final String EXTRA_ID = "plugin_id";
    static final String EXTRA_VER = "plugin_ver";
    static final String EXTRA_ENTRY = "plugin_entry";
    static final String EXTRA_PAGE = "plugin_page";
    static final String EXTRA_SLOT = "plugin_slot";
    static final String EXTRA_LABEL = "plugin_label";
    /** 第 160 轮：详情页当前选中的那一份（绝对路径）；其余页不带。 */
    static final String EXTRA_FILE = "plugin_file";

    /** 动态区（跳过横幅 / 分区标题 / 插件卡 / 空态）：`rebuild()` 只清这一层。 */
    private LinearLayout mBody;
    /** 列表骨架（含底部两张静态卡）：直接打开的插件页会把 ScrollView 内容换掉，回来时要重新挂它。 */
    private View mBodyRoot;
    private ScrollView mScroll;
    /** 跳过横幅的动态容器（第 183 轮：它仍在最上面，安装 / 重启卡在它下面、列表上面）。 */
    private LinearLayout mSkip;
    /** true = 这一趟是"直接打开某一条目"（返回 = 回上一页，不是回插件列表）。 */
    private boolean mDirect;
    /** true = 列表已换成了某个插件的页面（此时系统返回 / 顶栏箭头该回列表，而不是退出本页）。 */
    private boolean mPluginOpen;
    /** v17：当前插件页那个 Loaded（关页 / 销毁时叫 unload）。 */
    private PluginHost.Loaded mPageLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plugins);
        // 🔴 edge-to-edge（targetSdk ≥ 35）：不补 insets 的话整页从 y=0 画、被状态栏+顶栏盖住
        Util.applySystemInsets(findViewById(R.id.root));
        mScroll = (ScrollView) findViewById(R.id.plugins_scroll);
        mBodyRoot = findViewById(R.id.plugins_body);
        mSkip = (LinearLayout) findViewById(R.id.plugins_skip);
        mBody = (LinearLayout) findViewById(R.id.plugins_dynamic);
        bindStaticCards();
        String id = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ID);
        if (id != null && !id.trim().isEmpty()) {
            // ★ 内容页的动作行：标题用插件自己声明的名字，返回 = 回内容页（不是插件列表）
            mDirect = true;
            String label = getIntent().getStringExtra(EXTRA_LABEL);
            if (label != null && !label.trim().isEmpty()) setTitle(label.trim());
            open(id.trim(), getIntent().getStringExtra(EXTRA_VER),
                    getIntent().getStringExtra(EXTRA_ENTRY),
                    getIntent().getStringExtra(EXTRA_PAGE),
                    getIntent().getStringExtra(EXTRA_SLOT),
                    fileExtra(getIntent().getStringExtra(EXTRA_FILE)));
        } else {
            rebuild();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mDirect) return;            // 直接打开的页：回来时**不许**把页面换回插件列表
        rebuild();
    }

    /**
     * 返回键（第 184 轮，用户报「进入插件后返回是到主页」）：
     * 从**插件列表**点进插件页 ⇒ 先回列表；**直接打开**的那一页 ⇒ 回调用方（finish）。
     * 这里与顶栏箭头共用 {@link #backToPluginList}，别各写一套。
     */
    @Override
    public void onBackPressed() {
        if (backToPluginList(mDirect, mPluginOpen)) {
            rebuild();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home && backToPluginList(mDirect, mPluginOpen)) {
            rebuild();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 「返回」该回哪：从列表点进来的插件页 ⇒ 回列表；直接打开的页 ⇒ 回调用方。
     * 纯函数（自检钉真值表），返回键 / 顶栏箭头 / 页面底部那个「返回」都用它。
     */
    static boolean backToPluginList(boolean direct, boolean pluginOpen) {
        return !direct && pluginOpen;
    }

    /** 从内容页 / 通用页的动作行进来（第 159 轮；第 160 轮带选中项）。 */
    static void start(Activity a, String id, String ver, String entry,
                      String page, String slot, String label, File selected) {
        Intent i = new Intent(a, PluginsActivity.class);
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_VER, ver);
        i.putExtra(EXTRA_ENTRY, entry);
        i.putExtra(EXTRA_PAGE, page);
        i.putExtra(EXTRA_SLOT, slot);
        i.putExtra(EXTRA_LABEL, label);
        if (selected != null) i.putExtra(EXTRA_FILE, selected.getAbsolutePath());
        a.startActivity(i);
    }

    /** 空 / 不存在 ⇒ null（插件只该拿到"真的有一份"的选中项）。 */
    private static File fileExtra(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        File f = new File(path.trim());
        return f.isFile() ? f : null;
    }

    // ---- 页面 -----------------------------------------------------------------

    private void rebuild() {
        // 回到列表了 ⇒ 系统返回 / 顶栏箭头不再"先回列表"（下一按就该退出本页）
        mPluginOpen = false;
        // ⚠️ 「打开」那一步会把 mScroll 的内容换成插件页 ⇒ 回来时必须把列表骨架**重新挂上**，
        //   否则 mBodyRoot 已经不在视图树里：页面看着"什么都没发生"（清空的是那个孤儿容器）
        if (mScroll.getChildCount() != 1 || mScroll.getChildAt(0) != mBodyRoot) {
            mScroll.removeAllViews();
            mScroll.addView(mBodyRoot);
        }
        mSkip.removeAllViews();
        mBody.removeAllViews();

        // 跳过横幅在最上面（已经发生过的事优先于常态动作与列表）
        skipBanner();

        List<String> ids = Plugins.installedIds(this);
        if (ids.isEmpty()) {
            View empty = getLayoutInflater().inflate(R.layout.plugin_empty_card, mBody, false);
            ((TextView) empty.findViewById(R.id.plugin_empty))
                    .setText(Trans.get(this, R.string.plugin_none));
            mBody.addView(empty);
            return;
        }

        // 分区标题：只有真有插件时才出现（空态卡自己已经说清"还没有装插件"）
        addSectionHeader(Trans.get(this, R.string.plugin_section_installed));

        String act = Plugins.active(this), pend = Plugins.pending(this);
        String levels = Plugins.levels(this);
        for (final String id : ids) {
            String[] st = Plugins.rowState(act, pend, id);
            String[] vs = Plugins.versionsOf(this, id);
            String[] quar = Plugins.quarantinedOf(this, id);
            // 这一行"在说哪一份"：待生效的那份优先，其次是最新装的那份
            String probeVer = st[1].isEmpty() ? (vs.length > 0 ? vs[vs.length - 1] : null) : st[1];
            String trust = probeVer == null ? PluginTrust.THIRD : Plugins.trustOf(this, id, probeVer);
            String level = Plugins.levelOf(levels, trust, id);
            addPluginCard(id, Plugins.displayNameOf(this, id), st[0], quar.length > 0,
                    trust, level, probeVer);
        }
    }

    /** 把两张静态动作卡接上动作（只绑一次；`rebuild()` 不会碰它们）。 */
    private void bindStaticCards() {
        View root = findViewById(R.id.root);
        View install = Util.bindActionValue(root, R.id.row_plugin_install, R.drawable.ic_add,
                R.string.plugin_import, new Runnable() {
                    @Override public void run() { pickPlugin(); }
                });
        ((TextView) install.findViewById(R.id.act_sub))
                .setText(Trans.get(this, R.string.plugin_page_sub));

        View restart = Util.bindActionValue(root, R.id.row_plugin_restart, R.drawable.ic_restart,
                R.string.plugin_restart, new Runnable() {
                    @Override public void run() { restartNow(); }
                });
        ((TextView) restart.findViewById(R.id.act_sub))
                .setText(Trans.get(this, R.string.plugin_restart_hint));
    }

    /** 分区标题（与设置页 / 关于页同一个规格：12sp 灰字，上下留白按位置给）。 */
    private void addSectionHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        t.setTextColor(getResources().getColor(R.color.fg_muted));
        // 分区标题：上面 6dp（+ 容器的 10dp = 16dp，比普通卡片间距多一点，起分组作用）、
        // 下面 10dp（与卡片间距一致）—— 第 185 轮按真机量出来的像素对齐。
        t.setPadding(dp(4), dp(6), dp(4), dp(10));
        mBody.addView(t);
    }

    /**
     * 一张插件卡：名称 + 状态徽标 + 「来源 · 处置」+ 它声明的能力条目。
     *
     * @param state       {@link Plugins#rowState} 的 {@code [0]}（active / pending / off）
     * @param quarantined 手上还有隔离件（off 时徽标改说「已隔离」）
     * @param ver         这一行在说的那一份（null = 只剩隔离件、没有候选）
     */
    private void addPluginCard(final String id, String name, String state, boolean quarantined,
                               String trust, String level, final String ver) {
        View card = getLayoutInflater().inflate(R.layout.item_plugin, mBody, false);
        ((TextView) card.findViewById(R.id.plugin_name)).setText(name);
        bindBadge((TextView) card.findViewById(R.id.plugin_badge), state, quarantined);
        // 第二行：来源 + 处置等级（安装时刻不进列表 —— 那会让这行超出宽度预算；信息弹窗里有）
        ((TextView) card.findViewById(R.id.plugin_meta)).setText(
                Trans.get(this, R.string.plugin_trust_short_fmt,
                        Trans.get(this, trustShortRes(trust)),
                        Trans.get(this, levelShortRes(level))));
        // ★ 监听挂在**卡片本体**上（不是外层的行距容器）：`card_bg_press` 的按下态画在卡片上，
        //   挂错层的话点下去没有任何反馈（外层没有背景），uiautomator 里还会多出一个可点节点。
        card.findViewById(R.id.plugin_card).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { rowDialog(id); }
        });
        if (ver != null) bindExtensions(card, id, ver);
        mBody.addView(card);
    }

    /**
     * 状态徽标：**短词** + 三种语义色（生效 = accent / 待生效与隔离 = 警告 / 停用 = 中性）。
     * ★ 判据只有这一处 —— 列表行不再拼「名字 · 状态（…装的那份）」那种整句：
     *   名字与状态在这一行里已经各有一个位置了，整句会把它读成一堆字
     *   （整句形式 `plugin_state_*` 只留在老版本/用户语言包里，界面不再引用）。
     * ★ 三个映射写成**纯函数**（`badgeTextRes` / `badgeBgRes` / `badgeColorRes`），自检直接钉四态；
     *   以前这类"颜色 / 文案跟着状态走"的判断散在布局里，改一处忘一处就成了"两个状态长一样"。
     */
    static int badgeTextRes(String state, boolean quarantined) {
        if ("pending".equals(state)) return R.string.plugin_badge_pending;
        if (quarantined) return R.string.plugin_badge_quarantined;
        if ("active".equals(state)) return R.string.plugin_badge_active;
        return R.string.plugin_badge_off;
    }

    static int badgeBgRes(String state, boolean quarantined) {
        if ("pending".equals(state) || quarantined) return R.drawable.badge_slot_warn;
        return "active".equals(state) ? R.drawable.badge_slot : R.drawable.badge_muted;
    }

    static int badgeColorRes(String state, boolean quarantined) {
        if ("pending".equals(state) || quarantined) return R.color.chip_fg_warn;
        return "active".equals(state) ? R.color.chip_fg : R.color.fg_muted;
    }

    private void bindBadge(TextView badge, String state, boolean quarantined) {
        badge.setText(Trans.get(this, badgeTextRes(state, quarantined)));
        badge.setBackgroundResource(badgeBgRes(state, quarantined));
        badge.setTextColor(getResources().getColor(badgeColorRes(state, quarantined)));
    }

    /** 1dp 分割线，起点与卡片里那行插件名对齐（50dp = 14 padding + 24 图标 + 12 间距）。 */
    private View cardDivider() {
        View v = new View(this);
        v.setBackgroundColor(getResources().getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMarginStart(dp(50));
        v.setLayoutParams(lp);
        return v;
    }

    /** 上次带插件启动没成功 ⇒ 一张警告卡说清楚"谁被回滚 / 谁被停用"，并给一条恢复的路。 */
    private void skipBanner() {
        final List<String[]> skip = Plugins.lastSkip(this);
        if (skip.isEmpty()) return;
        // ★ 只讲**现在还装着的**那些：插件都卸载了就别翻旧账 —— 那句话此时没头没尾
        //   （"probe：v2 没起来" 配着"还没有装插件"读起来像自相矛盾）
        final List<String> ids = Plugins.installedIds(this);
        final List<String[]> live = new ArrayList<String[]>();
        boolean restorable = false;
        for (String[] s : skip) {
            if (!ids.contains(s[0])) continue;
            live.add(s);
            for (String v : Plugins.versionsOf(this, s[0])) if (v.equals(s[1])) restorable = true;
        }
        if (live.isEmpty()) return;

        View card = getLayoutInflater().inflate(R.layout.plugin_skip_card, mSkip, false);
        ((TextView) card.findViewById(R.id.plugin_skip_title))
                .setText(Trans.get(this, R.string.plugin_skip_title));

        // 每个插件一行；名字与列表卡同一个来源（自述名字优先）—— 别让同一件事有两个名字
        StringBuilder sb = new StringBuilder();
        for (String[] s : live) {
            boolean q = s.length > 5 && "yes".equals(s[5]);
            String name = Plugins.displayNameOf(this, s[0]);
            String line;
            if ("retry".equals(s[3])) {
                line = Trans.get(this, R.string.plugin_skip_retry_fmt, name,
                        verLabel(s[0], s[1]));
            } else if ("rollback".equals(s[3])) {
                line = Trans.get(this, q ? R.string.plugin_skip_rollback_q_fmt
                                         : R.string.plugin_skip_rollback_fmt, name,
                        verLabel(s[0], s[1]), verLabel(s[0], s[2]));
            } else {
                line = Trans.get(this, q ? R.string.plugin_skip_quarantine_fmt
                                         : R.string.plugin_skip_disable_fmt, name,
                        verLabel(s[0], s[1]));
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        ((TextView) card.findViewById(R.id.plugin_skip_detail)).setText(sb.toString());

        TextView restore = (TextView) card.findViewById(R.id.plugin_skip_restore);
        if (restorable) {
            // 那一版的文件不在了 ⇒ 这个按钮按下去什么都不会发生，那就别给
            restore.setText(Trans.get(this, R.string.plugin_skip_restore));
            restore.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { restoreSkipped(live); }
            });
        } else {
            restore.setVisibility(View.GONE);
        }
        mSkip.addView(card);
    }

    /**
     * 「把插件重新启用」：按上次的现场把**失败的那一版**重新记进 pending（退出重开再生效）。
     * ⚠️ 只是"再试一次"的意思，不是"跳过安全阀" —— 再崩一次的话安全阀照样会回滚。
     */
    private void restoreSkipped(List<String[]> skip) {
        int n = 0;
        for (String[] s : skip) {
            String ver = s[1];
            if (ver == null || ver.isEmpty()) continue;
            // ★ 那一版的文件得还在（用户可能已经卸载 / 启动成功那次被清过）——
            //   补登一个不存在的文件只会让下次启动多一条 MISSING
            boolean have = false;
            for (String v : Plugins.versionsOf(this, s[0])) if (v.equals(ver)) have = true;
            if (!have) continue;
            Plugins.enable(this, s[0], ver);
            n++;
        }
        rebuild();
        // ★ 第 181 轮（用户）：「启用 / 停用之后加个提示，问要不要重启管理器」——
        //   原来只弹一句 Toast（用户还得自己滚到底下找「立即重启」）⇒ 现在直接问，
        //   「稍后」= 关掉弹窗、页面上的「待重启」徽标和底部那张卡照样在。
        if (n > 0) askRestart(Trans.get(this, R.string.plugin_restart_hint));
    }

    // ---- 一行的动作 -------------------------------------------------------------

    private void rowDialog(final String id) {
        final String act = Plugins.stateGet(Plugins.active(this), id);
        final String pend = Plugins.stateGet(Plugins.pending(this), id);
        final String[] vers = Plugins.versionsOf(this, id);
        final String newest = vers.length == 0 ? null : vers[vers.length - 1];
        final String[] quar = Plugins.quarantinedOf(this, id);
        final String qv = quar.length == 0 ? null : quar[quar.length - 1];

        // ★ 来源 + 处置等级：列表卡第二行已经写着同一份判据；这里用它决定"下一档是什么"与"要不要给恢复"
        final String trust = newest == null ? PluginTrust.THIRD : Plugins.trustOf(this, id, newest);
        final String lv = Plugins.levelOf(Plugins.levels(this), trust, id);
        // ★ 按来源算下一档：内置只在 L1↔L2 之间转（它被封顶在 L2，给 L3 只会骗人）
        final String nextLv = Plugins.cycleLevelFor(trust, lv);

        List<String> items = new ArrayList<String>();
        final List<String> actions = new ArrayList<String>();
        final String cur = pend != null ? pend : act;   // 这一程"打算用"的那一版（待生效优先）
        if (act != null) { items.add(Trans.get(this, R.string.plugin_action_open)); actions.add("open"); }
        if (newest != null && !newest.equals(cur)) {
            items.add(Trans.get(this, R.string.plugin_action_enable)); actions.add("enable");
        }
        items.add(Trans.get(this, R.string.plugin_action_disable));
        actions.add("disable");
        // 🔴 内置插件**不给删除入口**（随本体发放，不能卸载）—— 就算给了，下次启动也会被放回来
        final boolean builtinRow = Plugins.isBuiltinId(this, id);
        if (!builtinRow) {
            items.add(Trans.get(this, R.string.plugin_action_uninstall));
            actions.add("uninstall");
        }
        if (qv != null) {
            // ★ 第 182 轮：不再把版本令牌（内置是 sha 前 8 位）拼进动作名 —— 「把隔离的那份放回来」就够
            items.add(Trans.get(this, R.string.plugin_action_restore_fmt));
            actions.add("restore");
        }
        items.add(Trans.get(this, R.string.plugin_action_level_fmt,
                        Trans.get(this, levelShortRes(nextLv))));
        actions.add("level");
        items.add(Trans.get(this, R.string.plugin_action_info));
        actions.add("info");

        // ★ 第 259 轮：改成**同一个窗里换页**（`CardStack`）—— 「插件信息」原来是再开一个 Dialog，
        //   叠窗那一下旧窗的 dim 跟着窗口一起消失、新窗的 dim 要重新渐入 ⇒ 整屏亮一下（用户：「还是闪」）。
        //   同窗换页时窗口与 dim 都不变，只有卡片里的内容交叉淡入。
        final CardStack stack = new CardStack(this);
        final CharSequence rowTitle = Plugins.displayNameOf(this, id);
        stack.root(rowTitle,
                CardStack.list(this, items.toArray(new CharSequence[0]), new CardStack.ItemClick() {
                    @Override public void onClick(int which) {
                        String a = actions.get(which);
                        if ("info".equals(a)) {
                            stack.push(rowTitle, CardStack.text(PluginsActivity.this,
                                    infoText(id, newest, trust, lv)));
                            return;
                        }
                        if ("open".equals(a)) {
                            stack.dismiss();
                            open(id, act);
                        } else if ("enable".equals(a)) {
                            // ★ v23：第三方 + 进程内探针 ⇒ 启用前弹一次风险告知（官方 / 隔离进程不弹）
                            final String want = newest;
                            java.util.List<PluginProbes.Spec> specs = PluginProbes.parse(id, want, "",
                                    PluginMeta.zipText(Plugins.apkOf(PluginsActivity.this, id, want),
                                            Extensions.ASSET));
                            if (PluginProbes.needsRiskPrompt(
                                    Plugins.trustOf(PluginsActivity.this, id, want), specs)) {
                                // 第 262 轮：风险告知**推进同一张卡**（原来是再开一个 Dialog ⇒ 换窗会闪）
                                stack.pushMessage(Trans.get(PluginsActivity.this,
                                                R.string.plugin_probe_risk_title),
                                        Trans.get(PluginsActivity.this, R.string.plugin_probe_risk_msg),
                                        Trans.get(PluginsActivity.this, R.string.plugin_action_enable),
                                        new Runnable() {
                                            @Override public void run() {
                                                doEnable(stack, id, want);
                                            }
                                        });
                            } else {
                                doEnable(stack, id, want);
                            }
                        } else if ("disable".equals(a)) {
                            // ★ 只有"这一程本来在跑的那一份"被拿掉才需要重启；
                            //   只取消 pending（还没加载过）的话重启也没意义 ⇒ 只给一句 Toast。
                            boolean wasActive = Plugins.stateGet(Plugins.active(PluginsActivity.this), id) != null;
                            Plugins.disable(PluginsActivity.this, id);
                            String done = Trans.get(PluginsActivity.this, R.string.plugin_disabled_fmt,
                                    Plugins.displayNameOf(PluginsActivity.this, id));
                            rebuild();
                            if (wasActive) {
                                askRestart(stack, done);
                            } else {
                                stack.dismiss();
                                // v27：不影响下次启动的改动，不弹重启对话框 ——
                                //   但**必须把"不用重启"说出来**，否则用户只感觉“时弹时不弹”。
                                Toast.makeText(PluginsActivity.this,
                                        Trans.get(PluginsActivity.this, R.string.plugin_pending_cancelled),
                                        Toast.LENGTH_LONG).show();
                            }
                        } else if ("uninstall".equals(a)) {
                            // ★ 搬不动时 uninstall 返回 false、插件**还在**（数据没丢）⇒ 界面必须说一句，
                            //   否则用户以为已经删掉了（第 148 轮 C1-b）
                            boolean wasActive = Plugins.stateGet(Plugins.active(PluginsActivity.this), id) != null;
                            boolean uninstalled = Plugins.uninstall(PluginsActivity.this, id);
                            // 🔴 2026-10-15：成功那句原来**无条件**弹 —— 失败时会先说
                            //   「卸载没成功…什么都没删」紧接着又说「已删除 X」（真机同屏两条 Toast 打架）。
                            //   名字也走 displayNameOf（别把内部 id 端给用户）。
                            if (uninstalled) {
                                String done = Trans.get(PluginsActivity.this, R.string.plugin_uninstalled_fmt,
                                        Plugins.displayNameOf(PluginsActivity.this, id));
                                rebuild();
                                if (wasActive) askRestart(stack, done);
                                else {
                                    stack.dismiss();
                                    Toast.makeText(PluginsActivity.this, done, Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                stack.dismiss();
                                Toast.makeText(PluginsActivity.this,
                                        Trans.get(PluginsActivity.this, R.string.plugin_uninstall_failed),
                                        Toast.LENGTH_LONG).show();
                                rebuild();
                            }
                        } else if ("restore".equals(a)) {
                            stack.dismiss();
                            boolean ok = Plugins.restoreQuarantined(PluginsActivity.this, id, qv);
                            Toast.makeText(PluginsActivity.this,
                                    Trans.get(PluginsActivity.this, ok ? R.string.plugin_restored_fmt
                                                                       : R.string.plugin_restore_failed),
                                    Toast.LENGTH_LONG).show();
                            rebuild();
                        } else if ("level".equals(a)) {
                            stack.dismiss();
                            Plugins.setLevel(PluginsActivity.this, id, nextLv);
                            Toast.makeText(PluginsActivity.this,
                                    Trans.get(PluginsActivity.this, R.string.plugin_level_set_fmt,
                                            id, Trans.get(PluginsActivity.this, levelRes(nextLv))), Toast.LENGTH_LONG).show();
                            rebuild();
                        }
                    }
                }),
                new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                }));
        stack.show();
    }

    /**
     * 「插件信息」那一页的正文：自述里那些**说明性**的字段（名称 / 自述版本 / 作者 / 说明）+ 入口类 + 来源 / 处置 / 文件版本。
     *
     * <p>★ 第 259 轮起它**只是一段文字**（`CharSequence`），由调用方推进 {@link CardStack} 的下一页 ——
     * 以前这里是再开一个 `AlertDialog`（框架那个"`setMessage` 与 `setItems` 同用会把 items 吞掉"的坑，
     * 第 133 轮真机踩的，所以信息只能单独一屏）⇒ 但**换窗**会让 dim 重播、整屏闪一下，改成同窗换页。
     * ★ 空字段整条不印（{@link Plugins#dropEmptyPairs}）—— 不许出现「作者：null」或空行。
     */
    private CharSequence infoText(String id, String fileVer, String trust, String lv) {
        File apk = fileVer == null ? null : Plugins.apkOf(this, id, fileVer);
        PluginMeta.Info meta = apk == null ? null : PluginMeta.read(this, apk);
        // ★ 第 182 轮：内置插件的"文件版本"是 sha 前 8 位，不是装进来的时间 ⇒ 这行只在真的是时间戳时才印
        boolean timeVer = Plugins.isTimeVersion(fileVer);
        String[] labels = {
                Trans.get(this, R.string.plugin_info_name),      // 内部 id（稳定键）
                Trans.get(this, R.string.plugin_info_selfname),  // 自述名称
                Trans.get(this, R.string.plugin_info_selfver),
                Trans.get(this, R.string.plugin_info_author),
                Trans.get(this, R.string.plugin_info_desc),
                Trans.get(this, R.string.plugin_info_entry),
                Trans.get(this, R.string.plugin_info_source),
                Trans.get(this, R.string.plugin_info_level),
                timeVer ? Trans.get(this, R.string.plugin_info_filever) : "",
                Trans.get(this, R.string.plugin_info_access),
        };
        String entry = apk == null ? null : PluginMeta.entryOf(this, apk, PluginHost.DEFAULT_ENTRY);
        String[] values = {
                id,
                meta == null ? null : meta.name,
                meta == null ? null : meta.version,
                meta == null ? null : meta.author,
                meta == null ? null : meta.description,
                entry,
                Trans.get(this, trustLongRes(trust)),
                Trans.get(this, levelRes(lv)),
                timeVer ? Plugins.versionLabel(fileVer) : "",
                // ★ 第 148 轮 C2：「它要碰什么」**永远显示一行**（哪怕是"没有声明"）——
                //   安全相关的字段留白 = 用户分不清"没声明"和"我们没看见"。声明是**告知**，不是沙箱。
                accessText(apk),
        };
        StringBuilder sb = new StringBuilder();
        for (String[] pair : Plugins.dropEmptyPairs(labels, values)) {
            if (sb.length() > 0) sb.append('\n');
            // 标签资源自带 `%1$s` 占位符 ⇒ 分隔符与语序都跟着语言走
            // （⚠️ 别用"标签末尾带空格"当分隔符：aapt2 会把首尾空白剥掉，装到机器上标签与值会连体 —— 门禁 RES-04 抓过）
            sb.append(String.format(pair[0], pair[1]));
        }
        return sb;
    }

    /** 来源 → 界面上那个短名（唯一映射处：列表行与信息弹窗共用）。 */
    static int trustShortRes(String trust) {
        if (PluginTrust.BUILTIN.equals(trust)) return R.string.plugin_trust_short_builtin;
        return PluginTrust.OFFICIAL.equals(trust) ? R.string.plugin_trust_short_official
                                                  : R.string.plugin_trust_short_third;
    }

    /** 来源 → 完整说法（信息弹窗里用）。 */
    static int trustLongRes(String trust) {
        if (PluginTrust.BUILTIN.equals(trust)) return R.string.plugin_trust_builtin;
        return PluginTrust.OFFICIAL.equals(trust) ? R.string.plugin_trust_official
                                                  : R.string.plugin_trust_third;
    }

    /** 等级 → 界面上那句人话（唯一映射处：别在几个地方各写一份）。 */
    /**
     * 三档的**短形式**（列表行 / 动作标签用 —— 那里放不下整句）。
     * ★ 与 {@link #levelRes} 同一条判据：**界面上不许出现 L1/L2/L3 这种内部代号**（用户看不到代码里的名字，
     * 只该看到"会发生什么"）。
     */
    static int levelShortRes(String level) {
        if (PluginTrust.L1.equals(level)) return R.string.plugin_level_short_l1;
        if (PluginTrust.L3.equals(level)) return R.string.plugin_level_short_l3;
        return R.string.plugin_level_short_l2;
    }

    static int levelRes(String level) {
        if (PluginTrust.L1.equals(level)) return R.string.plugin_level_l1;
        if (PluginTrust.L3.equals(level)) return R.string.plugin_level_l3;
        return R.string.plugin_level_l2;
    }

    /** 「打开」：注入过就走宿主 CL 那条路，没注入过才自建 DexClassLoader（同一个 dex 不许注册两次）。 */
    private void open(final String id, final String ver) {
        open(id, ver, null, Extensions.SLOT_PLUGINS, Data.currentSlot(this), null);
    }

    /**
     * 同上，但可以指定入口类（第 139 轮：点插件声明的**能力条目**时，要开的是那一条自己的入口类）。
     *
     * @param entryOverride null = 用插件自述里的默认入口
     */
    private void open(final String id, final String ver, final String entryOverride) {
        open(id, ver, entryOverride, Extensions.SLOT_PLUGINS, Data.currentSlot(this), null);
    }

    /**
     * 第 159 轮：把"从哪一页来 / 哪个槽"一起交给插件（{@link io.mdt.launcher.api.PluginApi}）。
     *
     * @param page {@code plugins} 或任一页面 id；null ⇒ plugins
     * @param slot 当前槽；null ⇒ 当前槽
     * @param selected 详情页当前那一份；其余页 null
     */
    private void open(final String id, final String ver, final String entryOverride,
                      String page, String slot, File selected) {
        final String usePage = (page == null || page.trim().isEmpty())
                ? Extensions.SLOT_PLUGINS : page.trim();
        final String useSlot = (slot == null || slot.trim().isEmpty())
                ? Data.currentSlot(this) : slot.trim();
        File apk = Plugins.apkOf(this, id, ver);
        View pageView;
        try {
            // ★ 入口类按**这个插件自己的**自述取（第 135 轮）——多插件下"点谁开谁"全靠它
            //   （共用同一个入口 FQN 时，宿主 CL 只会认第一个注入的那个插件）
            String entry = entryOverride != null ? entryOverride
                    : PluginMeta.entryOf(this, apk, PluginHost.DEFAULT_ENTRY);
            PluginHost.Loaded l = Plugins.isInjected(this, id, ver)
                    ? PluginHost.openInjected(this, apk, entry, usePage, useSlot, selected)
                    : PluginHost.load(this, apk, entry, usePage, useSlot, selected);
            // ★ 一处实现（第 159 轮）：v20 起只有 createView(PluginApi)，没有老写法回落
            mPageLoaded = l;
            View v = PluginHost.createView(l);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            // 「已加载 xxx（接口 N）」是给用户判断"这份到底跑没跑起来"的，字号压小、不抢插件自己的页面
            // ★ 第 184 轮：原来传的是 `apk.getName()`（内置插件就是 `2ed851ab.apk` —— 又是 sha 前 8 位），
            //   改成插件的自述名字；"哪一份"由卡片 / 信息弹窗负责，别在这里再漏一次内部令牌。
            TextView loaded = new TextView(this);
            loaded.setText(Trans.get(this, R.string.plugin_loaded_fmt,
                    Plugins.displayNameOf(this, id), l.api));
            loaded.setTextSize(12f);
            loaded.setTextColor(getResources().getColor(R.color.fg_muted));
            loaded.setPadding(0, 0, 0, dp(8));
            box.addView(loaded);
            if (v != null) box.addView(v);
            pageView = box;
        } catch (PluginHost.PluginException e) {
            // ★ 接口版本那句按"太新 / 太老"分岔（第 139 轮）：一句"不匹配"让用户不知道该升哪一边
            int rr = PluginHost.reasonRes(e.reason);
            pageView = fallback(rr == R.string.plugin_err_api_fmt
                    ? Trans.get(this, PluginHost.apiReasonRes(e.num1), e.num1,
                            PluginHost.API_MIN, PluginHost.API)
                    : Trans.get(this, rr));
        } catch (Throwable t) {
            pageView = fallback(Trans.get(this, R.string.plugin_err_page_fmt,
                    Plugins.displayNameOf(this, id)));
        }
        // 插件自己的页面 + 一个「返回」按钮（直接打开的 ⇒ 回上一页；从列表点的 ⇒ 重建本页）
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        TextView back = new TextView(this);
        back.setText(Trans.get(this, R.string.plugin_back));
        back.setTextSize(14f);
        back.setTextColor(getResources().getColor(R.color.accent));
        back.setBackgroundResource(R.drawable.btn_ghost);
        back.setPadding(dp(14), dp(7), dp(14), dp(7));
        back.setGravity(Gravity.CENTER);
        back.setClickable(true);
        back.setFocusable(false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                closePluginPage();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(12);
        root.addView(back, blp);
        root.addView(pageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mScroll.removeAllViews();
        mScroll.addView(root);
        // ★ 页面已换成插件页：系统返回 / 顶栏箭头要先回列表（第 184 轮，用户报的正是"直接回主页"）
        mPluginOpen = true;
    }

    /**
     * 关掉插件页、回到"打开它的那一页"（第 187 轮：插件自己的 {@code closePage()} 也走这里）。
     *
     * <p>★ 一处实现：页面底部「返回」按钮、系统返回键、顶栏箭头、插件调用的 closePage
     * 全都落到同一个去处 —— 从列表点进来的回列表，直达进来的回调用方。
     */
    void closePluginPage() {
        releasePage();
        if (backToPluginList(mDirect, mPluginOpen)) rebuild();
        else finish();
    }

    /** 插件没加载成时的兜底视图：**只说一句人话**（原因来自 {@link PluginHost#reasonRes}）。 */
    /** 关插件页：先让插件收尾（v17），再做导航。 */
    private void releasePage() {
        PluginHost.Loaded l = mPageLoaded;
        mPageLoaded = null;
        if (l != null) PluginHost.unload(l);
    }

    @Override protected void onDestroy() {
        releasePage();
        super.onDestroy();
    }

    private View fallback(String why) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 加载失败 = 警告，用与跳过横幅同一张警告底（不是又一块普通白卡）
        box.setBackgroundResource(R.drawable.card_warn_bg);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText(Trans.get(this, R.string.plugin_fallback_title));
        title.setTextSize(14f);
        // 与布局里的 fontFamily="sans-serif-medium" 同一款（代码建的视图拿不到 XML 那个属性）
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        title.setTextColor(getResources().getColor(R.color.chip_fg_warn));
        title.setPadding(0, 0, 0, dp(6));
        box.addView(title);

        TextView detail = new TextView(this);
        detail.setText(why);
        detail.setTextSize(14f);
        detail.setTextColor(getResources().getColor(R.color.fg));
        detail.setLineSpacing(dp(3), 1f);
        box.addView(detail);
        return box;
    }

    // ---- 装 / 重启 --------------------------------------------------------------

    private void pickPlugin() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // ⚠️ 通配：各家文件管理器对 .apk 报的 MIME 不一致（与模组导入、语言包那两处同一条坑）
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i, Trans.get(this, R.string.plugin_chooser_title)),
                REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override public void run() {
                final File tmp = new File(getCacheDir(), "plugin-import.apk");
                String msg;
                boolean ok = false;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("cannot open " + uri);
                    try {
                        Util.copyStream(in, tmp);
                    } finally {
                        in.close();
                    }
                    String id = baseName(tmp, uri);
                    // ★ 这一版的身份 = "装进来的那一刻"（epoch 秒）：它是**落点目录名 + 状态键**，
                    //   必须唯一（置只读的副本不能再被覆盖，见 ref/29 第 3 条）。
                    //   ⚠️ 但它**不是版本号**、也不该那样显示（第 150 轮：`Plugins.versionLabel` 把它
                    //   转成 `MM-dd HH:mm` 再上屏；自述里的版本号另有其字段）。
                    String ver = String.valueOf(System.currentTimeMillis() / 1000L);
                    String r = Plugins.install(PluginsActivity.this, tmp, id, ver, false);
                    ok = !r.startsWith("FAILED");
                    msg = ok
                            ? Trans.get(PluginsActivity.this, R.string.plugin_installed_fmt,
                                    id, verLabel(id, ver))
                            : Trans.get(PluginsActivity.this, R.string.plugin_install_failed_fmt,
                                    installFailReason(r));
                } catch (final Throwable t) {
                    // 按纪律：第一层只说"这件事没成"，原始异常进日志（§五"系统异常不许当弹窗正文"）
                    android.util.Log.w(LauncherApp.TAG, "plugin import failed", t);
                    // 🔴 2026-10-15：原来这里传的是 `t.getClass().getSimpleName()`
                    //   ⇒ 用户看到「没装成：FileNotFoundException」（那句注释说的和做的相反）。
                    msg = Trans.get(PluginsActivity.this, R.string.plugin_install_failed_fmt,
                            Trans.get(PluginsActivity.this, R.string.plugin_install_unknown));
                } finally {
                    tmp.delete();
                }
                final String m = msg;
                final boolean doneOk = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        rebuild();
                        // ★ 装失败时**不再**顺带说"退出重开生效"（原来失败也走 toastRestart，
                        //   读起来像"没装成，但记得重开"）；成功才问要不要重启。
                        if (doneOk) askRestart(m);
                        else Toast.makeText(PluginsActivity.this, m, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "plugin-import").start();
    }

    /** 文件名当插件 id（去掉 `.apk`）；拿不到就退回 uri 的最后一段。 */
    private static String baseName(File tmp, Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null || n.isEmpty()) n = "plugin";
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) n = n.substring(0, n.length() - 4);
        return n.isEmpty() ? "plugin" : n;
    }

    /**
     * `Plugins.install` 的失败返回值是**内部英文码 + 绝对路径**（`FAILED mkdirs /data/…`）——
     * 那是给日志看的，**不进第一层**（2026-10-15 起）。这里只按**类别**给一句人话；
     * 认不出的类别一律「换一份再试」，绝不把那串原文端给用户。
     */
    private String installFailReason(String r) {
        String s = r == null ? "" : r;
        if (s.contains("missing src") || s.contains("bad id")) {
            return Trans.get(this, R.string.plugin_install_pick_lost);
        }
        if (s.contains("mkdirs")) return Trans.get(this, R.string.plugin_install_no_dir);
        if (s.contains("cannot replace")) return Trans.get(this, R.string.plugin_install_stuck_old);
        return Trans.get(this, R.string.plugin_install_unknown);
    }

    /**
     * 「立即重启」：挂闹钟 + 自杀；**挂不上就如实说**（不许假装重启成功）。
     *
     * <p>★ 第 276 轮两条修正（都是用户「第一个重启后第二个不让重启了」那条线上一路查出来的）：
     * <ol>
     *   <li>走 {@link Restart#scheduleUser}：用户按的按钮**不撞** 15 秒去重窗口（那个窗口只该挡
     *       "dev 口重启后被系统用同一个 Intent 重放"）。</li>
     *   <li>没有「闹钟与提醒」权限时**先问、再挂闹钟**（原来先挂再问）：闹钟 900 ms 后就响了，
     *       用户稍慢一点，它就先把 MainActivity 拉到前台（进程还活着）——界面看着"回到主页"，
     *       其实什么都没重启；更糟的是随后 killSelf 把进程杀了，而闹钟**已经响过** ⇒ 没人再拉起应用。
     *       ⇒ 顺序改成"确认之后才挂闹钟 + 立刻自杀"，那条竞态就不存在了。</li>
     * </ol>
     */
    private void restartNow() {
        if (!Restart.exactAllowed(this)) {
            final CardStack[] ref = new CardStack[1];
            ref[0] = CardStack.show(null, this, Trans.get(this, R.string.plugin_restart),
                    CardStack.text(this, Trans.get(this, R.string.plugin_restart_inexact)),
                    new CardStack.Btn(Trans.get(this, R.string.cancel), new Runnable() {
                        @Override public void run() {
                            ref[0].dismiss();                       // 还没挂闹钟 ⇒ 不用撤
                        }
                    }),
                    new CardStack.Btn(Trans.get(this, R.string.plugin_restart_now), new Runnable() {
                        @Override public void run() {
                            ref[0].dismiss();
                            armAndDie();
                        }
                    }));
            return;
        }
        if (!armAndDie()) {
            Toast.makeText(this, Trans.get(this, R.string.plugin_restart_failed), Toast.LENGTH_LONG).show();
        }
    }

    /** 挂闹钟 + 立刻自杀（闹钟负责把应用拉回来）；挂不上返回 false，**绝不**先杀再说。 */
    private boolean armAndDie() {
        int r = Restart.scheduleUser(this);
        if (r == Restart.OK || r == Restart.INEXACT) {
            Restart.killSelf();
            return true;
        }
        return false;
    }

    /**
     * 界面上怎么指代"哪一份"：时间戳 → 「10-20 16:57 那份」；内置 → 「内置那份」；其他 → 「已安装那份」。
     * 🔴 绝不把内部版本令牌（内置插件是 sha256 前 8 位）当成人话端给用户（第 182 轮用户指出）。
     */
    /** 启用一版并问要不要重启（v23 抽出来：探针风险告知确认后走同一个出口）。 */
    private void doEnable(String id, String ver) {
        doEnable(null, id, ver);
    }

    /** 有卡片就接着在同一张卡里问"要不要重启"（第 271 轮）。 */
    private void doEnable(final CardStack card, String id, String ver) {
        Plugins.enable(this, id, ver);
        String done = Trans.get(this, R.string.plugin_enabled_fmt,
                Plugins.displayNameOf(this, id), verLabel(id, ver));
        rebuild();
        if (card != null && card.isShowing()) askRestart(card, done);
        else askRestart(done);
    }

    private String verLabel(String id, String ver) {
        if (ver == null || ver.trim().isEmpty()) return "";
        if (Plugins.isTimeVersion(ver)) {
            return Trans.get(this, R.string.plugin_ver_at_fmt, Plugins.versionLabel(ver));
        }
        if (id != null && PluginTrust.BUILTIN.equals(Plugins.trustOf(this, id, ver))) {
            return Trans.get(this, R.string.plugin_ver_builtin);
        }
        return Trans.get(this, R.string.plugin_ver_installed);
    }

    /**
     * 改动已经落盘、但**这一程不生效** ⇒ 直接问一句"现在要不要重启管理器"。
     * 「稍后」= 关掉弹窗，页面上的「待重启」徽标与底部那张「立即重启管理器」卡照样在。
     * ★ 只有真的需要退出重开时才调它（安装 / 启用 / 停用或卸载了**正在跑**的那一份 / 重新启用）。
     */
    /** 卡片里用：同一张卡推一页「现在重启吗」（换窗才闪，所以别在卡片里再 `new AlertDialog`）。 */
    /** 有卡片就**原地换成**这一页（第 271 轮：原来 push 一页，返回能退回"刚做完的动作页"）。 */
    private void askRestart(final CardStack stack, CharSequence message) {
        stack.replace(Trans.get(this, R.string.plugin_restart_ask_title), message,
                new CardStack.Btn(Trans.get(this, R.string.plugin_restart_later), new Runnable() {
                    @Override public void run() { stack.dismiss(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.plugin_restart_now), new Runnable() {
                    @Override public void run() {
                        stack.dismiss();
                        restartNow();
                    }
                }));
    }

    /** 没有卡片可用时：自己开一张（也不再是"关一个窗再开一个窗"）。 */
    private void askRestart(CharSequence message) {
        if (isFinishing()) return;
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(null, this,
                Trans.get(this, R.string.plugin_restart_ask_title), CardStack.text(this, message),
                new CardStack.Btn(Trans.get(this, R.string.plugin_restart_later), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.plugin_restart_now), new Runnable() {
                    @Override public void run() {
                        ref[0].dismiss();
                        restartNow();
                    }
                }));
    }

    // ---- 小工具 ----------------------------------------------------------------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * 「它要碰什么」那一行（第 148 轮 C2）：没有声明就明说"没有声明"，**不留白**。
     * 名字原样列出（认不出的也列 —— 用户有权知道插件要什么，我们认不认得是另一回事）。
     */
    private String accessText(File apk) {
        if (apk == null) return Trans.get(this, R.string.plugin_access_none);
        java.util.List<String> a = PluginMeta.accessOf(apk);
        if (a.isEmpty()) return Trans.get(this, R.string.plugin_access_none);
        return android.text.TextUtils.join(", ", a);
    }

    /**
     * 插件声明的"能力条目"（第 139 轮）：列在插件卡里、点条目 = 进那一条自己的页。
     * ⚠️ 读的是插件 APK 里的声明（几 KB），**不加载插件代码** ⇒ 列表页不因为"某台插件坏了"而打不开；
     * 真正的加载推迟到用户点它的那一刻（那条路本来就有 try/catch 兜底）。
     */
    private void bindExtensions(View card, final String id, final String ver) {
        List<Extensions.Item> all = Extensions.of(Plugins.apkOf(this, id, ver));
        List<Extensions.Item> items = Extensions.forSlot(all, Extensions.SLOT_PLUGINS);
        LinearLayout box = (LinearLayout) card.findViewById(R.id.plugin_exts);
        // ★ 声明了入口、但这一版一个都用不上 ⇒ 在卡里说一句。原来这里什么都不显示，用户会以为
        //   "插件没生效"（插件本身其实照常注入、照常工作 —— 见 Extensions.allUnknown 的注释）。
        if (items.isEmpty()) {
            if (!Extensions.allUnknown(all, Extensions.SLOT_PLUGINS)) return;
            box.setVisibility(View.VISIBLE);
            box.addView(cardDivider());
            TextView note = new TextView(this);
            note.setText(Trans.get(this, R.string.plugin_ext_unsupported));
            note.setTextSize(12f);
            note.setTextColor(getResources().getColor(R.color.fg_muted));
            note.setLineSpacing(dp(2), 1f);
            note.setPadding(dp(50), dp(8), dp(14), dp(10));
            box.addView(note);
            return;
        }
        box.setVisibility(View.VISIBLE);
        for (final Extensions.Item it : items) {
            box.addView(cardDivider());
            View row = getLayoutInflater().inflate(R.layout.item_plugin_ext, box, false);
            ((TextView) row.findViewById(R.id.plugin_ext_title)).setText(it.label());
            TextView sub = (TextView) row.findViewById(R.id.plugin_ext_sub);
            if (it.subtitle == null || it.subtitle.isEmpty()) {
                sub.setVisibility(View.GONE);
            } else {
                sub.setText(it.subtitle);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { open(id, ver, it.entry); }
            });
            box.addView(row);
        }
    }
}
