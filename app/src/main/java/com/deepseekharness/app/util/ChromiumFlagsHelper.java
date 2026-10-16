package com.deepseekharness.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Chromium 实验性参数助手：
 * 1. 在 APK 冷启动阶段通过 org.chromium.base.CommandLine.init() 进程内极速注入；
 * 2. 同步写入宿主 /data/local/tmp/webview-command-line 并授权 debug-app，为 Android 11~16 系统 WebView 提供双轨加速保障。
 */
public final class ChromiumFlagsHelper {

    private static final String TAG = "ChromiumFlags";
    private static final String[] CHROMIUM_FLAGS = new String[]{
            "_", // Chromium argv[0] 占位符
            "--enable-gpu-rasterization",
            "--enable-zero-copy",
            "--ignore-gpu-blocklist",
            "--enable-features=Vulkan",
            "--webview-draw-functor-uses-vulkan",
            "--disable-low-end-device-mode",
            "--js-flags=--max-old-space-size=2048"
    };

    private static boolean sApplied = false;

    private ChromiumFlagsHelper() {}

    /**
     * 在 Application.onCreate() 或 WebView 初始化前调用，注入进程内 Chromium 启动参数
     */
    public static synchronized void apply(Context context) {
        if (sApplied || context == null) return;
        SharedPreferences sp = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
        boolean enabled = sp.getBoolean(Constants.KEY_CHROMIUM_FLAGS, false);
        if (!enabled) return;

        try {
            Class<?> cmdClass = Class.forName("org.chromium.base.CommandLine");
            Method isInitMethod = cmdClass.getMethod("isInitialized");
            boolean initialized = Boolean.TRUE.equals(isInitMethod.invoke(null));
            if (!initialized) {
                Method initMethod = cmdClass.getMethod("init", String[].class);
                initMethod.invoke(null, (Object) CHROMIUM_FLAGS);
                sApplied = true;
                Log.i(TAG, "已成功通过 org.chromium.base.CommandLine 注入 Chromium 实验性参数");
            }
        } catch (Throwable t) {
            Log.d(TAG, "Chromium CommandLine.init 注入跳过: " + t.getMessage());
        }
    }

    /**
     * 用户切换开关时，同步配置宿主 /data/local/tmp 命令行文件与 debug-app 状态。
     * 注意：严禁使用 am set-debug-app，该命令会直接触发 Android ActivityManager 的 forceStopPackage 强制杀死 App；
     * 采用 settings put global debug_app 直接写入 SettingsProvider，既能让 Chromium 识别，又绝对不会引发前台闪退。
     */
    public static void syncSystemCommandLine(Context context, boolean enabled) {
        if (context == null) return;
        final String pkg = context.getPackageName();
        new Thread(() -> {
            try {
                if (enabled) {
                    String cmd = "echo '_ --enable-gpu-rasterization --enable-zero-copy --ignore-gpu-blocklist --enable-features=Vulkan --webview-draw-functor-uses-vulkan --disable-low-end-device-mode --js-flags=--max-old-space-size=2048' > /data/local/tmp/webview-command-line; "
                            + "chmod 777 /data/local/tmp/webview-command-line; "
                            + "settings put global debug_app " + pkg + " 2>/dev/null || cmd settings put global debug_app " + pkg;
                    Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
                } else {
                    String cmd = "rm -f /data/local/tmp/webview-command-line; "
                            + "settings delete global debug_app 2>/dev/null || cmd settings delete global debug_app";
                    Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
                }
            } catch (Throwable t) {
                Log.w(TAG, "同步系统 webview-command-line 失败: " + t.getMessage());
            }
        }, "sync-chromium-flags").start();
    }
}
