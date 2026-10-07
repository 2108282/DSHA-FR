package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Application;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.deepseekharness.app.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/** 统一处理 Android 15+ 强制铺满窗口后的状态栏、挖孔以及全局莫奈取色注入。 */
public final class ModernAndroidUi implements Application.ActivityLifecycleCallbacks {

    @Override
    public void onActivityPostCreated(Activity activity, Bundle saved) {
        if (activity instanceof WebFullscreenUi.Host) return;
        // 豁免快捷抽屉：抽屉自绘透明毛玻璃底板并有专属 keyboardSpacer 避让
        if (activity.getClass().getSimpleName().contains("QuickChatSheetActivity")) return;

        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        int color = activity.getColor(R.color.surface);
        content.setBackgroundColor(color);
        boolean light = ColorUtils.calculateLuminance(color) > 0.5;
        androidx.core.view.WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), content);
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);

        final int left = content.getPaddingLeft(), top = content.getPaddingTop();
        final int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(left + bars.left, top + bars.top, right + bars.right,
                    bottom + Math.max(bars.bottom, keyboard));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);

        // ==================== 全局莫奈取色动态注入引擎 ====================
        applyMonetTheming(activity);
    }

    /**
     * 仅在白天模式且开启莫奈取色时生效；黑夜模式坚决维持冷靛深蓝灰黑不变。
     */
    public static void applyMonetTheming(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (ThemeController.isDark(activity) || !ThemeController.isMonetEnabled(activity)) {
            return;
        }

        final int monetColor = ThemeController.getMonetPrimaryColor(activity);

        // 1. 底栏导航动态注入莫奈选中高亮
        BottomNavigationView nav = activity.findViewById(R.id.bottom_nav);
        if (nav != null) {
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_checked}
            };
            int[] colors = new int[]{monetColor, activity.getColor(R.color.text_muted)};
            ColorStateList csl = new ColorStateList(states, colors);
            nav.setItemIconTintList(csl);
            nav.setItemTextColor(csl);
        }

        // 2. 顶栏 leading 图标
        ImageView appbarIcon = activity.findViewById(R.id.appbar_icon);
        if (appbarIcon != null) {
            appbarIcon.setImageTintList(ColorStateList.valueOf(monetColor));
        }

        // 3. 递归遍历页面主要操作元素并动态着色
        View root = activity.findViewById(android.R.id.content);
        if (root instanceof ViewGroup) {
            tintMonetViews((ViewGroup) root, monetColor);
        }
    }

    private static void tintMonetViews(ViewGroup group, int monetColor) {
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                tintMonetViews((ViewGroup) child, monetColor);
            } else if (child instanceof ImageView) {
                ImageView iv = (ImageView) child;
                ColorStateList tint = iv.getImageTintList();
                if (tint != null) {
                    // 若使用了主题原色或主色，动态替换为莫奈色
                    iv.setImageTintList(ColorStateList.valueOf(monetColor));
                }
            }
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle saved) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle out) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
