package com.deepseekharness.app;

import com.deepseekharness.app.util.SensitiveData;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DSHA 统一无障碍服务：
 * 1. 继承自 {@link com.deepseekharness.app.phone.a11y.PhoneA11yService}，为 mcp__phone__* 提供
 *    MainScreenDriver（元素指纹校验、身份证/银行卡脱敏、回读校验、长列表 collect）与
 *    ControlOverlay（胶囊 / 卡片 / 贴边小球三态控制条 + 底部确认卡）；
 * 2. 同时保留 3095 桥 (/app/ui/*) 的静态兼容方法与 Root 自动开启能力。
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

    // ==================== 给 agent 用的屏幕操作能力 ====================

    private static volatile DshaAccessibilityService instance;

    public static boolean isConnected() {
        return instance != null;
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

    /** 服务没开时统一的提示语：告诉 agent 该让用户做什么，而不是只丢一个错误码 */
    private static final String NOT_READY =
            "[ERR] 无障碍服务未开启。请让用户在 DSHA「配置」页点「屏幕操作权限」，"
                    + "或到系统设置 → 无障碍 → DSHA 配对助手 打开。";

    /** 当前前台窗口的应用包名；取不到返回空串。授权闸门用它识别支付/银行类应用。 */
    public static String currentPackage() {
        DshaAccessibilityService s = instance;
        if (s == null) return "";
        AccessibilityNodeInfo root = null;
        try {
            root = s.getRootInActiveWindow();
            if (root == null) return "";
            CharSequence p = root.getPackageName();
            return p == null ? "" : p.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读当前屏幕：输出带序号、文本、可点击性与坐标的清单，供 agent 决定下一步点哪个。 */
    public static String uiDump() {
        DshaAccessibilityService s = instance;
        if (s == null) return NOT_READY;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return "[ERR] 取不到当前窗口（可能停在锁屏或系统弹窗上）";
            StringBuilder sb = new StringBuilder();
            CharSequence pkg = root.getPackageName();
            sb.append("窗口应用: ").append(pkg == null ? "未知" : pkg).append('\n');
            int[] n = {0};
            try {
                dumpNode(root, sb, 0, n);
            } finally {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
            if (n[0] == 0) sb.append("（没有可读节点）\n");
            return sb.toString();
        } catch (Throwable t) {
            return "[ERR] 读屏失败：" + SensitiveData.redact(String.valueOf(t));
        }
    }

    private static void dumpNode(AccessibilityNodeInfo node, StringBuilder sb, int depth, int[] count) {
        if (node == null || depth > 24 || count[0] > 200 || sb.length() > 12000) return;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        boolean clickable = node.isClickable();
        boolean editable = node.isEditable();
        String label = !TextUtils.isEmpty(t) ? t.toString()
                : (!TextUtils.isEmpty(d) ? d.toString() : "");
        // 只输出「有文字」或「能点/能输入」的节点：全量节点树对 agent 是噪音
        if (!label.isEmpty() || clickable || editable) {
            android.graphics.Rect r = new android.graphics.Rect();
            node.getBoundsInScreen(r);
            count[0]++;
            sb.append('[').append(count[0]).append("] ");
            if (!label.isEmpty()) sb.append('"').append(label.replace('\n', ' ')).append('"');
            if (clickable) sb.append(" 可点击");
            if (editable) sb.append(" 可输入");
            if (node.isChecked()) sb.append(" 已选中");
            if (!node.isEnabled()) sb.append(" 不可用");
            sb.append(" 中心=(").append(r.centerX()).append(',').append(r.centerY()).append(')')
                    .append(" 区域=").append(r.left).append(',').append(r.top)
                    .append(',').append(r.right).append(',').append(r.bottom).append('\n');
        }
        int cn = node.getChildCount();
        for (int i = 0; i < cn; i++) {
            AccessibilityNodeInfo ch = node.getChild(i);
            if (ch == null) continue;
            try {
                dumpNode(ch, sb, depth + 1, count);
            } finally {
                try {
                    ch.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 按坐标点按。坐标从 uiDump 的「中心=」里取。 */
    public static String uiTap(int x, int y) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return "手势点按需 Android 7+（当前系统过旧）";
        }
        DshaAccessibilityService s = instance;
        if (s == null) return NOT_READY;
        return s.gesture(buildTap(x, y), "点按 (" + x + "," + y + ")");
    }

    /** 按文字点按：优先走节点自身的 ACTION_CLICK，比盲点坐标稳得多
     *  （控件位置会随滚动、折叠、动画变化，文字不会）。 */
    public static String uiTapText(String text) {
        DshaAccessibilityService s = instance;
        if (s == null) return NOT_READY;
        if (text == null || text.isEmpty()) return "[ERR] 要点的文字不能为空";
        AccessibilityNodeInfo root = null;
        try {
            root = s.getRootInActiveWindow();
            if (root == null) return "[ERR] 取不到当前窗口";
            AccessibilityNodeInfo hit = findClickableByText(root, text, 0);
            if (hit == null) return "[ERR] 屏幕上找不到可点击的「" + text + "」（先用 dump 看看实际文字）";
            boolean ok;
            try {
                ok = hit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } finally {
                try {
                    hit.recycle();
                } catch (Throwable ignored) {
                }
            }
            return ok ? "OK 已点击「" + text + "」" : "[ERR] 点击被系统拒绝（控件可能不可用）";
        } catch (Throwable t) {
            return "[ERR] 点击失败：" + SensitiveData.redact(String.valueOf(t));
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 找到含指定文字、且自身或祖先可点击的节点（返回的节点由调用方 recycle） */
    private static AccessibilityNodeInfo findClickableByText(AccessibilityNodeInfo node, String text, int depth) {
        if (node == null || depth > 24) return null;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        boolean match = (t != null && t.toString().contains(text))
                || (d != null && d.toString().contains(text));
        if (match) {
            // 文字节点常常自己不可点击，真正的按钮是它的某级父节点
            AccessibilityNodeInfo cur = node;
            for (int up = 0; up < 6 && cur != null; up++) {
                if (cur.isClickable() && cur.isEnabled()) {
                    return AccessibilityNodeInfo.obtain(cur);
                }
                cur = cur.getParent();
            }
        }
        int cn = node.getChildCount();
        for (int i = 0; i < cn; i++) {
            AccessibilityNodeInfo ch = node.getChild(i);
            if (ch == null) continue;
            AccessibilityNodeInfo r = findClickableByText(ch, text, depth + 1);
            try {
                ch.recycle();
            } catch (Throwable ignored) {
            }
            if (r != null) return r;
        }
        return null;
    }

    /** 往当前焦点输入框填文字（没有焦点就找第一个可输入的） */
    public static String uiInput(String text) {
        DshaAccessibilityService s = instance;
        if (s == null) return NOT_READY;
        if (text == null) return "[ERR] 文本不能为空";
        AccessibilityNodeInfo root = null;
        try {
            root = s.getRootInActiveWindow();
            if (root == null) return "[ERR] 取不到当前窗口";
            AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null) target = findEditable(root, 0);
            if (target == null) return "[ERR] 屏幕上没有输入框（先点一下要输入的位置）";
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok;
            try {
                ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            } finally {
                try {
                    target.recycle();
                } catch (Throwable ignored) {
                }
            }
            return ok ? "OK 已输入 " + text.length() + " 个字符" : "[ERR] 输入被系统拒绝";
        } catch (Throwable t) {
            return "[ERR] 输入失败：" + SensitiveData.redact(String.valueOf(t));
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 24) return null;
        if (node.isEditable() && node.isEnabled()) return AccessibilityNodeInfo.obtain(node);
        int cn = node.getChildCount();
        for (int i = 0; i < cn; i++) {
            AccessibilityNodeInfo ch = node.getChild(i);
            if (ch == null) continue;
            AccessibilityNodeInfo r = findEditable(ch, depth + 1);
            try {
                ch.recycle();
            } catch (Throwable ignored) {
            }
            if (r != null) return r;
        }
        return null;
    }

    /** 全局按键：back / home / recents / notifications / quicksettings / lock */
    public static String uiKey(String name) {
        DshaAccessibilityService s = instance;
        if (s == null) return NOT_READY;
        String k = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        int action;
        switch (k) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents": case "recent": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": case "notification": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quicksettings": case "quick": action = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock":
                if (android.os.Build.VERSION.SDK_INT < 28) return "[ERR] 锁屏需要 Android 9+";
                action = GLOBAL_ACTION_LOCK_SCREEN;
                break;
            default:
                return "[ERR] 不认识的按键「" + name
                        + "」（可用：back/home/recents/notifications/quicksettings/lock）";
        }
        try {
            return s.performGlobalAction(action) ? "OK 已发送 " + k : "[ERR] 系统拒绝了 " + k;
        } catch (Throwable t) {
            return "[ERR] 按键失败：" + SensitiveData.redact(String.valueOf(t));
        }
    }

    /** 滑动：翻页、下拉刷新、侧滑都靠它。durationMs 太短系统会当成甩动。 */
    public static String uiSwipe(int x1, int y1, int x2, int y2, int durationMs) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return "手势滑动需 Android 7+（当前系统过旧）";
        }
        DshaAccessibilityService s = instance;
        if (s == null) return NOT_READY;
        int dur = durationMs <= 0 ? 300 : Math.min(durationMs, 5000);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, dur);
        return s.gesture(new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke).build(),
                "滑动 (" + x1 + "," + y1 + ")→(" + x2 + "," + y2 + ")");
    }

    @androidx.annotation.RequiresApi(24)
    private static android.accessibilityservice.GestureDescription buildTap(int x, int y) {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x, y);
        return new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 50))
                .build();
    }

    /** 派发手势并等结果：dispatchGesture 是异步回调，agent 那边要的是同步答复 */
    @androidx.annotation.RequiresApi(24)
    private String gesture(android.accessibilityservice.GestureDescription gd, String what) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        try {
            boolean accepted = dispatchGesture(gd, new GestureResultCallback() {
                @Override
                public void onCompleted(android.accessibilityservice.GestureDescription d) {
                    ok[0] = true;
                    latch.countDown();
                }

                @Override
                public void onCancelled(android.accessibilityservice.GestureDescription d) {
                    latch.countDown();
                }
            }, null);
            if (!accepted) return "[ERR] 手势未被接受（" + what + "）";
            if (!latch.await(6, java.util.concurrent.TimeUnit.SECONDS)) {
                return "[ERR] 手势超时（" + what + "）";
            }
            return ok[0] ? "OK 已" + what : "[ERR] 手势被取消（" + what + "，可能被其它手势打断）";
        } catch (Throwable t) {
            return "[ERR] 手势失败：" + SensitiveData.redact(String.valueOf(t));
        }
    }

    /** 截屏（Android 11+）。存成 PNG 落到 Download/DSHA 并返回路径 ——
     *  直接回 base64 会把一张几百 KB 的图塞进会话，把上下文撑爆。
     *  agent 拿到路径后可以走附件机制看图，或让用户自己打开。 */
    @android.annotation.TargetApi(30)
    public static String uiScreenshot() {
        DshaAccessibilityService s = instance;
        if (s == null) return NOT_READY;
        if (android.os.Build.VERSION.SDK_INT < 30) {
            return "[ERR] 截屏需要 Android 11 及以上（当前 API "
                    + android.os.Build.VERSION.SDK_INT + "）";
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] out = {"[ERR] 截屏无结果"};
        try {
            s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    java.util.concurrent.Executors.newSingleThreadExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            try {
                                android.graphics.Bitmap bmp = android.graphics.Bitmap.wrapHardwareBuffer(
                                        result.getHardwareBuffer(), result.getColorSpace());
                                if (bmp == null) {
                                    out[0] = "[ERR] 截屏数据无法解析";
                                } else {
                                    out[0] = saveShot(bmp);
                                    bmp.recycle();
                                }
                            } catch (Throwable t) {
                                out[0] = "[ERR] 保存截屏失败："
                                        + SensitiveData.redact(String.valueOf(t));
                            } finally {
                                try {
                                    result.getHardwareBuffer().close();
                                } catch (Throwable ignored) {
                                }
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            // 5 = 频率限制：系统对连续截屏有节流
                            out[0] = "[ERR] 截屏被系统拒绝（错误码 " + errorCode
                                    + (errorCode == 5 ? "，太频繁了，隔一秒再试" : "") + "）";
                            latch.countDown();
                        }
                    });
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                return "[ERR] 截屏超时";
            }
            return out[0];
        } catch (Throwable t) {
            return "[ERR] 截屏失败：" + SensitiveData.redact(String.valueOf(t));
        }
    }

    /** 存到 Download/DSHA —— 这个目录 rootfs 里也看得到，agent 能直接拿文件 */
    private static String saveShot(android.graphics.Bitmap bmp) {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "DSHA");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "[ERR] 建不了目录 " + dir;
            }
            java.io.File f = new java.io.File(dir, "screen-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                    .format(new java.util.Date()) + ".png");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, fo);
            }
            return "OK 截屏已保存：" + f.getAbsolutePath()
                    + "（" + bmp.getWidth() + "x" + bmp.getHeight() + "）";
        } catch (Throwable t) {
            return "[ERR] 写截屏文件失败：" + SensitiveData.redact(String.valueOf(t));
        }
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
