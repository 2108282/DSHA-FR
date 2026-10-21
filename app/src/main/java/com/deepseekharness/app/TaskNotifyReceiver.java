package com.deepseekharness.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;

/**
 * 任务与守卫通知的统一 Shell 调度接收器：
 * 接收来自底层 Linux 原生二进制（dsh-notify / dsh-guard）的显式广播，
 * 实现常规通知、守卫原子确认通知、以及四端同时弹出（通知/悬浮条/抽屉/网页）的统一调度。
 */
public class TaskNotifyReceiver extends BroadcastReceiver {

    private static final String TAG = "TaskNotifyReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        ConfigStore cfg = new ConfigStore(context);
        HttpShellService.ensureStarted(context);
        HttpShellService svc = HttpShellService.instance();

        // 1. 危险 Shell 命令拦截审批广播 (来自 dsh-guard C++ 原生守卫)
        if (Constants.ACTION_SHELL_CONFIRM_REQUEST.equals(action)) {
            String cmd = intent.getStringExtra("cmd");
            long epoch = intent.getLongExtra("epoch", System.currentTimeMillis());
            if (cmd != null && !cmd.isEmpty()) {
                Log.i(TAG, "收到原生守卫拦截申请: " + cmd + " (epoch " + epoch + ")");
                if (svc != null) {
                    svc.requestApprovalFromShell(cmd, epoch);
                }
            }
            return;
        }

        // 2. 常规任务运行与状态通知广播 (来自 dsh-notify 原生工具)
        if (Constants.ACTION_TASK_NOTIFY.equals(action)) {
            // 检查 App 内任务通知总开关
            if (!cfg.isTaskNotificationEnabled()) {
                return;
            }

            String type = intent.getStringExtra("type");
            String title = intent.getStringExtra("title");
            String text = intent.getStringExtra("text");

            if (type == null || type.isEmpty()) type = "running";

            switch (type) {
                case "running":
                    if (svc != null) {
                        svc.postTaskRunning(title != null ? title : "正在执行", text != null ? text : "");
                    }
                    break;
                case "done":
                    if (svc != null) {
                        svc.postTaskDone(title != null ? title : "任务完成", text != null ? text : "任务已结束");
                    }
                    break;
                case "ask":
                    if (svc != null) {
                        svc.postTaskAsk(text != null ? text : "智能体正在等待你的回答与选择");
                    }
                    break;
                case "cancel":
                    if (svc != null) {
                        svc.postTaskCancel();
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
