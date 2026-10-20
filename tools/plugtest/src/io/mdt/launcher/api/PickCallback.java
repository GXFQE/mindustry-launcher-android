package io.mdt.launcher.api;

import java.util.List;

/**
 * 插件让宿主弹「勾选条目」时的回调（第 187 轮，插件接口 v7）。
 *
 * <p>由 {@link PluginApi#pick(String, PickCallback)} 触发：宿主列出当前槽的某一类内容，
 * 用户勾完点确定 —— 回调把**用户真正勾的那几份**交给插件。
 *
 * <h3>三条约定</h3>
 * <ul>
 *   <li>在**主线程**回调（插件可以放心动 View）。</li>
 *   <li>用户取消 / 这一类本来就没有条目 ⇒ 回调一个**空表**（永不为 null）。</li>
 *   <li>给的是 {@link ItemInfo} **快照**：文件之后被删 / 被改，插件要用时自己判
 *       {@code readable} 或重新 {@code contents(...)}。</li>
 * </ul>
 *
 * <p>★ 本文件的字符串字面量一律 ASCII（门禁 SRC-02）。
 */
public interface PickCallback {

    /** @param items 用户勾的条目（主线程；取消 = 空表） */
    void onPicked(List<ItemInfo> items);
}
