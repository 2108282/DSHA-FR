package com.deepseekharness.app.phone.background

/**
 * WRITE_SECURE_SETTINGS for 自动恢复无障碍 ([com.deepseekharness.app.phone.a11y.A11yKeeper]):
 * which one-tap way to it the shell-uid engine offers right now, and the command for granting it
 * by hand (its `pm grant` part also runs as is in a root shell).
 */
object SecureSettingsGrant {
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    // `a11yKeeper.path` in the `dsh/phone` status.
    const val GRANTED = "granted"

    /** Shizuku runs and has let us in: the tap grants. */
    const val SHIZUKU = "shizuku"

    /** Self-ADB is paired, and wireless debugging isn't known to be off: the tap connects and grants. */
    const val ADB = "adb"

    /** Shizuku runs but hasn't let us in: the tap asks it first, then grants. */
    const val SHIZUKU_PERMISSION = "shizukuPermission"

    // Names exposed only by background.privileged v1; legacy a11yKeeper.path stays unchanged.
    const val SUI = "sui"
    const val SUI_PERMISSION = "suiPermission"
    const val ROOT = "root"

    /** Neither is set up (不打扰模式's page sets up both). */
    const val SETUP = "setup"

    fun path(granted: Boolean, shizuku: ShizukuStatus, adb: AdbStatus): String = when {
        granted -> GRANTED
        shizuku.running && shizuku.granted -> SHIZUKU
        adb.paired && adb.wirelessDebugging != false -> ADB
        shizuku.running -> SHIZUKU_PERMISSION
        else -> SETUP
    }

    fun command(packageName: String): String = "adb shell pm grant $packageName $PERMISSION"

    /** `background.privileged.grantPath`: a chosen root never falls back to another provider. */
    fun privilegedPath(granted: Boolean, shizuku: ShizukuStatus, adb: AdbStatus, rootSelected: Boolean, rootConnected: Boolean): String {
        val legacy = path(granted, shizuku, adb)
        return when {
            granted -> GRANTED
            rootSelected -> if (rootConnected) ROOT else SETUP
            shizuku.provider != ShizukuStatus.SUI -> legacy
            legacy == SHIZUKU -> SUI
            legacy == SHIZUKU_PERMISSION -> SUI_PERMISSION
            else -> legacy
        }
    }
}
