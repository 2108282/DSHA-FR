package com.deepseekharness.app.phone.background

/** The shared Shizuku API, served by the Shizuku app or by Sui; `installed` (the old field) means either is there. */
data class ShizukuStatus(
    val installed: Boolean,
    val running: Boolean,
    val granted: Boolean,
    val provider: String? = null,
    val appInstalled: Boolean = installed,
) {
    fun map(): Map<String, Any?> = mapOf(
        "installed" to installed, "running" to running, "granted" to granted,
        "provider" to provider, "appInstalled" to appInstalled,
    )

    companion object {
        const val SHIZUKU = "shizuku"
        const val SUI = "sui"

        fun fromFacts(
            appInstalled: Boolean,
            suiInitialized: Boolean,
            binderAlive: Boolean,
            permissionGranted: Boolean,
        ): ShizukuStatus = ShizukuStatus(
            installed = appInstalled || suiInitialized || binderAlive,
            running = binderAlive,
            granted = binderAlive && permissionGranted,
            provider = when {
                suiInitialized -> SUI
                appInstalled || binderAlive -> SHIZUKU
                else -> null
            },
            appInstalled = appInstalled,
        )
    }
}
