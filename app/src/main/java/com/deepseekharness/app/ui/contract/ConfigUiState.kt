package com.deepseekharness.app.ui.contract

data class ConfigUiState(
    val port: String = "3080",
    val taskset: String = "",
    val isConfirmShell: Boolean = true,
    val isLanMode: Boolean = false,
    val isOverlayStream: Boolean = false,
    val isCapSensors: Boolean = false,
    val isCapLocation: Boolean = false,
    val isAsrContinuous: Boolean = false,
    val allFilesStatusText: String = "",
    val a11yStatusText: String = "",
    val asrStatusText: String = "当前状态：未检测"
)
