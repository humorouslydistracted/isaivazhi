// Startup data pipeline — loadData, background scan, favorites/playlists I/O,
// art cache helpers, song-ref snapshot/restore, dedup, and album rebuild.
// Extracted from engine.js; imports engine-state, engine-indexes, engine-taste, engine-embeddings.
// engine.js wires cross-cutting callbacks via initDataCallbacks().

import { Preferences } from '@capacitor/preferences';
import { Recommender } from './recommender.js';
import { attachGpuToRec } from './gpu-recommender.js';
import { MusicBridge } from './music-bridge.js';
import * as EmbeddingCache from './embedding-cache.js';
import { SessionLogger } from './logger.js';
import { initActivityLog } from './activity-log.js';
import {
  TOP_N, PLAYLISTS_PREF_KEY,
  EXTERNAL_DATA_DIR, setExternalDataDir,
  songs, setSongs, embeddings, setEmbeddings, setEmbeddingMap,
  rec, setRec, setLog,
  setAlbumList, setAlbumArray, albumArray,
  favorites, setFavorites,
  playlists, setPlaylists,
  profileVec, setProfileVec,
  scanComplete, scanCallbacks, setScanComplete,
  recToggle, state, _tuning,
} from './engine-state.js';
import {
  _getFilenameMap, _getPathMap,
  _invalidateFilenameMap, _invalidatePathMap, _invalidateEmbIdxMap,
} from './engine-indexes.js';
import {
  _loadTuning, _loadNegativeScores, _loadSimilarityBoostScores,
  _loadDislikes, _loadTasteReviewIgnores,
  _applyDislikeFlags, buildProfileVec, _activity,
} from './engine-taste.js';
import {
  setLibrarySavedAt, librarySavedAt, SCAN_SKIP_WINDOW_MS,
  _markEmbeddingsReady,
  _loadLocalEmbeddings, _mergeLocalEmbeddings,
  _loadRemovedEmbeddingKeys, _applyRemovedEmbeddingFlags,
  _identifyOrphans, _saveSongLibrary, _triggerAutoEmbedding,
  _scheduleDeferredDeletionCheck, _markSongsMissing,
  _setupEmbeddingListeners,
} from './engine-embeddings.js';

// --- Callbacks wired by engine.js to avoid circular import ---
let _dcbs = {};
export function initDataCallbacks(cbs) { _dcbs = cbs; }

// --- Private state ---
let _artCacheDir = '';
export function setArtCacheDir(dir) { _artCacheDir = dir; }
const ART_CACHE_KEY_PREFIX = 'art_v2::';

// Fix D: in-flight embedding load started before loadData() completes.
// preloadEmbeddingsEarly() kicks off _loadLocalEmbeddings() against the cached
// data-dir path while the rest of loadData() is still doing Preferences and
// library reads. startBackgroundScan() picks up this promise instead of starting
// a second load — but only if the resolved data dir from getAppDataDir() still
// matches the cached path. If the user reinstalled the app or the system moved
// the data dir, the early result is discarded.
let _earlyEmbeddingPromise = null;
let _earlyEmbeddingPath = null;
let _earlyEmbeddingResolved = false;
const CACHED_DATA_DIR_KEY = 'cached_data_dir_v1';

/**
 * Start _loadLocalEmbeddings() in parallel with loadData(), using the cached
 * data dir from a previous successful getAppDataDir() call. Safe to call
 * multiple times — only the first call starts the load; subsequent calls
 * return the same promise. Safe to call on first launch — returns null when
 * no cached path exists, and startBackgroundScan() falls back to the legacy
 * flow.
 */
export async function preloadEmbeddingsEarly() {
  if (_earlyEmbeddingPromise) return _earlyEmbeddingPromise;
  try {
    const { value } = await Preferences.get({ key: CACHED_DATA_DIR_KEY });
    if (!value) return null;
    const cached = JSON.parse(value);
    if (!cached || !cached.path) return null;
    setExternalDataDir(cached.path);
    EmbeddingCache.setDataDir(cached.path);
    if (cached.artCacheDir) setArtCacheDir(cached.artCacheDir);
    _earlyEmbeddingPath = cached.path;
    _earlyEmbeddingPromise = _loadLocalEmbeddings();
    // Fix H: track when the early load actually finishes so the
    // playback-intent deferral can shrink to ~100ms when the heavy work
    // (binary read + parse) is already done. Both fulfill and reject mark
    // it resolved — failure means we'll fall back to the bridge path,
    // which is also short post-Fix-G.
    _earlyEmbeddingPromise
      .then(() => { _earlyEmbeddingResolved = true; })
      .catch(() => { _earlyEmbeddingResolved = true; });
    console.log('[PERF] preloadEmbeddingsEarly: started against cached path');
    return _earlyEmbeddingPromise;
  } catch (e) {
    return null;
  }
}

