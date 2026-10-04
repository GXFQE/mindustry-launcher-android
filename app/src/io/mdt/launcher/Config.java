package io.mdt.launcher;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 唯一配置：app_hub/config.json（模块边界：只依赖 Paths/Util —— 对齐桌面版 config）。
 * ⚠️ 位置是 `getDir("hub")`（= app_hub）而不是 `getFilesDir()`（= files/）——
 *   游戏首次启动会把 `files/` 整目录复制进数据根，放那儿会被抄进存档槽（见 Paths 头注释）。
 *
 * 铁律（照搬桌面版 MDT 的配置兼容三条）：
 *  1. 未知键原样保留 —— 直接在解析出来的 JSONObject 上读写，绝不重建整棵树；
 *  2. config_version 缺失 = 第 0 代，升级即落盘，版本号只升不降；
 *  3. 脏值退默认 + WARNING 点名键名，绝不判「配置损坏」去重建。
 *
 * 读不了整个文件时：原件改名为 config.json.corrupt-&lt;时间戳&gt; 留证，从默认开始
 * （区别于「重建」：只动这一个文件，绝不碰 versions / 存档等用户数据）。
 *
 * ⚠️ LauncherApp.onCreate 在主进程和 :game 进程各跑一次，两次是**各自独立**的实例。
 *   以前这里写「:game 进程只读不写」—— **那是错的**：GameSlot 在游戏启动成功后会
 *   写 `last_version`。跨进程写盘后，另一个进程内存里那份不会自己变，
 *   必须显式 {@link #reload} 才看得到（否则要重启启动器，见 reload 的注释）。
 */
public final class Config {
    private static final String TAG = "MDTLauncher";

    /** 当前配置格式代际。改结构时 +1 并在 load() 里挂迁移。 */
    public static final int CONFIG_VERSION = 1;

    /** 内置已知包名白名单（枚举被系统限制只返回自己，只能按精确包名查） */
    static final String[] BUILTIN_PACKAGES = {
        "io.anuke.mindustry",             // 官方 Mindustry
        "com.github.tinylake.mindustryX", // MindustryX（fork 线）
    };

    private static final String K_VER = "config_version";
    private static final String K_KNOWN = "known_packages";
    private static final String K_LAST = "last_version";
    private static final String K_IMPORTS = "imports";
    /** M3：版本 → 存档槽 的分配表 { "&lt;版本key&gt;": "&lt;槽名&gt;" } */
    private static final String K_VSLOTS = "version_slots";
    /** F3：新发现的版本缺省用哪个槽（空串 = 由调用方回落到 Data.SLOT_DEFAULT） */
    private static final String K_DEF_SLOT = "default_slot";
    /** F7：日志保留份数（日志页用） */
    private static final String K_LOGS = "max_log_files";
    /** F15：界面深浅色（0=跟随系统 1=浅色 2=深色） */
    private static final String K_THEME = "theme_mode";
    /** 界面语言（空串=跟随系统；否则 BCP47 标签，如 en / zh / zh-TW）—— 见 {@link LocaleMode} */
    private static final String K_LANG = "app_language";
    /** F5：每槽的自动备份策略 { "&lt;槽名&gt;": {enabled, min_minutes, max_backups} } */
    private static final String K_BACKUP = "backup_policy";
    /** F5：一次启动的记账 {key, slot, at} —— 游戏退出后回来结算自动备份用 */
    private static final String K_SESSION = "session";
    /** F20：进存档页时自动清理数据根残留（默认开；关掉后只能手动清） */
    private static final String K_AUTO_CLEAN = "auto_clean_redundant";

    /**
     * 自动备份的出厂默认，逐条对齐桌面版 config.py 的 PROFILE_DEFAULTS
     * （auto_backup=True / min_playtime=20 / max_backups=20）。
     */
    public static final boolean DEF_BACKUP_ENABLED = true;
    public static final int DEF_BACKUP_MIN_MINUTES = 20;
    public static final int DEF_BACKUP_MAX = 20;
    /** 日志保留份数的出厂默认（对齐桌面版 DEFAULT_LOG_KEEP）。 */
    public static final int DEF_LOG_FILES = 20;
    /**
     * ★ F20：进存档页时自动清理数据根残留的出厂默认 = **开**（保持第 57 轮以来的行为不变）。
     *   ⚠️ 加开关的理由：这是全工程**唯一一个"静默删文件"**的动作（见 {@link Data#autoCleanRedundant}），
     *   而且第 57 轮把体检的可见入口删掉之后，它连"发生过"都不会说一声。
     */
    public static final boolean DEF_AUTO_CLEAN = true;

    private static volatile Config sInstance;

    public static Config get() {
        Config c = sInstance;
        if (c == null) {
            synchronized (Config.class) {
                if (sInstance == null) sInstance = new Config();
                c = sInstance;
            }
        }
        return c;
    }

    private Config() {}

    private File mFile;
    private JSONObject mRoot = new JSONObject();

    /** 幂等：第二个进程再调直接返回。 */
    public synchronized void load(Context ctx) {
        if (mFile != null) return;
        mFile = new File(Paths.privateDir(ctx), "config.json");
        if (mFile.exists()) {
            try {
                mRoot = new JSONObject(Util.readText(mFile));
            } catch (Exception e) {
                Log.w(TAG, "config.json unreadable (" + e + ") — archived, using defaults");
                File bad = new File(mFile.getParentFile(),
                        "config.json.corrupt-" + System.currentTimeMillis());
                if (!mFile.renameTo(bad)) Log.w(TAG, "archive rename failed: " + bad);
                mRoot = new JSONObject();
            }
        }
        int v = mRoot.optInt(K_VER, 0);
        if (v > CONFIG_VERSION) {
            // 来自更新版本的配置：字段按各自默认兜底，绝不回写降版本号
            Log.w(TAG, "config_version " + v + " > app " + CONFIG_VERSION
                    + " (newer config on older app?) — fields fall back to defaults");
        }
        if (v < CONFIG_VERSION) {
            // 迁移挂点：第 0 代 -> 第 1 代 无结构变化，只补版本号；升级立即落盘
            put(K_VER, CONFIG_VERSION);
            save();
        }
    }

    /**
     * 从盘上**重新**读一遍配置（跨进程同步用）。
     *
     * ★★ 为什么必须有它 —— 2026-10-01 真机复现「继续上次必须把启动器重启才能更新」：
     *   {@link #load} 是幂等的（`mFile != null` 直接 return）⇒ 每个进程只在启动时读**一次**盘，
     *   之后一直用内存里的 `mRoot`。而 `last_version` 是 {@link GameSlot} 在 **:game 进程**
     *   里写的（游戏成功启动后 `setLastVersion`）—— 主进程那份 `mRoot` 毫不知情。
     *   于是回到启动器时「继续上次」还指着**上一次**的版本；实测走两次 onResume 都不变，
     *   **只有 force-stop 杀掉主进程**才会重新 load 并读到新值。
     *
     * 与 {@link #load} 的语义差别（都很关键，别混）：
     *  - **不做 mtime / 指纹短路，一律重读**：config.json 只有 1~2 KB，解析代价可忽略；
     *    而「漏判」的后果恰好就是本方法要修的这个 bug —— 省这点 I/O 不值得冒险。
     *  - **读失败保留内存值**，既不归档也不清空：reload 发生在运行期，此时用户已经在界面上
     *    看到了内容，「这次不刷新」远好于「把配置清成默认值」。
     *    （load 的读失败处理相反：那是启动阶段，还没人看过，归档留证 + 用默认值。）
     *  - 成功则**整体替换** mRoot（不逐键合并）：盘上才是唯一事实，合并会留下已删键的幽灵。
     *
     * 谁该调用：**所有会显示 ":game 进程写过的键" 的页面**。目前只有
     *   {@link MainActivity#onResume}（「继续上次」）。以后若 :game 也写别的键，
     *   读它的那个页面同样要在 onResume 里补一次 —— 这个坑不会自己消失。
     */
    public synchronized void reload(Context ctx) {
        if (mFile == null) { load(ctx); return; }
        if (!mFile.exists()) return;  // 文件被外部删了：保留内存值，不擅自重建
        JSONObject fresh;
        try {
            fresh = new JSONObject(Util.readText(mFile));
        } catch (Exception e) {
            Log.w(TAG, "config.json reload failed (" + e + ") — keeping in-memory values");
            return;
        }
        mRoot = fresh;
        int v = mRoot.optInt(K_VER, 0);
        if (v < CONFIG_VERSION) {
            // 别的进程可能写回了更老的配置：补版本号，但**绝不动它的其它字段**
            put(K_VER, CONFIG_VERSION);
            save();
        }
    }

    /** 用户补充的包名（内置白名单之外的） */
    public synchronized List<String> knownPackages() {
        ArrayList<String> out = new ArrayList<>();
        JSONArray a = mRoot.optJSONArray(K_KNOWN);
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    public synchronized void addKnownPackage(String pkg) {
        if (pkg == null) return;
        String p = pkg.trim();
        if (p.isEmpty()) return;
        JSONArray a = mRoot.optJSONArray(K_KNOWN);
        if (a == null) a = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            if (p.equals(a.optString(i))) return;
        }
        a.put(p);
        put(K_KNOWN, a);
        save();
    }

    /** 上次启动的版本标识（M2/M3 用） */
    public synchronized String lastVersion() {
        return mRoot.optString(K_LAST, "");
    }

    public synchronized void setLastVersion(String v) {
        put(K_LAST, v == null ? "" : v);
        save();
    }

    /** 导入版本登记表（M1）。每项: {file,label,version,size,md5,added}。 */
    public synchronized org.json.JSONArray imports() {
        org.json.JSONArray a = mRoot.optJSONArray(K_IMPORTS);
        return a == null ? new org.json.JSONArray() : a;
    }

    /** 同 file 视为重导（替换），否则追加。 */
    public synchronized void addImport(org.json.JSONObject entry) {
        if (entry == null) return;
        org.json.JSONArray a = mRoot.optJSONArray(K_IMPORTS);
        if (a == null) a = new org.json.JSONArray();
        String file = entry.optString("file", "");
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            if (o != null && file.equals(o.optString("file", ""))) {
                try {
                    a.put(i, entry);
                } catch (Exception e) {
                    Log.w(TAG, "addImport replace failed for '" + file + "': " + e);
                    return;
                }
                put(K_IMPORTS, a);
                save();
                return;
            }
        }
        a.put(entry);
        put(K_IMPORTS, a);
        save();
    }

    public synchronized void removeImport(String file) {
        org.json.JSONArray a = mRoot.optJSONArray(K_IMPORTS);
        if (a == null) return;
        org.json.JSONArray out = new org.json.JSONArray();
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            if (o != null && file.equals(o.optString("file", ""))) continue;
            out.put(o == null ? org.json.JSONObject.NULL : o);
        }
        put(K_IMPORTS, out);
        // 导入副本没了，它的槽分配也一并清掉（不留悬空引用）
        setSlotOf("import:" + file, "");
        save();
    }

    // ── M3：版本 → 槽 的分配 ──────────────────────────────────────────────

    /**
     * 某个版本用哪个槽。key 规则（{@link Versions.Entry#key()}）：
     * 已装 = 包名；已导入 = "import:" + 文件名。
     * 没分配返回空串，由调用方回退到 {@link Data#SLOT_DEFAULT}。
     */
    public synchronized String slotOf(String key) {
        if (key == null || key.isEmpty()) return "";
        JSONObject m = mRoot.optJSONObject(K_VSLOTS);
        return m == null ? "" : m.optString(key, "");
    }

    /** slot 传空串 = 清除分配（回落到默认槽） */
    public synchronized void setSlotOf(String key, String slot) {
        if (key == null || key.isEmpty()) return;
        JSONObject m = mRoot.optJSONObject(K_VSLOTS);
        if (m == null) m = new JSONObject();
        try {
            if (slot == null || slot.isEmpty()) m.remove(key);
            else m.put(key, slot);
        } catch (Exception e) {
            Log.w(TAG, "setSlotOf failed for '" + key + "': " + e);
            return;
        }
        put(K_VSLOTS, m);
        save();
    }

    /**
     * 槽改名 / 删除后修正分配表：把指向 oldName 的都改成 newName；
     * newName 为空表示槽被删了 ⇒ 清除分配（回落到默认槽）。
     * 返回被改动的版本数（供 UI 提示）。
     */
    public synchronized int retargetSlots(String oldName, String newName) {
        JSONObject m = mRoot.optJSONObject(K_VSLOTS);
        if (m == null || oldName == null || oldName.isEmpty()) return 0;
        java.util.Iterator<String> it = m.keys();
        List<String> hit = new ArrayList<>();
        while (it.hasNext()) {
            String k = it.next();
            if (oldName.equals(m.optString(k, ""))) hit.add(k);
        }
        if (hit.isEmpty()) return 0;
        try {
            for (String k : hit) {
                if (newName == null || newName.isEmpty()) m.remove(k);
                else m.put(k, newName);
            }
        } catch (Exception e) {
            Log.w(TAG, "retargetSlots failed: " + e);
            return 0;
        }
        put(K_VSLOTS, m);
        save();
        return hit.size();
    }

    /** 供 UI 展示：所有已分配关系（key → slot），按 key 排序 */
    public synchronized List<String[]> allSlotAssignments() {
        List<String[]> out = new ArrayList<>();
        JSONObject m = mRoot.optJSONObject(K_VSLOTS);
        if (m == null) return out;
        java.util.Iterator<String> it = m.keys();
        while (it.hasNext()) {
            String k = it.next();
            String v = m.optString(k, "");
            if (!v.isEmpty()) out.add(new String[]{k, v});
        }
        java.util.Collections.sort(out, new java.util.Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) { return a[0].compareTo(b[0]); }
        });
        return out;
    }

    // ── F3：全局设置 ──────────────────────────────────────────────────────

    /**
     * 新发现的版本缺省用哪个槽。
     * 空串 = 调用方回落到 {@link Data#SLOT_DEFAULT}（保留"没设置"与"显式设为 default"的区分）。
     */
    public synchronized String defaultSlot() {
        return mRoot.optString(K_DEF_SLOT, "");
    }

    public synchronized void setDefaultSlot(String s) {
        put(K_DEF_SLOT, s == null ? "" : s);
        save();
    }

    /**
     * 日志保留份数（F7 日志页用）。
     * ★ 桌面版纪律：坏值退默认 + WARNING **点名键名** —— 用户要拿这句话去 config.json 里找。
     */
    public synchronized int maxLogFiles() {
        if (!mRoot.has(K_LOGS)) return DEF_LOG_FILES;
        Object v = mRoot.opt(K_LOGS);
        if (v instanceof Number) {
            int n = ((Number) v).intValue();
            if (n >= 1 && n <= 999) return n;
            Log.w(TAG, "config key '" + K_LOGS + "' out of range (" + n
                    + "), falling back to default " + DEF_LOG_FILES);
        } else {
            Log.w(TAG, "config key '" + K_LOGS + "' is not a number (" + v
                    + "), falling back to default " + DEF_LOG_FILES);
        }
        return DEF_LOG_FILES;
    }

    public synchronized void setMaxLogFiles(int v) {
        put(K_LOGS, Math.max(1, Math.min(999, v)));
        save();
    }

    // ── F15：界面深浅色 ────────────────────────────────────────────────────

    /**
     * 界面深浅色（{@link ThemeMode#SYSTEM} / {@link ThemeMode#LIGHT} / {@link ThemeMode#DARK}）。
     *
     * ★ 为什么值得做成设置项：Android 的深浅色默认**只跟随系统**（`values-night/` 由系统
     *   uimode 选中），而系统那条曲线在晚上会自动变深、白天变浅 —— 用户盯着启动器点几下的事，
     *   没必要跟着系统的"日落时间"跳。桌面版也有同样的诉求（它直接给了一个外观开关）。
     *
     * ★ 坏值退默认 + WARNING 点名键名（与 maxLogFiles 同一套纪律，别用 `type(default)(value)`）。
     */
    public synchronized int themeMode() {
        if (!mRoot.has(K_THEME)) return ThemeMode.SYSTEM;
        Object v = mRoot.opt(K_THEME);
        if (v instanceof Number) {
            int n = ((Number) v).intValue();
            if (n >= ThemeMode.SYSTEM && n <= ThemeMode.DARK) return n;
            Log.w(TAG, "config key '" + K_THEME + "' out of range (" + n
                    + "), falling back to default " + ThemeMode.SYSTEM);
        } else {
            Log.w(TAG, "config key '" + K_THEME + "' is not a number (" + v
                    + "), falling back to default " + ThemeMode.SYSTEM);
        }
        return ThemeMode.SYSTEM;
    }

    public synchronized void setThemeMode(int v) {
        put(K_THEME, Math.max(ThemeMode.SYSTEM, Math.min(ThemeMode.DARK, v)));
        save();
    }

    // ── 界面语言 ──────────────────────────────────────────────────────────

    /**
     * 界面语言：{@link LocaleMode#SYSTEM}（空串）= 跟随系统；否则是 BCP47 标签。
     *
     * ★ **只做"类型"容错**（不是字符串就退默认 + WARNING 点名键名，与 maxLogFiles / themeMode 同纪律）。
     * ★ **"认不认识这个标签"由 {@link LocaleMode#of} 判** —— 名单住在资源里
     *   （`R.array.app_languages`，由构建期门禁 `RES-10` 保证与 `values-*` 目录一一对应），
     *   而 `Config` 刻意不依赖资源系统，所以不在这一层判。
     */
    public synchronized String appLanguage() {
        if (!mRoot.has(K_LANG)) return LocaleMode.SYSTEM;
        Object v = mRoot.opt(K_LANG);
        if (v instanceof String) return ((String) v).trim();
        Log.w(TAG, "config key '" + K_LANG + "' is not a string (" + v
                + "), falling back to default (follow system)");
        return LocaleMode.SYSTEM;
    }

    public synchronized void setAppLanguage(String tag) {
        put(K_LANG, tag == null ? LocaleMode.SYSTEM : tag.trim());
        save();
    }

    // ── F20：自动清理数据根残留（开关） ──────────────────────────────────────

    /**
     * 进存档页时要不要**自动**清掉数据根里的残留副本（默认开）。
     *
     * ★ 用户 2026-10-03：「我们是不是应该给一些功能加上开关」⇒ 只给这一条加了，理由：
     *   它是全工程**唯一一个"静默删文件"**的动作 —— 别的自动行为（自动备份有策略页、
     *   旧备份转 CAS 只动自己那份备份、地图预览只读）要么本来就能关，要么不删东西。
     *   ⚠️ 关掉**不等于**没有清理能力：设置页那一行始终能在你想清的时候"立即清理一次"。
     *
     * ★ 坏值退默认 + WARNING 点名键名（与 maxLogFiles / themeMode 同一套纪律）——
     *   用户要拿这句话去 `config.json` 里找。
     */
    public synchronized boolean autoCleanRedundant() {
        if (!mRoot.has(K_AUTO_CLEAN)) return DEF_AUTO_CLEAN;
        Object v = mRoot.opt(K_AUTO_CLEAN);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        Log.w(TAG, "config key '" + K_AUTO_CLEAN + "' is not a boolean (" + v
                + "), falling back to default " + DEF_AUTO_CLEAN);
        return DEF_AUTO_CLEAN;
    }

    public synchronized void setAutoCleanRedundant(boolean on) {
        put(K_AUTO_CLEAN, Boolean.valueOf(on));
        save();
    }

    // ── F5：自动备份（每槽策略 + 启动记账） ────────────────────────────────

    /** 某个槽的自动备份策略（对齐桌面版「存档分类设置」的三件套）。 */
    public static final class BackupPolicy {
        public final boolean enabled;
        public final int minMinutes;
        public final int maxBackups;

        BackupPolicy(boolean enabled, int minMinutes, int maxBackups) {
            this.enabled = enabled;
            this.minMinutes = minMinutes;
            this.maxBackups = maxBackups;
        }
    }

    /**
     * 取某个槽的自动备份策略。
     * 槽没配过 = 出厂默认；配了但某项坏 = **该项**退默认 + WARNING 点名 `backup_policy.&lt;槽&gt;.&lt;键&gt;`。
     * （绝不因为一个坏值就把整条策略或整份配置丢掉。）
     */
    public synchronized BackupPolicy backupPolicy(String slot) {
        JSONObject all = mRoot.optJSONObject(K_BACKUP);
        JSONObject o = (all == null || slot == null || slot.isEmpty())
                ? null : all.optJSONObject(slot);
        if (o == null) {
            return new BackupPolicy(DEF_BACKUP_ENABLED, DEF_BACKUP_MIN_MINUTES, DEF_BACKUP_MAX);
        }
        String scope = K_BACKUP + "." + slot;
        return new BackupPolicy(
                boolOf(o, "enabled", DEF_BACKUP_ENABLED, scope),
                intOf(o, "min_minutes", DEF_BACKUP_MIN_MINUTES, 0, 100000, scope),
                intOf(o, "max_backups", DEF_BACKUP_MAX, 1, 1000, scope));
    }

    public synchronized void setBackupPolicy(String slot, boolean enabled,
                                             int minMinutes, int maxBackups) {
        if (slot == null || slot.isEmpty()) return;
        JSONObject all = mRoot.optJSONObject(K_BACKUP);
        if (all == null) all = new JSONObject();
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled);
            o.put("min_minutes", Math.max(0, Math.min(100000, minMinutes)));
            o.put("max_backups", Math.max(1, Math.min(1000, maxBackups)));
            all.put(slot, o);
        } catch (Exception e) {
            Log.w(TAG, "setBackupPolicy failed for slot '" + slot + "': " + e);
            return;
        }
        put(K_BACKUP, all);
        save();
    }

    /**
     * 启动游戏**之前**调用：记下这次的槽与时刻。
     * 之所以要落盘而不是放内存：游戏会带走 :game 进程，而主进程也可能被系统回收 ——
     * 回来的那一刻要能知道"上次是几点开始玩的"，否则算不出运行时长。
     */
    public synchronized void sessionStart(String key, String slot) {
        JSONObject o = new JSONObject();
        try {
            o.put("key", key == null ? "" : key);
            o.put("slot", slot == null ? "" : slot);
            o.put("at", System.currentTimeMillis());
        } catch (Exception e) {
            Log.w(TAG, "sessionStart failed: " + e);
            return;
        }
        put(K_SESSION, o);
        save();
    }

    /** 上次记账的槽名（无记账 = 空串）。 */
    public synchronized String sessionSlot() {
        JSONObject o = mRoot.optJSONObject(K_SESSION);
        return o == null ? "" : o.optString("slot", "");
    }

    /** 上次记账的时刻（毫秒；无记账 = 0）。 */
    public synchronized long sessionStartedAt() {
        JSONObject o = mRoot.optJSONObject(K_SESSION);
        return o == null ? 0L : o.optLong("at", 0L);
    }

    /** 结算完（含"判定后决定跳过"）就清账 —— 不清的话每次回到启动器都会重判一遍。 */
    public synchronized void sessionClear() {
        mRoot.remove(K_SESSION);
        save();
    }

    // ── JSON 取值容错（对齐桌面版 _coerce_* 规格表：绝不 type(default)(value)） ──

    /**
     * JSON 里的布尔。
     * ★ 只认 true/false（数字 0/1 也认），**字符串 "false" 必须解析成 false**
     *   —— 这是桌面版踩过的经典坑（Python 的 `bool("false")` 是 True，会把开关反过来）。
     */
    private static boolean boolOf(JSONObject o, String key, boolean def, String scope) {
        if (!o.has(key)) return def;
        Object v = o.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0d;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(java.util.Locale.ROOT);
            if ("true".equals(s)) return true;
            if ("false".equals(s)) return false;
        }
        Log.w(TAG, "config " + scope + ": key '" + key + "' is not a boolean (" + v
                + "), falling back to default " + def);
        return def;
    }

    /** JSON 里的整数：非数字或越界都退默认 + WARNING 点名键名。 */
    private static int intOf(JSONObject o, String key, int def, int min, int max, String scope) {
        if (!o.has(key)) return def;
        Object v = o.opt(key);
        if (v instanceof Number) {
            int n = ((Number) v).intValue();
            if (n >= min && n <= max) return n;
            Log.w(TAG, "config " + scope + ": key '" + key + "' out of range (" + n
                    + "), falling back to default " + def);
            return def;
        }
        Log.w(TAG, "config " + scope + ": key '" + key + "' is not a number (" + v
                + "), falling back to default " + def);
        return def;
    }

    public synchronized void save() {
        if (mFile == null) return; // 尚未 load（不该发生，防御）
        try {
            Util.atomicWriteText(mFile, mRoot.toString(2));
        } catch (Exception e) {
            Log.w(TAG, "save config.json failed: " + e);
        }
    }

    private void put(String k, Object v) {
        try {
            mRoot.put(k, v);
        } catch (Exception e) {
            Log.w(TAG, "config put failed for key '" + k + "': " + e);
        }
    }
}
