package io.mdt.launcher;

import android.content.Context;

/** 产品版空壳（真正的验证台只在 dev 构建里，见 `app/src-dev`）。 */
final class ProbeLab {
    private ProbeLab() { }

    static void runIfArmed(Context ctx) { }

    /** 素材（资源补丁）实验的装填口 —— 产品版什么都不做。 */
    static void decorate(Context ctx, Injector.Plan plan) { }
}
