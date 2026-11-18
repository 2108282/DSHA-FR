package com.deepseekharness.app.phone.a11y;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class UiEventScopeTest {
    @Test public void connectionStartsIdleAndNestedForegroundReadKeepsOuterSubscription() throws Exception {
        List<Boolean> states = new ArrayList<>();
        UiEventScope scope = new UiEventScope(states::add);
        scope.connect();
        long open = scope.enter();
        long foreground = scope.enter();
        scope.exit(foreground);
        assertEquals(Arrays.asList(false, true), states);
        scope.exit(open);
        assertEquals(Arrays.asList(false, true, false), states);
    }

    @Test public void operationFailureStillReturnsToIdle() throws Exception {
        List<Boolean> states = new ArrayList<>();
        UiEventScope scope = new UiEventScope(states::add);
        scope.connect();
        assertThrows(IllegalArgumentException.class, () -> {
            long token = scope.enter();
            try { throw new IllegalArgumentException("capture failed"); }
            finally { scope.exit(token); }
        });
        assertEquals(Arrays.asList(false, true, false), states);
        long next = scope.enter();
        scope.exit(next);
        assertEquals(Arrays.asList(false, true, false, true, false), states);
    }

    @Test public void partiallyFailedActivationRollsBackWithoutLeakingReference() throws Exception {
        List<Boolean> states = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        UiEventScope scope = new UiEventScope(active -> {
            states.add(active);
            if (active && fail.getAndSet(false)) throw new IllegalStateException("binder failed");
        });
        scope.connect();
        assertThrows(IllegalStateException.class, scope::enter);
        long next = scope.enter();
        scope.exit(next);
        assertEquals(Arrays.asList(false, true, false, true, false), states);
    }

    @Test public void closeRejectsNewWorkAndFinalExitClearsAnInflightQueryAgain() throws Exception {
        List<Boolean> states = new ArrayList<>();
        UiEventScope scope = new UiEventScope(states::add);
        scope.connect();
        long token = scope.enter();
        scope.close();
        assertThrows(InterruptedException.class, scope::enter);
        scope.exit(token);
        assertEquals(Arrays.asList(false, true, false, false), states);
    }

    @Test public void oldConnectionFinallyCannotDisableReconnectedOperation() throws Exception {
        List<Boolean> states = new ArrayList<>();
        UiEventScope scope = new UiEventScope(states::add);
        scope.connect();
        long old = scope.enter();
        scope.close();
        scope.connect();
        long current = scope.enter();
        scope.exit(old);
        assertEquals(Arrays.asList(false, true, false, false, true), states);
        scope.exit(current);
        assertEquals(Arrays.asList(false, true, false, false, true, false), states);
    }

    @Test public void concurrentUserExitDoesNotDisableRemainingUser() throws Exception {
        List<Boolean> states = new ArrayList<>();
        UiEventScope scope = new UiEventScope(states::add);
        scope.connect();
        long first = scope.enter();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Long> second = worker.submit(scope::enter);
            long token = second.get(2, TimeUnit.SECONDS);
            scope.exit(first);
            assertEquals(Arrays.asList(false, true), states);
            worker.submit(() -> scope.exit(token)).get(2, TimeUnit.SECONDS);
            assertEquals(Arrays.asList(false, true, false), states);
        } finally { worker.shutdownNow(); }
    }

    @Test public void teardownDoesNotWaitForWorkerThatNeedsMainThread() throws Exception {
        UiEventScope scope = new UiEventScope(active -> {});
        scope.connect();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch mainThreadFinished = new CountDownLatch(1);
        try {
            Future<?> worker = threads.submit(() -> {
                long token = scope.enter();
                try {
                    entered.countDown();
                    assertTrue(mainThreadFinished.await(2, TimeUnit.SECONDS));
                } finally { scope.exit(token); }
                return null;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> teardown = threads.submit(scope::close);
            teardown.get(2, TimeUnit.SECONDS);
            mainThreadFinished.countDown();
            worker.get(2, TimeUnit.SECONDS);
        } finally {
            mainThreadFinished.countDown();
            threads.shutdownNow();
        }
    }

    @Test public void cleanupFailureDoesNotLeaveAReference() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(false);
        List<Boolean> states = new ArrayList<>();
        UiEventScope scope = new UiEventScope(active -> {
            states.add(active);
            if (!active && fail.getAndSet(false)) throw new IllegalStateException("cleanup failed");
        });
        scope.connect();
        long token = scope.enter();
        fail.set(true);
        assertThrows(IllegalStateException.class, () -> scope.exit(token));
        long next = scope.enter();
        scope.exit(next);
        assertEquals(Arrays.asList(false, true, false, true, false), states);
    }
}
