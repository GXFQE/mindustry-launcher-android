package io.mdt.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/**
 * **有毒的同名类**（第 138 轮的验收夹具，只住在夹具里、绝不进产品）。
 *
 * <p>它的 FQN 与宿主真正的 `io.mdt.launcher.LogActivity` **逐字相同**。宿主 manifest 里那个
 * 运行日志页就是按这个名字找类的 ⇒ 只要插件 dex 排在宿主自己那份 dex **之前**，
 * 系统实例化的就是**这一个**（"插件顶掉宿主逻辑"的可复现形态）。
 * 修完 B（插件排宿主之后）之后，这个类永远轮不到 ⇒ 页面还是宿主自己那些日志。
 */
public class LogActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("POISON: plugin provided this page");
        t.setTextSize(20f);
        t.setPadding(48, 96, 48, 48);
        setContentView(t);
    }
}