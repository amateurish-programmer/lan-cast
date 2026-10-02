package dev.lancast.phone

import dev.lancast.shared.LanUrlValidator

/** Discovery is a hint, never authority to contact arbitrary advertised endpoints. */
internal object ReceiverEligibility {
    fun validName(name: String): Boolean = name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= 255
    fun displayName(name: String): String = name.filterNot {
        Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()
    }.trim().take(80).ifBlank { "局域网电视" }
    fun accepts(protocol: String?, port: Int, ip: String): Boolean =
        protocol == "2" && port == 8765 && LanUrlValidator.isPrivateIpv4(ip)
}
