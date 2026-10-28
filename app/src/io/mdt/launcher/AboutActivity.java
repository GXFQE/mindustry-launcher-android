package io.mdt.launcher;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 「关于」二级页（第 175 轮）。
 *
 * <p>设置页底部原来直接摊着版本 / 包名 / 数据路径，用户 2026-10-19：
 * 「感觉把启动器信息直接放在设置下面不太合适，单独开个二级页面，还可以加些比如开源地址之类的」。
 * ⇒ 搬成独立页，并补上 GitHub 仓库 / Issues / GPL-3.0 三个外链。
 *
 * <p>页面只读：不写配置、不碰槽、不碰游戏数据。外链走系统浏览器；没有处理器就 Toast，
 * 不让 ACTION_VIEW 的 ActivityNotFoundException 冒到用户面前。
 *
 * <p>★ 第 296 轮：多了一个**彩蛋**——连点版本摘要 7 次解锁「开发者选项」卡片（Android 同款梗）。
 * 判定与内容都在 {@link DevMode}（纯函数，自检钉着），本页只负责：计数器 + 显隐 + 一句 Toast。
 * 计数**不落盘**，离开页面就归零；只有"已解锁"进 {@code config.json}。
 */
public final class AboutActivity extends BaseActivity {

    private static final String TAG = "MDTLauncher";

    /** 本次进页面点了多少下（离开即归零，见类注释）。 */
    private int mTaps;
    /** 累计点击数（跨会话，落 `config.json` 的 `tap_count`）—— 卡片第 8 行显示它。 */
    private int mTotalTaps;
    /** 开发者模式是否已解锁（= 卡片该不该显示）。 */
    private boolean mDev;
    private View mDevBox;
    /** 卡片前 7 行（进程事实）—— 只算一次；每次点击只重算第 8 行（见 {@link DevMode#tapLine}）。 */
    private String mInfoBase;
    /** 复用的那条 Toast：连点时要**原地换字**，不是排队（见 {@link #toast(String)}）。 */
    private Toast mToast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        Util.applySystemInsets(findViewById(R.id.root));
        setTitle(Trans.get(this, R.string.about_card_title));

        android.view.View root = findViewById(R.id.root);
        Trans.bind(root, R.id.about_app_name, R.string.app_name);
        Trans.bind(root, R.id.about_section_links, R.string.about_section_links);
        Trans.bind(root, R.id.about_section_data, R.string.about_section_data);
        Trans.bind(root, R.id.about_footer, R.string.about_footer);

