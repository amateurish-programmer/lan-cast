package dev.lancast.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import dev.lancast.shared.ApprovalSession
import dev.lancast.updater.UpdateController
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

@UnstableApi
class MainActivity : Activity(), ControlServer.Callbacks {
    private val main = Handler(Looper.getMainLooper())
    private val security = PairingSecurity()
    private lateinit var updater: UpdateController
    private lateinit var advertisement: ReceiverAdvertisement
    private var approvalDialog: AlertDialog? = null
    private var dialogRequest: String? = null
    private val focusButtons = mutableListOf<Button>()
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var rootContainer: LinearLayout
    private lateinit var pairPanelView: View
    private var fullscreen = false
    private lateinit var connectionView: TextView
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
            if (security.expire() || (mode != "idle" && security.pairedIp() == null)) {
                stopPlayback()
                security.revoke()
                statusView.text = "配对已过期，请在手机上重新连接"
            }
            if (dialogRequest != null && security.pending()?.id != dialogRequest) dismissApproval()
            renderPairing()
            updateStatus()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updater = UpdateController(this, "tv")
        advertisement = ReceiverAdvertisement(this) { name -> main.post { if (active) addressView.text = name } }
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
        security.revoke()
        try {
            server = ControlServer(security, main, this).also { it.start(5_000, false) }
            addressView.text = "正在广播接收设备…"
            advertisement.start()
            statusView.text = "接收器已就绪 · 在手机中选择这台电视"
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
        advertisement.stop()
        dismissApproval()
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
        updater.close()
        advertisement.stop()
        dismissApproval()
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

    override fun onResume() { super.onResume(); updater.onResume() }

    override fun pairingRequested(request: ApprovalSession.Request) {
        if (!active || security.pending()?.id != request.id) return
        setFullscreen(false)
        dismissApproval()
        dialogRequest = request.id
        approvalDialog = AlertDialog.Builder(this)
            .setTitle("允许这台手机连接？")
            .setMessage("${request.name}\n${request.ip}\n\n允许后可向电视发送视频和屏幕镜像。请只接受你认识的设备。请求将在 60 秒后失效。")
            .setPositiveButton("允许连接") { _, _ ->
                if (active && security.decide(request.id, true)) {
                    statusView.text = "已连接 ${request.name} · 等待投屏"
                    renderPairing()
                }
                dialogRequest = null
            }
            .setNegativeButton("拒绝") { _, _ -> security.decide(request.id, false); dialogRequest = null; renderPairing() }
            .setOnCancelListener { security.decide(request.id, false); dialogRequest = null; renderPairing() }
            .create().also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
            }
        renderPairing()
    }

