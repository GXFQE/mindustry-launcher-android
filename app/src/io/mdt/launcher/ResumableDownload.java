package io.mdt.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.Properties;

/**
 * **断点续传 + 限速 的下载引擎**（第 378 轮，2026-11-14；PC 上 53 条夹具验过）。
 *
 * <p>与 {@link UpdateClient} 同一条纪律：**纯 `java.io` / `java.net`，不 import `android.*`** ——
 * 所以整条链能在 PC 上起真 HTTP 服务当夹具跑（`.dsh/research/2026-11-14-mod-download/`）。
 *
 * <p>它比 {@link UpdateClient#download} 多四件事：
 * <ol>
 *   <li><b>续传</b>：落点文件长度就是偏移；旁边一份 {@code .part.meta}（etag / total / size / sha）；</li>
 *   <li><b>限速</b>：写盘后交给 {@link Bandwidth}（**全局限速**，不是每个任务各限各的）；</li>
 *   <li><b>取消 ≠ 失败</b>：{@code resume=true} 时取消 / 网络断都保留 {@code .part}；</li>
 *   <li><b>不信任 206</b>：206 只说明"服务端认了 Range"，**不说明给的是同一份文件** ——
 *       必须用 {@code Content-Range} 的总长对清单长度，最后再用 sha256 兜底。</li>
 * </ol>
 *
 * <p>★ {@code resume} 由调用方决定（用户 2026-11-14 定案）：{@code false} = 老行为
 * （每次从零、失败即删）；{@code true} = 同 key 接半截、成品命中、取消保留。
 * {@code resume=true} 必须给 sha256，否则续出来的文件无法证伪。
 *
 * <p>⚠️ 本类的 {@code NETWORK} / {@code CANCELED} **不删** {@code .part}（resume=true 时）；
 * 只有 {@code SIZE} / {@code SHA} 这种"内容不对"的失败才清盘。
 */
final class ResumableDownload {

    static final int CONNECT_MS = 15000;
    static final int READ_MS = 60000;

    enum Status {
        /** 完整、校验通过、已改名成成品 */
        OK,
        /** 用户取消（resume=true 时 `.part` 保留） */
        CANCELED,
        /** 网络断了 / 服务端提前关连接（resume=true 时 `.part` 保留） */
        NETWORK,
        /** 这个源给的对象与清单不符（总长 / 起点不对）：换下一个源 */
        WRONG,
        /** 长度与清单不符（且已经读到底）：清盘 */
        SIZE,
        /** sha256 不符（续传混进了另一份内容，且重下一次仍不符）：清盘 */
        SHA,
        /** 本地 IO 失败（建目录 / 写盘 / 改名） */
        IO,
        /** 参数不合法（`resume=true` 却没给 sha256 / key 为空） */
        INVALID
    }

    /** 一个候选源 + 它的超时（镜像短、直连长，与更新线路同一套口径）。 */
    static final class Source {
        final String url;
        final int connectMs;
        final int readMs;
        final long maxTotalMs;

        Source(String url) {
            this(url, CONNECT_MS, READ_MS, 0L);
        }

        Source(String url, int connectMs, int readMs, long maxTotalMs) {
            this.url = url;
            this.connectMs = connectMs > 0 ? connectMs : CONNECT_MS;
            this.readMs = readMs > 0 ? readMs : READ_MS;
            this.maxTotalMs = maxTotalMs;
        }
    }

    static final class Result {
        final Status status;
        /** 原始诊断（异常原文 / 算出来的 sha）；成功时为 null。 */
        final String detail;
        /** 成功时 = 校验过的成品文件；失败时 null。 */
        final File file;
        /** 这一次调用真正从网络收到多少字节 */
        final long bytes;
        /** 这次是从第几个字节接着下的（0 = 从头） */
        final long resumedFrom;
        /** 已知总长（0 = 不知道） */
        final long total;
        /** 实际算出来的 sha256（OK / SHA 时非 null） */
        final String sha256;

