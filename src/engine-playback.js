import { Preferences } from '@capacitor/preferences';
import { SessionLogger } from './logger.js';
import { flushActivityLog } from './activity-log.js';
import { MusicBridge } from './music-bridge.js';

import {
  PENDING_LISTEN_KEY, SIMILARITY_BOOST_KEY, TASTE_REVIEW_IGNORE_KEY,
  LISTEN_SKIP_THRESHOLD, NEUTRAL_SKIP_CAPTURE_THRESHOLD,
  songs, favorites, recToggle, log, state,
  _setRecToggle, _setQueueShuffleEnabled,
  setCurrentPlaybackInstanceId, setCapturedPlaybackInstanceId,
  setLastLoggedPlaybackStartInstanceId, setLastLoggedPlaybackStartSongId,
  _lastLoggedPlaybackStartInstanceId, _lastLoggedPlaybackStartSongId,
  _recommendationRebuildTimer, setRecommendationRebuildTimer,
  setRecommendationRebuildInFlight, setRecommendationRebuildPending,
  setRecommendationRebuildReason, setRecommendationRebuildOpts,
  setFavorites, setNegativeScores, setSimilarityBoostScores,
  setDislikedFilenames, setTasteReviewIgnores, setLastTasteReviewSnapshot,
  setRecentPlaybackSignalEvents, setProfileVec, setRecommendationPolicySnapshot, setLog,
  EXTERNAL_DATA_DIR,
} from './engine-state.js';
import { _getFilenameMap } from './engine-indexes.js';
import { _copySessionEntry, _activity, _songRef, _ensurePlaybackStartLogged } from './engine-taste.js';
import { _resolveSongIdFromNativePayload, _getPlaylistById } from './engine-data.js';

let _pcbs = {};
export function initPlaybackCallbacks(cbs) { _pcbs = cbs; }

export function _filenameToId(fn) {
  if (!fn) return null;
  const fnMap = _getFilenameMap();
  const sid = fnMap[fn.toLowerCase()];
  return sid != null ? sid : null;
}

