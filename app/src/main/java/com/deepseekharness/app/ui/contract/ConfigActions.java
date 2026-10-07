package com.deepseekharness.app.ui.contract;

public interface ConfigActions {
    void onSaveTaskset(String taskset);
    void onToggleConfirmShell(boolean enabled);
    void onToggleOverlayStream(boolean enabled);
    void onToggleSensors(boolean enabled);
    void onToggleLocation(boolean enabled);
    void onBack();
    void onOpenOverlayStyle();
    void onOpenAllFilesSettings();
    void onOpenBatteryOptimization();
    void onOpenA11ySettings();
    void onCheckRootClick();
    void onCheckAsrStatus();
    void onFixAsrConfig();
    void onToggleAsrContinuous(boolean enabled);
}
