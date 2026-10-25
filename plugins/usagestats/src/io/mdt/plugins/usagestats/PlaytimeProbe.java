package io.mdt.plugins.usagestats;

import java.io.File;
import java.io.FileOutputStream;

import io.mdt.launcher.api.Probe;
import io.mdt.launcher.api.ProbeEnv;

/**
 * 前台时长探针（v29）。
 *
 * <p>★ 分工：宿主只报「游戏 activity 进/出台」事件（{@link #onForeground}），
 * **累计与落盘全在这里** —— 宿主不知道我们记什么、怎么记。
 *
 * <p>口径：只算游戏 activity 在前台的时间（主菜单 / 暂停 / 编辑器都算，切到别的 App 不算）；
 * 进程被杀最多丢一次采样间隔（20 秒），**只少不多**。
 */
public final class PlaytimeProbe implements Probe {

    private File dir;
    private String slot = "";
    /** 已经结束的前台片段之和 */
    private long accumulatedMs;
    /** 当前这段前台从什么时候开始（0 = 不在前台） */
    private long fgSinceMs;

    @Override public String name() {
        return "usagestats";
    }

    @Override public void reset(ProbeEnv env) {
        dir = env.dir();
        slot = env.slot();
        accumulatedMs = 0;
        fgSinceMs = 0;
        write(0);
    }

    @Override public synchronized void onForeground(boolean foreground) {
        long now = System.currentTimeMillis();
        if (foreground) {
            if (fgSinceMs == 0) fgSinceMs = now;
        } else if (fgSinceMs != 0) {
            accumulatedMs += Math.max(0, now - fgSinceMs);
            fgSinceMs = 0;
        }
        write(nowMs(now));
    }

    @Override public synchronized void sample() {
        write(nowMs(System.currentTimeMillis()));
    }

    @Override public synchronized void summary() {
        write(nowMs(System.currentTimeMillis()));
    }

    private long nowMs(long now) {
        return accumulatedMs + (fgSinceMs == 0 ? 0 : Math.max(0, now - fgSinceMs));
    }

    /** 写自己会话目录里的 `playtime.txt`（格式见 {@code core/Playtime}）。 */
    private void write(long totalMs) {
        if (dir == null) return;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            FileOutputStream os = new FileOutputStream(new File(dir, "playtime.txt"));
            try {
                os.write(("total=" + totalMs + "\nslot=" + slot + "\nupdated="
                        + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
            } finally {
                os.close();
            }
        } catch (Throwable ignored) {
            // 探针写失败不许影响游戏：宿主那边也只记日志
        }
    }
}
