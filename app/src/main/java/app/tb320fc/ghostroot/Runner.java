package app.tb320fc.ghostroot;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * 用 Shizuku（shell 身份）把随包的 exploit 推到 /data/local/tmp 并运行。
 * 需要 shell 的原因：/data/local/tmp 只能由 shell 写，reboot 也只有 shell 权限。
 *
 * 刻意全部用绝对路径（/system/bin/sh、/system/bin/timeout）：Shizuku 拉起的
 * shell 环境变量和我们想的不一定一样，靠 PATH 找命令是之前失败过的一个坑。
 */
public final class Runner {

    public enum Result { ROOTED, FAILED, NEED_REBOOT, NO_SHIZUKU, PUSH_FAILED, ERROR }

    public interface Listener {
        void onLog(String line);

        void onPhase(String text);
    }

    public static final String BIN = "/data/local/tmp/ghostlock-dev";
    public static final String PROFILE = "/data/local/tmp/profile.bin";
    private static final String SH = "/system/bin/sh";

    private static final long RUN_TIMEOUT_MS = 125_000L;
    private static final long KSU_WAIT_MS = 25_000L;

    /** 最近一次失败的简短原因，给界面显示。 */
    private static String lastFailure = "";

    public static String lastFailure() {
        return lastFailure;
    }

