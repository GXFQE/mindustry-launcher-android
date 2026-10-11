package io.mdt.launcher;

/**
 * ⚠️ 这个类**故意与插件夹具里的 `io.mdt.launcher.PluginProbe` 同名**
 * （插件侧在 `tools/plugtest/src/io/mdt/launcher/PluginProbe.java`）。
 *
 * <p>用途只有一个：量出"插件能不能覆盖宿主已有的类"这条边界（S0 可行性实验 · Step E）。
 * 它不是产品功能，等实验结论落进 REF 之后可以删（或者留作自检夹具）。
 */
public final class PluginProbe {
    public static String tag() {
        return "SHELL";
    }
}
