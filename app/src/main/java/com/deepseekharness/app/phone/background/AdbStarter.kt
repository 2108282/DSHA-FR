package com.deepseekharness.app.phone.background

import android.content.Context
import com.deepseekharness.app.R
import com.deepseekharness.app.i18n.AppStrings

data class AdbStatus(
    val wirelessDebugging: Boolean? = false,
    val paired: Boolean = false,
    val connected: Boolean = false,
) {
    fun map(): Map<String, Any?> =
        mapOf("wirelessDebugging" to wirelessDebugging, "paired" to paired, "connected" to connected)
}

class AdbStarter(private val context: Context, private val onChanged: () -> Unit) {
    fun status(): AdbStatus = AdbStatus()
    fun reason(): String? = AppStrings.get(context, R.string.background_adb_not_paired)
    fun startPairing(): Map<String, Any?> = mapOf("ok" to false, "reason" to reason())
    fun pair(code: String, port: Int? = null): Map<String, Any?> = mapOf("ok" to false, "reason" to reason())
    fun connect(port: Int? = null): PrivilegedChannel? = null
    fun disconnect() {}
}