// Fix H: lets the UI-side scheduler shrink its post-playback-intent deferral
// when the heavy embedding read is already done. After Fix G the post-defer
// work is ~50ms (merge + Recommender + GPU attach) — no need to sleep 3s.
export function isEarlyEmbeddingLoaded() {
  return _earlyEmbeddingResolved;
}

let _loadingCb = null;
export function onLoadingStatus(cb) { _loadingCb = cb; }
function _setLoading(msg) { if (_loadingCb) _loadingCb(msg); }

let _artReadyCbs = [];
export function onAlbumArtReady(cb) { _artReadyCbs.push(cb); }

let _albumArtListenerSet = false;

// --- Album rebuild ---

export function _rebuildAlbums() {
  const albumMap = {};
  for (const s of songs) {
    if (!albumMap[s.album]) albumMap[s.album] = [];
    albumMap[s.album].push(s);
  }
  setAlbumList(albumMap);
  setAlbumArray(Object.keys(albumMap).sort().map(name => ({
    name,
    artist: albumMap[name][0].artist,
    count: albumMap[name].length,
    tracks: albumMap[name].map(t => ({ id: t.id, title: t.title, artist: t.artist })),
  })));
}

// --- Scan complete callback ---

export function onScanComplete(cb) {
  if (scanComplete) { cb(); return; }
  scanCallbacks.push(cb);
}

// --- Art cache helpers ---

function _javaStringHashCode(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash + str.charCodeAt(i)) | 0;
  }
  return hash;
}

function _expectedArtCacheBasename(filePath) {
  if (!filePath) return '';
  const hash = _javaStringHashCode(ART_CACHE_KEY_PREFIX + filePath);
  return ((hash >>> 0).toString(16)) + '.jpg';
}

function _normalizeSongArtPaths(list) {
  if (!_artCacheDir || !Array.isArray(list)) return;
  for (const song of list) {
    if (!song || !song.filePath || !song.artPath) continue;
    const actualName = String(song.artPath).split(/[\\/]/).pop();
    const expectedName = _expectedArtCacheBasename(song.filePath);
    if (actualName !== expectedName) song.artPath = null;
  }
}

export function _triggerAlbumArtExtraction() {
  const pathsWithoutArt = songs.filter(s => s.filePath && !s.artPath).map(s => s.filePath);
  if (pathsWithoutArt.length === 0) return;
  console.log(`Extracting album art for ${pathsWithoutArt.length} songs...`);
  MusicBridge.extractAlbumArtBatch({ paths: pathsWithoutArt }).catch(() => {});

  // Listen for completion (only once — guard prevents duplicate listeners across scans)
  if (_albumArtListenerSet) return;
  _albumArtListenerSet = true;
  MusicBridge.addListener('albumArtReady', (data) => {
    console.log(`Album art extraction done: ${data.extracted}/${data.total} extracted`);
    for (const cb of _artReadyCbs) {
      try { cb(); } catch (e) { /* ignore */ }
    }
  });
}

// --- Song deduplication ---

function _dedupeSongsArray(inputSongs) {
  const byKey = new Map();

  for (const song of inputSongs) {
    const pathKey = song.filePath ? `path:${song.filePath.toLowerCase()}` : null;
    const fileKey = `file:${(song.filename || '').toLowerCase()}`;
    const key = pathKey || fileKey;

    if (!byKey.has(key)) {
      byKey.set(key, { ...song });
      continue;
    }

    const existing = byKey.get(key);
    existing.filePath = existing.filePath || song.filePath || null;
    existing.artPath = existing.artPath || song.artPath || null;
    existing.dateModified = Math.max(existing.dateModified || 0, song.dateModified || 0);
    if ((!existing.title || existing.title === existing.filename) && song.title) existing.title = song.title;
    if ((!existing.artist || existing.artist === 'Unknown') && song.artist) existing.artist = song.artist;
    if ((!existing.album || existing.album === 'Unknown') && song.album) existing.album = song.album;
    if (song.hasEmbedding) {
      existing.hasEmbedding = true;
      if (existing.embeddingIndex == null) existing.embeddingIndex = song.embeddingIndex;
      existing.contentHash = existing.contentHash || song.contentHash;
    }
  }

  const deduped = Array.from(byKey.values());
  for (let i = 0; i < deduped.length; i++) deduped[i].id = i;
  return deduped;
}

// --- Song-ref snapshot/restore (survive dedup re-index) ---

function _songRefForId(songId) {
  const s = songs[songId];
  if (!s) return null;
  return {
    filename: s.filename || null,
    filePath: s.filePath || null,
  };
}

