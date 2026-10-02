package dev.lancast.shared

import org.junit.Assert.*
import org.junit.Test

class HttpRangeTest {
    @Test fun absentRangeReturnsFullLength() {
        val full = HttpRange.parse(null, 1000) as RangeResult.Full
        assertEquals(200, full.responseCode)
        assertEquals(1000L, full.contentLength)
    }

    @Test fun emptyRepresentationWithoutRangeIsValid() {
        assertEquals(RangeResult.Full(0), HttpRange.parse(null, 0))
    }

    @Test fun closedRangeUsesInclusiveEnd() {
        val part = HttpRange.parse("bytes=100-199", 1000) as RangeResult.Partial
        assertEquals(206, part.responseCode)
        assertEquals(100L, part.contentLength)
        assertEquals("bytes 100-199/1000", part.contentRange)
        assertEquals(RangeResult.Partial(100, 199, 1000), part)
    }

    @Test fun singleByteRange() {
        val part = HttpRange.parse("bytes=0-0", 1) as RangeResult.Partial
        assertEquals(1L, part.contentLength)
        assertEquals("bytes 0-0/1", part.contentRange)
    }

    @Test fun openRangeResolvesToLastByte() {
        assertEquals(RangeResult.Partial(400, 999, 1000), HttpRange.parse("bytes=400-", 1000))
    }

    @Test fun suffixRangeResolvesFromEnd() {
        assertEquals(RangeResult.Partial(900, 999, 1000), HttpRange.parse("bytes=-100", 1000))
    }

    @Test fun suffixClampsToFullRepresentationButRemainsPartial() {
        assertEquals(RangeResult.Partial(0, 999, 1000), HttpRange.parse("bytes=-1001", 1000))
    }

    @Test fun endClampsToLastByte() {
        assertEquals(RangeResult.Partial(900, 999, 1000), HttpRange.parse("bytes=900-2000", 1000))
    }

    @Test fun acceptsCaseInsensitiveUnitAndOuterHttpWhitespace() {
        assertEquals(RangeResult.Partial(0, 9, 10), HttpRange.parse(" \tBYTES=000-0009\t ", 10))
    }

    @Test fun allRangesOnEmptyRepresentationAreUnsatisfiable() {
        for (header in listOf("bytes=0-0", "bytes=0-", "bytes=-1", "bytes=-0")) {
            assertEquals(header, RangeResult.Unsatisfiable(0), HttpRange.parse(header, 0))
        }
    }

    @Test fun zeroSuffixIsUnsatisfiable() {
        assertEquals(RangeResult.Unsatisfiable(10), HttpRange.parse("bytes=-0000", 10))
    }

    @Test fun startAtOrBeyondLengthIsUnsatisfiable() {
        for (header in listOf("bytes=1000-", "bytes=1000-2000", "bytes=1001-")) {
            val result = HttpRange.parse(header, 1000) as RangeResult.Unsatisfiable
            assertEquals(416, result.responseCode)
            assertEquals("bytes */1000", result.contentRange)
        }
    }

    @Test fun malformedAndMultipleRangesAreRejected() {
        val invalid = listOf(
            "", " ", "bytes=-", "bytes=", "bytes=1-0", "bytes=1--2", "bytes=+1-2",
            "bytes=1-+2", "bytes= 1-2", "bytes=1 -2",
            "bytes =1-2", "bytes=1-2,3-4", "bytes=1-2,", "items=1-2", "bytes=1.0-2",
            "bytes=١-٢", "bytes=1-2\r\n", "bytes=1-2\nX-Test: hi", "bytes=1-2\u00a0",
        )
        for (header in invalid) {
            assertTrue("Should reject $header", HttpRange.parse(header, 10) is RangeResult.Invalid)
        }
        assertEquals(RangeResult.Partial(1, 2, 10), HttpRange.parse("bytes=1-2 ", 10))
    }

    @Test fun giantEndAndSuffixClampWithoutIntegerOverflow() {
        val huge = "9".repeat(100)
        assertEquals(RangeResult.Partial(1, 9, 10), HttpRange.parse("bytes=1-$huge", 10))
        assertEquals(RangeResult.Partial(0, 9, 10), HttpRange.parse("bytes=-$huge", 10))
        assertEquals(RangeResult.Unsatisfiable(10), HttpRange.parse("bytes=$huge-", 10))
        assertTrue(HttpRange.parse("bytes=$huge-1", 10) is RangeResult.Invalid)
    }

    @Test fun extremeResourceLengthDoesNotOverflow() {
        val part = HttpRange.parse("bytes=0-9223372036854775807", Long.MAX_VALUE) as RangeResult.Partial
        assertEquals(Long.MAX_VALUE, part.contentLength)
        assertEquals(Long.MAX_VALUE - 1L, part.endInclusive)
        assertEquals(RangeResult.Unsatisfiable(Long.MAX_VALUE), HttpRange.parse("bytes=9223372036854775807-", Long.MAX_VALUE))
    }

    @Test fun overlongHeaderIsBounded() {
        assertTrue(HttpRange.parse("bytes=-" + "9".repeat(1024), 10) is RangeResult.Invalid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownLengthIsNotSilentlyAccepted() {
        HttpRange.parse(null, -1)
    }
}
