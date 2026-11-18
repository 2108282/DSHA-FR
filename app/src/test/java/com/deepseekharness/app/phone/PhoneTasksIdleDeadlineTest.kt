package com.deepseekharness.app.phone

import org.junit.Assert.*
import org.junit.Test

class PhoneTasksIdleDeadlineTest {
    private var now = 1_000L
    private fun tasks() = PhoneTasks(clock = { now }, idleMs = 100L)

    @Test fun noDeadlineBeforeTaskOrAfterFinish() {
        val t = tasks()
        assertNull(t.idleDelayMs())
        t.begin()
        assertEquals(101L, t.idleDelayMs())
        assertTrue(t.finish())
        assertNull(t.idleDelayMs())
    }

    @Test fun slowCallHasNoDeadlineAndLastResultRearmsIt() {
        val t = tasks()
        var notifications = 0
        t.addListener { notifications++ }
        t.callStarted()
        t.begin()
        assertNull(t.idleDelayMs())
        now += 10_000L
        assertFalse(t.expireIdle())
        val before = notifications
        t.callEnded()
        assertEquals(before + 1, notifications)
        assertEquals(101L, t.idleDelayMs())
        now += 100L
        assertFalse(t.expireIdle())
        assertEquals(1L, t.idleDelayMs())
        now++
        assertTrue(t.expireIdle())
        assertNull(t.idleDelayMs())
    }

    @Test fun pauseCancelsDeadlineAndResumeStartsFresh() {
        val t = tasks()
        t.begin()
        t.setPaused(true)
        now += 10_000L
        assertNull(t.idleDelayMs())
        assertFalse(t.expireIdle())
        t.setPaused(false)
        assertEquals(101L, t.idleDelayMs())
    }

    @Test fun previousDeadlineCannotExpireNewActivity() {
        val t = tasks()
        t.begin()
        now += 90L
        t.touch()
        now += 11L
        assertFalse(t.expireIdle())
        assertEquals(90L, t.idleDelayMs())
        t.stop("stop")
        assertNull(t.idleDelayMs())
        t.clearStop()
        t.begin()
        assertEquals(101L, t.idleDelayMs())
    }

    @Test fun overlappingCallsArmOnlyAfterLastResult() {
        val t = tasks()
        t.callStarted()
        t.begin()
        t.callStarted()
        t.callEnded()
        assertNull(t.idleDelayMs())
        now += 80L
        t.callEnded()
        assertEquals(101L, t.idleDelayMs())
    }
}
