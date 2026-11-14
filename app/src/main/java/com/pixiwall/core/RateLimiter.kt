package com.pixiwall.core

import java.util.ArrayDeque

/** Allows at most [max] events in any sliding window of [windowMs] milliseconds. */
class RateLimiter(
    private val max: Int,
    private val windowMs: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val t = now()
        while (true) {
            val oldest = times.peekFirst() ?: break
            if (t - oldest >= windowMs) times.pollFirst() else break
        }
        if (times.size >= max) return false
        times.addLast(t)
        return true
    }
}
