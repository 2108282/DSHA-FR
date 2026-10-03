package com.deepseekharness.app.ui.contract;

public class DiagnosticUiState {
    public final String reportText;
    public final String statusText;
    public final boolean isRepairEnabled;

    public DiagnosticUiState(String reportText, String statusText, boolean isRepairEnabled) {
        this.reportText = reportText;
        this.statusText = statusText;
        this.isRepairEnabled = isRepairEnabled;
    }
}
