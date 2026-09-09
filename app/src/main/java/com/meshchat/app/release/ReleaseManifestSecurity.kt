package com.meshchat.app.release

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.net.URI

/**
 * Verification primitives for an explicit, user-approved update flow.
 * The app must never install an APK based only on a remote filename or URL.
 */
@Serializable
data class MeshReleaseManifest(
    val schemaVersion: Int = 1,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val changelog: List<String> = emptyList(),
    val apkUrl: String,
    val apkSha256: String,
    val signingCertificateSha256: String,
    val manifestSignature: String = ""
) {
    fun canonicalPayload(): String {
        return listOf(
            schemaVersion.toString(),
            packageName,
            versionCode.toString(),
            versionName,
            changelog.joinToString("\n"),
            apkUrl,
            apkSha256.lowercase(),
            signingCertificateSha256.lowercase()
        ).joinToString("|")
    }
}

object MeshReleaseVerifier {
    fun installedCertificateSha256(context: Context): String? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val signer = signers?.singleOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).toHex()
    }.getOrNull()

    fun isCompatibleSigner(manifest: MeshReleaseManifest, installedCertificate: String?): Boolean =
        !installedCertificate.isNullOrBlank() &&
            manifest.signingCertificateSha256.equals(installedCertificate, ignoreCase = true)

    fun verifyApkVersion(context: Context, apk: File, expectedVersion: Int): Boolean = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return false
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        version == expectedVersion.toLong()
    }.getOrDefault(false)

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    fun parseManifest(rawJson: String): MeshReleaseManifest? {
        return runCatching {
            json.decodeFromString<MeshReleaseManifest>(rawJson)
        }.getOrNull()
    }

    fun validateManifest(
        manifest: MeshReleaseManifest,
        expectedPackageName: String
    ): Boolean {
        val uri = runCatching { URI(manifest.apkUrl) }.getOrNull() ?: return false
        return manifest.schemaVersion == 1 &&
            manifest.packageName == expectedPackageName &&
            manifest.versionCode > 0 &&
            manifest.versionName.isNotBlank() &&
            uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            !uri.host.isNullOrBlank() &&
            manifest.apkSha256.matches(sha256Pattern) &&
            manifest.signingCertificateSha256.matches(sha256Pattern)
    }

    fun verifyManifestSignature(
        manifest: MeshReleaseManifest,
        trustedPublicKeyBase64: String
    ): Boolean {
        if (manifest.manifestSignature.isBlank() || trustedPublicKeyBase64.isBlank()) return false
        return runCatching {
            val keyBytes = Base64.decode(trustedPublicKeyBase64, Base64.NO_WRAP)
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(manifest.canonicalPayload().toByteArray(Charsets.UTF_8))
            verifier.verify(Base64.decode(manifest.manifestSignature, Base64.NO_WRAP))
        }.getOrDefault(false)
    }

    fun verifyApkSha256(apk: File, expectedSha256: String): Boolean {
        if (!apk.isFile || !expectedSha256.matches(sha256Pattern)) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(apk.inputStream()).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().toHex() == expectedSha256.lowercase()
        }.getOrDefault(false)
    }

    fun verifyApkPackageName(
        context: Context,
        apk: File,
        expectedPackageName: String
    ): Boolean {
        if (!apk.isFile || expectedPackageName.isBlank()) return false
        return runCatching {
            val packageInfo = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            packageInfo?.packageName == expectedPackageName
        }.getOrDefault(false)
    }

    fun verifyApkSigningCertificate(
        context: Context,
        apk: File,
        expectedCertificateSha256: String
    ): Boolean {
        if (!apk.isFile || !expectedCertificateSha256.matches(sha256Pattern)) return false
        return runCatching {
            val packageManager = context.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageArchiveInfo(
                    apk.path,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
            } ?: return false

            val certificateBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures?.firstOrNull()?.toByteArray()
            } ?: return false

            MessageDigest.getInstance("SHA-256").digest(certificateBytes)
                .toHex() == expectedCertificateSha256.lowercase()
        }.getOrDefault(false)
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { byte -> "%02x".format(byte) }
    }
}
