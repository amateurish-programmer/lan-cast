package dev.lancast.tv

import org.junit.Assert.*
import org.junit.Test

class LanSecurityTest {
    private val ip = "192.168.1.20"
    private val token = "a".repeat(64)

    @Test fun acceptsOnlyExactPrivatePhonePaths() {
        assertTrue(LanUrlPolicy.accepts("http://$ip:8766/media/$token", ip))
        assertTrue(LanUrlPolicy.accepts("http://$ip:8766/stream/$token", ip, mirror = true))
        val rejected = listOf(
            "https://$ip:8766/media/$token", "http://127.0.0.1:8766/media/$token",
            "http://192.168.1.21:8766/media/$token", "http://$ip:80/media/$token",
            "http://user@$ip:8766/media/$token", "http://$ip:8766/media/$token?q=x",
            "http://$ip:8766/media/$token#fragment", "http://example.com:8766/media/$token",
            "http://$ip:8766/media/../$token", "http://$ip:8766/media/%61$token",
            "http://$ip:8766/stream/$token", "http://$ip:8766/media/short"
        )
        rejected.forEach { assertFalse(it, LanUrlPolicy.accepts(it, ip)) }
        assertFalse(LanUrlPolicy.accepts("http://8.8.8.8:8766/media/$token", "8.8.8.8"))
        assertFalse(LanUrlPolicy.accepts("http://$ip:8766/stream/abc", ip, mirror = true))
    }

    @Test fun rejectsNonCanonicalIpAddresses() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1").forEach {
            assertTrue(it, LanUrlPolicy.isPrivateIpv4(it))
        }
        listOf("127.0.0.1", "169.254.1.1", "172.15.0.1", "172.32.0.1", "10.01.0.1",
            "192.168.1.999", "::1", "localhost", "0x0a.0.0.1", "3232235777").forEach {
            assertFalse(it, LanUrlPolicy.isPrivateIpv4(it))
        }
    }

    @Test fun pairsThenBindsTokenToPhoneAndExpires() {
        var time = 0L
        val auth = PairingSecurity(now = { time })
        val result = auth.pair(auth.snapshot().code, ip) as PairingSecurity.PairResult.Accepted
        assertEquals(43, result.token.length)
        assertTrue(auth.authorized("Bearer ${result.token}", ip))
        assertFalse(auth.authorized("Bearer ${result.token}", "192.168.1.21"))
        assertFalse(auth.authorized("Bearer wrong", ip))
        assertFalse(auth.authorized(null, ip))
        assertEquals(PairingSecurity.PairResult.AlreadyPaired, auth.pair(auth.snapshot().code, ip))
        time += PairingSecurity.SESSION_LIFETIME_MS
        assertFalse(auth.authorized("Bearer ${result.token}", ip))
        assertTrue(auth.expireSession())
        assertNull(auth.snapshot().pairedIp)
    }

    @Test fun rotationRevokesSession() {
        val auth = PairingSecurity()
        val result = auth.pair(auth.snapshot().code, ip) as PairingSecurity.PairResult.Accepted
        auth.rotate()
        assertFalse(auth.authorized("Bearer ${result.token}", ip))
    }

    @Test fun codeExpiresAndGlobalRateLimitCannotBeRotatedAway() {
        var time = 0L
        val auth = PairingSecurity(now = { time })
        repeat(PairingSecurity.MAX_ATTEMPTS) {
            assertEquals(PairingSecurity.PairResult.Incorrect, auth.pair("000000", ip))
        }
        auth.rotate()
        assertEquals(PairingSecurity.PairResult.RateLimited, auth.pair(auth.snapshot().code, ip))
        time += PairingSecurity.ATTEMPT_WINDOW_MS
        assertEquals(PairingSecurity.PairResult.Incorrect, auth.pair("000000", ip))
        time += PairingSecurity.CODE_LIFETIME_MS
        assertEquals(PairingSecurity.PairResult.Expired, auth.pair(auth.snapshot().code, ip))
    }
}
