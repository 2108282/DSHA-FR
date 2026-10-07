package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticRepository;
import com.deepseekharness.app.ui.contract.DiagnosticActions;
import com.deepseekharness.app.ui.contract.DiagnosticUiState;

public final class DiagnosticActivity extends AppCompatActivity implements DiagnosticActions {

    private DiagnosticRepository repository;
    private final DiagnosticActions actions = this;

    private TextView reportView;
    private TextView statusView;
    private View repairBtn;

    private String lastReport = "";
    private boolean lastBusy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeController.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

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

        repository = new ViewModelProvider(this).get(DiagnosticRepository.class);

        reportView = findViewById(R.id.diagnostic_report);
        statusView = findViewById(R.id.diagnostic_status);
        repairBtn = findViewById(R.id.diagnostic_repair);

        findViewById(R.id.diagnostic_back).setOnClickListener(v -> actions.onBackClick());
        repairBtn.setOnClickListener(v -> actions.onRepairClick());
        findViewById(R.id.diagnostic_plugins).setOnClickListener(v -> actions.onOpenPluginsClick());

        repository.report.observe(this, text -> {
            lastReport = text != null ? text : "";
            renderState();
        });

        repository.busy.observe(this, busy -> {
            lastBusy = Boolean.TRUE.equals(busy);
            renderState();
        });

        if (savedInstanceState == null) repository.generate();
    }

    private void renderState() {
        String status = lastBusy ? "正在检查环境…" : "诊断完成（报告保留在本机）";
        render(new DiagnosticUiState(lastReport, status, !lastBusy));
    }

    private void render(DiagnosticUiState state) {
        if (reportView != null) reportView.setText(state.reportText);
        if (statusView != null) statusView.setText(state.statusText);
        if (repairBtn != null) repairBtn.setEnabled(state.isRepairEnabled);
    }

    @Override
    public void onBackClick() {
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fragment_pop_enter, R.anim.fragment_pop_exit);
    }

    @Override
    public void onRepairClick() {
        if (repository != null) repository.repairNetworkTools();
    }

    @Override
    public void onOpenPluginsClick() {
        startActivity(new Intent(this, MainActivity.class).putExtra("open_plugins", true));
    }
}
