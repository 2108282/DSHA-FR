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
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快捷抽屉二级设置页：抽屉反色开关、圈定即搜重定向、白天与黑夜不透明度设置。
 */
public class SheetSettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_sheet_settings, container, false);

        TextView back = v.findViewById(R.id.sub_back);
        back.setVisibility(View.VISIBLE);
        back.setOnClickListener(x -> getParentFragmentManager().popBackStack());

        ConfigStore cfg = new ConfigStore(requireContext());

        // 1. 抽屉正反色开关（独立控制快捷抽屉深色反色，与主应用黑夜白天解耦）
        SwitchCompat invertSwitch = v.findViewById(R.id.sheet_settings_invert_switch);
        if (invertSwitch != null) {
            invertSwitch.setChecked(cfg.isSheetInvertColor());
            v.findViewById(R.id.sheet_settings_invert_row).setOnClickListener(x -> {
                boolean next = !invertSwitch.isChecked();
                invertSwitch.setChecked(next);
                cfg.setSheetInvertColor(next);
                QuickChatSheetActivity.refreshThemeFromConfig(requireContext());
                Toast.makeText(requireContext(),
                        next ? "抽屉反色已开启（深色反色视觉）" : "抽屉反色已关闭（常规浅色视觉）",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // 2. 抽屉莫奈取色开关（提取系统壁纸 Material You 调色板）
        SwitchCompat monetSwitch = v.findViewById(R.id.sheet_settings_monet_switch);
        View monetOptionsContainer = v.findViewById(R.id.sheet_settings_monet_options_container);
        TextView styleValue = v.findViewById(R.id.sheet_settings_palette_style_value);
        TextView specValue = v.findViewById(R.id.sheet_settings_color_spec_value);

        if (monetOptionsContainer != null) {
            monetOptionsContainer.setVisibility(cfg.isSheetMonetColor() ? View.VISIBLE : View.GONE);
        }

        if (styleValue != null) {
            styleValue.setText(getPaletteStyleTitle(cfg.getSheetPaletteStyle()) + " ▾");
        }
        if (specValue != null) {
            specValue.setText(getColorSpecTitle(cfg.getSheetColorSpec()) + " ▾");
        }

        if (monetSwitch != null) {
            monetSwitch.setChecked(cfg.isSheetMonetColor());
            v.findViewById(R.id.sheet_settings_monet_row).setOnClickListener(x -> {
                boolean next = !monetSwitch.isChecked();
                monetSwitch.setChecked(next);
                cfg.setSheetMonetColor(next);
                if (monetOptionsContainer != null) {
                    monetOptionsContainer.setVisibility(next ? View.VISIBLE : View.GONE);
                }
                MonetThemeHelper.clearCache(requireContext());
                QuickChatSheetActivity.refreshThemeFromConfig(requireContext());
                Toast.makeText(requireContext(),
                        next ? "莫奈取色已开启（跟随系统壁纸调色，仅浅色生效）" : "莫奈取色已关闭（恢复经典浅色）",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // 2.1 色彩风格选择弹窗
        View styleRow = v.findViewById(R.id.sheet_settings_palette_style_row);
        if (styleRow != null) {
            styleRow.setOnClickListener(x -> showPaletteStyleDialog(requireContext(), cfg, styleValue));
        }

        // 2.2 色彩标准选择弹窗
        View specRow = v.findViewById(R.id.sheet_settings_color_spec_row);
        if (specRow != null) {
            specRow.setOnClickListener(x -> showColorSpecDialog(requireContext(), cfg, specValue));
        }

        // 2. 圈定即搜重定向开关（LSPosed 模块配置同步）
        SwitchCompat ctsSwitch = v.findViewById(R.id.sheet_settings_cts_redirect_switch);
        if (ctsSwitch != null) {
            ctsSwitch.setChecked(cfg.isCtsRedirectEnabled());
            v.findViewById(R.id.sheet_settings_cts_redirect_row).setOnClickListener(x -> {
                boolean next = !ctsSwitch.isChecked();
                ctsSwitch.setChecked(next);
                cfg.setCtsRedirectEnabled(next);
                Toast.makeText(requireContext(),
                        next ? "圈定即搜重定向已开启（手势唤起抽屉）" : "圈定即搜已回退系统默认（Google）",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // 3. 抽屉白天不透明度
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

        // 4. 抽屉黑夜不透明度
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

        // 5. 抽屉默认展开高度与吸附档位 (30~95%)
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

        // 6. 低于 45% 自动恢复默认高度 开关
        SwitchCompat restoreSwitch = v.findViewById(R.id.sheet_settings_auto_restore_switch);
        if (restoreSwitch != null) {
            restoreSwitch.setChecked(cfg.isSheetAutoRestoreDefault());
            v.findViewById(R.id.sheet_settings_auto_restore_row).setOnClickListener(x -> {
                boolean next = !restoreSwitch.isChecked();
                restoreSwitch.setChecked(next);
                cfg.setSheetAutoRestoreDefault(next);
                Toast.makeText(requireContext(),
                        next ? "已开启：抽屉低于 45% 时下次自动回弹至默认高度" : "已关闭：抽屉保持上次停留高度",
                        Toast.LENGTH_SHORT).show();
            });
        }

        // 7. 左右屏幕独立边距 (0~100 dp)
        EditText mlInput = v.findViewById(R.id.sheet_settings_margin_left_input);
        EditText mrInput = v.findViewById(R.id.sheet_settings_margin_right_input);
        Button marginSave = v.findViewById(R.id.sheet_settings_margin_save);
        if (mlInput != null && mrInput != null) {
            mlInput.setText(String.valueOf(cfg.getSheetMarginLeft()));
            mrInput.setText(String.valueOf(cfg.getSheetMarginRight()));
        }
        if (marginSave != null) {
            marginSave.setOnClickListener(x -> {
                int l = 0, r = 0;
                try {
                    l = Integer.parseInt(mlInput.getText().toString().trim());
                    r = Integer.parseInt(mrInput.getText().toString().trim());
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

    private int dpToPx(int dp) {
        if (getContext() == null) return dp * 2;
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
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

    private void showPaletteStyleDialog(Context context, ConfigStore cfg, TextView styleValue) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(16);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(18));
        boolean dark = cfg.isSheetInvertColor();
        bg.setColor(dark ? Color.parseColor("#F01E222A") : Color.parseColor("#F8FFFFFF"));
        bg.setStroke(dpToPx(1), dark ? Color.parseColor("#354A5568") : Color.parseColor("#20000000"));
        root.setBackground(bg);

        TextView title = new TextView(context);
        title.setText("选择色彩风格");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(dark ? Color.parseColor("#E2E8F0") : Color.parseColor("#0F172A"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(12));
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

        for (Map.Entry<String, String> entry : styles.entrySet()) {
            final String key = entry.getKey();
            final String label = entry.getValue();
            final boolean isSelected = key.equalsIgnoreCase(current);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));

            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            row.setBackgroundResource(outValue.resourceId);
            row.setClickable(true);

            TextView itemText = new TextView(context);
            itemText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            itemText.setText(label);
            itemText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            itemText.setTextColor(dark ? Color.parseColor("#CBD5E1") : Color.parseColor("#1E293B"));
            if (isSelected) {
                itemText.setTypeface(null, android.graphics.Typeface.BOLD);
                itemText.setTextColor(dark ? Color.parseColor("#38BDF8") : Color.parseColor("#0284C7"));
            }
            row.addView(itemText);

            if (isSelected) {
                TextView check = new TextView(context);
                check.setText("✓");
                check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                check.setTextColor(dark ? Color.parseColor("#38BDF8") : Color.parseColor("#0284C7"));
                check.setTypeface(null, android.graphics.Typeface.BOLD);
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                cfg.setSheetPaletteStyle(key);
                if (styleValue != null) {
                    styleValue.setText(label + " ▾");
                }
                MonetThemeHelper.clearCache(context);
                QuickChatSheetActivity.refreshThemeFromConfig(context);
                dialog.dismiss();
                Toast.makeText(context, "已切换风格：" + label + (dark ? "（当前为反色，切换至浅色后显现）" : ""), Toast.LENGTH_SHORT).show();
            });

            list.addView(row);
        }

        sv.addView(list);
        root.addView(sv);

        dialog.setContentView(root);
        if (win != null) {
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.85f);
            win.setLayout(Math.min(width, dpToPx(380)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
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
        int pad = dpToPx(16);
        root.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(18));
        boolean dark = cfg.isSheetInvertColor();
        bg.setColor(dark ? Color.parseColor("#F01E222A") : Color.parseColor("#F8FFFFFF"));
        bg.setStroke(dpToPx(1), dark ? Color.parseColor("#354A5568") : Color.parseColor("#20000000"));
        root.setBackground(bg);

        TextView title = new TextView(context);
        title.setText("选择色彩标准");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(dark ? Color.parseColor("#E2E8F0") : Color.parseColor("#0F172A"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(12));
        root.addView(title);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);

        Map<String, String> specs = new LinkedHashMap<>();
        specs.put("spec_2025", "Material 3 Expressive 2025");
        specs.put("spec_2021", "Material 3 2021");

        String current = cfg.getSheetColorSpec();

        for (Map.Entry<String, String> entry : specs.entrySet()) {
            final String key = entry.getKey();
            final String label = entry.getValue();
            final boolean isSelected = key.equalsIgnoreCase(current);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));

            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            row.setBackgroundResource(outValue.resourceId);
            row.setClickable(true);

            TextView itemText = new TextView(context);
            itemText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            itemText.setText(label);
            itemText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            itemText.setTextColor(dark ? Color.parseColor("#CBD5E1") : Color.parseColor("#1E293B"));
            if (isSelected) {
                itemText.setTypeface(null, android.graphics.Typeface.BOLD);
                itemText.setTextColor(dark ? Color.parseColor("#38BDF8") : Color.parseColor("#0284C7"));
            }
            row.addView(itemText);

            if (isSelected) {
                TextView check = new TextView(context);
                check.setText("✓");
                check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                check.setTextColor(dark ? Color.parseColor("#38BDF8") : Color.parseColor("#0284C7"));
                check.setTypeface(null, android.graphics.Typeface.BOLD);
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                cfg.setSheetColorSpec(key);
                if (specValue != null) {
                    specValue.setText(getColorSpecTitle(key) + " ▾");
                }
                MonetThemeHelper.clearCache(context);
                QuickChatSheetActivity.refreshThemeFromConfig(context);
                dialog.dismiss();
                Toast.makeText(context, "已切换标准：" + label + (dark ? "（当前为反色，切换至浅色后显现）" : ""), Toast.LENGTH_SHORT).show();
            });

            list.addView(row);
        }

        root.addView(list);

        dialog.setContentView(root);
        if (win != null) {
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.85f);
            win.setLayout(Math.min(width, dpToPx(380)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }
}
