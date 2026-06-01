package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small red dot indicating "no AI embedding for this song" — the song
 * does not participate in similarity-based recommendations. Mirrors the
 * Capacitor `.red-dot-inline` element rendered when `!s.hasEmbedding`.
 *
 * Caller decides where to place it (typically before the title text or
 * as an overlay on the art thumbnail).
 */
@Composable
fun NoEmbeddingDot(size: Dp = 6.dp) {
    Spacer(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFFE84545)),  // Capacitor --accent red
    )
}
