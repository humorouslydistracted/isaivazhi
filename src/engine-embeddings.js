// On-device embedding pipeline — ONNX batch embedding, local embedding store,
// orphan/deletion management, and embeddings-ready lifecycle.
// Extracted from engine.js; imports engine-state.js, engine-indexes.js, engine-taste.js.
// engine.js wires up cross-cutting callbacks via initEmbeddingCallbacks().

import { Preferences } from '@capacitor/preferences';
import { Recommender } from './recommender.js';
import { attachGpuToRec } from './gpu-recommender.js';
import { MusicBridge } from './music-bridge.js';
import * as EmbeddingCache from './embedding-cache.js';
import {
  TOP_N,
  songs, setSongs, embeddings, embeddingMap, setEmbeddingMap,
  rec, setRec,
  setProfileVec,
  scanCallbacks,
  playlists, favorites, setFavorites,
  _tuning, state,
  EXTERNAL_DATA_DIR,
} from './engine-state.js';
import {
  _getFilenameMap, _getPathMap,
  _invalidateFilenameMap, _invalidatePathMap, _invalidateEmbIdxMap,
} from './engine-indexes.js';
import {
  _activity, _songRef,
  _embExcludeToFilepaths, _nativeNearestResultsToSongItems,
  buildProfileVec,
} from './engine-taste.js';

// --- Private embedding pipeline state ---
let embeddingInProgress = false;
let embeddingQueue = [];
let localEmbeddings = {};
let embeddingLog = [];
let embeddingCurrentFile = '';
let embeddingTotalCount = 0;
let embeddingProcessedCount = 0;
let embeddingReportedFailedCount = 0;
let embeddingFailedPaths = [];
let embeddingStartTime = null;
let embeddingPausedByUser = false;
let unmatchedEmbeddings = [];
export let librarySavedAt = 0;
export function setLibrarySavedAt(v) { librarySavedAt = v; }
export const SCAN_SKIP_WINDOW_MS = 6 * 60 * 60 * 1000;

let orphanedSongs = [];
let _songLibraryChangedCbs = [];
let removedEmbeddingKeys = new Set();
const REMOVED_EMB_PREF_KEY = 'embedding_removed_by_user';
// Songs that were requested for embedding while a batch was already running.
// Stored by id (deduped). Drained at the end of _runPostBatchMerge so user
// intent is preserved even if the action arrives mid-batch.
let _deferredReembedIds = new Set();
let _lastCompletedBatchSig = null;
let _embeddingStatusWatchdogId = null;
const EMBEDDING_STATUS_POLL_MS = 5000;
let _embeddingListenersSet = false;
let _embeddingsReadyCbs = [];
let _embeddingsReady = false;

// --- Callbacks wired by engine.js to avoid circular import ---
let _cbs = {};
export function initEmbeddingCallbacks(cbs) { _cbs = cbs; }

// --- Embeddings-ready lifecycle ---

export function _markEmbeddingsReady() {
  _embeddingsReady = true;
  for (const cb of _embeddingsReadyCbs) {
    try { cb(); } catch (e) { /* ignore */ }
  }
  _embeddingsReadyCbs = [];
}

export function isEmbeddingsReady() {
  return _embeddingsReady;
}

export function onEmbeddingsReady(cb) {
  if (_embeddingsReady) { cb(); return; }
  _embeddingsReadyCbs.push(cb);
}

// --- Embedding log ---

function _embLog(level, message) {
  const entry = { time: new Date().toISOString(), level, message };
  embeddingLog.push(entry);
  if (embeddingLog.length > 200) embeddingLog.shift();
  console[level === 'error' ? 'error' : 'log'](`[Embedding] ${message}`);
  _activity('embedding', 'embedding_log', message, { level }, {
    important: level === 'error' || level === 'success',
    level: level === 'error' ? 'error' : 'info',
    tags: ['embedding'],
  });
}

// --- Local embeddings I/O ---

export async function _loadLocalEmbeddings() {
  const t0 = performance.now();
  try {
    _embLog('info', `Loading embeddings from: ${EXTERNAL_DATA_DIR}`);
    const loaded = await EmbeddingCache.loadFromDisk();
    if (loaded) {
      const count = Object.keys(loaded).filter(k => k !== '_path_index').length;
      if (count > 0 || Object.keys(localEmbeddings).filter(k => k !== '_path_index').length === 0) {
        localEmbeddings = loaded;
      }
      _embLog('info', `Loaded ${count} embeddings from disk`);
      console.log(`[PERF] _loadLocalEmbeddings: ${count} entries in ${Math.round(performance.now() - t0)} ms`);
    } else {
      _embLog('info', 'No embeddings file found on disk');
      console.log(`[PERF] _loadLocalEmbeddings: empty in ${Math.round(performance.now() - t0)} ms`);
    }
  } catch (e) {
    _embLog('error', `Could not load embeddings: ${e.message}`);
    console.log(`[PERF] _loadLocalEmbeddings: ERROR in ${Math.round(performance.now() - t0)} ms — ${e.message}`);
  }
}

export function _saveSongLibrary() {
  // Save song metadata from scan so next startup has instant UI without needing scan
  try {
    const library = {
      songs: songs.filter(s => s.filePath).map(s => ({
        filename: s.filename,
        title: s.title,
        artist: s.artist,
        album: s.album,
        filePath: s.filePath,
        artPath: s.artPath || null,
        dateModified: s.dateModified,
        hasEmbedding: s.hasEmbedding || false,
        embeddingIndex: s.embeddingIndex != null ? s.embeddingIndex : null,
        contentHash: s.contentHash || null,
        disliked: !!s.disliked,
      })),
      savedAt: Date.now(),
    };
    MusicBridge.writeTextFile({
      path: `${EXTERNAL_DATA_DIR}/song_library.json`,
      content: JSON.stringify(library),
    }).catch(e => console.error('Failed to save song library:', e));
  } catch (e) { /* ignore */ }
}

function _saveLocalEmbeddings() {
  EmbeddingCache.saveToDisk(localEmbeddings);
}

// --- Merge local embeddings into songs/embeddings arrays ---

