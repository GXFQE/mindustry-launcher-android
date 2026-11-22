package io.mdt.launcher;

import android.content.Context;

import dalvik.system.DexClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * 判断某个版本的 APK 能不能走「同名类覆盖」：用一个**只含 override.dex + 该 APK** 的
 * {@link DexClassLoader} 把 `arc.backend.android.AndroidFi` 加载起来，然后
 *
 * <ol>
 *   <li>确认解析到的是**我们的**类（有静态 {@code init(Map)}）；</li>
 *   <li>往池里塞一个真实存在的探针文件，按 internal 读它 —— {@code read/length/exists/map/
 *       getAssetFileDescriptor} 都会走到我们覆盖的方法，任何一个方法在这个版本的 {@code Fi}
 *       上不存在都会在这里抛 {@link NoSuchMethodError}；</li>
 *   <li>读回来的字节必须和写进去的一致（不只是"没抛异常"）。</li>
 * </ol>
 *
 * <p>这比「解析 dex 比签名」便宜（一次类加载 + 几 KB 读写），而且判的是**真正会发生的事**。
 * 代价：只覆盖到我们这几个方法，别的（比如某个版本新增的 virtual 方法被游戏直接调）验不到 ——
 * 所以导入期通过 ≠ 运行时一定不炸，只是把「版本 ABI 变了」提前到导入。</p>
 */
public final class ArcAbi {
    private static final String TAG = "MDTLauncher";
    public static final String ANDROID_FI = "arc.backend.android.AndroidFi";
    private static final String PROBE_KEY = "mdt-arc-probe/hit.bin";
    private static final byte[] PROBE_BYTES =
            "mdt-arc-probe-0123456789-abcdefghijklmnopqrstuvwxyz".getBytes();

    private ArcAbi() {}

    /** 判定结果。 */
    public static final class Result {
        public final boolean eligible;
        public final String fingerprint;
        public final String reason;

        Result(boolean eligible, String fingerprint, String reason) {
            this.eligible = eligible;
            this.fingerprint = fingerprint;
            this.reason = reason;
        }

        public static Result no(String reason) {
            return new Result(false, null, reason);
        }
    }

    /**
     * 探一次。失败一律返回 eligible=false + 原因（不抛），调用方按「不可用」处理，
     * 让这个版本继续走原 APK 的路。
     */
    public static Result probe(Context ctx, File apk, File overrideDex) {
        if (apk == null || !apk.isFile() || apk.length() < 1024) return Result.no("no-apk");
        if (overrideDex == null || !overrideDex.isFile() || overrideDex.length() < 1024) {
            return Result.no("no-override");
        }
        // dex 载体必须只读，否则 DexClassLoader 直接 SecurityException。
        if (apk.canWrite() && !apk.setReadOnly()) {
            return Result.no("apk-not-readonly");
        }
        File opt = new File(ctx.getCodeCacheDir(), "arcprobe");
        if (!opt.exists()) opt.mkdirs();
        // 每次用新文件名：探针文件置过只读，复用同一个名字第二次就写不进去了。
        File hit = new File(opt, "hit-" + System.nanoTime() + ".bin");
        try {
            writeFile(hit, PROBE_BYTES);
            hit.setReadOnly();
        } catch (Throwable t) {
            return Result.no("probe-file:" + t.getMessage());
        }
        try {
            return probeWith(ctx, apk, overrideDex, opt, hit);
        } finally {
            hit.setWritable(true);
            hit.delete();
        }
    }

    private static Result probeWith(Context ctx, File apk, File overrideDex, File opt, File hit) {
        String dexPath = overrideDex.getAbsolutePath() + File.pathSeparator + apk.getAbsolutePath();
        try {
            // 父 loader 用 launcher 自己的：游戏的 arc/mindustry 类由 dexPath 里的 APK 提供。
            DexClassLoader cl = new DexClassLoader(dexPath, opt.getAbsolutePath(), null,
                    ArcAbi.class.getClassLoader().getParent());
            Class<?> fi;
            try {
                fi = Class.forName(ANDROID_FI, true, cl);
            } catch (Throwable t) {
                return Result.no("no-androidfi:" + t.getClass().getSimpleName());
            }
            if (fi.getClassLoader() != cl) return Result.no("not-ours");
            Method init;
            Constructor<?> ctor;
            try {
                init = fi.getDeclaredMethod("init", Map.class);
                ctor = fi.getDeclaredConstructor(android.content.res.AssetManager.class,
                        String.class, Class.forName("arc.Files$FileType", true, cl));
            } catch (Throwable t) {
                return Result.no("shape:" + t.getClass().getSimpleName());
            }
            Map<String, String> pool = new HashMap<String, String>();
            pool.put(PROBE_KEY, hit.getAbsolutePath());
            init.invoke(null, pool);
            Object typeInternal = enumValue(Class.forName("arc.Files$FileType", true, cl), "internal");
            Object handle = ctor.newInstance(null, PROBE_KEY, typeInternal);
            byte[] back = readAll(invoke(handle, "read"));
            if (back == null || back.length != PROBE_BYTES.length) {
                return Result.no("read-bad-len");
            }
            for (int i = 0; i < back.length; i++) {
                if (back[i] != PROBE_BYTES[i]) return Result.no("read-bad-bytes");
            }
            Object len = invoke(handle, "length");
            if (!(len instanceof Number) || ((Number) len).longValue() != PROBE_BYTES.length) {
                return Result.no("length-mismatch");
            }
            Object ex = invoke(handle, "exists");
            if (!(ex instanceof Boolean) || !((Boolean) ex).booleanValue()) {
                return Result.no("exists-false");
            }
            invoke(handle, "list");
            invoke(handle, "file");
            try {
                Method map = fi.getMethod("map", FileChannel.MapMode.class);
                Object buf = map.invoke(handle, FileChannel.MapMode.READ_ONLY);
                if (buf == null) return Result.no("map-null");
            } catch (NoSuchMethodError e) {
                return Result.no("nomethod:map");
            }
            try {
                Method afd = fi.getMethod("getAssetFileDescriptor");
                afd.invoke(handle);
            } catch (NoSuchMethodError e) {
                return Result.no("nomethod:getAssetFileDescriptor");
            }
            String fp = "probe:" + apk.length() + ":" + overrideDex.length();
            return new Result(true, fp, "ok");
        } catch (NoSuchMethodError e) {
            return Result.no("nomethod:" + e.getMessage());
        } catch (NoClassDefFoundError e) {
            return Result.no("noclass:" + e.getMessage());
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) c = c.getCause();
            return Result.no("probe:" + c.getClass().getSimpleName() + ":" + c.getMessage());
        }
    }

    private static Object invoke(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    private static byte[] readAll(Object in) throws Exception {
        if (!(in instanceof InputStream)) return null;
        InputStream is = (InputStream) in;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
            byte[] buf = new byte[512];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }

    private static Object enumValue(Class<?> cls, String name) throws Exception {
        Object[] all = cls.getEnumConstants();
        if (all == null) throw new IllegalStateException("not enum");
        for (Object o : all) {
            if (String.valueOf(o).equals(name)) return o;
        }
        return all[0];
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
