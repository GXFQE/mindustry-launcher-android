package io.mdt.launcher.api;

import android.content.Context;

import java.io.File;

/**
 * 夹具用的 **PluginApi 桩**（第 159 轮 v3；第 160 轮 v4）。
 *
 * <p>⚠️ 这是"给插件编译用"的副本：真正跑起来时，同名类由**宿主**提供（插件 dex 排在宿主之后）
 * ⇒ 两边方法签名必须逐字相同。改了宿主那份，这里也要跟着改（否则插件编译得过、运行期
 * {@code AbstractMethodError} / {@code NoSuchMethodError}）。
 *
 * <p>★ 字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public interface PluginApi {

    int hostApi();

    Context context();

    String page();

    String slot();

    File selectedFile();

    File slotDir();

    File savesDir();

    File modsDir();

    File mapsDir();

    File blueprintsDir();

    void toast(String message);

    void log(String message);
}