export async function savePlaybackState(currentTimeSec, durationSec) {
  try {
    // Save by filename for cross-session stability (IDs can shift)
    const currentFn = state.current != null && songs[state.current] ? songs[state.current].filename : null;
    const currentSong = state.current != null ? songs[state.current] : null;
    const data = {
      currentFilename: currentFn,
      currentFilePath: currentSong ? currentSong.filePath : null,
      currentTitle: currentSong ? currentSong.title : '',
      currentArtist: currentSong ? currentSong.artist : '',
      currentAlbum: currentSong ? currentSong.album : '',
      currentSource: state.currentSource,
      historyFilenames: state.history.map(id => songs[id] ? songs[id].filename : null).filter(Boolean),
      historyPos: state.historyPos,
      queueFilenames: state.queue.map(q => songs[q.id] ? { filename: songs[q.id].filename, similarity: q.similarity } : null).filter(Boolean),
      // Cap persisted state.listened to the most recent N entries. The runtime
      // array grows unbounded (one push per play/skip; reset only via
      // surprise()) and on a heavy-use account it can reach 1000+ entries.
      // Persisting that bloats `playback_state` to 500KB–1MB which makes the
      // cold-start `Preferences.get` + JSON.parse + filename-mapping pass on
      // restorePlaybackState a multi-second JS-thread block (observed:
      // 372ms baseline -> 3610ms on a heavy account on 2026-05-11). Capping
      // to 200 keeps sessionVec ramp-up working (it saturates well before
      // that) AND keeps recommendations diverse — a huge listened list
      // would otherwise make sessionVec average the centroid of every song
      // the user has ever played, clustering recs around that centroid.
      listenedFilenames: state.listened.slice(-200).map(entry => {
        if (!songs[entry.id]) return null;
        const copy = _copySessionEntry(entry);
        return {
          filename: songs[entry.id].filename,
          listen_fraction: copy.listen_fraction,
          encounters: copy.encounters,
          totalFraction: copy.totalFraction,
          skips: copy.skips,
          plays: copy.plays,
          source: copy.source,
        };
      }).filter(Boolean),
      sessionLabel: state.sessionLabel,
      playingFavorites: state.playingFavorites,
      playingAlbum: state.playingAlbum,
      playingPlaylist: state.playingPlaylist,
      currentPlaylistId: state.currentPlaylistId,
      albumTrackFilenames: (state.currentAlbumTracks || []).map(id => songs[id] ? songs[id].filename : null).filter(Boolean),
      timelineMode: state.timelineMode,
      timelineIndex: state.timelineIndex,
      timelineFilenames: (state.timelineItems || []).map(item => {
        const s = songs[item.id];
        return s ? { filename: s.filename, similarity: item.similarity, manual: !!item.manual } : null;
      }).filter(Boolean),
      explicitPlayedFilenames: (state.explicitPlayedIds || []).map(id => songs[id] ? songs[id].filename : null).filter(Boolean),
      recToggle,
      currentTime: currentTimeSec != null ? currentTimeSec : 0,
      duration: durationSec || 0,
      loggedPlaybackStartInstanceId: _lastLoggedPlaybackStartInstanceId || 0,
      loggedPlaybackStartFilename: _lastLoggedPlaybackStartSongId != null && songs[_lastLoggedPlaybackStartSongId]
        ? songs[_lastLoggedPlaybackStartSongId].filename
        : null,
    };
    // 2026-05-11 #6: split persistence into two Preferences keys.
    //   - `playback_state_current` (tiny): just the play-tap-critical info.
    //     Read first on cold start so the user's queued resume tap can fire
    //     within ~100ms instead of waiting on the full read.
    //   - `playback_state` (full): unchanged. Read in parallel; result
    //     populates history/queue/listened/timeline once it lands.
    // The captured logs.txt from v2026.05.11.5 showed Preferences.get(playback_state)
    // taking 2233ms for a 10KB payload with bin fetch NOT in flight. Splitting
    // the critical fields off a separate small key bypasses that latency for
    // the play-tap path without changing the rest of the restore semantics.
    const tinyData = {
      currentFilename: data.currentFilename,
      currentFilePath: data.currentFilePath,
      currentTitle: data.currentTitle,
      currentArtist: data.currentArtist,
      currentAlbum: data.currentAlbum,
      currentTime: data.currentTime,
      duration: data.duration,
      currentSource: data.currentSource,
    };
    const tinyJson = JSON.stringify(tinyData);
    // 2026-05-11 #7: ALSO write the tiny critical state to a plain JSON file
    // in the app data dir. The captured logs proved Capacitor's Preferences
    // plugin is ~2.3s/call regardless of payload size — payload doesn't
    // matter, the plugin executor itself is slow on this device. Filesystem
    // writes go through a different native executor and reads via
    // fetch(convertFileSrc(...)) bypass the plugin entirely (proven fast in
    // the same logs: Library 250KB / 150ms, bin 5MB / 70-162ms). Cold-start
    // restorePlaybackStateCritical now reads this file first; falls back to
    // Preferences if the file is missing (first launch after this change).
    if (EXTERNAL_DATA_DIR) {
      MusicBridge.writeTextFile({
        path: `${EXTERNAL_DATA_DIR}/playback_state_current.json`,
        content: tinyJson,
      }).catch(() => { /* ignore — Preferences write below is the durable copy */ });
    }
    // Write both. The tiny one first so a mid-write app kill leaves us with
    // a consistent (small) snapshot rather than an out-of-sync pair. Atomic
    // SharedPreferences semantics mean each `set` is durable on its own.
    await Preferences.set({ key: 'playback_state_current', value: tinyJson });
    await Preferences.set({ key: 'playback_state', value: JSON.stringify(data) });
  } catch (e) { /* ignore */ }
}

