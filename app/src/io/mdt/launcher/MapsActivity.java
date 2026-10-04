package io.mdt.launcher;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * 地图页（槽的子页面，REF §56）—— **从槽二级页面进**，返回就回那一页。
 *
 * 🔴 为什么不做成弹窗：弹窗属于**发起它的那个 Activity**。以前地图列表挂在存档页上，
 *   用户点"关闭"之后落在存档页（"上一级"），观感像是又开了一个页面（用户 2026-10-03 反馈）。
 *   做成独立页面后：返回键 = 回槽页面，语义就对了。
 *
 * 口径与实现全部复用既有组件：{@link Maps}（三来源清点）· {@link MapLoad}（配色表+渲染+缓存）
 * · {@link MapFiles}（导入/删除，删除走中转站"挪不删"）· {@link MsavListAdapter}（卡片行）。
 *
 * 🔴 **必须继承 {@link BaseActivity}**（2026-10-04 修）：深浅色设置的唯一生效点是它的
 *   `attachBaseContext`。本类原来写的是 `extends Activity` ⇒ 用户设了「浅色」/「深色」之后，
 *   本页仍按系统配色（症状：同一应用两套配色，但不报错）。见 {@link SlotActivity} 类注释。
 */
public class MapsActivity extends BaseActivity {
    /** 目标槽（**本页一切以它为准**，不许回落到当前槽） */
    public static final String EXTRA_SLOT = "slot";
    private static final int REQ_MAP = 61;
    /** 导出：SAF 的「保存到哪」回来 */
    private static final int REQ_MAP_EXPORT = 62;
    /** 地图详情页（F21）：回来时若它是"删掉了"就重新清点 */
    private static final int REQ_MAP_DETAIL = 63;

    private String mSlot;
    private TextView mHead;
    /** 空态（2026-10-04 补）：一张图都没有时把「为什么没有 / 下一步」说出来，见布局注释 */
    private TextView mEmpty;
    private MsavListAdapter mAdapter;
    private List<Maps.Item> mItems;
    private String mApkPath;
    /**
     * ★★ 导入地图的目标槽（2026-10-04 修）：必须是 **static**。
     *
     * 为什么：`startActivityForResult` 的结果**只回到发起它的那个实例**，而本页没声明
     * `configChanges` ⇒ 用户在系统选择器里时一转屏（或被系统回收后回前台），发起方实例
     * 就被销毁重建 —— 实例字段在新实例里是 null，回调于是**静默 return**，
     * 用户看到的就是"选完文件什么都没发生"（无 Toast、无报错）。
     * ⚠️ 这是本工程**踩过一次的坑**：{@link SlotIo} 的 `sMsavTarget` / `sZipSlot`
     *   就是同一件事，那边是 static 且在注释里写明了理由 —— 这里原来漏了。
     * ⚠️ 纪律同 {@link SlotIo}：**每次发起前重设、回调进来先清**（否则用户取消后
     *   残留的目标会被下一次选择"接上"，文件落进上一个槽）。
     */
    private static String sMapTarget;
    /** 导出：SAF 回来时要写出去的那一条（**成败都先清空**，见 onActivityResult）。
     *  ★ static 的理由同 {@link #sMapTarget}（转屏/回收后回调仍要认得它）。 */
    private static Maps.Item sExportItem;
    /** 列表三件套（导出对话框直接复用这一批文本与缩略图，不必重扫、重渲染） */
    private String[] mTitles;
    private String[] mSubs;
    private android.graphics.Bitmap[] mThumbs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mSlot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        if (mSlot == null || mSlot.trim().isEmpty()) {
            finish();                       // 没槽名就退出，绝不猜
            return;
        }
        mSlot = mSlot.trim();
        setTitle(getString(R.string.maps_title_fmt, mSlot));

        View root = getLayoutInflater().inflate(R.layout.activity_maps, null);
        // ★ 必须有（见 activity_maps.xml 的注释）：targetSdk ≥ 35 强制 edge-to-edge，
        //   不补 insets 的话整页从 y=0 开始画 ⇒ 表头 + 两行动作被状态栏和顶栏整块盖住
        //   （用户 2026-10-03 报的"顶部就有问题"就是这个）。
        Util.applySystemInsets(root);
        setContentView(root);
        mHead = (TextView) root.findViewById(R.id.maps_head);
        mHead.setText(R.string.maps_scanning);
        mEmpty = (TextView) root.findViewById(R.id.maps_empty);

