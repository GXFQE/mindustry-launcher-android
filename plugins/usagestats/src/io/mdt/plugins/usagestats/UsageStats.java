package io.mdt.plugins.usagestats;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.PluginTheme;
import io.mdt.plugins.usagestats.core.Heat;
import io.mdt.plugins.usagestats.core.Playtime;
import io.mdt.plugins.usagestats.core.Sessions;

/**
 * 游玩时长（官方插件，v32）。
 *
 * <p>两个落点，**都在宿主自己的页面里**（插件页不再放入口）：
 * <ul>
 *   <li>`slot: section` + `page: slot` ⇒ 槽页信息卡下面一张卡：本槽合计 + 按游戏版本分列
 *       （已装游戏原地更新后，更新前后是**两行**）；</li>
 *   <li>`playtimeTotal`（plugin.json）⇒ `total.txt`，宿主把它排版进**主界面摘要卡**。</li>
 * </ul>
 * 这个类同时是插件自述里的默认入口：从插件页长按「打开」时，页面显示总计 + 按槽分列。
 *
 * <p>★ 2026-11-17（第 384 轮）：**不再用 in-process 探针计时**。宿主在 `:game` 里用
 * `SessionClock` 记这一局（前台累计 `activeMs`），退出时通过 `game-exit` 事件交一次；
 * 这里只负责把 `activeMs` 追加进账本。少一层 `:game` 里的插件代码（官方内置插件崩了
 * 就是游戏崩，崩溃报告还会标「有插件参与」）。
 */
public final class UsageStats {

    public static int api() {
        // 2026-11-17（第 385 轮）：本插件依赖宿主 44 的 `game-exit` 观测键
        // （started / ended / activeMs / wallMs / key）——报 44 让旧宿主明确拒掉，
        // 而不是收不到键、静默不记时长。
        return 44;
    }

    public static View createView(PluginApi api) {
        // 第 400 轮：插件页里的「游玩统计」面板（宿主顶栏进来时 page = "panel"）
        if (api != null && "panel".equals(api.page())) return panel(api);
        Context c = api.context();
        PluginTheme th = api.theme();
        String ledger = api.dataDir() == null ? "" : read(new File(api.dataDir(), "ledger.tsv"));
        boolean slotPage = "slot".equals(api.page());
        String slot = api.slot() == null ? "" : api.slot();

        long total = slotPage ? Playtime.totalForSlot(ledger, slot) : Playtime.total(ledger);
        if (total <= 0) return null;                 // 没有记录 ⇒ 整张卡不出现（不留空壳）

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int hpad = th.dp(14);
        box.setPadding(hpad, th.dp(4), hpad, th.dp(4));

        TextView head = new TextView(c);
        head.setTextSize(14f);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(th.text);
        head.setText(c.getString(slotPage ? R.string.us_slot_total_fmt : R.string.us_total_fmt,
                duration(c, total)));
        box.addView(head);

        List<Playtime.Entry> rows = slotPage
                ? Playtime.forSlot(ledger, slot)
                : Playtime.bySlot(ledger);
        Map<String, ItemInfo> items = slotPage ? versionItems(api) : null;
        List<Playtime.Slice> slices = new ArrayList<Playtime.Slice>();
        for (Playtime.Entry e : rows) {
            String name = slotPage ? versionLabel(c, items, e) : e.slot;
            if (!e.patch.isEmpty()) {
                // ★ 第 388 轮：本体 + 补丁 ≠ 本体（用户：「应该算是半个改端」）——
                //   账本按补丁身份分行，行名不能再叠一句「本体+补丁」：宿主标题里已经有
                //   「原端 / 改端」类型词，把它换成「半改端」，补丁名只用短形式（去掉
                //   「(原版 + 补丁)」这类解释）。
                name = patchedLabel(c, name, e);
            } else if (slotPage) {
                name = prettyName(name);
            }
            slices.add(new Playtime.Slice(name, e.ms));
        }
        // 同一槽里「同一个游戏版本」可能有多行（已装包 + 导入的同版本 APK、换过包名等）：
        // 显示名里已含版本号，同名就合成一行（官方 / fork 同号但名字不同 ⇒ 不会误合）。
        final List<Playtime.Slice> merged = Playtime.mergeNamed(slices);
        final LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);

        final TextView more = new TextView(c);
        more.setTextSize(12f);
        more.setTextColor(th.textMuted);
        more.setPadding(0, th.dp(8), 0, 0);
        more.setVisibility(View.GONE);
        box.addView(more);

