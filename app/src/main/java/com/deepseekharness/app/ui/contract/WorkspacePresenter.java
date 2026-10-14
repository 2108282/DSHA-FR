package com.deepseekharness.app.ui.contract;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.WorkspaceModel;
import com.deepseekharness.app.core.WorkspaceRepository;
import com.deepseekharness.app.ui.DshaDialogBuilder;
import com.deepseekharness.app.ui.MonetEngine;
import com.deepseekharness.app.util.BackupScope;
import com.deepseekharness.app.util.ToastHelper;

import java.util.List;

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
        String currentWd = controller.config().getWorkdir();
        // 初始先行渲染，防止 UI 闪烁
        callback.onRender(new WorkspaceUiState(currentWd, "", null, true));

        // 异步加载完整工作区列表
        new Thread(() -> {
            List<WorkspaceModel> list = WorkspaceRepository.loadWorkspaces(controller);
            mainHandler.post(() -> {
                WorkspaceUiState state = new WorkspaceUiState(controller.config().getWorkdir(), "", list, false);
                callback.onRender(state);
            });
        }, "dsha-load-workspaces").start();
    }

    @Override
    public void onRefresh() {
        init();
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
            ToastHelper.makeText(context, "工作区路径已更新: " + p, Toast.LENGTH_SHORT).show();
            init();
        }
    }

    @Override
    public void onSwitchWorkspace(WorkspaceModel model) {
        if (model == null) return;
        new Thread(() -> {
            boolean ok = WorkspaceRepository.switchWorkspace(controller, model.id, model.path);
            mainHandler.post(() -> {
                if (ok) {
                    ToastHelper.makeText(context, "已切换至工作区: " + model.title, Toast.LENGTH_SHORT).show();
                }
                init();
            });
        }, "dsha-switch-ws").start();
    }

    @Override
    public void onCreateWorkspaceClick() {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_create_workspace, null);
        AlertDialog dialog = new DshaDialogBuilder(activity).setView(dialogView).create();

        EditText etTitle = dialogView.findViewById(R.id.dialogWsTitleInput);
        RadioGroup typeGroup = dialogView.findViewById(R.id.dialogWsTypeGroup);
        RadioButton radioNative = dialogView.findViewById(R.id.radioWsNativeLinux);
        TextView tvPreview = dialogView.findViewById(R.id.dialogWsPathPreview);
        Button btnCancel = dialogView.findViewById(R.id.btnWsCancel);
        Button btnConfirm = dialogView.findViewById(R.id.btnWsConfirm);

        Runnable updatePreview = () -> {
            if (tvPreview == null || etTitle == null) return;
            String name = etTitle.getText().toString().trim();
            if (name.isEmpty()) name = "project-demo";
            boolean isNative = radioNative != null && radioNative.isChecked();
            if (isNative) {
                tvPreview.setText("创建路径：/root/workspaces/" + name + " (支持软链接)");
            } else {
                tvPreview.setText("创建路径：/sdcard/Download/DSHA/" + name + " (手机直通)");
            }
        };

        if (etTitle != null) {
            etTitle.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updatePreview.run(); }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        if (typeGroup != null) {
            typeGroup.setOnCheckedChangeListener((group, checkedId) -> updatePreview.run());
        }
        updatePreview.run();

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                String name = etTitle != null ? etTitle.getText().toString().trim() : "";
                if (name.isEmpty()) {
                    ToastHelper.makeText(context, "请输入工作区名称", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean isNative = radioNative != null && radioNative.isChecked();
                dialog.dismiss();
                onCreateWorkspaceConfirm(name, isNative);
            });
        }

        MonetEngine.applyToViewTree(dialogView, MonetEngine.resolveCurrentPalette(activity));
        dialog.show();
    }

    @Override
    public void onCreateWorkspaceConfirm(String title, boolean isNativeLinux) {
        AlertDialog loading = showLoadingDialog("创建工作区", "正在初始化工作区目录与注册表…");
        new Thread(() -> {
            try {
                WorkspaceModel created = WorkspaceRepository.createWorkspace(controller, title, isNativeLinux);
                mainHandler.post(() -> {
                    loading.dismiss();
                    ToastHelper.makeText(context, "工作区「" + created.title + "」已创建并激活", Toast.LENGTH_SHORT).show();
                    init();
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    loading.dismiss();
                    showActionConfirmDialog(
                            R.drawable.ic_settings_database,
                            "创建失败",
                            t.getMessage() != null ? t.getMessage() : t.toString(),
                            null,
                            "确定",
                            null,
                            null
                    );
                });
            }
        }, "dsha-create-ws").start();
    }

    @Override
    public void onDeleteWorkspace(WorkspaceModel model) {
        if (model == null) return;
        String msg = "确定要从 DSHA 注册表中注销工作区「" + model.title + "」吗？\n\n"
                + "• 路径: " + model.path + "\n"
                + "• 关联会话: " + model.sessionCount + " 个\n\n"
                + "提示：操作仅解除注册关联，不会删除您磁盘中的代码与文件。";

        showActionConfirmDialog(
                R.drawable.ic_box,
                "注销工作区",
                msg,
                "取消",
                "确认注销",
                null,
                () -> new Thread(() -> {
                    boolean ok = WorkspaceRepository.deleteWorkspace(controller, model.id);
                    mainHandler.post(() -> {
                        if (ok) {
                            ToastHelper.makeText(context, "工作区已注销", Toast.LENGTH_SHORT).show();
                        }
                        init();
                    });
                }, "dsha-delete-ws").start()
        );
    }

    @Override
    public void onRenameWorkspace(WorkspaceModel model, String newTitle) {
        if (model == null || newTitle == null || newTitle.trim().isEmpty()) return;
        new Thread(() -> {
            boolean ok = WorkspaceRepository.renameWorkspace(controller, model.id, newTitle.trim());
            mainHandler.post(() -> {
                if (ok) {
                    ToastHelper.makeText(context, "已重命名为: " + newTitle.trim(), Toast.LENGTH_SHORT).show();
                }
                init();
            });
        }, "dsha-rename-ws").start();
    }

    @Override
    public void onBackupClick() {
        try {
            showBackupScopeDialog();
        } catch (Throwable t) {
            ToastHelper.makeText(context, "打开备份选项失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showBackupScopeDialog() {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_backup_scope, null);
        AlertDialog dialog = new DshaDialogBuilder(activity).setView(dialogView).create();

        RadioGroup scopeGroup = dialogView.findViewById(R.id.dialogBackupScopeGroup);
        TextView tvPathHint = dialogView.findViewById(R.id.dialogBackupPathHint);
        CheckBox cbApiKey = dialogView.findViewById(R.id.dialogBackupCbApiKey);
        CheckBox cbSshKey = dialogView.findViewById(R.id.dialogBackupCbSshKey);
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
                boolean includeSshKey = cbSshKey != null && cbSshKey.isChecked();
                doBackup(currentScope[0], includeApiKey, includeSshKey);
            });
        }

        MonetEngine.applyToViewTree(dialogView, MonetEngine.resolveCurrentPalette(activity));
        dialog.show();
    }

    private void doBackup(final int scope, final boolean includeApiKey, final boolean includeSshKey) {
        ToastHelper.makeText(context, "开始备份…", Toast.LENGTH_SHORT).show();
        AlertDialog progress = showLoadingDialog("备份中", "正在打包所选数据并校验…");

        new Thread(() -> {
            String path = BackupManager.backupToExternal(context, controller, scope, includeApiKey, includeSshKey);
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
                            + (includeSshKey ? "（已包含 SSH 密钥配置）" : "")
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
                + "• 智能自愈：多工作区路径自愈重映射，会话历史完整保留\n"
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
