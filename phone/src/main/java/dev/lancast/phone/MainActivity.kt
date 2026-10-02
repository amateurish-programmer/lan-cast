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
import dev.lancast.shared.LanUrlValidator
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var tvIp: EditText
    private lateinit var code: EditText
    private lateinit var phoneIp: EditText
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
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 24) }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(32, 24 + insets.systemWindowInsetTop, 32, 24 + insets.systemWindowInsetBottom)
            insets
        }
        fun label(s: String) { layout.addView(TextView(this).apply { text = s; textSize = 18f; setPadding(0, 12, 0, 12) }) }
        fun input(h: String, value: String = "") = EditText(this).apply { hint = h; setText(value); setSingleLine(); layout.addView(this) }
        fun button(s: String, action: () -> Unit) { layout.addView(Button(this).apply { text = s; setOnClickListener { action() } }) }
        label("局域网投屏 · 手机 v0.1.0")
        label("先在索尼电视打开接收端。两台设备连接同一可信 Wi-Fi。访客网络/AP隔离可能阻止连接。")
        tvIp = input("电视 IP，例如 192.168.1.20")
        code = input("电视显示的六位配对码")
        phoneIp = input("手机 Wi-Fi IP（可手动修正）", localIp())
        button("配对电视") { pair() }
        button("选择本地视频并播放") {
            if (!requirePair()) return@button
            pendingFileOperation = CastSession.generation.get()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, 1)
        }
        button("暂停") { if (MirrorService.active) show("实时镜像不支持暂停，请停止后重新开始") else command("pause") }
        button("继续") { if (MirrorService.active) show("实时镜像不支持暂停，请停止后重新开始") else command("resume") }
        button("开始屏幕影音镜像（实验性）") { requestMirror() }
        button("停止投屏及共享") { stopAll() }
        label("影音镜像需要系统屏幕共享和录音授权。声音可能包括当前用户下其他正在播放且允许录制的应用，不仅限于所选画面应用；不使用麦克风。红果、爱奇艺、腾讯视频可能禁止画面或声音采集；黑屏/静音时请使用平台官方投屏，不绕过保护。")
        button("检查在线更新") { checkUpdate() }
        status = TextView(this).apply { text = "尚未配对"; setPadding(0, 20, 0, 0); textSize = 16f }; layout.addView(status)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
    }
    private fun show(message: String) { main.post { if (!isDestroyed) status.text = message } }
    private fun requirePair(): Boolean { if (token.isBlank()) { show("请先配对电视"); return false }; return true }
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
    private fun pair() {
        if (MediaServerService.ready || MirrorService.active) { show("请先停止当前共享，再配对另一台电视"); return }
        val ip = tvIp.text.toString().trim(); val pin = code.text.toString().trim()
        if (!LanUrlValidator.isPrivateIpv4(ip) || !pin.matches(Regex("[0-9]{6}"))) { show("请输入局域网 IPv4 和六位配对码"); return }
        background {
            val result = request(ip, "pair", JSONObject().put("code", pin), "")
            token = result.getString("token"); pairedIp = ip
            MediaServerService.allowedTvIp = ip
            show("已配对。可选择本地视频，或启动实验性影音镜像。会话两小时后需重配对。")
        }
    }
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
            CastSession.ensureCurrent(operation)
            if (responseCode !in 200..299) error("电视返回 $responseCode：$text")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }
    private fun ownIp(): String { val ip = phoneIp.text.toString().trim(); require(LanUrlValidator.isPrivateIpv4(ip)) { "请填写手机实际 Wi-Fi IPv4" }; return ip }
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
        val expected = if (requestCode == 1) pendingFileOperation else pendingCaptureOperation
        if (CastSession.generation.get() != expected) { show("该投屏操作已取消，请重新开始"); return }
        if (resultCode != RESULT_OK || data == null) { show("已取消，不会开始共享"); return }
        if (requestCode == 1) {
            val uri = data.data ?: return
            val operation = CastSession.generation.incrementAndGet()
            try {
                val ip = ownIp(); stopService(Intent(this, MirrorService::class.java)); mirror = false
                MediaServerService.selectedUri = uri
                val secret = MediaServerService.rotateCapability(); prepareServer(operation)
                background {
                    try {
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
                val ip = ownIp(); MediaServerService.selectedUri = null; val secret = MediaServerService.rotateCapability(); prepareServer(operation)
                startForegroundService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_START).putExtra(MirrorService.EXTRA_GENERATION, operation).putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode).putExtra(MirrorService.EXTRA_RESULT_DATA, data))
                mirror = true
                background {
                    try {
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
    private fun stopAll() {
        CastSession.generation.incrementAndGet()
        MediaServerService.capability = ""
        if (token.isNotBlank()) command("stop")
        stopService(Intent(this, MirrorService::class.java)); stopService(Intent(this, MediaServerService::class.java)); mirror = false; show("共享已停止")
    }
    private fun checkUpdate() { background {
        val url = URL("https://api.github.com/repos/amateurish-programmer/lan-cast/releases/latest")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 5000; c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode == 404) { show("还没有发布版本。当前版本 0.1.0"); return@background }
            check(c.responseCode == 200) { "更新服务暂不可用：${c.responseCode}" }
            val release = JSONObject(readBounded(c.inputStream, 512 * 1024))
            val tag = release.getString("tag_name")
            main.post { if (isFinishing || isDestroyed) return@post; android.app.AlertDialog.Builder(this).setTitle("GitHub 最新版本：$tag").setMessage("当前 0.1.0。打开官方发布页，下载 phone APK 后由系统确认安装。只有相同签名的新版才能覆盖安装；应用不保存 GitHub 令牌。")
                .setPositiveButton("打开发布页") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/amateurish-programmer/lan-cast/releases/latest"))) }.setNegativeButton("取消", null).show() }
        } finally { c.disconnect() }
    } }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    companion object {
        fun localIp(): String = try { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { LanUrlValidator.isPrivateIpv4(it.hostAddress ?: "") }?.hostAddress ?: "" } catch (_: Exception) { "" }
    }
}
