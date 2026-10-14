package io.mdt.launcher;

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

    private LinearLayout mBody;
    private ScrollView mScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plugins);
        // 🔴 edge-to-edge（targetSdk ≥ 35）：不补 insets 的话整页从 y=0 画、被状态栏+顶栏盖住
        Util.applySystemInsets(findViewById(R.id.root));
        mScroll = (ScrollView) findViewById(R.id.plugins_scroll);
        mBody = (LinearLayout) findViewById(R.id.plugins_body);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    // ---- 页面 -----------------------------------------------------------------

    private void rebuild() {
        // ⚠️ 「打开」那一步会把 mScroll 的内容换成插件页 ⇒ 回来时必须把列表容器**重新挂上**，
        //   否则 mBody 已经不在视图树里：页面看着"什么都没发生"（清空的是那个孤儿容器）
        if (mScroll.getChildCount() != 1 || mScroll.getChildAt(0) != mBody) {
            mScroll.removeAllViews();
            mScroll.addView(mBody);
        }
        mBody.removeAllViews();
        // ⚠️ 页内**不再重复**顶栏那句「插件」（同一屏同一件事只说一次）
        addText(Trans.get(this, R.string.plugin_page_sub), 13f);

        skipBanner();
        addAction(Trans.get(this, R.string.plugin_import), new Runnable() {
            @Override public void run() { pickPlugin(); }
        });

        List<String> ids = Plugins.installedIds(this);
        if (ids.isEmpty()) {
            addText(Trans.get(this, R.string.plugin_none), 15f);
        } else {
            String act = Plugins.active(this), pend = Plugins.pending(this), known = Plugins.known(this);
            for (final String id : ids) {
                String[] st = Plugins.rowState(act, pend, id);
                // ★ 界面上说的名字 = 自述里的名字（缺自述才回落 id）—— 与横幅、弹窗同一处取（第 136 轮）
                final String name = Plugins.displayNameOf(this, id);
                String label;
                if ("pending".equals(st[0])) {
                    label = Trans.get(this, R.string.plugin_state_pending_fmt, name, st[1]);
                } else if ("active".equals(st[0])) {
                    label = Trans.get(this, R.string.plugin_state_active_fmt, name, st[1]);
                } else {
                    String k = Plugins.stateGet(known, id);
                    label = k == null ? Trans.get(this, R.string.plugin_state_off_never, name)
                                      : Trans.get(this, R.string.plugin_state_off_fmt, name, k);
                }
                // ★ 有隔离件时行里要点明（否则用户看着"已停用"、不知道还能放回来）
                String[] qv = Plugins.quarantinedOf(this, id);
                if (qv.length > 0 && "off".equals(st[0])) {
                    label = Trans.get(this, R.string.plugin_row_quarantined_fmt, name, qv[qv.length - 1]);
                }
                // 第二行小字：来源 + 当前处置等级（弹窗里放不下它们 —— 见 rowDialog 的注释）
                final String[] vs = Plugins.versionsOf(this, id);
                String probeVer = st[1].isEmpty() ? (vs.length > 0 ? vs[vs.length - 1] : null) : st[1];
                final String tr = probeVer == null ? PluginTrust.THIRD : Plugins.trustOf(this, id, probeVer);
                final String sub = Trans.get(this, R.string.plugin_trust_short_fmt,
                        Trans.get(this, trustShortRes(tr)),
                        Trans.get(this, levelShortRes(Plugins.levelOf(Plugins.levels(this), tr, id))));
                final String rowLabel = label;
                addAction(label, sub, new Runnable() {
                    @Override public void run() { rowDialog(id); }
                });
                // ★ 这台插件声明的**能力条目**（第 139 轮）：列在它那一节下面，点条目 = 进那一条自己的页
                if (probeVer != null) addExtensions(id, probeVer);
            }
        }

        addText(Trans.get(this, R.string.plugin_restart_hint), 13f);
        addAction(Trans.get(this, R.string.plugin_restart), new Runnable() {
            @Override public void run() { restartNow(); }
        });
    }

    /** 上次带插件启动没成功 ⇒ 在这个位置说清楚"谁被回滚 / 谁被停用"，并给一条恢复的路。 */
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
        addText(Trans.get(this, R.string.plugin_skip_title), 15f);
        for (String[] s : live) {
            boolean q = s.length > 5 && "yes".equals(s[5]);
            // ★ 横幅里说的名字与列表行同一个来源（自述名字优先）—— 别让同一件事有两个名字
            String name = Plugins.displayNameOf(this, s[0]);
            if ("retry".equals(s[3])) {
                addText(Trans.get(this, R.string.plugin_skip_retry_fmt, name, s[1]), 14f);
            } else if ("rollback".equals(s[3])) {
                addText(Trans.get(this, q ? R.string.plugin_skip_rollback_q_fmt
                                          : R.string.plugin_skip_rollback_fmt, name, s[1], s[2]), 14f);
            } else {
                addText(Trans.get(this, q ? R.string.plugin_skip_quarantine_fmt
                                          : R.string.plugin_skip_disable_fmt, name, s[1]), 14f);
            }
        }
        if (!restorable) return;   // 那一版的文件不在了 ⇒ 这个按钮按下去什么都不会发生，那就别给
        addAction(Trans.get(this, R.string.plugin_skip_restore), new Runnable() {
            @Override public void run() { restoreSkipped(live); }
        });
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
        if (n > 0) Toast.makeText(this, Trans.get(this, R.string.plugin_restart_hint),
                Toast.LENGTH_LONG).show();
        rebuild();
    }

    // ---- 一行的动作 -------------------------------------------------------------

    private void rowDialog(final String id) {
        final String act = Plugins.stateGet(Plugins.active(this), id);
        final String pend = Plugins.stateGet(Plugins.pending(this), id);
        final String[] vers = Plugins.versionsOf(this, id);
        final String newest = vers.length == 0 ? null : vers[vers.length - 1];
        final String[] quar = Plugins.quarantinedOf(this, id);
        final String qv = quar.length == 0 ? null : quar[quar.length - 1];

        // ★ 来源 + 当前处置等级写进弹窗的正文（列表行有宽度预算，塞不下这些字）
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
            items.add(Trans.get(this, R.string.plugin_action_restore_fmt, qv));
            actions.add("restore");
        }
        items.add(Trans.get(this, R.string.plugin_action_level_fmt,
                        Trans.get(this, levelShortRes(lv)), Trans.get(this, levelShortRes(nextLv))));
        actions.add("level");
        items.add(Trans.get(this, R.string.plugin_action_info));
        actions.add("info");

        new AlertDialog.Builder(this)
                .setTitle(Plugins.displayNameOf(this, id))
                // ⚠️ 别在这里加 `.setMessage(...)`：框架的 AlertController 在"有 message"时会**把 items 吞掉**
                //    （真机实测：同一段代码去掉 message 就正常显示 6 项，加上就只剩一个「取消」）。
                //    ⇒ 来源 / 等级的信息放在**列表行下面那行小字**里，弹窗只负责动作。
                .setItems(items.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String a = actions.get(which);
                        if ("open".equals(a)) {
                            open(id, act);
                        } else if ("enable".equals(a)) {
                            Plugins.enable(PluginsActivity.this, id, newest);
                            toastRestart(Trans.get(PluginsActivity.this, R.string.plugin_installed_fmt,
                                    id, newest));
                            rebuild();
                        } else if ("disable".equals(a)) {
                            Plugins.disable(PluginsActivity.this, id);
                            rebuild();
                        } else if ("uninstall".equals(a)) {
                            // ★ 搬不动时 uninstall 返回 false、插件**还在**（数据没丢）⇒ 界面必须说一句，
                    //   否则用户以为已经删掉了（第 148 轮 C1-b）
                    boolean uninstalled = Plugins.uninstall(PluginsActivity.this, id);
                    if (!uninstalled) {
                        Toast.makeText(PluginsActivity.this,
                                Trans.get(PluginsActivity.this, R.string.plugin_uninstall_failed),
                                Toast.LENGTH_LONG).show();
                    }
                            Toast.makeText(PluginsActivity.this,
                                    Trans.get(PluginsActivity.this, R.string.plugin_uninstalled_fmt, id),
                                    Toast.LENGTH_SHORT).show();
                            rebuild();
                        } else if ("restore".equals(a)) {
                            boolean ok = Plugins.restoreQuarantined(PluginsActivity.this, id, qv);
                            Toast.makeText(PluginsActivity.this,
                                    Trans.get(PluginsActivity.this, ok ? R.string.plugin_restored_fmt
                                                                       : R.string.plugin_restore_failed, qv),
                                    Toast.LENGTH_LONG).show();
                            rebuild();
                        } else if ("level".equals(a)) {
                            Plugins.setLevel(PluginsActivity.this, id, nextLv);
                            Toast.makeText(PluginsActivity.this,
                                    Trans.get(PluginsActivity.this, R.string.plugin_level_set_fmt,
                                            id, Trans.get(PluginsActivity.this, levelRes(nextLv))), Toast.LENGTH_LONG).show();
                            rebuild();
                        } else if ("info".equals(a)) {
                            infoDialog(id, newest, trust, lv);
                        }
                    }
                })
                .setNegativeButton(Trans.get(this, R.string.cancel), null)
                .show();
    }

    /**
     * 「插件信息」：自述里那些**说明性**的字段（名称 / 自述版本 / 作者 / 说明）+ 入口类 + 来源 / 处置 / 文件版本。
     *
     * <p>★ 为什么单独开一个弹窗：框架那个"`setMessage` 与 `setItems` 同用会把 items 吞掉"的坑
     * （第 133 轮真机踩的）⇒ **只有 message、没有 items 的弹窗**是安全的，所以信息单独一屏。
     * ★ 空字段整条不印（{@link Plugins#dropEmptyPairs}）—— 不许出现「作者：null」或空行。
     */
    private void infoDialog(String id, String fileVer, String trust, String lv) {
        File apk = fileVer == null ? null : Plugins.apkOf(this, id, fileVer);
        PluginMeta.Info meta = apk == null ? null : PluginMeta.read(this, apk);
        String[] labels = {
                Trans.get(this, R.string.plugin_info_name),      // 内部 id（稳定键）
                Trans.get(this, R.string.plugin_info_selfname),  // 自述名称
                Trans.get(this, R.string.plugin_info_selfver),
                Trans.get(this, R.string.plugin_info_author),
                Trans.get(this, R.string.plugin_info_desc),
                Trans.get(this, R.string.plugin_info_entry),
                Trans.get(this, R.string.plugin_info_source),
                Trans.get(this, R.string.plugin_info_level),
                Trans.get(this, R.string.plugin_info_filever),
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
                fileVer,
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
        new AlertDialog.Builder(this)
                .setTitle(Plugins.displayNameOf(this, id))
                .setMessage(sb.toString())
                .setPositiveButton(Trans.get(this, R.string.cancel), null)
                .show();
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
        open(id, ver, null);
    }

    /**
     * 同上，但可以指定入口类（第 139 轮：点插件声明的**能力条目**时，要开的是那一条自己的入口类）。
     *
     * @param entryOverride null = 用插件自述里的默认入口
     */
    private void open(final String id, final String ver, final String entryOverride) {
        File apk = Plugins.apkOf(this, id, ver);
        View page;
        try {
            // ★ 入口类按**这个插件自己的**自述取（第 135 轮）——多插件下"点谁开谁"全靠它
            //   （共用同一个入口 FQN 时，宿主 CL 只会认第一个注入的那个插件）
            String entry = entryOverride != null ? entryOverride
                    : PluginMeta.entryOf(this, apk, PluginHost.DEFAULT_ENTRY);
            PluginHost.Loaded l = Plugins.isInjected(this, id, ver)
                    ? PluginHost.openInjected(this, apk, entry)
                    : PluginHost.load(this, apk, entry);
            Object v = l.entry.getMethod("createView", android.content.Context.class).invoke(null, l.ctx);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            addText(box, Trans.get(this, R.string.plugin_loaded_fmt, apk.getName(), l.api), 13f);
            if (v instanceof View) box.addView((View) v);
            page = box;
        } catch (PluginHost.PluginException e) {
            // ★ 接口版本那句按"太新 / 太老"分岔（第 139 轮）：一句"不匹配"让用户不知道该升哪一边
            int rr = PluginHost.reasonRes(e.reason);
            page = fallback(rr == R.string.plugin_err_api_fmt
                    ? Trans.get(this, PluginHost.apiReasonRes(e.num1), e.num1,
                            PluginHost.API_MIN, PluginHost.API)
                    : Trans.get(this, rr));
        } catch (Throwable t) {
            page = fallback(Trans.get(this, R.string.plugin_err_other));
        }
        // 插件自己的页面 + 一行「返回」（返回 = 重建本页）
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        addAction(root, Trans.get(this, R.string.plugin_back), new Runnable() {
            @Override public void run() { rebuild(); }
        });
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mScroll.removeAllViews();
        mScroll.addView(root);
    }

    /** 插件没加载成时的兜底视图：**只说一句人话**（原因来自 {@link PluginHost#reasonRes}）。 */
    private View fallback(String why) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        addText(box, Trans.get(this, R.string.plugin_fallback_title), 15f);
        addText(box, why, 14f);
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
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("cannot open " + uri);
                    try {
                        Util.copyStream(in, tmp);
                    } finally {
                        in.close();
                    }
                    String id = baseName(tmp, uri);
                    // ★ 版本号用"装进来的时刻"：用户手上没有版本号这个概念，
                    //   而落点要唯一名（置只读的副本不能再被覆盖，见 ref/29 第 3 条）
                    String ver = String.valueOf(System.currentTimeMillis() / 1000L);
                    String r = Plugins.install(PluginsActivity.this, tmp, id, ver, false);
                    msg = r.startsWith("FAILED")
                            ? Trans.get(PluginsActivity.this, R.string.plugin_install_failed_fmt, r)
                            : Trans.get(PluginsActivity.this, R.string.plugin_installed_fmt, id, ver);
                } catch (final Throwable t) {
                    // 按纪律：第一层只说"这件事没成"，原始异常进日志（§五"系统异常不许当弹窗正文"）
                    android.util.Log.w(LauncherApp.TAG, "plugin import failed", t);
                    msg = Trans.get(PluginsActivity.this, R.string.plugin_install_failed_fmt,
                            t.getClass().getSimpleName());
                } finally {
                    tmp.delete();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toastRestart(m);
                        rebuild();
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

    /** 「立即重启」：挂闹钟 + 自杀；**挂不上就如实说**（不许假装重启成功）。 */
    private void restartNow() {
        int r = Restart.schedule(this);
        if (r == Restart.OK) {
            Restart.killSelf();
            return;
        }
        if (r == Restart.THROTTLED) return;   // 刚刚已经重开过（这是恢复重放）⇒ 静默
        Toast.makeText(this, Trans.get(this, R.string.plugin_restart_failed), Toast.LENGTH_LONG).show();
    }

    private void toastRestart(String msg) {
        Toast.makeText(this, msg + "\n" + Trans.get(this, R.string.plugin_restart_hint),
                Toast.LENGTH_LONG).show();
    }

    // ---- 小工具 ----------------------------------------------------------------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void addText(String s, float size) {
        addText(mBody, s, size);
    }

    private void addText(LinearLayout into, String s, float size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setPadding(0, dp(4), 0, dp(4));
        into.addView(t);
    }

    /**
     * 插件声明的"能力条目"那一行（第 139 轮）。
     * ⚠️ 读的是插件 APK 里的声明（几 KB），**不加载插件代码** ⇒ 列表页不因为"某台插件坏了"而打不开；
     * 真正的加载推迟到用户点它的那一刻（那条路本来就有 try/catch 兜底）。
     */
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

    private void addExtensions(final String id, final String ver) {
        List<Extensions.Item> items = Extensions.forSlot(
                Extensions.of(Plugins.apkOf(this, id, ver)), Extensions.SLOT_PLUGINS);
        for (final Extensions.Item it : items) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(28), dp(6), dp(8), dp(6));
            TextView title = new TextView(this);
            title.setText(it.label());
            title.setTextSize(15f);
            box.addView(title);
            if (it.subtitle != null && !it.subtitle.isEmpty()) {
                TextView sub = new TextView(this);
                sub.setText(it.subtitle);
                sub.setTextSize(12f);
                box.addView(sub);
            }
            box.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { open(id, ver, it.entry); }
            });
            mBody.addView(box);
        }
    }

    private void addAction(String label, final Runnable r) {
        addAction(mBody, label, r);
    }

    /**
     * 两行的一行：主行是可点的动作/条目，**副行给"来源 + 处置等级"**这种补充信息。
     * ★ 为什么副行不塞进弹窗：框架的 AlertController 在同时有 `message` 时会把 `items` 吞掉
     *   （真机实测），所以弹窗只放动作，信息放在列表里。
     */
    private void addAction(String label, String sub, final Runnable r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(10), dp(8), dp(10));
        box.setClickable(true);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16f);
        box.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextSize(12f);
            s.setTextColor(getResources().getColor(R.color.fg_muted));
            box.addView(s);
        }
        mBody.addView(box);
    }

    /** 一行"可点的动作"：整行可点（不靠小按钮 ⇒ 也不会出现"文字被挤没"那类）。 */
    private void addAction(LinearLayout into, String label, final Runnable r) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16f);
        t.setPadding(dp(8), dp(12), dp(8), dp(12));
        t.setGravity(Gravity.START);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        into.addView(t);
    }
}
