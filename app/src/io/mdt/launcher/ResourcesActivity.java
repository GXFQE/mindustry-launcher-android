package io.mdt.launcher;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 游戏资源页（第 171 轮）：长按版本 → 详情里的「资源」。
 *
 * <p>范围刻意收在**类型级**：页面只列“贴图 / 音效 / 音乐 / 地图 / …”几类，
 * 每类一个勾选框；勾哪几类，导出的 zip 里就只有哪几类。**不做文件级勾选、
 * 不做预览、不拆精灵图** —— 用户要贴图时不必被迫带走 27 MB 音乐。
 *
 * <p>数据来源与判据：
 * <ul>
 *   <li>{@link GameAssets} 只读 APK 的 {@code assets/**}（zip 中央目录）；</li>
 *   <li>{@link Exporter#zipEntries} 按条目名原样写 zip（保留 {@code assets/...} 路径）；</li>
 *   <li>不启动游戏、不碰 {@code addAssetPath}，与地图页读游戏自带图是同一条载体。</li>
 * </ul>
 *
 * <p>★ 默认**一类都不勾**：一进来就全选会让人误点出 78 MB；导出行的副标题实时报
 * “已选 N 类 · 约 X MB”，0 类时整行不可点。
 *
 * <p>★ 第 172 轮对齐四页风格：顶部灰字摘要 + 操作卡（全选 / 全不选 / 导出）+ 逐条
 * 卡片列表 + 空态 + 底部留白；不再自造固定底栏。用户 2026-10-19：「感觉这个页面和
 * 其他页面风格有点不搭啊」。
 */
public final class ResourcesActivity extends BaseActivity {

    static final String EXTRA_APK = "apk";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_VERSION = "version";

    private static final String TAG = "MDTLauncher";
    private static final int REQ_EXPORT = 5001;
    private static final String STATE_PENDING = "pending";
    /** 第 177 轮（杀后台 P0）：待导出条目清单的磁盘表示 */
    private static final String PEND_EXPORT = "resources.export";

    private File mApk;
    private String mName = "";
    private String mVersion = "";
    private GameAssets.Catalog mCatalog;

    private TextView mHead;
    private TextView mNote;
    private LinearLayout mActions;
    private LinearLayout mGroups;
    private TextView mEmpty;
    private View mExportRow;
    private TextView mExportSub;

    private final List<Row> mRows = new ArrayList<Row>();
    private ArrayList<String> mPendingNames;
    private boolean mExporting;

    private static final class Row {
        final String key;
        final CheckBox check;

        Row(String key, CheckBox check) {
            this.key = key;
            this.check = check;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_resources);
        Util.applySystemInsets(findViewById(R.id.root));
        setTitle(Trans.get(this, R.string.resources_title));

        mHead = (TextView) findViewById(R.id.resources_head);
        mNote = (TextView) findViewById(R.id.resources_note);
        mActions = (LinearLayout) findViewById(R.id.resources_actions);
        mGroups = (LinearLayout) findViewById(R.id.resources_groups);
        mEmpty = (TextView) findViewById(R.id.resources_empty);

        // 三行操作与地图 / 存档 / 蓝图 / 模组页同一套：row_action + 图标 + 标题/副标题。
        Util.bindAction(findViewById(R.id.root), R.id.row_resources_all,
                R.drawable.ic_select_all, R.string.resources_select_all,
                R.string.resources_select_all_sub, new Runnable() {
                    @Override public void run() {
                        selectAll(true);
                    }
                });
        Util.bindAction(findViewById(R.id.root), R.id.row_resources_none,
                R.drawable.ic_select_none, R.string.resources_select_none,
                R.string.resources_select_none_sub, new Runnable() {
                    @Override public void run() {
                        selectAll(false);
                    }
                });
        mExportRow = Util.bindActionValue(findViewById(R.id.root), R.id.row_resources_export,
                R.drawable.ic_zip, R.string.resources_export, new Runnable() {
                    @Override public void run() {
                        exportSelected();
                    }
                });
        mExportSub = (TextView) mExportRow.findViewById(R.id.act_sub);

        String apk = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_APK);
        mName = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_NAME);
        mVersion = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_VERSION);
        if (mName == null || mName.trim().isEmpty()) {
            mName = apk == null ? "" : new File(apk).getName();
        }
        if (mVersion == null) mVersion = "";
        mHead.setText(mName);

        if (savedInstanceState != null) {
            ArrayList<String> pending = savedInstanceState.getStringArrayList(STATE_PENDING);
            if (pending != null && !pending.isEmpty()) {
                mPendingNames = pending;
                mExporting = true;
            }
        }

        if (apk == null || apk.trim().isEmpty()) {
            showEmpty(R.string.resources_unreadable);
            return;
        }
        mApk = new File(apk.trim());
        mNote.setText(Trans.get(this, R.string.resources_scanning));
        mNote.setVisibility(View.VISIBLE);
        mExportRow.setEnabled(false);
        mExportRow.setAlpha(0.4f);
        scan();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (mPendingNames != null) {
            out.putStringArrayList(STATE_PENDING, new ArrayList<String>(mPendingNames));
        }
    }

    private void scan() {
        final File apk = mApk;
        new Thread(new Runnable() {
            @Override public void run() {
                GameAssets.Catalog c = null;
                boolean ok = false;
                try {
                    c = GameAssets.scan(apk);
                    ok = true;
                } catch (Throwable t) {
                    Log.w(TAG, "game assets scan failed: " + apk, t);
                }
                final GameAssets.Catalog fc = c;
                final boolean fok = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (!fok || fc == null) {
                            showEmpty(R.string.resources_unreadable);
                            return;
                        }
                        showCatalog(fc);
                    }
                });
            }
        }, "resources-scan").start();
    }

    private void showCatalog(GameAssets.Catalog c) {
        mCatalog = c;
        mHead.setText(Trans.get(this, R.string.resources_head_fmt,
                mName, c.entries, Util.formatSize(c.bytes)));
        if (c.groups.isEmpty()) {
            showEmpty(R.string.resources_no_assets);
            return;
        }
        mNote.setText(Trans.get(this, R.string.resources_note));
        mNote.setVisibility(View.VISIBLE);
        mActions.setVisibility(View.VISIBLE);
        mGroups.removeAllViews();
        mRows.clear();
        LayoutInflater inf = getLayoutInflater();
        for (final GameAssets.Group g : c.groups) {
            View row = inf.inflate(R.layout.item_resource_group, mGroups, false);
            TextView title = (TextView) row.findViewById(R.id.resources_group_title);
            TextView sub = (TextView) row.findViewById(R.id.resources_group_sub);
            final CheckBox check = (CheckBox) row.findViewById(R.id.resources_group_check);
            View card = row.findViewById(R.id.resources_group_row);
            title.setText(labelOf(g.key));
            sub.setText(Trans.get(this, R.string.resources_group_line_fmt,
                    g.count(), Util.formatSize(g.bytes)));
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    check.setChecked(!check.isChecked());
                }
            });
            check.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                    updateSummary();
                }
            });
            mGroups.addView(row);
            mRows.add(new Row(g.key, check));
        }
        mGroups.setVisibility(View.VISIBLE);
        mEmpty.setVisibility(View.GONE);
        updateSummary();
    }

    private void showEmpty(int resId) {
        mEmpty.setText(Trans.get(this, resId));
        mEmpty.setVisibility(View.VISIBLE);
        mActions.setVisibility(View.GONE);
        mGroups.setVisibility(View.GONE);
        mNote.setVisibility(View.GONE);
        mExporting = false;
        mExportRow.setEnabled(false);
        mExportRow.setAlpha(0.4f);
    }

    private String labelOf(String key) {
        if (GameAssets.GROUP_SPRITES.equals(key)) return Trans.get(this, R.string.resources_group_sprites);
        if (GameAssets.GROUP_SOUNDS.equals(key)) return Trans.get(this, R.string.resources_group_sounds);
        if (GameAssets.GROUP_MUSIC.equals(key)) return Trans.get(this, R.string.resources_group_music);
        if (GameAssets.GROUP_MAPS.equals(key)) return Trans.get(this, R.string.resources_group_maps);
        if (GameAssets.GROUP_BUNDLES.equals(key)) return Trans.get(this, R.string.resources_group_bundles);
        if (GameAssets.GROUP_FONTS.equals(key)) return Trans.get(this, R.string.resources_group_fonts);
        if (GameAssets.GROUP_ICONS.equals(key)) return Trans.get(this, R.string.resources_group_icons);
        if (GameAssets.GROUP_CUBEMAPS.equals(key)) return Trans.get(this, R.string.resources_group_cubemaps);
        if (GameAssets.GROUP_BASEPARTS.equals(key)) return Trans.get(this, R.string.resources_group_baseparts);
        if (GameAssets.GROUP_SHADERS.equals(key)) return Trans.get(this, R.string.resources_group_shaders);
        if (GameAssets.GROUP_SCRIPTS.equals(key)) return Trans.get(this, R.string.resources_group_scripts);
        return Trans.get(this, R.string.resources_group_other);
    }

    private void selectAll(boolean on) {
        for (Row r : mRows) r.check.setChecked(on);
    }

    private void updateSummary() {
        int n = 0;
        long bytes = 0;
        for (Row r : mRows) {
            if (!r.check.isChecked()) continue;
            n++;
            GameAssets.Group g = mCatalog == null ? null : mCatalog.group(r.key);
            if (g != null) bytes += g.bytes;
        }
        mExportSub.setText(n == 0
                ? Trans.get(this, R.string.resources_selected_none)
                : Trans.get(this, R.string.resources_selected_fmt,
                        n, Util.formatSize(bytes)));
        boolean can = n > 0 && !mExporting && mCatalog != null;
        mExportRow.setEnabled(can);
        mExportRow.setAlpha(can ? 1f : 0.4f);
    }

    private void exportSelected() {
        if (mCatalog == null || mExporting) return;
        List<String> keys = new ArrayList<String>();
        for (Row r : mRows) {
            if (r.check.isChecked()) keys.add(r.key);
        }
        if (keys.isEmpty()) {
            Toast.makeText(this, R.string.resources_selected_none, Toast.LENGTH_SHORT).show();
            return;
        }
        List<GameAssets.Entry> entries = mCatalog.selected(keys);
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.resources_selected_none, Toast.LENGTH_SHORT).show();
            return;
        }
        mPendingNames = new ArrayList<String>(entries.size());
        for (GameAssets.Entry e : entries) mPendingNames.add(e.name);
        // ★ 第 177 轮（杀后台 P0）：条目清单落盘 —— SAF 选择器打开时进程被杀，
        //   回来仍知道"要导出哪几条"（否则静默 no-op）。`mApk` 也从 intent 重建。
        JSONObject meta = new JSONObject();
        JSONArray arr = new JSONArray();
        for (String n : mPendingNames) arr.put(n);
        try {
            meta.put("names", arr);
            if (mApk != null) meta.put("apk", mApk.getAbsolutePath());
        } catch (Throwable ignored) {
        }
        Pending.put(this, PEND_EXPORT, meta);
        mExporting = true;
        updateSummary();

        String zip = GameAssets.zipName(mVersion, keys);
        Intent i = Exporter.createDoc(zip, "application/zip");
        startActivityForResult(Intent.createChooser(i,
                Trans.get(this, R.string.chooser_export)), REQ_EXPORT);
    }

    private static List<String> namesFromJson(JSONObject o) {
        if (o == null) return null;
        JSONArray arr = o.optJSONArray("names");
        if (arr == null) return null;
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            String n = arr.optString(i, null);
            if (n != null && !n.isEmpty()) out.add(n);
        }
        return out.isEmpty() ? null : out;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        mExporting = false;
        // ★ 第 177 轮：先取走磁盘待办（成败都取）
        final JSONObject pend = Pending.take(this, PEND_EXPORT);
        List<String> names = mPendingNames;
        mPendingNames = null;
        if (names == null) names = namesFromJson(pend);
        if (mApk == null && pend != null) {
            String p = pend.optString("apk", "");
            if (!p.isEmpty()) mApk = new File(p);
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            updateSummary();
            return;
        }
        if (names == null || names.isEmpty() || mApk == null) {
            Toast.makeText(this, R.string.pending_lost, Toast.LENGTH_LONG).show();
            updateSummary();
            return;
        }
        final Uri uri = data.getData();
        final File apk = mApk;
        final List<String> fnames = names;
        // ★ 第 270 轮：导出进度与结果都在卡片里
        final CardStack stack = new CardStack(this);
        stack.replace(Trans.get(this, R.string.resources_export),
                Trans.get(this, R.string.resources_exporting));
        new Thread(new Runnable() {
            @Override public void run() {
                Exporter.Result r = null;
                Throwable err = null;
                try {
                    r = Exporter.zipEntries(ResourcesActivity.this, uri, apk, fnames,
                            new Exporter.Step() {
                                @Override public void onStep(final int done, final int total) {
                                    if (done != total && done % 10 != 0) return;
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            stack.updateText(Trans.get(ResourcesActivity.this,
                                                    R.string.resources_progress_fmt, done, total));
                                        }
                                    });
                                }
                            });
                } catch (Throwable t) {
                    err = t;
                    Log.w(TAG, "game assets export failed", t);
                }
                final Exporter.Result fr = r;
                final Throwable fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mExporting = false;
                        updateSummary();
                        if (isFinishing()) return;
                        if (fe != null || fr == null) {
                            stack.replace(Trans.get(ResourcesActivity.this, R.string.export_failed),
                                    Util.ioReason(ResourcesActivity.this, fe,
                                            Trans.get(ResourcesActivity.this,
                                                    R.string.resources_export_error)),
                                    new CardStack.Btn(Trans.get(ResourcesActivity.this, R.string.close),
                                            new Runnable() {
                                                @Override public void run() { stack.dismiss(); }
                                            }));
                            return;
                        }
                        stack.dismiss();
                        Toast.makeText(ResourcesActivity.this,
                                Trans.get(ResourcesActivity.this, R.string.resources_export_ok_fmt,
                                        fr.files, Util.formatSize(fr.rawBytes)),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "resources-export").start();
    }
}
