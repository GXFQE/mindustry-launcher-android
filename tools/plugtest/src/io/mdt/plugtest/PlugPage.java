package io.mdt.plugtest;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

/**
 * 插件入口（S0 可行性实验）。
 *
 * <p>★ 壳**只通过反射按名字**调用这里的东西（壳的源码里不出现这个类的编译期引用）——
 * 这正是插件化的契约形态：名字 + 签名 = 接口，实现由被加载的 APK 提供。
 *
 * <p>构建时 {@code __API__} 会被 {@code tools/plugtest/build.sh} 替换掉：
 * 用它来造"接口版本不匹配"的坏插件（壳必须拒绝加载）。
 */
public final class PlugPage {

    /** 插件接口版本。壳按它决定认不认这个插件。 */
    public static int api() {
        return __API__;
    }

    /** 用**插件自己的**资源取字符串 ⇒ 传入的 ctx 必须是"带插件资源的 ctx"。 */
    public static String hello(Context ctx) {
        return ctx.getString(R.string.plug_hello);
    }

    /** 用插件自己的资源造一个 View（颜色也来自插件资源，避免"看起来像壳画的"）。 */
    public static View createView(Context ctx) {
        TextView t = new TextView(ctx);
        t.setText(ctx.getString(R.string.plug_hello));
        t.setTextColor(ctx.getResources().getColor(R.color.plug_accent));
        t.setTextSize(20f);
        t.setPadding(32, 32, 32, 32);
        return t;
    }
}
