package dev.lancast.shared

import java.io.ByteArrayOutputStream

/**
 * Small single-program MPEG-TS muxer for low-latency H.264 + AAC streaming.
 *
 * Both tracks must use the SAME monotonic microsecond timebase. H.264 must be
 * encoded without B-frames; each call supplies one complete access unit in decode
 * order, including SPS/PPS at every IDR for receivers joining mid-stream. AAC is
 * supplied as complete ADTS frames. A 100 ms presentation lead gives the receiver
 * time to assemble each access unit after its PCR. Timestamps wrap at 33 bits.
 *
 * Calls are serialized. [output] runs synchronously once per access unit with a
 * fresh buffer containing whole 188-byte TS packets. It must not block for slow
 * network clients; enqueue the buffer in a bounded broadcaster instead.
 */
class MpegTsMuxer(private val output: (ByteArray) -> Unit) {
    private val continuity = IntArray(8192)
    private val lastContinuity = IntArray(8192)
    private var lastVideoTime = -1L
    private var lastAudioTime = -1L
    private var lastClock = -1L
    private var lastTables = -1L
    private var lastPcr = -1L

    @Synchronized
    fun writeVideo(annexB: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        val nalOffset = Avc.startCodeLength(annexB)
        require(nalOffset > 0) { "H.264 access units must use Annex B start codes" }
        require(nalOffset < annexB.size) { "Missing H.264 NAL unit" }
        val time = timestamp(presentationTimeUs, lastVideoTime)
        lastVideoTime = time
        val clock = advanceClock(time)
        val chunk = ByteArrayOutputStream(annexB.size + 1024)
        writeTablesIfNeeded(chunk, clock, keyFrame)
        // MPEG-TS AVC access units need an AUD. A primary_pic_type of 7 permits
        // every slice type; preserve an existing AUD instead of duplicating it.
        val accessUnit = if ((annexB[nalOffset].toInt() and 0x1f) == 9) annexB else AUD + annexB
        writePes(chunk, VIDEO_PID, 0xe0, accessUnit, time + PRESENTATION_LEAD, clock, keyFrame)
        lastPcr = clock
        output(chunk.toByteArray())
    }

    @Synchronized
    fun writeAudio(adts: ByteArray, presentationTimeUs: Long) {
        require(adts.size >= 7 && adts[0].toInt() and 0xff == 0xff &&
            adts[1].toInt() and 0xf6 == 0xf0) { "AAC access units must include an ADTS header" }
        require(adts.size <= 65527) { "AAC access unit exceeds the PES length limit" }
        val time = timestamp(presentationTimeUs, lastAudioTime)
        lastAudioTime = time
        val clock = advanceClock(time)
        val chunk = ByteArrayOutputStream(adts.size + 1024)
        writeTablesIfNeeded(chunk, clock, false)
        // Keep the program clock alive if screen capture pauses on a static image.
        // An adaptation-only packet MUST NOT advance its PID's continuity counter.
        if (lastPcr < 0 || clock - lastPcr >= PCR_INTERVAL) {
            writePcrOnly(chunk, clock)
            lastPcr = clock
        }
        writePes(chunk, AUDIO_PID, 0xc0, adts, time + PRESENTATION_LEAD, null, false)
        output(chunk.toByteArray())
    }

    private fun timestamp(timeUs: Long, previous: Long): Long {
        require(timeUs >= 0) { "Presentation timestamps must be non-negative" }
        // Divide first to avoid overflowing when converting microseconds to 90 kHz.
        val ticks = timeUs / 100 * 9 + (timeUs % 100) * 9 / 100
        return maxOf(ticks, previous + 1)
    }

    private fun advanceClock(time: Long): Long {
        lastClock = maxOf(time, lastClock)
        return lastClock
    }

    private fun writeTablesIfNeeded(out: ByteArrayOutputStream, clock: Long, force: Boolean) {
        if (!force && lastTables >= 0 && clock - lastTables < TABLE_INTERVAL) return
        writeSection(out, PAT_PID, byteArrayOf(
            0x00, 0xb0.toByte(), 0x0d, 0x00, 0x01, 0xc1.toByte(), 0x00, 0x00,
            0x00, 0x01, (0xe0 or (PMT_PID shr 8)).toByte(), PMT_PID.toByte(),
        ))
        writeSection(out, PMT_PID, byteArrayOf(
            0x02, 0xb0.toByte(), 0x17, 0x00, 0x01, 0xc1.toByte(), 0x00, 0x00,
            (0xe0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), 0xf0.toByte(), 0x00,
            0x1b, (0xe0 or (VIDEO_PID shr 8)).toByte(), VIDEO_PID.toByte(), 0xf0.toByte(), 0x00,
            0x0f, (0xe0 or (AUDIO_PID shr 8)).toByte(), AUDIO_PID.toByte(), 0xf0.toByte(), 0x00,
        ))
        lastTables = clock
    }

