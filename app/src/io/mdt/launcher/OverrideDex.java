package io.mdt.launcher;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 同名覆盖 dex（`arc.backend.android.AndroidFi` 的产品版）的落盘与版本对齐。
 *
 * <p>它随本体发放：`app/assets/pkgs/override.dex`（由 `tools/poolspike` 编译，见那里的
 * README）。运行时只需要它在 `app_hub/pkgs/override.dex` 且**只读** —— 游戏要用它当
 * dexPath 的第一个元素。</p>
 *
 * <p>版本对齐靠 `<code>override.sha</code> 旁注文件：本体升级后资产里的 dex 换了，
 * 这里会重新落盘，不会出现「新代码配旧 dex」。</p>
 */
public final class OverrideDex {
    private static final String ASSET = "pkgs/override.dex";
    private static final String TAG = "MDTLauncher";

    private OverrideDex() {}

    /** 确保 `pkgs/override.dex` 与当前本体一致；拿不到资产就返回 null（调用方按不可用处理）。 */
    public static File ensure(Context ctx) {
        try {
            byte[] bytes = readAsset(ctx);
            if (bytes == null || bytes.length < 1024) return null;
            Pkgs pool = Pkgs.get(ctx);
            File dst = pool.overrideDex();
            String sha = Pkgs.hex(Pkgs.digest().digest(bytes));
            File side = new File(dst.getParentFile(), "override.sha");
            if (dst.isFile() && dst.length() == bytes.length && sha.equals(readText(side))) {
                return dst;
            }
            dst.setWritable(true);
            File part = new File(pool.tmpDir(), "override.dex.part");
            OutputStream out = new FileOutputStream(part);
            try {
                out.write(bytes);
                out.flush();
                if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
            } finally {
                out.close();
            }
            if (!part.renameTo(dst)) {
                Pkgs.copyFile(part, dst);
                part.delete();
            }
            if (!dst.setReadOnly()) android.util.Log.w(TAG, "override.dex: setReadOnly failed");
            writeText(side, sha);
            return dst;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "override.dex: ensure failed", t);
            return null;
        }
    }

    /** 覆盖 dex 的存在性（不落盘）：判断「能不能用同名类覆盖」的前置条件之一。 */
    public static boolean available(Context ctx) {
        try {
            return readAsset(ctx) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] readAsset(Context ctx) throws IOException {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(ASSET);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private static String readText(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return Util.readText(f).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File f, String s) {
        try {
            OutputStream out = new FileOutputStream(f);
            try {
                out.write(s.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // 旁注写不进去只会让下次重新落盘，不影响正确性
        }
    }
}
