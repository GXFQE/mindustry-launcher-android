package io.mdt.launcher;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 「关于」二级页（第 175 轮）。
 *
 * <p>设置页底部原来直接摊着版本 / 包名 / 数据路径，用户 2026-10-19：
 * 「感觉把启动器信息直接放在设置下面不太合适，单独开个二级页面，还可以加些比如开源地址之类的」。
 * ⇒ 搬成独立页，并补上 GitHub 仓库 / Issues / GPL-3.0 三个外链。
 *
 * <p>页面只读：不写配置、不碰槽、不碰游戏数据。外链走系统浏览器；没有处理器就 Toast，
 * 不让 ACTION_VIEW 的 ActivityNotFoundException 冒到用户面前。
 */
public final class AboutActivity extends BaseActivity {

    private static final String TAG = "MDTLauncher";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        Util.applySystemInsets(findViewById(R.id.root));
        setTitle(Trans.get(this, R.string.about_card_title));

        android.view.View root = findViewById(R.id.root);
        Trans.bind(root, R.id.about_app_name, R.string.about_app_name);
        Trans.bind(root, R.id.about_section_links, R.string.about_section_links);
        Trans.bind(root, R.id.about_section_data, R.string.about_section_data);
        Trans.bind(root, R.id.about_footer, R.string.about_footer);

        String verName = "?";
        int verCode = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            verName = pi.versionName;
            verCode = pi.versionCode;
        } catch (Exception e) {
            android.util.Log.w(TAG, "read PackageInfo failed: " + e);
        }
        ((TextView) findViewById(R.id.about_summary)).setText(Trans.get(this,
                R.string.about_summary_fmt, verName, verCode, getPackageName()));

        File dataRoot = Data.dataRoot(this);
        ((TextView) findViewById(R.id.about_data)).setText(Trans.get(this, R.string.about_data_fmt,
                dataRoot == null ? "?" : dataRoot.getAbsolutePath(),
                Data.hubDir(this).getAbsolutePath()));

        Util.bindAction(findViewById(R.id.root), R.id.row_about_repo, R.drawable.ic_link,
                R.string.about_repo_title, R.string.about_repo_sub, new Runnable() {
                    @Override public void run() { openUrl(R.string.about_repo_url); }
                });
        Util.bindAction(findViewById(R.id.root), R.id.row_about_issues, R.drawable.ic_link,
                R.string.about_issues_title, R.string.about_issues_sub, new Runnable() {
                    @Override public void run() { openUrl(R.string.about_issues_url); }
                });
        Util.bindAction(findViewById(R.id.root), R.id.row_about_license, R.drawable.ic_link,
                R.string.about_license_title, R.string.about_license_sub, new Runnable() {
                    @Override public void run() { openUrl(R.string.about_license_url); }
                });
    }

    private void openUrl(int resId) {
        String url = Trans.get(this, resId);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "open link failed: " + url, t);
            Toast.makeText(this, R.string.about_link_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
