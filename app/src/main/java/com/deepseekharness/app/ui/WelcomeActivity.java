package com.deepseekharness.app.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.ui.contract.WelcomeActions;
import com.deepseekharness.app.ui.contract.WelcomeUiState;

import java.util.Arrays;
import java.util.List;

public class WelcomeActivity extends AppCompatActivity implements WelcomeActions {

    private final View[] dotViews = new View[3];
    private ViewPager2 pager;
    private Button actionBtn;
    private final WelcomeActions actions = this;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeController.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        // 极光漫射效果 (Android 12+)
        View auroraView = findViewById(R.id.global_aurora);
        if (auroraView != null && Build.VERSION.SDK_INT >= 31) {
            float blurPx = 80f * getResources().getDisplayMetrics().density;
            try {
                auroraView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable ignored) { }
        }

        // 顶栏日夜间纯图标切换 (白天显示太阳，黑夜显示月亮，零文字)
        View themeBtn = findViewById(R.id.btn_theme);
        ImageView themeIcon = findViewById(R.id.img_theme_icon);
        if (themeBtn != null && themeIcon != null) {
            boolean dark = ThemeController.isDark(this);
            themeIcon.setImageResource(dark ? R.drawable.ic_moon : R.drawable.ic_sun);
            themeIcon.setContentDescription(dark ? "夜间模式" : "日间模式");
            themeBtn.setOnClickListener(v -> ThemeController.toggle(this));
        }

        pager = findViewById(R.id.welcome_pager);
        actionBtn = findViewById(R.id.welcome_btn);
        LinearLayout dotsBox = findViewById(R.id.welcome_dots);

        pager.setAdapter(new PageAdapter());
        pager.setUserInputEnabled(true);

        // 初始化 1:1 对齐概念稿的胶囊指示器
        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dot.setLayoutParams(lp);
            dotsBox.addView(dot);
            dotViews[i] = dot;
        }

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                actions.onPageSelected(position);
            }
        });

        actionBtn.setOnClickListener(v -> actions.onNextOrStartClick());

        actions.onPageSelected(0);
    }

    private void render(WelcomeUiState state) {
        int primaryColor = getColor(R.color.primary);
        int mutedColor = getColor(R.color.line);

        for (int i = 0; i < 3; i++) {
            View dot = dotViews[i];
            boolean isActive = (i == state.position);
            int width = isActive ? dp(26) : dp(8);

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            if (lp.width != width) {
                lp.width = width;
                dot.setLayoutParams(lp);
            }

            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setCornerRadius(dp(999));
            shape.setColor(isActive ? primaryColor : mutedColor);
            dot.setBackground(shape);
        }

        if (actionBtn != null) {
            actionBtn.setText(state.buttonText);
        }
    }

    @Override
    public void onNextOrStartClick() {
        int cur = pager.getCurrentItem();
        if (cur < 2) {
            pager.setCurrentItem(cur + 1, true);
        } else {
            new ConfigStore(this).setWelcomed(true);
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    }

    @Override
    public void onPageSelected(int position) {
        String text = (position == 2) ? "开始使用" : "下一步";
        render(new WelcomeUiState(position, text));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {
        private final List<Integer> layouts = Arrays.asList(
                R.layout.welcome_page1, R.layout.welcome_page2, R.layout.welcome_page3);

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(layouts.get(viewType), parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {}

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @Override
        public int getItemCount() {
            return layouts.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}
