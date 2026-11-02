package com.deepseekharness.app.phone.background

/** The shell commands the ADB and root paths run: fixed text plus the APK paths ApplicationInfo gives. */
object PrivilegedCommands {
    fun appProcess(sourceDir: String, splitSourceDirs: List<String> = emptyList()): String {
        val paths = listOf(sourceDir) + splitSourceDirs
        require(paths.all { it.startsWith('/') && '\u0000' !in it && ':' !in it })
        return "CLASSPATH=${quote(paths.joinToString(":"))} exec /system/bin/app_process /system/bin ${PrivilegedDisplayMain::class.java.name}"
    }

    fun grantSecureSettings(userId: Int): String {
        require(userId >= 0)
        return "exec /system/bin/pm grant --user $userId ${Proto.APP_PACKAGE} ${SecureSettingsGrant.PERMISSION}"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** `su -c` takes exactly one shell command on Magisk, KernelSU and APatch. */
internal object RootCommands {
    fun appProcess(sourceDir: String, splitSourceDirs: List<String> = emptyList()): List<String> =
        listOf("su", "-c", PrivilegedCommands.appProcess(sourceDir, splitSourceDirs))
    fun grantSecureSettings(userId: Int): List<String> =
        listOf("su", "-c", PrivilegedCommands.grantSecureSettings(userId))
}
