package io.mdt.plugtest;

import android.content.Context;
import android.view.View;

/**
 * 「**带这个插件启动会死**」的夹具模板（第 132 轮）。
 *
 * <p>为什么要它：回滚（"保留上一份可用插件"）只有在**真实的失败形态**下验过才算数 ——
 * 而真实形态就是"注入进宿主 CL 之后、界面还没起来就死了"。用 adb 侧手写一个 guard 只能证明
 * "看到 guard 会跳过"，证不了"这条路真的会留 guard"。
 *
 * <p>杀点选在 {@code api()}：壳的启动自查（`injectEarly` → `host.loadClass(ENTRY)` →
 * `api()`）正好会调到它，而那时 dex 已经注册进宿主 CL、guard 也已经落盘 ⇒ 下一次启动必然是被
 * 安全阀拦下的那一次（正是回滚的入口）。
 *
 * <p>⚠️ 构建脚本会把这个模板**拷成 `PlugPage.java`**（类名必须叫 `PlugPage`：
 * 壳按 {@code io.mdt.plugtest.PlugPage} 找入口，而 public 类的文件名必须与类名一致）。
 */
public final class PlugPage {

    /** 壳按它决定认不认这个插件 —— 但这一版**先说再见**：进程直接死，界面永远起不来。 */
    public static int api() {
        android.os.Process.killProcess(android.os.Process.myPid());
        return __API__;
    }

    public static String hello(Context ctx) {
        return "never";
    }

    public static View createView(io.mdt.launcher.api.PluginApi api) {
        return null;
    }
}
