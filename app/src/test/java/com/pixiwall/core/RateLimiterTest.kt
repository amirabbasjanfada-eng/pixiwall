package com.pixiwall.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    @Test
    fun oldEventsLeaveTheWindowOneByOne() {
        var t = 0L
        val limiter = RateLimiter(max = 2, windowMs = 1000L) { t }
        assertTrue(limiter.tryAcquire()) // t = 0
        t = 400
        assertTrue(limiter.tryAcquire()) // t = 400
        t = 999
        assertFalse(limiter.tryAcquire()) // both are still inside the window
        t = 1000
        assertTrue(limiter.tryAcquire()) // the event at 0 expired: now [400, 1000]
        assertFalse(limiter.tryAcquire()) // 400 is still inside (1000 - 400 < 1000)
        t = 1400
        assertTrue(limiter.tryAcquire()) // 400 expired as well
    }

    @Test
    fun aStormOfRequestsIsCutOff() {
        var t = 0L
        val limiter = RateLimiter(max = 4, windowMs = 15_000L) { t }
        var allowed = 0
        repeat(1000) { // 1000 rebuild requests within 5 seconds
            t += 5
            if (limiter.tryAcquire()) allowed++
        }
        assertEquals(4, allowed)
    }
}
