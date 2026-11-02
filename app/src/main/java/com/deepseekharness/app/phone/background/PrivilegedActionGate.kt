package com.deepseekharness.app.phone.background

import java.util.concurrent.atomic.AtomicLong

/** UI intents invalidate older work before it reaches the single phone worker queue. */
class PrivilegedActionGate {
    private val generation = AtomicLong()
    fun select(): Long = generation.incrementAndGet()
    fun snapshot(): Long = generation.get()
    fun current(ticket: Long): Boolean = generation.get() == ticket
}
