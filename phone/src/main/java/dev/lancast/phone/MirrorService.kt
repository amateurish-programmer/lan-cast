package dev.lancast.phone

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import dev.lancast.shared.Adts
import dev.lancast.shared.Avc
import dev.lancast.shared.MpegTsMuxer
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Real, experimental H.264/AAC-LC screen + eligible playback-audio mirror over MPEG-TS.
 * Requires Android 10+, RECORD_AUDIO and fresh MediaProjection consent for every session.
 * DRM/secure video may be black; playback capture is restricted by the source app's policy.
 * No microphone or calls are captured. Playback audio can include other playing apps even
 * when the user selects only one app in Android's screen-sharing picker.
 *
 * The Activity must start this foreground service only after successful system consent.
 * It passes that result Intent untouched, without calling getMediaProjection itself.
 * The existing token-authenticated local server owns transport and calls newClientStream().
 * No frames, consent Intents or media are logged or persisted here.
 *
 * Encoded dimensions are fixed for one session. Android 12L+ scales rotated/app-window
 * content into that canvas (possibly letterboxed). Restart sharing after rotating to
 * change the encoded orientation; older devices may not preserve aspect ratio on rotation.
 */
class MirrorService : Service() {
    companion object {
        const val ACTION_START = "dev.lancast.phone.MIRROR_START"
        const val ACTION_STOP = "dev.lancast.phone.MIRROR_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_GENERATION = "generation"

        private const val CHANNEL_ID = "lan_cast_mirror"
        private const val NOTIFICATION_ID = 2002
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val BYTES_PER_AUDIO_FRAME = CHANNELS * 2
        private const val AUDIO_CHUNK_BYTES = 1024 * BYTES_PER_AUDIO_FRAME
        private val stateLock = Any()
        private var runningService: MirrorService? = null

        @Volatile var active: Boolean = false
            private set
        /** The playback-capture route is running; this does not prove an app allows sound. */
        @Volatile var audioActive: Boolean = false
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile var audioStatus: String = "Stopped"
            private set
        @Volatile var videoWidth: Int = 0
            private set
        @Volatile var videoHeight: Int = 0
            private set
        @Volatile var lastVideoFrameAtMs: Long = 0L
            private set

        /**
         * Serve chunked video/mp2t with no-store, only after authenticating the session token.
         * At most four clients; slow readers are disconnected instead of growing memory.
         * The caller must close this stream on HTTP cancellation/disconnect.
         */
        @Throws(IOException::class)
        fun newClientStream(): InputStream = synchronized(stateLock) {
            val service = runningService
            if (!active || service == null || service.ending.get()) {
                throw IOException("Screen sharing is not running")
            }
            service.broadcast.open().also { service.syncFrameRequested.set(true) }
        }
    }

    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ending = AtomicBoolean(false)
    private val syncFrameRequested = AtomicBoolean(false)
    private val broadcast = BroadcastStream()
    private val muxLock = Any()
    private val muxer = MpegTsMuxer { packets -> broadcast.publish(packets) }
    private var startRequested = false
    private var sessionGeneration = -1L