export function _mergeLocalEmbeddings() {
  // Merge local on-device embeddings into the songs/embeddings arrays.
  // Local embeddings take priority over Colab ones (they're verified on-device).
  // Can be called early with cached library (songs have filePaths from save)
  // and again after scan to pick up newly discovered songs.
  // Matches by BOTH filepath and content hash simultaneously.
  let mergeCount = 0;
  let matchByPath = 0, matchByHash = 0, matchByFallback = 0, noMatch = 0;
  unmatchedEmbeddings = [];

  // Reset stale embeddingIndex values from saved library — the embeddings array
  // was cleared in loadData and is rebuilt here. Leaving stale indices causes
  // sparse-array holes (song.embeddingIndex=1688 into empty array → undefined slots).
  if (embeddings.length === 0) {
    for (const s of songs) {
      s.hasEmbedding = false;
      s.embeddingIndex = null;
    }
  }

  // Build lookup maps for matching
  const songsByPath = {};
  const songsByHash = {};
  const songsByFnArtistAlbum = {}; // "filename|artist|album" → song (for Colab import fallback)
  for (const s of songs) {
    if (s.filePath) songsByPath[s.filePath] = s;
    if (s.contentHash) songsByHash[s.contentHash] = s;
    const fallbackKey = `${s.filename.toLowerCase()}|${(s.artist || '').toLowerCase()}|${(s.album || '').toLowerCase()}`;
    if (!songsByFnArtistAlbum[fallbackKey]) songsByFnArtistAlbum[fallbackKey] = s;
  }

  const embEntries = Object.entries(localEmbeddings).filter(([k]) => k !== '_path_index');
  _embLog('info', `Merge: ${embEntries.length} embeddings vs ${songs.length} songs (${Object.keys(songsByPath).length} with paths)`);

  // Log a sample filepath from each side for debugging
  if (embEntries.length > 0) {
    const sampleEmb = embEntries[0][1];
    _embLog('info', `Emb sample: path=${sampleEmb.filepath}, file=${sampleEmb.filename}`);
  }
  const sampleSong = songs.find(s => s.filePath);
  if (sampleSong) {
    _embLog('info', `Song sample: path=${sampleSong.filePath}, file=${sampleSong.filename}`);
  }

  // After fix C the binary store hands us Float32Array embeddings; pending
  // JSON gives us plain Array. Both must be accepted, and we must NOT
  // re-allocate Float32Array when one is already provided — that's the
  // double-conversion this fix eliminates. The validity guard below also
  // can no longer use Array.isArray (it would reject typed arrays).
  for (const [key, data] of embEntries) {
    const emb = data && data.embedding;
    if (!emb || typeof emb.length !== 'number' || emb.length === 0) continue;
    if (!Array.isArray(emb) && !ArrayBuffer.isView(emb)) continue;

    // Match by filepath first, then content hash, then filename+artist (Colab import fallback)
    let song = null;
    if (data.filepath) song = songsByPath[data.filepath];
    if (song) { matchByPath++; }
    const hash = data.content_hash || data.contentHash;
    if (!song && hash) song = songsByHash[hash];
    if (song && !matchByPath) { matchByHash++; }
    if (!song && data.filename) {
      // Try filename+artist+album fallback
      const artist = (data.artist || '').toLowerCase();
      const album = (data.album || '').toLowerCase();
      const fallbackKey = `${data.filename.toLowerCase()}|${artist}|${album}`;
      const candidate = songsByFnArtistAlbum[fallbackKey];
      if (candidate && !candidate.hasEmbedding) { song = candidate; matchByFallback++; }
    }
    if (!song) {
      noMatch++;
      unmatchedEmbeddings.push({
        key,
        filename: data.filename || key,
        filepath: data.filepath || '',
        artist: data.artist || '',
        album: data.album || '',
      });
      continue;
    }

    // If matched by hash but path changed (file was moved/renamed), update path
    if (song.filePath && data.filepath && song.filePath !== data.filepath) {
      // Update the path index
      const pathIndex = localEmbeddings._path_index || {};
      delete pathIndex[data.filepath];
      pathIndex[song.filePath] = hash;
      data.filepath = song.filePath;
    }

    // Use the existing Float32Array directly when the loader already produced
    // one (binary store fast-path). Only allocate a new Float32Array when the
    // source is a plain Array (legacy JSON / pending JSON paths).
    const vec = emb instanceof Float32Array ? emb : new Float32Array(emb);
    if (song.hasEmbedding && song.embeddingIndex != null) {
      embeddings[song.embeddingIndex] = vec;
    } else {
      const newIdx = embeddings.length;
      embeddings.push(vec);
      embeddingMap[song.filename.toLowerCase()] = newIdx;
      song.hasEmbedding = true;
      song.embeddingIndex = newIdx;
    }
    song.contentHash = hash;
    mergeCount++;
  }
  // Second pass: use _path_index to catch songs that share content with an
  // already-matched song (same audio, different filename).  The path index maps
  // filepath → contentHash for EVERY filepath that was ever embedded, even when
  // the embedding entry's own filepath field points to a different file.
  const pathIndex = localEmbeddings._path_index || {};
  let matchByPathIndex = 0;
  for (const s of songs) {
    if (s.hasEmbedding || !s.filePath) continue;
    const hash = pathIndex[s.filePath];
    if (!hash) continue;
    const data = localEmbeddings[hash];
    if (!data) continue;
    const emb = data.embedding;
    if (!emb || typeof emb.length !== 'number' || emb.length === 0) continue;
    if (!Array.isArray(emb) && !ArrayBuffer.isView(emb)) continue;

    // Find the embedding vector — it may already be loaded from the first pass
    // (another song with the same hash was matched). Reuse its embeddingIndex.
    const donor = songsByHash[hash];
    if (donor && donor.hasEmbedding && donor.embeddingIndex != null) {
      // Share the same embedding slot — identical audio content
      s.hasEmbedding = true;
      s.embeddingIndex = donor.embeddingIndex;
      embeddingMap[s.filename.toLowerCase()] = donor.embeddingIndex;
    } else {
      const newIdx = embeddings.length;
      embeddings.push(emb instanceof Float32Array ? emb : new Float32Array(emb));
      embeddingMap[s.filename.toLowerCase()] = newIdx;
      s.hasEmbedding = true;
      s.embeddingIndex = newIdx;
    }
    s.contentHash = hash;
    songsByHash[hash] = songsByHash[hash] || s;
    matchByPathIndex++;
    mergeCount++;
  }

  _embLog('info', `Merge result: ${mergeCount} merged (path:${matchByPath}, hash:${matchByHash}, fallback:${matchByFallback}, pathIndex:${matchByPathIndex}), ${noMatch} unmatched`);
  return mergeCount;
}