        Result(Status status, String detail, File file, long bytes, long resumedFrom,
               long total, String sha256) {
            this.status = status;
            this.detail = detail;
            this.file = file;
            this.bytes = bytes;
            this.resumedFrom = resumedFrom;
            this.total = total;
            this.sha256 = sha256;
        }
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    interface Cancel {
        boolean canceled();
    }

    private ResumableDownload() {}

    /** 一次尝试（内部用）。 */
    private static final class Try {
        int code;
        boolean wrong;
        boolean canceled;
        boolean complete;
        long bytes;
        long total = -1L;
        long start;
        String url;
        String etag;
        String err;
    }

    /** 取消用异常把栈弹回去（不发栈）。 */
    private static final class Canceled extends Exception {
        final long bytes;

        Canceled(long bytes) {
            super("canceled", null, false, false);
            this.bytes = bytes;
        }
    }

    /**
     * 下载到 {@code <dir>/<key><suffix>}。
     *
     * @param sources     候选（镜像优先、直连最后）；同一个 `.part` 会在候选之间续
     * @param key         落点前缀（调用方保证唯一；不要把 URL 里的文件名直接当 key）
     * @param suffix      成品后缀（如 `.apk`；空串 = key 本身）
     * @param resume      false = 老行为（从零、失败删半截）；true = 续传（取消 / 断网保留）
     * @param expectSize  清单给的长度；{@code <= 0} 不知道
     * @param expectSha   清单给的 sha256；resume=true 时必填
     */
    static Result run(List<Source> sources, File dir, String key, String suffix, boolean resume,
                      long expectSize, String expectSha, Progress cb, Cancel cancel) {
        if (sources == null || sources.isEmpty()) return r(Status.IO, "no source", null, 0, 0, 0, null);
        if (key == null || key.isEmpty()) return r(Status.INVALID, "blank key", null, 0, 0, 0, null);
        if (resume && (expectSha == null || expectSha.isEmpty())) {
            return r(Status.INVALID, "resume needs sha256", null, 0, 0, 0, null);   // 没有内容锚，不许续
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return r(Status.IO, "mkdirs failed: " + dir, null, 0, 0, 0, null);
        }

        String sfx = suffix == null ? "" : suffix;
        File part = new File(dir, key + ".part");
        File metaFile = new File(dir, key + ".part.meta");
        File out = new File(dir, key + sfx);

        if (!resume) {
            delete(part);
            delete(metaFile);
            out.delete();
        }

        Meta meta = Meta.read(metaFile);
        long len = part.isFile() ? part.length() : 0L;
        if (resume) {
            if (len > 0 && !trusted(meta, expectSize, expectSha)) {
                delete(part);
                delete(metaFile);
                meta = null;
                len = 0;
            }
            if (len > 0 && expectSize > 0 && len > expectSize) {
                delete(part);
                delete(metaFile);
                meta = null;
                len = 0;
            }
            // 成品已在且内容对得上：一个请求都不发（装第二次不用重下）
            if (out.isFile() && (expectSize <= 0 || out.length() == expectSize)) {
                String got = sha256Quiet(out);
                if (got != null && got.equalsIgnoreCase(expectSha)) {
                    delete(metaFile);
                    return r(Status.OK, null, out, 0, out.length(), out.length(), got);
                }
            }
            // 上一轮已经把字节下满、只差校验 / 改名（进程死在 sha 之前）
            if (len > 0 && expectSize > 0 && len == expectSize) {
                String got = sha256Quiet(part);
                if (got != null && got.equalsIgnoreCase(expectSha)) {
                    if (part.renameTo(out)) {
                        delete(metaFile);
                        return r(Status.OK, null, out, 0, len, len, got);
                    }
                }
                delete(part);
                delete(metaFile);
                meta = null;
                len = 0;
            }
        }

        long offset = len;
        long resumedFrom = len;
        long transferred = 0;
        long lastTotal = 0;
        int shaRetry = 0;
        int sourcesUsed = 0;
        String lastErr = null;
        boolean sawWrong = false;

        for (int round = 0; round < 4; round++) {
            boolean progressed = false;
            for (Source s : sources) {
                if (s == null || s.url == null || s.url.isEmpty()) continue;
                if (cancel != null && cancel.canceled()) {
                    if (resume) remember(metaFile, meta, null, expectSize, expectSha, part);
                    else delete(part);
                    return r(Status.CANCELED, "canceled", null, transferred, resumedFrom, lastTotal, null);
                }
                if (!resume) {
                    delete(part);
                    delete(metaFile);
                    offset = 0;
                }
                if (resume && offset > 0 && expectSize > 0 && offset > expectSize) {
                    delete(part);
                    delete(metaFile);
                    offset = 0;
                }

                Try t = trySource(s, part, offset, expectSize, cb, cancel, meta);
                transferred += t.bytes;
                if (t.total > 0) lastTotal = t.total;

                if (t.canceled) {
                    if (resume) remember(metaFile, meta, t, expectSize, expectSha, part);
                    else delete(part);
                    return r(Status.CANCELED, "canceled", null, transferred, resumedFrom, lastTotal, null);
                }
                if (t.code == 200) {
                    offset = part.length();
                    progressed = true;
                    if (t.bytes > 0) sourcesUsed++;
                } else if (t.code == 206 && !t.wrong) {
                    offset = part.length();
                    if (t.bytes > 0) {
                        progressed = true;
                        sourcesUsed++;
                    }
                } else if (t.code == 416) {
                    long total = t.total;
                    if (total > 0 && part.length() == total
                            && (expectSize <= 0 || total == expectSize)) {
                        offset = total;                 // 其实已经下满，去校验
                    } else {
                        delete(part);
                        delete(metaFile);
                        offset = 0;
                        progressed = true;
                        continue;
                    }
                } else if (t.wrong) {
                    lastErr = t.err;
                    sawWrong = true;
                    if (!resume) {
                        delete(part);
                        delete(metaFile);
                        offset = 0;
                    }
                    continue;
                } else {
                    lastErr = t.err;
                    offset = part.length();
                    if (t.bytes > 0) progressed = true;
                    if (!resume) {
                        delete(part);
                        delete(metaFile);
                        offset = 0;
                    }
                    continue;
                }

                // 服务端给了比清单还多的字节：这份不是我们要的（copy 里提前断，这里兜底）
                if (expectSize > 0 && part.length() > expectSize) {
                    delete(part);
                    delete(metaFile);
                    return r(Status.SIZE, "longer than expected (" + part.length()
                            + " > " + expectSize + ")", null, transferred, resumedFrom, lastTotal, null);
                }
                if (resume) remember(metaFile, meta, t, expectSize, expectSha, part);

                if (!needsNoMore(part, t, expectSize)) continue;

                if (expectSize > 0 && part.length() != expectSize) {
                    delete(part);
                    delete(metaFile);
                    return r(Status.SIZE, "size " + part.length() + " != " + expectSize,
                            null, transferred, resumedFrom, lastTotal, null);
                }
                String got = sha256Quiet(part);
                if (got == null) {
                    delete(part);
                    delete(metaFile);
                    return r(Status.IO, "sha read failed", null, transferred, resumedFrom, lastTotal, null);
                }
                boolean shaOk = expectSha == null || expectSha.isEmpty()
                        || got.equalsIgnoreCase(expectSha);
                if (!shaOk) {
                    // 续传最坏的情况：前一半旧内容 + 后一半新内容（长度还正好对）。
                    // 只重来一次；再不对就是源在骗人。
                    if (shaRetry == 0 && (resumedFrom > 0 || sourcesUsed > 1)) {
                        shaRetry++;
                        delete(part);
                        delete(metaFile);
                        offset = 0;
                        resumedFrom = 0;
                        progressed = true;
                        break;
                    }
                    delete(part);
                    delete(metaFile);
                    return r(Status.SHA, "sha " + got + " != " + expectSha,
                            null, transferred, resumedFrom, lastTotal, got);
                }
                if (!part.renameTo(out)) {
                    delete(part);
                    delete(metaFile);
                    return r(Status.IO, "rename failed", null, transferred, resumedFrom, lastTotal, got);
                }
                delete(metaFile);
                return r(Status.OK, null, out, transferred, resumedFrom, lastTotal, got);
            }
            if (!progressed) break;
            if (cancel != null && cancel.canceled()) {
                if (resume) remember(metaFile, meta, null, expectSize, expectSha, part);
                else delete(part);
                return r(Status.CANCELED, "canceled", null, transferred, resumedFrom, lastTotal, null);
            }
        }

        // 走到这儿：没下完（或所有源都不给文件）。
        // ★ 一个字节都没收到、且所有源都"总长不对" ⇒ 报 WRONG（调用方据此报"源与清单不一致"）；
        //   只要收到过字节，就按"网络不完整"处理（半截还有用，续传主场景）。
        boolean wrongOnly = sawWrong && transferred == 0;
        if (resume && part.isFile() && part.length() > 0) {
            return r(wrongOnly ? Status.WRONG : Status.NETWORK,
                    lastErr == null ? "incomplete" : lastErr, null,
                    transferred, resumedFrom, lastTotal, null);
        }
        delete(part);
        delete(metaFile);
        return r(wrongOnly ? Status.WRONG : Status.NETWORK,
                lastErr == null ? "no usable source" : lastErr, null,
                transferred, resumedFrom, lastTotal, null);
    }

