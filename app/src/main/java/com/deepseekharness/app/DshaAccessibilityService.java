package com.deepseekharness.app;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * DSHA 统一无障碍服务：
 * 1. 继承自 {@link com.deepseekharness.app.phone.a11y.PhoneA11yService}，为 mcp__phone__* 提供
 *    MainScreenDriver（元素指纹校验、身份证/银行卡脱敏、回读校验、长列表 collect）与
 *    ControlOverlay（胶囊 / 卡片 / 贴边小球三态控制条 + 底部确认卡）；
 * 2. 保留服务连接状态与 Root 自动连接能力；手机动作统一由 MCP 驱动处理。
 */
public class DshaAccessibilityService extends com.deepseekharness.app.phone.a11y.PhoneA11yService {

    private static final String TAG = "DSHA";

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

    /** 自动静默自愈无障碍服务（Root 环境下自动授予 WRITE_SECURE_SETTINGS、解除 A13~A17 受限设置并挂载服务） */
    public static boolean ensureConnected(Context ctx) {
        if (instance != null) return true;
        if (ctx == null) return false;
        try {
            String pkg = ctx.getPackageName();
            String cls = DshaAccessibilityService.class.getName();
            String full = pkg + "/" + cls;
            int userId = android.os.Process.myUid() / 100000;
            String cmd = "pm grant --user " + userId + " " + pkg + " android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; "
                    + "appops set " + pkg + " ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; "
                    + "settings put secure accessibility_enabled 1; "
                    + "CUR=$(settings get secure enabled_accessibility_services 2>/dev/null); "
                    + "if [ \"$CUR\" = \"null\" ] || [ -z \"$CUR\" ]; then "
                    + "  settings put secure enabled_accessibility_services '" + full + "'; "
                    + "elif ! echo \"$CUR\" | grep -q '" + full + "'; then "
                    + "  settings put secure enabled_accessibility_services \"$CUR:" + full + "\"; "
                    + "fi";
            HttpShellService.execRootCommand(cmd);
            for (int i = 0; i < 10; i++) {
                if (instance != null) return true;
                Thread.sleep(100);
            }
        } catch (Throwable ignored) {}
        return instance != null;
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
