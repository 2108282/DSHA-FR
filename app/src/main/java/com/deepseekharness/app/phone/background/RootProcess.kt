package com.deepseekharness.app.phone.background

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** A root child on private pipes. Only fixed reason codes leave it, never what su printed. */
internal class RootProcess private constructor(private val process: Process) : Closeable {
    private val closed = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "dsh-root-pipe").apply { isDaemon = true } }
    private var synced = false

    init {
        // su and the manager write here too (authorization prompts included): drain it so the
        // pipe never fills, keep nothing.
        thread(isDaemon = true, name = "dsh-root-stderr") {
            runCatching { val buffer = ByteArray(4096); while (process.errorStream.read(buffer) >= 0) { } }
        }
    }

    fun alive(): Boolean = !closed.get() && process.isAlive

    fun exchange(request: ByteArray, timeoutMs: Long): ByteArray = bounded(timeoutMs) {
        if (!synced) {
            if (!Frames.syncToMagic(process.inputStream)) throw IOException("rootStartFailed")
            synced = true
        }
        Frames.write(process.outputStream, request)
        Frames.read(process.inputStream) ?: throw IOException("rootDisconnected")
    }

    /** The fixed pm grant: stdout drained unread. */
    fun exitCode(timeoutMs: Long): Int = bounded(timeoutMs) {
        val buffer = ByteArray(4096)
        while (process.inputStream.read(buffer) >= 0) { }
        process.waitFor()
    }

    // The deadline is the caller's: a pending authorization blocks the read until the person answers.
    private fun <T> bounded(timeoutMs: Long, action: () -> T): T {
        if (closed.get()) throw IOException("rootDisconnected")
        val task = io.submit(Callable { action() })
        try {
            return task.get(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true); close(); throw IOException("rootTimeout")
        } catch (_: InterruptedException) {
            task.cancel(true); close(); Thread.currentThread().interrupt(); throw IOException("rootCancelled")
        } catch (_: Exception) {
            close(); throw IOException("rootStartFailed")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        io.shutdownNow()
        // Closing a pipe another thread reads can block, so not on the caller's thread. Our
        // server also exits on stdin EOF; killing su revokes nothing at the manager.
        thread(isDaemon = true, name = "dsh-root-close") {
            runCatching { process.destroyForcibly() }
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    companion object {
        fun launch(command: List<String>, spawn: (List<String>) -> Process = { ProcessBuilder(it).start() }): RootProcess =
            try { RootProcess(spawn(command)) } catch (_: Exception) { throw IOException("rootStartFailed") }
    }
}
