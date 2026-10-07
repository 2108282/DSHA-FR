package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.ui.dialog.OverlayStyleDialog;
import com.deepseekharness.app.util.Constants;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ThemeNotifyFragment extends Fragment {

    private static final String PREF_KEY_MONET = "theme_monet_extracted";
    private static final String PREF_KEY_PALETTE_STYLE = "theme_palette_style_selected";

    private TextView paletteStyleValueText;
    private LinearLayout paletteStyleDotsContainer;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_theme_notify, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        ConfigStore cfg = ConfigStore.get(requireContext());

        // 拦截系统返回手势平滑退回设置页
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        getParentFragmentManager().popBackStack();
                    }
                }
        );

        // ==================== 1. 常驻后台服务通知 ====================
        DshaToggle persistentToggle = v.findViewById(R.id.theme_notify_toggle_persistent);
        if (persistentToggle != null) {
            persistentToggle.setChecked(cfg.isPersistentNotificationEnabled(), false, false);
            persistentToggle.setOnCheckedChangeListener((btn, isChecked) -> {
                cfg.setPersistentNotificationEnabled(isChecked);
                Toast.makeText(requireContext(),
                        isChecked ? "常驻后台通知已开启" : "常驻后台通知已关闭",
                        Toast.LENGTH_SHORT).show();
            });
            View persistentRow = v.findViewById(R.id.theme_notify_row_persistent);
            if (persistentRow != null) {
                persistentRow.setOnClickListener(x -> persistentToggle.toggle());
            }
        }

        // ==================== 2. 莫奈取色开关 (只做开关，不做功能) ====================
        DshaToggle monetToggle = v.findViewById(R.id.theme_notify_toggle_monet);
        if (monetToggle != null) {
            boolean monetEnabled = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PREF_KEY_MONET, true);
            monetToggle.setChecked(monetEnabled, false, false);
            monetToggle.setOnCheckedChangeListener((btn, isChecked) -> {
                requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(PREF_KEY_MONET, isChecked).apply();
                Toast.makeText(requireContext(),
                        isChecked ? "已启用莫奈动态取色" : "已停用莫奈动态取色",
                        Toast.LENGTH_SHORT).show();
            });
            View monetRow = v.findViewById(R.id.theme_notify_row_monet);
            if (monetRow != null) {
                monetRow.setOnClickListener(x -> monetToggle.toggle());
            }
        }

        // ==================== 3. 色彩风格行 (1:1 对齐快捷对话设置页) ====================
        paletteStyleValueText = v.findViewById(R.id.theme_notify_palette_style_value);
        paletteStyleDotsContainer = v.findViewById(R.id.theme_notify_palette_style_dots);

        String savedStyle = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getString(PREF_KEY_PALETTE_STYLE, "tonal_spot");
        int seedColor = MonetThemeHelper.getWallpaperSeedColor(requireContext());

        if (paletteStyleValueText != null) {
            paletteStyleValueText.setText(getStyleDisplayName(savedStyle));
        }
        updateDots(paletteStyleDotsContainer, savedStyle, seedColor);

        View paletteStyleRow = v.findViewById(R.id.theme_notify_row_palette_style);
        if (paletteStyleRow != null) {
            paletteStyleRow.setOnClickListener(anchor -> showPaletteStyleDropdown(anchor));
        }

        // ==================== 4. 启用悬浮栏开关 ====================
        DshaToggle floatingToggle = v.findViewById(R.id.theme_notify_toggle_floating);
        if (floatingToggle != null) {
            boolean isOverlay = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                    .getBoolean("overlay_stream", false);
            floatingToggle.setChecked(isOverlay, false, false);
            floatingToggle.setOnCheckedChangeListener((btn, isChecked) -> {
                requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean("overlay_stream", isChecked).apply();
                Toast.makeText(requireContext(),
                        isChecked ? "屏幕顶部悬浮栏已启用" : "屏幕顶部悬浮栏已关闭",
                        Toast.LENGTH_SHORT).show();
            });
            View floatingRow = v.findViewById(R.id.theme_notify_row_floating);
            if (floatingRow != null) {
                floatingRow.setOnClickListener(x -> floatingToggle.toggle());
            }
        }

        // ==================== 5. 悬浮条外观与行为配置 ====================
        View floatingSettingsRow = v.findViewById(R.id.theme_notify_row_floating_settings);
        if (floatingSettingsRow != null) {
            floatingSettingsRow.setOnClickListener(x -> OverlayStyleDialog.show(requireActivity()));
        }
    }

    private void updateDots(LinearLayout container, String style, int seedColor) {
        if (container == null || getContext() == null) return;
        container.removeAllViews();
        int[] colors = SheetSettingsFragment.getStylePreviewColors(style, seedColor);
        container.addView(createDotsView(requireContext(), colors));
    }

    private View createDotsView(Context context, int[] colors) {
        LinearLayout dots = new LinearLayout(context);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        int size = dp(8);
        int gap = dp(4);
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

    private String getStyleDisplayName(String key) {
        if ("neutral".equals(key)) return "Neutral";
        if ("vibrant".equals(key)) return "Vibrant";
        if ("expressive".equals(key)) return "Expressive";
        if ("rainbow".equals(key)) return "Rainbow";
        if ("fruit_salad".equals(key)) return "Fruit Salad";
        if ("monochrome".equals(key)) return "Monochrome";
        if ("fidelity".equals(key)) return "Fidelity";
        return "Tonal Spot";
    }

    private void showPaletteStyleDropdown(View anchor) {
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

        String currentKey = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getString(PREF_KEY_PALETTE_STYLE, "tonal_spot");
        int seedColor = MonetThemeHelper.getWallpaperSeedColor(context);

        showModernDropdown(anchor, styles, currentKey, seedColor, key -> {
            context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(PREF_KEY_PALETTE_STYLE, key).apply();
            if (paletteStyleValueText != null) {
                paletteStyleValueText.setText(styles.get(key));
            }
            updateDots(paletteStyleDotsContainer, key, seedColor);
        });
    }

    private void showModernDropdown(View anchor, Map<String, String> options, String selectedKey, int seedColor, OnOptionSelectedListener listener) {
        Context context = requireContext();
        int popupWidth = dp(240);
        int maxMenuHeight = dp(320);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout menuLayout = new LinearLayout(context);
        menuLayout.setOrientation(LinearLayout.VERTICAL);
        menuLayout.setPadding(0, dp(6), 0, dp(6));
        scrollView.addView(menuLayout);

        PopupWindow popupWindow = new PopupWindow(
                scrollView,
                popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popupWindow.setElevation(dp(10));
        popupWindow.setOutsideTouchable(true);

        scrollView.setBackgroundResource(R.drawable.bg_dropdown_popup);

        for (Map.Entry<String, String> entry : options.entrySet()) {
            final String key = entry.getKey();
            final String label = entry.getValue();
            boolean isSelected = key.equals(selectedKey);

            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), dp(11), dp(16), dp(11));
            TypedValue tvBg = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tvBg, true);
            item.setBackgroundResource(tvBg.resourceId);
            item.setClickable(true);
            item.setFocusable(true);

            TextView tv = new TextView(context);
            tv.setText(label);
            tv.setTextSize(14);
            tv.setTextColor(context.getColor(isSelected ? R.color.primary : R.color.text));
            if (isSelected) {
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            item.addView(tv, lp);

            // ★ 核心功能：每个选项右侧展示该风格对应的 3 个主色小圆圈！
            int[] previewColors = SheetSettingsFragment.getStylePreviewColors(key, seedColor);
            View dotsView = createDotsView(context, previewColors);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.setMarginStart(dp(8));
            dlp.setMarginEnd(dp(10));
            dotsView.setLayoutParams(dlp);
            item.addView(dotsView);

            if (isSelected) {
                ImageView check = new ImageView(context);
                check.setImageResource(R.drawable.ic_check_mini);
                check.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.primary)));
                item.addView(check, new LinearLayout.LayoutParams(dp(18), dp(18)));
            } else {
                View placeholder = new View(context);
                item.addView(placeholder, new LinearLayout.LayoutParams(dp(18), dp(18)));
            }

            item.setOnClickListener(v -> {
                popupWindow.dismiss();
                if (listener != null) listener.onSelected(key);
            });

            menuLayout.addView(item);
        }

        scrollView.measure(
                View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxMenuHeight, View.MeasureSpec.AT_MOST)
        );
        if (scrollView.getMeasuredHeight() > maxMenuHeight) {
            popupWindow.setHeight(maxMenuHeight);
        }

        int xOff = anchor.getWidth() - popupWidth;
        int yOff = dp(4);
        popupWindow.showAsDropDown(anchor, xOff, yOff);
    }

    private interface OnOptionSelectedListener {
        void onSelected(String key);
    }

    @Override
    public void onResume() {
        super.onResume();
        syncActivityTitle(true);
    }

    @Override
    public void onDestroyView() {
        syncActivityTitle(false);
        paletteStyleValueText = null;
        paletteStyleDotsContainer = null;
        super.onDestroyView();
    }

    private void syncActivityTitle(boolean isSubpage) {
        if (!isAdded()) return;
        TextView activityTitle = requireActivity().findViewById(R.id.app_title);
        if (activityTitle != null) {
            activityTitle.setText(isSubpage ? "主题与通知" : getString(R.string.nav_settings));
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v,
                getResources().getDisplayMetrics()
        );
    }
}
