package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「版本」这个概念的**唯一实现**：换槽 / 克隆（再加一项）/ 详情 / 命名 / 资源 / 删除。
 *
 * <p>★ 2026-10-25 从 `MainActivity` 的弹窗里搬出来的。用户原话：「槽操作和长按后的版本操作有点乱
 *   （应该把页面彻底整理下）」—— 根子是这些动作**没有归属**：换槽塞在槽弹窗、命名/删除塞在长按弹窗，
 *   而且已装版和导入版还是两套按钮。现在入口只有一个（{@code VersionActivity}），实现只有这一份。
 *
 * <p>★ 每个改状态的方法都收一个 {@link Done}：调用方自己决定怎么刷新（版本页 = finish 回列表，
 *   列表自己会重扫）。这里**不碰**任何界面的列表状态。
 *
 * <p>★ 删除的语义只有一条（2026-10-25 用户定案）：
 *   「除了来自已安装的版本中的最后一个实例不能删除外，其他功能完全同步（不管来源）」——
 *   行数 &gt; 1 ⇒ 只删这一项（主行也能删，自动把另一项提上来）；只剩这一项 ⇒ 删整个版本；
 *   已装的版本**不允许**删到 0（那份在系统里，删了就成"删版本"了）。
 */
final class VersionOps {

    /** 改完之后通知调用方（版本页通常直接 finish，主界面自己重扫） */
    interface Done { void changed(); }

    /** 选了一个槽（可能是刚新建的） */
    interface SlotPick { void onPicked(String slot); }

    private VersionOps() {}

