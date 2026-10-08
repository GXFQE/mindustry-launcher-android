package io.mdt.launcher;

import android.content.Context;

import java.io.File;

/**
 * {@link Mods} 里那些**"码"到文案**的映射（Android 侧）。
 *
 * ★ 为什么要有这个类：`Mods.State` 原来**枚举自己带显示文案**（`ENABLED("启用","启用")` …），
 *   `Mods.gates` 原来在 Java 里拼中文 ⇒ 英文界面下会冒出中文。枚举常量名本身就是**稳定的码**
 *   （而且与游戏 `ModState` 同名同序，不许改）⇒ 映射放这里，核心只留码。
 *
 * 🔴 **加一个状态就必须在这里加两条资源 + 两条分支**：`switch` 带 `default` 兜底，
 *   所以漏映射**不崩、不报错**，界面只是**静默退化**（徽标空白）。正因为不崩，
 *   自检里才要**遍历 {@link Mods.State#values()}** 过一遍。
 */
final class ModsText {

    private ModsText() {}

    /**
     * 句子形式（详情弹窗 / 报告）—— **别用在徽标上**：徽标挤在标题右边，太长会把标题挤到折行。
     */
    static String stateLabel(Context c, Mods.State st) {
        if (st == null) return "";
        switch (st) {
            case ENABLED: return Trans.get(c, R.string.mods_state_enabled);
            case CONTENT_ERRORS: return Trans.get(c, R.string.mods_state_content_errors);
            case MISSING_DEPENDENCIES: return Trans.get(c, R.string.mods_state_missing_deps);
            case INCOMPLETE_DEPENDENCIES: return Trans.get(c, R.string.mods_state_incomplete_deps);
            case CIRCULAR_DEPENDENCIES: return Trans.get(c, R.string.mods_state_circular_deps);
            case UNSUPPORTED: return Trans.get(c, R.string.mods_state_unsupported);
            case DISABLED: return Trans.get(c, R.string.mods_state_disabled);
            default: return "";
        }
    }

    /**
     * 徽标短形式（列表行）。与 {@link #stateLabel} **不是同一个东西** —— 只有本来就一样短的
     * 那三个状态（启用 / 内容有错 / 循环依赖）才与句子形式共用同一条资源。
     */
    static String stateBadge(Context c, Mods.State st) {
        if (st == null) return "";
        switch (st) {
            case MISSING_DEPENDENCIES: return Trans.get(c, R.string.mods_badge_missing_deps);
            case INCOMPLETE_DEPENDENCIES: return Trans.get(c, R.string.mods_badge_incomplete_deps);
            case UNSUPPORTED: return Trans.get(c, R.string.mods_badge_unsupported);
            case DISABLED: return Trans.get(c, R.string.mods_badge_disabled);
            default: return stateLabel(c, st);
        }
    }

    /**
     * ★ **这一行需不需要再补一句"为什么"**（2026-10-08 两次真机反馈的合并判据）。
     *
     * <p>列表行的构成是「标题 + 徽标（{@link #badge}，报结果）+ 副标题（可含一句原因）」。
     * 徽标现在报的是**结果**（会加载 / 不会加载 / 未读到设置 / 加载会失败），而"原因"来自
     * {@link #stateLabel} —— 两者**永远不同**（结果 ≠ 原因），所以依赖 / 版本那几类照旧要补一句；
     * 但「被关掉」这种原因由调用方换成更口语的那句（见 `ModsActivity` 里对 DISABLED 的分支），
     * 免得又出现"同一个词喊两遍"。
     */
    static boolean needsReason(Mods.State st) {
        return st != Mods.State.ENABLED && st != Mods.State.DISABLED
                && st != Mods.State.UNSUPPORTED;      // UNSUPPORTED 的三个成因上面几行各自说过了
    }