function _resolveSongIdFromRef(ref) {
  if (!ref) return null;
  if (ref.filePath) {
    const sid = _getPathMap()[String(ref.filePath).toLowerCase()];
    if (sid != null) return sid;
  }
  if (ref.filename) {
    const sid = _getFilenameMap()[String(ref.filename).toLowerCase()];
    if (sid != null) return sid;
  }
  return null;
}

function _snapshotStateBySongRefs() {
  return {
    current: _songRefForId(state.current),
    history: state.history.map(id => _songRefForId(id)).filter(Boolean),
    historyPos: state.historyPos,
    queue: state.queue.map(item => ({
      songRef: _songRefForId(item.id),
      similarity: item.similarity,
      manual: !!item.manual,
    })).filter(item => item.songRef),
    listened: state.listened.map(entry => ({
      songRef: _songRefForId(entry.id),
      entry: { ...entry },
    })).filter(item => item.songRef),
    currentAlbumTracks: (state.currentAlbumTracks || []).map(id => _songRefForId(id)).filter(Boolean),
    timelineMode: state.timelineMode,
    timelineIndex: state.timelineIndex,
    timeline: (state.timelineItems || []).map(item => ({
      songRef: _songRefForId(item.id),
      similarity: item.similarity,
      manual: !!item.manual,
    })).filter(item => item.songRef),
    explicitPlayed: (state.explicitPlayedIds || []).map(id => _songRefForId(id)).filter(Boolean),
  };
}

function _restoreStateBySongRefs(snapshot) {
  if (!snapshot) return;

  const restoredHistory = (snapshot.history || [])
    .map(ref => _resolveSongIdFromRef(ref))
    .filter(id => id != null);
  const restoredQueue = (snapshot.queue || [])
    .map(item => {
      const id = _resolveSongIdFromRef(item.songRef);
      return id != null ? { id, similarity: item.similarity, manual: !!item.manual } : null;
    })
    .filter(Boolean);
  const restoredListened = (snapshot.listened || [])
    .map(item => {
      const id = _resolveSongIdFromRef(item.songRef);
      return id != null ? { ...item.entry, id } : null;
    })
    .filter(Boolean);
  const restoredAlbumTracks = (snapshot.currentAlbumTracks || [])
    .map(ref => _resolveSongIdFromRef(ref))
    .filter(id => id != null);
  const restoredTimeline = (snapshot.timeline || [])
    .map(item => {
      const id = _resolveSongIdFromRef(item.songRef);
      return id != null ? { id, similarity: item.similarity, manual: !!item.manual } : null;
    })
    .filter(Boolean);
  const restoredExplicitPlayed = (snapshot.explicitPlayed || [])
    .map(ref => _resolveSongIdFromRef(ref))
    .filter(id => id != null);

  state.current = _resolveSongIdFromRef(snapshot.current);
  state.history = restoredHistory;
  state.historyPos = Math.min(snapshot.historyPos ?? (restoredHistory.length - 1), restoredHistory.length - 1);
  state.queue = restoredQueue;
  state.listened = restoredListened;
  state.currentAlbumTracks = restoredAlbumTracks;
  state.timelineMode = snapshot.timelineMode || null;
  state.timelineItems = restoredTimeline;
  state.timelineIndex = Math.min(snapshot.timelineIndex ?? -1, restoredTimeline.length - 1);
  _dcbs.setExplicitPlayedIds(restoredExplicitPlayed);

  if (state.current != null && !state.history.includes(state.current)) {
    state.history.push(state.current);
    state.historyPos = state.history.length - 1;
  }
  if (state.historyPos < 0 && state.history.length > 0) {
    state.historyPos = state.history.length - 1;
  }
  if (state.timelineMode !== 'explicit') {
    _dcbs.setExplicitPlayedIds([]);
    state.timelineMode = null;
    _dcbs.syncDynamicTimelineFromState();
  } else if (state.timelineIndex < 0 && state.timelineItems.length > 0 && state.current != null) {
    state.timelineIndex = state.timelineItems.findIndex(item => item.id === state.current);
  }
}

// --- Native payload resolver ---

function _filenameFromPath(filePath) {
  if (!filePath) return null;
  const norm = String(filePath);
  const slash = Math.max(norm.lastIndexOf('/'), norm.lastIndexOf('\\'));
  return slash >= 0 ? norm.slice(slash + 1) : norm;
}

export function _resolveSongIdFromNativePayload(data) {
  if (data && data.filePath) {
    const sid = _getPathMap()[String(data.filePath).toLowerCase()];
    if (sid != null) return sid;
  }

  const filename = data && (data.filename || _filenameFromPath(data.filePath));
  if (filename) {
    const sid = _getFilenameMap()[String(filename).toLowerCase()];
    if (sid != null) return sid;
  }

  if (data && data.songId != null && songs[data.songId]) return data.songId;
  return null;
}