function _normalizePendingListenSnapshot(snapshot) {
  if (!snapshot) return null;
  const filename = snapshot.filename || (snapshot.songId != null && songs[snapshot.songId] ? songs[snapshot.songId].filename : null);
  if (!filename) return null;
  const resolvedSongId = _filenameToId(filename);
  const song = resolvedSongId != null ? songs[resolvedSongId] : (snapshot.songId != null ? songs[snapshot.songId] : null);
  const playedMs = Math.max(0, Math.round(Number(snapshot.playedMs) || 0));
  const durationMs = Math.max(0, Math.round(Number(snapshot.durationMs) || 0));
  if (!(durationMs > 0)) return null;
  const playbackInstanceId = Math.max(0, Math.round(Number(snapshot.playbackInstanceId) || 0));
  const capturedAt = snapshot.capturedAt || new Date().toISOString();
  const capturedAtMs = Date.parse(capturedAt);
  return {
    songId: resolvedSongId != null ? resolvedSongId : (snapshot.songId != null ? snapshot.songId : null),
    filename,
    title: snapshot.title || (song ? song.title : filename),
    artist: snapshot.artist || (song ? song.artist : ''),
    album: snapshot.album || (song ? song.album : ''),
    playbackInstanceId,
    playedMs: Math.min(playedMs, durationMs),
    durationMs,
    reason: snapshot.reason || null,
    capturedAt,
    capturedAtMs: Number.isFinite(capturedAtMs) ? capturedAtMs : Date.now(),
  };
}

export async function savePendingListenSnapshot(snapshot) {
  const normalized = _normalizePendingListenSnapshot(snapshot);
  if (!normalized) return { saved: false, reason: 'invalid_snapshot' };
  try {
    const existing = await loadPendingListenSnapshot();
    let next = normalized;
    if (existing && existing.playbackInstanceId > 0 && existing.playbackInstanceId === normalized.playbackInstanceId) {
      next = {
        ...existing,
        ...normalized,
        playedMs: Math.max(existing.playedMs || 0, normalized.playedMs || 0),
        durationMs: Math.max(existing.durationMs || 0, normalized.durationMs || 0),
        capturedAt: normalized.capturedAt,
        capturedAtMs: normalized.capturedAtMs,
      };
    }
    await Preferences.set({ key: PENDING_LISTEN_KEY, value: JSON.stringify(next) });
    return { saved: true, snapshot: next };
  } catch (e) {
    return { saved: false, reason: 'preferences_write_failed' };
  }
}

export async function loadPendingListenSnapshot() {
  try {
    const { value } = await Preferences.get({ key: PENDING_LISTEN_KEY });
    if (!value) return null;
    return _normalizePendingListenSnapshot(JSON.parse(value));
  } catch (e) {
    return null;
  }
}

export async function clearPendingListenSnapshot(match = null) {
  try {
    if (!match) {
      await Preferences.remove({ key: PENDING_LISTEN_KEY });
      return true;
    }
    const existing = await loadPendingListenSnapshot();
    if (!existing) return false;
    const matchPlayback = match.playbackInstanceId == null || Number(match.playbackInstanceId) === Number(existing.playbackInstanceId);
    const matchFilename = !match.filename || match.filename === existing.filename;
    if (!matchPlayback || !matchFilename) return false;
    await Preferences.remove({ key: PENDING_LISTEN_KEY });
    return true;
  } catch (e) {
    return false;
  }
}

// Quick read of display info — no song resolution needed, works before loadData
export async function getLastPlayedDisplay() {
  try {
    const { value } = await Preferences.get({ key: 'playback_state' });
    if (!value) return null;
    const data = JSON.parse(value);
    if (!data.currentFilename) return null;
    return {
      title: data.currentTitle || data.currentFilename,
      artist: data.currentArtist || '',
      album: data.currentAlbum || '',
      filename: data.currentFilename,
      filePath: data.currentFilePath || null,
      currentTime: data.currentTime || 0,
      duration: data.duration || 0,
    };
  } catch (e) { return null; }
}

/**
 * Read JUST the tiny `playback_state_current` key. Used by the cold-start path
 * to enable the play tap before the full restorePlaybackState completes. Sets
 * state.current and returns a display object (no queue/history/listened —
 * those land later via restorePlaybackState). Returns null if no tiny key
 * exists (fresh install, or a save predating the split).
 */
