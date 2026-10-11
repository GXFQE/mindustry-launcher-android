package io.mdt.launcher;

/**
 * ⚠️ 与壳里的 `io.mdt.launcher.PluginProbe2` **同名**（S0 可行性实验 · Step E）。
 *
 * <p>它是"**尚未加载过**的同名类"那一半夹具：`PluginProbe` 会被先加载（量"已加载 ⇒ 换不掉"），
 * 而"插件能不能顶掉一个全新名字的同名类"必须用它来量。
 * {@code __TAG2__} 在构建时被替换成 PLUGIN-v1-2 / PLUGIN-v2-2。
 */
public final class PluginProbe2 {
    public static String tag() {
        return "__TAG2__";
    }
}