// --- Orphan management ---

export function _identifyOrphans() {
  // Orphan = file is gone from device BUT the embedding is still on disk.
  // Songs the user deleted via the menu have their embedding purged in
  // _purgeLocalEmbeddingForSong (and hasEmbedding flipped to false), so they
  // must NOT be classified as orphans. Without the hasEmbedding gate, deleted
  // songs would resurface in the AI page's "Orphaned Embeddings" section
  // forever — which is the bug we're fixing.
  orphanedSongs = songs.filter(s => !s.filePath && s.hasEmbedding);
  if (orphanedSongs.length > 0) {
    _embLog('info', `Found ${orphanedSongs.length} orphaned songs (in embeddings but not on device)`);
  }
}

export function getOrphanedSongs() {
  return orphanedSongs.map(s => ({
    id: s.id,
    filename: s.filename,
    title: s.title,
    artist: s.artist,
    album: s.album,
  }));
}

// --- Missing song detection ---

// Clears filePath on songs whose file is no longer present on the device, and
// removes them from favorites / queue / history / playlists / album-mode state.
// Keeps the `songs[]` entry (with hasEmbedding / embeddingIndex intact) so the
// AI-page Orphaned bucket can surface them for consent-gated embedding removal.
// Returns the list of affected songs ({ id, filename, title, filePath }).
export function _markSongsMissing(missingFilePaths, reason) {
  if (!missingFilePaths || missingFilePaths.size === 0) return [];
  const affected = [];
  for (const s of songs) {
    if (!s.filePath) continue;
    if (!missingFilePaths.has(s.filePath)) continue;
    affected.push({ id: s.id, filename: s.filename, title: s.title, filePath: s.filePath });
    s.filePath = null;
  }
  if (affected.length === 0) return [];

  const affectedIds = new Set(affected.map(a => a.id));
  const affectedFilenames = new Set(affected.map(a => a.filename).filter(Boolean));

  // Mirror deleteSong's state cleanup so orphans disappear from the UI.
  state.queue = state.queue.filter(q => !affectedIds.has(q.id));
  state.history = state.history.filter(hid => !affectedIds.has(hid));
  if (state.historyPos >= state.history.length) state.historyPos = Math.max(0, state.history.length - 1);
  state.listened = state.listened.filter(entry => !affectedIds.has(entry.id));
  state.currentAlbumTracks = (state.currentAlbumTracks || []).filter(id => !affectedIds.has(id));
  if (state.current != null && affectedIds.has(state.current)) state.current = null;

  let favChanged = false;
  for (const id of affectedIds) {
    if (favorites.has(id)) { favorites.delete(id); favChanged = true; }
  }
  if (favChanged) _cbs.saveFavorites().catch(() => {});

  let playlistsChanged = false;
  for (const playlist of playlists) {
    const before = (playlist.songFilenames || []).length;
    playlist.songFilenames = (playlist.songFilenames || []).filter(fn => !affectedFilenames.has(fn));
    if (playlist.songFilenames.length !== before) {
      playlist.updatedAt = Date.now();
      playlistsChanged = true;
    }
  }
  if (playlistsChanged) _cbs.savePlaylists().catch(() => {});

  _invalidatePathMap();
  _cbs.rebuildAlbums();
  _cbs.invalidateForYouCache(reason || 'songs_missing');
  _cbs.invalidateUnexploredClustersCache(reason || 'songs_missing');
  try { _saveSongLibrary(); } catch (e) {}
  _identifyOrphans();

  _activity('library', 'songs_missing_detected', `${affected.length} song file(s) no longer on device`, {
    count: affected.length,
    reason: reason || 'unknown',
    filenames: affected.slice(0, 10).map(a => a.filename),
  }, { important: true, tags: ['library'] });
  _embLog('info', `Marked ${affected.length} songs as missing (reason=${reason || 'unknown'})`);
  return affected;
}

export async function markSongMissingByPath(filePath) {
  if (!filePath) return { ok: false, error: 'No file path' };
  const affected = _markSongsMissing(new Set([filePath]), 'play_error_file_missing');
  return { ok: true, removed: affected.length };
}

// The cache-skip startup path avoids the full MediaStore scan for perf, which
// also skips deletion detection. Run a lightweight background scan after the
// cache-skip startup is done so externally deleted files still get reconciled.
// Fire-and-forget: failures fall back to the next real scan.
let _deferredDeletionCheckRan = false;
export function _scheduleDeferredDeletionCheck() {
  if (_deferredDeletionCheckRan) return;
  _deferredDeletionCheckRan = true;
  setTimeout(async () => {
    try {
      const result = await MusicBridge.scanAudioFiles();
      const scanned = (result && result.songs) || [];
      const scannedPaths = new Set();
      for (const s of scanned) { if (s && s.path) scannedPaths.add(s.path); }
      if (scannedPaths.size === 0) return; // anomaly — skip
      const missing = new Set();
      for (const s of songs) {
        if (s.filePath && !scannedPaths.has(s.filePath)) missing.add(s.filePath);
      }
      if (missing.size === 0) return;
      const affected = _markSongsMissing(missing, 'deferred_scan_external_deletion');
      if (affected.length > 0) {
        _fireSongLibraryChanged({ reason: 'external_deletion', affected: affected.length });
      }
    } catch (e) {
      console.warn('[SCAN] Deferred deletion check failed:', e && e.message || e);
    }
  }, 3000);
}

// Fires when the library shape changes outside of the normal scan callback
// (e.g. deferred deletion detection, play-time file-missing marking,
// user-initiated deleteSong, removeOrphanedEmbeddings) so the UI can re-render.
export function onSongLibraryChanged(cb) { _songLibraryChangedCbs.push(cb); }

