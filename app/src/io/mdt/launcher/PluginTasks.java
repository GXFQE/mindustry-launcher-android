package io.mdt.launcher;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import io.mdt.launcher.api.PluginApi;

/**
 * 插件**后台任务**的运行与登记（v22，第 221 轮）。
 *
 * <p>为什么要有它：`runBackground` 只是"页内长活"的语法糖 —— 页面一关，没人对结果负责。
 * 下载 / 复杂分析这类活需要的是**任务**：页面关了照跑、完成/失败/取消都告诉用户、能给取消句柄。
 *
 * <p>生命周期 = **本进程**：前台服务（{@link Work}）把进程提到 foreground-service 优先级，
 * 但 force-stop / 系统回收仍会杀。⇒ 下载要能断点续传、分析要把 checkpoint 落进
 * `dataDir()`，这是插件自己的责任（文档写死）。
 *
 * <p>⚠️ 任务里跑的是插件自己的字节码：宿主不取消、不打断（除非 {@link TaskImpl#cancel()}）；
 * `unload()` 绝不能被插件用来杀任务（页面关掉时宿主照旧调 `unload`，但那只是页面收尾）。
 */
final class PluginTasks {

    /** 正在跑的任务（按 id 登记；完成/失败/取消后移除）。 */
    private static final Map<String, TaskImpl> RUNNING = new HashMap<String, TaskImpl>();
    private static int sSeq = 0;

    private PluginTasks() {}

    /**
     * 起一个任务。
     *
     * @param ctx        调用方上下文（有 Activity 最好：前台服务的通知权限只能在界面申请）
     * @param label      通知正文（给用户看的一句话；空 ⇒ 通用文案）
     * @param body       任务体（在名为 `mdt-plugin-task` 的线程里跑）
     * @param notifyDone 结束时是否发完成通知（`runBackground` 传 false：页内长活不该在通知栏多一条）
     */
    static PluginApi.Task start(Context ctx, String label, Runnable body, boolean notifyDone) {
        final String id;
        synchronized (RUNNING) {
            id = "task-" + (++sSeq) + "-" + System.currentTimeMillis();
        }
        TaskImpl t = new TaskImpl(id, ctx, label, body, notifyDone);
        synchronized (RUNNING) {
            RUNNING.put(id, t);
        }
        t.thread.start();
        return t;
    }

    private static final class TaskImpl implements PluginApi.Task {
        final String id;
        final Context ctx;
        final String label;
        final Runnable body;
        final boolean notifyDone;
        final Thread thread;
        volatile boolean canceled;

        TaskImpl(String id, Context ctx, String label, Runnable body, boolean notifyDone) {
            this.id = id;
            this.ctx = ctx;
            this.label = label;
            this.body = body;
            this.notifyDone = notifyDone;
            this.thread = new Thread(new Runnable() {
                @Override public void run() {
                    work();
                }
            }, "mdt-plugin-task");
        }

        @Override public String id() {
            return id;
        }

        @Override public boolean running() {
            synchronized (RUNNING) {
                return RUNNING.containsKey(id);
            }
        }

        /** 取消：置标记 + 中断线程。任务体要自己检查中断（阻塞 IO 会抛 InterruptedException）。 */
        @Override public void cancel() {
            canceled = true;
            thread.interrupt();
        }

        private void work() {
            if (ctx != null) Work.begin(ctx, R.string.work_notif_plain, label);
            boolean failed = false;
            try {
                body.run();
            } catch (Throwable err) {
                // ★ 插件异常不许带走整个进程（非主线程未捕获异常默认会杀进程）
                failed = true;
                Log.w("MDTPlugin", "task failed: " + id, err);
            } finally {
                if (ctx != null) Work.end(ctx);
                synchronized (RUNNING) {
                    RUNNING.remove(id);
                }
                if (notifyDone) {
                    int text = canceled ? R.string.plugin_task_canceled
                            : (failed ? R.string.plugin_task_failed : R.string.plugin_task_done);
                    Work.notifyDone(ctx, label, safeGet(ctx, text), id.hashCode());
                }
            }
        }

        private static String safeGet(Context ctx, int res) {
            try {
                Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
                return app.getString(res);
            } catch (Throwable t) {
                return "";
            }
        }
    }
}
