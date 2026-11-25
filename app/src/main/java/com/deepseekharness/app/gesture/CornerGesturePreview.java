package com.deepseekharness.app.gesture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.deepseekharness.app.core.ConfigStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** APK-owned Root input session. No overlay, boot receiver, polling, HTTP or system hook. */
public final class CornerGesturePreview {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "dsh-corner-gesture");
        thread.setDaemon(true);
        return thread;
    });
    private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static volatile Process process;
    private static volatile long generation;
    private static volatile String status = "从左右底角滑动，唤起快捷抽屉（需 Root）";
    private static volatile boolean starting;
    private static volatile boolean running;
    private static Context app;

    private CornerGesturePreview() {}

    public static boolean isEnabled(Context context) {
        return new ConfigStore(context).isCornerGestureEnabled();
    }

    public static boolean isStarting() { return starting; }
    public static boolean isRunning() { return running; }
    public static String getStatus() { return status; }
    public static void addListener(Runnable listener) { LISTENERS.add(listener); }
    public static void removeListener(Runnable listener) { LISTENERS.remove(listener); }

    /** Called on APK startup only when the user previously enabled the switch. */
    public static void restore(Context context) {
        if (isEnabled(context)) start(context);
    }

    public static synchronized void setEnabled(Context context, boolean enabled) {
        app = context.getApplicationContext();
        new ConfigStore(app).setCornerGestureEnabled(enabled);
        if (enabled) start(app);
        else stop();
    }

    private static synchronized void start(Context context) {
        if (starting || running) return;
        app = context.getApplicationContext();
        starting = true;
        status = "正在请求 Root 授权…";
        long ticket = ++generation;
        notifyChanged();
        StringBuilder classpath = new StringBuilder(app.getApplicationInfo().sourceDir);
        String[] splits = app.getApplicationInfo().splitSourceDirs;
        if (splits != null) for (String split : splits) classpath.append(':').append(split);
        String command = "CLASSPATH=" + quote(classpath.toString())
                + " exec /system/bin/app_process /system/bin " + RootGestureMain.class.getName();
        IO.execute(() -> runSession(ticket, command));
    }

    private static void runSession(long ticket, String command) {
        Process child = null;
        Runnable timeout = () -> {
            if (generation == ticket && starting) fail(ticket, "Root 授权超时，请重新开启");
        };
        try {
            child = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            synchronized (CornerGesturePreview.class) {
                if (ticket != generation) { child.getOutputStream().close(); child.destroy(); return; }
                process = child;
            }
            MAIN.postDelayed(timeout, 30_000);
            BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream()));
            String line;
            String lastError = "Root 手势服务已退出，请重新开启";
            while ((line = output.readLine()) != null) {
                if (generation != ticket) break;
                if (line.startsWith("ERROR ")) lastError = line.substring(6);
                final String message = line;
                MAIN.post(() -> { if (generation == ticket) handle(ticket, message); });
            }
            child.waitFor();
            final String detail = lastError;
            MAIN.post(() -> fail(ticket, detail));
        } catch (Exception error) {
            String detail = error.getClass().getSimpleName() + ": " + error.getMessage();
            MAIN.post(() -> fail(ticket, detail));
        } finally {
            MAIN.removeCallbacks(timeout);
            if (child != null) {
                try { child.getOutputStream().close(); } catch (Exception ignored) {}
                child.destroy();
            }
        }
    }

    private static void handle(long ticket, String message) {
        if (message.startsWith("READY ")) {
            starting = false;
            running = true;
            status = "已开启 · 从左右底角滑动，唤起快捷抽屉";
            notifyChanged();
        } else if (message.startsWith("ERROR ")) {
            fail(ticket, message.substring(6));
        } else if (message.startsWith("HAPTIC")) {
            playHaptic();
        }
    }

    /**
     * The threshold tick is played here, in the APK process. The root helper only signals it:
     * a standalone app_process cannot build Vibrator reliably on Android 17, and uid 0 would
     * bypass the user's haptic setting. View feedback is what the system expects here, so it
     * follows the touch-feedback toggle and the OEM effect mapping on every release.
     */
    private static void playHaptic() {
        Context context = app;
        if (context == null) return;
        try {
            android.os.Vibrator vibrator = context.getSystemService(android.os.Vibrator.class);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (android.provider.Settings.System.getInt(context.getContentResolver(),
                    android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
            vibrator.vibrate(android.os.VibrationEffect.createPredefined(
                    android.os.VibrationEffect.EFFECT_CLICK),
                    new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
        } catch (Throwable ignored) {
            // Feedback is optional; never let it break the gesture session.
        }
    }

    private static synchronized void stop() {
        ++generation;
        Process child = process;
        process = null;
        running = false;
        starting = false;
        status = "从左右底角滑动，唤起快捷抽屉（需 Root）";
        notifyChanged();
        if (child != null) IO.execute(() -> {
            try {
                OutputStreamWriter writer = new OutputStreamWriter(child.getOutputStream());
                writer.write("STOP\n");
                writer.flush();
                child.getOutputStream().close();
                if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroy();
            } catch (Exception ignored) { child.destroy(); }
        });
    }

    private static synchronized void fail(long ticket, String detail) {
        if (generation != ticket) return;
        if (app != null) new ConfigStore(app).setCornerGestureEnabled(false);
        stop();
        status = "未能运行：" + detail;
        notifyChanged();
    }

    private static void notifyChanged() {
        MAIN.post(() -> {
            for (Runnable listener : LISTENERS) listener.run();
        });
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
