package com.deepseekharness.app;

/** One request generation shared by all approval entry points. */
final class ApprovalRequestGate {
    // Process restarts must not reuse the epoch carried by surviving notifications.
    private static final java.util.concurrent.atomic.AtomicLong NEXT =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    private long epoch = -1;
    private boolean resolved = true;

    private final Object writerLock = new Object();

    long begin() { return begin(() -> {}); }

    long begin(Runnable initialize) {
        // Request creation runs on bridge workers. UI claims never wait for root I/O.
        synchronized (writerLock) {
            synchronized (this) {
                epoch = NEXT.incrementAndGet();
                resolved = false;
                initialize.run();
                return epoch;
            }
        }
    }

    synchronized boolean claim(long candidate) {
        if (candidate != epoch || resolved) return false;
        resolved = true;
        return true;
    }

    synchronized void expire(long candidate) {
        if (candidate == epoch) resolved = true;
    }

    synchronized void invalidate() {
        epoch = NEXT.incrementAndGet();
        resolved = true;
    }

    void writeIfCurrent(long candidate, Runnable write) {
        synchronized (writerLock) {
            synchronized (this) {
                if (candidate != epoch) return;
            }
            write.run();
        }
    }
}
