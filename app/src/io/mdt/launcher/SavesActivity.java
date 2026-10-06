package io.mdt.launcher;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;

/**
 * 某个槽的**存档列表页**（二级页面，2026-10-06 第 115 轮第六批）。
 *
 * <p>★ 起因（用户）：「**存档页面也变成和模组 / 地图 / 蓝图这样的二级页面吧**」——
 * 原来槽页的「存档」只是一层**弹窗菜单**（看每份存档… / 导出存档… / 导入存档…），
 * 而模组 / 地图 / 蓝图那三条早就是**页面**了 ⇒ 存档补齐同一形状：
 * 表头 + 顶上动作行 + 页面自己的列表，返回键直接回槽页。
 *
 * <p>🔴 **两条纪律**（与那三个页面一字不差）：
 * <ul>
 *   <li>**只读**：本页不提供删除 —— 存档是用户的东西，删只走游戏自己的界面
 *       （口径见 {@link SlotIo#showUnreadable} 的注释）；</li>
 *   <li>SAF 的「导入」走 `startActivityForResult` ⇒ **本页必须转发 `onActivityResult`**
 *       （少了它就是"选完文件什么都没发生"）。</li>
 * </ul>
 *
 * <p>⚠️ 名字来历：本类原来指的是**槽列表页**（主页「存档与备份」那一行）；
 *   第六批把它改名成 {@link SlotsActivity}（它列的是**槽**），把这个名字让给了这里。
 */
public class SavesActivity extends BaseActivity {

    /** 进来的槽名。没有 ⇒ 直接退出，**不猜**（与模组页 / 地图页 / 蓝图页同一条） */
    public static final String EXTRA_SLOT = "slot";

    private String mSlot;
    private ListView mList;
    private TextView mHead;
    private TextView mEmpty;
    private View mBadRow;
    private View mBadDiv;
    /** 当前这份列表对应的文件（点行时按位置取；与列表**同一次扫描**，不重扫） */
    private File[] mFiles = new File[0];

    /** SAF 回调后要刷新本页（`SlotOps.Host` 是 `SlotIo` 那套 SAF 流程的回调口） */
    private final SlotOps.Host mHost = new SlotOps.Host() {
        @Override public void onSlotChanged() {
            if (!Util.dead(SavesActivity.this)) refresh();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSlot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        if (mSlot == null || mSlot.trim().isEmpty()) {
            finish();                       // 没槽名就退出，**不猜**
            return;
        }
        mSlot = mSlot.trim();

        View root = getLayoutInflater().inflate(R.layout.activity_saves, null);
        Util.applySystemInsets(root);
        setContentView(root);
        setTitle(getString(R.string.saves_list_title_fmt, mSlot));

        mHead = (TextView) root.findViewById(R.id.save_head);
        mEmpty = (TextView) root.findViewById(R.id.save_empty);
        // ⚠️ 空态那句话**复用**导入那条资源（「槽「X」里还没有存档。」）—— 同一件事只留一句文案
        mEmpty.setText(getString(R.string.export_no_save_fmt, mSlot));
        mList = (ListView) root.findViewById(R.id.save_list);
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= mFiles.length) return;
                SlotIo.showSaveDetail(SavesActivity.this, mFiles[pos]);
            }
        });
        mBadRow = root.findViewById(R.id.row_save_bad);
        mBadDiv = root.findViewById(R.id.div_save_bad);

        // ── 三条动作（顶上）────────────────────────────────────────────────
        Util.bindAction(root, R.id.row_save_import, R.drawable.ic_download,
                R.string.slot_op_import_save, R.string.save_page_import_sub, new Runnable() {
                    @Override public void run() {
                        Data.Slot s = findSlot();
                        if (s == null) { gone(); return; }
                        SlotIo.importSave(SavesActivity.this, s);
                    }
                });
        Util.bindAction(root, R.id.row_save_export, R.drawable.ic_upload,
                R.string.slot_op_export_save, R.string.save_page_export_sub, new Runnable() {
                    @Override public void run() {
                        Data.Slot s = findSlot();
                        if (s == null) { gone(); return; }
                        SlotIo.exportSave(SavesActivity.this, s);
                    }
                });
        Util.bindAction(root, R.id.row_save_bad, R.drawable.ic_health,
                R.string.slot_op_show_bad_saves, R.string.save_page_bad_sub, new Runnable() {
                    @Override public void run() {
                        SlotIo.showUnreadable(SavesActivity.this, mSlot);
                    }
                });
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();                          // 用户可能刚在游戏里存过 / 在别处导入过
    }

    /**
     * ★ SAF 的结果**必然回到发起它的那个 Activity** ⇒ 本页必须转发给 {@link SlotIo}
     *   （少了它就是"选完文件什么都没发生"；先例见 {@link SlotActivity} 的同一条注释）。
     */
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SlotIo.onActivityResult(this, requestCode, resultCode, data, mHost);
    }

    /** 重新扫盘 + 重建列表 + 决定空态 / 「读不出来」那一行的显隐 */
    private void refresh() {
        mFiles = SlotIo.fillSaves(this, mSlot, mList, null);
        boolean empty = mFiles.length == 0;
        // 表头那句同时承担"有几份"（标题里放不下多行，见 dialog_msav_list.xml 的实测）
        mHead.setText(getString(R.string.saves_list_head_fmt, mFiles.length));
        mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        mList.setVisibility(empty ? View.GONE : View.VISIBLE);

        // 「看看哪几份读不出来」**只在真有**的时候出现 —— 口径 = `MsavMeta.summarize`
        // （与槽页副标题、那个弹窗**同一处**实现；读 meta 要开流 ⇒ 走后台）
        new Thread(new Runnable() {
            @Override public void run() {
                final MsavMeta.Saves sm;
                try {
                    File dir = new File(Data.dirOf(SavesActivity.this, mSlot), "saves");
                    sm = MsavMeta.summarize(dir.listFiles());
                } catch (Throwable t) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(SavesActivity.this)) return;
                        int vis = sm.unreadableCount() > 0 ? View.VISIBLE : View.GONE;
                        if (mBadRow != null) mBadRow.setVisibility(vis);
                        // ⚠️ 线要跟着行一起显隐（只处理行 ⇒ 卡片底下留一条悬空横线，第 115 轮第二批踩过）
                        if (mBadDiv != null) mBadDiv.setVisibility(vis);
                    }
                });
            }
        }, "save-bad-count").start();
    }

    /** 这个槽已经不在了（被删 / 改名）：提示一句就退出去（文案与槽页那句同一条） */
    private void gone() {
        android.widget.Toast.makeText(this, getString(R.string.slot_page_gone_fmt, mSlot),
                android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 找本页认的那个槽对象（**每次都现扫** —— 槽会被重命名 / 删除，别缓存） */
    private Data.Slot findSlot() {
        for (Data.Slot x : Data.allSlots(this)) {
            if (mSlot.equals(x.name)) return x;
        }
        return null;
    }
}
