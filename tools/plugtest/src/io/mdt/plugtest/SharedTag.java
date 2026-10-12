package io.mdt.plugtest;

/**
 * 插件之间的"谁赢"尺子（第 138 轮）。
 *
 * <p>★ 为什么需要它：修完 B（插件一律排宿主之后）之后，原来的尺子 `io.mdt.launcher.PluginProbe`
 * **不管用了** —— 它同时存在于宿主里，插件再也盖不掉它。这个类**宿主里没有**，但每个夹具变体都有、
 * 且 tag 各不相同 ⇒ 宿主 CL 里 `loadClass` 到的那个 tag 就是"插件之间谁排在前面"。
 */
public final class SharedTag {
    public static String tag() { return "__TAG__"; }
}