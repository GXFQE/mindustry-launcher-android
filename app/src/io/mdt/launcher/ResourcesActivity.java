package io.mdt.launcher;

import android.app.AlertDialog;
import android.app.ProgressDialog;
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
 * <p>★ 默认**一类都不勾**：一进来就全选会让人误点出 78 MB；摘要里实时报
 * “已选 N 类 · 约 X MB”，0 类时导出按钮不可点。
 */
public final class ResourcesActivity extends BaseActivity {

    static final String EXTRA_APK = "apk";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_VERSION = "version";

    private static final String TAG = "MDTLauncher";
    private static final int REQ_EXPORT = 5001;
    private static final String STATE_PENDING = "pending";

    private File mApk;
    private String mName = "";
    private String mVersion = "";
    private GameAssets.Catalog mCatalog;

    private TextView mHead;
    private TextView mTotal;
    private TextView mNote;
    private LinearLayout mGroups;
    private TextView mEmpty;
    private LinearLayout mSummaryCard;
    private TextView mSummary;
    private TextView mExport;

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
        mTotal = (TextView) findViewById(R.id.resources_total);
        mNote = (TextView) findViewById(R.id.resources_note);
        mGroups = (LinearLayout) findViewById(R.id.resources_groups);
        mEmpty = (TextView) findViewById(R.id.resources_empty);
        mSummaryCard = (LinearLayout) findViewById(R.id.resources_summary_card);
        mSummary = (TextView) findViewById(R.id.resources_summary);
        mExport = (TextView) findViewById(R.id.resources_export);

        Trans.bind((TextView) findViewById(R.id.resources_select_all), R.string.resources_select_all);
        Trans.bind((TextView) findViewById(R.id.resources_select_none), R.string.resources_select_none);
        Trans.bind((TextView) findViewById(R.id.resources_export), R.string.resources_export);

        findViewById(R.id.resources_select_all).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selectAll(true);
            }
        });
        findViewById(R.id.resources_select_none).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selectAll(false);
            }
        });
        mExport.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                exportSelected();
            }
        });

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
        mTotal.setVisibility(View.GONE);
        mExport.setEnabled(false);
        mExport.setAlpha(0.4f);
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
        mTotal.setText(Trans.get(this, R.string.resources_total_fmt,
                c.entries, Util.formatSize(c.bytes)));
        mTotal.setVisibility(View.VISIBLE);
        if (c.groups.isEmpty()) {
            showEmpty(R.string.resources_no_assets);
            return;
        }
        mNote.setText(Trans.get(this, R.string.resources_note));
        mNote.setVisibility(View.VISIBLE);
        mGroups.removeAllViews();
        mRows.clear();
        LayoutInflater inf = getLayoutInflater();
        for (GameAssets.Group g : c.groups) {
            View row = inf.inflate(R.layout.item_resource_group, mGroups, false);
            CheckBox check = (CheckBox) row.findViewById(R.id.resources_group_check);
            TextView sub = (TextView) row.findViewById(R.id.resources_group_sub);
            check.setText(labelOf(g.key));
            sub.setText(Trans.get(this, R.string.resources_group_line_fmt,
                    g.count(), Util.formatSize(g.bytes)));
            check.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                    updateSummary();
                }
            });
            mGroups.addView(row);
            mRows.add(new Row(g.key, check));
        }
        mGroups.setVisibility(View.VISIBLE);
        mSummaryCard.setVisibility(View.VISIBLE);
        mEmpty.setVisibility(View.GONE);
        updateSummary();
    }

    private void showEmpty(int resId) {
        mEmpty.setText(Trans.get(this, resId));
        mEmpty.setVisibility(View.VISIBLE);
        mGroups.setVisibility(View.GONE);
        mSummaryCard.setVisibility(View.GONE);
        mTotal.setVisibility(View.GONE);
        mNote.setVisibility(View.GONE);
        mExporting = false;
        mExport.setEnabled(false);
        mExport.setAlpha(0.4f);
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
        mSummary.setText(n == 0
                ? Trans.get(this, R.string.resources_selected_none)
                : Trans.get(this, R.string.resources_selected_fmt,
                        n, Util.formatSize(bytes)));
        boolean can = n > 0 && !mExporting && mCatalog != null;
        mExport.setEnabled(can);
        mExport.setAlpha(can ? 1f : 0.4f);
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
        mExporting = true;
        updateSummary();

        String zip = GameAssets.zipName(mVersion, keys);
        Intent i = Exporter.createDoc(zip, "application/zip");
        startActivityForResult(Intent.createChooser(i,
                Trans.get(this, R.string.chooser_export)), REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        mExporting = false;
        final List<String> names = mPendingNames;
        mPendingNames = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || names == null) {
            updateSummary();
            return;
        }
        final Uri uri = data.getData();
        final ProgressDialog pd = ProgressDialog.show(this,
                Trans.get(this, R.string.resources_export),
                Trans.get(this, R.string.resources_exporting), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                Exporter.Result r = null;
                Throwable err = null;
                try {
                    r = Exporter.zipEntries(ResourcesActivity.this, uri, mApk, names,
                            new Exporter.Step() {
                                @Override public void onStep(final int done, final int total) {
                                    if (done != total && done % 10 != 0) return;
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            if (pd.isShowing()) {
                                                pd.setMessage(Trans.get(ResourcesActivity.this,
                                                        R.string.resources_progress_fmt, done, total));
                                            }
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
                        if (pd.isShowing()) pd.dismiss();
                        mExporting = false;
                        updateSummary();
                        if (isFinishing()) return;
                        if (fe != null || fr == null) {
                            new AlertDialog.Builder(ResourcesActivity.this)
                                    .setTitle(R.string.export_failed)
                                    .setMessage(Util.ioReason(ResourcesActivity.this, fe,
                                            Trans.get(ResourcesActivity.this, R.string.resources_export_error)))
                                    .setPositiveButton(R.string.close, null)
                                    .show();
                            return;
                        }
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
