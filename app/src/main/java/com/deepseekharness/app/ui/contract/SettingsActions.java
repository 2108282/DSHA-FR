package com.deepseekharness.app.ui.contract;

public interface SettingsActions {
    void onOpenTab(int index);
    void onTogglePersistentNotification(boolean enabled);
    void onCheckUpdateClick();
    void onRunSelftestClick();
    void onApplyPatchesClick();
    void onAboutClick();
}
