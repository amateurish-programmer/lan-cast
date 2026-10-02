package dev.lancast.shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PairingRateLimiterTest {
    @Test fun capsAttemptsPerPeerAndUnlocksAtWindowBoundary() {
        var now = 0L
        val limiter = PairingRateLimiter(maxAttempts = 2, windowMillis = 1000, nowMillis = { now })
        assertTrue(limiter.tryAcquire("peer"))
        now = 100
        assertTrue(limiter.tryAcquire("peer"))
        assertFalse(limiter.tryAcquire("peer"))
        assertTrue(limiter.tryAcquire("different-peer"))
        now = 999
        assertFalse(limiter.tryAcquire("peer"))
        now = 1000
        assertTrue(limiter.tryAcquire("peer"))
        assertFalse(limiter.tryAcquire("peer"))
        now = 1100
        assertTrue(limiter.tryAcquire("peer"))
    }

    @Test fun failsClosedAtCapacityAndPrunesOldPeers() {
        var now = 0L
        val limiter = PairingRateLimiter(maxKeys = 1, windowMillis = 1000, nowMillis = { now })
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("b"))
        now = 1000
        assertTrue(limiter.tryAcquire("b"))
    }

    @Test fun rejectsUnboundedOrBlankKeys() {
        val limiter = PairingRateLimiter()
        assertFalse(limiter.tryAcquire(""))
        assertFalse(limiter.tryAcquire("  "))
        assertFalse(limiter.tryAcquire("x".repeat(129)))
    }

    @Test fun backwardClockDoesNotUnlockAttempts() {
        var now = 100L
        val limiter = PairingRateLimiter(maxAttempts = 1, windowMillis = 100, nowMillis = { now })
        assertTrue(limiter.tryAcquire("peer"))
        now = 0
        assertFalse(limiter.tryAcquire("peer"))
        now = 200
        assertTrue(limiter.tryAcquire("peer"))
    }

    @Test fun clearStartsANewLifecycle() {
        val limiter = PairingRateLimiter(maxAttempts = 1)
        assertTrue(limiter.tryAcquire("peer"))
        assertFalse(limiter.tryAcquire("peer"))
        limiter.clear()
        assertTrue(limiter.tryAcquire("peer"))
    }

    @Test fun concurrentRequestsCannotExceedBudget() {
        val limiter = PairingRateLimiter(maxAttempts = 5, nowMillis = { 0L })
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(1)
        val accepted = AtomicInteger()
        try {
            repeat(100) {
                pool.submit {
                    latch.await()
                    if (limiter.tryAcquire("peer")) accepted.incrementAndGet()
                }
            }
            latch.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals(5, accepted.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidConfiguration() {
        PairingRateLimiter(maxAttempts = 0)
    }
}
