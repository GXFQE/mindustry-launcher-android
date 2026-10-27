package io.mdt.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;

/**
 * 管理器界面的**屏幕方向**（第 275 轮，2026-10-27）。
 *
 * <p>需求原文：「我觉得需要加个页面强制横屏和强制竖屏」——补一句「启动器，现在每次我想看下横屏
 * 效果必须开自动旋转」。⇒ 设置页加一行「屏幕方向」：跟随系统 / 强制竖屏 / 强制横屏。
 *
 * <p>★ 只管**管理器自己的页面**（{@link BaseActivity} 的子类）。{@link GameSlot} 例外 ——
 * 它跑完六步启动管线就被游戏自己的 Activity 盖住，方向该由游戏决定（见 {@code BaseActivity#honorsOrientation}）。
 *
 * <p>★ {@link #SYSTEM} 映射成 {@code SCREEN_ORIENTATION_USER}（**用户当前倾向的方向**），
 * 而不是 {@code UNSPECIFIED}。真机实测（2026-10-27，HONOR / Android 16，自动旋转关着）：
 * 从「强制横屏」切回 {@code UNSPECIFIED} 后，**屏幕停在横屏不动** —— 那只是"这一页没有偏好"，
 * 系统并不会替你转回用户锁定的方向；而 {@code USER} 是"用户现在倾向哪个方向"，切回去立刻转正，
 * 自动旋转开着时又照样跟传感器走。⇒ 这才是界面上「跟随系统」四个字的意思。
 *
 * <p>★ 横屏用 {@code SENSOR_LANDSCAPE}（两个横屏方向都认）：横屏有两种自然握法，
 * 写死 {@code LANDSCAPE} 时另一种握法会把界面**倒着**显示，用户会以为是 bug。
 * 竖屏用 {@code PORTRAIT}（不跟传感器翻转）：竖屏只有一种握法值得跟随，
 * 而"躺在床上把手机倒过来"是常态，跟随它反而会让界面莫名其妙翻面。
 *
 * <p>⚠️ 本工程**只有一套竖屏布局**（`res/layout`，没有 `layout-land`）—— 强制横屏是
 * "按横屏宽度铺开同一套布局"，不是专门排过的横屏版式（那一项在盘点里仍是「平板 / 横屏布局」暂缓）。
 * 这一行设置的意义是**能看 / 能验**，而不是"横屏已经排好了"。
 */
public final class OrientationMode {
    /** 跟随系统（默认）：映射成 USER（= 用户当前倾向的方向，见类注释里的真机实测） */
    public static final int SYSTEM = 0;
    /** 强制竖屏 */
    public static final int PORTRAIT = 1;
    /** 强制横屏（两个横屏方向都行） */
    public static final int LANDSCAPE = 2;

    private OrientationMode() {}

    /** 当前配置的模式（顺手确保 Config 已加载，幂等）。 */
    public static int of(Context ctx) {
        Config.get().load(ctx);
        return Config.get().orientationMode();
    }

    /**
     * 模式 → {@link Activity#setRequestedOrientation} 的值。
     *
     * <p>★ 这是**唯一**的映射处：{@link BaseActivity} 建页面时用它，自检也钉它
     * （三个值必须互不相同，否则"强制"了却看不出区别，且没有任何报错）。
     */
    public static int screenOrientation(int mode) {
        switch (mode) {
            case PORTRAIT:  return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case LANDSCAPE: return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            // 「跟随系统」= 用户当前倾向的方向。**不是 UNSPECIFIED** —— 那只是"没偏好"，
            // 从强制横屏切回来时屏幕会停横屏不动（真机实测，见类注释）。
            default:        return ActivityInfo.SCREEN_ORIENTATION_USER;
        }
    }

    /** 模式对应的显示名资源（设置页列表项与副标题共用，避免两处措辞分叉） */
    public static int labelRes(int mode) {
        switch (mode) {
            case PORTRAIT:  return R.string.orientation_portrait;
            case LANDSCAPE: return R.string.orientation_landscape;
            default:        return R.string.orientation_system;
        }
    }
}