function _fireSongLibraryChanged(payload) {
  for (const cb of _songLibraryChangedCbs) {
    try { cb(payload || {}); } catch (e) { /* ignore */ }
  }
}

// --- Orphaned embedding removal ---

export function removeOrphanedEmbeddings() {
  if (orphanedSongs.length === 0) return 0;
  if (embeddingInProgress) {
    _embLog('info', 'Cannot remove orphans while embedding is in progress');
    return 0;
  }

  const orphanIds = new Set(orphanedSongs.map(s => s.id));
  const removed = orphanedSongs.length;

  // Build old→new ID mapping BEFORE filtering
  const oldToNew = new Map();
  let newIdx = 0;
  for (const s of songs) {
    if (!orphanIds.has(s.id)) {
      oldToNew.set(s.id, newIdx);
      newIdx++;
    }
  }

  // Remove from songs array
  setSongs(songs.filter(s => !orphanIds.has(s.id)));

  // Re-assign IDs to be sequential
  for (let i = 0; i < songs.length; i++) {
    songs[i].id = i;
  }

  // Remap favorites to new IDs
  const newFavorites = new Set();
  for (const oldId of favorites) {
    const nid = oldToNew.get(oldId);
    if (nid != null) newFavorites.add(nid);
  }
  setFavorites(newFavorites);

  // Remap all state references to new IDs
  if (state.current != null) {
    state.current = oldToNew.get(state.current) ?? null;
  }
  state.history = state.history
    .map(id => oldToNew.get(id))
    .filter(id => id != null);
  state.historyPos = Math.min(state.historyPos, Math.max(0, state.history.length - 1));
  state.queue = state.queue
    .map(q => { const nid = oldToNew.get(q.id); return nid != null ? { ...q, id: nid } : null; })
    .filter(Boolean);
  state.listened = state.listened
    .map(entry => { const nid = oldToNew.get(entry.id); return nid != null ? { ...entry, id: nid } : null; })
    .filter(Boolean);
  if (state.currentAlbumTracks) {
    state.currentAlbumTracks = state.currentAlbumTracks
      .map(id => oldToNew.get(id))
      .filter(id => id != null);
  }

  // Remove orphaned entries from local_embeddings
  const pathIndex = localEmbeddings._path_index || {};
  for (const s of orphanedSongs) {
    if (s.contentHash) {
      delete localEmbeddings[s.contentHash];
    }
    for (const [path, hash] of Object.entries(pathIndex)) {
      if (!songs.some(song => song.filePath === path)) {
        delete localEmbeddings[hash];
        delete pathIndex[path];
      }
    }
  }

  // Compact embeddings[] — remove orphan vectors and remap indices
  const usedEmbIdxSet = new Set();
  for (const s of songs) {
    if (s.embeddingIndex != null) usedEmbIdxSet.add(s.embeddingIndex);
  }
  const newEmbeddings = [];
  const embOldToNew = new Map();
  for (let i = 0; i < embeddings.length; i++) {
    if (usedEmbIdxSet.has(i)) {
      embOldToNew.set(i, newEmbeddings.length);
      newEmbeddings.push(embeddings[i]);
    }
  }
  embeddings.length = 0;
  for (const e of newEmbeddings) embeddings.push(e);
  // Remap embeddingIndex on all songs and embeddingMap
  for (const s of songs) {
    if (s.embeddingIndex != null) {
      s.embeddingIndex = embOldToNew.get(s.embeddingIndex) ?? null;
      if (s.embeddingIndex == null) s.hasEmbedding = false;
    }
  }
  setEmbeddingMap({});
  for (const s of songs) {
    if (s.embeddingIndex != null) {
      embeddingMap[s.filename.toLowerCase()] = s.embeddingIndex;
    }
  }
  orphanedSongs = [];

  // Rebuild everything with clean, compacted data
  _cbs.rebuildAlbums();
  _cbs.invalidateArtistMap();
  _invalidateFilenameMap();
  _invalidatePathMap();
  _invalidateEmbIdxMap();
  if (embeddings.length > 0) {
    setRec(new Recommender(embeddings)); rec.lam = _tuning.adventurous;
    attachGpuToRec(rec, embeddings);
  }
  buildProfileVec().then(v => { setProfileVec(v); }).catch(() => {});
  _saveLocalEmbeddings();
  _cbs.saveFavorites();
  _cbs.savePlaybackState();

  _embLog('info', `Removed ${removed} orphaned embeddings`);
  // Library shape changed (songs[] was filtered + IDs reassigned). Notify
  // subscribers so the Songs/Albums tabs and AI page re-render off the new
  // ID space rather than holding references to deleted ids.
  _fireSongLibraryChanged({ reason: 'orphans_removed', affected: removed });
  return removed;
}

// ===== Manually removed embeddings (ISSUE-6) =====
// Songs the user explicitly removed from the embedding set — keyed by filename.
// Auto-embedding skips these; they appear in a "Manually Removed" list in the UI.

export async function _loadRemovedEmbeddingKeys() {
  try {
    const { value } = await Preferences.get({ key: REMOVED_EMB_PREF_KEY });
    if (value) {
      const arr = JSON.parse(value);
      if (Array.isArray(arr)) removedEmbeddingKeys = new Set(arr);
    }
  } catch (e) { /* ignore */ }
}

async function _saveRemovedEmbeddingKeys() {
  try {
    await Preferences.set({ key: REMOVED_EMB_PREF_KEY, value: JSON.stringify(Array.from(removedEmbeddingKeys)) });
  } catch (e) { /* ignore */ }
}

export function _applyRemovedEmbeddingFlags() {
  if (removedEmbeddingKeys.size === 0) return;
  for (const s of songs) {
    if (removedEmbeddingKeys.has(s.filename)) {
      s.hasEmbedding = false;
      s.embeddingIndex = null;
    }
  }
}

export function removeSongEmbedding(songId) {
  const s = songs[songId];
  if (!s) return false;
  removedEmbeddingKeys.add(s.filename);
  s.hasEmbedding = false;
  s.embeddingIndex = null;
  _saveRemovedEmbeddingKeys();
  _embLog('info', `User removed embedding for: ${s.filename}`);
  _activity('library', 'embedding_removed', `Removed embedding for "${s.title}"`, {
    ..._songRef(songId),
  }, { important: true, tags: ['embedding', 'library'] });
  buildProfileVec().then(v => { setProfileVec(v); }).catch(() => {});
  return true;
}

