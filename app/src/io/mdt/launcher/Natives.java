package io.mdt.launcher;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 游戏 native 库（arc 的 `.so`）的**落点与发布**（阶段 0.3，2026-11-15 第 382 轮）。
 *
 * <h3>为什么必须按版本分目录</h3>
 *
 * 旧实现解到 `app_hub/natives/&lt;abi&gt;/libarc.so` —— **所有版本共用一个固定路径**，
 * 而 `loadNatives` 是"先删后写、再 `System.load`"。2026-11-14 真机实测：
 * 159.7 的 `libarc.so` 是 802,496 B（`d33ac19e…`），v146 的是 550,584 B（`2594805f…`），
 * **互相覆盖**；两个实例（或一个实例启动、另一个已经在跑）同时启动时，
 * 可能 `load` 到半截 / 错版本 / ENOENT（见 plan §12.23 / §12.24 C）。
 *
 * <p>现在布局：`app_hub/natives/&lt;abi&gt;/&lt;tag&gt;/&lt;name&gt;`，`tag` 由**版本 key**
 * （`official-159.7` / `import:xxx.apk`）算出来（{@link #tagOf}），不同版本各一份。
 * 同一版本 + 不同槽共用一份（内容相同）；文件**只读**，写侧走 `.part → rename`。</p>
 *
 * <h3>三个纪律</h3>
 * <ol>
 *   <li><b>原子发布</b>：先写同目录 `.part-&lt;pid&gt;-&lt;nano&gt;`，校验内容一致才 rename；
 *       半截文件永远不会出现在最终路径上。</li>
 *   <li><b>同内容不重写</b>：目标已存在且长度 / md5 一致 ⇒ 直接用（省掉几十 MB 写盘，
 *       也不会在别的实例正 `load` 时把文件换掉）。</li>
 *   <li><b>只读</b>：发布完 `setReadOnly()`（ART 对 dex 有硬要求；.so 虽不强制，但固定下来
 *       能防"写一半被 load"这类事故，也让"谁改过"一眼可查）。</li>
 * </ol>
 *
 * <p>旧布局（`natives/&lt;abi&gt;/*.so`）由 {@link #sweepLegacy} 在**没有游戏在跑**时清掉
 * （`Recovery` 启动时调）；清的是**文件**，版本子目录一个不动。</p>
 */
final class Natives {
    private static final String TAG = "MDTLauncher";

    /** 版本子目录名里允许的字符 —— 别的都换成 `_`（tag 会拼进路径） */
    private static final int TAG_MAX = 40;

    private Natives() {}

    // ── 路径 ──────────────────────────────────────────────────────────────

    /** `app_hub/natives` */
    static File base(Context ctx) {
        return new File(Paths.privateDir(ctx), "natives");
    }

    /** 某个 ABI 的版本目录：`app_hub/natives/&lt;abi&gt;/&lt;tag&gt;` */
    static File dir(Context ctx, String abi, String tag) {
        return new File(new File(base(ctx), abi == null ? "unknown" : abi),
                tag == null || tag.isEmpty() ? "unknown" : tag);
    }

    /** 旧布局的 ABI 目录（`app_hub/natives/&lt;abi&gt;`，里面直接放 `.so`） */
    static File abiDir(Context ctx, String abi) {
        return new File(base(ctx), abi == null ? "unknown" : abi);
    }

    // ── 版本 tag（纯函数，自检直接钉）──────────────────────────────────────

    /**
     * 版本 key → 目录名：只留 `[A-Za-z0-9._-]`，截到 {@link #TAG_MAX}，末尾加
     * **内容哈希前 8 位**（防止 `import:中文1.apk` 与 `import:中文2.apk` 清洗后撞名）。
     *
     * <p>空 key ⇒ `unknown`（不让空串生成 `app_hub/natives/<abi>/` 本身）。</p>
     */
    static String tagOf(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) return "unknown";
        StringBuilder sb = new StringBuilder(Math.min(TAG_MAX, k.length()) + 9);
        for (int i = 0; i < k.length() && sb.length() < TAG_MAX; i++) {
            char c = k.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        String h = Content.sha256(k);
        sb.append('-').append(h, 0, 8);
        return sb.toString();
    }

    /**
     * 拿不到版本 key 时的兜底 tag：用 APK 的**文件名 + 长度 + mtime**（都是廉价字段）。
     *
     * <p>⚠️ 这不是"版本身份"—— 只服务 `GameSlot` 之外（自检 / 旧调用）的路径；
     * 正式启动一律带 `Plan.tag`（版本 key）。</p>
     */
    static String fallbackTag(File apk) {
        if (apk == null) return "unknown";
        return tagOf(apk.getName() + "|" + apk.length() + "|" + apk.lastModified());
    }

    /**
     * 启动管线用：`plan.tag`（版本 key）优先，没有就用 APK 的兜底 tag。
     * 返回**已经过 {@link #tagOf} 清洗**的目录名。
     */
    static String tagFor(String vkey, File apk) {
        String k = vkey == null ? "" : vkey.trim();
        return k.isEmpty() ? fallbackTag(apk) : tagOf(k);
    }

    // ── 发布 ──────────────────────────────────────────────────────────────

    /**
     * 把一个库发布到 `natives/&lt;abi&gt;/&lt;tag&gt;/&lt;name&gt;` 并返回它。
     *
     * <p>同内容（长度 + md5）已存在 ⇒ **不重写**，直接返回旧文件；否则写 `.part`、
     * rename、置只读。传入的流由本方法**负责关闭**（与 `try-with-resources` 同一语义）。</p>
     *
     * @throws IOException 目录建不出来 / 写失败 / rename 失败（调用方按"native 加载失败"处理）
     */
    static File publish(Context ctx, String abi, String tag, String name, InputStream in)
            throws IOException {
        File d = dir(ctx, abi, tag);
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
            throw new IOException("mkdir " + d);
        }
        File out = new File(d, name);
        File tmp = new File(d, name + ".part-" + Process.myPid() + "-" + System.nanoTime());
        FileOutputStream os = new FileOutputStream(tmp);
        try {
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
            } finally {
                os.close();
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (out.isFile() && sameContent(out, tmp)) {
                return out;
            }
            if (out.exists()) {
                out.setWritable(true);
                out.delete();
            }
            if (!tmp.renameTo(out)) {
                // 同目录 rename 正常不会失败；失败时保住内容（拷贝 + 删临时件）
                copyFile(tmp, out);
            }
            out.setReadOnly();
            return out;
        } finally {
            if (tmp.exists()) deleteQuietly(tmp);
        }
    }

    /**
     * 同内容判据：长度相同 + md5 相同。md5 读不出来时**按不同处理**（宁可重写，也不留错版本）。
     */
    private static boolean sameContent(File a, File b) {
        if (a.length() != b.length()) return false;
        String ha = Util.md5(a);
        String hb = Util.md5(b);
        return ha != null && ha.equals(hb);
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            out.close();
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null) {
                f.setWritable(true);
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    // ── 旧布局清扫 ────────────────────────────────────────────────────────

    /** 清掉**所有** ABI 目录下直接放着的旧 `.so`（版本子目录不动）；返回删掉的个数 */
    static int sweepLegacy(Context ctx) {
        File base = base(ctx);
        File[] abis = base.listFiles();
        int n = 0;
        if (abis == null) return 0;
        for (File abi : abis) {
            if (abi == null || !abi.isDirectory()) continue;
            n += sweepLegacyIn(abi);
        }
        return n;
    }

    /** 只清某个 ABI（自检用；不会碰真机其他 ABI） */
    static int sweepLegacyIn(File abiDir) {
        if (abiDir == null || !abiDir.isDirectory()) return 0;
        File[] fs = abiDir.listFiles();
        int n = 0;
        if (fs == null) return 0;
        for (File f : fs) {
            if (f == null || !f.isFile() || !f.getName().endsWith(".so")) continue;
            if (f.delete()) n++;
            else Log.w(TAG, "legacy native delete failed: " + f);
        }
        return n;
    }

    /** 整个 natives 目录的体积（Storage 口径用；含旧布局残留） */
    static long size(Context ctx) {
        return Data.sizeTree(base(ctx));
    }
}
