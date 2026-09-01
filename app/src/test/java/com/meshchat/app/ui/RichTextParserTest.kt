package com.meshchat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextParserTest {
    @Test
    fun parsesSupportedInlineStyles() {
        val segments = parseRichText("**bold** _italic_ __under__ ~~strike~~ `code`")

        assertTrue(segments.any { it.text == "bold" && RichTextStyle.BOLD in it.styles })
        assertTrue(segments.any { it.text == "italic" && RichTextStyle.ITALIC in it.styles })
        assertTrue(segments.any { it.text == "under" && RichTextStyle.UNDERLINE in it.styles })
        assertTrue(segments.any { it.text == "strike" && RichTextStyle.STRIKETHROUGH in it.styles })
        assertTrue(segments.any { it.text == "code" && RichTextStyle.MONOSPACE in it.styles })
    }

    @Test
    fun parsesPlainAndLabeledLinks() {
        val segments = parseRichText("Go to https://meshgram.example or [docs](https://docs.example/path)")

        assertTrue(segments.any { it.text == "https://meshgram.example" && it.url == it.text })
        assertTrue(segments.any { it.text == "docs" && it.url == "https://docs.example/path" })
    }

    @Test
    fun keepsUnclosedMarkersAsPlainText() {
        val source = "Broken **format stays"
        val restored = parseRichText(source).joinToString("") { it.text }

        assertEquals(source, restored)
    }
}