export function removeAlbumEmbeddings(albumName) {
  let count = 0;
  for (const s of songs) {
    if (s.album === albumName && s.hasEmbedding) {
      removedEmbeddingKeys.add(s.filename);
      s.hasEmbedding = false;
      s.embeddingIndex = null;
      count++;
    }
  }
  if (count > 0) {
    _saveRemovedEmbeddingKeys();
    _embLog('info', `User removed ${count} embeddings for album: ${albumName}`);
    _activity('library', 'album_embeddings_removed', `Removed embeddings for album "${albumName}"`, {
      album: albumName,
      count,
    }, { important: true, tags: ['embedding', 'library'] });
    buildProfileVec().then(v => { setProfileVec(v); }).catch(() => {});
  }
  return count;
}

export function readdSongEmbedding(songId) {
  const s = songs[songId];
  if (!s) return false;
  if (!s.filePath) return false;
  removedEmbeddingKeys.delete(s.filename);
  _saveRemovedEmbeddingKeys();
  _embLog('info', `User re-added to embedding queue: ${s.filename}`);
  _activity('library', 'embedding_readded', `Re-added embedding for "${s.title}"`, {
    ..._songRef(songId),
  }, { important: true, tags: ['embedding', 'library'] });
  // Either start a new batch immediately, or defer to run after the current
  // batch finishes. The previous code silently dropped the request when
  // embedding was in progress — the song would disappear from the "Embed
  // Removed Songs" list with no actual embedding ever queued.
  if (embeddingInProgress) {
    _deferredReembedIds.add(songId);
    _embLog('info', `Deferred re-embed for "${s.filename}" until current batch finishes`);
  } else {
    _startEmbedding([s]);
  }
  return true;
}

export function getRemovedEmbeddingSongs() {
  const result = [];
  for (const s of songs) {
    if (s.filePath && removedEmbeddingKeys.has(s.filename)) {
      result.push({ id: s.id, filename: s.filename, title: s.title, artist: s.artist, album: s.album });
    }
  }
  return result;
}

function _purgeLocalEmbeddingForSong(songId) {
  const s = songs[songId];
  if (!s) return;

  const pathIndex = localEmbeddings._path_index || {};
  const candidateHashes = new Set();
  let changed = false;

  if (s.filePath && pathIndex[s.filePath]) {
    candidateHashes.add(pathIndex[s.filePath]);
    delete pathIndex[s.filePath];
    changed = true;
  }
  if (s.contentHash) candidateHashes.add(s.contentHash);

  for (const [hash, data] of Object.entries(localEmbeddings)) {
    if (hash === '_path_index' || !data || typeof data !== 'object') continue;
    if ((s.filePath && data.filepath === s.filePath) ||
        (s.filename && data.filename && String(data.filename).toLowerCase() === s.filename.toLowerCase())) {
      candidateHashes.add(hash);
    }
  }

  const otherLiveHashes = new Set(
    songs
      .filter(other => other && other.id !== songId && other.filePath && other.contentHash)
      .map(other => other.contentHash)
  );

  for (const hash of candidateHashes) {
    if (!hash || otherLiveHashes.has(hash)) continue;
    const stillReferenced = Object.values(pathIndex).some(v => v === hash);
    if (!stillReferenced && localEmbeddings[hash]) {
      delete localEmbeddings[hash];
      changed = true;
    }
  }

  if (changed) {
    localEmbeddings._path_index = pathIndex;
    _saveLocalEmbeddings();
  }
}

export async function deleteSong(songId) {
  const s = songs[songId];
  if (!s) return { ok: false, error: 'Song not found' };
  if (!s.filePath) return { ok: false, error: 'No file path' };

  try {
    await MusicBridge.deleteAudioFile({ path: s.filePath });
  } catch (e) {
    return { ok: false, error: (e && e.message) || String(e) };
  }

  _purgeLocalEmbeddingForSong(songId);

  // Drop from library. Keep `songs[]` indices stable by marking filePath null
  // so existing queue/history ids keep resolving to metadata.
  s.filePath = null;
  s.hasEmbedding = false;
  s.embeddingIndex = null;
  s.contentHash = null;
  if (s.filename) {
    // Guard against Android scoped-storage MediaStore-row persistence: even when
    // File.delete() + resolver.delete() both return success, a zombie MediaStore
    // row can resurface the file on the next scan. Keeping the filename in the
    // removed set ensures a resurrected entry lands under "Embed Removed Songs"
    // (user-visible, opt-in re-embed) instead of "Embed New Songs".
    removedEmbeddingKeys.add(s.filename);
    let playlistsChanged = false;
    for (const playlist of playlists) {
      const before = (playlist.songFilenames || []).length;
      playlist.songFilenames = (playlist.songFilenames || []).filter(fn => fn !== s.filename);
      if (playlist.songFilenames.length !== before) {
        playlist.updatedAt = Date.now();
        playlistsChanged = true;
      }
    }
    if (playlistsChanged) _cbs.savePlaylists().catch(() => {});
  }
  _saveRemovedEmbeddingKeys();

  // Remove from queue, history, favorites.
  state.queue = state.queue.filter(q => q.id !== songId);
  state.history = state.history.filter(hid => hid !== songId);
  state.listened = state.listened.filter(entry => entry.id !== songId);
  state.currentAlbumTracks = (state.currentAlbumTracks || []).filter(id => id !== songId);
  if (state.historyPos >= state.history.length) state.historyPos = state.history.length - 1;
  if (state.current === songId) state.current = null;
  if (favorites.has(songId)) {
    favorites.delete(songId);
    _cbs.saveFavorites().catch(() => {});
  }

  _invalidatePathMap();
  _cbs.rebuildAlbums();
  try { setProfileVec(await buildProfileVec()); } catch (e) { /* ignore */ }
  _cbs.invalidateForYouCache('song_deleted');
  _cbs.invalidateUnexploredClustersCache('song_deleted');
  try { _saveSongLibrary(); } catch (e) {}
  // Refresh the orphaned-songs list so the AI page's "Orphaned Embeddings"
  // section drops this song immediately. Without this, the array keeps the
  // pre-deletion snapshot and the song shows up under orphans even though
  // its embedding was just purged.
  _identifyOrphans();
  _embLog('info', `User deleted song: ${s.filename}`);
  _activity('library', 'song_deleted', `Deleted "${s.title}" from library`, {
    ..._songRef(songId),
  }, { important: true, tags: ['library'] });
  // Notify subscribed UI (Songs tab list, AI page, etc.) that the library
  // shape changed. Same channel as the deferred-deletion-detection path.
  _fireSongLibraryChanged({ reason: 'user_delete', affected: 1 });
  return { ok: true };
}