// --- Data loading ---

export async function loadData() {
  const t0 = performance.now();
  console.log('[PERF] loadData start');
  await initActivityLog();
  _activity('app', 'engine_load_started', 'Engine load started', {}, { important: false, tags: ['startup'] });

  // Fix A: previously these were 5 sequential awaits (~30-100ms each on Android,
  // worse during the first cold start when Preferences are being demand-loaded).
  // Each function reads a distinct Preferences key and writes to a distinct
  // module-level state variable; they have no inter-dependencies, so running
  // them in parallel collapses 5 round-trips into the slowest single one.
  // We also fold getAppDataDir() into the same Promise.all — that native call
  // is also independent of the Preferences reads.
  const dataDirPromise = (async () => {
    try {
      const dirResult = await MusicBridge.getAppDataDir();
      if (dirResult && dirResult.path) {
        setExternalDataDir(dirResult.path);
        EmbeddingCache.setDataDir(dirResult.path);
        if (dirResult.artCacheDir) setArtCacheDir(dirResult.artCacheDir);
        // Fix D: cache the resolved data dir so the next launch can start
        // _loadLocalEmbeddings() before getAppDataDir() returns.
        try {
          await Preferences.set({ key: 'cached_data_dir_v1', value: JSON.stringify({
            path: dirResult.path,
            artCacheDir: dirResult.artCacheDir || '',
          }) });
        } catch (e) { /* cache miss next time, harmless */ }
      }
    } catch (e) {
      console.warn('Could not get app data dir, using fallback:', e.message);
    }
  })();

  await Promise.all([
    _loadTuning(),
    _loadNegativeScores(),
    _loadSimilarityBoostScores(),
    _loadDislikes(),
    _loadTasteReviewIgnores(),
    dataDirPromise,
  ]);
  console.log('[PERF] preferences + getAppDataDir parallel:', Math.round(performance.now() - t0), 'ms');

  // Load saved song library for instant UI — embeddings load in parallel
  setEmbeddings([]);
  setEmbeddingMap({});
  setSongs([]);

  // Embeddings loaded later by startBackgroundScan() — after restorePlaybackState,
  // so native file reads don't block Preferences.get on the Android main thread.

  // Read library — try fetch with 1s timeout, fallback to bridge
  const t1 = performance.now();
  let libraryLoaded = false;
  try {
    const libPath = `${EXTERNAL_DATA_DIR}/song_library.json`;
    const libUrl = window.Capacitor.convertFileSrc('file://' + libPath);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 1000);
    const resp = await fetch(libUrl, { signal: controller.signal });
    clearTimeout(timeout);
    if (resp.ok) {
      const library = await resp.json();
      if (Array.isArray(library.songs)) {
        setLibrarySavedAt(library.savedAt || 0);
        setSongs(library.songs.map((s, i) => ({
          id: i,
          filename: s.filename,
          title: s.title || s.filename.replace(/\.[^.]+$/, ''),
          artist: s.artist || 'Unknown',
          album: s.album || 'Unknown',
          hasEmbedding: s.hasEmbedding || false,
          embeddingIndex: s.embeddingIndex != null ? s.embeddingIndex : null,
          contentHash: s.contentHash || null,
          filePath: s.filePath || null,
          artPath: s.artPath || null,
          dateModified: s.dateModified || 0,
        })));
        setSongs(_dedupeSongsArray(songs)); _normalizeSongArtPaths(songs); _applyDislikeFlags();
        libraryLoaded = true;
        console.log(`[PERF] Library: ${songs.length} songs via fetch in`, Math.round(performance.now() - t1), 'ms');
      }
    }
  } catch (e) {
    console.log('[PERF] Fetch failed/timeout:', e.name, 'in', Math.round(performance.now() - t1), 'ms');
  }
  // Fallback: read via bridge if fetch failed
  if (!libraryLoaded) {
    try {
      const t1b = performance.now();
      const libPath = `${EXTERNAL_DATA_DIR}/song_library.json`;
      const result = await MusicBridge.readTextFile({ path: libPath });
      if (result && result.data) {
        const library = JSON.parse(result.data);
        if (Array.isArray(library.songs)) {
          setLibrarySavedAt(library.savedAt || 0);
          setSongs(library.songs.map((s, i) => ({
            id: i,
            filename: s.filename,
            title: s.title || s.filename.replace(/\.[^.]+$/, ''),
            artist: s.artist || 'Unknown',
            album: s.album || 'Unknown',
            hasEmbedding: s.hasEmbedding || false,
            embeddingIndex: s.embeddingIndex != null ? s.embeddingIndex : null,
            contentHash: s.contentHash || null,
            filePath: s.filePath || null,
            artPath: s.artPath || null,
            dateModified: s.dateModified || 0,
          })));
          setSongs(_dedupeSongsArray(songs)); _normalizeSongArtPaths(songs); _applyDislikeFlags();
          libraryLoaded = true;
          console.log(`[PERF] Library: ${songs.length} songs via bridge in`, Math.round(performance.now() - t1b), 'ms');
        }
      }
    } catch (e) {
      console.log('[PERF] Bridge read also failed, starting fresh');
    }
  }

  _rebuildAlbums();
  setLog(new SessionLogger());

  // Load favorites now (single Preferences read) so discover renders them immediately
  // Profile vec is intentionally deferred until embeddings are ready so startup
  // playback is not competing with AI hydration work on the JS thread.
  // Both reads are independent (different keys, different state) — run in parallel.
  await Promise.all([loadFavorites(), loadPlaylists()]);

  console.log(`[PERF] loadData done: ${songs.length} songs, ${albumArray.length} albums in`, Math.round(performance.now() - t0), 'ms');
  _activity('app', 'engine_load_completed', `Engine loaded ${songs.length} songs`, {
    songCount: songs.length,
    albumCount: albumArray.length,
    libraryLoaded,
  }, { important: true, tags: ['startup'] });

  // DO NOT start scan or embedding load here — init() calls startBackgroundScan()
  // after restoring playback state, so that native file reads and scanAudioFiles
  // don't block Preferences.get (restorePlaybackState) on the Android main thread.
}

