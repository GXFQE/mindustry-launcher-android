package io.mdt.launcher;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * 「版本」页（2026-10-25）—— 一个版本的**全部动作**都在这里。
 *
 * <p>★ 为什么要有这一页：用户原话「槽操作和长按后的版本操作有点乱（应该把页面彻底整理下）」。
 *   在这之前，版本级动作散在两处弹窗里：行上的「槽」弹窗（换槽 + 看详情 + 再加一行）、
 *   长按弹窗（命名 / 删除 / 资源 / 槽），而且已装版与导入版还是两套按钮。
 *   现在入口只有一个：行上的按钮与长按都进这里；动作实现在 {@link VersionOps}（只有一份）。
 *
 * <p>★ 传参只用**定位信息**：版本 key + 这一行的槽（`slotFor` 的结果）。页面自己重扫
 *   （{@link Versions#scanAll}）找回 {@link Versions.Entry} —— 不在 intent 里搬一整个 Entry，
 *   否则页面里的状态会和列表里的真值悄悄分叉。
 *
 * <p>★ 动作改完**默认** `finish()`：主界面在 `onResume` 里会重新扫描并重画（{@code MainActivity.onResume}
 *   → {@code rescan()}），回到列表就能看到结果。页面自己不留一份可能与盘上不一致的缓存。
 *   **唯一例外 = 命名**（2026-10-26 用户：「命名完后不要回到主页」）：它只改"这一项叫什么"，
 *   不改变列表的项集合 ⇒ 就地 {@code refresh()}，留在这一页接着改 / 看结果。
 */
public class VersionActivity extends BaseActivity {

    /** 版本 key（`io.anuke.mindustry` / `import:<文件名>`） */
    static final String EXTRA_KEY = "v_key";
    /** 这一行实际用的槽（`Versions.slotFor`）—— 一个版本多行时靠它区分是哪一行 */
    static final String EXTRA_SLOT = "v_slot";

    private String mKey = "";
    private String mSlot = "";
    private Versions.Entry mEntry;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_version);
        Util.applySystemInsets(findViewById(R.id.root));

        String k = getIntent().getStringExtra(EXTRA_KEY);
        String s = getIntent().getStringExtra(EXTRA_SLOT);
        mKey = k == null ? "" : k;
        mSlot = s == null ? "" : s;

        final VersionOps.Done done = new VersionOps.Done() {
            @Override public void changed() { finish(); }
        };
        // ★ 命名**不离开这一页**（2026-10-26 用户：「命名完后不要回到主页」）：
        //   改完就地重画（`refresh()` 会按 (key, 槽) 把这一行重新找回来 ⇒ 标题 / 卡片立刻是新名字），
        //   用户可以接着改另一项、也能看见结果。别的动作（换槽 / 克隆 / 删除）仍然 `finish()`：
        //   它们改变的是"列表里有哪些项"，留在这一页只会看到旧状态。
        final VersionOps.Done renamed = new VersionOps.Done() {
            @Override public void changed() { refresh(); }
        };

        findViewById(R.id.version_card).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mEntry != null) VersionOps.showDetail(VersionActivity.this, mEntry);
            }
        });

        Util.bindAction(findViewById(R.id.root), R.id.row_ver_slot, R.drawable.ic_folder,
                R.string.version_row_slot, R.string.version_row_slot_sub,
                new Runnable() {
                    @Override public void run() {
                        if (mEntry != null) VersionOps.pickSlot(VersionActivity.this, mEntry, done);
                    }
                });
        // ★ 第 320 轮：**进这一行的槽**（用户：「在实例管理页面加一个进入当前槽的管理页面的机制」）。
        //   槽名在点下去的那一刻从 mEntry 取（不在 onCreate 里缓存）：这一页的图标/文字都是 refresh() 重算的，
        //   缓存一份就有"换过槽、这里还指着旧槽"的可能。
        Util.bindAction(findViewById(R.id.root), R.id.row_ver_manslot, R.drawable.ic_storage,
                R.string.version_row_manslot, R.string.version_row_manslot_sub,
                new Runnable() {
                    @Override public void run() {
                        if (mEntry == null) return;
                        startActivity(slotIntent(VersionActivity.this, Versions.slotFor(mEntry)));
                    }
                });
        Util.bindAction(findViewById(R.id.root), R.id.row_ver_clone, R.drawable.ic_add,
                R.string.version_row_clone, R.string.version_row_clone_sub,
                new Runnable() {
                    @Override public void run() {
                        if (mEntry != null) VersionOps.promptAddBinding(VersionActivity.this, mEntry, done);
                    }
                });
        Util.bindAction(findViewById(R.id.root), R.id.row_ver_detail, R.drawable.ic_stats,
                R.string.version_row_detail, R.string.version_row_detail_sub,
                new Runnable() {
                    @Override public void run() {
                        if (mEntry != null) VersionOps.showDetail(VersionActivity.this, mEntry);
                    }
                });
        Util.bindAction(findViewById(R.id.root), R.id.row_ver_rename, R.drawable.ic_edit,
                R.string.version_row_rename, R.string.version_row_rename_sub,
                new Runnable() {
                    @Override public void run() {
                        if (mEntry != null) VersionOps.promptRename(VersionActivity.this, mEntry, renamed);
                    }
                });
        Util.bindAction(findViewById(R.id.root), R.id.row_ver_res, R.drawable.ic_zip,
                R.string.version_row_res, R.string.version_row_res_sub,
                new Runnable() {
                    @Override public void run() {
                        if (mEntry != null) VersionOps.openResources(VersionActivity.this, mEntry);
                    }
                });
        Util.bindAction(findViewById(R.id.root), R.id.row_ver_delete, R.drawable.ic_trash,
                R.string.version_row_delete, R.string.version_row_delete_sub,
                new Runnable() {
                    @Override public void run() {
                        if (mEntry != null) VersionOps.delete(VersionActivity.this, mEntry, done);
                    }
                });
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    /**
     * 按 (key, 槽) 找回这一行的 Entry 并刷新文字。
     *
     * <p>找不到 ⇒ 这一行已经不在了（槽被删 / 版本被删 / 在别的页面改过）⇒ 直接关闭，
     * 不在这里造一个"半存在"的页面。
     */
    private void refresh() {
        if (mKey.isEmpty()) { finish(); return; }
        Versions.Entry found = null;
        for (Versions.Entry e : Versions.scanAll(this)) {
            if (e.key().equals(mKey) && Versions.slotFor(e).equals(mSlot)) { found = e; break; }
        }
        if (found == null) { finish(); return; }
        mEntry = found;

        String name = found.displayName(this);
        String slot = Versions.slotFor(found);
        int saves = VersionOps.saveCountIn(this, slot);
        setTitle(name);
        ((TextView) findViewById(R.id.version_title)).setText(name);
        // 副标题用**列表行那份紧凑文案**（来源 · 大小 · 存档 N 份）——
        // 别用 Entry.subtitle() 的长形态：它带整条 `/data/app/~~…/base.apk` 路径，真机上占三行。
        ((TextView) findViewById(R.id.version_sub)).setText(Versions.subtitleLine(this, found, saves));
        ((TextView) findViewById(R.id.version_slot)).setText(Trans.get(this,
                R.string.version_page_slot_fmt, slot, saves));

        // 「换槽」的副标题写清这一行现在用的是哪个槽（页面标题是版本名，不是槽名）
        ((TextView) findViewById(R.id.row_ver_slot).findViewById(R.id.act_sub)).setText(
                Trans.get(this, R.string.version_row_slot_sub_fmt, slot));
        // 「管理这个槽」的副标题同样带槽名：它讲的是"进去管的内容"，不带名字就分不清是哪一份数据
        ((TextView) findViewById(R.id.row_ver_manslot).findViewById(R.id.act_sub)).setText(
                Trans.get(this, R.string.version_row_manslot_sub_fmt, slot));

        // 删除的范围随**行数**变（判据只有 Versions.rowCount 一处）。
        // ★ 标题也跟着变：用户 2026-10-25 说「还有两行才奇怪了，到底是删完还是没删完有歧义」
        //   ⇒ 不写数量，直接说清"删什么"（这一项 / 整个版本）。
        TextView delTitle = (TextView) findViewById(R.id.row_ver_delete).findViewById(R.id.act_title);
        TextView delSub = (TextView) findViewById(R.id.row_ver_delete).findViewById(R.id.act_sub);
        int scope = VersionOps.deleteScope(Versions.rowCount(mKey), found.imported);
        if (scope == VersionOps.DELETE_ROW) {
            delTitle.setText(R.string.version_row_delete);
            delSub.setText(R.string.version_row_delete_sub_row);
        } else if (scope == VersionOps.DELETE_VERSION) {
            delTitle.setText(R.string.version_row_delete_version);
            delSub.setText(R.string.version_row_delete_sub_version);
        } else {
            delTitle.setText(R.string.version_row_delete);
            delSub.setText(R.string.version_row_delete_sub_blocked);
        }
        // ★ 2026-10-25：已装版最后一项**灰掉**（原来还能点，点开只是一句说明 —— 真机反馈
        //   「到底能不能删」；现在按钮本身就说"不能"，不用点一次才知道）。
        View del = findViewById(R.id.row_ver_delete);
        boolean blocked = scope == VersionOps.DELETE_BLOCKED;
        del.setEnabled(!blocked);
        del.setAlpha(blocked ? 0.45f : 1f);
    }

    /**
     * 进某个槽的**管理页**（`SlotActivity`）的 Intent —— 槽名随 extra 带过去。
     *
     * <p>★ 第 320 轮抽成一个函数只为一件事：**让自检能钉住"槽名真的带过去了"**。
     * 直接写在点击回调里时，写错 extra 的键（`SlotActivity` 读的是 `EXTRA_SLOT`）编译器不响、
     * 真机上只会表现为"点进去看的是当前槽"—— 那是**静默错**（与「少一个 `@+id` 是静默跳过」同族）。
     */
    static android.content.Intent slotIntent(Context c, String slot) {
        return new android.content.Intent(c, SlotActivity.class)
                .putExtra(SlotActivity.EXTRA_SLOT, slot == null ? "" : slot);
    }
}
