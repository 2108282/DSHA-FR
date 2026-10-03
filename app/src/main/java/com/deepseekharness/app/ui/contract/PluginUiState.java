package com.deepseekharness.app.ui.contract;

import com.deepseekharness.app.core.PluginRepository;
import java.util.List;

/**
 * 插件页界面纯状态快照。
 */
public class PluginUiState {
    public final boolean isMarketTab;
    public final boolean isBusy;
    public final String statusMessage;

    public final boolean isInstallButtonEnabled;
    public final String linkHintText;

    public final String sortButtonText;
    public final String countText;
    public final String emptyText;
    public final boolean isEmptyVisible;
    public final List<PluginRepository.Item> displayItems;

    public PluginUiState(
            boolean isMarketTab,
            boolean isBusy,
            String statusMessage,
            boolean isInstallButtonEnabled,
            String linkHintText,
            String sortButtonText,
            String countText,
            String emptyText,
            boolean isEmptyVisible,
            List<PluginRepository.Item> displayItems) {
        this.isMarketTab = isMarketTab;
        this.isBusy = isBusy;
        this.statusMessage = statusMessage;
        this.isInstallButtonEnabled = isInstallButtonEnabled;
        this.linkHintText = linkHintText;
        this.sortButtonText = sortButtonText;
        this.countText = countText;
        this.emptyText = emptyText;
        this.isEmptyVisible = isEmptyVisible;
        this.displayItems = displayItems;
    }
}
