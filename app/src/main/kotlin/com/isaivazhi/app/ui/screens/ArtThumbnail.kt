package com.isaivazhi.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.AlbumArtRepository

/**
 * Square album-art thumbnail. Loads asynchronously from the audio file via
 * AlbumArtRepository (which uses MediaMetadataRetriever + an on-disk JPG
 * cache). Renders a music-note placeholder while loading or if the file
 * has no embedded picture.
 *
 * Keyed on filePath so LazyColumn recycling triggers a fresh load when a
 * row is reused for a different song.
 */
@Composable
fun ArtThumbnail(
    filePath: String?,
    size: Dp = 48.dp,
    cornerRadius: Dp = 6.dp,
    sampleSize: Int = 4,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    // Push #39: seed the initial state from the in-memory LRU on
    // composition. When the LazyColumn has already loaded this art for
    // a list row, the now-playing MiniPlayer ArtThumbnail (and other
    // re-renders with the same filePath + sampleSize) renders the cached
    // bitmap on the first frame — no null placeholder, no async hop.
    var bitmap by remember(filePath, sampleSize) {
        mutableStateOf(AlbumArtRepository.getCachedBitmap(filePath, sampleSize))
    }

    LaunchedEffect(filePath, sampleSize) {
        if (filePath.isNullOrEmpty()) return@LaunchedEffect
        // Already populated from the LRU? Skip the async load.
        if (bitmap != null) return@LaunchedEffect
        bitmap = AlbumArtRepository.load(ctx, filePath, sampleSize)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}
