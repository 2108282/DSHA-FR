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
            autoRestoreToggle.setChecked(cfg.isSheetAutoRestoreDefault(), false, false);
            autoRestoreToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                cfg.setSheetAutoRestoreDefault(isChecked);
                Toast.makeText(requireContext(),
                        isChecked ? "已开启：抽屉低于 45% 时下次自动回弹至默认高度" : "已关闭：抽屉保持上次停留高度",
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

        // 7. 抽屉默认展开高度与吸附档位 (30~95%)
        EditText heightInput = v.findViewById(R.id.sheet_settings_height_input);
        Button heightSave = v.findViewById(R.id.sheet_settings_height_save);
        if (heightInput != null) {
            heightInput.setText(String.valueOf(cfg.getSheetHeightPercent()));
        }
        if (heightSave != null) {
            heightSave.setOnClickListener(x -> {
                int val = 75;
                try {
                    val = Integer.parseInt(heightInput.getText().toString().trim());
                } catch (Exception ignored) {}
                if (val < 30 || val > 95) {
                    Toast.makeText(requireContext(), "请输入 30 ~ 95 之间的百分比", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.setSheetHeightPercent(val);
                Toast.makeText(requireContext(), "已将默认高度与吸附档位设为 " + val + "%", Toast.LENGTH_SHORT).show();
            });
        }

        // 8. 左右屏幕独立边距 (0~100 dp)
        EditText marginLeftInput = v.findViewById(R.id.sheet_settings_margin_left_input);
        EditText marginRightInput = v.findViewById(R.id.sheet_settings_margin_right_input);
        Button marginSave = v.findViewById(R.id.sheet_settings_margin_save);
        if (marginLeftInput != null && marginRightInput != null) {
            marginLeftInput.setText(String.valueOf(cfg.getSheetMarginLeft()));
            marginRightInput.setText(String.valueOf(cfg.getSheetMarginRight()));
        }
        if (marginSave != null) {
            marginSave.setOnClickListener(x -> {
                int l = 0, r = 0;
                try {
                    l = Integer.parseInt(marginLeftInput.getText().toString().trim());
                    r = Integer.parseInt(marginRightInput.getText().toString().trim());
                } catch (Exception ignored) {}
                if (l < 0 || l > 100 || r < 0 || r > 100) {
                    Toast.makeText(requireContext(), "边距建议在 0 ~ 100 dp 之间", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.setSheetMarginLeft(l);
                cfg.setSheetMarginRight(r);
                Toast.makeText(requireContext(), "边距已保存：左 " + l + "dp，右 " + r + "dp（下次唤起生效）", Toast.LENGTH_SHORT).show();
            });
        }

        return v;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    public static String getPaletteStyleTitle(String style) {
        if (style == null) return "Tonal Spot";
        switch (style.toLowerCase()) {
            case "neutral": return "Neutral";
            case "vibrant": return "Vibrant";
            case "expressive": return "Expressive";
            case "rainbow": return "Rainbow";
            case "fruit_salad": return "Fruit Salad";
            case "monochrome": return "Monochrome";
            case "fidelity": return "Fidelity";
            case "tonal_spot":
            default:
                return "Tonal Spot";
        }
    }

    public static String getColorSpecTitle(String spec) {
        if ("spec_2021".equalsIgnoreCase(spec)) {
            return "Material 3 2021";
        }
        return "Material 3\nExpressive 2025";
    }

    public static int[] getStylePreviewColors(String style, int seedColor) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(seedColor, hsl);
        float h = hsl[0];
        String s = style != null ? style.toLowerCase() : "tonal_spot";
        switch (s) {
            case "neutral":
                return new int[]{
                        ColorUtils.HSLToColor(new float[]{h, 0.12f, 0.45f}),
                        ColorUtils.HSLToColor(new float[]{h, 0.08f, 0.72f}),
                        ColorUtils.HSLToColor(new float[]{(h + 15f) % 360f, 0.18f, 0.85f})
                };
            case "vibrant":
                return new int[]{
                        ColorUtils.HSLToColor(new float[]{h, 0.95f, 0.48f}),
                        ColorUtils.HSLToColor(new float[]{h, 0.65f, 0.72f}),
                        ColorUtils.HSLToColor(new float[]{(h + 50f) % 360f, 0.75f, 0.85f})
                };
            case "expressive":
                float eh = (h + 120f) % 360f;
                return new int[]{
                        ColorUtils.HSLToColor(new float[]{eh, 0.85f, 0.42f}),
                        ColorUtils.HSLToColor(new float[]{eh, 0.45f, 0.72f}),
                        ColorUtils.HSLToColor(new float[]{(eh + 120f) % 360f, 0.55f, 0.85f})
                };
            case "rainbow":
                return new int[]{
                        ColorUtils.HSLToColor(new float[]{h, 0.85f, 0.45f}),
                        ColorUtils.HSLToColor(new float[]{(h + 30f) % 360f, 0.35f, 0.72f}),
                        ColorUtils.HSLToColor(new float[]{(h + 300f) % 360f, 0.65f, 0.85f})
                };
            case "fruit_salad":
                float fh = (h - 50f + 360f) % 360f;
                return new int[]{
                        ColorUtils.HSLToColor(new float[]{fh, 0.85f, 0.40f}),
                        ColorUtils.HSLToColor(new float[]{fh, 0.45f, 0.72f}),
                        ColorUtils.HSLToColor(new float[]{(fh + 70f) % 360f, 0.55f, 0.85f})
                };
            case "monochrome":
                return new int[]{
                        Color.parseColor("#1E293B"),
                        Color.parseColor("#64748B"),
                        Color.parseColor("#94A3B8")
                };
            case "fidelity":
                return new int[]{
                        ColorUtils.HSLToColor(new float[]{h, 0.85f, 0.45f}),
                        ColorUtils.HSLToColor(new float[]{h, 0.45f, 0.72f}),
                        ColorUtils.HSLToColor(new float[]{(h + 180f) % 360f, 0.65f, 0.48f})
                };
            case "tonal_spot":
            default:
                return new int[]{
                        ColorUtils.HSLToColor(new float[]{h, 0.75f, 0.45f}),
                        ColorUtils.HSLToColor(new float[]{h, 0.35f, 0.72f}),
                        ColorUtils.HSLToColor(new float[]{(h + 60f) % 360f, 0.45f, 0.85f})
                };
        }
    }

    private static View createPaletteDotsView(Context context, int[] colors) {
        LinearLayout dots = new LinearLayout(context);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        int size = dp(context, 8);
        int gap = dp(context, 4);
        for (int i = 0; i < colors.length; i++) {
            View dot = new View(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart(gap);
            dot.setLayoutParams(lp);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(colors[i]);
            dot.setBackground(d);
            dots.addView(dot);
        }
        return dots;
    }

    private void updatePaletteStyleDots(LinearLayout dotsContainer, String style, int seedColor) {
        if (dotsContainer == null || getContext() == null) return;
        dotsContainer.removeAllViews();
        int[] colors = getStylePreviewColors(style, seedColor);
        dotsContainer.addView(createPaletteDotsView(getContext(), colors));
    }

    private void showPaletteStyleDialog(Context context, ConfigStore cfg, TextView styleValue, LinearLayout styleDots) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 16);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(context, 18));
        boolean dark = cfg.isSheetInvertColor();
        bg.setColor(dark ? Color.parseColor("#F01E222A") : Color.parseColor("#F8FFFFFF"));
        bg.setStroke(dp(context, 1), dark ? Color.parseColor("#354A5568") : Color.parseColor("#20000000"));
        root.setBackground(bg);

        TextView title = new TextView(context);
        title.setText("选择色彩风格");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(dark ? Color.parseColor("#E2E8F0") : Color.parseColor("#0F172A"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 12));
        root.addView(title);

        ScrollView sv = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        Map<String, String> styles = new LinkedHashMap<>();
        styles.put("tonal_spot", "Tonal Spot");
        styles.put("neutral", "Neutral");
        styles.put("vibrant", "Vibrant");
        styles.put("expressive", "Expressive");
        styles.put("rainbow", "Rainbow");
        styles.put("fruit_salad", "Fruit Salad");
        styles.put("monochrome", "Monochrome");
        styles.put("fidelity", "Fidelity");

        String current = cfg.getSheetPaletteStyle();
        int seedColor = MonetThemeHelper.getWallpaperSeedColor(context);

        for (Map.Entry<String, String> entry : styles.entrySet()) {
            final String key = entry.getKey();
            final String name = entry.getValue();

            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
            item.setClickable(true);
            item.setFocusable(true);

            TypedValue tv = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            item.setBackgroundResource(tv.resourceId);

            int[] previewColors = getStylePreviewColors(key, seedColor);
            item.addView(createPaletteDotsView(context, previewColors));

            TextView label = new TextView(context);
            label.setText(name);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTextColor(dark ? Color.parseColor("#E2E8F0") : Color.parseColor("#1E293B"));
            if (key.equalsIgnoreCase(current)) {
                label.setTextColor(context.getColor(R.color.primary));
                label.setTypeface(null, android.graphics.Typeface.BOLD);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(context, 12));
            label.setLayoutParams(lp);
            item.addView(label);

            if (key.equalsIgnoreCase(current)) {
                TextView check = new TextView(context);
                check.setText("✓");
                check.setTextColor(context.getColor(R.color.primary));
                check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                check.setTypeface(null, android.graphics.Typeface.BOLD);
                item.addView(check);
            }

            item.setOnClickListener(v2 -> {
                cfg.setSheetPaletteStyle(key);
                if (styleValue != null) {
                    styleValue.setText(name + " ▾");
                }
                if (styleDots != null) {
                    updatePaletteStyleDots(styleDots, key, seedColor);
                }
                MonetThemeHelper.clearCache(context);
                QuickChatSheetActivity.refreshThemeFromConfig(context);
                dialog.dismiss();
            });

            list.addView(item);
        }

        sv.addView(list);
        root.addView(sv);

        dialog.setContentView(root);
        dialog.show();
    }

    private void showColorSpecDialog(Context context, ConfigStore cfg, TextView specValue) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 16);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(context, 18));
        boolean dark = cfg.isSheetInvertColor();
        bg.setColor(dark ? Color.parseColor("#F01E222A") : Color.parseColor("#F8FFFFFF"));
        bg.setStroke(dp(context, 1), dark ? Color.parseColor("#354A5568") : Color.parseColor("#20000000"));
        root.setBackground(bg);

        TextView title = new TextView(context);
        title.setText("选择色彩标准");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(dark ? Color.parseColor("#E2E8F0") : Color.parseColor("#0F172A"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 12));
        root.addView(title);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        Map<String, String> specs = new LinkedHashMap<>();
        specs.put("spec_2025", "Material 3 Expressive 2025\n更具层次与表现力的现代调色");
        specs.put("spec_2021", "Material 3 2021\n经典 Material 3 基础色板");

        String current = cfg.getSheetColorSpec();

        for (Map.Entry<String, String> entry : specs.entrySet()) {
            final String key = entry.getKey();
            final String desc = entry.getValue();

            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
            item.setClickable(true);
            item.setFocusable(true);

            TypedValue tv = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            item.setBackgroundResource(tv.resourceId);

            TextView label = new TextView(context);
            label.setText(desc);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setLineSpacing(dp(context, 2), 1f);
            label.setTextColor(dark ? Color.parseColor("#E2E8F0") : Color.parseColor("#1E293B"));
            if (key.equalsIgnoreCase(current)) {
                label.setTextColor(context.getColor(R.color.primary));
                label.setTypeface(null, android.graphics.Typeface.BOLD);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(lp);
            item.addView(label);

            if (key.equalsIgnoreCase(current)) {
                TextView check = new TextView(context);
                check.setText("✓");
                check.setTextColor(context.getColor(R.color.primary));
                check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                check.setTypeface(null, android.graphics.Typeface.BOLD);
                item.addView(check);
            }

            item.setOnClickListener(v2 -> {
                cfg.setSheetColorSpec(key);
                if (specValue != null) {
                    specValue.setText(getColorSpecTitle(key) + " ▾");
                }
                MonetThemeHelper.clearCache(context);
                QuickChatSheetActivity.refreshThemeFromConfig(context);
                dialog.dismiss();
            });

            list.addView(item);
        }

        root.addView(list);
        dialog.setContentView(root);
        dialog.show();
    }
}
