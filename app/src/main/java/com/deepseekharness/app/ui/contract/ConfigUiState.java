package com.deepseekharness.app.ui.contract;

public class ConfigUiState {
    public final String port;
    public final String taskset;
    public final boolean isConfirmShell;
    public final boolean isOverlayStream;
    public final boolean isCapSensors;
    public final boolean isCapLocation;
    public final boolean isAsrContinuous;
    public final String allFilesStatusText;
    public final String a11yStatusText;
    public final String asrStatusText;
    public final String rootStatusText;

    public ConfigUiState(String port, String taskset, boolean isConfirmShell,
                         boolean isOverlayStream, boolean isCapSensors, boolean isCapLocation,
                         boolean isAsrContinuous, String allFilesStatusText,
                         String a11yStatusText, String asrStatusText, String rootStatusText) {
        this.port = port;
        this.taskset = taskset;
        this.isConfirmShell = isConfirmShell;
        this.isOverlayStream = isOverlayStream;
        this.isCapSensors = isCapSensors;
        this.isCapLocation = isCapLocation;
        this.isAsrContinuous = isAsrContinuous;
        this.allFilesStatusText = allFilesStatusText;
        this.a11yStatusText = a11yStatusText;
        this.asrStatusText = asrStatusText;
        this.rootStatusText = rootStatusText;
    }
}
