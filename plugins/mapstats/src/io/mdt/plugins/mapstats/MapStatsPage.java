package io.mdt.plugins.mapstats;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PickCallback;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.PluginTheme;
import io.mdt.plugins.mapstats.core.MapStats;
import io.mdt.plugins.mapstats.core.MsavTiles;

/**
 * 官方插件 mapstats 的页面：地图资源统计（第 188 轮，从宿主的 F21 搬出来）。
 *
 * <p>宿主只按名字反射调 {@link #api()} + {@link #createView(PluginApi)}；编译期只依赖
 * {@code io.mdt.launcher.api} 那几个类（壳与插件各一份，运行时父加载器的赢）。
 *
 * <p>数据全走 v7 门面：{@code pick} 选图、{@code open} 读字节、{@code runBackground} 跑计数、
 * {@code progress} 报进度、{@code share} / {@code export} 出结果、{@code theme} 上色。
 * ⚠️ 本文件的字符串字面量一律 ASCII（与宿主同一条纪律）。
 */
public final class MapStatsPage {

    /** 接口版本：v7（主题 / 后台任务 + 进度 / 关页 / 分享·保存 / 勾选）。 */
    public static int api() {
        return 7;
    }

    /** 当前这一页的统计产物（页面重建就重新算）。 */
    private static final class Built {
        String name = "";
        MapStats.Result result;
        long millis;
        String error = "";
    }

    public static View createView(final PluginApi api) {
        final Context c = api.context();
        final PluginTheme th = api.theme();
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(th.background);

        final TextView title = new TextView(c);
        title.setText(c.getString(R.string.ms_title));
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(th.text);
        root.addView(title);

        final TextView hint = new TextView(c);
        hint.setText(c.getString(R.string.ms_hint));
        hint.setTextSize(13f);
        hint.setTextColor(th.textMuted);
        hint.setPadding(0, th.dp(6), 0, 0);
        root.addView(hint);

        final TextView status = new TextView(c);
        status.setText(c.getString(R.string.ms_nothing));
        status.setTextSize(13f);
        status.setTextColor(th.textMuted);
        status.setPadding(0, th.dp(10), 0, 0);
        root.addView(status);

        final LinearLayout results = new LinearLayout(c);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);

        final List<Built> built = new ArrayList<>();

        addButton(root, c, th, c.getString(R.string.ms_pick), new View.OnClickListener() {
            @Override public void onClick(View v) {
                api.pick(ItemInfo.KIND_MAPS, new PickCallback() {
                    @Override public void onPicked(List<ItemInfo> items) {
                        if (items == null || items.isEmpty()) {
                            api.toast(c.getString(R.string.ms_pick_empty));
                            return;
                        }
                        run(api, c, th, status, results, built, items);
                    }
                });
            }
        });

        final View[] actions = new View[2];
        actions[0] = addButton(root, c, th, c.getString(R.string.ms_share), new View.OnClickListener() {
            @Override public void onClick(View v) {
                String js = jsonOf(built);
                if (js == null) {
                    api.toast(c.getString(R.string.ms_nothing));
                    return;
                }
                api.shareText("mapstats.json", js);
            }
        });
        actions[1] = addButton(root, c, th, c.getString(R.string.ms_export), new View.OnClickListener() {
            @Override public void onClick(View v) {
                String js = jsonOf(built);
                if (js == null) {
                    api.toast(c.getString(R.string.ms_nothing));
                    return;
                }
                api.export("mapstats.json", "application/json",
                        new ByteArrayInputStream(js.getBytes(StandardCharsets.UTF_8)));
            }
        });
        setEnabled(actions, false);

        addButton(root, c, th, c.getString(R.string.ms_close), new View.OnClickListener() {
            @Override public void onClick(View v) {
                api.closePage();
            }
        });

