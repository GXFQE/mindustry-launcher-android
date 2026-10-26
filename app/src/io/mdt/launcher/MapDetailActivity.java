package io.mdt.launcher;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 地图详情页（F21）：大图 + 元数据 + 删除入口。
 *
 * <h3>资源统计去哪了（第 192 轮）</h3>
 * 四段资源统计（地矿 / 墙矿 / 可采地板 / 加成地板）+ 可建造 + 核心区 + 技术细节**整块搬进了
 * 官方插件 `io.mdt.plugins.mapstats`**（工程在 `plugins/mapstats/`，入口 = 地图页上的「资源统计」行）。
 * 本页只留"这张图是什么"与"删掉它"——统计的解析、口径、导出都在插件里，宿主不再留第二份实现。
 *
 * <p>⚠️ 本页仍然挂插件动作区（`@layout/plugin_section`，{@link #pluginPageId()} =
 * {@link Extensions#PAGE_MAP_DETAIL}）：插件可以在这里放自己的动作，`selectedFile()` = 这一张图。</p>
 *
 * 🔴 根节点带 `@+id/root` + {@link Util#applySystemInsets}：targetSdk ≥ 35 的强制 edge-to-edge 下，
 *   漏了它整页从 y=0 画、被状态栏与顶栏整块盖住，而且**全程不报错**（第 81 轮的坑）。
 *
 * 🔴 **必须继承 {@link BaseActivity}**（2026-10-04 修）：深浅色设置的唯一生效点是它的
 *   `attachBaseContext`。见 {@link SlotActivity} 类注释。
 */
public class MapDetailActivity extends BaseActivity {

    /** 第 160 轮：地图详情页挂插件动作区；selectedFile = 这一张图。 */
    @Override protected String pluginPageId() { return Extensions.PAGE_MAP_DETAIL; }
    @Override protected String pluginSlot() { return mSlot; }
    @Override protected io.mdt.launcher.api.ItemInfo pluginSelectedItem() {
        return mItem == null ? null : PluginData.mapItem(this, mItem);
    }
    @Override protected java.io.File pluginSelectedFile() {
        return mItem == null ? null : mItem.file;
    }
    /** 目标槽（**本页一切以它为准**：预览配色表、插件动作都按它取） */
    public static final String EXTRA_SLOT = "slot";

    private String mSlot;
    private Maps.Item mItem;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mSlot = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        mItem = Maps.fromExtra(this, getIntent());
        if (mSlot == null || mSlot.trim().isEmpty() || mItem == null) {
            finish();                       // 没槽名/没定位信息就退出，**绝不猜**
            return;
        }
        mSlot = mSlot.trim();
        setTitle(mItem.name());

        View root = getLayoutInflater().inflate(R.layout.activity_map_detail, null);
        Util.applySystemInsets(root);
        setContentView(root);

        ((TextView) root.findViewById(R.id.detail_text)).setText(mItem.detail(this));

        // ★ 只有**本槽**的图能删（游戏自带 / 模组自带的在 APK 和模组包里面）
        if (mItem.from == Maps.FROM_SLOT && mItem.file != null) {
            View actions = root.findViewById(R.id.detail_actions);
            actions.setVisibility(View.VISIBLE);
            Util.bindAction(actions, R.id.row_detail_delete, R.drawable.ic_zip,
                    R.string.map_delete, R.string.map_delete_sub, new Runnable() {
                        @Override public void run() {
                            confirmDelete();
                        }
                    });
        }
        loadPreview(root);

        // v24（第 226 轮）：section 改由 BaseActivity → PluginEntries.attachPage 统一挂
        //   （卡片里 plugin_section_body；本页旧的独立 body 不再使用）
    }

    /** 大图 + 版本 APK：两件事都要开 zip，**一起在后台做** */
    private void loadPreview(final View root) {
        new Thread(new Runnable() {
            @Override public void run() {
                String apk = null;
                try {
                    apk = Mods.targetsFor(MapDetailActivity.this, mSlot).apkPath;
                } catch (Throwable ignored) {
                }
                final String apkPath = apk;
                final android.graphics.Bitmap bm =
                        MapLoad.image(MapDetailActivity.this, mItem, apkPath, MapLoad.BIG);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        ImageView iv = (ImageView) root.findViewById(R.id.detail_image);
                        // ★ 出不了图就**保持 GONE**，不留空框（槽没指定版本 ⇒ 没配色表，最常见）
                        if (bm != null) {
                            iv.setImageBitmap(bm);
                            iv.setVisibility(View.VISIBLE);
                        } else {
                            iv.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }, "map-detail-img").start();
    }

    private void confirmDelete() {
        // ★ 2026-10-04：**先判游戏在不在跑**（与 MapFiles.deleteToTrash 里那道是同一个判据，
        //   这里只是把它提前到"问用户之前"）。原来只靠 `moved == null` 事后报错，
        //   而那句 `map_delete_fail` 还写着"或者这张图属于游戏 / 模组包里的"——
        //   本页的删除入口**只对本槽地图出现**（见 onCreate 里的 FROM_SLOT 判断），
        //   那个原因在任何情况下都不成立，等于把唯一可操作的那句稀释掉了。
        if (Data.gameAlive(this)) {
            alert(Trans.get(MapDetailActivity.this, R.string.game_busy_title), Trans.get(MapDetailActivity.this, R.string.map_delete_busy_msg));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.map_delete)
                .setMessage(Trans.get(MapDetailActivity.this, R.string.map_delete_confirm_fmt, mItem.name()))
                .setPositiveButton(R.string.map_delete, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        File moved = MapFiles.deleteToTrash(MapDetailActivity.this,
                                mItem.file.getParentFile(), mItem.file,
                                MapFiles.trashDirOf(MapDetailActivity.this));
                        if (moved == null) {
                            alert(Trans.get(MapDetailActivity.this, R.string.map_delete),
                                    Trans.get(MapDetailActivity.this, R.string.map_delete_fail));
                            return;
                        }
                        Toast.makeText(MapDetailActivity.this,
                                Trans.get(MapDetailActivity.this, R.string.map_deleted_ok_fmt, mItem.name()),
                                Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);   // 告诉列表：图没了，回去重新清点
                        finish();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 弹窗入口 —— ★ 在这里挡"已销毁的 Activity"（理由见 {@link Util#dead}）。
     */
    private void alert(String title, String msg) {
        if (CardStack.leaf(title, msg)) return;
        if (Util.dead(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
