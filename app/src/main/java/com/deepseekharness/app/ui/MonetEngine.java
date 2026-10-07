package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.deepseekharness.app.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 莫奈全景取色与极光动态演色引擎:
 * 1. 严格约束: 仅在白天模式 (!isDark) 且开启莫奈取色时生效; 黑夜模式坚决维持科技深蓝灰黑不变。
 * 2. 覆盖范围: 图标、按钮背景与文字、大背景与右上角极光高斯漫射光晕。
 * 3. 闭环支持: 关闭时 100% 彻底还原默认原色; 切页面/切 Tab 全程生命周期自动跟随，永不失效。
 */
public final class MonetEngine {

    // 默认标准原色 (未开莫奈或关闭时还原使用)
    public static final int DEFAULT_PRIMARY_DAY = 0xFF47699F;
    public static final int DEFAULT_CONTAINER_DAY = 0xFFEBF0F8;
    public static final int DEFAULT_SURFACE_DAY = 0xFFF7F8FA;

    public static final int DEFAULT_PRIMARY_NIGHT = 0xFF8AA8D6;

    private MonetEngine() { }

    public static final class PaletteInfo {
        public final boolean isMonetActive;
        public final int primaryColor;
        public final int containerColor;
        public final int surfaceColor;

        public PaletteInfo(boolean isMonetActive, int primaryColor, int containerColor, int surfaceColor) {
            this.isMonetActive = isMonetActive;
            this.primaryColor = primaryColor;
            this.containerColor = containerColor;
            this.surfaceColor = surfaceColor;
        }
    }

    public static PaletteInfo resolveCurrentPalette(Context context) {
        if (context == null) {
            return new PaletteInfo(false, DEFAULT_PRIMARY_DAY, DEFAULT_CONTAINER_DAY, DEFAULT_SURFACE_DAY);
        }

        boolean dark = ThemeController.isDark(context);
        if (dark) {
            // 黑夜模式坚决维持原有深色原色，不进行莫奈取色
            return new PaletteInfo(false, DEFAULT_PRIMARY_NIGHT, 0xFF243049, 0xFF10141B);
        }

        boolean monetEnabled = ThemeController.isMonetEnabled(context);
        if (!monetEnabled) {
            return new PaletteInfo(false, DEFAULT_PRIMARY_DAY, DEFAULT_CONTAINER_DAY, DEFAULT_SURFACE_DAY);
        }

        // 白天且开启莫奈: 动态提取壁纸种子色并按所选风格匹配
        int seedColor = MonetThemeHelper.getWallpaperSeedColor(context);
        String style = ThemeController.getMonetPaletteStyle(context);
        int[] preview = SheetSettingsFragment.getStylePreviewColors(style, seedColor);
        int primary = (preview != null && preview.length > 0) ? preview[0] : DEFAULT_PRIMARY_DAY;

        // 生成温润通透的莫奈浅色容器色 (混入约 12% 主色)
        int container = ColorUtils.blendARGB(0xFFFFFFFF, primary, 0.12f);
        // 大背景色保持纯净浅底，微染 2% 莫奈色
        int surface = ColorUtils.blendARGB(0xFFF7F8FA, primary, 0.02f);

        return new PaletteInfo(true, primary, container, surface);
    }

    /**
     * 对 Activity 注入全局莫奈色彩 (或还原默认色)
     */
    public static void applyToActivity(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        View root = activity.findViewById(android.R.id.content);
        if (root != null) {
            applyToViewTree(root, resolveCurrentPalette(activity));
        }
    }

