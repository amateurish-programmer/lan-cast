package dev.lancast.tv

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Deliberately IPv4-only: no DNS, loopback, redirects, credentials, query, or fragments. */
object LanUrlPolicy {
    fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4 || octets.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
        val parts = octets.map { it.toInt() }
        if (parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    fun accepts(url: String, pairedIp: String, mirror: Boolean = false): Boolean = runCatching {
        if (url.length > 512 || !isPrivateIpv4(pairedIp)) return false
        val uri = URI(url)
        val pathPattern = if (mirror) Regex("/stream/[A-Fa-f0-9]{64}")
            else Regex("/media/[A-Za-z0-9_-]{32,128}")
        uri.scheme == "http" && uri.host == pairedIp && isPrivateIpv4(uri.host) &&
            uri.port == 8766 && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && pathPattern.matches(uri.rawPath ?: "") &&
            uri.rawAuthority == "$pairedIp:8766"
    }.getOrDefault(false)
}

/** All state is process-local. Code rotation immediately revokes the previous phone. */
class PairingSecurity(
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: SecureRandom = SecureRandom()
) {
    data class Snapshot(val code: String, val codeSeconds: Long, val pairedIp: String?, val sessionSeconds: Long)
    sealed class PairResult {
        data class Accepted(val token: String) : PairResult()
        data object Incorrect : PairResult()
        data object Expired : PairResult()
        data object RateLimited : PairResult()
        data object AlreadyPaired : PairResult()
    }
    private var code = newCode()
    private var codeExpiresAt = now() + CODE_LIFETIME_MS
    private var sessionToken: String? = null
    private var sessionIp: String? = null
    private var sessionExpiresAt = 0L
    private var attempts = 0
    private var attemptsResetAt = now() + ATTEMPT_WINDOW_MS

    @Synchronized fun rotate() {
        code = newCode()
        codeExpiresAt = now() + CODE_LIFETIME_MS
        revoke()
        // Rotation does not bypass the global brute-force limit.
    }

    @Synchronized fun revoke() {
        sessionToken = null
        sessionIp = null
        sessionExpiresAt = 0
    }

    @Synchronized fun expireSession(): Boolean {
        if (sessionToken != null && now() >= sessionExpiresAt) {
            revoke()
            return true
        }
        return false
    }

    @Synchronized fun snapshot(): Snapshot {
        val time = now()
        return Snapshot(code, ((codeExpiresAt - time + 999) / 1000).coerceAtLeast(0),
            sessionIp, ((sessionExpiresAt - time + 999) / 1000).coerceAtLeast(0))
    }

    @Synchronized fun pair(candidate: String, ip: String): PairResult {
        val time = now()
        if (time >= attemptsResetAt) { attempts = 0; attemptsResetAt = time + ATTEMPT_WINDOW_MS }
        if (attempts >= MAX_ATTEMPTS) return PairResult.RateLimited
        attempts++
        if (!LanUrlPolicy.isPrivateIpv4(ip)) return PairResult.Incorrect
        expireSession()
        if (sessionToken != null) return PairResult.AlreadyPaired
        if (time >= codeExpiresAt) return PairResult.Expired
        if (!candidate.matches(Regex("[0-9]{6}")) || !constantEquals(code, candidate)) return PairResult.Incorrect
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessionToken = token
        sessionIp = ip
        sessionExpiresAt = time + SESSION_LIFETIME_MS
        return PairResult.Accepted(token)
    }

    @Synchronized fun authorized(authorization: String?, ip: String): Boolean {
        val expected = sessionToken ?: return false
        if (ip != sessionIp || now() >= sessionExpiresAt) return false
        val supplied = authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: return false
        return supplied.length <= 128 && constantEquals(expected, supplied)
    }

    private fun newCode(): String = (100_000 + random.nextInt(900_000)).toString()
    private fun constantEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    companion object {
        const val CODE_LIFETIME_MS = 5 * 60 * 1000L
        const val SESSION_LIFETIME_MS = 2 * 60 * 60 * 1000L
        const val ATTEMPT_WINDOW_MS = 60 * 1000L
        const val MAX_ATTEMPTS = 5
    }
}
