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
import androidx.core.widget.ImageViewCompat;

import com.deepseekharness.app.R;
import com.deepseekharness.app.ui.MonetThemeHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 莫奈全景取色与极光动态演色引擎:
 * 1. 严格约束: 仅在白天模式 (!isDark) 且开启莫奈取色时生效; 黑夜模式坚决维持冷靛深蓝灰黑不变。
 * 2. 覆盖范围: 完整采纳莫奈 3 色体系，全面覆盖所有深色主按钮、浅色次级胶囊、图标前景与极光漫射背景。
 * 3. 闭环支持: 关闭时 100% 彻底还原默认原色; 切页面/切 Tab 全程生命周期自动跟随，永不失效。
 */
public final class MonetEngine {

    // 默认标准原色 (未开莫奈或关闭时还原使用)
    public static final int DEFAULT_PRIMARY_DAY = 0xFF47699F;
    public static final int DEFAULT_CONTAINER_DAY = 0xFFEBF0F8;
    public static final int DEFAULT_TERTIARY_DAY = 0xFF355070;
    public static final int DEFAULT_SURFACE_DAY = 0xFFF7F8FA;

    public static final int DEFAULT_PRIMARY_NIGHT = 0xFF8AA8D6;

    private MonetEngine() { }

    public static final class PaletteInfo {
        public final boolean isMonetActive;
        public final int primaryColor;       // 色彩 1: 深色主强调色
        public final int containerColor;     // 色彩 2: 浅色容器色
        public final int tertiaryColor;      // 色彩 3: 点缀辅助色
        public final int surfaceColor;       // 背景色

        public PaletteInfo(boolean isMonetActive, int primaryColor, int containerColor, int tertiaryColor, int surfaceColor) {
            this.isMonetActive = isMonetActive;
            this.primaryColor = primaryColor;
            this.containerColor = containerColor;
            this.tertiaryColor = tertiaryColor;
            this.surfaceColor = surfaceColor;
        }
    }

    public static PaletteInfo resolveCurrentPalette(Context context) {
        if (context == null) {
            return new PaletteInfo(false, DEFAULT_PRIMARY_DAY, DEFAULT_CONTAINER_DAY, DEFAULT_TERTIARY_DAY, DEFAULT_SURFACE_DAY);
        }

        boolean dark = ThemeController.isDark(context);
        if (dark) {
            // 黑夜模式坚决维持原有深色原色，不进行莫奈取色
            return new PaletteInfo(false, DEFAULT_PRIMARY_NIGHT, 0xFF243049, 0xFF7DA7F4, 0xFF10141B);
        }

        boolean monetEnabled = ThemeController.isMonetEnabled(context);
        if (!monetEnabled) {
            return new PaletteInfo(false, DEFAULT_PRIMARY_DAY, DEFAULT_CONTAINER_DAY, DEFAULT_TERTIARY_DAY, DEFAULT_SURFACE_DAY);
        }

        // 白天且开启莫奈: 动态提取壁纸种子色并按所选风格匹配完整的 3 个代表色（支持取反倒序）
        int seedColor = MonetThemeHelper.getWallpaperSeedColor(context);
        String style = ThemeController.getMonetPaletteStyle(context);
        boolean inverted = ThemeController.isMonetInverted(context);
        int[] preview = SheetSettingsFragment.getStylePreviewColors(style, seedColor, inverted);

        int primary = (preview != null && preview.length > 0) ? preview[0] : DEFAULT_PRIMARY_DAY;
        int container = (preview != null && preview.length > 1) ? preview[1] : ColorUtils.blendARGB(0xFFFFFFFF, primary, 0.14f);
        int tertiary = (preview != null && preview.length > 2) ? preview[2] : primary;

        // 如果次色较暗，提亮为优雅浅底容器色，保证浅色按钮上的深色字绝对清晰
        if (ColorUtils.calculateLuminance(container) < 0.65f) {
            container = ColorUtils.blendARGB(0xFFFFFFFF, container, 0.20f);
        }

        // 大背景色微混 2% 莫奈色
        int surface = ColorUtils.blendARGB(0xFFF7F8FA, primary, 0.02f);

        return new PaletteInfo(true, primary, container, tertiary, surface);
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

        // 1. 底栏导航动态着色
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

        // 2. 极光漫射背景层动态生成与更新
        if (view.getId() == R.id.global_aurora) {
            updateAuroraGradient(view, targetPrimary, palette.isMonetActive);
            return;
        }

        // 3. 顶栏 leading 图标
        if (view.getId() == R.id.appbar_icon && view instanceof ImageView) {
            ((ImageView) view).setImageTintList(ColorStateList.valueOf(targetPrimary));
            return;
        }

        // 3.5. 图标盒容器着色: 浅色容器色 (Container)
        if (view.getId() == R.id.pluginIconBox || view.getId() == R.id.dialogBackupIconBox) {
            view.setBackground(createSolidPillDrawable(context, targetContainer, dp(context, 14)));
        }

        // 4. ImageView 着色: 如果原本使用了主题色，动态赋予新主色 (豁免关于页中央 Logo 图标)
        if (view instanceof ImageView) {
            ImageView iv = (ImageView) view;
            if (iv.getId() == R.id.about_logo_icon) {
                return; // 保持中央 Logo 独立纯白与经典底板，坚决不跟随莫奈取色变化
            }
            if (iv.getId() == R.id.pluginIcon || iv.getId() == R.id.dialogActionIcon
                    || iv.getId() == R.id.dialogBackupIcon || iv.getId() == R.id.appbar_icon
                    || iv.getId() == R.id.btnRefresh
                    || ImageViewCompat.getImageTintList(iv) != null || iv.getImageTintList() != null) {
                ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(targetPrimary));
            }
        }

