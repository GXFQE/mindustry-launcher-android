package io.mdt.plugins.usagestats;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import io.mdt.launcher.api.Probe;
import io.mdt.launcher.api.ProbeEnv;
import io.mdt.plugins.usagestats.core.Playtime;

/**
 * 前台时长探针（v30）。
 *
 * <p>★ 分工：宿主只报「游戏 activity 进/出台」事件（{@link #onForeground}），
 * **累计与落盘全在这里** —— 宿主不知道我们记什么、怎么记。
 *
 * <p>口径：只算游戏 activity 在前台的时间（主菜单 / 暂停 / 编辑器都算，切到别的 App 不算）；
 * 进程被杀最多丢一次采样间隔（20 秒），**只少不多**。
 *
 * <p>数据落两处（都在插件自己的 data 目录，见 {@link ProbeEnv#dataDir()}）：
 * <ul>
 *   <li>{@code ledger.tsv} = 按「槽 + 版本」累计的账本（页面看它，格式见 {@link Playtime}）；</li>
 *   <li>{@code total.txt} = 全槽总毫秒数（**主界面摘要卡**读它；一个整数，宿主不解析账本）。</li>
 * </ul>
 * 两份都走「临时文件 + 改名」，读到的永远是完整文件。
 */
public final class PlaytimeProbe implements Probe {

    private File dataDir;
    private String slot = "";
    private String version = "";
    /** 内存里的账本（reset 时读入；每次 flush 覆盖写回） */
    private String ledger = "";
    /** reset 时这个 (槽, 版本) 已经累计的毫秒 */
    private long baseMs;
    /** 本会话**已结束**的前台片段之和（单调时钟，不怕用户改系统时间） */
    private long accMs;
    /** 当前这段前台从什么时候开始（0 = 不在前台） */
    private long fgSince;

    @Override public String name() {
        return "usagestats";
    }

    @Override public void reset(ProbeEnv env) {
        dataDir = env == null ? null : env.dataDir();
        slot = env == null || env.slot() == null ? "" : env.slot().trim();
        String v = env == null || env.version() == null ? "" : env.version().trim();
        version = v.isEmpty() ? Playtime.UNKNOWN_VERSION : v;
        ledger = dataDir == null ? "" : read(new File(dataDir, "ledger.tsv"));
        baseMs = Playtime.valueFor(ledger, slot, version);
        accMs = 0;
        fgSince = 0;
    }

    @Override public synchronized void onForeground(boolean foreground) {
        long now = SystemClock.elapsedRealtime();
        if (foreground) {
            if (fgSince == 0) fgSince = now;
        } else if (fgSince != 0) {
            accMs += Math.max(0, now - fgSince);
            fgSince = 0;
        }
        flush();
    }

    @Override public synchronized void sample() {
        flush();
    }

    @Override public synchronized void summary() {
        flush();
    }

    /** 本会话已在前台多久（毫秒）。 */
    private long sessionMs() {
        return accMs + (fgSince == 0 ? 0 : Math.max(0, SystemClock.elapsedRealtime() - fgSince));
    }

    /** 把「base + 本会话」写回账本，并刷新 `total.txt`。异常全吞：探针失败不许影响游戏。 */
    private void flush() {
        if (dataDir == null || slot.isEmpty()) return;
        try {
            long ms = baseMs + sessionMs();
            if (ms <= 0) return;
            ledger = Playtime.put(ledger, slot, version, ms, System.currentTimeMillis());
            if (!dataDir.isDirectory() && !dataDir.mkdirs()) return;
            writeAtomic(new File(dataDir, "ledger.tsv"), ledger);
            writeAtomic(new File(dataDir, "total.txt"), String.valueOf(Playtime.total(ledger)));
        } catch (Throwable ignored) {
            // 探针写失败不许影响游戏：宿主那边也只记日志
        }
    }

    private static void writeAtomic(File f, String text) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write(text.getBytes("UTF-8"));
            os.flush();
            os.close();
            os = null;
            if (!tmp.renameTo(f)) {
                // 有些文件系统不许覆盖式 rename ⇒ 退一步：删了再改名（中间窗口只影响一次读）
                f.delete();
                if (!tmp.renameTo(f)) tmp.delete();
            }
        } catch (Throwable t) {
            if (os != null) {
                try { os.close(); } catch (Throwable ignored) { }
            }
            tmp.delete();
        }
    }

    private static String read(File f) {
        if (f == null || !f.isFile()) return "";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 256 * 1024) break;      // 账本不该这么大；坏文件也别撑爆内存
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
