package dev.lancast.updater

import dev.lancast.shared.UpdatePolicy
import org.json.JSONObject

internal object ReleaseParser {
    private fun positiveInteger(value: JSONObject, key: String): Long {
        val raw = value.get(key)
        require(raw is Int || raw is Long) { "$key 必须为整数" }
        return (raw as Number).toLong().also { require(it > 0) { "$key 必须为正数" } }
    }
    fun parse(text: String, prefix: String): UpdatePolicy.Manifest {
        require(text.toByteArray(Charsets.UTF_8).size <= UpdatePolicy.MAX_METADATA_BYTES)
        val root = JSONObject(text)
        require(positiveInteger(root, "schemaVersion") == 1L) { "不支持的更新清单版本" }
        require(prefix == "phone" || prefix == "tv")
        val a = root.getJSONObject("artifacts").getJSONObject(prefix)
        val hash = a.getString("sha256")
        require(Regex("[a-fA-F0-9]{64}").matches(hash)) { "更新 SHA-256 无效" }
        return UpdatePolicy.Manifest(root.getString("versionName"), positiveInteger(root, "versionCode"),
            root.getString("notes").take(32000), UpdatePolicy.Asset("$prefix.apk", a.getString("url"),
                positiveInteger(a, "size"), "sha256:$hash"), a.getString("packageName"))
    }
}
