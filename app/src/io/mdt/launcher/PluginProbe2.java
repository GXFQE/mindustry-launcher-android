package io.mdt.launcher;

/**
 * ⚠️ 与插件夹具里的 `io.mdt.launcher.PluginProbe2` **同名**（S0 可行性实验 · Step E）。
 *
 * <p>它是"**还没被加载过**的同名类"那一半夹具：`PluginProbe` 会先被加载（量"已加载 ⇒ 换不掉"），
 * 而"插件能不能顶掉一个尚未加载的同名类"必须用一个全新名字来量 ⇒ 就是它。
 */
public final class PluginProbe2 {
    public static String tag() {
        return "SHELL2";
    }
}
