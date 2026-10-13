package io.mdt.launcher;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 「分享包」的**身份信息剥离**（第 141 轮）。★ **纯 Java**（不 import 任何 Android 类）
 * —— 与 {@link SettingsBin} / {@link MsavMeta} 同一条路子：能在 PC 上单独编译，
 * 拿**真实的 settings.bin** 直接断言，不需要设备。
 *
 * ── 为什么需要它（起因是一句流传的说法）──────────────────────────────────
 * 2026-10-13 用户问：「据说存档里包含玩家的 uid，分享存档会导致被冒充？」
 * 现场取证（416 份真实 `.msav` + 游戏 jar 的字节码）结论分三档：
 * <pre>
 *   · **单个存档**（`saves/*.msav`）——**不含**任何身份字段。
 *     写侧：`SaveVersion.writeMeta(DataOutput, StringMap extraTags)` 只在 `extraTags != null`
 *     时 `putAll`，而存档那条路的匿名 `SaveOptions`（`Saves$SaveSlot$1`）只设 `embedAssets`、
 *     `extraTags` 留 null；地图那条路（`MapIO$1`）才 `extraTags = map.tags`。
 *     语料：312 份存档 0 命中；把本机 settings.bin 里 22 条真实 `usid-*` 与 `uuid` 拿去
 *     在全部 416 份里搜（原字节 + base64 两种形态）也 **0 命中**。
 *   · **地图 / 蓝图** —— meta 里可能带 `steamid`，但那是**Steam 创意工坊的物品 ID**
 *     （`MapEditor.load`：`if(map.file.parent().parent().name().equals("1127400") && Vars.steam)
 *     tags.put("steamid", map.file.parent().name())`；`1127400` 是 Mindustry 的 Steam appid）
 *     ⇒ 泄的是"这份图是谁发的"这条**关联**，不是凭据。**本类不管这条**（属 P1，见 REF §91）。
 *   · **`settings.bin`** —— 🔴 **这才是真能导致冒充的那条**：`usid` 就是 Mindustry 的
 *     服务器身份（`Packets$ConnectPacket.usid`；`Administration` 拿它判管理员 / 封禁；
 *     `NetClient` 按 `usid-<ip:port>` 存），**无签名的 bearer 值** —— 谁拿到，在那一台就是你。
 * </pre>
 * ⇒ 而**整槽导出 / 备份 / 游戏原生 `exportData` 包都把 `settings.bin` 打进去**
 *   （{@link Data#SLOT_EXCLUDE} 那张"除这些以外全打"的表），所以"分享整槽"
 *   等于把你在每一台服务器上的身份一起发出去。本类就是给这条路开一个**脱敏出口**。
 *
 * ── 两条纪律 ─────────────────────────────────────────────────────────────
 *  ① **只删该删的**：{@link #strip} 保序删除，其余键**一个字节都不动**。
 *     判据是"本来就没有敏感键 ⇒ 打出来的包与「导出整槽」**逐字节相同**"
 *     （{@link #plan} 在这种情况下**直接沿用原字节**，连重编码都不做 —— 见下面那条）。
 *  ② **读不出来就不导出**：`settings.bin` 解析失败时 {@link Plan#ok()} 为 false，
 *     调用方**必须拒绝**（而不是"原样塞进去 + 小字提示"）——
 *     一个"没法确认里面有什么"的文件混进标着"已脱敏"的包里，是把风险留给用户自己去发现。
 *
 * ⚠️ **不加中文**：本类刻意只出「数字 + 原因原文」，用户可见的每一句都在
 *   `res/values(-zh)/strings.xml`（`share_*`）。理由与 `MsavMeta`/`SettingsBin` 的
 *   "码 + 参数"同源，这里更进一步 —— 连码都不需要，因为用户只会看到两种结果：
 *   "去掉了 N 项"或"读不出来，没导出"。
 */
public final class SharePack {

    /** 要被**改写**（剥键）的那个顶层文件 —— 判据只认**槽数据根顶层**这一个位置 */
    public static final String SETTINGS_FILE = "settings.bin";

    /** 游戏自己那份服务器收藏夹（本机设备实测 **89 台**，每条 `{name, address}`）。 */
    public static final String DROP_FILE = "server_list.json";

    /**
     * 整份**不放进分享包**的顶层条目（目录按前缀算：`crashes/…` 全部命中）。
     *
     * <p>两类，理由不同（用户 2026-10-13 拍的第二刀："日志和可重建的缓存都丢了就行，
     * 纯分享这东西没啥用吧"）：
     * <ol>
     *   <li><b>会指向你的</b>：`server_list.json`（你在哪些服玩过）、`crashes/` 与
     *     `last_log.txt`（**故障证据** —— 对分享毫无用处，却可能带昵称与服务地址）。</li>
     *   <li><b>可重建的缓存</b>：`previews/`（我们自己生成的地图缩略图）与
     *     `assetCache/`（游戏自己解出来的资产缓存）。收包的人那边游戏会重建
     *     ⇒ 对分享是纯粹的体积。</li>
     * </ol>
     * ⚠️ 两条如实的边界（别当它们不存在）：
     *   · `crashes/` 与 `last_log.txt` 是 `Data.SLOT_EXCLUDE` 里**有意保留**的
     *     （"故障前的唯一证据"）⇒ **只对分享包丢，本地备份那条路一个字都不动**；
     *   · `assetCache/` 理论上可能装着某份地图/存档的**补丁资产**（`REF §72.8`：
     *     编辑器导出会内嵌、就地保存不会）⇒ 跨机分享**极少数**地图可能因此缺件
     *     （实测 `hasExternalAssets=true` 只有 2/312）。用户选了"丢"，这里如实记。
     */
    public static final String[] DROP_TOPS = {
            DROP_FILE, "crashes", "last_log.txt", "previews", "assetCache",
    };

    /**
     * 精确匹配就要删掉的键。
     *
     * <p>每一项的**实测出处**（桌面真实 `settings.bin`，5908 个键，2026-10-13 只读扫描）：
     * <pre>
     *   uuid           1 条（`KVg6DSuTUM8=`）—— `Packets$ConnectPacket.uuid`，与 usid 并列的身份值
     *   ip             1 条（`frp-ten.com:64934`）—— 你**上次连的**服务器（重连 / 预填用）
     *   ip-bans        二进制 —— 你封过的人（**别人的 IP**）
     *   banned-names   二进制 —— 你封过的人（**别人的昵称**）
     * </pre>
     * ⚠️ `banned-names` / `ip-bans` 本机是空的（各 2 字节），但它们在别的机器上装的是**第三方 PII**：
     *   分享出去等于把别人的 IP 一起发出去 —— 这与"给我自己脱敏"无关，纯属不该外流。
     *
     * <p>★ **故意不删**（都是**有意识的决定**，别顺手加进来）：
     * <ul>
     *   <li>`name` —— 你自己的游戏昵称。它是**身份关联**、不是凭据（冒充不了任何人），
     *       而删掉它会让收包的人进游戏后发现自己没名字。用户 2026-10-13 明确选的就是这一档。</li>
     *   <li>`mod-<名字>-steamid` —— 模组的工坊物品 ID（与地图那条同族）。本机 0 条；
     *       它泄的是"你装了哪个模组"的发源地，不泄身份。</li>
     * </ul>
     */
    public static final String[] STRIP_EXACT = {"uuid", "ip", "ip-bans", "banned-names"};

    /** 前缀匹配就要删掉的键：`usid-<服务器>`（实测真实文件里 29 个，后面那串无法穷举） */
    public static final String STRIP_PREFIX = "usid-";

    private SharePack() {}

    // ── 判据（**只此一处**）──────────────────────────────────────────────────

    /**
     * 这个键算不算"要剥掉的身份信息"。
     * ★ 唯一的判据实现 —— 计划（{@link #plan}）与自检的遍历都用它，**别在别处再写一遍**
     *   （第 137 轮"同一段判据写两处必漂"那条教训）。
     */
    public static boolean isSensitive(String key) {
        if (key == null || key.isEmpty()) return false;
        if (key.startsWith(STRIP_PREFIX)) return true;
        for (String k : STRIP_EXACT) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    /**
     * 这个条目要不要整份丢掉（{@link Exporter.Xform} 传进来的是**相对槽数据根的路径**）。
     * ⚠️ **只认根级**：`mods/config/server_list.json`、`mods/x/crashes/` 之类都是别的模组的文件，
     *   一个字节都不许动 —— 所以命中判据是"**等于**那个名字"或"**以 `名字/` 开头**"，
     *   而不是 `contains`/`endsWith`（那两个会把模组里的同名文件一起吃掉）。
     * ★ 目录也走同一条判据（`crashes` 自己、以及 `crashes/x.txt` 都算命中）——
     *   这样**空目录**也不会漏在包里（{@link Exporter} 的目录分支会拿它整棵剪掉）。
     */
    public static boolean dropTop(String rel) {
        if (rel == null || rel.isEmpty()) return false;
        for (String n : DROP_TOPS) {
            if (n.equals(rel)) return true;
            if (rel.length() > n.length() + 1 && rel.startsWith(n)
                    && rel.charAt(n.length()) == '/') return true;
        }
        return false;
    }

    // ── 剥离（纯函数）────────────────────────────────────────────────────────

    /** {@link #strip} 的结果 */
    public static final class Stripped {
        /** 剥离之后的值表（保序；原表**没被动过**） */
        public final SettingsBin.Values values;
        /** 被删掉的键（按原文件顺序） */
        public final List<String> removed;

        Stripped(SettingsBin.Values values, List<String> removed) {
            this.values = values;
            this.removed = removed;
        }

        public int removedCount() {
            return removed.size();
        }

        /** 其中"服务器身份"（`usid-*`）有几条 —— 确认框里要分开说，这是最要紧的那一类 */
        public int serverIdCount() {
            int n = 0;
            for (String k : removed) {
                if (k.startsWith(STRIP_PREFIX)) n++;
            }
            return n;
        }
    }

    /**
     * 剥掉身份信息：**保序删除**，其余键一个字节都不动。
     *
     * @param v 读出来的值表（`null` ⇒ 原样返回一个空的 Stripped，调用方按"没有设置"处理）
     */
    public static Stripped strip(SettingsBin.Values v) {
        List<String> removed = new ArrayList<>();
        if (v == null) return new Stripped(null, removed);
        for (String k : v.all().keySet()) {
            if (isSensitive(k)) removed.add(k);
        }
        return new Stripped(v.withoutKeys(removed), removed);
    }

    // ── 计划：打包前算好，确认框里的数字就是真会发生的 ────────────────────────

    /**
     * 一次分享包的**预计算**结果。
     *
     * <p>为什么要有"计划"这一层（而不是打包时现算）：
     * <ul>
     *   <li>确认框要能说出**这次到底会去掉几项** —— 先弹框、再在后台发现读不出来，
     *       用户已经点了"选择保存位置"、系统已经建出一个 0 字节的 zip
     *       （{@link SlotIo} 里「空槽拦在 SAF 之前」是同一条纪律）；</li>
     *   <li>打包必须发生在后台线程，而 `settings.bin` 的解析是 IO + CPU
     *       （真实文件 5908 键、502 KB）⇒ 不能放 UI 线程，也不能放"已经开写之后"。</li>
     * </ul>
     */
    public static final class Plan {
        /**
         * 要写进包里的 `settings.bin` 字节。
         * 🔴 `null` = **原样拷贝**（两种情形：这个槽没有 `settings.bin`；或者有、但**一个敏感键都没删**）
         * —— 后者是有意的：既然什么都没删，就没必要重编码一遍（压缩过的文件重编码后
         * 字节可能不同，而"逐字节相同"正是我们对外承诺的那条判据）。
         */
        public byte[] settings;
        /** 这个槽有没有 `settings.bin` */
        public boolean hasSettings;
        /** 这次删掉了几个键 */
        public int removedKeys;
        /** 上面那些里，`usid-*`（服务器身份）有几个 */
        public int serverIds;
        /** 这个槽**真的有**、且会被整份丢掉的那些顶层条目名（按 {@link #DROP_TOPS} 的顺序） */
        public final java.util.List<String> dropped = new java.util.ArrayList<String>();
        /** 读/解析失败的原因（**只给日志、dev 报告与自检看**；用户看到的是资源里那句话） */
        public String failReason;

        public boolean ok() {
            return failReason == null;
        }

        /** 这个包与「导出整槽」的内容**完全一样**吗（没删键、也没丢文件） */
        public boolean untouched() {
            return removedKeys == 0 && dropped.isEmpty();
        }
    }

    /**
     * 算一次计划（**只读**，不写、不改、不建任何文件）。
     *
     * <p>本方法**不抛异常**：读不出来是"一种结果"（`failReason` 非空），
     * 因为调用方要做的是"拒绝这次导出"，而不是崩掉一个后台线程。
     *
     * @param slotDir 槽的数据根（`null` / 不存在 ⇒ 当成"没有设置可剥"，算成功）
     */
    public static Plan plan(File slotDir) {
        Plan p = new Plan();
        if (slotDir == null || !slotDir.isDirectory()) return p;
        // 先记下"这个槽真的有哪几样"（有没有是一回事，丢不丢是另一回事 —— 判据在 dropTop）
        for (String n : DROP_TOPS) {
            if (new File(slotDir, n).exists()) p.dropped.add(n);
        }

        File set = new File(slotDir, SETTINGS_FILE);
        if (!set.isFile()) return p;                 // 空槽/新槽：没有设置文件，没什么可剥
        p.hasSettings = true;
        try {
            SettingsBin.Values v = SettingsBin.read(set);
            Stripped s = strip(v);
            p.removedKeys = s.removedCount();
            p.serverIds = s.serverIdCount();
            // ★ 一个键都没删 ⇒ 一个字节都不动（见 Plan.settings 的注释）
            p.settings = p.removedKeys == 0 ? null : SettingsBin.encode(s.values);
        } catch (Throwable t) {
            p.failReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            p.settings = null;
        }
        return p;
    }

    /**
     * 计划里那份字节（`null` = 原样拷贝）—— 给 {@link Exporter.Xform} 用。
     * ⚠️ 抽成一个方法是为了让"哪一份字节进包"只有一处判断，界面与 dev 口走同一条。
     */
    public static byte[] bytesFor(String rel, Plan plan) {
        if (plan == null || !SETTINGS_FILE.equals(rel)) return null;
        return plan.settings;
    }
}