    /** 这份 `.part` 配不配得上清单（meta 缺失时只按长度判断）。 */
    private static boolean trusted(Meta m, long expectSize, String expectSha) {
        if (m == null) return true;                 // 崩溃在写 meta 之前：长度本身可信
        if (expectSize > 0 && m.size > 0 && m.size != expectSize) return false;
        if (expectSha != null && !expectSha.isEmpty()
                && m.sha != null && !m.sha.isEmpty()
                && !m.sha.equalsIgnoreCase(expectSha)) return false;
        return true;
    }

    /**
     * 已下完的判据：读到 EOF，且（知道总长 ⇒ 长度对得上）。
     *
     * <p>⚠️ 服务端提前关连接时 `Content-Length` 是"应该有多少"，不能当"收到了多少"。
     */
    private static boolean needsNoMore(File part, Try t, long expectSize) {
        long len = part.length();
        if (t.code == 416 && t.total > 0 && len == t.total) return true;
        if (t.total > 0 && len < t.total) return false;
        if (expectSize > 0 && len != expectSize) return false;
        return t.complete;
    }

    private static void remember(File metaFile, Meta old, Try t, long expectSize,
                                 String expectSha, File part) {
        try {
            Meta m = new Meta();
            m.url = t != null && t.url != null ? t.url : (old == null ? null : old.url);
            m.etag = t != null && t.etag != null ? t.etag : (old == null ? null : old.etag);
            m.total = t != null && t.total > 0 ? t.total : (old == null ? 0 : old.total);
            m.size = expectSize > 0 ? expectSize : (old == null ? 0 : old.size);
            m.sha = expectSha != null && !expectSha.isEmpty() ? expectSha
                    : (old == null ? null : old.sha);
            m.at = System.currentTimeMillis();
            m.write(metaFile);
        } catch (Throwable ignored) {
        }
    }

