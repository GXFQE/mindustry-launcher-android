package io.mdt.launcher;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
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
    /** SAF：选一份 .msch 导进来 */
    private static final int REQ_BP_IMPORT = 72;
    /** SAF：把某一份写出去 */
    private static final int REQ_BP_EXPORT = 73;

    /**
     * SAF 的"待办目标"**必须是 static**（第 86 轮的硬规矩，见 `AGENTS.md` §五）：
     * 用户在系统选择器里时转屏 ⇒ Activity 会被销毁重建，实例字段在新实例里是 null
     * ⇒ 回调静默 return = "选完文件什么都没发生"。
     */
    private static String sImportSlot;
    private static Blueprints.Item sExportItem;
    /** dev 口用：把"选文件"换成路径（SAF 自动化不了） */
    public static final String EXTRA_DEV_IMPORT = "dev_bp_import";

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

        // ── 导入 / 导出（页面顶上两行，与地图页同一条定案）──────────────────────
        Util.bindAction(root, R.id.row_bp_import, R.drawable.ic_download,
                R.string.bp_import, R.string.bp_import_sub, new Runnable() {
                    @Override public void run() {
                        // ★ **先判游戏在不在跑**（拦在 SAF 选择器之前，别让用户白选一次）：
                        //   导入是往槽的 schematics/ 里写（同名旧件还会被挪去中转站），
                        //   而游戏本局存下来的蓝图就在那个目录里 —— 与地图导入同一条门禁。
                        if (Data.gameAlive(BlueprintsActivity.this)) {
                            alert(getString(R.string.game_busy_title),
                                    getString(R.string.bp_import_busy_msg));
                            return;
                        }
                        sImportSlot = mSlot;
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");     // 各家文件管理器对 .msch 的 MIME 报得五花八门
                        startActivityForResult(Intent.createChooser(i,
                                getString(R.string.bp_import)), REQ_BP_IMPORT);
                    }
                });
        Util.bindAction(root, R.id.row_bp_export, R.drawable.ic_upload,
                R.string.bp_export, R.string.bp_export_sub, new Runnable() {
                    @Override public void run() {
                        promptExport();
                    }
                });

        scan();

        // dev 口：把"选文件"换成路径（SAF 选择器自动化不了）—— ⚠️ 必须放在 scan() 之后，
        //   因为导入成功后会 refreshList() 再看一次列表。
        final String devPath = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_DEV_IMPORT);
        if (devPath != null && !devPath.trim().isEmpty()) {
            File df = new File(devPath.trim());
            importFile(android.net.Uri.fromFile(df), df.getName(), mSlot, false);
        }
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
                int opaque;
                try {
                    items = Blueprints.scan(BlueprintsActivity.this, mSlot);
                    // 缺件判据 = **本槽已启用模组**的那张内容表（与地图统计同源，含缓存）
                    MapStatsMods.SlotContent sc = MapStatsMods.contentFor(BlueprintsActivity.this, mSlot);
                    tab = sc.table;
                    opaque = sc.opaque;
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
                // ★ 再打一道"可能认错"的标记（判据在 MapStats.Pack#hasCode；只影响文案，不改判据）
                Blueprints.markSoft(items, opaque);
                final String[] titles = new String[items.size()];
                final String[] subs = new String[items.size()];
                // ★ 缩略图（第 116 轮）：与地图 / 存档页同一个形状 —— 先摆空数组，后台出来一张刷一张
                final android.graphics.Bitmap[] thumbs = new android.graphics.Bitmap[items.size()];
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
                        mAdapter = new MsavListAdapter(BlueprintsActivity.this, titles, subs, thumbs);
                        mList.setAdapter(mAdapter);
                        if (mEmpty != null) {
                            mEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                            if (items.isEmpty()) mEmpty.setText(R.string.bp_empty);
                        }
                        mScanned = true;
                    }
                });
                // ★ 后台逐张渲染（出来一张刷**那一行**）—— 与地图页同一套做法与同一条理由：
                //   几十份蓝图里 `msch` 已经在上面解析过，这里只是把瓦片画成位图 + 落缓存
                final ListView lv = mList;
                for (int i = 0; i < items.size(); i++) {
                    final int idx = i;
                    final android.graphics.Bitmap bm =
                            MschLoad.image(BlueprintsActivity.this, items.get(idx), MschLoad.THUMB);
                    if (bm == null) continue;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(BlueprintsActivity.this) || mAdapter == null) return;
                            mAdapter.setThumb(idx, bm);
                            // ★ 只更新**这一行**（`notifyDataSetChanged()` 会让整张列表在滚动时重排）
                            mAdapter.refreshThumb(lv, idx);
                        }
                    });
                }
            }
        }, "bp-scan").start();
    }

    private void showDetail(Blueprints.Item it) {
        Intent i = new Intent(this, BlueprintDetailActivity.class);
        i.putExtra(BlueprintDetailActivity.EXTRA_SLOT, mSlot);
        Blueprints.putExtra(i, it);
        // ⚠️ 用 startActivity（不收回执）：详情页删完之后本页的 `onResume` 就会重扫一次，
        //   再挂一条 RESULT_OK 分支只会**扫两遍**（而重扫本身是几十个小文件，很便宜）。
        startActivity(i);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BP_EXPORT) {
            // ★ 与导入同一条纪律：无论成败**先清掉待写的那一条**（否则下一次导出会认错蓝图）
            final Blueprints.Item it = sExportItem;
            sExportItem = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || it == null) return;
            exportItem(it, data.getData());
            return;
        }
        if (requestCode != REQ_BP_IMPORT) return;
        // ★ 与地图那条同一纪律：无论成败**先清目标槽**
        final String slot = sImportSlot;
        sImportSlot = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || slot == null) return;
        importFile(data.getData(), queryName(data.getData()), slot, false);
    }

    // ── 导入 ──────────────────────────────────────────────────────────────

    private void importFile(final android.net.Uri uri, final String displayName,
                            final String slot, final boolean overwrite) {
        final android.app.ProgressDialog pd = android.app.ProgressDialog.show(this,
                getString(R.string.bp_import), getString(R.string.bp_reading), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final BlueprintFiles.Result r;
                java.io.InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    File dir = BlueprintFiles.dirOf(BlueprintsActivity.this, slot);
                    r = BlueprintFiles.importSchem(BlueprintsActivity.this, dir,
                            BlueprintFiles.safeName(displayName), in, overwrite,
                            BlueprintFiles.trashDirOf(BlueprintsActivity.this));
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pd.dismiss();
                            alert(getString(R.string.bp_import), String.valueOf(t));
                        }
                    });
                    return;
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        // ★ 下面会弹 inline 的同名替换框 ⇒ 转屏销毁后必须停在这里（见 Util.dead）
                        if (Util.dead(BlueprintsActivity.this)) return;
                        if (r.ok) {
                            Toast.makeText(BlueprintsActivity.this,
                                    getString(R.string.bp_import_ok_fmt, r.finalName),
                                    Toast.LENGTH_SHORT).show();
                            scan();                       // ★ 与地图页统一：只重扫列表，不重建页面
                            return;
                        }
                        // ★ 判"要不要弹同名替换框"**只认 r.nameTaken**（权威判据字段），
                        //   不许去看 r.error 的字面内容（i18n P0.1；门禁规则 SRC-01）。
                        if (!overwrite && r.nameTaken) {
                            new android.app.AlertDialog.Builder(BlueprintsActivity.this)
                                    .setTitle(R.string.bp_overwrite_title)
                                    .setMessage(getString(R.string.bp_overwrite_msg_fmt,
                                            BlueprintFiles.safeName(displayName)))
                                    .setPositiveButton(R.string.bp_overwrite_ok,
                                            new android.content.DialogInterface.OnClickListener() {
                                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                                    importFile(uri, displayName, slot, true);
                                                }
                                            })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                            return;
                        }
                        // ⚠️ 失败原因走**码 → 文案**（`Msch` 只给码；`error` 原文是给报告与自检的）
                        String why = r.broken != null ? MschText.reason(BlueprintsActivity.this, r.broken)
                                : r.error;
                        alert(getString(R.string.bp_import),
                                getString(R.string.bp_import_fail_fmt, why));
                    }
                });
            }
        }, "bp-import").start();
    }

    // ── 导出 ──────────────────────────────────────────────────────────────

    /**
     * 先挑"导出哪一份"，再让用户选存到哪。
     *
     * ★ 与地图页同一条理由：**导出不限来源** —— 模组自带的蓝图在模组包（zip）里面，
     *   用户在文件管理器里根本看不见它们；只导本槽的话，"把模组带的那份拿出来"永远做不到。
     */
    private void promptExport() {
        if (mItems == null || mItems.isEmpty()) {
            Toast.makeText(this, R.string.bp_export_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Blueprints.Item> items = mItems;
        String[] titles = new String[items.size()];
        String[] subs = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            titles[i] = items.get(i).displayName();
            subs[i] = items.get(i).sourceLabel(this) + " · " + items.get(i).line(this);
        }
        View box = getLayoutInflater().inflate(R.layout.dialog_msav_list, null);
        TextView head = (TextView) box.findViewById(R.id.msav_head);
        head.setVisibility(View.VISIBLE);
        // ★ 条数这类信息只能放表头，**不能塞标题**（AlertDialog 的标题是单行的）
        head.setText(getString(R.string.bp_export_head_fmt, items.size()));
        ListView lv = (ListView) box.findViewById(R.id.msav_list);
        lv.setAdapter(new MsavListAdapter(this, titles, subs));
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.bp_export)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .create();
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= items.size()) return;
                sExportItem = items.get(pos);
                dlg.dismiss();
                startActivityForResult(Intent.createChooser(
                        // ★ 文件名用**原始名**：那才是游戏里认的名字，"安全化"成 ASCII 之后
                        //   用户在文件管理器里认不出来（同地图/存档导出那条纪律）
                        Exporter.createDoc(sExportItem.name(), "application/octet-stream"),
                        getString(R.string.chooser_export)), REQ_BP_EXPORT);
            }
        });
        dlg.show();
    }

    /** 把选中的那份写进 SAF 目标：本槽是真文件、模组自带要从 zip 条目流式拷 */
    private void exportItem(final Blueprints.Item it, final android.net.Uri uri) {
        final android.app.ProgressDialog pd = android.app.ProgressDialog.show(this,
                getString(R.string.bp_export), getString(R.string.bp_export_working), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final long n;
                try {
                    if (it.file != null && it.file.isFile()) {
                        n = Exporter.writeFile(BlueprintsActivity.this, uri, it.file);
                    } else if (it.container != null && it.entry != null) {
                        n = Exporter.writeEntry(BlueprintsActivity.this, uri, it.container, it.entry);
                    } else {
                        throw new java.io.IOException(getString(R.string.bp_export_nosrc));
                    }
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pd.dismiss();
                            // 同 `MapsActivity` 的导出失败：`ioReason` 只翻认识的系统 errno，
                            // 我们自己的文案原样透传（没 message 时用资源兜底，不甩类名）
                            alert(getString(R.string.export_failed),
                                    Util.ioReason(BlueprintsActivity.this, t,
                                            getString(R.string.bp_export_nosrc)));
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        Toast.makeText(BlueprintsActivity.this,
                                getString(R.string.bp_export_ok_fmt, it.name(),
                                        Util.formatSize(n)), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "bp-export").start();
    }

    /** 从 SAF 的 Uri 问出显示名（与地图/存档页同一套做法，失败退回 `blueprint.msch`） */
    private String queryName(android.net.Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (c.moveToFirst() && i >= 0) {
                        String n = c.getString(i);
                        if (n != null && !n.trim().isEmpty()) return n;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        String s = uri.getLastPathSegment();
        return s == null || s.trim().isEmpty() ? "blueprint.msch" : s;
    }

    /** 全类弹窗的唯一入口（挡"已销毁的 Activity"：导入/导出是后台任务收尾时弹的） */
    private void alert(String title, String msg) {
        if (Util.dead(this)) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
