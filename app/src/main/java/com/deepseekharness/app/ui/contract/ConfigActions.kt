package com.deepseekharness.app.ui.contract

sealed interface ConfigAction {
    data class SaveConfig(
        val port: String,
        val taskset: String,
        val confirmShell: Boolean,
        val lanMode: Boolean,
        val overlayStream: Boolean,
        val capSensors: Boolean,
        val capLocation: Boolean
    ) : ConfigAction

    object Back : ConfigAction
    object OpenWorkspace : ConfigAction
    object OpenOverlayStyle : ConfigAction
    object OpenAllFilesSettings : ConfigAction
    object OpenBatteryOptimization : ConfigAction
    object OpenA11ySettings : ConfigAction
    object CheckAsrStatus : ConfigAction
    object FixAsrConfig : ConfigAction
    data class ToggleAsrContinuous(val enabled: Boolean) : ConfigAction
}
