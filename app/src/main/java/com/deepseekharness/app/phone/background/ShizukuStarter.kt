package com.deepseekharness.app.phone.background

import android.content.Context
import com.deepseekharness.app.R
import com.deepseekharness.app.i18n.AppStrings

class ShizukuStarter(private val context: Context, private val onChanged: () -> Unit) {
    fun ensureListeners() {}
    fun status(): ShizukuStatus = ShizukuStatus(
        provider = null,
        installed = false,
        appInstalled = false,
        running = false,
        granted = false,
    )
    fun reason(): String? = AppStrings.get(context, R.string.background_shizuku_missing)
    fun requestPermission(onGranted: (() -> Unit)? = null): Boolean = false
    fun connect(): PrivilegedChannel? = null
    fun disconnect() {}
}
