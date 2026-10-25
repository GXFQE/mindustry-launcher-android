package io.mdt.plugins.usagestats;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import io.mdt.launcher.api.PluginApi;
import io.mdt.plugins.usagestats.core.Playtime;

/**
 * 使用时长页（官方插件，v29）。
 *
 * <p>数据全来自**本插件自己的探针**：每个游戏会话一个目录
 * `usage/telemetry/<会话>/usagestats/playtime.txt`。页面遍历求和 —— 宿主不参与。
 */
public final class UsageStats {

    public static int api() {
        return 29;
    }

    public static View createView(PluginApi api) {
        Context c = api.context();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * c.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(c);
        title.setTextSize(16);
        title.setText(c.getString(R.string.us_title));
        box.addView(title);

        TextView body = new TextView(c);
        body.setTextSize(14);
        body.setPadding(0, pad / 2, 0, pad / 2);
        body.setText(summary(c, api));
        box.addView(body);

        TextView note = new TextView(c);
        note.setTextSize(12);
        note.setText(c.getString(R.string.us_note));
        box.addView(note);
        return box;
    }

    private static String summary(Context c, PluginApi api) {
        List<String> texts = new ArrayList<String>();
        long latest = 0;
        long latestStamp = -1;
        File[] sessions = new File(api.usageDir(), "telemetry").listFiles();
        if (sessions != null) {
            for (File s : sessions) {
                File f = new File(s, "usagestats/playtime.txt");
                if (!f.isFile()) continue;
                String t = read(f);
                if (t == null) continue;
                texts.add(t);
                long stamp = Playtime.value(t, "updated");
                if (stamp > latestStamp) {
                    latestStamp = stamp;
                    latest = Playtime.value(t, "total");
                }
            }
        }
        long total = Playtime.sumTotals(texts);
        if (total <= 0) return c.getString(R.string.us_none);
        return c.getString(R.string.us_body_fmt, label(c, total), label(c, latest));
    }

    private static String label(Context c, long ms) {
        if (ms <= 0) return c.getString(R.string.us_sec);
        int[] hm = Playtime.hm(ms);
        if (hm[0] > 0) return c.getString(R.string.us_hm_fmt, hm[0], hm[1]);
        if (hm[1] > 0) return c.getString(R.string.us_min_fmt, hm[1]);
        return c.getString(R.string.us_sec);
    }

    private static String read(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private UsageStats() {}
}