    private fun writeSection(out: ByteArrayOutputStream, pid: Int, section: ByteArray) {
        val packet = newPacket(pid, true, 1)
        packet[4] = 0 // pointer_field: section begins immediately after this byte
        section.copyInto(packet, 5)
        val crc = mpegCrc32(section)
        for (i in 0..3) packet[5 + section.size + i] = (crc ushr (24 - 8 * i)).toByte()
        out.write(packet)
    }

    private fun writePes(
        out: ByteArrayOutputStream,
        pid: Int,
        streamId: Int,
        data: ByteArray,
        pts: Long,
        pcr: Long?,
        randomAccess: Boolean,
    ) {
        val pes = ByteArray(14 + data.size)
        pes[2] = 1
        pes[3] = streamId.toByte()
        // Video PES packets may have an unspecified length. Audio may not.
        val length = if (pid == VIDEO_PID) 0 else data.size + 8
        pes[4] = (length shr 8).toByte()
        pes[5] = length.toByte()
        pes[6] = 0x84.toByte() // MPEG-2 syntax + data_alignment_indicator
        pes[7] = 0x80.toByte() // PTS only; no B-frames/DTS reordering
        pes[8] = 5
        writePts(pes, 9, pts and TIMESTAMP_MASK)
        data.copyInto(pes, 14)

        var offset = 0
        while (offset < pes.size) {
            val first = offset == 0
            val packetPcr = if (first) pcr else null
            val random = first && randomAccess
            val minimumAdaptation = if (packetPcr != null) 8 else if (random) 2 else 0
            val size = minOf(pes.size - offset, 184 - minimumAdaptation)
            val adaptationSize = 184 - size
            val packet = newPacket(pid, first, if (adaptationSize > 0) 3 else 1)
            var payloadOffset = 4
            if (adaptationSize > 0) {
                packet[4] = (adaptationSize - 1).toByte()
                if (adaptationSize > 1) {
                    packet[5] = ((if (packetPcr != null) 0x10 else 0) or
                        (if (random) 0x40 else 0)).toByte()
                    if (packetPcr != null) writePcr(packet, 6, packetPcr)
                }
                payloadOffset += adaptationSize
            }
            pes.copyInto(packet, payloadOffset, offset, offset + size)
            out.write(packet)
            offset += size
        }
    }

    private fun writePcrOnly(out: ByteArrayOutputStream, clock: Long) {
        val packet = newPacket(VIDEO_PID, false, 2)
        packet[4] = 183.toByte()
        packet[5] = 0x10
        writePcr(packet, 6, clock)
        out.write(packet)
    }

