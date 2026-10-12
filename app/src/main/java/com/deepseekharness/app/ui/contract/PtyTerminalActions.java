package com.deepseekharness.app.ui.contract;

public interface PtyTerminalActions {
    void onBumpFont(int delta);
    void onResetSession();
    void onSwitchToSimple();
    void onToggleModifier(String which);
    void onSendKey(String seq);
    void onSelectAll();
    void onCopyAction();
}
