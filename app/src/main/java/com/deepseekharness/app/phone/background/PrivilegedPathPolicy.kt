package com.deepseekharness.app.phone.background

/** Pure status/selection rules. A recommendation never starts a process or asks for permission. */
object PrivilegedPathPolicy {
    val modes = listOf("off", "shizuku", "sui", "root", "adb")

    fun canonicalMode(requested: String, provider: String?): String? = when {
        requested !in modes -> null
        requested == "shizuku" && provider == "sui" -> "sui"
        else -> requested
    }

    /** Keep the old three-mode Dart enum and its exhaustive UI switches valid. */
    fun legacyMode(mode: String): String = when (mode) {
        "sui" -> "shizuku"
        "shizuku", "adb" -> mode
        else -> "off"
    }

    fun legacyReady(mode: String, ready: Boolean): Boolean = legacyMode(mode) != "off" && ready

    /** PhoneControl also consumes the old reason for failed background tool calls. */
    fun legacyReason(mode: String, actualReason: String?, rootCompatibilityReason: String): String? =
        if (mode == "root" && actualReason == null) rootCompatibilityReason else actualReason

    /** A screen-off keeps root's idle process: tasks never run su, Shizuku/Sui and ADB reopen by themselves. */
    fun closesOnInterrupt(starter: String): Boolean = starter != "root"

    /** Sui and the legacy Shizuku grant name share one UserService connection. */
    fun sharesGrantChannel(mode: String, via: String): Boolean = when (via) {
        "shizuku", "sui" -> mode == "shizuku" || mode == "sui"
        "adb" -> mode == "adb"
        else -> false
    }

    fun recommended(
        mode: String,
        ready: Boolean,
        androidOk: Boolean,
        provider: String?,
        sharedRunning: Boolean,
        sharedGranted: Boolean,
        rootSelected: Boolean,
        rootConnected: Boolean,
        adbPaired: Boolean,
        wirelessDebugging: Boolean?,
    ): String {
        if (!androidOk) return "off"
        if (ready && mode != "off") return mode
        val shared = if (provider == "sui") "sui" else "shizuku"
        return when {
            sharedRunning && sharedGranted -> shared
            rootSelected && rootConnected -> "root"
            adbPaired && wirelessDebugging != false -> "adb"
            sharedRunning -> shared
            else -> "off"
        }
    }
}
