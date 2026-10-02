package dev.lancast.updater

import org.junit.Assert.*
import org.junit.Test

class ReleaseParserTest {
    private val hash = "a".repeat(64)
    private fun json(code: String = "2", schema: String = "1", size: String = "123") = """{"schemaVersion":$schema,"versionName":"0.2.0","versionCode":$code,"notes":"Updates","artifacts":{"phone":{"packageName":"dev.lancast.phone","url":"https://example.com/phone.apk","sha256":"$hash","size":$size}}}"""
    private fun rejects(text: String, prefix: String = "phone") { try { ReleaseParser.parse(text, prefix); fail("Should reject") } catch (_: Exception) {} }
    @Test fun parsesExpectedSchema() { val release = ReleaseParser.parse(json(), "phone"); assertEquals(2L, release.versionCode); assertEquals("sha256:$hash", release.asset.digest) }
    @Test fun rejectsMalformedJson() { rejects("{") }
    @Test fun rejectsMissingFields() { rejects("{}") }
    @Test fun rejectsUnsupportedSchema() { rejects(json(schema = "2")) }
    @Test fun rejectsFractionalSchema() { rejects(json(schema = "1.1")) }
    @Test fun rejectsFractionalVersionCode() { rejects(json(code = "2.9")) }
    @Test fun rejectsTextVersionCode() { rejects(json(code = "\"2\"")) }
    @Test fun rejectsNegativeVersionCode() { rejects(json(code = "-1")) }
    @Test fun rejectsZeroVersionCode() { rejects(json(code = "0")) }
    @Test fun rejectsOverflowVersionCode() { rejects(json(code = "999999999999999999999999999")) }
    @Test fun rejectsFractionalSize() { rejects(json(size = "1.1")) }
    @Test fun rejectsMissingArtifact() { rejects(json(), "tv") }
    @Test fun rejectsBadPrefix() { rejects(json(), "other") }
    @Test fun rejectsMalformedHash() { rejects(json().replace(hash, "abcd")) }
    @Test fun rejectsNullHash() { rejects(json().replace("\"$hash\"", "null")) }
    @Test fun rejectsOversizedMetadata() { rejects(" ".repeat(1024 * 1024 + 1)) }
}
