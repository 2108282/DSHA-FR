package com.deepseekharness.app.ui;

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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快捷抽屉二级设置页：1:1 像素级 Skia 现代卡片设计 (ModernCardView + DshaToggle + 原生悬浮下拉条)，纯渲染与契约驱动。
 */
public class SheetSettingsFragment extends Fragment {

    private TextView styleValue;
    private LinearLayout styleDots;
    private TextView specValue;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_sheet_settings, container, false);

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
        View monetOptionsLabel = v.findViewById(R.id.sheet_settings_monet_options_label);
        styleValue = v.findViewById(R.id.sheet_settings_palette_style_value);
        styleDots = v.findViewById(R.id.sheet_settings_palette_style_dots);
        specValue = v.findViewById(R.id.sheet_settings_color_spec_value);

        boolean monetEnabled = cfg.isSheetMonetColor();
        if (monetOptionsContainer != null) {
            monetOptionsContainer.setVisibility(monetEnabled ? View.VISIBLE : View.GONE);
        }
        if (monetOptionsLabel != null) {
            monetOptionsLabel.setVisibility(monetEnabled ? View.VISIBLE : View.GONE);
        }

        int seedColor = MonetThemeHelper.getWallpaperSeedColor(requireContext());
        if (styleDots != null) {
            updatePaletteStyleDots(styleDots, cfg.getSheetPaletteStyle(), seedColor);
        }

        if (styleValue != null) {
            styleValue.setText(getPaletteStyleTitle(cfg.getSheetPaletteStyle()));
        }
        if (specValue != null) {
            specValue.setText(getColorSpecTitle(cfg.getSheetColorSpec()));
        }

        if (monetToggle != null) {
            monetToggle.setChecked(monetEnabled, false, false);
            monetToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                cfg.setSheetMonetColor(isChecked);
                if (monetOptionsContainer != null) {
                    monetOptionsContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
                if (monetOptionsLabel != null) {
                    monetOptionsLabel.setVisibility(isChecked ? View.VISIBLE : View.GONE);
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

        // 2.1 色彩风格原生下拉选项条（参考截图）
        View styleRow = v.findViewById(R.id.sheet_settings_palette_style_row);
        if (styleRow != null) {
            styleRow.setOnClickListener(anchor -> showPaletteStyleDropdown(anchor, cfg));
        }

        // 2.2 色彩标准原生下拉选项条（参考截图）
        View specRow = v.findViewById(R.id.sheet_settings_color_spec_row);
        if (specRow != null) {
            specRow.setOnClickListener(anchor -> showColorSpecDropdown(anchor, cfg));
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

    @Override
    public void onResume() {
        super.onResume();
        syncActivityTitle(true);
    }

    @Override
    public void onDestroyView() {
        syncActivityTitle(false);
        styleValue = null;
        styleDots = null;
        specValue = null;
        super.onDestroyView();
    }

    private void syncActivityTitle(boolean isSubpage) {
        if (!isAdded()) return;
        TextView activityTitle = requireActivity().findViewById(R.id.app_title);
        if (activityTitle != null) {
            activityTitle.setText(isSubpage ? "快捷对话设置" : getString(R.string.nav_settings));
        }
    }

    // ==================== 1:1 对齐截图：原生悬浮下拉菜单 ====================

    private void showPaletteStyleDropdown(View anchor, ConfigStore cfg) {
        Context context = requireContext();
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

        showModernDropdown(anchor, styles, current, key -> {
            cfg.setSheetPaletteStyle(key);
            if (styleValue != null) {
                styleValue.setText(styles.get(key));
            }
            if (styleDots != null) {
                updatePaletteStyleDots(styleDots, key, seedColor);
            }
            MonetThemeHelper.clearCache(context);
            QuickChatSheetActivity.refreshThemeFromConfig(context);
        });
    }

    private void showColorSpecDropdown(View anchor, ConfigStore cfg) {
        Map<String, String> specs = new LinkedHashMap<>();
        specs.put("spec_2025", "Material 3 Expressive 2025");
        specs.put("spec_2021", "Material 3 2021");

        String current = cfg.getSheetColorSpec();

        showModernDropdown(anchor, specs, current, key -> {
            cfg.setSheetColorSpec(key);
            if (specValue != null) {
                specValue.setText(specs.get(key));
            }
            MonetThemeHelper.clearCache(requireContext());
            QuickChatSheetActivity.refreshThemeFromConfig(requireContext());
        });
    }

    private interface OnDropdownSelectedListener {
        void onSelected(String key);
    }

    /**
     * 1:1 像素复刻截图样式的悬浮下拉菜单：
     * - 圆角微阴影浮层 (bg_dropdown_popup)
     * - 点击即选、无全屏遮罩阻断
     * - 选中项主题色高亮，右侧带蓝色对号 ✓
     */
    private void showModernDropdown(View anchor, Map<String, String> items, String currentKey,
                                    OnDropdownSelectedListener listener) {
        Context context = requireContext();
        PopupWindow popup = new PopupWindow(context);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // 浮层根容器：16dp 圆角卡片底，精细边框与微阴影
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dropdown_popup);
        root.setElevation(dp(context, 8));
        int padV = dp(context, 6);
        root.setPadding(0, padV, 0, padV);

        ScrollView scroll = new ScrollView(context);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        int minWidth = dp(context, 220);
        int itemHeight = dp(context, 48);

        for (Map.Entry<String, String> entry : items.entrySet()) {
            final String key = entry.getKey();
            final String title = entry.getValue();
            final boolean isSelected = key.equalsIgnoreCase(currentKey);

            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(context, 20), 0, dp(context, 16), 0);
            item.setMinimumHeight(itemHeight);
            item.setClickable(true);
            item.setFocusable(true);

            TypedValue tv = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            item.setBackgroundResource(tv.resourceId);

            // 选项文字：选中时主题色高亮且加粗，未选中时深色常规字体
            TextView label = new TextView(context);
            label.setText(title);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setTextColor(context.getColor(isSelected ? R.color.primary : R.color.text));
            if (isSelected) {
                label.setTypeface(null, android.graphics.Typeface.BOLD);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(lp);
            item.addView(label);

            // 选中项右侧蓝色对勾 ✓（1:1 对齐截图）
            if (isSelected) {
                ImageView check = new ImageView(context);
                check.setImageResource(R.drawable.ic_check_mini);
                check.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.primary)));
                int checkSize = dp(context, 18);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(checkSize, checkSize);
                clp.setMarginStart(dp(context, 12));
                check.setLayoutParams(clp);
                item.addView(check);
            }

            item.setOnClickListener(v -> {
                popup.dismiss();
                if (listener != null) {
                    listener.onSelected(key);
                }
            });

            list.addView(item, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight));
        }

        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        popup.setContentView(root);
        popup.setWidth(Math.max(minWidth, anchor.getWidth() / 2 + dp(context, 60)));
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);

        // 锚定在 anchor 视图的右侧下方弹出
        int xOffset = anchor.getWidth() - popup.getWidth() - dp(context, 8);
        popup.showAsDropDown(anchor, xOffset, dp(context, 2));
    }

    // ==================== 调色板预览辅助 ====================

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
        return "Material 3 Expressive 2025";
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
}