        Util.bindAction(root, R.id.row_maps_import, R.drawable.ic_download,
                R.string.maps_import, R.string.slot_page_maps_sub, new Runnable() {
                    @Override public void run() {
                        // ★ 2026-10-04：**先判游戏在不在跑**（拦在 SAF 选择器之前，别让用户白选一次）。
                        //   导入是往槽的 maps/ 里**写**（还会把同名旧图挪去中转站），
                        //   而工程里凡"写槽"的路径都有这道门禁（导入存档/整槽、备份、恢复、改设置）；
                        //   地图与模组导入原来漏了 —— 游戏本局写的图会被覆盖或被挪走。
                        if (Data.gameAlive(MapsActivity.this)) {
                            alert(getString(R.string.game_busy_title),
                                    getString(R.string.maps_import_busy_msg));
                            return;
                        }
                        sMapTarget = mSlot;
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");       // 各家文件管理器对 .msav 的 MIME 报得五花八门
                        startActivityForResult(Intent.createChooser(i,
                                getString(R.string.maps_import)), REQ_MAP);
                    }
                });

        // ★ 导出与导入成对放在**页面顶上**（用户 2026-10-03 定案）
        Util.bindAction(root, R.id.row_maps_export, R.drawable.ic_upload,
                R.string.maps_export, R.string.maps_export_sub, new Runnable() {
                    @Override public void run() {
                        promptExportMap();
                    }
                });

