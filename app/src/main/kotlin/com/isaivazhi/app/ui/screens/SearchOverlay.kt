package com.isaivazhi.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.AlbumGroup
import com.isaivazhi.app.engine.FolderEntry
import com.isaivazhi.app.engine.OtherHit
import com.isaivazhi.app.engine.PlaylistsEngine
import com.isaivazhi.app.engine.Song
import com.isaivazhi.app.engine.searchLibrary
import com.isaivazhi.app.ui.highlightedText

/**
 * Full-screen overlay search across songs, albums, playlists, and folders.
 * Album taps navigate to the Albums tab (no playback). Song taps start playback.
 */
@Composable
fun SearchOverlay(
    songs: List<Song>,
    playlists: List<PlaylistsEngine.Playlist> = emptyList(),
    folders: List<FolderEntry> = emptyList(),
    onDismiss: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onOpenAlbum: (albumName: String) -> Unit,
    onOpenPlaylist: (playlistId: String) -> Unit,
    onOpenFolders: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val results by remember(songs, playlists, folders, query) {
        derivedStateOf { searchLibrary(query, songs, playlists, folders) }
    }

    val highlightColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search title, artist, album…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            if (query.isNotBlank() && results.isEmpty) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No matches.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (query.isNotBlank()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (results.songs.isNotEmpty()) {
                        item(key = "header_songs") {
                            SearchSectionHeader("Songs")
                        }
                        items(
                            count = results.songs.size,
                            key = { i -> "song_${results.songs[i].song.id}" },
                        ) { i ->
                            val hit = results.songs[i]
                            SearchSongRow(
                                song = hit.song,
                                query = query,
                                highlightColor = highlightColor,
                                onClick = {
                                    onPlaySong(hit.song)
                                    onDismiss()
                                },
                            )
                        }
                    }

                    if (results.albums.isNotEmpty()) {
                        item(key = "header_albums") {
                            SearchSectionHeader("Albums")
                        }
                        items(
                            count = results.albums.size,
                            key = { i -> "album_${results.albums[i].album.name}" },
                        ) { i ->
                            val hit = results.albums[i]
                            SearchAlbumRow(
                                album = hit.album,
                                query = query,
                                highlightColor = highlightColor,
                                onClick = {
                                    onOpenAlbum(hit.album.name)
                                    onDismiss()
                                },
                            )
                        }
                    }

                    if (results.others.isNotEmpty()) {
                        item(key = "header_others") {
                            SearchSectionHeader("Others")
                        }
                        items(
                            count = results.others.size,
                            key = { i ->
                                when (val hit = results.others[i]) {
                                    is OtherHit.ArtistSong -> "artist_${hit.song.id}"
                                    is OtherHit.Playlist -> "playlist_${hit.playlist.id}"
                                    is OtherHit.Folder -> "folder_${hit.folder.path}"
                                }
                            },
                        ) { i ->
                            when (val hit = results.others[i]) {
                                is OtherHit.ArtistSong -> SearchArtistSongRow(
                                    song = hit.song,
                                    query = query,
                                    highlightColor = highlightColor,
                                    onClick = {
                                        onPlaySong(hit.song)
                                        onDismiss()
                                    },
                                )
                                is OtherHit.Playlist -> SearchPlaylistRow(
                                    playlist = hit.playlist,
                                    query = query,
                                    highlightColor = highlightColor,
                                    onClick = {
                                        onOpenPlaylist(hit.playlist.id)
                                        onDismiss()
                                    },
                                )
                                is OtherHit.Folder -> SearchFolderRow(
                                    folder = hit.folder,
                                    query = query,
                                    highlightColor = highlightColor,
                                    onClick = {
                                        onOpenFolders()
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SearchSongRow(
    song: Song,
    query: String,
    highlightColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val title = song.title.ifBlank { song.filename }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtThumbnail(filePath = song.filePath, size = 44.dp, cornerRadius = 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = highlightedText(title, query, highlightColor),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOf(song.artist, song.album).filter { it.isNotBlank() }
                    .joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchAlbumRow(
    album: AlbumGroup,
    query: String,
    highlightColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtThumbnail(filePath = album.firstArtPath, size = 56.dp, cornerRadius = 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = highlightedText(album.name, query, highlightColor),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${album.artist} • ${album.tracks.size} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchArtistSongRow(
    song: Song,
    query: String,
    highlightColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val title = song.title.ifBlank { song.filename }
    val artist = song.artist.ifBlank { "Unknown artist" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtThumbnail(filePath = song.filePath, size = 44.dp, cornerRadius = 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = highlightedText(artist, query, highlightColor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchPlaylistRow(
    playlist: PlaylistsEngine.Playlist,
    query: String,
    highlightColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = highlightedText(playlist.name, query, highlightColor),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Playlist • ${playlist.songFilenames.size} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchFolderRow(
    folder: FolderEntry,
    query: String,
    highlightColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = highlightedText(folder.displayName, query, highlightColor),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Folder • ${folder.songCount} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