export async function restorePlaybackStateCritical() {
  try {
    const tStart = performance.now();
    // 2026-05-11 #7: try the file fast path first. fetch(convertFileSrc(...))
    // goes through Capacitor's WebView HTTP server, which is in a different
    // native pool than the Preferences plugin — proven ~50–200 ms in logs.
    // Falls back to Preferences if the file is missing.
    let value = null;
    let source = 'file';
    if (EXTERNAL_DATA_DIR && typeof window !== 'undefined' && window.Capacitor && typeof window.Capacitor.convertFileSrc === 'function') {
      try {
        const url = window.Capacitor.convertFileSrc('file://' + EXTERNAL_DATA_DIR + '/playback_state_current.json');
        const resp = await fetch(url);
        if (resp && resp.ok) {
          value = await resp.text();
        }
      } catch (e) { /* fall through to Preferences */ }
    }
    if (!value) {
      source = 'prefs';
      const r = await Preferences.get({ key: 'playback_state_current' });
      value = r && r.value;
    }
    const tGet = performance.now();
    if (!value) {
      console.log(`[PERF] restorePlaybackStateCritical: no critical state (cold path will fall back to full read), ${Math.round(tGet - tStart)} ms`);
      return null;
    }
    console.log(`[PERF] restorePlaybackStateCritical: ${source} read ${Math.round(tGet - tStart)} ms, payload ${value.length} chars`);
    const data = JSON.parse(value);
    let currentId = null;
    if (data.currentFilename) currentId = _filenameToId(data.currentFilename);
    if (currentId == null) return null;
    state.current = currentId;
    state.currentSource = data.currentSource || 'restored';
    const s = songs[currentId];
    if (!s) return null;
    return {
      id: s.id,
      title: s.title,
      artist: s.artist,
      album: s.album,
      filename: s.filename,
      filePath: s.filePath,
      artPath: s.artPath,
      hasEmbedding: s.hasEmbedding,
      isFavorite: favorites.has(s.id),
      duration: data.duration || 0,
      currentTime: data.currentTime || 0,
    };
  } catch (e) {
    return null;
  }
}

