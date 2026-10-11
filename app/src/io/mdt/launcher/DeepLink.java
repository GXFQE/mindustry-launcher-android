package io.mdt.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.InputStream;
import java.util.ArrayList;
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
 * 由本类分派到四个内容页；游戏那个 Activity 一个 filter 都不加。
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
 * ② 落点**只有当前槽** —— 槽名不从 Intent 里读（外部塞 `slot` extra 也不认），
 *    所以"能塞数据"不等于"能往任意槽写"；
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

    /** {@link #kindFor} 的返回值：是压缩包，**读过内容**才知道是哪一类 */
    static final int PROBE = -2;
    /** {@link #kindFor} 的返回值：认不出 */
    static final int UNKNOWN = -1;

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
        if (kind != PROBE) {
            open(a, kind, doc);
            return true;
        }
        // 压缩包：装的是模组 / 地图 / 存档 / 蓝图，只有读过内容才知道 ⇒ 后台读一遍再问
        final ProgressDialog pd = ProgressDialog.show(a,
                Trans.get(a, R.string.deeplink_reading), doc.name, true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                final int k = BatchIo.guessKind(a, doc);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (Util.dead(a)) return;
                        if (k < 0) alert(a, doc.name, R.string.deeplink_zip_empty);
                        else open(a, k, doc);
                    }
                });
            }
        }, "deeplink-probe").start();
        return true;
    }

    /**
     * ★★ **纯函数**：这份文件该进哪一页。不碰盘、不碰 Context（头 4 字节由调用方读好传进来）。
     *
     * <p>判据顺序（**后缀 → 内容 → mime**，越靠前越可信）：
     * <ol>
     *   <li>`.msch` / `.msav`：后缀就是答案；</li>
     *   <li>`.zip`：**必须探内容** —— 它可能是整包（模组 / 地图 / 存档 / 蓝图），
     *       也可能是"一个模组包"本身；</li>
     *   <li>`.jar`：一个模组包（模组页自己还会再判一次是不是整包）；</li>
     *   <li>其余看头 4 字节：`PK..` ⇒ 压缩包 · `msch` ⇒ 蓝图 · zlib / gzip ⇒ 地图或存档
     *       （**同一个容器，分不开** ⇒ 按地图页处理，那页自己会认出"这其实是存档"）；</li>
     *   <li>最后看 mime（`application/x-msav` 是官方声明过的那个）。</li>
     * </ol>
     * ⚠️ 第 ④ 条对**无后缀**的下载文件才是决定性的："download" 这种名字在浏览器里很常见。
     */
    static int kindFor(String name, String mime, byte[] head) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (n.endsWith(".msch")) return BatchIo.KIND_BLUEPRINTS;
        if (n.endsWith(".msav")) return BatchIo.KIND_MAPS;
        if (BatchIo.isZipName(n)) return PROBE;
        if (BatchIo.isArchiveName(n)) return BatchIo.KIND_MODS;
        if (isMschHead(head)) return BatchIo.KIND_BLUEPRINTS;
        if (isZipHead(head)) return PROBE;
        if (isZlibHead(head) || isGzipHead(head)) return BatchIo.KIND_MAPS;
        String m = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        if (m.contains("msav")) return BatchIo.KIND_MAPS;
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

    /** 分派到**拥有那一类内容**的页面（落点 = 当前槽；页面自己那道 `gameAlive` 门禁照旧生效） */
    private static void open(Activity a, int kind, BatchIo.Doc doc) {
        Intent i;
        if (kind == BatchIo.KIND_BLUEPRINTS) i = new Intent(a, BlueprintsActivity.class);
        else if (kind == BatchIo.KIND_SAVES) i = new Intent(a, SavesActivity.class);
        else if (kind == BatchIo.KIND_MODS) i = new Intent(a, ModsActivity.class);
        else i = new Intent(a, MapsActivity.class);
        // 四个页面的 EXTRA_SLOT 都是 "slot"（见各自类的常量）
        i.putExtra(MapsActivity.EXTRA_SLOT, Data.currentSlot(a));
        i.putExtra(EXTRA_URI, doc.uri.toString());
        i.putExtra(EXTRA_NAME, doc.name);
        a.startActivity(i);
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
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(msgRes)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
