package dev.lancast.tv

import android.os.Handler
import fi.iki.elonen.NanoHTTPD
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** HTTP is intentionally LAN-only. Tokens are authentication, not transport encryption. */
class ControlServer(
    private val security: PairingSecurity,
    private val main: Handler,
    private val callbacks: Callbacks
) : NanoHTTPD("0.0.0.0", PORT) {
    interface Callbacks {
        fun paired(ip: String)
        fun play(url: String, ip: String, mirror: Boolean)
        fun pause()
        fun resume()
        fun stopPlayback()
        fun status(): JSONObject
        fun isMirroring(): Boolean
    }

    init {
        // NanoHTTPD's default runner is unbounded, so cap simultaneous LAN clients.
        setAsyncRunner(object : AsyncRunner {
            private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
            private val executor = Executors.newFixedThreadPool(4)
            override fun exec(code: ClientHandler) {
                if (clients.size >= 4) { code.close(); return }
                clients.add(code)
                try { executor.execute(code) } catch (_: RejectedExecutionException) {
                    clients.remove(code)
                    code.close()
                }
            }
            override fun closed(code: ClientHandler) { clients.remove(code) }
            override fun closeAll() {
                clients.forEach { it.close() }
                clients.clear()
                executor.shutdownNow()
            }
        })
    }

    override fun serve(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress ?: ""
        if (!LanUrlPolicy.isPrivateIpv4(ip)) return error(Response.Status.FORBIDDEN, "仅允许同一局域网的 IPv4 设备")
        // No browser CORS access, including simple cross-origin form requests.
        if (session.headers.containsKey("origin")) return error(Response.Status.FORBIDDEN, "不接受浏览器跨域请求")
        if (session.queryParameterString != null) return error(Response.Status.BAD_REQUEST, "请求不能包含查询参数")
        val isStatus = session.uri == "/status"
        if ((isStatus && session.method != Method.GET) || (!isStatus && session.method != Method.POST)) {
            return error(Response.Status.METHOD_NOT_ALLOWED, "请求方法不正确")
        }
        try {
            if (session.uri == "/pair") return pair(session, ip)
            val authorization = session.headers["authorization"]
            if (!security.authorized(authorization, ip)) return error(Response.Status.UNAUTHORIZED, "配对已过期，请重新配对")
            if (isStatus) return json(Response.Status.OK, callbacks.status())
            return when (session.uri) {
                "/play", "/mirror" -> {
                    val body = readJson(session)
                    val url = body.optString("url")
                    val mirror = session.uri == "/mirror"
                    if (!LanUrlPolicy.accepts(url, ip, mirror)) {
                        error(Response.Status.BAD_REQUEST, "媒体地址必须来自已配对手机的局域网服务")
                    } else dispatch(authorization, ip) { callbacks.play(url, ip, mirror) }
                }
                "/pause", "/resume" -> {
                    if (callbacks.isMirroring()) error(Response.Status.CONFLICT, "实时镜像请停止后重新开始，不支持暂停")
                    else dispatch(authorization, ip) {
                        if (session.uri == "/pause") callbacks.pause() else callbacks.resume()
                    }
                }
                "/stop" -> dispatch(authorization, ip) { callbacks.stopPlayback() }
                else -> error(Response.Status.NOT_FOUND, "未知接口")
            }
        } catch (e: RequestProblem) {
            return error(e.status, e.message ?: "请求不正确")
        } catch (_: JSONException) {
            return error(Response.Status.BAD_REQUEST, "JSON 格式不正确")
        } catch (_: Exception) {
            return error(Response.Status.BAD_REQUEST, "无法读取请求")
        }
    }

    private fun pair(session: IHTTPSession, ip: String): Response {
        val code = readJson(session).optString("code")
        return when (val result = security.pair(code, ip)) {
            is PairingSecurity.PairResult.Accepted -> {
                main.post {
                    if (security.authorized("Bearer ${result.token}", ip)) callbacks.paired(ip)
                }
                json(Response.Status.OK, JSONObject().put("token", result.token)
                    .put("expiresInSeconds", PairingSecurity.SESSION_LIFETIME_MS / 1000)
                    .put("protocolVersion", 1).put("ok", true))
            }
            PairingSecurity.PairResult.Incorrect -> error(Response.Status.UNAUTHORIZED, "配对码不正确")
            PairingSecurity.PairResult.Expired -> error(Response.Status.UNAUTHORIZED, "配对码已过期，请查看电视")
            PairingSecurity.PairResult.AlreadyPaired -> error(Response.Status.CONFLICT, "电视已配对，请在电视上更换配对码")
            PairingSecurity.PairResult.RateLimited -> json(TOO_MANY_REQUESTS, JSONObject()
                .put("error", "尝试过于频繁，请等待 60 秒")).apply { addHeader("Retry-After", "60") }
        }
    }

    private fun dispatch(authorization: String?, ip: String, action: () -> Unit): Response {
        main.post {
            // Reject commands queued just before code rotation or activity shutdown.
            if (security.authorized(authorization, ip)) action()
        }
        return json(Response.Status.OK, JSONObject().put("ok", true).put("accepted", true))
    }

    private fun readJson(session: IHTTPSession): JSONObject {
        val contentType = session.headers["content-type"]?.substringBefore(';')?.trim()
        if (contentType != "application/json") throw RequestProblem(Response.Status.UNSUPPORTED_MEDIA_TYPE, "请发送 application/json")
        if (session.headers.containsKey("transfer-encoding")) throw RequestProblem(Response.Status.BAD_REQUEST, "不支持分块请求体")
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: throw RequestProblem(Response.Status.BAD_REQUEST, "缺少请求体长度")
        if (length !in 1..4096) throw RequestProblem(Response.Status.PAYLOAD_TOO_LARGE, "请求体过大或为空")
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: throw RequestProblem(Response.Status.BAD_REQUEST, "缺少 JSON 请求体")
        return JSONObject(body)
    }

    private class RequestProblem(val status: Response.IStatus, message: String) : Exception(message)
    private fun error(status: Response.IStatus, message: String) = json(status, JSONObject().put("error", message))
    private fun json(status: Response.IStatus, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Connection", "close")
        }

    companion object {
        const val PORT = 8765
        private val TOO_MANY_REQUESTS = object : Response.IStatus {
            override fun getRequestStatus() = 429
            override fun getDescription() = "429 Too Many Requests"
        }
    }
}
