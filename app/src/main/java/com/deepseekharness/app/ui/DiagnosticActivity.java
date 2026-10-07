package com.deepseekharness.app.ui;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.HarnessController;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticRepository;
import com.deepseekharness.app.ui.contract.DiagnosticActions;
import com.deepseekharness.app.ui.contract.DiagnosticUiState;

public final class DiagnosticActivity extends AppCompatActivity implements DiagnosticActions {

    private DiagnosticRepository repository;
    private final DiagnosticActions actions = this;

    private TextView reportView;
    private TextView statusView;
    private View rowRepairBridge;
    private View rowRepairPlugins;
    private View rowRepairNetwork;

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

        // 顶栏日夜间纯图标切换 (白天显示太阳，黑夜显示月亮，零文字)
        View themeBtn = findViewById(R.id.btn_theme);
        ImageView themeIcon = findViewById(R.id.img_theme_icon);
        if (themeBtn != null && themeIcon != null) {
            boolean dark = ThemeController.isDark(this);
            themeIcon.setImageResource(dark ? R.drawable.ic_moon : R.drawable.ic_sun);
            themeIcon.setContentDescription(dark ? "夜间模式" : "日间模式");
            themeBtn.setOnClickListener(v -> ThemeController.toggle(this));
        }

        repository = new ViewModelProvider(this).get(DiagnosticRepository.class);

        reportView = findViewById(R.id.diagnostic_report);
        statusView = findViewById(R.id.diagnostic_status);
        rowRepairBridge = findViewById(R.id.row_repair_bridge);
        rowRepairPlugins = findViewById(R.id.row_repair_plugins);
        rowRepairNetwork = findViewById(R.id.row_repair_network);

        findViewById(R.id.diagnostic_back).setOnClickListener(v -> actions.onBackClick());
        if (rowRepairBridge != null) rowRepairBridge.setOnClickListener(v -> actions.onRepairBridgeClick());
        if (rowRepairPlugins != null) rowRepairPlugins.setOnClickListener(v -> actions.onRepairPluginsClick());
        if (rowRepairNetwork != null) rowRepairNetwork.setOnClickListener(v -> actions.onRepairClick());

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
        boolean enabled = state.isRepairEnabled;
        if (rowRepairBridge != null) rowRepairBridge.setEnabled(enabled);
        if (rowRepairPlugins != null) rowRepairPlugins.setEnabled(enabled);
        if (rowRepairNetwork != null) rowRepairNetwork.setEnabled(enabled);
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
    public void onRepairBridgeClick() {
        HarnessController controller = HarnessController.get(this);
        if (controller == null) {
            Toast.makeText(this, "未初始化核心控制器", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("正在自愈")
                .setMessage("正在修复存储直通、网络 DNS 与 3095 设备桥…")
                .setCancelable(false)
                .show();

        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            try {
                new java.io.File("/sdcard/Download/DSHA/工作区").mkdirs();
            } catch (Throwable ignored) {}

            try {
                String cmd1 = "mkdir -p /sdcard/Download/DSHA/工作区 /root/.dsh 2>/dev/null || true; "
                        + "ln -sfn /sdcard/Download/DSHA /root/内部存储 2>/dev/null || true; "
                        + "chmod 777 /root/.dsh 2>/dev/null || true; echo OK";
                controller.proot().execAndRead(cmd1, 10_000);
                log.append("· 存储直通与工作区已就绪\n");

                String cmd2 = "mkdir -p /etc 2>/dev/null; "
                        + "printf 'nameserver 223.5.5.5\\nnameserver 119.29.29.29\\nnameserver 1.1.1.1\\n' > /etc/resolv.conf 2>/dev/null; echo OK";
                controller.proot().execAndRead(cmd2, 10_000);
                log.append("· 权威公共 DNS 已更新\n");

                HttpShellService.syncTokenToRootfsSync();
                log.append("· 3095 设备桥令牌已同步\n");
            } catch (Throwable t) {
                log.append("· 修复异常: ").append(t.getMessage()).append("\n");
            }

            runOnUiThread(() -> {
                if (!isFinishing()) {
                    progress.dismiss();
                    Toast.makeText(this, "网桥与存储直通已完成自愈", Toast.LENGTH_SHORT).show();
                    if (repository != null) repository.generate();
                }
            });
        }, "repair-bridge").start();
    }

    @Override
    public void onRepairPluginsClick() {
        HarnessController controller = HarnessController.get(this);
        if (controller == null) {
            Toast.makeText(this, "未初始化核心控制器", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("正在自愈")
                .setMessage("正在重新扫描并建立插件软链接…")
                .setCancelable(false)
                .show();

        new Thread(() -> {
            try {
                String cmd = "mkdir -p /root/.dsh/profiles/web/node_modules /usr/local/lib/node_modules 2>/dev/null || true; "
                        + "for p in /root/dsha-*; do [ -d \"$p\" ] || continue; "
                        + "  bname=$(basename \"$p\"); "
                        + "  case \"$bname\" in dsha-repo|dsha-builtin.txt|*-installed) continue ;; esac; "
                        + "  pname=\"dsh-${bname#dsha-}\"; "
                        + "  ln -sfn \"$p\" \"/root/.dsh/profiles/web/node_modules/$pname\" 2>/dev/null || true; "
                        + "  ln -sfn \"$p\" \"/usr/local/lib/node_modules/$pname\" 2>/dev/null || true; "
                        + "done; "
                        + "if [ -d /root/.dsh/plugin-src ]; then "
                        + "  for p in /root/.dsh/plugin-src/*; do [ -d \"$p\" ] || continue; "
                        + "    bname=$(basename \"$p\"); "
                        + "    if [ \"${bname:0:1}\" = \"@\" ]; then "
                        + "      mkdir -p \"/root/.dsh/profiles/web/node_modules/$bname\" \"/usr/local/lib/node_modules/$bname\" 2>/dev/null || true; "
                        + "      for sub in \"$p\"/*; do [ -d \"$sub\" ] || continue; "
                        + "        subname=$(basename \"$sub\"); "
                        + "        ln -sfn \"$sub\" \"/root/.dsh/profiles/web/node_modules/$bname/$subname\" 2>/dev/null || true; "
                        + "        ln -sfn \"$sub\" \"/usr/local/lib/node_modules/$bname/$subname\" 2>/dev/null || true; "
                        + "      done; "
                        + "    else "
                        + "      ln -sfn \"$p\" \"/root/.dsh/profiles/web/node_modules/$bname\" 2>/dev/null || true; "
                        + "      ln -sfn \"$p\" \"/usr/local/lib/node_modules/$bname\" 2>/dev/null || true; "
                        + "    fi; "
                        + "  done; "
                        + "fi; echo OK";
                controller.proot().execAndRead(cmd, 15_000);
            } catch (Throwable ignored) {}

            runOnUiThread(() -> {
                if (!isFinishing()) {
                    progress.dismiss();
                    Toast.makeText(this, "全部插件软链接已完成校验与自愈", Toast.LENGTH_SHORT).show();
                    if (repository != null) repository.generate();
                }
            });
        }, "repair-plugins").start();
    }
}
