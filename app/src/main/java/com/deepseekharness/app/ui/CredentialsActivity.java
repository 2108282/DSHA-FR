package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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

    private TextView remoteBadgeText;
    private DshaToggle remoteSwitch;
    private android.widget.EditText remoteUrlInput;
    private Button remoteTestBtn;

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

        // 远端连接配置
        remoteBadgeText = findViewById(R.id.cred_remote_badge);
        remoteSwitch = findViewById(R.id.cred_remote_switch);
        remoteUrlInput = findViewById(R.id.cred_remote_url_input);
        remoteTestBtn = findViewById(R.id.cred_remote_test_btn);

        com.deepseekharness.app.core.ConfigStore cfg = com.deepseekharness.app.core.ConfigStore.get(this);
        if (remoteUrlInput != null) {
            String savedUrl = cfg.getRemoteDshUrl();
            if (savedUrl != null && !savedUrl.isEmpty()) {
                remoteUrlInput.setText(savedUrl);
            }
            remoteUrlInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    cfg.setRemoteDshUrl(s != null ? s.toString().trim() : "");
                }
            });
        }

        if (remoteSwitch != null) {
            boolean enabled = cfg.isRemoteDshEnabled();
            remoteSwitch.setChecked(enabled);
            updateRemoteBadge(enabled);
            remoteSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                cfg.setRemoteDshEnabled(isChecked);
                updateRemoteBadge(isChecked);
            });
        }

        if (remoteTestBtn != null) {
            remoteTestBtn.setOnClickListener(v -> testRemoteConnection());
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
            Toast.makeText(this, "已重新生成 Token 并更新地址", Toast.LENGTH_SHORT).show();
            refreshData();
        });

        refreshData();
        MonetEngine.applyToActivity(this);
    }

    private void updateRemoteBadge(boolean enabled) {
        if (remoteBadgeText == null) return;
        if (enabled) {
            remoteBadgeText.setText("已启用");
            remoteBadgeText.setTextColor(getColor(R.color.ok));
        } else {
            remoteBadgeText.setText("未启用");
            remoteBadgeText.setTextColor(getColor(R.color.text_muted));
        }
    }

    private void testRemoteConnection() {
        if (remoteUrlInput == null) return;
        String raw = remoteUrlInput.getText() != null ? remoteUrlInput.getText().toString().trim() : "";
        if (raw.isEmpty()) {
            Toast.makeText(this, "请先输入远端 DSH 地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "http://" + raw;
        }
        final String targetUrl = raw;
        if (remoteTestBtn != null) {
            remoteTestBtn.setEnabled(false);
            remoteTestBtn.setText("测试中…");
        }
        Toast.makeText(this, "正在探测远端连接...", Toast.LENGTH_SHORT).show();

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
                if (remoteTestBtn != null) {
                    remoteTestBtn.setEnabled(true);
                    remoteTestBtn.setText("连接测试");
                }
                if (finalSuccess) {
                    Toast.makeText(this, "✓ 远端连接成功 (HTTP " + finalCode + ")", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "✗ 连接失败: " + finalErr, Toast.LENGTH_LONG).show();
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

        com.deepseekharness.app.core.ConfigStore cfg = com.deepseekharness.app.core.ConfigStore.get(this);
        boolean remoteEnabled = cfg.isRemoteDshEnabled();
        if (remoteSwitch != null && remoteSwitch.isChecked() != remoteEnabled) {
            remoteSwitch.setChecked(remoteEnabled);
        }
        updateRemoteBadge(remoteEnabled);
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
            Toast.makeText(this, "先点「启动」，等服务就绪后再进入", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "当前内容无效，无需复制", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                Toast.makeText(this, label + " 已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