export function startBackgroundScan(opts) {
  const force = !!(opts && opts.force);
  _setupEmbeddingListeners();
  if (typeof MusicBridge.requestEmbeddingStatus === 'function') {
    MusicBridge.requestEmbeddingStatus().catch(() => {});
  }
  // Fix D: reuse the in-flight early load if its data dir still matches the
  // resolved one. If they differ (re-install / data dir moved), discard the
  // early result and start a fresh load against the correct path.
  let embeddingPromise = null;
  if (_earlyEmbeddingPromise && _earlyEmbeddingPath === EXTERNAL_DATA_DIR) {
    embeddingPromise = _earlyEmbeddingPromise;
    _earlyEmbeddingPromise = null;
    _earlyEmbeddingPath = null;
    console.log('[PERF] startBackgroundScan: reusing early embedding load');
  } else {
    if (_earlyEmbeddingPromise) {
      console.log('[PERF] startBackgroundScan: discarding stale early load (path changed)');
      _earlyEmbeddingPromise = null;
      _earlyEmbeddingPath = null;
    }
    embeddingPromise = _loadLocalEmbeddings();
  }
  _backgroundScan(embeddingPromise, force);
}

// ISSUE-3 Task 3: smart scan skip — if library cache is fresh (< 6 hours old)
// and we already loaded songs from it, skip the full MediaStore scan.
// Embeddings still load, merge runs, callbacks fire — just no scanAudioFiles call.
async function _backgroundScan(embeddingPromise, force) {
  try {
    const scanT0 = performance.now();
    const cacheAge = librarySavedAt > 0 ? (Date.now() - librarySavedAt) : Infinity;
    const canSkip = !force && songs.length > 0 && cacheAge < SCAN_SKIP_WINDOW_MS;

    if (canSkip) {
      _activity('library', 'library_scan_skipped', 'Skipped full library scan using cached library', {
        cacheAgeMs: cacheAge,
        songCount: songs.length,
      }, { important: false, tags: ['library', 'startup'] });
      console.log(`[PERF] _backgroundScan SKIPPED (cache age: ${Math.round(cacheAge / 60000)} min, ${songs.length} songs)`);
      _setLoading('Loading embeddings...');
      const tEmbWait = performance.now();
      if (embeddingPromise) await embeddingPromise;
      console.log(`[PERF] embeddings loaded from disk: ${Math.round(performance.now() - tEmbWait)} ms`);
      _setLoading('Merging embeddings...');
      const tMerge = performance.now();
      const mergedCount = _mergeLocalEmbeddings();
      console.log(`[PERF] _mergeLocalEmbeddings: ${mergedCount} merged in ${Math.round(performance.now() - tMerge)} ms`);
      if (mergedCount > 0 && embeddings.length > 0) {
        const tRec = performance.now();
        setRec(new Recommender(embeddings)); rec.lam = _tuning.adventurous;
        console.log(`[PERF] new Recommender(${embeddings.length} embeddings): ${Math.round(performance.now() - tRec)} ms`);
        const tGpu = performance.now();
        attachGpuToRec(rec, embeddings);
        console.log(`[PERF] attachGpuToRec: ${Math.round(performance.now() - tGpu)} ms`);
        _dcbs.invalidateArtistMap();
        _invalidateEmbIdxMap();
      }
      await _loadRemovedEmbeddingKeys();
      _applyRemovedEmbeddingFlags();
      _identifyOrphans();
      await loadFavorites();
      console.log(`[PERF] cache-skip pre-ready total: ${Math.round(performance.now() - scanT0)} ms`);
      // Fix F: fire embeddings-ready BEFORE buildProfileVec().
      // renderSimilar() uses rec.recommendSingle(currentSongEmbIdx, 8, ...) which
      // needs only the recommender + current song's embedding — not profileVec.
      // buildProfileVec is only required for taste-weighted surfaces (For You /
      // Because You Played / Unexplored), and those have their own discover
      // cache fallback (renderProfile / renderCachedBecauseYouPlayed) that
      // covers the brief gap until profileVec finishes.
      _setLoading('');
      _activity('embedding', 'embedding_ready', 'Embeddings ready after cached startup', {
        embeddingCount: embeddings.length,
        songCount: songs.length,
      }, { important: true, tags: ['embedding'] });
      _markEmbeddingsReady();
      // Build the profile vector in the background. When it lands, taste-weighted
      // surfaces refresh on their own next-render path; we don't block on it here.
      buildProfileVec('startup_embeddings_ready_cached', { logDeltas: false })
        .then(v => { setProfileVec(v); })
        .catch(e => { console.error('buildProfileVec failed (skip path, async):', e); setProfileVec(null); });
      if (rec && recToggle && state.current != null && songs[state.current] && songs[state.current].hasEmbedding) {
        _dcbs.softRefreshQueue();
      }
      setScanComplete(true);
      for (const cb of scanCallbacks) {
        try { cb(); } catch (e) { /* ignore */ }
      }
      _triggerAlbumArtExtraction();
      _triggerAutoEmbedding();
      _scheduleDeferredDeletionCheck();
      return;
    }

    _setLoading('Scanning songs on device...');
    _activity('library', 'library_scan_started', 'Started library scan', { force }, { important: false, tags: ['library'] });
    console.log('[PERF] _backgroundScan start');
    const result = await MusicBridge.scanAudioFiles();
    const scannedSongs = result.songs || [];
    _activity('library', 'library_scan_completed', `Library scan completed with ${scannedSongs.length} files`, {
      scannedCount: scannedSongs.length,
      force,
    }, { important: true, tags: ['library'] });
    console.log(`[PERF] scanAudioFiles: ${scannedSongs.length} files in`, Math.round(performance.now() - scanT0), 'ms');

    // Build a set of existing filenames (lowercased)
    const existingByFilename = {};
    for (const s of songs) {
      existingByFilename[s.filename.toLowerCase()] = s;
    }

    // Snapshot previous filePaths so we can detect files deleted externally
    // (via a file manager, another app, user wiping storage, etc.). The scanned
    // list is authoritative for what is actually on the device right now.
    const prevFilePaths = new Set();
    for (const s of songs) {
      if (s.filePath) prevFilePaths.add(s.filePath);
    }

    let newCount = 0;
    for (const scanned of scannedSongs) {
      const fnLower = scanned.filename.toLowerCase();
      if (existingByFilename[fnLower]) {
        // Update existing song with scan data
        const existing = existingByFilename[fnLower];
        existing.filePath = scanned.path;
        existing.artPath = scanned.artPath || existing.artPath || null;
        existing.dateModified = scanned.dateModified || 0;
        // Update metadata from MediaStore if it's better
        if (scanned.title && scanned.title !== scanned.filename) {
          existing.title = scanned.title;
        }
        if (scanned.artist && scanned.artist !== 'Unknown' && scanned.artist !== '<unknown>') {
          existing.artist = scanned.artist;
        }
        if (scanned.album && scanned.album !== 'Unknown' && scanned.album !== '<unknown>') {
          existing.album = scanned.album;
        }
      } else {
        // New song not in Colab embeddings
        const newSong = {
          id: songs.length,
          filename: scanned.filename,
          title: scanned.title || scanned.filename.replace(/\.[^.]+$/, ''),
          artist: (scanned.artist && scanned.artist !== '<unknown>') ? scanned.artist : 'Unknown',
          album: (scanned.album && scanned.album !== '<unknown>') ? scanned.album : 'Unknown',
          hasEmbedding: false,
          embeddingIndex: null,
          filePath: scanned.path,
          artPath: scanned.artPath || null,
          dateModified: scanned.dateModified || 0,
        };
        existingByFilename[fnLower] = newSong;
        songs.push(newSong);
        newCount++;
      }
    }

    // Detect files that existed in the cached library but are no longer on the
    // device. Guard against an empty/failed scan wiping out a legitimate library:
    // if the scanner returned zero while the previous library had files, treat
    // this as a scan anomaly (SD-card unmount, permission blip, MediaStore
    // temporarily empty) and skip the deletion pass instead of orphaning every
    // song the user owns.
    const scannedPaths = new Set();
    for (const scanned of scannedSongs) {
      if (scanned && scanned.path) scannedPaths.add(scanned.path);
    }
    if (scannedPaths.size === 0 && prevFilePaths.size > 0) {
      _activity('library', 'library_scan_anomaly', 'Scan returned zero files while cache had songs — skipping deletion detection', {
        cachedCount: prevFilePaths.size,
      }, { important: true, tags: ['library'], level: 'warning' });
      console.warn(`[SCAN] Anomaly: 0 scanned vs ${prevFilePaths.size} cached — skipping deletion pass`);
    } else {
      const missingPaths = new Set();
      for (const p of prevFilePaths) {
        if (!scannedPaths.has(p)) missingPaths.add(p);
      }
      if (missingPaths.size > 0) {
        _markSongsMissing(missingPaths, 'scan_external_deletion');
      }
    }

    const stateSnapshot = _snapshotStateBySongRefs();
    setSongs(_dedupeSongsArray(songs)); _normalizeSongArtPaths(songs); _applyDislikeFlags();
    _invalidateFilenameMap();
    _invalidatePathMap();
    _restoreStateBySongRefs(stateSnapshot);
    _rebuildAlbums();
    _dcbs.invalidateArtistMap();
    _invalidateEmbIdxMap();

    // Wait for embeddings to finish loading before merging
    if (embeddingPromise) await embeddingPromise;

    // Now merge local embeddings (needs filePaths to be set from scan)
    _setLoading('Merging embeddings...');
    const mergedCount = _mergeLocalEmbeddings();

    // Rebuild recommender if local embeddings were merged (new vectors added)
    if (mergedCount > 0 && embeddings.length > 0) {
      setRec(new Recommender(embeddings)); rec.lam = _tuning.adventurous;
      attachGpuToRec(rec, embeddings);
      _dcbs.invalidateArtistMap();
      _invalidateEmbIdxMap();
    }

    // Apply any user-removed embedding flags (ISSUE-6)
    await _loadRemovedEmbeddingKeys();
    _applyRemovedEmbeddingFlags();

    // Identify orphaned songs (in embeddings but not on device)
    _identifyOrphans();

    // Save song library (metadata from scan) for instant UI on next startup
    _saveSongLibrary();

    // Reload favorites here (cheap) so the next render can read them.
    await loadFavorites();

    // Fix F: fire embeddings-ready BEFORE buildProfileVec() so renderSimilar()
    // can show Most Similar immediately. analyzeProfile (For You) does need
    // profileVec, but it has its own discover cache fallback that covers the
    // sub-second gap. profileVec is filled in by the .then() below.
    _setLoading('');
    _activity('embedding', 'embedding_ready', 'Embeddings ready after library scan', {
      embeddingCount: embeddings.length,
      profileReady: false,
    }, { important: true, tags: ['embedding'] });
    console.log(`[PERF] Embeddings ready: rec=${!!rec}, ${embeddings.length} embeddings`);
    _markEmbeddingsReady();

    // Build the profile vector in the background after the ready event so
    // similar songs render without waiting on it. Taste-weighted surfaces pick
    // up the new vector via the existing recommendation rebuild path.
    buildProfileVec('startup_embeddings_ready_scan', { logDeltas: false })
      .then(v => { setProfileVec(v); })
      .catch(e => { console.error('buildProfileVec failed (scan path, async):', e); setProfileVec(null); });

    // If a song is currently playing with shuffle queue, upgrade to AI recs
    if (rec && recToggle && state.current != null && songs[state.current] && songs[state.current].hasEmbedding) {
      _dcbs.softRefreshQueue();
    }

    setScanComplete(true);
    const playable = songs.filter(s => s.filePath);
    const embedded = playable.filter(s => s.hasEmbedding);
    const allEmbedded = playable.length > 0 && embedded.length === playable.length;
    console.log(`Scan complete: ${newCount} new, ${songs.length} total, ${embedded.length}/${playable.length} embedded${allEmbedded ? ' (ALL DONE)' : ''}`);

    // Notify callbacks
    for (const cb of scanCallbacks) {
      try { cb(); } catch (e) { /* ignore */ }
    }

    // Extract album art in background for songs that don't have cached art yet
    _triggerAlbumArtExtraction();

    // Auto-embed songs that don't have embeddings yet
    _triggerAutoEmbedding();
  } catch (e) {
    console.error('Background scan failed:', e);
    _activity('library', 'library_scan_failed', `Background scan failed: ${e.message || e}`, {
      error: e.message || String(e),
    }, { important: true, tags: ['library', 'error'], level: 'error' });
    setScanComplete(true);
    _setLoading('');
    _markEmbeddingsReady();
    for (const cb of scanCallbacks) {
      try { cb(); } catch (e2) { /* ignore */ }
    }
  }
}

