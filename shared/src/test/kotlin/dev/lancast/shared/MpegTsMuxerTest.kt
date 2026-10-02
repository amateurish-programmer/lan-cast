package dev.lancast.shared

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class MpegTsMuxerTest {
    @Test fun crcMatchesMpeg2KnownVector() {
        assertEquals(0x0376e6e7, MpegTsMuxer.mpegCrc32("123456789".toByteArray()))
    }

    @Test fun patAndPmtHaveCorrectSectionsPidsAndCrc() {
        val chunks = mutableListOf<ByteArray>()
        MpegTsMuxer { chunks += it }.writeVideo(frame(30), 1_000_000, true)
        assertEquals(1, chunks.size)
        val packets = packets(chunks.single())
        val pat = packets[0]
        val pmt = packets[1]
        assertEquals(MpegTsMuxer.PAT_PID, pid(pat))
        assertEquals(MpegTsMuxer.PMT_PID, pid(pmt))
        for (packet in listOf(pat, pmt)) {
            assertEquals(0x40, u(packet[1]) and 0x40)
            assertEquals(0, u(packet[4])) // PSI pointer field
            val length = ((u(packet[6]) and 0x0f) shl 8) or u(packet[7])
            val section = packet.copyOfRange(5, 8 + length)
            assertEquals(0, MpegTsMuxer.mpegCrc32(section))
            assertTrue(packet.drop(8 + length).all { u(it) == 0xff })
        }
        assertEquals(0, u(pat[5]))
        assertEquals(13, u(pat[7]))
        assertEquals(MpegTsMuxer.PMT_PID, ((u(pat[15]) and 31) shl 8) or u(pat[16]))
        assertEquals(2, u(pmt[5]))
        assertEquals(23, u(pmt[7]))
        assertEquals(MpegTsMuxer.VIDEO_PID, ((u(pmt[13]) and 31) shl 8) or u(pmt[14]))
        assertEquals(0x1b, u(pmt[17]))
        assertEquals(MpegTsMuxer.VIDEO_PID, ((u(pmt[18]) and 31) shl 8) or u(pmt[19]))
        assertEquals(0x0f, u(pmt[22]))
        assertEquals(MpegTsMuxer.AUDIO_PID, ((u(pmt[23]) and 31) shl 8) or u(pmt[24]))
    }

    @Test fun videoPesRoundTripsAcrossPacketBoundariesWithoutPayloadStuffing() {
        // Cover every possible packet tail, including adaptation_field_length=0.
        for (size in 5..500) {
            val chunks = mutableListOf<ByteArray>()
            val accessUnit = frame(size)
            MpegTsMuxer { chunks += it }.writeVideo(accessUnit, 1_000_000, true)
            val video = packets(chunks.single()).filter { pid(it) == MpegTsMuxer.VIDEO_PID }
            val pes = payload(video)
            assertArrayEquals(byteArrayOf(0, 0, 1, 0xe0.toByte()), pes.copyOfRange(0, 4))
            assertEquals(0, u(pes[4]) or u(pes[5]))
            assertEquals(0x84, u(pes[6]))
            assertEquals(0x80, u(pes[7]))
            assertEquals(5, u(pes[8]))
            assertEquals(99_000L, pts(pes))
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 9, 0xf0.toByte()) + accessUnit,
                pes.copyOfRange(14, pes.size))
            assertEquals(0x50, u(video[0][5]) and 0x50)
            assertEquals(90_000L, pcr(video[0]))
            assertEquals(0x7e, u(video[0][10]) and 0x7e)
            assertEquals(0, u(video[0][11]))
            video.forEachIndexed { index, packet ->
                assertEquals(index and 15, u(packet[3]) and 15)
                assertEquals(if (index == 0) 0x40 else 0, u(packet[1]) and 0x40)
                assertEquals(0, u(packet[1]) and 0x80) // transport_error_indicator
            }
        }
    }

    @Test fun audioPesHasBoundedLengthAndExactAdtsPayload() {
        val chunks = mutableListOf<ByteArray>()
        val adts = Adts.header(600, 48000, 2) + ByteArray(600) { it.toByte() }
        MpegTsMuxer { chunks += it }.writeAudio(adts, 500_000)
        val all = packets(chunks.single())
        val audio = all.filter { pid(it) == MpegTsMuxer.AUDIO_PID }
        val pes = payload(audio)
        assertEquals(0xc0, u(pes[3]))
        assertEquals(adts.size + 8, (u(pes[4]) shl 8) or u(pes[5]))
        assertEquals(54_000L, pts(pes))
        assertArrayEquals(adts, pes.copyOfRange(14, pes.size))
        val clock = all.single { pid(it) == MpegTsMuxer.VIDEO_PID }
        assertEquals(2, u(clock[3]) shr 4)
        assertEquals(183, u(clock[4]))
        assertEquals(45_000L, pcr(clock))
    }

    @Test fun continuityIsIndependentPerPidAndWrapsModulo16() {
        val chunks = mutableListOf<ByteArray>()
        val muxer = MpegTsMuxer { chunks += it }
        repeat(24) { i ->
            muxer.writeVideo(frame(400), i * 100_000L, true)
            muxer.writeAudio(Adts.header(300, 48000, 2) + ByteArray(300), i * 100_000L + 50_000)
        }
        val previous = mutableMapOf<Int, Int>()
        for (packet in chunks.flatMap(::packets)) {
            val id = pid(packet)
            val cc = u(packet[3]) and 15
            val hasPayload = (u(packet[3]) shr 4) and 1 != 0
            val old = previous[id]
            if (old != null) assertEquals(if (hasPayload) (old + 1) and 15 else old, cc)
            previous[id] = cc
        }
    }

    @Test fun repeatsTablesOnKeyframesAndAt100Milliseconds() {
        val chunks = mutableListOf<ByteArray>()
        val muxer = MpegTsMuxer { chunks += it }
        muxer.writeVideo(frame(10), 0, false)
        muxer.writeVideo(frame(10), 33_333, false)
        muxer.writeVideo(frame(10), 66_666, true)
        muxer.writeVideo(frame(10), 100_000, false)
        muxer.writeVideo(frame(10), 166_666, false)
        assertEquals(listOf(1, 0, 1, 0, 1), chunks.map { chunk ->
            packets(chunk).count { pid(it) == MpegTsMuxer.PAT_PID }
        })
    }

    @Test fun ptsMarkersWrapAndPerTrackTimestampsNeverRegress() {
        val chunks = mutableListOf<ByteArray>()
        val muxer = MpegTsMuxer { chunks += it }
        val us = 100_000_000_000L // Beyond the 33-bit, 90 kHz wrap point.
        muxer.writeVideo(frame(10), us, true)
        muxer.writeVideo(frame(10), us, false)
        muxer.writeVideo(frame(10), us - 100_000, false)
        val times = chunks.map { chunk ->
            pts(payload(packets(chunk).filter { pid(it) == MpegTsMuxer.VIDEO_PID }))
        }
        val first = (9_000_000_000L + 9000) and 0x1ffffffffL
        assertEquals(listOf(first, first + 1, first + 2), times)
    }

    @Test fun existingAudIsPreservedAndNotDuplicated() {
        val chunks = mutableListOf<ByteArray>()
        val accessUnit = byteArrayOf(0, 0, 1, 9, 0xf0.toByte()) + frame(10)
        MpegTsMuxer { chunks += it }.writeVideo(accessUnit, 0, true)
        val pes = payload(packets(chunks.single()).filter { pid(it) == MpegTsMuxer.VIDEO_PID })
        assertArrayEquals(accessUnit, pes.copyOfRange(14, pes.size))
    }

    @Test fun adtsHeaderIsAacLc48KhzStereoWithCorrectLengthAndFullness() {
        val header = Adts.header(100, 48000, 2)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x4c, 0x80.toByte(),
            0x0d, 0x7f, 0xfc.toByte()), header)
        assertEquals(107, ((u(header[3]) and 3) shl 11) or (u(header[4]) shl 3) or (u(header[5]) shr 5))
        assertEquals(7, ((u(Adts.header(1, 48000, 8)[2]) and 1) shl 2) or
            (u(Adts.header(1, 48000, 8)[3]) shr 6))
        expectIllegalArgument { Adts.header(1, 12345, 2) }
        expectIllegalArgument { Adts.header(8185, 48000, 2) }
        expectIllegalArgument { Adts.header(0, 48000, 2) }
        expectIllegalArgument { Adts.header(1, 48000, 7) }
    }

    @Test fun avcConvertsLengthPrefixedNalsAndAvccConfiguration() {
        val expected = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68)
        assertArrayEquals(expected, Avc.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x67, 0x42, 0, 0, 0, 1, 0x68)))
        assertArrayEquals(expected, Avc.toAnnexB(byteArrayOf(2, 0x67, 0x42, 1, 0x68), 1))
        assertArrayEquals(expected, Avc.configurationToAnnexB(byteArrayOf(
            1, 0x42, 0, 0x1e, 0xff.toByte(), 0xe1.toByte(), 0, 2, 0x67, 0x42, 1, 0, 1, 0x68,
        )))
        assertArrayEquals(expected, Avc.toAnnexB(expected))
        expectIllegalArgument { Avc.toAnnexB(byteArrayOf(0, 0, 0, 5, 0x65)) }
        expectIllegalArgument { Avc.configurationToAnnexB(byteArrayOf(1, 0x42, 0, 0x1e, 0xff.toByte(), 0xe1.toByte(), 0)) }
    }

    @Test fun rejectsInvalidInputsBeforeEmittingData() {
        val chunks = mutableListOf<ByteArray>()
        val muxer = MpegTsMuxer { chunks += it }
        expectIllegalArgument { muxer.writeAudio(ByteArray(7), 0) }
        expectIllegalArgument { muxer.writeVideo(byteArrayOf(0x65), 0, true) }
        expectIllegalArgument { muxer.writeVideo(frame(10), -1, true) }
        assertTrue(chunks.isEmpty())
    }

    private fun frame(size: Int): ByteArray = ByteArray(size) { (it + 1).toByte() }.also {
        it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 1; it[4] = 0x65
    }

    private fun packets(data: ByteArray): List<ByteArray> {
        assertEquals(0, data.size % 188)
        return (data.indices step 188).map { offset ->
            data.copyOfRange(offset, offset + 188).also { assertEquals(0x47, u(it[0])) }
        }
    }

    private fun pid(packet: ByteArray): Int = ((u(packet[1]) and 31) shl 8) or u(packet[2])

    private fun payload(packets: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (packet in packets) {
            val afc = (u(packet[3]) shr 4) and 3
            if (afc and 1 == 0) continue
            val start = if (afc and 2 != 0) 5 + u(packet[4]) else 4
            assertTrue(start in 4..188)
            out.write(packet, start, 188 - start)
        }
        return out.toByteArray()
    }

    private fun pts(pes: ByteArray): Long {
        assertEquals(0x21, u(pes[9]) and 0xf1)
        assertEquals(1, u(pes[11]) and 1)
        assertEquals(1, u(pes[13]) and 1)
        return (((u(pes[9]) shr 1) and 7).toLong() shl 30) or
            (u(pes[10]).toLong() shl 22) or ((u(pes[11]) shr 1).toLong() shl 15) or
            (u(pes[12]).toLong() shl 7) or (u(pes[13]) shr 1).toLong()
    }

    private fun pcr(packet: ByteArray): Long = (u(packet[6]).toLong() shl 25) or
        (u(packet[7]).toLong() shl 17) or (u(packet[8]).toLong() shl 9) or
        (u(packet[9]).toLong() shl 1) or (u(packet[10]) shr 7).toLong()

    private fun u(byte: Byte) = byte.toInt() and 0xff

    private fun expectIllegalArgument(block: () -> Unit) {
        try { block(); fail("Expected IllegalArgumentException") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }
}
