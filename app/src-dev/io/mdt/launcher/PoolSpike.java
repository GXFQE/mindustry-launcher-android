package io.mdt.launcher;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/**
 * dev 探针：验证「同名 AndroidFi 替换 + 对象池」这条路。
 * 流程：ingest 一个真实 APK → 把 APK 改名藏起来 → 只用 池 + override.dex 造 classloader
 * → 反射 new AndroidFi → 真读资产 / list / map / openFd，并与改名前的原始字节比对。
 */
final class PoolSpike {

    private static final int BUF = 65536;
    private static final String APK_NAME = "v160.apk";

    static void run(Context ctx, StringBuilder sb) {
        long t0 = System.nanoTime();
        File base = new File(Paths.privateDir(ctx), "poolspike");
        File objects = new File(base, "objects");
        File tmp = new File(base, "tmp");
        File override = new File(base, "override.dex");
        File apk = new File(Importer.importDir(ctx), APK_NAME);
        java.lang.reflect.Field coreFilesField = null;
        Object coreFilesOld = null;

        sb.append("override.dex = ").append(override.isFile() ? override.length() + "B" : "缺失").append('\n');
        sb.append("apk = ").append(apk.isFile() ? apk.length() + "B" : "缺失").append('\n');
        if (!override.isFile()) {
            sb.append("ABORT: 先 push override.dex 到 ").append(override.getAbsolutePath()).append('\n');
            return;
        }
        if (!apk.isFile()) {
            sb.append("ABORT: 找不到源包\n");
            return;
        }

        try {
            deleteTree(objects);
            deleteTree(tmp);
            objects.mkdirs();
            tmp.mkdirs();

            // ---- 1. ingest ----
            Map<String, String> pool = new HashMap<String, String>();
            List<String> dexObjs = new ArrayList<String>();
            byte[] cursorOrig = null, sparseOrig = null;
            String classesSha = null;
            int entries = 0;
            long bytes = 0;
            long ti = System.nanoTime();
            ZipFile zf = new ZipFile(apk);
            try {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) continue;
                    String name = ze.getName();
                    if (name.startsWith("lib/") || name.startsWith("META-INF/")) continue;
                    byte[] data = readAll(zf.getInputStream(ze));
                    String sha = sha256(data);
                    File obj = objectPath(objects, sha);
                    if (!obj.isFile()) {
                        obj.getParentFile().mkdirs();
                        File part = new File(tmp, sha + ".part");
                        writeAll(part, data);
                        if (!part.renameTo(obj)) {
                            writeAll(obj, data);
                            part.delete();
                        }
                        obj.setReadOnly();
                    }
                    if (name.startsWith("assets/")) pool.put(name.substring(7), obj.getAbsolutePath());
                    if (name.equals("classes.dex")) {
                        dexObjs.add(obj.getAbsolutePath());
                        classesSha = sha;
                    } else if (name.matches("classes\\d+\\.dex")) {
                        dexObjs.add(obj.getAbsolutePath());
                    }
                    if (name.equals("assets/cursors/cursor.png")) cursorOrig = data;
                    if (name.equals("assets/sprites/sprites.png")) sparseOrig = data;
                    entries++;
                    bytes += data.length;
                }
            } finally {
                zf.close();
            }
            sb.append("① ingest ").append(entries).append(" 条目 / ").append(fmt(bytes))
                    .append("，池 ").append(pool.size()).append(" 路径，dex 对象 ").append(dexObjs.size())
                    .append("，耗时 ").append(ms(ti)).append("ms\n");
            sb.append("   classes.dex sha=").append(classesSha == null ? "?" : classesSha.substring(0, 16)).append("...\n");

