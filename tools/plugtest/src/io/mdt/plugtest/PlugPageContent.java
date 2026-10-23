package io.mdt.plugtest;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.List;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.PluginTheme;
import io.mdt.launcher.api.SlotInfo;

/**
 * 第 159~162 / 187 轮的**页面动作**夹具：演示 {@code createView(PluginApi)}、{@code content} 插槽、
 * 只读数据面（槽 / 四类内容 / 设置 / 日志 / 中转站 / 版本 / 页面快照 / open）与 v7 的"做事"那几条
 * （主题 / 后台任务与进度 / 关页 / 分享 / 勾选）。
 *
 * <p>壳只按名字反射调用（{@code api()} + {@code createView(PluginApi)}），编译期不依赖它。
 * 页面把上下文、各类条目数与第一条的路径打印出来 —— 真机上一眼就能看出"宿主传对了没有"。
 *
 * <p>★ 字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public final class PlugPage {

    /** v21 活体用例用的键（游戏不认识、写完立刻删）。 */
    private static final String PROBE_KEY = "mdt-selftest-probe";

    /** 插件接口版本：v20 冻结基线。 */
    public static int api() {
        return __API__;
    }

    /** v20 唯一的页面入口。 */
    public static View createView(final PluginApi api) {
        final Context c = api.context();
        ItemInfo selItem = api.selectedItem();
        File selected = selItem == null || selItem.path == null ? null : new File(selItem.path);
        String sel = selected == null ? "null"
                : (selected.getName() + " @ " + selected.getAbsolutePath());
        List<SlotInfo> slots = api.slots();
        List<ItemInfo> saves = api.contents(ItemInfo.KIND_SAVES);
        List<ItemInfo> maps = api.contents(ItemInfo.KIND_MAPS);
        List<ItemInfo> mods = api.contents(ItemInfo.KIND_MODS);
        List<ItemInfo> bps = api.contents(ItemInfo.KIND_BLUEPRINTS);
        List<ItemInfo> page = api.pageItems();
        List<ItemInfo> selItems = api.selectedItems();
        List<ItemInfo> logs = api.logs();
        List<ItemInfo> trash = api.trash();
        List<ItemInfo> vers = api.versions();
        String open = "n/a";
        if (!maps.isEmpty() && maps.get(0).readable) {
            InputStream in = null;
            try {
                in = api.open(maps.get(0));
                int b = in.read();
                open = "firstByte=" + b;
            } catch (Throwable t) {
                open = "open failed: " + t.getClass().getSimpleName();
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) { }
            }
        }
        String openLog = "n/a";
        if (!logs.isEmpty() && logs.get(0).readable) {
            InputStream in = null;
            try {
                in = api.open(logs.get(0));
                int b = in.read();
                openLog = "firstByte=" + b;
            } catch (Throwable t) {
                openLog = "open failed: " + t.getClass().getSimpleName();
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) { }
            }
        }
        api.log("content fixture opened: page=" + api.page() + " slot=" + api.slot()
                + " selected=" + (selected == null ? "null" : selected.getAbsolutePath())
                + " slots=" + slots.size() + " pageItems=" + page.size());
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 32, 32, 32);

        PluginTheme th = api.theme();
        box.setBackgroundColor(th.background);

        TextView title = new TextView(c);
        title.setText("content action fixture");
        title.setTextSize(20f);
        title.setTextColor(th.text);
        box.addView(title);

        TextView info = new TextView(c);
        info.setTextSize(13f);
        info.setTextColor(th.textMuted);
        info.setText("hostApi=" + api.hostApi()
                + "\npage=" + api.page()
                + "\nslot=" + api.slot()
                + "\nselected=" + sel
                + "\nslots=" + slots.size() + " pageItems=" + page.size()
                + " selectedItems=" + selItems.size()
                + "\nsaves=" + saves.size() + " maps=" + maps.size()
                + " mods=" + mods.size() + " blueprints=" + bps.size()
                + "\nlogs=" + logs.size()
                + " trash=" + trash.size() + " versions=" + vers.size()
                + "\ntheme dark=" + th.dark + " bg=" + Integer.toHexString(th.background)
                + " dp8=" + th.dp(8) + " sp14=" + th.sp(14)
                + "\nfirstMap=" + first(maps)
                + "\nopen=" + open + " openLog=" + openLog
                + "\nslotDir=" + api.slotDir().getAbsolutePath());
        box.addView(info);

        final TextView out = new TextView(c);
        out.setTextSize(13f);
        out.setTextColor(th.accent);
        out.setText("v7 actions: (none yet)");
        box.addView(out);

        final Context toastCtx = c;
        addButton(box, c, th, "tap to toast (api)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.widget.Toast.makeText(toastCtx, "toast via PluginApi",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        addButton(box, c, th, "run background (3s, progress)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                out.setText("background: running");
                api.runBackground("fixture background", new Runnable() {
                    @Override public void run() {
                        for (int i = 1; i <= 30; i++) {
                            api.progress(i, 30);
                            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                        }
                        api.log("fixture background done");
                    }
                });
            }
        });

        addButton(box, c, th, "pick maps (host dialog)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                api.pickContent(ItemInfo.KIND_MAPS, new PluginApi.PickContent() {
                    @Override public void onPicked(List<ItemInfo> items) {
                        StringBuilder sb = new StringBuilder("picked=" + items.size());
                        for (int i = 0; i < items.size() && i < 3; i++) {
                            sb.append("\n- ").append(items.get(i).title);
                        }
                        out.setText(sb.toString());
                    }
                });
            }
        });

        addButton(box, c, th, "share text", new View.OnClickListener() {
            @Override public void onClick(View v) {
                api.shareText("plugin-fixture.txt", "hello from a plugin\n");
                out.setText("share: asked the host");
            }
        });

        // v22（第 221 轮）：后台任务 —— 页面关了照跑，完成/取消各发通知
        addButton(box, c, th, "submit 4s task (v22)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                PluginApi.Task t = api.submit("fixture task", new Runnable() {
                    @Override public void run() {
                        try {
                            Thread.sleep(4000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                out.setText("task submitted: " + (t == null ? "null"
                        : t.id() + " running=" + t.running()));
            }
        });
        addButton(box, c, th, "submit + cancel (v22)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                final PluginApi.Task t = api.submit("cancel me", new Runnable() {
                    @Override public void run() {
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        new Runnable() {
                            @Override public void run() {
                                if (t != null) t.cancel();
                                out.setText("canceled: " + (t == null ? "null" : t.running()));
                            }
                        }, 1000);
            }
        });

        addButton(box, c, th, "export text (SAF)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                byte[] data = "export from plugin\n".getBytes();
                api.export("plugin-export.txt", "text/plain",
                        new java.io.ByteArrayInputStream(data));
                out.setText("export: asked the host");
            }
        });

        // v21（第 220 轮）：设置面的活体用例 —— 读当前槽 settings.bin 的解析结果
        addButton(box, c, th, "read game settings (v21)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                java.util.Map<String, Object> gs = api.gameSettings();
                out.setText(gs == null ? "gameSettings=null (unreadable)"
                        : "gameSettings=" + gs.size() + " keys; probe=" + gs.get(PROBE_KEY));
            }
        });

        // 写 → 读回 → 删掉（全走插件接口；键是游戏不认识的，写完立刻清）
        addButton(box, c, th, "write settings probe (v21)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                final java.util.LinkedHashMap<String, Object> put =
                        new java.util.LinkedHashMap<String, Object>();
                put.put(PROBE_KEY, "probe-" + System.currentTimeMillis());
                api.writeGameSettings(put, new PluginApi.Done() {
                    @Override public void onDone(io.mdt.launcher.api.Result r1) {
                        if (!r1.ok) {
                            out.setText("write failed: " + r1.reason);
                            return;
                        }
                        java.util.Map<String, Object> gs = api.gameSettings();
                        final Object got = gs == null ? null : gs.get(PROBE_KEY);
                        final java.util.LinkedHashMap<String, Object> del =
                                new java.util.LinkedHashMap<String, Object>();
                        del.put(PROBE_KEY, null);
                        api.writeGameSettings(del, new PluginApi.Done() {
                            @Override public void onDone(io.mdt.launcher.api.Result r2) {
                                out.setText("write ok; readback=" + got
                                        + "; cleanup=" + (r2.ok ? "ok" : r2.reason));
                            }
                        });
                    }
                });
            }
        });

        addButton(box, c, th, "close page", new View.OnClickListener() {
            @Override public void onClick(View v) {
                api.closePage();
            }
        });

        return box;
    }

    /** v22：事件扩展入口（game-start / game-exit）—— 宿主按 plugin.json 的 `event` 字段调。 */
    public static void onEvent(io.mdt.launcher.api.PluginApi api, String name,
                               java.util.Map<String, String> data) {
        api.log("EVENT " + name + " " + data);
    }

    /** 一行"像宿主动作行"的按钮（纯文字 + 强调色，够真机点就行）。 */
    private static void addButton(LinearLayout box, Context c, PluginTheme th, String label,
                                  View.OnClickListener l) {
        TextView btn = new TextView(c);
        btn.setText(label);
        btn.setTextSize(16f);
        btn.setTextColor(th.accent);
        btn.setPadding(0, th.dp(14), 0, 0);
        btn.setOnClickListener(l);
        box.addView(btn);
    }

    private static String first(List<ItemInfo> list) {
        if (list == null || list.isEmpty()) return "none";
        ItemInfo i = list.get(0);
        if (i.path != null) return i.title + " @ " + i.path;
        if (i.container != null) return i.title + " @ " + i.container + "!/" + i.entry;
        return i.title;
    }

    /** v24 分析器夹具：每个模组给一条结论（宿主负责渲染成列表顶上的卡）。 */
    public static List<io.mdt.launcher.api.Finding> analyze(ItemInfo item,
            io.mdt.launcher.api.PluginApi api) {
        List<io.mdt.launcher.api.Finding> out =
                new java.util.ArrayList<io.mdt.launcher.api.Finding>();
        if (item != null) {
            out.add(new io.mdt.launcher.api.Finding("fixture: " + item.title,
                    "analyzed by plugtest-content", io.mdt.launcher.api.Finding.WARN,
                    item.path == null ? "" : item.path));
        }
        return out;
    }
}
