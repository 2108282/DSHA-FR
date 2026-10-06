package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.contract.LaunchActions;
import com.deepseekharness.app.ui.contract.LaunchPresenter;
import com.deepseekharness.app.ui.contract.LaunchUiState;
import com.deepseekharness.app.util.Constants;

/**
 * 启动页：负责 UI 组件挂载与纯渲染，所有交互派发至 LaunchActions 契约。
 */
public class LaunchFragment extends Fragment implements LaunchPresenter.ViewCallback {

    private LaunchPresenter presenter;
    private LaunchActions actions;

    // View 控件引用
    private View runDot;
    private TextView runStateTitle;
    private TextView statusDescription;
    private ProgressBar busyBar;
    private Button startButton;
    private Button restartButton;
    private Button stopButton;
    private View lanRow;
    private Button lanCopyBtn;
    private Button lanMoreBtn;
    private DshaToggle lanSwitch;
    private View openSheetButton;
    private TextView logTextView;
    private ScrollView logScrollView;
    private EditText portEditText;

    private long lastRenderedLogRevision = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_launch, container, false);

        // 1. 查找控件引用
        runDot = v.findViewById(R.id.launch_run_dot);
        runStateTitle = v.findViewById(R.id.launch_run_state);
        statusDescription = v.findViewById(R.id.launch_status);
        busyBar = v.findViewById(R.id.launch_busy);
        startButton = v.findViewById(R.id.launch_start);
        restartButton = v.findViewById(R.id.launch_open);
        stopButton = v.findViewById(R.id.launch_stop);
        lanRow = v.findViewById(R.id.launch_lan_row);
        lanCopyBtn = v.findViewById(R.id.lan_copy);
        lanMoreBtn = v.findViewById(R.id.lan_more);
        lanSwitch = v.findViewById(R.id.lan_switch);
        openSheetButton = v.findViewById(R.id.launch_open_sheet);
        logTextView = v.findViewById(R.id.launch_log);
        logScrollView = v.findViewById(R.id.launch_log_scroll);
        portEditText = v.findViewById(R.id.launch_port_input);

        // 2. 初始化 Presenter 与契约
        presenter = new LaunchPresenter(requireActivity(), this);
        actions = presenter;

        // 3. 事件挂载
        startButton.setOnClickListener(x -> actions.onPrimaryActionClick());
        restartButton.setOnClickListener(x -> actions.onRestartClick());
        stopButton.setOnClickListener(x -> actions.onStopClick());

        View modelsButton = v.findViewById(R.id.launch_models);
        if (modelsButton != null) {
            modelsButton.setOnClickListener(x ->
                    startActivity(new Intent(requireContext(), ModelSetupActivity.class)));
        }

        if (openSheetButton != null) {
            openSheetButton.setOnClickListener(x -> actions.onOpenSheetClick());
        }

        if (lanMoreBtn != null) {
            lanMoreBtn.setOnClickListener(x -> openCredentialsPage());
        }

        if (lanCopyBtn != null) {
            lanCopyBtn.setOnClickListener(x -> copyLanAddress());
        }

        if (lanSwitch != null) {
            boolean currentLan = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(Constants.KEY_LAN_MODE, false);
            lanSwitch.setChecked(currentLan);
            lanSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(Constants.KEY_LAN_MODE, isChecked).apply();
                HarnessController controller = HarnessController.get(requireContext());
                if (controller != null && controller.isWebRunning()) {
                    if (isChecked) {
                        LanProxyService.start(requireContext());
                        Toast.makeText(requireContext(), "局域网服务已开启", Toast.LENGTH_SHORT).show();
                    } else {
                        LanProxyService.stop();
                        Toast.makeText(requireContext(), "局域网服务已关闭", Toast.LENGTH_SHORT).show();
                    }
                }
                if (presenter != null) presenter.recalculateState();
            });
        }

        // 4. 端口设置与快速切换芯片
        if (portEditText != null) {
            portEditText.setText(String.valueOf(presenter.getSavedPort()));
            portEditText.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void afterTextChanged(Editable s) {
                    actions.onPortInput(s.toString());
                }
            });
            View chip3080 = v.findViewById(R.id.launch_port_chip_3080);
            if (chip3080 != null) chip3080.setOnClickListener(x -> {
                portEditText.setText("3080");
                actions.onPortSelect(3080);
            });
            View chip3088 = v.findViewById(R.id.launch_port_chip_3088);
            if (chip3088 != null) chip3088.setOnClickListener(x -> {
                portEditText.setText("3088");
                actions.onPortSelect(3088);
            });
        }

        return v;
    }

    private void openCredentialsPage() {
        if (!isAdded()) return;
        startActivity(new Intent(requireContext(), CredentialsActivity.class));
    }

    private void copyLanAddress() {
        if (!isAdded()) return;
        String ip = HarnessController.getLanAddress();
        if (ip != null && !ip.isEmpty()) {
            String addr = "http://" + ip + ":" + LanProxyService.LAN_PORT + "/?token="
                    + LanProxyService.getLanToken(requireContext());
            copyAddr("局域网地址", addr);
        } else {
            Toast.makeText(requireContext(), "未检测到有效局域网 IP（请确认已连接 WiFi）", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (presenter != null) {
            presenter.start();
        }
    }

    @Override
    public void onPause() {
        if (presenter != null) {
            presenter.stop();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (presenter != null) {
            presenter.destroy();
        }
        actions = null;
        runDot = null;
        runStateTitle = null;
        statusDescription = null;
        busyBar = null;
        startButton = null;
        restartButton = null;
        stopButton = null;
        lanRow = null;
        lanCopyBtn = null;
        lanMoreBtn = null;
        lanSwitch = null;
        openSheetButton = null;
        logTextView = null;
        logScrollView = null;
        portEditText = null;
        super.onDestroyView();
    }

    /**
     * 单一渲染入口：所有的界面状态与按钮属性统一在此赋值，杜绝状态冲突与漏改。
     */
    @Override
    public void onRender(LaunchUiState state) {
        if (!isAdded() || getView() == null) return;

        // 状态标题与副文案
        if (runStateTitle != null) {
            runStateTitle.setText(state.runStateTitle);
        }
        if (statusDescription != null && !state.statusDescription.isEmpty()) {
            statusDescription.setText(state.statusDescription);
        }

        // 状态指示圆点
        if (runDot != null) {
            if ("服务运行中".equals(state.runStateTitle)) {
                runDot.setBackgroundResource(R.drawable.dot_active);
            } else if ("DSH 未运行".equals(state.runStateTitle) || "已停止".equals(state.runStateTitle)) {
                runDot.setBackgroundResource(R.drawable.dot_inactive);
            } else {
                runDot.setBackgroundResource(R.drawable.dot_warn);
            }
        }

        // 进度条
        if (busyBar != null) {
            busyBar.setVisibility(state.isBusy ? View.VISIBLE : View.GONE);
        }

        // 主操作按钮
        if (startButton != null) {
            startButton.setText(state.primaryActionText);
            startButton.setEnabled(state.isPrimaryActionEnabled);
        }

        // 重启按钮
        if (restartButton != null) {
            restartButton.setText(state.restartText);
            restartButton.setEnabled(state.isRestartEnabled);
        }

        // 停止按钮
        if (stopButton != null) {
            stopButton.setText(state.stopText);
            stopButton.setEnabled(state.isStopEnabled);
        }

        // 局域网行与抽屉入口（常驻三行功能卡，始终展示）
        if (lanRow != null) {
            lanRow.setVisibility(View.VISIBLE);
        }
        if (openSheetButton != null) {
            openSheetButton.setVisibility(View.VISIBLE);
        }
        if (lanSwitch != null && getContext() != null) {
            boolean currentLan = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(Constants.KEY_LAN_MODE, false);
            if (lanSwitch.isChecked() != currentLan) {
                lanSwitch.setChecked(currentLan);
            }
        }

        // 日志刷新
        if (logTextView != null && state.logRevision != lastRenderedLogRevision && !state.logContent.isEmpty()) {
            lastRenderedLogRevision = state.logRevision;
            logTextView.setText(state.logContent);
            if (logScrollView != null) {
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    @Override
    public void onAppendLog(String line) {
        if (logTextView == null || !isAdded()) return;
        String cur = logTextView.getText().toString();
        logTextView.setText("还没有日志。".equals(cur) ? line : cur + "\n" + line);
        if (logScrollView != null) {
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    public void onShowCredentialsDialog() {
        openCredentialsPage();
    }

    public void onOpenExternalBrowser(String url) {
        if (!isAdded()) return;
        HarnessController controller = HarnessController.get(requireContext());
        if (controller == null) return;
        new Thread(() -> {
            String cookie = controller.exchangeDshAuthCookie();
            Activity act = getActivity();
            if (act == null || act.isFinishing()) return;
            act.runOnUiThread(() -> {
                if (!isAdded() || getView() == null
                        || !url.equals(controller.getWebAuthUrl())) return;
                startActivity(WebPreviewActivity.intent(requireContext(), url, cookie));
            });
        }, "dsh-cookie").start();
    }

    private void copyAddr(String label, String addr) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, addr));
                Toast.makeText(requireContext(), label + " 已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "复制失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
