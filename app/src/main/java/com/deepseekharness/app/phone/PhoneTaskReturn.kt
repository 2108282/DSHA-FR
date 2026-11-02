package com.deepseekharness.app.phone

/** Return after an exact task was closed. OS start acceptance and actual resumed are distinct. */
class PhoneTaskReturn(
    private val current: () -> Boolean,
    private val visible: () -> Boolean,
    private val launch: () -> Boolean,
    private val notify: () -> Boolean,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val clock: () -> Long,
) {
    private var answered = false
    private var launched = false

    fun start(closed: Boolean, busy: Boolean, reopen: Boolean, reply: (Map<String, Any?>) -> Unit) {
        fun answer(notified: Boolean = false) {
            if (answered) return
            answered = true
            val valid = current()
            reply(mapOf("closed" to closed, "busy" to busy, "opened" to visible(), "launched" to launched,
                "notified" to notified, "current" to valid, "reason" to if (!valid) "stale" else null))
        }
        fun fallback() {
            if (!current()) { answer(); return }
            val notified = runCatching(notify).getOrDefault(false)
            answer(notified)
        }
        if (!closed || !reopen || !current() || visible()) { answer(); return }
        launched = runCatching(launch).getOrDefault(false)
        if (!launched) { fallback(); return }
        val deadline = clock() + 3_000
        fun check() {
            when {
                answered -> Unit
                !current() || visible() -> answer()
                clock() >= deadline -> fallback()
                else -> schedule(100, ::check)
            }
        }
        schedule(100, ::check)
    }
}