    /**
     * ★★ 列表行的**徽标** —— 报的是**结果**（下次启动会不会加载），**不是**游戏内部那个开关位
     * （2026-10-08 用户：「**你不需要告诉用户在游戏关闭时模组到底启没启用啊**」）。
     *
     * <p>为什么改：`-enabled` 是**游戏自己的记账**（只有游戏会写，而且每次启动都可能被
     * "整槽跳过"改写），把它当"状态"报给用户既没意义、又和"下次启动会被关掉"打架
     * （见 REF §86.7 / §86.10）。用户关心的是**它下次会不会加载**。
     *
     * <p>四条判据：
     * <ul>
     *   <li>读到设置 + 启用 ⇒ 「会加载」；</li>
     *   <li>**没读到设置文件** ⇒ 「未读到设置」（`-enabled` 不存在时 arc 给的是默认值 true，
     *       那不是我们读到的状态）；</li>
     *   <li>声明了 java 却打包里没有 `classes.dex` ⇒ 「加载会失败」（优先于「会加载」）；</li>
     *   <li>被关掉 ⇒ 「不会加载」。</li>
     * </ul>
     * <p>🔴 **还要看"下次启动会不会整槽跳过"**（2026-10-08 用户：「**所以为啥还是会加载啊**」）：
     * `launchid.dat` 在 + `modcrashdisable` 开 ⇒ 游戏会在**加载之前**把整槽模组的 `-enabled` 写成 false
     * （§86.1③ 的字节码）⇒ 那时它**根本不会被加载**。所以"现在是启用"**不等于**"下次启动会加载"，
     * 徽标必须把这条算进去，否则它和我们自己卡片那句警告互相打脸。
     *
     * <p>⚠️ 依赖 / 版本 / 内容那几类**保留原来的短词**（缺依赖 / 不兼容 / 内容有错 / 循环依赖）——
     * 它们本身就是**原因**，比笼统的「不会加载」信息量大。
     */
    static String badge(Context c, Mods.State st, boolean settingsKnown, boolean willFailJava,
                        boolean skipPending) {
        if (st == Mods.State.ENABLED) {
            if (!settingsKnown) return Trans.get(c, R.string.mods_badge_unknown);
            if (willFailJava) return Trans.get(c, R.string.mods_badge_willfail);
            if (skipPending) return Trans.get(c, R.string.mods_badge_noload);
            return Trans.get(c, R.string.mods_badge_load);
        }
        if (st == Mods.State.DISABLED) return Trans.get(c, R.string.mods_badge_noload);
        return stateBadge(c, st);
    }

    /**
     * 详情页顶上那行的**句子形式**：`下次启动：会不会加载`（+ 一句原因）。
     * 与 {@link #badge} 同一套语义，只是能多带一句为什么。
     *
     * <p>★ 原因用**游戏里的说法**（2026-10-08 用户：「**游戏里面显示的是加载失败而不是禁用啊**」）：
     * 那条 `-failed` 是游戏自己写的（MindustryX 会读它并显示成「加载失败」）⇒ 我们说「被关掉了」
     * 就与用户屏幕上的字对不上。带记录 ⇒ 说「游戏里显示「加载失败」」；不带记录（用户手动关的）
     * ⇒ 才说「被关掉了」。
     */
    static String outcome(Context c, Mods.State st, boolean settingsKnown, boolean willFailJava,
                          boolean failedMark, boolean skipPending) {
        if (st == Mods.State.ENABLED) {
            if (!settingsKnown) return Trans.get(c, R.string.mods_badge_unknown);
            if (willFailJava) {
                return Trans.get(c, R.string.mods_outcome_noload_fmt,
                        Trans.get(c, R.string.mods_badge_willfail));
            }
            if (skipPending) {
                return Trans.get(c, R.string.mods_outcome_noload_fmt,
                        Trans.get(c, R.string.mods_reason_skip_next));
            }
            return Trans.get(c, R.string.mods_badge_load);
        }
        String why = st != Mods.State.DISABLED ? stateLabel(c, st) : reasonOff(c, failedMark);
        return Trans.get(c, R.string.mods_outcome_noload_fmt, why);
    }

    /** 关闭态的**原因**：带游戏的失败记录 ⇒ 用游戏的说法；否则就是"被关掉了"。 */
    static String reasonOff(Context c, boolean failedMark) {
        return Trans.get(c, failedMark ? R.string.mods_reason_failed : R.string.mods_reason_off);
    }

    /** 徽标是不是"警告色"（与 {@link #badge} 同源判据，别再各写一套）。 */
    static boolean badgeWarns(Mods.State st, boolean settingsKnown, boolean willFailJava) {
        return st != Mods.State.ENABLED || !settingsKnown || willFailJava;
    }

