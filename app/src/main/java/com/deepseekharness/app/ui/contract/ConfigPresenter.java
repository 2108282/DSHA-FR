package com.deepseekharness.app.ui.contract;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.dialog.OverlayStyleDialog;
import com.deepseekharness.app.util.Constants;

public class ConfigPresenter implements ConfigActions {

    public interface ViewCallback {
        void onRender(ConfigUiState state);
        void onGoBack();
        void onRequestLocationPermission();
    }

    private final Activity activity;
    private final Context context;
    private final ViewCallback callback;
    private final ConfigStore config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String currentRootStatus = "正在检测 Root 权限…";

    public ConfigPresenter(Activity activity, ViewCallback callback) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.callback = callback;
        this.config = HarnessController.get(context).config();
    }

    public void init() {
        refreshRootStatus();
        refreshState();
    }

    private void refreshRootStatus() {
        new Thread(() -> {
            boolean ok = false;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                ok = (p.waitFor() == 0);
            } catch (Throwable ignored) {}
            currentRootStatus = ok
                    ? "✅ Root 授权正常"
                    : "⚠️ 未获取到 Root 权限，请在授权管理器中允许";
            mainHandler.post(this::refreshState);
        }, "check-root-config").start();
    }

    public void refreshState() {
        SharedPreferences sp = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);

        String port = config.getPort();
        String taskset = config.getTaskset();
        boolean confirmShell = config.isConfirmShell();
        boolean isIdleFreeze = config.isIdleFreezeEnabled();
        boolean overlayStream = sp.getBoolean("overlay_stream", false);
        boolean capSensors = sp.getBoolean("cap_sensors", false);
        boolean capLocation = sp.getBoolean("cap_location", false);
        boolean asrContinuous = sp.getBoolean("asr_continuous", false);

        boolean allFilesGranted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesGranted = Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            allFilesGranted = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            allFilesGranted = true;
        }
        String allFilesStatus = allFilesGranted
                ? "✅ 已开启：容器可读写手机存储任意文件（含工作区直通）"
                : "⚠️ 未开启：仅能访问 App 私有目录；点击跳转系统设置开启";

        String a11yStatus = checkA11yStatus();
        String asrStatus = checkAsrStatus();

        int freezeCode = HarnessController.get(context).getFreezeState();
        String freezeStatusText;
        if (freezeCode == 1) {
            freezeStatusText = "DSH 处于休眠冻结中 (SIGSTOP 挂起)";
        } else if (freezeCode == 0) {
            freezeStatusText = "DSH 正常运行中";
        } else {
            freezeStatusText = "DSH 核心未启动";
        }

        ConfigUiState state = new ConfigUiState(
                port, taskset, confirmShell, isIdleFreeze, overlayStream, capSensors,
                capLocation, asrContinuous, allFilesStatus, a11yStatus, asrStatus, currentRootStatus,
                freezeCode, freezeStatusText
        );
        mainHandler.post(() -> callback.onRender(state));
    }

    @Override
    public void onCheckRootClick() {
        new Thread(() -> {
            boolean ok = false;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                ok = (p.waitFor() == 0);
            } catch (Throwable ignored) {}
            final boolean rootOk = ok;
            mainHandler.post(() -> {
                toast(rootOk ? "✅ Root 授权正常" : "❌ 未获取到 Root 权限，请在授权管理器中允许");
                refreshRootStatus();
            });
        }, "recheck-root-config").start();
    }

    @Override
    public void onSaveTaskset(String taskset) {
        String cleanTaskset = taskset != null ? taskset.trim().replaceAll("[^0-9,-]", "") : "";
        config.setTaskset(cleanTaskset);
        applyTasksetImmediately(cleanTaskset);
        toast("CPU 核心调度已保存" + (cleanTaskset.isEmpty() ? "（全核调度）" : "：" + cleanTaskset));
        refreshState();
    }

    @Override
    public void onToggleConfirmShell(boolean enabled) {
        config.setConfirmShell(enabled);
        toast(enabled ? "已开启危险 Shell 操作拦截确认" : "已关闭危险 Shell 拦截确认");
        refreshState();
    }

    @Override
    public void onToggleIdleFreeze(boolean enabled) {
        HarnessController.get(context).applyIdleFreeze(enabled);
        toast(enabled ? "已开启休眠模式（30分钟无任务自动冻结）" : "已关闭休眠模式");
        refreshState();
    }

    @Override
    public void onToggleSensors(boolean enabled) {
        context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("cap_sensors", enabled).apply();
        toast(enabled ? "已允许读取传感器与手电" : "已禁用传感器与手电直通");
        refreshState();
    }

    @Override
    public void onToggleLocation(boolean enabled) {
        context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("cap_location", enabled).apply();
        if (enabled && context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onRequestLocationPermission();
        }
        toast(enabled ? "已允许读取位置" : "已禁用位置直通");
        refreshState();
    }

    @Override public void onBack() { callback.onGoBack(); }
    @Override public void onOpenAllFilesSettings() { openAllFilesAccess(); }
    @Override public void onOpenBatteryOptimization() { openBatteryOptimization(); }
    @Override public void onOpenA11ySettings() { openA11ySettings(); }
    @Override public void onCheckAsrStatus() { refreshState(); toast("ASR 状态检测已更新"); }
    @Override public void onFixAsrConfig() { applyAsrConfig(); }

    @Override
    public void onToggleAsrContinuous(boolean enabled) {
        context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("asr_continuous", enabled).apply();
        toast(enabled ? "已开启长语音连续接力模式" : "已恢复短语音模式（默认）");
        refreshState();
    }

    private void applyTasksetImmediately(String cpus) {
        new Thread(() -> {
            try {
                String cleanCpus = cpus != null ? cpus.trim().replaceAll("[^0-9,-]", "") : "";
                String writeCmd = "mkdir -p /data/adb/dsha/run /data/adb/dsha/rootfs/root/.dsh 2>/dev/null; "
                        + "echo '" + cleanCpus + "' > /data/adb/dsha/run/taskset 2>/dev/null; "
                        + "echo '" + cleanCpus + "' > /data/adb/dsha/rootfs/root/.dsh/taskset 2>/dev/null; ";
                String applyCmd = "PID=$(cat /data/adb/dsha/run/dsh.pid 2>/dev/null); "
                        + "if [ -n \"$PID\" ] && kill -0 \"$PID\" 2>/dev/null; then "
                        + "  if [ -f /dev/cpuset/cgroup.procs ]; then echo \"$PID\" > /dev/cpuset/cgroup.procs 2>/dev/null || true; fi; "
                        + "  TOTAL_CPUS=$(cat /sys/devices/system/cpu/online 2>/dev/null || echo '0-7'); "
                        + "  TARGET_CPUS=\"" + (cleanCpus.isEmpty() ? "$TOTAL_CPUS" : cleanCpus) + "\"; "
                        + "  chroot /data/adb/dsha/rootfs /usr/bin/taskset -a -p -c \"$TARGET_CPUS\" \"$PID\" >/dev/null 2>&1 || true; "
                        + "fi";
                Runtime.getRuntime().exec(new String[]{"su", "-mm", "-c", writeCmd + applyCmd}).waitFor();
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "动态应用 CPU 亲和度异常: " + e.getMessage());
            }
        }, "apply-taskset").start();
    }

    private String checkA11yStatus() {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) enabled = "";
            String myService = context.getPackageName() + "/com.deepseekharness.app.DshaAccessibilityService";
            if (enabled.contains(myService)) {
                return "✅ 正常：无障碍服务已开启，具备读屏/点按/输入能力";
            } else {
                return "⚠️ 未开启：点此前往系统设置开启无障碍，否则 Agent 无法操作屏幕";
            }
        } catch (Throwable t) {
            return "状态检测失败：" + t.getMessage();
        }
    }

    private String checkAsrStatus() {
        try {
            String currentService = Settings.Secure.getString(context.getContentResolver(), "voice_recognition_service");
            boolean isXiaomi = currentService != null && currentService.contains("com.xiaomi.mibrain.speech");
            boolean hasAudioPerm = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

            if (isXiaomi && hasAudioPerm) {
                return "✅ 已就绪：小米底层 ASR 引擎正常，录音权限已授予";
            } else {
                StringBuilder sb = new StringBuilder("⚠️ 需配置：");
                if (!isXiaomi) {
                    String shortName = currentService != null ? currentService.substring(currentService.lastIndexOf('.') + 1) : "未设置";
                    sb.append("当前引擎=").append(shortName).append("；");
                }
                if (!hasAudioPerm) {
                    sb.append("录音权限未授予；");
                }
                sb.append("请点击「一键配置」修复");
                return sb.toString();
            }
        } catch (Throwable t) {
            return "状态读取失败: " + t.getMessage();
        }
    }

    private void applyAsrConfig() {
        toast("正在通过 Root 配置小米原生 ASR 引擎...");
        new Thread(() -> {
            try {
                String pkg = context.getPackageName();
                String cmd = "settings put secure voice_recognition_service \"com.xiaomi.mibrain.speech/com.xiaomi.mibrain.speech.asr.AsrService\""
                + " && pm grant " + pkg + " android.permission.RECORD_AUDIO"
                + " && cmd appops set com.xiaomi.mibrain.speech RECORD_AUDIO allow"
                + " && cmd appops set " + pkg + " RECORD_AUDIO allow";
                HttpShellService.execRootCommand(cmd);
                mainHandler.post(() -> {
                    refreshState();
                    toast("ASR 引擎与录音权限已配置完成");
                });
            } catch (Throwable t) {
                mainHandler.post(() -> toast("配置失败：" + t.getMessage()));
            }
        }).start();
    }

    private void openAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + context.getPackageName()));
                activity.startActivity(i);
            } else {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + context.getPackageName()));
                activity.startActivity(i);
            }
        } catch (Throwable t) {
            toast("无法打开设置：" + t.getMessage());
        }
    }

    private void openBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + context.getPackageName()));
                activity.startActivity(i);
            }
        } catch (Throwable t) {
            toast("无法打开电池优化设置");
        }
    }

    private void openA11ySettings() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            toast("无法打开无障碍设置");
        }
    }

    @Override
    public void onTestFreeze() {
        HarnessController ctrl = HarnessController.get(context);
        int curState = ctrl.getFreezeState();
        if (curState == -1) {
            toast("DSH 核心未启动，无法进入休眠");
            return;
        }
        if (curState == 1) {
            toast("当前已处于休眠状态");
            return;
        }
        ctrl.testFreeze(success -> {
            if (success) {
                toast("已执行休眠冻结，请下拉通知栏查看「DSH 休眠中」");
            } else {
                toast("触发休眠失败，请检查 Root 权限");
            }
            refreshState();
        });
    }

    @Override
    public void onTestWake() {
        HarnessController ctrl = HarnessController.get(context);
        int curState = ctrl.getFreezeState();
        if (curState == -1) {
            toast("DSH 核心未启动");
            return;
        }
        ctrl.testWake(success -> {
            if (success) {
                toast("已解除休眠，主进程已复苏运行");
            } else {
                toast("触发唤醒失败，请检查 Root 权限");
            }
            refreshState();
        });
    }

    private void toast(String msg) {
        com.deepseekharness.app.util.ToastHelper.show(context, msg);
    }
}
