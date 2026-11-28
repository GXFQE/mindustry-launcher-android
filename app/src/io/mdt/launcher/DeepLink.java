package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * F9 深链：外部（浏览器 / 文件管理器 / 任何 App）把一份文件交给启动器时，
 * **只在这里决定"这该进哪一页"**；导入本身仍然走各页原有的那一份实现
 * （{@link BatchIo#wantsBatch} + 各页自己的 {@code importDocs}）。
 *
 * <h3>🔴 为什么那三组 intent-filter 不挂在游戏那个 Activity 上（与 `docs/flows/07` 的初稿相反）</h3>
 * 官方 APK 的 filter 是挂在 `mindustry.android.AndroidLauncher` 上的，而**那个类不在我们的 APK 里**：
 * 它是 `:game` 进程跑完 {@link Injector} 六步、把游戏 dex 追加进**那个进程的 base ClassLoader**
 * 之后才存在的（{@link Injector#launch}）。冷启动的深链 = 系统新起一个**干净的** `:game` 进程，
 * `ActivityThread` 拿自己那份 `PathClassLoader` 去实例化它 ⇒ `ClassNotFoundException`
 * （"Unable to instantiate activity"）—— 用户看到的是闪退，连"卡在哪一步"都没有。
 * ⇒ 本工程的形状是：**filter 挂我们自己的入口（MainActivity，主进程、已经在 exported=true）**，
 * 由本类分派到四个内容页（整槽包另算：它进**槽页**、走「导入整槽」那条既有流程）；游戏那个 Activity 一个 filter 都不加。
 *
 * <h3>判据分两层（都能单独钉）</h3>
 * <ol>
 *   <li>{@link #kindFor} —— **纯函数**（名字 + mime + 头 4 字节），自检直接喂三样东西；</li>
 *   <li>{@link BatchIo#guessKind} —— 压缩包才需要：**读一遍内容**才知道装的是哪一类
 *       （名字 + 内容都认不出 ⇒ 一句"包里没有可导入的东西"）。</li>
 * </ol>
 *
 * <h3>安全面（`exported` + VIEW/BROWSABLE 的代价）</h3>
 * 挂上 filter 之后**任何 App 都能给我们塞数据**。收口三条：
 * ① 只认 **content / file** 两种 scheme（http 之类要联网，不接）；
 * ② 落点槽**只由界面上的那次选择决定**（第 321 轮起是先问「导入到哪个槽？」；只有一个槽就不问）——
 *    **不从 Intent 里读槽名**：外部塞 `slot` extra **不认**，所以"能塞数据"不等于"能往任意槽写"，
 *    用户看见的永远是"我选的哪个槽"；
 * ③ 导入本身走的还是各页那条既有路径（zlib 魔数校验 / 大小闸 / 同名走中转站），
 *    不假设数据可信（真正的校验在 `MapFiles` / `BlueprintFiles` / `Mods` 里）。
 */
final class DeepLink {

    /**
     * 交给页面那条路用的一对 extra：**只由本类写、只由四个内容页读**。
     * ⚠️ 外部 App 也能伪造它们（MainActivity 是 exported）—— 但那没有多出任何本事：
     *   他要塞的 Uri 反正也能直接走 VIEW 那条路进来（见类注释的安全面）。
     */
    static final String EXTRA_URI = "mdt_deeplink_uri";
    static final String EXTRA_NAME = "mdt_deeplink_name";
    /**
     * ★ 第 380 轮：**分派到的是哪一类**（{@code BatchIo.KIND_*}）。
     *
     * <p>为什么需要：槽页现在收**两种**深链（整槽包 / 混合包），页内必须知道走哪条流程
     * —— 两条都是"写这个槽"，但一条是**整槽写入**、一条是"四类各进各的目录"。
     * ⚠️ 键写错编译器不响，真机只表现为"整槽包被当成混合包拆"（那就是丢数据级别的错）。
     */
    static final String EXTRA_KIND = "mdt_deeplink_kind";

    /** {@link #kindFor} 的返回值：是压缩包，**读过内容**才知道是哪一类 */
    static final int PROBE = -2;
    /** {@link #kindFor} 的返回值：认不出 */
    static final int UNKNOWN = -1;
    /**
     * ★ 第 436 轮：`.apk` 游戏本体 —— 不走内容页，交给宿主那条「添加游戏」
     * （它本来就有进度卡 / 签名询问 / 兼容性探测），选好的槽在导入成功后落到这一行上。
     */
    static final int KIND_GAME = -3;

    private DeepLink() {
    }

    /**
     * 这个 Intent 是不是"外部递进来的文件"？—— 是就接住并分派（返回 true）。
     *
     * <p>判据三条：`ACTION_VIEW` + 有 data + scheme 是 **content / file**。
     * 其余一律不接（http 那类要联网下载，不是深链的活）。
     */
    static boolean claim(Activity a, Intent it) {
        if (it == null || !Intent.ACTION_VIEW.equals(it.getAction())) return false;
        Uri uri = it.getData();
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"content".equals(scheme) && !"file".equals(scheme)) {
            alert(a, BatchIo.displayNameOf(a, uri), R.string.deeplink_unrecognized);
            return true;
        }
        final BatchIo.Doc doc = new BatchIo.Doc(uri, BatchIo.displayNameOf(a, uri));

        // 头 4 字节：名字认不出时靠它认容器（zip / zlib / gzip / 'msch'）。
        // ⚠️ 读不到（没授权 / 文件没了 / scoped storage 挡着）⇒ **如实说打不开**，
        //   别硬往下走 —— 走下去各页只会报一句更绕的话（"这不是有效的存档"）。
        byte[] head = headOf(a, uri);
        if (head == null) {
            alert(a, doc.name, R.string.deeplink_open_failed);
            return true;
        }
        int kind = kindFor(doc.name, it.getType(), head);
        if (kind == UNKNOWN) {
            alert(a, doc.name, R.string.deeplink_unrecognized);
            return true;
        }
        // ★ 第 437 轮（用户：「补丁为啥不能自动识别」）：`.apk` 先开箱看一眼再决定走哪条路。
        //   补丁 v2 容器就是一份 APK（根上 patch.json + classes.dex），后缀 / mime 与游戏本体一样，
        //   只有打开 zip 才分得开 ⇒ 拷进 app_hub/tmp 看一眼（判据见 PatchPackage.isApkContainer）。
        if (kind == KIND_GAME) {
            preflightApk(a, doc);
            return true;
        }
        if (kind != PROBE) {
            // ★ 第 380 轮（用户「导入前对识别情况给个确认」）：后缀就已经定类的这几种，
            //   也让用户先看一眼"我们认成什么、导到哪个槽"。
            //   ★ `.msav` 是地图/存档同一个容器 ⇒ 顺手读一下 meta（有 `name` 是地图），
            //     这样确认卡上说的就是真的，而不是"一律当地图"。
            if (kind == BatchIo.KIND_MAPS) kind = BatchIo.sniffMsav(a, doc, kind);
            open(a, kind, doc, detailOf(a, kind, null));
            return true;
        }
        // 压缩包：装的是模组 / 地图 / 存档 / 蓝图，只有读过内容才知道 ⇒ 后台读一遍再问
        // ★ 第 270 轮：读包这段用卡片（原来一个 ProgressDialog）
        final CardStack stack = new CardStack(a);
        stack.replace(Trans.get(a, R.string.deeplink_reading), doc.name);
        new Thread(new Runnable() {
            @Override public void run() {
                final BatchIo.Summary s = BatchIo.summarize(a, doc);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        stack.dismiss();
                        if (Util.dead(a)) return;
                        // ★ 第 379 轮：**多个整槽 / 整槽与别的东西混装** = 未定义行为 ⇒ 明说一句、不猜
                        if (s.kind == BatchIo.KIND_SLOT_AMBIGUOUS) {
                            alert(a, doc.name, R.string.deeplink_slot_ambiguous);
                        } else if (s.kind < 0) {
                            alert(a, doc.name, R.string.deeplink_zip_empty);
                        } else {
                            open(a, s.kind, doc, detailOf(a, s.kind, s));
                        }
                    }
                });
            }
        }, "deeplink-probe").start();
        return true;
    }

    // ══ 补丁包识别（第 437 轮）════════════════════════════════════════════════

    /**
     * `.apk` 的**开箱预检**：是补丁包 ⇒ 转补丁那条路；不是 ⇒ 当游戏本体、临时件立刻删。
     *
     * <p>为什么必须落盘：content:// 只能顺流读，而"是不是补丁包"要读 zip 中央目录。
     * 代价 = 一次拷贝（几 MB ~ 80 MB），所以放后台 + 一张「正在识别…」的卡。
     */
    private static void preflightApk(final Activity a, final BatchIo.Doc doc) {
        final CardStack stack = new CardStack(a);
        stack.replace(Trans.get(a, R.string.deeplink_reading), doc.name);
        new Thread(new Runnable() {
            @Override public void run() {
                File tmp = null;
                PatchManifest m = null;
                List<Hit> hits = null;
                String err = null;
                try {
                    tmp = new File(HubTmp.session(a, HubTmp.NS_PATCH), "deeplink.apk");
                    InputStream in = a.getContentResolver().openInputStream(doc.uri);
                    if (in == null) throw new java.io.IOException("no stream");
                    try {
                        Util.copyStream(in, tmp);
                    } finally {
                        try { in.close(); } catch (Throwable ignored) { }
                    }
                    if (PatchPackage.isApkContainer(tmp)) {
                        m = PatchPackage.readManifest(tmp);
                        hits = matchVersions(a, m);
                    }
                } catch (Throwable t) {
                    err = Util.ioReason(a, t, Trans.get(a, R.string.deeplink_open_failed));
                }
                final File ftmp = tmp;
                final PatchManifest fm = m;
                final List<Hit> fh = hits;
                final String fe = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        stack.dismiss();
                        if (Util.dead(a)) { HubTmp.drop(a, ftmp); return; }
                        if (fe != null) {
                            HubTmp.drop(a, ftmp);
                            card(a, Trans.get(a, R.string.deeplink_open_failed), fe);
                        } else if (fm != null) {
                            confirmPatch(a, doc, ftmp, fm, fh);
                        } else {
                            HubTmp.drop(a, ftmp);      // 游戏本体：临时件没用，删掉再走原路
                            open(a, KIND_GAME, doc, detailOf(a, KIND_GAME, null));
                        }
                    }
                });
            }
        }, "deeplink-apk").start();
    }

    /** 本机的一个候选版本（补丁包点名的目标 APK 就在它身上）。 */
    static final class Hit {
        final String key;
        final String name;
        final String slot;

        Hit(String key, String name, String slot) {
            this.key = key == null ? "" : key;
            this.name = name == null ? "" : name;
            this.slot = slot == null ? "" : slot;
        }
    }

    /**
     * 在**本机已有版本**里找补丁包点名的那些 APK（按 sha256）。
     *
     * <p>两个来源都要看：CAS 开着时 APK 已删，原件哈希在清单的 `originSha256` 里
     * （{@link PkgManifest#patchShaOf}，第 427 轮）；盘上还有文件就现算。
     * 只在后台线程调（要读几十 MB）。
     */
    static List<Hit> matchVersions(Context ctx, PatchManifest m) {
        List<Hit> out = new ArrayList<>();
        if (m == null) return out;
        HashSet<String> want = new HashSet<>();
        for (PatchManifest.Target t : m.targets) {
            if (t != null && t.apkSha256 != null && !t.apkSha256.isEmpty()) {
                want.add(t.apkSha256.toLowerCase(Locale.ROOT));
            }
        }
        if (want.isEmpty()) return out;
        List<Versions.Entry> rows;
        try {
            rows = Versions.scanAll(ctx);
        } catch (Throwable t) {
            return out;
        }
        HashSet<String> seen = new HashSet<>();
        for (Versions.Entry e : rows) {
            if (e == null) continue;
            String key = e.key();
            if (key == null || key.isEmpty() || !seen.add(key)) continue;
            File apk = e.imported
                    ? new File(Importer.importDir(ctx), e.importFile == null ? "" : e.importFile)
                    : (e.apkPath == null ? null : new File(e.apkPath));
            String sha = "";
            try {
                if (e.imported) sha = PkgManifest.patchShaOf(ctx, key);   // CAS：清单里的原件哈希
                if ((sha == null || sha.isEmpty()) && apk != null && apk.isFile()) {
                    sha = Pkgs.sha256Of(apk);
                }
            } catch (Throwable ignored) {
                // 读不出来就当"对不上"（与 patchShaOf 的语义一致：判不出不冤枉）
            }
            if (sha != null && want.contains(sha.toLowerCase(Locale.ROOT))) {
                out.add(new Hit(key, e.displayName(ctx), Versions.slotFor(e)));
            }
        }
        return out;
    }

    /** 「识别为」那半句里的名字（`name version`；名字空 ⇒ 只写版本）。纯函数。 */
    static String patchLabel(PatchManifest m) {
        if (m == null) return "";
        String n = m.name == null ? "" : m.name.trim();
        String v = m.version == null ? "" : m.version.trim();
        if (n.isEmpty()) return v;
        return v.isEmpty() ? n : n + " " + v;
    }

    /** 目标版本给人看的那串（`gameVersion`；空 ⇒ APK sha256 前 8 位）。纯函数。 */
    static String patchTarget(PatchManifest m) {
        if (m == null) return "";
        if (m.gameVersion != null && !m.gameVersion.trim().isEmpty()) return m.gameVersion.trim();
        String s = m.apkSha256 == null ? "" : m.apkSha256;
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    /** 补丁包的确认卡：文件 / 识别为 / 目标 / 对得上几个版本；按钮「取消」「导入」。 */
    private static void confirmPatch(final Activity a, final BatchIo.Doc doc, final File tmp,
                                     final PatchManifest m, final List<Hit> hits) {
        final List<Hit> hs = hits == null ? new ArrayList<Hit>() : hits;
        String body = Trans.get(a, R.string.deeplink_confirm_file_fmt, doc.name) + "\n"
                + Trans.get(a, R.string.deeplink_confirm_patch_fmt, patchLabel(m)) + "\n"
                + Trans.get(a, R.string.deeplink_patch_target_fmt, patchTarget(m)) + "\n"
                + (hs.isEmpty()
                ? Trans.get(a, R.string.deeplink_patch_none_fmt, patchTarget(m))
                : Trans.get(a, R.string.deeplink_patch_hits_fmt, hs.size()));
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(null, a, Trans.get(a, R.string.deeplink_confirm_title),
                CardStack.text(a, body),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() {
                        ref[0].dismiss();
                        HubTmp.drop(a, tmp);
                    }
                }),
                new CardStack.Btn(Trans.get(a, hs.isEmpty()
                        ? R.string.deeplink_patch_import_anyway : R.string.deeplink_confirm_ok),
                        new Runnable() {
                            @Override public void run() {
                                ref[0].dismiss();
                                if (hs.size() > 1) askVersion(a, tmp, hs);
                                else importPatch(a, tmp, hs.isEmpty() ? null : hs.get(0));
                            }
                        }));
    }

    /** 多个版本对得上时问一句进哪一页（形状与选槽那屏一致）。 */
    private static void askVersion(final Activity a, final File tmp, final List<Hit> hits) {
        final String[] labels = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) labels[i] = hits.get(i).name;
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(null, a, Trans.get(a, R.string.deeplink_patch_pick_title),
                CardStack.list(a, labels, new CardStack.ItemClick() {
                    @Override public void onClick(int w) {
                        if (w < 0 || w >= hits.size()) return;
                        ref[0].dismiss();
                        importPatch(a, tmp, hits.get(w));
                    }
                }),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() {
                        ref[0].dismiss();
                        HubTmp.drop(a, tmp);
                    }
                }));
    }

    /** 真导入（后台）；成功后进那个版本的补丁页，没有匹配版本就只报一句「已导入」。 */
    private static void importPatch(final Activity a, final File tmp, final Hit hit) {
        final CardStack stack = new CardStack(a);
        stack.replace(Trans.get(a, R.string.patch_importing), tmp == null ? "" : tmp.getName());
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    Patches.importPackage(a, tmp);
                } catch (PatchException e) {
                    err = PatchText.reason(a, e);
                } catch (Throwable t) {
                    err = Util.ioReason(a, t, Trans.get(a, R.string.patch_import_failed));
                } finally {
                    HubTmp.drop(a, tmp);
                }
                final String fe = err;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        stack.dismiss();
                        if (Util.dead(a)) return;
                        if (fe != null) {
                            card(a, Trans.get(a, R.string.patch_import_failed), fe);
                        } else if (hit == null) {
                            card(a, Trans.get(a, R.string.deeplink_patch_done_title),
                                    Trans.get(a, R.string.deeplink_patch_done));
                        } else {
                            a.startActivity(PatchesActivity.intent(a, hit.key, hit.slot));
                        }
                    }
                });
            }
        }, "deeplink-patch").start();
    }

    /** 一张只有「关闭」的卡（出错 / 一句话都走这儿）。 */
    private static void card(Activity a, String title, String body) {
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(null, a, title, CardStack.text(a, body),
                new CardStack.Btn(Trans.get(a, R.string.patch_close), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }));
    }

    /**
     * ★★ **纯函数**：这份文件该进哪一页。不碰盘、不碰 Context（头 4 字节由调用方读好传进来）。
     *
     * <p>判据顺序（**后缀 → 内容 → mime**，越靠前越可信）：
     * <ol>
     *   <li>`.apk`：游戏本体（`.apk` 也是 zip ⇒ 排在容器判据之前）；</li>
     *   <li>`.msch` / `.msav`：后缀就是答案；</li>
     *   <li>`.zip`：**必须探内容** —— 它可能是整包（模组 / 地图 / 存档 / 蓝图），
     *       也可能是"一个模组包"本身；</li>
     *   <li>`.jar`：一个模组包（模组页自己还会再判一次是不是整包）；</li>
     *   <li>其余看头 4 字节：`PK..` ⇒ 压缩包 · `msch` ⇒ 蓝图 · zlib / gzip ⇒ 地图或存档
     *       （**同一个容器，分不开** ⇒ 按地图页处理，那页自己会认出"这其实是存档"）；</li>
     *   <li>最后看 mime（`application/x-msav` 是官方声明过的那个；`application/java-archive`
     *       是第 431 轮补的 —— content:// 那条路没有文件名，`.jar` 只剩 mime 可认）。</li>
     * </ol>
     * ⚠️ 第 ④ 条对**无后缀**的下载文件才是决定性的："download" 这种名字在浏览器里很常见。
     */
    static int kindFor(String name, String mime, byte[] head) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        String m = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        // ★ 第 436 轮：游戏本体（`.apk` 也是 zip ⇒ 必须排在下面那几个容器判据之前）。
        //   content:// 这条路上常常没有文件名，只剩 mime —— 与清单里收的那条一致。
        if (n.endsWith(".apk")) return KIND_GAME;
        if (m.contains("vnd.android.package-archive")) return KIND_GAME;
        if (n.endsWith(".msch")) return BatchIo.KIND_BLUEPRINTS;
        if (n.endsWith(".msav")) return BatchIo.KIND_MAPS;
        if (BatchIo.isZipName(n)) return PROBE;
        if (BatchIo.isArchiveName(n)) return BatchIo.KIND_MODS;
        if (isMschHead(head)) return BatchIo.KIND_BLUEPRINTS;
        if (isZipHead(head)) return PROBE;
        if (isZlibHead(head) || isGzipHead(head)) return BatchIo.KIND_MAPS;
        if (m.contains("msav")) return BatchIo.KIND_MAPS;
        // ★ 第 431 轮：content:// 那条路常常**没有文件名**（DocumentsUI 给的是
        //   `…/document/msf%3A1234`，展示名有时也拿不到）⇒ 后缀判不出来，只剩 mime。
        //   判据与清单里收的那两条 mime 一致；`.jar` 在内容层再分"单个模组 / 整包"
        //   （`BatchIo.modsBatchWanted`），这里只说"它是模组那一类"。
        if (m.contains("java-archive")) return BatchIo.KIND_MODS;
        return UNKNOWN;
    }

    /**
     * 页面那条路：本页是不是被深链叫起来的？是就给出那**一份**
     * （没有 ⇒ 空 list —— 与 SAF 那条路同一个形状，页面按同一个判据分流）。
     */
    static List<BatchIo.Doc> docsOf(Intent it, Context ctx) {
        List<BatchIo.Doc> out = new ArrayList<>();
        if (it == null) return out;
        String s = it.getStringExtra(EXTRA_URI);
        if (s == null || s.trim().isEmpty()) return out;
        String name = it.getStringExtra(EXTRA_NAME);
        Uri uri = Uri.parse(s.trim());
        out.add(new BatchIo.Doc(uri, name == null || name.trim().isEmpty()
                ? BatchIo.displayNameOf(ctx, uri) : name.trim()));
        return out;
    }

    /**
     * 分派到**拥有那一类内容**的页面。
     *
     * <p>★ 第 321 轮（用户：「再加一个从深链进入的文件可以选择导入到那个槽」）：落点槽先问一句
     * 「导入到哪个槽？」—— 但**只有一个槽就不问**（问一句没有别的选项的话只是多一次点击）。
     * ★ 第 380 轮（用户：「导入前对识别情况给个确认」）：槽定了之后再弹一张**确认卡**
     * （文件 / 识别为哪一类几份 / 导入到哪个槽，按钮「取消」「导入」）——
     * 除非 `detail == null`（整槽包：槽页自己那张确认框会列出包里的内容 + 三个写入模式，比这里更细，
     * 再问一遍就成了两道门）。
     *
     * @param detail 「识别为…」那句话；`null` ⇒ 不额外确认，直接进页面
     */
    private static void open(final Activity a, final int kind, final BatchIo.Doc doc,
                             final String detail) {
        // ★ 第 425 轮：归档槽不能当落点（它本体已清空，导入进去的东西不会进快照、解除归档时对不上）
        final List<Data.Slot> all = new java.util.ArrayList<Data.Slot>();
        for (Data.Slot s : Data.allSlots(a)) {
            if (s != null && !SlotArchive.isArchived(a, s.name)) all.add(s);
        }
        if (!shouldAsk(all)) {
            // 0 个槽（理论上不会有）⇒ 回落到当前槽；1 个 ⇒ 就是它
            afterSlot(a, kind, doc, all.isEmpty() ? Data.currentSlot(a) : all.get(0).name, detail);
            return;
        }
        askSlot(a, kind, doc, all, detail);
    }

    /** 槽定了之后的最后一步：有识别说明 ⇒ 先确认；没有 ⇒ 直接进页面 */
    private static void afterSlot(final Activity a, final int kind, final BatchIo.Doc doc,
                                  final String slot, final String detail) {
        if (detail == null || detail.trim().isEmpty()) {
            route(a, kind, doc, slot);
            return;
        }
        confirm(a, kind, doc, slot, detail);
    }

    /**
     * 「识别为…」那句话（第 380 轮）。**纯装配**，不进后台：数据来自 `summarize` / 后缀。
     *
     * @param s 压缩包那一路的摘要；后缀那一路传 `null`（那就按"1 份"说）
     */
    static String detailOf(Context a, int kind, BatchIo.Summary s) {
        if (kind == KIND_GAME) return Trans.get(a, R.string.deeplink_confirm_game);  // 也是负数，必须排在下面那条之前
        if (kind == BatchIo.KIND_SLOT) return null;       // 整槽包：槽页自己那张确认框更细
        if (kind == BatchIo.KIND_SLOT_AMBIGUOUS || kind < 0) return null;
        final int one = 1;
        if (kind == BatchIo.KIND_MIXED) {
            return Trans.get(a, R.string.deeplink_confirm_mixed_fmt,
                    s == null ? 0 : s.mods, s == null ? 0 : s.blueprints, s == null ? 0 : s.maps);
        }
        if (kind == BatchIo.KIND_MODS) {
            return Trans.get(a, R.string.deeplink_confirm_mods_fmt, s == null ? one : s.mods);
        }
        if (kind == BatchIo.KIND_BLUEPRINTS) {
            return Trans.get(a, R.string.deeplink_confirm_blueprints_fmt,
                    s == null ? one : s.blueprints);
        }
        if (kind == BatchIo.KIND_SAVES) {
            return Trans.get(a, R.string.deeplink_confirm_saves_fmt, s == null ? one : s.maps);
        }
        return Trans.get(a, R.string.deeplink_confirm_maps_fmt, s == null ? one : s.maps);
    }

    /**
     * 要不要问"导入到哪个槽"—— **只有一个槽时不问**（§77：「只在真需要时问」）。
     *
     * <p>纯函数，自检直接喂 0/1/2 个槽。
     */
    static boolean shouldAsk(List<Data.Slot> all) {
        return all != null && all.size() > 1;
    }

    /**
     * 选槽那一屏（第 321 轮）：名字 + 「（当前）」 + 「N 个文件 · 大小」，与换槽/克隆那两处**同一个形状**
     * （同一件事在两处长得不一样，用户会以为是两件事）。取消 = 这次不导（**什么都不做**）。
     */
    private static void askSlot(final Activity a, final int kind, final BatchIo.Doc doc,
                                List<Data.Slot> all, final String detail) {
        final String[] labels = new String[all.size()];
        final String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Data.Slot s = all.get(i);
            names[i] = s.name;
            labels[i] = s.name + (s.active ? Trans.get(a, R.string.slot_current_suffix) : "")
                    + "  " + Trans.get(a, R.string.slot_entry_fmt, s.files, Util.formatSize(s.bytes));
        }
        final CardStack[] ref = new CardStack[1];
        ref[0] = CardStack.show(null, a, Trans.get(a, R.string.deeplink_slot_title),
                CardStack.list(a, labels, new CardStack.ItemClick() {
                    @Override public void onClick(int w) {
                        if (w < 0 || w >= names.length) return;
                        ref[0].dismiss();
                        afterSlot(a, kind, doc, names[w], detail);
                    }
                }),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }));
    }

    /**
     * ★ 第 380 轮（用户：「导入前对识别情况给个确认」）：**导入前的确认卡**。
     *
     * <p>三行：**文件** / **识别为什么、几份** / **导入到哪个槽**；按钮「取消」「导入」。
     * 取消 = 这次不导（什么都不写）。⚠️ 按钮的 action **不会自动关窗** ⇒ 两个都要自己 `dismiss()`。
     */
    private static void confirm(final Activity a, final int kind, final BatchIo.Doc doc,
                                final String slot, final String detail) {
        final CardStack[] ref = new CardStack[1];
        String body = Trans.get(a, R.string.deeplink_confirm_file_fmt, doc.name) + "\n"
                + detail + "\n"
                + Trans.get(a, R.string.deeplink_confirm_slot_fmt, slot);
        ref[0] = CardStack.show(null, a, Trans.get(a, R.string.deeplink_confirm_title),
                CardStack.text(a, body),
                new CardStack.Btn(Trans.get(a, R.string.cancel), new Runnable() {
                    @Override public void run() { ref[0].dismiss(); }
                }),
                new CardStack.Btn(Trans.get(a, R.string.deeplink_confirm_ok), new Runnable() {
                    @Override public void run() {
                        ref[0].dismiss();
                        route(a, kind, doc, slot);
                    }
                }));
    }

    private static void route(Activity a, int kind, BatchIo.Doc doc, String slot) {
        // ★ 第 436 轮：游戏本体不是"进某一页"，而是宿主自己那条「添加游戏」
        //   （进度卡 / 签名询问 / 兼容性探测都在那儿）；选好的槽由它导入成功后落库。
        if (kind == KIND_GAME) {
            if (a instanceof MainActivity) ((MainActivity) a).startImport(doc.uri, doc.name, slot);
            return;
        }
        a.startActivity(openIntent(a, kind, doc, slot));
    }

    /**
     * 分派用的 Intent（**纯装配**）。抽出来只为一件事：让自检能钉住"去哪一页 + 槽名带对了"。
     *
     * <p>五个页面的 `EXTRA_SLOT` 都是 `"slot"`（见各自类的常量；槽页也一样）—— 写错哪个键编译器不响，
     * 真机只表现为"导进当前槽了"（**静默错位**，与第 320 轮那条同族）。
     */
    static Intent openIntent(Context c, int kind, BatchIo.Doc doc, String slot) {
        Intent i;
        if (kind == BatchIo.KIND_BLUEPRINTS) i = new Intent(c, BlueprintsActivity.class);
        else if (kind == BatchIo.KIND_SAVES) i = new Intent(c, SavesActivity.class);
        else if (kind == BatchIo.KIND_MODS) i = new Intent(c, ModsActivity.class);
        // ★ 第 379 轮：**整槽包**（判据 `settings.bin`，见 `BatchIo.Probe#slotState`）⇒ 槽页，
        //   由它走「导入整槽」那条既有流程（确认框 + 三个模式 + 写门禁）。
        // ★ 第 380 轮：**混合包**（模组 / 地图 / 蓝图 / 存档混装）也进槽页 —— 由它按类型各进各的目录。
        else if (kind == BatchIo.KIND_SLOT || kind == BatchIo.KIND_MIXED) {
            i = new Intent(c, SlotActivity.class);
        }
        else i = new Intent(c, MapsActivity.class);
        i.putExtra(MapsActivity.EXTRA_SLOT, slot == null ? "" : slot);
        // ★ 槽页收两种深链（整槽包 / 混合包）⇒ 把"是哪一种"带过去（页内据此选哪条流程）
        i.putExtra(EXTRA_KIND, kind);
        if (doc != null) {
            i.putExtra(EXTRA_URI, doc.uri == null ? "" : doc.uri.toString());
            i.putExtra(EXTRA_NAME, doc.name == null ? "" : doc.name);
        }
        return i;
    }

    // ── 认容器用的四个魔数（纯函数，自检直接喂字节）────────────────────────

    /** `PK\u0003\u0004` —— 一个 zip（`.zip` / `.jar` / 我们导出的整包都是它） */
    static boolean isZipHead(byte[] h) {
        return h != null && h.length >= 4 && (h[0] & 0xff) == 0x50 && (h[1] & 0xff) == 0x4b
                && (h[2] & 0xff) == 0x03 && (h[3] & 0xff) == 0x04;
    }

    /** `'msch'` —— 蓝图自己的文件头（`Msch`：`'msch' + 版本 1B + zlib(body)`） */
    static boolean isMschHead(byte[] h) {
        return h != null && h.length >= 4 && (h[0] & 0xff) == 0x6d && (h[1] & 0xff) == 0x73
                && (h[2] & 0xff) == 0x63 && (h[3] & 0xff) == 0x68;
    }

    /** zlib（`78 01` / `78 9c` / `78 da` / `78 5e`）—— 真地图 / 真存档就是它 */
    static boolean isZlibHead(byte[] h) {
        if (h == null || h.length < 2) return false;
        int a = h[0] & 0xff, b = h[1] & 0xff;
        return a == 0x78 && (b == 0x01 || b == 0x9c || b == 0xda || b == 0x5e);
    }

    /** gzip（`1f 8b`）—— 少数老存档是 gzip */
    static boolean isGzipHead(byte[] h) {
        return h != null && h.length >= 2 && (h[0] & 0xff) == 0x1f && (h[1] & 0xff) == 0x8b;
    }

    /** 前 4 字节；读不到 / 空文件以外的任何失败 ⇒ null（调用方按"打不开"处理） */
    private static byte[] headOf(Context ctx, Uri uri) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            byte[] buf = new byte[4];
            int n = in.read(buf);
            if (n <= 0) return new byte[0];
            if (n == 4) return buf;
            byte[] cut = new byte[n];
            System.arraycopy(buf, 0, cut, 0, n);
            return cut;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 标题 = 文件名，正文 = 资源里的整句（**不在调用点拼文案**，也不重复印一遍名字） */
    private static void alert(final Activity a, String title, int msgRes) {
        if (CardStack.leaf(title, Trans.get(a, msgRes))) return;
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(msgRes)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