        final ListView lv = (ListView) root.findViewById(R.id.maps_list);
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (mItems != null && pos >= 0 && pos < mItems.size()) showDetail(mItems.get(pos));
            }
        });
        scan(lv);
    }

    /** 三来源清点 + 缩略图**后台逐张渲染**（出来一张刷一张） */
    private void scan(final ListView lv) {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Maps.Item> items;
                final String apk;
                try {
                    Mods.Target t = Mods.targetsFor(MapsActivity.this, mSlot);
                    apk = t.apkPath;
                    items = Maps.scan(MapsActivity.this, mSlot, apk);
                } catch (Throwable ex) {
                    android.util.Log.w("MDTLauncher", "map scan failed", ex);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // ⚠️ 这里只改文字，不会崩；但统一走 Util.dead() 是为了
                            //    "一个工程只有一种判据"（转屏后旧实例仍在 post，判死即止）。
                            if (Util.dead(MapsActivity.this)) return;
                            // ★ 原来这里是 `mHead.setText(String.valueOf(ex))` —— 表头直接
                            //   变成 `java.lang.NullPointerException: …`（2026-10-04 修）。
                            //   异常原文归 logcat / 报告，界面只给白话 + 下一步。
                            mHead.setText(R.string.maps_scan_failed);
                        }
                    });
                    return;
                }
                final String[] titles = new String[items.size()];
                final String[] subs = new String[items.size()];
                final android.graphics.Bitmap[] thumbs = new android.graphics.Bitmap[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    Maps.Item it = items.get(i);
                    titles[i] = it.name();
                    subs[i] = it.sourceLabel(MapsActivity.this) + " · " + it.line(MapsActivity.this);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mItems = items;
                        mApkPath = apk;
                        // ★ 三件套存成字段：导出对话框直接复用（缩略图已经在后台渲染过一遍，
                        //   再渲染一次既慢又白费电）
                        mTitles = titles;
                        mSubs = subs;
                        mThumbs = thumbs;
                        mHead.setText(getString(R.string.maps_counts_fmt,
                                Maps.count(items, Maps.FROM_SLOT),
                                Maps.count(items, Maps.FROM_GAME),
                                Maps.count(items, Maps.FROM_MOD)));
                        mAdapter = new MsavListAdapter(MapsActivity.this, titles, subs, thumbs);
                        lv.setAdapter(mAdapter);
                        // ★ 空态（2026-10-04 补）：一张图都没有时必须说清"为什么没有 + 下一步"。
                        //   文案 `maps_empty_fmt` 早就写好了，此前**全工程零引用**（孤儿串）
                        //   ⇒ 用户只看到一排 0 和一片空白。见布局里那段注释。
                        if (mEmpty != null) {
                            mEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                            if (items.isEmpty()) {
                                mEmpty.setText(getString(R.string.maps_empty_fmt, mSlot));
                            }
                        }
                    }
                });
                for (int i = 0; i < items.size(); i++) {
                    final int idx = i;
                    final android.graphics.Bitmap bm =
                            MapLoad.image(MapsActivity.this, items.get(idx), apk, MapLoad.THUMB);
                    if (bm == null) continue;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (Util.dead(MapsActivity.this) || mAdapter == null) return;
                            mAdapter.setThumb(idx, bm);
                            // ★ 只更新**这一行**，绝不 `notifyDataSetChanged()` ——
                            //   后者会让整张列表在用户滚动时不断重排，手感就是"滑不上去 / 一滑就跳"
                            //   （114 张缩略图 = 114 次重排，用户 2026-10-03 实测反馈）。
                            int first = lv.getFirstVisiblePosition();
                            View row = lv.getChildAt(idx - first);
                            if (row != null) {
                                android.widget.ImageView iv =
                                        (android.widget.ImageView) row.findViewById(R.id.msav_thumb);
                                if (iv != null) {
                                    iv.setImageBitmap(bm);
                                    iv.setVisibility(View.VISIBLE);
                                }
                            }
                        }
                    });
                }
            }
        }, "maps-page").start();
    }

    /**
     * 点开一项 ⇒ **进详情页**（F21：大图 + 元数据 + 四段资源统计）。
     *
     * ★ 为什么从弹窗改成独立页面：详情里现在有四段可折叠的统计，弹窗装不下；而且弹窗属于
     *   发起它的那个页面 —— 关掉会落在"上一级"（地图列表那一版已经因此改过一次，REF §56）。
     * ★ 用 `startActivityForResult`：详情页里删掉这张图后要回来**重新清点**
     *   （比手工维护列表可靠，与导入/删除原本的做法一致）。
     */
    private void showDetail(final Maps.Item it) {
        Intent i = new Intent(this, MapDetailActivity.class);
        i.putExtra(MapDetailActivity.EXTRA_SLOT, mSlot);
        Maps.putExtra(i, it);
        startActivityForResult(i, REQ_MAP_DETAIL);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MAP_DETAIL) {
            // 详情页里把这张图删了 ⇒ 回来重新清点（与导入/删除原本的做法一致）
            if (resultCode == RESULT_OK) recreate();
            return;
        }
        if (requestCode == REQ_MAP_EXPORT) {
            // ★ 与导入同一条纪律：无论成败**先清掉待写的那一条**（否则下一次导出会认错图）
            final Maps.Item it = sExportItem;
            sExportItem = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || it == null) return;
            exportItem(it, data.getData());
            return;
        }
        if (requestCode != REQ_MAP) return;
        // ★ 与存档那条同一纪律：无论成败**先清目标槽**
        final String slot = sMapTarget;
        sMapTarget = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || slot == null) return;
        importFile(data.getData(), queryName(data.getData()), slot, false);
    }

    // ── 导出地图（用户 2026-10-03：「导入和导出都放页面顶上吧」） ──────────────

    /**
     * 先挑"导出哪一张"，再让用户选存到哪。
     *
     * ★ 为什么不是"导出本槽全部"：地图有三个来源（本槽 / 游戏自带 / 模组自带，见 {@link Maps}），
     *   后两者**在 APK 和模组包里面**，用户在文件管理器里根本看不见它们 ——
     *   只导本槽的话，"把游戏自带那张图拿出来"这件事永远做不到。
     *   所以这里列**全部**地图，点哪张导哪张。
     */
    private void promptExportMap() {
        if (mItems == null || mItems.isEmpty()) {
            Toast.makeText(this, R.string.maps_export_none, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Maps.Item> items = mItems;
        View box = getLayoutInflater().inflate(R.layout.dialog_msav_list, null);
        TextView head = (TextView) box.findViewById(R.id.msav_head);
        head.setVisibility(View.VISIBLE);
        // ★ 条数这类信息只能放表头，**不能塞标题**：AlertDialog 的标题是单行的
        //   （第 58 轮实测被截成省略号）—— 见 dialog_msav_list.xml 的注释
        head.setText(getString(R.string.maps_export_head_fmt, items.size()));
        ListView lv = (ListView) box.findViewById(R.id.msav_list);
        lv.setAdapter(new MsavListAdapter(this, mTitles, mSubs, mThumbs));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.maps_export)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .create();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= items.size()) return;
                Maps.Item it = items.get(pos);
                sExportItem = it;
                dlg.dismiss();
                startActivityForResult(Intent.createChooser(
                        // ★ 文件名用**原始名**：`0.msav` 这种就是游戏里认的名字，
                        //   "安全化"成 ASCII 之后用户在文件管理器里认不出来（同存档导出那条纪律）
                        Exporter.createDoc(it.name(), "application/octet-stream"),
                        getString(R.string.chooser_export)), REQ_MAP_EXPORT);
            }
        });
        dlg.show();
    }

    /**
     * 把选中的那张图写进 SAF 目标。
     * 两条路：本槽（以及目录形态模组）里的是**真文件**；游戏 APK / 模组包里的要**从 zip 条目流式拷**。
     */
    private void exportItem(final Maps.Item it, final Uri uri) {
        final ProgressDialog pd = ProgressDialog.show(this, getString(R.string.maps_export),
                getString(R.string.maps_export_working), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final long n;
                try {
                    if (it.file != null && it.file.isFile()) {
                        n = Exporter.writeFile(MapsActivity.this, uri, it.file);
                    } else if (it.container != null && it.entry != null) {
                        n = Exporter.writeEntry(MapsActivity.this, uri, it.container, it.entry);
                    } else {
                        throw new java.io.IOException(getString(R.string.maps_export_nosrc));
                    }
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pd.dismiss();
                            // 异常消息就是我们自己抛的那几句人话（见 Exporter）；
                            // 万一没有 message，也**不把类名甩给用户**（文案纪律第三条）
                            alert(getString(R.string.export_failed),
                                    t.getMessage() == null
                                            ? getString(R.string.maps_export_nosrc) : t.getMessage());
                        }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        Toast.makeText(MapsActivity.this,
                                getString(R.string.map_export_ok_fmt, it.name(),
                                        Util.formatSize(n)), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "map-export").start();
    }

    private void importFile(final Uri uri, final String displayName, final String slot,
                            final boolean overwrite) {
        final ProgressDialog pd = ProgressDialog.show(this, getString(R.string.maps_import),
                getString(R.string.maps_scanning), true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final MapFiles.Result r;
                java.io.InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    File dir = new File(Data.dirOf(MapsActivity.this, slot), "maps");
                    r = MapFiles.importMap(dir, MapFiles.safeName(displayName), in, overwrite,
                            MapFiles.trashDirOf(MapsActivity.this));
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pd.dismiss();
                            alert(getString(R.string.maps_import), t.toString());
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
                        // ★ 本分支下面会弹 inline 的同名替换框 ⇒ 发起它的实例若已被转屏销毁，
                        //   必须停在这里（否则 BadTokenException 闪退，见 {@link Util#dead}）。
                        //   ⚠️ 成功/失败那两条出口走 alert()/Toast，已由它们各自挡住。
                        if (Util.dead(MapsActivity.this)) return;
                        if (r.ok) {
                            Toast.makeText(MapsActivity.this,
                                    getString(R.string.map_import_ok_fmt, r.finalName),
                                    Toast.LENGTH_SHORT).show();
                            recreate();
                            return;
                        }
                        // ★ 判"要不要弹同名替换框"**只认 r.nameTaken**（MapFiles 给的权威标志），
                        //   不许去看 r.error 的字面内容 —— 那是给人看的话，一改措辞/一翻译就判错
                        //   （2026-10-04 i18n P0.1；门禁规则 SRC-01）。
                        if (!overwrite && r.nameTaken) {
                            new AlertDialog.Builder(MapsActivity.this)
                                    .setTitle(R.string.map_overwrite_title)
                                    .setMessage(getString(R.string.map_overwrite_msg_fmt,
                                            MapFiles.safeName(displayName)))
                                    .setPositiveButton(R.string.map_overwrite_ok,
                                            new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface d, int w) {
                                                    importFile(uri, displayName, slot, true);
                                                }
                                            })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                            return;
                        }
                        alert(getString(R.string.maps_import),
                                getString(R.string.map_import_fail_fmt, r.error));
                    }
                });
            }
        }, "map-import").start();
    }

    /** 从 SAF 的 Uri 问出显示名（与存档页同一套做法，失败退回 `map.msav`） */
    private String queryName(Uri uri) {
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
        return s == null || s.trim().isEmpty() ? "map.msav" : s;
    }

    /** 全类弹窗的唯一入口 —— ★ 2026-10-04 起在这里挡"已销毁的 Activity"：
     *  地图导入/导出是后台任务收尾时弹的（见 {@link Util#dead}）。 */
    private void alert(String title, String msg) {
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