    // Setup/release run on captureThread; codecs are then owned by their dedicated drain threads.
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var audioRecord: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var originUs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            requestStop(invalidateSession = true)
        }
        // A fixed encoded canvas avoids an illegal second createVirtualDisplay() call.
        // Surface scaling is handled by Android; see the orientation limitation above.
        override fun onCapturedContentResize(width: Int, height: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("LanCastProjection").apply { start() }
        captureHandler = Handler(captureThread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "屏幕和播放音频共享", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop(invalidateSession = true)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            if (!startRequested) requestStop()
            return START_NOT_STICKY
        }
        // Duplicate taps do not consume consent twice or replace an existing session.
        if (ending.get()) {
            synchronized(stateLock) {
                if (runningService == null) lastError = "Screen sharing is still stopping. Wait a moment and start again."
            }
            return START_NOT_STICKY
        }
        if (startRequested) return START_NOT_STICKY
        sessionGeneration = intent.getLongExtra(EXTRA_GENERATION, CastSession.generation.get())
        if (sessionGeneration != CastSession.generation.get()) {
            requestStop()
            return START_NOT_STICKY
        }
        synchronized(stateLock) {
            runningService = this
            active = false
            audioActive = false
            lastError = null
            audioStatus = "Starting playback capture"
            lastVideoFrameAtMs = 0L
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            requestStop("Screen-sharing permission was not granted. Start again to request it.")
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestStop("Screen and playback-audio sharing needs Android 10 or later and audio capture permission.")
            return START_NOT_STICKY
        }
        startRequested = true
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (_: Exception) {
            requestStop("Could not start screen sharing. Open LAN Cast and grant capture permission again.")
            return START_NOT_STICKY
        }
        captureHandler.post {
            if (!ending.get()) {
                try {
                    startCapture(resultCode, resultData)
                } catch (failure: Exception) {
                    requestStop("Could not start screen/audio capture: ${failure.message ?: failure.javaClass.simpleName}")
                }
            }
        }
        // Never replay consent or silently restart capture after process death.
        return START_NOT_STICKY
    }

    // onStartCommand rejects pre-29 devices before this method is posted.
    @android.annotation.TargetApi(29)
    private fun startCapture(resultCode: Int, resultData: Intent) {
        check(Build.VERSION.SDK_INT >= 29)
        val mediaProjection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, resultData) ?: error("Permission expired; request screen sharing again")
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, captureHandler)
        if (ending.get()) return

        val size = encoderSize()
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, size.x, size.y).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30f)
            // Keep a static screen alive, including PCRs and a fresh IDR for new viewers.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 33_333L)
        }
        val encoderInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(VIDEO_MIME, true) } &&
                runCatching { info.getCapabilitiesForType(VIDEO_MIME).isFormatSupported(videoFormat) }.getOrDefault(false)
        } ?: error("No compatible hardware H.264 encoder for ${size.x} × ${size.y}")
        val video = MediaCodec.createByCodecName(encoderInfo.name)
        videoCodec = video
        video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = video.createInputSurface()
        video.start()
        if (ending.get()) return

        val audio = MediaCodec.createEncoderByType(AUDIO_MIME)
        audioCodec = audio
        audio.configure(MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_CHUNK_BYTES)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audio.start()
        if (ending.get()) return
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "48 kHz stereo playback capture is unavailable" }
        // Re-check on this worker: permission may have changed since the Activity started us.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Playback audio capture permission was revoked")
        }
        val recorder = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            .setBufferSizeInBytes(max(minBuffer * 4, AUDIO_CHUNK_BYTES * 4))
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
        audioRecord = recorder
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Playback capture could not initialize" }
        check(recorder.sampleRate == SAMPLE_RATE && recorder.channelCount == CHANNELS) {
            "Playback capture did not provide the requested 48 kHz stereo format"
        }
        if (ending.get()) return

        // Both media tracks share CLOCK_MONOTONIC, never wall-clock/network arrival time.
        originUs = System.nanoTime() / 1000L
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Playback capture did not start" }
        if (ending.get()) return
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "LAN Cast screen", size.x, size.y, resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, captureHandler
        ) ?: error("No capture display was created")
        synchronized(stateLock) {
            if (!ending.get() && runningService === this) {
                active = true
                audioActive = true
                audioStatus = "Capturing permitted playback; protected or opted-out apps may be silent"
                videoWidth = size.x
                videoHeight = size.y
            }
        }
        if (ending.get()) return
        videoThread = Thread({ drainVideo(video) }, "LanCastVideo").apply { start() }
        audioThread = Thread({ captureAudio(recorder, audio) }, "LanCastAudio").apply { start() }
    }

    @Suppress("DEPRECATION")
    private fun encoderSize(): Point {
        val size = if (Build.VERSION.SDK_INT >= 30) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            Point().also { getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(it) }
        }
        val longEdge = max(size.x, size.y).coerceAtLeast(1)
        val shortEdge = minOf(size.x, size.y).coerceAtLeast(1)
        val scale = minOf(1.0, 1280.0 / longEdge, 720.0 / shortEdge)
        // Common hardware encoders require aligned dimensions. Preserve aspect approximately.
        fun aligned(value: Int) = ((value * scale).roundToInt() / 16 * 16).coerceAtLeast(16)
        return Point(aligned(size.x), aligned(size.y))
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var parameterSets = ByteArray(0)
        var nalLengthSize = 4
        var ptsOffset: Long? = null
        var lastPtsUs = -1L
        try {
            while (!ending.get()) {
                if (syncFrameRequested.getAndSet(false)) {
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                }
                val index = codec.dequeueOutputBuffer(info, 10_000L)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    parameterSets = (0..1).mapNotNull { number ->
                        format.getByteBuffer("csd-$number")?.let {
                            val config = copyBytes(it)
                            if (config.size > 4 && config[0] == 1.toByte()) nalLengthSize = (config[4].toInt() and 3) + 1
                            Avc.configurationToAnnexB(config)
                        }
                    }.fold(ByteArray(0)) { accumulated, bytes -> accumulated + bytes }
                } else if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index) ?: error("Missing video buffer")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = copyBytes(buffer)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                if (bytes.size > 4 && bytes[0] == 1.toByte()) nalLengthSize = (bytes[4].toInt() and 3) + 1
                                parameterSets = Avc.configurationToAnnexB(bytes)
                            } else {
                                val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                val annexB = Avc.toAnnexB(bytes, nalLengthSize)
                                check(!keyframe || parameterSets.isNotEmpty()) { "Hardware encoder omitted H.264 configuration" }
                                if (ptsOffset == null) {
                                    // Surface timestamps normally share CLOCK_MONOTONIC. Accommodate
                                    // vendor codecs that instead reset the first timestamp near zero.
                                    ptsOffset = if (abs(info.presentationTimeUs - originUs) < 10_000_000L) originUs
                                        else info.presentationTimeUs - (System.nanoTime() / 1000L - originUs)
                                }
                                val ptsUs = max(lastPtsUs + 1, info.presentationTimeUs - ptsOffset!!).coerceAtLeast(0L)
                                lastPtsUs = ptsUs
                                synchronized(muxLock) {
                                    if (!ending.get()) {
                                        if (keyframe) broadcast.activateWaitingClients()
                                        muxer.writeVideo(if (keyframe) parameterSets + annexB else annexB, ptsUs, keyframe)
                                    }
                                }
                                synchronized(stateLock) {
                                    if (!ending.get() && active && runningService === this) {
                                        lastVideoFrameAtMs = System.currentTimeMillis()
                                    }
                                }
                            }
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (!ending.get()) requestStop("The video encoder ended unexpectedly. Start sharing again.")
                        break
                    }
                }
            }
        } catch (failure: Exception) {
            if (!ending.get()) requestStop("Video encoder stopped: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    private fun captureAudio(recorder: AudioRecord, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val timestamp = AudioTimestamp()
        var capturedFrames = 0L
        var fallbackFirstSampleUs: Long? = null
        var previousInputPts = -1L
        var previousOutputPts = -1L
        try {
            while (!ending.get()) {
                val inputIndex = codec.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: error("Missing audio input buffer")
                    input.clear()
                    val requested = minOf(AUDIO_CHUNK_BYTES, input.capacity()) / BYTES_PER_AUDIO_FRAME * BYTES_PER_AUDIO_FRAME
                    check(requested > 0) { "Audio input buffer is too small" }
                    val read = recorder.read(input, requested, AudioRecord.READ_BLOCKING)
                    if (ending.get()) break
                    check(read > 0 && read % BYTES_PER_AUDIO_FRAME == 0) { "Playback audio read failed ($read)" }
                    val frames = read / BYTES_PER_AUDIO_FRAME
                    if (fallbackFirstSampleUs == null) {
                        fallbackFirstSampleUs = System.nanoTime() / 1000L - originUs - frames * 1_000_000L / SAMPLE_RATE
                    }
                    val timestampAvailable = recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                    val measuredPts = if (timestampAvailable) {
                        timestamp.nanoTime / 1000L - originUs +
                            (capturedFrames - timestamp.framePosition) * 1_000_000L / SAMPLE_RATE
                    } else {
                        fallbackFirstSampleUs!! + capturedFrames * 1_000_000L / SAMPLE_RATE
                    }
                    val ptsUs = max(previousInputPts + 1, measuredPts).coerceAtLeast(0L)
                    previousInputPts = ptsUs
                    codec.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                    capturedFrames += frames
                }
                while (!ending.get()) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 0L)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outputIndex < 0) continue
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = codec.getOutputBuffer(outputIndex) ?: error("Missing audio output buffer")
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val rawAac = copyBytes(output)
                            val adts = Adts.header(rawAac.size, SAMPLE_RATE, CHANNELS) + rawAac
                            val ptsUs = max(previousOutputPts + 1, info.presentationTimeUs).coerceAtLeast(0L)
                            previousOutputPts = ptsUs
                            synchronized(muxLock) {
                                if (!ending.get()) muxer.writeAudio(adts, ptsUs)
                            }
                        }
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (!ending.get()) requestStop("The audio encoder ended unexpectedly. Start sharing again.")
                        break
                    }
                }
            }
        } catch (failure: Exception) {
            if (!ending.get()) requestStop("Playback audio stopped: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    private fun copyBytes(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("正在共享屏幕和播放音频")
            .setContentText("受保护视频可能黑屏或静音，点击停止结束共享。")
            .setStyle(Notification.BigTextStyle().bigText(
                "正在共享所选屏幕/应用及允许采集的播放音频，其他播放中的应用也可能被听到。" +
                    "不采集麦克风或通话。受保护视频可能黑屏或静音，点击停止结束。"
            ))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "停止", stop).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(PendingIntent.getActivity(
                this, 1, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        }
        return builder.build()
    }

    private fun clearPublishedState(error: String? = null) {
        synchronized(stateLock) {
            if (runningService === this) {
                active = false
                audioActive = false
                audioStatus = "Stopped"
                videoWidth = 0
                videoHeight = 0
                lastVideoFrameAtMs = 0L
                if (error != null) lastError = error
                runningService = null
            }
        }
        broadcast.close()
    }

    private fun requestStop(error: String? = null, invalidateSession: Boolean = false) {
        if (!ending.compareAndSet(false, true)) return
        // Cancel background work immediately, even when Android delivers onStop off main.
        val invalidated = invalidateSession && sessionGeneration >= 0 &&
            CastSession.generation.compareAndSet(sessionGeneration, sessionGeneration + 1)
        val revokedCapability = if (invalidated) MediaServerService.capability else null
        clearPublishedState(error)
        mainHandler.post {
            // Capability writes are on main with new Activity sessions. Never clear a
            // capability that was rotated by a newer cast while this callback was queued.
            if (invalidated && CastSession.generation.get() == sessionGeneration + 1 &&
                MediaServerService.capability == revokedCapability) {
                MediaServerService.capability = ""
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun releaseCapture() {
        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.unregisterCallback(projectionCallback) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        // Unblock a READ_BLOCKING call before joining the capture worker.
        runCatching { audioRecord?.stop() }
        runCatching { videoThread?.join(1500L) }
        runCatching { audioThread?.join(1500L) }
        videoThread = null
        audioThread = null
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        videoCodec = null
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { oldProjection?.stop() }
    }

    override fun onDestroy() {
        ending.set(true)
        clearPublishedState()
        captureHandler.post {
            try {
                releaseCapture()
            } finally {
                captureThread.quitSafely()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
