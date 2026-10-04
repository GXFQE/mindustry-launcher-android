package io.mdt.launcher;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * `.msav` 的**数据补丁区**（v12+ 才有，F21）。
 *
 * <h3>为什么要有它</h3>
 * 地图可以自带"改内容定义"的补丁：`patch-xxxx.json` 会把某个方块的字段改掉
 * （实测 `{"block":{"dark-metal":{"attributes":{"scrapmetal":2}}}}` 就是把一块地板变成可采），
 * `content xxx.json = type: StaticWall` 则会**新增**一个内容（游戏给它加 `dp-` 前缀）。
 * 不认这个区，这类图的统计就会偏（实测 460 个语料里 14 个带非空补丁）。
 *
 * <h3>格式（照抄 `SaveVersion.readDataPatches` + `PatchAsset.read` / `ContentAsset.read`）</h3>
 * <pre>
 *   int  格式版本（=2）
 *   int  条数
 *   条 × { byte 类型 | UTF 路径 | boolean 是否内嵌 | 内嵌?数据 : 32 字节 hash }
 *      类型 0=patch   数据 = int 长度 + UTF-8 原文
 *      类型 1=content 数据 = short 内容类型 + int 长度 + UTF-8 原文
 *      类型 2..5       bundle/image/sound/music —— hash 指向 assetCache，我们**不解析**
 * </pre>
 * 区顺序固定：`meta → patches → content → map → …` ⇒ 补丁区**只在第 2 个区**（下标 1）。
 * 这条位置判据很关键：单靠"能不能解"去认区，迟早会把别的区认成补丁区。
 *
 * 🔴 判据：解完**必须刚好读完**（剩余 0 字节）—— 与 {@link MsavTiles} 同一条纪律。
 *
 * 本类**不碰 Android**（纯 Java），能在 PC 上单独编译验证。
 */
public final class MsavPatches {
    public static final int PATCH = 0, CONTENT = 1, BUNDLE = 2, IMAGE = 3, SOUND = 4, MUSIC = 5;

    /** 一条补丁/内容条目 */
    public static final class Entry {
        public int type;
        public String path = "";
        /** 内嵌原文（图/音/乐那三类为 null —— 它们的内容在 assetCache 里） */
        public String text;
    }

    private MsavPatches() {}

    /**
     * 解一个"像补丁区"的载荷；**不像就返回 null**（不是补丁区），是补丁区但为空返回空表。
     */
    public static List<Entry> parse(byte[] payload) {
        if (payload == null || payload.length < 8) return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            int ver = in.readInt();
            if (ver < 0 || ver > 20) return null;
            int count = in.readInt();
            if (count < 0 || count > 500) return null;
            List<Entry> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int type = in.readByte();
                if (type < 0 || type > MUSIC) return null;
                String path = in.readUTF();
                if (path.isEmpty() || path.length() > 300) return null;
                Entry e = new Entry();
                e.type = type;
                e.path = path;
                if (in.readBoolean()) {
                    if (type == PATCH) {
                        e.text = readText(in);
                    } else if (type == CONTENT) {
                        in.readShort();                    // 内容类型序号（我们自己按 JSON 的 type 判）
                        e.text = readText(in);
                    } else {
                        return null;                       // 图/音/乐不猜，整区放弃
                    }
                } else {
                    in.readFully(new byte[32]);            // 32 字节 hash
                }
                out.add(e);
            }
            // ★ 唯一判据：刚好读完（多一个字节都不认 —— 认错了会静默改掉统计口径）
            if (in.available() != 0) return null;
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readText(DataInputStream in) throws Exception {
        int len = in.readInt();
        if (len < 0 || len > (4 << 20)) throw new IllegalArgumentException("补丁太长");
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
