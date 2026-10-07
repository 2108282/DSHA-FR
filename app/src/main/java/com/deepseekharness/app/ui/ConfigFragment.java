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
    private DshaToggle overlayStreamToggle;
    private DshaToggle sensorsToggle;
    private DshaToggle locationToggle;

    private View overlayStyleBtn;
    private View translateBtn;
    private View allFilesBtn;
    private TextView allFilesStatusText;
    private TextView rootStatusView;
    private Button rootAuthBtn;
    private View batteryOptBtn;
    private View a11yBtn;
    private TextView a11yStatusText;
    private TextView asrStatusText;
    private CheckBox asrContinuousCheck;
    private View asrCheckBtn;
    private View asrFixBtn;

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

        overlayStreamToggle = view.findViewById(R.id.config_toggle_overlay_stream);
        if (overlayStreamToggle != null) {
            overlayStreamToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                if (isBinding) return;
                actions.onToggleOverlayStream(isChecked);
            });
            View row = view.findViewById(R.id.config_row_overlay_stream);
            if (row != null) row.setOnClickListener(v -> overlayStreamToggle.toggle());
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

        // 3. 扩展功能与系统保活权限入口
        overlayStyleBtn = view.findViewById(R.id.config_overlay_style);
        translateBtn = view.findViewById(R.id.config_translate);
        allFilesBtn = view.findViewById(R.id.config_all_files);
        allFilesStatusText = view.findViewById(R.id.config_all_files_status);
        rootStatusView = view.findViewById(R.id.config_root_status);
        rootAuthBtn = view.findViewById(R.id.config_root_auth);
        batteryOptBtn = view.findViewById(R.id.config_battery_opt);
        a11yBtn = view.findViewById(R.id.config_a11y);
        a11yStatusText = view.findViewById(R.id.config_a11y_status);
        asrStatusText = view.findViewById(R.id.config_asr_status);
        asrContinuousCheck = view.findViewById(R.id.config_asr_continuous);
        asrCheckBtn = view.findViewById(R.id.config_asr_btn_check);
        asrFixBtn = view.findViewById(R.id.config_asr_btn_fix);

        overlayStyleBtn.setOnClickListener(v -> actions.onOpenOverlayStyle());
        if (allFilesBtn != null) allFilesBtn.setOnClickListener(v -> actions.onOpenAllFilesSettings());
        if (rootAuthBtn != null) rootAuthBtn.setOnClickListener(v -> actions.onCheckRootClick());
        batteryOptBtn.setOnClickListener(v -> actions.onOpenBatteryOptimization());
        a11yBtn.setOnClickListener(v -> actions.onOpenA11ySettings());
        asrCheckBtn.setOnClickListener(v -> actions.onCheckAsrStatus());
        asrFixBtn.setOnClickListener(v -> actions.onFixAsrConfig());

        translateBtn.setOnClickListener(v ->
                Toast.makeText(requireContext(), "插件市场翻译组件已内置，后续版本开放自定义模型接口", Toast.LENGTH_SHORT).show());

        asrContinuousCheck.setOnCheckedChangeListener((btn, checked) -> actions.onToggleAsrContinuous(checked));

        presenter.init();
    }

    @Override
    public void onResume() {
        super.onResume();
        syncActivityTitle(true);
        if (presenter != null) {
            presenter.refreshState();
        }
    }

    @Override
    public void onDestroyView() {
        syncActivityTitle(false);
        if (confirmShellToggle != null) confirmShellToggle.setOnCheckedChangeListener(null);
        if (overlayStreamToggle != null) overlayStreamToggle.setOnCheckedChangeListener(null);
        if (sensorsToggle != null) sensorsToggle.setOnCheckedChangeListener(null);
        if (locationToggle != null) locationToggle.setOnCheckedChangeListener(null);

        presenter = null;
        actions = null;
        tasksetInput = null;
        tasksetSaveBtn = null;
        confirmShellToggle = null;
        overlayStreamToggle = null;
        sensorsToggle = null;
        locationToggle = null;
        overlayStyleBtn = null;
        translateBtn = null;
        allFilesBtn = null;
        allFilesStatusText = null;
        rootStatusView = null;
        rootAuthBtn = null;
        batteryOptBtn = null;
        a11yBtn = null;
        a11yStatusText = null;
        asrStatusText = null;
        asrContinuousCheck = null;
        asrCheckBtn = null;
        asrFixBtn = null;
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
        if (overlayStreamToggle != null && overlayStreamToggle.isChecked() != state.isOverlayStream) {
            overlayStreamToggle.setChecked(state.isOverlayStream, false, false);
        }
        if (sensorsToggle != null && sensorsToggle.isChecked() != state.isCapSensors) {
            sensorsToggle.setChecked(state.isCapSensors, false, false);
        }
        if (locationToggle != null && locationToggle.isChecked() != state.isCapLocation) {
            locationToggle.setChecked(state.isCapLocation, false, false);
        }
        isBinding = false;

        if (asrContinuousCheck != null) {
            asrContinuousCheck.setOnCheckedChangeListener(null);
            asrContinuousCheck.setChecked(state.isAsrContinuous);
            asrContinuousCheck.setOnCheckedChangeListener((b, c) -> {
                if (actions != null) actions.onToggleAsrContinuous(c);
            });
        }

        if (allFilesStatusText != null) allFilesStatusText.setText(state.allFilesStatusText);
        if (a11yStatusText != null) a11yStatusText.setText(state.a11yStatusText);
        if (asrStatusText != null) asrStatusText.setText(state.asrStatusText);
        if (rootStatusView != null && state.rootStatusText != null) {
            rootStatusView.setText(state.rootStatusText);
        }
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
