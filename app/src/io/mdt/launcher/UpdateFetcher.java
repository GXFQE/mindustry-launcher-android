package io.mdt.launcher;

import android.content.Context;

import java.io.File;

/**
 * 更新链路的**编排**：拉清单 → 判 URL → 下载 → 校 sha256 → 校证书。
 *
 * <p>它是设备侧唯一需要 {@code Context} 的一层（取存储目录 + 取本应用签名），
 * 所以**故意做得极薄**：每一段都调一个能单独验的纯逻辑部件
 * （{@link UpdateClient} / {@link UpdateManifest} / {@link UpdateDownload}），
 * 那些部件在 PC 上就能跑（见 `docs/plans/2026-10-29-更新器设计.md` §11）。
 *
 * <p>这道门之后才是 {@code PackageInstaller}（第 4 轮）——**证书和 sha256 都过不了的东西，
 * 一个字节都不许进安装会话**。
 */
final class UpdateFetcher {

    /** 卡在哪一步（给报告/文案分岔用；机器码不上屏）。 */
    static final String S_NETWORK = "network";
    static final String S_PARSE = "parse";
    static final String S_URL = "url";
    static final String S_SIZE = "size";
    static final String S_SHA = "sha";
    static final String S_SIGN = "sign";
    static final String S_IO = "io";
    static final String S_OK = "ok";

    static final class Outcome {
        final String step;
        /** 原始诊断（异常原文 / 两个指纹）；成功时为 null。 */
        final String detail;
        final UpdateManifest manifest;
        /** 成功时 = 两道校验都过的那份 APK；其余 null。 */
        final File apk;
        final long ms;

        Outcome(String step, String detail, UpdateManifest manifest, File apk, long ms) {
            this.step = step;
            this.detail = detail;
            this.manifest = manifest;
            this.apk = apk;
            this.ms = ms;
        }

        boolean ok() { return S_OK.equals(step); }
    }

    private UpdateFetcher() {}

    /** 更新包的落点（外部 hub —— 不参与 dex 加载，不需要内部存储）。 */
    static File dir(Context ctx) {
        return new File(Data.hubDir(ctx), "update");
    }

    /**
     * 跑完整条链路。**不装包、不弹窗、不碰 UI** —— 调用方负责报告与文案。
     *
     * @param manifestUrl `update.json` 的地址（dev 夹具里是 `http://127.0.0.1:8080/...`）
     */
    static Outcome run(Context ctx, String manifestUrl) {
        long t0 = System.currentTimeMillis();

        String json;
        try {
            json = UpdateClient.fetchText(manifestUrl, UpdateManifest.MAX_BYTES);
        } catch (Throwable t) {
            return new Outcome(S_NETWORK, String.valueOf(t), null, null,
                    System.currentTimeMillis() - t0);
        }

        UpdateManifest m = UpdateManifest.parse(json);
        if (!m.ok()) {
            return new Outcome(S_PARSE, m.error, m, null, System.currentTimeMillis() - t0);
        }
        if (!UpdateManifest.allowedUrl(m.url)) {
            return new Outcome(S_URL, String.valueOf(m.url), m, null,
                    System.currentTimeMillis() - t0);
        }

        UpdateDownload.Result d = UpdateDownload.run(
                m.url, dir(ctx), m.versionCode, m.size, m.sha256, null);
        if (d == null || !d.status.ok()) {
            String step = d == null ? S_IO : d.status.name().toLowerCase(java.util.Locale.US);
            return new Outcome(step, d == null ? "null result" : d.detail, m, null,
                    System.currentTimeMillis() - t0);
        }

        // 第二道：证书。sha256 只能证明"没被改坏"，证明不了"是谁签的"。
        String self = PluginTrust.selfFingerprint(ctx);
        String got = PluginTrust.apkFingerprint(ctx, d.file);
        if (self == null || got == null || !self.equals(got)) {
            d.file.delete();
            return new Outcome(S_SIGN, "self=" + self + " got=" + got, m, null,
                    System.currentTimeMillis() - t0);
        }

        return new Outcome(S_OK, null, m, d.file, System.currentTimeMillis() - t0);
    }
}
