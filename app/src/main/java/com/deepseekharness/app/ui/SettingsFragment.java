package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.ui.contract.SettingsActions;
import com.deepseekharness.app.ui.contract.SettingsPresenter;
import com.deepseekharness.app.ui.contract.SettingsUiState;

import java.util.function.Supplier;

/**
 * 设置页：模块入口（安装/配置/数据与备份）+ 其他（更新/自检/关于），纯渲染与契约驱动。
 */
public class SettingsFragment extends Fragment implements SettingsPresenter.ViewCallback {

    private SettingsPresenter presenter;
    private SettingsActions actions;

    private TextView verText;
    private TextView updateSubText;
    private SwitchCompat persistentNotifSwitch;
    private boolean isBinding = false;

    private static final TabOption[] TAB_OPTIONS = {
            new TabOption("配置", "端口 · 行为 · 权限", ConfigFragment::new),
            new TabOption("数据与备份", "备份恢复 · 保存位置 · 工作区", WorkspaceFragment::new),
            new TabOption("抽屉设置", "反色 · 圈定即搜 · 白天黑夜透明度", SheetSettingsFragment::new),
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);

        presenter = new SettingsPresenter(requireActivity(), this);
        actions = presenter;

        LinearLayout tabs = v.findViewById(R.id.settings_tabs);
        if (tabs != null) {
            tabs.removeAllViews();
            for (int i = 0; i < TAB_OPTIONS.length; i++) {
                if (i > 0) {
                    View divider = new View(requireContext());
                    divider.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1));
                    divider.setBackgroundColor(requireContext().getColor(R.color.line));
                    tabs.addView(divider);
                }
                tabs.addView(buildRow(i));
            }
        }

        verText = v.findViewById(R.id.settings_ver);
        updateSubText = v.findViewById(R.id.settings_update_sub);
        persistentNotifSwitch = v.findViewById(R.id.settings_persistent_notification_switch);

        // 按钮契约派发
        v.findViewById(R.id.settings_about).setOnClickListener(x -> actions.onAboutClick());
        v.findViewById(R.id.settings_update).setOnClickListener(x -> actions.onCheckUpdateClick());
        v.findViewById(R.id.settings_selftest).setOnClickListener(x -> actions.onRunSelftestClick());
        v.findViewById(R.id.settings_apply_patches).setOnClickListener(x -> actions.onApplyPatchesClick());

        if (persistentNotifSwitch != null) {
            persistentNotifSwitch.setClickable(true);
            persistentNotifSwitch.setFocusable(false);
            persistentNotifSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isBinding) return;
                actions.onTogglePersistentNotification(isChecked);
            });

            View notifRow = v.findViewById(R.id.settings_persistent_notification_row);
            if (notifRow != null) {
                notifRow.setOnClickListener(x -> persistentNotifSwitch.toggle());
            }
        }

        View reextract = v.findViewById(R.id.settings_reextract);
        if (reextract != null) {
            reextract.setVisibility(View.GONE);
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
        if (persistentNotifSwitch != null) {
            persistentNotifSwitch.setOnCheckedChangeListener(null);
        }
        presenter = null;
        actions = null;
        verText = null;
        updateSubText = null;
        persistentNotifSwitch = null;
        super.onDestroyView();
    }

    @Override
    public void onRender(SettingsUiState state) {
        if (!isAdded()) return;
        if (verText != null) verText.setText(state.versionText);
        if (updateSubText != null) updateSubText.setText(state.updateSubText);
        if (persistentNotifSwitch != null) {
            isBinding = true;
            if (persistentNotifSwitch.isChecked() != state.isPersistentNotificationEnabled) {
                persistentNotifSwitch.setChecked(state.isPersistentNotificationEnabled);
            }
            isBinding = false;
        }
    }

    @Override
    public void onOpenSubFragment(int index) {
        if (!isAdded() || index < 0 || index >= TAB_OPTIONS.length) return;
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, TAB_OPTIONS[index].factory.get())
                .addToBackStack("settings")
                .commit();
    }

    private LinearLayout buildRow(final int index) {
        TabOption opt = TAB_OPTIONS[index];
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(15), dp(15), dp(15));

        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(requireContext());
        title.setText(opt.title);
        title.setTextSize(14);
        title.setTextColor(requireContext().getColor(R.color.text));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView sub = new TextView(requireContext());
        sub.setText(opt.sub);
        sub.setTextSize(12);
        sub.setTextColor(requireContext().getColor(R.color.text_muted));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        sub.setLayoutParams(slp);

        body.addView(title);
        body.addView(sub);

        TextView chev = new TextView(requireContext());
        chev.setText("›");
        chev.setTextSize(18);
        chev.setTextColor(requireContext().getColor(R.color.text_muted));

        row.addView(body);
        row.addView(chev);
        row.setOnClickListener(v -> actions.onOpenTab(index));
        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
