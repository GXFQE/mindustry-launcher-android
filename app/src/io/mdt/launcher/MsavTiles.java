package io.mdt.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

/**
 * `.msav` 的**瓦片解码**（F10 地图预览的地基）。
 *
 * 全部照抄游戏（Mindustry 160.4）：
 * <pre>
 *   SaveVersion.readMap / ShortChunkSaveVersion.readMap  —— 两趟 + 游程 + 变长块记录
 *   SaveVersion.readContentHeader                        —— content 区：id → 名字
 *   SaveIO.versionArray                                  —— 版本 → reader 的映射
 * </pre>
 *
 * 🔴 **判定纪律（本工程一贯）**：地图区是逐瓦片变长的，**任何一处长度/游程/跳块算错，
 * 游标必然错位** ⇒ 唯一可信的判据是「**该区载荷刚好读完**（剩余 0 字节）」。
 * 实测：全部 386 个语料文件（v4/v7/v8/v9/v10/v11/v13，三套不同布局）**386/386 通过**。
 * ⚠️ 别再加"宽高与载荷相称"之类的紧护栏 —— 游程压缩得好的大图（空旷野）载荷可能只有几十 KB，
 * 实测曾用 `≤载荷×8` 误杀 18 个文件；真正的判据永远是"刚好读完"。
 *
 * 本类**不碰 Android**（纯 Java），所以能在 PC 上单独编译验证（与 {@link MsavMeta} 同一套做法）。
 */
public final class MsavTiles {
    /** 地图尺寸上限（离谱就当不是地图区） */
    private static final int MAX_SIDE = 4096;
    /** 最多看几个区（正常 5~8 个） */
    private static final int MAX_REGIONS = 12;
    /** 单个区上限（防坏文件把内存吃光） */
    private static final int MAX_REGION = 64 << 20;

    /** ContentType 的顺序（照抄游戏 `ContentType.all`）—— 只有 `block` 那一类我们要 */
    private static final String[] TYPE_NAMES = {
            "item", "block", "mech_UNUSED", "bullet", "liquid", "status", "unit", "weather",
            "effect_UNUSED", "sector", "loadout_UNUSED", "typeid_UNUSED", "error", "planet",
            "ammo_UNUSED", "team", "unitCommand", "unitStance"};

    /** 解出来的地图 */
    public static final class Tiles {
        public int version;
        public int width, height;
        /** 每格的地板 / 矿（overlay）/ 方块 —— 下标都是 content id，长度 = width*height */
        public short[] floors;
        public short[] ores;
        public short[] blocks;
        /** block 类内容的名字表（下标 = id），来自地图自带的 content 区 */
        public final List<String> blockNames = new ArrayList<>();
        /** 解的是第几个区（诊断用） */
        public int regionIndex = -1;
        /** 那次解的是不是"预览小地图"（语料里目前没有，将来有也不用改码） */
        public boolean preview;
        /**
         * 地图自带的数据补丁（F21 统计要用它改内容定义；**不是**补丁区时为空表）。
         * 判据见 {@link MsavPatches}：区顺序固定 ⇒ 补丁区只可能是**第 2 个区**（下标 1），
         * 且只有 v12+ 才有这个区。
         */
        public final List<MsavPatches.Entry> patchEntries = new ArrayList<>();

        public int airId() {
            int i = blockNames.indexOf("air");
            return i < 0 ? 0 : i;
        }

        public int stoneId() {
            int i = blockNames.indexOf("stone");
            return i < 0 ? 0 : i;
        }

        public boolean ok() {
            return width > 0 && height > 0 && floors != null
                    && floors.length == width * height;
        }
    }

    private MsavTiles() {}

