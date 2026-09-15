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
        assertEquals("🚀", MeshExpressions.animatedEmoji("🚀"))
        assertNull(MeshExpressions.animatedEmoji("hello ❤️"))
        assertNull(MeshExpressions.animatedEmoji(""))
    }
    @Test fun emojiCatalogIsLargeCategorizedAndDuplicateFree() {
        val emoji = MeshExpressions.emojiGroups.flatten()
        assertTrue(emoji.size >= 500)
        assertEquals(emoji.size, emoji.toSet().size)
        assertEquals(MeshExpressions.emojiGroups.size, MeshExpressions.emojiCategoryIcons.size)
    }
    @Test fun recentEmojiFiltersUnknownValuesDeduplicatesAndBounds() {
        val all = MeshExpressions.emojiGroups.flatten()
        val recent = MeshExpressions.recentEmoji(all.take(40) + listOf("unknown", all[0]), all[5])
        assertEquals(all[5], recent.first())
        assertEquals(32, recent.size)
        assertEquals(recent.size, recent.toSet().size)
        assertFalse(recent.contains("unknown"))
    }
    @Test fun stickerSendPreservesTypedDraftButTextSendConsumesIt() {
        assertFalse(MeshExpressions.shouldConsumeDraft(MeshExpressions.token("hello")))
        assertTrue(MeshExpressions.shouldConsumeDraft("hello"))
        assertTrue(MeshExpressions.shouldConsumeDraft("❤️"))
    }
}
