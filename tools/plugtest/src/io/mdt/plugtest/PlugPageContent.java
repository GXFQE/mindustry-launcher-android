package io.mdt.plugtest;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import io.mdt.launcher.api.ItemInfo;
import io.mdt.launcher.api.PluginApi;
import io.mdt.launcher.api.SlotInfo;

/**
 * 第 159~162 轮的**页面动作**夹具：演示 {@code createView(PluginApi)}、{@code content} 插槽
 * 与只读数据面（槽 / 四类内容 / 设置 / 日志 / 中转站 / 版本 / 页面快照 / open）。
 *
 * <p>壳只按名字反射调用（{@code api()} + {@code createView(PluginApi)}），编译期不依赖它。
 * 页面把上下文、各类条目数与第一条的路径打印出来 —— 真机上一眼就能看出"宿主传对了没有"。
 *
 * <p>★ 字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public final class PlugPage {

    /** 插件接口版本：v6 只读数据面。 */
    public static int api() {
        return __API__;
    }

    /** 宿主优先调这一个（比 createView(Context) 更具体）。 */
    public static View createView(PluginApi api) {
        Context c = api.context();
        File selected = api.selectedFile();
        String sel = selected == null ? "null"
                : (selected.getName() + " @ " + selected.getAbsolutePath());
        List<SlotInfo> slots = api.slots();
        List<ItemInfo> saves = api.contents(ItemInfo.KIND_SAVES);
        List<ItemInfo> maps = api.contents(ItemInfo.KIND_MAPS);
        List<ItemInfo> mods = api.contents(ItemInfo.KIND_MODS);
        List<ItemInfo> bps = api.contents(ItemInfo.KIND_BLUEPRINTS);
        List<ItemInfo> page = api.pageItems();
        List<ItemInfo> selItems = api.selectedItems();
        Map<String, String> settings = api.settings();
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

        TextView title = new TextView(c);
        title.setText("content action fixture");
        title.setTextSize(20f);
        box.addView(title);

        TextView info = new TextView(c);
        info.setTextSize(13f);
        info.setText("hostApi=" + api.hostApi()
                + "\npage=" + api.page()
                + "\nslot=" + api.slot()
                + "\nselectedFile=" + sel
                + "\nslots=" + slots.size() + " pageItems=" + page.size()
                + " selectedItems=" + selItems.size()
                + "\nsaves=" + saves.size() + " maps=" + maps.size()
                + " mods=" + mods.size() + " blueprints=" + bps.size()
                + "\nsettings=" + settings.size() + " logs=" + logs.size()
                + " trash=" + trash.size() + " versions=" + vers.size()
                + "\nfirstMap=" + first(maps)
                + "\nopen=" + open + " openLog=" + openLog
                + "\nslotDir=" + api.slotDir().getAbsolutePath());
        box.addView(info);

        final Context toastCtx = c;
        TextView btn = new TextView(c);
        btn.setText("tap to toast (api)");
        btn.setTextSize(16f);
        btn.setPadding(0, 24, 0, 0);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.widget.Toast.makeText(toastCtx, "toast via PluginApi",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(btn);
        return box;
    }

    private static String first(List<ItemInfo> list) {
        if (list == null || list.isEmpty()) return "none";
        ItemInfo i = list.get(0);
        if (i.path != null) return i.title + " @ " + i.path;
        if (i.container != null) return i.title + " @ " + i.container + "!/" + i.entry;
        return i.title;
    }
}
