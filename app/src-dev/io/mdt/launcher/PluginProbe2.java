package io.mdt.launcher;

/**
 * 与 {@link PluginProbe} 同族的第二个同名类夹具（S0 可行性实验）。
 *
 * <p>为什么要两个：`PluginProbe` 会被**先加载**（用来量"已加载的类换不掉"），
 * 而"**还没被加载过**的同名类能不能被插件顶掉"必须用另一个全新名字来量 ⇒ 就是它。
 * 两个合起来才是一组能分辨两种解释的夹具（AGENTS §五「夹具要能分辨两种解释」）。
 */
public final class PluginProbe2 {
    public static String tag() {
        return "SHELL2";
    }
}
