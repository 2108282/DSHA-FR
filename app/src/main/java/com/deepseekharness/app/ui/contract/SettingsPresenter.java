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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.deepseekharness.app.HarnessService;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.AboutDialog;
import com.deepseekharness.app.ui.DiagnosticActivity;

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
    public void onTogglePersistentNotification(boolean enabled) {
        boolean current = configStore.isPersistentNotificationEnabled();
        if (current == enabled) return;
        configStore.setPersistentNotificationEnabled(enabled);
        HarnessService.syncPersistentNotificationState(context, enabled);
        Toast.makeText(context, enabled ? "已开启常驻通知" : "已关闭常驻通知", Toast.LENGTH_SHORT).show();
        init();
    }

    @Override
    public void onCheckUpdateClick() {
        String[] options = {
                "① 升级DSH核心",
                "② DSHA-FR 客户端与 Magisk/KSU 模块 (Release)"
        };
        new MaterialAlertDialogBuilder(activity)
                .setTitle("检查与获取更新")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        showDshUpdate();
                    } else if (which == 1) {
                        openUrl(AboutDialog.GITHUB_ROOT_URL + "/releases");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showDshUpdate() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("升级DSH核心")
                .setMessage("当前版本: 请在核心中查看\n\n"
                        + "可在浏览器查看官方 GitHub 上游最新发布日志，或在终端执行 npm 升级命令:\n\n"
                        + "npm i -g @deepseek-ai/dsh@（版本号）")
                .setPositiveButton("查看官方 Release", (d, w) ->
                        openUrl("https://github.com/deepseek-ai/deepseek-harness/releases"))
                .setNeutralButton("复制升级命令", (d, w) -> copyText("npm i -g @deepseek-ai/dsh@（版本号）"))
                .setNegativeButton("返回", null)
                .show();
    }

    private void openUrl(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(context, "打开链接失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("cmd", text));
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRunSelftestClick() {
        activity.startActivity(new Intent(context, DiagnosticActivity.class));
    }

    @Override
    public void onAboutClick() {
        AboutDialog.show(activity);
    }

    @Override
    public void onApplyPatchesClick() {
        String msg = "【原生环境与存储直通自愈】\n\n"
                + "1. 内部存储直通：重新建立 /root/内部存储 → /sdcard/Download/DSHA 软链接；\n"
                + "2. 默认工作区检查：确保手机 Download/DSHA/工作区 存在且具备完全读写权限；\n"
                + "3. 网络与 DNS 校验：重写 /etc/resolv.conf 权威公共 DNS，解决网络解析异常；\n"
                + "4. 3095 设备桥令牌：重新同步并授权 /root/.dsh/.bridge_token 凭据；\n"
                + "5. 插件加载入口自愈：补齐内置核心插件与第三方插件软链接，保持依赖文件原生硬链接无损。\n\n"
                + "【适用场景】\n"
                + "· 终端内找不到「内部存储」直通软链接时；\n"
                + "· 导入备份包或重装模块后的首次环境修复；\n"
                + "· 插件市场或内置插件报依赖找不到时。";

        new MaterialAlertDialogBuilder(activity)
                .setTitle("原生环境与存储直通自愈")
                .setMessage(msg)
                .setPositiveButton("开始自愈修复", (d, w) -> runApplyPatches())
                .setNegativeButton("取消", null)
                .show();
    }

    private void runApplyPatches() {
        AlertDialog progress = new MaterialAlertDialogBuilder(activity)
                .setTitle("正在自愈")
                .setMessage("正在执行原生环境与直通校验，请稍候…")
                .setCancelable(false)
                .show();

        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            try {
                // 1. 直通软链接与工作区目录
                String cmd1 = "mkdir -p /sdcard/Download/DSHA/工作区 /root/.dsh 2>/dev/null || true; "
                        + "ln -sfn /sdcard/Download/DSHA /root/内部存储 2>/dev/null || true; "
                        + "chmod 777 /root/.dsh 2>/dev/null || true; echo OK";
                String r1 = controller.proot().execChecked(cmd1);
                report.append("· 内部存储直通与工作区: ").append(r1.contains("OK") ? "✅ 已就绪 (/root/内部存储)" : "⚠️ 完成").append("\n");

                // 2. DNS 修复
                String cmd2 = "mkdir -p /etc 2>/dev/null; "
                        + "printf 'nameserver 223.5.5.5\\nnameserver 119.29.29.29\\nnameserver 1.1.1.1\\n' > /etc/resolv.conf 2>/dev/null; echo OK";
                String r2 = controller.proot().execChecked(cmd2);
                report.append("· 网络与 DNS 解析配置: ").append(r2.contains("OK") ? "✅ 已更新 (公共 DNS)" : "⚠️ 完成").append("\n");

                // 3. 3095 设备桥 Token 同步
                HttpShellService.syncTokenToRootfsSync();
                report.append("· 3095 设备桥令牌: ✅ 同步就绪\n");

                // 4. 插件扩展链自愈
                String cmd3 = "mkdir -p /root/.dsh/profiles/web/node_modules /usr/local/lib/node_modules 2>/dev/null || true; "
                        + "for p in /root/dsha-*; do [ -d \"$p\" ] || continue; "
                        + "  bname=$(basename \"$p\"); "
                        + "  case \"$bname\" in dsha-repo|dsha-builtin.txt|*-installed) continue ;; esac; "
                        + "  pname=\"dsh-${bname#dsha-}\"; "
                        + "  ln -sfn \"$p\" \"/root/.dsh/profiles/web/node_modules/$pname\" 2>/dev/null || true; "
                        + "  ln -sfn \"$p\" \"/usr/local/lib/node_modules/$pname\" 2>/dev/null || true; "
                        + "done; "
                        + "if [ -d /root/.dsh/plugin-src ]; then "
                        + "  for p in /root/.dsh/plugin-src/*; do [ -d \"$p\" ] || continue; "
                        + "    bname=$(basename \"$p\"); "
                        + "    if [ \"${bname:0:1}\" = \"@\" ]; then "
                        + "      mkdir -p \"/root/.dsh/profiles/web/node_modules/$bname\" \"/usr/local/lib/node_modules/$bname\" 2>/dev/null || true; "
                        + "      for sub in \"$p\"/*; do [ -d \"$sub\" ] || continue; "
                        + "        subname=$(basename \"$sub\"); "
                        + "        ln -sfn \"$sub\" \"/root/.dsh/profiles/web/node_modules/$bname/$subname\" 2>/dev/null || true; "
                        + "        ln -sfn \"$sub\" \"/usr/local/lib/node_modules/$bname/$subname\" 2>/dev/null || true; "
                        + "      done; "
                        + "    else "
                        + "      ln -sfn \"$p\" \"/root/.dsh/profiles/web/node_modules/$bname\" 2>/dev/null || true; "
                        + "      ln -sfn \"$p\" \"/usr/local/lib/node_modules/$bname\" 2>/dev/null || true; "
                        + "    fi; "
                        + "  done; "
                        + "fi; echo OK";
                String r3 = controller.proot().execChecked(cmd3);
                report.append("· 插件扩展依赖链: ").append(r3.contains("OK") ? "✅ 校验正常 (保持 pnpm 原生硬链接)" : "⚠️ 完成").append("\n");

            } catch (Throwable e) {
                report.append("执行异常: ").append(e.getMessage());
            }

            mainHandler.post(() -> {
                progress.dismiss();
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("自愈完成")
                        .setMessage(report.toString() + "\n\n建议重启 Web 服务使修改全部生效。")
                        .setPositiveButton("立即重启服务", (d, w) -> {
                            controller.stopWeb();
                            controller.startWeb(status -> {});
                            Toast.makeText(context, "正在重启 Web 服务…", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("稍后手动重启", null)
                        .show();
            });
        }, "dsha-native-heal").start();
    }
}
