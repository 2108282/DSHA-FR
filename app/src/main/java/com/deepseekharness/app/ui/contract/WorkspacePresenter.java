package com.deepseekharness.app.ui.contract;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.DshaDialogBuilder;
import com.deepseekharness.app.util.BackupScope;

public class WorkspacePresenter implements WorkspaceActions {

    public interface ViewCallback {
        void onRender(WorkspaceUiState state);
        void onGoBack();
        void onLaunchRestorePicker();
    }

    private final Activity activity;
    private final Context context;
    private final ViewCallback callback;
    private final HarnessController controller;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public WorkspacePresenter(Activity activity, ViewCallback callback) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.callback = callback;
        this.controller = new HarnessController(context);
    }

    public void init() {
        WorkspaceUiState state = new WorkspaceUiState(controller.config().getWorkdir(), "");
        callback.onRender(state);
    }

    @Override
    public void onBackClick() {
        callback.onGoBack();
    }

    @Override
    public void onApplyWorkdir(String path) {
        String p = path != null ? path.trim() : "";
        if (!p.isEmpty()) {
            controller.config().setWorkdir(p);
            Toast.makeText(context, "工作区目录已更新：" + p, Toast.LENGTH_SHORT).show();
            init();
        }
    }

    @Override
    public void onBackupClick() {
        try {
            chooseScopeAndBackup();
        } catch (Throwable t) {
            Toast.makeText(context, "打开备份选项失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void chooseScopeAndBackup() {
        final CharSequence[] choices = new CharSequence[BackupScope.ALL.length];
        for (int i = 0; i < BackupScope.ALL.length; i++) {
            choices[i] = BackupScope.label(BackupScope.ALL[i]) + "\n" + BackupScope.describe(BackupScope.ALL[i]);
        }
        final int[] selected = {0};
        new DshaDialogBuilder(activity)
                .setTitle("选择备份范围")
                .setSingleChoiceItems(choices, 0, (d, which) -> selected[0] = which)
                .setPositiveButton("下一步", (d, which) -> confirmBackup(BackupScope.ALL[selected[0]]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmBackup(final int scope) {
        float density = activity.getResources().getDisplayMetrics().density;
        int padH = (int) (20 * density);
        int padTop = (int) (8 * density);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padH, padTop, padH, 0);

        TextView summaryView = new TextView(activity);
        String summary = "即将备份：" + BackupScope.label(scope)
                + "\n" + BackupScope.describe(scope)
                + "\n\n保存位置：Download/DSHA/" + BackupScope.fileNamePrefix(scope) + "latest.tar.gz";
        summaryView.setText(summary);
        summaryView.setTextSize(14);
        summaryView.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.text_secondary));
        summaryView.setLineSpacing(0f, 1.25f);
        layout.addView(summaryView);

        CheckBox cbApiKey = new CheckBox(activity);
        cbApiKey.setText("同时备份 API key（关掉更安全，恢复后需重填）");
        cbApiKey.setTextSize(14);
        cbApiKey.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.text));
        cbApiKey.setChecked(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = (int) (14 * density);
        cbApiKey.setLayoutParams(lp);
        layout.addView(cbApiKey);

        new DshaDialogBuilder(activity)
                .setTitle("确认备份")
                .setView(layout)
                .setPositiveButton("开始备份", (d, w) -> doBackup(scope, cbApiKey.isChecked()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doBackup(final int scope, final boolean includeApiKey) {
        Toast.makeText(context, "开始备份…", Toast.LENGTH_SHORT).show();
        AlertDialog progress = new DshaDialogBuilder(activity)
                .setTitle("备份中")
                .setMessage("正在打包所选数据…")
                .setCancelable(false)
                .show();

        new Thread(() -> {
            String path = BackupManager.backupToExternal(context, controller, scope, includeApiKey);
            mainHandler.post(() -> {
                progress.dismiss();
                if (path == null) {
                    new DshaDialogBuilder(activity)
                            .setTitle("备份失败")
                            .setMessage(BackupManager.lastError())
                            .setPositiveButton("关闭", null)
                            .show();
                } else {
                    new DshaDialogBuilder(activity)
                            .setTitle("备份成功（已校验）")
                            .setMessage("已备份 " + BackupScope.label(scope)
                                    + (includeApiKey ? "（已包含 API Key）" : "（未包含 API Key）")
                                    + "\n\n保存位置：\n" + path
                                    + "\n\n归档已通过条目数与大小校验。")
                            .setPositiveButton("关闭", null)
                            .show();
                }
            });
        }, "dsha-backup").start();
    }

    @Override
    public void onRestoreClick() {
        try {
            confirmRestore();
        } catch (Throwable t) {
            Toast.makeText(context, "打开恢复选项失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmRestore() {
        new DshaDialogBuilder(activity)
                .setTitle("恢复备份")
                .setMessage("选择要恢复的备份文件（Download/DSHA/ 下的 .tar.gz）。\n\n"
                        + "会覆盖当前配置/对话（恢复前会自动把现有 .dsh 挪到 .dsh.pre-restore-* 保留）。\n确定？")
                .setPositiveButton("选择文件", (d, w) -> callback.onLaunchRestorePicker())
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onRestoreSelected(Uri uri) {
        if (uri == null) return;
        Toast.makeText(context, "开始恢复…", Toast.LENGTH_SHORT).show();
        AlertDialog progress = new DshaDialogBuilder(activity)
                .setTitle("恢复中")
                .setMessage("正在解压覆盖并合并数据…")
                .setCancelable(false)
                .show();

        new Thread(() -> {
            try {
                controller.stopWeb();
                String err = BackupManager.restoreFromUri(context, controller, uri);
                mainHandler.post(() -> {
                    progress.dismiss();
                    if (err != null) {
                        new DshaDialogBuilder(activity)
                                .setTitle("恢复失败")
                                .setMessage(err)
                                .setPositiveButton("关闭", null)
                                .show();
                    } else {
                        init();
                        new DshaDialogBuilder(activity)
                                .setTitle("恢复完成")
                                .setMessage("备份已恢复（原配置已安全备份保留在 .dsh.pre-restore-*）。\n重启 Web 生效。")
                                .setPositiveButton("立即重启 Web", (d, w) -> {
                                    controller.stopWeb();
                                    controller.startWeb(status -> {});
                                })
                                .setNegativeButton("稍后手动重启", null)
                                .show();
                    }
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    progress.dismiss();
                    new DshaDialogBuilder(activity)
                            .setTitle("恢复异常")
                            .setMessage(t.getMessage())
                            .setPositiveButton("关闭", null)
                            .show();
                });
            }
        }, "dsha-restore").start();
    }
}