    /**
     * ★★ 「游戏里的开关」那一节的**表头** —— 带上**读自哪个文件、什么时候读的**
     * （2026-10-08 用户：「游戏没开时用户也不知道模组到底启没启用啊」）。
     *
     * <p>模组的开关只有**游戏**会写（见 {@code Mods.failedKey} 的 Javadoc），我们只能读；
     * 不写清"这是几点的值"，用户就无法判断它是不是刚才那次游戏跑完留下的。
     */
    static String settingsHead(Context c, File settingsFile) {
        if (settingsFile == null || !settingsFile.isFile()) {
            return Trans.get(c, R.string.mods_detail_settings_head);
        }
        String when = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(settingsFile.lastModified()));
        return Trans.get(c, R.string.mods_detail_settings_head_fmt, when);
    }

    /**
     * ★★ 摘要卡那一句该显示**哪一句**（0 = 不显示）。判据全在**机制**上，与界面无关
     * （2026-10-08 用户真机：「**明明游戏禁用了所有模组但依旧显示会加载**」/「**就是目前这个状态，
     * 但下次启动是不加载的**」）。
     *
     * <p>🔴 这里修的是一个**真的漏报**：原来那句的显示条件是"有模组带 `-failed` 标记"
     * （`anyFailed`），而"下次启动会整槽跳过"的判据是 **`launchid.dat` 在 + `modcrashdisable` 开**
     * ——两件事**互相独立**。用户在游戏里点过恢复（`-failed` 被清掉）之后：
     * 文件里 12 个模组都是"启用"，但 `launchid.dat` 还在 ⇒ 下次启动游戏照样会把它们全关掉，
     * 而我们的卡片**一句话都不说**，只剩列表里的「会加载」⇒ 用户看到的就是"明明会被禁用却说会加载"。
     *
     * <p>判据表（先看"下次会不会跳"，它是最要紧的未来事实）：
     * <ul>
     *   <li>`skipModLoading` ⇒ {@link R.string#mods_warn_failed}（下次启动会关整槽，**与有没有标记无关**）</li>
     *   <li>否则 `launchidExists && anyFailed` ⇒ `mods_warn_failed_noskip`（你关了跳过开关 ⇒ 下次还会加载）</li>
     *   <li>否则 `anyFailed` ⇒ `mods_warn_failed_applied`（跳过已经发生过，模组现在是被关掉的）</li>
     *   <li>都没有 ⇒ 0（不显示；干净槽不该有这句）</li>
     * </ul>
     */
    static int cardNote(Mods.Scan scan, boolean anyFailed) {
        if (scan != null && scan.skipModLoading) return R.string.mods_warn_failed;
        if (anyFailed) {
            return (scan != null && scan.launchIdExists)
                    ? R.string.mods_warn_failed_noskip : R.string.mods_warn_failed_applied;
        }
        return 0;
    }

    /**
     * ★★ **那次崩溃还算不算"活着"**（摘要卡该不该还挂着「最可能的原因：…」）。
     *
     * <p>用户原话：「**已经不崩溃了为什么还显示最可能的原因**」。判据两条（任一成立就算活着）：
     * <ul>
     *   <li>下次启动还会因它**整槽跳过**（`launchid.dat` 在 + `modcrashdisable` 开）——
     *       这时那句话是**可行动**的（先关掉点名那个模组）；</li>
     *   <li>它留下的**后果还在**（有模组带着那次写的记录 / 还关着）。</li>
     * </ul>
     * 游戏这次跑完了（`launchid.dat` 被 `finishLaunch` 删掉）且模组都开着 ⇒ 那是**历史**，
     * 卡片不该再挂着它（要考古去运行日志 —— 导出里那份判断一个字都没少）。
     */
    static boolean crashStillLive(Mods.Scan scan, boolean anyFailed) {
        return (scan != null && scan.skipModLoading) || anyFailed;
    }

    /**
     * ★★ **单一嫌疑人**（2026-10-08，用户：「其实崩溃那个弹窗也可能加上的」）。
     *
     * <p>归因器只点了**一个**模组、而且它还在我们的扫描结果里 ⇒ 返回它；
     * 否则 null（**并列时点哪一个都不对** ⇒ 宁可不给这个动作）。
     * 两处共用：摘要卡那行可点（{@code ModsActivity}）与崩溃弹窗（{@code CrashAlert}）。
     */
    static Mods.Info soleSuspect(java.util.List<Mods.Info> mods, CrashAnalysis.Verdict v) {
        java.util.List<String> internals = CrashAnalysis.blamedInternals(v);
        if (mods == null || internals == null || internals.size() != 1) return null;
        String want = internals.get(0);
        for (Mods.Info m : mods) {
            if (m != null && m.internalName != null
                    && want.equals(m.internalName.toLowerCase(java.util.Locale.ROOT))) {
                return m;
            }
        }
        return null;
    }

    /**
     * 模组**说明文件**读不出来时的"人话版"原因（原来在 `Mods.Info.metaReason()` 里）。
     *
     * ★ 为什么搬出来：`Mods.Info` 是纯数据类、拿不到 `Context`，而这两句会经
     *   `mods_warn_meta_fmt` **直接显示在模组详情里** ⇒ 英文界面下会冒中文。
     * ⚠️ 与 `SettingsText.userReason` 同一条纪律：**先看码**（`metaErrCode`，我们自己写的那 8 句
     *   在 `Mods` 里都配了码）；码为 0 时才退回"异常形态"判断；最后才原样透传
     *   （那时 `metaError` 是我们自己的中文诊断，给排查看的）。
     */
    static String infoMetaReason(Context c, Mods.Info m) {
        if (m != null && m.metaErrCode != Mods.Info.M_NONE) {
            switch (m.metaErrCode) {
                case Mods.Info.M_COLON: return Trans.get(c, R.string.mods_meta_colon);
                case Mods.Info.M_NO_META_HERE: return Trans.get(c, R.string.mods_meta_no_meta_here);
                case Mods.Info.M_NO_META_IN_PACK: return Trans.get(c, R.string.mods_meta_no_meta_in_pack);
                case Mods.Info.M_META_TOO_BIG: return Trans.get(c, R.string.mods_meta_too_big);
                case Mods.Info.M_META_UNREADABLE: return Trans.get(c, R.string.mods_meta_unreadable);
                case Mods.Info.M_META_BAD_FORMAT: return Trans.get(c, R.string.mods_meta_bad_format);
                case Mods.Info.M_META_NO_NAME: return Trans.get(c, R.string.mods_meta_no_name);
                case Mods.Info.M_META_BAD_JSON: return Trans.get(c, R.string.mods_meta_bad_json);
                default: break;
            }
        }
        String e = m == null || m.metaError == null ? "" : m.metaError.trim();
        if (e.isEmpty()) return Trans.get(c, R.string.mods_meta_no_file);
        if (e.matches("^[A-Za-z_$][A-Za-z0-9_$]*(Exception|Error)\\b.*")) {
            return Trans.get(c, R.string.mods_meta_bad_format);
        }
        return e;
    }

    /**
     * 模组包**导入 / 跨槽复制**的失败原因（`Mods.PackResult`）。
     *
     * 🔴 与 `SettingsText.userReason` 同一条纪律：**第一层不许夹带"原因"**
     *   （异常原文 / `metaReason` 诊断）；**中性参数**（路径 / 文件名）可以进。
     * ⚠️ 码为 0 时退回 `error` 原文（那是给维护者的诊断）。
     */
    static String packReason(Context c, Mods.PackResult r) {
        if (r == null) return "";
        switch (r.errCode) {
            case Mods.PackResult.P_SRC_MISSING:
                return Trans.get(c, R.string.pack_err_src_missing_fmt, r.errS1);
            case Mods.PackResult.P_SRC_OPEN:
                return Trans.get(c, R.string.pack_err_src_open);
            case Mods.PackResult.P_NO_MODS_DIR:
                return Trans.get(c, R.string.pack_err_no_mods_dir);
            case Mods.PackResult.P_NAME_EMPTY:
                return Trans.get(c, R.string.pack_err_name_empty);
            case Mods.PackResult.P_NAME_COLON:
                return Trans.get(c, R.string.pack_err_name_colon);
            case Mods.PackResult.P_NAME_DOT:
                return Trans.get(c, R.string.pack_err_name_dot);
            case Mods.PackResult.P_NAME_EXT:
                return Trans.get(c, R.string.pack_err_name_ext);
            case Mods.PackResult.P_MKDIR:
                return Trans.get(c, R.string.pack_err_mkdir_fmt, r.errS1);
            case Mods.PackResult.P_NAME_TAKEN:
                return Trans.get(c, R.string.pack_err_name_taken_fmt, r.errS1);
            case Mods.PackResult.P_NOT_A_MOD:
                return Trans.get(c, R.string.pack_err_not_a_mod);
            case Mods.PackResult.P_IMPORT_FAILED:
                return Trans.get(c, R.string.pack_err_import_failed);
            case Mods.PackResult.P_COPY_NO_SRC:
                return Trans.get(c, R.string.pack_err_copy_no_src);
            case Mods.PackResult.P_COPY_NO_DST:
                return Trans.get(c, R.string.pack_err_copy_no_dst);
            case Mods.PackResult.P_COPY_SAME:
                return Trans.get(c, R.string.pack_err_copy_same);
            case Mods.PackResult.P_COPY_UNREADABLE:
                return Trans.get(c, R.string.pack_err_copy_unreadable);
            case Mods.PackResult.P_COPY_NOTHING:
                return Trans.get(c, R.string.pack_err_copy_nothing);
            default:
                return r.error == null ? "" : r.error;
        }
    }
}
