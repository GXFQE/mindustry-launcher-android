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

    /**
     * 当前配置格式代际。改结构时 +1 并在 load() 里挂迁移。
     * <ul>
     *   <li>第 1 代：M3 的槽分配（`version_slots`）；</li>
     *   <li>第 2 代（2026-10-26）：名字从"按版本"改成"按项"（{@link #K_VROWNAMES}）——
     *       迁移见 {@link #migrateRowNames()}：老名字**抄给每一行**，别让用户升级后少一个名字。</li>
     * </ul>
     */
    public static final int CONFIG_VERSION = 2;

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
    /**
     * 一个版本**额外**绑定的槽 { "&lt;版本key&gt;": ["&lt;槽名&gt;", ...] } —— 每个额外绑定 = 列表里多一行。
     *
     * <p>★ 为什么另起一个键、不把 {@link #K_VSLOTS} 的值改成数组：那边存的是一根字符串，
     *   老版本启动器用 `optString` 读；换成数组后老版本读到的是 `["x"]` 这种**非法槽名**
     *   ⇒ 注入不了数据根 ⇒ 存档看起来没了。新键对老版本是**未知键**（原样保留、不解析）⇒ 降级安全。
     *   ⚠️ 于是"这个版本用哪些槽"有两个存储位（主槽 / 附加槽），但**读写只许走本类下面那几个方法**
     *   （`extraSlotsOf` / `addExtraSlot` / `removeExtraSlot` / `replaceSlotBinding` / `retargetSlots`），
     *   别在别处直接碰这两个键 —— 两处各写一遍必漂，而症状只会是"某个槽的绑定莫名不见了"。
     */
    private static final String K_VEXTRA = "version_extra_slots";
    /**
     * **主项**（这个版本的第一项启动项）的名字 { "&lt;版本key&gt;": "&lt;名字&gt;" }。
     *
     * <p>★★ 2026-10-26 用户：「让游戏命名跟随实例而不是跟随文件」—— 名字要跟着**启动项**走，
     *   不是跟着 APK 文件走。于是一个版本绑两个槽时，两项可以各叫各的名字。
     *
     * <p>★ 主项的名字**留在本键**（而不是和附加项一样按槽存），因为主项的槽会变
     *   （显式换槽 / 默认槽改了 / 槽改名），按槽存的名字会跟着槽"丢"掉；而主项在绑定表里的
     *   位置本来就不变 ⇒ 按**版本 key** 存才真的是"跟着这一项走"。
     *   附加项的身份**就是它绑的那个槽**（它存在的理由）⇒ 见 {@link #K_VROWNAMES}。
     *
     * <p>⚠️ 读写的唯一入口是 {@link #rowNameOf} / {@link #setRowNameOf}（`boundSlot == null` = 主项），
     *   别在别处直接碰这两个键 —— 两处各判一次必漂，症状只会是"某一项的名字莫名变成另一项的"。
     */
    private static final String K_VNAMES = "version_names";
    /**
     * **附加项**（绑在额外槽上的启动项）的名字
     * { "&lt;版本key&gt;": { "&lt;槽名&gt;": "&lt;名字&gt;" } }。
     *
     * <p>与 {@link #K_VEXTRA} **同一套口径**（一项附加绑定 = 一个启动项 = 可以有自己的名字）。
     * 所以凡是会动附加绑定的地方都必须**把名字一起搬**：换槽（{@link #replaceSlotBinding}）、
     * 槽改名 / 删槽（{@link #retargetSlots}）、删行 / 主项被删后提升（{@link #removeBinding}）。
     * 否则"给这一项改个名，换个槽回来名字就没了"—— 静默、只有用户自己会发现。
     *
     * <p>★ 为什么另起一个键：{@link #K_VNAMES} 的值是一根字符串，老版本启动器用 `optString` 读；
     *   改成嵌套对象后老版本读到的是 `{"…"}` 这种**非法名字**（会原样显示在列表里）。
     *   新键对老版本是**未知键**（原样保留、不解析）⇒ 降级安全。
     */
    private static final String K_VROWNAMES = "version_row_names";
    /** F3：新发现的版本缺省用哪个槽（空串 = 由调用方回落到 Data.SLOT_DEFAULT） */
    private static final String K_DEF_SLOT = "default_slot";
    /** F7：日志保留份数（日志页用） */
    private static final String K_LOGS = "max_log_files";
    /** F15：界面深浅色（0=跟随系统 1=浅色 2=深色） */
    private static final String K_THEME = "theme_mode";
    /**
     * 第 275 轮：**管理器界面**的屏幕方向（0=跟随系统 1=强制竖屏 2=强制横屏）—— 见 {@link OrientationMode}。
     * 只作用于管理器自己的页面；游戏那一页仍按它自己的清单（`screenOrientation="user"`）。
     */
    private static final String K_ORIENT = "orientation_mode";
    /** 界面语言（空串=跟随系统；否则 BCP47 标签，如 en / zh / zh-TW）—— 见 {@link LocaleMode} */
    private static final String K_LANG = "app_language";
    /** F5：每槽的自动备份策略 { "&lt;槽名&gt;": {enabled, min_minutes, max_backups} } */
    private static final String K_BACKUP = "backup_policy";
    /** F5：一次启动的记账 {key, slot, at} —— 游戏退出后回来结算自动备份用 */
    private static final String K_SESSION = "session";
    /** F20：进存档页时自动清理数据根残留（默认开；关掉后只能手动清） */
    private static final String K_AUTO_CLEAN = "auto_clean_redundant";
    /**
     * 第 296 轮：开发者模式**是否已解锁**（默认关）—— 「关于」页连点版本摘要 7 次打开。
     *
     * <p>★ 只存"解锁状态"：点击计数每次进页面都从 0 开始（见 {@link DevMode}）。
     *   本键只影响 About 页那张卡片显不显示，**不改变任何功能行为**。
     */
    private static final String K_DEV_MODE = "developer_mode";
    /**
     * 第 297 轮：「关于」页版本摘要被连点的**累计**次数（跨会话）—— 只给彩蛋用。
     *
     * <p>★ 与 {@link #K_DEV_MODE} 分开：解锁状态是"开关"，计数是"记账"。合成一个键的话，
     *   想统计"点了多少下"就得把开关也当数字读，而 bool 与 int 混在一个键里迟早出现"true = 1 下"。
     */
    private static final String K_TAPS = "tap_count";
    /**
     * 第 300 轮：**出厂 `content.json` 的指纹**（见 {@link Content}）。
     *
     * <p>用来判断 hub 里那份 `content.json` 还是不是"上次原样拷贝的那份"：
     * 没改过 ⇒ 升级时换成新出厂值；改过 ⇒ 一个字都不动（用户的编辑优先）。
     * 存的是出厂内容的 SHA-256，不是文件路径 —— 路径会变，内容不会。
     */
    private static final String K_CONTENT_HASH = "content_hash";
    /**
     * 第 173 轮：中转站保留份数。
     *
     * <p>普通条目（地图 / 存档 / 蓝图 / 模组 / 插件）与**整个槽**分开：一个槽可能几十 MB，
     * 沿用普通条目的份数会让中转站悄悄占掉一两个 GB。默认值与 {@link Trash#KEEP} /
     * {@link Trash#KEEP_SLOTS} 相同；坏值退默认 + WARNING 点名键名。
     */
    private static final String K_TRASH_KEEP = "trash_keep";
    private static final String K_TRASH_KEEP_SLOTS = "trash_keep_slots";
    /**
     * F23 之后：**已经提示过**的那一份崩溃报告（`&lt;槽&gt;|&lt;文件名&gt;`）—— 同一份只提示一次。
     *
     * <p>★ 为什么要有它："崩了马上弹窗"有两条路（进程活着时立刻 Toast / 回到界面时补弹），
     * 没有这个记帐就会**同一份崩两次提示两次**；反过来，不记又能重开应用再烦一次。
     */
    private static final String K_ALERT = "crash_alert_last";
    /** 第 174 轮：崩溃提示总开关（默认开；关掉只影响提示，不影响报告落盘 / 分析）。 */
    private static final String K_CRASH_ALERT_ENABLED = "crash_alert_enabled";
    /**
     * 第 311 轮：**接收预览版**（默认开 —— 1.0 之前每个版本都是 prerelease，
     * 默认关等于谁都收不到更新）。
     *
     * <p>两个通道的语义（见 {@link UpdateUi}）：
     * <ul>
     *   <li>开 ⇒ 读固定通道 `update-channel-preview`（覆盖 preview / rc / 正式）；</li>
     *   <li>关 ⇒ 读 `releases/latest`（GitHub 只把它指向**非 prerelease**，正好就是"稳定版"）。</li>
     * </ul>
     * ★ 1.0 发布时把默认改成 false（正式版用户不该被预览版打扰）。
     */
    private static final String K_UPDATE_PREVIEW = "update_preview";

    /**
     * 一档④（2026-10-06）：每个版本**上次启动的时刻** { "&lt;版本key&gt;": 毫秒 }。
     *
     * ★ 为什么需要它：{@link #sessionStartedAt()} 记的是"**正在进行**的那一次"，
     *   结算完就 {@link #sessionClear() 清掉} ⇒ 界面问不出"我上次什么时候玩的这个版本"。
     * ★ 与 session **同一次写入**（都在 {@link #sessionStart}）：不额外多一次落盘，
     *   也不会出现"记了 session 却没记历史"的半截状态。
     * ⚠️ 有上限（{@link #PLAYED_KEEP}）：版本列表可能长期累积，别让它无限长大。
     */
    private static final String K_PLAYED = "last_played";
    /** `last_played` 最多留几条（旧的先丢）—— 只是给界面看的一行字，不值得无限保存 */
    private static final int PLAYED_KEEP = 64;

    /**
     * 自动备份的出厂默认，逐条对齐桌面版 config.py 的 PROFILE_DEFAULTS
     * （auto_backup=True / min_playtime=20 / max_backups=20）。
     */
    public static final boolean DEF_BACKUP_ENABLED = true;
    public static final int DEF_BACKUP_MIN_MINUTES = 20;
    public static final int DEF_BACKUP_MAX = 20;
    /** 日志保留份数的出厂默认（对齐桌面版 DEFAULT_LOG_KEEP）。 */
    public static final int DEF_LOG_FILES = 20;
    /** 中转站保留份数的出厂默认（普通条目 / 整个槽）—— 与 Trash.KEEP / KEEP_SLOTS 对齐。 */
    public static final int DEF_TRASH_KEEP = 20;
    public static final int DEF_TRASH_KEEP_SLOTS = 3;
    /**
     * ★ F20：进存档页时自动清理数据根残留的出厂默认 = **开**（保持第 57 轮以来的行为不变）。
     *   ⚠️ 加开关的理由：这是全工程**唯一一个"静默删文件"**的动作（见 {@link Data#autoCleanRedundant}），
     *   而且第 57 轮把体检的可见入口删掉之后，它连"发生过"都不会说一声。
     */
    public static final boolean DEF_AUTO_CLEAN = true;
    /** 崩溃提示的出厂默认 = **开**（第 174 轮加的开关；关掉不影响报告本身）。 */
    public static final boolean DEF_CRASH_ALERT = true;
    /**
     * 接收预览版的出厂默认 = **开**（第 311 轮）。
     * ⚠️ 1.0 之前每个版本都是 prerelease —— 默认 false 等于谁都收不到更新，所以这里必须是 true。
     *    1.0 发布时改成 false。
     */
    public static final boolean DEF_UPDATE_PREVIEW = true;

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
            // 迁移挂点（逐代往上补）：
            //   第 0 代 -> 第 1 代：无结构变化，只补版本号；
            //   第 1 代 -> 第 2 代：名字改成"按项"存，老名字抄给每一行（见 migrateRowNames）。
            if (v < 2) migrateRowNames();
            put(K_VER, CONFIG_VERSION);
            save();
        }
    }

    /**
     * 第 2 代迁移（2026-10-26，名从"按版本"改成"按项"）—— **把老名字抄给这个版本的每一行**。
     *
     * <p>★ 为什么必须抄、不能"附加行从空开始"：老数据里一个名字是全版本共用的，只保留在主项上，
     *   就等于用户升级后**悄悄少了一个名字**（他会读成"名字丢了"；而这个功能本来就是他要求加的）。
     *   抄完之后每一行各有自己的名字，从此各改各的 —— 这正是本轮要的行为。
     *
     * <p>幂等：只补**还没有自己名字**的行；重复跑不会覆盖用户后来改过的名字。
     *
     * <p>⚠️ 包级可见（不是 private）：自检要直接钉住"抄给每一行 / 不覆盖改过的名字"这两条
     *   —— 迁移只跑在 `load()` / `reload()` 里（进程内只跑一次），自检没法重放那一步。
     */
    void migrateRowNames() {
        JSONObject names = mRoot.optJSONObject(K_VNAMES);
        JSONObject extras = mRoot.optJSONObject(K_VEXTRA);
        if (names == null || extras == null) return;
        java.util.Iterator<String> it = names.keys();
        while (it.hasNext()) {
            String key = it.next();
            String n = names.optString(key, "");
            if (n.isEmpty()) continue;
            JSONArray a = extras.optJSONArray(key);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                String slot = a.optString(i, "");
                if (slot.isEmpty()) continue;
                if (rowNameOf(key, slot).isEmpty()) setRowNameOf(key, slot, n);
            }
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
            // 别的进程可能写回了更老的配置：补版本号 + 补这一代的迁移，
            //   但**绝不动它的其它字段**（迁移只往空位里补，见 migrateRowNames 的幂等性）。
            if (v < 2) migrateRowNames();
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

    /** F23 之后：已经提示过的那一份崩溃报告（`<槽>|<文件名>`；空串 = 还没提示过任何一份） */
    public synchronized String crashAlerted() {
        return mRoot.optString(K_ALERT, "");
    }

    public synchronized void setCrashAlerted(String v) {
        put(K_ALERT, v == null ? "" : v);
        save();
    }

    /**
     * 崩溃提示总开关（默认开）。
     *
     * <p>★ 关掉只影响 {@link CrashAlert} 的 Toast / 弹窗；崩溃报告照常落盘、照常进
     * 崩溃分析页。坏值退默认 + WARNING 点名键名（与 autoClean / maxLogFiles 同纪律）。
     */
    public synchronized boolean crashAlertEnabled() {
        if (!mRoot.has(K_CRASH_ALERT_ENABLED)) return DEF_CRASH_ALERT;
        Object v = mRoot.opt(K_CRASH_ALERT_ENABLED);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        Log.w(TAG, "config key '" + K_CRASH_ALERT_ENABLED + "' is not a boolean (" + v
                + "), falling back to default " + DEF_CRASH_ALERT);
        return DEF_CRASH_ALERT;
    }

    public synchronized void setCrashAlertEnabled(boolean on) {
        put(K_CRASH_ALERT_ENABLED, Boolean.valueOf(on));
        save();
    }

    /**
     * 第 311 轮：是否接收**预览版**（默认开；坏值退默认 + WARNING 点名键名 —— 同上一族纪律）。
     *
     * <p>★ 它决定更新器读哪个通道（见 {@link UpdateUi}），不影响已经装上的版本。
     */
    public synchronized boolean updatePreview() {
        if (!mRoot.has(K_UPDATE_PREVIEW)) return DEF_UPDATE_PREVIEW;
        Object v = mRoot.opt(K_UPDATE_PREVIEW);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        Log.w(TAG, "config key '" + K_UPDATE_PREVIEW + "' is not a boolean (" + v
                + "), falling back to default " + DEF_UPDATE_PREVIEW);
        return DEF_UPDATE_PREVIEW;
    }

    public synchronized void setUpdatePreview(boolean on) {
        put(K_UPDATE_PREVIEW, Boolean.valueOf(on));
        save();
    }

    /** 导入版本登记表（M1）。每项: {file,label,version,size,md5,added}。 */
    public synchronized org.json.JSONArray imports() {
        org.json.JSONArray a = mRoot.optJSONArray(K_IMPORTS);
        return a == null ? new org.json.JSONArray() : a;
    }

    /**
     * 某一项导入登记（按文件名）。没有 ⇒ null。
     * ★ 2026-10-25：删版本时要把这条登记**原样存进中转站**（放回来时靠它重建），
     *   所以读入口只留这一处 —— 别在 Importer 里自己遍历 `imports()` 再拼一份。
     */
    public synchronized org.json.JSONObject importEntry(String file) {
        if (file == null || file.isEmpty()) return null;
        org.json.JSONArray a = mRoot.optJSONArray(K_IMPORTS);
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            if (o != null && file.equals(o.optString("file", ""))) return o;
        }
        return null;
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
        // 导入副本没了，它的槽分配也一并清掉（不留悬空引用）——
        // ★ 包括**这一版新增的附加绑定**（否则列表里会留下一行"指向不存在的版本"的启动项），
        //   以及**每一项的名字**（否则会留给下一次同文件名的导入）
        clearBindings("import:" + file);
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

    // ── 一个版本绑多个槽（每个额外绑定 = 列表里一行启动项）───────────────────
    //
    // 需求原文（2026-10-24）：「同一个版本有可能有玩家需要两个不同存档，每次启动换槽很麻烦，
    // 但是复制一份又占地方」。⇒ 不复制 APK，只**多绑一个槽**：每绑一个槽，列表里就多一行，
    // 点哪行走哪个槽的存档。判据（哪一行用哪个槽）只有一处：{@link Versions#slotFor}。

    /** 这个版本**额外**绑了哪些槽（保持写入顺序；主槽不在这里，见 {@link #slotOf}） */
    public synchronized List<String> extraSlotsOf(String key) {
        List<String> out = new ArrayList<>();
        if (key == null || key.isEmpty()) return out;
        JSONObject m = mRoot.optJSONObject(K_VEXTRA);
        JSONArray a = m == null ? null : m.optJSONArray(key);
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /**
     * 这个版本是不是已经绑着这个槽（实际主槽 + 全部附加绑定）。
     *
     * <p>★ 主槽要取**实际会用的那个**：没显式分配过时是默认槽（`defaultSlot` 或 `default`）。
     *   只看 `slotOf` 的话，"还没分配过槽的版本 + 选默认槽"会被当成没绑过 ⇒ 加一行**看不见的行**
     *   （展开时按"与主槽重复"跳过），而 Toast 还说"已加一行"。
     * ★ **调用方别再自己按行算一遍**（第 236 轮真机抓过）：曾经在 `Versions` 里另写了一个
     *   "这一行的槽 + 附加绑定"的版本 ⇒ 在**附加行**上打开加槽列表时，主行正在用的那个槽照样列出来，
     *   点了才被这里拒掉。列表与写入必须是同一份判据。
     */
    public synchronized boolean hasSlotBinding(String key, String slot) {
        if (slot == null || slot.isEmpty()) return false;
        if (slot.equals(effectivePrimaryOf(key))) return true;
        return extraSlotsOf(key).contains(slot);
    }

    /** 这个版本**实际**会用的主槽（显式分配 → 默认槽 → 内置 `default`）—— 与 `Versions.slotFor` 同口径 */
    private String effectivePrimaryOf(String key) {
        String s = slotOf(key);
        if (!s.isEmpty()) return s;
        String d = defaultSlot();
        return d.isEmpty() ? Paths.SLOT_DEFAULT : d;
    }

    /**
     * 追加一个附加绑定。已绑着（主槽或别的附加槽）⇒ 返回 false 且**什么都不写**
     * （不做"覆盖"，否则界面上会悄悄少一行 —— 变化必须是用户点出来的那一下）。
     */
    public synchronized boolean addExtraSlot(String key, String slot) {
        if (key == null || key.isEmpty() || slot == null || slot.isEmpty()) return false;
        if (hasSlotBinding(key, slot)) return false;
        JSONObject m = mRoot.optJSONObject(K_VEXTRA);
        if (m == null) m = new JSONObject();
        try {
            JSONArray a = m.optJSONArray(key);
            if (a == null) a = new JSONArray();
            a.put(slot);
            m.put(key, a);
        } catch (Exception e) {
            Log.w(TAG, "addExtraSlot failed for '" + key + "': " + e);
            return false;
        }
        put(K_VEXTRA, m);
        save();
        return true;
    }

    /** 去掉一个附加绑定（主槽不在此列；动主槽请用 {@link #setSlotOf}） */
    public synchronized boolean removeExtraSlot(String key, String slot) {
        if (key == null || key.isEmpty() || slot == null || slot.isEmpty()) return false;
        JSONObject m = mRoot.optJSONObject(K_VEXTRA);
        JSONArray a = m == null ? null : m.optJSONArray(key);
        if (a == null) return false;
        List<String> kept = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (slot.equals(s)) changed = true;
            else if (!s.isEmpty()) kept.add(s);
        }
        if (!changed) return false;
        try {
            if (kept.isEmpty()) {
                m.remove(key);                          // 不留空数组（config.json 里一行噪音）
            } else {
                JSONArray arr = new JSONArray();
                for (String s : kept) arr.put(s);
                m.put(key, arr);
            }
        } catch (Exception e) {
            Log.w(TAG, "removeExtraSlot failed for '" + key + "': " + e);
            return false;
        }
        storeExtra(m);
        return true;
    }

    /**
     * 落盘 {@link #K_VEXTRA}。**空了就把整个键删掉** —— 真机验收时 config.json 里留下了
     * `"version_extra_slots": {}`，虽然无害，但一个空对象会让人以为"这里有东西"。
     */
    private void storeExtra(JSONObject m) {
        if (m == null || m.length() == 0) mRoot.remove(K_VEXTRA);
        else put(K_VEXTRA, m);
        save();
    }

    /**
     * 去掉**这一行**的绑定；主行被去掉时把第一条附加绑定提升成主行。
     *
     * <p>★ 为什么主行也允许"去掉"（2026-10-25 用户定案：行就是实例，删掉最后一行 = 删版本）：
     *   主行只是"这一版的主分配存在 {@link #K_VSLOTS} 里"的实现细节，不该是一种特权 ——
     *   否则"删掉 default 那一行、只留存档B"这件事永远做不到。
     * <p>★ 提升之后**必须**把被提升的那条从附加里删掉，否则它同时是主槽又是附加槽
     *   ⇒ 列表里两行一模一样。
     * <p>★★ 名字要跟着**项**走（2026-10-26）：删附加项 ⇒ 它自己的名字一起清；删主项、提升附加项 ⇒
     *   被提升那一项带**它自己的名字**上来（旧主项的名字随旧主项消失 —— 名字是"这一项"的，
     *   不是"这个版本"的，白送给新主项就是张冠李戴）。
     *
     * @return true = 确实动过；调用方自己去判断"这个版本还有没有别的行"
     */
    public synchronized boolean removeBinding(String key, String slot) {
        if (key == null || key.isEmpty() || slot == null || slot.isEmpty()) return false;
        JSONObject vs = mRoot.optJSONObject(K_VSLOTS);
        String prim = vs == null ? "" : vs.optString(key, "");
        boolean isPrimaryRow = slot.equals(prim)
                || (prim.isEmpty() && slot.equals(effectivePrimaryOf(key)));
        if (!isPrimaryRow) {
            if (!removeExtraSlot(key, slot)) return false;
            setRowNameOf(key, slot, "");     // 这一项没了，它的名字也一起走
            return true;
        }
        List<String> ex = extraSlotsOf(key);
        if (!ex.isEmpty()) {
            String up = ex.get(0);
            String upName = rowNameOf(key, up);   // 提上来的那一项叫什么 —— 摘附加之前先读
            removeExtraSlot(key, up);   // 先摘附加（它会 save）
            setSlotOf(key, up);         // 再写成主分配
            setNameOf(key, upName);     // 名字跟着"这一项"走到主项那一格（旧主项的名字到此为止）
            setRowNameOf(key, up, "");  // 它已经不在附加表里了，别留一份影子
            return true;
        }
        setSlotOf(key, "");
        setNameOf(key, "");
        return true;
    }

    /** {@link #replaceSlotBinding} 的返回值（都是"用户能看懂"的原因，不是异常） */
    public static final int BIND_OK = 0;
    public static final int BIND_TARGET_TAKEN = 1;   // 目标槽这个版本已经绑着
    public static final int BIND_NOT_FOUND = 2;      // old 不在这个版本上（别人刚改过？）
    public static final int BIND_BAD_ARG = 3;

    /**
     * 把**这一行**从 oldSlot 换到 newSlot —— 主槽走 {@link #K_VSLOTS}，附加槽原地改数组。
     *
     * <p>★ 为什么不能"直接 setSlotOf(key, new)"：那样在任何一行上点换槽都会改到**主行**，
     *   附加行看着没反应（或更糟：两行变成同一个槽、界面一模一样却互不相干）。
     */
    public synchronized int replaceSlotBinding(String key, String oldSlot, String newSlot) {
        if (key == null || key.isEmpty() || oldSlot == null || oldSlot.isEmpty()
                || newSlot == null || newSlot.isEmpty()) return BIND_BAD_ARG;
        if (oldSlot.equals(newSlot)) return BIND_OK;
        if (hasSlotBinding(key, newSlot)) return BIND_TARGET_TAKEN;

        JSONObject vs = mRoot.optJSONObject(K_VSLOTS);
        if (vs != null && oldSlot.equals(vs.optString(key, ""))) {
            try {
                vs.put(key, newSlot);
            } catch (Exception e) {
                Log.w(TAG, "replaceSlotBinding(primary) failed: " + e);
                return BIND_NOT_FOUND;
            }
            put(K_VSLOTS, vs);
            save();
            return BIND_OK;
        }

        JSONObject ex = mRoot.optJSONObject(K_VEXTRA);
        JSONArray a = ex == null ? null : ex.optJSONArray(key);
        if (a == null) return BIND_NOT_FOUND;
        boolean hit = false;
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (oldSlot.equals(s)) { out.put(newSlot); hit = true; }
            else out.put(s);
        }
        if (!hit) return BIND_NOT_FOUND;
        try {
            ex.put(key, out);
        } catch (Exception e) {
            Log.w(TAG, "replaceSlotBinding(extra) failed: " + e);
            return BIND_NOT_FOUND;
        }
        put(K_VEXTRA, ex);
        save();
        // ★ 这一项的名字跟着它一起换槽（名字按槽存，见 K_VROWNAMES）——
        //   漏了这一步的症状是"换个槽回来名字没了"，静默且只有用户自己会发现。
        //   主项不在这里：它的名字按版本 key 存，换槽压根不动那一格。
        String nm = rowNameOf(key, oldSlot);
        if (!nm.isEmpty()) {
            setRowNameOf(key, newSlot, nm);
            setRowNameOf(key, oldSlot, "");
        }
        return BIND_OK;
    }

    /**
     * 去掉一个版本的**全部行状态**（删导入版本 / 自检清理）：主槽、附加槽、以及**每一项的名字**。
     *
     * <p>★ 名字也算"行状态"（2026-10-26）：版本没了还留着它的名字 ⇒ 下一次同文件名的导入会
     *   顶着一个它没起过的名字出现。所以这里一次清干净，别让调用方自己补 —— 补一处漏一处。
     */
    public synchronized void clearBindings(String key) {
        if (key == null || key.isEmpty()) return;
        setSlotOf(key, "");
        setNameOf(key, "");
        clearRowNames(key);
        JSONObject ex = mRoot.optJSONObject(K_VEXTRA);
        if (ex == null || !ex.has(key)) return;
        ex.remove(key);
        storeExtra(ex);
    }

    // ── 给**启动项**起的名字（2026-10-16 起给版本命名 → 2026-10-26 起按项命名）──
    //
    // 需求原文（2026-10-26）：「让游戏命名跟随实例而不是跟随文件」。
    // 一个版本绑两个槽 = 两个**实例**（各自的存档 / 设置 / 模组），以前共用一个名字 ⇒
    // 给第二项改名会把第一项也改掉，两项永远同名、只能靠槽徽标区分。现在名字跟着"这一项"走。

    /**
     * **主项**（这个版本的第一项启动项）的名字。没起名返回空串（调用方回落到 APK 自己的 label）。
     *
     * <p>⚠️ 它**只代表主项**（2026-10-26 起）。要问"某一项叫什么"一律走 {@link #rowNameOf} ——
     *   直接调本方法去拼界面的，都会在"这个版本有第二项"时把两项叫成同一个名字。
     */
    public synchronized String nameOf(String key) {
        if (key == null || key.isEmpty()) return "";
        JSONObject m = mRoot.optJSONObject(K_VNAMES);
        return m == null ? "" : m.optString(key, "");
    }

    /** 主项的名字；传空串 = 清除（回落到 APK 自己的 label）。附加项见 {@link #setRowNameOf} */
    public synchronized void setNameOf(String key, String name) {
        if (key == null || key.isEmpty()) return;
        JSONObject m = mRoot.optJSONObject(K_VNAMES);
        if (m == null) m = new JSONObject();
        try {
            if (name == null || name.trim().isEmpty()) m.remove(key);
            else m.put(key, name.trim());
        } catch (Exception e) {
            Log.w(TAG, "setNameOf failed for '" + key + "': " + e);
            return;
        }
        put(K_VNAMES, m);
        save();
    }

    /**
     * **这一项**（启动项）的名字 —— 界面上问"这一项叫什么"的**唯一入口**。
     *
     * @param boundSlot 这一项的 {@code Entry.boundSlot}：`null`（或空）= **主项**（它的槽会变，
     *                  名字按版本 key 存，见 {@link #K_VNAMES}）；非空 = 附加项，名字按**它绑的那个槽**
     *                  存（见 {@link #K_VROWNAMES}）
     * @return 没起名 = 空串（调用方回落到 APK 自己的 label）；**绝不**因为另一项起过名就给一个默认
     */
    public synchronized String rowNameOf(String key, String boundSlot) {
        if (key == null || key.isEmpty()) return "";
        if (boundSlot == null || boundSlot.isEmpty()) return nameOf(key);
        JSONObject all = mRoot.optJSONObject(K_VROWNAMES);
        JSONObject one = all == null ? null : all.optJSONObject(key);
        return one == null ? "" : one.optString(boundSlot, "");
    }

    /**
     * 给**这一项**起名；空串 = 清除（回落到 APK 自己的 label）。只动这一项，别的启动项一个字不变。
     *
     * @param boundSlot 同 {@link #rowNameOf}（`null`/空 = 主项）
     */
    public synchronized void setRowNameOf(String key, String boundSlot, String name) {
        if (key == null || key.isEmpty()) return;
        String n = name == null ? "" : name.trim();
        if (boundSlot == null || boundSlot.isEmpty()) {
            setNameOf(key, n);                 // 主项：与老数据同一格，天然不需要迁移
            return;
        }
        JSONObject all = mRoot.optJSONObject(K_VROWNAMES);
        if (all == null) {
            if (n.isEmpty()) return;           // 没起过名 ⇒ 清除是空操作（不留空对象）
            all = new JSONObject();
        }
        JSONObject one = all.optJSONObject(key);
        if (one == null) {
            if (n.isEmpty()) return;
            one = new JSONObject();
        }
        try {
            if (n.isEmpty()) one.remove(boundSlot);
            else one.put(boundSlot, n);
            // ★ 新建的那一份必须挂回 `all`（漏了这一步：读到的永远是 null，名字写进去就没了 ——
            //   真机自检当场抓到的 8 条红，全是"附加项的名字读不回来"）
            if (one.length() == 0) all.remove(key);
            else all.put(key, one);
        } catch (Exception e) {
            Log.w(TAG, "setRowNameOf failed for '" + key + "'/'" + boundSlot + "': " + e);
            return;
        }
        if (all.length() == 0) mRoot.remove(K_VROWNAMES);   // 空对象不留（同 storeExtra）
        else put(K_VROWNAMES, all);
        save();
    }

    /** 清掉这个版本**全部附加项**的名字（主项名字走 {@link #setNameOf}） */
    private void clearRowNames(String key) {
        JSONObject all = mRoot.optJSONObject(K_VROWNAMES);
        if (all == null || !all.has(key)) return;
        all.remove(key);
        if (all.length() == 0) mRoot.remove(K_VROWNAMES);
        else put(K_VROWNAMES, all);
        save();
    }

    /**
     * 槽改名 / 删除后修正绑定表（**主槽与附加绑定都要修**）。
     *
     * <p>语义差别（界面上必须分开说）：
     *   · 主槽：`newName` 非空 ⇒ 改名跟过去；空 ⇒ **清除分配**（回落到默认槽，这一行还在）；
     *   · 附加绑定：`newName` 非空 ⇒ 改名跟过去；空 ⇒ **这一行整个消失**（它存在的理由就是那个槽）。
     *   ⇒ 所以返回值要分开计数，别把"消失了几行"说成"回落到默认槽"。
     */
    public static final class Retarget {
        /** 主分配被改/被清的版本数 */
        public int primary;
        /** 附加绑定被改 / 被移除的**行**数 */
        public int extras;
        public int total() { return primary + extras; }
    }

    /**
     * 见 {@link Retarget}。实现只有这一处 —— 两个存储位（主槽 / 附加槽）在同一个循环外一起收口，
     * 免得"改名修了主槽、附加行还指着旧名字"这种只在真机上慢慢发现的漂移。
     */
    public synchronized Retarget retargetSlots(String oldName, String newName) {
        Retarget r = new Retarget();
        if (oldName == null || oldName.isEmpty()) return r;
        boolean drop = (newName == null || newName.isEmpty());

        // ① 主分配 { key: slot }
        JSONObject vs = mRoot.optJSONObject(K_VSLOTS);
        if (vs != null) {
            List<String> hit = new ArrayList<>();
            java.util.Iterator<String> it = vs.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (oldName.equals(vs.optString(k, ""))) hit.add(k);
            }
            try {
                for (String k : hit) {
                    if (drop) vs.remove(k);
                    else vs.put(k, newName);
                }
            } catch (Exception e) {
                Log.w(TAG, "retargetSlots(primary) failed: " + e);
                hit.clear();
            }
            if (!hit.isEmpty()) {
                r.primary = hit.size();
                put(K_VSLOTS, vs);
            }
        }

        // ② 附加绑定 { key: [slot, ...] }
        JSONObject ex = mRoot.optJSONObject(K_VEXTRA);
        if (ex != null) {
            List<String> keys = new ArrayList<>();
            java.util.Iterator<String> it = ex.keys();
            while (it.hasNext()) keys.add(it.next());
            boolean touched = false;
            for (String k : keys) {
                JSONArray a = ex.optJSONArray(k);
                if (a == null) continue;
                List<String> kept = new ArrayList<>();
                boolean changed = false;
                for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i, "");
                    if (s.isEmpty()) continue;
                    boolean renamed = oldName.equals(s);
                    if (renamed) {
                        changed = true;
                        // ★ 改名与删槽**都要计数**：界面上"跟着改了 N 行" / "消失了 N 行"都靠这个数
                        //   （第 236 轮自检当场抓到：只在删槽那条分支里 ++，改名时 extras 恒为 0）。
                        r.extras++;
                        if (drop) {
                            // 这一行没了 ⇒ 它自己的名字也清掉（名字是按槽存的，见 K_VROWNAMES）
                            setRowNameOf(k, s, "");
                            continue;
                        }
                        s = newName;
                    }
                    // 改名时可能撞上同一版本已有的绑定（极少见，但撞了就必须去重，否则两行一模一样）
                    if (kept.contains(s)) {
                        // 被并掉的那一行：名字随它一起消失（不覆盖活下来那一项的名字）
                        if (renamed) setRowNameOf(k, oldName, "");
                        continue;
                    }
                    if (renamed) {
                        String nm = rowNameOf(k, oldName);
                        if (!nm.isEmpty() && rowNameOf(k, s).isEmpty()) setRowNameOf(k, s, nm);
                        setRowNameOf(k, oldName, "");
                    }
                    kept.add(s);
                }
                if (!changed) continue;
                touched = true;
                try {
                    if (kept.isEmpty()) {
                        ex.remove(k);
                    } else {
                        JSONArray arr = new JSONArray();
                        for (String s : kept) arr.put(s);
                        ex.put(k, arr);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "retargetSlots(extra) failed for '" + k + "': " + e);
                }
            }
            if (touched) {
                if (ex.length() == 0) mRoot.remove(K_VEXTRA);   // 空对象不留（同 storeExtra）
                else put(K_VEXTRA, ex);
            }
        }

        if (r.total() > 0) save();
        return r;
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

    // ── 第 173 轮：中转站保留份数（普通条目 / 整个槽分开） ───────────────────

    /**
     * 读一个 1..999 的整数键：坏值 / 越界都退默认，并 WARNING **点名键名**。
     * 与 {@link #maxLogFiles()} 同一套纪律；中转站两项共用这一份，别各写一遍。
     */
    private synchronized int intKey(String key, int def, int min, int max) {
        if (!mRoot.has(key)) return def;
        Object v = mRoot.opt(key);
        if (v instanceof Number) {
            int n = ((Number) v).intValue();
            if (n >= min && n <= max) return n;
            Log.w(TAG, "config key '" + key + "' out of range (" + n
                    + "), falling back to default " + def);
        } else {
            Log.w(TAG, "config key '" + key + "' is not a number (" + v
                    + "), falling back to default " + def);
        }
        return def;
    }

    /** 中转站普通条目（地图 / 存档 / 蓝图 / 模组 / 插件）保留份数。 */
    public synchronized int trashKeep() {
        return intKey(K_TRASH_KEEP, DEF_TRASH_KEEP, 1, 999);
    }

    public synchronized void setTrashKeep(int v) {
        put(K_TRASH_KEEP, Math.max(1, Math.min(999, v)));
        save();
    }

    /** 中转站里**整个槽**的保留份数（默认 3，一个槽可能几十 MB）。 */
    public synchronized int trashKeepSlots() {
        return intKey(K_TRASH_KEEP_SLOTS, DEF_TRASH_KEEP_SLOTS, 1, 999);
    }

    public synchronized void setTrashKeepSlots(int v) {
        put(K_TRASH_KEEP_SLOTS, Math.max(1, Math.min(999, v)));
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

    // ── 管理器界面的屏幕方向（2026-10-27 第 275 轮）─────────────────────────

    /**
     * 管理器界面用哪个屏幕方向（{@link OrientationMode} 的 SYSTEM / PORTRAIT / LANDSCAPE）。
     *
     * <p>需求原文：「我觉得需要加个页面强制横屏和强制竖屏」—— 真机上自动旋转是关的，
     * 想看一眼横屏效果就得先去系统设置里开自动旋转，验完再关。
     *
     * <p>★ 只作用于**管理器自己的页面**（`BaseActivity` 统一 `setRequestedOrientation`），
     * 游戏那一页仍按它自己的清单走（`screenOrientation="user"`）。
     * ★ 坏值退默认 + WARNING 点名键名（与 themeMode / maxLogFiles 同一套纪律）。
     */
    public synchronized int orientationMode() {
        if (!mRoot.has(K_ORIENT)) return OrientationMode.SYSTEM;
        Object v = mRoot.opt(K_ORIENT);
        if (v instanceof Number) {
            int n = ((Number) v).intValue();
            if (n >= OrientationMode.SYSTEM && n <= OrientationMode.LANDSCAPE) return n;
            Log.w(TAG, "config key '" + K_ORIENT + "' out of range (" + n
                    + "), falling back to default " + OrientationMode.SYSTEM);
        } else {
            Log.w(TAG, "config key '" + K_ORIENT + "' is not a number (" + v
                    + "), falling back to default " + OrientationMode.SYSTEM);
        }
        return OrientationMode.SYSTEM;
    }

    public synchronized void setOrientationMode(int v) {
        put(K_ORIENT, Math.max(OrientationMode.SYSTEM, Math.min(OrientationMode.LANDSCAPE, v)));
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

    // ── 第 296 轮：开发者模式（连点版本摘要解锁）─────────────────────────

    /**
     * 开发者模式是否已解锁（默认关）。
     *
     * <p>★ 与 {@link #autoCleanRedundant()} 同纪律：坏值退默认 + WARNING **点名键名** ——
     *   用户要拿这句话去 `config.json` 里找。
     */
    public synchronized boolean devMode() {
        if (!mRoot.has(K_DEV_MODE)) return false;
        Object v = mRoot.opt(K_DEV_MODE);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        Log.w(TAG, "config key '" + K_DEV_MODE + "' is not a boolean (" + v
                + "), falling back to default false");
        return false;
    }

    public synchronized void setDevMode(boolean on) {
        put(K_DEV_MODE, Boolean.valueOf(on));
        save();
    }

    /**
     * 版本摘要被连点的**累计**次数（跨会话；没记录 = 0）。
     * 坏值 / 负数退 0 + WARNING 点名键名（与 {@link #trashKeep()} 同一套读数纪律）。
     */
    public synchronized int tapCount() {
        return intKey(K_TAPS, 0, 0, Integer.MAX_VALUE);
    }

    /**
     * 记账并落盘，返回记完之后的累计值。界面上只有 `+1` 一种调用。
     *
     * <p>★ 收 delta 而不是另给一个 setter：自检要能"加一再加回来"，不污染用户真实的计数；
     *   而且负数天然夹到 0（{@link #tapCount()} 的下界），不会把计数记成负的。
     */
    public synchronized int bumpTapCount(int delta) {
        long next = (long) tapCount() + delta;
        int v = (int) Math.max(0L, Math.min((long) Integer.MAX_VALUE, next));
        put(K_TAPS, Integer.valueOf(v));
        save();
        return v;
    }

    /**
     * 把连点计数清零（「重新变回普通人」时调用）。
     *
     * <p>用户 2026-10-29：「应该给一个重置版本号计数的方式（比如关开发者模式？）」——
     * 关掉开发者模式就等于"从头再来"，累计数也该跟着归零，否则那个数只增不减、还会跨版本留着。
     *
     * <p>★ 只清计数、**不动「是否解锁」**（那是 {@link #setDevMode} 的事）：两件事分开写，
     *   自检才能单独验证"计数能归零"而不必真的切换开关。
     */
    public synchronized void resetTapCount() {
        put(K_TAPS, Integer.valueOf(0));
        save();
    }

    /** 出厂 `content.json` 的指纹（空串 = 还没记过，见 {@link #K_CONTENT_HASH}）。 */
    public synchronized String contentHash() {
        return mRoot.optString(K_CONTENT_HASH, "");
    }

    public synchronized void setContentHash(String hash) {
        put(K_CONTENT_HASH, hash == null ? "" : hash);
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
        rememberPlayed(key);
        save();
    }

    /**
     * 记一笔"这个版本被启动过"（一档④）。与 {@link #sessionStart} 同一次写盘。
     * ⚠️ 超过 {@link #PLAYED_KEEP} 条时**丢最旧的那几条**（按时刻），不是拒绝新值。
     */
    private void rememberPlayed(String key) {
        if (key == null || key.trim().isEmpty()) return;
        JSONObject all = mRoot.optJSONObject(K_PLAYED);
        if (all == null) all = new JSONObject();
        try {
            all.put(key, System.currentTimeMillis());
        } catch (Exception e) {
            Log.w(TAG, "rememberPlayed failed: " + e);
            return;
        }
        if (all.length() > PLAYED_KEEP) {
            // 找最旧的一条删掉（JSONObject 没有有序删除 ⇒ 扫一遍取最小）
            String oldest = null;
            long min = Long.MAX_VALUE;
            java.util.Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                long v = all.optLong(k, 0L);
                if (v < min) {
                    min = v;
                    oldest = k;
                }
            }
            if (oldest != null && !oldest.equals(key)) all.remove(oldest);
        }
        put(K_PLAYED, all);
    }

    /**
     * 这个版本上次启动的时刻（毫秒）；从没启动过 = 0。
     * ★ 只读、不进 {@link #sessionStartedAt} 那条链路（那条是"正在进行的这一局"）。
     */
    public synchronized long lastPlayed(String key) {
        if (key == null) return 0L;
        JSONObject all = mRoot.optJSONObject(K_PLAYED);
        return all == null ? 0L : all.optLong(key, 0L);
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