    // ── 小工具 ────────────────────────────────────────────────────────────

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    static void alert(Activity a, int titleRes, String msg) {
        if (Util.dead(a)) return;
        new AlertDialog.Builder(a)
                .setTitle(titleRes)
                .setMessage(msg)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    /** 这个槽里有几份存档 —— 与列表行、详情同一口径（`.msav`、跳过隐藏文件） */
    static int saveCountIn(Context c, String slot) {
        File dir = new File(Data.dirOf(c, slot), "saves");
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".msav")) continue;
            n++;
        }
        return n;
    }

    // ── 选槽（换槽与克隆共用同一份列表）──────────────────────────────────

    /**
     * 列一个槽清单；末项永远是「新建一个槽…」。
     *
     * <p>★ 2026-10-25 用户：「两个相同实例不能用一个槽，不能光在创建时拦截啊，切换页面也要删掉已有」
     *   ⇒ 列表里**不放**这个版本已经绑着的槽（除了"当前这一行的槽"，换槽时它要标 [当前]）。
     *   以前是"全列出来、点了再拒绝"—— 用户点了才被告知"已经在用"，等于让他在错误的列表里挑。
     *
     * @param includeCurrent true = 换槽（当前槽要留在表里并打勾）；false = 克隆（当前槽也不许选）
     */
    private static void slotList(final Activity a, final Versions.Entry e, boolean includeCurrent,
                                 String title, final SlotPick pick) {
        final List<Data.Slot> all = Data.allSlots(a);
        final List<Data.Slot> out = new ArrayList<>();
        final String cur = Versions.slotFor(e);
        final int curIndex;
        for (Data.Slot s : all) {
            if (includeCurrent && s.name.equals(cur)) out.add(s);
            else if (!Config.get().hasSlotBinding(e.key(), s.name)) out.add(s);
        }
        curIndex = includeCurrent ? indexOf(out, cur) : -1;
        final String[] names = new String[out.size() + 1];
        for (int i = 0; i < out.size(); i++) {
            Data.Slot s = out.get(i);
            names[i] = s.name + (s.active ? Trans.get(a, R.string.slot_current_suffix) : "")
                    + "  " + Trans.get(a, R.string.slot_entry_fmt, s.files, Util.formatSize(s.bytes));
        }
        names[out.size()] = Trans.get(a, R.string.binding_new_slot);
        // ⚠️ 这里**不能**同时 setMessage：本机（HONOR / Android 16）上"正文 + 列表"并存时
        //   列表**整块不渲染**（真机 dump 里连 ListView 都没有，只剩正文和取消）。
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setSingleChoiceItems(names, curIndex, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        if (w == out.size()) { newSlot(a, pick); return; }
                        pick.onPicked(out.get(w).name);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static int indexOf(List<Data.Slot> list, String name) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).name.equals(name)) return i;
        return -1;
    }

    /**
     * 当场新建一个空槽，然后交给调用方（换槽 = 把这一行换过去；克隆 = 加一项）。
     *
     * ★ 2026-10-25 用户：「换槽可以和克隆一样的（也加新建）」—— 两条路共用这一个对话框。
     */
    private static void newSlot(final Activity a, final SlotPick pick) {
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.new_slot_hint);
        int pad = dp(a, 20);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle(R.string.new_slot_title)
                .setMessage(R.string.new_slot_msg)
                .setView(box)
                .setPositiveButton(R.string.create, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String raw = input.getText().toString();
                        String err = Data.createSlot(a, raw);
                        if (err != null) {
                            alert(a, R.string.create_failed, err);
                            return;
                        }
                        String name = Data.sanitizeSlot(raw);
                        pick.onPicked(name == null ? raw.trim() : name);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── 换槽（只换这一行）────────────────────────────────────────────────

    /**
     * 给**这一行**换一个槽。
     *
     * <p>★ 主行的实际槽可能**从来没有显式分配过**（一直用默认槽）：此时拿 (key, 旧槽名) 去
     *   `replaceSlotBinding` 会返回"找不到" ⇒ 界面报"改不动"。所以主行一律 `setSlotOf`
     *   （写成显式分配），附加行才走替换。这个分岔在真机前抓过一次，别合并回去。
     */
    static void pickSlot(final Activity a, final Versions.Entry e, final Done done) {
        final String cur = Versions.slotFor(e);
        slotList(a, e, true, Trans.get(a, R.string.slot_pick_title_fmt, e.displayName(a, false)),
                new SlotPick() {
                    @Override public void onPicked(String want) {
                        if (want.equals(cur)) return;         // 选的就是当前槽：什么都不做
                        int r;
                        if (e.boundSlot == null) {
                            Config.get().setSlotOf(e.key(), want);
                            r = Config.BIND_OK;
                        } else {
                            r = Config.get().replaceSlotBinding(e.key(), e.boundSlot, want);
                        }
                        if (r == Config.BIND_TARGET_TAKEN) {
                            Toast.makeText(a, Trans.get(a, R.string.binding_taken_fmt, want),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (r != Config.BIND_OK) {
                            Toast.makeText(a, Trans.get(a, R.string.binding_failed),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(a, Trans.get(a, R.string.slot_set_fmt, want),
                                Toast.LENGTH_SHORT).show();
                        if (done != null) done.changed();
                    }
                });
    }

    // ── 克隆 / 再加一项 ───────────────────────────────────────────────────

    /**
     * 「克隆」：同一个版本再绑一个槽（列表里多一项）。
     *
     * <p>★ 为什么不复制任何东西：游戏本体（APK / dex / 资产）**共用同一份**，新的一项只是绑到
     *   另一个槽。需求原文：「复制一份又占地方」。存档也**不复制**（要连存档一起复制用「克隆此槽」）。
     */
    static void promptAddBinding(final Activity a, final Versions.Entry e, final Done done) {
        slotList(a, e, false, Trans.get(a, R.string.binding_add_title_fmt, e.displayName(a, false)),
                new SlotPick() {
                    @Override public void onPicked(String slot) {
                        boolean ok = Config.get().addExtraSlot(e.key(), slot);
                        // ⚠️ Toast 只给一行：带 \n 的第二行会被系统截成「…」（真机抓过），写了等于没写。
                        Toast.makeText(a, ok ? Trans.get(a, R.string.binding_added_fmt, slot)
                                        : Trans.get(a, R.string.binding_taken_fmt, slot),
                                Toast.LENGTH_SHORT).show();
                        if (ok && done != null) done.changed();
                    }
                });
    }

    // ── 命名 ──────────────────────────────────────────────────────────────

    /**
     * 给这个版本起名。落点只有一处：`Config.setNameOf(key, 名字)` —— 名字进列表行标题、
     * 版本页标题；**不动 APK 本身**。留空 / 点「恢复原名」都等于清除。
     *
     * <p>★ 2026-10-25 起**已装版本也能命名**（用户：「其他功能完全同步（不管来源）」）——
     *   改的只是我们的显示名，系统里那个包一个字都不动。
     */
    static void promptRename(final Activity a, final Versions.Entry e, final Done done) {
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(e.label);                       // 提示就是原名：留空=恢复它
        input.setText(e.hasCustomName() ? e.customName : "");
        input.setSelection(input.getText().length());
        int pad = dp(a, 20);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, 0, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle(R.string.version_name_title)
                .setMessage(R.string.version_name_msg)
                .setView(box)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        applyName(a, e, input.getText().toString(), done);
                    }
                })
                .setNeutralButton(R.string.version_name_reset, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        applyName(a, e, "", done);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void applyName(Activity a, Versions.Entry e, String name, Done done) {
        boolean clear = name == null || name.trim().isEmpty();
        Config.get().setNameOf(e.key(), clear ? "" : name.trim());
        Toast.makeText(a, clear ? a.getString(R.string.version_name_reset_done)
                        : Trans.get(a, R.string.version_name_saved_fmt, name.trim()),
                Toast.LENGTH_SHORT).show();
        if (done != null) done.changed();
    }

    // ── 详情（只读）───────────────────────────────────────────────────────

    /**
     * 版本详情的**只读**弹窗（动作已经不在这里了 —— 那是版本页的事）。
     *
     * <p>★ 标题/正文里显示的是**剥过构建前缀**的版本号 ⇒ 这里把原文还回来：剥前缀是有损显示，
     *   详情是唯一能对照"界面值从哪来"的地方。★ fork 的"基座版本"也必须说出来（它是
     *   "这个版本为什么和官方某一版不互相提示"的全部依据）—— 去标记 ≠ 去信息。
     * <p>★ MD5 与"架构 / 构建号"要**读整个包**（75 MB ⇒ 几百毫秒）：先把弹窗显示出来，算完再补。
     */
    static void showDetail(final Activity a, final Versions.Entry e) {
        String slotLine = Trans.get(a, R.string.detail_slot_line_fmt, Versions.slotFor(e))
                + Trans.get(a, R.string.detail_slot_hint);
        String rawLine = e.versionTrimmed()
                ? Trans.get(a, R.string.detail_raw_ver_fmt, e.rawVersion()) : "";
        List<Versions.Entry> one = new ArrayList<>();
        one.add(e);
        Versions.resolveUpstreamVersions(one, a);
        String up = e.upstreamNote();
        String upLine = (up != null) ? Trans.get(a, R.string.detail_upstream_fmt, up) : "";
        String nameLine = e.origNameLine(a);
        final String head = e.subtitle(a) + nameLine + rawLine + upLine + slotLine
                + a.getString(e.imported ? R.string.detail_imported_note : R.string.detail_installed_note);
        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(e.displayName(a))
                .setMessage(head + Trans.get(a, R.string.detail_probing))
                .setPositiveButton(R.string.close, null)
                .create();
        dlg.show();
        new Thread(new Runnable() {
            @Override public void run() {
                final String detail = probeDetail(a, e);
                final String facts = slotFacts(a, e);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!a.isFinishing() && dlg.isShowing()) dlg.setMessage(head + facts + detail);
                    }
                });
            }
        }, "version-detail").start();
    }

    /** 详情里"这个槽"那两行（后台线程）：存档份数 + 上次玩（读 config 那笔记账） */
    private static String slotFacts(Activity a, Versions.Entry e) {
        int n = saveCountIn(a, Versions.slotFor(e));
        StringBuilder sb = new StringBuilder();
        sb.append(n > 0 ? Trans.get(a, R.string.detail_saves_fmt, n)
                : Trans.get(a, R.string.detail_saves_none));
        long at = Config.get().lastPlayed(e.key());
        sb.append(at > 0 ? Trans.get(a, R.string.detail_played_fmt, Trash.timeText(at))
                : Trans.get(a, R.string.detail_played_none));
        return sb.toString();
    }

    /** 详情里"必须读包才知道"的事实（后台线程）；读不出来就说读不出来，不猜 */
    private static String probeDetail(Activity a, Versions.Entry e) {
        StringBuilder sb = new StringBuilder();
        String md5 = (e.md5 != null && !e.md5.isEmpty()) ? e.md5 : Util.md5(new File(e.apkPath));
        sb.append(Trans.get(a, R.string.detail_md5_fmt, md5 == null || md5.isEmpty() ? "?" : md5));
        String abis = "";
        try {
            abis = TextUtils.join("、", Compat.probe(new File(e.apkPath)).apkAbis);
        } catch (Throwable ignored) {
        }
        sb.append(Trans.get(a, R.string.detail_abi_fmt,
                abis.isEmpty() ? Trans.get(a, R.string.detail_abi_unknown) : abis));
        int code = 0;
        try {
            if (e.imported) {
                android.content.pm.PackageInfo pi =
                        a.getPackageManager().getPackageArchiveInfo(e.apkPath, 0);
                if (pi != null) code = pi.versionCode;
            } else {
                code = a.getPackageManager().getPackageInfo(e.pkg, 0).versionCode;
            }
        } catch (Throwable ignored) {
        }
        if (code > 0) sb.append(Trans.get(a, R.string.detail_build_fmt, code));
        return sb.toString();
    }

    // ── 资源 ──────────────────────────────────────────────────────────────

    /** 从版本页打开「游戏资源」。只传定位信息（APK 路径 + 显示名 + 显示版本号）。 */
    static void openResources(Activity a, Versions.Entry e) {
        if (e == null || e.apkPath == null || e.apkPath.trim().isEmpty()) {
            Toast.makeText(a, R.string.resources_unreadable, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(a, ResourcesActivity.class);
        i.putExtra(ResourcesActivity.EXTRA_APK, e.apkPath);
        i.putExtra(ResourcesActivity.EXTRA_NAME, e.displayName(a));
        i.putExtra(ResourcesActivity.EXTRA_VERSION, e.displayVersion());
        a.startActivity(i);
    }

    // ── 删除 ──────────────────────────────────────────────────────────────

    /**
     * 删除（唯一入口）。范围由**行数**决定，不按来源分岔：
     * <ul>
     *   <li>还有别的项 ⇒ 只删这一项（确认框说清"其他项与存档都不动"）；</li>
     *   <li>这是最后一项、且是**导入**版 ⇒ 删整个版本：本体进**中转站**（能放回）；</li>
     *   <li>这是最后一项、且是**已装**版 ⇒ **不许删**（这份在系统里）—— 说明原因，不给动作。</li>
     * </ul>
     */
    static void delete(final Activity a, final Versions.Entry e, final Done done) {
        final int rows = Versions.rowCount(e.key());
        if (rows > 1) {
            // ★ 槽名先抓下来：删完这一项之后 `slotFor` 可能已经变了（主行被删 ⇒ 另一项提上来）
            final String slot = Versions.slotFor(e);
            new AlertDialog.Builder(a)
                    .setTitle(R.string.delete_row_title)
                    .setMessage(Trans.get(a, R.string.delete_row_msg_fmt, slot))
                    .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            if (!Config.get().removeBinding(e.key(), slot)) {
                                Toast.makeText(a, R.string.binding_failed, Toast.LENGTH_LONG).show();
                                return;
                            }
                            Toast.makeText(a, Trans.get(a, R.string.delete_row_done_fmt, slot),
                                    Toast.LENGTH_SHORT).show();
                            if (done != null) done.changed();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        if (!e.imported) {
            alert(a, R.string.delete_installed_last_title, a.getString(R.string.delete_installed_last_msg));
            return;
        }
        // 最后一项 + 导入版 = 删整个版本：本体进中转站（确认框里必须说清，用户 2026-10-25 要求）
        new AlertDialog.Builder(a)
                .setTitle(R.string.delete_version_title)
                .setMessage(Trans.get(a, R.string.delete_version_msg_fmt, e.displayName(a)))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        trashVersionAsync(a, e, done);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 本体是几十 MB 的跨分区搬运 ⇒ 后台线程 + 前台服务；回来再报结果 */
    private static void trashVersionAsync(final Activity a, final Versions.Entry e, final Done done) {
        Work.begin(a, R.string.delete_version_title);
        new Thread(new Runnable() {
            @Override public void run() {
                final Trash.Result r = Importer.trashVersion(a, e);
                Work.end(a);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (Util.dead(a)) return;
                        if (r == null || !r.ok) {
                            Toast.makeText(a, R.string.version_delete_failed, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(a, Trans.get(a, R.string.version_deleted_fmt, e.displayName(a)),
                                Toast.LENGTH_LONG).show();
                        if (done != null) done.changed();
                    }
                });
            }
        }, "version-trash").start();
    }
}
