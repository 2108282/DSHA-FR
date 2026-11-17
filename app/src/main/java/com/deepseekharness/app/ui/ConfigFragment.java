package com.deepseekharness.app.ui;

import android.Manifest;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.deepseekharness.app.util.ToastHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.ui.contract.ConfigActions;
import com.deepseekharness.app.ui.contract.ConfigPresenter;
import com.deepseekharness.app.ui.contract.ConfigUiState;

/**
 * 详细配置二级页：1:1 像素级 Skia 现代卡片设计 (ModernCardView + DshaToggle)，纯渲染与契约驱动。
 */
public class ConfigFragment extends Fragment implements ConfigPresenter.ViewCallback {

    private ConfigPresenter presenter;
    private ConfigActions actions;

    private EditText tasksetInput;
    private Button tasksetSaveBtn;

    private DshaToggle confirmShellToggle;
    private DshaToggle idleFreezeToggle;
    private DshaToggle sensorsToggle;
    private DshaToggle locationToggle;
    private DshaToggle blockGuardActiveToggle;
    private DshaToggle blockGuardGlobalToggle;
    private TextView blockGuardStatusText;
    private View blockGuardDotView;

    private View translateBtn;
    private View allFilesBtn;
    private TextView allFilesStatusText;
    private TextView rootStatusView;
    private Button rootAuthBtn;
    private View a11yBtn;
    private TextView a11yStatusText;

    private View freezeDotView;
    private TextView freezeStatusText;
    private Button btnTestFreeze;
    private Button btnTestWake;
    private Button btnViewFreezeLog;
    private androidx.appcompat.app.AlertDialog freezeLogDialog;
    private Button btnFreezeWhitelist;
    private androidx.appcompat.app.AlertDialog freezeActionDialog;
    private View freezeLogRoot;
    private TextView freezeLogContent;
    private Button freezeLogDelete;
    private Button freezeLogLocation;

    private boolean isBinding = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        presenter = new ConfigPresenter(requireActivity(), this);
        actions = presenter;

        // 1. CPU 调度核心绑定（常驻展开 + 独立保存按钮）
        tasksetInput = view.findViewById(R.id.config_taskset);
        tasksetSaveBtn = view.findViewById(R.id.config_taskset_save);
        if (tasksetSaveBtn != null) {
            tasksetSaveBtn.setOnClickListener(v -> {
                if (actions != null && tasksetInput != null) {
                    actions.onSaveTaskset(tasksetInput.getText().toString());
                }
            });
        }

