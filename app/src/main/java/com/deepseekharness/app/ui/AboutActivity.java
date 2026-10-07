package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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
        TextView themeBtn = findViewById(R.id.btn_theme);
        if (themeBtn != null) {
            boolean dark = ThemeController.isDark(this);
            themeBtn.setText(dark ? "☀ 白天" : "☾ 黑夜");
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
            Toast.makeText(this, "QQ 群号已复制：" + QQ_GROUP, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.about_row_license).setOnClickListener(v -> showLicenseDialog());
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
            Toast.makeText(this, "无法打开浏览器：" + t.getMessage(), Toast.LENGTH_SHORT).show();
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

    private void showLicenseDialog() {
        new AlertDialog.Builder(this)
                .setTitle("开源协议 (MIT License)")
                .setMessage("Copyright (c) 2026 DSHA-FR Contributors\n\n"
                        + "Permission is hereby granted, free of charge, to any person obtaining a copy "
                        + "of this software and associated documentation files (the \"Software\"), to deal "
                        + "in the Software without restriction, including without limitation the rights "
                        + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies "
                        + "of the Software, and to permit persons to whom the Software is furnished to do so, "
                        + "subject to the following conditions:\n\n"
                        + "The above copyright notice and this permission notice shall be included in all "
                        + "copies or substantial portions of the Software.")
                .setPositiveButton("访问仓库 LICENSE", (d, w) -> openUrl(GITHUB_ROOT_URL + "/blob/main/LICENSE"))
                .setNegativeButton("关闭", null)
                .show();
    }
}
