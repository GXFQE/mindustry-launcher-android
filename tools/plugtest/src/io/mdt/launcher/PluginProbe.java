package io.mdt.launcher;

/**
 * ⚠️ 这个类的**全限定名与壳里的同名**（`app/src/io/mdt/launcher/PluginProbe.java`）。
 *
 * <p>它是故意造的<b>同名类夹具</b>：用来量"插件能不能覆盖宿主已有的类"这条边界
 * （dexElements 追加在后 ⇒ 宿主赢；插到最前 ⇒ 插件赢；但**已经加载过的类谁也换不掉**）。
 *
 * <p>{@code __TAG__} 在构建时被替换成 PLUGIN-v1 / PLUGIN-v2，
 * 于是"拿到哪个字符串"就是"哪一份类生效"的判据。
 */
public final class PluginProbe {
    public static String tag() {
        return "__TAG__";
    }
}
