package dev.lancast.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var deviceHint: TextView
    private lateinit var sessionLabel: TextView
    private lateinit var cancelPair: Button
    private lateinit var discovery: ReceiverDiscovery
    private lateinit var updater: dev.lancast.updater.UpdateController
    private val pairingEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var pairing = false
    private var receiverName = "电视"
    private var visible = false
    private var observedNetwork: android.net.Network? = null
    private var networkRegistered = false
    private val connectivity by lazy { getSystemService(android.net.ConnectivityManager::class.java) }
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { main.post {
            if (isDestroyed) return@post
            if (observedNetwork != null && observedNetwork != network) invalidateNetwork()
            observedNetwork = network
        } }
        override fun onLost(network: android.net.Network) { main.post {
            if (!isDestroyed && observedNetwork == network) { observedNetwork = null; invalidateNetwork() }
        } }
    }
    private fun invalidateNetwork() {
        if (token.isNotBlank() || pairing || MediaServerService.ready || MirrorService.active) {
            pairingEpoch.incrementAndGet(); pairing = false; cancelPair.visibility = android.view.View.GONE
            CastSession.generation.incrementAndGet(); token = ""; pairedIp = ""
            pendingMirror = false; pendingFileOperation = -1; pendingCaptureOperation = -1
            MediaServerService.capability = ""
            stopService(Intent(this, MirrorService::class.java)); stopService(Intent(this, MediaServerService::class.java))
            sessionLabel.text = "●  网络已改变，连接已断开"
            show("网络已改变或断开，已停止共享。请在当前网络重新选择电视。")
        }
        discovery.stop(); if (visible) discovery.start()
    }
    private val navy = android.graphics.Color.rgb(9, 17, 31)
    private val panel = android.graphics.Color.rgb(18, 31, 48)
    private val mint = android.graphics.Color.rgb(109, 237, 201)
    private val white = android.graphics.Color.rgb(238, 245, 250)
    private val muted = android.graphics.Color.rgb(153, 176, 193)
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun rounded(color: Int, radius: Int = 20) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pairedIp: String
        get() = CastSession.pairedIp
        set(value) { CastSession.pairedIp = value }
    private var token: String
        get() = CastSession.token
        set(value) { CastSession.token = value }
    private var mirror = false
    private var pendingMirror = false
    private var pendingFileOperation = -1L
    private var pendingCaptureOperation = -1L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy; window.navigationBarColor = navy
        window.decorView.systemUiVisibility = 0
        updater = dev.lancast.updater.UpdateController(this, "phone")
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(navy); isFillViewport = true; addView(layout) })
        layout.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(24), dp(20) + insets.systemWindowInsetTop, dp(24), dp(28) + insets.systemWindowInsetBottom); insets
        }
        fun label(text: String, size: Float = 15f, color: Int = muted) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); setPadding(0, dp(8), 0, dp(8)); layout.addView(this)
        }
        fun button(text: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
            backgroundTintList = null; this.text = text; isAllCaps = false; textSize = 16f; minHeight = dp(54)
            setTextColor(if (primary) navy else white); background = rounded(if (primary) mint else panel, 14)
            layout.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }); setOnClickListener { action() }
        }
        label("LAN CAST  /  局域网投屏", 12f, mint).letterSpacing = .13f
        label("把精彩，放大。", 32f, white).setTypeface(null, android.graphics.Typeface.BOLD)
        label("手机与电视连接同一可信 Wi-Fi，\n打开电视接收端，即可发现。")
        sessionLabel = label(if (token.isBlank()) "●  尚未连接" else "●  已连接电视", 14f, mint)
        label("01  选择电视", 18f, white)
        deviceHint = label("正在寻找附近的接收端…", 14f).apply {
            background = rounded(panel, 16); setPadding(dp(18), dp(18), dp(18), dp(18))
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; layout.addView(devices)
        button("重新搜索") {
            discovery.stop(); discovery.start()
            Toast.makeText(this, "已重新搜索，请保持电视接收端打开", Toast.LENGTH_SHORT).show()
        }
        cancelPair = button("取消连接请求") { cancelPairing() }.apply { visibility = android.view.View.GONE }
        label("02  开始分享", 18f, white).setPadding(0, dp(26), 0, dp(8))
        button("选择本地视频", true) {
            if (!requirePair()) return@button
            pendingFileOperation = CastSession.generation.get()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, 1)
        }
        button("屏幕与声音镜像  ·  实验性") { requestMirror() }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; layout.addView(controls)
        listOf("暂停" to "pause", "继续" to "resume").forEach { (title, path) ->
            controls.addView(Button(this).apply {
                backgroundTintList = null; text = title; isAllCaps = false; setTextColor(white); background = rounded(panel, 14); minHeight = dp(50)
                setOnClickListener { if (MirrorService.active) show("实时镜像不支持暂停，请停止后重新开始") else command(path) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10); rightMargin = if (path == "pause") dp(8) else 0 })
        }
        button("停止投屏及共享") { stopAll() }
        button("断开电视连接") { disconnectReceiver() }
        status = label("准备就绪。选择电视后，请在电视上允许连接。", 15f, white).apply {
            background = rounded(panel); setPadding(dp(18), dp(18), dp(18), dp(18))
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(22)
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        label("仅限可信局域网", 15f, mint).setPadding(0, dp(24), 0, 0)
        label("传输未加密，请勿在公共网络使用。访客 Wi-Fi、AP 隔离或 VPN 可能阻止发现。镜像需要系统屏幕共享及录音授权，不使用麦克风；音频可能包括其他允许录制的应用。受 DRM 保护的内容可能黑屏或静音，请使用平台官方投屏。", 12f)
        button("检查更新") { updater.check() }
        label("LAN CAST  ·  ${packageManager.getPackageInfo(packageName, 0).versionName}", 11f)
        discovery = ReceiverDiscovery(this, ::renderDevices) { deviceHint.text = it }
        observedNetwork = connectivity.activeNetwork
        try { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered = true } catch (_: Exception) { }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
    }
    private fun renderDevices(receivers: List<Receiver>) {
        if (isDestroyed) return
        devices.removeAllViews()
        // Discovery owns empty/searching/error hints, so clearing the cards must
        // not overwrite a timeout or failure with an endless “searching” state.
        if (receivers.isNotEmpty()) deviceHint.text = "找到 ${receivers.size} 台电视 · 轻点后在电视上确认"
        receivers.forEach { receiver ->
            val connected = receiver.ip == pairedIp && token.isNotBlank()
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(18), dp(16), dp(18)); minimumHeight = dp(90)
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x337DEACB), rounded(panel, 16), null)
                isClickable = true; isFocusable = true; isEnabled = !pairing
                contentDescription = "${receiver.name}，${if (connected) "已连接" else "选择后在电视上允许连接"}"
                setOnClickListener { pair(receiver) }
                setOnFocusChangeListener { view, focused ->
                    view.background = rounded(panel, 16).apply { if (focused) setStroke(dp(2), mint) }
                }
            }
            card.addView(TextView(this).apply {
                text = "▣"; textSize = 32f; setTextColor(mint); gravity = android.view.Gravity.CENTER
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            words.addView(TextView(this).apply { text = receiver.name; textSize = 17f; setTextColor(white); setTypeface(null, android.graphics.Typeface.BOLD) })
            words.addView(TextView(this).apply {
                text = if (connected) "●  已连接，可以开始分享" else "就绪 · 需要电视确认"
                textSize = 12f; setTextColor(if (connected) mint else muted); setPadding(0, dp(5), 0, 0)
            })
            card.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(TextView(this).apply { text = "›"; textSize = 28f; setTextColor(mint); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            devices.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    override fun onStart() { super.onStart(); visible = true; renderDevices(emptyList()); discovery.start() }
    override fun onStop() { visible = false; discovery.stop(); super.onStop() }
    override fun onResume() { super.onResume(); if (::updater.isInitialized) updater.onResume() }
    private fun show(message: String) { main.post { if (!isDestroyed) status.text = message } }
    private fun requirePair(): Boolean { if (pairing) { show("请先在电视上确认连接"); return false }; if (token.isBlank()) { show("请先配对电视"); return false }; return true }
    private fun background(block: () -> Unit) {
        val operation = CastSession.generation.get()
        worker.execute {
            try { CastSession.ensureCurrent(operation); block() }
            catch (_: java.util.concurrent.CancellationException) { }
            catch (e: Exception) { if (CastSession.generation.get() == operation) show("操作失败：${e.message ?: e.javaClass.simpleName}") }
        }
    }
    private fun readBounded(input: java.io.InputStream?, maximum: Int): String {
        if (input == null) return ""
        return input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = it.read(buffer, 0, minOf(buffer.size, maximum + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
                check(output.size() <= maximum) { "服务响应过大" }
            }
            output.toString("UTF-8")
        }
    }
    private fun cancelPairing() {
        if (!pairing) return
        pairingEpoch.incrementAndGet(); pairing = false
        cancelPair.visibility = android.view.View.GONE
        sessionLabel.text = if (token.isBlank()) "●  尚未连接" else "●  已连接电视"
        show("连接请求已取消")
        discovery.stop(); discovery.start()
    }
    private fun pair(receiver: Receiver) {
        if (receiver.ip == pairedIp && token.isNotBlank()) { show("已连接这台电视，可以开始分享"); return }
        if (pairing) { show("请先完成或取消当前连接请求"); return }
        if (token.isNotBlank()) { show("请先断开当前电视连接，再选择另一台电视"); return }
        if (MediaServerService.ready || MirrorService.active) { show("请先停止当前共享，再连接另一台电视"); return }
        val epoch = pairingEpoch.incrementAndGet()
        val operation = CastSession.generation.incrementAndGet()
        pendingMirror = false; pendingFileOperation = -1; pendingCaptureOperation = -1
        pairing = true; cancelPair.visibility = android.view.View.VISIBLE
        sessionLabel.text = "●  等待电视允许连接"
        show("请在 ${receiver.name} 上选择「允许」。请求将在 60 秒后过期。")
        worker.execute {
            var credentials: JSONObject? = null
            var accepted = false
            try {
                ReceiverRoute.localIpv4(receiver.ip)
                val result = request(receiver.ip, "pair/request", JSONObject().put("deviceName", android.os.Build.MODEL.take(60)), "", operation)
                credentials = JSONObject().put("requestId", result.getString("requestId")).put("requestSecret", result.getString("requestSecret"))
                val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
                while (pairingEpoch.get() == epoch && android.os.SystemClock.elapsedRealtime() < deadline) {
                    CastSession.ensureCurrent(operation)
                    val state = request(receiver.ip, "pair/poll", credentials, "", operation)
                    when (state.getString("status")) {
                        "approved" -> {
                            val grantedToken = state.getString("token")
                            // Commit on main so cancellation cannot race a late network response.
                            val committed = java.util.concurrent.CountDownLatch(1)
                            main.post {
                                if (!isDestroyed && pairingEpoch.get() == epoch && CastSession.generation.get() == operation) {
                                    token = grantedToken; pairedIp = receiver.ip; receiverName = receiver.name
                                    MediaServerService.allowedTvIp = receiver.ip; accepted = true
                                    pairing = false; cancelPair.visibility = android.view.View.GONE
                                    sessionLabel.text = "●  已连接 ${receiver.name}"
                                    show("电视已允许连接。现在可以选择视频或开始镜像；会话有效期为两小时。")
                                }
                                committed.countDown()
                            }
                            committed.await(); break
                        }
                        "declined" -> { showPair(epoch, "电视拒绝了连接请求。请确认电视使用者同意后重试。"); break }
                        "expired" -> { showPair(epoch, "连接请求已超时。请重新选择电视并在电视上确认。"); break }
                        "cancelled" -> { showPair(epoch, "连接请求已取消。"); break }
                        "pending" -> Thread.sleep(1000)
                        else -> error("接收端返回了未知连接状态")
                    }
                }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) showPair(epoch, "等待电视确认超时。请重新选择电视。")
            } catch (_: java.util.concurrent.CancellationException) { }
            catch (e: Exception) { showPair(epoch, "连接失败：${e.message ?: "请检查 Wi-Fi 后重试"}") }
            finally {
                if (!accepted && credentials != null) try { request(receiver.ip, "pair/cancel", credentials, "") } catch (_: Exception) { }
                main.post { if (!isDestroyed && pairingEpoch.get() == epoch) {
                    pairing = false; cancelPair.visibility = android.view.View.GONE
                    if (!accepted) sessionLabel.text = if (token.isBlank()) "●  尚未连接" else "●  已连接电视"
                    discovery.stop(); if (visible) discovery.start()
                } }
            }
        }
    }
    private fun showPair(epoch: Long, message: String) { main.post { if (!isDestroyed && pairingEpoch.get() == epoch) status.text = message } }
    private fun command(path: String) { if (requirePair()) background { request(pairedIp, path, JSONObject(), token); show("已发送：$path") } }
    private fun request(ip: String, path: String, body: JSONObject, bearer: String, operation: Long = CastSession.generation.get()): JSONObject {
        CastSession.ensureCurrent(operation)
        val connection = URL("http://$ip:8765/$path").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        connection.apply { requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000; instanceFollowRedirects = false; doOutput = true; setRequestProperty("Content-Type", "application/json"); if (bearer.isNotEmpty()) setRequestProperty("Authorization", "Bearer $bearer") }
        try {
            CastSession.ensureCurrent(operation)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            val text = readBounded(if (responseCode in 200..299) connection.inputStream else connection.errorStream, 8192)
            // Preserve the cancellation credentials even if Stop/network change raced
            // the server creating the request. The pairing loop/finally owns cleanup.
            if (path != "pair/request") CastSession.ensureCurrent(operation)
            if (responseCode == 401 && bearer.isNotBlank()) main.post {
                if (!isDestroyed && CastSession.generation.get() == operation && token == bearer) {
                    CastSession.generation.incrementAndGet(); token = ""; pairedIp = ""
                    MediaServerService.capability = ""
                    stopService(Intent(this, MirrorService::class.java)); stopService(Intent(this, MediaServerService::class.java))
                    sessionLabel.text = "●  连接已失效"
                    status.text = "电视连接已过期或被撤销。共享已停止，请重新选择电视。"
                }
            }
            if (responseCode !in 200..299) error(when (responseCode) {
                409 -> "电视正忙或已有连接，请先在电视端断开当前会话"
                429 -> "连接请求过于频繁，请稍后重试"
                401 -> "连接已失效，请重新选择电视"
                else -> "电视返回 $responseCode：${text.take(300)}"
            })
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }
    private fun ownIp(): String = ReceiverRoute.localIpv4(pairedIp)
    private fun prepareServer(operation: Long) {
        CastSession.ensureCurrent(operation)
        MediaServerService.allowedTvIp = pairedIp
        startForegroundService(Intent(this, MediaServerService::class.java).putExtra("generation", operation))
    }
    private fun awaitServer(operation: Long) { repeat(50) { CastSession.ensureCurrent(operation); if (MediaServerService.ready) return; Thread.sleep(100) }; error(MediaServerService.error ?: "视频服务启动超时") }
    private fun requestMirror() {
        if (!requirePair()) return
        if (MirrorService.active) { show("镜像已运行，请先停止后重新开始"); return }
        if (android.os.Build.VERSION.SDK_INT < 29) { show("影音镜像需要 Android 10 或更高版本"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { pendingMirror = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3); return }
        pendingCaptureOperation = CastSession.generation.get()
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 2)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3 && pendingMirror) { pendingMirror = false; if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestMirror() else show("未授予录音权限，未开始影音镜像") }
    }
    @Deprecated("Activity result API for dependency-minimal prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 && requestCode != 2) return
        val expected = if (requestCode == 1) pendingFileOperation else pendingCaptureOperation
        if (CastSession.generation.get() != expected) { show("该投屏操作已取消，请重新开始"); return }
        if (requestCode == 1) pendingFileOperation = -1 else pendingCaptureOperation = -1
        if (resultCode != RESULT_OK || data == null) { show("已取消，不会开始共享"); return }
        if (requestCode == 1) {
            val uri = data.data ?: return
            val operation = CastSession.generation.incrementAndGet()
            try {
                stopService(Intent(this, MirrorService::class.java)); mirror = false
                MediaServerService.selectedUri = uri
                val secret = MediaServerService.rotateCapability(); prepareServer(operation)
                background {
                    try {
                        val ip = ownIp()
                        awaitServer(operation)
                        request(pairedIp, "play", JSONObject().put("url", "http://$ip:8766/media/$secret"), token, operation)
                        show("播放请求已发送；请查看电视状态，可用遥控器快进/后退")
                    } catch (failure: Exception) {
                        stopOperation(operation)
                        throw failure
                    }
                }
            } catch (e: Exception) { stopOperation(operation); show(e.message ?: "无法打开视频") }
        } else if (requestCode == 2) {
            val operation = CastSession.generation.incrementAndGet()
            try {
                MediaServerService.selectedUri = null; val secret = MediaServerService.rotateCapability(); prepareServer(operation)
                startForegroundService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_START).putExtra(MirrorService.EXTRA_GENERATION, operation).putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode).putExtra(MirrorService.EXTRA_RESULT_DATA, data))
                mirror = true
                background {
                    try {
                        val ip = ownIp()
                        awaitServer(operation)
                        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                        while (!MirrorService.active && android.os.SystemClock.elapsedRealtime() < deadline) {
                            CastSession.ensureCurrent(operation)
                            if (android.os.SystemClock.elapsedRealtime() > deadline - 9000) MirrorService.lastError?.let { error(it) }
                            Thread.sleep(100)
                        }
                        check(MirrorService.active) { MirrorService.lastError ?: "镜像编码器启动超时" }
                        request(pairedIp, "mirror", JSONObject().put("url", "http://$ip:8766/stream/$secret"), token, operation)
                        show("镜像编码已启动并请求电视播放。请查看电视后切换目标应用；受保护画面/声音可能不可用。")
                    } catch (failure: Exception) {
                        stopOperation(operation)
                        throw failure
                    }
                }
            } catch (e: Exception) { stopOperation(operation); show(e.message ?: "无法启动镜像") }
        }
    }
    private fun stopOperation(operation: Long) {
        main.post {
            // All Activity starts and this teardown are serialized on main. A failed
            // older worker must never revoke or stop a newer cast between check/action.
            if (CastSession.generation.get() == operation) {
                MediaServerService.capability = ""
                stopService(Intent(this, MirrorService::class.java))
                stopService(Intent(this, MediaServerService::class.java))
                mirror = false
            }
        }
    }
    private fun disconnectReceiver() {
        val ip = pairedIp; val bearer = token
        if (pairing) cancelPairing()
        CastSession.generation.incrementAndGet(); token = ""; pairedIp = ""
        pendingMirror = false; pendingFileOperation = -1; pendingCaptureOperation = -1
        MediaServerService.capability = ""
        stopService(Intent(this, MirrorService::class.java)); stopService(Intent(this, MediaServerService::class.java)); mirror = false
        sessionLabel.text = "●  尚未连接"; show("已断开电视并停止共享。可以重新选择电视。")
        discovery.stop(); if (visible) discovery.start()
        if (ip.isNotBlank() && bearer.isNotBlank()) worker.execute {
            try { request(ip, "disconnect", JSONObject(), bearer) } catch (_: Exception) { }
        }
    }
    private fun stopAll() {
        if (pairing) cancelPairing()
        pendingMirror = false; pendingFileOperation = -1; pendingCaptureOperation = -1
        CastSession.generation.incrementAndGet()
        MediaServerService.capability = ""
        if (token.isNotBlank()) command("stop")
        stopService(Intent(this, MirrorService::class.java)); stopService(Intent(this, MediaServerService::class.java)); mirror = false; show("共享已停止")
    }
    override fun onDestroy() {
        pairingEpoch.incrementAndGet()
        discovery.stop(); updater.close(); worker.shutdown()
        if (networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        super.onDestroy()
    }
}
