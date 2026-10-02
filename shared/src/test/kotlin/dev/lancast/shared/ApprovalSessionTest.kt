package dev.lancast.shared

import org.junit.Assert.*
import org.junit.Test

class ApprovalSessionTest {
    @Test fun requesterNameCannotInjectDisplayControls() {
        val security = ApprovalSession()
        val request = (security.start("192.168.1.2", "Phone\u202E\nSafe") as ApprovalSession.Start.Pending).request
        assertEquals("PhoneSafe", request.name)
    }

    private val ip = "192.168.1.2"
    private fun request(s: ApprovalSession) = (s.start(ip, "Phone") as ApprovalSession.Start.Pending).request
    @Test fun disconnectRequiresCurrentPeerAndRevokesCapability() {
        val s = ApprovalSession(); val r = request(s)
        s.decide(r.id, true)
        val header = "Bearer ${s.poll(r.id, r.secret, ip)!!.token}"
        assertFalse(s.disconnect(header, "192.168.1.3"))
        assertTrue(s.authorized(header, ip))
        assertTrue(s.disconnect(header, ip))
        assertFalse(s.authorized(header, ip))
        assertNull(s.poll(r.id, r.secret, ip))
    }
    @Test fun stoppedServerCannotRecreatePendingRequest() {
        val s = ApprovalSession()
        assertEquals(ApprovalSession.Start.Busy, s.start(ip, "Phone") { false })
        assertNull(s.pending())
    }
    @Test fun explicitDecisionRequiredAndCapabilitiesBindToIp() {
        val s = ApprovalSession(); val r = request(s)
        assertEquals(43, r.secret.length)
        assertEquals(43, r.id.length)
        assertEquals("pending", s.poll(r.id, r.secret, ip)?.status)
        assertNull(s.poll(r.id, r.secret, "192.168.1.3"))
        assertNull(s.poll(r.id, "bad", ip))
        assertNull(s.poll("bad", r.secret, ip))
        assertFalse(s.authorized("Bearer ${r.secret}", ip))
        assertTrue(s.decide(r.id, true))
        val token = s.poll(r.id, r.secret, ip)!!.token!!
        assertTrue(s.authorized("Bearer $token", ip))
        assertFalse(s.authorized("Bearer $token", "192.168.1.3"))
        assertEquals(ApprovalSession.Start.Busy, s.start(ip, "Other"))
    }
    @Test fun declineCancelAndStaleDialogsFailClosed() {
        val s = ApprovalSession(); val a = request(s)
        assertTrue(s.decide(a.id, false))
        assertEquals("declined", s.poll(a.id, a.secret, ip)?.status)
        assertFalse(s.decide(a.id, true))
        val b = request(s)
        assertFalse(s.decide(a.id, true))
        assertTrue(s.cancel(b.id, b.secret, ip))
        assertFalse(s.decide(b.id, true))
        assertEquals("cancelled", s.poll(b.id, b.secret, ip)?.status)
    }
    @Test fun cancelRacingApprovalRevokesToken() {
        val s = ApprovalSession(); val r = request(s)
        s.decide(r.id, true)
        val t = s.poll(r.id, r.secret, ip)!!.token!!
        assertFalse(s.cancel(r.id, r.secret, "192.168.1.3"))
        assertTrue(s.cancel(r.id, r.secret, ip))
        assertFalse(s.authorized("Bearer $t", ip))
    }
    @Test fun timeoutAndShutdownRejectStaleCallbacks() {
        var now = 0L; val s = ApprovalSession { now }; val r = request(s)
        now = ApprovalSession.REQUEST_MS
        assertFalse(s.decide(r.id, true))
        assertEquals("expired", s.poll(r.id, r.secret, ip)?.status)
        val next = request(s)
        s.revoke()
        assertFalse(s.decide(next.id, true))
        assertNull(s.poll(next.id, next.secret, ip))
    }
    @Test fun sessionHasFixedLifetimeAndRevocationDoesNotResetRateLimit() {
        var now = 0L; val s = ApprovalSession { now }; val r = request(s)
        s.decide(r.id, true)
        val t = s.poll(r.id, r.secret, ip)!!.token!!
        now = ApprovalSession.SESSION_MS
        assertFalse(s.authorized("Bearer $t", ip))
        repeat(5) { request(s); s.revoke() }
        assertEquals(ApprovalSession.Start.Limited, s.start(ip, "Phone"))
        now += 60_000
        assertTrue(s.start(ip, "Phone") is ApprovalSession.Start.Pending)
    }
}