        String verName = "?";
        int verCode = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            verName = pi.versionName;
            verCode = pi.versionCode;
        } catch (Exception e) {
            android.util.Log.w(TAG, "read PackageInfo failed: " + e);
        }
        ((TextView) findViewById(R.id.about_summary)).setText(Trans.get(this,
                R.string.about_summary_fmt, verName, verCode, getPackageName()));

        File dataRoot = Data.dataRoot(this);
        ((TextView) findViewById(R.id.about_data)).setText(Trans.get(this, R.string.about_data_fmt,
                dataRoot == null ? "?" : dataRoot.getAbsolutePath(),
                Data.hubDir(this).getAbsolutePath()));

        Util.bindAction(findViewById(R.id.root), R.id.row_about_repo, R.drawable.ic_link,
                R.string.about_repo_title, R.string.about_repo_sub, new Runnable() {
                    @Override public void run() { openUrl(R.string.about_repo_url); }
                });
        Util.bindAction(findViewById(R.id.root), R.id.row_about_issues, R.drawable.ic_link,
                R.string.about_issues_title, R.string.about_issues_sub, new Runnable() {
                    @Override public void run() { openUrl(R.string.about_issues_url); }
                });
        Util.bindAction(findViewById(R.id.root), R.id.row_about_license, R.drawable.ic_link,
                R.string.about_license_title, R.string.about_license_sub, new Runnable() {
                    @Override public void run() { openUrl(R.string.about_license_url); }
                });

        // ── 第 296 轮：开发者模式的彩蛋入口 ──────────────────────────────
        //   挂点选「版本摘要」这一行：版本列表里那几行的长按已经用来打开版本页了，别抢。
        mDev = Config.get().devMode();
        mTotalTaps = Config.get().tapCount();
        mDevBox = findViewById(R.id.devmode_box);
        Trans.bind(root, R.id.devmode_section_title, R.string.devmode_title);
        Util.bindAction(root, R.id.row_devmode_relock, R.drawable.ic_restart,
                R.string.devmode_relock_title, R.string.devmode_relock_sub, new Runnable() {
                    @Override public void run() { relock(); }
                });
        findViewById(R.id.about_summary).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onSummaryTap(v); }
        });
        findViewById(R.id.about_summary).setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                // 第二个彩蛋：**解锁之后**长按版本摘要 = 彩蛋清单（未解锁时不给反应，照 Android）。
                if (mDev) showEggs();
                return true;
            }
        });
        applyDevCard();
    }

    /**
     * 连点版本摘要：计数 → {@link DevMode#tapKind(int, boolean)} 给反应。
     *
     * <p>★ 每次点（含没反应的那些）都计数：20 / 50 / 100 / 200 / 500 五档段子靠它。
     */
    private void onSummaryTap(View v) {
        mTaps++;
        // ★ 累计数先记（跨会话），再判这次该给什么反应：卡片第 8 行与段子里的数字都读它。
        mTotalTaps = Config.get().bumpTapCount(1);
        // 已解锁 ⇒ 第 8 行跟着涨（只是拼字符串 + 一次 setText，连点几十下也扛得住）。
        if (mDev) applyDevCard();
        switch (DevMode.tapKind(mTaps, mDev)) {
            case DevMode.TAP_REMAINING:
                toast(Trans.get(this, R.string.devmode_taps_left_fmt,
                        Integer.valueOf(DevMode.tapsLeft(mTaps))));
                break;
            case DevMode.TAP_UNLOCK:
                mDev = true;
                Config.get().setDevMode(true);
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                applyDevCard();
                toast(Trans.get(this, R.string.devmode_unlocked));
                break;
            case DevMode.TAP_ALREADY:
                toast(Trans.get(this, R.string.devmode_already));
                break;
            case DevMode.TAP_SNARK:
                toast(Trans.get(this, R.string.devmode_snark));
                break;
            case DevMode.TAP_TRIVIA:
                toast(Trans.get(this, R.string.devmode_trivia_fmt, DevMode.installedOn(this)));
                break;
            case DevMode.TAP_CURIOUS:
                toast(Trans.get(this, R.string.devmode_curious));
                break;
            case DevMode.TAP_SUSPICIOUS:
                toast(Trans.get(this, R.string.devmode_suspicious));
                break;
            case DevMode.TAP_GRATEFUL:
                toast(Trans.get(this, R.string.devmode_grateful));
                break;
            default:
                break;      // 空档（第 8~19 次、以及已解锁后的前 6 次）—— 照 Android，安静
        }
    }

    /**
     * 按解锁状态显隐卡片；**首次显示时才去算那 7 行**（读包信息 + 算指纹，没必要每次点击都付）。
     * 第 8 行（累计点击数）每次调用都重排 —— 它只是拼字符串。
     */
    private void applyDevCard() {
        mDevBox.setVisibility(mDev ? View.VISIBLE : View.GONE);
        if (!mDev) return;
        if (mInfoBase == null) mInfoBase = DevMode.buildInfo(this);
        TextView info = (TextView) findViewById(R.id.devmode_info);
        info.setText(DevMode.cardText(this, mInfoBase, mTotalTaps));
    }

    /**
     * 第二个彩蛋：**解锁之后**长按版本摘要 ⇒ 一张「彩蛋清单」。
     *
     * <p>★ 它同时是这套彩蛋的**自文档**：以后加/删彩蛋往这里补一行（四个数字全部读
     * {@link DevMode} 的常量、关键词读 {@code ListQuery.EGG_QUERY}），别让清单与代码各说各话。
     */
    private void showEggs() {
        String body = Trans.get(this, R.string.egg_line_unlock_fmt,
                        Integer.valueOf(DevMode.UNLOCK_AT))
                + "\n\n" + Trans.get(this, R.string.egg_line_milestones_fmt,
                        Integer.valueOf(DevMode.SNARK_AT), Integer.valueOf(DevMode.TRIVIA_AT),
                        Integer.valueOf(DevMode.CURIOUS_AT),
                        Integer.valueOf(DevMode.SUSPICIOUS_AT),
                        Integer.valueOf(DevMode.GRATEFUL_AT))
                + "\n\n" + Trans.get(this, R.string.egg_line_longpress)
                + "\n\n" + Trans.get(this, R.string.egg_line_search_fmt, ListQuery.EGG_QUERY);
        alert(Trans.get(this, R.string.egg_list_title), body);
    }

    /** 「重新变回普通人」：关掉开关、清计数、收起卡片（下次还要再连点 7 次）。 */
    private void relock() {
        mDev = false;
        mTaps = 0;
        Config.get().setDevMode(false);
        applyDevCard();
        toast(Trans.get(this, R.string.devmode_relock_done));
    }

    /** 页面里弹一条只读提示（与地图 / 存档 / 模组页**同名同形**：先让位给同窗换页，再挡死页面）。 */
    private void alert(String title, String msg) {
        if (CardStack.leaf(title, msg)) return;
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void toast(String s) {
        // ★ 连点时会连着弹 6 次「还差 N 次」：**必须先把上一条撤掉**再弹，否则
        //   Android 是把 Toast 排队 —— 真机实测点完 7 下，"你现在是开发者了"要等前 6 条
        //   各 2 秒放完才出现（约 12 秒后才反馈，看着像没生效）。同一条复用 = 原地换字。
        if (mToast != null) mToast.cancel();
        mToast = Toast.makeText(this, s, Toast.LENGTH_SHORT);
        mToast.show();
    }

    private void openUrl(int resId) {
        String url = Trans.get(this, resId);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "open link failed: " + url, t);
            Toast.makeText(this, R.string.about_link_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
