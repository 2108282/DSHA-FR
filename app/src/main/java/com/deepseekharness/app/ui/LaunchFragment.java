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
    private TextView runStateTitle;
    private TextView statusDescription;
    private ProgressBar busyBar;
    private Button startButton;
    private Button restartButton;
    private Button stopButton;
    private TextView lanAddrText;
    private Button openSheetButton;
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
        runStateTitle = v.findViewById(R.id.launch_run_state);
        statusDescription = v.findViewById(R.id.launch_status);
        busyBar = v.findViewById(R.id.launch_busy);
        startButton = v.findViewById(R.id.launch_start);
        restartButton = v.findViewById(R.id.launch_open);
        stopButton = v.findViewById(R.id.launch_stop);
        lanAddrText = v.findViewById(R.id.lan_addr);
        openSheetButton = v.findViewById(R.id.launch_open_sheet);
        logTextView = v.findViewById(R.id.launch_log);
        logScrollView = v.findViewById(R.id.launch_log_scroll);
        portEditText = v.findViewById(R.id.launch_port_input);

        // 2. 初始化 Presenter 与契约
        presenter = new LaunchPresenter(requireActivity(), this);
        actions = presenter;

        // 3. 事件挂载（每个点击仅一行，契约派发）
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
        if (lanAddrText != null) {
            lanAddrText.setOnClickListener(x -> actions.onLanAddressClick());
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
            View chip8080 = v.findViewById(R.id.launch_port_chip_8080);
            if (chip8080 != null) chip8080.setOnClickListener(x -> {
                portEditText.setText("8080");
                actions.onPortSelect(8080);
            });
        }

        return v;
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
            presenter = null;
        }
        actions = null;
        runStateTitle = null;
        statusDescription = null;
        busyBar = null;
        startButton = null;
        restartButton = null;
        stopButton = null;
        lanAddrText = null;
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
        if (busyBar != null) {
            busyBar.setVisibility(state.isBusy ? View.VISIBLE : View.GONE);
        }

        // 主按钮（启动 / 进入）
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

        // 局域网卡片与抽屉入口
        if (lanAddrText != null) {
            lanAddrText.setVisibility(state.isLanCardVisible ? View.VISIBLE : View.GONE);
            if (state.isLanCardVisible) {
                lanAddrText.setText(state.lanAddressText);
            }
        }
        if (openSheetButton != null) {
            openSheetButton.setVisibility(state.isSheetButtonVisible ? View.VISIBLE : View.GONE);
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
        if (!isAdded()) return;
        HarnessController controller = HarnessController.get(requireContext());
        final String bridgeToken = HttpShellService.currentToken();
        final String authUrl = controller != null ? controller.getWebAuthUrl() : "";

        boolean lan = requireContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_LAN_MODE, false);
        String ip = HarnessController.getLanAddress();
        final String lanAddr = (lan && ip != null && !ip.isEmpty())
                ? "http://" + ip + ":" + LanProxyService.LAN_PORT + "/?token="
                        + LanProxyService.getLanToken(requireContext())
                : null;

        java.util.List<String> items = new java.util.ArrayList<>();
        java.util.List<Runnable> acts = new java.util.ArrayList<>();

        // 1. 复制 Bridge Token
        items.add("📋 复制设备桥令牌 (Bridge Token)\n" + (bridgeToken.isEmpty() ? "（尚未生成）" : bridgeToken));
        acts.add(() -> copyAddr("Bridge Token", bridgeToken));

        // 2. 本机 Web 访问链接
        if (!authUrl.isEmpty()) {
            items.add("🌐 复制本机 Web 访问地址（带 Launch Token）\n" + authUrl);
            acts.add(() -> copyAddr("本机 Web 地址", authUrl));

            items.add("🚀 内部web访问");
            acts.add(this::enterWeb);
        }

        // 3. 局域网访问地址
        if (lanAddr != null) {
            items.add("📶 复制局域网访问地址（同 WiFi 其它设备）\n" + lanAddr);
            acts.add(() -> copyAddr("局域网地址", lanAddr));

            items.add("🔄 重新生成局域网访问 Token（更换密码）");
            acts.add(() -> {
                LanProxyService.regenerateLanToken(requireContext());
                if (presenter != null) presenter.recalculateState();
                Toast.makeText(requireContext(), "已生成新 Token 并更新地址", Toast.LENGTH_SHORT).show();
            });
        } else if (lan) {
            items.add("📶 局域网模式已开启，等待获取 WiFi IP…");
            acts.add(() -> {});
        } else {
            items.add("📶 局域网访问未开启（可在配置页中打开）");
            acts.add(() -> {});
        }

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("访问地址与鉴权凭据")
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    if (which >= 0 && which < acts.size()) acts.get(which).run();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void enterWeb() {
        HarnessController controller = HarnessController.get(requireContext());
        String url = controller != null ? controller.getWebAuthUrl() : "";
        if (url.isEmpty()) {
            if (statusDescription != null) {
                statusDescription.setText("先点「启动」，等鉴权链接就绪后再进入");
            }
            return;
        }
        final Activity activity = requireActivity();
        final long generation = controller.getWebGeneration();
        new Thread(() -> {
            String cookie = controller.exchangeDshAuthCookie();
            String finalUrl = url;
            activity.runOnUiThread(() -> {
                if (!isAdded() || generation != controller.getWebGeneration()
                        || !url.equals(controller.getWebAuthUrl())) return;
                startActivity(WebPreviewActivity.intent(requireContext(), finalUrl, cookie));
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
