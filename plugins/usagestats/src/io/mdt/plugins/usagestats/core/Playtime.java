package io.mdt.plugins.usagestats.core;

import java.util.List;

/**
 * 时长解析 / 求和（**纯函数**，PC 侧 `build.sh check` 直接测；不碰 Android）。
 *
 * <p>数据格式由本插件自己定：探针每 20 秒写一份 `playtime.txt`：
 * <pre>total=&lt;ms&gt;
 * slot=&lt;槽名&gt;
 * updated=&lt;ms&gt;</pre>
 */
public final class Playtime {

    private Playtime() {}

    /** 取 `key=value` 里的 value（没有 / 解析不了 ⇒ -1）。 */
    public static long value(String text, String key) {
        if (text == null || key == null) return -1;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            if (!line.substring(0, eq).trim().equals(key)) continue;
            try {
                return Long.parseLong(line.substring(eq + 1).trim());
            } catch (Throwable t) {
                return -1;
            }
        }
        return -1;
    }

    /** 逐份求和（`total` 缺失 / 负数 / 坏行都跳过）。 */
    public static long sumTotals(List<String> texts) {
        long sum = 0;
        if (texts == null) return 0;
        for (String t : texts) {
            long v = value(t, "total");
            if (v > 0) sum += v;
        }
        return sum;
    }

    /** ms ⇒ `{小时, 分}`（负值按 0）。 */
    public static int[] hm(long ms) {
        long sec = Math.max(0, ms) / 1000L;
        return new int[] { (int) (sec / 3600L), (int) ((sec % 3600L) / 60L) };
    }
}