// --- Favorites I/O ---

export async function loadFavorites() {
  try {
    const { value } = await Preferences.get({ key: 'favorites' });
    console.log('[FAV] loadFavorites: raw value =', value ? value.substring(0, 200) : 'null');
    if (value) {
      const data = JSON.parse(value);
      if (data.filenames) {
        setFavorites(new Set());
        const fnMap = _getFilenameMap();
        for (const fn of data.filenames) {
          const sid = fnMap[fn.toLowerCase()];
          if (sid != null) {
            favorites.add(sid);
          } else {
            console.warn('[FAV] loadFavorites: filename not found in songs:', fn);
          }
        }
      } else if (data.ids) {
        setFavorites(new Set(data.ids.filter(id => id < songs.length)));
      }
    }
  } catch (e) {
    console.error('[FAV] loadFavorites ERROR:', e);
  }
  console.log('[FAV] loadFavorites: loaded', favorites.size, 'favorites, ids:', [...favorites]);
}

export async function saveFavorites() {
  const filenames = [...favorites]
    .filter(id => id < songs.length)
    .map(id => songs[id].filename);
  const payload = JSON.stringify({ filenames });
  console.log('[FAV] saveFavorites: saving', filenames.length, 'favorites:', filenames);
  await Preferences.set({ key: 'favorites', value: payload });
  // Verify it was actually saved
  const check = await Preferences.get({ key: 'favorites' });
  console.log('[FAV] saveFavorites: verified saved =', !!check.value, 'length =', (check.value || '').length);
}

