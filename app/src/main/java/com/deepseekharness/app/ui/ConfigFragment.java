package com.deepseekharness.app.ui;

import android.Manifest;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
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
 * 详细配置二级页：1:1 像素级 Skia 现代卡片设计 (ModernCardView)，纯渲染与契约驱动。
 */
public class ConfigFragment extends Fragment implements ConfigPresenter.ViewCallback {

    private ConfigPresenter presenter;
    private ConfigActions actions;

    private View subBack;
    private View workspaceEntry;
    private View advHeader;
    private View advBody;
    private ImageView advArrow;
    private EditText portInput;
    private EditText tasksetInput;
    private CheckBox confirmShellCheck;
    private CheckBox lanModeCheck;
    private CheckBox overlayStreamCheck;
    private View overlayStyleBtn;
    private View translateBtn;
    private CheckBox sensorsCheck;
    private CheckBox locationCheck;
    private View allFilesBtn;
    private TextView allFilesStatusText;
    private Button saveBtn;
    private View batteryOptBtn;
    private View a11yBtn;
    private TextView a11yStatusText;
    private TextView asrStatusText;
    private CheckBox asrContinuousCheck;
    private View asrCheckBtn;
    private View asrFixBtn;

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

        subBack = view.findViewById(R.id.sub_back);
        workspaceEntry = view.findViewById(R.id.config_workspace_entry);
        advHeader = view.findViewById(R.id.config_adv_header);
        advBody = view.findViewById(R.id.config_adv_body);
        advArrow = view.findViewById(R.id.config_adv_arrow);
        portInput = view.findViewById(R.id.config_port);
        tasksetInput = view.findViewById(R.id.config_taskset);
        confirmShellCheck = view.findViewById(R.id.config_confirm_shell);
        lanModeCheck = view.findViewById(R.id.config_lan_mode);
        overlayStreamCheck = view.findViewById(R.id.config_overlay_stream);
        overlayStyleBtn = view.findViewById(R.id.config_overlay_style);
        translateBtn = view.findViewById(R.id.config_translate);
        sensorsCheck = view.findViewById(R.id.config_cap_sensors);
        locationCheck = view.findViewById(R.id.config_cap_location);
        allFilesBtn = view.findViewById(R.id.config_all_files);
        allFilesStatusText = view.findViewById(R.id.config_all_files_status);
        saveBtn = view.findViewById(R.id.config_save);
        batteryOptBtn = view.findViewById(R.id.config_battery_opt);
        a11yBtn = view.findViewById(R.id.config_a11y);
        a11yStatusText = view.findViewById(R.id.config_a11y_status);
        asrStatusText = view.findViewById(R.id.config_asr_status);
        asrContinuousCheck = view.findViewById(R.id.config_asr_continuous);
        asrCheckBtn = view.findViewById(R.id.config_asr_btn_check);
        asrFixBtn = view.findViewById(R.id.config_asr_btn_fix);

        advHeader.setOnClickListener(v -> {
            boolean isVisible = advBody.getVisibility() == View.VISIBLE;
            advBody.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            if (advArrow != null) {
                advArrow.setRotation(isVisible ? 0f : 90f);
            }
        });

        if (subBack != null) {
            subBack.setOnClickListener(v -> actions.onBack());
        }
        workspaceEntry.setOnClickListener(v -> actions.onOpenWorkspace());
        overlayStyleBtn.setOnClickListener(v -> actions.onOpenOverlayStyle());
        allFilesBtn.setOnClickListener(v -> actions.onOpenAllFilesSettings());
        batteryOptBtn.setOnClickListener(v -> actions.onOpenBatteryOptimization());
        a11yBtn.setOnClickListener(v -> actions.onOpenA11ySettings());
        asrCheckBtn.setOnClickListener(v -> actions.onCheckAsrStatus());
        asrFixBtn.setOnClickListener(v -> actions.onFixAsrConfig());

        translateBtn.setOnClickListener(v ->
                Toast.makeText(requireContext(), "插件市场翻译组件已内置，后续版本开放自定义模型接口", Toast.LENGTH_SHORT).show());

        asrContinuousCheck.setOnCheckedChangeListener((btn, checked) -> actions.onToggleAsrContinuous(checked));

        saveBtn.setOnClickListener(v -> actions.onSaveConfig(
                portInput.getText().toString(),
                tasksetInput.getText().toString(),
                confirmShellCheck.isChecked(),
                lanModeCheck.isChecked(),
                overlayStreamCheck.isChecked(),
                sensorsCheck.isChecked(),
                locationCheck.isChecked()
        ));

        presenter.init();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (presenter != null) {
            presenter.refreshState();
        }
    }

    @Override
    public void onDestroyView() {
        presenter = null;
        actions = null;
        subBack = null;
        workspaceEntry = null;
        advHeader = null;
        advBody = null;
        advArrow = null;
        portInput = null;
        tasksetInput = null;
        confirmShellCheck = null;
        lanModeCheck = null;
        overlayStreamCheck = null;
        overlayStyleBtn = null;
        translateBtn = null;
        sensorsCheck = null;
        locationCheck = null;
        allFilesBtn = null;
        allFilesStatusText = null;
        saveBtn = null;
        batteryOptBtn = null;
        a11yBtn = null;
        a11yStatusText = null;
        asrStatusText = null;
        asrContinuousCheck = null;
        asrCheckBtn = null;
        asrFixBtn = null;
        super.onDestroyView();
    }

    @Override
    public void onRender(ConfigUiState state) {
        if (!isAdded() || getView() == null) return;

        if (portInput != null) portInput.setText(state.port);
        if (tasksetInput != null) tasksetInput.setText(state.taskset);
        if (confirmShellCheck != null) confirmShellCheck.setChecked(state.isConfirmShell);
        if (lanModeCheck != null) lanModeCheck.setChecked(state.isLanMode);
        if (overlayStreamCheck != null) overlayStreamCheck.setChecked(state.isOverlayStream);
        if (sensorsCheck != null) sensorsCheck.setChecked(state.isCapSensors);
        if (locationCheck != null) locationCheck.setChecked(state.isCapLocation);

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
    }

    @Override
    public void onOpenWorkspace() {
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new WorkspaceFragment())
                .addToBackStack("workspace")
                .commit();
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
