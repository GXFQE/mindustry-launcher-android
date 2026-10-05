package io.mdt.launcher;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

/**
 * 蓝图列表页（F22 第一步，2026-10-05 第 109 轮）：从槽二级页面的「蓝图」进来。
 *
 * <h3>这一页解决什么</h3>
 * 蓝图是**槽自己的数据**（`<槽>/schematics/`，游戏 `schematicDirectory`），加上**已启用模组**
 * 自带的那些。在启动器里能列出来之后，才有"开游戏之前先看一眼这蓝图缺不缺件"这件事
 * —— 而缺件在游戏里是**静默**的（认不出的方块直接当空气，贴出来才发现少东西）。
 *
 * <h3>形状</h3>
 * 表头（本槽 N · 模组自带 M）+ 卡片列表（名字 / 尺寸·瓦片·分类，缺件时多一行警告）+ 空态。
 * 点一条进 {@link BlueprintDetailActivity}（**独立页面**：弹窗属于发起它的页面，关掉会落到上一级）。
 *
 * 🔴 两条工程硬规矩：
 *   · **必须继承 {@link BaseActivity}** —— 深浅色的唯一生效点（写成 `extends Activity` 时
 *     用户设的主题对本页完全无效，且不崩不报错，自检 ㊱ 会拦）；
 *   · 根节点带 `@+id/root` + `Util.applySystemInsets(root)` —— 否则整页被状态栏/顶栏盖住（自检 ㉛）。
 *   · 后台任务收尾一律先 `Util.dead(this)`（转屏时 `isFinishing()` 是 false，只判它挡不住）。
 */
public class BlueprintsActivity extends BaseActivity {
    /** 目标槽（本页一切以它为准，**绝不回落当前槽**） */
    public static final String EXTRA_SLOT = "slot";

    private String mSlot;
    private List<Blueprints.Item> mItems;
    private ListView mList;
    private MsavListAdapter mAdapter;
    private TextView mHead, mEmpty;
    /** 已经扫过一轮（第一次 `onResume` 紧跟 `onCreate`，靠它避免开局扫两遍） */
    private boolean mScanned;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mSlot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        if (mSlot == null || mSlot.trim().isEmpty()) {
            finish();                       // 没槽名就退出，**不猜**
            return;
        }
        mSlot = mSlot.trim();

        View root = getLayoutInflater().inflate(R.layout.activity_blueprints, null);
        Util.applySystemInsets(root);
        setContentView(root);
        setTitle(R.string.bp_title);

        mHead = (TextView) root.findViewById(R.id.bp_head);
        mHead.setText(R.string.bp_reading);
        mEmpty = (TextView) root.findViewById(R.id.bp_empty);

        mList = (ListView) root.findViewById(R.id.bp_list);
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (mItems != null && pos >= 0 && pos < mItems.size()) showDetail(mItems.get(pos));
            }
        });
        scan();
    }

    @Override protected void onResume() {
        super.onResume();
        // 从详情页回来、或用户在游戏里存了新蓝图再切回来 ⇒ 重扫一次
        // （几十份小文件，很便宜；`mScanned` 只为挡掉"开局紧跟 onCreate 的那一次"）
        if (mScanned) scan();
    }

    /** 重扫并刷新（**只重扫列表，不重建页面** —— 与地图页同一个理由：重建会闪） */
    private void scan() {
        if (Util.dead(this) || mList == null) return;
        mHead.setText(R.string.bp_reading);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Blueprints.Item> items;
                MapStats.Table tab;
                try {
                    items = Blueprints.scan(BlueprintsActivity.this, mSlot);
                    // 缺件判据 = **本槽已启用模组**的那张内容表（与地图统计同源，含缓存）
                    tab = MapStatsMods.contentFor(BlueprintsActivity.this, mSlot).table;
                } catch (Throwable ex) {
                    android.util.Log.w("MDTLauncher", "blueprint scan failed", ex);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(BlueprintsActivity.this)) return;
                            mHead.setText(R.string.bp_stats_failed);
                        }
                    });
                    return;
                }
                // 每份的派生量（方块种类 / 缺件）在后台算好，界面只渲染 —— 列表行要显示缺件警告
                for (Blueprints.Item it : items) {
                    Blueprints.summarize(it, Blueprints.rows(it.msch, tab, null));
                }
                final String[] titles = new String[items.size()];
                final String[] subs = new String[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    Blueprints.Item it = items.get(i);
                    titles[i] = it.displayName();
                    String src = it.sourceLabel(BlueprintsActivity.this);
                    subs[i] = src + " · " + it.line(BlueprintsActivity.this);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(BlueprintsActivity.this)) return;
                        mItems = items;
                        mHead.setText(getString(R.string.bp_head_fmt,
                                Blueprints.count(items, Blueprints.FROM_SLOT),
                                Blueprints.count(items, Blueprints.FROM_MOD)));
                        mAdapter = new MsavListAdapter(BlueprintsActivity.this, titles, subs);
                        mList.setAdapter(mAdapter);
                        if (mEmpty != null) {
                            mEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                            if (items.isEmpty()) mEmpty.setText(R.string.bp_empty);
                        }
                        mScanned = true;
                    }
                });
            }
        }, "bp-scan").start();
    }

    private void showDetail(Blueprints.Item it) {
        Intent i = new Intent(this, BlueprintDetailActivity.class);
        i.putExtra(BlueprintDetailActivity.EXTRA_SLOT, mSlot);
        Blueprints.putExtra(i, it);
        startActivity(i);
    }
}