// --- Playlist helpers ---

export function _normalizePlaylistName(name) {
  return String(name || '').replace(/\s+/g, ' ').trim().slice(0, 60);
}

export function _normalizePlaylist(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const name = _normalizePlaylistName(raw.name);
  if (!name) return null;
  const rawSongs = Array.isArray(raw.songFilenames)
    ? raw.songFilenames
    : (Array.isArray(raw.songs) ? raw.songs : []);
  const songFilenames = [...new Set(rawSongs
    .map(item => typeof item === 'string' ? item : (item && item.filename))
    .map(fn => String(fn || '').trim())
    .filter(Boolean))];
  return {
    id: String(raw.id || `pl_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`),
    name,
    songFilenames,
    createdAt: Number(raw.createdAt) || Date.now(),
    updatedAt: Number(raw.updatedAt) || Date.now(),
  };
}

export function _findPlaylistIndex(playlistId) {
  return playlists.findIndex(pl => pl.id === playlistId);
}

export function _getPlaylistById(playlistId) {
  const idx = _findPlaylistIndex(playlistId);
  return idx >= 0 ? playlists[idx] : null;
}

export function _resolvePlaylistSongIds(playlist) {
  if (!playlist) return [];
  const fnMap = _getFilenameMap();
  const ids = [];
  const seen = new Set();
  for (const fn of playlist.songFilenames || []) {
    const sid = fnMap[String(fn).toLowerCase()];
    if (sid == null || !songs[sid] || !songs[sid].filePath || seen.has(sid)) continue;
    seen.add(sid);
    ids.push(sid);
  }
  return ids;
}

