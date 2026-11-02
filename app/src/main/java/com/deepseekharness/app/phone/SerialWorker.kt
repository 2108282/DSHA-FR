package com.deepseekharness.app.phone

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Every phone action runs here, one at a time, in a single serial queue. Each call has its own
 * time budget that excludes the time spent waiting for the person ([Scope.userWait]: confirmations,
 * pause, handoff), plus an overall cap below DSH's 660 s MCP timeout. A call that overruns is
 * interrupted; if its thread doesn't come back, the worker thread is abandoned and replaced so the
 * queue never wedges behind a stuck driver.
 */
class SerialWorker(private val name: String) {

    class Scope internal constructor() {
        internal val waited = AtomicLong()
        @Volatile internal var waitingSince = 0L

        /** Runs [block] (which waits for the person) without charging it to the call's own budget. */
        fun <T> userWait(block: () -> T): T {
            val start = now()
            waitingSince = start
            try {
                return block()
            } finally {
                waited.addAndGet(now() - start)
                waitingSince = 0L
            }
        }

        internal fun charged(startedAt: Long, at: Long): Long {
            val since = waitingSince
            return at - startedAt - waited.get() - (if (since > 0) at - since else 0)
        }
    }

    sealed interface Outcome<out T> {
        class Done<T>(val value: T) : Outcome<T>
        class Failed(val error: Throwable) : Outcome<Nothing>
        /** Ran past its budget ([queued]: never got to start). */
        class TimedOut(val queued: Boolean) : Outcome<Nothing>
        /** Interrupted by [interruptCurrent] (stop). */
        object Interrupted : Outcome<Nothing>
    }

    private val generation = AtomicLong()
    @Volatile private var executor: ExecutorService = newExecutor()
    private val current = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
    private val retirementLock = Any()
    private var retirementEpoch = 0L

    private fun newExecutor(): ExecutorService {
        val gen = generation.incrementAndGet()
        return Executors.newSingleThreadExecutor { r -> Thread(r, "$name-$gen").apply { isDaemon = true } }
    }

    /** Runs [job] on the worker; blocks the caller until it finishes or overruns. */
    fun <T> run(budgetMs: Long, maxTotalMs: Long, job: (Scope) -> T): Outcome<T> {
        val admittedEpoch = synchronized(retirementLock) { retirementEpoch }
        val scope = Scope()
        val submitted = now()
        val startedAt = AtomicLong(0)
        val exec = executor
        val future: Future<T> = try {
            exec.submit(Callable {
                startedAt.set(now())
                val self = Thread.currentThread()
                try {
                    synchronized(retirementLock) {
                        if (admittedEpoch != retirementEpoch) throw InterruptedException()
                        current.set(self)
                    }
                    job(scope)
                } finally {
                    synchronized(retirementLock) {
                        current.compareAndSet(self, null)
                        Thread.interrupted() // don't leak an interrupt into the next job
                    }
                }
            })
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            return Outcome.Failed(e)
        }
        while (true) {
            try {
                return Outcome.Done(future.get(POLL_MS, TimeUnit.MILLISECONDS))
            } catch (_: TimeoutException) {
                val t = now()
                val started = startedAt.get()
                if (started == 0L) {
                    if (t - submitted > maxTotalMs) {
                        future.cancel(false)
                        return Outcome.TimedOut(queued = true)
                    }
                    continue
                }
                if (scope.charged(started, t) > budgetMs || t - submitted > maxTotalMs) {
                    future.cancel(true)
                    abandonIfStuck(exec, future)
                    return Outcome.TimedOut(queued = false)
                }
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                return if (cause is InterruptedException) Outcome.Interrupted else Outcome.Failed(cause)
            } catch (_: CancellationException) {
                return Outcome.Interrupted
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                return Outcome.Interrupted
            }
        }
    }

    /** Retires already-admitted calls for this engine; later calls can use the same worker. */
    fun retire() = synchronized(retirementLock) {
        retirementEpoch++
        interruptCurrent()
    }

    /** Interrupts the job that is running now, if any (the stop button). Gates treat it as STOP. */
    fun interruptCurrent() {
        current.get()?.interrupt()
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    /** A cancelled future reports done at once; the thread is free only once [current] clears. */
    private fun abandonIfStuck(exec: ExecutorService, future: Future<*>) {
        val stuck = current.get() ?: return
        val deadline = now() + 1000
        while (current.get() === stuck && now() < deadline) Thread.sleep(20)
        if (current.get() === stuck && executor === exec) {
            current.compareAndSet(stuck, null)
            executor = newExecutor()
            exec.shutdownNow()
        }
    }

    companion object {
        private const val POLL_MS = 50L
        internal fun now(): Long = System.nanoTime() / 1_000_000
    }
}
