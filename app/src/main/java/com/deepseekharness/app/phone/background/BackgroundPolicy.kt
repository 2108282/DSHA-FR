package com.deepseekharness.app.phone.background

// Pure decision logic for 不打扰模式: display sizing, the hard-gate on which
// screens/windows must be handed back, and the sensitive-action test. No Android types,
// so it is unit-tested on the JVM and shared by the engine and the driver.

object BackgroundPolicy {
    /** Lowest Android the trusted, own-focus virtual display needs (OWN_FOCUS is 14+). */
    const val MIN_SDK = 34

    /**
     * Phone-shaped size: the requested short side, rounded to an even number, with the long side
     * from the real aspect ratio (not Phase's fixed 720×1280, which letterboxes 20:9 phones).
     * Returns {width, height} in the display's natural (portrait) orientation.
     */
    fun displaySize(shortSide: Int, aspectW: Int, aspectH: Int): IntArray {
        val short = (shortSide / 2 * 2).coerceIn(360, 1080)
        val w = minOf(aspectW, aspectH).coerceAtLeast(1)
        val h = maxOf(aspectW, aspectH).coerceAtLeast(1)
        var long = (short.toLong() * h / w).toInt()
        long = (long / 2 * 2).coerceIn(short, short * 3)
        return intArrayOf(short, long)
    }

    /** True when this window must be handed back to the user rather than driven. */
    fun mustHandBack(secure: Boolean, locked: Boolean): Boolean = secure || locked

    /** Whether an action on an element needs confirmation, from its flags + label. */
    fun needsConfirm(flags: Int, label: String, submit: Boolean, firstInApp: Boolean): Boolean {
        if (firstInApp) return true
        if (submit) return true
        if (flags and Proto.FLAG_SENSITIVE != 0) return true
        return BackgroundTree.isSensitiveLabel(label)
    }
}
