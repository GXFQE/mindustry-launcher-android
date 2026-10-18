package io.mdt.plugtest;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.mdt.launcher.api.PluginApi;

/**
 * 第 159 轮的**内容页动作**夹具：演示 {@code createView(PluginApi)} 与 {@code content} 插槽。
 *
 * <p>壳只按名字反射调用（{@code api()} + {@code createView(PluginApi)}），编译期不依赖它。
 * 页面把 {@code page / slot / 各目录} 打印出来 —— 真机上一眼就能看出"宿主传对了没有"。
 *
 * <p>★ 字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public final class PlugPage {

    /** 插件接口版本：用 PluginApi + content 插槽 ⇒ 报 3。 */
    public static int api() {
        return __API__;
    }

    /** 宿主优先调这一个（比 createView(Context) 更具体）。 */
    public static View createView(PluginApi api) {
        Context c = api.context();
        api.log("content fixture opened: page=" + api.page() + " slot=" + api.slot());
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 32, 32, 32);

        TextView title = new TextView(c);
        title.setText("content action fixture");
        title.setTextSize(20f);
        box.addView(title);

        TextView info = new TextView(c);
        info.setTextSize(14f);
        info.setText("hostApi=" + api.hostApi()
                + "\npage=" + api.page()
                + "\nslot=" + api.slot()
                + "\nslotDir=" + api.slotDir().getAbsolutePath()
                + "\nsavesDir=" + api.savesDir().getAbsolutePath()
                + "\nmodsDir=" + api.modsDir().getAbsolutePath());
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
}
