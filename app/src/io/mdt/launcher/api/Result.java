package io.mdt.launcher.api;

/**
 * 一次操作的**结果**（写文件 / 挪中转站 / 存盘 / 将来更多动作）。
 *
 * <p>为什么要一个类型而不是 {@code boolean}：失败得说得出**为什么**。宿主只给机器码
 * （{@link #reason}），**文案由插件自己出** —— 每个插件有自己的语言包，宿主的
 * {@code SettingsText} 那一套只服务宿主自己。
 *
 * <p>约定（构造时强制）：
 * <ul>
 *   <li>成功 ⇒ {@link #ok} = true、{@link #reason} = null；</li>
 *   <li>失败 ⇒ {@link #ok} = false、{@link #reason} **一定非空**（没给就补 {@code "unknown"}）。</li>
 * </ul>
 * 这条与宿主"标签 + 原因"成对渲染的规矩同源：{@code ok=false} 却拿不到原因，界面上就只剩一个叉。
 *
 * <p>已知的 reason（宿主这一版会出现的，其余按 {@code "unknown"} 兜底）：
 * <table>
 *   <tr><td>{@code game-running}</td><td>游戏正在跑，写操作被拒</td></tr>
 *   <tr><td>{@code not-in-slot}</td><td>条目不在槽内那四类目录里</td></tr>
 *   <tr><td>{@code not-found}</td><td>条目不存在 / 没有路径</td></tr>
 *   <tr><td>{@code canceled}</td><td>用户取消（不是错误）</td></tr>
 *   <tr><td>{@code busy}</td><td>同一时刻只允许一份（保存 / 分享排队）</td></tr>
 *   <tr><td>{@code no-page}</td><td>当下没有可用的界面</td></tr>
 *   <tr><td>{@code io}</td><td>读写失败</td></tr>
 *   <tr><td>{@code invalid}</td><td>参数不合法（null / 类型不对）</td></tr>
 * </table>
 *
 * <p>⚠️ {@code reason} 是**机器码**，别直接上屏（"游戏正在运行"这种句子归插件）。
 */
public final class Result {

    /** 成功（唯一实例，可以直接比较引用，但请用 {@link #ok}）。 */
    public static final Result OK = new Result(true, null);

    /** 操作是否成功。 */
    public final boolean ok;

    /** 机器码；成功时恒为 null，失败时恒非空。 */
    public final String reason;

    public Result(boolean ok, String reason) {
        this.ok = ok;
        this.reason = ok ? null : (reason == null || reason.trim().isEmpty() ? "unknown" : reason);
    }

    /** 成功。 */
    public static Result ok() { return OK; }

    /** 失败（{@code reason} 为空 ⇒ 记 {@code "unknown"}）。 */
    public static Result fail(String reason) { return new Result(false, reason); }

    @Override public String toString() {
        return ok ? "ok" : "fail:" + reason;
    }
}
