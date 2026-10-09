package com.deepseekharness.app.ui.contract;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import com.deepseekharness.app.util.ToastHelper;

import androidx.appcompat.app.AlertDialog;

import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.DshaDialogBuilder;
import com.deepseekharness.app.ui.MonetEngine;
import com.deepseekharness.app.util.BackupScope;

public class WorkspacePresenter implements WorkspaceActions {

    public interface ViewCallback {
        void onRender(WorkspaceUiState state);
        void onGoBack();
        void onLaunchRestorePicker();
    }

    private final Activity activity;
    private final Context context;
    private final ViewCallback callback;
    private final HarnessController controller;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public WorkspacePresenter(Activity activity, ViewCallback callback) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.callback = callback;
        this.controller = new HarnessController(context);
    }

    public void init() {
        WorkspaceUiState state = new WorkspaceUiState(controller.config().getWorkdir(), "");
        callback.onRender(state);
    }

    @Override
    public void onBackClick() {
        callback.onGoBack();
    }

    @Override
    public void onApplyWorkdir(String path) {
        String p = path != null ? path.trim() : "";
        if (!p.isEmpty()) {
            controller.config().setWorkdir(p);
            ToastHelper.makeText(context, "工作区目录已更新：" + p, Toast.LENGTH_SHORT).show();
            init();
        }
    }

    @Override
    public void onBackupClick() {
        try {
            showBackupScopeDialog();
        } catch (Throwable t) {
            ToastHelper.makeText(context, "打开备份选项失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 1:1 对齐插件安装弹窗规范：基于 ModernCardView 的纯 Skia 风格备份范围与安全选项弹窗
     */
    private void showBackupScopeDialog() {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_backup_scope, null);
        AlertDialog dialog = new DshaDialogBuilder(activity).setView(dialogView).create();

        RadioGroup scopeGroup = dialogView.findViewById(R.id.dialogBackupScopeGroup);
        RadioButton radioTools = dialogView.findViewById(R.id.radioScopeTools);
        RadioButton radioFull = dialogView.findViewById(R.id.radioScopeFull);
        RadioButton radioSessions = dialogView.findViewById(R.id.radioScopeSessions);
        RadioButton radioSettings = dialogView.findViewById(R.id.radioScopeSettings);
        RadioButton radioPlugins = dialogView.findViewById(R.id.radioScopePlugins);
        TextView tvPathHint = dialogView.findViewById(R.id.dialogBackupPathHint);
        CheckBox cbApiKey = dialogView.findViewById(R.id.dialogBackupCbApiKey);
        Button btnCancel = dialogView.findViewById(R.id.btnBackupCancel);
        Button btnConfirm = dialogView.findViewById(R.id.btnBackupConfirm);

        final int[] currentScope = {BackupScope.TOOLS};

        Runnable updatePathHint = () -> {
            if (tvPathHint != null) {
                String prefix = BackupScope.fileNamePrefix(currentScope[0]);
                tvPathHint.setText("保存位置：Download/DSHA/" + prefix + "latest.tar.gz");
            }
        };
        updatePathHint.run();

        if (scopeGroup != null) {
            scopeGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.radioScopeTools) {
                    currentScope[0] = BackupScope.TOOLS;
                } else if (checkedId == R.id.radioScopeFull) {
                    currentScope[0] = BackupScope.FULL;
                } else if (checkedId == R.id.radioScopeSessions) {
                    currentScope[0] = BackupScope.SESSIONS;
                } else if (checkedId == R.id.radioScopeSettings) {
                    currentScope[0] = BackupScope.SETTINGS;
                } else if (checkedId == R.id.radioScopePlugins) {
                    currentScope[0] = BackupScope.PLUGINS;
                } else {
                    currentScope[0] = BackupScope.TOOLS;
                }
                updatePathHint.run();
            });
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                dialog.dismiss();
                boolean includeApiKey = cbApiKey != null && cbApiKey.isChecked();
                doBackup(currentScope[0], includeApiKey);
            });
        }

        MonetEngine.applyToViewTree(dialogView, MonetEngine.resolveCurrentPalette(activity));
        dialog.show();
    }

    private void doBackup(final int scope, final boolean includeApiKey) {
        ToastHelper.makeText(context, "开始备份…", Toast.LENGTH_SHORT).show();
        AlertDialog progress = showLoadingDialog("备份中", "正在打包所选数据并校验…");

        new Thread(() -> {
            String path = BackupManager.backupToExternal(context, controller, scope, includeApiKey);
            mainHandler.post(() -> {
                progress.dismiss();
                if (path == null) {
                    showActionConfirmDialog(
                            R.drawable.ic_settings_database,
                            "备份失败",
                            BackupManager.lastError() != null ? BackupManager.lastError() : "未知错误，请检查存储权限",
                            null,
                            "关闭",
                            null,
                            null
                    );
                } else {
                    String msg = "已备份 " + BackupScope.label(scope)
                            + (includeApiKey ? "（已包含 API Key）" : "（未包含 API Key）")
                            + "\n\n保存位置：\n" + path
                            + "\n\n归档已通过条目数与大小完整性校验。";
                    showActionConfirmDialog(
                            R.drawable.ic_check_circle,
                            "备份成功（已校验）",
                            msg,
                            null,
                            "完成",
                            null,
                            null
                    );
                }
            });
        }, "dsha-backup").start();
    }

    @Override
    public void onRestoreClick() {
        try {
            confirmRestore();
        } catch (Throwable t) {
            ToastHelper.makeText(context, "打开恢复选项失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmRestore() {
        String msg = "请从存储中选择要恢复的备份文件（Download/DSHA/ 下的 .tar.gz）。\n\n"
                + "• 自动保护：恢复前会自动将现有配置安全重命名保留\n"
                + "• 覆盖生效：将解压并合并覆盖配置与历史会话\n"
                + "• 隔离安全：仅作用于 DSHA 原生容器运行环境";

        showActionConfirmDialog(
                R.drawable.ic_sheet_restore,
                "确认恢复数据？",
                msg,
                "取消",
                "选择文件",
                null,
                () -> callback.onLaunchRestorePicker()
        );
    }

    @Override
    public void onRestoreSelected(Uri uri) {
        if (uri == null) return;
        ToastHelper.makeText(context, "开始恢复…", Toast.LENGTH_SHORT).show();
        AlertDialog progress = showLoadingDialog("恢复中", "正在解压覆盖并合并数据…");

        new Thread(() -> {
            try {
                controller.stopWeb();
                String report = BackupManager.restoreFromBackup(context, controller, uri);
                mainHandler.post(() -> {
                    progress.dismiss();
                    init();
                    showActionConfirmDialog(
                            R.drawable.ic_check_circle,
                            "恢复完成（已校验）",
                            report + "\n\n建议立即重启服务以加载恢复的数据。",
                            "稍后手动启动",
                            "立即重启",
                            null,
                            () -> {
                                controller.stopWeb();
                                controller.startWeb(status -> {});
                                ToastHelper.makeText(context, "正在重启服务…", Toast.LENGTH_SHORT).show();
                            }
                    );
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    progress.dismiss();
                    showActionConfirmDialog(
                            R.drawable.ic_settings_database,
                            "恢复失败",
                            t.getMessage() != null ? t.getMessage() : t.toString(),
                            null,
                            "关闭",
                            null,
                            null
                    );
                });
            }
        }, "dsha-restore").start();
    }

    private AlertDialog showLoadingDialog(String title, String message) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_loading_action, null);
        TextView tvTitle = view.findViewById(R.id.dialogLoadingTitle);
        TextView tvMsg = view.findViewById(R.id.dialogLoadingMessage);
        if (tvTitle != null && title != null) tvTitle.setText(title);
        if (tvMsg != null && message != null) tvMsg.setText(message);

        AlertDialog dialog = new DshaDialogBuilder(activity)
                .setView(view)
                .setCancelable(false)
                .create();
        MonetEngine.applyToViewTree(view, MonetEngine.resolveCurrentPalette(activity));
        dialog.show();
        return dialog;
    }

    private void showActionConfirmDialog(int iconRes, String title, String message,
                                         String cancelText, String confirmText,
                                         Runnable onCancel, Runnable onConfirm) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_confirm_action, null);
        AlertDialog dialog = new DshaDialogBuilder(activity).setView(dialogView).create();

        ImageView imgIcon = dialogView.findViewById(R.id.dialogActionIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogActionTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogActionMessage);
        Button btnCancel = dialogView.findViewById(R.id.btnActionCancel);
        Button btnConfirm = dialogView.findViewById(R.id.btnActionConfirm);

        if (imgIcon != null) imgIcon.setImageResource(iconRes);
        if (tvTitle != null) tvTitle.setText(title);
        if (tvMessage != null) tvMessage.setText(message);

        if (cancelText == null && btnCancel != null) {
            btnCancel.setVisibility(View.GONE);
        } else if (btnCancel != null) {
            btnCancel.setVisibility(View.VISIBLE);
            btnCancel.setText(cancelText);
            btnCancel.setOnClickListener(v -> {
                dialog.dismiss();
                if (onCancel != null) onCancel.run();
            });
        }

        if (btnConfirm != null) {
            if (confirmText != null) btnConfirm.setText(confirmText);
            btnConfirm.setOnClickListener(v -> {
                dialog.dismiss();
                if (onConfirm != null) onConfirm.run();
            });
        }

        MonetEngine.applyToViewTree(dialogView, MonetEngine.resolveCurrentPalette(activity));
        dialog.show();
    }
}
