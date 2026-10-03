package com.deepseekharness.app.ui.contract;

/**
 * 启动页纯状态快照：单一可信数据源（Single Source of Truth）。
 */
public class LaunchUiState {
    public final String runStateTitle;
    public final String statusDescription;
    public final boolean isBusy;

    public final String primaryActionText;
    public final boolean isPrimaryActionEnabled;

    public final String restartText;
    public final boolean isRestartEnabled;

    public final String stopText;
    public final boolean isStopEnabled;

    public final boolean isLanCardVisible;
    public final String lanAddressText;
    public final boolean isSheetButtonVisible;

    public final String logContent;
    public final long logRevision;

    public LaunchUiState(
            String runStateTitle,
            String statusDescription,
            boolean isBusy,
            String primaryActionText,
            boolean isPrimaryActionEnabled,
            String restartText,
            boolean isRestartEnabled,
            String stopText,
            boolean isStopEnabled,
            boolean isLanCardVisible,
            String lanAddressText,
            boolean isSheetButtonVisible,
            String logContent,
            long logRevision) {
        this.runStateTitle = runStateTitle;
        this.statusDescription = statusDescription;
        this.isBusy = isBusy;
        this.primaryActionText = primaryActionText;
        this.isPrimaryActionEnabled = isPrimaryActionEnabled;
        this.restartText = restartText;
        this.isRestartEnabled = isRestartEnabled;
        this.stopText = stopText;
        this.isStopEnabled = isStopEnabled;
        this.isLanCardVisible = isLanCardVisible;
        this.lanAddressText = lanAddressText;
        this.isSheetButtonVisible = isSheetButtonVisible;
        this.logContent = logContent;
        this.logRevision = logRevision;
    }
}
