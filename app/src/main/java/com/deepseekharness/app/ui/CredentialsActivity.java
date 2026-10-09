package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.deepseekharness.app.util.ToastHelper;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;

/**
 * 访问地址与鉴权凭据二级页面 (1:1 对齐 credentials-concept.html)
 */
public class CredentialsActivity extends AppCompatActivity {

    private TextView bridgeTokenText;
    private TextView authUrlText;
    private TextView lanAddrText;
    private TextView lanBadgeText;
    private DshaToggle lanSwitch;

    private LinearLayout remoteListContainer;
    private Button addRemoteBtn;
    private java.util.List<com.deepseekharness.app.core.ConfigStore.RemoteDshEntry> remoteList = new java.util.ArrayList<>();

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeController.apply(this);
        setContentView(R.layout.activity_credentials);

        // 返回
        findViewById(R.id.cred_back).setOnClickListener(v -> finish());

        bridgeTokenText = findViewById(R.id.cred_bridge_token);
        authUrlText = findViewById(R.id.cred_auth_url);
        lanAddrText = findViewById(R.id.cred_lan_addr);
        lanBadgeText = findViewById(R.id.cred_lan_badge);
        lanSwitch = findViewById(R.id.cred_lan_switch);
        if (lanSwitch != null) {
            boolean currentLan = getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(Constants.KEY_LAN_MODE, false);
            lanSwitch.setChecked(currentLan);
            lanSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(Constants.KEY_LAN_MODE, isChecked).apply();
                HarnessController ctl = HarnessController.get(this);
                if (ctl != null && ctl.isWebRunning()) {
                    if (isChecked) {
                        LanProxyService.start(this);
                    } else {
                        LanProxyService.stop();
                    }
                }
                refreshData();
            });
        }

        // 远端连接列表与新增
        remoteListContainer = findViewById(R.id.cred_remote_list_container);
        addRemoteBtn = findViewById(R.id.cred_add_remote_btn);
        if (addRemoteBtn != null) {
            addRemoteBtn.setOnClickListener(v -> addNewRemoteEntry());
        }

        // 复制设备桥令牌
        findViewById(R.id.cred_copy_bridge_token).setOnClickListener(v -> {
            String token = bridgeTokenText.getText().toString();
            copyToClipboard("Bridge Token", token);
        });

        // 复制本机 Web 地址
        findViewById(R.id.cred_copy_auth_url).setOnClickListener(v -> {
            String url = authUrlText.getText().toString();
            copyToClipboard("本机地址", url);
        });

        // 进入 Web
        findViewById(R.id.cred_enter_web).setOnClickListener(v -> enterWeb());

        // 复制局域网地址
        findViewById(R.id.cred_copy_lan_addr).setOnClickListener(v -> {
            String addr = lanAddrText.getText().toString();
            copyToClipboard("局域网地址", addr);
        });

        // 重新生成局域网 Token
        findViewById(R.id.cred_regenerate_token).setOnClickListener(v -> {
            LanProxyService.regenerateLanToken(this);
            ToastHelper.makeText(this, "已重新生成 Token 并更新地址", Toast.LENGTH_SHORT).show();
            refreshData();
        });

        loadRemoteList();
        refreshData();
        MonetEngine.applyToActivity(this);
    }

    private void loadRemoteList() {
        com.deepseekharness.app.core.ConfigStore cfg = com.deepseekharness.app.core.ConfigStore.get(this);
        remoteList = cfg.getRemoteDshList();
        renderRemoteCards();
    }

    private void addNewRemoteEntry() {
        com.deepseekharness.app.core.ConfigStore cfg = com.deepseekharness.app.core.ConfigStore.get(this);
        int nextIdx = remoteList.size() + 1;
        com.deepseekharness.app.core.ConfigStore.RemoteDshEntry entry =
                new com.deepseekharness.app.core.ConfigStore.RemoteDshEntry(
                        java.util.UUID.randomUUID().toString(),
                        "远端 " + nextIdx,
                        "",
                        false
                );
        remoteList.add(entry);
        cfg.saveRemoteDshList(remoteList);
        renderRemoteCards();
        ToastHelper.makeText(this, "已添加「远端 " + nextIdx + "」，请填写访问地址", Toast.LENGTH_SHORT).show();
    }

    private void renderRemoteCards() {
        if (remoteListContainer == null) return;
        remoteListContainer.removeAllViews();

        if (remoteList.isEmpty()) {
            // 空状态卡片
            ModernCardView emptyCard = new ModernCardView(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(18));
            emptyCard.setCardRadius(18f);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyCard.setLayoutParams(elp);

            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("暂无远端连接配置，点击右上角「添加远端」即可新增。");
            tvEmpty.setTextColor(getColor(R.color.text_muted));
            tvEmpty.setTextSize(13);
            tvEmpty.setGravity(android.view.Gravity.CENTER);
            emptyCard.addView(tvEmpty);
            remoteListContainer.addView(emptyCard);
            return;
        }

        com.deepseekharness.app.core.ConfigStore cfg = com.deepseekharness.app.core.ConfigStore.get(this);

        for (int i = 0; i < remoteList.size(); i++) {
            final int index = i;
            final com.deepseekharness.app.core.ConfigStore.RemoteDshEntry entry = remoteList.get(i);
            String titleName = (entry.name != null && !entry.name.trim().isEmpty())
                    ? entry.name.trim() : ("远端 " + (index + 1));

            ModernCardView card = new ModernCardView(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(18));
            card.setCardRadius(18f);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) clp.topMargin = dpToPx(12);
            card.setLayoutParams(clp);

            // 1. 顶部操作条：[图标] + [标题] + [徽章] + [互斥开关]
            LinearLayout headerRow = new LinearLayout(this);
            headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

            android.widget.FrameLayout iconBox = new android.widget.FrameLayout(this);
            iconBox.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(38), dpToPx(38)));
            iconBox.setBackgroundResource(R.drawable.bg_settings_icon_box);

            android.widget.ImageView ivIcon = new android.widget.ImageView(this);
            android.widget.FrameLayout.LayoutParams ivLp = new android.widget.FrameLayout.LayoutParams(dpToPx(20), dpToPx(20));
            ivLp.gravity = android.view.Gravity.CENTER;
            ivIcon.setLayoutParams(ivLp);
            ivIcon.setImageResource(R.drawable.ic_globe);
            MonetEngine.PaletteInfo currentPalette = MonetEngine.resolveCurrentPalette(this);
            int primaryColor = currentPalette.isMonetActive ? currentPalette.primaryColor : getColor(R.color.primary);
            androidx.core.widget.ImageViewCompat.setImageTintList(ivIcon, android.content.res.ColorStateList.valueOf(primaryColor));
            iconBox.addView(ivIcon);
            headerRow.addView(iconBox);

            TextView tvTitle = new TextView(this);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            tlp.setMarginStart(dpToPx(12));
            tvTitle.setLayoutParams(tlp);
            tvTitle.setText(titleName);
            tvTitle.setTextColor(getColor(R.color.text));
            tvTitle.setTextSize(15);
            tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            headerRow.addView(tvTitle);

            TextView badgeView = new TextView(this);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.setMarginEnd(dpToPx(10));
            badgeView.setLayoutParams(blp);
            badgeView.setBackgroundResource(R.drawable.bg_chip);
            badgeView.setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3));
            badgeView.setTextSize(11);
            badgeView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            if (entry.enabled) {
                badgeView.setText("已生效");
                badgeView.setTextColor(getColor(R.color.ok));
            } else {
                badgeView.setText("未启用");
                badgeView.setTextColor(getColor(R.color.text_muted));
            }
            headerRow.addView(badgeView);

            DshaToggle toggle = new DshaToggle(this);
            toggle.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(44), dpToPx(26)));
            toggle.setChecked(entry.enabled);
            toggle.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) {
                    // 严格互斥：开启当前项，关闭其他所有项
                    for (com.deepseekharness.app.core.ConfigStore.RemoteDshEntry other : remoteList) {
                        other.enabled = (other.id != null && other.id.equals(entry.id));
                    }
                    cfg.saveRemoteDshList(remoteList);
                    ToastHelper.makeText(this, "已启用「" + titleName + "」，其他远端已停用", Toast.LENGTH_SHORT).show();
                } else {
                    entry.enabled = false;
                    cfg.saveRemoteDshList(remoteList);
                    ToastHelper.makeText(this, "已停用「" + titleName + "」，回退本机模式", Toast.LENGTH_SHORT).show();
                }
                renderRemoteCards();
            });
            headerRow.addView(toggle);
            card.addView(headerRow);

            // 2. 输入框（远端 DSH URL）
            android.widget.EditText input = new android.widget.EditText(this);
            LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            inLp.topMargin = dpToPx(12);
            input.setLayoutParams(inLp);
            input.setBackgroundResource(R.drawable.bg_input);
            input.setTypeface(android.graphics.Typeface.MONOSPACE);
            input.setHint("http://192.168.1.xxx:3081/?token=...");
            input.setHintTextColor(getColor(R.color.text_muted));
            input.setTextColor(getColor(R.color.text));
            input.setTextSize(13);
            input.setSingleLine(true);
            input.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
            if (entry.url != null && !entry.url.isEmpty()) {
                input.setText(entry.url);
            }
            input.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    entry.url = s != null ? s.toString().trim() : "";
                    cfg.saveRemoteDshList(remoteList);
                }
            });
            card.addView(input);

            // 3. 底部操作栏：右下角操作组 [连接测试] + [删除]
            LinearLayout bottomBar = new LinearLayout(this);
            LinearLayout.LayoutParams botLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            botLp.topMargin = dpToPx(12);
            bottomBar.setLayoutParams(botLp);
            bottomBar.setOrientation(LinearLayout.HORIZONTAL);
            bottomBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

            // 连接测试（左）
            Button btnTest = new Button(this);
            LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(38));
            btnTest.setLayoutParams(testLp);
            btnTest.setBackgroundResource(R.drawable.m3_btn_primary_expressive);
            btnTest.setText("连接测试");
            btnTest.setAllCaps(false);
            btnTest.setTextColor(getColor(R.color.accent_on));
            btnTest.setTextSize(13);
            btnTest.setTypeface(Typeface.DEFAULT_BOLD);
            btnTest.setPadding(dpToPx(16), 0, dpToPx(16), 0);
            try {
                btnTest.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.btn_press_scale));
            } catch (Throwable ignored) {}
            btnTest.setOnClickListener(v -> {
                String testUrl = input.getText() != null ? input.getText().toString().trim() : "";
                testSingleRemoteConnection(testUrl, btnTest);
            });
            bottomBar.addView(btnTest);

            // 删除（右下角，跟随莫奈取色与次级胶囊规范，默认蓝色）
            Button btnDelete = new Button(this);
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(38));
            delLp.setMarginStart(dpToPx(10));
            btnDelete.setLayoutParams(delLp);
            btnDelete.setBackgroundResource(R.drawable.m3_btn_tonal_expressive);
            btnDelete.setText("删除");
            btnDelete.setAllCaps(false);
            btnDelete.setTextColor(getColor(R.color.primary));
            btnDelete.setTextSize(13);
            btnDelete.setTypeface(Typeface.DEFAULT_BOLD);
            btnDelete.setPadding(dpToPx(16), 0, dpToPx(16), 0);
            try {
                btnDelete.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.btn_press_scale));
            } catch (Throwable ignored) {}
            btnDelete.setOnClickListener(v -> confirmDeleteRemoteEntry(entry, titleName));
            bottomBar.addView(btnDelete);

            card.addView(bottomBar);
            // 动态注入全局莫奈调色板，确保深色/浅色胶囊即时演色
            try {
                MonetEngine.applyToViewTree(card, MonetEngine.resolveCurrentPalette(this));
            } catch (Throwable ignored) {}
            remoteListContainer.addView(card);
        }
    }

    private void confirmDeleteRemoteEntry(com.deepseekharness.app.core.ConfigStore.RemoteDshEntry entry, String titleName) {
        FrameLayout root = new FrameLayout(this);
        root.setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(18));
        root.setClipChildren(false);
        root.setClipToPadding(false);

        ModernCardView card = new ModernCardView(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(18));
        card.setCardRadius(18f);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(clp);

        // 1. 顶部标题行: 40dp 图标框 + 标题
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)));
        iconBox.setBackgroundResource(R.drawable.m3_btn_tonal_expressive);

        ImageView iv = new ImageView(this);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dpToPx(22), dpToPx(22));
        ivLp.gravity = Gravity.CENTER;
        iv.setLayoutParams(ivLp);
        iv.setImageResource(R.drawable.ic_globe);
        iv.setColorFilter(getColor(R.color.primary));
        iconBox.addView(iv);
        titleRow.addView(iconBox);

        TextView tvTitle = new TextView(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(dpToPx(12));
        tvTitle.setLayoutParams(tlp);
        tvTitle.setText("删除远端配置");
        tvTitle.setTextColor(getColor(R.color.text));
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(tvTitle);
        card.addView(titleRow);

        // 2. 远端名称
        TextView tvName = new TextView(this);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dpToPx(16);
        tvName.setLayoutParams(nlp);
        tvName.setText(titleName);
        tvName.setTextColor(getColor(R.color.text));
        tvName.setTextSize(16);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(tvName);

        // 3. 内嵌说明面板 (bg_input 样式半透玻璃槽)
        LinearLayout infoBox = new LinearLayout(this);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ibLp.topMargin = dpToPx(12);
        infoBox.setLayoutParams(ibLp);
        infoBox.setBackgroundResource(R.drawable.bg_input);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        infoBox.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));

        TextView tvUrl = new TextView(this);
        tvUrl.setText((entry.url != null && !entry.url.isEmpty()) ? entry.url : "（尚未填写目标地址）");
        tvUrl.setTextColor(getColor(R.color.text_secondary));
        tvUrl.setTextSize(13);
        tvUrl.setTypeface(Typeface.MONOSPACE);
        infoBox.addView(tvUrl);

        TextView tvTip = new TextView(this);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dpToPx(6);
        tvTip.setLayoutParams(tipLp);
        tvTip.setText("确定删除此远端配置吗？删除后此条目将被移除，可随时重新添加。");
        tvTip.setTextColor(getColor(R.color.text_muted));
        tvTip.setTextSize(12);
        infoBox.addView(tvTip);
        card.addView(infoBox);

        // 4. 底部操作按钮行 (高度 44dp, 取消 weight=1, 确认删除 weight=1.2)
        LinearLayout btnRow = new LinearLayout(this);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dpToPx(18);
        btnRow.setLayoutParams(brLp);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnCancel = new Button(this);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dpToPx(44), 1.0f);
        btnCancel.setLayoutParams(cLp);
        btnCancel.setBackgroundResource(R.drawable.m3_btn_tonal_expressive);
        btnCancel.setText("取消");
        btnCancel.setAllCaps(false);
        btnCancel.setTextColor(getColor(R.color.primary));
        btnCancel.setTextSize(15);
        btnCancel.setTypeface(Typeface.DEFAULT_BOLD);
        try {
            btnCancel.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.btn_press_scale));
        } catch (Throwable ignored) {}
        btnRow.addView(btnCancel);

        Button btnConfirm = new Button(this);
        LinearLayout.LayoutParams cfLp = new LinearLayout.LayoutParams(0, dpToPx(44), 1.2f);
        cfLp.setMarginStart(dpToPx(10));
        btnConfirm.setLayoutParams(cfLp);
        btnConfirm.setBackgroundResource(R.drawable.m3_btn_primary_expressive);
        btnConfirm.setText("确认删除");
        btnConfirm.setAllCaps(false);
        btnConfirm.setTextColor(getColor(R.color.accent_on));
        btnConfirm.setTextSize(15);
        btnConfirm.setTypeface(Typeface.DEFAULT_BOLD);
        try {
            btnConfirm.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.btn_press_scale));
        } catch (Throwable ignored) {}
        btnRow.addView(btnConfirm);
        card.addView(btnRow);
        root.addView(card);

        // 莫奈主题注入
        try {
            MonetEngine.applyToViewTree(card, MonetEngine.resolveCurrentPalette(this));
        } catch (Throwable ignored) {}

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(root)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            com.deepseekharness.app.core.ConfigStore cfg = com.deepseekharness.app.core.ConfigStore.get(this);
            remoteList.remove(entry);
            cfg.saveRemoteDshList(remoteList);
            renderRemoteCards();
            ToastHelper.makeText(this, "已删除「" + titleName + "」", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    private void testSingleRemoteConnection(String rawUrl, Button testBtn) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            ToastHelper.makeText(this, "请先输入远端 DSH 地址", Toast.LENGTH_SHORT).show();
            return;
        }
        String formatted = rawUrl.trim();
        if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
            formatted = "http://" + formatted;
        }
        final String targetUrl = formatted;
        if (testBtn != null) {
            testBtn.setEnabled(false);
            testBtn.setText("测试中…");
        }
        ToastHelper.makeText(this, "正在探测远端连接...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            boolean success = false;
            int code = -1;
            String errMsg = null;
            try {
                java.net.URL url = new java.net.URL(targetUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                code = conn.getResponseCode();
                if (code >= 200 && code < 500) {
                    success = true;
                } else {
                    errMsg = "服务器响应错误: " + code;
                }
                conn.disconnect();
            } catch (Exception e) {
                errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }

            final boolean finalSuccess = success;
            final int finalCode = code;
            final String finalErr = errMsg;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (testBtn != null) {
                    testBtn.setEnabled(true);
                    testBtn.setText("连接测试");
                }
                if (finalSuccess) {
                    ToastHelper.makeText(this, "✓ 远端连接成功 (HTTP " + finalCode + ")", Toast.LENGTH_SHORT).show();
                } else {
                    ToastHelper.makeText(this, "✗ 连接失败: " + finalErr, Toast.LENGTH_LONG).show();
                }
            });
        }, "dsha-remote-test").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshData();
    }

    private void refreshData() {
        HarnessController controller = HarnessController.get(this);
        String bridgeToken = HttpShellService.currentToken();
        bridgeTokenText.setText(bridgeToken.isEmpty() ? "（尚未生成）" : bridgeToken);

        String authUrl = controller != null ? controller.getWebAuthUrl() : "";
        authUrlText.setText(authUrl.isEmpty() ? "（服务未启动）" : authUrl);

        boolean lan = getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_LAN_MODE, false);
        if (lanSwitch != null && lanSwitch.isChecked() != lan) {
            lanSwitch.setChecked(lan);
        }
        String ip = HarnessController.getLanAddress();

        if (lan && ip != null && !ip.isEmpty()) {
            String lanUrl = "http://" + ip + ":" + LanProxyService.LAN_PORT + "/?token="
                    + LanProxyService.getLanToken(this);
            lanAddrText.setText(lanUrl);
            lanBadgeText.setText("已开启");
            lanBadgeText.setTextColor(getColor(R.color.ok));
        } else if (lan) {
            lanAddrText.setText("局域网模式已开启，等待获取 WiFi IP…");
            lanBadgeText.setText("等待网络");
            lanBadgeText.setTextColor(getColor(R.color.warn));
        } else {
            lanAddrText.setText("（局域网未开启，请在首页或设置中打开）");
            lanBadgeText.setText("未开启");
            lanBadgeText.setTextColor(getColor(R.color.text_muted));
        }

        loadRemoteList();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fragment_pop_enter, R.anim.fragment_pop_exit);
    }

    private void enterWeb() {
        HarnessController controller = HarnessController.get(this);
        if (controller == null) return;
        String authUrl = controller.getWebAuthUrl();
        if (authUrl.isEmpty()) {
            ToastHelper.makeText(this, "先点「启动」，等服务就绪后再进入", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String cookie = controller.exchangeDshAuthCookie();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                startActivity(WebPreviewActivity.intent(this, authUrl, cookie));
            });
        }, "cred-cookie").start();
    }

    private void copyToClipboard(String label, String text) {
        if (text.startsWith("（") && text.endsWith("）")) {
            ToastHelper.makeText(this, "当前内容无效，无需复制", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                ToastHelper.makeText(this, label + " 已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            ToastHelper.makeText(this, "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
