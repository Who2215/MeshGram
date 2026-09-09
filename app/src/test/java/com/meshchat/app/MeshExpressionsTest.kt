package com.meshchat.app

import com.meshchat.app.ui.MeshExpressions
import org.junit.Assert.*
import org.junit.Test

class MeshExpressionsTest {
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
