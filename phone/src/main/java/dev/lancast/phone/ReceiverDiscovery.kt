package dev.lancast.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import dev.lancast.shared.LanUrlValidator
import java.net.DatagramSocket
import java.net.InetAddress

internal data class Receiver(val name: String, val ip: String, val port: Int)

/** Resolves services serially for Android versions allowing only one active resolve. */
internal class ReceiverDiscovery(context: Context, private val changed: (List<Receiver>) -> Unit, private val failed: (String) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("lan-cast-discovery").apply { setReferenceCounted(false) }
    private val found = linkedMapOf<String, Receiver>()
    private val queue = java.util.ArrayDeque<NsdServiceInfo>()
    private val present = mutableSetOf<String>()
    private var resolving = false
    private var epoch = 0
    private var listener: NsdManager.DiscoveryListener? = null
    fun start() {
        if (listener != null) return
        val current = ++epoch
        val callbacks = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post { if (current == epoch) { stop(); failed("暂时无法搜索电视，请检查 Wi-Fi 后重试") } } }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { handler.post { if (current == epoch && info.serviceType.trimEnd('.') == "_lancast._tcp" && ReceiverEligibility.validName(info.serviceName) && present.size < 32 && queue.size < 32 && present.add(info.serviceName)) { queue.add(info); resolveNext(current) } } }
            override fun onServiceLost(info: NsdServiceInfo) { handler.post { if (current == epoch) { present.remove(info.serviceName); queue.removeAll { it.serviceName == info.serviceName }; found.remove(info.serviceName); changed(found.values.toList()) } } }
        }
        listener = callbacks
        try { lock.acquire(); nsd.discoverServices("_lancast._tcp.", NsdManager.PROTOCOL_DNS_SD, callbacks) }
        catch (_: Exception) { stop(); failed("无法搜索电视，请连接同一可信 Wi-Fi 后重试") }
    }
    @Suppress("DEPRECATION")
    private fun resolveNext(current: Int) {
        if (current != epoch || resolving || queue.isEmpty()) return
        val info = queue.removeFirst(); resolving = true
        val callbacks = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { handler.post { resolving = false; resolveNext(epoch) } }
            override fun onServiceResolved(service: NsdServiceInfo) { handler.post {
                if (current != epoch) { resolving = false; resolveNext(epoch); return@post }
                resolving = false
                val ip = service.host?.hostAddress.orEmpty()
                val protocol = service.attributes["protocol"]?.toString(Charsets.UTF_8)
                if (service.serviceName in present && ReceiverEligibility.accepts(protocol, service.port, ip)) {
                    found[service.serviceName] = Receiver(ReceiverEligibility.displayName(service.serviceName), ip, service.port); changed(found.values.toList())
                }
                resolveNext(current)
            } }
        }
        try { nsd.resolveService(info, callbacks) } catch (_: Exception) { resolving = false; resolveNext(current) }
    }
    fun stop() {
        ++epoch
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null; queue.clear(); found.clear(); present.clear(); changed(emptyList())
        if (lock.isHeld) lock.release()
    }
}

/** UDP connect selects the OS route without sending packets or scanning interfaces. */
internal object ReceiverRoute {
    fun localIpv4(receiver: String): String {
        require(LanUrlValidator.isPrivateIpv4(receiver)) { "电视地址不是可信局域网 IPv4" }
        return DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName(receiver), 8765)
            socket.localAddress.hostAddress.orEmpty().also { require(LanUrlValidator.isPrivateIpv4(it)) { "无法确定通往电视的本机 IPv4，请检查 Wi-Fi/VPN" } }
        }
    }
}
