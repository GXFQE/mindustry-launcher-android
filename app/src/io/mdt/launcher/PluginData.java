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
        if ("map-detail".equals(page)) return one(ctx, selected, ItemInfo.KIND_MAPS);
        if ("blueprint-detail".equals(page)) return one(ctx, selected, ItemInfo.KIND_BLUEPRINTS);
        return new ArrayList<ItemInfo>();      // v5 第一阶段：其余页面留空
    }

    static InputStream open(ItemInfo item) throws IOException {
        if (item == null) throw new IOException("item is null");
        if (item.path != null) {
            File f = new File(item.path);
            if (f.isFile()) return new FileInputStream(f);
        }
        if (item.container != null && item.entry != null) {
            final ZipFile zip = new ZipFile(item.container);
            ZipEntry e = zip.getEntry(item.entry);
            if (e == null) {
                zip.close();
                throw new IOException("entry not found: " + item.entry);
            }
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
        for (Maps.Item it : items) {
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
            out.add(new ItemInfo(ItemInfo.KIND_MAPS, it.name(), it.line(ctx),
                    f == null ? null : f.getAbsolutePath(),
                    c == null ? null : c.getAbsolutePath(), it.entry,
                    it.bytes, f != null ? f.lastModified() : (c == null ? 0L : c.lastModified()),
                    it.sourceLabel(ctx), err(it.error),
                    false, f != null ? f.canRead() : (c != null && it.entry != null),
                    ex));
        }
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
