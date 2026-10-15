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
import com.deepseekharness.app.util.ToastHelper;

import androidx.appcompat.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.ui.ModernBadgeView;
import com.deepseekharness.app.ui.MonetEngine;

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
            ToastHelper.makeText(context, "无法打开浏览器，请在浏览器中访问 https://dsha.cc/", Toast.LENGTH_SHORT).show();
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
            ToastHelper.makeText(context, "剪贴板没有链接", Toast.LENGTH_SHORT).show();
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
            ToastHelper.makeText(context, error.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onImportClick(boolean alternative) {
        if (repository.isBusy()) {
            ToastHelper.makeText(context, "请等待当前插件操作完成后再导入", Toast.LENGTH_SHORT).show();
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
            ToastHelper.makeText(context, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            repository.selectionMessage("无法打开文件选择器，请使用备用入口：" + error.getClass().getSimpleName());
        }
    }

    @Override
    public void onExportClick() {
        if (repoState == null || repository.isBusy()) return;
        List<PluginRepository.Item> exportableItems = new ArrayList<>();
        for (PluginRepository.Item item : repoState.items) {
            if (item.exportable) exportableItems.add(item);
        }
        if (exportableItems.isEmpty()) {
            ToastHelper.makeText(context, "没有可导出的插件", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_plugin_export, null);
        MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(activity);
        MonetEngine.applyToViewTree(dialogView, palette);

        TextView dialogExportSub = dialogView.findViewById(R.id.dialogExportSub);
        TextView dialogExportToggleAll = dialogView.findViewById(R.id.dialogExportToggleAll);
        LinearLayout listContainer = dialogView.findViewById(R.id.dialogExportListContainer);
        Button btnCancel = dialogView.findViewById(R.id.btnDialogCancel);
        Button btnConfirm = dialogView.findViewById(R.id.btnDialogConfirm);

        final int totalCount = exportableItems.size();
        final boolean[] checked = new boolean[totalCount];
        for (int i = 0; i < totalCount; i++) {
            checked[i] = true;
        }

        final float density = activity.getResources().getDisplayMetrics().density;
        final int dp8 = (int) (8 * density + 0.5f);
        final int dp10 = (int) (10 * density + 0.5f);
        final int dp1 = Math.max(1, (int) (1 * density + 0.5f));
        final int badgeHeight = (int) (20 * density + 0.5f);

        final List<CheckBox> checkBoxes = new ArrayList<>();
        if (listContainer != null) {
            listContainer.removeAllViews();
            for (int i = 0; i < totalCount; i++) {
                final int index = i;
                PluginRepository.Item item = exportableItems.get(i);

                if (i > 0) {
                    View divider = new View(activity);
                    divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp1));
                    divider.setBackgroundColor(activity.getColor(R.color.line_soft));
                    listContainer.addView(divider);
                }

                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp8, dp10, dp8, dp10);

                android.util.TypedValue outValue = new android.util.TypedValue();
                activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                row.setBackgroundResource(outValue.resourceId);
                row.setClickable(true);
                row.setFocusable(true);

                CheckBox cb = new CheckBox(activity);
                cb.setClickable(false);
                cb.setFocusable(false);
                cb.setChecked(checked[i]);
                if (palette.isMonetActive) {
                    cb.setButtonTintList(ColorStateList.valueOf(palette.primaryColor));
                } else {
                    cb.setButtonTintList(ColorStateList.valueOf(activity.getColor(R.color.primary)));
                }
                checkBoxes.add(cb);

                LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cbLp.setMarginEnd(dp8);
                row.addView(cb, cbLp);

                TextView nameView = new TextView(activity);
                nameView.setText(item.name);
                nameView.setTextColor(activity.getColor(R.color.text));
                nameView.setTextSize(14f);
                nameView.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(nameView, nameLp);

                if (item.version != null && !item.version.isEmpty()) {
                    ModernBadgeView badge = new ModernBadgeView(activity);
                    badge.setBadge(true, "v" + item.version);
                    LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, badgeHeight);
                    badgeLp.setMarginStart(dp8);
                    row.addView(badge, badgeLp);
                }

                row.setOnClickListener(v -> {
                    checked[index] = !checked[index];
                    cb.setChecked(checked[index]);
                    updateExportDialogState(checked, totalCount, dialogExportSub, dialogExportToggleAll, btnConfirm);
                });

                listContainer.addView(row);
            }
        }

        updateExportDialogState(checked, totalCount, dialogExportSub, dialogExportToggleAll, btnConfirm);

        if (dialogExportToggleAll != null) {
            if (palette.isMonetActive) {
                dialogExportToggleAll.setTextColor(palette.primaryColor);
            }
            dialogExportToggleAll.setOnClickListener(v -> {
                int sel = 0;
                for (boolean c : checked) {
                    if (c) sel++;
                }
                boolean target = (sel != totalCount);
                for (int i = 0; i < totalCount; i++) {
                    checked[i] = target;
                    if (i < checkBoxes.size()) {
                        checkBoxes.get(i).setChecked(target);
                    }
                }
                updateExportDialogState(checked, totalCount, dialogExportSub, dialogExportToggleAll, btnConfirm);
            });
        }

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.Dialog_DSHA_Alert)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnConfirm != null) {
            if (palette.isMonetActive) {
                btnConfirm.setBackgroundTintList(ColorStateList.valueOf(palette.primaryColor));
            }
            btnConfirm.setOnClickListener(v -> {
                ArrayList<String> selected = new ArrayList<>();
                for (int i = 0; i < totalCount; i++) {
                    if (checked[i]) selected.add(exportableItems.get(i).name);
                }
                if (!selected.isEmpty()) {
                    dialog.dismiss();
                    String name = selected.size() == 1 ? selected.get(0).replaceAll("[^A-Za-z0-9._-]", "_") : "DSHA-plugins";
                    String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                    callback.onLaunchExport(name + "-" + stamp + ".tar.gz", selected);
                }
            });
        }

        dialog.show();
    }

    private void updateExportDialogState(boolean[] checked, int total, TextView subView, TextView toggleAllView, Button confirmBtn) {
        int selectedCount = 0;
        for (boolean c : checked) {
            if (c) selectedCount++;
        }
        if (subView != null) {
            subView.setText("已选择 " + selectedCount + " / " + total + " 个插件：");
        }
        if (toggleAllView != null) {
            toggleAllView.setText(selectedCount == total ? "取消全选" : "全选");
        }
        if (confirmBtn != null) {
            confirmBtn.setEnabled(selectedCount > 0);
            confirmBtn.setAlpha(selectedCount > 0 ? 1.0f : 0.45f);
        }
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
            android.view.View dialogView = android.view.LayoutInflater.from(activity).inflate(com.deepseekharness.app.R.layout.dialog_plugin_status, null);
            MonetEngine.PaletteInfo palette = MonetEngine.resolveCurrentPalette(activity);
            MonetEngine.applyToViewTree(dialogView, palette);

            android.widget.TextView msgView = dialogView.findViewById(com.deepseekharness.app.R.id.dialogStatusMessage);
            if (msgView != null) msgView.setText(repoState.message);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setView(dialogView)
                    .setCancelable(true)
                    .create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
            android.view.View btnClose = dialogView.findViewById(com.deepseekharness.app.R.id.btnDialogStatusClose);
            if (btnClose != null) {
                if (palette.isMonetActive) {
                    btnClose.setBackgroundTintList(ColorStateList.valueOf(palette.primaryColor));
                }
                btnClose.setOnClickListener(v -> dialog.dismiss());
            }
            dialog.show();
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
            ToastHelper.makeText(context, label + " 已复制", Toast.LENGTH_SHORT).show();
        }
    }
}
