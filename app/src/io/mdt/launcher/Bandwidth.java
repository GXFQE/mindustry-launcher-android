package io.mdt.launcher;

/**
 * **全局下载限速器**（第 378 轮，2026-11-14）。
 *
 * <p>为什么是全局而不是"每个任务各限各的"：用户设的是"下载总共别超过 N KB/s"，
 * 三个任务各限 512 KB/s 等于 1.5 MB/s，设置就形同虚设。所以这里只有一个额度，
 * 谁在下载谁就从这里领时间片。
 *
 * <p>算法 = **虚拟时钟**（token bucket 的等价写法，但没有容量 / 突发参数）：
 * <ol>
 *   <li>每次写盘 n 字节，就按 {@code n / bps} 秒给自己排一个"下次可以开始"的时刻；</li>
 *   <li>排完看当前时刻离那个时刻还有多久，睡掉；</li>
 *   <li>网络比限速慢时（实际时刻已经超过排期），排期直接重置到"现在"，不会倒欠。</li>
 * </ol>
 * 于是多线程共享同一个时钟 ⇒ 总速率不超过设定值，且各任务按到达顺序大致公平。
 *
 * <p>⚠️ 纯 `java.lang`：不 import `android.*`，PC 夹具能直接编跑（{@code BandwidthCheck}）。
 * 取消靠 {@link Thread#interrupt()}：睡眠切成 ≤100 ms 片，所以取消延迟上限约 100 ms。
 */
final class Bandwidth {

    private static final Object LOCK = new Object();

    /** 上限（字节/秒）；0 = 不限。由设置项 {@code download_limit} 写。 */
    private static volatile long sBps;

    /** 下一次可以开始传输的时刻（{@link System#nanoTime()}）；受 {@link #LOCK} 保护。 */
    private static long sNextNs;

    private Bandwidth() {}

    /** 设置上限（0 = 不限）；改了之后从"现在"重新排期。 */
    static void set(long bytesPerSec) {
        synchronized (LOCK) {
            sBps = Math.max(0L, bytesPerSec);
            sNextNs = 0L;
        }
    }

    /** 当前上限（字节/秒；0 = 不限）。 */
    static long bps() {
        return sBps;
    }

    /**
     * 限速时每次读多大：约 50 ms 的量（下限 4 KB、上限 64 KB）。
     *
     * <p>为什么跟着限速变：限速很低时还用 64 KB 一片，一片就要睡好几秒（进度条一跳一跳、
     * 取消也不及时）；限速高时小片又白费 syscall。不限制时用 64 KB。
     */
    static int chunkSize() {
        long bps = sBps;
        if (bps <= 0L) return 65536;
        long per = bps / 20L;
        if (per < 4096L) per = 4096L;
        if (per > 65536L) per = 65536L;
        return (int) per;
    }

    /**
     * 记下刚写了 {@code bytes} 字节，并睡到"允许下一个字节"的时刻。
     *
     * @throws InterruptedException 被 {@link Thread#interrupt()} 打断（取消）
     */
    static void after(int bytes) throws InterruptedException {
        long bps = sBps;
        if (bps <= 0L || bytes <= 0) return;
        long sleepNs;
        synchronized (LOCK) {
            long now = System.nanoTime();
            if (sNextNs < now) sNextNs = now;      // 网络比限速慢：不积累欠账
            sleepNs = sNextNs - now;
            long cost = (long) bytes * 1000000000L / bps;
            sNextNs += Math.max(1L, cost);
        }
        if (sleepNs > 0L) sleepSlices(sleepNs);
    }

    /** 睡 {@code ns}，每片 ≤100 ms（取消及时）。 */
    private static void sleepSlices(long ns) throws InterruptedException {
        long ms = (ns + 999999L) / 1000000L;
        while (ms > 0L) {
            long slice = ms > 100L ? 100L : ms;
            Thread.sleep(slice);
            ms -= slice;
        }
    }
}
