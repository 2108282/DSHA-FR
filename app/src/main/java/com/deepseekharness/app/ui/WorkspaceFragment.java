package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.WorkspaceModel;
import com.deepseekharness.app.ui.contract.WorkspaceActions;
import com.deepseekharness.app.ui.contract.WorkspacePresenter;
import com.deepseekharness.app.ui.contract.WorkspaceUiState;
import com.deepseekharness.app.util.ToastHelper;

import java.util.List;

/**
 * 工作区管理与数据备份二级页：
 * - 纯 Skia 现代卡片设计 (ModernCardView)
 * - 支持原生 Linux 工作区 (支持软链接 symlink) 与手机共享存储双轨管理
 * - 契约驱动，多工作区自动识别与动态渲染，接入莫奈动态取色，全流程无 emoji。
 */
public class WorkspaceFragment extends Fragment implements WorkspacePresenter.ViewCallback {

    private WorkspacePresenter presenter;
    private WorkspaceActions actions;

    private EditText wsPathInput;
    private LinearLayout wsListContainer;

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
        wsListContainer = v.findViewById(R.id.workspace_list_container);

        v.findViewById(R.id.workspace_backup).setOnClickListener(x -> actions.onBackupClick());
        v.findViewById(R.id.workspace_restore).setOnClickListener(x -> actions.onRestoreClick());
        v.findViewById(R.id.workspace_location).setOnClickListener(x ->
                ToastHelper.makeText(requireContext(), "备份保存在 Download/DSHA/", Toast.LENGTH_LONG).show());

        View btnCreate = v.findViewById(R.id.btn_create_workspace);
        if (btnCreate != null) {
            btnCreate.setOnClickListener(x -> actions.onCreateWorkspaceClick());
        }

        if (wsPathInput != null) {
            v.findViewById(R.id.workspace_apply).setOnClickListener(x ->
                    actions.onApplyWorkdir(wsPathInput.getText().toString()));
        }

        TextView shareStatus = v.findViewById(R.id.workspace_share_status);
        if (shareStatus != null) {
            shareStatus.setText("KernelSU / Magisk 原生环境存储架构说明：\n\n"
                    + "· 原生开发工作区：/root/workspaces（ext4 格式，完整支持软链接 symlink 与 pnpm）\n"
                    + "· 手机共享工作区：/sdcard/Download/DSHA（直通手机内部存储，便于系统文件管理）\n"
                    + "· 容器系统根目录：/data/adb/dsha/rootfs\n"
                    + "· 会话与配置目录：/data/adb/dsha/rootfs/root/.dsh\n\n"
                    + "支持在 MT 管理器、Termux 或手机系统文件管理器中直接访问与读写。");
        }

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
    public void onResume() {
        super.onResume();
        syncActivityTitle(true);
    }

    @Override
    public void onDestroyView() {
        syncActivityTitle(false);
        presenter = null;
        actions = null;
        wsPathInput = null;
        wsListContainer = null;
        super.onDestroyView();
    }

    private void syncActivityTitle(boolean isSubpage) {
        if (!isAdded()) return;
        TextView activityTitle = requireActivity().findViewById(R.id.app_title);
        if (activityTitle != null) {
            activityTitle.setText(isSubpage ? "工作区与备份" : getString(R.string.nav_settings));
        }
    }

    @Override
    public void onRender(WorkspaceUiState state) {
        if (!isAdded() || getContext() == null) return;

        if (wsPathInput != null && state.workdirPath != null) {
            wsPathInput.setText(state.workdirPath);
        }

        if (wsListContainer == null) return;
        wsListContainer.removeAllViews();

        List<WorkspaceModel> list = state.workspaces;
        if (list == null || list.isEmpty()) return;

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(requireActivity());

        for (WorkspaceModel item : list) {
            View itemView = inflater.inflate(R.layout.item_workspace, wsListContainer, false);

            TextView tvTitle = itemView.findViewById(R.id.item_ws_title);
            TextView tvPath = itemView.findViewById(R.id.item_ws_path);
            TextView tvSessions = itemView.findViewById(R.id.item_ws_sessions);
            ModernBadgeView badgeType = itemView.findViewById(R.id.item_ws_badge_type);
            ModernBadgeView badgeCurrent = itemView.findViewById(R.id.item_ws_badge_current);
            Button btnSwitch = itemView.findViewById(R.id.item_ws_btn_switch);
            Button btnDelete = itemView.findViewById(R.id.item_ws_btn_delete);

            if (tvTitle != null) tvTitle.setText(item.title);
            if (tvPath != null) tvPath.setText(item.path);
            if (tvSessions != null) tvSessions.setText("历史会话：" + item.sessionCount + " 条");

            if (badgeType != null) {
                if (item.supportsSymlink) {
                    badgeType.setBadge(true, "支持软链接");
                } else {
                    badgeType.setBadge(false, "共享存储");
                }
            }

            if (badgeCurrent != null) {
                if (item.isCurrent) {
                    badgeCurrent.setVisibility(View.VISIBLE);
                    badgeCurrent.setBadge(true, "当前活跃");
                } else {
                    badgeCurrent.setVisibility(View.GONE);
                }
            }

            if (btnSwitch != null) {
                if (item.isCurrent) {
                    btnSwitch.setVisibility(View.GONE);
                } else {
                    btnSwitch.setVisibility(View.VISIBLE);
                    btnSwitch.setOnClickListener(v -> {
                        if (actions != null) actions.onSwitchWorkspace(item);
                    });
                }
            }

            if (btnDelete != null) {
                if (list.size() <= 1 || item.isCurrent) {
                    btnDelete.setVisibility(View.GONE);
                } else {
                    btnDelete.setVisibility(View.VISIBLE);
                    btnDelete.setOnClickListener(v -> {
                        if (actions != null) actions.onDeleteWorkspace(item);
                    });
                }
            }

            MonetEngine.applyToViewTree(itemView, palette);
            wsListContainer.addView(itemView);
        }
    }

    @Override
    public void onLaunchRestorePicker() {
        try {
            restorePicker.launch(new String[]{"*/*"});
        } catch (Throwable t) {
            ToastHelper.makeText(requireContext(), "打开选择器失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onGoBack() {
        getParentFragmentManager().popBackStack();
    }
}