    private fun newPacket(pid: Int, start: Boolean, adaptationControl: Int): ByteArray {
        val packet = ByteArray(PACKET_SIZE) { 0xff.toByte() }
        packet[0] = 0x47
        packet[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        packet[2] = pid.toByte()
        val hasPayload = adaptationControl and 1 != 0
        val cc = if (hasPayload) continuity[pid] else lastContinuity[pid]
        packet[3] = ((adaptationControl shl 4) or cc).toByte()
        if (hasPayload) {
            lastContinuity[pid] = cc
            continuity[pid] = (cc + 1) and 15
        }
        return packet
    }

    private fun writePts(data: ByteArray, offset: Int, pts: Long) {
        data[offset] = (0x21 or (((pts ushr 30).toInt() and 7) shl 1)).toByte()
        data[offset + 1] = (pts ushr 22).toByte()
        data[offset + 2] = ((((pts ushr 15).toInt() and 0x7f) shl 1) or 1).toByte()
        data[offset + 3] = (pts ushr 7).toByte()
        data[offset + 4] = (((pts.toInt() and 0x7f) shl 1) or 1).toByte()
    }

    private fun writePcr(data: ByteArray, offset: Int, clock: Long) {
        val base = clock and TIMESTAMP_MASK
        data[offset] = (base ushr 25).toByte()
        data[offset + 1] = (base ushr 17).toByte()
        data[offset + 2] = (base ushr 9).toByte()
        data[offset + 3] = (base ushr 1).toByte()
        data[offset + 4] = (((base and 1).toInt() shl 7) or 0x7e).toByte()
        data[offset + 5] = 0 // 27 MHz extension = 0
    }

    companion object {
        const val PACKET_SIZE = 188
        const val PAT_PID = 0
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
        private const val TIMESTAMP_MASK = 0x1ffffffffL
        private const val TABLE_INTERVAL = 9_000L // 100 ms
        private const val PCR_INTERVAL = 3_600L // 40 ms
        private const val PRESENTATION_LEAD = 9_000L // 100 ms
        private val AUD = byteArrayOf(0, 0, 0, 1, 9, 0xf0.toByte())

        /** CRC-32/MPEG-2: polynomial 0x04C11DB7, init all ones, no final XOR. */
        internal fun mpegCrc32(data: ByteArray): Int {
            var crc = -1
            for (byte in data) {
                crc = crc xor ((byte.toInt() and 0xff) shl 24)
                repeat(8) {
                    crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
                }
            }
            return crc
        }
    }
}

/** ADTS framing for a single raw AAC access unit from MediaCodec. */
object Adts {
    private val sampleRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** [audioObjectType] defaults to AAC-LC (2), matching the phone encoder. */
    fun header(payloadSize: Int, sampleRate: Int, channelCount: Int, audioObjectType: Int = 2): ByteArray {
        val frequency = sampleRates.indexOf(sampleRate)
        require(frequency >= 0) { "Unsupported ADTS sample rate: $sampleRate" }
        require(channelCount in 1..6 || channelCount == 8) { "ADTS requires 1–6 or 8 channels" }
        require(audioObjectType in 1..4) { "Unsupported ADTS audio object type" }
        require(payloadSize in 1..8184) { "AAC payload must fit the 13-bit ADTS frame length" }
        val channels = if (channelCount == 8) 7 else channelCount
        val length = payloadSize + 7
        return byteArrayOf(
            0xff.toByte(), 0xf1.toByte(), // MPEG-4, no CRC, one raw_data_block
            (((audioObjectType - 1) shl 6) or (frequency shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(),
            (length shr 3).toByte(), (((length and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte(),
        )
    }
}

/** Conversion helpers for MediaCodec Annex B buffers and AVC length-prefixed buffers. */
object Avc {
    /** Returns Annex B unchanged or converts a strictly length-prefixed access unit. */
    fun toAnnexB(data: ByteArray, lengthSize: Int = 4): ByteArray {
        require(lengthSize in 1..4) { "AVC NAL length size must be 1–4 bytes" }
        if (data.isEmpty() || startCodeLength(data) > 0) return data
        val out = ByteArrayOutputStream(data.size + 16)
        var offset = 0
        while (offset < data.size) {
            require(offset + lengthSize <= data.size) { "Truncated AVC NAL length" }
            var length = 0L
            repeat(lengthSize) { length = (length shl 8) or (data[offset++].toLong() and 0xff) }
            require(length > 0 && length <= data.size - offset) { "Invalid AVC NAL length" }
            out.write(START_CODE)
            out.write(data, offset, length.toInt())
            offset += length.toInt()
        }
        return out.toByteArray()
    }

    /** Converts AVCDecoderConfigurationRecord (avcC) SPS/PPS or Annex B codec-specific data. */
    fun configurationToAnnexB(data: ByteArray): ByteArray {
        if (data.isEmpty() || startCodeLength(data) > 0) return data
        require(data.size >= 7 && data[0].toInt() == 1) { "Expected avcC or Annex B codec configuration" }
        val out = ByteArrayOutputStream()
        var offset = 6
        fun copyNals(count: Int) {
            repeat(count) {
                require(offset + 2 <= data.size) { "Truncated avcC NAL length" }
                val size = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
                offset += 2
                require(size > 0 && size <= data.size - offset) { "Truncated avcC NAL data" }
                out.write(START_CODE)
                out.write(data, offset, size)
                offset += size
            }
        }
        copyNals(data[5].toInt() and 0x1f)
        require(offset < data.size) { "Missing avcC PPS count" }
        copyNals(data[offset++].toInt() and 0xff)
        // High-profile avcC may append chroma/bit-depth and SPS extension fields;
        // SPS/PPS above are the decoder configuration needed by this AVC stream.
        return out.toByteArray()
    }

    internal fun startCodeLength(data: ByteArray): Int = when {
        data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
        data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte() -> 3
        else -> 0
    }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)
}
