package com.isaivazhi.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Returns [text] with every case-insensitive occurrence of [query] styled bold
 * in [highlightColor]. When [query] is blank or there is no match, returns plain text.
 */
fun highlightedText(
    text: String,
    query: String,
    highlightColor: Color,
): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)

    val lowerText = text.lowercase()
    val lowerQuery = q.lowercase()

    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val idx = lowerText.indexOf(lowerQuery, i)
            if (idx < 0) {
                append(text.substring(i))
                break
            }
            if (idx > i) append(text.substring(i, idx))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor)) {
                append(text.substring(idx, idx + lowerQuery.length))
            }
            i = idx + lowerQuery.length
        }
    }
}
