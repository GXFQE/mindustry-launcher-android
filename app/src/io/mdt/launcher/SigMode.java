package io.mdt.launcher;

import android.content.Context;

/**
 * 游戏本体 APK 的**签名校验三档**（第 415 轮）。
 *
 * <p>三档按「松 → 严」排：{@link #LOG} 只记账、{@link #ASK} 让用户当场决定、
 * {@link #REJECT} 直接拒绝。默认 {@link #ASK} —— 既不会像 REJECT 那样把自家改端
 * （MindustryX / 汉化包）挡在门外，也不会像 LOG 那样悄悄放行一个从没见过的签名。
 *
 * <p>★ 值存在 `Config.sig_check_mode`（int）。这个键是本轮新加的，
 * 缺键 = 出厂默认；不 bump {@code CONFIG_VERSION}（缺键吃默认是这一族键的既有做法）。
 */
final class SigMode {

    /** 只写 `report-import.txt`，导入照常。 */
    static final int LOG = 0;
    /** 签名不在受信任列表里 ⇒ 弹卡问用户（默认）。 */
    static final int ASK = 1;
    /** 签名不在受信任列表里 ⇒ 清理副本并拒绝导入。 */
    static final int REJECT = 2;

    /** 出厂默认 = 询问。 */
    static final int DEFAULT = ASK;

    /** 选择列表里的顺序：从「最松」到「最严」，与语义的递进一致。 */
    static final int[] ALL = {LOG, ASK, REJECT};

    private SigMode() {}

    static int of(Context c) {
        return Config.get().sigMode();
    }

    static void set(Context c, int m) {
        Config.get().setSigMode(m);
    }

    static boolean valid(int m) {
        return m == LOG || m == ASK || m == REJECT;
    }

    /** 三档各自的**人话**（也是设置页那行的副标题）。 */
    static int labelRes(int m) {
        switch (m) {
            case LOG: return R.string.sig_mode_log;
            case REJECT: return R.string.sig_mode_reject;
            default: return R.string.sig_mode_ask;
        }
    }
}
