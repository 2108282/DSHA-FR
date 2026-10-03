package com.deepseekharness.app.ui.contract;

public class UpdateUiState {
    public final String statusMessage;
    public final String notesText;
    public final boolean isBusy;
    public final boolean isIndeterminate;
    public final int progressPercent;
    public final String bytesText;
    public final boolean isCheckEnabled;
    public final boolean isDownloadEnabled;
    public final boolean isInstallEnabled;

    public UpdateUiState(
            String statusMessage,
            String notesText,
            boolean isBusy,
            boolean isIndeterminate,
            int progressPercent,
            String bytesText,
            boolean isCheckEnabled,
            boolean isDownloadEnabled,
            boolean isInstallEnabled) {
        this.statusMessage = statusMessage;
        this.notesText = notesText;
        this.isBusy = isBusy;
        this.isIndeterminate = isIndeterminate;
        this.progressPercent = progressPercent;
        this.bytesText = bytesText;
        this.isCheckEnabled = isCheckEnabled;
        this.isDownloadEnabled = isDownloadEnabled;
        this.isInstallEnabled = isInstallEnabled;
    }
}
