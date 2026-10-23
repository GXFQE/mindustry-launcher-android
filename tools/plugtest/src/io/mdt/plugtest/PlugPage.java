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

    /**
     * 这一版夹具的**编译期标记**（`build.sh` 把 `__TAGPAGE__` 换掉，与同名宿主类那份 tag 同源）。
     *
     * <p>★ 加它的理由（2026-10-12 第 134 轮，多插件覆盖顺序实验）：装了 ≥2 个插件时，
     * 入口类是**同一个 FQN**，宿主 CL 只会认其中一个 ⇒ 点 A 那行可能渲染出 B 的类。
     * 把 tag 打进出**页面文字**，这件事就从"读代码推出来"变成"屏幕上看得见"。
     */
    public static String tag() {
        return "__TAGPAGE__";
    }

    /** 用插件自己的资源造一个 View（颜色也来自插件资源，避免"看起来像壳画的"）。 */
    public static View createView(io.mdt.launcher.api.PluginApi api) {
        android.content.Context ctx = api.context();
        // ★ 第 148 轮：写一次"插件自己的数据目录" —— 壳把 getFilesDir() 指到了 `<插件>/data`，
        //   写出来的文件必须落在那里（宿主与插件的**约定**，不是沙箱）。
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "plug-test.txt");
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(("tag=" + tag()).getBytes("UTF-8"));
            o.close();
        } catch (Throwable ignored) { }
        TextView t = new TextView(ctx);
        t.setText(ctx.getString(R.string.plug_hello) + "  [" + tag() + "]");
        t.setTextColor(ctx.getResources().getColor(R.color.plug_accent));
        t.setTextSize(20f);
        t.setPadding(32, 32, 32, 32);
        return t;
    }
}