        // 2. 四大单独开关与整行联动 (DshaToggle 纯 Skia 自绘)
        confirmShellToggle = view.findViewById(R.id.config_toggle_confirm_shell);
        if (confirmShellToggle != null) {
            confirmShellToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                if (isBinding) return;
                actions.onToggleConfirmShell(isChecked);
            });
            View row = view.findViewById(R.id.config_row_confirm_shell);
            if (row != null) row.setOnClickListener(v -> confirmShellToggle.toggle());
        }

        idleFreezeToggle = view.findViewById(R.id.config_toggle_idle_freeze);
        if (idleFreezeToggle != null) {
            idleFreezeToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                if (isBinding) return;
                actions.onToggleIdleFreeze(isChecked);
            });
            View row = view.findViewById(R.id.config_row_idle_freeze);
            if (row != null) row.setOnClickListener(v -> idleFreezeToggle.toggle());
        }

        sensorsToggle = view.findViewById(R.id.config_toggle_sensors);
        if (sensorsToggle != null) {
            sensorsToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                if (isBinding) return;
                actions.onToggleSensors(isChecked);
            });
            View row = view.findViewById(R.id.config_row_sensors);
            if (row != null) row.setOnClickListener(v -> sensorsToggle.toggle());
        }

        locationToggle = view.findViewById(R.id.config_toggle_location);
        if (locationToggle != null) {
            locationToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                if (isBinding) return;
                actions.onToggleLocation(isChecked);
            });
            View row = view.findViewById(R.id.config_row_location);
            if (row != null) row.setOnClickListener(v -> locationToggle.toggle());
        }

        // 高危操作：物理块设备防砖隔离即时控制 (临时开关)
        blockGuardActiveToggle = view.findViewById(R.id.config_toggle_block_guard_active);
        if (blockGuardActiveToggle != null) {
            blockGuardActiveToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                if (isBinding) return;
                actions.onToggleBlockGuardActive(isChecked);
            });
            View row = view.findViewById(R.id.config_row_block_guard_active);
            if (row != null) row.setOnClickListener(v -> blockGuardActiveToggle.toggle());
        }

        // 高危操作：全局穿透隔离 (整机生效开关)
        blockGuardGlobalToggle = view.findViewById(R.id.config_toggle_block_guard_global);
        if (blockGuardGlobalToggle != null) {
            blockGuardGlobalToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                if (isBinding) return;
                actions.onToggleBlockGuardGlobal(isChecked);
            });
            View row = view.findViewById(R.id.config_row_block_guard_global);
            if (row != null) row.setOnClickListener(v -> blockGuardGlobalToggle.toggle());
        }

        blockGuardStatusText = view.findViewById(R.id.config_block_guard_status);
        blockGuardDotView = view.findViewById(R.id.config_block_guard_dot);

        // 3. 扩展功能与系统保活权限入口
        translateBtn = view.findViewById(R.id.config_translate);
        allFilesBtn = view.findViewById(R.id.config_all_files);
        allFilesStatusText = view.findViewById(R.id.config_all_files_status);
        rootStatusView = view.findViewById(R.id.config_root_status);
        rootAuthBtn = view.findViewById(R.id.config_root_auth);
        a11yBtn = view.findViewById(R.id.config_a11y);
        a11yStatusText = view.findViewById(R.id.config_a11y_status);

        freezeDotView = view.findViewById(R.id.config_freeze_dot);
        freezeStatusText = view.findViewById(R.id.config_freeze_status);
        btnTestFreeze = view.findViewById(R.id.config_btn_test_freeze);
        btnTestWake = view.findViewById(R.id.config_btn_test_wake);
        btnViewFreezeLog = view.findViewById(R.id.config_btn_view_freeze_log);
        btnFreezeWhitelist = view.findViewById(R.id.config_btn_freeze_whitelist);

        if (allFilesBtn != null) allFilesBtn.setOnClickListener(v -> actions.onOpenAllFilesSettings());
        if (rootAuthBtn != null) rootAuthBtn.setOnClickListener(v -> actions.onCheckRootClick());
        a11yBtn.setOnClickListener(v -> actions.onOpenA11ySettings());

        if (btnTestFreeze != null) btnTestFreeze.setOnClickListener(v -> actions.onTestFreeze());
        if (btnTestWake != null) btnTestWake.setOnClickListener(v -> actions.onTestWake());
        if (btnViewFreezeLog != null) btnViewFreezeLog.setOnClickListener(v -> actions.onViewFreezeLog());
        if (btnFreezeWhitelist != null) btnFreezeWhitelist.setOnClickListener(v -> actions.onOpenFreezeWhitelistLocation());

        translateBtn.setOnClickListener(v ->
                ToastHelper.makeText(requireContext(), "插件市场翻译组件已内置，后续版本开放自定义模型接口", Toast.LENGTH_SHORT).show());

        MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(requireContext());
        MonetEngine.applyToViewTree(view, palette);

        presenter.init();
    }

    @Override
    public void onResume() {
        super.onResume();
        syncActivityTitle(true);
        if (getView() != null) {
            MonetEngine.applyToViewTree(getView(), MonetEngine.resolveCurrentPalette(requireContext()));
        }
        if (freezeLogRoot != null) {
            MonetEngine.applyToViewTree(freezeLogRoot, MonetEngine.resolveCurrentPalette(requireContext()));
        }
        if (presenter != null) {
            presenter.refreshState();
        }
    }

    @Override
    public void onDestroyView() {
        syncActivityTitle(false);
        if (confirmShellToggle != null) confirmShellToggle.setOnCheckedChangeListener(null);
        if (idleFreezeToggle != null) idleFreezeToggle.setOnCheckedChangeListener(null);
        if (sensorsToggle != null) sensorsToggle.setOnCheckedChangeListener(null);
        if (locationToggle != null) locationToggle.setOnCheckedChangeListener(null);

        if (presenter != null) presenter.dispose();
        if (freezeLogDialog != null) freezeLogDialog.dismiss();
        freezeLogDialog = null;
        if (freezeActionDialog != null) freezeActionDialog.dismiss();
        freezeActionDialog = null;
        freezeLogRoot = null;
        freezeLogContent = null;
        freezeLogDelete = null;
        freezeLogLocation = null;
        btnFreezeWhitelist = null;
        presenter = null;
        actions = null;
        tasksetInput = null;
        tasksetSaveBtn = null;
        confirmShellToggle = null;
        idleFreezeToggle = null;
        sensorsToggle = null;
        locationToggle = null;
        translateBtn = null;
        allFilesBtn = null;
        allFilesStatusText = null;
        rootStatusView = null;
        rootAuthBtn = null;
        a11yBtn = null;
        a11yStatusText = null;
        freezeDotView = null;
        freezeStatusText = null;
        btnTestFreeze = null;
        btnTestWake = null;
        btnViewFreezeLog = null;
        super.onDestroyView();
    }

    private void syncActivityTitle(boolean isSubpage) {
        if (!isAdded()) return;
        TextView activityTitle = requireActivity().findViewById(R.id.app_title);
        if (activityTitle != null) {
            activityTitle.setText(isSubpage ? "详细配置" : getString(R.string.nav_settings));
        }
    }

    @Override
    public void onRender(ConfigUiState state) {
        if (!isAdded() || getView() == null) return;

        isBinding = true;
        if (tasksetInput != null && !tasksetInput.hasFocus()) tasksetInput.setText(state.taskset);

        if (confirmShellToggle != null && confirmShellToggle.isChecked() != state.isConfirmShell) {
            confirmShellToggle.setChecked(state.isConfirmShell, false, false);
        }
        if (idleFreezeToggle != null && idleFreezeToggle.isChecked() != state.isIdleFreeze) {
            idleFreezeToggle.setChecked(state.isIdleFreeze, false, false);
        }
        if (sensorsToggle != null && sensorsToggle.isChecked() != state.isCapSensors) {
            sensorsToggle.setChecked(state.isCapSensors, false, false);
        }
        if (locationToggle != null && locationToggle.isChecked() != state.isCapLocation) {
            locationToggle.setChecked(state.isCapLocation, false, false);
        }
        if (blockGuardActiveToggle != null && blockGuardActiveToggle.isChecked() != state.isBlockGuardActive) {
            blockGuardActiveToggle.setChecked(state.isBlockGuardActive, false, false);
        }
        if (blockGuardGlobalToggle != null && blockGuardGlobalToggle.isChecked() != state.isBlockGuardGlobal) {
            blockGuardGlobalToggle.setChecked(state.isBlockGuardGlobal, false, false);
        }
        isBinding = false;

        if (allFilesStatusText != null) allFilesStatusText.setText(state.allFilesStatusText);
        if (blockGuardStatusText != null && state.blockGuardStatusText != null) {
            blockGuardStatusText.setText(state.blockGuardStatusText);
        }
        if (blockGuardDotView != null) {
            blockGuardDotView.setBackgroundResource(state.isBlockGuardActive ? R.drawable.dot_active : R.drawable.dot_inactive);
        }
        if (a11yStatusText != null) a11yStatusText.setText(state.a11yStatusText);
        if (rootStatusView != null && state.rootStatusText != null) {
            rootStatusView.setText(state.rootStatusText);
        }

        if (freezeStatusText != null && state.freezeStatusText != null) {
            freezeStatusText.setText(state.freezeStatusText);
        }
        if (freezeDotView != null) {
            if (state.freezeStateCode == 1) {
                freezeDotView.setBackgroundResource(R.drawable.dot_warn);
            } else if (state.freezeStateCode == 0) {
                freezeDotView.setBackgroundResource(R.drawable.dot_active);
            } else {
                freezeDotView.setBackgroundResource(R.drawable.dot_inactive);
            }
        }
    }

    @Override
    public void onFreezeLogBusy(boolean busy) {
        if (btnViewFreezeLog != null) btnViewFreezeLog.setEnabled(!busy);
        if (freezeLogDelete != null) freezeLogDelete.setEnabled(!busy);
        if (freezeLogLocation != null) freezeLogLocation.setEnabled(!busy);
    }

    @Override
    public void onShowFreezeLog(String text) {
        if (!isAdded() || getView() == null) return;
        if (freezeLogDialog != null) freezeLogDialog.dismiss();
        View root = getLayoutInflater().inflate(R.layout.dialog_freeze_log, null);
        TextView content = root.findViewById(R.id.freeze_log_content);
        content.setText(text);
        Button delete = root.findViewById(R.id.freeze_log_delete);
        Button location = root.findViewById(R.id.freeze_log_location);
        Button close = root.findViewById(R.id.freeze_log_close);
        androidx.appcompat.app.AlertDialog dialog = new DshaDialogBuilder(requireContext()).setView(root).create();
        freezeLogDialog = dialog;
        freezeLogRoot = root;
        freezeLogContent = content;
        freezeLogDelete = delete;
        freezeLogLocation = location;
        delete.setOnClickListener(v -> confirmFreezeLogDeletion());
        location.setOnClickListener(v -> { if (actions != null) actions.onOpenFreezeLogLocation(); });
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (freezeLogDialog == dialog) {
                freezeLogDialog = null;
                freezeLogRoot = null;
                freezeLogContent = null;
                freezeLogDelete = null;
                freezeLogLocation = null;
            }
        });
        MonetEngine.applyToViewTree(root, MonetEngine.resolveCurrentPalette(requireContext()));
        dialog.show();
        if (dialog.getWindow() != null) {
            android.graphics.Rect bounds = requireActivity().getWindowManager().getCurrentWindowMetrics().getBounds();
            android.view.WindowInsets insets = requireActivity().getWindowManager().getCurrentWindowMetrics().getWindowInsets();
            android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars());
            int height = Math.round((bounds.height() - bars.top - bars.bottom) * .9f);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
        }
        android.widget.ScrollView scroll = root.findViewById(R.id.freeze_log_scroll);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void confirmFreezeLogDeletion() {
        if (!isAdded() || actions == null || freezeActionDialog != null) return;
        View root = getLayoutInflater().inflate(R.layout.dialog_confirm_action, null);
        ((TextView) root.findViewById(R.id.dialogActionTitle)).setText("删除冻结日志？");
        ((TextView) root.findViewById(R.id.dialogActionMessage)).setText(
                "现有日志记录将被删除，无法恢复。冻结守护继续运行，后续检查会重新生成日志。");
        Button confirm = root.findViewById(R.id.btnActionConfirm);
        confirm.setText("确认删除");
        androidx.appcompat.app.AlertDialog dialog = new DshaDialogBuilder(requireContext()).setView(root).create();
        freezeActionDialog = dialog;
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            if (actions != null) actions.onDeleteFreezeLog();
        });
        root.findViewById(R.id.btnActionCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> { if (freezeActionDialog == dialog) freezeActionDialog = null; });
        MonetEngine.applyToViewTree(root, MonetEngine.resolveCurrentPalette(requireContext()));
        dialog.show();
    }

    @Override
    public void onFreezeLogDeleted() {
        if (freezeLogContent != null) freezeLogContent.setText("日志已删除，后续检查会重新生成。");
    }

    @Override
    public void onFreezeWhitelistBusy(boolean busy) {
        if (btnFreezeWhitelist != null) btnFreezeWhitelist.setEnabled(!busy);
    }

    @Override
    public void onOpenFreezeLocation(String path) {
        if (!isAdded() || getView() == null) return;
        if (com.deepseekharness.app.util.RootFileLocation.open(requireContext(), path)) return;
        // Root路径交给具备Root权限的文件管理器；系统文件选择器不保证能浏览该目录。
        View root = getLayoutInflater().inflate(R.layout.dialog_confirm_action, null);
        ((TextView) root.findViewById(R.id.dialogActionTitle)).setText("打开文件位置");
        ((TextView) root.findViewById(R.id.dialogActionMessage)).setText(
                "未找到可打开此位置的文件管理器。请使用已授权 Root 的文件管理器进入：\n"
                + path + (com.deepseekharness.app.core.FreezeWhitelistRepository.PATH.equals(path)
                ? "\n\n白名单规则示例和教程保存在同目录《冻结白名单教程.md》。" : ""));
        Button copy = root.findViewById(R.id.btnActionConfirm);
        copy.setText("复制路径");
        ((Button) root.findViewById(R.id.btnActionCancel)).setText("关闭");
        androidx.appcompat.app.AlertDialog dialog = new DshaDialogBuilder(requireContext()).setView(root).create();
        if (freezeActionDialog != null) freezeActionDialog.dismiss();
        freezeActionDialog = dialog;
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("文件路径", path));
            ToastHelper.show(requireContext(), "路径已复制");
            dialog.dismiss();
        });
        root.findViewById(R.id.btnActionCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> { if (freezeActionDialog == dialog) freezeActionDialog = null; });
        MonetEngine.applyToViewTree(root, MonetEngine.resolveCurrentPalette(requireContext()));
        dialog.show();
    }

    @Override
    public void onGoBack() {
        getParentFragmentManager().popBackStack();
    }

    @Override
    public void onRequestLocationPermission() {
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, 104);
    }
}