    private fun dismissApproval() { approvalDialog?.dismiss(); approvalDialog = null; dialogRequest = null }
    override fun pairingCancelled() {
        if (!active) return
        // A queued cancellation must never dismiss a newer request or stop its playback.
        if (dialogRequest != null && security.pending()?.id != dialogRequest) dismissApproval()
        if (security.pairedIp() == null && mode != "idle") stopPlayback()
        renderPairing()
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
        stateJson = JSONObject().put("ok", true).put("protocolVersion", 2)
            .put("mode", mode).put("playing", player.isPlaying)
            .put("positionMs", player.currentPosition)
            .put("durationMs", player.duration.takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("error", error ?: player.playerError?.errorCodeName ?: JSONObject.NULL).toString()
    }

    private fun renderPairing() {
        val ip = security.pairedIp()
        val pending = security.pending()
        connectionView.text = when { ip != null -> "已连接"; pending != null -> "等待批准"; else -> "准备就绪" }
        pairStateView.text = when {
            ip != null -> "手机 $ip\n连接剩余 ${security.seconds() / 60} 分钟"
            pending != null -> "${pending.name} 请求连接\n请用遥控器允许或拒绝"
            else -> "1  手机和电视连接同一网络\n2  在手机里选择这台电视\n3  用遥控器允许连接"
        }
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28), dp(26), dp(28), dp(26))
            setBackgroundColor(Color.rgb(16, 26, 41))
        }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(24), 0) }
        panel.addView(label("Lan Cast", 30, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
        panel.addView(label("大屏接收端  /  v0.2", 14, muted()).apply { setPadding(0, dp(6), 0, dp(20)) })
        val connectionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(5))
            background = shape(Color.rgb(22, 37, 53), dp(14).toFloat(), Color.rgb(39, 59, 74))
        }
        panel.addView(connectionCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        connectionCard.addView(label("附近设备中显示为", 12, accent()))
        addressView = label("正在获取…", 17, Color.WHITE).apply { typeface = Typeface.MONOSPACE }
        connectionCard.addView(addressView)
        connectionCard.addView(label("连接状态", 12, accent()).apply { setPadding(0, dp(18), 0, dp(5)) })
        connectionView = label("", 28, Color.WHITE).apply { typeface = Typeface.create("monospace", Typeface.BOLD) }
        connectionCard.addView(connectionView)
        pairStateView = label("", 13, muted()).apply { setPadding(0, dp(4), 0, dp(12)) }
        connectionCard.addView(pairStateView)
        panel.addView(button("断开当前手机") {
            security.revoke()
            dismissApproval()
            stopPlayback()
            renderPairing()
            statusView.text = "已断开手机 · 请重新配对"
        })
        pauseButton = button("暂停视频") { pause() }.apply { isEnabled = false }
        resumeButton = button("继续视频") { resume() }.apply { isEnabled = false }
        val controls = LinearLayout(this)
        controls.addView(pauseButton, LinearLayout.LayoutParams(0, dp(43), 1f).apply { rightMargin = dp(4); bottomMargin = dp(7) })
        controls.addView(resumeButton, LinearLayout.LayoutParams(0, dp(43), 1f).apply { leftMargin = dp(4); bottomMargin = dp(7) })
        panel.addView(controls)
        panel.addView(button("全屏播放（返回键退出）") { if (mode != "idle") setFullscreen(true) })
        panel.addView(button("停止投屏") { stopPlayback() })
        panel.addView(button("检查应用更新") { updater.check() })
        panel.addView(label("请让手机和电视连接同一网络。\n退出此界面会停止接收并断开配对。", 12, muted())
            .apply { setPadding(0, dp(12), 0, 0) })
        val panelScroll = ScrollView(this).apply { addView(panel); isFillViewport = true }
        pairPanelView = panelScroll
        rootContainer = root
        root.addView(panelScroll, LinearLayout.LayoutParams(dp(310), LinearLayout.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val stage = FrameLayout(this).apply { background = shape(Color.rgb(10, 17, 29), dp(18).toFloat(), Color.rgb(41, 58, 77)); clipToOutline = true }
        playerView = PlayerView(this).apply { useController = false; keepScreenOn = true }
        stage.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        emptyView = label("让精彩，来到大屏\n\n连接手机后，即可播放视频或镜像屏幕", 23, muted()).apply { gravity = Gravity.CENTER }
        stage.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        content.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        statusView = label("等待手机连接", 17, Color.WHITE).apply { setPadding(0, dp(12), 0, dp(5)) }
        content.addView(statusView)
        warningView = label("仅在可信任的家庭局域网使用。此原型使用明文 HTTP，不提供传输加密。", 12, muted())
        content.addView(warningView)
        root.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        focusButtons.forEachIndexed { index, button ->
            button.nextFocusUpId = focusButtons.getOrNull(index - 1)?.id ?: button.id
            button.nextFocusDownId = focusButtons.getOrNull(index + 1)?.id ?: button.id
        }
        pauseButton.nextFocusRightId = resumeButton.id
        resumeButton.nextFocusLeftId = pauseButton.id
        pauseButton.nextFocusDownId = focusButtons[3].id
        resumeButton.nextFocusUpId = focusButtons[0].id
        focusButtons.firstOrNull()?.requestFocus()
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

    private fun label(value: String, size: Int, color: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
    }
    private fun shape(color: Int, radius: Float = dp(10).toFloat(), stroke: Int = color) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; setStroke(dp(2), stroke)
    }
    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        id = View.generateViewId()
        text = title
        textSize = 13f
        isAllCaps = false
        isFocusable = true
        setTextColor(Color.WHITE)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(Color.rgb(28, 85, 79), stroke = accent()))
            addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(28, 85, 79), stroke = accent()))
            addState(intArrayOf(), shape(Color.rgb(30, 45, 63)))
        }
        setOnFocusChangeListener { _, focused -> alpha = if (isEnabled) 1f else .4f; elevation = if (focused) dp(5).toFloat() else 0f }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(43)).apply { bottomMargin = dp(7) }
        focusButtons.add(this)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun accent() = Color.rgb(99, 220, 197)
    private fun muted() = Color.rgb(167, 185, 207)
}
