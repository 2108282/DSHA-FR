package com.deepseekharness.app.ui.contract;

public class SettingsUiState {
    public final String versionText;
    public final String updateSubText;
    public final boolean isPersistentNotificationEnabled;

    public SettingsUiState(String versionText, String updateSubText, boolean isPersistentNotificationEnabled) {
        this.versionText = versionText;
        this.updateSubText = updateSubText;
        this.isPersistentNotificationEnabled = isPersistentNotificationEnabled;
    }
}
