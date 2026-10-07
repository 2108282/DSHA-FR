package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatDelegate;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.UiThemePreference;

/** 统一保存和应用外观选择；Activity 重建由 AppCompat 处理。 */
public final class ThemeController {
    private ThemeController() { }

    public static void apply(Context context) {
        String mode = new ConfigStore(context).getUiTheme();
        int night = UiThemePreference.DARK.equals(mode) ? AppCompatDelegate.MODE_NIGHT_YES
                : UiThemePreference.LIGHT.equals(mode) ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(night);
    }

    public static void select(Context context, String mode) {
        new ConfigStore(context).setUiTheme(mode);
        apply(context);
    }

    public static boolean isDark(Context context) {
        if (context == null) return false;
        String mode = new ConfigStore(context).getUiTheme();
        if (UiThemePreference.DARK.equals(mode)) return true;
        if (UiThemePreference.LIGHT.equals(mode)) return false;
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void toggle(Context context) {
        select(context, isDark(context) ? UiThemePreference.LIGHT : UiThemePreference.DARK);
    }

    public static boolean isMonetEnabled(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean("theme_monet_extracted", true);
    }

    public static String getMonetPaletteStyle(Context context) {
        if (context == null) return "tonal_spot";
        return context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getString("theme_palette_style_selected", "tonal_spot");
    }

    /**
     * 仅在白天模式且开启莫奈时计算动态取色；黑夜模式坚决维持冷靛蓝灰黑不变。
     */
    public static int getMonetPrimaryColor(Context context) {
        if (context == null || isDark(context) || !isMonetEnabled(context)) {
            return isDark(context) ? 0xFF8AA8D6 : 0xFF47699F;
        }
        try {
            int seedColor = MonetThemeHelper.getWallpaperSeedColor(context);
            String style = getMonetPaletteStyle(context);
            int[] preview = SheetSettingsFragment.getStylePreviewColors(style, seedColor);
            return preview != null && preview.length > 0 ? preview[0] : 0xFF47699F;
        } catch (Throwable t) {
            return 0xFF47699F;
        }
    }
}
