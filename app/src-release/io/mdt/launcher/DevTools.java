package io.mdt.launcher;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 产品版的【dev 直通口空壳】—— 真实现整块在 {@code app/src-dev/io/mdt/launcher/DevTools.java}
 * （**约 1200 行，只在 debuggable 构建里编进包**）。
 *
 * <p>行为对齐：原来 {@code MainActivity} 里那道门禁是「`FLAG_DEBUGGABLE` 没置位 + intent 带了
 * {@code dev_*} 键 ⇒ 弹一句『开发直通口在产品版不可用』并停手」。产品版按定义就是不可调试的，
 * 所以这里只保留"看到 {@code dev_} 前缀就弹那一句"这一段，其余什么都不做。
 *
 * 🔴 与 SelfTest 的桩同一条纪律：**这里不许长逻辑**，且它的公开面改了要**两种构建都过**
 * （见 {@code docs/DEVELOPING.md}「dev 源集」）。
 */
final class DevTools {

    private DevTools() {}

    static void check(MainActivity a, Intent intent) {
        Bundle ex = intent == null ? null : intent.getExtras();
        if (ex == null) return;
        for (String k : ex.keySet()) {
            if (k != null && k.startsWith("dev_")) {
                Toast.makeText(a, R.string.dev_blocked_toast, Toast.LENGTH_SHORT).show();
                return;
            }
        }
    }
}
