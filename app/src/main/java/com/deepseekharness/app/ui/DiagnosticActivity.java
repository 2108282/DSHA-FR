package com.deepseekharness.app.ui;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticRepository;
import com.deepseekharness.app.core.HarnessController;
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

    private void showActionConfirmDialog(String title, String message, int iconRes, Runnable onConfirm) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_action, null);
        AlertDialog dialog = new DshaDialogBuilder(this).setView(dialogView).create();

        ImageView imgIcon = dialogView.findViewById(R.id.dialogActionIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogActionTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogActionMessage);
        Button btnCancel = dialogView.findViewById(R.id.btnActionCancel);
        Button btnConfirm = dialogView.findViewById(R.id.btnActionConfirm);

        if (imgIcon != null) imgIcon.setImageResource(iconRes);
        if (tvTitle != null) tvTitle.setText(title);
        if (tvMessage != null) tvMessage.setText(message);

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                dialog.dismiss();
                if (onConfirm != null) onConfirm.run();
            });
        }

        dialog.show();
    }

    @Override
    public void onRepairClick() {
        showActionConfirmDialog(
                "修复证书与网络环境？",
                "将重新部署系统根证书到标准路径 (/etc/ssl 与 /usr/lib/ssl)，修复 Python SSL 握手与 npm 镜像网络连接。",
                R.drawable.ic_wifi,
                () -> {
                    if (repository != null) repository.repairNetworkTools();
                }
        );
    }

    @Override
    public void onRepairBridgeClick() {
        showActionConfirmDialog(
                "修复网桥与存储环境？",
                "将重新建立内部存储直通链接 (/root/内部存储)、修复公共权威 DNS 解析，并重新同步 3095 设备桥通信凭据。",
                R.drawable.ic_settings_sliders,
                this::executeRepairBridge
        );
    }

    private void executeRepairBridge() {
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
            try {
                new java.io.File("/sdcard/Download/DSHA/工作区").mkdirs();
            } catch (Throwable ignored) {}

            try {
                String cmd1 = "mkdir -p /sdcard/Download/DSHA/工作区 /root/.dsh 2>/dev/null || true; "
                        + "ln -sfn /sdcard/Download/DSHA /root/内部存储 2>/dev/null || true; "
                        + "chmod 777 /root/.dsh 2>/dev/null || true; echo OK";
                controller.proot().execAndRead(cmd1, 10_000);

                String cmd2 = "mkdir -p /etc 2>/dev/null; "
                        + "printf 'nameserver 223.5.5.5\\nnameserver 119.29.29.29\\nnameserver 1.1.1.1\\n' > /etc/resolv.conf 2>/dev/null; echo OK";
                controller.proot().execAndRead(cmd2, 10_000);

                HttpShellService.syncTokenToRootfsSync();
            } catch (Throwable ignored) {}

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
        showActionConfirmDialog(
                "修复插件丢失问题？",
                "将部署并执行 /usr/local/bin/dsha-heal 智能自愈脚本，全盘扫描所有扩展插件，自动重挂软链并补齐 Cordis 配置清单。",
                R.drawable.ic_plugins,
                this::executeRepairPlugins
        );
    }

    private void executeRepairPlugins() {
        HarnessController controller = HarnessController.get(this);
        if (controller == null) {
            Toast.makeText(this, "未初始化核心控制器", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("正在自愈")
                .setMessage("正在执行智能插件找回与自愈脚本…")
                .setCancelable(false)
                .show();

        new Thread(() -> {
            String healScript = "cat << 'EOF' > /usr/local/bin/dsha-heal\n"
                    + "#!/bin/bash\n"
                    + "python3 -c \"\n"
                    + "import os, json\n"
                    + "search_paths = ['/root/.dsh/plugin-src', '/root', '/root/.dsh', '/usr/local/lib/node_modules', '/root/.dsh/profiles/web/node_modules', '/sdcard/Download/DSHA']\n"
                    + "pkg_f = '/root/.dsh/profiles/web/package.json'\n"
                    + "manifest = json.load(open(pkg_f))\n"
                    + "bundles = manifest['dsh']['profile']['bundles']\n"
                    + "deps = manifest['dependencies']\n"
                    + "nm = '/root/.dsh/profiles/web/node_modules'\n"
                    + "gnm = '/usr/local/lib/node_modules'\n"
                    + "found = {}\n"
                    + "for base in search_paths:\n"
                    + "    if not os.path.exists(base): continue\n"
                    + "    for root, dirs, files in os.walk(base):\n"
                    + "        if 'package.json' in files:\n"
                    + "            try:\n"
                    + "                data = json.load(open(os.path.join(root, 'package.json')))\n"
                    + "                name = data.get('name')\n"
                    + "                if name and ('dsh' in name or (data.get('dsh') or {}).get('bundle') or 'cordis.patch.yml' in files):\n"
                    + "                    if not name.startswith('@deepseek-ai/dsh-') or name in ['@deepseek-ai/dsh-im']:\n"
                    + "                        if name not in found and root != '/root/.dsh/profiles/web':\n"
                    + "                            found[name] = root\n"
                    + "            except Exception: pass\n"
                    + "        dirs[:] = [d for d in dirs if d not in ['.git', 'node_modules', '.pnpm', 'dist']]\n"
                    + "added = []\n"
                    + "for name, path in sorted(found.items()):\n"
                    + "    if name not in bundles:\n"
                    + "        bundles.append(name)\n"
                    + "        deps[name] = 'link:' + path\n"
                    + "        added.append(name)\n"
                    + "    for link_base in [nm, gnm]:\n"
                    + "        dest = os.path.join(link_base, name)\n"
                    + "        os.makedirs(os.path.dirname(dest), exist_ok=True)\n"
                    + "        if os.path.lexists(dest):\n"
                    + "            try: os.remove(dest)\n"
                    + "            except: pass\n"
                    + "        try: os.symlink(path, dest)\n"
                    + "        except: pass\n"
                    + "json.dump(manifest, open(pkg_f, 'w'), indent=2)\n"
                    + "print('✓ 扫描完成！已登记并就绪的所有插件:', [x for x in bundles if not x.startswith('@deepseek-ai/')])\n"
                    + "if added: print('★ 本次新找回并补齐的插件:', added)\n"
                    + "\"\n"
                    + "EOF\n"
                    + "chmod +x /usr/local/bin/dsha-heal\n"
                    + "/usr/local/bin/dsha-heal\n";

            String result = "";
            try {
                result = controller.proot().execAndRead(healScript, 30_000);
            } catch (Throwable t) {
                result = "执行异常: " + t.getMessage();
            }

            final String finalResult = result;
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    progress.dismiss();
                    Toast.makeText(this, "插件丢失自愈执行完成", Toast.LENGTH_SHORT).show();
                    if (repository != null) repository.generate();
                }
            });
        }, "repair-plugins").start();
    }
}
