package io.mdt.launcher;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 读游戏图集的目录文件 `sprites.aatls`（arc 的 `TextureAtlas.TextureAtlasData` 那个格式）。
 *
 * <h3>格式（**实测钉死**，不是猜的）</h3>
 * <pre>
 *   "AATLS" + 版本(1 字节)
 *   逐页直到 EOF：
 *     1 字节（每页开头都有；原版文件里都是 0x01）
 *     UTF 字符串：页图片名（"sprites.png" / "sprites2.png" …）
 *     short 页宽, short 页高
 *     byte minFilter, byte magFilter, byte wrapX, byte wrapY
 *     int 区域数
 *     每个区域：
 *       UTF 字符串：区域名（= 方块名，多格方块是整张，如 `copper-wall-large` 64×64）
 *       short left, short top, short 宽, short 高
 *       byte hasOffsets ⇒ 有就再读 4 个 short（offsetX, offsetY, 原宽, 原高）
 *       byte hasSplits  ⇒ 有就再读 4 个 short
 *       byte hasPads    ⇒ 有就再读 4 个 short
 * </pre>
 *
 * 🔴 **判据（神谕）**：把这份解析器与**游戏自己的读取器**
 * （`arc.graphics.g2d.TextureAtlas$TextureAtlasData`，PC 探针 `_lab/msch/AtlasProbe.java`）
 * 对同一个 `[Android][v160]Mindustry.apk` 跑一遍 —— 4 页 / **5200 个区域逐条（名字 + 页 + 矩形）相同**、
 * 而且解析结束时**位置刚好等于文件长度**（不多不少）。
 * ⚠️ arc 的源码里版本只读 1 字节、随后直接读页名 —— 与实测文件对不上（实测每页开头还有一个字节）；
 * 本类**按实测文件**写，判据就是上面那条"与游戏读取器逐条相同 + 刚好 EOF"。
 *
 * ⚠️ 本类**只解目录**，不碰图片：区域像素在 Android 侧用 `BitmapRegionDecoder` 按矩形裁
 * （PC 侧用 `ImageIO`）—— 见 {@link MschSprite.Sheet}。
 */
public final class MschAtlas {
    /** 一条区域：在哪张页图的哪个矩形里 */
    public static final class Region {
        public final String page;
        public final int x, y, w, h;

        Region(String page, int x, int y, int w, int h) {
            this.page = page;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        @Override public String toString() {
            return page + "@" + x + "," + y + " " + w + "x" + h;
        }
    }

    private MschAtlas() {}

    /**
     * 解一份 `sprites.aatls`。
     *
     * @return 区域名 → 区域；**解析失败返回 null**（调用方按"没有图集"处理 ⇒ 回落到色块预览）
     */
    public static Map<String, Region> read(InputStream in) {
        if (in == null) return null;
        DataInputStream d = null;
        try {
            d = new DataInputStream(new java.io.BufferedInputStream(in, 1 << 16));
            byte[] magic = new byte[5];
            d.readFully(magic);
            if (magic[0] != 'A' || magic[1] != 'A' || magic[2] != 'T' || magic[3] != 'L'
                    || magic[4] != 'S') {
                return null;
            }
            d.readByte();                                  // 版本
            Map<String, Region> out = new HashMap<>(8192);
            int pages = 0;
            while (true) {
                int lead = d.read();                        // 每页开头那一个字节
                if (lead < 0) break;                        // 正常 EOF
                String page = d.readUTF();
                d.readShort();                              // 页宽
                d.readShort();                              // 页高
                d.readByte();                               // min filter
                d.readByte();                               // mag filter
                d.readByte();                               // wrap x
                d.readByte();                               // wrap y
                int rects = d.readInt();
                if (rects < 0 || rects > 1 << 20) return null;
                for (int i = 0; i < rects; i++) {
                    String name = d.readUTF();
                    int x = d.readShort(), y = d.readShort();
                    int w = d.readShort(), h = d.readShort();
                    if (d.readByte() != 0) {                // hasOffsets
                        d.readShort(); d.readShort(); d.readShort(); d.readShort();
                    }
                    if (d.readByte() != 0) {                // hasSplits
                        d.readShort(); d.readShort(); d.readShort(); d.readShort();
                    }
                    if (d.readByte() != 0) {                // hasPads
                        d.readShort(); d.readShort(); d.readShort(); d.readShort();
                    }
                    if (w > 0 && h > 0) out.put(name, new Region(page, x, y, w, h));
                }
                pages++;
                if (pages > 64) return null;                // 防跑飞（真实文件 4~8 页）
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (d != null) {
                try {
                    d.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
