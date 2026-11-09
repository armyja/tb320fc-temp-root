package app.tb320fc.ghostroot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后如果上次是“自动重启续跑”，弹一条通知把用户带回 App。 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "ghostroot";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        android.util.Log.i("GhostRoot", "BootReceiver action=" + intent.getAction()
                + " autoRetry=" + Status.autoRetry(ctx));
        if (!Status.autoRetry(ctx)) {
            // 手动/调试触发时也给个反馈，便于确认组件可达
            android.util.Log.i("GhostRoot", "autoRetry off, nothing to do");
            return;
        }

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "临时 root",
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(ch);

        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("临时 root 中断：设备已重启")
                .setContentText("点此继续自动尝试（约 3 次）")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(1, n);

        // 尽量直接拉回前台；被系统拦下时上面的通知仍可点
        try {
            ctx.startActivity(open);
        } catch (Throwable ignored) {
        }
    }
}
