package com.deepseekharness.app.ui.contract;

import android.net.Uri;
import com.deepseekharness.app.core.WorkspaceModel;

public interface WorkspaceActions {
    void onBackClick();
    void onBackupClick();
    void onRestoreClick();
    void onRestoreSelected(Uri uri);
    void onApplyWorkdir(String path);
    void onRefresh();
    void onSwitchWorkspace(WorkspaceModel model);
    void onCreateWorkspaceClick();
    void onCreateWorkspaceConfirm(String title, boolean isNativeLinux);
    void onDeleteWorkspace(WorkspaceModel model);
    void onRenameWorkspace(WorkspaceModel model, String newTitle);
}
