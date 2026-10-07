package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class UpdateActivity extends AppCompatActivity {

    private static final String URL_DSHA_RELEASES_API = "https://api.github.com/repos/2108282/DSHA-FR/releases?per_page=10";
    private static final String URL_DSHA_RELEASES_PAGE = "https://github.com/2108282/DSHA-FR/releases";

    private static final String URL_CORE_RELEASES_API = "https://api.github.com/repos/deepseek-ai/deepseek-harness/releases?per_page=5";
    private static final String URL_CORE_RELEASES_PAGE = "https://github.com/deepseek-ai/deepseek-harness/releases";

    private TextView statusView;
    private TextView notesView;
    private ProgressBar progressBar;
    private RadioGroup channelsGroup;
    private RadioButton stableRadio;
    private RadioButton previewRadio;
    private Button browserBtn;
    private View coreActionsLayout;
    private Button copyCmdBtn;
    private Button copyMirrorCmdBtn;

    private boolean isCoreChannel = false;
    private String currentBrowserUrl = URL_DSHA_RELEASES_PAGE;
    private String latestCoreVersion = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeController.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);

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

        ((TextView) findViewById(R.id.update_current)).setText("当前安装版本：" + BuildConfig.VERSION_NAME + " · 版本码 " + BuildConfig.VERSION_CODE
                + (BuildConfig.LOW_ANDROID ? " · 兼容版" : " · 标准版"));

        TextView currentCoreView = findViewById(R.id.update_current_core);
        queryInstalledCoreVersion(currentCoreView);

        statusView = findViewById(R.id.update_status);
        notesView = findViewById(R.id.update_notes);
        progressBar = findViewById(R.id.update_progress);
        channelsGroup = findViewById(R.id.update_channels);
        stableRadio = findViewById(R.id.update_stable);
        previewRadio = findViewById(R.id.update_preview);
        browserBtn = findViewById(R.id.update_browser);
        coreActionsLayout = findViewById(R.id.layout_core_actions);
        copyCmdBtn = findViewById(R.id.update_copy_cmd);
        copyMirrorCmdBtn = findViewById(R.id.update_copy_mirror_cmd);

        findViewById(R.id.update_back).setOnClickListener(v -> finish());

        // 默认选中客户端与模块 (Stable)
        channelsGroup.check(R.id.update_stable);
        updateRadioStyles(false);

        channelsGroup.setOnCheckedChangeListener((g, id) -> {
            boolean isCore = (id == R.id.update_preview);
            isCoreChannel = isCore;
            updateRadioStyles(isCore);
            loadReleaseData(isCore);
        });

        browserBtn.setOnClickListener(v -> openBrowser(currentBrowserUrl));

        if (copyCmdBtn != null) {
            copyCmdBtn.setOnClickListener(v -> {
                if (latestCoreVersion.isEmpty()) {
                    Toast.makeText(this, "正在拉取核心版本号，请稍候…", Toast.LENGTH_SHORT).show();
                    return;
                }
                String cmd = "npm install -g @deepseek-ai/dsh@" + latestCoreVersion;
                copyToClipboard("官方更新命令", cmd);
                Toast.makeText(this, "已复制官方更新命令：\n" + cmd, Toast.LENGTH_SHORT).show();
            });
        }

        if (copyMirrorCmdBtn != null) {
            copyMirrorCmdBtn.setOnClickListener(v -> {
                if (latestCoreVersion.isEmpty()) {
                    Toast.makeText(this, "正在拉取核心版本号，请稍候…", Toast.LENGTH_SHORT).show();
                    return;
                }
                String cmd = "npm install -g @deepseek-ai/dsh@" + latestCoreVersion + " --registry=https://registry.npmmirror.com";
                copyToClipboard("国内源更新命令", cmd);
                Toast.makeText(this, "已复制国内源更新命令：\n" + cmd, Toast.LENGTH_SHORT).show();
            });
        }

        // 首次进入加载客户端与模块发布信息
        loadReleaseData(false);
    }

    private void updateRadioStyles(boolean isCore) {
        if (stableRadio != null && previewRadio != null) {
            stableRadio.setBackgroundResource(!isCore ? R.drawable.bg_tab_on : R.drawable.bg_tab);
            stableRadio.setTextColor(getColor(!isCore ? R.color.primary : R.color.text_secondary));
            previewRadio.setBackgroundResource(isCore ? R.drawable.bg_tab_on : R.drawable.bg_tab);
            previewRadio.setTextColor(getColor(isCore ? R.color.primary : R.color.text_secondary));
        }
    }

    private void loadReleaseData(boolean isCore) {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (statusView != null) statusView.setText(isCore ? "正在拉取原生 DSH 核心最新 Release…" : "正在拉取客户端最新正式 Release…");
        if (notesView != null) notesView.setText("");

        if (coreActionsLayout != null) {
            coreActionsLayout.setVisibility(isCore ? View.VISIBLE : View.GONE);
        }

        new Thread(() -> {
            String apiUrl = isCore ? URL_CORE_RELEASES_API : URL_DSHA_RELEASES_API;
            String fallbackUrl = isCore ? URL_CORE_RELEASES_PAGE : URL_DSHA_RELEASES_PAGE;

            HttpURLConnection conn = null;
            try {
                URL url = new URL(apiUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "DSHA-Client");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONArray releases = new JSONArray(sb.toString());
                    JSONObject target = null;

                    if (!isCore) {
                        // 通道 1: 客户端与模块 -> 过滤排除预览版 (prerelease == false)
                        for (int i = 0; i < releases.length(); i++) {
                            JSONObject r = releases.getJSONObject(i);
                            if (!r.optBoolean("prerelease", false)) {
                                target = r;
                                break;
                            }
                        }
                        if (target == null && releases.length() > 0) {
                            target = releases.getJSONObject(0);
                        }
                    } else {
                        // 通道 2: 原生 DSH 核心 -> 包含预览版，直接取最新第一项
                        if (releases.length() > 0) {
                            target = releases.getJSONObject(0);
                        }
                    }

                    if (target != null) {
                        final String tagName = target.optString("tag_name", "");
                        final String title = target.optString("name", tagName);
                        final String body = target.optString("body", "暂无详细发布说明。");
                        final String pageUrl = target.optString("html_url", fallbackUrl);

                        final String parsedVersion;
                        if (isCore) {
                            String vStr = tagName;
                            if (vStr.startsWith("dsh-v")) vStr = vStr.substring(5);
                            else if (vStr.startsWith("dsh-")) vStr = vStr.substring(4);
                            else if (vStr.startsWith("v")) vStr = vStr.substring(1);
                            parsedVersion = vStr.isEmpty() ? tagName : vStr;
                        } else {
                            parsedVersion = tagName;
                        }

                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            if (progressBar != null) progressBar.setVisibility(View.GONE);
                            currentBrowserUrl = pageUrl;

                            if (isCore) {
                                latestCoreVersion = parsedVersion;
                                if (statusView != null) {
                                    statusView.setText("DSH 核心最新发布：" + tagName);
                                }
                                if (notesView != null) {
                                    notesView.setText("【版本】 " + title + "\n【动态版本号】 " + parsedVersion + "\n\n" + body);
                                }
                            } else {
                                if (statusView != null) {
                                    statusView.setText("客户端最新正式版：" + tagName);
                                }
                                if (notesView != null) {
                                    notesView.setText("【标题】 " + title + "\n\n" + body);
                                }
                            }
                        });
                        return;
                    }
                }
                throw new Exception("HTTP " + code);
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    currentBrowserUrl = fallbackUrl;
                    if (statusView != null) {
                        statusView.setText("拉取 Release 失败（可能受 GitHub API 限制）");
                    }
                    if (notesView != null) {
                        notesView.setText("错误信息：" + t.getMessage() + "\n\n可点击下方「在浏览器查看」按钮直达 GitHub Releases 网页浏览与下载。");
                    }
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "fetch-releases").start();
    }

    private void openBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "无法调用系统浏览器: " + t.getMessage(), Toast.LENGTH_SHORT).show();
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

    private void queryInstalledCoreVersion(TextView targetView) {
        if (targetView == null) return;
        new Thread(() -> {
            String ver = "";
            try {
                com.deepseekharness.app.core.HarnessController ctl = com.deepseekharness.app.core.HarnessController.get(this);
                if (ctl != null && ctl.proot().isEnvironmentReady()) {
                    String cmd = "cat /usr/local/lib/node_modules/@deepseek-ai/dsh/package.json 2>/dev/null | grep \x27\"version\"\x27 | head -n 1 | awk -F\x27\"\x27 \x27{print $4}\x27";
                    ver = ctl.proot().execAndRead(cmd, 3000).trim();
                    if (ver.isEmpty()) {
                        ver = ctl.proot().execAndRead("dsh --version 2>/dev/null || true", 3000).trim();
                    }
                }
            } catch (Throwable ignored) {}

            final String displayText = ver.isEmpty()
                    ? "当前 DSH 核心版本：未安装 / 未启动"
                    : "当前 DSH 核心版本：v" + ver.replace("v", "");

            runOnUiThread(() -> {
                if (!isFinishing() && targetView != null) {
                    targetView.setText(displayText);
                }
            });
        }, "query-core-version").start();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fragment_pop_enter, R.anim.fragment_pop_exit);
    }
}
