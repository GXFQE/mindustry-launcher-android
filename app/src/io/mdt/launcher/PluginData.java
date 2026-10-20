package io.mdt.launcher;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.SlotInfo;

/**
 * 插件只读数据面的**唯一实现**（第 161 轮，接口 v5）。
 *
 * <p>分成三层：
 * <ol>
 *   <li>{@link #slots}：所有槽；</li>
 *   <li>{@link #contents}：任意槽的存档 / 地图 / 模组 / 蓝图 —— 复用宿主自己的扫描器
 *       （{@link Maps#scan} / {@link Blueprints#scan} / {@link Mods#scan} / {@link MsavMeta}），
 *       插件不用自己认 zip / APK / 元数据；</li>
 *   <li>{@link #open}：把「槽内文件」与「包内条目」统一成一条流。</li>
 * </ol>
 *
 * <p>★ 不在主线程做重活：扫描会读文件头，插件必须自己放到后台线程。
 * 这里的方法都返回**新表**，不改宿主状态。
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
final class PluginData {

    private PluginData() { }

    static List<SlotInfo> slots(Context ctx) {
        List<SlotInfo> out = new ArrayList<SlotInfo>();
        if (ctx == null) return out;
        List<String> names = new ArrayList<String>(Data.slotNames(ctx));
        String cur = Data.currentSlot(ctx);
        if (cur != null && !cur.trim().isEmpty()) {
            names.remove(cur);
            names.add(0, cur);                 // 当前槽放最前，其余保持名字序
        }
        File ext = Paths.externalRoot(ctx);
        String extPath = ext == null ? null : ext.getAbsolutePath();
        for (String name : names) {
            File dir = Data.dirOf(ctx, name);
            String path = dir == null ? null : dir.getAbsolutePath();
            boolean legacy = path != null && extPath != null && path.equals(extPath);
            out.add(new SlotInfo(name, path, name.equals(cur), legacy));
        }
        return out;
    }

    static List<ItemInfo> contents(Context ctx, String slot, String kind) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (ctx == null || kind == null) return out;
        String s = slot == null || slot.trim().isEmpty() ? Data.currentSlot(ctx) : slot.trim();
        if (s == null || s.isEmpty()) return out;
        String k = kind.trim().toLowerCase(Locale.ROOT);
        try {
            if (ItemInfo.KIND_SAVES.equals(k)) scanSaves(ctx, s, out);
            else if (ItemInfo.KIND_MAPS.equals(k)) scanMaps(ctx, s, out);
            else if (ItemInfo.KIND_MODS.equals(k)) scanMods(ctx, s, out);
            else if (ItemInfo.KIND_BLUEPRINTS.equals(k)) scanBlueprints(ctx, s, out);
        } catch (Throwable ignored) {
            // 某一种类别坏了不影响别的类别；返回已经拿到的那些
        }
        return out;
    }

    /**
     * 游戏这一轮会不会加载模组（v8 `PluginApi.modsSkipped()` 的**唯一实现**）。
     *
     * <p>判据只有一处：`Mods.scan(...).skipModLoading`（= 上次启动崩过 + `modcrashdisable`）。
     * 插件别自己拼 `launchid.dat` 与设置的组合 —— 那条判据在 {@code Mods} 里，写两处必漂。
     */
    static boolean modsSkipped(Context ctx, String slot) {
        if (ctx == null) return false;
        try {
            String s = slot == null || slot.trim().isEmpty() ? Data.currentSlot(ctx) : slot.trim();
            if (s == null || s.isEmpty()) return false;
            Mods.Scan scan = Mods.scan(ctx, s);
            return scan != null && scan.skipModLoading;
        } catch (Throwable t) {
            return false;
        }
    }

    static List<ItemInfo> pageItems(Context ctx, String page, String slot, File selected) {
        if (ctx == null || page == null) return new ArrayList<ItemInfo>();
        if (ItemInfo.KIND_SAVES.equals(page)) return contents(ctx, slot, ItemInfo.KIND_SAVES);
        if (ItemInfo.KIND_MAPS.equals(page)) return contents(ctx, slot, ItemInfo.KIND_MAPS);
        if (ItemInfo.KIND_MODS.equals(page)) return contents(ctx, slot, ItemInfo.KIND_MODS);
        if (ItemInfo.KIND_BLUEPRINTS.equals(page)) return contents(ctx, slot, ItemInfo.KIND_BLUEPRINTS);
        if ("slots".equals(page)) {
            List<ItemInfo> out = new ArrayList<ItemInfo>();
            for (SlotInfo si : slots(ctx)) {
                Map<String, String> ex = new LinkedHashMap<String, String>();
                ex.put("current", String.valueOf(si.current));
                ex.put("legacy", String.valueOf(si.legacy));
                out.add(new ItemInfo(ItemInfo.KIND_SLOT, si.name, "", si.path,
                        null, null, 0L, 0L, "", null, true,
                        false, ex));
            }
            return out;
        }
        if ("map-detail".equals(page)) {
            // 槽内文件能对上的 ⇒ 给**扫描出来的完整条目**（标题 / 星球 / 元数据都在）；
            // 对不上（游戏 / 模组包里的）仍然是 null —— 那类条目由 v9 `selectedItem()` 直给。
            if (selected != null) {
                for (ItemInfo it : contents(ctx, slot, ItemInfo.KIND_MAPS)) {
                    if (it.path != null && selected.getAbsolutePath().equals(it.path)) {
                        List<ItemInfo> hit = new ArrayList<ItemInfo>();
                        hit.add(it);
                        return hit;
                    }
                }
            }
            return one(ctx, selected, ItemInfo.KIND_MAPS);
        }
        if ("blueprint-detail".equals(page)) return one(ctx, selected, ItemInfo.KIND_BLUEPRINTS);
        if ("main".equals(page)) return versions(ctx);
        if ("settings".equals(page)) return settingItems(ctx);
        if ("logs".equals(page)) return logs(ctx);
        if ("trash".equals(page)) return trash(ctx);
        return new ArrayList<ItemInfo>();      // 插件页 / 启动页没有内容数据
    }

    static List<ItemInfo> selectedItems(Context ctx, String page, String slot, File selected) {
        if ("map-detail".equals(page) || "blueprint-detail".equals(page)) {
            return one(ctx, selected,
                    "map-detail".equals(page) ? ItemInfo.KIND_MAPS : ItemInfo.KIND_BLUEPRINTS);
        }
        // 四个内容页的勾选是 BatchIo.pickMulti 的临时弹窗状态，页面不保存
        return new ArrayList<ItemInfo>();
    }

    /**
     * 第 187 轮（v7）：{@code pick(kind)} 的取数——**只读快照**，与 {@link #contents} 同源。
     *
     * <p>四种内容走 {@link #contents}（复用宿主扫描器）；另外支持日志 / 中转站 / 版本
     * （就是页面上那三份清单）。认不出的 kind ⇒ 空表（不抛、不崩）。
     *
     * <p>⚠️ 会读文件头 / 开包，调用方必须放后台线程。
     */
    static List<ItemInfo> pickItems(Context ctx, String slot, String kind) {
        if (ctx == null || kind == null) return new ArrayList<ItemInfo>();
        String k = kind.trim().toLowerCase(Locale.ROOT);
        if (ItemInfo.KIND_SAVES.equals(k) || ItemInfo.KIND_MAPS.equals(k)
                || ItemInfo.KIND_MODS.equals(k) || ItemInfo.KIND_BLUEPRINTS.equals(k)) {
            return contents(ctx, slot, k);
        }
        if (ItemInfo.KIND_LOGS.equals(k) || "log".equals(k)) return logs(ctx);
        if (ItemInfo.KIND_TRASH.equals(k)) return trash(ctx);
        if (ItemInfo.KIND_VERSION.equals(k) || "versions".equals(k)) return versions(ctx);
        return new ArrayList<ItemInfo>();
    }

    // ---- 设置 / 日志 / 中转站 / 版本 ------------------------------------------

    static Map<String, String> settings(Context ctx) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (ctx == null) return out;
        Config c = Config.get();
        try {
            c.reload(ctx);
        } catch (Throwable ignored) {
        }
        out.put("config_version", String.valueOf(Config.CONFIG_VERSION));
        out.put("theme_mode", String.valueOf(c.themeMode()));
        out.put("app_language", safe(c.appLanguage()));
        out.put("default_slot", safe(c.defaultSlot()));
        out.put("max_log_files", String.valueOf(c.maxLogFiles()));
        out.put("auto_clean_redundant", String.valueOf(c.autoCleanRedundant()));
        out.put("last_version", safe(c.lastVersion()));
        List<String> slots = new ArrayList<String>(Data.slotNames(ctx));
        String cur = Data.currentSlot(ctx);
        if (cur != null && !cur.isEmpty() && !slots.contains(cur)) slots.add(cur);
        for (String s : slots) {
            Config.BackupPolicy p = c.backupPolicy(s);
            out.put("backup." + s + ".enabled", String.valueOf(p.enabled));
            out.put("backup." + s + ".min_minutes", String.valueOf(p.minMinutes));
            out.put("backup." + s + ".max_backups", String.valueOf(p.maxBackups));
        }
        return Collections.unmodifiableMap(out);
    }

    static List<ItemInfo> logs(Context ctx) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (ctx == null) return out;
        for (File f : LogActivity.pluginLogFiles(ctx)) {
            Map<String, String> ex = new LinkedHashMap<String, String>();
            String cat = categoryOfLog(ctx, f);
            ex.put("category", cat);
            out.add(new ItemInfo(ItemInfo.KIND_LOGS, f.getName(), cat,
                    f.getAbsolutePath(), null, null, f.length(), f.lastModified(),
                    "logs", null, false, f.canRead(), ex));
        }
        return out;
    }

    static List<ItemInfo> trash(Context ctx) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (ctx == null) return out;
        String[] cats = {"maps", "mods", "saves", "slots", "schematics", "plugins"};
        File[] dirs = Trash.allDirs(ctx);
        for (int i = 0; i < dirs.length && i < cats.length; i++) {
            File dir = dirs[i];
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) continue;
            List<File> list = new ArrayList<File>();
            for (File f : fs) list.add(f);
            Collections.sort(list, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return b.getName().compareTo(a.getName());
                }
            });
            for (File f : list) {
                Map<String, String> ex = new LinkedHashMap<String, String>();
                ex.put("category", cats[i]);
                out.add(new ItemInfo(ItemInfo.KIND_TRASH, f.getName(), cats[i],
                        f.getAbsolutePath(), null, null,
                        f.isDirectory() ? 0L : f.length(), f.lastModified(),
                        "trash", null, f.isDirectory(), f.isFile() && f.canRead(), ex));
            }
        }
        return out;
    }

    static List<ItemInfo> versions(Context ctx) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (ctx == null) return out;
        List<Versions.Entry> entries;
        try {
            entries = Versions.scanAll(ctx);
        } catch (Throwable t) {
            return out;
        }
        for (Versions.Entry e : entries) {
            if (e == null) continue;
            File apk = e.apkPath == null || e.apkPath.isEmpty() ? null : new File(e.apkPath);
            Map<String, String> ex = new LinkedHashMap<String, String>();
            put(ex, "pkg", e.pkg);
            put(ex, "realPkg", e.realPkg);
            put(ex, "versionName", e.versionName);
            put(ex, "displayVersion", e.displayVersion());
            put(ex, "imported", String.valueOf(e.imported));
            put(ex, "importFile", e.importFile);
            put(ex, "md5", e.md5);
            put(ex, "customName", e.customName);
            put(ex, "upstreamVersion", e.upstreamVersion);
            put(ex, "slot", Versions.slotFor(e));
            String sub = e.displayVersion();
            if (e.pkg != null && !e.pkg.isEmpty()) sub = sub + " · " + e.pkg;
            out.add(new ItemInfo(ItemInfo.KIND_VERSION, e.displayName(ctx), sub,
                    apk == null ? null : apk.getAbsolutePath(), null, null,
                    e.apkSize, apk == null ? 0L : apk.lastModified(),
                    e.imported ? "import" : "installed", null, false,
                    apk != null && apk.isFile() && apk.canRead(), ex));
        }
        return out;
    }

    private static List<ItemInfo> settingItems(Context ctx) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        for (Map.Entry<String, String> e : settings(ctx).entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue();
            Map<String, String> ex = new LinkedHashMap<String, String>();
            ex.put("key", e.getKey());
            ex.put("value", v);
            out.add(new ItemInfo(ItemInfo.KIND_SETTING, e.getKey(), v,
                    null, null, null, 0L, 0L, "config", null, false, false, ex));
        }
        return out;
    }

    private static String categoryOfLog(Context ctx, File f) {
        File crashes = Data.dataRoot(ctx) == null ? null : new File(Data.dataRoot(ctx), "crashes");
        if (crashes != null && f.getParentFile() != null
                && f.getParentFile().getAbsolutePath().equals(crashes.getAbsolutePath())) {
            return "crash";
        }
        File hub = Data.hubDir(ctx);
        if (hub != null && f.getParentFile() != null
                && f.getParentFile().getAbsolutePath().equals(hub.getAbsolutePath())) {
            return "launcher";
        }
        return "game";
    }

    static InputStream open(ItemInfo item) throws IOException {        if (item == null) throw new IOException("item is null");
        if (item.path != null) {
            File f = new File(item.path);
            if (f.isFile()) return new FileInputStream(f);
        }
        if (item.container != null && item.entry != null) {
            final ZipFile zip = new ZipFile(item.container);
            try {
                ZipEntry e = zip.getEntry(item.entry);
                if (e == null) throw new IOException("entry not found: " + item.entry);
                final InputStream in = zip.getInputStream(e);
                return new FilterInputStream(in) {
                    @Override public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            zip.close();
                        }
                    }
                };
            } catch (IOException e) {
                zip.close();
                throw e;
            }
        }
        throw new IOException("item is not readable: " + item.title);
    }

    // ---- 四类内容 --------------------------------------------------------------

    private static void scanSaves(Context ctx, String slot, List<ItemInfo> out) {
        File root = Data.dirOf(ctx, slot);
        if (root == null) return;
        File dir = new File(root, "saves");
        File[] all = dir.listFiles();
        if (all == null) return;
        List<File> files = new ArrayList<File>();
        for (File f : all) if (MsavMeta.isSaveFile(f)) files.add(f);
        Collections.sort(files, byName());
        for (File f : files) {
            MsavMeta m = MsavMeta.read(f);
            Map<String, String> ex = new LinkedHashMap<String, String>();
            if (m != null) {
                put(ex, "width", m.width);
                put(ex, "height", m.height);
                put(ex, "wave", m.wave);
                put(ex, "build", m.build);
                put(ex, "saved", m.saved);
                put(ex, "playtime", m.playtime);
                put(ex, "name", m.name);
                put(ex, "author", m.author);
                put(ex, "description", m.description);
                put(ex, "mapname", m.mapname);
                put(ex, "planet", m.planet);
                ex.put("ok", String.valueOf(m.ok));
                if (!m.ok) put(ex, "error", m.error);
            }
            String sub = m == null ? "" : (m.ok
                    ? MsavText.shortLine(ctx, m, true) : MsavText.userReason(ctx, m));
            out.add(new ItemInfo(ItemInfo.KIND_SAVES, f.getName(), sub,
                    f.getAbsolutePath(), null, null, f.length(), f.lastModified(),
                    "slot", m != null && !m.ok ? err(m.error) : null,
                    false, f.canRead(), ex));
        }
    }

    private static void scanMaps(Context ctx, String slot, List<ItemInfo> out) {
        String apk = null;
        try {
            Mods.Target t = Mods.targetsFor(ctx, slot);
            if (t != null) apk = t.apkPath;
        } catch (Throwable ignored) {
        }
        List<Maps.Item> items = Maps.scan(ctx, slot, apk);
        for (Maps.Item it : items) out.add(mapItem(ctx, it));
    }

    /**
     * 一张地图 ⇒ {@link ItemInfo}（第 193 轮 v9 抽出来：`pageItems()` 与
     * {@link PluginApi#selectedItem()} **必须是同一份转换**，否则详情页与列表页给的字段会漂）。
     */
    static ItemInfo mapItem(Context ctx, Maps.Item it) {
        Map<String, String> ex = new LinkedHashMap<String, String>();
        if (it.meta != null) {
            put(ex, "width", it.meta.width);
            put(ex, "height", it.meta.height);
            put(ex, "wave", it.meta.wave);
            put(ex, "build", it.meta.build);
            put(ex, "saved", it.meta.saved);
            put(ex, "playtime", it.meta.playtime);
            put(ex, "name", it.meta.name);
            put(ex, "author", it.meta.author);
            put(ex, "description", it.meta.description);
            put(ex, "mapname", it.meta.mapname);
            put(ex, "planet", it.meta.planet);
            ex.put("ok", String.valueOf(it.meta.ok));
        }
        File f = it.file;
        File c = it.container;
        return new ItemInfo(ItemInfo.KIND_MAPS, it.name(), it.line(ctx),
                f == null ? null : f.getAbsolutePath(),
                c == null ? null : c.getAbsolutePath(), it.entry,
                it.bytes, f != null ? f.lastModified() : (c == null ? 0L : c.lastModified()),
                it.sourceLabel(ctx), err(it.error),
                false, f != null ? f.canRead() : (c != null && it.entry != null),
                ex);
    }

    private static void scanBlueprints(Context ctx, String slot, List<ItemInfo> out) {
        List<Blueprints.Item> items = Blueprints.scan(ctx, slot);
        for (Blueprints.Item it : items) {
            Map<String, String> ex = new LinkedHashMap<String, String>();
            if (it.msch != null) {
                put(ex, "width", it.msch.width());
                put(ex, "height", it.msch.height());
                put(ex, "tiles", it.msch.tiles.size());
                put(ex, "blockKinds", it.blockKinds);
                put(ex, "missingKinds", it.missingKinds);
                put(ex, "missingTiles", it.missingTiles);
                ex.put("softMissing", String.valueOf(it.softMissing));
                ex.put("ok", String.valueOf(it.msch.ok));
            }
            File f = it.file;
            File c = it.container;
            out.add(new ItemInfo(ItemInfo.KIND_BLUEPRINTS, it.name(), it.line(ctx),
                    f == null ? null : f.getAbsolutePath(),
                    c == null ? null : c.getAbsolutePath(), it.entry,
                    it.bytes, f != null ? f.lastModified() : (c == null ? 0L : c.lastModified()),
                    it.sourceLabel(ctx), err(it.error),
                    false, f != null ? f.canRead() : (c != null && it.entry != null),
                    ex));
        }
    }

    private static void scanMods(Context ctx, String slot, List<ItemInfo> out) {
        Mods.Scan scan = Mods.scan(ctx, slot);
        if (scan == null) return;
        Map<String, ItemInfo> byPath = new LinkedHashMap<String, ItemInfo>();
        addMods(byPath, scan.mods, ctx);
        addMods(byPath, scan.broken, ctx);
        List<ItemInfo> list = new ArrayList<ItemInfo>(byPath.values());
        Collections.sort(list, new Comparator<ItemInfo>() {
            @Override public int compare(ItemInfo a, ItemInfo b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        out.addAll(list);
    }

    private static void addMods(Map<String, ItemInfo> byPath, List<Mods.Info> infos, Context ctx) {
        if (infos == null) return;
        for (Mods.Info m : infos) {
            if (m == null || m.file == null) continue;
            String path = m.file.getAbsolutePath();
            if (byPath.containsKey(path)) continue;
            Map<String, String> ex = new LinkedHashMap<String, String>();
            put(ex, "internalName", m.internalName);
            put(ex, "version", m.version);
            put(ex, "author", m.author);
            put(ex, "description", m.description);
            put(ex, "main", m.main);
            put(ex, "minGameVersion", m.minGameVersion);
            put(ex, "dependencies", join(m.dependencies));
            put(ex, "softDependencies", join(m.softDependencies));
            ex.put("java", String.valueOf(m.java));
            ex.put("hidden", String.valueOf(m.hidden));
            ex.put("hasClassesDex", String.valueOf(m.hasClassesDex));
            ex.put("hasScripts", String.valueOf(m.hasScripts));
            ex.put("jsCount", String.valueOf(m.jsCount));
            // v8：内容表要用到的三条事实（判据都在宿主 Mods 里，插件照抄数字就行）
            ex.put("enabled", String.valueOf(m.enabled));
            ex.put("willFailJavaLoad", String.valueOf(m.willFailJavaLoad()));
            put(ex, "rootPrefix", m.rootPrefix);
            String title = m.displayName == null || m.displayName.trim().isEmpty()
                    ? m.fileName : m.displayName.trim();
            String sub = modSubtitle(m);
            byPath.put(path, new ItemInfo(ItemInfo.KIND_MODS, title, sub, path,
                    null, null, m.bytes, m.file.lastModified(), "mods", err(m.metaError),
                    m.directory, m.file.canRead(), ex));
        }
    }

    private static String modSubtitle(Mods.Info m) {
        if (m.metaError != null && !m.metaError.isEmpty()) return m.metaError;
        StringBuilder sb = new StringBuilder();
        if (m.version != null && !m.version.isEmpty() && !"0".equals(m.version)) {
            sb.append('v').append(m.version);
        }
        if (m.author != null && !m.author.trim().isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(m.author.trim());
        }
        if (sb.length() == 0 && m.subtitle != null) sb.append(m.subtitle);
        return sb.toString();
    }

    // ---- 详情页一份 ------------------------------------------------------------

    private static List<ItemInfo> one(Context ctx, File f, String kind) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (f == null || !f.isFile()) return out;
        Map<String, String> ex = new LinkedHashMap<String, String>();
        ex.put("size", String.valueOf(f.length()));
        out.add(new ItemInfo(kind, f.getName(), "", f.getAbsolutePath(),
                null, null, f.length(), f.lastModified(), "slot", null,
                false, f.canRead(), ex));
        return out;
    }

    // ---- 小工具 ----------------------------------------------------------------

    private static Comparator<File> byName() {
        return new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        };
    }

    private static String err(String e) {
        return e == null || e.trim().isEmpty() ? null : e;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) m.put(k, v);
    }

    private static void put(Map<String, String> m, String k, long v) {
        m.put(k, String.valueOf(v));
    }

    private static void put(Map<String, String> m, String k, int v) {
        m.put(k, String.valueOf(v));
    }

    private static String join(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
