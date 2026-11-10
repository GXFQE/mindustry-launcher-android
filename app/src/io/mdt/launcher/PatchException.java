package io.mdt.launcher;

import java.io.IOException;

/**
 * 补丁机制里**会给用户看的失败**（2026-11-10）。
 *
 * <p>形状照抄本工程既有做法（{@code Cas.CasException} / {@code SlotZip} 的拒绝路径）：
 * 异常里带一个**码**（判据）+ 一段**定位**（哪个文件 / 哪个字段），界面用
 * {@link PatchText#reason} 把码翻成人话 —— 文案只有一处来源，自检可以逐一断言
 * "每个码都有人话"（不会出现弹窗里只有类名那种原始诊断）。
 */
final class PatchException extends IOException {

    /** 不是 zip / zip 打不开。 */
    static final String C_BAD_ZIP = "bad_zip";
    /** 包里没有 patch.json，或 JSON 解析失败。 */
    static final String C_BAD_JSON = "bad_json";
    /** schema 不是 1（不猜未来格式）。 */
    static final String C_SCHEMA = "schema";
    /** 某个字段缺失 / 非法。 */
    static final String C_FIELD = "field";
    /** targets 数组为空。 */
    static final String C_NO_TARGETS = "no_targets";
    /** apkSha256 不是 64 位十六进制。 */
    static final String C_BAD_HASH = "bad_hash";
    /** 路径不安全（绝对路径 / .. / 盘符 / 反斜杠）或不在 targets/&lt;hash&gt;/ 下。 */
    static final String C_BAD_PATH = "bad_path";
    /** 清单声明的条目在 zip 里不存在。 */
    static final String C_MISSING_ENTRY = "missing_entry";
    /** dex 文件不是合法 dex / 解析失败。 */
    static final String C_BAD_DEX = "bad_dex";
    /** 素材包不是 zip，或条目不在 assets/ 下。 */
    static final String C_BAD_ASSETS = "bad_assets";
    /** 同一个 id + version 已经导入过。 */
    static final String C_DUP = "dup";
    /** 读写失败（磁盘 / 权限）。 */
    static final String C_IO = "io";
    /** 超过体积上限。 */
    static final String C_TOO_BIG = "too_big";
    /** 记录里没有当前 APK 哈希对应的 target（游戏版本变了）。 */
    static final String C_NO_MATCH = "no_match";
    /** 与已启用的补丁冲突（改同一个类 / 素材）。 */
    static final String C_CONFLICT = "conflict";
    /** 游戏在跑，拒绝改补丁。 */
    static final String C_GAME_ALIVE = "game_alive";
    /** 记录文件缺失（dex / 素材被外部删了）。 */
    static final String C_MISSING = "missing";
    /** 已经启用。 */
    static final String C_ENABLED = "enabled";
    /** 还在启用中（删除前要先停用）。 */
    static final String C_IN_USE = "in_use";
    /** 记录不存在（被删了）。 */
    static final String C_RECORD_GONE = "record_gone";
    /** 启动时二次检查发现冲突（防御性，界面本该拦掉）。 */
    static final String C_CONFLICT_LAUNCH = "conflict_launch";

    final String code;
    /** 给"人话模板"填空的定位信息（文件名 / 字段名 / 补丁名）；可为空串。 */
    final String detail;

    PatchException(String code, String detail) {
        super(code + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.code = code;
        this.detail = detail == null ? "" : detail;
    }
}