    public static boolean shizukuAlive() {
        try {
            return Shizuku.pingBinder() && Shizuku.getBinder() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static IShizukuService service() {
        try {
            return IShizukuService.Stub.asInterface(Shizuku.getBinder());
        } catch (Throwable t) {
            return null;
        }
    }

    private static IRemoteProcess sh(String cmd) throws Exception {
        IShizukuService svc = service();
        if (svc == null) throw new IllegalStateException("Shizuku service unavailable");
        return svc.newProcess(new String[]{SH, "-c", cmd}, null, null);
    }

    private static InputStream in(IRemoteProcess p) throws Exception {
        return new ParcelFileDescriptor.AutoCloseInputStream(p.getInputStream());
    }

    private static OutputStream out(IRemoteProcess p) throws Exception {
        return new ParcelFileDescriptor.AutoCloseOutputStream(p.getOutputStream());
    }

    /** 跑一条命令并返回 (退出码, 输出)；用于校验推送结果等短命令。 */
    private static String runForOutput(String cmd, int timeoutMs) {
        IRemoteProcess p = null;
        try {
            p = sh(cmd);
            final IRemoteProcess fp = p;
            final StringBuilder sb = new StringBuilder();
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in(fp)))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                } catch (Throwable ignored) {
                }
            });
            t.setDaemon(true);
            t.start();
            p.waitFor();
            t.join(timeoutMs);
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 给状态探测用：以 shell 身份跑一条命令并拿输出（失败返回空串）。 */
    public static String shellOut(String cmd) {
        return hasPermission() ? runForOutput(cmd, 6000) : "";
    }

    public static boolean pushAssets(Context ctx, Listener l) {
        return pushAsset(ctx, "ghostlock-dev", BIN, true, l)
                && pushAsset(ctx, "profile.bin", PROFILE, false, l);
    }

    /**
     * 通过 shell 的 stdin 写文件；写完用 stat 校验字节数，避免"看起来推成功但被截断"
     * 这种以前踩过的坑（exploit 会因为读不到完整 profile 而立刻失败）。
     */
    private static boolean pushAsset(Context ctx, String asset, String dest, boolean exec, Listener l) {
        AssetManager am = ctx.getAssets();
        long expect;
        IRemoteProcess p = null;
        try {
            expect = assetSize(am, asset);
            p = sh("/system/bin/cat > " + dest + ".tmp && /system/bin/chmod 755 " + dest + ".tmp"
                    + " && /system/bin/mv " + dest + ".tmp " + dest
                    + (exec ? "" : " && /system/bin/chmod 644 " + dest));
            try (OutputStream os = out(p); InputStream is = am.open(asset)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
            } catch (Throwable ignored) {
                // 进程退出会断管道，属正常
            }
            int rc = p.waitFor();
            if (rc != 0) {
                lastFailure = "推送 " + asset + " 返回 " + rc;
                if (l != null) l.onLog("[app] " + lastFailure);
                return false;
            }
            String size = runForOutput("/system/bin/stat -c %s " + dest, 5000);
            if (!size.isEmpty() && !size.equals(String.valueOf(expect))) {
                lastFailure = asset + " 大小不符：期望 " + expect + " 实际 " + size;
                if (l != null) l.onLog("[app] " + lastFailure);
                return false;
            }
            if (l != null) l.onLog("[app] 已推送 " + asset + " (" + expect + " B)");
            return true;
        } catch (Throwable t) {
            lastFailure = "推送 " + asset + " 失败: " + t;
            if (l != null) l.onLog("[app] " + lastFailure);
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static long assetSize(AssetManager am, String asset) {
        try (InputStream is = am.open(asset)) {
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) total += n;
            return total;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 跑一次 exploit。 */
    public static Result attempt(Listener l) {
        lastFailure = "";
        if (!shizukuAlive()) {
            lastFailure = "Shizuku 未运行";
            return Result.NO_SHIZUKU;
        }
        if (!hasPermission()) {
            lastFailure = "没有 Shizuku 授权";
            return Result.NO_SHIZUKU;
        }

        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final IRemoteProcess p;
        try {
            // 有的 ROM/环境没有 timeout 命令；没有就自己管超时（下面 deadline 会 kill）
            boolean hasTimeout = !runForOutput("/system/bin/ls /system/bin/timeout 2>/dev/null", 3000).isEmpty();
            String prefix = hasTimeout ? "/system/bin/timeout 115 " : "";
            if (!hasTimeout) l.onLog("[app] 设备上没有 /system/bin/timeout，改由 App 侧计时");
            p = sh(prefix + BIN + " --load-prebuilt-profile " + PROFILE + " 2>&1");
        } catch (Throwable t) {
            lastFailure = "启动 exploit 失败: " + t;
            l.onLog("[app] " + lastFailure);
            return Result.ERROR;
        }

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in(p)))) {
                String line;
                while ((line = r.readLine()) != null) lines.offer(line);
            } catch (Throwable ignored) {
            }
        }, "ghostlock-reader");
        reader.setDaemon(true);
        reader.start();

        boolean rootedSeen = false;
        boolean ksuSeen = false;
        boolean reachedRace = false;
        long deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS;
        long ksuDeadline = 0;

        while (true) {
            long now = System.currentTimeMillis();
            String line = null;
            try {
                line = lines.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            if (line != null) {
                l.onLog(line);
                if (line.contains("child is root")) rootedSeen = true;
                if (line.contains("KernelSU ready") || line.contains("KernelSU module loaded")) ksuSeen = true;
                if (line.contains("pselect pre-select")) reachedRace = true;
                if (rootedSeen && ksuDeadline == 0) ksuDeadline = now + KSU_WAIT_MS;
                if (rootedSeen && ksuSeen) {
                    destroy(p);
                    return Result.ROOTED;
                }
            }
            if (rootedSeen && ksuDeadline > 0 && now > ksuDeadline) {
                destroy(p);
                return Result.ROOTED;   // 拿到 uid 0 就算成功
            }
            if (now > deadline) {
                l.onPhase("超时，清理进程");
                destroy(p);
                killLeftovers();
                lastFailure = "超时（竞态卡住）";
                return Result.NEED_REBOOT;
            }
            if (!alive(p) && lines.isEmpty()) break;
        }
        /* 进程刚退出时，读线程可能还在把最后几行塞进队列；不排空就会把"成功"
         * 误判成失败并去重启设备（这会把已经到手的 root 直接扔掉）。 */
        try {
            reader.join(2000);
        } catch (InterruptedException ignored) {
        }
        String line2;
        while ((line2 = lines.poll()) != null) {
            l.onLog(line2);
            if (line2.contains("child is root")) rootedSeen = true;
            if (line2.contains("KernelSU ready") || line2.contains("KernelSU module loaded")) ksuSeen = true;
            if (line2.contains("pselect pre-select")) reachedRace = true;
        }
        if (rootedSeen || Status.rootAliveNow()) {
            killLeftovers();
            return Result.ROOTED;
        }
        lastFailure = reachedRace ? "进入竞态但没写成（PI 路由失败）" : "没走到竞态就失败了（看日志尾部）";
        killLeftovers();
        return reachedRace ? Result.NEED_REBOOT : Result.FAILED;
    }

    private static boolean alive(IRemoteProcess p) {
        try {
            return p.alive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void destroy(IRemoteProcess p) {
        try {
            p.destroy();
        } catch (Throwable ignored) {
        }
    }

    public static void killLeftovers() {
        try {
            IRemoteProcess p = sh("/system/bin/pkill -f ghostlock-dev");
            p.waitFor();
            p.destroy();
        } catch (Throwable ignored) {
        }
    }

    /** shell 身份可以重启设备（shell 有 REBOOT 权限）。 */
    public static void reboot(Listener l) {
        l.onPhase("自动重启设备，开机后会自动接着试…");
        try {
            IRemoteProcess p = sh("/system/bin/svc power reboot || /system/bin/reboot");
            p.waitFor();
            p.destroy();
        } catch (Throwable t) {
            l.onLog("[app] 重启失败: " + t);
        }
    }
}
