package com.deepseekharness.app.phone.a11y

import android.os.Looper
import android.os.SystemClock

/**
 * Counts accessibility events (delivered on the main thread) so a worker thread can
 * wait for the UI to settle after an action: the first change, then a quiet gap.
 * Waiting on the main thread would block event delivery, so there it returns at once.
 */
internal class EventClock {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object() // java.lang.Object for wait / notifyAll

    @Volatile var count = 0L
        private set

    @Volatile var lastAt = 0L
        private set

    @Volatile var lastPackage: String? = null
        private set

    fun tick(packageName: CharSequence?) {
        synchronized(lock) {
            count++
            lastAt = SystemClock.uptimeMillis()
            if (packageName != null) lastPackage = packageName.toString()
            lock.notifyAll()
        }
    }

    /**
     * Waits for the first event after [since] (up to [firstMs]), then until [quietMs] pass
     * without events; never longer than [maxMs] in total. True if anything happened.
     */
    fun settle(since: Long, firstMs: Long, quietMs: Long, maxMs: Long, cancelled: () -> Boolean = { false }): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return count != since
        val start = SystemClock.uptimeMillis()
        val deadline = start + maxMs
        synchronized(lock) {
            while (count == since) {
                val left = minOf(start + firstMs, deadline) - SystemClock.uptimeMillis()
                if (left <= 0 || cancelled()) return false
                lock.wait(minOf(left, 100))
            }
            while (true) {
                val now = SystemClock.uptimeMillis()
                if (now >= deadline || cancelled()) return true
                val quietLeft = lastAt + quietMs - now
                if (quietLeft <= 0) return true
                lock.wait(minOf(quietLeft, deadline - now, 100).coerceAtLeast(1))
            }
        }
    }

    /** Sleeps up to [ms] in short slices, waking early on [cancelled]. */
    fun pause(ms: Long, cancelled: () -> Boolean = { false }) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val end = SystemClock.uptimeMillis() + ms
        synchronized(lock) {
            while (true) {
                val left = end - SystemClock.uptimeMillis()
                if (left <= 0 || cancelled()) return
                lock.wait(minOf(left, 50))
            }
        }
    }
}
