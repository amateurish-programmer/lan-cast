package dev.lancast.phone

import org.junit.Assert.*
import org.junit.Test

class ReceiverEligibilityTest {
    @Test fun boundsNamesAndRemovesDeceptiveControls() {
        assertFalse(ReceiverEligibility.validName(" "))
        assertFalse(ReceiverEligibility.validName("电".repeat(86)))
        assertTrue(ReceiverEligibility.validName("电".repeat(85)))
        assertEquals("TVsafe", ReceiverEligibility.displayName("TV\n\u202Esafe\u2069"))
        assertEquals("局域网电视", ReceiverEligibility.displayName("\u202E\n"))
        assertEquals(80, ReceiverEligibility.displayName("a".repeat(100)).length)
    }
    @Test fun acceptsOnlyCurrentLanReceivers() {
        assertTrue(ReceiverEligibility.accepts("2", 8765, "192.168.1.3"))
        assertTrue(ReceiverEligibility.accepts("2", 8765, "10.0.0.4"))
        assertTrue(ReceiverEligibility.accepts("2", 8765, "172.16.0.8"))
    }
    @Test fun rejectsPublicLoopbackIpv6AndUnspecifiedAddresses() {
        listOf("8.8.8.8", "127.0.0.1", "0.0.0.0", "::1", "fe80::1234", "localhost", "192.168.1.1/path").forEach {
            assertFalse(it, ReceiverEligibility.accepts("2", 8765, it))
        }
    }
    @Test fun rejectsOtherProtocolsAndPorts() {
        listOf(null, "", "1", "3", "02").forEach { assertFalse(ReceiverEligibility.accepts(it, 8765, "192.168.1.3")) }
        listOf(-1, 0, 80, 443, 8766, 65536).forEach { assertFalse(ReceiverEligibility.accepts("2", it, "192.168.1.3")) }
    }
}
