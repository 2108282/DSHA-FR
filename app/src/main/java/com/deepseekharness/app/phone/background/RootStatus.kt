package com.deepseekharness.app.phone.background

/** available is the last explicit probe result; null means not checked yet. */
data class RootStatus(
    val checked: Boolean = false,
    val available: Boolean? = null,
    val connected: Boolean = false,
    val starting: Boolean = false,
    val reasonCode: String? = null,
) {
    fun map(): Map<String, Any?> = mapOf(
        "checked" to checked, "available" to available, "connected" to connected,
        "starting" to starting, "reasonCode" to reasonCode,
    )
}

/** A cancelled startup cannot publish a late connection. No process operations here. */
internal class RootState {
    private var generation = 0L
    private var value = RootStatus()
    @Synchronized fun status(): RootStatus = value
    @Synchronized fun current(ticket: Long): Boolean = ticket == generation && value.starting
    @Synchronized fun note(reason: String) { value = value.copy(reasonCode = reason) }
    /** The predicate and ticket creation share the same admission critical section. */
    @Synchronized fun beginIfAllowed(mayStart: () -> Boolean): Long? =
        if (mayStart()) begin() else null
    @Synchronized fun begin(): Long {
        generation++
        value = value.copy(checked = true, starting = true, connected = false, reasonCode = null)
        return generation
    }
    @Synchronized fun finish(ticket: Long, reason: String?): Boolean {
        if (ticket != generation || !value.starting) return false
        value = RootStatus(checked = true, available = reason == null,
            connected = reason == null, reasonCode = reason)
        return true
    }
    @Synchronized fun disconnect() {
        generation++
        value = value.copy(starting = false, connected = false,
            reasonCode = if (value.checked) "rootDisconnected" else null)
    }
}

internal object RootProbe {
    fun accepted(ok: Boolean, uid: Int, revision: String?, expectedRevision: String): Boolean =
        ok && uid == 0 && revision == expectedRevision
}
