package com.deepseekharness.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.deepseekharness.app.util.Constants;

/**
 * 核心生命周期广播接收器：
 * 接收来自 Shell 底层（start.sh / stop.sh / idle-freezer.sh）的系统级显式广播，
 * 毫秒级原地更新常驻通知，脱离脆弱的 HTTP 网络通道。
 */
public class CoreStateReceiver extends BroadcastReceiver {

    private static final String TAG = "CoreStateReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Constants.ACTION_CORE_STATE.equals(action)) return;

        int state = intent.getIntExtra(Constants.EXTRA_CORE_STATE, 0);
        String reason = intent.getStringExtra("reason");
        Log.i(TAG, "收到 Shell 核心状态广播: state=" + state + ", reason=" + reason);

        if (state >= Constants.CORE_STATE_RUNNING && state <= Constants.CORE_STATE_ERROR) {
            HarnessService.updateCoreState(context, state);
        }
    }
}
