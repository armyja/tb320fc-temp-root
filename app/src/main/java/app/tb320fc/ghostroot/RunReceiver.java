package app.tb320fc.ghostroot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 无界面入口，方便自动化和排障：
 *   adb shell am broadcast -a app.tb320fc.ghostroot.RUN
 *   adb shell am broadcast -a app.tb320fc.ghostroot.STATUS
 * 结果写到 /sdcard/Android/data/app.tb320fc.ghostroot/files/ghostroot.log（shell 可读）。
 */
public final class RunReceiver extends BroadcastReceiver {

    static final String ACTION_RUN = "app.tb320fc.ghostroot.RUN";
    static final String ACTION_STATUS = "app.tb320fc.ghostroot.STATUS";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        Log.i("GhostRoot", "RunReceiver " + action);
        if (ACTION_STATUS.equals(action)) {
            Status s = Status.read(ctx);
            write(ctx, "status root=" + s.rootAlive + " selinux=" + s.selinux
                    + " kernel=" + s.kernel + " last=" + s.lastResult);
            return;
        }
        if (!ACTION_RUN.equals(action)) return;

        write(ctx, "run: requested");
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                if (!Runner.shizukuAlive()) {
                    write(ctx, "run: Shizuku 未运行/未安装");
                    return;
                }
                if (!Runner.hasPermission()) {
                    write(ctx, "run: 需要先在 Shizuku 里给本应用授权（打开 Shizuku → 应用列表）");
                    return;
                }
                final Runner.Listener l = new LogListener(ctx);
                if (!Runner.pushAssets(ctx, l)) {
                    write(ctx, "run: 推送失败");
                    return;
                }
                for (int i = 1; i <= 3; i++) {
                    write(ctx, "run: 第 " + i + " 次");
                    Runner.Result r = Runner.attempt(l);
                    write(ctx, "run: 结果 " + r);
                    if (r == Runner.Result.ROOTED) {
                        Status.remember(ctx, "成功");
                        return;
                    }
                    if (r == Runner.Result.NEED_REBOOT) {
                        Status.setAutoRetry(ctx, true);
                        Runner.reboot(l);
                        return;
                    }
                }
                Status.remember(ctx, "失败");
            } catch (Throwable t) {
                write(ctx, "run: 异常 " + t);
            } finally {
                pending.finish();
            }
        }, "ghostroot-run").start();
    }

    private static final class LogListener implements Runner.Listener {
        private final Context ctx;

        LogListener(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onLog(String line) {
            write(ctx, line);
        }

        @Override
        public void onPhase(String text) {
            write(ctx, "phase: " + text);
        }
    }

    private static synchronized void write(Context ctx, String line) {
        Log.i("GhostRoot", line);
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (FileWriter w = new FileWriter(new File(dir, "ghostroot.log"), true)) {
                w.write(line);
                w.write('\n');
            }
        } catch (IOException ignored) {
        }
    }
}
