package dev.lancast.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : Activity(), ControlServer.Callbacks {
    private val main = Handler(Looper.getMainLooper())
    private val security = PairingSecurity()
    private val updateWorker = Executors.newSingleThreadExecutor()
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var rootContainer: LinearLayout
    private lateinit var pairPanelView: View
    private var fullscreen = false
    private lateinit var codeView: TextView
    private lateinit var addressView: TextView
    private lateinit var pairStateView: TextView
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var warningView: TextView
    private lateinit var pauseButton: Button
    private lateinit var resumeButton: Button
    private var server: ControlServer? = null
    private var active = false
    private var ready = false
    @Volatile private var mode = "idle"
    @Volatile private var stateJson = "{\"mode\":\"idle\",\"playing\":false}"

    private val refresh = object : Runnable {
        override fun run() {
            if (!active) return
            if (security.expireSession()) {
                stopPlayback()
                security.rotate()
                statusView.text = "配对已过期，请在手机上重新连接"
            }
            val snapshot = security.snapshot()
            if (snapshot.pairedIp == null && snapshot.codeSeconds == 0L) security.rotate()
            renderPairing()
            updateStatus()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildLayout()
        player = ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_000, 5_000, 250, 500).build())
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (mode == "idle") return
                        statusView.text = when (playbackState) {
                            Player.STATE_BUFFERING -> "正在缓冲…"
                            Player.STATE_READY -> if (isPlaying) playingLabel() else "已暂停"
                            Player.STATE_ENDED -> if (mode == "mirror") "镜像已结束，请在手机上重新开始" else "视频播放完毕"
                            else -> "等待媒体"
                        }
                        updateStatus()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (mode != "idle" && playbackState == Player.STATE_READY) {
                            statusView.text = if (isPlaying) playingLabel() else "已暂停"
                        }
                        updateStatus()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        statusView.text = "播放失败（${error.errorCodeName}）。请检查手机网络和媒体格式，再重新投屏。"
                        emptyView.text = "连接中断或格式不支持\n请在手机上停止后重新开始"
                        emptyView.visibility = View.VISIBLE
                        updateStatus(error.errorCodeName)
                    }
                })
            }
        playerView.player = player
        ready = true
    }

    override fun onStart() {
        super.onStart()
        active = true
        security.rotate()
        try {
            server = ControlServer(security, main, this).also { it.start(5_000, false) }
            addressView.text = privateAddresses().joinToString("\n") { "$it:${ControlServer.PORT}" }
                .ifBlank { "未找到局域网 IPv4 地址\n请连接 Wi-Fi 或网线后重新打开" }
            statusView.text = "服务已开启 · 等待手机配对"
        } catch (_: Exception) {
            server?.stop()
            server = null
            addressView.text = "接收服务启动失败"
            statusView.text = "端口 ${ControlServer.PORT} 可能被占用，请退出后重试"
        }
        main.post(refresh)
    }

    override fun onStop() {
        active = false
        main.removeCallbacks(refresh)
        security.revoke()
        server?.stop()
        server = null
        if (ready) stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        active = false
        main.removeCallbacksAndMessages(null)
        updateWorker.shutdownNow()
        security.revoke()
        server?.stop()
        server = null
        if (ready) {
            playerView.player = null
            player.release()
            ready = false
        }
        super.onDestroy()
    }

    override fun paired(ip: String) {
        if (!active) return
        renderPairing()
        statusView.text = "已连接手机 $ip · 请选择视频或开始镜像"
    }

    override fun play(url: String, ip: String, mirror: Boolean) {
        if (!active || !LanUrlPolicy.accepts(url, ip, mirror)) return
        player.stop()
        mode = if (mirror) "mirror" else "video"
        playerView.useController = !mirror
        pauseButton.isEnabled = !mirror
        resumeButton.isEnabled = !mirror
        warningView.text = if (mirror) "实验性屏幕镜像 · 音频取决于来源 App 是否允许\n受保护内容可能黑屏或无声；实时流请停止后重新开始。"
            else "本地视频由手机直接传输；播放期间请保持手机服务运行。"
        emptyView.visibility = View.GONE
        val item = MediaItem.Builder().setUri(url).apply {
            if (mirror) setMimeType(MimeTypes.VIDEO_MP2T)
        }.build()
        val source = ProgressiveMediaSource.Factory(DataSource.Factory { LanMediaDataSource(ip, mirror) })
            .createMediaSource(item)
        player.setMediaSource(source)
        player.prepare()
        player.play()
        statusView.text = if (mirror) "正在连接实时镜像…" else "正在载入视频…"
        updateStatus()
    }

    override fun pause() {
        if (active && mode == "video") player.pause()
        updateStatus()
    }

    override fun resume() {
        if (active && mode == "video") player.play()
        updateStatus()
    }

    override fun stopPlayback() {
        setFullscreen(false)
        mode = "idle"
        if (ready) { player.stop(); player.clearMediaItems() }
        playerView.useController = false
        pauseButton.isEnabled = false
        resumeButton.isEnabled = false
        emptyView.text = "在手机上选择本地视频\n或开始屏幕镜像"
        emptyView.visibility = View.VISIBLE
        warningView.text = "仅在可信任的家庭局域网使用。此原型使用明文 HTTP，不提供传输加密。"
        statusView.text = "已停止 · 等待新的投屏"
        updateStatus()
    }

    override fun status(): JSONObject = JSONObject(stateJson)
    override fun isMirroring(): Boolean = mode == "mirror"
    private fun playingLabel() = if (mode == "mirror") "正在屏幕镜像 · 实验性" else "正在播放本地视频"

    private fun updateStatus(error: String? = null) {
        if (!ready) return
        stateJson = JSONObject().put("ok", true).put("protocolVersion", 1)
            .put("mode", mode).put("playing", player.isPlaying)
            .put("positionMs", player.currentPosition)
            .put("durationMs", player.duration.takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("error", error ?: player.playerError?.errorCodeName ?: JSONObject.NULL).toString()
    }

    private fun renderPairing() {
        val snapshot = security.snapshot()
        codeView.text = if (snapshot.pairedIp == null) snapshot.code.chunked(3).joinToString(" ") else "已配对"
        pairStateView.text = if (snapshot.pairedIp == null) "配对码 ${snapshot.codeSeconds} 秒后更新\n在手机上输入电视地址和 6 位配对码"
            else "手机：${snapshot.pairedIp}\n连接剩余 ${snapshot.sessionSeconds / 60} 分钟"
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), dp(26), dp(28), dp(26))
            setBackgroundColor(Color.rgb(16, 26, 41))
        }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(24), 0) }
        panel.addView(label("局域网投屏", 28, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
        panel.addView(label("电视接收端 · v0.1", 14, muted()).apply { setPadding(0, dp(6), 0, dp(20)) })
        panel.addView(label("电视地址", 14, accent()))
        addressView = label("正在获取…", 17, Color.WHITE).apply { typeface = Typeface.MONOSPACE }
        panel.addView(addressView)
        panel.addView(label("配对码", 14, accent()).apply { setPadding(0, dp(20), 0, 0) })
        codeView = label("", 38, Color.WHITE).apply { typeface = Typeface.create("monospace", Typeface.BOLD) }
        panel.addView(codeView)
        pairStateView = label("", 13, muted()).apply { setPadding(0, dp(4), 0, dp(12)) }
        panel.addView(pairStateView)
        panel.addView(button("更换配对码 / 断开手机") {
            security.rotate()
            stopPlayback()
            renderPairing()
            statusView.text = "已断开手机 · 请重新配对"
        })
        pauseButton = button("暂停视频") { pause() }.apply { isEnabled = false }
        resumeButton = button("继续视频") { resume() }.apply { isEnabled = false }
        val controls = LinearLayout(this)
        controls.addView(pauseButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        controls.addView(resumeButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        panel.addView(controls)
        panel.addView(button("全屏播放（返回键退出）") { if (mode != "idle") setFullscreen(true) })
        panel.addView(button("停止投屏") { stopPlayback() })
        val updateButton = button("检查更新（手动安装）") { }
        updateButton.setOnClickListener { checkUpdate(updateButton) }
        panel.addView(updateButton)
        panel.addView(label("请让手机和电视连接同一网络。\n退出此界面会停止接收并断开配对。", 12, muted())
            .apply { setPadding(0, dp(12), 0, 0) })
        val panelScroll = ScrollView(this).apply { addView(panel); isFillViewport = true }
        pairPanelView = panelScroll
        rootContainer = root
        root.addView(panelScroll, LinearLayout.LayoutParams(dp(310), LinearLayout.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply { useController = false; keepScreenOn = true }
        stage.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        emptyView = label("在手机上选择本地视频\n或开始屏幕镜像", 23, muted()).apply { gravity = Gravity.CENTER }
        stage.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        content.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        statusView = label("等待手机连接", 17, Color.WHITE).apply { setPadding(0, dp(12), 0, dp(5)) }
        content.addView(statusView)
        warningView = label("仅在可信任的家庭局域网使用。此原型使用明文 HTTP，不提供传输加密。", 12, muted())
        content.addView(warningView)
        root.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        panel.getChildAt(7)?.requestFocus()
    }

    private fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        pairPanelView.visibility = if (enabled) View.GONE else View.VISIBLE
        statusView.visibility = if (enabled) View.GONE else View.VISIBLE
        warningView.visibility = if (enabled) View.GONE else View.VISIBLE
        rootContainer.setPadding(if (enabled) 0 else dp(28), if (enabled) 0 else dp(26),
            if (enabled) 0 else dp(28), if (enabled) 0 else dp(26))
        if (enabled) playerView.requestFocus() else pairPanelView.requestFocus()
    }

    @Deprecated("Simple Activity back handling")
    override fun onBackPressed() {
        if (fullscreen) setFullscreen(false) else super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Live mirroring has no seek/pause controls; stop and restart from the phone.
        if (mode == "mirror" && event.keyCode in intArrayOf(KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_REWIND)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun checkUpdate(button: Button) {
        button.isEnabled = false
        button.text = "正在检查…"
        updateWorker.execute {
            val result = runCatching {
                val conn = URL("https://api.github.com/repos/amateurish-programmer/lan-cast/releases/latest")
                    .openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    conn.setRequestProperty("User-Agent", "LanCast-TV/0.1.0")
                    when (conn.responseCode) {
                        404 -> "尚无已发布版本。当前版本：0.1.0。"
                        200 -> {
                            val text = conn.inputStream.bufferedReader().use { reader ->
                                val buffer = CharArray(262_145)
                                var count = 0
                                while (count < buffer.size) {
                                    val n = reader.read(buffer, count, buffer.size - count)
                                    if (n < 0) break
                                    count += n
                                }
                                check(count <= 262_144) { "更新响应过大" }
                                String(buffer, 0, count)
                            }
                            val tag = JSONObject(text).getString("tag_name").take(80)
                            "最新发布：$tag\n当前版本：0.1.0。\n下载 tv APK 后手动安装；只有相同签名的新版才能覆盖安装。"
                        }
                        else -> "更新服务暂不可用（HTTP ${conn.responseCode}）。"
                    }
                } finally { conn.disconnect() }
            }.getOrElse { "检查更新失败，请稍后重试。" }
            main.post {
                if (isFinishing || isDestroyed) return@post
                button.isEnabled = true
                button.text = "检查更新（手动安装）"
                if (!active) return@post
                AlertDialog.Builder(this).setTitle("应用更新")
                    .setMessage("$result\n\n没有电视浏览器时，可在手机或电脑打开发布页，下载 tv APK 后通过 U 盘安装。")
                    .setPositiveButton("打开发布页") { _, _ -> openReleases() }
                    .setNegativeButton("关闭", null).show()
            }
        }
    }

    private fun openReleases() {
        val url = "https://github.com/amateurish-programmer/lan-cast/releases/latest"
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this).setTitle("请在手机或电脑打开")
                .setMessage("$url\n\n下载 tv APK，再通过 U 盘手动安装到电视。")
                .setPositiveButton("知道了", null).show()
        }
    }

    private fun label(value: String, size: Int, color: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
    }
    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 13f
        isAllCaps = false
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(46))
    }
    private fun privateAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter(LanUrlPolicy::isPrivateIpv4).distinct()
    }.getOrDefault(emptyList())
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun accent() = Color.rgb(99, 220, 197)
    private fun muted() = Color.rgb(167, 185, 207)
}
