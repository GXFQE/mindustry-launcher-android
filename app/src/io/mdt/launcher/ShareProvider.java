package io.mdt.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * 「直接分享」用的那个 `content://` 出口（第 155 轮）。
 *
 * <h3>为什么需要它</h3>
 * 导出原本只有一条路：{@link Exporter#createDoc} 拿用户选的 SAF 写句柄，写完整份字节就撒手
 * —— 我们**不持有任何能被别的 App 读的 URI**。而 {@code ACTION_SEND} 需要一个。
 * 本工程无 androidx（清单里一个第三方库都没有）⇒ **没有 FileProvider 可用**，于是自己写一个
 * 只干一件事的：把 `cache/share/` 下的一个**平铺文件**以只读方式借给收到授权的 App。
 *
 * <h3>三条硬纪律（少一条就是"微信里名字变成一串数字/没有后缀"，而且不报错）</h3>
 * <ol>
 *   <li><b>query 必须给 {@link OpenableColumns#DISPLAY_NAME} 与 {@code SIZE}</b> ——
 *       接收方靠它显示文件名与大小（FileProvider 干的就是这件事）。缺了它，微信/QQ 会自己编一个。</li>
 *   <li><b>只认 `cache/share/` 下的**一个平铺名字**</b>：{@code ..}、路径分隔符、控制字符一律拒，
 *       并且再走一次 canonical 前缀校验 —— 否则 `content://…/../../databases/x` 就能读到私有文件。</li>
 *   <li><b>只读</b>：分享出去的是"给别人的一份"，谁都不许写回（`openFile` 收到 w 就抛）。</li>
 * </ol>
 *
 * <p>⚠️ provider 在**每个进程**都会实例化（包括 `:game`）⇒ {@link #onCreate()} 里**不许做重活**，
 * 否则等于给游戏启动加了一道。</p>
 */
public final class ShareProvider extends ContentProvider {

    /** 清单里那一条必须与它逐字相同（自检按 {@code getProviderInfo} 比对）。 */
    static final String AUTHORITY = "io.mdt.launcher.share";

    /** 缓存下的子目录（与 {@code cache} 其它临时件同一层，Storage 的占用口径已经算到它） */
    static final String DIR = "share";

    /** 文件名长度上限（超过就不是我们产生的东西了） */
    private static final int MAX_NAME = 120;

    // ── 路径判据（纯函数，自检直接调） ─────────────────────────────────────

    /** `cache/share` 目录（懒创建；建不出来就把 File 交回去，调用方自己会失败） */
    static File dirOf(Context c) {
        File d = new File(c.getCacheDir(), DIR);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 分享 URI：`content://io.mdt.launcher.share/<名字>`（名字按路径段转义） */
    static Uri uriOf(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(name));
    }

    /**
     * 这个名字能不能用：只允许**一个平铺的文件名**。
     *
     * ★ 中文、空格、括号都放行（导出文件名本来就可能带图名/槽名）；
     * 拒的是 `..`、`/`、`\`、控制字符 —— 那是"换一个文件"与"读到别处"的唯一入口。
     */
    static boolean safeName(String n) {
        if (n == null || n.isEmpty() || n.length() > MAX_NAME) return false;
        if (".".equals(n) || "..".equals(n)) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }

    /**
     * 名字 → 文件。**两道门**：{@link #safeName} + canonical 前缀（防符号链接/奇怪分段）。
     *
     * @throws FileNotFoundException 名字不合法、解析到目录外、或文件不存在
     */
    static File fileOf(Context c, String name) throws FileNotFoundException {
        if (!safeName(name)) throw new FileNotFoundException("bad name");
        File d = dirOf(c);
        File f = new File(d, name);
        try {
            String base = d.getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(base)) {
                throw new FileNotFoundException("outside share dir");
            }
        } catch (IOException e) {
            throw new FileNotFoundException("unresolvable name");
        }
        if (!f.isFile()) throw new FileNotFoundException("no such file");
        return f;
    }

    /** 扩展名 → MIME（接收方查 {@code getType} 时用；Intent 自己那份以调用方给的为准） */
    static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".jsonl")) return "application/x-ndjson";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    /**
     * 给接收方的那张"文件卡片"：名字 + 字节数。
     *
     * <p>★ 故意做成**纯函数**（吃 File）—— 自检不必把 provider 实例化/挂到真实进程上，
     * 直接喂一个临时文件就能钉住"这两个列真的在、值真的是这个"。{@link #query} 只是它的壳。</p>
     */
    static Cursor cursorFor(File f, String[] projection) {
        String[] cols = projection == null || projection.length == 0
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    // ── ContentProvider ───────────────────────────────────────────────────

    @Override public boolean onCreate() {
        // ⚠️ 见类注释：这里会在 `:game` 进程里也跑一次 ⇒ 只许 return true
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            return cursorFor(fileOf(getContext(), uri == null ? null : uri.getLastPathSegment()),
                    projection);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public String getType(Uri uri) {
        return mimeOf(uri == null ? null : uri.getLastPathSegment());
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        // 只读：给别人的一份，谁都不许写（收到 "w"/"wt"/"rw" 一律拒）
        if (mode != null && mode.indexOf('w') >= 0) {
            throw new FileNotFoundException("read-only");
        }
        return ParcelFileDescriptor.open(
                fileOf(getContext(), uri == null ? null : uri.getLastPathSegment()),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        return 0;
    }
}
