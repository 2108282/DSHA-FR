package com.deepseekharness.app.ui.contract;

import android.net.Uri;

public interface WorkspaceActions {
    void onBackClick();
    void onBackupClick();
    void onRestoreClick();
    void onRestoreSelected(Uri uri);
    void onApplyWorkdir(String path);
}
