package com.deepseekharness.app.ui.contract;

public interface ConfigActions {
    void onSaveConfig(String port, String taskset, boolean confirmShell, boolean lanMode,
                      boolean overlayStream, boolean capSensors, boolean capLocation);
    void onBack();
    void onOpenWorkspace();
    void onOpenOverlayStyle();
    void onOpenAllFilesSettings();
    void onOpenBatteryOptimization();
    void onOpenA11ySettings();
    void onCheckAsrStatus();
    void onFixAsrConfig();
    void onToggleAsrContinuous(boolean enabled);
}