export function _playlistSummary(playlist, previewLimit = 4) {
  const ids = _resolvePlaylistSongIds(playlist);
  return {
    id: playlist.id,
    name: playlist.name,
    count: ids.length,
    preview: ids.slice(0, previewLimit).map(sid => ({
      id: sid,
      title: songs[sid].title,
      artist: songs[sid].artist,
      album: songs[sid].album,
      hasEmbedding: songs[sid].hasEmbedding,
      artPath: songs[sid].artPath,
    })),
    createdAt: playlist.createdAt,
    updatedAt: playlist.updatedAt,
  };
}

// --- Playlists I/O ---

export async function loadPlaylists() {
  try {
    const { value } = await Preferences.get({ key: PLAYLISTS_PREF_KEY });
    if (!value) {
      setPlaylists([]);
      return;
    }
    const data = JSON.parse(value);
    const list = Array.isArray(data) ? data : (Array.isArray(data.playlists) ? data.playlists : []);
    setPlaylists(list
      .map(_normalizePlaylist)
      .filter(Boolean)
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0)));
  } catch (e) {
    setPlaylists([]);
  }
}

export async function savePlaylists() {
  const payload = JSON.stringify({
    playlists: playlists.map(pl => ({
      id: pl.id,
      name: pl.name,
      songFilenames: pl.songFilenames || [],
      createdAt: pl.createdAt || Date.now(),
      updatedAt: pl.updatedAt || Date.now(),
    })),
  });
  await Preferences.set({ key: PLAYLISTS_PREF_KEY, value: payload });
}
