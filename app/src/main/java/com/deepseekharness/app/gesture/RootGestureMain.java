package com.deepseekharness.app.gesture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.HapticFeedbackConstants;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/** Runs in a separate uid=0 app_process. stdout is a private pipe to our APK, never a socket. */
public final class RootGestureMain {
    private static Object monitor;
    private static InputEventReceiver receiver;
    private static Context context;
    private static CornerGestureRecognizer recognizer;
    private static Display display;
    private static Method pilfer;
    private static boolean captured;
    private static int frames;
    private static Method eventDisplayId;
    private static android.os.Vibrator vibrator;
    private static Method hapticFeedback;

    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0) throw new SecurityException("Root uid required");
            HiddenApiBypass.addHiddenApiExemptions("");
            Looper.prepareMainLooper();
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            context = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
            vibrator = context.getSystemService(android.os.Vibrator.class);
            try {
                hapticFeedback = android.os.Vibrator.class.getMethod("performHapticFeedback",
                        int.class, String.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
                // Older Android releases use the equivalent predefined click below.
            }
            display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            eventDisplayId = InputEvent.class.getMethod("getDisplayId");
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            recognizer = new CornerGestureRecognizer(metrics.density);
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Object im = imClass.getMethod("getInstance").invoke(null);
            monitor = imClass.getMethod("monitorGestureInput", String.class, int.class)
                    .invoke(im, "DSH Corner Gesture Preview", Display.DEFAULT_DISPLAY);
            pilfer = monitor.getClass().getMethod("pilferPointers");
            InputChannel channel = (InputChannel) monitor.getClass().getMethod("getInputChannel").invoke(monitor);
            receiver = new InputEventReceiver(channel, Looper.myLooper()) {
                @Override public void onInputEvent(InputEvent event) {
                    try {
                        if (event instanceof MotionEvent) touch((MotionEvent) event);
                    } catch (Throwable error) {
                        emit("ERROR " + reason(error));
                        new android.os.Handler(Looper.getMainLooper()).post(RootGestureMain::shutdown);
                    } finally {
                        finishInputEvent(event, true);
                    }
                }
            };
            // Input remains active until the switch is disabled or the APK's pipe closes.
            Thread owner = new Thread(() -> {
                try {
                    BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
                    while (true) {
                        String line = input.readLine();
                        if (line == null || "STOP".equals(line)) break;
                    }
                } catch (Exception ignored) {
                } finally {
                    new android.os.Handler(Looper.getMainLooper()).post(RootGestureMain::shutdown);
                }
            }, "dsh-gesture-owner");
            owner.setDaemon(true);
            owner.start();
            emit("READY uid=0 threshold=55dp corners=48dp height=25dp");
            if (args.length > 0 && "--probe".equals(args[0])) return;
            Looper.loop();
        } catch (Throwable error) {
            emit("ERROR " + reason(error));
        } finally {
            dispose();
        }
    }

    private static void touch(MotionEvent event) throws Exception {
        if ((int) eventDisplayId.invoke(event) != Display.DEFAULT_DISPLAY) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            Point size = new Point();
            display.getRealSize(size);
            PowerManager power = context.getSystemService(PowerManager.class);
            android.app.KeyguardManager keyguard = context.getSystemService(android.app.KeyguardManager.class);
            captured = false;
            recognizer.cancel();
            if (!power.isInteractive() || keyguard.isKeyguardLocked()) return;
            // First preview only owns the portrait bottom corners. Rotation cancels on the next DOWN.
            if (size.x >= size.y) return;
            if (recognizer.begin(event.getRawX(), event.getRawY(), size.x, size.y)) {
                frames = 0;

            }
            return;
        }
        if (!recognizer.isTracking()) return;
        if (event.getPointerCount() != 1 || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_CANCEL) {
            recognizer.end();
            emit("CANCEL");
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            // Reserve only streams that started in our corners. Do not wait until the 55dp launch.
            if (!captured) {
                pilfer.invoke(monitor);
                captured = true;
            }
            boolean launched = recognizer.isTriggered();
            recognizer.move(event.getRawX(), event.getRawY());
            frames++;
            if (!launched && recognizer.isTriggered()) {
                triggerHapticFeedback();
                emit("TRIGGER " + (recognizer.isLeft() ? "L" : "R"));
                Intent intent = new Intent(Intent.ACTION_ASSIST);
                intent.setComponent(new ComponentName("com.dsha.fr", "com.deepseekharness.app.ui.AssistGatewayActivity"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                launchQuickChat(intent);
                emit("LAUNCHED frames=" + frames);
            }
        } else if (action == MotionEvent.ACTION_UP) {
            boolean triggered = recognizer.isTriggered();
            recognizer.end();
            emit(triggered ? "END" : "CANCEL");
        }
    }

    private static void triggerHapticFeedback() {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (hapticFeedback != null) {
                // Same semantic feedback as launcher View.performHapticFeedback(VIRTUAL_KEY).
                // flags=0 preserves the system's haptic setting and vendor effect mapping.
                hapticFeedback.invoke(vibrator, HapticFeedbackConstants.VIRTUAL_KEY,
                        "DSH corner gesture threshold", 0, 0);
            } else if (android.provider.Settings.System.getInt(context.getContentResolver(),
                    android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
                vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK),
                        new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
            }
            emit("HAPTIC VIRTUAL_KEY");
        } catch (Throwable error) {
            // Missing OEM feedback support must never prevent QuickChat from opening.
            emit("WARN haptic " + reason(error));
        }
    }

    private static void launchQuickChat(Intent intent) throws Exception {
        // A standalone app_process has no registered ApplicationThread. Supply a null caller.
        Class<?> atm = Class.forName("android.app.ActivityTaskManager");
        Object service = atm.getMethod("getService").invoke(null);
        Class<?> api = Class.forName("android.app.IActivityTaskManager");
        for (Method method : api.getMethods()) {
            if (!method.getName().equals("startActivityAsUser") || method.getParameterCount() != 12) continue;
            int result = (int) method.invoke(service, null, "com.android.shell", null, intent,
                    null, null, null, 0, 0, null, null, 0);
            if (result < 0) throw new IllegalStateException("Activity launch result " + result);
            return;
        }
        throw new NoSuchMethodException("IActivityTaskManager.startActivityAsUser");
    }

    private static synchronized void emit(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static String reason(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return (error.getClass().getSimpleName() + ": " + error.getMessage()).replace('\n', ' ');
    }

    private static void shutdown() {
        dispose();
        // prepareMainLooper owns a non-quittable queue. Exit only this standalone helper.
        System.exit(0);
    }

    private static synchronized void dispose() {
        if (receiver != null) {
            receiver.dispose();
            receiver = null;
        }
        if (monitor != null) {
            try { monitor.getClass().getMethod("dispose").invoke(monitor); } catch (Throwable ignored) {}
            monitor = null;
        }
    }
}
