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
    private CapsuleProgressView busyBar;
    private Button startButton;
    private Button restartButton;
    private Button stopButton;
    private View lanRow;
    private TextView lanSubtitle;
    private View openSheetButton;
    private TextView logTextView;
    private ScrollView logScrollView;
    private EditText portEditText;

    private android.animation.ObjectAnimator dotPulseAnimator;
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
        lanSubtitle = v.findViewById(R.id.launch_lan_subtitle);
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

        // 局域网连接行: 单击进入下级页面，长按复制链接
        if (lanRow != null) {
            lanRow.setOnClickListener(x -> openCredentialsPage());
            lanRow.setOnLongClickListener(x -> {
                copyLanAddress();
                return true;
            });
        }
        updateLanSubtitle();

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
        boolean lan = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_LAN_MODE, false);
        if (!lan) {
            Toast.makeText(requireContext(), "局域网访问未授权，请单击进入设置开启", Toast.LENGTH_SHORT).show();
            return;
        }
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
        updateLanSubtitle();
        if (presenter != null) {
            presenter.start();
        }
    }

    private void updateLanSubtitle() {
        if (lanSubtitle == null || getContext() == null) return;
        boolean lan = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_LAN_MODE, false);
        lanSubtitle.setText(lan ? "已授权·长按复制链接" : "局域网访问未授权");
    }

    @Override
    public void onPause() {
        updateDotAnimation(false);
        if (presenter != null) {
            presenter.stop();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        updateDotAnimation(false);
        if (dotPulseAnimator != null) {
            dotPulseAnimator.cancel();
            dotPulseAnimator = null;
        }
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
        lanSubtitle = null;
        openSheetButton = null;
        logTextView = null;
        logScrollView = null;
        portEditText = null;
        super.onDestroyView();
    }

    private void updateDotAnimation(boolean shouldPulse) {
        if (runDot == null) return;
        if (shouldPulse) {
            if (dotPulseAnimator == null) {
                // 1:1 对齐 HTML @keyframes pulse: 0%,100%{opacity:1} 50%{opacity:.4} (1.2s ease infinite)
                dotPulseAnimator = android.animation.ObjectAnimator.ofFloat(runDot, "alpha", 1.0f, 0.4f);
                dotPulseAnimator.setDuration(600); // 单程 600ms，双向往返即 1.2s
                dotPulseAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                dotPulseAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                dotPulseAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            }
            if (!dotPulseAnimator.isRunning()) {
                dotPulseAnimator.start();
            }
        } else {
            if (dotPulseAnimator != null && dotPulseAnimator.isRunning()) {
                dotPulseAnimator.cancel();
            }
            runDot.setAlpha(1.0f);
        }
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
            boolean isRunning = "服务运行中".equals(state.runStateTitle)
                    || "DSH 已就绪，可进入".equals(state.runStateTitle);
            boolean isStopped = "DSH 未运行".equals(state.runStateTitle)
                    || "DSH 已停止".equals(state.runStateTitle)
                    || "已停止".equals(state.runStateTitle);

            if (isRunning) {
                runDot.setBackgroundResource(R.drawable.dot_active);
                updateDotAnimation(true);
            } else if (isStopped) {
                runDot.setBackgroundResource(R.drawable.dot_inactive);
                updateDotAnimation(false);
            } else {
                runDot.setBackgroundResource(R.drawable.dot_warn);
                updateDotAnimation(true);
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
        updateLanSubtitle();

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
