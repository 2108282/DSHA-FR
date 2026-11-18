package com.deepseekharness.app;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DSHA 统一无障碍服务：
 * 1. 继承自 {@link com.deepseekharness.app.phone.a11y.PhoneA11yService}，为 mcp__phone__* 提供
 *    MainScreenDriver（元素指纹校验、身份证/银行卡脱敏、回读校验、长列表 collect）与
 *    ControlOverlay（胶囊 / 卡片 / 贴边小球三态控制条 + 底部确认卡）；
 * 2. 保留服务连接状态；自动恢复权限和恢复开关均由用户显式控制，手机动作统一由 MCP 驱动处理。
 */
public class DshaAccessibilityService extends com.deepseekharness.app.phone.a11y.PhoneA11yService {

    private static final String TAG = "DSHA";
    private static final AtomicBoolean restoreGrantInFlight = new AtomicBoolean(false);
    private static volatile String restoreGrantError;

    public static boolean isRestoreGrantInFlight() {
        return restoreGrantInFlight.get();
    }

    public static String restoreGrantError() {
        return restoreGrantError;
    }

    /** 三态：YES 确认已开 / NO 确认未开 / UNKNOWN 读不到设置（别当成未开）。 */
    public static String enabledState(Context ctx) {
        if (instance != null) return "YES";
        try {
            String cls = DshaAccessibilityService.class.getName();
            String want = ctx.getPackageName() + "/" + cls;
            String on = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on == null) return "UNKNOWN";
            if (on.isEmpty()) return "NO";
            for (String e0 : on.split(":")) {
                String e = e0.trim();
                if (e.equalsIgnoreCase(want)) return "YES";
                if (e.startsWith(ctx.getPackageName() + "/")
                        && cls.endsWith(e.substring(e.indexOf('/') + 1))) {
                    return "YES";
                }
            }
            return "NO";
        } catch (Throwable e) {
            return "UNKNOWN";
        }
    }

    /** 用户是否已在系统设置里开启本服务 */
    public static boolean enabled(Context ctx) {
        try {
            String cls = DshaAccessibilityService.class.getName();
            String want = ctx.getPackageName() + "/" + cls;
            String on = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on == null || on.isEmpty()) return false;
            for (String s : on.split(":")) {
                String e = s.trim();
                if (e.equalsIgnoreCase(want)) return true;
                if (e.startsWith(ctx.getPackageName() + "/") && cls.endsWith(e.substring(e.indexOf('/') + 1))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        super.onAccessibilityEvent(event);
    }

    // ==================== 服务连接与生命周期 ====================

    private static volatile DshaAccessibilityService instance;

    public static boolean isConnected() {
        return instance != null;
    }

    public static DshaAccessibilityService getInstance() {
        return instance;
    }

    /** Explicit restore-switch enable only. Grants permission; the caller completes the switch. */
    public static String grantRestorePermission(Context ctx) {
        if (!restoreGrantInFlight.compareAndSet(false, true)) return "授权请求已在进行中，请稍候";
        try {
            if (ctx == null) return rememberGrantError("无法获取应用上下文");
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return rememberGrantError(null);
            String pkg = ctx.getPackageName();
            int userId = android.os.Process.myUid() / 100000;
            String result = HttpShellService.execRootCommand("pm grant --user " + userId + " " + pkg
                    + " android.permission.WRITE_SECURE_SETTINGS");
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return rememberGrantError(null);
            return rememberGrantError("授权失败：" + (result.isEmpty() ? "系统未授予权限，请检查 Root 授权状态" : result));
        } catch (Exception error) {
            return rememberGrantError("授权失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        } finally {
            restoreGrantInFlight.set(false);
        }
    }

    private static String rememberGrantError(String value) {
        restoreGrantError = value;
        return value;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接 (含 PhoneA11yService 驱动与三态悬浮条)");
    }

    @Override
    public void onInterrupt() {
        super.onInterrupt();
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