            // ---- 2. 把 APK 藏起来（证明不依赖它）----
            File hidden = new File(apk.getParentFile(), APK_NAME + ".hidden");
            boolean renamed = apk.renameTo(hidden);
            sb.append("② APK 改名 ").append(renamed ? "OK" : "失败").append(" -> ").append(hidden.getName()).append('\n');
            try {
                // ---- 3. 只用 池 + override.dex 建 classloader ----
                StringBuilder path = new StringBuilder(override.getAbsolutePath());
                for (String d : dexObjs) path.append(File.pathSeparator).append(d);
                sb.append("③ dexPath = override.dex + ").append(dexObjs.size()).append(" 个池内 dex\n");
                long t1 = System.nanoTime();
                DexClassLoader cl = new DexClassLoader(path.toString(),
                        ctx.getCodeCacheDir().getAbsolutePath(), null,
                        PoolSpike.class.getClassLoader().getParent());
                Class<?> af = Class.forName("arc.backend.android.AndroidFi", true, cl);
                sb.append("   AndroidFi = ").append(af.getName()).append(" loader=")
                        .append(af.getClassLoader() == cl ? "本 loader(我们的)" : String.valueOf(af.getClassLoader()))
                        .append(" 构造 ").append(ms(t1)).append("ms\n");

                // 4. 注入池映射
                af.getMethod("init", Map.class).invoke(null, pool);
                sb.append("④ 注入映射 poolSize=").append(af.getMethod("poolSize").invoke(null)).append('\n');

                Class<?> ft = Class.forName("arc.Files$FileType", true, cl);
                Object internal = enumOf(ft, "internal");
                Object local = enumOf(ft, "local");

                // 5. 读资产
                Object cursor = newFi(af, ft, null, "cursors/cursor.png", internal);
                byte[] got = readBytes(cursor);
                sb.append("⑤ read(cursors/cursor.png) = ").append(got.length).append("B ")
                        .append(java.util.Arrays.equals(got, cursorOrig) ? "字节一致 ✓" : "字节不一致 ✗").append('\n');
                sb.append("   length=").append(call(cursor, "length"))
                        .append(" exists=").append(call(cursor, "exists"))
                        .append(" isDirectory=").append(call(cursor, "isDirectory"))
                        .append(" path=").append(call(cursor, "path")).append('\n');

                Object sp = newFi(af, ft, null, "sprites/sprites.png", internal);
                byte[] spBytes = readBytes(sp);
                sb.append("⑥ read(sprites/sprites.png) = ").append(fmt(spBytes.length)).append(' ')
                        .append(sparseOrig != null && sha256(spBytes).equals(sha256(sparseOrig)) ? "sha 一致 ✓" : "sha 不一致 ✗").append('\n');

                // 7. list 目录
                Object dir = newFi(af, ft, null, "sprites", internal);
                Object[] kids = (Object[]) call(dir, "list");
                sb.append("⑦ list(sprites) = ").append(kids == null ? -1 : kids.length).append(" 项");
                if (kids != null && kids.length > 0) {
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < Math.min(4, kids.length); i++) {
                        if (i > 0) names.append(", ");
                        names.append(call(kids[i], "name"));
                    }
                    sb.append(" [").append(names).append("...]");
                }
                sb.append("  isDirectory=").append(call(dir, "isDirectory")).append('\n');

                // 8. map / openFd
                Object mapped = af.getMethod("map", java.nio.channels.FileChannel.MapMode.class)
                        .invoke(sp, java.nio.channels.FileChannel.MapMode.READ_ONLY);
                java.nio.ByteBuffer bb = (java.nio.ByteBuffer) mapped;
                sb.append("⑧ map(sprites.sprites.png) = ").append(bb == null ? "null" : bb.capacity() + "B capacity").append('\n');
                Object afd = af.getMethod("getAssetFileDescriptor").invoke(sp);
                sb.append("   getAssetFileDescriptor = ").append(afd == null ? "null" : "len=" + afd.getClass().getMethod("getLength").invoke(afd)).append('\n');

                // 9. 非 internal 走父类；local 必须重定位到 local storage（mods / saves 都是 local）
                Class<?> afs = Class.forName("arc.backend.android.AndroidFiles", true, cl);
                File localDir = new File(base, "localstore");
                localDir.mkdirs();
                Object files = afs.getConstructor(AssetManager.class, String.class)
                        .newInstance(null, localDir.getAbsolutePath());
                Class<?> core = Class.forName("arc.Core", true, cl);
                coreFilesField = core.getField("files");
                coreFilesOld = coreFilesField.get(null);
                coreFilesField.set(null, files);

