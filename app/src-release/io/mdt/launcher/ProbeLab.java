package io.mdt.launcher;

import android.content.Context;

/** 产品版空壳（真正的验证台只在 dev 构建里，见 `app/src-dev`）。 */
final class ProbeLab {
    private ProbeLab() { }

    static void runIfArmed(Context ctx) { }

    /** 素材（资源补丁）实验的装填口 —— 产品版什么都不做。 */
    static void decorate(Context ctx, Injector.Plan plan) { }

    /** 本体 CAS spike 的 prepend 口 —— 产品版不前置任何 dex。 */
    static String dexPrepend(Context ctx) { return null; }

    /** 本体 CAS spike 的池注入口 —— 产品版什么都不做。 */
    static void afterInject(Context ctx, ClassLoader baseCl) { }

    /** 本体 CAS 的「不挂 AssetManager」口 —— 产品版永远返回 false（照旧挂 APK）。 */
    static boolean skipAssetMount(Context ctx) { return false; }

    /** 池是否就绪 —— 产品版永远 false（没有池这套东西）。 */
    static boolean poolReady(Context ctx) { return false; }

    /** dexPath 拼装口 —— 产品版就是 APK 自己。 */
    static String dexPath(Context ctx, java.io.File apk) { return apk.getAbsolutePath(); }

    /** 池里的 native ABI —— 产品版没有。 */
    static String nativeAbi(Context ctx) { return null; }

    /** 池里的 native 对象文件 —— 产品版没有。 */
    static java.io.File nativeFile(Context ctx, String abi, String name) { return null; }
}
