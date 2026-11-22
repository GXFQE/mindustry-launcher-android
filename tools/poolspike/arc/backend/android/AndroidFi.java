package arc.backend.android;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import arc.Files.FileType;
import arc.files.Fi;

/**
 * spike：同名类替换 arc 的 AndroidFi。internal 路径先查对象池，查不到回落原始 assets。
 * 非 internal 一律走父类（文件系统），与原实现一致。
 *
 * 只依赖 JDK + arc + android.*；池映射由 {@link #init(Map)} 在加载后注入，
 * 避免跨 classloader 引用 launcher 自己的类。
 */
public class AndroidFi extends Fi {

    private static final String TAG = "MDT-PoolFi";
    private static int hits;
    private static int misses;

    private static Map<String, String> pool = new HashMap<String, String>();
    private static Map<String, List<String>> kids = new HashMap<String, List<String>>();
    private static boolean inited;

    private final AssetManager assets;

    public static void init(Map<String, String> paths) {
        Map<String, String> p = new HashMap<String, String>(paths);
        Map<String, List<String>> k = new HashMap<String, List<String>>();
        for (String path : p.keySet()) {
            int i = path.lastIndexOf('/');
            String dir = i < 0 ? "" : path.substring(0, i);
            String name = i < 0 ? path : path.substring(i + 1);
            let(k, dir).add(name);
            String d = dir;
            while (d.length() > 0) {
                int j = d.lastIndexOf('/');
                d = j < 0 ? "" : d.substring(0, j);
                if (!k.containsKey(d)) k.put(d, new ArrayList<String>());
            }
            if (!k.containsKey("")) k.put("", new ArrayList<String>());
        }
        pool = p;
        kids = k;
        inited = true;
        Log.i(TAG, "init pool=" + p.size() + " dirs=" + k.size() + " loader=" + AndroidFi.class.getClassLoader());
    }

    /** 读池的账（spike 用；游戏跑一会儿之后由探针打出来）。 */
    public static String stats() {
        return "hits=" + hits + " misses=" + misses + " pool=" + pool.size();
    }

    public static int poolSize() {
        return pool.size();
    }

    public static boolean inited() {
        return inited;
    }

    private static List<String> let(Map<String, List<String>> m, String key) {
        List<String> v = m.get(key);
        if (v == null) {
            v = new ArrayList<String>();
            m.put(key, v);
        }
        return v;
    }

    public AndroidFi(AssetManager assets, File file, FileType type) {
        super(file, type);
        this.assets = assets;
    }

    public AndroidFi(AssetManager assets, String path, FileType type) {
        super(path.replace('\\', '/'), type);
        this.assets = assets;
    }

    /**
     * 原 AndroidFi.file() 对 local 会把相对路径重定位到 getLocalStoragePath() 下
     * （mods / saves 全是 local 类型），不覆盖就会退化成相对路径 ⇒ 游戏找不到文件。
     * 其余类型原实现就是 super.file()，这里照抄。
     */
    @Override
    public File file() {
        if (type == FileType.local) {
            return new File(arc.Core.files.getLocalStoragePath(), file.getPath());
        }
        return super.file();
    }

    private boolean internal() {
        // ★ classpath 也要走池：游戏把 `vfxshaders/*` 这类文件放在 APK **根目录**，
        //   原实现靠 `Fi.read()` 里的 classloader 资源查找（APK 在 dexPath 里才找得到）。
        //   不挂 APK 之后那条路断了，所以把 classpath 和 internal 一起当成"池里读"。
        return type == FileType.internal || type == FileType.classpath;
    }

    /** 逻辑路径在池里的对象文件；不是 internal 或没命中就是 null。 */
    private File obj() {
        if (!internal()) return null;
        String p = path();
        String o = pool.get(p);
        return o == null ? null : new File(o);
    }

    private static Fi plain(File f) {
        return new Fi(f);
    }

