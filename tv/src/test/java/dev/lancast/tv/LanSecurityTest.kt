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

}
