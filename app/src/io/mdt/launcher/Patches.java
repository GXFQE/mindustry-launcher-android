package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 补丁机制的**数据层**（补丁机制 v1，2026-11-10）。
 *
 * <h3>它负责什么（宿主只做机制，不生成补丁）</h3>
 * <ul>
 *   <li><b>存储</b>：导入后的补丁住在**内部存储** {@code app_hub/patches/<id>/<version>/}
 *       ——ART 只允许加载内部 + 只读的 dex（`ref/48` 的硬约束）；外部 hub 只放证据报告。</li>
 *   <li><b>匹配</b>：按**目标 APK 的 sha256** 精确匹配 target（不是版本号区间，理由见
 *       {@link PatchManifest} 的类注释）。</li>
 *   <li><b>启用状态</b>：按**启动项（实例）**存进 `config.json`（键 = `Versions.instanceKey`，版本 key + 槽；第 353 轮从"按版本"改成"按实例"）；
 *       一个版本可以启用多个补丁，但**改同一个类 / 同一个素材的组合必须被拒绝**。</li>
 *   <li><b>冲突检测</b>：判据 = 补丁 dex 里**实际定义的类**（{@link PatchDex}）+ 素材 zip 里
 *       **实际修改的 assets 路径**；清单里写的不算数（那只是单方面声明）。</li>
 *   <li><b>启动解析</b>：给 {@link Injector.Plan} 一份"这次要注入哪些 dex / 挂哪些素材包"。</li>
 * </ul>
 *
 * <h3>为什么不按设计草案做"引用计数"</h3>
 * 草案（`docs/plans/2026-10-28-补丁机制-ART路线.md` §3）设想的是"一份补丁文件被 N 个槽引用"。
 * 实现时补丁天然是**全局一份 + 版本级启用列表**（补丁按 APK 哈希匹配，不可能跨 APK 复制），
 * 所以根本没有"多份副本"需要计数 —— 启用/停用只改 config 里的引用，删记录时才动文件。
 * ⇒ 少一套计数器（少一类"数错了就删错文件"的 bug），语义不变。
 *
 * <h3>线程</h3>
 * {@link #importPackage} / {@link #sha256Of} 是重 IO（几十 MB），**调用方负责后台线程**
 * + {@link Work} 前台服务；{@link #enable} / {@link #disable} 只读写几百字节的 config。
 */
final class Patches {

    private static final String TAG = "MDTLauncher";

    /** 内部存储里的补丁库（`app_hub/patches`）。 */
    static final String STORE = "patches";

    /** 外部 hub 里的启动报告名（adb 可直接读；:game 进程写、主进程读）。 */
    static final String REPORT = "report-patch.txt";

    // 体积 / 数量上限：补丁是"类替换"，正常产品 1~20 MB；上限只是防坏包把盘写满。
    static final long MAX_DEX = 64L * 1024 * 1024;
    static final long MAX_ASSETS = 128L * 1024 * 1024;
    static final long MAX_TOTAL = 256L * 1024 * 1024;
    static final int MAX_ENTRIES = 4096;

    private Patches() {}

    // ══ 记录 ════════════════════════════════════════════════════════════════

    /** 一个已导入的补丁（= `<id>/<version>/` 目录 + 清单 + 每个 target 的文件）。 */
    static final class Record {
        final PatchManifest manifest;
        final File dir;
        final List<TargetFile> targets;
        /** 目录里文件齐全吗（dex / 素材在不在）。缺了仍保留记录，让用户看得见、删得掉。 */
        final boolean filesOk;

        Record(PatchManifest manifest, File dir, List<TargetFile> targets) {
            this.manifest = manifest;
            this.dir = dir;
            this.targets = Collections.unmodifiableList(targets);
            boolean ok = true;
            for (TargetFile t : targets) if (!t.exists()) ok = false;
            this.filesOk = ok;
        }

        String id() { return manifest.id; }
        String version() { return manifest.version; }
        String label() { return manifest.name; }

        /** config 里存的引用串：`id@version`（id / version 的字符集都不含 '@'）。 */
        String ref() { return manifest.id + "@" + manifest.version; }

        TargetFile targetFor(String apkSha256) {
            if (apkSha256 == null || apkSha256.isEmpty()) return null;
            String want = apkSha256.toLowerCase(Locale.ROOT);
            for (TargetFile t : targets) {
                if (t.spec.apkSha256.equals(want)) return t;
            }
            return null;
        }

        boolean applicable(String apkSha256) {
            return targetFor(apkSha256) != null;
        }
    }

    /** 一个 target 在本地盘上的文件（dex 必在；assets 可有可无）。类名列表惰性解析。 */
    static final class TargetFile {
        final PatchManifest.Target spec;
        final File dex;
        final File assets;
        private List<String> classes;
        private List<String> assetKeys;
        private String parseError;

        TargetFile(PatchManifest.Target spec, File dex, File assets) {
            this.spec = spec;
            this.dex = dex;
            this.assets = assets;
        }

        boolean exists() {
            return dex.isFile() && dex.length() > 0 && (assets == null || assets.isFile());
        }

        /**
         * 这个 dex 定义了**多少个类**（只读文件头的 `class_defs_size`，不解析字符串表）。
         *
         * <p>给列表 / 详情卡出"内容量"用；冲突检测仍走 {@link #classes()}（那个真要类名）。
         */
        int classCount() throws IOException {
            if (classes != null) return classes.size();
            if (parseError != null) throw new IOException(parseError);
            return PatchDex.classCount(readAll(dex, MAX_DEX));
        }

        /** 素材包里改了几个 `assets` 路径（剥前缀后的键数；没有素材包 = 0）。 */
        int assetCount() {
            try {
                return assetKeys().size();
            } catch (IOException e) {
                return 0;
            }
        }

        /** dex 文件（详情卡读大小用；记录可能已经缺文件，所以只返回引用、不保证存在）。 */
        File dexFile() { return dex; }

        /** 素材包文件（没有素材包时返回 null）。 */
        File assetsFile() { return assets; }

        /** dex 里定义的类（点分名）；读不出来抛 IOException（调用方翻成"补丁文件坏了"）。 */
        List<String> classes() throws IOException {
            if (classes != null) return classes;
            if (parseError != null) throw new IOException(parseError);
            try {
                byte[] bytes = readAll(dex, MAX_DEX);
                classes = PatchDex.classes(bytes);
                if (classes.isEmpty()) throw new IOException("dex defines no classes");
                return classes;
            } catch (IOException e) {
                parseError = e.getMessage();
                throw e;
            }
        }

        /** 素材包里改了哪些 assets 路径（**已剥掉 `assets/` 前缀**，与 AssetManager 里的键一致）。 */
        List<String> assetKeys() throws IOException {
            if (assetKeys != null) return assetKeys;
            if (assets == null) {
                assetKeys = Collections.emptyList();
                return assetKeys;
            }
            List<String> keys = Shipped.assetKeysOf(assets);
            assetKeys = keys;
            return assetKeys;
        }
    }

    // ══ 路径 ════════════════════════════════════════════════════════════════

    static File storeDir(Context ctx) {
        File d = new File(Paths.privateDir(ctx), STORE);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static File recordDir(Context ctx, String id, String version) {
        return new File(new File(storeDir(ctx), id), version);
    }

    /** 扫描补丁库（目录序 = id 字典序、version 字典序；坏记录跳过并记日志）。 */
    static List<Record> scan(Context ctx) {
        List<Record> out = new ArrayList<Record>();
        File[] ids = storeDir(ctx).listFiles();
        if (ids == null) return out;
        Arrays.sort(ids, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        for (File idDir : ids) {
            if (!idDir.isDirectory() || idDir.getName().startsWith(".")) continue;
            File[] vers = idDir.listFiles();
            if (vers == null) continue;
            Arrays.sort(vers, new Comparator<File>() {
                @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
            });
            for (File verDir : vers) {
                if (!verDir.isDirectory() || verDir.getName().startsWith(".")) continue;
                Record r = readRecord(verDir);
                if (r != null) out.add(r);
            }
        }
        return out;
    }

    /** 按目录读一条记录；清单坏了返回 null（不把半条记录塞给界面）。 */
    static Record readRecord(File dir) {
        File mf = new File(dir, PatchManifest.FILE);
        if (!mf.isFile()) return null;
        PatchManifest m;
        try {
            m = PatchManifest.parse(readAll(mf, 64 * 1024));
        } catch (IOException e) {
            Log.w(TAG, "patch manifest unreadable: " + mf + " (" + e + ")");
            return null;
        }
        List<TargetFile> files = new ArrayList<TargetFile>();
        for (PatchManifest.Target t : m.targets) {
            File dex = new File(dir, t.apkSha256 + ".dex");
            File assets = t.hasAssets() ? new File(dir, t.apkSha256 + ".assets.zip") : null;
            files.add(new TargetFile(t, dex, assets));
        }
        return new Record(m, dir, files);
    }

    static Record find(Context ctx, String id, String version) {
        for (Record r : scan(ctx)) {
            if (r.manifest.id.equals(id) && r.manifest.version.equals(version)) return r;
        }
        return null;
    }

    /** `id@version` → 记录（不存在返回 null）。 */
    static Record findByRef(Context ctx, String ref) {
        int at = ref == null ? -1 : ref.lastIndexOf('@');
        if (at <= 0 || at == ref.length() - 1) return null;
        return find(ctx, ref.substring(0, at), ref.substring(at + 1));
    }

    // ══ 导入 ════════════════════════════════════════════════════════════════

    /**
     * 导入一个补丁包（zip）。**重 IO：调用方放后台线程 + {@link Work} 前台服务。**
     *
     * <p>流程：校验整个包的条目名 → 读清单 → 查重 → 逐 target 抽 dex / 素材到临时目录
     * → dex 解析（必须合法且至少 1 个类）→ 素材包条目必须都在 `assets/` 下
     * → 清单原字节落盘 → 原子 rename 成正式记录。
     *
     * <p>失败时临时目录被清掉、**已有同名记录一个字节不动**（先见后改）。
     */
    static Record importPackage(Context ctx, File zipFile) throws PatchException {
        if (zipFile == null || !zipFile.isFile()) {
            throw new PatchException(PatchException.C_IO, zipFile == null ? "" : zipFile.getName());
        }
        ZipFile zip;
        try {
            zip = new ZipFile(zipFile);
        } catch (IOException e) {
            throw new PatchException(PatchException.C_BAD_ZIP, e.getMessage());
        }
        File tmp = null;
        try {
            long total = 0;
            int entries = 0;
            ZipEntry manifestEntry = null;
            Set<String> seen = new HashSet<String>();
            for (ZipEntry e : Collections.list(zip.entries())) {
                entries++;
                if (entries > MAX_ENTRIES) throw new PatchException(PatchException.C_TOO_BIG, "entries");
                String name = e.getName();
                if (!PatchManifest.safeRel(name)) {
                    throw new PatchException(PatchException.C_BAD_PATH, name);
                }
                // ★ B12：同名条目会让"清单取哪一个 / dex 取哪一个"取决于 ZipFile 的实现
                //   （实测 getEntry 取第一个、遍历见到两个）⇒ 读到的可能是不同副本。直接拒绝。
                if (!seen.add(name)) {
                    throw new PatchException(PatchException.C_BAD_ZIP, "duplicate entry: " + name);
                }
                if (PatchManifest.FILE.equals(name)) manifestEntry = e;
                long size = e.getSize();
                if (size > 0) total += size;
                if (total > MAX_TOTAL) throw new PatchException(PatchException.C_TOO_BIG, "total");
            }
            if (manifestEntry == null) throw new PatchException(PatchException.C_BAD_JSON, PatchManifest.FILE);

            byte[] manifestBytes = readEntry(zip, manifestEntry, 64 * 1024);
            PatchManifest m = PatchManifest.parse(manifestBytes);

            File dest = recordDir(ctx, m.id, m.version);
            if (dest.exists()) throw new PatchException(PatchException.C_DUP, m.id + " " + m.version);

            File root = storeDir(ctx);
            tmp = new File(root, ".tmp-" + System.currentTimeMillis() + "-"
                    + Integer.toHexString((int) (Math.random() * 0xFFFF)));
            deleteTree(tmp);
            if (!tmp.mkdirs()) throw new PatchException(PatchException.C_IO, "mkdir: " + tmp.getName());

            for (PatchManifest.Target t : m.targets) {
                ZipEntry dexEntry = zip.getEntry(t.dex);
                if (dexEntry == null) throw new PatchException(PatchException.C_MISSING_ENTRY, t.dex);
                File dexOut = new File(tmp, t.apkSha256 + ".dex");
                long n = copyEntry(zip, dexEntry, dexOut, MAX_DEX);
                if (n <= 0) throw new PatchException(PatchException.C_MISSING_ENTRY, t.dex);
                if (!PatchDex.looksLikeDex(readPrefix(dexOut, 4096))) {
                    throw new PatchException(PatchException.C_BAD_DEX, t.dex);
                }
                try {
                    // ★ 真解析一遍：冲突检测靠这份类表；解析不了就别让用户以为装好了
                    byte[] all = readAll(dexOut, MAX_DEX);
                    if (PatchDex.classes(all).isEmpty()) {
                        throw new PatchException(PatchException.C_BAD_DEX, t.dex + " (0 classes)");
                    }
                } catch (IOException e) {
                    throw new PatchException(PatchException.C_BAD_DEX, t.dex + ": " + e.getMessage());
                }
                dexOut.setReadOnly();

                if (t.hasAssets()) {
                    ZipEntry a = zip.getEntry(t.assets);
                    if (a == null) throw new PatchException(PatchException.C_MISSING_ENTRY, t.assets);
                    File assetsOut = new File(tmp, t.apkSha256 + ".assets.zip");
                    copyEntry(zip, a, assetsOut, MAX_ASSETS);
                    try {
                        Shipped.assetKeysOf(assetsOut);
                    } catch (IOException e) {
                        throw new PatchException(PatchException.C_BAD_ASSETS, t.assets + ": " + e.getMessage());
                    }
                    assetsOut.setReadOnly();
                }
            }

            File mfOut = new File(tmp, PatchManifest.FILE);
            writeBytes(mfOut, manifestBytes);
            mfOut.setReadOnly();

            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new PatchException(PatchException.C_IO, "mkdir: " + parent.getName());
            }
            if (!tmp.renameTo(dest)) {
                throw new PatchException(PatchException.C_IO, "rename: " + dest.getName());
            }
            tmp = null;   // 已就位
            Record r = readRecord(dest);
            if (r == null) throw new PatchException(PatchException.C_IO, dest.getName());
            Log.i(TAG, "patch imported: " + r.ref() + " targets=" + r.targets.size());
            return r;
        } catch (PatchException pe) {
            throw pe;
        } catch (IOException e) {
            throw new PatchException(PatchException.C_IO, e.getClass().getSimpleName());
        } finally {
            try { zip.close(); } catch (IOException ignored) { }
            if (tmp != null) deleteTree(tmp);
        }
    }

    /**
     * 删一条补丁记录 —— **挪进中转站**（不是直接删；第 357 轮起，与其它删除一致）。
     *
     * <p>★ 为什么改：地图 / 存档 / 蓝图 / 模组 / 插件 / 版本 / 整槽 都是"先挪进中转站、能放回来"，
     *   只有补丁是裸删 —— 用户问「游戏补丁中转站保留个数按照哪个算的啊」，答案是"根本没有中转站"。
     *   现在删除 = 挪到 `<hub>/patches-trash/`，保留份数走**整槽那一档**（默认 3，
     *   `Trash.keepFor(Kind.PATCH)`），能从「中转站」放回补丁库（`Trash.restorePatch`）。
     *
     * <p>**启用中的补丁拒绝删**（先停用，避免下回启动找不到）。
     * <p>挪进去时 `origName` 用 `id@version`（恢复时按最后一个 '@' 拆回两级目录）；
     * 引用串的字符集不含 '@'（见 {@link Record#ref()}），所以能无歧义拆。
     */
    static void delete(Context ctx, Record r) throws PatchException {
        if (r == null) throw new PatchException(PatchException.C_RECORD_GONE, "");
        // 2026-11-12（第 359 轮）：补丁库是 hub 全局数据（不变式 ④）—— 删除一份**没被任何实例
        // 启用**的补丁文件不影响正在跑的游戏（它的 dex 在启动时就加载了）。"被启用"由下面的
        // C_IN_USE 拦（按实例键的引用）；所以不再用全局 gameAlive 拦（那会误挡无关操作）。
        List<String> owners = Config.get().patchRefOwners(r.ref());
        if (!owners.isEmpty()) {
            // ★ 第 353 轮：启用状态按**实例**存，owner 键里带 NUL ⇒ 上屏前翻译成人话
            //   （游戏名 + 槽）；否则 C_IN_USE 的 detail 会出现一个带控制字符的怪串。
            List<String> labels = new ArrayList<String>();
            for (String o : owners) labels.add(instanceLabel(ctx, o));
            throw new PatchException(PatchException.C_IN_USE, join(labels));
        }
        File trash = Trash.patchesDir(ctx);
        File dst = new File(trash, Trash.nameFor("", r.id() + "@" + r.version()));
        if (!Trash.move(r.dir, dst)) {
            // ⚠️ Trash.move 失败时**源还在**（它只在复制校验通过后才删源）⇒ 记录与文件都没丢
            throw new PatchException(PatchException.C_IO, r.dir.getName());
        }
        Trash.pruneIn(trash);          // 保留份数 = 整槽那一档（默认 3）
        // 空 id 目录顺手收掉（只删空目录；失败无害）
        File idDir = r.dir.getParentFile();
        if (idDir != null) {
            String[] left = idDir.list();
            if (left != null && left.length == 0) idDir.delete();
        }
        Log.i(TAG, "patch moved to trash: " + r.ref());
    }

    // ══ 启用 / 停用 ═════════════════════════════════════════════════════════

    /**
     * 实例键 → 给人看的一行（`游戏名 · 槽「X」`）。
     *
     * <p>第 353 轮：启用状态按 {@link Versions#instanceKey}（版本 key + NUL + 槽）存，
     * 键里有控制字符、不能直接上屏；「删补丁被占用」那条 detail 需要列出**哪些启动项**在用。
     * 找不到对应版本时回落成"版本 key · 槽"（不崩、不隐藏）。
     */
    static String instanceLabel(Context ctx, String ikey) {
        String vkey = Versions.instanceVkey(ikey);
        String slot = Versions.instanceSlot(ikey);
        String name = vkey;
        try {
            for (Versions.Entry e : Versions.scanAll(ctx)) {
                if (e != null && e.key().equals(vkey) && Versions.slotFor(e).equals(slot)) {
                    name = e.displayLabel();
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (slot.isEmpty()) return name;
        return Trans.get(ctx, R.string.patch_instance_fmt, name, slot);
    }

    static List<String> enabledRefs(Context ctx, String ikey) {
        return Config.get().patchRefs(ikey);
    }

    static List<Record> enabledRecords(Context ctx, String ikey) {
        List<Record> out = new ArrayList<Record>();
        for (String ref : Config.get().patchRefs(ikey)) {
            Record r = findByRef(ctx, ref);
            if (r != null) out.add(r);
        }
        return out;
    }

    /**
     * 启用一个补丁。**所有拒绝都是 {@link PatchException}**（码 → 人话在 {@link PatchText}）：
     * 游戏在跑 / 不适用于这个 APK / 文件缺失 / 与已启用的冲突 / 已经启用。
     *
     * <p>★ "先查再写"在同一个 synchronized(config) 里完成（Config 的方法是同步的）。
     */
    static void enable(Context ctx, String ikey, Record r, String apkSha256) throws PatchException {
        if (r == null) throw new PatchException(PatchException.C_RECORD_GONE, "");
        // 2026-11-12（第 359 轮）：按**实例键 (V,S)** 判（以前是全局 gameAlive：别的实例在跑也拦）。
        // 启用状态本来就按实例存（第 353 轮）⇒ 门禁维度必须跟它一致。
        if (WriteGate.blockedInstance(ctx, ikey)) {
            throw new PatchException(PatchException.C_GAME_ALIVE, "");
        }
        if (!r.filesOk) throw new PatchException(PatchException.C_MISSING, r.label());
        if (apkSha256 == null || apkSha256.isEmpty()) {
            throw new PatchException(PatchException.C_NO_MATCH, r.label());
        }
        if (!r.applicable(apkSha256)) throw new PatchException(PatchException.C_NO_MATCH, r.label());
        // ★ 顺序（B4）：先判"已经启用"再查冲突 —— 反过来的话同一条记录会被 conflicts 当成
        //   "与已启用的补丁冲突"，用户看到「与已启用的补丁冲突：它自己」。
        List<String> refs = Config.get().patchRefs(ikey);
        if (refs.contains(r.ref())) throw new PatchException(PatchException.C_ENABLED, "");
        List<String> names = conflicts(ctx, ikey, r, apkSha256);
        if (!names.isEmpty()) throw new PatchException(PatchException.C_CONFLICT, join(names));
        refs.add(r.ref());
        Config.get().setPatchRefs(ikey, refs);
        Log.i(TAG, "patch enabled: " + r.ref() + " for " + ikey);
    }

    static void disable(Context ctx, String ikey, Record r) throws PatchException {
        if (r == null) throw new PatchException(PatchException.C_RECORD_GONE, "");
        // 2026-11-12（第 359 轮）：与 enable 对称 —— 这是"写实例级状态"，同一把门禁。
        if (WriteGate.blockedInstance(ctx, ikey)) {
            throw new PatchException(PatchException.C_GAME_ALIVE, "");
        }
        List<String> refs = Config.get().patchRefs(ikey);
        if (!refs.remove(r.ref())) return;
        Config.get().setPatchRefs(ikey, refs);
        Log.i(TAG, "patch disabled: " + r.ref() + " for " + ikey);
    }

    /**
     * 候选补丁与"这个版本已启用的补丁"之间的冲突，返回**对方的名字**（给用户看）。
     *
     * <p>三条判据（任一命中就算冲突）：
     * <ol>
     *   <li>**同一个补丁的另一个版本**已启用（同一逻辑补丁的两个版本同时生效没有意义）；</li>
     *   <li>两边**实际定义的类**有交集（后注入的静默赢 ⇒ 另一个的部分改动失效）；</li>
     *   <li>两边**修改的素材路径**有交集（后挂的静默覆盖）。</li>
     * </ol>
     * 冲突检测只用**能匹配当前 APK** 的 target —— 不适用于这版的补丁本来就不会被注入，
     * 拿它去拦会让用户莫名其妙。
     *
     * <p>⚠️ 候选自己的 dex 解析不了时**抛 {@link PatchException#C_BAD_DEX}**（B4）：
     * 旧实现返回"自己跟自己冲突"，用户会看到「与已启用的补丁冲突：它自己」。
     * 已启用集合里如果有同一条记录（同 ref），由 {@link #enable} 在前面用 `C_ENABLED` 拦掉。
     */
    static List<String> conflicts(Context ctx, String ikey, Record cand, String apkSha256)
            throws PatchException {
        List<String> out = new ArrayList<String>();
        TargetFile mine = cand.targetFor(apkSha256);
        if (mine == null) return out;
        Set<String> myClasses;
        Set<String> myAssets;
        try {
            myClasses = new HashSet<String>(mine.classes());
            myAssets = new HashSet<String>(mine.assetKeys());
        } catch (IOException e) {
            throw new PatchException(PatchException.C_BAD_DEX, cand.label() + ": " + e.getMessage());
        }
        for (Record other : enabledRecords(ctx, ikey)) {
            if (other.ref().equals(cand.ref())) continue;   // 自己不算冲突（enable 另有 C_ENABLED）
            if (other.id().equals(cand.id())) {
                out.add(other.label() + " (" + other.version() + ")");
                continue;
            }
            TargetFile t = other.targetFor(apkSha256);
            if (t == null) continue;         // 这版用不到它 ⇒ 不拦
            if (!t.exists()) continue;       // 文件坏了：启动时会被跳过，不拦新补丁
            boolean hit = false;
            try {
                for (String c : t.classes()) {
                    if (myClasses.contains(c)) { hit = true; break; }
                }
                if (!hit) {
                    for (String a : t.assetKeys()) {
                        if (myAssets.contains(a)) { hit = true; break; }
                    }
                }
            } catch (IOException e) {
                continue;
            }
            if (hit) out.add(other.label() + " (" + other.version() + ")");
        }
        return out;
    }

    /** 清掉 config 里指向"已不存在的记录"的引用（删过目录 / 手工清过盘）。返回清掉的条数。 */
    static int pruneStale(Context ctx) {
        int removed = 0;
        for (String ikey : Config.get().patchRefVkeys()) {
            List<String> keep = new ArrayList<String>();
            for (String ref : Config.get().patchRefs(ikey)) {
                if (findByRef(ctx, ref) != null) keep.add(ref);
                else removed++;
            }
            if (keep.size() != Config.get().patchRefs(ikey).size()) {
                Config.get().setPatchRefs(ikey, keep);
            }
        }
        return removed;
    }

    static int enabledCount(Context ctx, String ikey) {
        return enabledRecords(ctx, ikey).size();
    }

    /**
     * 每个**启动项（实例）键**上**启用了几条记录还在的补丁**（主界面列表一次算完，空的不进 map）。
     *
     * <p>为什么不直接数 config 里的 refs：那是"配置说启用了几条"，
     * 手工清过盘 / 删除失败时会留下指向不存在记录的孤儿引用（B10 同一个坑）。
     * 这里先扫一遍补丁库、只数**记录还在**的 —— 与补丁页显示的"已启用 N 个"同一口径。
     */
    static Map<String, Integer> enabledCounts(Context ctx) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        Set<String> refs = new HashSet<String>();
        for (Record r : scan(ctx)) refs.add(r.ref());
        for (String ikey : Config.get().patchRefVkeys()) {
            int n = 0;
            for (String ref : Config.get().patchRefs(ikey)) {
                if (refs.contains(ref)) n++;
            }
            if (n > 0) out.put(ikey, n);
        }
        return out;
    }

    // ══ 启动解析 ════════════════════════════════════════════════════════════

    /** 这次启动要注入的一个补丁。 */
    static final class Loaded {
        final Record record;
        final TargetFile target;
        Loaded(Record record, TargetFile target) {
            this.record = record;
            this.target = target;
        }
    }

    /** 一个"没应用"的原因（码 + 定位；给人话由 {@link PatchText} 负责）。 */
    static final class Problem {
        final String code;
        final String detail;
        Problem(String code, String detail) {
            this.code = code;
            this.detail = oneLine(detail);
        }
        String line() { return code + "|" + detail; }
    }

    static final class Resolved {
        String apkSha = "";
        final List<Loaded> loaded = new ArrayList<Loaded>();
        final List<Problem> problems = new ArrayList<Problem>();
    }

    /**
     * 解析"这个版本这次启动该带哪些补丁"。
     *
     * <p>★ 只在**真有启用记录**时才去算 APK 的 sha256（80 MB 一遍约 0.2~0.5 s）——
     * 没启用任何补丁的人不该为这个功能付启动代价。
     *
     * <p>启动期还会再查一遍冲突（防御性）：配置文件被手工改过 / 旧版本写的数据可能绕过界面那道门。
     * 命中的后一个补丁**整条跳过**（不阻塞启动，按设计草案 §11 第 5 问的"跳过整个补丁"）。
     */
    static Resolved resolve(Context ctx, File apk, String ikey) {
        Resolved res = new Resolved();
        List<String> refs = Config.get().patchRefs(ikey);
        if (refs.isEmpty() || apk == null || !apk.isFile()) return res;
        try {
            res.apkSha = sha256Of(apk);
        } catch (IOException e) {
            res.problems.add(new Problem(PatchException.C_IO, apk.getName()));
            return res;
        }
        Set<String> usedClasses = new HashSet<String>();
        Set<String> usedAssets = new HashSet<String>();
        Set<String> usedIds = new HashSet<String>();
        for (String ref : refs) {
            Record r = findByRef(ctx, ref);
            if (r == null) {
                res.problems.add(new Problem(PatchException.C_RECORD_GONE, ref));
                continue;
            }
            TargetFile t = r.targetFor(res.apkSha);
            if (t == null) {
                res.problems.add(new Problem(PatchException.C_NO_MATCH, r.label()));
                continue;
            }
            if (!t.exists()) {
                res.problems.add(new Problem(PatchException.C_MISSING, r.label()));
                continue;
            }
            List<String> cls;
            List<String> keys;
            try {
                cls = t.classes();
                keys = t.assetKeys();
            } catch (IOException e) {
                res.problems.add(new Problem(PatchException.C_BAD_DEX, r.label()));
                continue;
            }
            if (usedIds.contains(r.id())) {
                res.problems.add(new Problem(PatchException.C_CONFLICT_LAUNCH, r.label()));
                continue;
            }
            String dup = firstCommon(usedClasses, cls);
            if (dup == null) dup = firstCommon(usedAssets, keys);
            if (dup != null) {
                res.problems.add(new Problem(PatchException.C_CONFLICT_LAUNCH, r.label() + " (" + dup + ")"));
                continue;
            }
            usedIds.add(r.id());
            usedClasses.addAll(cls);
            usedAssets.addAll(keys);
            res.loaded.add(new Loaded(r, t));
        }
        return res;
    }

    private static String firstCommon(Set<String> used, List<String> mine) {
        for (String s : mine) if (used.contains(s)) return s;
        return null;
    }

    // ══ 启动报告（:game 写 → 主进程读）══════════════════════════════════════

    /**
     * 写/清 `hub/report-patch.txt`：**只记"这次启动有补丁没应用"**，没有问题时把旧文件删掉
     * （否则上次的失败会永远挂在界面上 —— "过期结论"是最容易误导人的那种）。
     */
    static void writeLaunchReport(Context ctx, String ikey, Resolved res) {
        if (res == null || res.problems.isEmpty()) {
            File old = new File(Data.hubDir(ctx), REPORT);
            if (old.isFile()) old.delete();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# patch launch report ").append(new java.util.Date()).append('\n');
        sb.append("# ikey = ").append(ikey == null ? "" : ikey).append('\n');
        for (Problem p : res.problems) sb.append(p.line()).append('\n');
        try {
            Util.atomicWriteText(new File(Data.hubDir(ctx), REPORT), sb.toString());
        } catch (IOException e) {
            Log.w(TAG, "write patch report failed: " + e);
        }
    }

    /** 读回上次启动的问题（主进程界面用）；没有报告/空报告返回空表。 */
    static List<Problem> launchProblems(Context ctx) {
        List<Problem> out = new ArrayList<Problem>();
        File f = new File(Data.hubDir(ctx), REPORT);
        if (!f.isFile()) return out;
        try {
            String text = Util.readText(f);
            for (String line : text.split("\\r?\\n")) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int bar = line.indexOf('|');
                if (bar < 0) out.add(new Problem(line, ""));
                else out.add(new Problem(line.substring(0, bar), line.substring(bar + 1)));
            }
        } catch (IOException e) {
            Log.w(TAG, "read patch report failed: " + e);
        }
        return out;
    }

    /** 清掉报告（界面点了"知道了"） */
    static void clearLaunchReport(Context ctx) {
        File f = new File(Data.hubDir(ctx), REPORT);
        if (f.isFile()) f.delete();
    }

    // ══ 首次启用的告知一次 ══════════════════════════════════════════════════

    static boolean warned(Context ctx) {
        return Config.get().patchWarned();
    }

    static void markWarned(Context ctx) {
        Config.get().setPatchWarned();
    }

    // ══ 工具 ════════════════════════════════════════════════════════════════

    /**
     * 文件 sha256（小写十六进制）。
     *
     * <p>⚠️ 第 345 轮独立校验 B11：**不做缓存**。旧实现按 `路径|长度|mtime` 缓存 —— 文件被替换成
     * 长度与 mtime 都相同的内容（同一秒内的 cp -p / 原子替换）时会返回**旧哈希**，而这里的返回值
     * 决定"补丁要不要应用"，静默拿到旧值 = 拿错补丁去顶新 APK（直接崩游戏）。80 MB 一遍 ~0.2~0.5 s，
     * 且只在"真有启用记录"时才走，正确性不值得为它冒险。
     */
    static String sha256Of(File f) throws IOException {
        if (f == null || !f.isFile()) throw new IOException("no file: " + f);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
        return hex(md.digest());
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    static String shortHash(String sha) {
        return sha == null || sha.length() < 12 ? "" : sha.substring(0, 12);
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 把一段外部数据压成**单行**（B9）：启动报告是 `code|detail` 的行格式，detail 里一旦有换行
     * 就会多出一行、被读回时当成另一条 problem（补丁名来自不可信清单）。
     * 同时把分隔符 `|` 换掉（否则读回时 detail 被截断）。
     */
    static String oneLine(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c == '|') sb.append(' ');
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        // 压缩连续空格（外部名字里塞几十个空格会让报告/界面难读）
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }

    private static byte[] readEntry(ZipFile z, ZipEntry e, long max) throws IOException, PatchException {
        InputStream in = z.getInputStream(e);
        try {
            byte[] b = new byte[(int) Math.min(max == 0 ? 4096 : max, 64 * 1024)];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            if (n == b.length && in.read() >= 0) throw new PatchException(PatchException.C_TOO_BIG, e.getName());
            byte[] out = new byte[n];
            System.arraycopy(b, 0, out, 0, n);
            return out;
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static long copyEntry(ZipFile z, ZipEntry e, File out, long max)
            throws IOException, PatchException {
        InputStream in = z.getInputStream(e);
        OutputStream os = new FileOutputStream(out);
        long total = 0;
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new PatchException(PatchException.C_TOO_BIG, e.getName());
                os.write(buf, 0, n);
            }
        } finally {
            try { os.close(); } catch (IOException ignored) { }
            try { in.close(); } catch (IOException ignored) { }
        }
        return total;
    }

    private static byte[] readAll(File f, long max) throws IOException {
        long len = f.length();
        if (len > max) throw new IOException("too big: " + len);
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) len];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            if (n != b.length) throw new IOException("short read: " + f.getName());
            return b;
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** 读文件头（最多 `max` 字节；短文件按实际长度返回）—— 只用于 dex magic 这种前缀判断。 */
    private static byte[] readPrefix(File f, int max) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) Math.min(max, Math.max(0, f.length()))];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            if (n == 0) throw new IOException("empty: " + f.getName());
            byte[] out = new byte[n];
            System.arraycopy(b, 0, out, 0, n);
            return out;
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static void writeBytes(File f, byte[] b) throws IOException {
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(b);
            os.flush();
        } finally {
            try { os.close(); } catch (IOException ignored) { }
        }
    }

    /** 递归删除（先放开只读位 —— 补丁 dex 是只读的，不放开会静默残留）。 */
    static boolean deleteTree(File f) {
        if (f == null || !f.exists()) return true;
        boolean ok = true;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) ok = deleteTree(k) & ok;
        }
        f.setWritable(true);
        if (!f.delete()) {
            Log.w(TAG, "delete failed: " + f);
            return false;
        }
        return ok;
    }

    // ══ 素材包辅助（纯逻辑也在这里，自检可单独喂文件）══════════════════════════

    /**
     * 素材（assets）增量包的规矩：**条目必须都在 `assets/` 下**（真机实测：根布局的 zip
     * 挂得上、但游戏读不到 —— `FileNotFoundException`，见设计草案 §5）。
     */
    static final class Shipped {
        private Shipped() {}

        static List<String> assetKeysOf(File zipFile) throws IOException {
            if (zipFile == null || !zipFile.isFile()) throw new IOException("no assets zip");
            ZipFile z;
            try {
                z = new ZipFile(zipFile);
            } catch (IOException e) {
                throw new IOException("not a zip: " + e.getMessage());
            }
            try {
                LinkedHashSet<String> keys = new LinkedHashSet<String>();
                int n = 0;
                for (ZipEntry e : Collections.list(z.entries())) {
                    n++;
                    if (n > MAX_ENTRIES) throw new IOException("too many entries");
                    String name = e.getName();
                    if (name.endsWith("/")) continue;       // 目录条目：内容靠文件条目判定
                    if (!name.startsWith("assets/")) {
                        throw new IOException("entry not under assets/: " + name);
                    }
                    if (!PatchManifest.safeRel(name)) {
                        throw new IOException("unsafe entry: " + name);
                    }
                    keys.add(name.substring("assets/".length()));
                }
                if (keys.isEmpty()) throw new IOException("no asset files");
                return new ArrayList<String>(keys);
            } finally {
                try { z.close(); } catch (IOException ignored) { }
            }
        }
    }
}
