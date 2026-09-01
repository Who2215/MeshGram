package com.meshchat.app.release

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseManifestSecurityTest {
    @Test
    fun manifestRequiresHttpsAndMatchingPackage() {
        val valid = MeshReleaseManifest(
            packageName = "com.meshchat.app",
            versionCode = 2,
            versionName = "0.2.0",
            changelog = listOf("Safer update verification"),
            apkUrl = "https://updates.example.test/meshgram.apk",
            apkSha256 = "a".repeat(64),
            signingCertificateSha256 = "b".repeat(64)
        )

        assertTrue(MeshReleaseVerifier.validateManifest(valid, "com.meshchat.app"))
        assertFalse(MeshReleaseVerifier.validateManifest(valid.copy(apkUrl = "http://updates.example.test/app.apk"), "com.meshchat.app"))
        assertFalse(MeshReleaseVerifier.validateManifest(valid, "com.other.app"))
    }

    @Test
    fun apkHashIsCheckedBeforeInstall() {
        val file = Files.createTempFile("meshgram", ".apk").toFile()
        try {
            file.writeText("test-payload")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }

            assertTrue(MeshReleaseVerifier.verifyApkSha256(file, digest))
            assertFalse(MeshReleaseVerifier.verifyApkSha256(file, "0".repeat(64)))
        } finally {
            file.delete()
        }
    }
}
