package com.deepseekharness.app.ui.contract;

public class WorkspaceUiState {
    public final String workdirPath;
    public final String rootStatusText;

    public WorkspaceUiState(String workdirPath, String rootStatusText) {
        this.workdirPath = workdirPath;
        this.rootStatusText = rootStatusText;
    }
}