export async function removeUnmatchedEmbeddings() {
  if (unmatchedEmbeddings.length === 0) return 0;
  const removed = unmatchedEmbeddings.length;
  const unmatchedKeys = new Set(unmatchedEmbeddings.map(u => u.key).filter(Boolean));
  const unmatchedFilenames = new Set(unmatchedEmbeddings.map(u => (u.filename || '').toLowerCase()));
  const unmatchedPaths = new Set(unmatchedEmbeddings.map(u => u.filepath).filter(Boolean));
  const pathIndex = localEmbeddings._path_index || {};

  for (const [hash, data] of Object.entries(localEmbeddings)) {
    if (hash === '_path_index' || !data || typeof data !== 'object') continue;
    const fn = (data.filename || '').toLowerCase();
    const fp = data.filepath || '';
    if (unmatchedKeys.has(hash) || unmatchedFilenames.has(fn) || unmatchedPaths.has(fp)) {
      delete localEmbeddings[hash];
      if (fp && pathIndex[fp]) delete pathIndex[fp];
    }
  }

  localEmbeddings._path_index = pathIndex;
  unmatchedEmbeddings = [];
  // Await the atomic binary-store rewrite (tmp + rename in embedding-cache.js)
  // so the next app cold start doesn't re-load the deleted entries.
  try { await EmbeddingCache.saveToDisk(localEmbeddings); } catch (e) {
    _embLog('error', `Failed to persist unmatched removal: ${e && e.message || e}`);
  }
  _activity('embedding', 'embedding_unmatched_removed', `Removed ${removed} unmatched embeddings`, {
    removed,
  }, { important: true, tags: ['embedding', 'cleanup'] });
  _embLog('info', `Removed ${removed} unmatched embeddings`);
  return removed;
}

// --- Embedding pipeline ---

export async function _triggerAutoEmbedding() {
  // Load removed-by-user set if not already loaded
  if (removedEmbeddingKeys.size === 0) {
    await _loadRemovedEmbeddingKeys();
    _applyRemovedEmbeddingFlags();
  }

  const unembedded = songs.filter(s =>
    !s.hasEmbedding && s.filePath && !removedEmbeddingKeys.has(s.filename)
  );
  if (unembedded.length === 0) {
    _embLog('info', 'All songs already have embeddings (or marked as removed by user)');
    return;
  }

  // Check if user previously paused embedding — don't auto-restart
  if (!embeddingPausedByUser) {
    try {
      const { value } = await Preferences.get({ key: 'embedding_paused' });
      if (value === 'true') embeddingPausedByUser = true;
    } catch (e) { /* ignore */ }
  }
  if (embeddingPausedByUser) {
    _embLog('info', `${unembedded.length} songs without embeddings, but embedding paused by user — use retry to resume`);
    return;
  }

  _embLog('info', `Found ${unembedded.length} songs without embeddings — starting on-device embedding`);
  _startEmbedding(unembedded);
}

function _startEmbedding(songList) {
  if (embeddingInProgress) {
    _embLog('info', 'Embedding already in progress — ignoring new request');
    return;
  }
  embeddingInProgress = true;
  embeddingStartTime = Date.now();
  embeddingTotalCount = songList.length;
  embeddingProcessedCount = 0;
  embeddingReportedFailedCount = 0;
  embeddingFailedPaths = [];
  embeddingQueue = songList.map(s => ({ id: s.id, path: s.filePath, filename: s.filename }));
  _lastCompletedBatchSig = null;
  const paths = songList.map(s => s.filePath);
  _setupEmbeddingListeners();
  _startEmbeddingStatusWatchdog();
  MusicBridge.embedNewSongs({ paths }).then(result => {
    _embLog('info', `Embedding call returned: ${JSON.stringify(result)}`);
  }).catch(e => {
    _embLog('error', `Embedding failed to start: ${e.message || e}`);
    embeddingInProgress = false;
    _stopEmbeddingStatusWatchdog();
  });
}

export async function reembedAll() {
  if (embeddingInProgress) {
    _embLog('info', 'Stopping current embedding before re-embed all...');
    await stopEmbedding();
    // Wait briefly for native side to finish
    await new Promise(r => setTimeout(r, 500));
    embeddingInProgress = false;
  }
  const playable = songs.filter(s => s.filePath);
  if (playable.length === 0) {
    _embLog('error', 'No songs with file paths found');
    return;
  }
  _embLog('info', `Re-embedding ALL ${playable.length} songs on device`);
  embeddingPausedByUser = false;
  Preferences.remove({ key: 'embedding_paused' }).catch(() => {});
  _startEmbedding(playable);
}

export async function stopEmbedding() {
  if (!embeddingInProgress) return;
  _embLog('info', 'Stopping embedding (user requested)...');
  embeddingPausedByUser = true;
  try {
    await Preferences.set({ key: 'embedding_paused', value: 'true' });
  } catch (e) { /* ignore */ }
  try {
    await MusicBridge.stopEmbedding();
  } catch (e) {
    _embLog('error', `Stop failed: ${e.message || e}`);
  }
  // The embeddingComplete event will fire from native side with current counts
}