        // 版本多了页面会被顶得很高：默认只看前几个，点一下展开 / 收起。
        final boolean[] expanded = {false};
        final Runnable[] paint = new Runnable[1];
        paint[0] = new Runnable() {
            @Override public void run() {
                list.removeAllViews();
                // 默认**全折叠**：只留合计，点「展开（N 个版本）」才画版本行（版本多了页面太高）。
                int show = expanded[0] ? merged.size() : 0;
                for (int i = 0; i < show; i++) {
                    Playtime.Slice s = merged.get(i);
                    list.addView(row(c, th, s.name, duration(c, s.ms)));
                }
                if (merged.isEmpty()) {
                    more.setVisibility(View.GONE);
                    more.setOnClickListener(null);
                } else {
                    more.setVisibility(View.VISIBLE);
                    more.setText(expanded[0] ? c.getString(R.string.us_collapse)
                            : c.getString(R.string.us_more_fmt, merged.size()));
                    more.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            expanded[0] = !expanded[0];
                            paint[0].run();
                        }
                    });
                }
            }
        };
        paint[0].run();

        TextView note = new TextView(c);
        note.setTextSize(11f);
        note.setTextColor(th.textMuted);
        note.setPadding(0, th.dp(8), 0, 0);
        note.setText(c.getString(R.string.us_note));
        box.addView(note);
        return box;
    }

    // ---- 第 400 轮：插件页「游玩统计」面板 ---------------------------------------

    /** 范围预设（天）。第 401 轮加「1 天」（用户：「应该加上一天这个选项」）。 */
    private static final int[] RANGE_PRESETS = {1, 7, 30, 90, 365};

    /**
     * 插件页里的统计面板：范围 × 粒度 + 热力图 + 合计 + 颜色档位。
     *
     * <p>宿主插件页（`PluginsActivity` 直达）传 `page = "panel"` 进来；**只有图区滚动**
     * （用户 2026-11-19：「滚动区域应该只有图的部分」），头部 / 合计 / 档位固定。
     *
     * <p>范围 / 粒度不持久化：每次进页面回到「近 30 天 · 日」；颜色档位按粒度存 `data/levels.txt`。
     */
    private static View panel(PluginApi api) {
        final Context c = api.context();
        final PluginTheme th = api.theme();
        final TimeZone tz = TimeZone.getDefault();

        final LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int hpad = th.dp(14);
        box.setPadding(hpad, th.dp(4), hpad, th.dp(8));

        // 状态：默认近 30 天 / 日粒度
        final long[] from = { daysAgoStart(29, tz) };
        final long[] to = { endOfToday(tz) };
        final Heat.Grain[] grain = { Heat.Grain.DAY };
        final int[] preset = { 2 };          // RANGE_PRESETS[2] = 30 天
        final boolean[] all = { false };

        final TextView title = new TextView(c);
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(th.text);
        box.addView(title);

        // 第 401 轮：两个分组标题（用户：「上面需要加上标题（统计范围选择 / 精度选择）」）
        // 实例筛选（用户 2026-11-19：多选；「不选=全选，已选两项≠全选」⇒ 默认全选、记录排除集）：
        // 一行「全部 / 已选 N 个 ▾」按钮，点开在页面里展开勾选列表（默认全勾，点一下取消该实例）。
        final List<String[]> excluded = new ArrayList<String[]>();
        final boolean[] instOpen = { false };
        box.addView(sectionLabel(c, th, R.string.us_inst_title));
        final LinearLayout instRow = new LinearLayout(c);
        instRow.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(instRow);
        final LinearLayout instList = new LinearLayout(c);
        instList.setOrientation(LinearLayout.VERTICAL);
        box.addView(instList);

        box.addView(sectionLabel(c, th, R.string.us_range_title));
        // 范围分两行：7 个胶囊一行放不下（360dp 宽的内容区），「自定义」会被截掉
        final LinearLayout rangeRow = new LinearLayout(c);
        rangeRow.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(rangeRow);
        final LinearLayout rangeRow2 = new LinearLayout(c);
        rangeRow2.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(rangeRow2);

        box.addView(sectionLabel(c, th, R.string.us_grain_title));
        final LinearLayout grainRow = new LinearLayout(c);
        grainRow.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(grainRow);

        final LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        box.addView(body);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override public void run() {
                title.setText(c.getString(R.string.us_panel_title));
                List<Sessions.Entry> es = sessions(api);
                // 实例：默认全选（排除集为空 = 全部）；取消勾选 = 排除
                instRow.removeAllViews();
                instList.removeAllViews();
                final List<String[]> insts = instances(es);
                int selCount = Math.max(0, insts.size() - excluded.size());
                String curLabel = excluded.isEmpty()
                        ? c.getString(R.string.us_range_all)
                        : c.getString(R.string.us_inst_picked_fmt, selCount);
                instRow.addView(chip(c, th, curLabel + " ▾", true,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                instOpen[0] = !instOpen[0];
                                rebuild[0].run();
                            }
                        }), chipLp(th));
                if (instOpen[0]) {
                    for (final String[] ins : insts) {
                        final boolean on = !isExcluded(excluded, ins[0], ins[1]);
                        instList.addView(instPickRow(c, th,
                                instanceLabel(api, ins[0], ins[1]), on,
                                new View.OnClickListener() {
                                    @Override public void onClick(View v) {
                                        toggleExcluded(excluded, ins[0], ins[1]);
                                        rebuild[0].run();
                                    }
                                }));
                    }
                }
                if (!excluded.isEmpty()) {
                    List<Sessions.Entry> one = new ArrayList<Sessions.Entry>();
                    for (Sessions.Entry e : es) {
                        if (e != null && !isExcluded(excluded, e.key, e.slot)) one.add(e);
                    }
                    es = one;
                }
                rangeRow.removeAllViews();
                rangeRow2.removeAllViews();
                for (int i = 0; i < RANGE_PRESETS.length; i++) {
                    final int idx = i;
                    rangeRow.addView(chip(c, th,
                            c.getString(R.string.us_range_days_fmt, RANGE_PRESETS[i]),
                            !all[0] && preset[0] == i, new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    preset[0] = idx;
                                    all[0] = false;
                                    from[0] = daysAgoStart(RANGE_PRESETS[idx] - 1, tz);
                                    to[0] = endOfToday(tz);
                                    rebuild[0].run();
                                }
                            }), chipLp(th));
                }
                rangeRow2.addView(chip(c, th, c.getString(R.string.us_range_all), all[0],
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                List<Sessions.Entry> es = sessions(api);
                                if (es.isEmpty()) return;
                                from[0] = earliestDayStart(es, tz);
                                to[0] = endOfToday(tz);
                                all[0] = true;
                                rebuild[0].run();
                            }
                        }), chipLp(th));
                rangeRow2.addView(chip(c, th, c.getString(R.string.us_range_custom), false,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                pickCustom(api, from, to, rebuild);
                            }
                        }), chipLp(th));

                grainRow.removeAllViews();
                Heat.Grain[] gs = { Heat.Grain.HOUR, Heat.Grain.DAY,
                        Heat.Grain.WEEK, Heat.Grain.MONTH };
                for (int i = 0; i < gs.length; i++) {
                    final Heat.Grain g = gs[i];
                    grainRow.addView(chip(c, th, c.getString(gnameRes(g)), grain[0] == g,
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    grain[0] = g;
                                    rebuild[0].run();
                                }
                            }), chipLp(th));
                }

                body.removeAllViews();
                if (es.isEmpty()) {
                    body.addView(hint(c, th, R.string.us_panel_empty));
                    return;
                }
                int[] bounds = boundsFor(api, grain[0]);
                Heat.Grid grid = Heat.grid(es, System.currentTimeMillis(), tz,
                        grain[0], from[0], to[0], bounds);
                if (grid.tooBig) {
                    body.addView(hint(c, th, R.string.us_panel_too_big));
                    return;
                }
                if (grid.tooSmall) {
                    body.addView(hint(c, th, R.string.us_panel_too_small));
                    return;
                }
                if (grid.firstDay.isEmpty()) {
                    body.addView(hint(c, th, R.string.us_panel_empty));
                    return;
                }
                TextView sum = new TextView(c);
                sum.setTextSize(12f);
                sum.setTextColor(th.text);
                sum.setPadding(0, th.dp(6), 0, 0);
                sum.setText(c.getString(R.string.us_panel_total_fmt, duration(c, Heat.total(grid)))
                        + " · " + c.getString(R.string.us_heat_since_fmt, grid.firstDay));
                body.addView(sum);

                // 图区：固定高度（≤420dp）内部滚（用户 2026-11-19：「滚动区域应该只有图的部分」）。
                // ★ 用 onMeasure 封顶（onLayoutChange 的时机不稳，真机验过：高度没被收住）。
                // 列轴固定（不随图滚）；图区固定高度（≤420dp）内部滚（用户 2026-11-19）
                body.addView(new HeatAxis(c, th, grid), new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                final int maxH = th.dp(420);
                final android.widget.ScrollView sc = new android.widget.ScrollView(c) {
                    @Override protected void onMeasure(int wSpec, int hSpec) {
                        super.onMeasure(wSpec, hSpec);
                        if (getMeasuredHeight() > maxH) {
                            setMeasuredDimension(getMeasuredWidth(), maxH);
                        }
                    }
                };
                sc.setVerticalScrollBarEnabled(true);
                final HeatView hv = new HeatView(c, th, grid);
                sc.addView(hv);
                sc.setOnTouchListener(new View.OnTouchListener() {
                    @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return false;
                    }
                });
                body.addView(sc, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                sc.post(new Runnable() {
                    @Override public void run() {
                        sc.fullScroll(View.FOCUS_DOWN);
                    }
                });
                body.addView(hint(c, th, R.string.us_panel_hint));
                body.addView(levelsEditor(api, c, th, grain[0], bounds, rebuild));
            }
        };
        rebuild[0].run();
        return box;
    }

    /** 分组小标题（统计范围 / 精度）。 */
    private static TextView sectionLabel(Context c, PluginTheme th, int res) {
        TextView t = new TextView(c);
        t.setTextSize(12f);
        t.setTextColor(th.textMuted);
        t.setPadding(0, th.dp(6), 0, th.dp(2));
        t.setText(c.getString(res));
        return t;
    }

    /** 粒度名资源。 */
    private static int gnameRes(Heat.Grain g) {
        if (g == Heat.Grain.HOUR) return R.string.us_grain_hour;
        if (g == Heat.Grain.DAY) return R.string.us_grain_day;
        if (g == Heat.Grain.WEEK) return R.string.us_grain_week;
        return R.string.us_grain_month;
    }

    /**
     * 颜色档位编辑（用户 2026-11-19：「多少的定义应该也可以自定义吧」）。
     * 4 个分钟输入 = 第 2 / 3 / 4 / 5 档的下界；留空 = 默认；按粒度存 `data/levels.txt`。
     */
    private static View levelsEditor(final PluginApi api, final Context c,
                                     final PluginTheme th, final Heat.Grain g,
                                     int[] bounds, final Runnable[] rebuild) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setTextSize(12f);
        t.setTextColor(th.textMuted);
        t.setText(c.getString(R.string.us_levels_title) + " · " + c.getString(gnameRes(g)));
        box.addView(t);

        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(row);
        final android.widget.EditText[] fields = new android.widget.EditText[4];
        for (int i = 0; i < 4; i++) {
            android.widget.EditText e = new android.widget.EditText(c);
            e.setTextSize(12f);
            e.setTextColor(th.text);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            e.setSingleLine(true);
            e.setText(String.valueOf(bounds[i]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = th.dp(6);
            row.addView(e, lp);
            fields[i] = e;
        }
        View apply = chip(c, th, c.getString(R.string.us_apply), true, null);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int[] def = Heat.defaultBounds(g);
                int[] b = new int[4];
                for (int i = 0; i < 4; i++) {
                    String s = fields[i].getText() == null ? ""
                            : fields[i].getText().toString().trim();
                    if (s.isEmpty()) {
                        b[i] = def[i];
                        continue;
                    }
                    try {
                        b[i] = Integer.parseInt(s);
                    } catch (Throwable t2) {
                        b[i] = -1;
                    }
                }
                boolean ok = b[0] >= 0 && b[0] < b[1] && b[1] < b[2] && b[2] < b[3];
                if (!ok) {
                    Toast.makeText(c, c.getString(R.string.us_levels_bad),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                saveBounds(api, g, b);
                rebuild[0].run();
            }
        });
        row.addView(apply, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(hint(c, th, R.string.us_levels_hint));
        return box;
    }

    /** 实例多选行：✓ + 标签，点一下切换。 */
    private static View instPickRow(Context c, PluginTheme th, String label, boolean on,
                                    View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setTextSize(13f);
        t.setText((on ? "✓ " : "   ") + label);
        t.setTextColor(on ? th.accent : th.text);
        t.setPadding(th.dp(6), th.dp(7), th.dp(6), th.dp(7));
        t.setClickable(true);
        t.setFocusable(false);
        t.setOnClickListener(l);
        return t;
    }

    static boolean isExcluded(List<String[]> excluded, String key, String slot) {
        if (excluded == null) return false;
        for (String[] p : excluded) {
            if (p[0].equals(key) && p[1].equals(slot)) return true;
        }
        return false;
    }

    static void toggleExcluded(List<String[]> excluded, String key, String slot) {
        if (excluded == null) return;
        for (int i = 0; i < excluded.size(); i++) {
            if (excluded.get(i)[0].equals(key) && excluded.get(i)[1].equals(slot)) {
                excluded.remove(i);
                return;
            }
        }
        excluded.add(new String[]{key, slot});
    }

    /** distinct (key, slot)，按出现顺序；key = 版本 key，slot = 槽。 */
    static List<String[]> instances(List<Sessions.Entry> es) {
        List<String[]> out = new ArrayList<String[]>();
        if (es == null) return out;
        for (Sessions.Entry e : es) {
            if (e == null || e.key.isEmpty() || e.slot.isEmpty()) continue;
            boolean seen = false;
            for (String[] o : out) {
                if (o[0].equals(e.key) && o[1].equals(e.slot)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) out.add(new String[]{e.key, e.slot});
        }
        return out;
    }

    /** 实例标签：版本显示名 + 槽；反查不到版本名就只给 key。 */
    static String instanceLabel(PluginApi api, String key, String slot) {
        String name = key;
        try {
            for (ItemInfo it : api.versions()) {
                if (it == null || it.extra == null) continue;
                if (!key.equals(it.extra.get("key"))) continue;
                name = it.title;
                String dv = it.extra.get("displayVersion");
                // title 里常已带版本号（如「滚木 原端 159.7」）⇒ 只在它不含时才补，避免「159.7 159.7」
                if (dv != null && !dv.isEmpty() && !name.contains(dv)) name = name + " " + dv;
                break;
            }
        } catch (Throwable ignored) {
            // 版本表读不到就用 key
        }
        return name + " · " + slot;
    }

    /** 读某粒度的颜色档位（`data/levels.txt`，`GRAIN:a,b,c,d` 一行；坏行 / 缺失 ⇒ 默认）。 */
    static int[] boundsFor(PluginApi api, Heat.Grain g) {
        int[] def = Heat.defaultBounds(g);
        File dir = api == null ? null : api.dataDir();
        if (dir == null) return def;
        String txt = readText(new File(dir, "levels.txt"), 4096);
        for (String line : txt.split("\n")) {
            String s = line.trim();
            int p = s.indexOf(':');
            if (p <= 0 || !s.substring(0, p).equals(g.name())) continue;
            String[] vs = s.substring(p + 1).split(",");
            if (vs.length != 4) continue;
            try {
                int[] b = new int[4];
                for (int i = 0; i < 4; i++) b[i] = Integer.parseInt(vs[i].trim());
                if (b[0] >= 0 && b[0] < b[1] && b[1] < b[2] && b[2] < b[3]) return b;
            } catch (Throwable ignored) {
                // 坏行忽略，继续找
            }
        }
        return def;
    }

    /** 写某粒度的颜色档位（保留其它粒度的行）。 */
    static void saveBounds(PluginApi api, Heat.Grain g, int[] b) {
        File dir = api == null ? null : api.dataDir();
        if (dir == null || b == null || b.length != 4) return;
        File f = new File(dir, "levels.txt");
        String txt = readText(f, 4096);
        StringBuilder sb = new StringBuilder();
        for (String line : txt.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            int p = s.indexOf(':');
            if (p > 0 && s.substring(0, p).equals(g.name())) continue;
            sb.append(s).append('\n');
        }
        sb.append(g.name()).append(':').append(b[0]).append(',').append(b[1])
                .append(',').append(b[2]).append(',').append(b[3]).append('\n');
        writeAtomic(f, sb.toString());
    }

    /** 读每局流水（宿主不解析；插件自己的数据）。 */
    private static List<Sessions.Entry> sessions(PluginApi api) {
        File dir = api == null ? null : api.dataDir();
        if (dir == null) return new ArrayList<Sessions.Entry>();
        return Sessions.parse(readText(new File(dir, "sessions.tsv"), 1024 * 1024));
    }

    /** 今天往前 N 天的本地 0 点。 */
    private static long daysAgoStart(int daysAgo, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(System.currentTimeMillis());
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -Math.max(0, daysAgo));
        return c.getTimeInMillis();
    }

    /** 今天的 23:59:59.999。 */
    private static long endOfToday(TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(System.currentTimeMillis());
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis() - 1L;
    }

    /** 最早一条流水的当天 0 点（「全部」范围的起点）。 */
    private static long earliestDayStart(List<Sessions.Entry> es, TimeZone tz) {
        long min = Long.MAX_VALUE;
        for (Sessions.Entry e : es) {
            if (e != null && e.started > 0L && e.started < min) min = e.started;
        }
        if (min == Long.MAX_VALUE) return daysAgoStart(29, tz);
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(min);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 一个范围 / 粒度小胶囊（选中 = 淡 accent 底 + accent 字）。 */
    private static View chip(Context c, PluginTheme th, String text, boolean selected,
                             View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12f);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        int h = th.dp(10);
        int v = th.dp(4);
        t.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(th.dp(20));
        if (selected) {
            bg.setColor(Color.argb(36, Color.red(th.accent), Color.green(th.accent),
                    Color.blue(th.accent)));
            t.setTextColor(th.accent);
        } else {
            bg.setColor(Color.TRANSPARENT);
            t.setTextColor(th.textMuted);
        }
        t.setBackground(bg);
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    private static LinearLayout.LayoutParams chipLp(PluginTheme th) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = th.dp(6);
        return lp;
    }

    private static TextView hint(Context c, PluginTheme th, int res) {
        TextView t = new TextView(c);
        t.setTextSize(12f);
        t.setTextColor(th.textMuted);
        t.setPadding(0, th.dp(8), 0, 0);
        t.setText(c.getString(res));
        return t;
    }

    /**
     * 自定义范围：**宿主代弹**日期选择器（v47）。
     *
     * ★ 插件自己 `new DatePickerDialog` 会崩：插件 `Resources` 里没有宿主主题，系统布局
     * inflate 时 `Failed to resolve attribute` → `InflateException` → 整个应用崩溃（真机踩过）。
     */
    private static void pickCustom(final PluginApi api, final long[] from, final long[] to,
                                   final Runnable[] rebuild) {
        final Context c = api.context();
        api.pickDate(c.getString(R.string.us_pick_from), from[0], new PluginApi.DatePick() {
            @Override public void onDate(final long first) {
                api.pickDate(c.getString(R.string.us_pick_to), to[0], new PluginApi.DatePick() {
                    @Override public void onDate(long second) {
                        long nt = endOfDay(second, TimeZone.getDefault());
                        if (nt < first) {
                            Toast.makeText(c, c.getString(R.string.us_range_bad),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        from[0] = first;
                        to[0] = nt;
                        rebuild[0].run();
                    }
                });
            }
        });
    }

    /** 某天 0 点 ⇒ 当天 23:59:59.999。 */
    private static long endOfDay(long dayStart, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(dayStart);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis() - 1L;
    }

    /** 名字 + 时长（时长靠右；名字让位，别把时长挤没）。 */
    private static View row(Context c, PluginTheme th, String name, String value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, th.dp(7), 0, 0);

        TextView left = new TextView(c);
        left.setTextSize(13f);
        left.setTextColor(th.text);
        left.setSingleLine(true);
        left.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(left, lp);
        left.setText(name);

        TextView right = new TextView(c);
        right.setTextSize(13f);
        right.setTextColor(th.textMuted);
        right.setSingleLine(true);
        right.setPadding(th.dp(12), 0, 0, 0);
        row.addView(right);
        right.setText(value);
        return row;
    }

    /**
     * 统一行名分隔符：宿主标题是「名称  原端  版本」两个空格，这里换成「名称 · 原端 · 版本」。
     * 所有行共用，避免标题用空格、补丁后缀用 `·` 的混用。
     */
    private static String prettyName(String name) {
        return joinParts(splitName(name));
    }

    private static String[] splitName(String name) {
        String s = name == null ? "" : name.trim();
        return s.isEmpty() ? new String[0] : s.split(" {2,}");
    }

    private static String joinParts(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    /**
     * 带补丁行的显示名：把标题里的「原端 / 改端」换成「半改端」，再缀一个**短**补丁名。
     *
     * <p>为什么不直接 `name + " · 本体+补丁（...）"`：宿主标题已经写了类型，再叠一句
     * 「本体+补丁」既重复又长（真机被 `ellipsize=END` 截断）；这里只留「半改端 + 短补丁名」。
     */
    private static String patchedLabel(Context c, String name, Playtime.Entry e) {
        String half = c.getString(R.string.us_patch_half);
        String[] parts = splitName(name);
        String base;
        if (parts.length >= 3) {
            parts[1] = half;
            base = joinParts(parts);
        } else if (parts.length == 2) {
            base = parts[0] + " · " + half + " · " + parts[1];
        } else if (parts.length == 1) {
            base = parts[0] + " · " + half;
        } else {
            base = half;
        }
        int count = patchCount(e.patch);
        String what = shortPatch(c, e, count);
        if (what.isEmpty()) return base;
        return c.getString(R.string.us_patch_row_fmt, base, what);
    }

    private static int patchCount(String refs) {
        if (refs == null || refs.trim().isEmpty()) return 0;
        int n = 0;
        for (String s : refs.split(",")) if (!s.trim().isEmpty()) n++;
        return n;
    }

    private static String shortPatch(Context c, Playtime.Entry e, int count) {
        // 补丁的名字用 **ref 的 id**（`mdtx-1607`）—— manifest 的 name 是
        // 「MindustryX (原版 + 补丁)」，那是游戏/改端的描述，不是补丁名；
        // 直接截成 MindustryX 会被误认成"补丁叫 MindustryX"。
        String id = refId(firstRef(e.patch));
        if (count > 1) {
            return id.isEmpty() ? "" : c.getString(R.string.us_patch_more_fmt, id, count - 1);
        }
        if (!id.isEmpty()) return id;
        String label = e.patchLabel == null ? "" : e.patchLabel.trim();
        return label.replaceAll("\\s*[\\(（][^()（）]*[\\)）]", "").trim();
    }

    private static String firstRef(String refs) {
        if (refs == null) return "";
        for (String s : refs.split(",")) if (!s.trim().isEmpty()) return s.trim();
        return "";
    }

    private static String refId(String ref) {
        int at = ref == null ? -1 : ref.indexOf('@');
        return at > 0 ? ref.substring(0, at) : (ref == null ? "" : ref);
    }

    /** 版本 key → 宿主当前的版本条目（含自定义名 / 版本号）。 */
    private static Map<String, ItemInfo> versionItems(PluginApi api) {
        Map<String, ItemInfo> out = new HashMap<String, ItemInfo>();
        try {
            for (ItemInfo it : api.versions()) {
                if (it == null) continue;
                String key = it.extra == null ? null : it.extra.get("key");
                if (key == null || key.isEmpty() || out.containsKey(key)) continue;
                out.put(key, it);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 一行的显示名：**当前版本**用现查的显示名；**老版本行**用账本里的快照。
     *
     * <p>已装游戏原地更新后，同一个 key 会有两行（旧版本 / 新版本）—— 只有 tag 与当前
     * displayVersion 相同（或旧数据没有 tag）时才用现查的 title，否则用写账本时记下的 title，
     * 这样"更新前"那一行不会顶着新版本的名字。再退一步：tag → key / 文件名。
     */
    private static String versionLabel(Context c, Map<String, ItemInfo> items, Playtime.Entry e) {
        String key = e.key;
        if (key == null || key.isEmpty() || Playtime.UNKNOWN_VERSION.equals(key)) {
            return c.getString(R.string.us_unknown_version);
        }
        ItemInfo it = items == null ? null : items.get(key);
        if (it != null && (e.tag.isEmpty() || e.tag.equals(tagOf(it)))) {
            if (it.title != null && !it.title.trim().isEmpty()) return it.title;
        }
        if (e.title != null && !e.title.trim().isEmpty()) return e.title.trim();
        if (!e.tag.isEmpty()) return e.tag;
        return key.startsWith("import:") ? key.substring("import:".length()) : key;
    }

    /** 宿主当前条目的版本号（拿不到 ⇒ 空；旧宿主没有这个 extra）。 */
    private static String tagOf(ItemInfo it) {
        String t = it == null || it.extra == null ? null : it.extra.get("displayVersion");
        return t == null ? "" : t;
    }

    private static String duration(Context c, long ms) {
        int[] hm = Playtime.hm(ms);
        if (hm[0] > 0) return c.getString(R.string.us_hm_fmt, hm[0], hm[1]);
        if (hm[1] > 0) return c.getString(R.string.us_min_fmt, hm[1]);
        return c.getString(R.string.us_sec);
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
                if (bos.size() > 256 * 1024) break;
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

    /** 读文件（超过 max 字节只读尾部，并丢掉可能被截断的第一行）。 */
    private static String readText(File f, int max) {
        if (f == null || !f.isFile()) return "";
        long len = f.length();
        boolean cut = len > max;
        long skip = cut ? len - max : 0L;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            while (skip > 0L) {
                long n = in.skip(skip);
                if (n <= 0L) break;
                skip -= n;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String s = new String(bos.toByteArray(), "UTF-8");
            if (cut) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
            }
            return s;
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 2026-11-17：`game-exit` 事件（宿主时钟算好的一局）⇒ 追加进账本。
     *
     * <p>取数：`activeMs`（本局前台累计）+ `key`（版本身份）；版本号 / 显示名 / 原始版本名
     * 从宿主 {@link PluginApi#versions()} 的 extra 取 —— 与旧探针写账本时的快照同源。
     * 没有 `activeMs`（启动即崩 / 宿主没量到）⇒ 什么都不做，别记一笔 0。
     *
     * <p>⚠️ 一个 `game-exit` 只加一次：宿主在派发后就把这局从 `sSessions` / `event-pending`
     * 里清掉了；这里不再做去重（重复派发 = 宿主 bug）。
     */
    public static void onEvent(PluginApi api, String event, Map<String, String> data) {
        if (api == null || data == null || !"game-exit".equals(event)) return;
        long active = parseLong(data.get("activeMs"));
        if (active <= 0L) return;
        String slot = clean(data.get("slot"));
        if (slot.isEmpty()) slot = clean(api.slot());
        String key = clean(data.get("key"));
        File dir = api.dataDir();
        if (slot.isEmpty() || key.isEmpty() || dir == null) return;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            String ledger = read(new File(dir, "ledger.tsv"));
            Playtime.Snap snap = snapOf(api, key);
            String patch = clean(data.get("patch"));
            String patchLabel = clean(data.get("patchLabel"));
            if (!patch.isEmpty()) {
                snap = new Playtime.Snap(snap.tag, snap.title, snap.rawName, snap.upstream,
                        patch, patchLabel);
            }
            long base = Playtime.valueFor(ledger, slot, key, snap.tag, snap.patch);
            String next = Playtime.put(ledger, slot, key, snap, base + active,
                    System.currentTimeMillis());
            if (next.equals(ledger)) return;       // 没写进去（坏 key / 空槽）⇒ 别动文件
            writeAtomic(new File(dir, "ledger.tsv"), next);
            writeAtomic(new File(dir, "total.txt"), String.valueOf(Playtime.total(next)));
            // 第 396 轮：每局流水（「哪一天」+ 具体时刻；按天 / 单日分析从它现算）。
            // 单独 try：流水写失败不许影响账本 —— 时长总数是主数据，流水是分析用的副本。
            try {
                appendSession(dir, data, slot, key, snap, active);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            // 插件写失败不许影响宿主 / 游戏：宿主那边也只记日志
        }
    }

    /**
     * 第 396 轮：把这一局追加进流水（`sessions.tsv`，一行一局）。
     *
     * <p>为什么记「一局」而不是「一天一行」：日 / 小时桶都能从起止时刻现算，反过来不行 ——
     * 只记「每天一把毫秒」就再也算不出单日几点在玩。文件每局一行，一年也就几十 KB。
     *
     * <p>去重：**最后一行的核心字段与本次完全一致** ⇒ 视为宿主重复派发，跳过（不记两次）。
     *
     * @param active 这一局的前台毫秒（宿主时钟给的）
     */
    private static void appendSession(File dir, Map<String, String> data, String slot,
                                      String key, Playtime.Snap snap, long active) {
        try {
            long started = parseLong(data.get("started"));
            if (started <= 0L) return;            // 没有开始时刻 ⇒ 归不到「哪一天」，别编一个
            long ended = parseLong(data.get("ended"));
            if (ended < started) ended = started;
            Sessions.Entry e = new Sessions.Entry(started, ended, active, ended - started,
                    clean(data.get("exit")), slot, key, snap.tag, snap.patch);
            File f = new File(dir, "sessions.tsv");
            String last = lastLine(f);
            if (last != null) {
                List<Sessions.Entry> one = Sessions.parse(last);
                if (one.size() == 1 && Sessions.sameRun(one.get(0), e)) return;
            }
            FileOutputStream os = new FileOutputStream(f, true);
            try {
                os.write(Sessions.format(e).getBytes("UTF-8"));
                os.flush();
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            // 流水是分析用的副本：写失败不影响账本 / 游戏
        }
    }

    /** 读文件最后一行（只读尾部 1 KB；文件没有结尾换行也认）。没有 ⇒ null。 */
    private static String lastLine(File f) {
        if (f == null || !f.isFile()) return null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            if (len <= 0L) return null;
            int take = (int) Math.min(len, 1024L);
            raf.seek(len - take);
            byte[] buf = new byte[take];
            int n = 0;
            while (n < take) {
                int r = raf.read(buf, n, take - n);
                if (r < 0) break;
                n += r;
            }
            String s = new String(buf, 0, n, "UTF-8");
            while (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
            int nl = s.lastIndexOf('\n');
            return nl < 0 ? s : s.substring(nl + 1);
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 版本 key → 账本行要的快照（宿主 `versions()` 的 extra；找不到就只写 key）。 */
    private static Playtime.Snap snapOf(PluginApi api, String key) {
        try {
            for (ItemInfo it : api.versions()) {
                if (it == null || it.extra == null) continue;
                if (!key.equals(it.extra.get("key"))) continue;
                return new Playtime.Snap(it.extra.get("displayVersion"), it.title,
                        it.extra.get("versionName"), it.extra.get("upstreamVersion"));
            }
        } catch (Throwable ignored) {
        }
        return new Playtime.Snap("", "", "", "");
    }

    /** 临时文件 + 改名（读到的永远是完整文件；与旧探针同一套）。 */
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

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private UsageStats() {}
}