export async function restorePlaybackState() {
  try {
    const tStart = performance.now();
    const { value } = await Preferences.get({ key: 'playback_state' });
    const tGet = performance.now();
    if (!value) return null;
    console.log(`[PERF] restorePlaybackState: Preferences.get(playback_state) ${Math.round(tGet - tStart)} ms, payload ${value.length} chars`);
    const data = JSON.parse(value);
    const tParse = performance.now();
    console.log(`[PERF] restorePlaybackState: JSON.parse ${Math.round(tParse - tGet)} ms, listened=${(data.listenedFilenames || []).length} history=${(data.historyFilenames || []).length} queue=${(data.queueFilenames || []).length} timeline=${(data.timelineFilenames || []).length}`);

    // Restore by filename (stable) — fall back to old ID-based format for migration
    let currentId = null;
    if (data.currentFilename) {
      currentId = _filenameToId(data.currentFilename);
    } else if (data.current != null && data.current < songs.length) {
      currentId = data.current; // legacy ID-based format
    }
    if (currentId == null) return null;

    state.current = currentId;
    state.currentSource = data.currentSource || 'restored';

    if (data.historyFilenames) {
      state.history = data.historyFilenames.map(fn => _filenameToId(fn)).filter(id => id != null);
    } else {
      state.history = (data.history || []).filter(id => id < songs.length);
    }
    state.historyPos = Math.min(data.historyPos ?? state.history.length - 1, state.history.length - 1);

    if (data.queueFilenames) {
      state.queue = data.queueFilenames.map(q => { const id = _filenameToId(q.filename); return id != null ? { id, similarity: q.similarity } : null; }).filter(Boolean);
    } else {
      state.queue = (data.queue || []).filter(q => q.id < songs.length);
    }

    if (data.listenedFilenames) {
      state.listened = data.listenedFilenames.map(entry => {
        const id = _filenameToId(entry.filename);
        if (id == null) return null;
        const listenFraction = (typeof entry.listen_fraction === 'number' && !Number.isNaN(entry.listen_fraction))
          ? entry.listen_fraction
          : 0;
        const encounters = Number.isFinite(Number(entry.encounters))
          ? Number(entry.encounters)
          : (entry.listen_fraction != null ? 1 : 0);
        const totalFraction = Number.isFinite(Number(entry.totalFraction))
          ? Number(entry.totalFraction)
          : (encounters > 0 ? listenFraction : 0);
        const skips = Number.isFinite(Number(entry.skips))
          ? Number(entry.skips)
          : (encounters > 0 && (listenFraction != null && listenFraction <= LISTEN_SKIP_THRESHOLD) ? 1 : 0);
        const plays = Number.isFinite(Number(entry.plays))
          ? Number(entry.plays)
          : (encounters > 0 && !(listenFraction != null && listenFraction <= LISTEN_SKIP_THRESHOLD) ? 1 : 0);
        return {
          id,
          listen_fraction: listenFraction,
          encounters,
          totalFraction,
          skips,
          plays,
          source: entry.source || null,
        };
      }).filter(Boolean);
    } else {
      state.listened = (data.listened || []).filter(s => s.id < songs.length);
    }

    state.sessionLabel = data.sessionLabel || '';
    state.playingFavorites = data.playingFavorites || false;
    state.playingAlbum = data.playingAlbum || false;
    state.playingPlaylist = data.playingPlaylist || false;
    state.currentPlaylistId = data.currentPlaylistId || null;

    if (data.albumTrackFilenames) {
      state.currentAlbumTracks = data.albumTrackFilenames.map(fn => _filenameToId(fn)).filter(id => id != null);
    } else {
      state.currentAlbumTracks = (data.currentAlbumTracks || []).filter(id => id < songs.length);
    }
    if (data.timelineFilenames) {
      state.timelineItems = data.timelineFilenames.map(item => {
        const id = _filenameToId(item.filename);
        return id != null ? { id, similarity: item.similarity, manual: !!item.manual } : null;
      }).filter(Boolean);
      state.timelineIndex = Math.min(data.timelineIndex ?? -1, state.timelineItems.length - 1);
      state.timelineMode = data.timelineMode || null;
      if (data.explicitPlayedFilenames) {
        _pcbs.setExplicitPlayedIds(data.explicitPlayedFilenames.map(fn => _filenameToId(fn)).filter(id => id != null));
      } else {
        _pcbs.setExplicitPlayedIds([]);
      }
    } else {
      _pcbs.clearTimelineContext();
    }
    if (data.recToggle !== undefined) _setRecToggle(data.recToggle);
    setLastLoggedPlaybackStartInstanceId(Math.max(0, Math.round(Number(data.loggedPlaybackStartInstanceId) || 0)));
    setLastLoggedPlaybackStartSongId(data.loggedPlaybackStartFilename
      ? _filenameToId(data.loggedPlaybackStartFilename)
      : null);
    if (state.playingPlaylist && !_getPlaylistById(state.currentPlaylistId)) {
      state.playingPlaylist = false;
      state.currentPlaylistId = null;
    }
    if (state.timelineMode !== 'explicit') {
      _pcbs.setExplicitPlayedIds([]);
      state.timelineMode = null;
      _pcbs.syncDynamicTimelineFromState();
    } else if (state.timelineIndex < 0 && state.current != null) {
      state.timelineIndex = state.timelineItems.findIndex(item => item.id === state.current);
    }

    const s = songs[state.current];
    const isFav = favorites.has(s.id);
    console.log(`[PERF] restorePlaybackState: filename-mapping passes done ${Math.round(performance.now() - tStart)} ms total`);
    console.log('[FAV] restorePlaybackState: song', s.id, s.filename, 'isFavorite =', isFav, 'favorites set:', [...favorites]);
    return {
      id: s.id,
      title: s.title,
      artist: s.artist,
      album: s.album,
      filename: s.filename,
      filePath: s.filePath,
      artPath: s.artPath,
      hasEmbedding: s.hasEmbedding,
      isFavorite: isFav,
      currentTime: data.currentTime || 0,
      duration: data.duration || 0,
    };
  } catch (e) {
    console.error('[FAV] restorePlaybackState error:', e);
    return null;
  }
}

