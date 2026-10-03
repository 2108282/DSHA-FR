package com.deepseekharness.app.ui.contract;

public interface PtyTerminalActions {
    void onBumpFont(int delta);
    void onSwitchToSimple();
    void onToggleModifier(String which);
    void onSendKey(String seq);
}
