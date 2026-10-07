package com.deepseekharness.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快捷抽屉二级设置页：1:1 像素级 Skia 现代卡片设计 (ModernCardView + DshaToggle)，纯渲染与契约驱动。
 */
public class SheetSettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_sheet_settings, container, false);

        View back = v.findViewById(R.id.sub_back);
        if (back != null) {
            back.setOnClickListener(x -> getParentFragmentManager().popBackStack());
        }

        ConfigStore cfg = new ConfigStore(requireContext());

        // 1. 抽屉正反色开关（DshaToggle 纯 Skia 自绘）
        DshaToggle invertToggle = v.findViewById(R.id.sheet_settings_invert_toggle);
        if (invertToggle != null) {
            invertToggle.setChecked(cfg.isSheetInvertColor(), false, false);
            invertToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                cfg.setSheetInvertColor(isChecked);
                QuickChatSheetActivity.refreshThemeFromConfig(requireContext());
                Toast.makeText(requireContext(),
                        isChecked ? "抽屉反色已开启（深色反色视觉）" : "抽屉反色已关闭（常规浅色视觉）",
                        Toast.LENGTH_SHORT).show();
            });
            View invertRow = v.findViewById(R.id.sheet_settings_invert_row);
            if (invertRow != null) {
                invertRow.setOnClickListener(x -> invertToggle.toggle());
            }
        }

        // 2. 莫奈取色开关（DshaToggle 纯 Skia 自绘）
        DshaToggle monetToggle = v.findViewById(R.id.sheet_settings_monet_toggle);
        View monetOptionsContainer = v.findViewById(R.id.sheet_settings_monet_options_container);
        TextView styleValue = v.findViewById(R.id.sheet_settings_palette_style_value);
        LinearLayout styleDots = v.findViewById(R.id.sheet_settings_palette_style_dots);
        TextView specValue = v.findViewById(R.id.sheet_settings_color_spec_value);

        if (monetOptionsContainer != null) {
            monetOptionsContainer.setVisibility(cfg.isSheetMonetColor() ? View.VISIBLE : View.GONE);
        }

        int seedColor = MonetThemeHelper.getWallpaperSeedColor(requireContext());
        if (styleDots != null) {
            updatePaletteStyleDots(styleDots, cfg.getSheetPaletteStyle(), seedColor);
        }

        if (styleValue != null) {
            styleValue.setText(getPaletteStyleTitle(cfg.getSheetPaletteStyle()) + " ▾");
        }
        if (specValue != null) {
            specValue.setText(getColorSpecTitle(cfg.getSheetColorSpec()) + " ▾");
        }

        if (monetToggle != null) {
            monetToggle.setChecked(cfg.isSheetMonetColor(), false, false);
            monetToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                cfg.setSheetMonetColor(isChecked);
                if (monetOptionsContainer != null) {
                    monetOptionsContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
                MonetThemeHelper.clearCache(requireContext());
                QuickChatSheetActivity.refreshThemeFromConfig(requireContext());
                Toast.makeText(requireContext(),
                        isChecked ? "莫奈取色已开启（跟随系统壁纸调色，仅浅色生效）" : "莫奈取色已关闭（恢复经典浅色）",
                        Toast.LENGTH_SHORT).show();
            });
            View monetRow = v.findViewById(R.id.sheet_settings_monet_row);
            if (monetRow != null) {
                monetRow.setOnClickListener(x -> monetToggle.toggle());
            }
        }

        // 2.1 色彩风格选择弹窗
        View styleRow = v.findViewById(R.id.sheet_settings_palette_style_row);
        if (styleRow != null) {
            styleRow.setOnClickListener(x -> showPaletteStyleDialog(requireContext(), cfg, styleValue, styleDots));
        }

        // 2.2 色彩标准选择弹窗
        View specRow = v.findViewById(R.id.sheet_settings_color_spec_row);
        if (specRow != null) {
            specRow.setOnClickListener(x -> showColorSpecDialog(requireContext(), cfg, specValue));
        }

        // 3. 圈定即搜重定向开关（DshaToggle 纯 Skia 自绘）
        DshaToggle ctsToggle = v.findViewById(R.id.sheet_settings_cts_redirect_toggle);
        if (ctsToggle != null) {
            ctsToggle.setChecked(cfg.isCtsRedirectEnabled(), false, false);
            ctsToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                cfg.setCtsRedirectEnabled(isChecked);
                Toast.makeText(requireContext(),
                        isChecked ? "圈定即搜重定向已开启（手势唤起抽屉）" : "圈定即搜已回退系统默认（Google）",
                        Toast.LENGTH_SHORT).show();
            });
            View ctsRow = v.findViewById(R.id.sheet_settings_cts_redirect_row);
            if (ctsRow != null) {
                ctsRow.setOnClickListener(x -> ctsToggle.toggle());
            }
        }

        // 4. 低位自动恢复默认高度开关（DshaToggle 纯 Skia 自绘）
        DshaToggle autoRestoreToggle = v.findViewById(R.id.sheet_settings_auto_restore_toggle);
        if (autoRestoreToggle != null) {
            autoRestoreToggle.setChecked(cfg.isSheetAutoRestoreHeight(), false, false);
            autoRestoreToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                cfg.setSheetAutoRestoreHeight(isChecked);
                Toast.makeText(requireContext(),
                        isChecked ? "已开启低于 45% 自动恢复默认高度" : "已关闭低位自动恢复",
                        Toast.LENGTH_SHORT).show();
            });
            View autoRestoreRow = v.findViewById(R.id.sheet_settings_auto_restore_row);
            if (autoRestoreRow != null) {
                autoRestoreRow.setOnClickListener(x -> autoRestoreToggle.toggle());
            }
        }

        // 5. 抽屉白天不透明度
        EditText opacityDayInput = v.findViewById(R.id.sheet_settings_opacity_day_input);
        Button opacityDaySave = v.findViewById(R.id.sheet_settings_opacity_day_save);
        if (opacityDayInput != null) {
            opacityDayInput.setText(String.valueOf(cfg.getSheetOpacityDay()));
        }
        if (opacityDaySave != null) {
            opacityDaySave.setOnClickListener(x -> {
                int val = 88;
                try {
                    val = Integer.parseInt(opacityDayInput.getText().toString().trim());
                } catch (Exception ignored) {}
                if (val < 30 || val > 100) {
                    Toast.makeText(requireContext(), "请输入 30 ~ 100 之间的数值", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.setSheetOpacityDay(val);
                Toast.makeText(requireContext(), "已保存白天不透明度为 " + val + "%（下次唤起抽屉生效）", Toast.LENGTH_SHORT).show();
            });
        }

        // 6. 抽屉黑夜不透明度
        EditText opacityNightInput = v.findViewById(R.id.sheet_settings_opacity_night_input);
        Button opacityNightSave = v.findViewById(R.id.sheet_settings_opacity_night_save);
        if (opacityNightInput != null) {
            opacityNightInput.setText(String.valueOf(cfg.getSheetOpacityNight()));
        }
        if (opacityNightSave != null) {
            opacityNightSave.setOnClickListener(x -> {
                int val = 80;
                try {
                    val = Integer.parseInt(opacityNightInput.getText().toString().trim());
                } catch (Exception ignored) {}
                if (val < 30 || val > 100) {
                    Toast.makeText(requireContext(), "请输入 30 ~ 100 之间的数值", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.setSheetOpacityNight(val);
                Toast.makeText(requireContext(), "已保存黑夜不透明度为 " + val + "%（下次唤起抽屉生效）", Toast.LENGTH_SHORT).show();
            });
        }

        // 7. 默认展开高度
        EditText heightInput = v.findViewById(R.id.sheet_settings_height_input);
        Button heightSave = v.findViewById(R.id.sheet_settings_height_save);
        if (heightInput != null) {
            heightInput.setText(String.valueOf(cfg.getSheetDefaultHeight()));
        }
        if (heightSave != null) {
            heightSave.setOnClickListener(x -> {
                int val = 75;
                try {
                    val = Integer.parseInt(heightInput.getText().toString().trim());
                } catch (Exception ignored) {}
                if (val < 30 || val > 95) {
                    Toast.makeText(requireContext(), "请输入 30 ~ 95 之间的数值", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.setSheetDefaultHeight(val);
                Toast.makeText(requireContext(), "已保存默认展开高度为 " + val + "%（下次唤起抽屉生效）", Toast.LENGTH_SHORT).show();
            });
        }

        // 8. 左右屏幕间距
        EditText marginLeftInput = v.findViewById(R.id.sheet_settings_margin_left_input);
        EditText marginRightInput = v.findViewById(R.id.sheet_settings_margin_right_input);
        Button marginSave = v.findViewById(R.id.sheet_settings_margin_save);
        if (marginLeftInput != null) {
            marginLeftInput.setText(String.valueOf(cfg.getSheetMarginLeft()));
        }
        if (marginRightInput != null) {
            marginRightInput.setText(String.valueOf(cfg.getSheetMarginRight()));
        }
        if (marginSave != null) {
            marginSave.setOnClickListener(x -> {
                int left = 0;
                int right = 0;
                try {
                    left = Integer.parseInt(marginLeftInput.getText().toString().trim());
                } catch (Exception ignored) {}
                try {
                    right = Integer.parseInt(marginRightInput.getText().toString().trim());
                } catch (Exception ignored) {}
                if (left < 0 || left > 60 || right < 0 || right > 60) {
                    Toast.makeText(requireContext(), "边距请输入 0 ~ 60 dp 之间的数值", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.setSheetMarginLeft(left);
                cfg.setSheetMarginRight(right);
                Toast.makeText(requireContext(), "已保存左右屏幕间距（左 " + left + " dp，右 " + right + " dp）", Toast.LENGTH_SHORT).show();
            });
        }

        return v;
    }

    private static String getPaletteStyleTitle(int style) {
        switch (style) {
            case 0: return "浮雕柔和 (Tonal Spot)";
            case 1: return "中性低饱和 (Neutral)";
            case 2: return "鲜明浓郁 (Vibrant)";
            case 3: return "富表现力 (Expressive)";
            case 4: return "丰富多彩 (Rainbow)";
            case 5: return "高饱和度 (Fruit Salad)";
            case 6: return "单色灰阶 (Monochrome)";
            case 7: return "保真取色 (Fidelity)";
            case 8: return "内容匹配 (Content)";
            default: return "浮雕柔和 (Tonal Spot)";
        }
    }

    private static String getColorSpecTitle(int spec) {
        switch (spec) {
            case 0: return "Material 3 Expressive 2025";
            case 1: return "Material 3 (Classic)";
            case 2: return "Material You (Monet)";
            default: return "Material 3 Expressive 2025";
        }
    }

    private static void updatePaletteStyleDots(LinearLayout container, int style, int seedColor) {
        container.removeAllViews();
        Context ctx = container.getContext();
        int[] palette = MonetThemeHelper.generatePaletteColors(seedColor, style, false);
        int[] previewIndices = new int[]{1, 5, 8}; // Primary, Container, Accent
        for (int idx : previewIndices) {
            if (idx >= palette.length) continue;
            View dot = new View(ctx);
            int size = dp(ctx, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(dp(ctx, 4));
            dot.setLayoutParams(lp);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(palette[idx]);
            dot.setBackground(d);
            container.addView(dot);
        }
    }

    private static void showPaletteStyleDialog(Context ctx, ConfigStore cfg, TextView styleValueView, LinearLayout styleDotsView) {
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(buildPaletteStyleDialogView(ctx, cfg, dialog, styleValueView, styleDotsView));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        dialog.show();
    }

    private static View buildPaletteStyleDialogView(Context ctx, ConfigStore cfg, Dialog dialog, TextView styleValueView, LinearLayout styleDotsView) {
        boolean isDark = ThemeController.isDark(ctx);
        int seedColor = MonetThemeHelper.getWallpaperSeedColor(ctx);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 24));
        bg.setColor(ctx.getColor(R.color.card));
        root.setBackground(bg);

        TextView title = new TextView(ctx);
        title.setText("选择莫奈色彩风格");
        title.setTextSize(18);
        title.setTextColor(ctx.getColor(R.color.text));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(title);

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);

        Map<Integer, String> styles = new LinkedHashMap<>();
        styles.put(0, "浮雕柔和 (Tonal Spot)\nAndroid 标准壁纸取色，色调柔和平衡");
        styles.put(1, "中性低饱和 (Neutral)\n低饱和度冷淡色调，几乎不偏色");
        styles.put(2, "鲜明浓郁 (Vibrant)\n色彩更浓郁饱满，视觉冲击力强");
        styles.put(3, "富表现力 (Expressive)\n强调色彩反差，主色与强调色跨度大");
        styles.put(4, "丰富多彩 (Rainbow)\n活泼多色阶过渡");
        styles.put(5, "高饱和度 (Fruit Salad)\n如同水果沙拉般明亮跳脱的色彩搭配");
        styles.put(6, "单色灰阶 (Monochrome)\n纯粹无彩度的优雅灰黑白设计");
        styles.put(7, "保真取色 (Fidelity)\n极高保真度还原壁纸原本的核心色度");
        styles.put(8, "内容匹配 (Content)\n针对多媒体界面优化的适应性取色");

        int currentStyle = cfg.getSheetPaletteStyle();

        for (Map.Entry<Integer, String> entry : styles.entrySet()) {
            final int style = entry.getKey();
            String desc = entry.getValue();

            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
            item.setClickable(true);
            item.setFocusable(true);

            TypedValue outValue = new TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            item.setBackgroundResource(outValue.resourceId);

            LinearLayout dots = new LinearLayout(ctx);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(Gravity.CENTER_VERTICAL);
            updatePaletteStyleDots(dots, style, seedColor);
            item.addView(dots);

            TextView label = new TextView(ctx);
            label.setText(desc);
            label.setTextSize(14);
            label.setTextColor(ctx.getColor(style == currentStyle ? R.color.primary : R.color.text));
            if (style == currentStyle) {
                label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(ctx, 10));
            label.setLayoutParams(lp);
            item.addView(label);

            if (style == currentStyle) {
                TextView check = new TextView(ctx);
                check.setText("✓");
                check.setTextSize(16);
                check.setTextColor(ctx.getColor(R.color.primary));
                check.setTypeface(check.getTypeface(), android.graphics.Typeface.BOLD);
                item.addView(check);
            }

            item.setOnClickListener(v -> {
                cfg.setSheetPaletteStyle(style);
                if (styleValueView != null) {
                    styleValueView.setText(getPaletteStyleTitle(style) + " ▾");
                }
                if (styleDotsView != null) {
                    updatePaletteStyleDots(styleDotsView, style, seedColor);
                }
                MonetThemeHelper.clearCache(ctx);
                QuickChatSheetActivity.refreshThemeFromConfig(ctx);
                dialog.dismiss();
            });

            list.addView(item);
        }

        scroll.addView(list);
        root.addView(scroll);
        return root;
    }

    private static void showColorSpecDialog(Context ctx, ConfigStore cfg, TextView specValueView) {
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(buildColorSpecDialogView(ctx, cfg, dialog, specValueView));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        dialog.show();
    }

    private static View buildColorSpecDialogView(Context ctx, ConfigStore cfg, Dialog dialog, TextView specValueView) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 24));
        bg.setColor(ctx.getColor(R.color.card));
        root.setBackground(bg);

        TextView title = new TextView(ctx);
        title.setText("选择色彩标准");
        title.setTextSize(18);
        title.setTextColor(ctx.getColor(R.color.text));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(title);

        Map<Integer, String> specs = new LinkedHashMap<>();
        specs.put(0, "Material 3 Expressive 2025\n谷歌 2025 最新前沿表现力色彩规范，更高饱和与对比");
        specs.put(1, "Material 3 (Classic)\n经典 Material Design 3 色调体系，严谨低调");
        specs.put(2, "Material You (Monet)\n原生 Android 12/13 经典莫奈算法，高保真还原");

        int currentSpec = cfg.getSheetColorSpec();

        for (Map.Entry<Integer, String> entry : specs.entrySet()) {
            final int spec = entry.getKey();
            String desc = entry.getValue();

            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 14));
            item.setClickable(true);
            item.setFocusable(true);

            TypedValue outValue = new TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            item.setBackgroundResource(outValue.resourceId);

            TextView label = new TextView(ctx);
            label.setText(desc);
            label.setTextSize(14);
            label.setTextColor(ctx.getColor(spec == currentSpec ? R.color.primary : R.color.text));
            if (spec == currentSpec) {
                label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(lp);
            item.addView(label);

            if (spec == currentSpec) {
                TextView check = new TextView(ctx);
                check.setText("✓");
                check.setTextSize(16);
                check.setTextColor(ctx.getColor(R.color.primary));
                check.setTypeface(check.getTypeface(), android.graphics.Typeface.BOLD);
                item.addView(check);
            }

            item.setOnClickListener(v -> {
                cfg.setSheetColorSpec(spec);
                if (specValueView != null) {
                    specValueView.setText(getColorSpecTitle(spec) + " ▾");
                }
                MonetThemeHelper.clearCache(ctx);
                QuickChatSheetActivity.refreshThemeFromConfig(ctx);
                dialog.dismiss();
            });

            root.addView(item);
        }

        return root;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
