package com.meshchat.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

@Composable
fun RichMessageText(
    text: String,
    textColor: Color,
    linkColor: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    val annotatedText = remember(text, textColor, linkColor, codeBackground) {
        buildRichAnnotatedString(
            text = text,
            textColor = textColor,
            linkColor = linkColor,
            codeBackground = codeBackground
        )
    }

    ClickableText(
        text = annotatedText,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        onClick = { offset ->
            annotatedText
                .getStringAnnotations(URL_ANNOTATION, offset, offset)
                .firstOrNull()
                ?.let { annotation -> pendingUrl = annotation.item }
        }
    )

    val url = pendingUrl
    if (url != null) {
        AlertDialog(
            onDismissRequest = { pendingUrl = null },
            title = { Text("Open link?") },
            text = { Text(url) },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        pendingUrl = null
                    }
                ) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUrl = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun buildRichAnnotatedString(
    text: String,
    textColor: Color,
    linkColor: Color,
    codeBackground: Color
): AnnotatedString = buildAnnotatedString {
    parseRichText(text).forEach { segment ->
        val decorations = buildList {
            if (RichTextStyle.UNDERLINE in segment.styles || segment.url != null) {
                add(TextDecoration.Underline)
            }
            if (RichTextStyle.STRIKETHROUGH in segment.styles) {
                add(TextDecoration.LineThrough)
            }
        }
        val spanStyle = SpanStyle(
            color = if (segment.url != null) linkColor else textColor,
            fontWeight = if (RichTextStyle.BOLD in segment.styles) FontWeight.Bold else null,
            fontStyle = if (RichTextStyle.ITALIC in segment.styles) FontStyle.Italic else null,
            fontFamily = if (RichTextStyle.MONOSPACE in segment.styles) FontFamily.Monospace else null,
            background = if (RichTextStyle.MONOSPACE in segment.styles) codeBackground else Color.Unspecified,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations)
        )
        pushStyle(spanStyle)
        if (segment.url != null) pushStringAnnotation(URL_ANNOTATION, segment.url)
        append(segment.text)
        if (segment.url != null) pop()
        pop()
    }
}

private const val URL_ANNOTATION = "meshgram_url"
