package dev.lancast.shared

import java.net.URI
import java.net.URISyntaxException

/** Validates literal RFC 1918 IPv4 URLs without resolving DNS or opening a connection. */
object LanUrlValidator {
    /**
     * Requires plain HTTP, a canonical dotted-decimal private IPv4 address, an explicit
     * exact port, and an exact raw path. User info, query, fragment, Unicode/escaped hosts,
     * shortened/octal/integer addresses, and any implicit URL normalization are rejected.
     *
     * This is an SSRF input boundary, not peer authentication. The caller must additionally
     * pin the URL to the paired peer and disable redirects in its HTTP/media client.
     */
    fun validate(rawUrl: String, expectedPort: Int, expectedPath: String): URI? {
        require(expectedPort in 1..65535) { "Expected port must be between 1 and 65535" }
        require(expectedPath.startsWith('/') && '?' !in expectedPath && '#' !in expectedPath) {
            "Expected path must be an absolute path without query or fragment"
        }
        if (rawUrl.length > 2048 || rawUrl.any { it <= ' ' || it >= '\u007f' }) return null
        val uri = try {
            URI(rawUrl)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.isOpaque || uri.scheme != "http") return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val host = uri.host ?: return null
        if (!isPrivateIpv4(host)) return null
        if (uri.port != expectedPort || uri.rawPath != expectedPath) return null
        // Also rejects noncanonical ports (e.g. :08080), empty user info, and alternate authorities.
        if (uri.rawAuthority != "$host:$expectedPort") return null
        return uri
    }

    fun isPrivateIpv4(host: String): Boolean {
        if (host.length !in 7..15) return false
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return false
            if (part.length > 1 && part[0] == '0') return false
            octets[index] = part.toInt()
            if (octets[index] !in 0..255) return false
        }
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}