export async function resetEngine() {
  _activity('app', 'engine_reset', 'Engine reset requested', {}, { important: true, tags: ['reset'] });
  state.current = null;
  state.currentSource = null;
  state.history = [];
  state.historyPos = -1;
  state.queue = [];
  state.listened = [];
  state.sessionLabel = '';
  state.playingFavorites = false;
  state.playingAlbum = false;
  state.playingPlaylist = false;
  state.currentPlaylistId = null;
  state.currentAlbumTracks = [];
  _pcbs.clearTimelineContext();
  _setRecToggle(true);
  _setQueueShuffleEnabled(false);
  setCurrentPlaybackInstanceId(0);
  setCapturedPlaybackInstanceId(null);
  setLastLoggedPlaybackStartInstanceId(0);
  setLastLoggedPlaybackStartSongId(null);
  if (_recommendationRebuildTimer) {
    clearTimeout(_recommendationRebuildTimer);
    setRecommendationRebuildTimer(null);
  }
  setRecommendationRebuildInFlight(false);
  setRecommendationRebuildPending(false);
  setRecommendationRebuildReason(null);
  setRecommendationRebuildOpts({ refreshQueue: false, refreshDiscover: false });

  // Clear saved state and logs
  await Preferences.remove({ key: 'playback_state' });
  await Preferences.remove({ key: 'favorites' });
  await Preferences.remove({ key: 'profile_summary_v2' });
  await Preferences.remove({ key: 'profile_summary_v1' });
  await Preferences.remove({ key: 'profile_reset_markers_v1' });
  await Preferences.remove({ key: 'negative_scores' });
  await Preferences.remove({ key: SIMILARITY_BOOST_KEY });
  await Preferences.remove({ key: 'disliked_songs' });
  await Preferences.remove({ key: TASTE_REVIEW_IGNORE_KEY });
  await Preferences.remove({ key: PENDING_LISTEN_KEY });
  setFavorites(new Set());
  setNegativeScores({});
  setSimilarityBoostScores({});
  setDislikedFilenames(new Set());
  setTasteReviewIgnores({});
  setLastTasteReviewSnapshot('');
  setRecentPlaybackSignalEvents([]);

  // Clear session logs
  try {
    const { value } = await Preferences.get({ key: 'session_logs_index' });
    if (value) {
      const index = JSON.parse(value);
      for (const key of index) {
        await Preferences.remove({ key });
      }
      await Preferences.remove({ key: 'session_logs_index' });
    }
  } catch (e) { /* ignore */ }

  setProfileVec(null);
  setRecommendationPolicySnapshot({
    rowsById: new Map(),
    hardExcludeSongIds: new Set(),
    fingerprint: '',
    version: 0,
    updatedAt: 0,
  });
  setLog(new SessionLogger());
}

export async function clearPlaybackSession() {
  state.current = null;
  state.currentSource = null;
  state.queue = [];
  state.sessionLabel = '';
  state.playingFavorites = false;
  state.playingAlbum = false;
  state.playingPlaylist = false;
  state.currentPlaylistId = null;
  state.currentAlbumTracks = [];
  _pcbs.clearTimelineContext();
  setCurrentPlaybackInstanceId(0);
  setCapturedPlaybackInstanceId(null);
  setLastLoggedPlaybackStartInstanceId(0);
  setLastLoggedPlaybackStartSongId(null);
  await Preferences.remove({ key: 'playback_state' });
}

export function shutdown() {
  if (log) log.shutdown();
  savePlaybackState();
  flushActivityLog().catch(() => {});
}

export function getNativeQueueSnapshot() {
  const items = [];
  let startIndex = 0;
  if (state.timelineIndex >= 0 && Array.isArray(state.timelineItems) && state.timelineItems.length > 0) {
    for (let i = 0; i < state.timelineItems.length; i++) {
      const entry = state.timelineItems[i];
      const s = songs[entry.id];
      if (!s || !s.filePath) continue;
      items.push({
        songId: s.id,
        filePath: s.filePath,
        title: s.title || 'Unknown',
        artist: s.artist || '',
        album: s.album || '',
      });
      if (i === state.timelineIndex) startIndex = items.length - 1;
    }
    if (items.length > 0) {
      return { items, startIndex };
    }
  }

  if (state.current != null && songs[state.current]) {
    const s = songs[state.current];
    items.push({
      songId: s.id,
      filePath: s.filePath || '',
      title: s.title || 'Unknown',
      artist: s.artist || '',
      album: s.album || '',
    });
  }
  for (const q of state.queue) {
    const s = songs[q.id];
    if (s && s.filePath) {
      items.push({
        songId: s.id,
        filePath: s.filePath,
        title: s.title || 'Unknown',
        artist: s.artist || '',
        album: s.album || '',
      });
    }
  }
  return { items, startIndex: 0 };
}

