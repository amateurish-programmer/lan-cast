package dev.lancast.phone

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dev.lancast.shared.HttpRange
import dev.lancast.shared.RangeResult
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/** Content is never copied to public storage. Every playback gets a fresh, unguessable capability. */
class MediaServerService : Service() {
    private var server: NanoHTTPD? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("media", "局域网视频服务", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val stop = PendingIntent.getService(this, 2, Intent(this, MediaServerService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        if (intent?.action == "stop") { CastSession.generation.incrementAndGet(); capability = ""; stopSelf(); return START_NOT_STICKY }
        if (intent?.getLongExtra("generation", -1L) != CastSession.generation.get()) {
            if (!ready) stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(2, Notification.Builder(this, "media").setContentTitle("正在向电视提供视频")
            .setContentText("仅在可信 Wi-Fi 使用，点击停止结束共享")
            .setSmallIcon(android.R.drawable.ic_media_play).addAction(Notification.Action.Builder(null, "停止共享", stop).build()).build())
        if (server == null) try {
            error = null
            server = MediaHttpServer().also { it.start(5000, false) }
            ready = true
        } catch (e: Exception) {
            ready = false
            error = e.message ?: "Media server could not start"
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { CastSession.generation.incrementAndGet(); capability = ""; stopSelf() }
    override fun onDestroy() {
        ready = false
        capability = ""
        selectedUri = null
        allowedTvIp = ""
        server?.stop()
        server = null
        // The media notification's Stop action must also end the underlying capture.
        stopService(Intent(this, MirrorService::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private inner class MediaHttpServer : NanoHTTPD(8766) {
        init {
            setAsyncRunner(object : AsyncRunner {
                private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<ClientHandler>()
                private val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
                @Synchronized override fun exec(code: ClientHandler) {
                    if (clients.size >= 8) { code.close(); return }
                    clients.add(code)
                    try { pool.execute(code) } catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(code); code.close() }
                }
                override fun closed(code: ClientHandler) { clients.remove(code) }
                override fun closeAll() { clients.forEach { it.close() }; clients.clear(); pool.shutdownNow() }
            })
        }

        // Keep byte ranges tied to the original representation, and prevent gzip
        // from creating a nonempty compressed payload for an otherwise empty HEAD.
        override fun useGzipWhenAccepted(response: Response): Boolean = false

        override fun serve(session: IHTTPSession): Response {
            val response = try { route(session) } catch (_: Exception) {
                newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream unavailable; restart sharing")
            }
            response.addHeader("Cache-Control", "no-store")
            response.addHeader("X-Content-Type-Options", "nosniff")
            if (session.method == Method.HEAD) {
                // NanoHTTPD 2.3.1 still reads fixed-length HEAD bodies. Keep the GET
                // Content-Length metadata but explicitly replace the body with EOF.
                runCatching { response.data?.close() }
                response.data = ByteArrayInputStream(ByteArray(0))
            }
            return response
        }

        private fun route(session: IHTTPSession): Response {
            val secret = capability
            if (secret.isBlank() || session.remoteIpAddress != allowedTvIp) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
            if (session.method != Method.GET && session.method != Method.HEAD) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET/HEAD only").apply { addHeader("Allow", "GET, HEAD") }
            if (!session.queryParameterString.isNullOrEmpty()) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Query parameters are not supported")
            if (session.uri == "/stream/$secret") {
                if (session.method == Method.HEAD) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Live streams require GET").apply { addHeader("Allow", "GET") }
                if (!MirrorService.active) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Mirror unavailable")
                return newChunkedResponse(Response.Status.OK, "video/mp2t", MirrorService.newClientStream())
            }
            if (session.uri != "/media/$secret") return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            val uri = selectedUri ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No media")
            var descriptor: ParcelFileDescriptor? = null
            var stream: ParcelFileDescriptor.AutoCloseInputStream? = null
            var handedToResponse = false
            try {
                val fd = contentResolver.openFileDescriptor(uri, "r")
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Media is no longer available")
                descriptor = fd
                val input = ParcelFileDescriptor.AutoCloseInputStream(fd)
                stream = input
                val size = fd.statSize
                if (size < 0) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Please select a seekable local file")
                // No representation validators are exposed, so an If-Range condition
                // cannot be verified and must result in a full representation.
                val rangeHeader = if (session.method == Method.GET && session.headers["if-range"] == null) session.headers["range"] else null
                val range = HttpRange.parse(rangeHeader, size)
                if (range is RangeResult.Invalid) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Malformed or unsupported Range header")
                }
                if (range is RangeResult.Unsatisfiable) {
                    return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Range selects no bytes").apply {
                        addHeader("Content-Range", range.contentRange)
                        addHeader("Accept-Ranges", "bytes")
                    }
                }
                val start = if (range is RangeResult.Partial) range.start else 0L
                val length = if (range is RangeResult.Partial) range.contentLength else size
                try {
                    input.channel.position(start)
                } catch (_: Exception) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Please select a seekable local file")
                }
                val mimeType = contentResolver.getType(uri)?.takeIf {
                    it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
                } ?: "application/octet-stream"
                val response = newFixedLengthResponse(if (range is RangeResult.Partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                    mimeType, input, length)
                response.addHeader("Accept-Ranges", "bytes")
                if (range is RangeResult.Partial) response.addHeader("Content-Range", range.contentRange)
                // NanoHTTPD closes the response and its stream on success or a broken
                // socket. Until this point the server retains ownership of the FD.
                handedToResponse = true
                return response
            } catch (_: Exception) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Media read failed")
            } finally {
                if (!handedToResponse) {
                    // Close the owning stream once; close the descriptor directly only
                    // if constructing its AutoCloseInputStream failed.
                    runCatching { stream?.close() ?: descriptor?.close() }
                }
            }
        }
    }
    companion object {
        @Volatile var selectedUri: Uri? = null
        @Volatile var allowedTvIp = ""
        @Volatile var capability = ""
        @Volatile var ready = false
        @Volatile var error: String? = null
        fun rotateCapability(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }.also { capability = it }
    }
}
