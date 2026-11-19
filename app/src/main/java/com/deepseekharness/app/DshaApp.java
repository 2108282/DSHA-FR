package com.deepseekharness.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import com.deepseekharness.app.util.Constants;

/**
 * 应用入口：全局初始化。
 * 初始化设备桥、手机控制与界面支持，并创建保活、运行状态、任务结果和安全确认通知渠道。
 */
public class DshaApp extends Application {

    private static volatile DshaApp sInstance;

    public static DshaApp get() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        com.deepseekharness.app.util.ChromiumFlagsHelper.apply(this);
        com.deepseekharness.app.ui.ThemeController.apply(this);
        // 保证 3095 设备与运行时桥随应用启动，不依赖 ADB 开关
        HttpShellService.ensureStarted(this);
        try {
            com.deepseekharness.app.phone.PhoneControl.get(this);
        } catch (Throwable ignored) {}

        com.deepseekharness.app.core.DiagnosticLog.installCrashHandler(this);
        registerActivityLifecycleCallbacks(new com.deepseekharness.app.ui.ModernAndroidUi());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                // 1. 常驻后台服务渠道
                NotificationChannel chService = new NotificationChannel(
                        "dsh_harness_channel", "DSHA后台服务", NotificationManager.IMPORTANCE_LOW);
                chService.setDescription("保持 DeepSeek Harness 原生守护与硬件桥后台运行");
                nm.createNotificationChannel(chService);

                // 2. 运行状态实时胶囊渠道（保持 IMPORTANCE_DEFAULT 规避 Heads-Up 展开大岛，直通 InitToBigIsland 收缩态胶囊）
                NotificationChannel chRunning = new NotificationChannel(
                        Constants.CHANNEL_AGENT_RUNNING, "Agent 运行状态", NotificationManager.IMPORTANCE_DEFAULT);
                chRunning.setDescription("智能体运行中实时操作步骤通知");
                nm.createNotificationChannel(chRunning);
                try {
                    nm.deleteNotificationChannel("dsh_agent_channel_v2");
                } catch (Throwable ignored) {}

                // 3. 任务结果与交付卡片渠道
                NotificationChannel chResult = new NotificationChannel(
                        Constants.CHANNEL_TASK_RESULT, "任务结果与交互", NotificationManager.IMPORTANCE_HIGH);
                chResult.setDescription("智能体任务完成、异常结束或终止时的结果通知");
                chResult.enableVibration(true);
                chResult.enableLights(true);
                nm.createNotificationChannel(chResult);

                // 4. 安全确认与助手提问渠道
                NotificationChannel chConfirm = new NotificationChannel(
                        Constants.CHANNEL_SHELL_CONFIRM, "安全确认", NotificationManager.IMPORTANCE_HIGH);
                chConfirm.setDescription("模型执行危险操作时的确认提醒与助手提问");
                chConfirm.enableVibration(true);
                chConfirm.enableLights(true);
                nm.createNotificationChannel(chConfirm);
            }
        }

        // 核心运转常驻通知：若核心在运行且常驻通知开关开启，自动同步挂载
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                HarnessService.checkAndSyncService(this);
                com.deepseekharness.app.gesture.CornerGesturePreview.restore(this);
            } catch (Throwable ignored) {}
        }, 500);
    }
}
