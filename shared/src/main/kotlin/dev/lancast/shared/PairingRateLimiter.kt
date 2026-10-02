package dev.lancast.shared

import java.util.ArrayDeque

/**
 * Thread-safe, bounded sliding-window limit. Call before checking a pairing code and
 * key it by the socket's remote address, never a user-supplied forwarding header.
 * Success and failure both consume an attempt. Use a separate global key/instance too
 * when several clients must share one short-code guessing budget.
 */
class PairingRateLimiter(
    private val maxAttempts: Int = 5,
    private val windowMillis: Long = 60_000L,
    private val maxKeys: Int = 256,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val attempts = mutableMapOf<String, ArrayDeque<Long>>()

    init {
        require(maxAttempts > 0 && windowMillis > 0L && maxKeys > 0)
    }

    /** Denied attempts do not extend the lockout. New keys fail closed at capacity. */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        if (key.isBlank() || key.length > 128) return false
        val now = nowMillis()
        val iterator = attempts.entries.iterator()
        while (iterator.hasNext()) {
            val queue = iterator.next().value
            while (queue.isNotEmpty() && now - queue.peekFirst() >= windowMillis) queue.removeFirst()
            if (queue.isEmpty()) iterator.remove()
        }
        var queue = attempts[key]
        if (queue == null) {
            if (attempts.size >= maxKeys) return false
            queue = ArrayDeque()
            attempts[key] = queue
        }
        if (queue.size >= maxAttempts) return false
        queue.addLast(now)
        return true
    }

    /** Reset only when deliberately starting a new pairing lifecycle. */
    @Synchronized
    fun clear() = attempts.clear()
}
