package dev.lancast.shared

import org.junit.Assert.*
import org.junit.Test

class ExpiringSessionStoreTest {
    @Test fun getDoesNotRenewFixedExpiry() {
        var now = 0L
        val sessions = ExpiringSessionStore<String>(ttlMillis = 1000, nowMillis = { now })
        assertTrue(sessions.put("token", "peer"))
        now = 999
        assertEquals("peer", sessions.get("token"))
        now = 1000
        assertNull(sessions.get("token"))
        assertEquals(0, sessions.size())
    }

    @Test fun capacityFailsClosedAndExpires() {
        var now = 0L
        val sessions = ExpiringSessionStore<String>(ttlMillis = 1000, maxSessions = 1, nowMillis = { now })
        assertTrue(sessions.put("a", "first"))
        assertFalse(sessions.put("b", "second"))
        assertEquals("first", sessions.get("a"))
        now = 1000
        assertTrue(sessions.put("b", "second"))
        assertNull(sessions.get("a"))
        assertEquals("second", sessions.get("b"))
    }

    @Test fun explicitReplacementRenewsSessionAtCapacity() {
        var now = 0L
        val sessions = ExpiringSessionStore<String>(ttlMillis = 1000, maxSessions = 1, nowMillis = { now })
        assertTrue(sessions.put("a", "first"))
        now = 500
        assertTrue(sessions.put("a", "replacement"))
        now = 1000
        assertEquals("replacement", sessions.get("a"))
        now = 1500
        assertNull(sessions.get("a"))
    }

    @Test fun removeAndClearRevokeSessions() {
        val sessions = ExpiringSessionStore<String>()
        sessions.put("a", "first")
        sessions.put("b", "second")
        assertEquals("first", sessions.remove("a"))
        assertNull(sessions.get("a"))
        assertNull(sessions.remove("missing"))
        sessions.clear()
        assertNull(sessions.get("b"))
        assertEquals(0, sessions.size())
    }

    @Test fun removeNeverReturnsExpiredValues() {
        var now = 0L
        val sessions = ExpiringSessionStore<String>(ttlMillis = 1000, nowMillis = { now })
        sessions.put("a", "first")
        now = 1000
        assertNull(sessions.remove("a"))
    }

    @Test fun sizePrunesExpiredSessions() {
        var now = 0L
        val sessions = ExpiringSessionStore<String>(ttlMillis = 1000, nowMillis = { now })
        sessions.put("a", "first")
        now = 500
        sessions.put("b", "second")
        now = 1000
        assertEquals(1, sessions.size())
        now = 1500
        assertEquals(0, sessions.size())
    }

    @Test fun backwardClockDoesNotExpireLiveSessions() {
        var now = 100L
        val sessions = ExpiringSessionStore<String>(ttlMillis = 1000, nowMillis = { now })
        sessions.put("a", "first")
        now = 0L
        assertEquals("first", sessions.get("a"))
    }

    @Test fun rejectsUnboundedOrBlankKeys() {
        val sessions = ExpiringSessionStore<String>()
        assertFalse(sessions.put("", "value"))
        assertFalse(sessions.put("  ", "value"))
        assertFalse(sessions.put("x".repeat(257), "value"))
        assertEquals(0, sessions.size())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidConfiguration() {
        ExpiringSessionStore<String>(ttlMillis = 0)
    }
}
