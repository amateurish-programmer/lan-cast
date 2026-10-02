package dev.lancast.updater

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.lancast.shared.UpdatePolicy
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Activity-owned, cancellable updater. Call onResume()/close() from Activity lifecycle. */
class UpdateController(private val activity: Activity, private val appAssetPrefix: String) {
    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private var cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    private var busy = false
    private var dialog: AlertDialog? = null
    private val prefs = activity.getSharedPreferences("verified_updater", 0)
    private val directory = File(activity.cacheDir, "verified-updates").apply { mkdirs() }
    private data class Candidate(val file: File, val hash: String, val tag: String, val versionCode: Long)

    init {
        require(appAssetPrefix == "phone" || appAssetPrefix == "tv")
        val staged = prefs.getString("file", null)
        directory.listFiles()?.filter { it.name != staged && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
    }

    fun check() {
        if (closed.get() || busy) return
        runJob("检查更新", "正在连接 GitHub…") {
            val release = ReleaseParser.parse(readText("https://raw.githubusercontent.com/amateurish-programmer/lan-cast/updates/manifest.json", UpdatePolicy.MAX_METADATA_BYTES), appAssetPrefix)
            val current = installed()
            if (release.versionCode <= version(current)) {
                complete { show("无需更新", "当前版本：${current.versionName} (${version(current)})\n服务器版本：${release.versionName} (${release.versionCode})\n已是相同或更新版本，不会降级安装。") }
                return@runJob
            }
            UpdatePolicy.validateManifest(release, appAssetPrefix, identity(current))
            val asset = release.asset
            complete {
                dialog = AlertDialog.Builder(activity).setTitle("发现发布 ${release.versionName}")
                    .setMessage("当前版本：${current.versionName} (${version(current)})\n发布版本：${release.versionName}\n\n${release.notes.ifBlank { "未提供更新说明" }}\n\n下载后会校验版本、包名、SHA-256 和签名。相同或较旧版本不会安装。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("下载并验证") { _, _ -> download(release, asset) }.show()
            }
        }
    }

    fun onResume() {
        if (closed.get() || busy || !prefs.getBoolean("awaiting_permission", false)) return
        prefs.edit().putBoolean("awaiting_permission", false).apply()
        if (!activity.packageManager.canRequestPackageInstalls()) {
            show("安装权限未开启", "没有更改应用。可再次检查更新，在系统设置允许本应用安装后重试。")
            return
        }
        runJob("验证更新", "正在重新验证下载的安装包…") {
            val candidate = loadCandidate() ?: error("下载缓存已失效，请重新检查更新")
            verify(candidate)
            complete { promptInstall(candidate) }
        }
    }

    fun close() {
        closed.set(true)
        cancelled.set(true)
        connection?.disconnect()
        executor.shutdownNow()
        dismiss()
    }

    private fun download(release: UpdatePolicy.Manifest, asset: UpdatePolicy.Asset) {
        if (busy || closed.get()) return
        runJob("下载更新", "正在获取 SHA-256 校验信息…") {
            val expected = UpdatePolicy.digest(asset.digest) ?: error("更新缺少 SHA-256")
            val temporary = File(directory, "${UUID.randomUUID()}.part")
            var finalFile: File? = null
            try {
                transfer(asset.url, UpdatePolicy.MAX_APK_BYTES, asset.size) { input ->
                    temporary.outputStream().use { output ->
                        val bytes = ByteArray(64 * 1024)
                        var received = 0L
                        var previousPercent = -1
                        while (true) {
                            ensureRunning()
                            val count = input.read(bytes)
                            if (count < 0) break
                            received += count
                            require(received <= asset.size && received <= UpdatePolicy.MAX_APK_BYTES) { "下载超过发布声明大小" }
                            output.write(bytes, 0, count)
                            val percent = (received * 100 / asset.size).toInt()
                            if (percent != previousPercent) {
                                previousPercent = percent
                                ui { dialog?.setMessage("下载 $percent% · ${received / 1024} / ${asset.size / 1024} KiB\n可按取消中止") }
                            }
                        }
                        require(received == asset.size) { "下载不完整，请重试" }
                        output.fd.sync()
                    }
                }
                ensureRunning()
                val candidate = Candidate(temporary, expected, release.versionName, release.versionCode)
                verify(candidate)
                val target = File(directory, "${UUID.randomUUID()}.apk")
                require(temporary.renameTo(target)) { "无法保存验证后的 APK" }
                finalFile = target
                ensureRunning()
                val previous = prefs.getString("file", null)
                require(prefs.edit().putString("file", target.name).putString("hash", expected).putString("tag", release.versionName).putLong("versionCode", release.versionCode).commit()) { "无法保存更新状态" }
                previous?.takeIf { it.matches(Regex("[a-f0-9-]+\\.apk")) }?.let { File(directory, it).delete() }
                finalFile = null
                complete { promptInstall(Candidate(target, expected, release.versionName, release.versionCode)) }
            } finally { temporary.delete(); finalFile?.delete() }
        }
    }

    private fun promptInstall(candidate: Candidate) {
        dialog = AlertDialog.Builder(activity).setTitle("更新已通过校验")
            .setMessage("${candidate.tag} 的包名、版本、SHA-256 和签名均已验证。\n\n继续将打开 Android 系统安装确认界面；不会自动安装。")
            .setNegativeButton("稍后", null).setPositiveButton("继续安装") { _, _ -> prepareInstall(candidate) }.show()
    }

    private fun prepareInstall(candidate: Candidate) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            dialog = AlertDialog.Builder(activity).setTitle("需要系统安装权限")
                .setMessage("请在下一页允许此应用安装未知来源应用。返回后会再次验证并请你确认安装；可随时取消。")
                .setNegativeButton("取消", null).setPositiveButton("打开设置") { _, _ ->
                    try {
                        prefs.edit().putBoolean("awaiting_permission", true).commit()
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    } catch (_: Exception) {
                        prefs.edit().putBoolean("awaiting_permission", false).apply()
                        show("系统不支持此安装设置", "此设备未提供未知来源安装设置。更新未安装，请使用设备设置或受信任的手动安装方式。")
                    }
                }.show()
            return
        }
        runJob("验证更新", "安装前再次验证文件…") {
            verify(candidate)
            ensureRunning()
            complete {
                try {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updater.files", candidate.file)
                    @Suppress("DEPRECATION")
                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false)
                    intent.clipData = ClipData.newRawUri("已验证更新", uri)
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    show("无法启动系统安装器", "设备没有可用的 APK 安装器，或设备策略禁止安装。更新尚未安装。")
                }
            }
        }
    }

    private fun loadCandidate(): Candidate? {
        val name = prefs.getString("file", null) ?: return null
        if (!name.matches(Regex("[a-f0-9-]+\\.apk"))) return null
        return Candidate(File(directory, name), prefs.getString("hash", "") ?: "", prefs.getString("tag", "") ?: "", prefs.getLong("versionCode", -1))
    }

    private fun verify(candidate: Candidate) {
        require(candidate.file.isFile && candidate.file.length() in 1..UpdatePolicy.MAX_APK_BYTES) { "更新缓存缺失或大小无效" }
        val digest = MessageDigest.getInstance("SHA-256")
        candidate.file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { ensureRunning(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        UpdatePolicy.requireHash(candidate.hash, hex(digest.digest()))
        val archive = activity.packageManager.getPackageArchiveInfo(candidate.file.path, signatureFlags()) ?: error("APK 损坏或无法解析签名")
        UpdatePolicy.requireUpgrade(identity(installed()), identity(archive))
        require(version(archive) == candidate.versionCode && archive.versionName == candidate.tag) { "APK 版本与更新清单不一致" }
        val minSdk = archive.applicationInfo?.minSdkVersion ?: error("APK 缺少应用信息")
        require(minSdk <= Build.VERSION.SDK_INT) { "此更新需要更高版本 Android" }
    }

    @Suppress("DEPRECATION")
    private fun installed() = activity.packageManager.getPackageInfo(activity.packageName, signatureFlags())
    @Suppress("DEPRECATION")
    private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): UpdatePolicy.Apk {
        // Require exact current signer identity: intentionally reject key rotation until explicitly supported.
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return UpdatePolicy.Apk(info.packageName, version(info), signers.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun readText(url: String, limit: Int): String = transfer(url, limit.toLong(), null) { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            ensureRunning()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "服务器响应过大" }
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }

    private fun <T> transfer(initial: String, limit: Long, expected: Long?, block: (java.io.InputStream) -> T): T {
        ensureRunning()
        val uri = UpdatePolicy.checkedUrl(initial)
        val conn = uri.toURL().openConnection() as HttpURLConnection
        connection = conn
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.setRequestProperty("User-Agent", "LanCast-updater")
        conn.setRequestProperty("Accept-Encoding", "identity")
        try {
            val status = conn.responseCode
            require(status == 200) { when (status) {
                in 300..399 -> "更新出现非预期重定向，已停止下载"
                404 -> "更新清单或安装包尚未发布"
                403, 429 -> "GitHub 限流或拒绝访问，请稍后重试"
                else -> "下载服务器返回 HTTP $status"
            } }
            val length = conn.contentLengthLong
            require(length <= limit && (expected == null || length < 0 || length == expected)) { "服务器返回的文件大小无效" }
            return conn.inputStream.use(block)
        } finally { conn.disconnect(); if (connection === conn) connection = null }
    }

    private fun runJob(title: String, message: String, job: () -> Unit) {
        if (busy || closed.get()) return
        busy = true
        cancelled = AtomicBoolean(false)
        dismiss()
        dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setNegativeButton("取消") { _, _ -> cancel() }.setOnCancelListener { cancel() }.show()
        executor.execute {
            try { job() } catch (e: Exception) {
                val wasCancelled = cancelled.get() || closed.get()
                ui { busy = false; dismiss(); if (!wasCancelled) show("更新未完成", e.message?.take(400) ?: "网络或文件错误，请稍后重试") }
            }
        }
    }
    private fun cancel() { cancelled.set(true); connection?.disconnect() }
    private fun ensureRunning() { if (closed.get() || cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("已取消") }
    private fun complete(action: () -> Unit) {
        val token = cancelled
        ui { busy = false; dismiss(); if (!token.get()) action() }
    }
    private fun ui(action: () -> Unit) { activity.runOnUiThread { if (!closed.get() && !activity.isFinishing && !activity.isDestroyed) action() } }
    private fun dismiss() { dialog?.dismiss(); dialog = null }
    private fun show(title: String, message: String) { dismiss(); dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("确定", null).show() }
}