    private void note(String what, boolean hit) {
        if (hit) {
            hits++;
            if (hits <= 60) Log.i(TAG, what + " HIT " + path());
        } else {
            misses++;
            if (misses <= 40) Log.w(TAG, what + " MISS " + path() + " -> fallback assets");
        }
    }

    @Override
    public InputStream read() {
        File o = obj();
        if (o != null) {
            note("read", true);
            return plain(o).read();
        }
        if (internal()) note("read", false);
        if (internal() && assets != null) {
            try {
                return assets.open(path());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return super.read();
    }

    @Override
    public long length() {
        File o = obj();
        if (o != null) return o.length();
        if (internal() && assets != null) {
            note("len", false);
            try {
                AssetFileDescriptor afd = assets.openFd(path());
                try {
                    return afd.getLength();
                } finally {
                    afd.close();
                }
            } catch (IOException ignored) {
            }
        }
        return super.length();
    }

    @Override
    public boolean exists() {
        if (obj() != null) return true;
        if (internal()) {
            List<String> c = kids.get(path());
            if (c != null) return true;
        }
        if (internal() && assets != null) {
            String p = path();
            note("exists", false);
            try {
                InputStream in = assets.open(p);
                in.close();
                return true;
            } catch (IOException e) {
                try {
                    String[] l = assets.list(p);
                    return l != null && l.length > 0;
                } catch (IOException ignored) {
                }
            }
        }
        return super.exists();
    }

    @Override
    public boolean isDirectory() {
        if (internal()) {
            List<String> c = kids.get(path());
            if (c != null) return true;
        }
        return super.isDirectory();
    }

    @Override
    public Fi[] list() {
        if (internal()) {
            List<String> c = kids.get(path());
            if (c != null) {
                Fi[] out = new Fi[c.size()];
                for (int i = 0; i < out.length; i++) out[i] = child(c.get(i));
                return out;
            }
        }
        return super.list();
    }

    @Override
    public Fi[] list(FileFilter filter) {
        Fi[] all = list();
        List<Fi> out = new ArrayList<Fi>();
        for (Fi f : all) if (filter == null || filter.accept(f.file())) out.add(f);
        return out.toArray(new Fi[out.size()]);
    }

    @Override
    public Fi[] list(FilenameFilter filter) {
        Fi[] all = list();
        List<Fi> out = new ArrayList<Fi>();
        for (Fi f : all)
            if (filter == null || filter.accept(f.file(), f.name())) out.add(f);
        return out.toArray(new Fi[out.size()]);
    }

    @Override
    public Fi[] list(String suffix) {
        Fi[] all = list();
        List<Fi> out = new ArrayList<Fi>();
        for (Fi f : all) if (suffix == null || f.name().endsWith(suffix)) out.add(f);
        return out.toArray(new Fi[out.size()]);
    }

    /** AndroidFi 独有的方法（Fi 基类没有）。 */
    public AssetFileDescriptor getAssetFileDescriptor() {
        File o = obj();
        if (o != null) {
            note("afd", true);
            try {
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(o, ParcelFileDescriptor.MODE_READ_ONLY);
                return new AssetFileDescriptor(pfd, 0, o.length());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (internal() && assets != null) {
            note("afd", false);
            try {
                return assets.openFd(path());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    @Override
    public ByteBuffer map(FileChannel.MapMode mode) {
        File o = obj();
        if (o != null) {
            note("map", true);
            return plain(o).map(mode);
        }
        if (internal()) note("map", false);
        return super.map(mode);
    }

    @Override
    public Fi child(String name) {
        if (name == null) return this;
        String n = name.replace('\\', '/');
        return file.getPath().length() == 0
                ? new AndroidFi(assets, new File(n), type)
                : new AndroidFi(assets, new File(file, n), type);
    }

    @Override
    public Fi parent() {
        File p = file.getParentFile();
        return p == null ? new AndroidFi(assets, new File("."), type) : new AndroidFi(assets, p, type);
    }

    @Override
    public Fi sibling(String name) {
        return parent().child(name);
    }
}
