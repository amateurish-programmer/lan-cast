package dev.lancast.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** A minimal range-capable source which never follows redirects or resolves hostnames. */
@UnstableApi
class LanMediaDataSource(private val pairedIp: String, private val mirror: Boolean) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var opened = false
    private var currentUri: Uri? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val value = dataSpec.uri.toString()
        if (!LanUrlPolicy.accepts(value, pairedIp, mirror)) throw IOException("不允许的媒体地址")
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) throw IOException("仅支持 GET")
        currentUri = dataSpec.uri
        try {
            val conn = (URL(value).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 5_000
                readTimeout = if (mirror) 10_000 else 15_000
                setRequestProperty("Accept-Encoding", "identity")
                if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) ""
                        else (dataSpec.position + dataSpec.length - 1).toString()
                    setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                }
            }
            connection = conn
            val response = conn.responseCode
            if (response != 200 && response != 206) throw IOException("媒体服务响应 $response（不允许重定向）")
            if (dataSpec.position > 0 && response != 206) throw IOException("手机媒体服务不支持断点读取")
            if (response == 206) {
                val contentRange = conn.getHeaderField("Content-Range") ?: ""
                val rangeStart = Regex("bytes ([0-9]+)-([0-9]+)/(?:[0-9]+|\\*)").matchEntire(contentRange)
                    ?.groupValues?.get(1)?.toLongOrNull()
                if (rangeStart != dataSpec.position) throw IOException("媒体服务返回了错误的字节范围")
            }
            input = conn.inputStream
            val length = conn.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                else length ?: C.LENGTH_UNSET.toLong()
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val amount = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val count = (input ?: throw IOException("媒体流未打开")).read(buffer, offset, amount)
        if (count == -1) {
            if (bytesRemaining > 0) throw EOFException("媒体流意外中断")
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri
    override fun getResponseHeaders(): Map<String, List<String>> = connection?.headerFields
        ?.filterKeys { it != null }?.mapKeys { it.key!! } ?: emptyMap()

    override fun close() {
        try { input?.close() } finally {
            input = null
            connection?.disconnect()
            connection = null
            currentUri = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
