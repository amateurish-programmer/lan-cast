package dev.lancast.shared

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val hash = "a".repeat(64)
    private val installed = UpdatePolicy.Apk("dev.lancast.phone", 1, setOf(hash))
    private val url = "https://raw.githubusercontent.com/amateurish-programmer/lan-cast/${"b".repeat(40)}/apks/v0.2.0/phone.apk"
    private val good = UpdatePolicy.Manifest("0.2.0", 2, "Notes", UpdatePolicy.Asset("phone.apk", url, 123, "sha256:$hash"), installed.packageName)
    private fun reject(block: () -> Unit) { try { block(); fail("Should reject") } catch (_: IllegalArgumentException) {} }
    @Test fun acceptsImmutableManifest() { UpdatePolicy.validateManifest(good, "phone", installed) }
    @Test fun acceptsTvManifest() { UpdatePolicy.validateManifest(good.copy(packageName = "dev.lancast.tv", asset = good.asset.copy(url = url.replace("phone.apk", "tv.apk"))), "tv", installed.copy(packageName = "dev.lancast.tv")) }
    @Test fun rejectsHttp() { reject { UpdatePolicy.validateManifest(good.copy(asset = good.asset.copy(url = url.replace("https:", "http:"))), "phone", installed) } }
    @Test fun rejectsHostSpoofing() { for (host in listOf("raw.githubusercontent.com.evil.com", "evil.com", "localhost", "127.0.0.1", "github.com")) reject { UpdatePolicy.validateManifest(good.copy(asset = good.asset.copy(url = url.replace("raw.githubusercontent.com", host))), "phone", installed) } }
    @Test fun rejectsUserInfo() { reject { UpdatePolicy.validateManifest(good.copy(asset = good.asset.copy(url = url.replace("https://", "https://user@"))), "phone", installed) } }
    @Test fun rejectsMutableBranch() { reject { UpdatePolicy.validateManifest(good.copy(asset = good.asset.copy(url = url.replace("b".repeat(40), "updates"))), "phone", installed) } }
    @Test fun rejectsWrongRepo() { reject { UpdatePolicy.validateManifest(good.copy(asset = good.asset.copy(url = url.replace("lan-cast/", "other/"))), "phone", installed) } }
    @Test fun rejectsWrongArtifact() { reject { UpdatePolicy.validateManifest(good.copy(asset = good.asset.copy(url = url.replace("phone.apk", "tv.apk"))), "phone", installed) } }
    @Test fun rejectsPathEscapes() { for (path in listOf("../phone.apk", "%70hone.apk", "phone.apk/..", "phone.apk?token=abc", "phone.apk#fragment")) reject { UpdatePolicy.validateManifest(good.copy(asset = good.asset.copy(url = url.replace("phone.apk", path))), "phone", installed) } }
    @Test fun rejectsExplicitPort() { reject { UpdatePolicy.validateManifest(good.copy(asset = good.asset.copy(url = url.replace(".com/", ".com:443/"))), "phone", installed) } }
    @Test fun rejectsOversizeAndEmpty() { for (size in listOf(-1L, 0L, UpdatePolicy.MAX_APK_BYTES + 1)) reject { UpdatePolicy.validateManifest(good.copy(asset = good.asset.copy(size = size)), "phone", installed) } }
    @Test fun rejectsMissingHash() { reject { UpdatePolicy.validateManifest(good.copy(asset = good.asset.copy(digest = null)), "phone", installed) } }
    @Test fun rejectsMalformedHashes() { for (value in listOf("", "sha256:bad", "md5:$hash", "sha256:${"g".repeat(64)}", "sha256:$hash ")) reject { UpdatePolicy.digest(value) } }
    @Test fun rejectsVersionDowngrade() { reject { UpdatePolicy.validateManifest(good.copy(versionCode = 0), "phone", installed) } }
    @Test fun rejectsSameVersion() { reject { UpdatePolicy.validateManifest(good.copy(versionCode = 1), "phone", installed) } }
    @Test fun rejectsMalformedVersionName() { reject { UpdatePolicy.validateManifest(good.copy(versionName = "../../evil"), "phone", installed) } }
    @Test fun rejectsManifestPackageMismatch() { reject { UpdatePolicy.validateManifest(good.copy(packageName = "evil.app"), "phone", installed) } }
    @Test fun acceptsMatchingHash() { UpdatePolicy.requireHash(hash, hash.uppercase()) }
    @Test fun rejectsTamperedHash() { reject { UpdatePolicy.requireHash(hash, "b".repeat(64)) } }
    @Test fun acceptsSignedUpgrade() { UpdatePolicy.requireUpgrade(installed, installed.copy(versionCode = 2)) }
    @Test fun rejectsApkPackageMismatch() { reject { UpdatePolicy.requireUpgrade(installed, installed.copy(versionCode = 2, packageName = "other")) } }
    @Test fun rejectsApkDowngradeAndSame() { for (code in listOf(0L, 1L)) reject { UpdatePolicy.requireUpgrade(installed, installed.copy(versionCode = code)) } }
    @Test fun rejectsSignatureMismatch() { reject { UpdatePolicy.requireUpgrade(installed, installed.copy(versionCode = 2, signerHashes = setOf("b".repeat(64)))) } }
    @Test fun rejectsPartialSignerMatch() { reject { UpdatePolicy.requireUpgrade(installed, installed.copy(versionCode = 2, signerHashes = setOf(hash, "b".repeat(64)))) } }
    @Test fun rejectsMissingSignatures() { reject { UpdatePolicy.requireUpgrade(installed.copy(signerHashes = emptySet()), installed.copy(versionCode = 2, signerHashes = emptySet())) } }
}
