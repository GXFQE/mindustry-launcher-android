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
        // v42：默认槽（"新建启动项落到哪个槽"）与当前槽不是一回事 —— 两个标志都要给
        String def = "";
        try {
            def = Config.get().defaultSlot();
        } catch (Throwable ignored) {
            // 配置没加载 / 坏了 ⇒ 没有默认槽（不给假值）
        }
        for (String name : names) {
            File dir = Data.dirOf(ctx, name);
            String path = dir == null ? null : dir.getAbsolutePath();
            boolean legacy = path != null && extPath != null && path.equals(extPath);
            // v42：统计是"当场量"的（与槽列表页同一套实现：Data.slotInfo + Data.contentCounts）
            int files = 0;
            long bytes = 0L;
            try {
                Data.Slot si = Data.slotInfo(ctx, name);
                if (si != null) {
                    files = si.files;
                    bytes = si.bytes;
                }
            } catch (Throwable ignored) {
                // 量不出来 ⇒ 0（目录不存在也是 0，两者在接口上分不开，文档已写明）
            }
            int[] cc = new int[4];
            try {
                cc = Data.contentCounts(dir);
            } catch (Throwable ignored) {
                // 同上
            }
            out.add(new SlotInfo(name, path, name.equals(cur), legacy,
                    def != null && !def.isEmpty() && def.equals(name),
                    files, bytes, cc[0], cc[1], cc[2], cc[3]));
        }
        return out;
    }

    /**
     * **v42**：一台插件**已安装的所有版本**（{@link PluginApi#pluginVersions(String)} 的实现）。
     *
     * <p>为什么给：管理 / 回滚时界面上的"版本"只是版本令牌（sha 前 8 位或自定义），插件要能
     * 回答"这台插件装过几份、哪份在生效、哪份是待生效"就得靠宿主 —— 自己去数目录不是契约。
     */
    static List<ItemInfo> pluginVersions(Context ctx, String id) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (ctx == null || id == null || id.trim().isEmpty()) return out;
        String pid = id.trim();
        String active = Plugins.active(ctx);
        String pending = Plugins.pending(ctx);
        String levels = Plugins.levels(ctx);
        String actVer = Plugins.stateGet(active, pid);
        String pendVer = Plugins.stateGet(pending, pid);
        String[] quar = Plugins.quarantinedOf(ctx, pid);
        String name = Plugins.displayNameOf(ctx, pid);
        String[] vers;
        try {
            vers = Plugins.versionsOf(ctx, pid);
        } catch (Throwable t) {
            return out;                        // 目录读不出来 ⇒ 空表（不编造）
        }
        for (String ver : vers) {
            if (ver == null || ver.isEmpty()) continue;
            try {
                File apk = Plugins.apkOf(ctx, pid, ver);
                boolean hasApk = apk != null && apk.isFile();
                PluginMeta.Info meta = hasApk ? PluginMeta.read(ctx, apk) : null;
                String trust = Plugins.trustOf(ctx, pid, ver);
                Map<String, String> ex = new LinkedHashMap<String, String>();
                put(ex, "id", pid);
                put(ex, "name", name);
                put(ex, "version", ver);
                ex.put("active", String.valueOf(ver.equals(actVer)));
                ex.put("pending", String.valueOf(ver.equals(pendVer)));
                ex.put("builtin", String.valueOf(Plugins.isBuiltin(ctx, pid, ver)));
                put(ex, "trust", trust);
                put(ex, "level", Plugins.levelOf(levels, trust, pid));
                ex.put("quarantined", String.valueOf(quar.length > 0));
                ex.put("size", String.valueOf(hasApk ? apk.length() : 0L));
                ex.put("modified", String.valueOf(hasApk ? apk.lastModified() : 0L));
                if (meta != null) {
                    put(ex, "selfVersion", meta.version);
                    put(ex, "author", meta.author);
                    put(ex, "description", meta.description);
                    ex.put("entry", meta.entryOr(PluginHost.DEFAULT_ENTRY));
                }
                String title = meta != null && meta.version != null && !meta.version.isEmpty()
                        ? meta.version : ver;
                out.add(new ItemInfo(ItemInfo.KIND_PLUGIN_VERSION, title, ver, null, null, null,
                        hasApk ? apk.length() : 0L, hasApk ? apk.lastModified() : 0L,
                        "plugins", null, false, false, ex));
            } catch (Throwable ignored) {
                // 一份坏了不影响别的
            }
        }
        return out;
    }

    /**
     * **v42**：存储占用（{@link Storage.Report} 的字节数，键见 {@link PluginApi#storage()} 的文档）。
     *
     * <p>★ 只该在后台线程调（{@code Storage.collect} 会递归全盘）。
     */
    static Map<String, Long> storage(Context ctx) {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        if (ctx == null) return out;
        try {
            Storage.Report r = Storage.collect(ctx);
            if (r == null) return out;
            out.put("total", Long.valueOf(r.totalBytes()));
            out.put("hub", Long.valueOf(r.hubBytes));
            out.put("internal", Long.valueOf(r.internalBytes));
            out.put("import", Long.valueOf(r.importBytes));
            out.put("natives", Long.valueOf(r.nativesBytes));
            out.put("plugins", Long.valueOf(r.pluginsBytes));
            out.put("patches", Long.valueOf(r.patchBytes));
            out.put("cache", Long.valueOf(r.cacheBytes));
            out.put("slots", Long.valueOf(r.slotBytes));
            out.put("backups", Long.valueOf(r.pool == null ? 0L
                    : r.pool.actual + r.pool.legacyBytes));
            out.put("trash", Long.valueOf(r.trashTotalBytes));
            out.put("other", Long.valueOf(r.otherBytes));
            out.put("redundant", Long.valueOf(r.redundantBytes));
        } catch (Throwable t) {
            // 读不出来 ⇒ 空表（没有失败码，与 instances()/backups() 同一条约定）
            return new LinkedHashMap<String, Long>();
        }
        return out;
    }

    /**
     * **v42**：补丁问题**事实**（{@link Patches#launchProblems} + 崩溃后自动停用记录）。
     *
     * <p>⚠️ 只给事实：launch = "这次启动没应用"（code + 明细），crash = "崩溃后自动停用"
     * （code + 显示名 + ref）。崩溃记录的数据行格式是 `code|label|ref`；启动报告是 `code|detail`。
     * 归因（是不是补丁害的）不在这里下结论。
     */
    static List<ItemInfo> patchProblems(Context ctx) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (ctx == null) return out;
        try {
            for (Patches.Problem p : Patches.launchProblems(ctx)) {
                if (p == null) continue;
                out.add(patchProblemItem("launch", p.code, p.detail, null));
            }
        } catch (Throwable ignored) {
            // 报告读不出来 ⇒ 不加这一批（不编造）
        }
        try {
            File f = Patches.crashReportFile(ctx);
            if (f.isFile()) {
                for (String line : Util.readText(f).split("\\r?\\n")) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("\\|", 3);
                    if (parts.length < 2) continue;
                    String code = parts[0] == null ? "" : parts[0];
                    String label = parts[1] == null ? "" : parts[1];
                    String ref = parts.length > 2 ? parts[2] : null;
                    out.add(patchProblemItem("crash", code, label,
                            ref == null || ref.isEmpty() ? null : ref));
                }
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return out;
    }

    private static ItemInfo patchProblemItem(String source, String code, String detail, String ref) {
        Map<String, String> ex = new LinkedHashMap<String, String>();
        put(ex, "source", source);
        put(ex, "code", code);
        put(ex, "detail", detail);
        put(ex, "ref", ref);
        String title = detail == null || detail.trim().isEmpty() ? code : detail;
        String sub = source + (code == null || code.isEmpty() ? "" : " · " + code);
        return new ItemInfo(ItemInfo.KIND_PATCH_PROBLEM, title, sub, null, null, null,
                0L, 0L, null, null, false, false, ex);
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
        if (Extensions.PAGE_VERSION.equals(page) || Extensions.PAGE_RESOURCES.equals(page)) {
            // v40：版本页 / 资源页把"这一份"放进 pageItems（插件的 section 常常只有 api 没有 item）。
            // 评审 B2/M4：这里必须按 **APK 路径反查**、只组装这一条（不要扫全部版本 + 补丁库 + 备份）。
            if (selected != null) {
                ItemInfo one = versionItemByApk(ctx, selected);
                if (one != null) {
                    List<ItemInfo> hit = new ArrayList<ItemInfo>();
                    hit.add(one);
                    return hit;
                }
            }
            return new ArrayList<ItemInfo>();
        }
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
        // 第 275 轮：管理器界面的屏幕方向（0 跟随系统 / 1 强制竖屏 / 2 强制横屏）——
        // 与 theme_mode 同一种"界面偏好"，插件要按它判断自己画的页面落在横屏还是竖屏。
        out.put("orientation_mode", String.valueOf(c.orientationMode()));
        out.put("app_language", safe(c.appLanguage()));
        out.put("default_slot", safe(c.defaultSlot()));
        out.put("max_log_files", String.valueOf(c.maxLogFiles()));
        out.put("auto_clean_redundant", String.valueOf(c.autoCleanRedundant()));
        out.put("last_version", safe(c.lastVersion()));
        // v39：界面「设置」页里能被改的项补齐（插件要按用户设置行事时别再猜默认值）
        out.put("plugin_auto_update", String.valueOf(c.pluginAutoUpdate()));
        out.put("crash_alert", String.valueOf(c.crashAlertEnabled()));
        out.put("trash_keep", String.valueOf(c.trashKeep()));
        out.put("trash_keep_slots", String.valueOf(c.trashKeepSlots()));
        out.put("update_mode", String.valueOf(c.updateMode()));
        out.put("update_mirror", safe(c.updateMirror()));
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
        // ★ 第 373 轮（v39）：原来这里维护一份"目录 → 类别名"的平行数组，而 `Trash.allDirs()`
        //   已经有 8 个目录（第 357 轮加了 patches、更早加了 versions）⇒ 多出来的两种**永远不出现**，
        //   以后再加 Kind 还会继续静默漏。现在只认 `Trash.Item` 里的 `kind` 一处来源。
        File[] dirs = Trash.allDirs(ctx);
        for (File dir : dirs) {
            if (dir == null) continue;
            // ⚠️ 用 listLite：目录形态的条目（整槽 / 版本）在 parse() 里会递归量体积，
            //   几十 MB / 上千文件 ⇒ 插件在 UI 线程调 trash() 会卡（评审 M11）。
            //   体积 / 文件数这两项在插件快照里给 0（不是"没有"，是"这次没量"——文档写明）。
            for (Trash.Item it : Trash.listLite(dir)) {
                if (it == null || it.path == null) continue;
                Map<String, String> ex = new LinkedHashMap<String, String>();
                String cat = categoryOf(it.kind);
                ex.put("category", cat);
                put(ex, "slot", it.slot);
                put(ex, "originalName", it.name);
                ex.put("stamp", String.valueOf(it.stamp));
                ex.put("files", String.valueOf(it.files));
                out.add(new ItemInfo(ItemInfo.KIND_TRASH, it.path.getName(), cat,
                        it.path.getAbsolutePath(), null, null, it.bytes,
                        it.stamp > 0 ? it.stamp : it.path.lastModified(),
                        "trash", null, it.path.isDirectory(),
                        it.path.isFile() && it.path.canRead(), ex));
            }
        }
        return out;
    }

    /**
     * 中转站类别名（**唯一映射**）：`Trash.Kind` → 给插件的字符串。
     *
     * <p>⚠️ 这张表就是 {@code Trash.Kind} 的覆盖检查对象：自检遍历所有 Kind 断言
     * {@code categoryOf} 不会掉进 `other`（新加 Kind 忘了映射 ⇒ 自检红）。`OTHER` 本身才是 `other`。
     */
    static String categoryOf(Trash.Kind k) {
        if (k == null) return "other";
        switch (k) {
            case MAP: return "maps";
            case MOD: return "mods";
            case SAVE: return "saves";
            case SLOT: return "slots";
            case SCHEM: return "schematics";
            case PLUGIN: return "plugins";
            case VERSION: return "versions";
            case PATCH: return "patches";
            default: return "other";
        }
    }

    /** **v39**：正在跑的实例（含启动占位；判据与写门禁同一份 `Instances.live`）。 */
    static List<ItemInfo> instances(Context ctx) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (ctx == null) return out;
        try {
            for (Instances.Running r : Instances.live(ctx)) {
                if (r == null) continue;
                Map<String, String> ex = new LinkedHashMap<String, String>();
                ex.put("pid", String.valueOf(r.pid));
                put(ex, "vkey", r.vkey);
                put(ex, "slot", r.slot);
                put(ex, "instanceKey", r.instanceKey());
                ex.put("started", String.valueOf(r.started));
                // 启动占位（主进程 startActivity 之后、:game publish 之前）没有进程名
                ex.put("starting", r.proc == null || r.proc.isEmpty() ? "true" : "false");
                out.add(new ItemInfo(ItemInfo.KIND_INSTANCE, r.label(),
                        r.proc == null || r.proc.isEmpty() ? "starting" : "pid " + r.pid,
                        null, null, null, 0L, r.started, "instance", null, false, false, ex));
            }
        } catch (Throwable ignored) {
            // 实例表读不出来 ⇒ 空表（别让插件因为这个崩）
        }
        return out;
    }

    /** **v39**：某个槽的备份快照（只读元信息；恢复/删除不给插件做）。 */
    static List<ItemInfo> backups(Context ctx, String slot) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (ctx == null) return out;
        String s = slot == null ? "" : slot.trim();
        if (s.isEmpty()) s = Data.currentSlot(ctx);
        try {
            for (Backup.Snapshot ss : Backup.list(ctx, s)) {
                if (ss == null) continue;
                Map<String, String> ex = new LinkedHashMap<String, String>();
                put(ex, "slot", s);
                ex.put("created", String.valueOf(ss.created));
                put(ex, "label", ss.label);
                put(ex, "source", ss.source);
                ex.put("files", String.valueOf(ss.count));
                ex.put("bytes", String.valueOf(ss.bytes));
                ex.put("legacy", String.valueOf(ss.legacy()));
                out.add(new ItemInfo(ItemInfo.KIND_BACKUP, ss.title(),
                        ss.source == null ? "" : ss.source,
                        null, null, null, ss.bytes, ss.created, "backups", null, false, false, ex));
            }
        } catch (Throwable ignored) {
            // 备份目录还没建 / 清单坏了 ⇒ 空表
        }
        return out;
    }

    /**
     * 这个 `(版本 key, 槽)` 是不是**存在的一个启动项**（`versions()` 里有一行）。
     *
     * <p>补丁写接口用它挡"插件随手传个不存在的槽名"—— 否则会在 config 里留下一条
     * 指向不存在启动项的启用记录（界面上看不见、也删不掉的那种脏数据）。
     */
    static Boolean hasLaunchItem(Context ctx, String versionKey, String slot) {
        if (ctx == null || versionKey == null || versionKey.isEmpty()) return Boolean.FALSE;
        String want = slot == null ? "" : slot.trim();
        try {
            for (Versions.Entry e : Versions.scanAll(ctx)) {
                if (e != null && versionKey.equals(e.key())
                        && want.equals(Versions.slotFor(e))) return Boolean.TRUE;
            }
        } catch (Throwable t) {
            return null;                   // 读失败——上层要报 io，不能折成 not-found（评审 M7）
        }
        return Boolean.FALSE;
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
        // v39：宿主列表行本来就在算的两个数（补丁 N / 备份 N）—— 插件不该为了这两个数
        // 自己去扫 APK 哈希（Patches 那条路要几十 MB 的 sha256）或重读备份清单。
        // ⚠️ 评审 M4：`Patches.scan` / `VersionOps.backupCountIn` 在"读不出来"时都会给空表 / 0，
        //   与"真的没有"分不开 ⇒ 先用 storeReadable / listFiles 判一次可读性，读不出来就别放这个键。
        Map<String, Integer> patchCounts = Patches.storeReadable(ctx)
                ? Patches.enabledCounts(ctx) : null;
        Map<String, Integer> backupCounts = new LinkedHashMap<String, Integer>();
        for (Versions.Entry e : entries) {
            if (e == null) continue;
            try {
                out.add(versionItem(ctx, e, patchCounts, backupCounts));
            } catch (Throwable ignored) {
                // 一行坏了不影响别的
            }
        }
        return out;
    }

    /**
     * **v40**：单个启动项（版本页的 `selectedItem()` / `pageItems()` 用）。
     *
     * <p>⚠️ 不走 {@link #versions()}：那个会扫全部版本 + 全库补丁 + 每槽备份清单；版本页只需要
     * "这一行"（第 374 轮评审 B2/m2）。`slot` 空 = 这个版本 key 的第一行。
     */
    static ItemInfo versionItem(Context ctx, String versionKey, String slot) {
        if (ctx == null || versionKey == null || versionKey.trim().isEmpty()) return null;
        String vk = versionKey.trim();
        String want = slot == null ? "" : slot.trim();
        try {
            for (Versions.Entry e : Versions.scanAll(ctx)) {
                if (e == null || !vk.equals(e.key())) continue;
                if (!want.isEmpty() && !want.equals(Versions.slotFor(e))) continue;
                return versionItem(ctx, e, singlePatchCounts(ctx, e),
                        new LinkedHashMap<String, Integer>());
            }
        } catch (Throwable ignored) {
            // 读不出来 ⇒ null（调用方把那当作"这一份拿不到"，不编造）
        }
        return null;
    }

    /** **v40**：按 APK 路径反查版本条目（resources 页的 `pageItems()` 用）。 */
    static ItemInfo versionItemByApk(Context ctx, File apk) {
        if (ctx == null || apk == null) return null;
        try {
            String path = apk.getAbsolutePath();
            for (Versions.Entry e : Versions.scanAll(ctx)) {
                if (e == null || e.apkPath == null || !path.equals(e.apkPath)) continue;
                return versionItem(ctx, e.key(), Versions.slotFor(e));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 单个启动项的补丁数（只算这一条；读不出来 ⇒ null = 不放键）。 */
    private static Map<String, Integer> singlePatchCounts(Context ctx, Versions.Entry e) {
        if (e == null || !Patches.storeReadable(ctx)) return null;
        try {
            String ikey = Versions.instanceKey(e.key(), Versions.slotFor(e));
            int n = Patches.enabledCount(ctx, ikey);
            Map<String, Integer> m = new LinkedHashMap<String, Integer>();
            if (n > 0) m.put(ikey, Integer.valueOf(n));
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一条版本记录 ⇒ {@link ItemInfo}（**纯组装**：两个计数由调用方给，自检可直接喂）。 */
    private static ItemInfo versionItem(Context ctx, Versions.Entry e,
                                        Map<String, Integer> patchCounts,
                                        Map<String, Integer> backupCounts) {
        File apk = e.apkPath == null || e.apkPath.isEmpty() ? null : new File(e.apkPath);
        String rowSlot = Versions.slotFor(e);
        String ikey = Versions.instanceKey(e.key(), rowSlot);
        Map<String, String> ex = new LinkedHashMap<String, String>();
        put(ex, "pkg", e.pkg);
        put(ex, "realPkg", e.realPkg);
        put(ex, "versionName", e.versionName);
        // v30：版本 key（已装 = 包名 / 导入 = import:文件名）—— 与 ProbeEnv.version() 同一个值，
        //       插件按它把探针记的数字挂到版本上。
        put(ex, "key", e.key());
        put(ex, "displayVersion", e.displayVersion());
        put(ex, "imported", String.valueOf(e.imported));
        put(ex, "importFile", e.importFile);
        put(ex, "md5", e.md5);
        put(ex, "customName", e.customName);
        put(ex, "upstreamVersion", e.upstreamVersion);
        put(ex, "slot", rowSlot);
        // v39：启动项身份 —— 插件别自己拼 `key + NUL + slot`（那是宿主内部格式）
        put(ex, "instanceKey", ikey);
        ex.put("bound", e.boundSlot != null && !e.boundSlot.isEmpty() ? "true" : "false");
        if (patchCounts != null) {
            Integer n = patchCounts.get(ikey);
            ex.put("patchCount", String.valueOf(n == null ? 0 : n.intValue()));
        }
        File bdir = Backup.slotDir(ctx, rowSlot);
        // 目录不存在 = 这个槽从没备份过 ⇒ 0 是事实；目录在却列不出来 = 读失败 ⇒ 不放键
        if (!bdir.exists() || bdir.listFiles() != null) {
            if (!backupCounts.containsKey(rowSlot)) {
                backupCounts.put(rowSlot, Integer.valueOf(
                        VersionOps.backupCountIn(ctx, rowSlot)));
            }
            ex.put("backupCount", String.valueOf(backupCounts.get(rowSlot)));
        }
        String sub = e.displayVersion();
        if (e.pkg != null && !e.pkg.isEmpty()) sub = sub + " · " + e.pkg;
        return new ItemInfo(ItemInfo.KIND_VERSION, e.displayName(ctx), sub,
                apk == null ? null : apk.getAbsolutePath(), null, null,
                e.apkSize, apk == null ? 0L : apk.lastModified(),
                e.imported ? "import" : "installed", null, false,
                apk != null && apk.isFile() && apk.canRead(), ex);
    }

    // ---- 插件与补丁清单（v38，第 372 轮）---------------------------------------

    /**
     * **v38**：装着哪些插件（{@code PluginApi.plugins()} 的唯一实现）。
     *
     * <p>为什么要宿主给：插件之间没有共享表，而"装了谁 / 谁是内置 / 这一程谁生效"这些事实只有
     * {@link Plugins} 的登记表知道 —— 插件自己反射或读文件都不是契约，宿主一改就断。
     *
     * <p>包含**停用 / 待生效 / 已隔离**的（"所有插件"），状态在 {@code extra["state"]}。
     * 一台插件坏了只跳过它自己（与 {@link #contents} 同一条兜底）。
     */
    static List<ItemInfo> plugins(Context ctx) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (ctx == null) return out;
        List<String> ids;
        try {
            ids = Plugins.installedIds(ctx);
        } catch (Throwable t) {
            return out;
        }
        String active = Plugins.active(ctx);
        String pending = Plugins.pending(ctx);
        String levels = Plugins.levels(ctx);
        for (String id : ids) {
            try {
                out.add(pluginItem(ctx, id, active, pending, levels));
            } catch (Throwable ignored) {
                // 一台坏了不影响别的
            }
        }
        return out;
    }

    /**
     * 一台插件 ⇒ {@link ItemInfo}。
     *
     * <p>登记文本由调用方给（{@link #plugins(Context)} 读真文件，自检直接喂字符串）：
     * **组装只有这一份**，状态 / 来源 / 等级全部走 {@link Plugins} 与 {@link PluginTrust}
     * 的现成纯函数（与插件页同一批判据）。
     */
    static ItemInfo pluginItem(Context ctx, String id, String activeText, String pendingText,
                               String levelsText) {
        String[] row = Plugins.rowState(activeText, pendingText, id);
        String state = row[0];
        String ver = row[1];
        String actVer = Plugins.stateGet(activeText, id);
        String pendVer = Plugins.stateGet(pendingText, id);
        String[] installed = Plugins.versionsOf(ctx, id);
        // "这一行在说哪一份"：待生效 > 生效 > 已装版本里**文件名字典序最后一份**
        // （⚠️ 不是"最新装的"：版本令牌可能是 sha 前 8 位，字典序与新旧无关 —— 第 372 轮评审订正）
        String probeVer = ver.isEmpty()
                ? (installed.length == 0 ? null : installed[installed.length - 1]) : ver;
        File apk = probeVer == null ? null : Plugins.apkOf(ctx, id, probeVer);
        boolean hasApk = apk != null && apk.isFile();
        // 🔴 只在 APK 真的在时才读自述：`PluginMeta.read(缺失文件)` 返回的是**所有字段为 null 的
        // Info**（不是 null），照它写 entry 会得到 DEFAULT_ENTRY —— "没有 APK"就成了"默认入口"，
        // 与文档（没有 APK ⇒ 没有 entry/selfVersion/author/description/access/extCount）相反。
        PluginMeta.Info meta = hasApk ? PluginMeta.read(ctx, apk) : null;
        String trust = probeVer == null ? PluginTrust.THIRD : Plugins.trustOf(ctx, id, probeVer);
        String level = Plugins.levelOf(levelsText, trust, id);
        String[] quar = Plugins.quarantinedOf(ctx, id);
        String name = Plugins.displayName(meta == null ? null : meta.name, id);
        Map<String, String> ex = new LinkedHashMap<String, String>();
        ex.put("id", id);
        ex.put("name", name);
        ex.put("state", state);
        put(ex, "version", probeVer);
        put(ex, "activeVersion", actVer);
        put(ex, "pendingVersion", pendVer);
        // v39：插件自动更新的状态（「设置」页/插件页里本来就在显示的东西）——
        // 只有真的有一条更新记录时才放键（"没有"与"读不出来"都不编默认值）。
        try {
            PluginUpdateState.Item up = PluginUpdateState.get(PluginUpdates.stateText(ctx), id);
            if (up != null) {
                put(ex, "updateState", up.state);
                // 记录里没有版本号 / 备注时给空串（记录在 = 这三个键在一起；空串不是"读不出来"，
                // 而是"这条更新记录没写这一项"—— 与其它 extra 键的"缺 = 拿不到"区分开）
                ex.put("updateVersion", up.ver == null ? "" : up.ver);
                ex.put("updateNote", up.note == null ? "" : up.note);
            }
        } catch (Throwable ignored) {
            // 更新状态文件坏了 ⇒ 不放这几个键
        }
        ex.put("builtin", String.valueOf(PluginTrust.BUILTIN.equals(trust)));
        ex.put("trust", trust);
        ex.put("level", level);
        ex.put("quarantined", String.valueOf(quar.length > 0));
        ex.put("size", String.valueOf(hasApk ? apk.length() : 0L));
        ex.put("modified", String.valueOf(hasApk ? apk.lastModified() : 0L));
        if (meta != null) {
            put(ex, "selfVersion", meta.version);
            put(ex, "author", meta.author);
            put(ex, "description", meta.description);
            ex.put("entry", meta.entryOr(PluginHost.DEFAULT_ENTRY));
        }
        if (hasApk) {
            // access 与 extensions 都在插件自述里；"没声明"与"自述读不出来"**分不开**（都返回空表），
            // 文档已写明这两个键别拿来判断插件好坏（要判就看能不能加载 / trust）
            List<String> access = PluginMeta.accessOf(apk);
            String joined = join(access);
            ex.put("access", joined == null ? "" : joined);
            List<Extensions.Item> exts = Extensions.of(apk);
            ex.put("extCount", String.valueOf(exts.size()));
            for (int i = 0; i < exts.size(); i++) {
                Extensions.Item e = exts.get(i);
                String p = "ext." + i + ".";
                ex.put(p + "id", e.id);
                ex.put(p + "title", e.label());
                put(ex, p + "subtitle", e.subtitle);
                ex.put(p + "slot", e.slot);
                put(ex, p + "page", e.page);
                put(ex, p + "event", e.event);
            }
        }
        // 没有 APK（只剩隔离件 / 登记指向的文件被删）⇒ access / extCount / entry 这些键**不出现**：
        // 对插件来说"没读到"和"声明为空"必须分得开（页面上"没声明"那句是界面文案，不是数据）。
        return new ItemInfo(ItemInfo.KIND_PLUGIN, name, id, null, null, null,
                hasApk ? apk.length() : 0L, hasApk ? apk.lastModified() : 0L,
                "plugins", null, false, false, ex);
    }

    /** **v38**：已导入的游戏补丁（{@code PluginApi.patches()} 的唯一实现；不判启用状态）。 */
    static List<ItemInfo> patches(Context ctx) {
        return patches(ctx, null, null);
    }

    /**
     * **v38**：已导入的游戏补丁；给了启动项（版本 key + 槽）才判 {@code enabled} / {@code applies}。
     *
     * <p>补丁的匹配键是**目标 APK 的 sha256**（不是版本号区间，见 {@link PatchManifest}）⇒
     * "这个补丁适用不适用"天然是相对某个版本说的 —— 那一份上下文只能由调用方给。
     *
     * @param versionKey 版本 key（{@code PluginApi.versions()} 的 {@code extra["key"]}）；
     *                   null / 空 ⇒ 不判启动项（不写 enabled / applies）
     * @param slot       槽名；null / 空 = 当前槽
     */
    static List<ItemInfo> patches(Context ctx, String versionKey, String slot) {
        List<ItemInfo> out = new ArrayList<ItemInfo>();
        if (ctx == null) return out;
        // 先扫库：**一条补丁都没有时绝不碰版本 APK 的哈希**（几十 MB 的读，白花）
        List<Patches.Record> records;
        try {
            records = Patches.scan(ctx);
        } catch (Throwable t) {
            return out;                    // 补丁库整个读不出来 ⇒ 空表（插件别据此删东西）
        }
        if (records.isEmpty()) return out;
        String vk = versionKey == null ? "" : versionKey.trim();
        List<String> enabled = null;
        String apkSha = "";
        if (!vk.isEmpty()) {
            String s = slot == null || slot.trim().isEmpty() ? Data.currentSlot(ctx) : slot.trim();
            try {
                enabled = Patches.enabledRefs(ctx, Versions.instanceKey(vk, s));
            } catch (Throwable t) {
                enabled = null;            // 读不出来 ⇒ **不放 enabled**（别用 false 冒充"没启用"）
            }
            String sha = apkShaOfVersion(ctx, vk);
            apkSha = sha == null ? "" : sha;   // 读失败（null）⇒ 不写 applies，与"没有版本"同一个键语义
        }
        for (Patches.Record r : records) {
            try {
                out.add(patchItem(r, enabled, apkSha));
            } catch (Throwable ignored) {
                // 一条记录坏了不影响别的
            }
        }
        return out;
    }

    /**
     * 版本 key ⇒ 它那份 APK 的 sha256。
     *
     * @return 64 位十六进制；**版本不存在 / APK 文件不在 ⇒ 空串**（事实是"没有这个版本"）；
     *         **扫描或哈希过程出错 ⇒ null**（读失败，不是"没有"——评审 M7：原来两种情况都折成空串，
     *         插件写接口只能报 `no_match`）。
     */
    static String apkShaOfVersion(Context ctx, String versionKey) {
        if (ctx == null || versionKey == null || versionKey.trim().isEmpty()) return "";
        String vk = versionKey.trim();
        try {
            for (Versions.Entry e : Versions.scanAll(ctx)) {
                if (e == null || e.apkPath == null || e.apkPath.isEmpty()) continue;
                if (!vk.equals(e.key())) continue;
                File apk = new File(e.apkPath);
                if (!apk.isFile()) continue;
                return Patches.sha256Of(apk);
            }
        } catch (Throwable t) {
            return null;                   // 读失败 ≠ 没有这个版本
        }
        return "";
    }

    /**
     * 一条补丁记录 ⇒ {@link ItemInfo}（**纯组装**：启用列表与 APK 哈希由调用方给，自检直接喂）。
     *
     * <p>每个 target 的事实写成 {@code target.<i>.*}（与模组依赖的 {@code dep.<i>} 同一套扁平写法）；
     * 读不出来的量**不放这个键**，别让插件把"读不出来"当成 0。
     */
    static ItemInfo patchItem(Patches.Record r, List<String> enabledRefs, String apkSha) {
        PatchManifest m = r.manifest;
        Map<String, String> ex = new LinkedHashMap<String, String>();
        ex.put("id", m.id);
        ex.put("version", m.version);
        ex.put("ref", r.ref());
        ex.put("name", m.name);
        put(ex, "author", m.author);
        put(ex, "note", m.note);
        put(ex, "tool", m.tool);
        ex.put("filesOk", String.valueOf(r.filesOk));
        ex.put("targetCount", String.valueOf(m.targets.size()));
        boolean hasSha = apkSha != null && !apkSha.isEmpty();
        boolean applies = false;
        long bytes = 0L;
        for (int i = 0; i < r.targets.size(); i++) {
            Patches.TargetFile t = r.targets.get(i);
            PatchManifest.Target spec = t.spec;
            String p = "target." + i + ".";
            ex.put(p + "sha256", spec.apkSha256);
            put(ex, p + "gameVersion", spec.gameVersion);
            File dex = t.dexFile();
            File as = t.assetsFile();
            boolean dexOk = dex != null && dex.isFile() && dex.length() > 0;
            boolean asOk = as != null && as.isFile();
            long dexBytes = dexOk ? dex.length() : 0L;
            long asBytes = asOk ? as.length() : 0L;
            bytes += dexBytes + asBytes;
            ex.put(p + "dexBytes", String.valueOf(dexBytes));
            ex.put(p + "assetsBytes", String.valueOf(asBytes));
            if (dexOk) {
                try {
                    ex.put(p + "classes", String.valueOf(t.classCount()));
                } catch (Throwable ignored) {
                    // dex 坏了 / 读不出来 ⇒ 不放这个键（别当 0）
                }
            }
            if (!spec.hasAssets()) {
                ex.put(p + "assets", "0");      // 没声明素材包 = **事实**，不是"读不出来"
            } else if (asOk) {
                try {
                    ex.put(p + "assets", String.valueOf(t.assetKeys().size()));
                } catch (Throwable ignored) {
                }
            }
            if (hasSha) {
                boolean match = spec.apkSha256.equalsIgnoreCase(apkSha);
                ex.put(p + "match", String.valueOf(match));
                if (match) applies = true;
            }
        }
        ex.put("bytes", String.valueOf(bytes));
        if (enabledRefs != null) {
            ex.put("enabled", String.valueOf(enabledRefs.contains(r.ref())));
            if (hasSha) ex.put("applies", String.valueOf(applies));
        }
        File mf = new File(r.dir, PatchManifest.FILE);
        long importedAt = mf.lastModified();
        return new ItemInfo(ItemInfo.KIND_PATCH, m.name, r.ref(), null, null, null, bytes,
                importedAt, "patches", r.filesOk ? null : "missing-files", false, false, ex);
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
        // v25（P3）：解析结果与目标版本是"事实"（宿主算出来给插件用）；
        // "这些事实意味着什么"归插件。⚠️ 只调一次：两批条目共用同一份。
        Mods.Resolved resolved = Mods.resolveDependencies(scan.mods);
        Mods.Target target = Mods.targetsFor(ctx, slot);
        addMods(byPath, scan.mods, ctx, resolved, target, scan);
        addMods(byPath, scan.broken, ctx, resolved, target, scan);
        List<ItemInfo> list = new ArrayList<ItemInfo>(byPath.values());
        Collections.sort(list, new Comparator<ItemInfo>() {
            @Override public int compare(ItemInfo a, ItemInfo b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        out.addAll(list);
    }

    private static void addMods(Map<String, ItemInfo> byPath, List<Mods.Info> infos, Context ctx,
                                Mods.Resolved resolved, Mods.Target target, Mods.Scan scan) {
        if (infos == null) return;
        for (Mods.Info m : infos) {
            if (m == null || m.file == null) continue;
            String path = m.file.getAbsolutePath();
            if (byPath.containsKey(path)) continue;
            Map<String, String> ex = new LinkedHashMap<String, String>();
            put(ex, "internalName", m.internalName);
            put(ex, "name", m.name);
            put(ex, "version", m.version);
            put(ex, "author", m.author);
            put(ex, "description", m.description);
            put(ex, "main", m.main);
            put(ex, "minGameVersion", m.minGameVersion);
            put(ex, "dependencies", join(m.dependencies));
            put(ex, "softDependencies", join(m.softDependencies));
            // v25：给插件的是**逐条**依赖（原来只有拼好的一串，插件拆不出来）
            if (m.dependencies != null) {
                for (int i = 0; i < m.dependencies.size(); i++) {
                    put(ex, "dep." + i, m.dependencies.get(i));
                }
            }
            if (m.softDependencies != null) {
                for (int i = 0; i < m.softDependencies.size(); i++) {
                    put(ex, "softdep." + i, m.softDependencies.get(i));
                }
            }
            put(ex, "duplicated", String.valueOf(m.duplicated));
            // v25（P3）：依赖解析结果（宿主 `Mods.resolveDependencies` 的**产物**，不是插件再算一遍）
            if (resolved != null && m.internalName != null) {
                Mods.State ds = resolved.stateOf(m.internalName);
                put(ex, "depState", ds == null ? "" : ds.name());
            }
            put(ex, "blacklisted", String.valueOf(Mods.isBlacklisted(m.name, m.version)));
            // v25（P3b）：版本判断要的事实 —— **比较交给插件**（宿主不再自己比一遍）
            put(ex, "legacyCompatible", String.valueOf(m.legacyCompatible));
            put(ex, "minJavaMajor", String.valueOf(Mods.MIN_JAVA_MOD_GAME_VERSION));
            put(ex, "minModMajor", String.valueOf(Mods.MIN_MOD_GAME_VERSION));
            if (target != null) {
                put(ex, "targetBuild", String.valueOf(target.build));
                put(ex, "targetRevision", String.valueOf(target.revision));
            }
            put(ex, "failed", String.valueOf(m.failed));
            put(ex, "willFailJavaLoad", String.valueOf(m.willFailJavaLoad()));
            ex.put("java", String.valueOf(m.java));
            ex.put("hidden", String.valueOf(m.hidden));
            ex.put("hasClassesDex", String.valueOf(m.hasClassesDex));
            ex.put("hasScripts", String.valueOf(m.hasScripts));
            ex.put("jsCount", String.valueOf(m.jsCount));
            // v25（P2）：多脚本"只认 main.js"的判据在插件那边，这条是它要的事实
            ex.put("hasMainJs", String.valueOf(m.hasMainJs));
            // v8：内容表要用到的三条事实（判据都在宿主 Mods 里，插件照抄数字就行）
            ex.put("enabled", String.valueOf(m.enabled));
            put(ex, "rootPrefix", m.rootPrefix);
            // 第 230 轮：六道门要的两条事实 —— 只给事实（"意味着什么"在插件里）：
            //   settingsKnown：没读到 settings.bin 时 `enabled` 只是默认值 true，插件要说清"这是默认值"；
            //   launchIdExists：`launchid.dat` 在但用户关了 modcrashdisable ⇒ 不会整槽跳过，
            //   插件那关要通过并补一句机制说明（判据仍在宿主 `Mods.scan.skipModLoading`）。
            put(ex, "settingsKnown", String.valueOf(m.settingsKnown));
            put(ex, "launchIdExists", String.valueOf(scan != null && scan.launchIdExists));
            // 目录形态的"根"（`resolveRoot` 下潜一层之后那一层）：插件找 classes.dex 要用它，
            // 别让插件自己再推一遍这条规则（第 229 轮，mods-health 插件）。
            if (m.directory && m.rootDir != null) put(ex, "rootDir", m.rootDir.getAbsolutePath());
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
