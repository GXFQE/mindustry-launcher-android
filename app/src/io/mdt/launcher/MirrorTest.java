package io.mdt.launcher;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 更新线路的**手动测速**：四个预设 + 直连，各真下一小段同一个 APK，按速度排序。
 *
 * <p>🔴 **纯 `java.net`，不 import android** —— 和 {@link UpdateClient} 同一条纪律：
 * 判据能在 PC 上直接编跑；真机那一层只验 TLS / 明文策略。
 *
 * <p>为什么不是 ping / HEAD：镜像快不快取决于它到 GitHub 的回源和 CDN 缓存，
 * 跟 RTT 基本没关系（桌面版 `mirrortest.py` 的实测结论）。这里真发 GET、
 * 读 16 KB，读多少算多少。
 *
 * <p>★ 采样是**交错**的：第 1 轮把所有候选各测一次，再回来测第 2 轮 ——
 * 不是「把 A 测完两次再测 B」。顺序测量在抖动链路上量到的是「当时网络好不好」，
 * 不是「谁更快」。最后每个候选取中位。
 *
 * <p>★ 判据只有一条：**前两个字节是 `PK`**（APK 的 zip 头）。镜像返回 HTML
 * 错误页 / 健康页时状态码可能也是 200，光看状态码会把坏节点当成可用。
 */
final class MirrorTest {

    /** 单次采样最多读 16 KB —— 启动器更新包本身才 773 KB，测速不该比包还贵。 */
    static final int SAMPLE_BYTES = 16 * 1024;
    /** 镜像的单次读窗口；直连单独放宽（GitHub 首字节经常要 2~4 秒）。 */
    static final int READ_MS = 2000;
    static final int DIRECT_READ_MS = 5000;
    static final int CONNECT_MS = 3000;
    /** 交错轮数：2 轮取中位，抗单次尖峰。 */
    static final int ROUNDS = 2;
    /** 整轮测速的总预算；超了就用手上已有的样本出结果。 */
    static final long BUDGET_MS = 25000L;
    /** 低于这个字节数不算“下到了 APK”（多半是错误页/被掐断）。 */
    static final int MIN_OK_BYTES = 8 * 1024;

    private static final String USER_AGENT = "mdt-launcher-updater";

    enum Status {
        OK,
        TIMEOUT,
        HTTP,
        SHORT,
        NET,
        CANCELED
    }

    /** 一个候选的聚合结果（多轮取中位）。 */
    static final class Result {
        final String prefix;
        final Status status;
        final long ms;
        final int bytes;
        final long speedBps;
        final int httpCode;
        final String detail;

        Result(String prefix, Status status, long ms, int bytes, long speedBps,
               int httpCode, String detail) {
            this.prefix = prefix;
            this.status = status;
            this.ms = ms;
            this.bytes = bytes;
            this.speedBps = speedBps;
            this.httpCode = httpCode;
            this.detail = detail;
        }

        boolean ok() {
            return status == Status.OK;
        }
    }

    interface Progress {
        void onProgress(int done, int total, String prefix);
    }

    interface Stop {
        boolean isStopped();
    }

    private MirrorTest() {}

    /**
     * 跑一轮交错的测速。**不抛异常** —— 任何候选出问题都折成一条 {@link Result}。
     *
     * @param testUrl  必须是一个真实 APK 的 `browser_download_url`
     * @param prefixes 前缀列表；空串 = 直连
     */
    static List<Result> run(String testUrl, List<String> prefixes, Progress progress, Stop stop) {
        List<String> targets = new ArrayList<String>(prefixes);
        int n = targets.size();
        int total = n * ROUNDS;
        int done = 0;
        long startedAt = System.currentTimeMillis();

        List<List<Result>> buckets = new ArrayList<List<Result>>();
        for (int i = 0; i < n; i++) buckets.add(new ArrayList<Result>());
        boolean[] alive = new boolean[n];
        long[] spent = new long[n];
        for (int i = 0; i < n; i++) alive[i] = true;

        for (int round = 0; round < ROUNDS; round++) {
            if (stopped(stop)) break;
            for (int i = 0; i < n; i++) {
                if (stopped(stop)) break;
                if (!alive[i]) {
                    done++;
                    continue;
                }
                if (System.currentTimeMillis() - startedAt > BUDGET_MS
                        || spent[i] > BUDGET_MS / 2) {
                    alive[i] = false;
                    done++;
                    continue;
                }
                long t0 = System.currentTimeMillis();
                Result r = probe(targets.get(i), testUrl);
                spent[i] += System.currentTimeMillis() - t0;
                buckets.get(i).add(r);
                done++;
                if (progress != null) {
                    try {
                        progress.onProgress(done, total, targets.get(i));
                    } catch (Throwable ignored) {
                    }
                }
                // 明确死掉的节点（404 / 连不上 / 错误页）不必再测下一轮。
                if (r.status == Status.HTTP || r.status == Status.NET
                        || r.status == Status.SHORT) {
                    alive[i] = false;
                }
                if (r.status == Status.CANCELED) break;
            }
        }

        List<Result> out = new ArrayList<Result>();
        for (int i = 0; i < n; i++) {
            if (buckets.get(i).isEmpty()) continue;
            out.add(aggregate(targets.get(i), buckets.get(i)));
        }
        return out;
    }

