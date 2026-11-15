package com.deepseekharness.app;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ApprovalRequestGateTest {
    @Test public void oldRequestCannotClaimOrWriteForNewRequest() {
        ApprovalRequestGate gate = new ApprovalRequestGate();
        long old = gate.begin();
        long current = gate.begin();
        assertFalse(gate.claim(old));
        AtomicInteger writes = new AtomicInteger();
        gate.writeIfCurrent(old, writes::incrementAndGet);
        assertEquals(0, writes.get());
        assertTrue(gate.claim(current));
        gate.writeIfCurrent(current, writes::incrementAndGet);
        assertEquals(1, writes.get());
    }

    @Test public void expiryRejectsLateClicksButKeepsAcceptedWriter() {
        ApprovalRequestGate gate = new ApprovalRequestGate();
        long expired = gate.begin();
        gate.expire(expired);
        assertFalse(gate.claim(expired));
        long accepted = gate.begin();
        assertTrue(gate.claim(accepted));
        gate.expire(accepted);
        AtomicInteger writes = new AtomicInteger();
        gate.writeIfCurrent(accepted, writes::incrementAndGet);
        assertEquals(1, writes.get());
    }

    @Test public void serviceReplacementDoesNotReuseNotificationEpoch() {
        long old = new ApprovalRequestGate().begin();
        ApprovalRequestGate replacement = new ApprovalRequestGate();
        long current = replacement.begin();
        assertNotEquals(old, current);
        assertFalse(replacement.claim(old));
        assertTrue(replacement.claim(current));
    }

    @Test public void notificationClaimNeverWaitsForFileIo() throws Exception {
        ApprovalRequestGate gate = new ApprovalRequestGate();
        long epoch = gate.begin();
        assertTrue(gate.claim(epoch));
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread writer = new Thread(() -> gate.writeIfCurrent(epoch, () -> {
            writing.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
        }));
        writer.start();
        assertTrue(writing.await(3, java.util.concurrent.TimeUnit.SECONDS));
        try {
            java.util.concurrent.FutureTask<Boolean> click = new java.util.concurrent.FutureTask<>(() -> gate.claim(epoch));
            new Thread(click).start();
            assertFalse(click.get(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
            writer.join();
        }
    }

    @Test public void channelsCanOnlyClaimOnce() throws Exception {
        ApprovalRequestGate gate = new ApprovalRequestGate();
        long epoch = gate.begin();
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Runnable click = () -> {
            try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            if (gate.claim(epoch)) winners.incrementAndGet();
        };
        Thread first = new Thread(click), second = new Thread(click);
        first.start(); second.start(); start.countDown();
        first.join(); second.join();
        assertEquals(1, winners.get());
    }
}
