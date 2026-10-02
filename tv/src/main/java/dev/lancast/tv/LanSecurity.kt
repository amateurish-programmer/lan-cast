package dev.lancast.tv

import java.net.URI

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

typealias PairingSecurity = dev.lancast.shared.ApprovalSession