export function retryEmbedding() {
  _embLog('info', 'Manual retry triggered');
  // Clear the paused flag — user wants to resume
  embeddingPausedByUser = false;
  Preferences.remove({ key: 'embedding_paused' }).catch(() => {});
  // Include unembedded songs + previously failed songs, but EXCLUDE manually-removed
  // (those are embedded explicitly via embedRemovedSongsBatch / readdSongEmbedding)
  const unembedded = songs.filter(s => !s.hasEmbedding && s.filePath && !removedEmbeddingKeys.has(s.filename));
  const failedSet = new Set(embeddingFailedPaths);
  const failedSongs = songs.filter(s => s.filePath && failedSet.has(s.filePath) && !removedEmbeddingKeys.has(s.filename));
  // Combine, dedup by path
  const seen = new Set();
  const toEmbed = [];
  for (const s of [...unembedded, ...failedSongs]) {
    if (!seen.has(s.filePath)) {
      seen.add(s.filePath);
      toEmbed.push(s);
    }
  }
  if (toEmbed.length === 0) {
    _embLog('info', 'No songs to retry');
    return;
  }
  _embLog('info', `Retrying: ${unembedded.length} unembedded + ${failedSongs.length} previously failed = ${toEmbed.length} songs`);
  _startEmbedding(toEmbed);
}

// Batch re-add: clears the removed flag for all manually-removed songs and embeds them.
export function embedRemovedSongsBatch() {
  const toEmbed = [];
  for (const s of songs) {
    if (removedEmbeddingKeys.has(s.filename) && s.filePath) {
      removedEmbeddingKeys.delete(s.filename);
      toEmbed.push(s);
    }
  }
  if (toEmbed.length === 0) {
    _embLog('info', 'No manually-removed songs to re-add');
    return 0;
  }
  _saveRemovedEmbeddingKeys();
  embeddingPausedByUser = false;
  Preferences.remove({ key: 'embedding_paused' }).catch(() => {});
  // If a batch is already running, defer all re-embeds until it finishes.
  // Without this, the songs would be removed from the "removed" list (because
  // we already mutated removedEmbeddingKeys above) but _startEmbedding would
  // refuse and the user's request would be silently lost.
  if (embeddingInProgress) {
    for (const s of toEmbed) _deferredReembedIds.add(s.id);
    _embLog('info', `Deferred re-embed of ${toEmbed.length} songs until current batch finishes`);
  } else {
    _embLog('info', `Re-adding ${toEmbed.length} manually-removed songs to embedding queue`);
    _startEmbedding(toEmbed);
  }
  return toEmbed.length;
}

// --- Batch completion ---

export async function _runPostBatchMerge(data, source) {
  const processed = Number(data && data.processed) || 0;
  const failed = Number(data && data.failed) || 0;
  const sig = `${processed}:${failed}:${embeddingStartTime || 0}`;
  if (_lastCompletedBatchSig === sig) {
    return { deduped: true };
  }
  _lastCompletedBatchSig = sig;
  _stopEmbeddingStatusWatchdog();

  const elapsed = embeddingStartTime ? Math.round((Date.now() - embeddingStartTime) / 1000) : 0;
  const tag = source === 'native_event' ? '' : ` [via ${source}]`;
  _embLog('info', `Embedding complete: ${processed} processed, ${failed} failed (${elapsed}s total)${tag}`);
  embeddingInProgress = false;
  embeddingQueue = [];
  embeddingCurrentFile = '';
  embeddingProcessedCount = processed;
  embeddingReportedFailedCount = failed;
  embeddingTotalCount = processed + failed;

  // Reload local embeddings from disk (Java is the sole writer during embedding —
  // it writes incrementally every 5 songs + final write. Don't write from JS here
  // to avoid a race condition where both sides write different data simultaneously.)
  try {
    await EmbeddingCache.recoverPendingToStable();
    await _loadLocalEmbeddings();
    const mergedCount = _mergeLocalEmbeddings();
    _embLog('info', `Merged ${mergedCount} embeddings from disk after batch completion`);
  } catch (e) {
    _embLog('error', `Failed to promote pending embeddings: ${e.message}`);
  }
  _saveSongLibrary();

  const playable = songs.filter(s => s.filePath);
  const embedded = playable.filter(s => s.hasEmbedding);
  if (playable.length > 0 && embedded.length === playable.length) {
    _embLog('info', `All ${playable.length} songs have embeddings — AI recommendations ready!`);
  }

  if (embeddings.length > 0) {
    setRec(new Recommender(embeddings));
    rec.lam = _tuning.adventurous;
    attachGpuToRec(rec, embeddings);
    _cbs.invalidateArtistMap();
    _invalidateEmbIdxMap();
  }
  buildProfileVec().then(v => { setProfileVec(v); }).catch(() => {});
  for (const cb of scanCallbacks) {
    try { cb(); } catch (e) { /* ignore */ }
  }

  // Drain any re-embed requests that arrived while this batch was running.
  // Schedule on a fresh tick so the current batch's cleanup finishes first.
  if (_deferredReembedIds.size > 0) {
    const ids = Array.from(_deferredReembedIds);
    _deferredReembedIds = new Set();
    const deferredSongs = [];
    for (const id of ids) {
      const s = songs[id];
      if (s && s.filePath) deferredSongs.push(s);
    }
    if (deferredSongs.length > 0) {
      _embLog('info', `Starting deferred re-embed of ${deferredSongs.length} songs queued during previous batch`);
      setTimeout(() => {
        try { _startEmbedding(deferredSongs); } catch (e) { /* ignore */ }
      }, 100);
    }
  }
  return { deduped: false };
}

// --- Status watchdog ---

function _startEmbeddingStatusWatchdog() {
  if (_embeddingStatusWatchdogId != null) return;
  if (typeof MusicBridge.requestEmbeddingStatus !== 'function') return;
  _embeddingStatusWatchdogId = setInterval(() => {
    if (!embeddingInProgress) {
      _stopEmbeddingStatusWatchdog();
      return;
    }
    try {
      MusicBridge.requestEmbeddingStatus().catch(() => {});
    } catch (e) { /* ignore */ }
  }, EMBEDDING_STATUS_POLL_MS);
}

function _stopEmbeddingStatusWatchdog() {
  if (_embeddingStatusWatchdogId != null) {
    clearInterval(_embeddingStatusWatchdogId);
    _embeddingStatusWatchdogId = null;
  }
}

// --- Native event listeners ---

