package dev.lancast.shared

/**
 * Thread-safe, in-memory, bounded sessions with fixed lifetimes. Reads do not renew
 * expiration. Values and keys are not persisted; the caller generates cryptographically
 * random keys and may bind values to the paired socket address. Expiration uses elapsed
 * monotonic time so changing the wall clock cannot extend an active session.
 */
class ExpiringSessionStore<T : Any>(
    private val ttlMillis: Long = 30 * 60_000L,
    private val maxSessions: Int = 16,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry<T>(val value: T, val createdAt: Long)
    private val sessions = mutableMapOf<String, Entry<T>>()

    init {
        require(ttlMillis > 0L && maxSessions > 0)
    }

    /** Returns false for invalid keys or capacity exhaustion, rather than evicting live peers. */
    @Synchronized
    fun put(key: String, value: T): Boolean {
        if (key.isBlank() || key.length > 256) return false
        val now = nowMillis()
        prune(now)
        if (key !in sessions && sessions.size >= maxSessions) return false
        sessions[key] = Entry(value, now)
        return true
    }

    @Synchronized
    fun get(key: String): T? {
        val entry = sessions[key] ?: return null
        if (nowMillis() - entry.createdAt >= ttlMillis) {
            sessions.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun remove(key: String): T? {
        val entry = sessions.remove(key) ?: return null
        return if (nowMillis() - entry.createdAt >= ttlMillis) null else entry.value
    }

    @Synchronized
    fun clear() = sessions.clear()

    @Synchronized
    fun size(): Int {
        prune(nowMillis())
        return sessions.size
    }

    private fun prune(now: Long) {
        sessions.entries.removeAll { now - it.value.createdAt >= ttlMillis }
    }
}
