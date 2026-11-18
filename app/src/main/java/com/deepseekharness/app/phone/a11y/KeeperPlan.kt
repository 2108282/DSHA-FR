package com.deepseekharness.app.phone.a11y

/**
 * The keeper's decisions, apart from Android so they are unit-tested. ENABLED_ACCESSIBILITY_SERVICES
 * is `pkg/cls` or `pkg/.Short` entries joined by ':'; edits touch only ours and keep every other
 * entry as it was.
 */
class KeeperPlan(private val pkg: String, private val cls: String) {
    enum class Step { NONE, ADD, REBIND }

    private val me = "$pkg/$cls"
    private var unboundSince = NEVER
    private var heldSince = NEVER
    private var writes = 0
    private var nextAt = 0L

    fun reset() {
        unboundSince = NEVER
        heldSince = NEVER
        writes = 0
        nextAt = 0L
    }

    // Allow the final attempt time to bind before reporting the exhausted budget.
    fun exhausted(now: Long): Boolean = writes >= MAX_ATTEMPTS && now >= nextAt

    /**
     * What to do at [now]: [on] — phone control is switched on in DSHA; [granted] —
     * WRITE_SECURE_SETTINGS is held; [list] — the setting as read; [connected] — our service is bound.
     */
    fun next(on: Boolean, granted: Boolean, list: String?, connected: Boolean, now: Long,
             bindingGraceMs: Long = UNBOUND_MS): Step {
        if (!on) {
            reset()
            return Step.NONE
        }
        if (connected) {
            unboundSince = NEVER
            if (heldSince == NEVER) heldSince = now
            // Held a while: whatever took it away before was not a fight, start the backoff over.
            if (now - heldSince >= STABLE_MS) {
                writes = 0
                nextAt = 0L
            }
            return Step.NONE
        }
        heldSince = NEVER
        if (!granted || writes >= MAX_ATTEMPTS) return Step.NONE
        val step = if (!listed(list)) {
            Step.ADD
        } else {
            if (unboundSince == NEVER) unboundSince = now
            // Listed and not bound yet: the system is still binding, or gave up on it.
            if (now - unboundSince < bindingGraceMs) return Step.NONE
            Step.REBIND
        }
        if (now < nextAt) return Step.NONE
        writes = (writes + 1).coerceAtMost(8)
        nextAt = now + backoff(writes)
        unboundSince = NEVER
        return step
    }

    fun listed(list: String?): Boolean = list.orEmpty().split(':').any(::ours)

    /** [list] with ours at the end (unchanged when it is there already). */
    fun withMe(list: String?): String = when {
        listed(list) -> list.orEmpty()
        list.isNullOrEmpty() -> me
        else -> "$list:$me"
    }

    /** [list] without ours. */
    fun withoutMe(list: String?): String = list.orEmpty().split(':').filterNot(::ours).joinToString(":")

    private fun ours(entry: String): Boolean {
        val e = entry.trim()
        val slash = e.indexOf('/')
        if (slash <= 0 || e.substring(0, slash) != pkg) return false
        val c = e.substring(slash + 1)
        return c == cls || (c.startsWith('.') && pkg + c == cls)
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE

        /** Listed but unbound this long: the system won't bind it by itself. */
        const val UNBOUND_MS = 10_000L

        /** Bound this long: the next loss starts the backoff over. */
        const val STABLE_MS = 60_000L

        const val MAX_ATTEMPTS = 5
        private const val FIRST_WAIT_MS = 5_000L
        private const val MAX_WAIT_MS = 10 * 60_000L

        /** After the [n]th write in a row: 5 s, 10 s, 20 s … at most 10 minutes. */
        fun backoff(n: Int): Long = minOf(FIRST_WAIT_MS shl (n - 1).coerceIn(0, 7), MAX_WAIT_MS)
    }
}
