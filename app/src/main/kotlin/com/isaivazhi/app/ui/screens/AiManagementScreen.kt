package com.isaivazhi.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.EmbeddingDbFacade
import com.isaivazhi.app.engine.EmbeddingEngine
import com.isaivazhi.app.engine.EmbeddingSplitCount
import com.isaivazhi.app.engine.SignalTimelineEngine
import com.isaivazhi.app.engine.Song
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AiManagementScreen(
    songs: List<Song>,
    embeddingsRowCount: Int,
    embeddingsDim: Int,
    vecExtensionLoaded: Boolean,
    status: EmbeddingEngine.EmbeddingStatus,
    embeddingSplitCount: Int = 3,
    libraryEmbedSplitCount: Int? = null,
    onEmbeddingSplitCountChange: (Int) -> Unit = {},
    embeddedFilenames: Set<String>,
    // Push #53: filepath-based "is this song embedded" lookup. The
    // previous filename-based comparison failed for songs whose
    // MediaStore DISPLAY_NAME differed from `File(path).getName()` (the
    // value stored in the embedding row). Pending now uses filepath
    // which is unambiguous. embeddedFilenames is kept for back-compat /
    // legacy callers but should be considered secondary.
    embeddedFilepaths: Set<String> = emptySet(),
    onBack: () -> Unit,
    onEmbedPending: () -> Unit,
    onEmbedOne: (Song) -> Unit,
    onReembedAll: () -> Unit,
    onRescanLibrary: () -> Unit,
    onStop: () -> Unit,
    // Push #49: filenames present in the embeddings DB but missing from
    // the current MediaStore library (file deleted / renamed / imported
    // from another install). Surfaced under the "Stale" stat — tapping
    // the stat expands the list with a "Remove N stale embeddings" CTA.
    staleFilenames: List<String> = emptyList(),
    onRemoveStale: (List<String>) -> Unit = {},
    // Push #51: duplicate-filepath rows from the embeddings table. Multiple
    // rows can share a filepath because the table's PRIMARY KEY is
    // content_hash, not filepath — re-embedding the same file with a
    // different decode pipeline produces a new hash, leaving the old row.
    // Rows with empty filepath are excluded by the SQL (they surface as
    // Stale instead). Display metadata comes from songsByFilepath (real
    // MediaStore title/artist), not from the embedding row.
    duplicateRows: List<EmbeddingDbFacade.DuplicateRow> = emptyList(),
    // Push #51: real song metadata lookup. Embedding rows often have
    // blank artist/album fields (legacy imports, embed-time metadata
    // gaps), so we display from the live MediaStore Song when possible.
    // Null lookup → file is no longer on the device.
    songsByFilepath: Map<String, Song> = emptyMap(),
    // Lookup for "last N playback signals for filename X" — drawn from
    // SignalTimelineEngine's 30-event ring. Most filenames will have 0
    // matching events; the detail sheet renders "No recent signals" then.
    signalsForFilename: (String) -> List<SignalTimelineEngine.Event> = { emptyList() },
    onPlayDuplicate: (EmbeddingDbFacade.DuplicateRow) -> Unit = {},
    onRemoveDuplicateRows: (List<String>) -> Unit = {},
    // Push #57: filepaths the user has explicitly skipped (won't be
    // counted as Pending). Typically files Android's MediaExtractor
    // can't decode. Skip is keyed on filepath so renames un-skip naturally.
    skippedEmbeddings: Set<String> = emptySet(),
    onSkipEmbedding: (filepath: String) -> Unit = {},
    onUnskipEmbedding: (filepath: String) -> Unit = {},
    // Push #61: groups of filepaths sharing one content_hash. Surfaced
    // in a new collapsible section so the user can find and resolve
    // audio-identical-but-different-files (e.g. "song.flac" and
    // "song (1).flac" being byte-identical). Tap-to-play, (−) to delete.
    audioDuplicateGroups: List<EmbeddingDbFacade.AudioDuplicateGroup> = emptyList(),
    onPlayAudioDup: (filepath: String) -> Unit = {},
    onDeleteAudioDup: (filepath: String) -> Unit = {},
) {
    val ctx = LocalContext.current
    // Push #53: Pending uses filepath comparison. MediaStore's DISPLAY_NAME
    // (Song.filename) can drift from File(path).getName() stored on the
    // embedding row, so a filename-based comparison was leaving correctly
    // embedded songs stuck in Pending. Filepath is unambiguous.
    //
    // Push #57: also exclude filepaths the user has explicitly skipped
    // (typically permanently-failing decodes like an unusual FLAC variant).
    val pending = songs.filter {
        it.filePath != null
            && it.filePath !in embeddedFilepaths
            && it.filePath !in skippedEmbeddings
    }
    // Push #57: skipped songs surfaced in their own collapsible section
    // so the user can un-skip if they re-encode the file.
    val skippedSongs = songs.filter { it.filePath != null && it.filePath in skippedEmbeddings }
    val librarySize = songs.count { it.filePath != null }

    var reembedExpanded by remember { mutableStateOf(false) }
    var skippedExpanded by remember { mutableStateOf(false) }
    // Push #61: audio-dupes section expand state + optimistic hide for
    // filepaths the user just deleted.
    var audioDupesExpanded by remember { mutableStateOf(false) }
    var hiddenAudioDupFilepaths by remember(audioDuplicateGroups) { mutableStateOf<Set<String>>(emptySet()) }
    // Push #62: filepath of an audio-dup row currently shown in the
    // details bottom sheet. Null when sheet is closed.
    var audioDupDetailFilepath by remember { mutableStateOf<String?>(null) }
    var audioDupDetailHash by remember { mutableStateOf<String?>(null) }
    // Push #47: pending list pagination — show 10 at a time. Each tap on
    // "Load more" reveals another 10. Resets when the pending list size
    // changes (e.g. a song finishes embedding and leaves the list).
    var pendingVisible by remember(pending.size) { mutableStateOf(10) }
    var showReembedConfirm by remember { mutableStateOf(false) }
    var showRescanConfirm by remember { mutableStateOf(false) }
    // Push #43: confirmation dialog for the top-bar "Embed Pending" CTA
    // so the user doesn't accidentally kick off a multi-hour batch with
    // a stray tap.
    var showEmbedPendingConfirm by remember { mutableStateOf(false) }
    // Push #49: expand state + remove-confirm for the stale embeddings
    // list. Stale entries cost DB rows + JSON-mirror size but can never
    // match a current library song, so we let the user clean them up.
    var staleExpanded by remember { mutableStateOf(false) }
    var staleVisible by remember(staleFilenames.size) { mutableStateOf(20) }
    var showRemoveStaleConfirm by remember { mutableStateOf(false) }
    // Push #50: duplicates section UI state.
    var duplicatesExpanded by remember { mutableStateOf(false) }
    // Pagination unit = "group" (one filename's set of rows). Resets when
    // the underlying duplicates change.
    var duplicateGroupsVisible by remember(duplicateRows.size) { mutableStateOf(10) }
    // Optimistic-hide set: contentHashes that the user has tapped (−) on
    // and that we want to disappear immediately while the actual DB
    // delete is in flight. Cleared whenever the duplicates list itself
    // changes (i.e. the post-delete refresh removed the row for real).
    var hiddenContentHashes by remember(duplicateRows) { mutableStateOf<Set<String>>(emptySet()) }
    var showDedupeAllConfirm by remember { mutableStateOf(false) }
    var detailSheetRow by remember { mutableStateOf<EmbeddingDbFacade.DuplicateRow?>(null) }

    // Push #51: group by FILEPATH (not filename — that produced false
    // positives across folders). Each group is one physical file with
    // multiple embedding rows. Rows within a group sorted newest first
    // so the "newest" tag lands on row 0. Groups sorted by row count
    // DESC (worst offenders first) then by display title A→Z for
    // stable rendering. Hidden hashes are filtered for optimistic
    // remove feel; groups that collapse to ≤ 1 row drop out entirely.
    val duplicateGroups: List<Pair<String, List<EmbeddingDbFacade.DuplicateRow>>> =
        remember(duplicateRows, hiddenContentHashes, songsByFilepath) {
            duplicateRows
                .filter { it.contentHash !in hiddenContentHashes }
                .groupBy { it.filepath }
                .map { (fp, rows) -> fp to rows.sortedByDescending { it.timestamp } }
                .filter { it.second.size > 1 }
                .sortedWith(
                    compareByDescending<Pair<String, List<EmbeddingDbFacade.DuplicateRow>>> { it.second.size }
                        .thenBy {
                            val firstRow = it.second.first()
                            songsByFilepath[it.first]?.title?.ifBlank { null }
                                ?: firstRow.filename
                        }
                        .thenBy { it.first }
                )
        }
    val visibleDuplicateRowCount: Int = remember(duplicateGroups) {
        duplicateGroups.sumOf { it.second.size }
    }
    // Push #51: missing-file aware "remove all extras" math. For each
    // group, if the file is no longer in MediaStore we'd delete ALL rows
    // (no point keeping any embedding of a file that's gone). Otherwise
    // keep newest, delete the rest.
    val extraRowsToDelete: List<String> = remember(duplicateGroups, songsByFilepath) {
        buildList {
            for ((filepath, rows) in duplicateGroups) {
                if (songsByFilepath[filepath] == null) {
                    // Missing file: delete every row.
                    rows.forEach { add(it.contentHash) }
                } else {
                    // Present file: keep rows[0] (newest), delete rest.
                    rows.drop(1).forEach { add(it.contentHash) }
                }
            }
        }
    }
    val extraRowsCount: Int = extraRowsToDelete.size

    val audioDupIssueGroups = remember(audioDuplicateGroups, hiddenAudioDupFilepaths) {
        audioDuplicateGroups
            .map { g -> g.copy(filepaths = g.filepaths.filter { it !in hiddenAudioDupFilepaths }) }
            .filter { it.filepaths.size >= 2 }
    }
    // One "issue" per category bucket (not per duplicate row): orphan filename,
    // filepath with multiple embedding rows, or audio-identical file group.
    val healthIssueCount = staleFilenames.size + duplicateGroups.size + audioDupIssueGroups.size
    val healthBreakdownSummary = remember(staleFilenames.size, duplicateGroups.size, audioDupIssueGroups.size) {
        buildHealthBreakdownSummary(
            staleCount = staleFilenames.size,
            duplicateGroupCount = duplicateGroups.size,
            audioDupGroupCount = audioDupIssueGroups.size,
        )
    }
    var libraryHealthExpanded by remember(healthIssueCount) { mutableStateOf(healthIssueCount > 0) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var engineStatusExpanded by remember { mutableStateOf(false) }
    var showEngineInfoDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "AI & Library",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            // Push #40 Tier 1A: top action bar — sticky reachable CTAs.
            // Was buried at the bottom of a collapsible Pending list that
            // forced the user to scroll past hundreds of rows. Now the
            // three primary actions sit immediately under the title.
            item {
                TopActionBar(
                    pendingCount = pending.size,
                    inProgress = status.inProgress,
                    // Push #43: route the primary CTA through a confirm
                    // dialog so a tap doesn't immediately start embedding
                    // potentially hundreds of songs.
                    onEmbedPending = { showEmbedPendingConfirm = true },
                    onStop = onStop,
                    onRescan = { showRescanConfirm = true },
                )
            }

            // Push #40 Tier 2G: backend badge — visible at all times so
            // the user can tell whether the GPU/NPU (nnapi*) or CPU is
            // doing the work, even before any progress arrives.
            item {
                BackendBadge(activeBackend = status.activeBackend, inProgress = status.inProgress, error = status.error)
            }

            item {
                EmbeddingSplitSection(
                    selected = embeddingSplitCount,
                    librarySplitCount = libraryEmbedSplitCount,
                    inProgress = status.inProgress,
                    onSelect = onEmbeddingSplitCountChange,
                )
            }

            // Progress banner (in-progress)
            item { EmbeddingStatusBanner(status = status, onStop = onStop) }

            // 4-cell stats grid. Push #49: "Stale" cell is now clickable
            // and the precise count comes from the staleFilenames list
            // that MainActivity computes from embeddedFilenames -
            // currentLibraryFilenames. Tapping the cell expands an
            // inline list with a "Remove N stale embeddings" CTA.
            // Falls back to the old inference formula only when the
            // caller didn't supply a list (older call-sites / tests).
            item {
                val inLibraryEmbedded = (librarySize - pending.size).coerceAtLeast(0)
                val stale = if (staleFilenames.isNotEmpty()) staleFilenames.size
                            else (embeddingsRowCount - inLibraryEmbedded).coerceAtLeast(0)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatCell(Modifier.weight(1f), "$embeddingsRowCount", "Embedded")
                    StatCell(Modifier.weight(1f), "${pending.size}", "Pending")
                    StatCell(Modifier.weight(1f), "$librarySize", "Total")
                    StatCell(
                        Modifier.weight(1f),
                        "$stale",
                        if (staleFilenames.isNotEmpty()) "Stale ▾" else "Stale",
                        onClick = if (staleFilenames.isNotEmpty()) {
                            {
                                libraryHealthExpanded = true
                                staleExpanded = true
                            }
                        } else null,
                    )
                }
            }

            item {
                Text(
                    text = "Open Diagnostics → Activity or Embedding for history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )
            }

            item {
                val healthLabel = if (healthIssueCount == 0) {
                    "Library health (no issues)"
                } else {
                    "Library health ($healthIssueCount to review)"
                }
                CollapsibleHeader(
                    label = healthLabel,
                    expanded = libraryHealthExpanded,
                    onToggle = {
                        val next = !libraryHealthExpanded
                        libraryHealthExpanded = next
                        if (next) {
                            if (staleFilenames.isNotEmpty()) staleExpanded = true
                            if (duplicateGroups.isNotEmpty()) duplicatesExpanded = true
                            if (audioDupIssueGroups.isNotEmpty()) audioDupesExpanded = true
                        }
                    },
                )
            }

            if (libraryHealthExpanded) {
                if (healthIssueCount > 0) {
                    item {
                        Text(
                            text = "$healthIssueCount to review: $healthBreakdownSummary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (duplicateGroups.isEmpty() && audioDupIssueGroups.isEmpty() && staleFilenames.isEmpty()) {
                    item {
                        Text(
                            text = "No stale rows, duplicate embeddings, or audio duplicates. Tap Scan library above to recheck after library changes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                if (staleFilenames.isNotEmpty() && staleExpanded) {
                    item {
                        Text(
                            text = "Orphan embedding rows (${staleFilenames.size}) — file gone from library",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Text(
                            text = "These DB rows point to songs that were deleted, renamed, or moved. Removing them frees space; you cannot re-embed without the file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                        )
                    }
                    val visibleStale = staleVisible.coerceAtMost(staleFilenames.size)
                    itemsIndexed(
                        staleFilenames.take(visibleStale),
                        key = { i, fn -> "stale_${i}_$fn" },
                    ) { _, fn ->
                        Text(
                            text = "• $fn",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                        )
                    }
                    if (staleFilenames.size > visibleStale) {
                        item("stale_loadmore") {
                            val remaining = staleFilenames.size - visibleStale
                            val next = remaining.coerceAtMost(20)
                            TextButton(
                                onClick = { staleVisible += 20 },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "Load $next more ($remaining remaining)",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                    item {
                        TextButton(
                            onClick = { showRemoveStaleConfirm = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "Remove ${staleFilenames.size} orphan row${if (staleFilenames.size == 1) "" else "s"}",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                item {
                    val label = if (duplicateGroups.isEmpty()) {
                        "Duplicate embeddings (none)"
                    } else {
                        "Duplicate embeddings ($visibleDuplicateRowCount rows in ${duplicateGroups.size} group${if (duplicateGroups.size == 1) "" else "s"})"
                    }
                    CollapsibleHeader(
                        label = label,
                        expanded = duplicatesExpanded,
                        onToggle = { duplicatesExpanded = !duplicatesExpanded },
                    )
                }
                if (duplicatesExpanded && duplicateGroups.isEmpty()) {
                    item {
                        Text(
                            text = "No duplicates detected. Tap Scan library above to recheck — duplicates refresh on every scan and when this page opens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (duplicateGroups.isNotEmpty()) {
                    if (duplicatesExpanded) {
                        item {
                            Text(
                                text = "Multiple embedding rows point to the same physical file " +
                                    "(same filepath, different content hashes — usually because " +
                                    "re-embedding produced a new hash). Tap a row to play, long-press " +
                                    "or tap (ⓘ) for details. Use (−) to remove a specific row, or the " +
                                    "button below to remove everything except the newest in each " +
                                    "group. Groups whose file is no longer on the device are deleted " +
                                    "entirely by the bulk action.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        if (extraRowsCount > 0) {
                            item {
                                TextButton(
                                    onClick = { showDedupeAllConfirm = true },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        "Remove all $extraRowsCount extra${if (extraRowsCount == 1) "" else "s"} (keep newest where file exists)",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }

                        val visibleGroups = duplicateGroups.take(duplicateGroupsVisible.coerceAtMost(duplicateGroups.size))
                        itemsIndexed(
                            items = visibleGroups,
                            key = { _, pair -> "dupgrp_${pair.first}" },
                        ) { _, pair ->
                            val filepath = pair.first
                            val rows = pair.second
                            val matchedSong = songsByFilepath[filepath]
                            DuplicateGroupCard(
                                filepath = filepath,
                                rows = rows,
                                matchedSong = matchedSong,
                                signalsLookup = signalsForFilename,
                                onPlay = onPlayDuplicate,
                                onRemoveRow = { hash ->
                                    hiddenContentHashes = hiddenContentHashes + hash
                                    onRemoveDuplicateRows(listOf(hash))
                                },
                                onOpenDetails = { detailSheetRow = it },
                            )
                        }
                        if (duplicateGroups.size > duplicateGroupsVisible) {
                            item("dupgrp_loadmore") {
                                val remaining = duplicateGroups.size - duplicateGroupsVisible
                                val next = remaining.coerceAtMost(10)
                                TextButton(
                                    onClick = { duplicateGroupsVisible += 10 },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = "Load $next more group${if (next == 1) "" else "s"} ($remaining remaining)",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    val visibleGroups = audioDuplicateGroups
                        .map { g -> g.copy(filepaths = g.filepaths.filter { it !in hiddenAudioDupFilepaths }) }
                        .filter { it.filepaths.size >= 2 }
                    val visibleFileCount = visibleGroups.sumOf { it.filepaths.size }
                    val label = if (visibleGroups.isEmpty()) {
                        "Audio duplicates (none)"
                    } else {
                        "Audio duplicates ($visibleFileCount files in ${visibleGroups.size} group${if (visibleGroups.size == 1) "" else "s"})"
                    }
                    CollapsibleHeader(
                        label = label,
                        expanded = audioDupesExpanded,
                        onToggle = { audioDupesExpanded = !audioDupesExpanded },
                    )
                }
                if (audioDupesExpanded) {
                    val visibleGroups = audioDuplicateGroups
                        .map { g -> g.copy(filepaths = g.filepaths.filter { it !in hiddenAudioDupFilepaths }) }
                        .filter { it.filepaths.size >= 2 }
                    if (visibleGroups.isEmpty()) {
                        item {
                            Text(
                                text = "No audio duplicates detected. Finds byte-identical audio (first 30 s) at different filepaths — e.g. copies in multiple folders. Tap Scan library to re-check.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        item {
                            Text(
                                text = "Each group is one piece of audio at multiple filepaths. Tap to play, (−) to delete a copy from device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        itemsIndexed(
                            items = visibleGroups,
                            key = { _, g -> "audiodup_${g.contentHash}" },
                        ) { _, group ->
                            AudioDuplicateGroupCard(
                                group = group,
                                songsByFilepath = songsByFilepath,
                                onPlay = onPlayAudioDup,
                                onDelete = { fp ->
                                    hiddenAudioDupFilepaths = hiddenAudioDupFilepaths + fp
                                    onDeleteAudioDup(fp)
                                },
                                onOpenDetails = { fp ->
                                    audioDupDetailFilepath = fp
                                    audioDupDetailHash = group.contentHash
                                },
                            )
                        }
                    }
                }
            } // libraryHealthExpanded

            if (!status.inProgress && status.lastCompletedAtMs > 0) {
                // Push #49: keep showing the most recent batch's
                // summary until the user starts another batch. Capacitor
                // had this; the Kotlin port previously hid it as soon as
                // inProgress flipped false, so the user couldn't tell
                // how many succeeded vs failed.
                item {
                    val ageMs = (System.currentTimeMillis() - status.lastCompletedAtMs).coerceAtLeast(0)
                    val ageText = when {
                        ageMs < 60_000L -> "just now"
                        ageMs < 3600_000L -> "${ageMs / 60_000L}m ago"
                        ageMs < 86_400_000L -> "${ageMs / 3600_000L}h ago"
                        else -> "${ageMs / 86_400_000L}d ago"
                    }
                    Text(
                        text = buildString {
                            append("Last run: ${status.lastCompletedProcessed} succeeded")
                            if (status.lastCompletedFailed > 0) append(", ${status.lastCompletedFailed} failed")
                            append(" • completed $ageText")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            item {
                CollapsibleHeader(
                    label = "Engine status",
                    expanded = engineStatusExpanded,
                    onToggle = { engineStatusExpanded = !engineStatusExpanded },
                )
            }
            if (engineStatusExpanded) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val vecLabel = if (vecExtensionLoaded) "sqlite-vec (fast)" else "NEON fallback"
                        val dimLabel = if (embeddingsDim > 0) "$embeddingsDim-D" else "unknown"
                        val backendLabel = status.activeBackend.ifBlank { "— until embedding runs" }
                        Text(
                            text = "Vector search: $vecLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Embedding size: $dimLabel (fixed by model)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = "Sampling: 3 × 10 s per song at 20%, 50%, 80%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (status.activeBackend.isNotBlank()) {
                            Text(
                                text = "ONNX backend: $backendLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        TextButton(onClick = { showEngineInfoDialog = true }) {
                            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("What do these mean?", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            item {
                val failedHint = if (status.failed > 0) ", ${status.failed} failed" else ""
                val advancedLabel = when {
                    pending.isEmpty() -> "Advanced embedding"
                    else -> "Advanced embedding (${pending.size} pending$failedHint)"
                }
                CollapsibleHeader(
                    label = advancedLabel,
                    expanded = advancedExpanded,
                    onToggle = { advancedExpanded = !advancedExpanded },
                )
            }

            if (advancedExpanded) {
                if (pending.isEmpty()) {
                    item {
                        Text(
                            text = "No songs pending.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    val visibleCount = pendingVisible.coerceAtMost(pending.size)
                    itemsIndexed(
                        pending.take(visibleCount),
                        key = { i, s -> "pend_${i}_${s.id}" },
                    ) { _, song ->
                        PendingRow(
                            song = song,
                            onEmbedOne = { onEmbedOne(song) },
                            onSkip = { song.filePath?.let { onSkipEmbedding(it) } },
                            enabled = true,
                        )
                    }
                    if (pending.size > visibleCount) {
                        item("pend_loadmore") {
                            val remaining = pending.size - visibleCount
                            val next = remaining.coerceAtMost(10)
                            TextButton(
                                onClick = { pendingVisible += 10 },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = "Load $next more ($remaining remaining)",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }

            // Push #57: Skipped Songs section — files the user has
            // explicitly opted out of embedding. Always visible (header
            // shows "(none)" when empty) so user can find and un-skip
            // later if they re-encode the file.
            item {
                CollapsibleHeader(
                    label = if (skippedSongs.isEmpty()) "Skipped Songs (none)"
                            else "Skipped Songs (${skippedSongs.size})",
                    expanded = skippedExpanded,
                    onToggle = { skippedExpanded = !skippedExpanded },
                )
            }
            if (skippedExpanded) {
                if (skippedSongs.isEmpty()) {
                    item {
                        Text(
                            text = "No skipped songs. Use ⊖ on a pending row to skip a file that won't embed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    itemsIndexed(
                        skippedSongs,
                        key = { i, s -> "skipped_${i}_${s.id}" },
                    ) { _, song ->
                        SkippedRow(
                            song = song,
                            onUnskip = { song.filePath?.let { onUnskipEmbedding(it) } },
                        )
                    }
                }
            }

            // Push #48 (revised): the destructive "Re-embed all" action
            // is tucked into a collapsible section so it can't be tapped
            // accidentally from the top bar. The user expand-then-confirm
            // sequence (expand collapsible → tap Start → confirm dialog)
            // gives three deliberate steps before the multi-hour batch
            // kicks off.
            item {
                CollapsibleHeader(
                    label = "Re-embed All Songs ($librarySize)",
                    expanded = reembedExpanded,
                    onToggle = { reembedExpanded = !reembedExpanded },
                )
            }
            if (reembedExpanded) {
                item {
                    val etaMin = (librarySize * 25 / 60).coerceAtLeast(1)
                    Text(
                        text = "Replaces every existing embedding from scratch. ~$etaMin min on NPU/GPU, much longer on CPU. Best run while plugged in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (!status.inProgress) {
                        TextButton(
                            onClick = { showReembedConfirm = true },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            Text("Start re-embedding all", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            } // advancedExpanded
        }
    }

    if (showReembedConfirm) {
        AlertDialog(
            onDismissRequest = { showReembedConfirm = false },
            title = { Text("Re-embed all songs?") },
            text = { Text("This drops every existing embedding and rebuilds from scratch. Takes time — best done while plugged in.") },
            confirmButton = {
                TextButton(onClick = { showReembedConfirm = false; onReembedAll() }) {
                    Text("Start", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showReembedConfirm = false }) { Text("Cancel") } },
        )
    }
    if (showEmbedPendingConfirm) {
        val etaMin = (pending.size * 25 / 60).coerceAtLeast(1)
        AlertDialog(
            onDismissRequest = { showEmbedPendingConfirm = false },
            title = { Text("Embed ${pending.size} pending songs?") },
            text = {
                Text(
                    "This runs in the background. Roughly ~$etaMin min on a phone NPU/GPU, "
                        + "much longer on CPU. Best done while plugged in.\n\n"
                        + "You can keep using the app and queue more songs via the per-row icon."
                )
            },
            confirmButton = {
                TextButton(onClick = { showEmbedPendingConfirm = false; onEmbedPending() }) {
                    Text("Start")
                }
            },
            dismissButton = { TextButton(onClick = { showEmbedPendingConfirm = false }) { Text("Cancel") } },
        )
    }
    if (showDedupeAllConfirm) {
        val missingGroupCount = duplicateGroups.count { songsByFilepath[it.first] == null }
        AlertDialog(
            onDismissRequest = { showDedupeAllConfirm = false },
            title = { Text("Remove $extraRowsCount duplicate row${if (extraRowsCount == 1) "" else "s"}?") },
            text = {
                Text(
                    buildString {
                        append("For each file with multiple embedding rows, the newest row is kept and the rest deleted.")
                        if (missingGroupCount > 0) {
                            append(" $missingGroupCount group")
                            if (missingGroupCount != 1) append("s")
                            append(" reference a file that's no longer on the device — those will be removed entirely.")
                        }
                        append(" Cannot be undone — re-importing a file will require re-embedding.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDedupeAllConfirm = false
                    duplicatesExpanded = false
                    // Optimistic hide so the section visibly shrinks
                    // before the DB callback returns.
                    hiddenContentHashes = hiddenContentHashes + extraRowsToDelete
                    onRemoveDuplicateRows(extraRowsToDelete)
                }) {
                    Text("Remove all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDedupeAllConfirm = false }) { Text("Cancel") }
            },
        )
    }
    if (detailSheetRow != null) {
        DuplicateDetailSheet(
            row = detailSheetRow!!,
            signals = signalsForFilename(detailSheetRow!!.filename),
            onDismiss = { detailSheetRow = null },
            onPlay = {
                val r = detailSheetRow
                detailSheetRow = null
                if (r != null) onPlayDuplicate(r)
            },
            onRemove = {
                val r = detailSheetRow
                detailSheetRow = null
                if (r != null) {
                    hiddenContentHashes = hiddenContentHashes + r.contentHash
                    onRemoveDuplicateRows(listOf(r.contentHash))
                }
            },
        )
    }
    // Push #62: Audio Duplicate row details sheet.
    if (audioDupDetailFilepath != null) {
        val fp = audioDupDetailFilepath!!
        val hash = audioDupDetailHash ?: ""
        val matched = songsByFilepath[fp]
        AudioDuplicateDetailSheet(
            filepath = fp,
            contentHash = hash,
            matchedSong = matched,
            onDismiss = { audioDupDetailFilepath = null; audioDupDetailHash = null },
            onPlay = {
                val f = audioDupDetailFilepath
                audioDupDetailFilepath = null
                audioDupDetailHash = null
                if (f != null) onPlayAudioDup(f)
            },
            onDelete = {
                val f = audioDupDetailFilepath
                audioDupDetailFilepath = null
                audioDupDetailHash = null
                if (f != null) {
                    hiddenAudioDupFilepaths = hiddenAudioDupFilepaths + f
                    onDeleteAudioDup(f)
                }
            },
        )
    }
    if (showRemoveStaleConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveStaleConfirm = false },
            title = { Text("Remove ${staleFilenames.size} stale embedding${if (staleFilenames.size == 1) "" else "s"}?") },
            text = {
                Text(
                    "These rows reference songs that are no longer in your library " +
                        "(file deleted, renamed, or imported from another device). " +
                        "Removal cannot be undone — re-adding the file will require " +
                        "re-embedding."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveStaleConfirm = false
                    staleExpanded = false
                    onRemoveStale(staleFilenames.toList())
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveStaleConfirm = false }) { Text("Cancel") }
            },
        )
    }
    if (showRescanConfirm) {
        AlertDialog(
            onDismissRequest = { showRescanConfirm = false },
            title = { Text("Scan library?") },
            text = {
                Text(
                    "Re-reads MediaStore for added or removed songs, refreshes counts, " +
                        "and recomputes stale rows and duplicates. Does not run embedding."
                )
            },
            confirmButton = {
                TextButton(onClick = { showRescanConfirm = false; onRescanLibrary() }) {
                    Text("Scan")
                }
            },
            dismissButton = { TextButton(onClick = { showRescanConfirm = false }) { Text("Cancel") } },
        )
    }
    if (showEngineInfoDialog) {
        AlertDialog(
            onDismissRequest = { showEngineInfoDialog = false },
            title = { Text("Engine status") },
            text = {
                Text(
                    "These values are automatic, not settings.\n\n" +
                        "• Vector search: sqlite-vec speeds up similarity search; otherwise NEON fallback.\n" +
                        "• Embedding size: 512 numbers per song (fixed by the ONNX model).\n" +
                        "• Sampling: 3 × 10 s clips per song at 20%, 50%, and 80% of track length, then averaged."
                )
            },
            confirmButton = {
                TextButton(onClick = { showEngineInfoDialog = false }) { Text("OK") }
            },
        )
    }
}

// Push #48: LogTab enum removed — the AI page now shows a single unified
// log instead of Common / Embedding tabs that always rendered the same data.

@Composable
private fun StatCell(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    // Push #49: optional click handler so the "Stale" cell can toggle
    // the inline removal list. Null = static (existing 3 cells).
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CollapsibleHeader(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildHealthBreakdownSummary(
    staleCount: Int,
    duplicateGroupCount: Int,
    audioDupGroupCount: Int,
): String {
    val parts = mutableListOf<String>()
    if (staleCount > 0) {
        parts.add("$staleCount orphan ${if (staleCount == 1) "row" else "rows"}")
    }
    if (duplicateGroupCount > 0) {
        parts.add("$duplicateGroupCount duplicate-embedding ${if (duplicateGroupCount == 1) "group" else "groups"}")
    }
    if (audioDupGroupCount > 0) {
        parts.add("$audioDupGroupCount audio-duplicate ${if (audioDupGroupCount == 1) "group" else "groups"}")
    }
    return parts.joinToString(" · ")
}

/** Prefer title/artist; avoid showing a full filesystem path as the row title. */
private fun songDisplayTitle(song: Song): String {
    val raw = song.title.ifBlank { song.filename }
    if (raw.contains('/') || raw.contains('\\')) {
        return song.filePath?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.ifBlank { song.filename }
            ?: song.filename.ifBlank { raw.substringAfterLast('/', raw) }
    }
    return raw
}

@Composable
private fun PendingRow(
    song: Song,
    onEmbedOne: () -> Unit,
    onSkip: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtThumbnail(filePath = song.filePath, size = 40.dp, cornerRadius = 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = songDisplayTitle(song),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist.ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Push #57: per-row "skip embedding" icon. Adds the filepath to
        // the skip set so this song stops counting as Pending. Useful
        // when the song's audio can't be decoded by Android's
        // MediaExtractor and won't ever embed successfully.
        IconButton(onClick = onSkip) {
            Icon(
                imageVector = Icons.Filled.RemoveCircle,
                contentDescription = "Skip embedding for this song",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onEmbedOne, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = "Embed this song",
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Push #61: one audio-duplicate group. Each filepath in the group plays
 * back the same audio. Renders each as a row with thumb + title + artist
 * (from songsByFilepath when available; falls back to the filename
 * inferred from the path). Tap plays. (−) deletes the file from device.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioDuplicateGroupCard(
    group: EmbeddingDbFacade.AudioDuplicateGroup,
    songsByFilepath: Map<String, Song>,
    onPlay: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    val hashShort = if (group.contentHash.length > 12) group.contentHash.take(12) + "…" else group.contentHash
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "hash $hashShort",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${group.filepaths.size} files",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        group.filepaths.forEachIndexed { idx, filepath ->
            val matched = songsByFilepath[filepath]
            val displayTitle = matched?.title?.ifBlank { null }
                ?: filepath.substringAfterLast('/').substringBeforeLast('.')
            val displayArtist = matched?.artist?.ifBlank { null } ?: "Unknown artist"
            val present = matched != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .combinedClickable(
                        onClick = { if (present) onPlay(filepath) },
                        onLongClick = { /* details could be added later */ },
                    )
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtThumbnail(filePath = filepath, size = 36.dp, cornerRadius = 4.dp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = displayArtist + if (!present) "  •  ⚠ file missing" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!present) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = filepath,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (present) {
                    IconButton(onClick = { onPlay(filepath) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play this file",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // Push #62: details icon — opens a bottom sheet with
                // file size, format, last modified, full path, etc. so
                // the user can pick which duplicate to delete based on
                // concrete file attributes.
                IconButton(onClick = { onOpenDetails(filepath) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "Details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { onDelete(filepath) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.RemoveCircle,
                        contentDescription = "Delete this file",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (idx < group.filepaths.lastIndex) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Push #57: row inside the Skipped Songs collapsible. Same shape as
 * PendingRow but the action button is "un-skip" (re-add to pending).
 */
@Composable
private fun SkippedRow(song: Song, onUnskip: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtThumbnail(filePath = song.filePath, size = 40.dp, cornerRadius = 4.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = song.title.ifBlank { song.filename },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist.ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onUnskip) {
            Text("Un-skip", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Push #40 Tier 1A: sticky top action bar.
 * Holds the 3 CTAs (Embed Pending / Re-embed All / Rescan) and the
 * Stop button shown while a batch is in progress. Replaces the buried
 * `PrimaryActionRow` that used to sit at the bottom of a collapsible
 * pending-list LazyColumn.
 */
@Composable
private fun TopActionBar(
    pendingCount: Int,
    inProgress: Boolean,
    onEmbedPending: () -> Unit,
    onStop: () -> Unit,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Primary CTA — either "Embed N pending" or "Stop" depending on state.
        if (inProgress) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(onClick = onStop)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Stop embedding",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (pendingCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable(enabled = pendingCount > 0, onClick = onEmbedPending)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = if (pendingCount > 0) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (pendingCount > 0) "Embed $pendingCount pending songs"
                               else "Nothing pending — library fully embedded",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (pendingCount > 0) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pendingCount > 0) {
                        Text(
                            text = "Runs in background; you can keep playing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        )
                    }
                }
                if (pendingCount > 0) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onRescan,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Scan library", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmbeddingSplitSection(
    selected: Int,
    librarySplitCount: Int?,
    inProgress: Boolean,
    onSelect: (Int) -> Unit,
) {
    val normalized = EmbeddingSplitCount.normalize(selected)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
    ) {
        Text(
            text = "CLAP windows per song",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = EmbeddingSplitCount.positionsLabel(normalized) +
                " — averaged, then L2-normalized (512-d)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (n in EmbeddingSplitCount.allowed) {
                FilterChip(
                    selected = normalized == n,
                    onClick = { if (!inProgress) onSelect(n) },
                    label = { Text("$n") },
                    enabled = !inProgress,
                )
            }
        }
        if (normalized > 3) {
            val pct = ((EmbeddingSplitCount.timeMultiplier(normalized) - 1f) * 100f).toInt()
            Text(
                text = "Roughly ~$pct% longer per song than 3 windows (more ONNX passes).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (librarySplitCount != null && librarySplitCount != normalized) {
            Text(
                text = "Library was embedded with $librarySplitCount splits. " +
                    "Use Re-embed all after changing — do not mix split counts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (librarySplitCount == null && normalized != 3) {
            Text(
                text = "Existing backups are usually 3-split. Re-embed all after switching to $normalized.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (inProgress) {
            Text(
                text = "Split count locked while a batch is running.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Push #40 Tier 2G: backend badge.
 * Visible all the time so the user can verify the NPU/GPU is engaged
 * (or see "CPU" early enough to react). Green = accelerated, amber =
 * CPU fallback, gray = not yet known.
 */
@Composable
private fun BackendBadge(activeBackend: String, inProgress: Boolean, error: String?) {
    val isNnapi = activeBackend.startsWith("nnapi", ignoreCase = true)
    val isCpu = activeBackend.equals("cpu", ignoreCase = true)
    val (bg, fg, label) = when {
        error != null -> Triple(MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Backend error — see status below")
        isNnapi -> Triple(androidx.compose.ui.graphics.Color(0xFF1F3D2A),
            androidx.compose.ui.graphics.Color(0xFF7ADCA1),
            "Backend: $activeBackend  (NPU/GPU — fast)")
        isCpu -> Triple(androidx.compose.ui.graphics.Color(0xFF4A3110),
            androidx.compose.ui.graphics.Color(0xFFF5C16C),
            "Backend: CPU  (NPU/GPU unavailable — slower)")
        inProgress -> Triple(MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Backend: warming up audio model…")
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Inference hardware: picks NPU/GPU or CPU when embedding starts")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
    }
}

@Composable
private fun PrimaryActionRow(label: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun TabChip(active: Boolean, label: String, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

private fun copyToClipboard(ctx: android.content.Context, label: String, text: String) {
    val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * Push #51: one filepath's worth of duplicate embedding rows. Header
 * carries the song identity (title / artist / album from the MediaStore
 * Song, with the filepath as a mono subline) since every row in the
 * group is the SAME physical file. Per-row content is reduced to the
 * embed-time metadata that actually differs across rows: timestamp,
 * dim, the (newest) tag, and the action buttons.
 *
 * When the file is no longer on the device (songsByFilepath lookup
 * returned null = matchedSong is null), we render a warning badge and
 * disable the play row-tap; the (−) and (ⓘ) actions still work so the
 * user can clean up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DuplicateGroupCard(
    filepath: String,
    rows: List<EmbeddingDbFacade.DuplicateRow>,
    matchedSong: Song?,
    signalsLookup: (String) -> List<SignalTimelineEngine.Event>,
    onPlay: (EmbeddingDbFacade.DuplicateRow) -> Unit,
    onRemoveRow: (String) -> Unit,
    onOpenDetails: (EmbeddingDbFacade.DuplicateRow) -> Unit,
) {
    val newestHash = rows.firstOrNull()?.contentHash
    val firstRow = rows.first()
    // Display title prefers the live MediaStore Song; falls back to the
    // embedding row's filename (extension stripped) when the file is
    // missing. The embedding row's artist/album are last-resort because
    // legacy migrations often left them blank.
    val displayTitle = matchedSong?.title?.ifBlank { null }
        ?: firstRow.filename.substringBeforeLast('.').ifBlank { firstRow.filename }
    val displayArtist = matchedSong?.artist?.ifBlank { null }
        ?: firstRow.artist.ifBlank { null }
        ?: "Unknown artist"
    val displayAlbum = matchedSong?.album?.ifBlank { null }
        ?: firstRow.album.ifBlank { null }
    val signalsForFile = remember(signalsLookup, firstRow.filename) {
        signalsLookup(firstRow.filename)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = if (matchedSong == null) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(8.dp),
    ) {
        // Group header — song identity + filepath + row-count badge.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtThumbnail(filePath = filepath, size = 40.dp, cornerRadius = 6.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${rows.size} rows",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = buildString {
                        append(displayArtist)
                        if (displayAlbum != null) append("  •  $displayAlbum")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (matchedSong == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "⚠ file no longer on device",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                Text(
                    text = filepath,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        rows.forEach { row ->
            DuplicateRowItem(
                row = row,
                isNewest = row.contentHash == newestHash,
                fileExists = matchedSong != null,
                signalCount = signalsForFile.count { it.filename == row.filename },
                onPlay = if (matchedSong != null) {
                    { onPlay(row) }
                } else null,
                onRemove = { onRemoveRow(row.contentHash) },
                onOpenDetails = { onOpenDetails(row) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DuplicateRowItem(
    row: EmbeddingDbFacade.DuplicateRow,
    isNewest: Boolean,
    fileExists: Boolean,
    signalCount: Int,
    onPlay: (() -> Unit)?,
    onRemove: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    val tapBehavior: Modifier = if (onPlay != null) {
        Modifier.combinedClickable(onClick = onPlay, onLongClick = onOpenDetails)
    } else {
        Modifier.combinedClickable(onClick = onOpenDetails, onLongClick = onOpenDetails)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(tapBehavior)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isNewest) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1F3D2A))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "newest",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF7ADCA1),
                )
            }
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.width(56.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildString {
                    val ts = if (row.timestamp > 0L) dateFmt.format(Date(row.timestamp)) else "—"
                    append("embedded $ts")
                    if (row.dim > 0) append("  •  dim ${row.dim}")
                    if (signalCount > 0) append("  •  $signalCount signal${if (signalCount == 1) "" else "s"}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "hash " + (if (row.contentHash.length > 16) row.contentHash.take(16) + "…" else row.contentHash),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onPlay != null) {
            IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play this file",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onOpenDetails, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Info,
                contentDescription = "Details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.RemoveCircle,
                contentDescription = "Remove this row",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Push #50: bottom sheet rendered when the user long-presses a duplicate
 * row OR taps the (ⓘ) icon. Shows full filepath, file-type, embedded
 * timestamp, dim, and any matching playback signals from the last 30
 * captured (most rows will have zero). Action buttons mirror the
 * row-level controls but with bigger hit areas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicateDetailSheet(
    row: EmbeddingDbFacade.DuplicateRow,
    signals: List<SignalTimelineEngine.Event>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val ext = remember(row.filename, row.filepath) {
        val src = row.filename.ifBlank { row.filepath }
        val i = src.lastIndexOf('.')
        if (i in 0 until src.length - 1) src.substring(i + 1).lowercase() else "—"
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = row.filename.ifBlank { "(no filename)" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.artist.ifBlank { "Unknown artist" } + (if (row.album.isNotBlank()) "  •  ${row.album}" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Push #62: read the on-disk file size + last-modified once
            // when the sheet opens so the user can tell otherwise-
            // identical duplicate rows apart by size.
            val fileMeta = remember(row.filepath) {
                com.isaivazhi.app.engine.FileMeta.read(row.filepath)
            }
            Spacer(Modifier.height(12.dp))
            DetailLabelValue("Filepath", row.filepath.ifBlank { "—" }, mono = true)
            DetailLabelValue("Type", ext)
            DetailLabelValue(
                "File size",
                if (fileMeta.exists) com.isaivazhi.app.engine.FileMeta.formatSize(fileMeta.sizeBytes)
                else "— (file missing)",
            )
            DetailLabelValue(
                "File modified",
                if (fileMeta.exists && fileMeta.lastModified > 0L)
                    dateFmt.format(Date(fileMeta.lastModified)) else "—",
            )
            DetailLabelValue(
                "Embedded at",
                if (row.timestamp > 0L) dateFmt.format(Date(row.timestamp)) else "—",
            )
            DetailLabelValue("Embedding dim", row.dim.toString())
            DetailLabelValue(
                "Content hash",
                if (row.contentHash.length > 16) row.contentHash.take(16) + "…" else row.contentHash,
                mono = true,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Recent playback signals (this song)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val mine = signals.filter { it.filename == row.filename }
            if (mine.isEmpty()) {
                Text(
                    text = "No recent signals captured for this song. Signals are kept for the most recent 30 plays/skips across the whole library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                mine.take(10).forEachIndexed { i, ev ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "${i + 1}. ${if (ev.classification == SignalTimelineEngine.Classification.SKIP) "skip" else "listen"}  •  ${(ev.fraction * 100).toInt()}%  •  ${dateFmt.format(Date(ev.timestamp))}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "score ${"%+.2f".format(ev.tasteBefore.score)} → ${"%+.2f".format(ev.tasteAfter.score)}  •  source ${ev.source}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (mine.size > 10) {
                    Text(
                        text = "… and ${mine.size - 10} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.RemoveCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Remove this row", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Push #62: bottom sheet with concrete file attributes (size, format,
 * mtime, hash, title/artist/album) for one filepath inside an audio-
 * duplicate group. Lets the user pick which copy to keep based on the
 * actual file rather than guessing from filename alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioDuplicateDetailSheet(
    filepath: String,
    contentHash: String,
    matchedSong: Song?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val meta = remember(filepath) { com.isaivazhi.app.engine.FileMeta.read(filepath) }
    val title = matchedSong?.title?.ifBlank { null }
        ?: filepath.substringAfterLast('/').substringBeforeLast('.')
    val artist = matchedSong?.artist?.ifBlank { null } ?: "Unknown artist"
    val album = matchedSong?.album?.ifBlank { null }
    val hashShort = if (contentHash.length > 16) contentHash.take(16) + "…" else contentHash

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist + (if (album != null) "  •  $album" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            DetailLabelValue("Filepath", filepath, mono = true)
            DetailLabelValue("Format", meta.extension.ifBlank { "—" }.uppercase())
            DetailLabelValue(
                "File size",
                if (meta.exists) com.isaivazhi.app.engine.FileMeta.formatSize(meta.sizeBytes)
                else "— (file missing)",
            )
            DetailLabelValue(
                "File modified",
                if (meta.exists && meta.lastModified > 0L)
                    dateFmt.format(Date(meta.lastModified)) else "—",
            )
            DetailLabelValue("Content hash", hashShort, mono = true)
            DetailLabelValue(
                "In MediaStore",
                if (matchedSong != null) "Yes" else "No",
            )
            if (!meta.exists) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "⚠ The file at this path is no longer on the device. Tap Remove to drop the path index entry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onPlay,
                    enabled = meta.exists && matchedSong != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play")
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.RemoveCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Delete file", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailLabelValue(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            style = if (mono) MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                   else MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