    /** 打开连接并**手动**跟 302（Range 要在每一跳上重新带；跨域也由这里决定要不要丢头）。 */
    private static HttpURLConnection connect(Source s, long start, String etag) throws IOException {
        String cur = s.url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(s.connectMs);
            c.setReadTimeout(s.readMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "mdt-launcher/1.0");
            c.setRequestProperty("Accept", "application/octet-stream");
            // ★ 不加 identity，Android 的 HttpURLConnection 会带 gzip 并**透明解压**，
            //   于是 Range 的偏移是解压后字节，续传必然错位。
            c.setRequestProperty("Accept-Encoding", "identity");
            if (start > 0) {
                c.setRequestProperty("Range", "bytes=" + start + "-");
                if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-Range", etag);
            }
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("redirect without Location");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    private static Try trySource(Source s, File part, long offset, long expectSize,
                                 Progress cb, Cancel cancel, Meta meta) {
        Try t = new Try();
        t.start = offset;
        t.url = s.url;
        HttpURLConnection c = null;
        try {
            String etag = meta == null ? null : meta.etag;
            c = connect(s, offset, etag);
            int code = c.getResponseCode();
            t.code = code;
            if (code == 416) {
                t.total = parseUnsatisfied(c.getHeaderField("Content-Range"));
                t.err = "416";
                return t;
            }
            if (code == 200) {
                t.total = c.getContentLengthLong();
                if (expectSize > 0 && t.total > 0 && t.total != expectSize) {
                    t.wrong = true;
                    t.err = "total " + t.total + " != " + expectSize + " @ " + s.url;
                    return t;
                }
            } else if (code == 206) {
                long[] cr = parseContentRange(c.getHeaderField("Content-Range"));
                if (cr == null) {
                    t.wrong = true;
                    t.err = "206 without Content-Range @ " + s.url;
                    return t;
                }
                if (cr[0] != offset) {
                    t.wrong = true;
                    t.err = "range start " + cr[0] + " != " + offset + " @ " + s.url;
                    return t;
                }
                t.total = cr[2];
                // ★ 206 **不代表是同一份文件**：2026-11-14 实测镜像会对错误页回
                //   `206 + Content-Range: bytes 0-1023/1901`。
                if (expectSize > 0 && t.total > 0 && t.total != expectSize) {
                    t.wrong = true;
                    t.err = "total " + t.total + " != " + expectSize + " @ " + s.url;
                    return t;
                }
            } else {
                t.err = "http " + code + " @ " + s.url;
                return t;
            }
            t.etag = c.getHeaderField("ETag");

            long base = code == 206 ? offset : 0L;
            Copy cp = copy(c, part, base, t.total, expectSize, s.maxTotalMs, cb, cancel);
            t.bytes = cp.bytes;
            t.complete = cp.complete;
            if (t.total > 0 && base + cp.bytes < t.total && cp.complete) {
                t.err = "closed early (" + (base + cp.bytes) + " / " + t.total + ")";
            }
            return t;
        } catch (Canceled ce) {
            t.canceled = true;
            t.bytes = ce.bytes;
            return t;
        } catch (IOException e) {
            t.err = String.valueOf(e);
            return t;
        } catch (Throwable e) {
            t.err = String.valueOf(e);
            return t;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    private static final class Copy {
        long bytes;
        boolean complete;
    }

    /**
     * 把正文写进 {@code part}。
     *
     * @param base 起始偏移（206 时 = 请求的 Range 起点；200 时 = 0，会截断重写）
     */
    private static Copy copy(HttpURLConnection c, File part, long base, long total,
                             long expectSize, long maxTotalMs, Progress cb, Cancel cancel)
            throws IOException, Canceled {
        Copy out = new Copy();
        InputStream in = c.getInputStream();
        FileOutputStream fos = new FileOutputStream(part, base > 0);
        long lastCb = 0L;
        long started = System.currentTimeMillis();
        try {
            byte[] buf = new byte[Bandwidth.chunkSize()];
            while (true) {
                if (cancel != null && cancel.canceled()) throw new Canceled(out.bytes);
                if (Thread.currentThread().isInterrupted()) throw new Canceled(out.bytes);
                if (maxTotalMs > 0 && System.currentTimeMillis() - started > maxTotalMs) {
                    throw new IOException("too slow (> " + maxTotalMs + " ms)");
                }
                int n = in.read(buf);
                if (n < 0) {
                    out.complete = true;
                    break;
                }
                fos.write(buf, 0, n);
                out.bytes += n;
                if (expectSize > 0 && base + out.bytes > expectSize) {
                    throw new IOException("body longer than expected");
                }
                long now = System.currentTimeMillis();
                if (cb != null && (now - lastCb >= 250 || n == 0)) {
                    lastCb = now;
                    cb.onProgress(base + out.bytes, total > 0 ? total : 0);
                }
                Bandwidth.after(n);          // 全局限速；被 interrupt 时抛 InterruptedException
            }
            fos.flush();
            try { fos.getFD().sync(); } catch (Throwable ignored) { }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new Canceled(out.bytes);
        } finally {
            try { fos.close(); } catch (Throwable ignored) { }
            try { in.close(); } catch (Throwable ignored) { }
        }
        if (cb != null) cb.onProgress(base + out.bytes, total > 0 ? total : 0);
        return out;
    }

    // ---- Content-Range --------------------------------------------------------

    /** `bytes 100-199/12345` → {100, 199, 12345}；`*` 总长记 -1；认不出 null。 */
    static long[] parseContentRange(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (!s.startsWith("bytes ")) return null;
        s = s.substring(6).trim();
        int dash = s.indexOf('-');
        int slash = s.indexOf('/');
        if (dash <= 0 || slash <= dash) return null;
        try {
            long start = Long.parseLong(s.substring(0, dash).trim());
            long end = Long.parseLong(s.substring(dash + 1, slash).trim());
            String tail = s.substring(slash + 1).trim();
            long total = "*".equals(tail) ? -1L : Long.parseLong(tail);
            return new long[]{start, end, total};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** `bytes *​/12345` → 12345；认不出 0。 */
    static long parseUnsatisfied(String v) {
        if (v == null) return 0L;
        int slash = v.indexOf('/');
        if (slash < 0) return 0L;
        try {
            return Long.parseLong(v.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ---- 元信息 ---------------------------------------------------------------

    /** `.part` 旁边的小状态；**只用于少走弯路**，判据永远是长度 + sha256。 */
    static final class Meta {
        String url;
        String etag;
        long total;
        long size;
        long at;
        String sha;

        static Meta read(File f) {
            if (f == null || !f.isFile()) return null;
            Properties p = new Properties();
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                p.load(in);
                Meta m = new Meta();
                m.url = p.getProperty("url");
                m.etag = p.getProperty("etag");
                m.total = num(p.getProperty("total"));
                m.size = num(p.getProperty("size"));
                m.at = num(p.getProperty("at"));
                m.sha = p.getProperty("sha");
                return m;
            } catch (Throwable t) {
                return null;
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) { }
            }
        }

        void write(File f) throws IOException {
            Properties p = new Properties();
            put(p, "url", url);
            put(p, "etag", etag);
            p.setProperty("total", String.valueOf(total));
            p.setProperty("size", String.valueOf(size));
            p.setProperty("at", String.valueOf(at));
            put(p, "sha", sha);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            OutputStream os = new FileOutputStream(tmp);
            try {
                p.store(os, "mdt download state");
                try { ((FileOutputStream) os).getFD().sync(); } catch (Throwable ignored) { }
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        }

        private static void put(Properties p, String k, String v) {
            if (v != null) p.setProperty(k, v);
        }

        private static long num(String s) {
            if (s == null) return 0L;
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }

    // ---- 杂项 ------------------------------------------------------------------

    static String sha256(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("no sha-256", e);
        }
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
        return hex(md.digest());
    }

    /** sha256 的"不抛"版本（校验路径上用它，失败只表示读不出来）。 */
    private static String sha256Quiet(File f) {
        try {
            return sha256(f);
        } catch (Throwable t) {
            return null;
        }
    }

    static String hex(byte[] data) {
        if (data == null) return null;
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static void delete(File f) {
        try {
            if (f != null && f.isFile()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static Result r(Status s, String detail, File file, long bytes, long from,
                            long total, String sha) {
        return new Result(s, detail, file, bytes, from, total, sha);
    }
}