export function getNativeQueueItems() {
  return getNativeQueueSnapshot().items;
}

export function getUpcomingNativeItems() {
  const items = [];
  for (const q of state.queue) {
    const s = songs[q.id];
    if (s && s.filePath) {
      items.push({
        songId: s.id,
        filePath: s.filePath,
        title: s.title || 'Unknown',
        artist: s.artist || '',
        album: s.album || '',
      });
    }
  }
  return items;
}

export function syncCurrentFromNativeState(data, opts = {}) {
  const songId = _resolveSongIdFromNativePayload(data);
  if (songId == null || !songs[songId]) return null;
  state.current = songId;
  if (opts.currentSource) state.currentSource = opts.currentSource;
  if (state.history.length === 0 || state.history[state.history.length - 1] !== songId) {
    state.history.push(songId);
  }
  state.historyPos = state.history.length - 1;
  _pcbs.syncDynamicTimelineFromState();
  if (opts.ensurePlaybackStart === true) {
    _ensurePlaybackStartLogged(songId, state.currentSource || 'native_restore', null, null, {
      playbackInstanceId: opts.playbackInstanceId,
    });
  }
  return _pcbs.currentSongInfo();
}

export function syncQueueFromNativeSnapshot(queueState, opts = {}) {
  const items = Array.isArray(queueState && queueState.items) ? queueState.items : [];
  if (items.length === 0) return null;

  const rawIndex = queueState && Number.isInteger(queueState.currentIndex) ? queueState.currentIndex : 0;
  const currentIndex = Math.max(0, Math.min(rawIndex, items.length - 1));
  const currentId = _resolveSongIdFromNativePayload(items[currentIndex]);
  if (currentId == null || !songs[currentId]) return null;

  if (opts.appendHistory === true) {
    if (state.historyPos < state.history.length - 1) {
      state.history = state.history.slice(0, state.historyPos + 1);
    }
    if (state.history.length === 0 || state.history[state.history.length - 1] !== currentId) {
      state.history.push(currentId);
    }
    state.historyPos = state.history.length - 1;
  }

  state.current = currentId;
  if (opts.currentSource) state.currentSource = opts.currentSource;

  const resolvedTimeline = [];
  let resolvedCurrentIndex = -1;
  for (let i = 0; i < items.length; i++) {
    const sid = _resolveSongIdFromNativePayload(items[i]);
    if (sid == null || !songs[sid] || !songs[sid].filePath) continue;
    resolvedTimeline.push({ id: sid, similarity: 1.0, manual: false });
    if (i === currentIndex) resolvedCurrentIndex = resolvedTimeline.length - 1;
  }
  if (resolvedCurrentIndex >= 0) {
    state.timelineItems = resolvedTimeline;
    state.timelineIndex = resolvedCurrentIndex;
    if (state.timelineMode !== 'explicit') state.timelineMode = 'dynamic';
  }

  const upcoming = [];
  for (let i = currentIndex + 1; i < items.length; i++) {
    const sid = _resolveSongIdFromNativePayload(items[i]);
    if (sid == null || !songs[sid] || !songs[sid].filePath) continue;
    upcoming.push({ id: sid, similarity: 1.0 });
  }
  state.queue = upcoming;
  if (opts.ensurePlaybackStart === true) {
    _ensurePlaybackStartLogged(currentId, state.currentSource || 'native_restore', null, null, {
      playbackInstanceId: opts.playbackInstanceId,
    });
  }
  return _pcbs.currentSongInfo();
}

