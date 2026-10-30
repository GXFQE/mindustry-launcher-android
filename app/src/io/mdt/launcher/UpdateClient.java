package io.mdt.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 更新用的 HTTP 客户端。
 *
 * <p>🔴 **纯 `java.net`，不许 `import android.*`** —— 与 `UpdateManifest` / `FlatJson` 同一条纪律：
 * 这样它能用普通 JDK 直接编跑（PC 上起一个 `com.sun.net.httpserver.HttpServer` 当夹具），
 * **不必赌手机的外网**。真机那一层只用 `adb reverse` 验"Android 网络栈 + 明文策略"。
 *
 * <p>三件事固定在这里，调用方不用各自操心：
 * <ul>
 *   <li>**超时**：连接 15 s / 读 60 s（比系统默认短得多，用户不该为一次检查转圈）；</li>
 *   <li>**跟随重定向**：`api.github.com` 的 asset 会 302 到 `objects.githubusercontent.com`，
 *       不跟就会拿到一个空 body；</li>
 *   <li>**正文上限**：清单只该几百字节，超了立刻断（防止一个坏路由返回一个 100 MB 的"清单"）。</li>
 * </ul>
 */
final class UpdateClient {

    static final int CONNECT_TIMEOUT_MS = 15000;
    static final int READ_TIMEOUT_MS = 60000;

    /** 下载进度回调（主线程之外的线程上调用）。 */
    interface Progress {
        void onProgress(long done, long total);
    }

    private UpdateClient() {}

    /**
     * 拉一段文本（UTF-8）。
     *
     * @param maxBytes 正文上限；{@code <= 0} 表示用 {@link UpdateManifest#MAX_BYTES}
     * @return 正文（非 null；空正文也是合法返回 —— 由解析层判它坏）
     * @throws IOException 网络失败 / 非 200 / 超限
     */
    static String fetchText(String url, int maxBytes) throws IOException {
        return fetchText(url, maxBytes, "application/json", CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /**
     * 同上，但可指定 `Accept`。
     *
     * <p>★ 拉 release 的 **asset**（`/releases/assets/<id>`）必须传
     * {@code "application/octet-stream"}，否则拿到的是元数据 JSON。
     */
    static String fetchText(String url, int maxBytes, String accept) throws IOException {
        return fetchText(url, maxBytes, accept, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /**
     * 同 {@link #fetchText(String, int, String)}，但**每次尝试**可以给不同的超时。
     *
     * <p>这是给更新线路兜底用的：镜像只值 4~5 秒的等待，直连才值得 15 秒；
     * 一刀切用默认值会让一个死镜像把整次检查拖到 60 秒。
     */
    static String fetchText(String url, int maxBytes, String accept,
                            int connectMs, int readMs) throws IOException {
        int cap = maxBytes > 0 ? maxBytes : UpdateManifest.MAX_BYTES;
        HttpURLConnection c = open(url, accept, connectMs, readMs);
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("http " + code + " @ " + url);
            }
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > cap) throw new IOException("body too big (> " + cap + ")");
                }
                return out.toString("UTF-8");
            } finally {
                closeQuietly(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * 流式下载到 {@code dest}（调用方给的是 {@code .part}）。
     *
     * <p>★ **不在这里改名**：一次下载成功与否只由"写完 + 调用方校验"决定；
     * 半截文件必须让调用方有机会删掉（验 sha256 的那一层）。
     *
     * @param expectSize {@code > 0} 时边下边校验总长度（早失败，省流量）；{@code <= 0} 不校验
     * @throws IOException 网络失败 / 非 200 / 长度不符
     */
    static void download(String url, File dest, long expectSize, Progress cb) throws IOException {
        download(url, dest, expectSize, cb, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /** 同 {@link #download(String, File, long, Progress)}，但可给每次尝试单独的超时。 */
    static void download(String url, File dest, long expectSize, Progress cb,
                         int connectMs, int readMs) throws IOException {
        download(url, dest, expectSize, cb, connectMs, readMs, 0L);
    }

    /**
     * 同上，外加一个**整次尝试**的总预算。
     *
     * <p>为什么光有 readMs 不够：readMs 是"单次 read 最长等多久"，一个半小时才吐
     * 1 KB 的服务端可以每次都卡在 readMs 边缘、总时长无限长。镜像只值 12 秒，
     * 到点就换下一个；直连可以给 120 秒。
     */
    static void download(String url, File dest, long expectSize, Progress cb,
                         int connectMs, int readMs, long maxTotalMs) throws IOException {
        HttpURLConnection c = open(url, "application/octet-stream", connectMs, readMs);
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("http " + code + " @ " + url);
            }
            long total = c.getContentLengthLong();
            if (total <= 0) total = expectSize;          // 服务端没给 Content-Length 时用清单里的
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(dest);
            long done = 0;
            long started = System.currentTimeMillis();
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (maxTotalMs > 0 && System.currentTimeMillis() - started > maxTotalMs) {
                        throw new IOException("too slow (> " + maxTotalMs + " ms) @ " + url);
                    }
                    os.write(buf, 0, n);
                    done += n;
                    if (cb != null) cb.onProgress(done, total);
                    if (expectSize > 0 && done > expectSize) {
                        throw new IOException("body longer than expected (" + done + " > " + expectSize + ")");
                    }
                }
                os.flush();
                // ★ 掉电后 rename 还在、内容却是半截 —— 这类 bug 只在真机出现，所以先 fsync。
                //   需要 FileOutputStream 的 FD（声明类型是 OutputStream，强转是安全的）。
                ((FileOutputStream) os).getFD().sync();
            } finally {
                closeQuietly(os);
                closeQuietly(in);
            }
            if (expectSize > 0 && done != expectSize) {
                throw new IOException("size mismatch (" + done + " != " + expectSize + ")");
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String accept,
                                          int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs > 0 ? connectMs : CONNECT_TIMEOUT_MS);
        c.setReadTimeout(readMs > 0 ? readMs : READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "mdt-launcher-updater");
        // ★ `Accept` **必须按接口给**，不能一刀切（2026-10-29 真机踩的）：
        //   · `/repos/…/releases`（列表）只认 `application/json` —— 发 octet-stream 会得到
        //     **415 Unsupported Media Type**（症状是"连不上服务器"，很容易误判成网络问题）；
        //   · release 的 asset API（`/releases/assets/<id>`）反过来：不带 octet-stream
        //     拿到的是**元数据 JSON**而不是文件本身。
        c.setRequestProperty("Accept", accept);
        return c;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
