package com.meshchat.app.stickers

import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerPackSecurityTest {
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
    private val lottie = """{"v":"5.7.4","fr":30,"ip":0,"op":60,"w":128,"h":128,"layers":[],"assets":[]}"""
        .toByteArray()

    @Test
    fun manifestRejectsUnsafeIdsUrlsDuplicatesAndOversizedAssets() {
        val valid = manifest()
        assertTrue(StickerPackVerifier.validate(valid))
        assertFalse(StickerPackVerifier.validate(valid.copy(packId = "../bad")))
        assertFalse(StickerPackVerifier.validate(valid.copy(licenseUrl = "http://example.test/license")))
        assertFalse(StickerPackVerifier.validate(valid.copy(stickers = valid.stickers + valid.stickers.first())))
        assertFalse(StickerPackVerifier.validate(valid.copy(
            stickers = listOf(valid.stickers.first().copy(assetBytes = StickerPackVerifier.MAX_LOTTIE_BYTES + 1))
        )))
        assertFalse(StickerPackVerifier.validate(valid, setOf(valid.stickers.first().id)))
    }

    @Test
    fun signatureBindsEveryManifestField() {
        val keys = keyPair()
        val signed = sign(manifest(), keys)
        val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
        assertTrue(StickerPackVerifier.verifySignature(signed, publicKey))
        assertFalse(StickerPackVerifier.verifySignature(signed.copy(title = "Changed"), publicKey))
        assertFalse(StickerPackVerifier.verifySignature(signed, Base64.getEncoder().encodeToString(keyPair().public.encoded)))
    }

    @Test
    fun canonicalPayloadMatchesThePublicationTool() {
        val sample = manifest().copy(stickers = listOf(manifest().stickers.single().copy(
            assetSha256 = "a".repeat(64),
            previewSha256 = "b".repeat(64),
            assetBytes = 100,
            previewBytes = 80
        )))
        assertEquals(
            "af7ba4104cbb8c50feaaacb305621bd8d010b1a10b745779057100668137a928",
            sha256(sample.canonicalPayload().toByteArray())
        )
    }

    @Test
    fun installerVerifiesContentAndCommitsAtomically() {
        val root = Files.createTempDirectory("meshgram-packs").toFile()
        val stage = Files.createTempDirectory("meshgram-pack-stage").toFile()
        try {
            val asset = stage.resolve("animation.json").apply { writeBytes(lottie) }
            val preview = stage.resolve("preview.png").apply { writeBytes(png) }
            val keys = keyPair()
            val signed = sign(manifest(), keys)
            val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
            val installer = StickerPackInstaller(root)
            val installed = installer.install(
                signed,
                mapOf(signed.stickers.single().id to StagedStickerFiles(asset, preview)),
                publicKey
            )

            assertNotNull(installed)
            assertTrue(installed!!.directory.resolve("laugh.json").isFile)
            assertTrue(installed.directory.resolve("laugh.preview.png").isFile)
            assertEquals("1", root.resolve("community.fun.current").readText())
            assertEquals(1, StickerPackStore(root).load(publicKey).size)

            installed.directory.resolve("laugh.json").appendText("damaged")
            assertFalse(installer.verifyInstalled(installed.directory, signed))
            assertTrue(StickerPackStore(root).load(publicKey).isEmpty())
            val repaired = installer.install(
                signed,
                mapOf(signed.stickers.single().id to StagedStickerFiles(asset, preview)),
                publicKey
            )
            assertNotNull(repaired)
            assertTrue(installer.verifyInstalled(repaired!!.directory, signed))
            assertEquals(1, StickerPackStore(root).load(publicKey).size)

            val tamperedManifest = sign(manifest(version = 2), keys)
            asset.appendText("tampered")
            assertNull(StickerPackInstaller(root).install(
                tamperedManifest,
                mapOf(tamperedManifest.stickers.single().id to StagedStickerFiles(asset, preview)),
                publicKey
            ))
            assertFalse(root.resolve("community.fun/2").exists())
        } finally {
            root.deleteRecursively()
            stage.deleteRecursively()
        }
    }

    private fun manifest(version: Int = 1): StickerPackManifest {
        val item = StickerPackItem(
            id = "community.fun/laugh",
            label = "Laugh",
            category = "reactions",
            kind = StickerPackAssetKind.LOTTIE,
            assetUrl = "https://example.test/stickers/laugh.json",
            previewUrl = "https://example.test/stickers/laugh.png",
            assetSha256 = sha256(lottie),
            previewSha256 = sha256(png),
            assetBytes = lottie.size.toLong(),
            previewBytes = png.size.toLong(),
            width = 128,
            height = 128,
            durationMs = 2_000,
            frameRate = 30
        )
        return StickerPackManifest(
            packId = "community.fun",
            version = version,
            title = "Community Fun",
            attribution = "MeshGram test pack",
            licenseUrl = "https://example.test/license",
            stickers = listOf(item)
        )
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun sign(manifest: StickerPackManifest, keys: KeyPair): StickerPackManifest {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keys.private)
        signer.update(manifest.canonicalPayload().toByteArray(Charsets.UTF_8))
        return manifest.copy(manifestSignature = Base64.getEncoder().encodeToString(signer.sign()))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
