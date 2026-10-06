package io.mdt.launcher;

/**
 * **方块类名 → 会不会按朝向转**（第 120 轮，生成表，**别手改**）。
 *
 * 生成器：`.tmp-prev-lab/GenClassRotate.java`（用法：`java GenClassRotate <游戏 jar> <本文件>`）。
 *
 * <h3>为什么要有它</h3>
 * 数据模组的方块 JSON 用 `type` 指定**游戏 jar 里的类名**（实测：蓝钢拓展的
 * `content/blocks/质驱/钢桶子驱动器.json` 写着 `"type": "MassDriver"`），而 `rotate / rotateDraw`
 * 是那些类**构造函数**里设的 ⇒ 拿到类名就能回答"这个模组方块转不转"。
 * ⚠️ 上一轮我漏了这条，把它说成"模组方块一律拿不到朝向"（错，见 REF §81.7）。
 *
 * <h3>数据</h3>
 * 列（Tab 分隔）：`类名` `转不转(1/0)` —— 取自原版 447 个方块的实际值，
 * 同一个类名只记一次。表里 136 个类，其中会转的 32 个。
 * ⚠️ 原版类名冲突（同一个类有转也有不转的实例）1 个：[GenericCrafter(electrolyzer=true)] —— 按**不转**记（见生成器注释）
 *
 * ⚠️ **边界**：表里只有原版真正用到的类。数据模组只能 `type` 到 jar 里已有的类 ⇒ 覆盖了数据模组的
 * 全部情形；**查不到类名时调用方按"不转"处理**（不猜）。Java 模组（自带 dex）里那些类看不见 ——
 * 那种情况也走"不转"。
 */
final class ClassRotateTable {
    /** zlib+Base64 的 `类名\t转不转\n`（解压走 {@link MapStats#inflate}） */
    static final String DATA_B64 =
            "eNplVdF22yAMfW6+JvmExl677iRtTpx2zxQrNiuGTEBy8veTMBDSPVlXgJCurvCjlKABhbf4sFw8KlxrK7/YxMki9I01fVD+YVU5" +
            "znCl3eTxHtVn8NCgOHqIAYK3rY2x1sJBNyrQfQSeNlzZAjG1qLSmAGy/2h7YHY5HoPAvHqY1qn6YnUr3h4AIPiJ0fj66XDTCnIXL" +
            "yTajQCHphrczoBZXit1odTzyktWc9pOes0rwt5ijlOrIch6D9CVkdsRSGYUJnsEUrsjllQk2uI36G1TOc1Ut1K5MWkPX5ztahSC9" +
            "smaOkOpe3fxCvxttRQ98MvGaGWgpN/ZyzuUggb0NPu7/MZ38NdddvhYl7ND+ATmX8UTEfcXexeKUvDXzJwgfGUpbI75bpUC0WiBg" +
            "TdDLdKKe7EGk4y9Gqmq1bjSD/5c7GyjZBAqXDD6sYlX9CibSROZGmF6ZYSf6CBxgObARtG1MKzAIeW3sNNH+Bsxcyuzdwv2ud6P8" +
            "U0yedbtRw1jEcdevDOtsoiN1IsNSzje9zDCVtLGDkuUaBq1yJ5Y0QyrQXgDTzGyFc6SGc7xjCzSe13xyC6avu7wF58RQhPcapAaB" +
            "t9683UTJI9Rz1Po8O4/aXpLoMnymnqdVSjFrbCeuLNpK9MnTgqymqmzcZIUnXNVVfEXVCScyC67mJHkSoTurjG/hCMZBeUoii61i" +
            "GleLmtJol0ZFVPoUUY6K9sSfMPFnL3rB9ezv6lsRHsDUNO6pSWcgsoudHqI9nITCA99wQznboqN9MJBfuOWiA7EObpytC0AfrZPI" +
            "I9SNFNteZnlFzI9xurAbqdtbGrhsX8QZ8v2d1eJulMmh+lRsZ3HOpjuJi8mq6Tw9I1XHIy5NI+SVTKTN4IAABeSkPP0QPmgqo22x" +
            "Umx3UV6OGR1o/83GAXwbpolJOYyAk9B18ocRg5vzOCiS/aeGb4N1wDgH/DsqpHN++Qp+CR6dg4mO4ne8pSdQQ/I2lIxNeq48szqj" +
            "FJM7PyyrRVHucvEhUHF6t7lMxPAnv7yrxT98vJYk"
            ;
    /** 表里有多少个类（自检拿它对账） */
    static final int COUNT = 136;

    private ClassRotateTable() {}
}
