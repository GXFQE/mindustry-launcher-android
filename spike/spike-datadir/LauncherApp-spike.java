package io.mdt.launcher;

import android.app.Application;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;

/**
 * Application。onCreate 在主进程与 :game 进程各跑一次 —— 必须保持轻量
 * （只做 Config 加载；M2 的注入流程不在 App 里做，全在 GameSlot）。
 */
public class LauncherApp extends Application {
    public static final String TAG = "MDTLauncher";

    @Override
    public void onCreate() {
        super.onCreate();
        // 历史版本把私有数据误放 getFilesDir()（会被游戏在首次启动时复制进数据根），
        // 这里幂等地搬到 app_hub。必须在 Config.load 之前做。
        Paths.migrateFromLegacy(this);
        Config.get().load(this);
        spikeInjectDataDir();
        Log.i(TAG, "app up, privateDir=" + Paths.privateDir(this)
                + " pid=" + android.os.Process.myPid());
    }

    /**
     * ★ SPIKE（2026-10-01）：验证能否用 System.setProperty("mindustry.data.dir", ...)
     * 免掉 M3 的「目录改名交换」。
     *
     * 原理链（三处源码已核实）：
     *   1) ClientLauncher.setup() 第一件事就读该属性：
     *        String dataDir = System.getProperty("mindustry.data.dir", OS.env("MINDUSTRY_DATA_DIR"));
     *        if(dataDir != null) Core.settings.setDataDirectory(files.absolute(dataDir));
     *   2) 安卓**确实**实例化 ClientLauncher（AndroidLauncher.java:58
     *      `initialize(new ClientLauncher(){...})`）⇒ 上面这段在安卓上会执行。
     *   3) AndroidLauncher.onCreate() 里那行
     *      `setDataDirectory(getExternalFilesDir(null))` 跑在**主线程**、
     *      setup() 之前 ⇒ setup() 这次会**覆盖**它。
     *   4) Application.onCreate 又早于任何 Activity ⇒ 这是最靠前的注入点。
     *
     * 零副作用设计：只在 :game 进程、且开关文件存在时生效；内容写空行 = 显式清除。
     * 结果落 spike-result.txt（本 ROM 的 main logcat 被滤掉，只能写文件）。
     */
    private void spikeInjectDataDir() {
        if (!isGameProcess()) return;
        File out = null;
        try {
            out = new File(getExternalFilesDir(null), SPIKE_RESULT);
            File sw = new File(getExternalFilesDir(null), SPIKE_SWITCH);
            if (!sw.exists()) return;

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(sw), "UTF-8"));
            String line = r.readLine();
            r.close();
            String path = line == null ? "" : line.trim();

            if (path.isEmpty()) {
                System.clearProperty("mindustry.data.dir");
                write(out, "spike: switch empty -> cleared property\n");
                return;
            }
            File dir = new File(path);
            boolean made = dir.exists() || dir.mkdirs();
            System.setProperty("mindustry.data.dir", path);
            write(out, "---- " + new java.util.Date() + "\n"
                    + "spike: set mindustry.data.dir=" + path + "\n"
                    + "  dir exists/created = " + made + "\n"
                    + "  process = " + readCmdline() + "\n"
                    + "  pid = " + android.os.Process.myPid() + "\n");
        } catch (Throwable t) {
            try { if (out != null) write(out, "spike FAILED: " + t + "\n"); } catch (Throwable ignored) {}
            Log.e(TAG, "spike failed", t);
        }
    }

    private static void write(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f, true);
        try {
            o.write(s.getBytes("UTF-8"));
            o.flush();
        } finally {
            o.close();
        }
    }

    /** /proc/self/cmdline 是 NUL 分隔的；返回进程名（主进程 = 包名，子进程 = 包名:xxx）。 */
    private static String readCmdline() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/self/cmdline"));
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = r.read()) > 0) sb.append((char) c);
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean isGameProcess() {
        return readCmdline().endsWith(":game");
    }

    /** ★ SPIKE 开关文件名：存在且首行非空时，把该行当作数据根注入给游戏。 */
    public static final String SPIKE_SWITCH = "spike_datadir.txt";
    public static final String SPIKE_RESULT = "spike-result.txt";
}