    /**
     * 解一个 `.msav` 的地图区（文件形态）。
     *
     * @param usePreview true ⇒ 有"预览小地图"时优先要它（更小、更快）
     * @return 解不出来时返回 {@code null}（**不抛**：调用方按"没预览"处理即可）
     */
    public static Tiles read(File f, boolean usePreview) {
        if (f == null || !f.isFile()) return null;
        java.io.InputStream fin = null;
        try {
            fin = new BufferedInputStream(new FileInputStream(f), 1 << 16);
            return read(fin, usePreview);
        } catch (Throwable t) {
            return null;
        } finally {
            if (fin != null) {
                try {
                    fin.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 同上，但从**任意流**读 —— 模组包与游戏 APK 里的地图是 zip 条目，不是文件。
     * ★ 调用方负责关流。
     */
    public static Tiles read(java.io.InputStream src, boolean usePreview) {
        if (src == null) return null;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(
                    new InflaterInputStream(src), 1 << 16));
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'M' || magic[1] != 'S' || magic[2] != 'A' || magic[3] != 'V') return null;
            int version = in.readInt();

            // ① 收区（名字不写在文件里，顺序由版本决定；我们只按长度读，再靠"能不能解"认）
            List<byte[]> regions = new ArrayList<>();
            try {
                for (int i = 0; i < MAX_REGIONS; i++) {
                    int len = in.readInt();
                    if (len < 0 || len > MAX_REGION) return null;
                    byte[] p = new byte[len];
                    in.readFully(p);
                    regions.add(p);
                }
            } catch (EOFException eof) {
                // 正常：读到文件尾
            }

            // ② content 区：那个能解出名字表的（顺序会被 v11/v12+ 的 patches 挪位，所以靠"试"）
            int contentIdx = -1;
            List<String> names = null;
            for (int i = 0; i < regions.size(); i++) {
                List<String> got = parseContent(regions.get(i));
                if (got != null && got.size() > 3) {
                    contentIdx = i;
                    names = got;
                    break;
                }
            }
            if (contentIdx < 0) return null;

            // ③ content 之后的每个区都试着当"地图"解，只认**载荷刚好读完**的；
            //    多个命中时：大的那份是地图本体、小的那份是 preview_map
            List<Tiles> good = new ArrayList<>();
            for (int i = contentIdx + 1; i < regions.size(); i++) {
                Tiles t = decodeRegion(regions.get(i), names, version);
                if (t != null) {
                    t.regionIndex = i;
                    good.add(t);
                }
            }
            if (good.isEmpty()) return null;
            Tiles best = good.get(0);
            for (Tiles t : good) {
                int a = t.width * t.height, b = best.width * best.height;
                if (usePreview ? (a < b) : (a > b)) best = t;
            }
            if (good.size() > 1) {
                int max = 0;
                for (Tiles t : good) max = Math.max(max, t.width * t.height);
                best.preview = best.width * best.height < max;
            }
            // ④ 数据补丁区（v12+ 的**第 2 个区**；F21 统计要用它改内容定义）
            if (version >= 12 && regions.size() > 1) {
                List<MsavPatches.Entry> pe = MsavPatches.parse(regions.get(1));
                if (pe != null) best.patchEntries.addAll(pe);
            }
            return best;
        } catch (Throwable t) {
            return null;                    // 坏文件/半截文件：当"没有预览"处理，不拖垮界面
        }
    }

    /**
     * 解一个"像地图"的区。**载荷必须刚好读完**，否则返回 null。
     */
    static Tiles decodeRegion(byte[] payload, List<String> names, int version) {
        try {
            DataInputStream m = new DataInputStream(new ByteArrayInputStream(payload));
            Tiles t = new Tiles();
            t.version = version;
            t.blockNames.addAll(names);
            int w = m.readUnsignedShort(), h = m.readUnsignedShort();
            if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return null;
            t.width = w;
            t.height = h;
            int n = w * h;
            // ⚠️ 护栏只能是**量级**判据（见类注释里那 18 个被误杀的文件）
            if ((long) n > (payload.length * 64L) + 4096) return null;
            t.floors = new short[n];
            t.ores = new short[n];
            t.blocks = new short[n];

            // ── 趟 1：地板 + 矿（带游程）────────────────────────────────────────
            for (int i = 0; i < n; i++) {
                short floorid = m.readShort();
                short oreid = m.readShort();
                int consecutives = m.readUnsignedByte();
                // 游戏：地板 id 指向 air 时按石头处理（地图里"没有地板"的格子）
                if (floorid == t.airId()) floorid = (short) t.stoneId();
                t.floors[i] = floorid;
                t.ores[i] = oreid;
                for (int j = i + 1; j < i + 1 + consecutives && j < n; j++) {
                    t.floors[j] = floorid;
                    t.ores[j] = oreid;
                }
                i += consecutives;
            }

            // ── 趟 2：方块（变长记录 + 游程）───────────────────────────────────
            // ★ 版本分叉（照抄 SaveIO.versionArray 的映射，已全语料实测）：
            //   v10+（Save10..13 extends SaveVersion）：`packed&4` ⇒ 7 字节；实体块长度 **int**
            //   v7~v9（Save7/8/9 extends ShortChunkSaveVersion）：多 `packed&2` ⇒ **1 字节且无游程**；
            //                                                    实体块长度 **short**
            boolean shortChunks = version < 10;
            for (int i = 0; i < n; i++) {
                short blockId = m.readShort();
                byte packed = m.readByte();
                boolean hadEntity = (packed & 1) != 0;
                boolean hadDataOld = (packed & 2) != 0;
                boolean hadDataNew = (packed & 4) != 0;
                boolean isCenter = true;
                if (hadDataNew) {
                    m.readByte();
                    m.readByte();
                    m.readByte();
                    m.readInt();
                }
                if (hadEntity) {
                    isCenter = m.readBoolean();
                }
                if (isCenter) t.blocks[i] = blockId;
                if (hadEntity) {
                    if (isCenter) {
                        // 实体数据是**自描述长度**的 ⇒ 我们只跳过，不需要知道哪个方块有建筑
                        int len = shortChunks ? m.readUnsignedShort() : m.readInt();
                        if (len < 0) return null;
                        m.skipBytes(len);
                    }
                } else if (hadDataOld || hadDataNew) {
                    if (hadDataOld) {
                        t.blocks[i] = blockId;
                        m.readByte();
                    }
                } else {
                    int consecutives = m.readUnsignedByte();
                    for (int j = i + 1; j < i + 1 + consecutives && j < n; j++) {
                        t.blocks[j] = blockId;
                    }
                    i += consecutives;
                }
            }
            // ★ 唯一判据：刚好读完
            if (m.available() != 0) return null;
            return t;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解 content 区，返回 **block 类**的名字表（下标 = id）；不像 content 区就返回 null。
     *
     * 格式（`SaveVersion.readContentHeader`）：`u8 类型数` → 每类：`i8 类型序号` + `short 条数`
     * + 条数 × `readUTF 名字`（**按全局注册顺序**）。
     */
    static List<String> parseContent(byte[] payload) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            int mapped = in.readUnsignedByte();
            if (mapped <= 0 || mapped > 40) return null;
            List<String> blocks = new ArrayList<>();
            for (int i = 0; i < mapped; i++) {
                int typeOrd = in.readByte();
                if (typeOrd < 0 || typeOrd >= TYPE_NAMES.length) return null;
                int total = in.readShort() & 0xffff;
                if (total > 4000) return null;
                boolean isBlock = "block".equals(TYPE_NAMES[typeOrd]);
                for (int j = 0; j < total; j++) {
                    String nm = in.readUTF();
                    if (isBlock) blocks.add(nm);
                }
            }
            return blocks;
        } catch (Throwable t) {
            return null;
        }
    }
}