        // 有结果之后才让"分享 / 保存"可点（与宿主那条"没有结果就别给出口"一致）
        results.setTag(actions);
        return root;
    }

    /** 后台算、主线程画。 */
    private static void run(final PluginApi api, final Context c, final PluginTheme th,
                            final TextView status, final LinearLayout results,
                            final List<Built> built, final List<ItemInfo> items) {
        built.clear();
        results.removeAllViews();
        status.setText(c.getString(R.string.ms_busy_fmt, 1, items.size()));
        api.runBackground("mapstats", new Runnable() {
            @Override public void run() {
                final List<Built> out = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    ItemInfo it = items.get(i);
                    api.progress(i + 1, items.size());
                    Built b = new Built();
                    b.name = it.title == null ? "map" : it.title;
                    InputStream in = null;
                    try {
                        in = api.open(it);
                        MsavTiles.Tiles t = MsavTiles.read(in, false);
                        if (t == null || !t.ok()) {
                            b.error = "unreadable";
                        } else {
                            b.result = MapStats.analyze(t, MapStats.vanilla(), new RawNames(), null);
                            b.millis = b.result.millis;
                        }
                    } catch (Throwable e) {
                        b.error = String.valueOf(e.getMessage());
                    } finally {
                        closeQuietly(in);
                    }
                    out.add(b);
                }
                results.post(new Runnable() {
                    @Override public void run() {
                        built.clear();
                        built.addAll(out);
                        render(c, th, status, results, out);
                        View[] actions = actionsOf(results);
                        setEnabled(actions, jsonOf(built) != null);
                    }
                });
            }
        });
    }

    private static void render(Context c, PluginTheme th, TextView status,
                               LinearLayout results, List<Built> list) {
        results.removeAllViews();
        long millis = 0;
        for (Built b : list) {
            if (b.error != null && !b.error.isEmpty() && b.result == null) {
                addLine(results, c, th, c.getString(R.string.ms_failed_fmt, b.error), th.textMuted, 13f);
                continue;
            }
            MapStats.Result r = b.result;
            millis += b.millis;
            TextView head = new TextView(c);
            head.setText(c.getString(R.string.ms_map_head_fmt, b.name, r.width, r.height));
            head.setTextSize(15f);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextColor(th.text);
            head.setPadding(0, th.dp(14), 0, 0);
            results.addView(head);

            section(results, c, th, c.getString(R.string.ms_sec_ores), r.ores);
            section(results, c, th, c.getString(R.string.ms_sec_walls), r.oreWalls);
            section(results, c, th, c.getString(R.string.ms_sec_floors), r.floors);
            section(results, c, th, c.getString(R.string.ms_sec_bonus), r.bonuses);

            addLine(results, c, th, c.getString(R.string.ms_buildable_fmt, r.buildable.count()),
                    th.text, 13f);
            if (!r.core.skip && r.core.zone > 0) {
                addLine(results, c, th,
                        c.getString(R.string.ms_core_fmt, r.core.usable, r.core.max, r.core.size),
                        th.text, 13f);
            }
            int unknown = r.unkOre + r.unkFloor + r.unkBonus;
            if (unknown > 0) {
                addLine(results, c, th, c.getString(R.string.ms_unknown_fmt, unknown), th.textMuted, 13f);
            }
        }
        status.setText(c.getString(R.string.ms_done_fmt, millis));
    }

    private static void section(LinearLayout box, Context c, PluginTheme th, String title,
                                List<MapStats.Row> rows) {
        if (rows == null || rows.isEmpty()) return;
        TextView h = new TextView(c);
        h.setText(title);
        h.setTextSize(14f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(th.accent);
        h.setPadding(0, th.dp(10), 0, 0);
        box.addView(h);
        for (MapStats.Row r : rows) {
            addLine(box, c, th, c.getString(R.string.ms_row_fmt, r.label, r.total), th.text, 13f);
        }
    }

    private static void addLine(LinearLayout box, Context c, PluginTheme th, String text,
                                int color, float size) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, th.dp(4), 0, 0);
        box.addView(t);
    }

    private static TextView addButton(LinearLayout box, Context c, PluginTheme th, String label,
                                      View.OnClickListener l) {
        TextView btn = new TextView(c);
        btn.setText(label);
        btn.setTextSize(16f);
        btn.setTextColor(th.accent);
        btn.setPadding(0, th.dp(14), 0, 0);
        btn.setOnClickListener(l);
        box.addView(btn);
        return btn;
    }

    private static View[] actionsOf(View any) {
        Object tag = any.getTag();
        return tag instanceof View[] ? (View[]) tag : new View[0];
    }

    private static void setEnabled(View[] views, boolean on) {
        for (View v : views) {
            if (v != null) {
                v.setEnabled(on);
                v.setAlpha(on ? 1f : 0.4f);
            }
        }
    }

    // ---- 出口：JSON（v1 只出这份统计本身；声明 / 版本信息等下一轮补齐） -----------------

    private static String jsonOf(List<Built> list) {
        boolean any = false;
        for (Built b : list) if (b.result != null && b.result.ok) { any = true; break; }
        if (!any) return null;
        StringBuilder sb = new StringBuilder("{\"plugin\":\"mapstats\",\"maps\":[");
        boolean first = true;
        for (Built b : list) {
            if (b.result == null || !b.result.ok) continue;
            if (!first) sb.append(',');
            first = false;
            MapStats.Result r = b.result;
            sb.append("{\"map\":").append(q(b.name))
                    .append(",\"width\":").append(r.width)
                    .append(",\"height\":").append(r.height)
                    .append(",\"cells\":").append(r.cells)
                    .append(",\"ores\":").append(rows(r.ores))
                    .append(",\"oreWalls\":").append(rows(r.oreWalls))
                    .append(",\"floors\":").append(rows(r.floors))
                    .append(",\"bonuses\":").append(rows(r.bonuses))
                    .append(",\"buildable\":{\"free\":").append(r.buildable.free)
                    .append(",\"cleared\":").append(r.buildable.cleared)
                    .append(",\"count\":").append(r.buildable.count()).append('}')
                    .append(",\"core\":{\"skip\":").append(r.core.skip)
                    .append(",\"zone\":").append(r.core.zone)
                    .append(",\"usable\":").append(r.core.usable)
                    .append(",\"max\":").append(r.core.max)
                    .append(",\"size\":").append(r.core.size).append('}')
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String rows(List<MapStats.Row> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            MapStats.Row r = rows.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"label\":").append(q(r.label))
                    .append(",\"total\":").append(r.total)
                    .append(",\"reachable\":").append(r.reachable())
                    .append('}');
        }
        return sb.append(']').toString();
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20) sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.append('"').toString();
    }

    /** 没有译名表时的兜底：内部名 → 可读（`dark-metal` → `Dark Metal`）。 */
    private static final class RawNames implements MapStats.Names {
        @Override public String block(String internal) {
            return MapStats.pretty(internal);
        }

        @Override public String item(String internal) {
            return MapStats.pretty(internal);
        }

        @Override public String wallName(String base) {
            return base + " (wall)";
        }

        @Override public String attr(String key) {
            return MapStats.pretty(key);
        }

        @Override public String liquid(String internal) {
            return MapStats.pretty(internal);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }

    private MapStatsPage() {}
}
