package dev.lancast.phone

import java.util.concurrent.atomic.AtomicLong

/** Process-only pairing: survives Activity recreation, never persisted or silently reconnected. */
internal object CastSession {
    @Volatile var pairedIp = ""
    @Volatile var token = ""
    val generation = AtomicLong()
    fun ensureCurrent(operation: Long) {
        if (generation.get() != operation) throw java.util.concurrent.CancellationException("投屏操作已取消")
    }
}
