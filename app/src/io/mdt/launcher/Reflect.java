package io.mdt.launcher;

import android.content.res.AssetManager;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 非 SDK 接口访问（模块边界：最底层，与 Util 同级）。
 *
 * 本工程需要的反射只有这几处（探针已在 targetSdk 36 + debuggable=false 下全部实测放行，
 * 见 MDT-Android-Dev README §六.5）：
 *   BaseDexClassLoader.pathList / DexPathList.dexElements   —— dex 注入
 *   AssetManager.addAssetPath                               —— 资产挂载
 *   arc.util.ArcNativesLoader.loaded                        —— 阻止重复加载（是 public static，普通字段访问）
 *
 * ★ 不做 setHiddenApiExemptions 全局豁免：
 *   Android 16 上该方法已不存在；且目标接口本来就在放行名单里。
 *   一旦某个接口被拦，应当**修具体做法**，而不是把整条限制拆掉。
 */
final class Reflect {
    private Reflect() {}

    static Field field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** BaseDexClassLoader.pathList */
    static Object pathListOf(ClassLoader cl) throws Exception {
        Field f = field(Class.forName("dalvik.system.BaseDexClassLoader"), "pathList");
        return f.get(cl);
    }

    static Object[] dexElementsOf(ClassLoader cl) throws Exception {
        Object pl = pathListOf(cl);
        return (Object[]) field(pl.getClass(), "dexElements").get(pl);
    }

    /**
     * 把一个已经建好的 DexClassLoader 的 dexElements 拼到目标 CL 之后。
     *
     * 为什么这么做（而不是自己调 DexPathList.makeDexElements）：那个方法签名各版本会变，
     * 而这里只依赖两个字段名，跨版本稳得多（探针 T3 的原始做法，已验证）。
     */
    static int appendDexElements(ClassLoader target, ClassLoader extra) throws Exception {
        Object targetList = pathListOf(target);
        Object extraList = pathListOf(extra);
        Field elems = field(targetList.getClass(), "dexElements");

        Object[] a = (Object[]) elems.get(targetList);
        Object[] b = (Object[]) elems.get(extraList);

        Object[] merged = (Object[]) Array.newInstance(
                a.getClass().getComponentType(), a.length + b.length);
        System.arraycopy(a, 0, merged, 0, a.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        elems.set(targetList, merged);
        return merged.length;
    }

    /** AssetManager.addAssetPath —— 返回 cookie；0 表示没认这个路径 */
    static int addAssetPath(AssetManager am, File path) throws Exception {
        Method m = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        m.setAccessible(true);
        Object cookie = m.invoke(am, path.getAbsolutePath());
        return cookie instanceof Integer ? (Integer) cookie : -1;
    }
}
