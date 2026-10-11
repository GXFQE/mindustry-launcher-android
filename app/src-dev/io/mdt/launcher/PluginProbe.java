package io.mdt.launcher;

/**
 * ⚠️ 这个类**故意与插件夹具里的 `io.mdt.launcher.PluginProbe` 同名**
 * （插件侧在 `tools/plugtest/src/io/mdt/launcher/PluginProbe.java`）。
 *
 * <p>用途只有一个：量出"插件能不能覆盖宿主已有的类"这条边界（S0 可行性实验）。
 * ★ 它现在住 `app/src-dev` —— 这纯粹是**实验夹具**，产品版不该带（与 `PluginProbeDev` 同一批裁剪）。
 */
public final class PluginProbe {
    public static String tag() {
        return "SHELL";
    }
}