export function onNativeAdvance(data, nativeQueueState = null) {
  const prevFraction = (data.prevFraction != null && data.prevFraction >= 0) ? data.prevFraction : null;
  const action = data.action || '';

  _pcbs.markExplicitCurrentPlayed();
  const shouldRecordListen = !(action === 'neutral_skip' && (prevFraction == null || prevFraction <= NEUTRAL_SKIP_CAPTURE_THRESHOLD));
  if (shouldRecordListen) _pcbs.recordListen(prevFraction, { transitionAction: action });

  const songId = _resolveSongIdFromNativePayload(data);
  if (songId == null || !songs[songId]) return null;

  _activity('native', 'native_queue_advanced', `Native advanced queue via ${data.action || 'unknown'} to "${songs[songId].title}"`, {
    action: data.action || null,
    prevFraction,
    ..._songRef(songId),
    nativeSongId: data.songId,
    nativeFilePath: data.filePath || null,
  }, { important: true, tags: ['native', 'queue'] });

  if (action === 'user_prev') {
    let targetHistoryPos = -1;
    for (let i = Math.max(0, state.historyPos - 1); i >= 0; i--) {
      if (state.history[i] === songId) {
        targetHistoryPos = i;
        break;
      }
    }
    if (targetHistoryPos >= 0) {
      state.historyPos = targetHistoryPos;
    } else {
      if (state.historyPos < state.history.length - 1) {
        state.history = state.history.slice(0, state.historyPos + 1);
      }
      if (state.history.length === 0 || state.history[state.history.length - 1] !== songId) {
        state.history.push(songId);
      }
      state.historyPos = state.history.length - 1;
    }
  } else {
    if (state.historyPos < state.history.length - 1) {
      state.history = state.history.slice(0, state.historyPos + 1);
    }
    if (state.history.length === 0 || state.history[state.history.length - 1] !== songId) {
      state.history.push(songId);
    }
    state.historyPos = state.history.length - 1;
  }
  state.current = songId;
  state.currentSource = 'native_' + (data.action || 'advance');

  if (nativeQueueState && Array.isArray(nativeQueueState.items) && nativeQueueState.items.length > 0) {
    syncQueueFromNativeSnapshot(nativeQueueState, { appendHistory: false, currentSource: state.currentSource });
  } else if (action.startsWith('auto_') || action === 'user_next' || action === 'neutral_skip') {
    const idx = state.queue.findIndex(q => q.id === songId);
    if (idx !== -1) {
      state.queue.splice(0, idx + 1);
    } else if (state.queue.length > 0 && state.queue[0].id !== songId) {
      state.queue.shift();
    }
    if (state.timelineMode !== 'explicit') {
      _pcbs.syncDynamicTimelineFromState();
    }
  }
  _pcbs.beginPlaybackInstance();

  _ensurePlaybackStartLogged(songId, state.currentSource, null, prevFraction, {
    playbackInstanceId: Number(data && data.currentPlaybackInstanceId) || 0,
  });

  // Decide whether to extend the queue with fresh AI recommendations.
  //
  // Two triggers:
  //   (a) Dynamic mode + queue running low (existing behavior).
  //   (b) Explicit section / ordered-list mode AND we just advanced to the
  //       LAST item of the timeline. Without this, sections silently end
  //       (with loop=off) or loop forever (with loop=all default — which
  //       prevents the queueEnded path entirely on the native side). Albums,
  //       favorites, and playlists are excluded — those genuinely "end" by
  //       design and the user expects them to stop / loop themselves.
  const isContextThatEnds = state.playingAlbum || state.playingFavorites || state.playingPlaylist;
  const queueLow = state.queue.length < 5;
  const explicitAtLast = state.timelineMode === 'explicit'
      && Array.isArray(state.timelineItems)
      && state.timelineItems.length > 0
      && songId === state.timelineItems[state.timelineItems.length - 1].id;
  const needsRefresh = recToggle
      && !isContextThatEnds
      && (
          (state.timelineMode !== 'explicit' && queueLow)
          || explicitAtLast
      );
  if (needsRefresh) {
    _pcbs.cleanQueue();
    _pcbs.doRefresh(explicitAtLast ? 'section_ending' : 'queue_low');
  }

  return { songInfo: _pcbs.currentSongInfo(), needsSync: needsRefresh };
}
