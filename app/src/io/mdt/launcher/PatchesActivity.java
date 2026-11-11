package io.mdt.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 「补丁」页（第 345 轮，补丁机制 v1）：**某个版本**能用的补丁 —— 导入 / 启用 / 停用 / 删除。
 *
 * <h3>为什么按版本进、不做一个全局补丁页</h3>
 * 补丁按**目标 APK 的 sha256** 精确匹配（见 {@link PatchManifest} 类注释）⇒ "这个补丁能用"
 * 本身就是**相对某个版本**说的。放在版本页里，用户看到的永远是"这一版能用什么"；
 * 一个全局列表反而要在每一行额外解释"它适用于哪几版"。
 *
 * <h3>状态词（判据只有一处：{@link #stateOf}）</h3>
 * <ul>
 *   <li><b>已启用</b>：这个版本 key 的启用列表里有它；</li>
 *   <li><b>未启用</b>：有匹配这个 APK 的 target，但没启用；</li>
 *   <li><b>不适用这个 APK</b>：包里的 target 都不匹配当前 APK（游戏版本变了）—— 强行启用会被拒；</li>
 *   <li><b>文件缺失</b>：dex / 素材被外部删了（记录留着让用户看得见、删得掉）。</li>
 * </ul>
 *
 * <h3>数据流程</h3>
 * 后台线程：找 Entry → 算 APK sha256 → 扫补丁库 → 读启用列表 / 启动报告 → 解析每个 target 的
 * 类数 / 素材数 → 回 UI 线程渲染。**解析 12k 个类的 dex 也在后台**（大补丁不能卡界面）。
 *
 * <p>页面只做"调用"，判据全在 {@link Patches}（导入 / 冲突 / 启用 / 删除都只有一份实现）——
 * 这与"同一件事两条路"的纪律一致：dev 口（{@code dev_patch_*}）走的是同一批函数。
 */
public final class PatchesActivity extends BaseActivity {

    /** 版本 key（`Versions.Entry#key()`；与版本页传参同一套） */
    static final String EXTRA_KEY = "v_key";
    /**
     * ★ 第 353 轮（用户：「游戏补丁的启用状态应该绑定实例吧」）：这个补丁页属于哪个**启动项**。
     *
     * <p>启用状态按 {@link Versions#instanceKey}（版本 key + 槽）存 —— 同一个版本绑多个槽时，
     * 每行（启动项）各开各的补丁。不传 = 空串（旧调用方 / 直达 intent）⇒ 只能看到"没有启用"的视图，
     * 不会误改到别的实例。
     */
    static final String EXTRA_SLOT = "v_slot";

    // 行状态（显示用；判据在 stateOf）
    static final int STATE_OFF = 0;
    static final int STATE_ON = 1;
    static final int STATE_NA = 2;
    static final int STATE_BROKEN = 3;

    private static final String TAG = "MDTLauncher";
    private static final int REQ_PICK = 6101;

    private String mKey = "";
    /** 这个补丁页对应的实例槽（空 = 未知；见 {@link #EXTRA_SLOT}） */
    private String mSlot = "";
    /** 实例键（{@link Versions#instanceKey}）—— 启用状态的真正键 */
    private String mIkey = "";
    private String mApkSha = "";
    private int mLoadGen = 0;
    private boolean mBusy;

    private TextView mHead;
    private TextView mSub;
    private TextView mEmpty;
    private TextView mEmptySub;
    private TextView mProblemsTitle;
    private TextView mProblemsBody;
    private LinearLayout mList;
    private LinearLayout mProblemsCard;

    /**
     * 一行的全部渲染数据（**后台线程算好**，UI 线程只负责画 —— 解析大 dex 不能卡界面）。
     *
     * <p>详情卡只展示**元信息**（标识 / 作者 / 目标版本 / 数量 / 体积 / 工具 / 导入时间 / 说明），
     * **不列类名**：那是给生成器 / 排错看的，对使用者既读不懂也判断不了什么
     * （2026-11-10 用户反馈：「详情页面不建议写替换哪些类，应该是补丁的元信息」）。
     */
    private static final class Row {
        final Patches.Record record;
        final boolean enabled;
        final int state;
        /** 与当前 APK 匹配的 target；没有匹配时回落到包里第一个（仅用于展示元信息）。 */
        final Patches.TargetFile target;
        /** target 是否匹配当前 APK（行副标题里的类数只对匹配的 target 有意义）。 */
        final boolean targetMatches;
        final int classCount;
        final int assetCount;
        final long dexBytes;
        final long assetsBytes;
        final long importedAt;

        Row(Patches.Record record, boolean enabled, int state, Patches.TargetFile target,
            boolean targetMatches, int classCount, int assetCount, long dexBytes, long assetsBytes,
            long importedAt) {
            this.record = record;
            this.enabled = enabled;
            this.state = state;
            this.target = target;
            this.targetMatches = targetMatches;
            this.classCount = classCount;
            this.assetCount = assetCount;
            this.dexBytes = dexBytes;
            this.assetsBytes = assetsBytes;
            this.importedAt = importedAt;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_patches);
        Util.applySystemInsets(findViewById(R.id.root));
        setTitle(Trans.get(this, R.string.patch_page_title));

        String k = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_KEY);
        mKey = k == null ? "" : k;
        String sl = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SLOT);
        mSlot = sl == null ? "" : sl;
        mIkey = Versions.instanceKey(mKey, mSlot);
        if (mKey.isEmpty()) {
            finish();
            return;
        }

        mHead = (TextView) findViewById(R.id.patch_head_title);
        mSub = (TextView) findViewById(R.id.patch_head_sub);
        mEmpty = (TextView) findViewById(R.id.patch_empty);
        mEmptySub = (TextView) findViewById(R.id.patch_empty_sub);
        mProblemsTitle = (TextView) findViewById(R.id.patch_problems_title);
        mProblemsBody = (TextView) findViewById(R.id.patch_problems_body);
        mProblemsCard = (LinearLayout) findViewById(R.id.patch_problems_card);
        mList = (LinearLayout) findViewById(R.id.patch_list);

        Util.bindAction(findViewById(R.id.root), R.id.row_patch_import, R.drawable.ic_upload,
                R.string.patch_import, R.string.patch_import_sub, new Runnable() {
                    @Override public void run() {
                        pickPackage();
                    }
                });

        // 点问题卡 = "知道了"（把上次启动的报告清掉，别让它永远挂着）
        mProblemsCard.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Patches.clearLaunchReport(PatchesActivity.this);
                mProblemsCard.setVisibility(View.GONE);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    // ══ 读取 / 渲染 ═════════════════════════════════════════════════════════

    /** 后台重扫；`mLoadGen` 挡掉"旧线程晚到"的覆盖（SAF 回来 + onResume 会并发触发）。 */
    private void reload() {
        final int gen = ++mLoadGen;
        final String key = mKey;
        final String slot = mSlot;
        final String ikey = mIkey;
        new Thread(new Runnable() {
            @Override public void run() {
                // 清孤儿引用（手工清过盘 / 旧版本数据）；不动别的东西
                Patches.pruneStale(PatchesActivity.this);
                Versions.Entry entry = null;
                for (Versions.Entry e : Versions.scanAll(PatchesActivity.this)) {
                    if (e.key().equals(key) && Versions.slotFor(e).equals(slot)) { entry = e; break; }
                }
                if (entry == null) {
                    uiGone(gen);
                    return;
                }
                File apk = entry.apkPath == null ? null : new File(entry.apkPath);
                String sha = "";
                if (apk != null && apk.isFile()) {
                    try {
                        sha = Patches.sha256Of(apk);
                    } catch (Throwable t) {
                        Log.w(TAG, "patch apk hash failed: " + apk, t);
                    }
                }
                List<Patches.Record> records = Patches.scan(PatchesActivity.this);
                List<String> enabled = Patches.enabledRefs(PatchesActivity.this, ikey);
                List<Row> rows = new ArrayList<Row>(records.size());
                for (Patches.Record r : records) {
                    boolean on = enabled.contains(r.ref());
                    int state = stateOf(r, sha, on);
                    Patches.TargetFile match = r.targetFor(sha);
                    Patches.TargetFile show = match != null ? match
                            : (r.targets.isEmpty() ? null : r.targets.get(0));
                    int classes = 0, assets = 0;
                    long dexBytes = 0, assetsBytes = 0;
                    if (show != null) {
                        File dex = show.dexFile();
                        File as = show.assetsFile();
                        if (dex != null && dex.isFile()) dexBytes = dex.length();
                        if (as != null && as.isFile()) assetsBytes = as.length();
                        if (r.filesOk) {
                            try {
                                classes = show.classCount();
                            } catch (Throwable ignored) {
                            }
                            assets = show.assetCount();
                        }
                    }
                    File mf = new File(r.dir, PatchManifest.FILE);
                    long importedAt = mf.lastModified();
                    rows.add(new Row(r, on, state, show, match != null,
                            classes, assets, dexBytes, assetsBytes, importedAt));
                }
                List<Patches.Problem> problems = Patches.launchProblems(PatchesActivity.this);
                ui(gen, entry, sha, rows, problems);
            }
        }, "patches-load").start();
    }

    /** 状态判据（唯一实现；列表 / 详情 / 副标题都读它）。 */
    static int stateOf(Patches.Record r, String apkSha, boolean enabled) {
        if (!r.filesOk) return STATE_BROKEN;
        if (r.targetFor(apkSha) == null) return STATE_NA;
        return enabled ? STATE_ON : STATE_OFF;
    }

    private void uiGone(final int gen) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (gen != mLoadGen || isFinishing()) return;
                finish();
            }
        });
    }

    private void ui(final int gen, final Versions.Entry entry, final String sha,
                    final List<Row> rows, final List<Patches.Problem> problems) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (gen != mLoadGen || isFinishing()) return;
                mApkSha = sha;

                mHead.setText(Trans.get(PatchesActivity.this, R.string.patch_head_fmt,
                        entry.displayName(PatchesActivity.this)));
                String shortSha = sha.isEmpty() ? "-" : Patches.shortHash(sha);
                mSub.setText(Trans.get(PatchesActivity.this, R.string.patch_head_sub_fmt,
                        shortSha, rows.size())
                        + (mSlot.isEmpty() ? ""
                        : "\n" + Trans.get(PatchesActivity.this, R.string.patch_head_instance_fmt, mSlot)));

                mList.removeAllViews();
                LayoutInflater inf = getLayoutInflater();
                for (final Row row : rows) {
                    View item = inf.inflate(R.layout.item_patch, mList, false);
                    ((TextView) item.findViewById(R.id.patch_item_title)).setText(
                            Trans.get(PatchesActivity.this, R.string.patch_detail_title_fmt,
                                    row.record.label(), row.record.version()));
                    ((TextView) item.findViewById(R.id.patch_item_sub)).setText(
                            Trans.get(PatchesActivity.this, R.string.patch_row_sub_fmt,
                                    row.targetMatches ? row.classCount : 0,
                                    PatchText.state(PatchesActivity.this, row.state)));
                    item.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            showDetail(row);
                        }
                    });
                    mList.addView(item);
                }

                boolean empty = rows.isEmpty();
                mEmpty.setText(Trans.get(PatchesActivity.this, R.string.patch_empty));
                mEmptySub.setText(Trans.get(PatchesActivity.this, R.string.patch_empty_sub));
                mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                mEmptySub.setVisibility(empty ? View.VISIBLE : View.GONE);

                if (problems == null || problems.isEmpty()) {
                    mProblemsCard.setVisibility(View.GONE);
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (Patches.Problem p : problems) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(Trans.get(PatchesActivity.this, R.string.patch_problem_line_fmt,
                                PatchText.problem(PatchesActivity.this, p)));
                    }
                    // 卡片可点 = "知道了"；把提示写在卡上（B7：不能让人猜这里能点）
                    sb.append('\n').append(Trans.get(PatchesActivity.this,
                            R.string.patch_last_launch_dismiss));
                    mProblemsTitle.setText(Trans.get(PatchesActivity.this,
                            R.string.patch_last_launch_title));
                    mProblemsBody.setText(sb.toString());
                    mProblemsCard.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    // ══ 详情卡（启用 / 停用 / 删除 / 类清单）══════════════════════════════════

    /**
     * 详情卡：**补丁的元信息**（不列类名 —— 2026-11-10 用户反馈）。
     *
     * <p>使用者要回答的问题依次是"这是什么 / 谁做的 / 给哪个版本 / 多大 / 什么时候导入的"，
     * 而不是"它替换了哪些类"（那既读不懂、也判断不了；真要排查看运行日志里的注入行）。
     */
    private void showDetail(final Row row) {
        final CardStack stack = new CardStack(this);
        final Patches.Record rec = row.record;
        final PatchManifest m = rec.manifest;
        StringBuilder body = new StringBuilder();
        body.append(Trans.get(this, R.string.patch_detail_id_fmt, m.id));
        if (m.author != null && !m.author.isEmpty()) {
            body.append('\n').append(Trans.get(this, R.string.patch_detail_author_fmt, m.author));
        }
        if (row.target != null) {
            String gv = row.target.spec.gameVersion;
            body.append('\n').append(Trans.get(this, R.string.patch_detail_target_fmt,
                    gv == null || gv.isEmpty() ? "-" : gv,
                    Patches.shortHash(row.target.spec.apkSha256)));
        }
        body.append('\n').append(Trans.get(this, R.string.patch_detail_content_fmt,
                row.classCount, row.assetCount));
        body.append('\n').append(Trans.get(this, R.string.patch_detail_size_fmt,
                humanBytes(row.dexBytes + row.assetsBytes)));
        if (m.tool != null && !m.tool.isEmpty()) {
            body.append('\n').append(Trans.get(this, R.string.patch_detail_tool_fmt, m.tool));
        }
        if (row.importedAt > 0) {
            body.append('\n').append(Trans.get(this, R.string.patch_detail_imported_fmt,
                    formatTime(row.importedAt)));
        }
        if (m.note != null && !m.note.isEmpty()) {
            body.append(Trans.get(this, R.string.patch_detail_note_fmt, m.note));
        }
        if (row.state == STATE_BROKEN) {
            body.append('\n').append(PatchText.reason(this, PatchException.C_MISSING, rec.label()));
        } else if (row.state == STATE_NA) {
            body.append('\n').append(PatchText.reason(this, PatchException.C_NO_MATCH, rec.label()));
        }
        CardStack.Btn close = new CardStack.Btn(Trans.get(this, R.string.patch_close), new Runnable() {
            @Override public void run() { stack.dismiss(); }
        });
        CardStack.Btn del = new CardStack.Btn(Trans.get(this, R.string.patch_delete), new Runnable() {
            @Override public void run() { confirmDelete(stack, row); }
        });
        CardStack.Btn toggle = new CardStack.Btn(
                Trans.get(this, row.enabled ? R.string.patch_disable : R.string.patch_enable),
                new Runnable() {
                    @Override public void run() {
                        if (row.enabled) doDisable(stack, row);
                        else startEnable(stack, row);
                    }
                });
        stack.root(Trans.get(this, R.string.patch_detail_title_fmt,
                        rec.label(), rec.version()),
                CardStack.text(this, body.toString()), close, del, toggle);
        stack.show();
    }

    private void startEnable(final CardStack stack, final Row row) {
        // "这会替换游戏代码"只告知一次（全局看一次就够；每次启用都弹就是骚扰）
        if (!Patches.warned(this)) {
            stack.push(Trans.get(this, R.string.patch_warn_title),
                    CardStack.text(this, Trans.get(this, R.string.patch_warn_body)),
                    new CardStack.Btn(Trans.get(this, R.string.patch_cancel), new Runnable() {
                        @Override public void run() { stack.pop(); }
                    }),
                    new CardStack.Btn(Trans.get(this, R.string.patch_warn_ok), new Runnable() {
                        @Override public void run() {
                            Patches.markWarned(PatchesActivity.this);
                            doEnable(stack, row);
                        }
                    }));
            return;
        }
        doEnable(stack, row);
    }

    /**
     * 启用（B6：放后台）。冲突检测要读**已启用补丁的 dex**（MindustryY 那种 1.2 万类 / 8.5 MB），
     * 放 UI 线程会卡白屏；这里换成"弹卡 → 后台算 → 回 UI 报结果"，与导入/删除同一个模式。
     */
    private void doEnable(final CardStack stack, final Row row) {
        if (mBusy) return;
        mBusy = true;
        stack.replace(Trans.get(this, R.string.patch_enable), row.record.label());
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    Patches.enable(PatchesActivity.this, mIkey, row.record, mApkSha);
                } catch (PatchException e) {
                    err = PatchText.reason(PatchesActivity.this, e);
                } catch (Throwable t) {
                    err = Util.ioReason(PatchesActivity.this, t,
                            Trans.get(PatchesActivity.this, R.string.patch_op_failed));
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mBusy = false;
                        if (isFinishing()) return;
                        stack.dismiss();
                        if (ferr == null) {
                            android.widget.Toast.makeText(PatchesActivity.this,
                                    Trans.get(PatchesActivity.this, R.string.patch_enable_ok_fmt,
                                            row.record.label()),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            reload();
                        } else {
                            final CardStack done = new CardStack(PatchesActivity.this);
                            done.replace(Trans.get(PatchesActivity.this, R.string.patch_op_failed),
                                    ferr,
                                    new CardStack.Btn(Trans.get(PatchesActivity.this,
                                            R.string.patch_close), new Runnable() {
                                                @Override public void run() { done.dismiss(); }
                                            }));
                        }
                    }
                });
            }
        }, "patches-enable").start();
    }

    private void doDisable(final CardStack stack, final Row row) {
        Patches.disable(this, mIkey, row.record);
        stack.dismiss();
        android.widget.Toast.makeText(this,
                Trans.get(this, R.string.patch_disable_ok_fmt, row.record.label()),
                android.widget.Toast.LENGTH_SHORT).show();
        reload();
    }

    private void confirmDelete(final CardStack stack, final Row row) {
        stack.push(Trans.get(this, R.string.patch_delete_title),
                CardStack.text(this, Trans.get(this, R.string.patch_delete_body_fmt,
                        row.record.label())),
                new CardStack.Btn(Trans.get(this, R.string.patch_cancel), new Runnable() {
                    @Override public void run() { stack.pop(); }
                }),
                new CardStack.Btn(Trans.get(this, R.string.patch_delete_ok), new Runnable() {
                    @Override public void run() { doDelete(stack, row); }
                }));
    }

    private void doDelete(final CardStack stack, final Row row) {
        if (mBusy) return;
        mBusy = true;
        stack.replace(Trans.get(this, R.string.patch_deleting), row.record.label());
        Work.begin(this, R.string.patch_deleting);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    Patches.delete(PatchesActivity.this, row.record);
                } catch (PatchException e) {
                    err = PatchText.reason(PatchesActivity.this, e);
                } catch (Throwable t) {
                    err = Util.ioReason(PatchesActivity.this, t,
                            Trans.get(PatchesActivity.this, R.string.patch_import_failed));
                } finally {
                    Work.end(PatchesActivity.this);
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mBusy = false;
                        if (isFinishing()) return;
                        stack.dismiss();
                        if (ferr == null) {
                            android.widget.Toast.makeText(PatchesActivity.this,
                                    Trans.get(PatchesActivity.this, R.string.patch_delete_done_fmt,
                                            row.record.label()),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            reload();
                        } else {
                            final CardStack done = new CardStack(PatchesActivity.this);
                            done.replace(Trans.get(PatchesActivity.this, R.string.patch_op_failed),
                                    ferr,
                                    new CardStack.Btn(Trans.get(PatchesActivity.this, R.string.patch_close),
                                            new Runnable() {
                                                @Override public void run() { done.dismiss(); }
                                            }));
                        }
                    }
                });
            }
        }, "patches-delete").start();
    }

    // ══ 导入（SAF）══════════════════════════════════════════════════════════

    private void pickPackage() {
        if (mBusy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // ⚠️ MIME 一律 */*：各家文件管理器对 .mdp / .zip 报的 MIME 五花八门，限死会让用户选不中
        //（与 SlotIo / BatchIo 的单份导入同一条理由）。
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i,
                Trans.get(this, R.string.patch_import_chooser)), REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = BatchIo.displayNameOf(this, uri);
        importFrom(uri, name);
    }

    /** 导入一段 SAF 流：先落到 cache 的临时文件，再交给 {@link Patches#importPackage}（重 IO 在后台）。 */
    private void importFrom(final Uri uri, final String name) {
        if (mBusy) return;
        mBusy = true;
        final CardStack stack = new CardStack(this);
        stack.replace(Trans.get(this, R.string.patch_importing), name);
        Work.begin(this, R.string.patch_importing);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                Patches.Record rec = null;
                File tmp = new File(getCacheDir(), "patch-import.tmp");
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("no stream");
                    Util.copyStream(in, tmp);
                    rec = Patches.importPackage(PatchesActivity.this, tmp);
                } catch (PatchException e) {
                    err = PatchText.reason(PatchesActivity.this, e);
                } catch (Throwable t) {
                    err = Util.ioReason(PatchesActivity.this, t,
                            Trans.get(PatchesActivity.this, R.string.patch_import_failed));
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                    if (tmp.exists()) tmp.delete();
                    Work.end(PatchesActivity.this);
                }
                final String ferr = err;
                final Patches.Record frec = rec;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mBusy = false;
                        if (isFinishing()) return;
                        stack.dismiss();
                        if (ferr == null && frec != null) {
                            android.widget.Toast.makeText(PatchesActivity.this,
                                    Trans.get(PatchesActivity.this, R.string.patch_import_ok_fmt,
                                            frec.label(), frec.version()),
                                    android.widget.Toast.LENGTH_LONG).show();
                            reload();
                        } else {
                            final CardStack done = new CardStack(PatchesActivity.this);
                            done.replace(Trans.get(PatchesActivity.this, R.string.patch_import_failed),
                                    ferr == null ? "" : ferr,
                                    new CardStack.Btn(Trans.get(PatchesActivity.this, R.string.patch_close),
                                            new Runnable() {
                                                @Override public void run() { done.dismiss(); }
                                            }));
                        }
                    }
                });
            }
        }, "patches-import").start();
    }

    // ══ 小工具 ══════════════════════════════════════════════════════════════

    /** 人类可读的体积（只用于详情卡元信息；不参与任何判据）。 */
    private static String humanBytes(long bytes) {
        java.util.Locale loc = java.util.Locale.getDefault();
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(loc, "%.0f KB", bytes / 1024.0);
        return String.format(loc, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 导入时间（读 `patch.json` 的 mtime = 落盘时刻；格式化给详情卡）。 */
    private static String formatTime(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                java.util.Locale.getDefault()).format(new java.util.Date(ms));
    }

    /** 进补丁页的 Intent —— 抽出来只为让自检能钉住"版本 key 带过去了"（写错键编译器不响）。 */
    static Intent intent(Context c, String vkey) {
        return new Intent(c, PatchesActivity.class)
                .putExtra(PatchesActivity.EXTRA_KEY, vkey == null ? "" : vkey);
    }
}
