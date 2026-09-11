package com.meshchat.app

import com.meshchat.app.ui.MeshExpressions
import org.junit.Assert.*
import org.junit.Test

class MeshExpressionsTest {
    @Test fun thirdPartyPackHasDistinctStableIdsAndPreservesDraft() {
        assertEquals(12, MeshExpressions.fluentStickers.size)
        assertEquals(MeshExpressions.stickers.size, MeshExpressions.stickers.toSet().size)
        MeshExpressions.fluentStickers.forEach {
            assertEquals(it, MeshExpressions.stickerId(MeshExpressions.token(it)))
            assertFalse(MeshExpressions.shouldConsumeDraft(MeshExpressions.token(it)))
        }
    }
    @Test fun everyBrandedStickerRoundTrips() {
        MeshExpressions.stickers.forEach { assertEquals(it, MeshExpressions.stickerId(MeshExpressions.token(it))) }
    }
    @Test fun oldMessagesRemainRecognized() {
        assertEquals("wave", MeshExpressions.stickerId("[[sticker:wave]]"))
        assertEquals("hello", MeshExpressions.artworkId("wave"))
    }
    @Test fun mixedAndUnknownTokensArePlainText() {
        assertNull(MeshExpressions.stickerId("hello [[sticker:love]]"))
        assertNull(MeshExpressions.stickerId("[[sticker:unknown]]"))
        assertNull(MeshExpressions.stickerId("[[sticker:../love]]"))
    }
    @Test fun onlyStandaloneEmojiBecomeLargeAnimatedArt() {
        assertEquals("❤️", MeshExpressions.animatedEmoji("❤️"))
        assertNull(MeshExpressions.animatedEmoji("hello ❤️"))
        assertNull(MeshExpressions.animatedEmoji(""))
    }
    @Test fun stickerSendPreservesTypedDraftButTextSendConsumesIt() {
        assertFalse(MeshExpressions.shouldConsumeDraft(MeshExpressions.token("hello")))
        assertTrue(MeshExpressions.shouldConsumeDraft("hello"))
        assertTrue(MeshExpressions.shouldConsumeDraft("❤️"))
    }
}
