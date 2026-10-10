package io.mdt.launcher;

import android.content.Context;

/**
 * ★ 产品版的【自检桩】—— 真自检在 {@code app/src/io/mdt/launcher/SelfTest.java}（约 1 万行），
 *   **只在 debuggable 构建里进包**。
 *
 * <h3>为什么会有这个文件（2026-10-10）</h3>
 *
 * 自检是**开发工具**，不是产品功能：
 * <ul>
 *   <li>它只被 {@code MainActivity} 的 {@code dev_*} 直通口调用，而那道口**只在
 *       {@code ApplicationInfo.FLAG_DEBUGGABLE} 下才跑**（产品版点下去只会弹「开发直通口
 *       在产品版不可用」）；</li>
 *   <li>可它一直**原样编进发布包** ⇒ 白占一份 dex（约占全部源码的四分之一强），
 *       还把一个「能对测试槽做破坏性验证」的类送到用户机器上，只靠一道运行时白名单挡着。</li>
 * </ul>
 *
 * ⇒ 于是构建按**与运行时那道门同一个谓词**裁剪：{@code DEBUGGABLE=true} 编真自检，否则用本桩。
 *   落地处在 {@code build.sh} 的 {@code [2/4] javac}（收源码那一段），
 *   说明见 {@code docs/DEVELOPING.md} 的「自检源集」一节。
 *
 * <h3>🔴 三条纪律</h3>
 *
 * <ol>
 *   <li>本文件**不许**长出真逻辑 —— 它存在的意义就是「小到可以忽略」；</li>
 *   <li>三个方法的**签名必须与真自检逐字一致**（{@code MainActivity} 直接静态调用它们）。
 *       签名一漂 = **产品版编译不过、而 dev 版照样绿** ⇒ 那种错只能靠「两种构建都跑一遍」发现；</li>
 *   <li>里面**不许**出现中文字符串字面量（i18n 门禁 {@code SRC-02} 的台账按文件记预算；
 *       注释不受影响，字面量会）。</li>
 * </ol>
 */
public final class SelfTest {

    private SelfTest() {}

    /**
     * 产品版没有自检。返回一句可辨认的说明 —— 正常路径下根本走不到这里（dev 口已被运行时门禁挡住），
     * 所以这里**故意不抛异常**：真被调到也只是显示一句话，不会把主进程带走。
     */
    public static String runM3(Context passed) {
        return "selftest not in this build";
    }

    /** {@code runHealthReport} 的桩（同上理由）。 */
    public static String runHealthReport(Context ctx, boolean alsoClean) {
        return "selftest not in this build";
    }

    /** {@code summaryOf} 的桩：真实现会去解析报告里第一行以 {@code =====} 开头的那一行。 */
    public static String summaryOf(String report) {
        return "selftest not in this build";
    }
}
