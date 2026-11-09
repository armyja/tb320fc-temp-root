package app.tb320fc.ghostroot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** 设备状态判定：临时 root 是否还在、SELinux、内核版本、上次尝试结果。 */
public final class Status {

    public static final String PREFS = "ghostroot";
    public static final String KEY_LAST_RESULT = "last_result";
    public static final String KEY_LAST_TIME = "last_time";
    public static final String KEY_AUTO_RETRY = "auto_retry";
    public static final String KEY_ROOT_BOOT = "root_boot_id";

    public boolean rootAlive;      // KernelSU 模块是否加载（唯一可靠的“当前是否临时 root”信号）
    public String selinux;         // Enforcing / Permissive / 未知
    public String kernel;          // uname -r
    public String lastResult;      // 上次尝试：成功 / 失败 / 中止
    public long lastTime;

    /** /sys/module/<name> 只有模块真正加载后才存在，且对所有进程可读。 */
    public static boolean moduleLoaded(String name) {
        return new File("/sys/module/" + name).isDirectory();
    }

    static String readText(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 只看"此刻是否还有临时 root"，不需要 Context：root 脚本写的 boot_id 标记 + 模块兜底。 */
    public static boolean rootAliveNow() {
        String marker = readText("/data/local/tmp/.ghostlock_status");
        String[] parts = marker.isEmpty() ? new String[0] : marker.split("\\s+");
        String bootId = readText("/proc/sys/kernel/random/boot_id");
        boolean markerAlive = parts.length >= 3
                && !bootId.isEmpty() && parts[0].equals(bootId)
                && !"0".equals(parts[2]);
        return markerAlive || moduleLoaded("kernelsu");
    }

    public static Status read(Context ctx) {
        Status s = new Status();
        s.rootAlive = rootAliveNow();
        s.selinux = selinuxMode();
        s.kernel = readKernel();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        s.lastResult = p.getString(KEY_LAST_RESULT, "");
        s.lastTime = p.getLong(KEY_LAST_TIME, 0L);
        return s;
    }

    /** 先读 /sys/fs/selinux/enforce（这台机器上应用可读），失败再走系统 API。 */
    static String selinuxMode() {
        String enforce = readText("/sys/fs/selinux/enforce");
        if ("1".equals(enforce)) return "Enforcing";
        if ("0".equals(enforce)) return "Permissive";
        try {
            Class<?> c = Class.forName("android.os.SELinux");
            java.lang.reflect.Method m = c.getMethod("isSELinuxEnforced");
            Object enforced = m.invoke(null);
            return Boolean.TRUE.equals(enforced) ? "Enforcing" : "Permissive";
        } catch (Throwable ignored) {
        }
        return "未知";
    }

    public static String readKernel() {
        String v = System.getProperty("os.version", "");
        if (v == null || v.isEmpty()) v = readText("/proc/sys/kernel/osrelease");
        int sp = v.indexOf(' ');
        return sp > 0 ? v.substring(0, sp) : v;
    }

    public static void remember(Context ctx, String result) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_RESULT, result)
                .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                .apply();
    }

    /** 记住"这次开机已经拿到 root"，重启后 boot_id 变了就自动失效。 */
    /** 应用一般能读；读不到时返回空串（调用方会用 Shizuku 兜底）。 */
    public static String currentBootId() {
        return readText("/proc/sys/kernel/random/boot_id");
    }

    public static void markRooted(Context ctx, String bootId) {
        if (bootId == null || bootId.isEmpty()) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ROOT_BOOT, bootId).apply();
    }

    public static String savedRootBoot(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ROOT_BOOT, "");
    }

    public static void clearRooted(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_ROOT_BOOT).apply();
    }

    public static void setAutoRetry(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_RETRY, on).apply();
    }

    public static boolean autoRetry(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_RETRY, false);
    }
}
