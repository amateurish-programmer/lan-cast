package dev.lancast.shared

/** One resolved HTTP byte range. End positions are inclusive. */
sealed interface RangeResult {
    val responseCode: Int

    data class Full(val resourceLength: Long) : RangeResult {
        override val responseCode = 200
        val contentLength: Long get() = resourceLength
    }

    data class Partial(
        val start: Long,
        val endInclusive: Long,
        val resourceLength: Long,
    ) : RangeResult {
        override val responseCode = 206
        val contentLength: Long get() = endInclusive - start + 1L
        val contentRange: String get() = "bytes $start-$endInclusive/$resourceLength"
    }

    data class Invalid(val reason: String) : RangeResult {
        override val responseCode = 400
    }

    data class Unsatisfiable(val resourceLength: Long) : RangeResult {
        override val responseCode = 416
        val contentRange: String get() = "bytes */$resourceLength"
    }
}

/**
 * A deliberately single-range parser for a seekable, fixed-length media representation.
 *
 * Missing Range means 200. Malformed, unsupported, and multiple ranges mean 400;
 * syntactically valid ranges that select no bytes mean 416. This server deliberately
 * rejects invalid ranges instead of silently serving the full representation. A suffix
 * or an end greater than the representation is clamped, including decimal integers
 * larger than Long.MAX_VALUE. This avoids numeric overflow on untrusted headers.
 *
 * Call only for GET. HEAD must report the corresponding full-GET metadata and ignore
 * Range (RFC 9110 section 14.2). The caller should also ignore Range if If-Range fails.
 */
object HttpRange {
    private const val MAX_HEADER_LENGTH = 1024
    private val singleRange = Regex("(?i:bytes)=([0-9]*)-([0-9]*)")

    fun parse(header: String?, resourceLength: Long): RangeResult {
        require(resourceLength >= 0L) { "The representation length must be known and non-negative" }
        if (header == null) return RangeResult.Full(resourceLength)
        if (header.length > MAX_HEADER_LENGTH) return RangeResult.Invalid("Range header too long")
        // Only HTTP optional whitespace is allowed around a field value, never CR/LF.
        val value = header.trim(' ', '\t')
        val match = singleRange.matchEntire(value)
            ?: return RangeResult.Invalid("Expected one bytes=start-end, bytes=start-, or bytes=-suffix range")
        val first = match.groupValues[1]
        val last = match.groupValues[2]
        if (first.isEmpty() && last.isEmpty()) return RangeResult.Invalid("Missing byte positions")

        if (first.isEmpty()) {
            val suffixLength = normalized(last)
            if (suffixLength == "0" || resourceLength == 0L) return RangeResult.Unsatisfiable(resourceLength)
            val count = suffixLength.toLongOrNull()?.coerceAtMost(resourceLength) ?: resourceLength
            return RangeResult.Partial(resourceLength - count, resourceLength - 1L, resourceLength)
        }

        val normalizedFirst = normalized(first)
        val normalizedLast = last.takeIf(String::isNotEmpty)?.let(::normalized)
        if (normalizedLast != null && compareDecimal(normalizedFirst, normalizedLast) > 0) {
            return RangeResult.Invalid("Range end precedes its start")
        }
        val start = normalizedFirst.toLongOrNull() ?: return RangeResult.Unsatisfiable(resourceLength)
        if (start >= resourceLength) return RangeResult.Unsatisfiable(resourceLength)
        val end = normalizedLast?.toLongOrNull()?.coerceAtMost(resourceLength - 1L)
            ?: (resourceLength - 1L)
        return RangeResult.Partial(start, end, resourceLength)
    }

    private fun normalized(decimal: String): String = decimal.trimStart('0').ifEmpty { "0" }

    private fun compareDecimal(a: String, b: String): Int =
        if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
}
