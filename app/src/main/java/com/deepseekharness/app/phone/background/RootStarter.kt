// Root app_process path. Magisk/KernelSU/APatch command syntax checked against upstream
// source; authorization UI, SELinux and display behavior have NOT been verified on devices.
package com.deepseekharness.app.phone.background

import android.content.Context
import android.os.Bundle
import android.os.Process
import java.io.IOException

/**
 * su runs only from [start] and [grantSecureSettings], both behind an explicit user action on the
 * plugin's IO worker. Tasks only [connect] to what an earlier start left alive.
 */
class RootStarter(private val context: Context, private val onChanged: () -> Unit) {
    private val guard = Any()
    private val startLock = Any()
    private val state = RootState()
    private var channel: RootChannel? = null
    private var pending: RootProcess? = null

    fun status(): RootStatus = synchronized(guard) {
        discardDead()
        state.status()
    }

    fun connect(): PrivilegedChannel? = synchronized(guard) {
        discardDead()
        channel
    }

    /**
     * [mayStart] (a quick read of the caller's UI ticket) is checked under the guard [disconnect]
     * takes, so a cancel either comes first and nothing starts, or ends this start's ticket.
     */
    fun start(mayStart: () -> Boolean = { true }): PrivilegedChannel? = synchronized(startLock) start@{
        val ticket = synchronized(guard) {
            discardDead()
            channel?.let { return@start if (mayStart()) it else null }
            state.beginIfAllowed(mayStart) ?: return@start null
        }
        onChanged()
        val info = context.applicationInfo
        val process = try {
            RootProcess.launch(RootCommands.appProcess(info.sourceDir, info.splitSourceDirs?.toList().orEmpty()))
        } catch (error: Exception) {
            synchronized(guard) { state.finish(ticket, reason(error)) }
            onChanged()
            return@start null
        }
        // Registered, disconnect() closes it mid-authorization; already cancelled, it closes now.
        synchronized(guard) { if (state.current(ticket)) pending = process else process.close() }
        val failure = try {
            val probe = Bundles.unmarshal(process.exchange(Bundles.marshal(Bundle().apply {
                putString(Proto.OP, Proto.OP_PROBE)
            }), START_TIMEOUT_MS))
            when {
                !probe.getBoolean(Proto.OK) -> "rootStartFailed"
                !RootProbe.accepted(true, probe.getInt(Proto.UID, -1), probe.getString("revision"), Proto.REVISION) -> "rootIdentityMismatch"
                else -> null
            }
        } catch (error: Exception) {
            reason(error)
        }
        val result = synchronized(guard) {
            pending = null
            if (state.finish(ticket, failure) && failure == null) {
                RootChannel(process) { channelFailed(process, it) }.also { channel = it }
            } else null
        }
        if (result == null) process.close()
        onChanged()
        result
    }

    /** A second, short-lived su for the fixed pm grant; only once [start] succeeded. */
    fun grantSecureSettings(): Boolean {
        if (connect() == null) return false
        val ok = runCatching {
            RootProcess.launch(RootCommands.grantSecureSettings(Process.myUid() / 100000)).use { it.exitCode(GRANT_TIMEOUT_MS) == 0 }
        }.getOrDefault(false)
        if (!ok) synchronized(guard) { state.note("rootGrantFailed") }
        onChanged()
        return ok
    }

    fun disconnect() {
        synchronized(guard) {
            state.disconnect()
            pending?.close(); pending = null
            channel?.close(); channel = null
        }
        onChanged()
    }

    private fun discardDead() {
        if (channel?.alive() == false) {
            channel?.close(); channel = null
            state.disconnect()
        }
    }

    private fun channelFailed(process: RootProcess, failure: String) {
        synchronized(guard) {
            if (channel?.process !== process) return
            channel = null
            state.disconnect()
            state.note(failure)
        }
        onChanged()
    }

    companion object {
        // App policy, not the managers' own authorization timeouts.
        const val START_TIMEOUT_MS = 30_000L
        const val GRANT_TIMEOUT_MS = 10_000L
        private fun reason(error: Exception): String =
            error.message.takeIf { error is IOException && it in setOf("rootTimeout", "rootCancelled") } ?: "rootStartFailed"
    }
}

private class RootChannel(
    val process: RootProcess,
    private val onFailure: (String) -> Unit,
) : PrivilegedChannel {
    override fun alive(): Boolean = process.alive()
    override fun close() = process.close()
    override fun execute(request: Bundle): Bundle = try {
        Bundles.unmarshal(process.exchange(Bundles.marshal(request), 30_000))
    } catch (error: Exception) {
        val timeout = error is IOException && error.message == "rootTimeout"
        process.close()
        onFailure(if (timeout) "rootTimeout" else "rootDisconnected")
        Bundle().apply {
            putBoolean(Proto.OK, false)
            putString(Proto.ERROR, if (timeout) "timeout" else "deviceUnavailable")
        }
    }
}
