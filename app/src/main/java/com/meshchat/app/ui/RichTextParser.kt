package com.meshchat.app.ui

enum class RichTextStyle {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    MONOSPACE
}

data class RichTextSegment(
    val text: String,
    val styles: Set<RichTextStyle> = emptySet(),
    val url: String? = null
)

fun parseRichText(source: String): List<RichTextSegment> {
    if (source.isEmpty()) return emptyList()
    val segments = mutableListOf<RichTextSegment>()
    var cursor = 0

    fun append(text: String, styles: Set<RichTextStyle> = emptySet(), url: String? = null) {
        if (text.isEmpty()) return
        val last = segments.lastOrNull()
        if (last != null && last.styles == styles && last.url == url) {
            segments[segments.lastIndex] = last.copy(text = last.text + text)
        } else {
            segments += RichTextSegment(text = text, styles = styles, url = url)
        }
    }

    while (cursor < source.length) {
        val markdownLink = parseMarkdownLink(source, cursor)
        if (markdownLink != null) {
            append(markdownLink.label, url = markdownLink.url)
            cursor = markdownLink.endExclusive
            continue
        }

        val marker = RICH_TEXT_MARKERS.firstOrNull { candidate ->
            source.startsWith(candidate.token, cursor)
        }
        if (marker != null) {
            val contentStart = cursor + marker.token.length
            val contentEnd = source.indexOf(marker.token, contentStart)
            if (contentEnd > contentStart) {
                append(
                    text = source.substring(contentStart, contentEnd),
                    styles = setOf(marker.style)
                )
                cursor = contentEnd + marker.token.length
                continue
            }
        }

        val urlMatch = URL_REGEX.find(source, cursor)?.takeIf { match ->
            match.range.first == cursor
        }
        if (urlMatch != null) {
            val normalizedUrl = urlMatch.value.trimEnd('.', ',', ';', ':', '!', '?')
            append(normalizedUrl, url = normalizedUrl)
            cursor += normalizedUrl.length
            continue
        }

        append(source[cursor].toString())
        cursor++
    }

    return segments
}

private fun parseMarkdownLink(source: String, start: Int): ParsedMarkdownLink? {
    if (source.getOrNull(start) != '[') return null
    val labelEnd = source.indexOf(']', start + 1)
    if (labelEnd <= start + 1 || source.getOrNull(labelEnd + 1) != '(') return null
    val urlEnd = source.indexOf(')', labelEnd + 2)
    if (urlEnd <= labelEnd + 2) return null
    val url = source.substring(labelEnd + 2, urlEnd)
    if (!url.startsWith("https://", ignoreCase = true) &&
        !url.startsWith("http://", ignoreCase = true)
    ) {
        return null
    }
    return ParsedMarkdownLink(
        label = source.substring(start + 1, labelEnd),
        url = url,
        endExclusive = urlEnd + 1
    )
}

private data class ParsedMarkdownLink(
    val label: String,
    val url: String,
    val endExclusive: Int
)

private data class RichTextMarker(
    val token: String,
    val style: RichTextStyle
)

private val RICH_TEXT_MARKERS = listOf(
    RichTextMarker("__", RichTextStyle.UNDERLINE),
    RichTextMarker("**", RichTextStyle.BOLD),
    RichTextMarker("~~", RichTextStyle.STRIKETHROUGH),
    RichTextMarker("`", RichTextStyle.MONOSPACE),
    RichTextMarker("_", RichTextStyle.ITALIC)
)

private val URL_REGEX = Regex("https?://[^\\s<>()]+", RegexOption.IGNORE_CASE)
