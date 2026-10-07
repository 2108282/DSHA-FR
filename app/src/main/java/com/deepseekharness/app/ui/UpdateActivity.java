package com.deepseekharness.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.UpdateRepository;
import com.deepseekharness.app.ui.contract.UpdateActions;
import com.deepseekharness.app.ui.contract.UpdateUiState;
import com.deepseekharness.app.util.UpdatePolicy;

import java.util.Locale;

public final class UpdateActivity extends AppCompatActivity implements UpdateActions {

    private UpdateRepository repository;
    private final UpdateActions actions = this;
    private boolean resumeInstall;

    private TextView statusView;
    private TextView notesView;
    private ProgressBar progressBar;
    private TextView bytesView;
    private View checkBtn;
    private View downloadBtn;
    private View installBtn;
    private View cancelBtn;
    private RadioGroup channelsGroup;
    private RadioButton stableRadio;
    private RadioButton previewRadio;

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

        // 顶栏日夜间切换
        TextView themeBtn = findViewById(R.id.btn_theme);
        if (themeBtn != null) {
            boolean dark = ThemeController.isDark(this);
            themeBtn.setText(dark ? "☀ 白天" : "☾ 黑夜");
            themeBtn.setOnClickListener(v -> ThemeController.toggle(this));
        }

        repository = new ViewModelProvider(this).get(UpdateRepository.class);

        ((TextView) findViewById(R.id.update_current)).setText("当前 " + BuildConfig.VERSION_NAME + " · 版本码 " + BuildConfig.VERSION_CODE
                + (BuildConfig.LOW_ANDROID ? " · 兼容版" : " · 标准版"));

        statusView = findViewById(R.id.update_status);
        notesView = findViewById(R.id.update_notes);
        progressBar = findViewById(R.id.update_progress);
        bytesView = findViewById(R.id.update_bytes);
        checkBtn = findViewById(R.id.update_check);
        downloadBtn = findViewById(R.id.update_download);
        installBtn = findViewById(R.id.update_install);
        cancelBtn = findViewById(R.id.update_cancel);
        channelsGroup = findViewById(R.id.update_channels);
        stableRadio = findViewById(R.id.update_stable);
        previewRadio = findViewById(R.id.update_preview);

        boolean isPreview = UpdatePolicy.PREVIEW.equals(repository.channel());
        channelsGroup.check(isPreview ? R.id.update_preview : R.id.update_stable);
        updateRadioStyles(isPreview);

        channelsGroup.setOnCheckedChangeListener((g, id) -> {
            boolean prev = (id == R.id.update_preview);
            updateRadioStyles(prev);
            actions.onChannelSelect(prev);
        });

        findViewById(R.id.update_back).setOnClickListener(v -> actions.onBackClick());
        checkBtn.setOnClickListener(v -> actions.onCheckClick());
        downloadBtn.setOnClickListener(v -> actions.onDownloadClick());
        cancelBtn.setOnClickListener(v -> actions.onCancelClick());
        installBtn.setOnClickListener(v -> actions.onInstallClick());
        findViewById(R.id.update_browser).setOnClickListener(v -> actions.onOpenBrowserClick());

        repository.state().observe(this, state -> {
            String notes = state.release == null
                    ? "稳定通道只接收稳定版；预览通道也接收后续稳定版。自动匹配当前高/低版本，版本码不增加时不会提示更新。"
                    : state.release.version + " · " + String.format(Locale.ROOT, "%.2f MiB", state.release.bytes / 1048576.0) + "\n\n" + state.release.notes;
            int pct = state.total > 0 ? (int) (state.downloaded * 100 / state.total) : 0;
            String bytes = state.total > 0 ? String.format(Locale.ROOT, "%.1f / %.1f MiB", state.downloaded / 1048576.0, state.total / 1048576.0) : "";

            render(new UpdateUiState(
                    state.message,
                    notes,
                    state.busy,
                    state.total <= 0,
                    pct,
                    bytes,
                    !state.busy,
                    !state.busy && state.release != null,
                    !state.busy && state.apk != null
            ));
        });

        if (savedInstanceState == null) repository.check();
    }

    private void updateRadioStyles(boolean isPreview) {
        if (stableRadio != null && previewRadio != null) {
            stableRadio.setBackgroundResource(!isPreview ? R.drawable.bg_tab_on : R.drawable.bg_tab);
            stableRadio.setTextColor(getColor(!isPreview ? R.color.primary : R.color.text_secondary));
            previewRadio.setBackgroundResource(isPreview ? R.drawable.bg_tab_on : R.drawable.bg_tab);
            previewRadio.setTextColor(getColor(isPreview ? R.color.primary : R.color.text_secondary));
        }
    }

    private void render(UpdateUiState state) {
        if (statusView != null) statusView.setText(state.statusMessage);
        if (notesView != null) notesView.setText(state.notesText);
        if (progressBar != null) {
            progressBar.setVisibility(state.isBusy ? View.VISIBLE : View.GONE);
            progressBar.setIndeterminate(state.isIndeterminate);
            if (!state.isIndeterminate) progressBar.setProgress(state.progressPercent);
        }
        if (bytesView != null) {
            bytesView.setVisibility(state.bytesText.isEmpty() ? View.GONE : View.VISIBLE);
            bytesView.setText(state.bytesText);
        }
        if (checkBtn != null) {
            checkBtn.setEnabled(state.isCheckEnabled);
            checkBtn.setVisibility(!state.isInstallEnabled ? View.VISIBLE : View.GONE);
        }
        if (downloadBtn != null) {
            downloadBtn.setEnabled(state.isDownloadEnabled);
            downloadBtn.setVisibility(state.isDownloadEnabled ? View.VISIBLE : View.GONE);
        }
        if (installBtn != null) {
            installBtn.setEnabled(state.isInstallEnabled);
            installBtn.setVisibility(state.isInstallEnabled ? View.VISIBLE : View.GONE);
        }
        if (cancelBtn != null) cancelBtn.setVisibility(state.isBusy ? View.VISIBLE : View.GONE);
        if (channelsGroup != null) {
            for (int i = 0; i < channelsGroup.getChildCount(); i++) {
                channelsGroup.getChildAt(i).setEnabled(!state.isBusy);
            }
        }
    }

    @Override
    public void onBackClick() {
        finish();
    }

    @Override
    public void onChannelSelect(boolean isPreview) {
        if (repository != null) {
            repository.setChannel(isPreview ? UpdatePolicy.PREVIEW : UpdatePolicy.STABLE);
        }
    }

    @Override
    public void onCheckClick() {
        if (repository != null) repository.check();
    }

    @Override
    public void onDownloadClick() {
        if (repository != null) repository.download();
    }

    @Override
    public void onCancelClick() {
        if (repository != null) repository.cancel();
    }

    @Override
    public void onInstallClick() {
        install();
    }

    private void install() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                resumeInstall = true;
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", repository.installableApk());
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception error) {
            Toast.makeText(this, "无法安装：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onOpenBrowserClick() {
        UpdateRepository.State state = repository != null ? repository.state().getValue() : null;
        AboutDialog.openBrowser(this, state != null && state.release != null ? state.release.pageUrl
                : AboutDialog.GITHUB_ROOT_URL + "/releases");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumeInstall) {
            resumeInstall = false;
            if (android.os.Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) {
                install();
            } else {
                Toast.makeText(this, "未允许安装更新，可稍后重试", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
