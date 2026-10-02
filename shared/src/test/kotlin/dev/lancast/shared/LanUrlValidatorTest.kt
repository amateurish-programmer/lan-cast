package dev.lancast.shared

import org.junit.Assert.*
import org.junit.Test

class LanUrlValidatorTest {
    private val port = 8090
    private val path = "/media/test-token"

    @Test fun acceptsOnlyTheThreePrivateIpv4Blocks() {
        val accepted = listOf("10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255")
        for (host in accepted) {
            assertTrue(host, LanUrlValidator.isPrivateIpv4(host))
            assertNotNull(host, LanUrlValidator.validate("http://$host:$port$path", port, path))
        }
    }

    @Test fun rejectsPublicLoopbackLinkLocalAndNonCanonicalIpForms() {
        val rejected = listOf(
            "8.8.8.8", "127.0.0.1", "169.254.1.1", "100.64.0.1", "172.15.255.255", "172.32.0.0",
            "192.167.255.255", "192.169.0.0", "0.0.0.0", "255.255.255.255", "224.0.0.1",
            "10.0.0.256", "10.0.0.-1", "010.0.0.1", "10.00.0.1", "10.0.0.01", "10.1",
            "167772161", "0x0a000001", "10.0.0.1.", "10.0.0.1.evil.test", "localhost", "phone.local",
            "::1", "::ffff:10.0.0.1", "10.0.0.+1", "10.0.0.１", "", ".", "10.0..1",
        )
        for (host in rejected) {
            assertFalse(host, LanUrlValidator.isPrivateIpv4(host))
            assertNull(host, LanUrlValidator.validate("http://$host:$port$path", port, path))
        }
    }

    @Test fun rejectsUnexpectedSchemePortPathOrAuthoritySyntax() {
        val bad = listOf(
            "https://10.0.0.1:$port$path", "HTTP://10.0.0.1:$port$path", "//10.0.0.1:$port$path",
            "http://10.0.0.1$path", "http://10.0.0.1:8091$path", "http://10.0.0.1:08090$path",
            "http://10.0.0.1:$port$path/", "http://10.0.0.1:$port/media/other", "http://10.0.0.1:$port/media/../media/test-token",
            "http://10.0.0.1:$port/media/%74est-token", "http://10.0.0.1:$port//media/test-token",
            "http://name@10.0.0.1:$port$path", "http://name:secret@10.0.0.1:$port$path", "http://@10.0.0.1:$port$path",
            "http://10.0.0.1:$port$path?", "http://10.0.0.1:$port$path?x=y", "http://10.0.0.1:$port$path#",
            "http://10.0.0.1:$port$path#fragment", "http://%31%30.0.0.1:$port$path", "http://[::ffff:10.0.0.1]:$port$path",
            " http://10.0.0.1:$port$path", "http://10.0.0.1:$port$path\n", "http://10.0.0.1:$port\\media\\test-token",
        )
        for (url in bad) assertNull(url, LanUrlValidator.validate(url, port, path))
    }

    @Test fun returnsTheOriginalValidatedUri() {
        val raw = "http://192.168.1.5:$port$path"
        val uri = LanUrlValidator.validate(raw, port, path)!!
        assertEquals(raw, uri.toASCIIString())
        assertEquals("192.168.1.5", uri.host)
        assertEquals(port, uri.port)
    }

    @Test fun rejectsUnboundedInput() {
        assertNull(LanUrlValidator.validate("http://10.0.0.1:$port/" + "x".repeat(3000), port, path))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidExpectedPortIsConfigurationError() {
        LanUrlValidator.validate("http://10.0.0.1:0$path", 0, path)
    }

    @Test(expected = IllegalArgumentException::class)
    fun relativeExpectedPathIsConfigurationError() {
        LanUrlValidator.validate("http://10.0.0.1:$port$path", port, "media/test-token")
    }
}
