package com.deepseekharness.app.ui.contract;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.deepseekharness.app.HarnessService;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.AboutActivity;
import com.deepseekharness.app.ui.DiagnosticActivity;
import com.deepseekharness.app.ui.UpdateActivity;

public class SettingsPresenter implements SettingsActions {

    public interface ViewCallback {
        void onRender(SettingsUiState state);
        void onOpenSubFragment(int index);
    }

    private final Activity activity;
    private final Context context;
    private final ViewCallback callback;
    private final ConfigStore configStore;
    private final HarnessController controller;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public SettingsPresenter(Activity activity, ViewCallback callback) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.callback = callback;
        this.configStore = new ConfigStore(context);
        this.controller = HarnessController.get(context);
    }

    public void init() {
        String version = "unknown";
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) { }

        String versionText = "DSHA-FR v" + version + " · MIT License";
        String updateSubText = "当前 v" + version + " · 稳定 / 预览更新通道";
        boolean persistent = configStore.isPersistentNotificationEnabled();

        SettingsUiState state = new SettingsUiState(versionText, updateSubText, persistent);
        callback.onRender(state);
    }

    @Override
    public void onOpenTab(int index) {
        callback.onOpenSubFragment(index);
    }

    @Override
    public void onCheckUpdateClick() {
        Intent intent = new Intent(context, UpdateActivity.class);
        activity.startActivity(intent);
        activity.overridePendingTransition(R.anim.fragment_enter, R.anim.fragment_exit);
    }

    @Override
    public void onRunSelftestClick() {
        activity.startActivity(new Intent(context, DiagnosticActivity.class));
        activity.overridePendingTransition(R.anim.fragment_enter, R.anim.fragment_exit);
    }

    @Override
    public void onAboutClick() {
        activity.startActivity(new Intent(context, AboutActivity.class));
        activity.overridePendingTransition(R.anim.fragment_enter, R.anim.fragment_exit);
    }

}