export function _setupEmbeddingListeners() {
  if (_embeddingListenersSet) return;
  _embeddingListenersSet = true;

  // Per-song completion event from native. The native side now writes the
  // actual embedding directly to pending_embeddings.json (memory-savings
  // change — see project_development.md). JS only updates lightweight
  // bookkeeping here; the in-memory embeddings[] array, song.hasEmbedding /
  // song.embeddingIndex flags, and sibling matching are all reconciled in
  // bulk by _runPostBatchMerge() at the end of the batch via
  // EmbeddingCache.recoverPendingToStable() + _loadLocalEmbeddings() +
  // _mergeLocalEmbeddings().
  MusicBridge.addListener('embeddingSongComplete', (data) => {
    const { filename, filepath, contentHash } = data;
    embeddingProcessedCount = Math.max(embeddingProcessedCount, Number(data.processed) || 0);
    embeddingReportedFailedCount = Math.max(embeddingReportedFailedCount, Number(data.failed) || 0);
    _embLog('success', `Embedded: ${filename}`);
    embeddingQueue = embeddingQueue.filter(q => q.path !== filepath);
    if (contentHash && filepath) {
      if (!localEmbeddings._path_index) localEmbeddings._path_index = {};
      localEmbeddings._path_index[filepath] = contentHash;
    }
  });

  MusicBridge.addListener('embeddingComplete', async (data) => {
    await _runPostBatchMerge(data, 'native_event');
  });

  MusicBridge.addListener('embeddingProgress', (data) => {
    embeddingInProgress = true;
    embeddingCurrentFile = data.filename || '';
    embeddingTotalCount = Number(data.total) || embeddingTotalCount;
    embeddingProcessedCount = Number(data.processed) || embeddingProcessedCount;
    embeddingReportedFailedCount = Number(data.failed) || embeddingReportedFailedCount;
    if (!embeddingStartTime) embeddingStartTime = Date.now();
    _embLog('progress', `Processing ${data.current}/${data.total}: ${data.filename}`);
  });

  MusicBridge.addListener('embeddingError', (data) => {
    const msg = data.error || 'Unknown error';
    embeddingReportedFailedCount = Math.max(embeddingReportedFailedCount, Number(data.failed) || 0);
    _embLog('error', msg);
    // Track failed song paths for retry
    if (data.filepath) {
      embeddingFailedPaths.push(data.filepath);
    }
    // Only mark as stopped if it's a fatal init error, not a per-song failure
    if (msg.includes('initialize') || msg.includes('ONNX Runtime')) {
      embeddingInProgress = false;
      embeddingCurrentFile = '';
    }
  });
}

/**
 * Reload embeddings from the on-disk binary store (local_embeddings.bin +
 * local_embeddings_meta.json) and rebuild the recommender. Used by the
 * Settings page after the user uploads pre-computed embeddings from a laptop.
 *
 * Reads from the same data dir the embedding pipeline writes to, so any
 * external producer that follows the same format works (e.g. a Colab notebook
 * exporting matching files).
 *
 * Returns { merged, total, embeddings } so the caller can show feedback.
 */
export async function reloadEmbeddingsFromDisk() {
  if (embeddingInProgress) {
    throw new Error('Cannot reload while embedding is in progress');
  }
  await _loadLocalEmbeddings();
  const mergedCount = _mergeLocalEmbeddings();
  _embLog('info', `Reload from disk: merged ${mergedCount} embeddings into ${songs.length} songs`);
  if (mergedCount > 0 && embeddings.length > 0) {
    setRec(new Recommender(embeddings));
    rec.lam = _tuning.adventurous;
    attachGpuToRec(rec, embeddings);
    _cbs.invalidateArtistMap();
    _invalidateEmbIdxMap();
    _markEmbeddingsReady();
    try { setProfileVec(await buildProfileVec()); } catch (e) { /* ignore */ }
    _saveSongLibrary();
    _activity('library', 'embeddings_uploaded', `Loaded ${mergedCount} embeddings from upload`, {
      merged: mergedCount,
      total: songs.length,
    }, { important: true, tags: ['embedding', 'library'] });
    // Library shape effectively changed (hasEmbedding flipped on many songs);
    // notify subscribers so the AI page and Songs/Albums tabs refresh.
    _fireSongLibraryChanged({ reason: 'embeddings_reloaded', affected: mergedCount });
  }
  return { merged: mergedCount, total: songs.length, embeddings: embeddings.length };
}

// --- Embedding status ---

export function getEmbeddingStatus() {
  const localCount = Object.keys(localEmbeddings).filter(k => k !== '_path_index').length;
  const playable = songs.filter(s => s.filePath);
  const totalEmbedded = playable.filter(s => s.hasEmbedding).length;
  const unembedded = playable.filter(s => !s.hasEmbedding).length;
  const failedCount = Math.max(embeddingFailedPaths.length, embeddingReportedFailedCount);
  const queueSize = embeddingInProgress
    ? Math.max(0, (embeddingTotalCount || embeddingQueue.length) - embeddingProcessedCount - failedCount)
    : embeddingQueue.length;
  return {
    inProgress: embeddingInProgress,
    paused: embeddingPausedByUser,
    queueSize,
    localCount,
    totalEmbedded,
    totalSongs: playable.length,
    unembedded,
    allDone: playable.length > 0 && unembedded === 0 && failedCount === 0,
    currentFile: embeddingCurrentFile,
    log: embeddingLog,
    startTime: embeddingStartTime,
    orphanCount: orphanedSongs.length,
    failedCount,
    unmatchedEmbeddings,
  };
}

// --- Native similarity search ---

export async function _nativeRecommendFromQueryVec(queryVec, limit, excludeEmbIdx = new Set(), opts = {}) {
  if (!queryVec || typeof MusicBridge.findNearestEmbeddings !== 'function') return [];
  try {
    const topK = Math.max(limit || TOP_N, (limit || TOP_N) + (excludeEmbIdx ? excludeEmbIdx.size : 0) + 20);
    const result = await MusicBridge.findNearestEmbeddings({
      queryVector: Array.from(queryVec),
      topK,
      excludeFilepaths: _embExcludeToFilepaths(excludeEmbIdx),
    });
    return _nativeNearestResultsToSongItems(result && result.results, { ...opts, limit });
  } catch (e) {
    _embLog('info', `Native similarity unavailable, using JS fallback: ${e && e.message || e}`);
    return [];
  }
}
