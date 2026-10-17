package io.mdt.shareprobe;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * 收到一次 {@code ACTION_SEND}，把**元数据 + 字节**都记下来（第 155 轮分享出口的第三方尺子）。
 *
 * <p>为什么要单独一个 APK：微信/QQ 那条路自动化不了（微信有应用锁），而"另一个 UID 能不能
 * 通过我们给的授权读走字节"正是这条功能唯一的未知数。本夹具用 debug 密钥签、装成独立包，
 * 收到的内容落到自己的外部目录 —— `adb shell` 能直接拉回来比对 sha256。</p>
 */
public class ProbeActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        Uri u = i == null ? null : (Uri) i.getParcelableExtra(Intent.EXTRA_STREAM);
        StringBuilder sb = new StringBuilder();
        sb.append("uri=").append(u).append('\n');
        String name = "?";
        String size = "?";
        try {
            Cursor c = getContentResolver().query(u, null, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    name = ni >= 0 ? c.getString(ni) : "?";
                    size = si >= 0 ? String.valueOf(c.getLong(si)) : "?";
                }
                c.close();
            }
            sb.append("display=").append(name).append('\n');
            sb.append("size=").append(size).append('\n');
        } catch (Throwable t) {
            sb.append("QUERY_FAIL=").append(t).append('\n');
        }

        long n = -1;
        String sha = "?";
        try {
            InputStream in = getContentResolver().openInputStream(u);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            File out = new File(getExternalFilesDir(null), "received.bin");
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int r;
            n = 0;
            while ((r = in.read(buf)) > 0) {
                fo.write(buf, 0, r);
                md.update(buf, 0, r);
                n += r;
            }
            fo.close();
            in.close();
            StringBuilder h = new StringBuilder();
            for (byte x : md.digest()) h.append(String.format("%02x", x & 0xff));
            sha = h.toString();
            sb.append("bytes=").append(n).append('\n');
            sb.append("sha256=").append(sha).append('\n');
        } catch (Throwable t) {
            sb.append("READ_FAIL=").append(t).append('\n');
        }

        try {
            sb.append("type=").append(getContentResolver().getType(u)).append('\n');
        } catch (Throwable t) {
            sb.append("TYPE_FAIL=").append(t).append('\n');
        }

        try {
            File f = new File(getExternalFilesDir(null), "result.txt");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(sb.toString().getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) {
        }

        Toast.makeText(this, "probe: " + name + " / " + n + " B", Toast.LENGTH_LONG).show();
        finish();
    }
}
