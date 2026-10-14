package com.deepseekharness.app.ui.contract;

import com.deepseekharness.app.core.WorkspaceModel;
import java.util.Collections;
import java.util.List;

public class WorkspaceUiState {
    public final String workdirPath;
    public final String rootStatusText;
    public final List<WorkspaceModel> workspaces;
    public final boolean isLoading;

    public WorkspaceUiState(String workdirPath, String rootStatusText) {
        this(workdirPath, rootStatusText, Collections.emptyList(), false);
    }

    public WorkspaceUiState(String workdirPath, String rootStatusText,
                            List<WorkspaceModel> workspaces, boolean isLoading) {
        this.workdirPath = workdirPath != null ? workdirPath : "";
        this.rootStatusText = rootStatusText != null ? rootStatusText : "";
        this.workspaces = workspaces != null ? workspaces : Collections.emptyList();
        this.isLoading = isLoading;
    }
}