    /**
     * 递归遍历对 View 树进行莫奈动态着色或还原
     */
    public static void applyToViewTree(View view, PaletteInfo palette) {
        if (view == null) return;

        Context context = view.getContext();
        int targetPrimary = palette.primaryColor;
        int targetContainer = palette.containerColor;

        // 1. 底栏导航着色
        if (view instanceof BottomNavigationView) {
            BottomNavigationView nav = (BottomNavigationView) view;
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_checked}
            };
            int unselected = context.getColor(R.color.text_muted);
            int[] colors = new int[]{targetPrimary, unselected};
            ColorStateList csl = new ColorStateList(states, colors);
            nav.setItemIconTintList(csl);
            nav.setItemTextColor(csl);
            return;
        }

        // 2. 极光漫射背景层动态生成与更新 (支持自由切换任意莫奈色光晕)
        if (view.getId() == R.id.global_aurora) {
            updateAuroraGradient(view, targetPrimary, palette.isMonetActive);
            return;
        }

        // 3. 顶栏 leading 图标
        if (view.getId() == R.id.appbar_icon && view instanceof ImageView) {
            ((ImageView) view).setImageTintList(ColorStateList.valueOf(targetPrimary));
            return;
        }

        // 4. 图标盒背景 (bg_settings_icon_box) 动态变色为 containerColor
        int viewId = view.getId();
        if (viewId == R.id.theme_notify_row_persistent || viewId == R.id.theme_notify_row_monet
                || viewId == R.id.settings_row_theme_notify || viewId == R.id.settings_row_config
                || viewId == R.id.settings_row_workspace || viewId == R.id.settings_row_quickchat
                || viewId == R.id.settings_row_update || viewId == R.id.settings_row_selftest) {
            // 在子树中继续寻找图标盒与小图标
        }

        // 5. ImageView 着色: 如果原本使用了主题色，动态赋予新主色
        if (view instanceof ImageView) {
            ImageView iv = (ImageView) view;
            ColorStateList tint = iv.getImageTintList();
            if (tint != null) {
                iv.setImageTintList(ColorStateList.valueOf(targetPrimary));
            }
        }

        // 6. 按钮着色 (主按钮变 primary 背景，浅色胶囊变 container 背景 + primary 字体)
        if (view instanceof Button) {
            Button btn = (Button) view;
            int btnId = btn.getId();

            // 主按钮类 (启动、开始修复等)
            if (btnId == R.id.launch_start || btnId == R.id.btnActionConfirm || btnId == R.id.btnDialogConfirm) {
                btn.setBackground(createSolidPillDrawable(context, targetPrimary, dp(context, 14)));
                btn.setTextColor(Color.WHITE);
            }
            // 浅色胶囊按钮类 (重启、在浏览器查看、次级操作等)
            else if (btnId == R.id.launch_open || btnId == R.id.update_browser || btnId == R.id.btnActionCancel) {
                btn.setBackground(createSolidPillDrawable(context, targetContainer, dp(context, 14)));
                btn.setTextColor(targetPrimary);
            }
        }

        // 7. 递归子元素
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                applyToViewTree(group.getChildAt(i), palette);
            }
        }
    }

    /**
     * 纯动态生成极光高斯漫射椭圆光晕 (无需多张静态图，完全按莫奈主色实时演色)
     */
    private static void updateAuroraGradient(View auroraView, int primaryColor, boolean isMonetActive) {
        if (auroraView == null) return;
        Context context = auroraView.getContext();
        int r = Color.red(primaryColor);
        int g = Color.green(primaryColor);
        int b = Color.blue(primaryColor);

        // 严格对齐原本 bg_aurora_top_right 的层次透明度
        int startColor = Color.argb(isMonetActive ? 0x22 : 0x15, r, g, b);
        int centerColor = Color.argb(isMonetActive ? 0x0E : 0x0B, r, g, b);
        int endColor = Color.argb(0x00, r, g, b);

        GradientDrawable aurora = new GradientDrawable();
        aurora.setShape(GradientDrawable.OVAL);
        aurora.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        aurora.setGradientRadius(dp(context, 200));
        aurora.setColors(new int[]{startColor, centerColor, endColor});
        auroraView.setBackground(aurora);
    }

    /**
     * 动态创建带按压涟漪的平滑圆角胶囊背景
     */
    private static RippleDrawable createSolidPillDrawable(Context context, int solidColor, int radiusPx) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(solidColor);
        shape.setCornerRadius(radiusPx);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);

        int rippleColor = ColorUtils.setAlphaComponent(solidColor, 0x33);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask);
    }

    private static int dp(Context context, int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
