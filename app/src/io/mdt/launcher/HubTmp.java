package io.mdt.launcher;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HUB 临时件的**唯一落点**：`app_hub/tmp/`（阶段 0.1，2026-11-15 第 382 轮）。
 *
 * <h3>为什么搬离 `getCacheDir()`</h3>
 *
 * 系统随时可以清 cache（低存储、用户点「清除缓存」、系统回收），而我们的
 * 导出 / 分享 / 补丁导入 / 插件导入 / 语言包这些是**正在被用户用的中间件**：
 * 清掉它 ＝ 用户看到「导出成功」却拿到半截包（第 177 轮真机踩过 15 MB 坏 zip）。
 * 更关键的是多实例：游戏 cache 是**按实例目录**隔离的（阶段 1 的
 * `inst/&lt;key&gt;/cache`），HUB 的临时件必须有一个**宿主级、不随实例清**的位置 ——
 * 否则"整目录清 cache"会把 HUB 正在写的 `export` / `patch-import` 一起删掉
 * （见 `docs/plans/2026-11-14-多实例实施路线.md` 的依赖：0.1 必须先于 0.2）。
 *
 * <h3>目录约定（§12.30）</h3>
 *
 * <pre>
 *   app_hub/tmp/&lt;ns&gt;/&lt;pid&gt;-&lt;started&gt;-&lt;rand&gt;/owner.pid   一次会话（batch / patch-import / …）
 *   app_hub/tmp/&lt;ns&gt;/&lt;文件名&gt;                          固定命名空间（export / share）
 *   app_hub/tmp/plugin-cache/&lt;pid&gt;/                     按进程（插件回落 cache）
 * </pre>
 *
 * `owner.pid` 里第一行是 pid，第二行是创建时刻；目录名里也带同一份信息
 * （owner 文件写失败时**还能从名字解析**，sweep 不会因此把活件当死件）。
 *
 * <h3>清扫判据（唯一实现，{@link #sweep}）</h3>
 * <ul>
 *   <li><b>活着的进程一个字节都不动</b>——pid 在 `/proc/&lt;pid&gt;` 里就是活；
 *       TTL 只用来清**没有主人**的件（旧版本遗留 / owner 写失败）。</li>
 *   <li>主人不在了 ⇒ 整个会话目录删掉（先留 {@link #GRACE_MS} 宽限，防"刚建目录、还没写 owner"）。</li>
 *   <li>固定命名空间里的**文件**超过 TTL 才删（share 24 h，其余 1 h）。</li>
 *   <li>总量超过 {@link #CAP_BYTES} 时按最旧优先删**无主**会话，绝不动活进程的。</li>
 *   <li>**不跟随符号链接**（`java.nio.file.Files.isSymbolicLink`）：模组可以往我们的临时目录里放一个
 *       指向槽数据的链接，跟随删除就会删到用户存档（§12.29 的同一条纪律）。</li>
 * </ul>
 *
 * <p>⚠️ 这个类**只动 `app_hub/tmp` 下面**：槽 / 导入包 / 插件 / 补丁库一个字节都不碰。
 *   `Recovery` 只在**没有游戏在跑**时调它（主进程启动那一刻），接口里的 `now` / `alive` 可注入
 *   是为了让自检能造"死 pid / 活 pid / 过期 / 新鲜"四类夹具。</p>
 */
final class HubTmp {
    private static final String TAG = "MDTLauncher";

    /** 临时根的名字（`app_hub/tmp`） */
    static final String ROOT = "tmp";

    /** owner 标记文件名（内容：pid 换行创建时刻） */
    static final String OWNER = "owner.pid";

    // ── 命名空间（每个 ns 的 TTL 不同，见 ttlMs）────────────────────────────

    /** 整槽 / 批量导出先在本地写完整的中间件（完成即删，启动兜底） */
    static final String NS_EXPORT = "export";
    /** 插件导出（`PluginActions.export` 先落一份再拷给 SAF） */
    static final String NS_PLUGIN_EXPORT = "plugin-export";
    /** 批量导入的临时目录（一次导入一个会话） */
    static final String NS_BATCH = "batch";
    /** 补丁包导入（SAF 流先落盘再 `Patches.importPackage`） */
    static final String NS_PATCH = "patch-import";
    /** 插件 APK 导入 */
    static final String NS_PLUGIN = "plugin-import";
    /** 语言包导入的校验件 */
    static final String NS_LANG = "lang";
    /** 「直接分享」给别的 App 的那一份（provider 只读借出，24 h） */
    static final String NS_SHARE = "share";
    /** 插件 cache 的兜底（拿不到插件自己的目录时；按 pid 分） */
    static final String NS_PLUGIN_CACHE = "plugin-cache";

    /** 主人不在了也要等这么久才删（刚建的目录可能还没写 owner） */
    static final long GRACE_MS = 30L * 1000L;

    /**
     * 体积修剪时"刚放下"的宽限（1 h）：分享包 / 插件导出可能正被别的 App 借出去读，
     * 刚写完就删会让对方拿到一个空文件。
     */
    private static final long TRIM_GRACE_MS = 60L * 60L * 1000L;

    private HubTmp() {}

    // ── 路径 ──────────────────────────────────────────────────────────────

    /** `app_hub/tmp`（懒创建；只读调用方请用 {@link #rootNoCreate}） */
    static File root(Context ctx) {
        File d = new File(Paths.privateDir(ctx), ROOT);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 不创建（Storage 统计 / 只读判断用） */
    static File rootNoCreate(Context ctx) {
        return new File(Paths.privateDir(ctx), ROOT);
    }

    /** 固定命名空间目录（export / share / plugin-export …）：文件自己保证名字唯一 */
    static File dir(Context ctx, String ns) {
        File d = new File(root(ctx), ns);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 按 pid 分的目录（plugin-cache：同一进程多次调用落到同一个） */
    static File pidDir(Context ctx, String ns) {
        File d = new File(dir(ctx, ns), String.valueOf(Process.myPid()));
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /**
     * 新开一次会话目录（batch / patch-import / plugin-import / lang）。
     *
     * <p>名字 = `&lt;pid&gt;-&lt;毫秒&gt;-&lt;随机&gt;`，里面写 `owner.pid`。**目录建不出来时照样返回
     * 这个 File**（不回落成 ns 根！否则 `cleanup` 会把整个命名空间删掉）—— 后续写文件会
     * 以 ENOENT 失败，调用方按普通 IO 失败处理。</p>
     */
    static File session(Context ctx, String ns) {
        File base = dir(ctx, ns);
        String name = Process.myPid() + "-" + System.currentTimeMillis() + "-"
                + Long.toHexString(System.nanoTime() & 0xffffffL);
        File d = new File(base, name);
        if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
            Log.w(TAG, "tmp session mkdir failed: " + d);
        }
        try {
            Util.atomicWriteText(new File(d, OWNER),
                    Process.myPid() + "\n" + System.currentTimeMillis() + "\n");
        } catch (Throwable t) {
            Log.w(TAG, "tmp owner write failed: " + d, t);
        }
        return d;
    }

    /** 各命名空间的寿命（只对**无主**的件生效；见类注释） */
    static long ttlMs(String ns) {
        if (NS_SHARE.equals(ns)) return 24L * 3600L * 1000L;
        if (NS_EXPORT.equals(ns)) return 24L * 3600L * 1000L;
        if (NS_PLUGIN_CACHE.equals(ns)) return 24L * 3600L * 1000L;
        return 60L * 60L * 1000L;
    }

    /** 这个路径是不是在 `app_hub/tmp` 下面（canonical 比较；拿不到 canonical 时按前缀） */
    static boolean isUnder(Context ctx, File f) {
        if (f == null) return false;
        File root = rootNoCreate(ctx);
        try {
            String base = root.getCanonicalPath() + File.separator;
            return f.getCanonicalPath().startsWith(base);
        } catch (Throwable t) {
            String base = root.getAbsolutePath() + File.separator;
            return f.getAbsolutePath().startsWith(base);
        }
    }

    // ── 删除（唯一实现：不跟符号链接）──────────────────────────────────────

    /** 只删这一份；文件 / 目录 / 链接都可以。**不跟随符号链接**。 */
    static boolean deleteNoFollow(File f) {
        if (f == null) return true;
        try {
            if (isLink(f)) return f.delete();
            if (f.isDirectory()) {
                File[] fs = f.listFiles();
                if (fs != null) {
                    for (File c : fs) deleteNoFollow(c);
                }
            }
            if (f.delete()) return true;
            f.setWritable(true);
            return f.delete();
        } catch (Throwable t) {
            Log.w(TAG, "tmp delete failed: " + f, t);
            return false;
        }
    }

    /**
     * 删掉一份临时件；如果是"一次会话"的目录（只剩 owner.pid 了），连会话目录一起收。
     *
     * <p>会话里还有别的件（例如批量导入还在用）⇒ 只删这一份，目录留给 {@link #sweep} 或后续清理。</p>
     */
    static void drop(Context ctx, File f) {
        if (f == null) return;
        deleteNoFollow(f);
        File p = f.getParentFile();
        if (p == null || !isUnder(ctx, p) || isLink(p) || !p.isDirectory()) return;
        File[] left = p.listFiles();
        if (left == null) return;
        boolean onlyOwner = true;
        for (File c : left) {
            if (c == null) continue;
            if (!c.isFile() || !OWNER.equals(c.getName())) {
                onlyOwner = false;
                break;
            }
        }
        if (!onlyOwner) return;
        deleteNoFollow(new File(p, OWNER));
        File[] now = p.listFiles();
        if (now != null && now.length == 0) p.delete();
    }

    /** 目录体积（不跟链接） */
    static long sizeNoFollow(File f) {
        if (f == null || isLink(f)) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] fs = f.listFiles();
        if (fs != null) {
            for (File c : fs) n += sizeNoFollow(c);
        }
        return n;
    }

    /**
     * 体积上限的**分母**：只算参与修剪的命名空间（`share` 不参与）。
     *
     * <p>⚠️ 不能直接用 {@link #sizeNoFollow}(root)：分享缓存那份大文件不参与清理、却算进总量
     * ⇒ 目标永远达不到，每次清扫都把别的命名空间删光（"分享一下 = 清空缓存"）。
     * 用户 2026-11-24 指出的正是这个。
     */
    static long capOwnedBytes(File root) {
        if (root == null || !root.isDirectory()) return 0;
        File[] list = root.listFiles();
        if (list == null) return 0;
        long total = 0;
        for (File f : list) {
            if (f == null || isLink(f) || !f.isDirectory()) continue;  // 只算可清的那些命名空间目录
            if (NS_SHARE.equals(f.getName())) continue;
            total += sizeNoFollow(f);
        }
        return total;
    }

    // ── 清扫 ──────────────────────────────────────────────────────────────

    /** pid 存活判据（可注入，自检用） */
    interface PidAlive {
        boolean alive(int pid);
    }

    private static final PidAlive REAL_ALIVE = new PidAlive() {
        @Override public boolean alive(int pid) {
            return pid > 0 && new File("/proc/" + pid).exists();
        }
    };

    /** 一次清扫的账（Recovery 报告用） */
    static final class Swept {
        int dirs;
        int files;
        long bytes;

        int total() {
            return dirs + files;
        }

        String brief() {
            return dirs + " dir(s) / " + files + " file(s) / " + Util.formatSize(bytes);
        }
    }

    /** 正式入口：真实时间 / 真实 pid / 上限读设置（默认 64 MB，见 {@link Config#hubTmpCapMb()}） */
    static Swept sweep(Context ctx) {
        return sweep(ctx, System.currentTimeMillis(), null, capBytes());
    }

    /** 体积上限（字节）。设置里存 MB，**最小 1 MB**（用户：「不让设成 0MB 的」）。 */
    static long capBytes() {
        return Math.max(1, Config.get().hubTmpCapMb()) * 1024L * 1024L;
    }

    /**
     * 清扫一次。
     *
     * @param now      "现在"（毫秒；自检注入用）
     * @param alive    pid 存活判据（null = 真实 `/proc`）
     * @param capBytes 总量上限（&le;0 = 不按总量清）
     */
    static Swept sweep(Context ctx, long now, PidAlive alive, long capBytes) {
        Swept out = new Swept();
        File root = rootNoCreate(ctx);
        if (!root.isDirectory()) return out;
        PidAlive live = alive == null ? REAL_ALIVE : alive;
        File[] nsDirs = root.listFiles();
        if (nsDirs == null) return out;

        for (File nsDir : nsDirs) {
            if (nsDir == null || isLink(nsDir) || !nsDir.isDirectory()) continue;
            String ns = nsDir.getName();
            long ttl = ttlMs(ns);
            File[] items = nsDir.listFiles();
            if (items == null) continue;
            for (File it : items) {
                if (it == null) continue;
                if (isLink(it)) {
                    // 只删链接本身（模组放的链接指向槽数据时，删链接 ≠ 删目标）
                    if (age(it, now) > ttl && deleteNoFollow(it)) {
                        out.files++;
                    }
                    continue;
                }
                if (it.isFile()) {
                    if (age(it, now) > ttl) {
                        long len = it.length();
                        if (deleteNoFollow(it)) {
                            out.files++;
                            out.bytes += len;
                        }
                    }
                    continue;
                }
                if (!it.isDirectory()) continue;
                int pid = ownerPid(it);
                if (pid > 0) {
                    // 主人明确：活着就一个字节都不动；死了就直接收（不需要宽限 —— owner.pid 已经写下了）
                    if (pid == Process.myPid() || live.alive(pid)) continue;
                    dropTree(it, out);
                    continue;
                }
                // 无主：先过 TTL（宽限 30 s 是 TTL 的下界，这里用 TTL 一条就够）
                if (age(it, now) <= Math.max(GRACE_MS, ttl)) continue;
                dropTree(it, out);
            }
        }

        // 总量上限：只清**散落文件**，最旧优先（会话目录由 owner.pid 那套自己收）
        if (capBytes > 0) trimToCap(ctx, now, capBytes, out);
        return out;
    }

    /**
     * 体积上限：只清**各命名空间根下的散落文件**（`plugin-export` / `export` …），
     * 会话目录由 owner.pid 那套自己收，活进程的一个字节都不动。
     *
     * <p>★ 为什么口径是散文件（第 426 轮，用户：「导出和分享不一样」）：拖到几百 MB 的正是
     * 插件导出这些平铺件；会话目录有主、有 TTL，冷启动也会收。
     *
     * <p>★ 分享缓存（`share`）**不参与**：一份分享包本身就可能大于上限，按体积清等于
     * 「刚分享完就盯上它」（用户 2026-11-24：「分享缓存还是算了，这东西太大了一个就容易超」）。
     * 它只按 {@link #ttlMs} 的 24 h 走。
     *
     * <p>保护：`.part`（正在写）、符号链接、1 h 内放下的、每个命名空间留最新 1 份
     * （该命名空间只剩一份时不再护着，否则上限永远不生效）。
     */
    private static void trimToCap(Context ctx, long now, long capBytes, Swept out) {
        File root = rootNoCreate(ctx);
        long total = capOwnedBytes(root);        // share 不算分母，否则永远清不到目标
        if (total <= capBytes) return;
        List<File> droppable = capCandidates(root, now);
        for (int i = 0; i < droppable.size() && total > capBytes; i++) {
            File it = droppable.get(i);                       // 最旧的在前
            long len = it.length();
            if (deleteNoFollow(it)) {
                out.files++;
                out.bytes += len;
                total -= len;
            }
        }
    }

    /**
     * 体积修剪的**候选列表**（最旧 → 新）—— 拆成纯函数，自检可以直接喂夹具
     * （真 tmp 根里可能有用户正在用的分享缓存，自检不能拿它当实验场）。
     *
     * <p>规则：每个命名空间（`tmp/<ns>/`）根下的**散落文件**，排除 `.part`（正在写）、
     * 符号链接、1 小时内放下的（可能正被别的 App 借出去读）；组里不止一份时留**最新 1 份**
     * （只剩一份时它是唯一一件，再护着上限就永远不生效）。
     *
     * <p>★ 分享缓存（`share`）整目录跳过 —— 一份分享包就可能超过上限，按体积清它没有意义
     * （用户 2026-11-24：「分享缓存还是算了，这东西太大了一个就容易超」）。
     *
     * <p>⚠️ "留最新 1 份"必须按**整组**算：先收全组、排序、再剔除最后一个。若一开始就按宽限
     * 过滤，组里只剩一件旧件时 `size()==1` ⇒ 一个都不删，上限永远不生效（2026-11-24 真机踩到）。
     */
    static List<File> capCandidates(File root, long now) {
        List<File> droppable = new ArrayList<>();
        if (root == null || !root.isDirectory()) return droppable;
        Map<String, List<File>> byNs = new LinkedHashMap<>();
        File[] nsDirs = root.listFiles();
        if (nsDirs == null) return droppable;
        for (File nsDir : nsDirs) {
            if (nsDir == null || isLink(nsDir) || !nsDir.isDirectory()) continue;
            if (NS_SHARE.equals(nsDir.getName())) continue;   // 分享缓存只按 TTL 走，见 trimToCap 的说明
            File[] items = nsDir.listFiles();
            if (items == null) continue;
            for (File it : items) {
                if (it == null || isLink(it) || !it.isFile()) continue;
                if (it.getName().endsWith(".part")) continue;         // 正在写
                List<File> g = byNs.get(nsDir.getName());
                if (g == null) {
                    g = new ArrayList<>();
                    byNs.put(nsDir.getName(), g);
                }
                g.add(it);
            }
        }
        for (List<File> g : byNs.values()) {
            Collections.sort(g, BY_AGE_ASC);
            // 每个 ns 留最新 1 份 —— **只在组里不止一份时**：刚分享出去的那份不能因为"最旧"被删；
            // 但组里就剩一件时它也是唯一一件，再护着上限就永远不生效（第 426 轮自检 ④d 钉的这条）。
            int guard = g.size() >= 2 ? 1 : 0;
            for (int i = 0; i < g.size() - guard; i++) {
                File f = g.get(i);
                if (age(f, now) <= TRIM_GRACE_MS) continue;   // 刚放下：可能正被借出读
                droppable.add(f);
            }
        }
        Collections.sort(droppable, BY_AGE_ASC);
        return droppable;
    }

    /** 体积修剪的排序：越旧越前（散文件按修改时间；目录名里的时刻对平铺件没意义） */
    private static final Comparator<File> BY_AGE_ASC = new Comparator<File>() {
        @Override public int compare(File a, File b) {
            return Long.compare(a.lastModified(), b.lastModified());
        }
    };

    // ── 小工具 ────────────────────────────────────────────────────────────

    /** 删一棵目录并把账记到 {@link Swept}（只统计真的删掉的） */
    private static void dropTree(File dir, Swept out) {
        long len = sizeNoFollow(dir);
        if (deleteNoFollow(dir)) {
            out.dirs++;
            out.bytes += len;
        }
    }

    private static boolean isLink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这一份的"年龄"：取 max(修改时刻, 目录名里的创建时刻)，避免把正在写的件判老 */
    private static long age(File f, long now) {
        long t = f.lastModified();
        // 会话目录名 = `<pid>-<started>-<rand>` ⇒ 第二段才是创建时刻
        String[] parts = f.getName().split("-");
        if (parts.length >= 2) {
            try {
                long started = Long.parseLong(parts[1]);
                t = Math.max(t, started);
            } catch (Throwable ignored) {
            }
        }
        return Math.max(0L, now - t);
    }

    /** 会话目录的 owner pid；owner 文件缺 / 坏时从目录名解析；都没有 ⇒ -1 */
    static int ownerPid(File dir) {
        String text = "";
        try {
            text = Util.readText(new File(dir, OWNER)).trim();
        } catch (Throwable ignored) {
        }
        String head = text.isEmpty() ? "" : text.split("\\s+")[0];
        if (head.isEmpty()) {
            String name = dir.getName();
            int dash = name.indexOf('-');
            head = dash > 0 ? name.substring(0, dash) : "";
        }
        try {
            return Integer.parseInt(head);
        } catch (Throwable t) {
            return -1;
        }
    }
}
