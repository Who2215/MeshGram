package com.meshchat.app.stickers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerPackIndexTest {
    @Test
    fun indexAllowsOnlySmallUniqueHttpsManifestLists() {
        val valid = StickerPackIndex(manifests = listOf(
            "https://who2215.github.io/MeshGram/stickers/community.fun/1/manifest.json"
        ))
        assertTrue(StickerPackIndexVerifier.validate(valid))
        assertFalse(StickerPackIndexVerifier.validate(valid.copy(manifests = valid.manifests + valid.manifests)))
        assertFalse(StickerPackIndexVerifier.validate(valid.copy(
            manifests = listOf("http://example.test/pack.json")
        )))
        assertFalse(StickerPackIndexVerifier.validate(valid.copy(
            manifests = listOf("https://user:password@example.test/pack.json")
        )))
    }

    @Test
    fun parserRejectsUnknownFieldsAndOversizedDocuments() {
        assertNotNull(StickerPackIndexVerifier.parse("""{"schemaVersion":1,"manifests":[]}"""))
        assertNull(StickerPackIndexVerifier.parse("""{"schemaVersion":1,"manifests":[],"extra":true}"""))
        assertNull(StickerPackIndexVerifier.parse("x".repeat(StickerPackIndexVerifier.MAX_INDEX_BYTES + 1)))
    }
}