    /**
     * 测一个候选一次。**不抛异常**。
     *
     * <p>用 Range 要 16 KB：镜像支持 206 最好；不支持、回 200 整包也只读 16 KB 就断开。
     * 直连的读窗口放宽到 5 秒（GitHub 首字节慢），否则会把能用的直连误判成超时。
     */
    static Result probe(String prefix, String testUrl) {
        String url = (prefix == null || prefix.isEmpty()) ? testUrl : prefix + testUrl;
        boolean direct = prefix == null || prefix.isEmpty();
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        InputStream in = null;
        int total = 0;
        int http = 0;
        byte[] head = new byte[2];
        int headLen = 0;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(direct ? DIRECT_READ_MS : READ_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Range", "bytes=0-" + (SAMPLE_BYTES - 1));
            http = c.getResponseCode();
            if (http != 200 && http != 206) {
                return new Result(prefix, Status.HTTP, System.currentTimeMillis() - t0,
                        0, 0, http, "HTTP " + http);
            }
            in = c.getInputStream();
            byte[] buf = new byte[4 * 1024];
            while (total < SAMPLE_BYTES) {
                int k = in.read(buf, 0, Math.min(buf.length, SAMPLE_BYTES - total));
                if (k <= 0) break;
                if (headLen < 2) {
                    for (int i = 0; i < k && headLen < 2; i++) head[headLen++] = buf[i];
                }
                total += k;
            }
            long elapsed = Math.max(1L, System.currentTimeMillis() - t0);
            if (headLen < 2 || head[0] != 'P' || head[1] != 'K') {
                return new Result(prefix, Status.SHORT, elapsed, total, 0, http, "not an apk");
            }
            if (total < MIN_OK_BYTES) {
                return new Result(prefix, Status.SHORT, elapsed, total,
                        speed(total, elapsed), http, "too few bytes");
            }
            // 读满 16 KB，或者窗口内读到了够量 —— 都算速率可用。
            return new Result(prefix, Status.OK, elapsed, total,
                    speed(total, elapsed), http, null);
        } catch (SocketTimeoutException e) {
            long elapsed = Math.max(1L, System.currentTimeMillis() - t0);
            // 窗口到了但已经读到够量：算可用（只是慢）。桌面版 mirrortest 同一条判据。
            if (headLen >= 2 && head[0] == 'P' && head[1] == 'K' && total >= MIN_OK_BYTES) {
                return new Result(prefix, Status.OK, elapsed, total,
                        speed(total, elapsed), http, "slow");
            }
            return new Result(prefix, Status.TIMEOUT, elapsed, total,
                    total > 0 ? speed(total, elapsed) : 0, http, "timeout");
        } catch (Throwable t) {
            long elapsed = Math.max(1L, System.currentTimeMillis() - t0);
            return new Result(prefix, Status.NET, elapsed, total,
                    total > 0 ? speed(total, elapsed) : 0, http, String.valueOf(t));
        } finally {
            close(in);
            if (c != null) c.disconnect();
        }
    }

    /** 多轮聚合：中位速度 / 中位耗时；没有可用样本时返回最后一次采样。 */
    private static Result aggregate(String prefix, List<Result> samples) {
        List<Result> usable = new ArrayList<Result>();
        for (Result r : samples) {
            if (r.ok()) usable.add(r);
        }
        if (usable.isEmpty()) return samples.get(samples.size() - 1);
        List<Long> speeds = new ArrayList<Long>();
        List<Long> times = new ArrayList<Long>();
        int bytes = 0;
        for (Result r : usable) {
            speeds.add(Long.valueOf(r.speedBps));
            times.add(Long.valueOf(r.ms));
            bytes += r.bytes;
        }
        Collections.sort(speeds);
        Collections.sort(times);
        long medianSpeed = speeds.get((speeds.size() - 1) / 2).longValue();
        long medianMs = times.get((times.size() - 1) / 2).longValue();
        return new Result(prefix, Status.OK, medianMs, bytes, medianSpeed, 0, null);
    }

    /** 排序：能用的在前、按耗时升序；失败按 超时 → 短包 → HTTP → 网络。 */
    static List<Result> sorted(List<Result> results) {
        List<Result> out = new ArrayList<Result>(results);
        Collections.sort(out, new java.util.Comparator<Result>() {
            @Override public int compare(Result a, Result b) {
                int ra = rank(a.status);
                int rb = rank(b.status);
                if (ra != rb) return ra < rb ? -1 : 1;
                if (a.ok() && b.ok()) {
                    // 界面只报“读这一段用了多久”，排序就必须按耗时 —— 按 speedBps
                    // 排会出现“1.1 s 排在 0.7 s 前面”（两者读到的字节数不同），
                    // 用户看着就是乱序。
                    if (a.ms != b.ms) return a.ms < b.ms ? -1 : 1;
                }
                return a.prefix == null ? (b.prefix == null ? 0 : 1)
                        : (b.prefix == null ? -1 : a.prefix.compareTo(b.prefix));
            }
        });
        return out;
    }

    private static int rank(Status s) {
        switch (s) {
            case OK: return 0;
            case TIMEOUT: return 1;
            case SHORT: return 2;
            case HTTP: return 3;
            case NET: return 4;
            default: return 5;
        }
    }

    private static long speed(int bytes, long ms) {
        return bytes <= 0 || ms <= 0 ? 0 : (bytes * 1000L) / ms;
    }

    private static boolean stopped(Stop stop) {
        return stop != null && stop.isStopped();
    }

    private static void close(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }
}
