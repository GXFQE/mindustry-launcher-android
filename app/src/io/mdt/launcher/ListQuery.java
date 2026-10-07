package io.mdt.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 列表的「搜索 / 排序 / 只看有问题的」—— **四个列表页共用的唯一实现**（2026-10-07 第 122 轮）。
 *
 * <h3>为什么要有这个类</h3>
 * 模组页（F13 第二批）先有了搜索框 + 「筛选与排序」对话框，2026-10-07 用户点单
 * 「把搜索 / 排序推广到地图 / 蓝图 / 存档列表」⇒ 三个页面各抄一份的话，
 * 「关键词怎么算命中」「有问题的怎么排」这种判据就有四份，其中一份改了另三份不改**不会报错**
 * （本工程已经为同类问题吃过一次亏：`MsavListAdapter#refreshThumb` 就是三份内联实现收成一份的产物）。
 *
 * <h3>形状：纯函数 + 一把「钥匙」</h3>
 * 每个列表页只要回答"一条记录对这四个问题怎么答"（{@link Key}），
 * 剩下的过滤 / 排序 / 命中判定**全在这里**，而且**不碰文件、不改入参** ⇒ 能直接喂给自检。
 *
 * <h3>🔴 常量取值与 {@link Mods#SORT_NAME} 等**刻意同值**</h3>
 * 模组页那两个常量现在就是本类的别名（见 {@link Mods}）—— 不做映射表，
 * 因为"两张常量表 + 一个映射"本身就是第二个会写错的地方。
 */
public final class ListQuery {

    /**
     * 排序方式。
     * ★ 取值 0/1/2 与 `Mods.SORT_NAME / SORT_STATE / SORT_SIZE` 相同（那边已改成别名）。
     * · {@link #SORT_TIME} 只有存档页用（槽里的存档是**真文件**，有修改时间；
     *   地图 / 蓝图有相当一部分住在 zip 条目里，压根没有时间可排 ⇒ 那两页不提供它）。
     */
    public static final int SORT_NAME = 0, SORT_PROBLEM = 1, SORT_SIZE = 2, SORT_TIME = 3;

    /**
     * 一条列表记录要回答的四个问题（**页面判据的唯一落点**）。
     *
     * 只有 {@link #text} 与 {@link #bytes} 是必须实现的；其余三个都有默认值 ——
     * 这样三页各自只写自己真正需要的那几个，而"默认值写错"这件事**只可能发生在这一处**。
     */
    public static abstract class Key<T> {
        /** 参与搜索的文本（多个字段用 {@link #haystack} 拼，分隔符保证**不许跨字段命中**） */
        public abstract String text(T item);

        /** 大小（排序用；界面上的大小是另一处格式化的） */
        public abstract long bytes(T item);

        /** 按名称排序用的键（默认就用 {@link #text}；模组页那种"搜五个字段、排一个标题"才需要覆盖） */
        public String title(T item) {
            return text(item);
        }

        /** 时间（只有 {@link #SORT_TIME} 用它；没有这一维的列表不必覆盖） */
        public long time(T item) {
            return 0L;
        }

        /** 「只看有问题的」/「有问题的在前」（默认"一条都没有问题"） */
        public boolean problem(T item) {
            return false;
        }

        /**
         * 这一页**额外**的保留条件（默认全留）。
         * ★ 只有模组页用它（类型筛选是它独有的一个维度）—— 别的页面别拿它当杂物袋。
         */
        public boolean keep(T item) {
            return true;
        }
    }

    /**
     * 直接用 `java.io.File` 当一条记录的那把钥匙（**存档页**用）。
     *
     * ★ 存档列表就是 `saves/` 下的一堆文件（名字 = 游戏里那个存档名）⇒ 搜索文件名、
     *   按大小 / 修改时间排，三个问题文件自己就能答。
     * ⚠️ `problem()` **刻意留默认（false）**：存档的"读不出来"在这页**已经有专用入口**
     *   （`row_save_bad` 那一行 → 「哪几份读不出来」列表，判据是 `MsavMeta.summarize`）——
     *   再做一个"只看有问题的"开关就是"同一件事两个入口"（F15b 的教训）。
     */
    public static final Key<java.io.File> FILE_KEY = new Key<java.io.File>() {
        @Override public String text(java.io.File f) {
            return f == null ? "" : f.getName();
        }

        @Override public long bytes(java.io.File f) {
            return f == null ? 0L : f.length();
        }

        @Override public long time(java.io.File f) {
            return f == null ? 0L : f.lastModified();
        }
    };

    private ListQuery() {}

    /**
     * ★ 搜索 + 筛选 + 排序（**纯函数**：不改入参、不碰文件 ⇒ 能单独喂给自检）。
     *
     * @param query        关键词（空 = 不筛）；走 {@link Key#text}，忽略大小写子串命中
     * @param sort         {@link #SORT_NAME} / {@link #SORT_PROBLEM} / {@link #SORT_SIZE} / {@link #SORT_TIME}
     * @param onlyProblems 只看有问题的（见 {@link Key#problem}）
     * @return **新列表**（入参一个字节都不动 —— 自检钉着这一条）
     */
    public static <T> List<T> apply(List<T> in, String query, int sort, boolean onlyProblems,
                                    Key<T> key) {
        List<T> out = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (in != null && key != null) {
            for (T it : in) {
                if (it == null) continue;
                if (!key.keep(it)) continue;
                if (onlyProblems && !key.problem(it)) continue;
                if (!matches(key.text(it), q)) continue;
                out.add(it);
            }
        }
        Collections.sort(out, comparator(sort, key));
        return out;
    }

    /**
     * 关键词命中（**判据的唯一实现**）：忽略大小写、子串。
     *
     * ★ 空关键词一律命中（与 `String.contains("")` 同义）—— 这样调用方不必"先判空再判命中"，
     *   少一处能写反的地方（写反的症状是"搜不到就返回全部"，自检 ⑲ 有一条元断言专门钉它）。
     *
     * @param lowerQuery 已经 `toLowerCase` 过的关键词（热路径只降一次；见 {@link #apply}）
     */
    public static boolean matches(String text, String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isEmpty()) return true;
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    /**
     * 把几个字段拼成**一次匹配**用的文本：用 `\n` 分隔。
     *
     * ★ 为什么不直接拼（`a + b`）：那样"前一个字段的尾巴 + 后一个字段的头"会凑出一个
     *   两个字段里都不存在的词（用户搜 `A` 会命中 `…A` + `B…`）—— 分隔符把这条路堵死。
     */
    public static String haystack(String... parts) {
        StringBuilder sb = new StringBuilder();
        if (parts != null) {
            for (String p : parts) {
                if (p == null || p.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /**
     * 排序（**判据的唯一实现**）：先按选定的那一维，**再一律用名称升序兜底**。
     *
     * ★ "再按名称兜底"是刻意的：否则"按大小"时两个同大小的条目谁在前取决于
     *   扫描顺序（本工程地图/蓝图扫描结果跟文件系统顺序有关）⇒ 同一份数据两次打开顺序可能不同，
     *   看着像"排序没生效"。
     */
    private static <T> Comparator<T> comparator(final int sort, final Key<T> key) {
        return new Comparator<T>() {
            @Override public int compare(T a, T b) {
                int c = 0;
                if (sort == SORT_SIZE) {
                    c = Long.compare(key.bytes(b), key.bytes(a));          // 大的在前
                } else if (sort == SORT_TIME) {
                    c = Long.compare(key.time(b), key.time(a));            // 新的在前
                } else if (sort == SORT_PROBLEM) {
                    boolean pa = key.problem(a), pb = key.problem(b);
                    if (pa != pb) c = pa ? -1 : 1;                         // 有问题的在前
                }
                if (c != 0) return c;
                String ta = key.title(a), tb = key.title(b);
                if (ta == null) return tb == null ? 0 : -1;
                return tb == null ? 1 : ta.compareToIgnoreCase(tb);
            }
        };
    }

    /**
     * 这一页现在算不算"筛过了"（决定要不要显示「显示 N / 共 M」那一行）。
     * ★ 与 {@link #apply} 的过滤条件**必须同步**：漏一维的症状是"筛掉了东西但界面不说"。
     */
    public static boolean filtering(String query, int sort, boolean onlyProblems, int defaultSort) {
        return onlyProblems || sort != defaultSort || (query != null && !query.trim().isEmpty());
    }
}
