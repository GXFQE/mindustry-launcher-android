package io.mdt.plugins.sample;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.PluginTheme;
import io.mdt.launcher.api.Result;

/**
 * 文档配套示例（PLUGIN-API.md）：一个最小但完整的插件。
 *
 * <ul>
 *   <li>{@code section} 挂在 map-detail：显示当前地图的标题/大小，并读前 8 字节证明 open() 可用</li>
 *   <li>{@code action} 挂在 maps：勾选若干净地图 → 选格式 → 导出 json/csv</li>
 *   <li>{@code unload}：关页时写一行日志</li>
 * </ul>
 */
public final class SamplePlugin {

    public static int api() {
        return 41;
    }

    public static View createView(PluginApi api) {
        Context c = api.context();
        PluginTheme t = api.theme();
        if ("map-detail".equals(api.page())) {
            // v41：用**宿主组件工具箱**拼这一段（字号 / 颜色 / 圆角都跟宿主一样）
            io.mdt.launcher.api.PluginUi ui = api.ui();
            LinearLayout card = (LinearLayout) ui.card();
            card.addView(ui.sectionTitle("Sample plugin"));
            ItemInfo it = api.selectedItem();
            if (it == null) {
                card.addView(ui.text("no selected item", true));
            } else {
                card.addView(ui.kv("title", it.title));
                card.addView(ui.kv("size", it.size + " B"));
                String head = "";
                try {
                    InputStream in = api.open(it);
                    if (in != null) {
                        byte[] buf = new byte[8];
                        int n = in.read(buf);
                        in.close();
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < Math.max(n, 0); i++) {
                            sb.append(String.format("%02x", buf[i] & 0xff));
                        }
                        head = sb.toString();
                    }
                } catch (Throwable e) {
                    head = "err: " + e.getClass().getSimpleName();
                }
                card.addView(ui.kv("head", head));
            }
            // v41：宿主按钮 + 打开插件自己的整页（openPluginPage）
            View open = ui.button("Open plugin page");
            open.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    api.openPluginPage();
                }
            });
            card.addView(open);
            return card;
        }
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(c);
        title.setText("Sample plugin");
        title.setTextSize(16f);
        title.setTextColor(t.text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        ItemInfo it = api.selectedItem();
        TextView line = new TextView(c);
        line.setTextSize(13f);
        line.setTextColor(t.textMuted);
        line.setPadding(0, dp(c, 6), 0, 0);
        // v40：三个新页面钩子（version / patches / resources）—— 宿主把 section 画进那一页
        String pg = api.page();
        if ("version".equals(pg) || "patches".equals(pg) || "resources".equals(pg)) {
            line.setText("Sample plugin · " + pg + " hook"
                    + (it == null || it.title == null ? "" : " · " + it.title));
            box.addView(line);
            return box;
        }
        if (it == null) {
            line.setText("no selected item");
        } else {
            String head = "";
            try {
                InputStream in = api.open(it);
                if (in != null) {
                    byte[] buf = new byte[8];
                    int n = in.read(buf);
                    in.close();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.max(n, 0); i++) {
                        sb.append(String.format("%02x", buf[i] & 0xff));
                    }
                    head = sb.toString();
                }
            } catch (Throwable e) {
                head = "err: " + e.getClass().getSimpleName();
            }
            line.setText(it.title + " · " + it.size + " B · head " + head);
        }
        box.addView(line);
        return box;
    }

    public static void run(final PluginApi api) {
        api.pickContent(ItemInfo.KIND_MAPS, new PluginApi.PickContent() {
            @Override public void onPicked(List<ItemInfo> items) {
                if (items == null || items.isEmpty()) {
                    api.toast("nothing selected");
                    return;
                }
                final List<ItemInfo> picked = items;
                api.choose("Export format", new String[]{"json", "csv"},
                        new PluginApi.Choose() {
                            @Override public void onChoose(int index) {
                                String out = index == 0 ? toJson(picked) : toCsv(picked);
                                String name = "sample." + (index == 0 ? "json" : "csv");
                                String mime = index == 0 ? "application/json" : "text/csv";
                                api.export(name, mime,
                                        new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8)),
                                        new PluginApi.After() {
                                            @Override public void onAfter(Result r) {
                                                api.log("SAMPLE export " + r);
                                            }
                                        });
                            }
                        });
            }
        });
    }

    public static void unload() {
        android.util.Log.i("MDTPlugin", "SAMPLE unload");
    }

    private static String toJson(List<ItemInfo> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            ItemInfo it = items.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"title\":\"").append(escape(it.title))
              .append("\",\"size\":").append(it.size).append('}');
        }
        return sb.append(']').toString();
    }

    private static String toCsv(List<ItemInfo> items) {
        StringBuilder sb = new StringBuilder("title,size\n");
        for (ItemInfo it : items) {
            sb.append('"').append(escape(it.title)).append("\",").append(it.size).append('\n');
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
