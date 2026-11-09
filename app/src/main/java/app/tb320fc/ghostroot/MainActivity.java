package app.tb320fc.ghostroot;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity implements Runner.Listener {

    private static final int REQ_SHIZUKU = 1001;
    private static final int REQ_NOTIF = 1002;
    private static final int MAX_BOOTS = 8;
    private static final String KEY_ROUND = "round";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private TextView title;
    private TextView detail;
    private TextView phase;
    private TextView log;
    private Button runBtn;
    private ScrollView logScroll;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        // 跑的时候别让屏幕/设备进入 idle，竞态对时序很敏感
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Shizuku.addRequestPermissionResultListener(this::onShizukuPermission);
        askNotifications();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (!busy && Status.autoRetry(this)) {
            if (!Runner.shizukuAlive()) {
                // 开机后 Shizuku 还没起来：保留 autoRetry，等它起来再点一下按钮就会自动续跑
                phase.setText("等 Shizuku 启动（App 内「通过无线调试启动」，或 adb 拉起 /data/local/tmp/shizuku）。"
                        + "启动后再点一次按钮，会接着上次的进度继续。");
                return;
            }
            Status.setAutoRetry(this, false);
            start("开机后自动继续");
        }
    }

    private android.view.View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        title = new TextView(this);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        detail = new TextView(this);
        detail.setTextSize(13f);
        detail.setPadding(0, dp(6), 0, dp(6));
        root.addView(detail);

        runBtn = new Button(this);
        runBtn.setText("一键获取临时 root");
        runBtn.setOnClickListener(v -> start("手动"));
        root.addView(runBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        phase = new TextView(this);
        phase.setTextSize(13f);
        phase.setPadding(0, dp(8), 0, dp(4));
        root.addView(phase);

        log = new TextView(this);
        log.setTextSize(10f);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setMovementMethod(new ScrollingMovementMethod());

        logScroll = new ScrollView(this);
        logScroll.addView(log);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(Status.PREFS, Context.MODE_PRIVATE);
    }

    /** Shizuku 没准备好时不要把"开机后续跑"标记清掉，否则这一轮就悄悄结束了。 */
    private void Task_keepAutoRetry() {
        if (!Status.autoRetry(this)) Status.setAutoRetry(this, true);
    }

    private int round() {
        return prefs().getInt(KEY_ROUND, 0);
    }

    private void setRound(int v) {
        prefs().edit().putInt(KEY_ROUND, v).apply();
    }

    // ---------------------------------------------------------------- 状态

    private void refreshStatus() {
        Status s = Status.read(this);
        if (!s.rootAlive && sameBootAsRooted()) s.rootAlive = true;
        if (s.rootAlive) {
            title.setText("已临时 root");
            title.setTextColor(0xFF2E7D32);
        } else {
            title.setText("未 root");
            title.setTextColor(0xFFC62828);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("KernelSU 模块：").append(s.rootAlive ? "已加载（临时 root 生效中）" : "未加载").append('\n');
        sb.append("SELinux：").append(s.selinux).append('\n');
        sb.append("内核：").append(s.kernel).append('\n');
        int r = round();
        if (r > 0) {
            sb.append("本轮进度：第 ").append(r).append(" / ").append(MAX_BOOTS).append(" 次开机尝试\n");
        }
        if (s.lastTime > 0) {
            sb.append("上次尝试：").append(s.lastResult).append(" · ")
                    .append(new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                            .format(new Date(s.lastTime)));
        } else {
            sb.append("上次尝试：无");
        }
        if (s.rootAlive) {
            sb.append("\n\n重启会失效；如需恢复出厂运行状态，直接重启即可。");
        }
        detail.setText(sb.toString());
        runBtn.setEnabled(!busy);
        runBtn.setText(busy ? "正在尝试…" : (s.rootAlive ? "重新获取（其实已 root）" : "一键获取临时 root"));

        /* 应用读不到 /data/local/tmp（SELinux/shell_data_file），/sys/module/kernelsu
         * 在部分内核上也不生成，所以本地判断可能漏。这里用 Shizuku 以 shell 身份
         * 直接查 /proc/modules 兜底，查到就记下 boot_id。 */
        if (!s.rootAlive && !busy && Runner.shizukuAlive() && Runner.hasPermission()) {
            new Thread(this::probeRootViaShell, "ghostroot-probe").start();
        }
    }

    private boolean sameBootAsRooted() {
        String saved = Status.savedRootBoot(this);
        String cur = Status.currentBootId();
        return !saved.isEmpty() && !cur.isEmpty() && saved.equals(cur);
    }

    private void probeRootViaShell() {
        try {
            String cnt = Runner.shellOut("/system/bin/grep -c '^kernelsu ' /proc/modules").trim();
            boolean loaded = !cnt.isEmpty() && !"0".equals(cnt);
            String bootId = Runner.shellOut("/system/bin/cat /proc/sys/kernel/random/boot_id").trim();
            if (loaded) {
                Status.markRooted(this, bootId);
                ui.post(this::refreshStatus);
            } else if (!bootId.isEmpty() && !bootId.equals(Status.savedRootBoot(this))) {
                Status.clearRooted(this);   // 换了一次开机，旧记录作废
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 运行

    private void start(String why) {
        android.util.Log.i("GhostRoot", "start why=" + why + " busy=" + busy
                + " alive=" + Runner.shizukuAlive() + " perm=" + Runner.hasPermission());
        if (busy) return;
        if (!Runner.shizukuAlive()) {
            Task_keepAutoRetry();
            phase.setTextColor(Color.RED);
            phase.setText("需要 Shizuku：先启动 Shizuku（App 内「通过无线调试启动」，"
                    + "或用 adb 拉起 /data/local/tmp/shizuku），再回来点一次（会接着上次进度）。");
            return;
        }
        if (!Runner.hasPermission()) {
            try {
                Shizuku.requestPermission(REQ_SHIZUKU);
                phase.setText("请在 Shizuku 弹窗里选「始终允许」…");
            } catch (Throwable t) {
                phase.setText("请求 Shizuku 权限失败：" + t);
            }
            return;
        }
        if (why.startsWith("手动")) {
            setRound(0);   // 手动点按钮 = 新一轮
        }
        int r = round() + 1;
        setRound(r);
        if (r > MAX_BOOTS) {
            setRound(0);
            phase.setTextColor(Color.DKGRAY);
            phase.setText("已经连续尝试 " + MAX_BOOTS + " 次开机都没成功，先停下。"
                    + "建议：稍后再试，或把 App 日志（见 README）发我看看。");
            return;
        }

        busy = true;
        runBtn.setEnabled(false);
        log.setText("");
        recent.clear();
        refreshStatus();
        append("=== 第 " + r + "/" + MAX_BOOTS + " 次尝试（" + why + "）===");

        new Thread(this::runLoop, "ghostlock-run").start();
    }

    /** 一次开机只跑一次：失败就重启（同一开机内重试几乎无效）。 */
    private void runLoop() {
        android.util.Log.i("GhostRoot", "runLoop begin");
        boolean pushed = Runner.pushAssets(this, this);
        android.util.Log.i("GhostRoot", "pushAssets=" + pushed);
        if (!pushed) {
            failed("推送失败：" + Runner.lastFailure(), false);
            return;
        }
        Runner.Result r = Runner.attempt(this);
        android.util.Log.i("GhostRoot", "attempt=" + r + " reason=" + Runner.lastFailure());
        switch (r) {
            case ROOTED:
                setRound(0);
                Status.setAutoRetry(this, false);
                finish("成功", "临时 root 已就绪");
                return;
            case NO_SHIZUKU:
                setRound(0);
                finish("中止", "Shizuku 不可用或未授权：" + Runner.lastFailure());
                return;
            case NEED_REBOOT:
            case FAILED:
            case PUSH_FAILED:
            case ERROR:
            default:
                failed(Runner.lastFailure(), true);
                break;
        }
    }

    /** 从最近日志里挑几条能说明问题的，写进失败提示（方便截图/事后看）。 */
    private String tailSummary() {
        StringBuilder sb = new StringBuilder();
        synchronized (recent) {
            int n = 0;
            for (String line : recent) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("[*]") || t.startsWith("[+]") || t.startsWith("[-]")
                        || t.contains("not found") || t.contains("denied")
                        || t.contains("failed") || t.contains("failed")) {
                    sb.append("  ").append(t.length() > 90 ? t.substring(0, 90) : t).append('\n');
                    if (++n >= 5) break;
                }
            }
        }
        return sb.toString();
    }

    private void failed(String reason, boolean reboot) {
        if (Status.rootAliveNow()) {
            // 日志判定失败但 root 其实已经在了（例如最后几行没读到）：别重启，直接算成功
            setRound(0);
            Status.setAutoRetry(this, false);
            finish("成功", "临时 root 已就绪（以设备状态为准）");
            return;
        }
        Status.remember(this, "失败");
        String tail = tailSummary();
        append("--- 失败摘要 ---");
        if (!tail.isEmpty()) append(tail.trim());
        ui.post(() -> {
            busy = false;
            phase.setTextColor(0xFFC62828);
            String t = tailSummary();
            phase.setText("本次失败：" + reason + (reboot ? "（即将自动重启）" : "")
                    + (t.isEmpty() ? "" : "\n日志尾部：\n" + t));
            runBtn.setEnabled(false);
            refreshStatus();
        });
        if (!reboot) return;
        Status.setAutoRetry(this, true);
        append("--- 失败：" + reason + "，自动重启后继续 ---");
        Runner.reboot(this);
    }

    private void finish(String result, String msg) {
        Status.remember(this, result);
        if (result.equals("成功")) {
            String bootId = Runner.shellOut("/system/bin/cat /proc/sys/kernel/random/boot_id").trim();
            Status.markRooted(this, bootId);
        }
        ui.post(() -> {
            busy = false;
            phase.setTextColor(result.equals("成功") ? 0xFF2E7D32 : Color.DKGRAY);
            phase.setText(msg);
            refreshStatus();
            if (result.equals("成功")) {
                append("=== 成功：打开 KernelSU 给需要的 App 授权 su ===");
            }
        });
    }

    // ---------------------------------------------------------------- 回调

    @Override
    public void onLog(String line) {
        append(line);
    }

    @Override
    public void onPhase(String text) {
        ui.post(() -> phase.setText(text));
    }

    private void append(String line) {
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > 30) recent.removeFirst();
        }
        logToFile(line);
        ui.post(() -> {
            log.append(line);
            log.append("\n");
            logScroll.post(() -> logScroll.fullScroll(android.view.View.FOCUS_DOWN));
        });
    }

    /** 同时落盘，方便事后 adb pull 或发给别人看。 */
    private void logToFile(String line) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            try (FileWriter w = new FileWriter(new File(dir, "ghostroot.log"), true)) {
                w.write(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
                w.write(' ');
                w.write(line);
                w.write('\n');
            }
        } catch (Throwable ignored) {
        }
    }

    private void onShizukuPermission(int code, int grantResult) {
        if (code != REQ_SHIZUKU) return;
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            ui.post(() -> start("已授权"));
        } else {
            ui.post(() -> phase.setText("Shizuku 权限被拒绝"));
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }
}
