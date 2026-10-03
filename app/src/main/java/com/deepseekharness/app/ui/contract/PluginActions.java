package com.deepseekharness.app.ui.contract;

import com.deepseekharness.app.core.PluginRepository;

/**
 * 插件页用户交互契约：所有按钮及列表条目操作行为定义。
 */
public interface PluginActions {
    void onSelectTab(boolean showMarket);
    void onRefreshClick();
    void onWebsiteClick();
    void onLinkChanged(String link);
    void onPasteLinkClick();
    void onInstallLinkClick();
    void onImportClick(boolean alternative);
    void onExportClick();
    void onCheckUpdatesClick();
    void onSearchQueryChanged(String query);
    void onHideBuiltinChanged(boolean hide);
    void onToggleSortClick();
    void onStatusMessageClick();
    void onToggleItem(PluginRepository.Item item, boolean enabled);
    void onItemActionClick(PluginRepository.Item item);
}
