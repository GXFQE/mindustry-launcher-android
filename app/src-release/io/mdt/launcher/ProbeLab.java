package io.mdt.launcher;

import android.content.Context;

/** 产品版空壳（真正的验证台只在 dev 构建里，见 `app/src-dev`）。 */
final class ProbeLab {
    private ProbeLab() { }

    static void runIfArmed(Context ctx) { }
}
