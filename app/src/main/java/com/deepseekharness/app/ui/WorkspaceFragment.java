package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.ui.contract.WorkspaceActions;
import com.deepseekharness.app.ui.contract.WorkspacePresenter;
import com.deepseekharness.app.ui.contract.WorkspaceUiState;

/**
 * 数据与备份二级页：1:1 像素级 Skia 现代卡片设计 (ModernCardView)，纯渲染与契约驱动。
 */
public class WorkspaceFragment extends Fragment implements WorkspacePresenter.ViewCallback {

    private WorkspacePresenter presenter;
    private WorkspaceActions actions;

    private EditText wsPathInput;
    private TextView rootStatusView;

    private final ActivityResultLauncher<String[]> restorePicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null && actions != null) {
                            actions.onRestoreSelected(uri);
                        }
                    });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_workspace, container, false);

        presenter = new WorkspacePresenter(requireActivity(), this);
        actions = presenter;

        wsPathInput = v.findViewById(R.id.workspace_path);
        rootStatusView = v.findViewById(R.id.workspace_shizuku_status);

        View subBack = v.findViewById(R.id.sub_back);
        if (subBack != null) {
            subBack.setOnClickListener(x -> actions.onBackClick());
        }
        v.findViewById(R.id.workspace_backup).setOnClickListener(x -> actions.onBackupClick());
        v.findViewById(R.id.workspace_restore).setOnClickListener(x -> actions.onRestoreClick());
        v.findViewById(R.id.workspace_location).setOnClickListener(x ->
                Toast.makeText(requireContext(), "备份保存在 Download/DSHA/", Toast.LENGTH_LONG).show());

        if (wsPathInput != null) {
            v.findViewById(R.id.workspace_apply).setOnClickListener(x ->
                    actions.onApplyWorkdir(wsPathInput.getText().toString()));
        }

        TextView shareStatus = v.findViewById(R.id.workspace_share_status);
        if (shareStatus != null) {
            shareStatus.setText("KernelSU / Magisk 原生环境已打通直连：\n\n"
                    + "· 原生根目录：/data/adb/dsha/rootfs\n"
                    + "· 内部存储直通：/root/内部存储 → /sdcard/Download/DSHA\n"
                    + "· 默认工作区：/root/内部存储/工作区（手机物理 Download/DSHA/工作区）\n"
                    + "· 配置文件目录：/data/adb/dsha/rootfs/root/.dsh\n\n"
                    + "支持在 MT 管理器、Termux 或手机系统文件管理器中直接访问与读写！");
        }

        v.findViewById(R.id.workspace_shizuku_auth).setOnClickListener(x -> actions.onCheckRootClick());

        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (presenter != null) {
            presenter.init();
        }
    }

    @Override
    public void onDestroyView() {
        presenter = null;
        actions = null;
        wsPathInput = null;
        rootStatusView = null;
        super.onDestroyView();
    }

    @Override
    public void onRender(WorkspaceUiState state) {
        if (!isAdded()) return;
        if (wsPathInput != null && state.workdirPath != null) {
            wsPathInput.setText(state.workdirPath);
        }
        if (rootStatusView != null && state.rootStatusText != null) {
            rootStatusView.setText(state.rootStatusText);
        }
    }

    @Override
    public void onLaunchRestorePicker() {
        try {
            restorePicker.launch(new String[]{"*/*"});
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "打开选择器失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onGoBack() {
        getParentFragmentManager().popBackStack();
    }
}
