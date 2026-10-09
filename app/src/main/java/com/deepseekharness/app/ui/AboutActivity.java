package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.deepseekharness.app.util.ToastHelper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;

public final class AboutActivity extends AppCompatActivity {

    public static final String GITHUB_URL = "https://github.com/DSH-APP/DSHA";
    public static final String GITHUB_ROOT_URL = "https://github.com/2108282/DSHA-FR";
    public static final String QQ_GROUP = "975836806";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeController.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        // 极光漫射效果 (Android 12+)
        View auroraView = findViewById(R.id.global_aurora);
        if (auroraView != null && Build.VERSION.SDK_INT >= 31) {
            float blurPx = 80f * getResources().getDisplayMetrics().density;
            try {
                auroraView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable ignored) { }
        }

        // 顶栏日夜间切换
        View themeBtn = findViewById(R.id.btn_theme);
        ImageView themeIcon = findViewById(R.id.img_theme_icon);
        if (themeBtn != null && themeIcon != null) {
            boolean dark = ThemeController.isDark(this);
            themeIcon.setImageResource(dark ? R.drawable.ic_moon : R.drawable.ic_sun);
            themeIcon.setContentDescription(dark ? "夜间模式" : "日间模式");
            themeBtn.setOnClickListener(v -> ThemeController.toggle(this));
        }

        // 版本信息
        TextView versionView = findViewById(R.id.about_version);
        if (versionView != null) {
            versionView.setText("v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                    + (BuildConfig.LOW_ANDROID ? " · 兼容版" : " · 标准版"));
        }

        findViewById(R.id.about_back).setOnClickListener(v -> finish());

        // 链接跳转与点击事件
        findViewById(R.id.about_row_root_repo).setOnClickListener(v -> openUrl(GITHUB_ROOT_URL));
        findViewById(R.id.about_row_upstream_repo).setOnClickListener(v -> openUrl(GITHUB_URL));
        findViewById(R.id.about_row_qq_group).setOnClickListener(v -> {
            copyToClipboard("QQ群号", QQ_GROUP);
            ToastHelper.makeText(this, "QQ 群号已复制：" + QQ_GROUP, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fragment_pop_enter, R.anim.fragment_pop_exit);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            ToastHelper.makeText(this, "无法打开浏览器：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyToClipboard(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
            }
        } catch (Throwable ignored) {}
    }

}
