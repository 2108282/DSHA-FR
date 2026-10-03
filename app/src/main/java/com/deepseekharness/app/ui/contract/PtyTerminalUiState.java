package com.deepseekharness.app.ui.contract;

public class PtyTerminalUiState {
    public final String title;
    public final boolean isCtrlActive;
    public final boolean isAltActive;
    public final int fontSp;

    public PtyTerminalUiState(String title, boolean isCtrlActive, boolean isAltActive, int fontSp) {
        this.title = title;
        this.isCtrlActive = isCtrlActive;
        this.isAltActive = isAltActive;
        this.fontSp = fontSp;
    }
}
