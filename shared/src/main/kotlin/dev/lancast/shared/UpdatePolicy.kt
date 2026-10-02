package dev.lancast.shared

import java.net.URI
import java.security.MessageDigest

/** All externally supplied release metadata is untrusted until validated here. */
object UpdatePolicy {
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    const val MAX_METADATA_BYTES = 1024 * 1024
    private val hash = Regex("[a-fA-F0-9]{64}")
    private val hosts = setOf("raw.githubusercontent.com")
    data class Asset(val name: String, val url: String, val size: Long, val digest: String?)
    data class Apk(val packageName: String, val versionCode: Long, val signerHashes: Set<String>)

    data class Manifest(val versionName: String, val versionCode: Long, val notes: String, val asset: Asset, val packageName: String)
    fun validateManifest(manifest: Manifest, prefix: String, installed: Apk) {
        require(prefix == "phone" || prefix == "tv")
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(manifest.versionName)) { "更新版本号无效" }
        require(manifest.versionCode > installed.versionCode) { "当前已经是最新版本，或服务器版本更旧" }
        require(manifest.packageName == installed.packageName) { "更新包名与当前应用不同" }
        require(manifest.asset.size in 1..MAX_APK_BYTES) { "APK 大小无效" }
        require(manifest.asset.digest != null) { "更新缺少 SHA-256" }
        digest(manifest.asset.digest)
        val uri = checkedUrl(manifest.asset.url)
        require(uri.host == "raw.githubusercontent.com" && uri.rawQuery == null && uri.port == -1 &&
            Regex("/amateurish-programmer/lan-cast/[a-f0-9]{40}/apks/v" + Regex.escape(manifest.versionName) + "/" + prefix + "\\.apk").matches(uri.rawPath)) { "APK 必须使用本仓库固定提交版本的 HTTPS 地址" }
    }
    fun checkedUrl(value: String): URI {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)) { "更新地址不是受信任的 HTTPS GitHub 地址" }
        require(uri.port == -1 && uri.rawQuery == null) { "更新地址不可包含端口或查询参数" }
        val manifestPath = "/amateurish-programmer/lan-cast/updates/manifest.json"
        val immutableApk = Regex("/amateurish-programmer/lan-cast/[a-f0-9]{40}/apks/v[0-9]+\\.[0-9]+\\.[0-9]+/(phone|tv)\\.apk")
        require(uri.rawPath == manifestPath || immutableApk.matches(uri.rawPath)) { "更新地址不属于本仓库的更新清单或固定版本 APK" }
        return uri
    }
    fun digest(value: String?): String? {
        if (value == null) return null
        require(value.startsWith("sha256:") && hash.matches(value.removePrefix("sha256:"))) { "发布中的 SHA-256 无效" }
        return value.removePrefix("sha256:").lowercase()
    }
    fun requireHash(expected: String, actual: String) {
        require(hash.matches(expected) && hash.matches(actual) && MessageDigest.isEqual(expected.lowercase().toByteArray(), actual.lowercase().toByteArray())) { "APK SHA-256 不匹配，已拒绝安装" }
    }
    fun requireUpgrade(installed: Apk, candidate: Apk) {
        require(installed.packageName == candidate.packageName) { "APK 包名与当前应用不同" }
        require(candidate.versionCode > installed.versionCode) { "已经是此版本或更新版本；禁止降级" }
        require(installed.signerHashes.isNotEmpty() && installed.signerHashes.all { hash.matches(it) } && installed.signerHashes == candidate.signerHashes) { "APK 签名与当前应用不同，已拒绝安装" }
    }
}
