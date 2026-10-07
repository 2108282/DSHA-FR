package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.ui.contract.SettingsActions;
import com.deepseekharness.app.ui.contract.SettingsPresenter;
import com.deepseekharness.app.ui.contract.SettingsUiState;

import java.util.function.Supplier;

/**
 * 设置页：1:1 像素级 Skia 现代卡片设计 (ModernCardView + DshaToggle)，纯渲染与契约驱动。
 */
public class SettingsFragment extends Fragment implements SettingsPresenter.ViewCallback {

    private SettingsPresenter presenter;
    private SettingsActions actions;

    private TextView verText;
    private TextView updateSubText;
    private DshaToggle persistentNotifToggle;
    private boolean isBinding = false;

    private static final TabOption[] TAB_OPTIONS = {
            new TabOption("配置", "行为 · CPU调度 · 权限", ConfigFragment::new),
            new TabOption("数据与备份", "备份恢复 · 保存位置 · 工作区", WorkspaceFragment::new),
            new TabOption("快捷对话设置", "反色 · 圈定即搜 · 白天黑夜透明度", SheetSettingsFragment::new),
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);

        presenter = new SettingsPresenter(requireActivity(), this);
        actions = presenter;

        verText = v.findViewById(R.id.settings_ver);
        updateSubText = v.findViewById(R.id.settings_update_sub);
        persistentNotifToggle = v.findViewById(R.id.settings_persistent_notification_toggle);

        // 1. 模块区域入口派发
        View rowConfig = v.findViewById(R.id.settings_row_config);
        if (rowConfig != null) {
            rowConfig.setOnClickListener(x -> actions.onOpenTab(0));
        }

        View rowWorkspace = v.findViewById(R.id.settings_row_workspace);
        if (rowWorkspace != null) {
            rowWorkspace.setOnClickListener(x -> actions.onOpenTab(1));
        }

        View rowQuickchat = v.findViewById(R.id.settings_row_quickchat);
        if (rowQuickchat != null) {
            rowQuickchat.setOnClickListener(x -> actions.onOpenTab(2));
        }

        // 2. 其他区域按钮契约派发
        View rowUpdate = v.findViewById(R.id.settings_row_update);
        if (rowUpdate != null) {
            rowUpdate.setOnClickListener(x -> actions.onCheckUpdateClick());
        }

        View rowSelftest = v.findViewById(R.id.settings_row_selftest);
        if (rowSelftest != null) {
            rowSelftest.setOnClickListener(x -> actions.onRunSelftestClick());
        }

        View rowPatches = v.findViewById(R.id.settings_row_patches);
        if (rowPatches != null) {
            rowPatches.setOnClickListener(x -> actions.onApplyPatchesClick());
        }

        View rowAbout = v.findViewById(R.id.settings_row_about);
        if (rowAbout != null) {
            rowAbout.setOnClickListener(x -> actions.onAboutClick());
        }

        // 3. 常驻后台服务通知开关
        if (persistentNotifToggle != null) {
            persistentNotifToggle.setOnCheckedChangeListener((toggle, isChecked) -> {
                if (isBinding) return;
                actions.onTogglePersistentNotification(isChecked);
            });

            View notifRow = v.findViewById(R.id.settings_row_persistent_notif);
            if (notifRow != null) {
                notifRow.setOnClickListener(x -> persistentNotifToggle.toggle());
            }
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
    public void onDestroyView() {
        if (persistentNotifToggle != null) {
            persistentNotifToggle.setOnCheckedChangeListener(null);
        }
        presenter = null;
        actions = null;
        verText = null;
        updateSubText = null;
        persistentNotifToggle = null;
        super.onDestroyView();
    }

    @Override
    public void onRender(SettingsUiState state) {
        if (!isAdded()) return;
        if (verText != null) verText.setText(state.versionText);
        if (updateSubText != null) updateSubText.setText(state.updateSubText);
        if (persistentNotifToggle != null) {
            isBinding = true;
            if (persistentNotifToggle.isChecked() != state.isPersistentNotificationEnabled) {
                persistentNotifToggle.setChecked(state.isPersistentNotificationEnabled, false, false);
            }
            isBinding = false;
        }
    }

    @Override
    public void onOpenSubFragment(int index) {
        if (!isAdded() || index < 0 || index >= TAB_OPTIONS.length) return;
        getParentFragmentManager().beginTransaction()
                .setCustomAnimations(
                        R.anim.fragment_enter, R.anim.fragment_exit,
                        R.anim.fragment_pop_enter, R.anim.fragment_pop_exit)
                .replace(R.id.fragment_container, TAB_OPTIONS[index].factory.get())
                .addToBackStack("settings")
                .commit();
    }

    private static final class TabOption {
        final String title;
        final String sub;
        final Supplier<Fragment> factory;

        TabOption(String title, String sub, Supplier<Fragment> factory) {
            this.title = title;
            this.sub = sub;
            this.factory = factory;
        }
    }
}
