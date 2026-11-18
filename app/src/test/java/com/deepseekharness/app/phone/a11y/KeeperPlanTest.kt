package com.deepseekharness.app.phone.a11y

import org.junit.Assert.*
import org.junit.Test

class KeeperPlanTest {
    private val pkg = "com.dsha.fr"
    private val cls = "com.deepseekharness.app.DshaAccessibilityService"
    private fun plan() = KeeperPlan(pkg, cls)

    @Test fun disabledAndMissingPermissionNeverAttemptRecovery() {
        val p = plan()
        repeat(100) {
            assertEquals(KeeperPlan.Step.NONE, p.next(false, true, "", false, it * 10000L))
            assertEquals(KeeperPlan.Step.NONE, p.next(true, false, "", false, it * 10000L))
        }
    }

    @Test fun failingRecoveryBacksOffAndStopsAfterFiveAttempts() {
        val p = plan()
        var now = 0L
        repeat(KeeperPlan.MAX_ATTEMPTS) { attempt ->
            assertEquals(KeeperPlan.Step.ADD, p.next(true, true, "", false, now))
            val wait = KeeperPlan.backoff(attempt + 1)
            assertEquals(KeeperPlan.Step.NONE, p.next(true, true, "", false, now + wait - 1))
            assertFalse(p.exhausted(now + wait - 1))
            now += wait
        }
        assertTrue(p.exhausted(now))
        repeat(100) {
            assertEquals(KeeperPlan.Step.NONE, p.next(true, true, "", false, now + it * 600000L))
        }
    }

    @Test fun listedServiceGetsBindingGraceBeforeRebind() {
        val p = plan()
        val list = "$pkg/$cls"
        assertEquals(KeeperPlan.Step.NONE, p.next(true, true, list, false, 0))
        assertEquals(KeeperPlan.Step.NONE, p.next(true, true, list, false, KeeperPlan.UNBOUND_MS - 1))
        assertEquals(KeeperPlan.Step.REBIND, p.next(true, true, list, false, KeeperPlan.UNBOUND_MS))
    }

    @Test fun demandCallCanRebindWithinItsBoundedWaitWithoutBackgroundTick() {
        val p = plan()
        val list = "$pkg/$cls"
        assertEquals(KeeperPlan.Step.NONE, p.next(true, true, list, false, 0, bindingGraceMs = 1000))
        assertEquals(KeeperPlan.Step.NONE, p.next(true, true, list, false, 999, bindingGraceMs = 1000))
        assertEquals(KeeperPlan.Step.REBIND, p.next(true, true, list, false, 1000, bindingGraceMs = 1000))
        // Further calls inside the backoff do not toggle the service again.
        assertEquals(KeeperPlan.Step.NONE, p.next(true, true, list, false, 2000, bindingGraceMs = 1000))
        assertEquals(KeeperPlan.Step.NONE, p.next(true, true, list, false, 3000, bindingGraceMs = 1000))
    }

    @Test fun stableConnectionResetsBudgetButBriefConnectionDoesNot() {
        val p = plan()
        var now = 0L
        repeat(KeeperPlan.MAX_ATTEMPTS) {
            p.next(true, true, "", false, now)
            now += KeeperPlan.backoff(it + 1)
        }
        p.next(true, true, null, true, now)
        p.next(true, true, null, true, now + KeeperPlan.STABLE_MS - 1)
        assertEquals(KeeperPlan.Step.NONE, p.next(true, true, "", false, now + KeeperPlan.STABLE_MS))
        now += KeeperPlan.STABLE_MS + 1
        p.next(true, true, null, true, now)
        p.next(true, true, null, true, now + KeeperPlan.STABLE_MS)
        assertEquals(KeeperPlan.Step.ADD, p.next(true, true, "", false, now + KeeperPlan.STABLE_MS + 1))
    }

    @Test fun explicitResetAllowsNewAttemptAndPreservesOtherServices() {
        val p = plan()
        var now = 0L
        repeat(KeeperPlan.MAX_ATTEMPTS) {
            p.next(true, true, "", false, now)
            now += KeeperPlan.backoff(it + 1)
        }
        p.reset()
        assertEquals(KeeperPlan.Step.ADD, p.next(true, true, "", false, now))
        val other = "other.pkg/.Reader"
        assertEquals(other, p.withoutMe("$other:$pkg/$cls"))
        assertEquals("$other:$pkg/$cls", p.withMe(other))
        assertEquals("$other:$pkg/$cls", p.withMe("$other:$pkg/$cls"))
        val shortPlan = KeeperPlan(pkg, "$pkg.Service")
        assertTrue(shortPlan.listed("$other:$pkg/.Service"))
        assertEquals(other, shortPlan.withoutMe("$other:$pkg/.Service"))
    }
}
