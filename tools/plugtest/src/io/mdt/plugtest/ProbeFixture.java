package io.mdt.plugtest;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 探针夹具（`:game` 进程内加载的验证用类，2026-10-23 第 222 轮）。
 *
 * <p>刻意不实现宿主接口（探针契约还没冻结）：只按方法名被反射调用
 * `name()` / `reset(File)` / `sample()`，证明"插件 dex 能在游戏进程里加载并跑"。
 * 数据自己写，宿主不解析格式（B2 的分层）。
 */
public final class ProbeFixture {

    private File dir;

    public String name() {
        return "fixture";
    }

    public void reset(File dir) {
        this.dir = dir;
    }

    public void sample() {
        if (dir == null) return;
        try {
            File f = new File(dir, "probe.tsv");
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(("sample " + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            // 探针失败不许影响游戏
        }
    }

    public String summary() {
        return "fixture ok";
    }
}