                Object lf = newFi(af, ft, null, "poolspike-local.txt", local);
                lf.getClass().getMethod("writeString", String.class).invoke(lf, "hello-local");
                File lfile = (File) call(lf, "file");
                sb.append("⑨ local(相对路径) file() = ").append(lfile).append('\n');
                sb.append("   isAbsolute=").append(lfile.isAbsolute())
                        .append(" 落在 localstore 下=").append(lfile.getPath().startsWith(localDir.getAbsolutePath()))
                        .append(" read=").append(new String(readBytes(lf), "UTF-8")).append('\n');

                // 10. 游戏 dex 能否从池里加载
                long t2 = System.nanoTime();
                Class<?> game = Class.forName("mindustry.android.AndroidLauncher", false, cl);
                sb.append("⑩ loadClass(").append(game.getName()).append(") = OK, 耗时 ").append(ms(t2)).append("ms\n");

                // 11. 未命中回落
                Object missing = newFi(af, ft, null, "cursors/nope.png", internal);
                sb.append("⑪ 未命中路径 exists=").append(call(missing, "exists")).append("（应为 false）\n");

                // 12. 真正的一跳：游戏自己的 AndroidFiles.get() 会不会 new 出我们的 AndroidFi。
                //     这一步才证明「只覆盖一个类」能拦住全部 internal 读取。
                try {
                    sb.append("⑫ AndroidFiles loader=")
                            .append(afs.getClassLoader() == cl ? "本 loader(游戏 dex)" : String.valueOf(afs.getClassLoader()))
                            .append('\n');
                    Object viaGet = afs.getMethod("get", String.class, ft).invoke(files, "cursors/cursor.png", internal);
                    sb.append("   AndroidFiles.get(internal) -> ").append(viaGet.getClass().getName())
                            .append(" loader=").append(viaGet.getClass().getClassLoader() == cl ? "本 loader" : "别的")
                            .append(" 我们是同一类=").append(viaGet.getClass() == af).append('\n');
                    byte[] viaBytes = readBytes(viaGet);
                    sb.append("   read = ").append(viaBytes.length).append("B ")
                            .append(cursorOrig != null && java.util.Arrays.equals(viaBytes, cursorOrig) ? "字节一致 ✓" : "不一致 ✗").append('\n');
                    Object viaLocal = afs.getMethod("get", String.class, ft).invoke(files, "poolspike-local.txt", local);
                    sb.append("   AndroidFiles.get(local) -> ").append(viaLocal.getClass().getName())
                            .append(" file=").append(call(viaLocal, "file"))
                            .append(" read=").append(new String(readBytes(viaLocal), "UTF-8")).append('\n');
                } catch (Throwable e) {
                    sb.append("⑫ 集成测试异常: ").append(e).append('\n');
                }
            } finally {
                if (coreFilesField != null) {
                    try {
                        coreFilesField.set(null, coreFilesOld);
                    } catch (Throwable ignored) {
                    }
                }
                if (renamed && hidden.isFile()) hidden.renameTo(apk);
            }
            sb.append("总耗时 ").append(ms(t0)).append("ms\n");
        } catch (Throwable e) {
            sb.append("FAILED: ").append(e).append('\n');
            StackTraceElement[] st = e.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) sb.append("  at ").append(st[i]).append('\n');
        }
    }

    private static Object newFi(Class<?> af, Class<?> ft, AssetManager am, String path, Object type) throws Exception {
        return af.getConstructor(AssetManager.class, String.class, ft).newInstance(am, path, type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumOf(Class<?> ft, String name) {
        return Enum.valueOf((Class) ft, name);
    }

    private static Object call(Object o, String m) {
        try {
            return o.getClass().getMethod(m).invoke(o);
        } catch (Throwable e) {
            return "ERR:" + e;
        }
    }

    private static byte[] readBytes(Object fi) throws Exception {
        InputStream in = (InputStream) fi.getClass().getMethod("read").invoke(fi);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static File objectPath(File objects, String sha) {
        return new File(new File(objects, sha.substring(0, 2)), sha.substring(2));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[BUF];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1000000L;
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2fMB", bytes / 1048576.0);
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
