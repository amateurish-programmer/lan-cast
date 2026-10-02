package dev.lancast.shared

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Explicit receiver approval. All identities and capabilities are process-local and IP-bound. */
class ApprovalSession(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    data class Request(val id: String, val secret: String, val ip: String, val name: String, val deadline: Long)
    data class Result(val status: String, val token: String? = null, val seconds: Long = 0)
    sealed class Start {
        data class Pending(val request: Request) : Start()
        data object Busy : Start()
        data object Limited : Start()
    }
    private val random = SecureRandom()
    private val limiter = PairingRateLimiter(5, 60_000, 1, now)
    private var request: Request? = null
    private var state = "expired"
    private var token: String? = null
    private var sessionDeadline = 0L
    private var terminalDeadline = 0L

    @Synchronized fun start(ip: String, name: String, accepting: () -> Boolean = { true }): Start {
        if (!accepting()) return Start.Busy
        expire()
        if (!limiter.tryAcquire("global")) return Start.Limited
        if (state == "pending" || token != null) return Start.Busy
        val value = Request(secret(), secret(), ip, name.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }.take(48).ifBlank { "Android 手机" }, now() + REQUEST_MS)
        request = value
        state = "pending"
        return Start.Pending(value)
    }
    /** Only the TV's UI calls this; the request id prevents stale dialog callbacks approving a newer peer. */
    @Synchronized fun decide(id: String, allow: Boolean): Boolean {
        expire()
        if (request?.id != id || state != "pending") return false
        state = if (allow) "approved" else "declined"
        terminalDeadline = now() + REQUEST_MS
        if (allow) { token = secret(); sessionDeadline = now() + SESSION_MS }
        return true
    }
    @Synchronized fun poll(id: String, secret: String, ip: String): Result? {
        expire()
        val r = request ?: return null
        if (r.id != id || r.ip != ip || !equalsSecret(r.secret, secret)) return null
        return Result(state, if (state == "approved") token else null,
            ((if (state == "approved") sessionDeadline else r.deadline) - now()).coerceAtLeast(0) / 1000)
    }
    @Synchronized fun cancel(id: String, secret: String, ip: String): Boolean {
        if (poll(id, secret, ip) == null) return false
        state = "cancelled"
        token = null
        terminalDeadline = now() + REQUEST_MS
        return true
    }
    @Synchronized fun pending(): Request? { expire(); return request?.takeIf { state == "pending" } }
    @Synchronized fun pairedIp(): String? { expire(); return request?.ip?.takeIf { token != null } }
    @Synchronized fun seconds(): Long { expire(); return ((sessionDeadline - now()).coerceAtLeast(0) / 1000).takeIf { token != null } ?: 0 }
    @Synchronized fun authorized(header: String?, ip: String): Boolean {
        expire()
        val t = token ?: return false
        return request?.ip == ip && header?.startsWith("Bearer ") == true && equalsSecret(t, header.removePrefix("Bearer "))
    }
    @Synchronized fun disconnect(header: String?, ip: String): Boolean {
        if (!authorized(header, ip)) return false
        revoke()
        return true
    }
    @Synchronized fun revoke() { request = null; token = null; state = "expired"; sessionDeadline = 0 }
    /** Returns true exactly when an active session expires. */
    @Synchronized fun expire(): Boolean {
        val time = now()
        var expiredSession = false
        if (token != null && time >= sessionDeadline) { token = null; state = "expired"; terminalDeadline = time + REQUEST_MS; expiredSession = true }
        if (state == "pending" && time >= (request?.deadline ?: 0)) { state = "expired"; terminalDeadline = time + REQUEST_MS }
        if (state != "pending" && token == null && time >= terminalDeadline) request = null
        return expiredSession
    }
    private fun secret() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun equalsSecret(a: String, b: String) = b.length <= 128 && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    companion object { const val REQUEST_MS = 60_000L; const val SESSION_MS = 7_200_000L }
}