        // 5. 胶囊开关 DshaToggle 动态注入展开背景色 (随莫奈主色实时演色或秒级复原)
        if (view instanceof DshaToggle) {
            ((DshaToggle) view).setColorOn(targetPrimary);
        }

        // 6. 按钮与操作胶囊全面着色 (深色主按钮变色彩 1，浅色胶囊变色彩 2 背景 + 色彩 1 文字)
        if (view instanceof Button || (view instanceof TextView && view.isClickable() && view.getBackground() != null)) {
            TextView btn = (TextView) view;
            int btnId = btn.getId();

            // 豁免危险警告按钮 (停止服务、删除、卸载等)，保持警示红醒目
            boolean isDanger = (btnId == R.id.launch_stop || btnId == R.id.btnPluginUninstall
                    || btnId == R.id.btnDiscardConfirm || btnId == R.id.term_ctrlc);

            if (!isDanger) {
                if (isPrimaryButton(btn)) {
                    // 主按钮: 背景色彩 1 (Primary)，文字根据亮度自适应纯白或深黑高对比度
                    btn.setBackground(createSolidPillDrawable(context, targetPrimary, dp(context, 14)));
                    boolean isLightPrimary = ColorUtils.calculateLuminance(targetPrimary) > 0.5f;
                    btn.setTextColor(isLightPrimary ? 0xFF10141B : Color.WHITE);
                } else if (isTonalButton(btn)) {
                    // 浅色胶囊按钮: 背景色彩 2 (Container)，文字色彩 1 (Primary)
                    btn.setBackground(createSolidPillDrawable(context, targetContainer, dp(context, 14)));
                    btn.setTextColor(targetPrimary);
                }
            }
        }

        // 6. 递归子元素
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                applyToViewTree(group.getChildAt(i), palette);
            }
        }
    }

    private static boolean isPrimaryButton(TextView view) {
        int id = view.getId();
        if (id == R.id.launch_start || id == R.id.btnActionConfirm || id == R.id.btnDialogConfirm
                || id == R.id.btnBackupConfirm || id == R.id.welcome_btn || id == R.id.term_send
                || id == R.id.config_taskset_save || id == R.id.cred_copy_bridge_token
                || id == R.id.cred_copy_auth_url || id == R.id.cred_copy_lan_addr || id == R.id.update_copy_cmd) {
            return true;
        }
        int curTextColor = view.getCurrentTextColor();
        return (view instanceof Button) && (curTextColor == Color.WHITE || curTextColor == 0xFFFFFFFF);
    }

    private static boolean isTonalButton(TextView view) {
        int id = view.getId();
        if (id == R.id.launch_open || id == R.id.update_browser || id == R.id.btnActionCancel
                || id == R.id.btnDialogCancel || id == R.id.btnDiscardCancel
                || id == R.id.btnBackupCancel || id == R.id.cred_enter_web || id == R.id.update_copy_mirror_cmd
                || id == R.id.cred_add_remote_btn || id == R.id.pluginActions || id == R.id.btnPluginAddr || id == R.id.btnPluginRename
                || id == R.id.btnPluginExport || id == R.id.term_clear || id == R.id.term_pty
                || id == R.id.pty_font_dec || id == R.id.pty_font_inc || id == R.id.pty_simple
                || id == R.id.launch_port_chip_3080 || id == R.id.launch_port_chip_3088) {
            return true;
        }
        return (view instanceof Button);
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

        // 确保容器通栏撑满屏幕 match_parent，消除任何左右方向上的竖直截断边缘
        ViewGroup.LayoutParams vlp = auroraView.getLayoutParams();
        if (vlp != null && vlp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
            vlp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            auroraView.setLayoutParams(vlp);
        }

        // 4 阶指数级超平滑衰减色阶: 从右上角核心柔光向左下自然消融
        int c0 = Color.argb(isMonetActive ? 0x22 : 0x16, r, g, b);
        int c1 = Color.argb(isMonetActive ? 0x11 : 0x0A, r, g, b);
        int c2 = Color.argb(isMonetActive ? 0x04 : 0x02, r, g, b);
        int c3 = Color.argb(0x00, r, g, b);

        GradientDrawable aurora = new GradientDrawable();
        aurora.setShape(GradientDrawable.RECTANGLE);
        aurora.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        aurora.setGradientCenter(1.0f, 0.0f); // 圆心定在右上角顶点，平滑向中间辐射
        aurora.setGradientRadius(dp(context, 360));
        aurora.setColors(new int[]{c0, c1, c2, c3});
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
