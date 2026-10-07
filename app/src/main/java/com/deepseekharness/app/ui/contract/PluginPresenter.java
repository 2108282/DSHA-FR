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

import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.ui.PluginFilePicker;
import com.deepseekharness.app.util.PluginSource;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 插件页业务逻辑控制器：处理过滤、排序、批量导出、单项动作分发与单一状态派发。
 */
public class PluginPresenter implements PluginActions {

    public interface ViewCallback {
        void onRender(PluginUiState state);
        void onLaunchImport(Intent intent);
        void onLaunchExport(String fileName, ArrayList<String> selectedPlugins);
        void onShowInstallPreview(PluginRepository.Preview preview);
        void onScrollToTop();
        void onSetLinkInputText(String text);
    }

    private final Activity activity;
    private final Context context;
    private final PluginRepository repository;
    private final ViewCallback callback;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private boolean isMarket = true;
    private boolean isEnabledFirst = false;
    private boolean hideBuiltin = false;
    private String currentSearchQuery = "";
    private String currentLinkInput = "";
    private PluginRepository.State repoState;

    public PluginPresenter(Activity activity, PluginRepository repository, ViewCallback callback) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.repository = repository;
        this.callback = callback;
    }

    public void updateRepoState(PluginRepository.State state) {
        this.repoState = state;
        recalculateState();
    }

    public void setMarket(boolean market) {
        this.isMarket = market;
        recalculateState();
    }

    public boolean isHideBuiltin() {
        return hideBuiltin;
    }

    public boolean isMarket() {
        return isMarket;
    }

    public boolean isEnabledFirst() {
        return isEnabledFirst;
    }

    public void setEnabledFirst(boolean enabledFirst) {
        this.isEnabledFirst = enabledFirst;
        recalculateState();
    }

    /** 核心计算：根据仓储状态、过滤条件与排序生成纯净状态快照 */
    public void recalculateState() {
        boolean busy = repository.isBusy() || (repoState != null && repoState.busy);
        String rawMsg = repoState != null ? repoState.message : "";
        String statusMsg;
        if (rawMsg == null || rawMsg.trim().isEmpty()
                || rawMsg.contains("选择链接安装或导入")
                || rawMsg.contains("插件状态已同步")) {
            statusMsg = "插件管理功能正常";
        } else {
            statusMsg = rawMsg.trim();
        }

        // 1. 链接识别与安装按钮可用态
        boolean linkValid = false;
        String linkHint = "";
        if (currentLinkInput.trim().isEmpty()) {
            linkHint = "支持仓库、分支/子目录、Release 下载和压缩包直链";
        } else {
            try {
                PluginSource source = PluginSource.parse(currentLinkInput);
                linkHint = "已识别：" + source.description();
                linkValid = true;
            } catch (IllegalArgumentException error) {
                linkHint = error.getMessage();
            }
        }
        boolean isInstallEnabled = linkValid && !busy;

        // 2. 插件列表过滤与排序
        List<PluginRepository.Item> displayItems = new ArrayList<>();
        if (repoState != null && repoState.items != null) {
            String query = currentSearchQuery.trim().toLowerCase(Locale.ROOT);
            for (PluginRepository.Item item : repoState.items) {
                if (hideBuiltin && (item.builtin || item.official)) continue;
                if (!query.isEmpty() && !(item.name + " " + item.description).toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                displayItems.add(item);
            }
            Comparator<PluginRepository.Item> comparator = Comparator.comparing(it -> it.name.toLowerCase(Locale.ROOT));
            if (isEnabledFirst) {
                comparator = Comparator.<PluginRepository.Item, Boolean>comparing(it -> !it.enabled)
                        .thenComparing(comparator);
            }
            displayItems.sort(comparator);
        }

        String sortText = isEnabledFirst ? "已启用优先" : "名称排序";
        String countText = "共 " + displayItems.size() + " 个插件";
        boolean isEmptyVisible = !isMarket && displayItems.isEmpty();
        String emptyText = busy ? "正在读取插件…" : "没有符合条件的插件";

        PluginUiState state = new PluginUiState(
                isMarket,
                busy,
                statusMsg,
                isInstallEnabled,
                linkHint,
                sortText,
                countText,
                emptyText,
                isEmptyVisible,
                displayItems
        );

        uiHandler.post(() -> callback.onRender(state));
    }

    @Override
    public void onSelectTab(boolean showMarket) {
        this.isMarket = showMarket;
        recalculateState();
        callback.onScrollToTop();
    }

    @Override
    public void onRefreshClick() {
        repository.refresh();
    }

    @Override
    public void onWebsiteClick() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://dsha.cc/"))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (RuntimeException error) {
            Toast.makeText(context, "无法打开浏览器，请在浏览器中访问 https://dsha.cc/", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onLinkChanged(String link) {
        this.currentLinkInput = link != null ? link : "";
        recalculateState();
    }

    @Override
    public void onPasteLinkClick() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(context, "剪贴板没有链接", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(context);
        if (text != null) {
            callback.onSetLinkInputText(text.toString());
        }
    }

    @Override
    public void onInstallLinkClick() {
        if (repository.isBusy()) return;
        try {
            repository.install(PluginSource.parse(currentLinkInput));
        } catch (IllegalArgumentException error) {
            Toast.makeText(context, error.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onImportClick(boolean alternative) {
        if (repository.isBusy()) {
            Toast.makeText(context, "请等待当前插件操作完成后再导入", Toast.LENGTH_SHORT).show();
            return;
        }
        repository.selectionMessage("请选择插件压缩包；文件选择器无法返回时，可使用「其他文件选择器」。");
        try {
            callback.onLaunchImport(PluginFilePicker.intent(context, alternative));
        } catch (android.content.ActivityNotFoundException error) {
            if (!alternative) {
                onImportClick(true);
                return;
            }
            repository.selectionMessage("未找到可用的文件选择器，请启用系统「文件」应用后重试。");
            Toast.makeText(context, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            repository.selectionMessage("无法打开文件选择器，请使用备用入口：" + error.getClass().getSimpleName());
        }
    }

    @Override
    public void onExportClick() {
        if (repoState == null || repository.isBusy()) return;
        List<String> names = new ArrayList<>();
        for (PluginRepository.Item item : repoState.items) {
            if (item.exportable) names.add(item.name);
        }
        if (names.isEmpty()) {
            Toast.makeText(context, "没有可导出的插件", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean[] checked = new boolean[names.size()];
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("选择要导出的插件")
                .setMultiChoiceItems(names.toArray(new String[0]), checked, (d, which, value) -> {
                    checked[which] = value;
                    boolean any = false;
                    for (boolean selected : checked) any |= selected;
                    ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(any);
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("选择保存位置", (d, which) -> {
                    ArrayList<String> selected = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) selected.add(names.get(i));
                    }
                    if (!selected.isEmpty()) {
                        String name = selected.size() == 1 ? selected.get(0).replaceAll("[^A-Za-z0-9._-]", "_") : "DSHA-plugins";
                        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                        callback.onLaunchExport(name + "-" + stamp + ".tar.gz", selected);
                    }
                }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false));
        dialog.show();
    }

    @Override
    public void onSearchQueryChanged(String query) {
        this.currentSearchQuery = query != null ? query : "";
        recalculateState();
    }

    @Override
    public void onHideBuiltinChanged(boolean hide) {
        this.hideBuiltin = hide;
        recalculateState();
    }

    @Override
    public void onToggleSortClick() {
        this.isEnabledFirst = !isEnabledFirst;
        recalculateState();
    }

    @Override
    public void onStatusMessageClick() {
        if (repoState != null && !repoState.message.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("插件操作结果")
                    .setMessage(repoState.message)
                    .setPositiveButton("关闭", null)
                    .show();
        }
    }

    @Override
    public void onToggleItem(PluginRepository.Item item, boolean enabled) {
        if (repository.isBusy()) {
            recalculateState();
            return;
        }
        if (item.official && !enabled) {
            new AlertDialog.Builder(activity)
                    .setTitle("禁用官方核心？")
                    .setMessage(item.name + " 是 Web 运行所需的核心，禁用后页面可能无法启动。")
                    .setPositiveButton("禁用", (d, which) -> repository.setEnabled(item, false))
                    .setNegativeButton("取消", null)
                    .setOnDismissListener(d -> recalculateState())
                    .show();
        } else {
            repository.setEnabled(item, enabled);
        }
    }

    @Override
    public void onItemActionClick(PluginRepository.Item item) {
        List<String> actions = new ArrayList<>();
        actions.add("复制插件名称");
        if (!item.source.isEmpty()) actions.add("复制来源链接");
        if (item.exportable) actions.add("导出插件包");
        if (!item.official && (!item.source.isEmpty() || item.builtin)) actions.add("检查插件更新");
        if (item.updateAvailable) actions.add("更新至 " + item.latestVersion);
        if (!item.rollbackVersion.isEmpty()) {
            actions.add(item.builtin ? "恢复至 " + item.rollbackVersion : "回退至 " + item.rollbackVersion);
        }
        if (item.deletable) actions.add("删除插件");

        new AlertDialog.Builder(activity)
                .setTitle(item.name)
                .setItems(actions.toArray(new String[0]), (d, which) -> {
                    String action = actions.get(which);
                    if ("复制插件名称".equals(action)) {
                        copyText("插件名称", item.name);
                    } else if ("复制来源链接".equals(action)) {
                        copyText("来源链接", item.source);
                    } else if ("导出插件包".equals(action)) {
                        ArrayList<String> selected = new ArrayList<>();
                        selected.add(item.name);
                        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                        callback.onLaunchExport(item.name.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + stamp + ".tar.gz", selected);
                    } else if ("检查插件更新".equals(action)) {
                        repository.checkUpdates(item);
                    } else if (action.startsWith("更新至 ")) {
                        repository.prepareUpdate(item);
                    } else if (action.startsWith("回退至 ") || action.startsWith("恢复至 ")) {
                        new AlertDialog.Builder(activity)
                                .setTitle(item.builtin ? "恢复预装版本？" : "回退插件？")
                                .setMessage(item.name + "：" + item.version + " → " + item.rollbackVersion
                                        + (item.builtin ? "\n恢复为底座预装内置版本，当前启用状态保留；重启 Web 生效。"
                                                        : "\n只恢复插件文件，当前启用状态和对话数据保留；重启 Web 生效。"))
                                .setNegativeButton("取消", null)
                                .setPositiveButton(item.builtin ? "恢复" : "回退", (c, b) -> repository.rollback(item))
                                .show();
                    } else if ("删除插件".equals(action)) {
                        new AlertDialog.Builder(activity)
                                .setTitle("删除 " + item.name + "？")
                                .setMessage("插件文件将被移除，相关配置和对话数据保留；重启 Web 生效。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除", (c, b) -> repository.delete(item))
                                .show();
                    }
                }).show();
    }

    private void copyText(String label, String content) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, content));
            Toast.makeText(context, label + " 已复制", Toast.LENGTH_SHORT).show();
        }
    }
}
