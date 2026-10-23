package io.mdt.plugtest;

import java.io.File;
import java.io.FileOutputStream;

import io.mdt.launcher.api.Probe;
import io.mdt.launcher.api.ProbeEnv;

/**
 * 探针夹具（v23）：实现正式接口，进程内 / `:probe` 两侧都能跑。
 *
 * <p>只写自己目录里的 `probe.tsv`（宿主不解析格式）；进程内多写一行 `inProcess=1`，
 * 隔离进程写 `inProcess=0` —— 用来区分两边的数据。
 */
public final class ProbeFixture implements Probe {

    private ProbeEnv env;
    private File out;

    @Override public String name() {
        return "fixture";
    }

    @Override public void reset(ProbeEnv env) {
        this.env = env;
        this.out = env == null ? null : new File(env.dir(), "probe.tsv");
    }

    @Override public void sample() {
        if (out == null) return;
        try {
            FileOutputStream o = new FileOutputStream(out, true);
            try {
                o.write(("sample " + System.currentTimeMillis()
                        + " inProcess=" + (env != null && env.inProcess() ? 1 : 0)
                        + " pid=" + (env == null ? 0 : env.pid()) + "\n").getBytes("UTF-8"));
            } finally {
                try { o.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            // 探针失败不许影响游戏
        }
    }

    @Override public void summary() {
        if (out == null) return;
        try {
            FileOutputStream o = new FileOutputStream(out, true);
            try {
                o.write(("summary inProcess=" + (env != null && env.inProcess() ? 1 : 0) + "\n")
                        .getBytes("UTF-8"));
            } finally {
                try { o.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
        }
    }
}
