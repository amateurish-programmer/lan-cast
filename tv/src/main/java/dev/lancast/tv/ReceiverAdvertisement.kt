package dev.lancast.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

/** Advertises only this app's live receiver; never implies compatibility with Chromecast/DLNA. */
class ReceiverAdvertisement(context: Context, private val changed: (String) -> Unit) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null
    private var running = false
    fun start() {
        stop()
        running = true
        val next = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { if (running && listener === this) changed(info.serviceName) else runCatching { manager.unregisterService(this) } }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { if (running && listener === this) changed("发现服务不可用 · 请重新打开应用") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        listener = next
        try {
            manager.registerService(NsdServiceInfo().apply {
                serviceName = "Lan Cast · ${Build.MODEL.take(30)}"
                serviceType = "_lancast._tcp."
                port = ControlServer.PORT
                setAttribute("protocol", "2")
            }, NsdManager.PROTOCOL_DNS_SD, next)
        } catch (_: Exception) { changed("发现服务不可用 · 请重新打开应用") }
    }
    fun stop() { running = false; listener?.let { runCatching { manager.unregisterService(it) } }; listener = null }
}
