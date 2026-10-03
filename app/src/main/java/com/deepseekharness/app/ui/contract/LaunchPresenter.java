package com.deepseekharness.app.ui.contract;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.deepseekharness.app.HarnessService;
import com.deepseekharness.app.LanProxyService;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.QuickChatSheetActivity;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.StartupTrace;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 启动页业务逻辑控制器：负责状态计算、服务启停调度、意图跳转与单一状态派发。
 */
public class LaunchPresenter implements LaunchActions {

    public interface ViewCallback {
        void onRender(LaunchUiState state);
        void onAppendLog(String line);
        void onShowCredentialsDialog();
    }

    private final Context context;
    private final Activity activity;
    private final HarnessController controller;
    private final ViewCallback callback;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final HarnessController.StatusListener statusListener = () -> uiHandler.post(this::recalculateState);

    private boolean isDestroyed = false;
    private long startAtMs = 0;
    private String customStatusMsg = "";
    private long currentLogRevision = -1;
    private String currentLogText = "";

    private final Runnable pollStateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed) return;
            recalculateState();
            boolean running = controller.isWebRunning();
            boolean hasUrl = !controller.getWebAuthUrl().isEmpty();
            long delay = (running && hasUrl) ? 2500L : 1000L;
            uiHandler.postDelayed(this, delay);
        }
    };

    public LaunchPresenter(Activity activity, ViewCallback callback) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.controller = HarnessController.get(context);
        this.callback = callback;
    }

    public void start() {
        isDestroyed = false;
        controller.addStatusListener(statusListener);
        controller.asyncRefreshStatus();
        uiHandler.post(pollStateRunnable);
    }

    public void stop() {
        controller.removeStatusListener(statusListener);
        uiHandler.removeCallbacks(pollStateRunnable);
    }

    public void destroy() {
        isDestroyed = true;
        stop();
    }

    public int getSavedPort() {
        return controller.config().getPortInt();
    }

    /** 核心计算：根据核心底层状态生成不可变 UI 状态快照 */
    public void recalculateState() {
        if (isDestroyed) return;

        boolean starting = controller.isStarting();
        boolean restarting = controller.isRestarting();
        boolean stopping = controller.isStopping();
        boolean running = controller.isWebRunning();
        boolean ready = !starting && !stopping && running && !controller.getWebAuthUrl().isEmpty();

        // 1. 同步最新日志追踪
        StartupTrace.Snapshot trace = controller.startupDiagnostics().snapshot();
        if (trace.revision != currentLogRevision && !trace.log.isEmpty()) {
            currentLogRevision = trace.revision;
            currentLogText = trace.log;
        }

        if (!ready && !starting && !stopping && running) {
            controller.tryRecoverRunningUrl();
        }

        // 2. 状态标题与 Busy 指示
        String title;
        boolean busy;
        if (stopping) {
            title = "DSH 停止中…";
            busy = true;
        } else if (restarting) {
            title = "DSH 重启中…";
            busy = true;
        } else if (starting) {
            title = "DSH 启动中…";
            busy = true;
        } else if (ready) {
            title = "DSH 已就绪，可进入";
            busy = false;
        } else if (running) {
            title = "DSH 运行中，正在同步连接…";
            busy = true;
        } else if (!controller.isEnvironmentReady()) {
            title = "⚠️ 未检测到 KernelSU 模块或未授权 Root";
            busy = false;
        } else if (controller.isUserStopped()) {
            title = "DSH 已停止";
            busy = false;
        } else {
            title = "DSH 未运行";
            busy = false;
        }

        // 3. 主操作按钮（启动/进入）状态
        String primaryText;
        boolean primaryEnabled;
        if (stopping || restarting) {
            primaryText = "启动";
            primaryEnabled = false;
        } else if (starting) {
            primaryText = "启动中…";
            primaryEnabled = false;
        } else if (ready || running) {
            primaryText = "进入";
            primaryEnabled = true;
        } else {
            primaryText = "启动";
            primaryEnabled = true;
        }

        // 4. 重启按钮状态
        String restartText = restarting ? "重启中…" : "重启";
        boolean restartEnabled = !starting && !stopping && running && !restarting;

        // 5. 停止按钮状态
        String stopText = stopping ? "停止中…" : "停止";
        boolean stopEnabled = !stopping && (running || starting);

        // 6. 局域网卡片与抽屉入口
        boolean lan = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Constants.KEY_LAN_MODE, false);
        boolean lanCardVisible = lan || ready;
        String lanAddress = "";
        boolean sheetBtnVisible = lanCardVisible;

        if (lanCardVisible) {
            if (lan) {
                if (LanProxyService.isBound()) {
                    String ip = HarnessController.getLanAddress();
                    if (ip != null && !ip.isEmpty()) {
                        lanAddress = "🔗 局域网服务已就绪 · " + ip + ":" + LanProxyService.LAN_PORT;
                    } else {
                        lanAddress = "🔗 局域网服务已开启（等待连接 WiFi）";
                    }
                } else {
                    lanAddress = "🔗 局域网核心代理启动中…";
                }
            } else {
                lanAddress = "🔗 访问地址与鉴权凭据";
            }
        }

        String statusDesc = customStatusMsg;

        LaunchUiState state = new LaunchUiState(
                title,
                statusDesc,
                busy,
                primaryText,
                primaryEnabled,
                restartText,
                restartEnabled,
                stopText,
                stopEnabled,
                lanCardVisible,
                lanAddress,
                sheetBtnVisible,
                currentLogText,
                currentLogRevision
        );

        uiHandler.post(() -> {
            if (!isDestroyed) {
                callback.onRender(state);
            }
        });
    }

    @Override
    public void onPrimaryActionClick() {
        boolean running = controller.isWebRunning();
        boolean hasUrl = !controller.getWebAuthUrl().isEmpty();

        if (running && hasUrl) {
            openExternalBrowser();
            return;
        }
        if (running && !hasUrl) {
            Toast.makeText(context, "正在同步鉴权凭据，请稍候…", Toast.LENGTH_SHORT).show();
            controller.tryRecoverRunningUrl();
            return;
        }
        doStart();
    }

    private void doStart() {
        if (controller.isStarting() || controller.isStopping()) return;
        startAtMs = System.currentTimeMillis();
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        customStatusMsg = "启动中…（" + time + "）";
        callback.onAppendLog("—— 启动 " + time + " ——");
        recalculateState();

        Consumer<String> startStatus = msg -> {
            long generation = controller.getWebGeneration();
            activity.runOnUiThread(() -> {
                if (isDestroyed || generation != controller.getWebGeneration()) return;
                customStatusMsg = msg;
                if (!controller.getWebAuthUrl().isEmpty()) {
                    long sec = (System.currentTimeMillis() - startAtMs) / 1000;
                    callback.onAppendLog("启动成功，耗时 " + sec + "s");
                    callback.onAppendLog("本机打开：" + controller.getWebAuthUrl()
                            + "　（仅本机；其它设备请用「局域网地址」那条）");
                    if (com.deepseekharness.app.HarnessService.currentInstance != null) {
                        com.deepseekharness.app.HarnessService.currentInstance.refreshNotification();
                    }
                }
                recalculateState();
            });
        };

        boolean accepted = controller.startWeb(startStatus);
        recalculateState();
        if (accepted) {
            com.deepseekharness.app.HarnessService.checkAndSyncService(context);
        }
    }

    @Override
    public void onRestartClick() {
        startAtMs = System.currentTimeMillis();
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        customStatusMsg = "重启中…（" + time + "）";
        callback.onAppendLog("—— 强制重启 " + time + " ——");
        recalculateState();

        Consumer<String> startStatus = msg -> {
            long generation = controller.getWebGeneration();
            activity.runOnUiThread(() -> {
                if (isDestroyed || generation != controller.getWebGeneration()) return;
                customStatusMsg = msg;
                if (!controller.getWebAuthUrl().isEmpty()) {
                    long sec = (System.currentTimeMillis() - startAtMs) / 1000;
                    callback.onAppendLog("启动成功，耗时 " + sec + "s");
                    callback.onAppendLog("本机打开：" + controller.getWebAuthUrl()
                            + "　（仅本机；其它设备请用「局域网地址」那条）");
                    if (com.deepseekharness.app.HarnessService.currentInstance != null) {
                        com.deepseekharness.app.HarnessService.currentInstance.refreshNotification();
                    }
                }
                recalculateState();
            });
        };
        controller.restartWeb(startStatus);
        recalculateState();
    }

    @Override
    public void onStopClick() {
        customStatusMsg = "停止中…";
        recalculateState();
        controller.stopWeb(msg -> {
            long generation = controller.getWebGeneration();
            activity.runOnUiThread(() -> {
                if (isDestroyed || generation != controller.getWebGeneration()) return;
                customStatusMsg = msg;
                recalculateState();
                com.deepseekharness.app.HarnessService.stopServiceIfNecessary(context);
            });
        });
        com.deepseekharness.app.HarnessService.stopServiceIfNecessary(context);
    }

    @Override
    public void onOpenSheetClick() {
        try {
            Intent intent = QuickChatSheetActivity.createLaunchIntent(context);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, "无法打开快捷对话抽屉：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPortSelect(int port) {
        controller.config().setPort(String.valueOf(port));
    }

    @Override
    public void onPortInput(String port) {
        String p = port.trim();
        if (!p.isEmpty()) {
            controller.config().setPort(p);
        }
    }

    @Override
    public void onLanAddressClick() {
        callback.onShowCredentialsDialog();
    }

    private void openExternalBrowser() {
        String url = controller.getWebAuthUrl();
        if (url.isEmpty()) {
            customStatusMsg = "先点「启动」，等鉴权链接就绪后再进入";
            recalculateState();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, "无法打开浏览器：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
