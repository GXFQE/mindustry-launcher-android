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

    /**
     * 反过来：把 extra 的 dexElements 排在**目标之前**（shadowing）——
     * 这样同名类**插件赢**（只要那个类还没被加载/解析过）。
     *
     * <p>与 {@link #appendDexElements} 是同一个动作，只差拼接顺序；
     * 两条一起构成"同名类到底谁能顶掉谁"的判据（S0 可行性实验 · Step E）。
     */
    static int prependDexElements(ClassLoader target, ClassLoader extra) throws Exception {
        Object targetList = pathListOf(target);
        Object extraList = pathListOf(extra);
        Field elems = field(targetList.getClass(), "dexElements");

        Object[] a = (Object[]) elems.get(targetList);
        Object[] b = (Object[]) elems.get(extraList);

        Object[] merged = (Object[]) Array.newInstance(
                a.getClass().getComponentType(), a.length + b.length);
        System.arraycopy(b, 0, merged, 0, b.length);
        System.arraycopy(a, 0, merged, b.length, a.length);
        elems.set(targetList, merged);
        return merged.length;
    }

    /**
     * 把一个 dex/APK **只注册一次**地注入目标 CL。
     *
     * <p>为什么不复用 {@link #appendDexElements}：那条路要先建一个 {@code DexClassLoader} 才能拿到
     * elements，而同一个 dex 文件**注册给两个 CL 会让 ART 直接报错**
     * （实测 `InternalError: Attempt to register dex file ... with multiple class loaders`）。
     * 这里改成自己造 elements：`DexPathList.makeDexElements(...)` —— 只登记给目标 CL 一次。
     *
     * <p>⚠️ `makeDexElements` 的签名各版本变过（老版有 `optimizedDirectory`，新版换成 `boolean isTrusted`；
     * 参数个数与顺序都动过）⇒ 这里**按参数类型现配实参**，不写死签名。
     *
     * @param prepend true = 插件排在宿主之前（同名类**插件赢**，前提是那个类还没被加载过）
     * @return 注入后的 dexElements 总数；{@code suppressed} 里带出来的 IOException 由调用方读
     */
    static int injectDex(ClassLoader target, File apk, File optimizedDir, boolean prepend,
                         java.util.List<String> notes) throws Exception {
        return injectDex(target, apk, optimizedDir, prepend ? 0 : -1, notes);
    }

    /**
     * 同上，但**自己指定插到哪个下标**（第 138 轮）。
     *
     * <p>为什么要这个：插件原先一律**前插**（排在宿主自己那份 dex 之前）⇒ 插件能顶掉宿主里
     * **尚未加载**的类 —— 包括 `LogActivity` / 设置页这种"我们自己的逻辑"。全插件化之后宿主内核
     * 必须永远可用、不可被替换 ⇒ 插件要插在**宿主自己那一段之后**（下标 = 宿主 dex 条数）。
     * ★ 插件之间仍是"后注入的排前面"（都在这一段里前插）⇒ 内置→官方外接→第三方的优先级不变。
     *
     * @param at 插入下标；{@code < 0} = 追加到末尾（老语义的 {@code prepend=false}）
     */
    static int injectDex(ClassLoader target, File apk, File optimizedDir, int at,
                         java.util.List<String> notes) throws Exception {
        Object targetList = pathListOf(target);
        Method mk = null;
        for (Method m : Class.forName("dalvik.system.DexPathList").getDeclaredMethods()) {
            if ("makeDexElements".equals(m.getName())) { mk = m; break; }
        }
        if (mk == null) throw new NoSuchMethodException("DexPathList.makeDexElements");
        mk.setAccessible(true);

        java.util.List<File> files = new java.util.ArrayList<File>();
        files.add(apk);
        java.util.List<java.io.IOException> suppressed = new java.util.ArrayList<java.io.IOException>();

        Class<?>[] pt = mk.getParameterTypes();
        Object[] args = new Object[pt.length];
        int listSeen = 0;
        for (int i = 0; i < pt.length; i++) {
            Class<?> c = pt[i];
            if (ClassLoader.class.isAssignableFrom(c)) {
                args[i] = target;
            } else if (File.class.isAssignableFrom(c)) {
                args[i] = optimizedDir;
            } else if (java.util.List.class.isAssignableFrom(c)) {
                args[i] = (listSeen++ == 0) ? files : suppressed;
            } else if (c == boolean.class) {
                args[i] = Boolean.FALSE;
            } else {
                args[i] = null;
            }
        }
        Object[] made = (Object[]) mk.invoke(null, args);
        if (notes != null) {
            notes.add("makeDexElements(" + pt.length + " args) -> " + (made == null ? "null" : made.length)
                    + " elements, suppressed=" + suppressed.size()
                    + (suppressed.isEmpty() ? "" : (" [" + suppressed.get(0) + "]")));
        }

        Field elems = field(targetList.getClass(), "dexElements");
        Object[] a = (Object[]) elems.get(targetList);
        int n = (made == null) ? 0 : made.length;
        Object[] merged = mergeAt(a, made, at, a.getClass().getComponentType());
        elems.set(targetList, merged);
        return merged.length;
    }

    /**
     * 把 {@code made} 插到 {@code a} 的下标 {@code at} 处（**纯函数**，自检直接喂假数组钉它）。
     * {@code at < 0} ⇒ 追加到末尾；{@code at} 超界 ⇒ 夹到两端。
     * ★ 这条数学就是"插件能不能顶掉宿主类"的全部所在，所以它必须能被断言钉住，而不是埋在反射里。
     */
    static Object[] mergeAt(Object[] a, Object[] made, int at, Class<?> componentType) {
        int na = a == null ? 0 : a.length;
        int nm = made == null ? 0 : made.length;
        int idx = (at < 0) ? na : Math.max(0, Math.min(at, na));
        Object[] merged = (Object[]) Array.newInstance(componentType, na + nm);
        if (idx > 0) System.arraycopy(a, 0, merged, 0, idx);
        if (nm > 0) System.arraycopy(made, 0, merged, idx, nm);
        if (na - idx > 0) System.arraycopy(a, idx, merged, idx + nm, na - idx);
        return merged;
    }

    /** 目标 CL 现在有多少个 dexElements（= 注入前读一次 ⇒ 那就是"宿主自己那一段"的长度）。 */
    static int dexElementCount(ClassLoader target) throws Exception {
        Object targetList = pathListOf(target);
        Field elems = field(targetList.getClass(), "dexElements");
        Object[] a = (Object[]) elems.get(targetList);
        return a == null ? 0 : a.length;
    }

    /** AssetManager.addAssetPath —— 返回 cookie；0 表示没认这个路径 */    static int addAssetPath(AssetManager am, File path) throws Exception {
        Method m = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        m.setAccessible(true);
        Object cookie = m.invoke(am, path.getAbsolutePath());
        return cookie instanceof Integer ? (Integer) cookie : -1;
    }
}
