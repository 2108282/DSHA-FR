package com.deepseekharness.app.ui.contract;

public class ConfigUiState {
    public final String port;
    public final String taskset;
    public final boolean isConfirmShell;
    public final boolean isLanMode;
    public final boolean isOverlayStream;
    public final boolean isCapSensors;
    public final boolean isCapLocation;
    public final boolean isAsrContinuous;
    public final String allFilesStatusText;
    public final String a11yStatusText;
    public final String asrStatusText;

    public ConfigUiState(String port, String taskset, boolean isConfirmShell, boolean isLanMode,
                         boolean isOverlayStream, boolean isCapSensors, boolean isCapLocation,
                         boolean isAsrContinuous, String allFilesStatusText,
                         String a11yStatusText, String asrStatusText) {
        this.port = port;
        this.taskset = taskset;
        this.isConfirmShell = isConfirmShell;
        this.isLanMode = isLanMode;
        this.isOverlayStream = isOverlayStream;
        this.isCapSensors = isCapSensors;
        this.isCapLocation = isCapLocation;
        this.isAsrContinuous = isAsrContinuous;
        this.allFilesStatusText = allFilesStatusText;
        this.a11yStatusText = a11yStatusText;
        this.asrStatusText = asrStatusText;
    }
}
