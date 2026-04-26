/**
 * IsaiVazhi UI — Capacitor version.
 * No server fetch calls — all logic runs locally via engine.js.
 */

// Global error handler to surface hidden errors
window.onerror = (msg, src, line, col, err) => {
  const el = document.getElementById('debug-toast') || (() => {
    const d = document.createElement('div');
    d.id = 'debug-toast';
    d.style.cssText = 'position:fixed;top:10px;left:50%;transform:translateX(-50%);background:#c00;color:#fff;padding:8px 16px;border-radius:8px;z-index:99999;font-size:11px;max-width:90%;word-break:break-all;';
    document.body.appendChild(d);
    return d;
  })();
  el.textContent = 'JS ERROR: ' + msg + ' (line ' + line + ')';
  el.style.opacity = '1';
};
window.onunhandledrejection = (e) => {
  console.error('Unhandled rejection:', e.reason);
};

import { Filesystem, Directory } from '@capacitor/filesystem';
import { App } from '@capacitor/app';
import * as engine from './engine.js';
import { MusicBridge } from './music-bridge.js';
import { initActivityLog, logActivity } from './activity-log.js';

// On-screen debug logger — writes to #debugLogText + console
function _dbg(msg) {
  const ts = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 });
  const line = ts + ' ' + msg;
  console.log('[DBG] ' + msg);
  try {
    const el = document.getElementById('debugLogText');
    if (el) {
      el.textContent += line + '\n';
      const panel = document.getElementById('debugLogPanel');
      if (panel) panel.scrollTop = panel.scrollHeight;
    }
  } catch (e) { /* ignore */ }
}


let allSongs = [];
let allAlbums = [];
let currentSong = null;
let currentIsFav = false;
let activeTab = 'discover';
let shuffleOn = false;
let loopMode = 'all';
let nextSongInfo = null;
let _recsShouldFocusCurrent = false;
let _artUiRefreshTimer = null;
const _artRequestQueue = [];
const _artRequestState = new Map();
let _artRequestActive = 0;
const ART_REQUEST_CONCURRENCY = 4;

// Native audio state — updated by audioTimeUpdate events from native service
let nativeAudioPos = 0;   // seconds
let nativeAudioDur = 0;   // seconds
let nativeAudioPlayedMs = 0;       // cumulative listened ms from native accumulator
let nativeAudioDurationMs = 0;     // track duration in ms from native
let nativePlaybackInstanceId = 0;   // native playback-instance authority for recovery
let nativeAudioPlaying = false;
let nativeFileLoaded = false; // true once playAudio has been called this session
let _initRestoreComplete = false; // true after restorePlaybackState resolves
let _quickRestoreInfo = null; // cached song info for UI only; not used as a playback source
let _miniPlayerFromQuickDisplay = false; // mini player was shown from quickDisplay; keep visible even if engine restore returned null (library not ready yet)
let _startupPlaybackTouched = false; // user started playback before startup restore finished
let _pendingStartupResume = false; // play was tapped before authoritative restore completed
let _aiHydrationState = 'cold'; // cold -> playback_ready -> ai_hydrating -> ai_ready
let _scanCompleted = false;
let _embeddingsReadyEvent = false;
let _aiReadyCommitted = false;
let _lastPlaybackIntentAt = 0;
let _lastPlaybackPersistAt = 0;
let _lastPlaybackPersistPos = -1;
let _nativeAudioEventsBound = false;
const NAV_GUARD_MS = 320;
const STARTUP_AI_HYDRATION_DELAY_MS = 1500;
const PLAY_INTENT_AI_DEFER_MS = 4000;
const _lastNavAtByKind = { next: 0, prev: 0 };

function _notePlaybackIntent() {
  _lastPlaybackIntentAt = Date.now();
}

function _shouldBlockRapidNav(kind) {
  const now = Date.now();
  const lastAt = _lastNavAtByKind[kind] || 0;
  if ((now - lastAt) < NAV_GUARD_MS) return true;
  _lastNavAtByKind[kind] = now;
  return false;
}

function _resolveSongFromNativeState(data) {
  if (!data) return null;
  const songs = engine.getSongs();
  if (!songs || songs.length === 0) return null;

  if (data.filePath) {
    const match = songs.find(s => s && s.filePath && s.filePath.toLowerCase() === String(data.filePath).toLowerCase());
    if (match) return match;
  }

  const filename = data.filename || (data.filePath ? String(data.filePath).split(/[\\/]/).pop() : null);
  if (filename) {
    const match = songs.find(s => s && s.filename && s.filename.toLowerCase() === String(filename).toLowerCase());
    if (match) return match;
  }

  return null;
}

function _buildPendingListenSnapshot(reason) {
  if (currentSong == null) return null;
  const songs = engine.getSongs();
  const song = songs && songs[currentSong];
  if (!song || !song.filename) return null;

  const durationMs = nativeAudioDurationMs > 0
    ? Math.round(nativeAudioDurationMs)
    : (nativeAudioDur > 0 ? Math.round(nativeAudioDur * 1000) : 0);
  const playedMs = nativeAudioDurationMs > 0
    ? Math.round(Math.max(0, nativeAudioPlayedMs))
    : (nativeAudioDur > 0 ? Math.round(Math.max(0, nativeAudioPos) * 1000) : 0);
  if (!(durationMs > 0)) return null;

  return {
    songId: currentSong,
    filename: song.filename,
    title: song.title || song.filename,
    artist: song.artist || '',
    album: song.album || '',
    playbackInstanceId: Number(nativePlaybackInstanceId) || 0,
    playedMs: Math.min(playedMs, durationMs),
    durationMs,
    reason,
    capturedAt: new Date().toISOString(),
  };
}

async function _persistPendingListenEvidence(reason) {
  try {
    const snapshot = _buildPendingListenSnapshot(reason);
    if (!snapshot) return false;
    const result = await engine.savePendingListenSnapshot(snapshot);
    if (result && result.saved) {
      logActivity({
        category: 'playback',
        type: 'pending_listen_saved',
        message: `Saved pending listen snapshot on ${reason}`,
        data: {
          reason,
          filename: snapshot.filename,
          playbackInstanceId: snapshot.playbackInstanceId,
          playedMs: snapshot.playedMs,
          durationMs: snapshot.durationMs,
        },
        tags: ['listen', 'recovery'],
        important: false,
      });
      return true;
    }
  } catch (e) { /* best effort */ }
  return false;
}

async function _clearPendingListenIfResolvedByNative(data) {
  try {
    const prevPlaybackInstanceId = Number(data && data.prevPlaybackInstanceId) || 0;
    let cleared = false;
    if (prevPlaybackInstanceId > 0) {
      cleared = await engine.clearPendingListenSnapshot({ playbackInstanceId: prevPlaybackInstanceId });
    }
    if (!cleared && data && data.prevFilename) {
      cleared = await engine.clearPendingListenSnapshot({ filename: data.prevFilename });
    }
    if (cleared) {
      logActivity({
        category: 'playback',
        type: 'pending_listen_cleared',
        message: 'Cleared pending listen after native transition',
        data: {
          action: data.action || '',
          prevPlaybackInstanceId,
          prevFraction: data.prevFraction,
        },
        tags: ['listen', 'recovery'],
        important: false,
      });
    }
    return cleared;
  } catch (e) {
    return false;
  }
}

function _findMatchingNativeTransition(pendingSnapshot, transitions) {
  if (!pendingSnapshot || !Array.isArray(transitions) || transitions.length === 0) return null;
  const targetInstanceId = Number(pendingSnapshot.playbackInstanceId) || 0;
  if (!(targetInstanceId > 0)) return null;
  for (let i = transitions.length - 1; i >= 0; i--) {
    const evt = transitions[i];
    if ((Number(evt && evt.prevPlaybackInstanceId) || 0) === targetInstanceId) return evt;
  }
  return null;
}

async function _recoverPendingListenIfNeeded(liveAudioState = null) {
  try {
    const pending = await engine.loadPendingListenSnapshot();
    if (!pending) return;

    let recentTransitions = [];
    try {
      const payload = await MusicBridge.getRecentPlaybackTransitions({ limit: 24 });
      recentTransitions = Array.isArray(payload && payload.items) ? payload.items : [];
    } catch (e) { /* ignore */ }

    const matchingTransition = _findMatchingNativeTransition(pending, recentTransitions);
    const livePlaybackInstanceId = Number(liveAudioState && liveAudioState.currentPlaybackInstanceId) || 0;
    const nativeCompletedState = !!(liveAudioState && liveAudioState.completedState);
    const nativeHasLiveSession = !!(liveAudioState && liveAudioState.filePath && (liveAudioState.isPlaying || liveAudioState.position > 0 || liveAudioState.duration > 0));
    const nativeHasUsableSession = nativeHasLiveSession && !nativeCompletedState;

    if (matchingTransition) {
      const authoritativeAction = matchingTransition.action || 'native_transition_recovery';
      const result = engine.recordRecoveredListen({
        ...pending,
        songId: matchingTransition.prevSongId != null ? matchingTransition.prevSongId : pending.songId,
        filename: matchingTransition.prevFilename || pending.filename,
        playedMs: matchingTransition.prevPlayedMs != null ? matchingTransition.prevPlayedMs : pending.playedMs,
        durationMs: matchingTransition.prevDurationMs != null ? matchingTransition.prevDurationMs : pending.durationMs,
        reason: authoritativeAction,
      }, {
        fraction: matchingTransition.prevFraction,
        transitionAction: authoritativeAction,
      });
      await engine.clearPendingListenSnapshot({ playbackInstanceId: pending.playbackInstanceId, filename: pending.filename });
      logActivity({
        category: 'playback',
        type: 'pending_listen_recovered',
        message: result && result.ok
          ? 'Recovered pending listen from native transition history'
          : 'Dropped pending listen after native transition history match',
        data: {
          pendingPlaybackInstanceId: pending.playbackInstanceId,
          action: matchingTransition.action || '',
          recovered: !!(result && result.ok),
          fraction: result && result.fraction != null ? result.fraction : matchingTransition.prevFraction,
          filename: (result && result.filename) || pending.filename,
        },
        tags: ['listen', 'recovery'],
        important: true,
      });
      return;
    }

    if (nativeHasUsableSession && livePlaybackInstanceId > 0 && livePlaybackInstanceId === (Number(pending.playbackInstanceId) || 0)) {
      logActivity({
        category: 'playback',
        type: 'pending_listen_deferred',
        message: 'Pending listen snapshot kept because the same native playback instance is still active',
        data: {
          playbackInstanceId: pending.playbackInstanceId,
          filename: pending.filename,
        },
        tags: ['listen', 'recovery'],
        important: false,
      });
      return;
    }

    const result = engine.recordRecoveredListen(pending, {
      transitionAction: 'background_recovery',
    });
    await engine.clearPendingListenSnapshot({ playbackInstanceId: pending.playbackInstanceId, filename: pending.filename });
    logActivity({
      category: 'playback',
      type: 'pending_listen_recovered',
      message: result && result.ok
        ? 'Recovered pending listen from saved snapshot'
        : 'Dropped pending listen snapshot during recovery',
      data: {
        playbackInstanceId: pending.playbackInstanceId,
        recovered: !!(result && result.ok),
        fraction: result && result.fraction != null ? result.fraction : null,
        filename: (result && result.filename) || pending.filename,
      },
      tags: ['listen', 'recovery'],
      important: true,
    });
  } catch (e) { /* best effort */ }
}

async function _refreshNativePlaybackInstanceId() {
  try {
    const liveAudioState = await MusicBridge.getAudioState();
    nativePlaybackInstanceId = Number(liveAudioState && liveAudioState.currentPlaybackInstanceId) || nativePlaybackInstanceId;
    if (typeof liveAudioState?.playedMs === 'number') nativeAudioPlayedMs = liveAudioState.playedMs;
    if (typeof liveAudioState?.durationMs === 'number' && liveAudioState.durationMs > 0) nativeAudioDurationMs = liveAudioState.durationMs;
    if (nativePlaybackInstanceId > 0 && currentSong != null) {
      engine.bindCurrentPlaybackStartInstance(nativePlaybackInstanceId, currentSong);
    }
  } catch (e) { /* ignore */ }
}

function _focusCurrentRecsRow() {
  const currentRow = document.querySelector('#recs-list .song-item.playing');
  if (!currentRow) return;
  currentRow.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'auto' });
}

function _scheduleRecsFocusCurrent() {
  _recsShouldFocusCurrent = true;
  const run = () => {
    if (!_recsShouldFocusCurrent) return;
    _focusCurrentRecsRow();
    _recsShouldFocusCurrent = false;
  };
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(() => requestAnimationFrame(run));
  } else {
    setTimeout(run, 30);
  }
}

function _resolveSongForArt(input) {
  if (input == null) return null;
  const songs = engine.getSongs();
  if (typeof input === 'number') return songs[input] || null;
  if (input.id != null && songs[input.id]) return songs[input.id];
  return input;
}

function _scheduleArtUiRefresh() {
  if (_artUiRefreshTimer) return;
  _artUiRefreshTimer = setTimeout(() => {
    _artUiRefreshTimer = null;
    allSongs = engine.getPlayableSongs();
    allAlbums = engine.getAlbums();
    _songsDirty = true;
    _albumsDirty = true;
    if (activeTab === 'songs') {
      renderSongs(allSongs);
      _songsDirty = false;
    }
    if (activeTab === 'albums') {
      renderAlbums(allAlbums);
      _albumsDirty = false;
    }
    if (activeTab === 'recs') {
      refreshStateUI();
      return;
    }
    const discoverPanel = document.getElementById('panel-discover');
    if (discoverPanel && discoverPanel.querySelector('.taste-weights-page')) {
      showTasteWeightsOverlay();
      return;
    }
    if (activeTab === 'discover' && discoverPanel && !discoverPanel.querySelector('.emb-detail-page, .viewall-header')) {
      renderDiscoverSnapshotFromCache({ fade: false });
      refreshVisibleDiscoverCardState();
    }
    if (activeTab === 'browse') {
      const browsePanel = document.getElementById('panel-browse');
      if (browsePanel && !browsePanel.querySelector('.viewall-header')) {
        loadPlaylistsUI();
        loadFavorites();
        if (_lastProfile) renderDiscoverTiles(_lastProfile);
      }
    }
    syncFullPlayer();
  }, 120);
}

function _pumpArtRequestQueue() {
  while (_artRequestActive < ART_REQUEST_CONCURRENCY && _artRequestQueue.length > 0) {
    const nextSongId = _artRequestQueue.shift();
    const entry = _artRequestState.get(nextSongId);
    const nextSong = _resolveSongForArt(nextSongId);
    if (!entry || !nextSong || !nextSong.filePath) {
      if (entry && entry.resolve) entry.resolve(false);
      continue;
    }
    if (entry.status !== 'queued') continue;
    entry.status = 'pending';
    _artRequestActive++;
    MusicBridge.getAlbumArtUri({ path: nextSong.filePath }).then((res) => {
      if (res && res.exists && res.uri) {
        nextSong.artPath = res.uri;
        entry.status = 'ready';
        entry.resolve(true);
        _scheduleArtUiRefresh();
      } else {
        nextSong.artPath = null;
        entry.status = 'missing';
        entry.resolve(false);
      }
    }).catch(() => {
      nextSong.artPath = null;
      entry.status = 'missing';
      entry.resolve(false);
    }).finally(() => {
      _artRequestActive = Math.max(0, _artRequestActive - 1);
      _pumpArtRequestQueue();
    });
  }
}

function _enqueueSongArt(songInput, opts = {}) {
  const song = _resolveSongForArt(songInput);
  if (!song || song.id == null || !song.filePath) return Promise.resolve(false);
  if (song.artPath) return Promise.resolve(true);

  const existing = _artRequestState.get(song.id);
  const retry = opts.retry === true;
  if (existing) {
    if (existing.status === 'pending' || existing.status === 'queued') return existing.promise;
    if (!retry && (existing.status === 'ready' || existing.status === 'missing')) return existing.promise;
  }

  let resolvePromise = null;
  const promise = new Promise(resolve => { resolvePromise = resolve; });
  _artRequestState.set(song.id, { status: 'queued', promise, resolve: resolvePromise });
  if (opts.priority) _artRequestQueue.unshift(song.id);
  else _artRequestQueue.push(song.id);
  _pumpArtRequestQueue();
  return promise;
}

function _applyArtFallback(imgEl, fallbackText, fallbackClass) {
  if (!imgEl) return;
  const parent = imgEl.parentElement;
  if (!parent) return;
  if (fallbackClass) parent.classList.add(fallbackClass);
  imgEl.remove();
  if (!parent.querySelector('.art-fallback-text')) {
    const span = document.createElement('span');
    span.className = 'art-fallback-text';
    span.textContent = fallbackText || '?';
    parent.appendChild(span);
  }
}

async function handleArtErrorUI(imgEl, songId, fallbackText = '?', fallbackClass = '') {
  if (!imgEl) return;
  if (imgEl.dataset.artRecovered === '1') {
    _applyArtFallback(imgEl, fallbackText, fallbackClass);
    return;
  }
  imgEl.dataset.artRecovered = '1';
  const song = _resolveSongForArt(songId);
  if (song) song.artPath = null;
  const recovered = await _enqueueSongArt(songId, { retry: true, priority: true });
  const recoveredSong = _resolveSongForArt(songId);
  if (recovered && recoveredSong && recoveredSong.artPath) {
    const recoveredUrl = getArtUrl(recoveredSong, { prime: false });
    if (recoveredUrl) {
      imgEl.src = recoveredUrl;
      return;
    }
  }
  _applyArtFallback(imgEl, fallbackText, fallbackClass);
}

function _artOnErrorAttr(songId, fallbackText, fallbackClass) {
  return `onerror='window._app.handleArtError(this, ${songId}, ${JSON.stringify(fallbackText || '?')}, ${JSON.stringify(fallbackClass || '')})'`;
}

function persistPlaybackState(force = false) {
  if (currentSong == null) return;
  const now = Date.now();
  const pos = nativeAudioPos || 0;
  if (!force) {
    if ((now - _lastPlaybackPersistAt) < 2000) return;
    if (Math.abs(pos - _lastPlaybackPersistPos) < 1.5) return;
  }
  _lastPlaybackPersistAt = now;
  _lastPlaybackPersistPos = pos;
  engine.savePlaybackState(pos, nativeAudioDur || 0);
}

function _setAiHydrationState(next) {
  if (_aiHydrationState === next) return;
  _aiHydrationState = next;
  logActivity({
    category: 'engine',
    type: 'ai_hydration_state_changed',
    message: `AI state -> ${next}`,
    data: { state: next },
    tags: ['startup', 'ai'],
    important: false,
  });
}

function _scheduleBackgroundHydration() {
  const start = () => {
    const recentPlaybackIntentAge = _lastPlaybackIntentAt > 0 ? (Date.now() - _lastPlaybackIntentAt) : Infinity;
    if (_pendingStartupResume || recentPlaybackIntentAge < PLAY_INTENT_AI_DEFER_MS) {
      const remaining = _pendingStartupResume
        ? 350
        : Math.max(350, PLAY_INTENT_AI_DEFER_MS - recentPlaybackIntentAge);
      _dbg('init: background scan deferred ' + remaining + 'ms after playback intent');
      setTimeout(start, remaining);
      return;
    }
    _setAiHydrationState('ai_hydrating');
    engine.startBackgroundScan();
    _dbg('init: background scan started');
  };
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(() => setTimeout(start, STARTUP_AI_HYDRATION_DELAY_MS));
  } else {
    setTimeout(start, STARTUP_AI_HYDRATION_DELAY_MS);
  }
}

function _commitAiReady(discoverCache) {
  if (_aiReadyCommitted || !_scanCompleted || !_embeddingsReadyEvent) return;
  _aiReadyCommitted = true;
  _setAiHydrationState('ai_ready');
  _dbg('commitAiReady: entry currentSong=' + currentSong + ' nativeAudioPlaying=' + nativeAudioPlaying + ' cachedForYou=' + ((_lastProfile && _lastProfile.forYou) || []).length + ' cachedByp=' + (_cachedBecauseYouPlayed || []).length + ' cachedUnexp=' + (_cachedUnexplored || []).length);

  allSongs = engine.getPlayableSongs();
  allAlbums = engine.getAlbums();
  _songsDirty = true;
  _albumsDirty = true;

  // Preserve the existing Discover snapshot. Only Most Similar refreshes
  // automatically once embeddings become ready; the other Discover rows now
  // wait for manual pull-to-refresh — with one-time repair below when the
  // cached state is clearly incomplete.
  renderSimilar();
  if ((_cachedUnexplored || []).length === 0) renderCachedUnexplored({ fade: false });

  // One-time repair: if the cached discover snapshot is missing or sparse
  // because embeddings were not merged when it was saved, reseed now that
  // embeddings are authoritative. This does NOT make Discover auto-refresh —
  // it only fills in gaps so cached sections actually appear on cold start.
  _repairDiscoverCacheOnReady();

  updateEmbeddingStatus();
  updateModeIndicator();
  refreshStateUI();
  hideStatusToast();
  _dbg('EVENT: aiReady');
  showStatusToast('Embeddings ready', 2000);
  _saveVisibleDiscoverCache();
}

// One-shot repair on the first aiReady commit. Called only from _commitAiReady.
// - Empty For You  → run analyzeProfile once and render + save
// - Fewer than 3 Because You Played sections → call getBecauseYouPlayed and accept
//   the fresh result ONLY if it produces more sections than currently cached
function _repairDiscoverCacheOnReady() {
  const forYouEmpty = !_lastProfile || !Array.isArray(_lastProfile.forYou) || _lastProfile.forYou.length === 0;
  const bypCount = (_cachedBecauseYouPlayed || []).length;

  if (bypCount < 3) {
    try {
      const freshByp = engine.getBecauseYouPlayed(3, 6, {}) || [];
      if (freshByp.length > bypCount) {
        _dbg('commitAiReady: repairing becauseYouPlayed ' + bypCount + ' -> ' + freshByp.length);
        _renderBecauseYouPlayedSections(freshByp, { fade: false });
      } else {
        _dbg('commitAiReady: byp repair skipped (fresh=' + freshByp.length + ' <= cached=' + bypCount + ')');
      }
    } catch (e) {
      _dbg('commitAiReady: byp repair failed ' + e.message);
    }
  }

  if (forYouEmpty) {
    _dbg('commitAiReady: repairing empty For You via analyzeProfile');
    engine.analyzeProfile(15, { refreshForYou: false })
      .then(data => {
        if (data) {
          renderProfile(data);
          _saveVisibleDiscoverCache();
          _dbg('commitAiReady: For You repair done, length=' + ((data.forYou || []).length));
        }
      })
      .catch(e => { _dbg('commitAiReady: For You repair failed ' + (e && e.message || e)); });
  }
}

// ===== INIT =====

let _toastTimer = null;
function showStatusToast(text, duration) {
  const el = document.getElementById('statusToast');
  if (!el) return;
  el.textContent = text;
  el.style.display = 'block';
  el.classList.remove('hidden');
  if (_toastTimer) clearTimeout(_toastTimer);
  if (duration) {
    _toastTimer = setTimeout(() => {
      el.classList.add('hidden');
      setTimeout(() => { el.style.display = 'none'; }, 300);
    }, duration);
  }
}
function hideStatusToast() {
  const el = document.getElementById('statusToast');
  if (!el) return;
  el.classList.add('hidden');
  setTimeout(() => { el.style.display = 'none'; }, 300);
}

let _recommendationStatusTimer = null;
function _setRecommendationStatus(text, show) {
  const el = document.getElementById('recommendationStatus');
  if (!el) return;
  if (!show || !text) {
    el.classList.remove('is-visible');
    el.textContent = '';
    return;
  }
  el.textContent = text;
  el.classList.add('is-visible');
}

function _formatRecommendationReason(reason) {
  switch (reason) {
    case 'favorite_toggle': return 'Updating recommendations after favorite...';
    case 'dislike_toggle': return 'Updating recommendations after dislike...';
    case 'playback_skip': return 'Updating recommendations after skip...';
    case 'playback_complete': return 'Updating recommendations after listen...';
    case 'queue_remove': return 'Updating recommendations after X...';
    case 'tuning_changed': return 'Applying recommendation tuning...';
    case 'reset_song_recommendation_history': return 'Refreshing recommendations after reset...';
    default: return 'Updating recommendations...';
  }
}

function _handleRecommendationRebuildStatus(state) {
  if (!state || !state.phase) return;
  if (state.phase === 'queued' || state.phase === 'running') {
    if (_recommendationStatusTimer) clearTimeout(_recommendationStatusTimer);
    const text = _formatRecommendationReason(state.reason);
    _recommendationStatusTimer = setTimeout(() => {
      _setRecommendationStatus(text, true);
    }, 320);
    return;
  }

  if (_recommendationStatusTimer) {
    clearTimeout(_recommendationStatusTimer);
    _recommendationStatusTimer = null;
  }
  _setRecommendationStatus('', false);

  if (state.phase === 'completed') {
    refreshStateUI();
    if (document.querySelector('#panel-discover .taste-weights-page')) {
      showTasteWeightsOverlay();
    }
  }
}

async function init() {
  try {
    await initActivityLog();
    logActivity({ category: 'app', type: 'app_opened', message: 'App opened', tags: ['startup'], important: true });
    // Show mini player IMMEDIATELY from cached display info (before loadData)
    const quickDisplay = await engine.getLastPlayedDisplay();
    if (quickDisplay) {
      document.getElementById('npTitle').textContent = quickDisplay.title;
      document.getElementById('npArtist').textContent = quickDisplay.artist + ' \u00b7 ' + (quickDisplay.album || '');
      document.getElementById('nowPlaying').style.display = 'block';
      document.getElementById('npRedDot').style.display = 'none';
      if (quickDisplay.duration > 0) {
        nativeAudioDur = quickDisplay.duration;
        nativeAudioPos = quickDisplay.currentTime;
        updateProgressUI(quickDisplay.currentTime, quickDisplay.duration);
      }
      updatePlayIcon(true); // paused state until we check native
      // Keep quick restore info only as a visual placeholder for the mini
      // player. Playback must wait for the authoritative restore path so it
      // resumes from the real saved position instead of briefly starting at 0.
      if (quickDisplay.filePath) {
        _quickRestoreInfo = {
          title: quickDisplay.title,
          artist: quickDisplay.artist,
          album: quickDisplay.album,
          filename: quickDisplay.filename,
          filePath: quickDisplay.filePath,
        };
      }
      _miniPlayerFromQuickDisplay = true;
      _dbg('init: quickDisplay shown, filename=' + quickDisplay.filename);
    }

    // Load discover cache BEFORE loadData — render instantly from checkpoint
    const discoverCache = await engine.loadDiscoverCache();
    if (discoverCache) {
      renderDiscoverFromCache(discoverCache);
      showStatusToast('Loading embeddings...', 0); // 0 = stay until manually hidden
    }

    const _t0 = performance.now();
    _dbg('init: loadData starting');
    engine.onLoadingStatus((msg) => {
      // Suppress background-scan toasts while the user is on the embedding detail
      // page — they're distracting and unrelated to the user's current action.
      const panel = document.getElementById('panel-discover');
      if (panel && panel.querySelector('.emb-detail-page')) return;
      showStatusToast(msg);
    });
    engine.onRecommendationRebuildStatus(_handleRecommendationRebuildStatus);
    await engine.loadData();
    _dbg('init: loadData done ' + Math.round(performance.now() - _t0) + 'ms, songs=' + engine.getPlayableSongs().length);
    allSongs = engine.getPlayableSongs();
    allAlbums = engine.getAlbums();
    _songsDirty = true;
    _albumsDirty = true;
    loadPlaylistsUI();
    if (!discoverCache) hideStatusToast();
    setupNativeAudioEvents();
    setupSeekDrag();
    setupFullPlayerGestures();
    setupNativeMediaListener();
    setupPositionPersistence();
    setupEmbeddingUI();

    // Load discover content (non-blocking) — skip if cache already rendered
    if (!discoverCache) loadDiscover();

    // Now resolve full playback state (needs songs array from loadData)
    _dbg('init: restoring playback state');
    const restored = await engine.restorePlaybackState();
    const pendingListenSnapshot = await engine.loadPendingListenSnapshot();
    _dbg('init: restore done ' + Math.round(performance.now() - _t0) + 'ms, currentSong=' + (restored ? restored.id : 'null') + ', filePath=' + (restored ? !!restored.filePath : 'n/a'));
    if (restored || pendingListenSnapshot) {
      let liveAudioState = null;
      let liveQueueState = null;
      try {
        liveAudioState = await MusicBridge.getAudioState();
      } catch (e) { /* ignore */ }
      nativePlaybackInstanceId = Number(liveAudioState && liveAudioState.currentPlaybackInstanceId) || nativePlaybackInstanceId;
      if (typeof liveAudioState?.playedMs === 'number') nativeAudioPlayedMs = liveAudioState.playedMs;
      if (typeof liveAudioState?.durationMs === 'number' && liveAudioState.durationMs > 0) nativeAudioDurationMs = liveAudioState.durationMs;
      const nativeCompletedState = !!(liveAudioState && liveAudioState.completedState);
      const nativeHasLiveSession = !!(liveAudioState && liveAudioState.filePath && (liveAudioState.isPlaying || liveAudioState.position > 0 || liveAudioState.duration > 0));
      const nativeHasUsableSession = nativeHasLiveSession && !nativeCompletedState;
      if (restored && nativeHasUsableSession) {
        try {
          liveQueueState = await MusicBridge.getQueueState();
        } catch (e) { /* ignore */ }
      }
      await _recoverPendingListenIfNeeded(liveAudioState);
      if (restored) {
        const preserveLivePlayback = (_startupPlaybackTouched || nativeFileLoaded) && nativeHasUsableSession;
        const liveSong = nativeHasUsableSession ? _resolveSongFromNativeState(liveAudioState) : null;
        const syncedNativeSong = nativeHasUsableSession
          ? (liveQueueState
              ? engine.syncQueueFromNativeSnapshot(liveQueueState, {
                  appendHistory: true,
                  currentSource: liveAudioState.isPlaying ? 'native_resume' : 'native_paused_restore',
                  ensurePlaybackStart: true,
                  playbackInstanceId: nativePlaybackInstanceId,
                })
              : engine.syncCurrentFromNativeState(liveAudioState, {
                  currentSource: liveAudioState.isPlaying ? 'native_resume' : 'native_paused_restore',
                  ensurePlaybackStart: true,
                  playbackInstanceId: nativePlaybackInstanceId,
                }))
          : null;
        const displaySong = syncedNativeSong || (nativeHasUsableSession && liveSong
          ? {
              id: liveSong.id,
              title: liveSong.title,
              artist: liveSong.artist,
              album: liveSong.album,
              filename: liveSong.filename,
              filePath: liveSong.filePath,
              hasEmbedding: liveSong.hasEmbedding,
              isFavorite: engine.isFavorite(liveSong.id),
            }
          : restored);

        currentSong = displaySong.id;
        currentIsFav = displaySong.isFavorite || false;
        _miniPlayerFromQuickDisplay = false; // real restore succeeded; no longer need the shim
        document.getElementById('npTitle').textContent = displaySong.title;
        document.getElementById('npArtist').textContent = displaySong.artist + ' \u00b7 ' + (displaySong.album || '');
        document.getElementById('nowPlaying').style.display = 'block';
        document.getElementById('npRedDot').style.display = 'none';
        updateHeartIcon(currentIsFav);
        updateModeIndicator();
        syncFullPlayer();
        refreshStateUI();

        if (preserveLivePlayback) {
          nativeAudioPos = liveAudioState.position || nativeAudioPos || 0;
          nativeAudioDur = liveAudioState.duration || nativeAudioDur || displaySong.duration || restored.duration || 0;
          nativeAudioPlaying = !!liveAudioState.isPlaying;
          nativeFileLoaded = true;
          updateProgressUI(nativeAudioPos, nativeAudioDur);
          updatePlayIcon(!nativeAudioPlaying);
          _dbg('init: preserving live playback during restore pos=' + nativeAudioPos + ' playing=' + nativeAudioPlaying);
        } else {
          if (restored.duration > 0) nativeAudioDur = restored.duration;
          if (nativeHasUsableSession) {
            nativeAudioPos = liveAudioState.position || 0;
            nativeAudioDur = liveAudioState.duration || nativeAudioDur;
            nativeAudioPlaying = !!liveAudioState.isPlaying;
            nativeFileLoaded = true;
            updateProgressUI(nativeAudioPos, nativeAudioDur);
            updatePlayIcon(!nativeAudioPlaying);
          } else if (restored.currentTime > 0) {
            nativeAudioPos = restored.currentTime;
            nativeAudioPlaying = false;
            nativeFileLoaded = false;
            updateProgressUI(restored.currentTime, nativeAudioDur);
            updatePlayIcon(true);
          } else {
            nativeFileLoaded = false;
          }
          if (nativeCompletedState) {
            _dbg('init: native session was in completed state, forcing cold restore path');
          }
        }
        if (_pendingStartupResume && !nativeHasUsableSession && displaySong.filePath && !nativeFileLoaded) {
          _pendingStartupResume = false;
          _dbg('init: honoring queued startup resume tap');
          await loadAndPlay({
            id: displaySong.id,
            title: displaySong.title,
            artist: displaySong.artist,
            album: displaySong.album,
            filename: displaySong.filename,
            filePath: displaySong.filePath,
            isFavorite: displaySong.isFavorite || false,
          }, restored.currentTime || nativeAudioPos || 0);
        }
      }
    }
    _quickRestoreInfo = null; // full restore done, no longer needed
    _initRestoreComplete = true;
    _pendingStartupResume = false;
    _setAiHydrationState('playback_ready');
    _dbg('init: READY — currentSong=' + currentSong + ' initComplete=true');

    // Start AI hydration only after playback-ready state is reached so early
    // play taps are not competing with embedding merge/profile work.
    _scheduleBackgroundHydration();

    // Track background scan completion, but defer AI-heavy UI refresh until the
    // embeddings-ready transition commits once.
    engine.onScanComplete(async () => {
      _dbg('EVENT: scanComplete');
      _scanCompleted = true;
      allSongs = engine.getPlayableSongs();
      allAlbums = engine.getAlbums();
      _songsDirty = true;
      _albumsDirty = true;
      // If user is currently on songs/albums tab, render now
      if (activeTab === 'songs') renderSongs(allSongs);
      if (activeTab === 'albums') renderAlbums(allAlbums);
      // If the initial restore could not resolve currentFilename because the song
      // library was not yet complete, retry now that the scan has populated it.
      if (currentSong == null && _miniPlayerFromQuickDisplay) {
        try {
          _dbg('scanComplete: retrying restorePlaybackState (initial restore did not set currentSong)');
          const retried = await engine.restorePlaybackState();
          if (retried && retried.id != null) {
            currentSong = retried.id;
            currentIsFav = retried.isFavorite || false;
            document.getElementById('npTitle').textContent = retried.title;
            document.getElementById('npArtist').textContent = retried.artist + ' \u00b7 ' + (retried.album || '');
            document.getElementById('nowPlaying').style.display = 'block';
            updateHeartIcon(currentIsFav);
            _miniPlayerFromQuickDisplay = false;
            _dbg('scanComplete: retry restore OK id=' + retried.id);
            if (_pendingStartupResume && retried.filePath && !nativeFileLoaded) {
              _pendingStartupResume = false;
              _dbg('scanComplete: honoring queued startup resume tap');
              await loadAndPlay({
                id: retried.id,
                title: retried.title,
                artist: retried.artist,
                album: retried.album,
                filename: retried.filename,
                filePath: retried.filePath,
                isFavorite: retried.isFavorite || false,
              }, retried.currentTime || nativeAudioPos || 0);
            }
          } else {
            _dbg('scanComplete: retry restore still null, keeping quickDisplay shim');
          }
        } catch (e) {
          _dbg('scanComplete: retry restore failed ' + e.message);
        }
      }
      // Refresh mini player state — mode indicator and red dot are now accurate
      updateModeIndicator();
      syncFullPlayer();
      _commitAiReady(discoverCache);
    });

    // Deferred deletion detection (cache-skip startup path) and play-time
    // file-missing reconciliation both fire this callback after the library
    // shape changed outside of the normal scan flow. Re-pull songs and
    // re-render the active tab so the UI reflects the new state.
    engine.onSongLibraryChanged(() => {
      _dbg('EVENT: songLibraryChanged');
      allSongs = engine.getPlayableSongs();
      allAlbums = engine.getAlbums();
      _songsDirty = true;
      _albumsDirty = true;
      if (activeTab === 'songs') renderSongs(allSongs);
      if (activeTab === 'albums') renderAlbums(allAlbums);
      refreshStateUI();
    });

    // Embeddings ready is the AI hydration completion signal. Commit the heavy
    // Discover refresh exactly once after both scan + embeddings transitions.
    engine.onEmbeddingsReady(() => {
      _dbg('EVENT: embeddingsReady');
      _embeddingsReadyEvent = true;
      _commitAiReady(discoverCache);
    });

    // When album art extraction finishes, refresh cards and current player art
    engine.onAlbumArtReady(() => {
      allSongs = engine.getPlayableSongs();
      allAlbums = engine.getAlbums();
      _songsDirty = true;
      _albumsDirty = true;
      if (activeTab === 'songs') { renderSongs(allSongs); _songsDirty = false; }
      if (activeTab === 'albums') { renderAlbums(allAlbums); _albumsDirty = false; }
      syncFullPlayer();
      _scheduleArtUiRefresh();
    });
  } catch (e) {
    console.error('Init failed:', e);
    document.body.innerHTML = `<div style="display:flex;height:100vh;align-items:center;justify-content:center;color:#e84545;font-family:sans-serif;padding:20px;text-align:center;">
      Failed to load: ${esc(e.message)}<br><br>Make sure embeddings.json and metadata.json are available.
    </div>`;
  }
}

// ===== AUDIO FILE ACCESS =====

async function getAudioUrl(songInfo) {
  // Use full file path if available (from scan)
  if (songInfo.filePath) {
    try {
      return window.Capacitor.convertFileSrc('file://' + songInfo.filePath);
    } catch (e) {
      console.error('convertFileSrc failed for path:', songInfo.filePath, e);
    }
  }
  // Fallback: try reading from known songs directory
  try {
    const result = await Filesystem.getUri({
      path: `songs_downloaded/${songInfo.filename}`,
      directory: Directory.ExternalStorage,
    });
    return window.Capacitor.convertFileSrc(result.uri);
  } catch (e) {
    console.error('Cannot access file:', songInfo.filename, e);
    return null;
  }
}

// ===== FAVORITES =====

function handleFavToggle() {
  try {
    if (currentSong == null) {
      showDebugToast('FAV: no song playing');
      return;
    }
    const result = engine.toggleFavorite(currentSong);
    if (result && result.ok) {
      currentIsFav = result.isFavorite;
      updateHeartIcon(currentIsFav);
      loadFavorites();
      updateFavCount(result.count);
      const songs = engine.getSongs();
      const title = songs[currentSong] ? songs[currentSong].title : '';
      logActivity({
        category: 'ui',
        type: 'favorite_button_pressed',
        message: `${result.isFavorite ? 'Favorited' : 'Unfavorited'} "${title}"`,
        data: { songId: currentSong, isFavorite: result.isFavorite },
        tags: ['favorite'],
        important: true,
      });
      showStatusToast(result.isFavorite ? `"${title}" added to favorites` : `"${title}" removed from favorites`, 2000);
      syncNativeNotificationMeta();
    } else {
      showStatusToast('Failed to update favorite', 2000);
    }
  } catch (e) {
    showDebugToast('FAV CRASH: ' + e.message);
    console.error('[FAV-UI] CRASH:', e);
  }
}

// Attach fav button listener directly via JS
document.addEventListener('DOMContentLoaded', () => {
  const favBtn = document.getElementById('favBtn');
  if (favBtn) {
    favBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      handleFavToggle();
    });
    favBtn.addEventListener('touchend', (e) => {
      e.preventDefault();
      e.stopPropagation();
      handleFavToggle();
    });
  }
});

function showDebugToast(msg) {
  let toast = document.getElementById('debug-toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'debug-toast';
    toast.style.cssText = 'position:fixed;top:10px;left:50%;transform:translateX(-50%);background:#333;color:#fff;padding:8px 16px;border-radius:8px;z-index:99999;font-size:13px;transition:opacity 0.3s;pointer-events:none;';
    document.body.appendChild(toast);
  }
  toast.textContent = msg;
  toast.style.opacity = '1';
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => { toast.style.opacity = '0'; }, 3000);
}

function updateFavCount(count) {
  // Update the fav badge in stats
  const statItems = document.querySelectorAll('#profile-stats .stat-item');
  for (const item of statItems) {
    const label = item.querySelector('.stat-label');
    if (label && label.textContent === 'Favs') {
      const num = item.querySelector('.stat-num');
      if (num) num.textContent = count;
    }
  }
}

function updateHeartIcon(isFav) {
  const btn = document.getElementById('favBtn');
  if (btn) {
    btn.classList.toggle('fav-active', isFav);
    btn.textContent = isFav ? '\u2665' : '\u2661';
  }
  const fpFav = document.getElementById('fpFavBtn');
  if (fpFav) {
    fpFav.classList.toggle('is-fav', isFav);
    fpFav.textContent = isFav ? '\u2665' : '\u2661';
  }
}

function syncNativeNotificationMeta() {
  if (currentSong == null) return;
  const songs = engine.getSongs();
  const song = songs[currentSong];
  if (!song) return;
  try {
    MusicBridge.updatePlaybackState({
      title: song.title || '',
      artist: song.artist || '',
      album: song.album || '',
      isPlaying: !!nativeAudioPlaying,
    });
  } catch (e) { /* ignore */ }
}

function loadFavorites() {
  const favs = engine.getFavoritesList();
  renderFavorites(favs);
}

function renderFavorites(favs) {
  _cachedFavorites = favs || [];
  renderDiscoverTiles(_lastProfile);
}

function getViewAllMeta(type) {
  if (type === 'mostPlayed') return { title: 'Most Played', empty: 'No history yet' };
  if (type === 'recentlyPlayed') return { title: 'Recently Played', empty: 'No history yet' };
  if (type === 'lastAdded') return { title: 'Last Added', empty: 'Nothing new' };
  if (type === 'neverPlayed') return { title: 'Never Played', empty: 'Played everything!' };
  if (type === 'noEmbedding') return { title: 'Songs Without AI Embedding', empty: 'Everything has AI ready' };
  if (type === 'favorites') return { title: 'Favorites', empty: 'No favorites yet' };
  if (type === 'dislikedSongs') return { title: 'Disliked Songs', empty: 'No disliked songs' };
  if (type === 'forYou') return { title: 'For You', empty: 'Play songs to get personalized picks' };
  if (type === 'similar') return { title: 'Most Similar', empty: 'Play a song to see similar picks' };
  return { title: 'View All', empty: 'Nothing here yet' };
}

function loadPlaylistsUI() {
  renderPlaylists(engine.getPlaylists());
}

function renderPlaylists(playlists) {
  const el = document.getElementById('playlists-list');
  if (!el) return;

  _cachedPlaylists = playlists || [];
  if (!_cachedPlaylists.length) {
    el.innerHTML = '<div class="playlist-empty">Create a playlist here, or use any song menu to add songs into one.</div>';
    return;
  }

  const cardHtml = (pl) => {
    const thumbs = (pl.preview || []).slice(0, 4).map(s => {
      const art = getArtUrl(s);
      if (art) {
        const letter = esc((s.title || '?')[0].toUpperCase());
        const onerr = s && s.id != null ? _artOnErrorAttr(s.id, letter, 'playlist-thumb-letter') : '';
        return `<div class="playlist-thumb"><img src="${art}" decoding="async" alt="" ${onerr}></div>`;
      }
      return `<div class="playlist-thumb playlist-thumb-letter"><span>${esc((s.title || '?')[0].toUpperCase())}</span></div>`;
    }).join('');
    const filler = (pl.preview || []).length < 4 ? '<div class="playlist-thumb playlist-thumb-empty"></div>'.repeat(4 - (pl.preview || []).length) : '';
    const grid = pl.count > 0
      ? `<div class="playlist-thumb-grid">${thumbs}${filler}</div>`
      : '<div class="playlist-card-empty">No songs yet</div>';
    return `<div class="playlist-card" onclick="window._app.openPlaylist('${pl.id}')">
      <div class="playlist-card-head">
        <div class="playlist-card-title">${esc(pl.name)}</div>
        <div class="playlist-card-count">${pl.count}</div>
      </div>
      ${grid}
    </div>`;
  };

  el.innerHTML = _cachedPlaylists.map(cardHtml).join('');
}

function refreshPlaylistViews() {
  loadPlaylistsUI();
  if (_currentPlaylistViewId) {
    if (engine.getPlaylistMeta(_currentPlaylistViewId)) {
      openPlaylistUI(_currentPlaylistViewId);
    } else {
      closeViewAll();
    }
  }
}

async function playFromFavoritesUI(id) {
  const frac = getListenFraction();
  const info = engine.playFromFavorites(id, frac);
  if (info) {
    await loadAndPlay(info);
    currentIsFav = info.isFavorite || false;
    updateHeartIcon(currentIsFav);
    refreshStateUI();
  }
}

async function playFromPlaylistUI(id, playlistId) {
  if (id === currentSong && nativeAudioPlaying) {
    if (!fullPlayerOpen) openFullPlayer();
    return;
  }
  const frac = getListenFraction();
  const song = engine.getSongs()[id];
  const meta = engine.getPlaylistMeta(playlistId);
  logActivity({
    category: 'ui',
    type: 'playlist_song_tapped',
    message: `Tapped "${song ? song.title : id}" from playlist "${meta ? meta.name : playlistId}"`,
    data: { songId: id, playlistId, playlistName: meta ? meta.name : '', prevFraction: frac, title: song ? song.title : '', filename: song ? song.filename : '' },
    tags: ['playlist', 'playback'],
    important: true,
  });
  const info = engine.playFromPlaylist(playlistId, id, frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
  }
}

// ===== DISCOVER TAB =====

function loadDiscover(opts = {}) {
  _clearQueuedDiscoverRefresh();
  // Render sync sections immediately — zero awaits
  if (opts.refreshDiscover === true) {
    renderBecauseYouPlayed({ refresh: true });
    renderUnexploredClusters({ refresh: true });
  } else {
    if ((_cachedBecauseYouPlayed || []).length > 0) renderCachedBecauseYouPlayed();
    else renderBecauseYouPlayed();

    if ((_cachedUnexplored || []).length > 0) renderCachedUnexplored();
    else renderUnexploredClusters();
  }
  // renderSimilar handles all 5 placeholder states internally based on embedding
  // readiness / per-song embedding / paused / in-progress / idle.
  renderSimilar();
  loadFavorites();
  loadPlaylistsUI();

  engine.loadRecentlyPlayed()
    .then(data => {
      _cachedRecentlyPlayed = data || [];
      if (_lastProfile) renderDiscoverTiles(_lastProfile);
      _saveVisibleDiscoverCacheDebounced();
    })
    .catch(() => {});
  // analyzeProfile: pass opts.refreshForYou through so pull-to-refresh (and only
  // pull-to-refresh / explicit refresh) triggers a fresh For You shuffle. All
  // other callers reuse the cached For You list.
  engine.analyzeProfile(15, { refreshForYou: opts.refreshForYou === true })
    .then(data => {
      if (data) {
        renderProfile(data);
        // Persist the populated profile (forYou etc.) into the discover cache so
        // subsequent cold starts render For You from cache instead of showing empty.
        _saveVisibleDiscoverCacheDebounced();
      }
    })
    .catch(() => {});
}

// Render discover page entirely from cached data — no engine calls needed
function renderDiscoverFromCache(cache) {
  // Profile stats
  if (cache.profile && cache.profile.stats) {
    renderProfile(cache.profile, { fade: false });
  }

  // Because You Played
  _cachedBecauseYouPlayed = cache.becauseYouPlayed || [];
  renderCachedBecauseYouPlayed({ fade: false });

  // Unexplored is intentionally snapshot-only here. If startup cache did not
  // include it, it stays empty until a manual Discover refresh populates it.
  _cachedUnexplored = Array.isArray(cache.unexplored) ? cache.unexplored : [];
  renderCachedUnexplored({ fade: false });

  // Reserve the Most Similar slot from cold startup so nothing below shifts
  // when real cards arrive. A loading placeholder occupies the same height as
  // a card row; renderSimilar() later refines to the correct per-song state.
  const similarHeader = document.getElementById('similar-header');
  const similarList = document.getElementById('similar-list');
  if (similarHeader) similarHeader.style.display = 'flex';
  if (similarList) {
    similarList.innerHTML = `
      <div class="similar-placeholder similar-placeholder-loading">
        <div class="similar-placeholder-text">Loading similar songs<span class="similar-loading-dots"><span></span><span></span><span></span></span></div>
      </div>`;
  }

  // Recently Played cache feeds tile grid
  if (cache.recentlyPlayed && cache.recentlyPlayed.length > 0) {
    _cachedRecentlyPlayed = cache.recentlyPlayed;
  }
  if (_lastProfile) renderDiscoverTiles(_lastProfile);

  // Favorites
  if (cache.favorites && cache.favorites.length > 0) {
    renderFavorites(cache.favorites);
  }

  // Queue + History — render Up Next tab from cached state
  if (cache.queue || cache.history) {
    renderRecs({
      queue: cache.queue || [],
      sessionLabel: cache.sessionLabel || '',
      recToggle: cache.recToggle != null ? cache.recToggle : true,
    });
    renderHistory(cache.history || [], cache.historyPos || 0);
  }

  // Embedding status — use cached stats for accurate display
  updateEmbeddingStatus();
}

function updateEmbeddingStatus() {
  // Embedding banner removed (ISSUE: embedding moved into stats tile).
  // The AI stat tile shows `embedded/total` and is updated whenever
  // renderProfile runs. Re-render if profile is available.
  if (_lastProfile) {
    try { renderProfile(_lastProfile, { fade: false }); } catch (e) {}
  }
}

// Track which embedding-detail collapsible sections are expanded so we can
// restore them across re-renders (ISSUE-11).
const _embDetailExpanded = {
  unmatched: false,
  unmatchedConfirm: false,
  orphanConfirm: false,
  removed: false,
  pending: false,
  pendingNew: false,
  pendingRemoved: false,
  pendingReembed: false,
};
let _embLogTab = 'common';
let _embDetailScrollTargetId = null;
let _tastePlaybackExpanded = false;
let _tasteEngineExpanded = false;
let _tasteLogsExpanded = false;
let _tastePlaybackVisibleCount = 10;
const TASTE_PLAYBACK_PAGE_SIZE = 10;
const TASTE_PLAYBACK_MAX = 30;
let _tuningInfoActiveKey = null;
let _tuningInfoTimer = null;
let _tasteResetInfoVisible = false;
const TUNING_INFO_AUTO_HIDE_MS = 3000;

function _hideTuningInfoPopup() {
  if (_tuningInfoActiveKey) {
    const prev = document.getElementById('tuningHint-' + _tuningInfoActiveKey);
    if (prev) prev.style.display = 'none';
  }
  if (_tuningInfoTimer) { clearTimeout(_tuningInfoTimer); _tuningInfoTimer = null; }
  _tuningInfoActiveKey = null;
}
function _toggleTuningInfoPopup(key) {
  const msg = (window._tuningHints && window._tuningHints[key]) || '';
  if (!msg) return;
  const wasSame = _tuningInfoActiveKey === key;
  _hideTuningInfoPopup();
  if (wasSame) return;
  const el = document.getElementById('tuningHint-' + key);
  if (!el) return;
  el.style.display = 'block';
  _tuningInfoActiveKey = key;
  _tuningInfoTimer = setTimeout(_hideTuningInfoPopup, TUNING_INFO_AUTO_HIDE_MS);
}

function toggleTasteResetInfoUI() {
  _tasteResetInfoVisible = !_tasteResetInfoVisible;
  const el = document.getElementById('tasteResetHelp');
  if (el) el.style.display = _tasteResetInfoVisible ? 'block' : 'none';
}

function _formatTasteScoreSnapshot(snapshot) {
  if (!snapshot || snapshot.score == null) return '-';
  const score = Number(snapshot.score) || 0;
  const direction = snapshot.direction || (score > 0 ? 'positive' : (score < 0 ? 'negative' : 'neutral'));
  return `${direction} ${score > 0 ? '+' : ''}${score.toFixed(2)}`;
}

function _formatSignedNumber(n, digits = 2) {
  const v = Number(n) || 0;
  return `${v > 0 ? '+' : ''}${v.toFixed(digits)}`;
}

function _formatDirectPlayLine(before, after) {
  const beforeDirect = before && before.directScore != null ? Number(before.directScore) || 0 : 0;
  const afterDirect = after && after.directScore != null ? Number(after.directScore) || 0 : 0;
  const delta = afterDirect - beforeDirect;
  return `${_formatSignedNumber(beforeDirect)} \u2192 ${_formatSignedNumber(afterDirect)} (\u0394 ${_formatSignedNumber(delta)})`;
}

function _formatSimilarityDeltaLine(after) {
  const afterBoost = after && after.similarityBoost != null ? Number(after.similarityBoost) || 0 : 0;
  return _formatSignedNumber(afterBoost);
}

function _formatTotalScoreLine(before, after) {
  const beforeScore = before && before.score != null ? Number(before.score) || 0 : 0;
  const afterScore = after && after.score != null ? Number(after.score) || 0 : 0;
  return `${_formatSignedNumber(beforeScore)} \u2192 ${_formatSignedNumber(afterScore)}`;
}

function _formatReasonCodes(codes) {
  return Array.isArray(codes) && codes.length > 0
    ? codes.map(code => String(code).replace(/_/g, ' ')).join(', ')
    : '';
}

function _formatActivityMeta(entry) {
  const d = entry && entry.data ? entry.data : {};
  const parts = [];
  if (entry.type === 'profile_summary_updated') {
    if (d.eventType) parts.push(`type ${d.eventType}`);
    if (d.plays != null) parts.push(`starts ${d.plays}`);
    if (d.skips != null) parts.push(`skips ${d.skips}`);
    if (d.completions != null) parts.push(`completions ${d.completions}`);
    if (d.fracsCount != null) parts.push(`fractions ${d.fracsCount}`);
    parts.push(d.avgFrac != null ? `avg ${Math.round(d.avgFrac * 100)}%` : 'avg -');
    if (d.lastFraction != null) parts.push(`last ${Math.round(d.lastFraction * 100)}%`);
  } else if (entry.type === 'profile_summary_rebuilt') {
    if (d.songCount != null) parts.push(`songs ${d.songCount}`);
    if (d.totalPlays != null) parts.push(`starts ${d.totalPlays}`);
    if (d.totalSkips != null) parts.push(`skips ${d.totalSkips}`);
    if (d.noFractionCount != null) parts.push(`no-frac ${d.noFractionCount}`);
  } else if (entry.type === 'session_weight_changed') {
    if (d.after && d.after.plays != null) parts.push(`positive ${d.after.plays}`);
    if (d.after && d.after.skips != null) parts.push(`skips ${d.after.skips}`);
    if (d.after && d.after.encounters != null) parts.push(`encounters ${d.after.encounters}`);
    if (d.afterFrac != null) parts.push(`session ${Math.round(d.afterFrac * 100)}%`);
  } else if (entry.type === 'profile_weight_changed') {
    if (d.positiveAfter != null) parts.push(`+ ${d.positiveAfter}`);
    if (d.negativeAfter != null) parts.push(`- ${d.negativeAfter}`);
  } else if (entry.type === 'playback_signal_applied') {
    parts.push(`taste ${_formatTasteScoreSnapshot(d.tasteBefore)} -> ${_formatTasteScoreSnapshot(d.tasteAfter)}`);
    if (d.summaryAfter && d.summaryAfter.avgFrac != null) parts.push(`avg ${Math.round(d.summaryAfter.avgFrac * 100)}%`);
    if (d.summaryAfter && d.summaryAfter.fracsCount != null) parts.push(`fractions ${d.summaryAfter.fracsCount}`);
  } else if (entry.type === 'suspicious_review_snapshot_updated') {
    if (d.total != null) parts.push(`total ${d.total}`);
    if (d.positive != null) parts.push(`+ ${d.positive}`);
    if (d.negative != null) parts.push(`- ${d.negative}`);
    if (d.uncertain != null) parts.push(`? ${d.uncertain}`);
    if (d.suppressed != null) parts.push(`ignored ${d.suppressed}`);
    if (d.reasons && typeof d.reasons === 'object') {
      const reasonText = Object.entries(d.reasons).map(([key, count]) => `${key}:${count}`).join(', ');
      if (reasonText) parts.push(reasonText);
    }
  } else if (entry.type === 'suspicious_review_ignored') {
    if (d.reasonCodes) parts.push(`reasons ${_formatReasonCodes(d.reasonCodes)}`);
    if (d.tasteScore != null) parts.push(`kept ${Number(d.tasteScore) > 0 ? '+' : ''}${Number(d.tasteScore).toFixed(2)}`);
  } else if (entry.type === 'recommendation_reset_applied') {
    if (d.action) parts.push(d.action === 'reset_history_and_x' ? 'reset + x' : 'reset');
    parts.push(`taste ${_formatTasteScoreSnapshot(d.tasteBefore)} -> ${_formatTasteScoreSnapshot(d.tasteAfter)}`);
    if (d.xScoreBefore != null || d.xScoreAfter != null) parts.push(`x ${Number(d.xScoreBefore || 0).toFixed(1)} -> ${Number(d.xScoreAfter || 0).toFixed(1)}`);
    if (d.reasonCodes) parts.push(`reasons ${_formatReasonCodes(d.reasonCodes)}`);
  } else if (d.fraction != null) {
    parts.push(`fraction ${Math.round(d.fraction * 100)}%`);
  }
  return parts.length > 0 ? parts.join(' | ') : '';
}

function _renderActivityLogHtml(entries) {
  return entries.map(entry => {
    const time = new Date(entry.ts).toLocaleTimeString();
    const levelClass = entry.level === 'error' ? 'log-error' : entry.level === 'warn' ? 'log-progress' : entry.important ? 'log-success' : 'log-info';
    const badge = `<span class="emb-log-tag">${esc(entry.category)}</span>`;
    const context = entry.data && (entry.data.title || entry.data.filename || entry.data.nativeFilePath || entry.data.filePath)
      ? ` &mdash; ${esc(String(entry.data.title || entry.data.filename || entry.data.nativeFilePath || entry.data.filePath))}`
      : '';
    const meta = _formatActivityMeta(entry);
    const metaHtml = meta ? `<div class="emb-log-meta">${esc(meta)}</div>` : '';
    return `<div class="emb-log-entry ${levelClass}"><span class="emb-log-time">${time}</span>${badge} ${esc(entry.message)}${context}${metaHtml}</div>`;
  }).join('');
}

function _activityEntriesToCopyText(entries) {
  return entries.map(e => {
    const payload = e.data && Object.keys(e.data).length > 0 ? ` | ${JSON.stringify(e.data)}` : '';
    return `[${new Date(e.ts).toLocaleTimeString()}] ${e.category.toUpperCase()} ${e.level.toUpperCase()}: ${e.message}${payload}`;
  }).join('\n');
}

function _playbackTimelineEntriesToCopyText(entries) {
  return (entries || []).map((evt, idx) => {
    const when = evt && evt.timestamp ? new Date(evt.timestamp).toLocaleString() : '-';
    const sourceLabel = evt && evt.source ? String(evt.source).replace(/_/g, ' ') : '-';
    const fractionText = evt && evt.fraction != null ? `${Math.round(evt.fraction * 100)}%` : '-';
    const beforePull = evt && evt.sessionBefore && evt.sessionBefore.weight != null ? `${Math.round(evt.sessionBefore.weight * 100)}%` : '-';
    const afterPull = evt && evt.sessionAfter && evt.sessionAfter.weight != null ? `${Math.round(evt.sessionAfter.weight * 100)}%` : '-';
    const beforeSessionEncounters = evt && evt.sessionBefore && evt.sessionBefore.encounters != null ? evt.sessionBefore.encounters : 0;
    const afterSessionEncounters = evt && evt.sessionAfter && evt.sessionAfter.encounters != null ? evt.sessionAfter.encounters : 0;
    const beforeSessionSkips = evt && evt.sessionBefore && evt.sessionBefore.skips != null ? evt.sessionBefore.skips : 0;
    const afterSessionSkips = evt && evt.sessionAfter && evt.sessionAfter.skips != null ? evt.sessionAfter.skips : 0;
    const beforeSessionPositive = evt && evt.sessionBefore && evt.sessionBefore.plays != null ? evt.sessionBefore.plays : 0;
    const afterSessionPositive = evt && evt.sessionAfter && evt.sessionAfter.plays != null ? evt.sessionAfter.plays : 0;
    const beforeAvg = evt && evt.summaryBefore && evt.summaryBefore.avgFrac != null ? `${Math.round(evt.summaryBefore.avgFrac * 100)}%` : '-';
    const afterAvg = evt && evt.summaryAfter && evt.summaryAfter.avgFrac != null ? `${Math.round(evt.summaryAfter.avgFrac * 100)}%` : '-';
    const beforePlays = evt && evt.summaryBefore && evt.summaryBefore.plays != null ? evt.summaryBefore.plays : 0;
    const afterPlays = evt && evt.summaryAfter && evt.summaryAfter.plays != null ? evt.summaryAfter.plays : 0;
    const beforeSkips = evt && evt.summaryBefore && evt.summaryBefore.skips != null ? evt.summaryBefore.skips : 0;
    const afterSkips = evt && evt.summaryAfter && evt.summaryAfter.skips != null ? evt.summaryAfter.skips : 0;
    const beforeCompletions = evt && evt.summaryBefore && evt.summaryBefore.completions != null ? evt.summaryBefore.completions : 0;
    const afterCompletions = evt && evt.summaryAfter && evt.summaryAfter.completions != null ? evt.summaryAfter.completions : 0;
    const beforeFracs = evt && evt.summaryBefore && evt.summaryBefore.fracsCount != null ? evt.summaryBefore.fracsCount : 0;
    const afterFracs = evt && evt.summaryAfter && evt.summaryAfter.fracsCount != null ? evt.summaryAfter.fracsCount : 0;
    const beforeNeg = evt && evt.negativeBefore != null ? Number(evt.negativeBefore).toFixed(1) : '0.0';
    const afterNeg = evt && evt.negativeAfter != null ? Number(evt.negativeAfter).toFixed(1) : '0.0';
    const beforeTaste = _formatTasteScoreSnapshot(evt && evt.tasteBefore);
    const afterTaste = _formatTasteScoreSnapshot(evt && evt.tasteAfter);
    const title = evt && (evt.title || evt.filename) ? (evt.title || evt.filename) : 'Unknown';
    const artist = evt && evt.artist ? evt.artist : '-';
    const classification = evt && evt.classification === 'skip' ? 'skip' : 'positive listen';
    const directPlayLine = _formatDirectPlayLine(evt && evt.tasteBefore, evt && evt.tasteAfter);
    const similarityDeltaLine = _formatSimilarityDeltaLine(evt && evt.tasteAfter);
    const totalScoreLine = _formatTotalScoreLine(evt && evt.tasteBefore, evt && evt.tasteAfter);
    return [
      `${idx + 1}. ${title} | ${artist}`,
      `Time: ${when}`,
      `Classification: ${classification} | Listened: ${fractionText} | Source: ${sourceLabel}`,
      `Direct play effect: ${directPlayLine}`,
      `Similarity delta effect: ${similarityDeltaLine}`,
      `Total score: ${totalScoreLine}`,
      `Session pull: ${beforePull} -> ${afterPull}`,
      `Session counts: encounters ${beforeSessionEncounters} -> ${afterSessionEncounters} | skips ${beforeSessionSkips} -> ${afterSessionSkips} | positive ${beforeSessionPositive} -> ${afterSessionPositive}`,
      `Library avg: ${beforeAvg} -> ${afterAvg}`,
      `Library counts: starts ${beforePlays} -> ${afterPlays} | skips ${beforeSkips} -> ${afterSkips} | completions ${beforeCompletions} -> ${afterCompletions} | fractions ${beforeFracs} -> ${afterFracs}`,
      `X-score: ${beforeNeg} -> ${afterNeg}`,
    ].join('\n');
  }).join('\n\n');
}

function _copyTextToClipboard(text, successLabel) {
  navigator.clipboard.writeText(text).then(
    () => showStatusToast(successLabel, 1500),
    () => showStatusToast('Copy failed', 1500)
  );
}

function _getTasteActivityEntries(limit = 160) {
  const playbackTypes = new Set(['listen_fraction_captured', 'song_classified_skip', 'song_classified_complete']);
  return engine.getRecentActivityEvents({ limit: 300 })
    .filter(entry => {
      if (!entry) return false;
      if (entry.category === 'taste') return true;
      if (entry.category === 'playback' && playbackTypes.has(entry.type)) return true;
      if (entry.category === 'engine' && entry.type === 'profile_rebuilt') return true;
      return false;
    })
    .slice(-limit);
}

function showEmbeddingDetail() {
  const st = engine.getEmbeddingStatus();
  const panel = document.getElementById('panel-discover');
  const wasDetailOpen = !!panel.querySelector('.emb-detail-page');
  const embScrollTargetId = _embDetailScrollTargetId;
  _embDetailScrollTargetId = null;
  // Only save backup on first open, not on live refreshes
  if (!wasDetailOpen) {
    discoverContentBackup = panel.innerHTML;
    discoverContentBackupPanelId = 'panel-discover';
    const content = document.querySelector('.content');
    if (content) content.scrollTop = 0;
    _embLogTab = 'common';
  }
  // Preserve scroll positions across live re-renders only. Fresh opens should
  // start at the top, but embedding-event updates should not yank the user.
  const _embScrollEl = document.querySelector('.content') || panel;
  const _embScrollState = wasDetailOpen ? {
    page: _embScrollEl ? _embScrollEl.scrollTop : 0,
    unmatched: document.getElementById('unmatchedSongsList')?.scrollTop || 0,
    embLog: document.getElementById('embLogContainer')?.scrollTop || 0,
    activityLog: document.getElementById('activityLogContainer')?.scrollTop || 0,
  } : null;

  const removedSongs = engine.getRemovedEmbeddingSongs();
  const removedSet = new Set(removedSongs.map(s => s.id));
  // Unembedded but NOT manually removed = genuinely pending
  const pendingNewSongs = allSongs.filter(s => s.filePath && !s.hasEmbedding && !removedSet.has(s.id));
  const unembeddedSongs = allSongs.filter(s => !s.hasEmbedding);
  const playableSongs = allSongs.filter(s => s.filePath);

  // Build log HTML (newest first)
  const logHtml = st.log.slice().reverse().map(entry => {
    const time = new Date(entry.time).toLocaleTimeString();
    const levelClass = entry.level === 'error' ? 'log-error' : entry.level === 'success' ? 'log-success' : entry.level === 'progress' ? 'log-progress' : 'log-info';
    return `<div class="emb-log-entry ${levelClass}"><span class="emb-log-time">${time}</span> ${esc(entry.message)}</div>`;
  }).join('');
  const activityRows = engine.getRecentActivityEvents({ limit: 300 }).slice().reverse();
  const activityHtml = _renderActivityLogHtml(activityRows);

  // Stats
  const elapsed = st.startTime ? Math.round((Date.now() - st.startTime) / 1000) : 0;
  const successCount = st.log.filter(e => e.level === 'success').length;
  const errorCount = st.log.filter(e => e.level === 'error').length;

  // Categorize songs
  const embeddedSongs = playableSongs.filter(s => s.hasEmbedding);
  const unmatched = st.unmatchedEmbeddings || [];
  const totalPending = pendingNewSongs.length + (st.failedCount || 0);

  const disp = (flag) => flag ? 'block' : 'none';

  // Status / stop button (retry button moved into pending section below — ISSUE-12)
  let statusText = '';
  if (st.inProgress) {
    const done = st.log.filter(e => e.level === 'success').length;
    statusText = `<div class="emb-status-active"><div class="emb-spinner"></div> Embedding in progress: ${done} done, ${st.queueSize} remaining</div>
      <button class="emb-stop-btn" onclick="window._app.stopEmbedding()">Stop Embedding</button>`;
  } else if (totalPending === 0 && removedSongs.length === 0) {
    statusText = `<div class="emb-status-done"><span class="emb-done">&#10003;</span> All songs have AI embeddings</div>`;
  }

  // Embedded + Total Songs lists removed — tiles are now read-only.

  // Unmatched songs list (collapsible) — ISSUE-14
  const unmatchedListHtml = unmatched.length > 0
    ? `<div style="display:${disp(_embDetailExpanded.unmatched)};">
        <div id="unmatchedSongsList" class="emb-song-list">
          <div class="emb-song-item" style="color:var(--text3);font-size:11px;line-height:1.4;">
            "Unmatched" means your imported (e.g. Colab) embeddings reference songs that aren't on this device —
            they may have been renamed, moved, or never copied over. They take up space but can't be used.
          </div>
          ${unmatched.map(u =>
            `<div class="emb-song-item"><span class="orange-dot-inline"></span> ${esc(u.filename)} <span class="emb-song-artist">— ${esc(u.artist)}${u.filepath ? ' (' + esc(u.filepath) + ')' : ''}</span></div>`
          ).join('')}
        </div>
        <div id="unmatchedConfirm" style="display:${disp(_embDetailExpanded.unmatchedConfirm)};">
          <div class="emb-confirm-msg">Remove ${unmatched.length} unmatched embeddings? This cannot be undone.</div>
          <div class="emb-confirm-btns">
            <button class="emb-confirm-yes" onclick="window._app.confirmRemoveUnmatched()">Yes, Remove</button>
            <button class="emb-confirm-no" onclick="window._app.setEmbConfirm('unmatchedConfirm', false)">Cancel</button>
          </div>
        </div>
        <button class="emb-orphan-btn" onclick="window._app.setEmbConfirm('unmatchedConfirm', true)">Remove ${unmatched.length} Unmatched Embeddings</button>
      </div>`
    : '';

  // Pending section — now a collapsible container. Pending stat tile toggles it.
  // Inside: three collapsible action rows (Embed New / Embed Removed / Re-embed All).
  const newCount = pendingNewSongs.length + (st.failedCount || 0);
  const caret = (open) => open ? '&#9660;' : '&#9654;';

  const pendingNewHtml = `
    <div class="emb-pending-action">
      <div class="emb-pending-action-header" onclick="window._app.embToggle('pendingNew')">
        <span>${caret(_embDetailExpanded.pendingNew)} Embed New Songs (${newCount})</span>
      </div>
      <div class="emb-pending-action-body" style="display:${disp(_embDetailExpanded.pendingNew)};">
        ${pendingNewSongs.length === 0
          ? '<div class="emb-song-item" style="color:var(--text3);">No new songs pending.</div>'
          : pendingNewSongs.slice(0, 100).map(s =>
              `<div class="emb-song-item"><span class="red-dot-inline"></span> ${esc(s.title)} <span class="emb-song-artist">— ${esc(s.artist)}</span></div>`
            ).join('') + (pendingNewSongs.length > 100 ? `<div class="emb-song-item" style="color:var(--text2);">... and ${pendingNewSongs.length - 100} more</div>` : '')
        }
        ${!st.inProgress && newCount > 0 ? `<button class="emb-retry-btn" onclick="window._app.embedNewPending()">Embed ${newCount} New Songs</button>` : ''}
      </div>
    </div>`;

  const pendingRemovedHtml = `
    <div class="emb-pending-action">
      <div class="emb-pending-action-header" onclick="window._app.embToggle('pendingRemoved')">
        <span>${caret(_embDetailExpanded.pendingRemoved)} Embed Removed Songs (${removedSongs.length})</span>
      </div>
      <div class="emb-pending-action-body" style="display:${disp(_embDetailExpanded.pendingRemoved)};">
        ${removedSongs.length === 0
          ? '<div class="emb-song-item" style="color:var(--text3);">No manually-removed songs.</div>'
          : removedSongs.map(s =>
              `<div class="emb-song-item"><span class="orange-dot-inline"></span> ${esc(s.title)} <span class="emb-song-artist">— ${esc(s.artist)}</span>
                <button class="emb-readd-btn" onclick="event.stopPropagation();window._app.readdEmbedding(${s.id})">Re-add</button></div>`
            ).join('')
        }
        ${!st.inProgress && removedSongs.length > 0 ? `<button class="emb-retry-btn" onclick="window._app.embedRemovedPending()">Re-add All ${removedSongs.length} Removed Songs</button>` : ''}
      </div>
    </div>`;

  const pendingReembedHtml = `
    <div class="emb-pending-action">
      <div class="emb-pending-action-header" onclick="window._app.embToggle('pendingReembed')">
        <span>${caret(_embDetailExpanded.pendingReembed)} Re-embed All Songs (${playableSongs.length})</span>
      </div>
      <div class="emb-pending-action-body" style="display:${disp(_embDetailExpanded.pendingReembed)};">
        <div class="emb-confirm-msg">Replaces existing embeddings for all ${playableSongs.length} songs. Estimated ~${Math.round(playableSongs.length * 25 / 60)} min. Best run while plugged in.</div>
        ${!st.inProgress ? `<button class="emb-reembed-btn" onclick="window._app.confirmReembedAll()">Start Re-embedding All</button>` : '<div class="emb-song-item" style="color:var(--text3);">Embedding in progress — wait until current run finishes.</div>'}
      </div>
    </div>`;

  const pendingSectionHtml = `
    <div class="emb-pending-section" id="emb-pending-wrap" style="display:${disp(_embDetailExpanded.pending)};">
      ${pendingNewHtml}
      ${pendingRemovedHtml}
      ${pendingReembedHtml}
    </div>
    ${!st.inProgress ? `<button class="emb-retry-btn" style="background:var(--bg3);margin-top:10px;" onclick="window._app.rescanLibrary()">Rescan Library</button>` : ''}`;

  panel.innerHTML = `
    <div class="viewall-header">
      <button class="viewall-back" onclick="window._app.closeViewAll()">\u2190</button>
      <span class="viewall-title">AI Embedding</span>
    </div>
    <div class="emb-detail-page">
      <div class="emb-stats-grid">
        <div class="emb-stat"><div class="emb-stat-num">${st.totalEmbedded}</div><div class="emb-stat-label">Embedded</div></div>
        <div class="emb-stat clickable" onclick="window._app.embToggle('pending')"><div class="emb-stat-num">${pendingNewSongs.length + removedSongs.length}</div><div class="emb-stat-label">Pending ${_embDetailExpanded.pending ? '&#9660;' : '&#9654;'}</div></div>
        <div class="emb-stat"><div class="emb-stat-num">${st.totalSongs}</div><div class="emb-stat-label">Total Songs</div></div>
        <div class="emb-stat clickable" onclick="window._app.embToggle('unmatched')"><div class="emb-stat-num">${unmatched.length}</div><div class="emb-stat-label">Unmatched</div></div>
      </div>
      ${unmatchedListHtml}

      ${statusText}

      ${successCount > 0 || errorCount > 0 ? `
      <div class="emb-run-stats">
        Session: ${successCount} succeeded, ${errorCount} errors${elapsed > 0 ? ', ' + elapsed + 's elapsed' : ''}
      </div>` : ''}

      ${pendingSectionHtml}

      ${st.orphanCount > 0 ? `
      <div class="emb-section-title">Orphaned Embeddings (${st.orphanCount})</div>
      <div class="emb-orphan-section">
        <div class="emb-orphan-desc">These songs were deleted from your device (via file manager, another app, or removed storage). They still have embeddings on-device. Removing them keeps your data clean.</div>
        <div class="emb-song-list">${engine.getOrphanedSongs().map(s =>
          `<div class="emb-song-item"><span class="orphan-dot-inline"></span> ${esc(s.title)} <span class="emb-song-artist">— ${esc(s.artist)}</span></div>`
        ).join('')}</div>
        <div id="orphanConfirm" style="display:${disp(_embDetailExpanded.orphanConfirm)};">
          <div class="emb-confirm-msg">Remove ${st.orphanCount} orphaned embeddings? This cannot be undone.</div>
          <div class="emb-confirm-btns">
            <button class="emb-confirm-yes" onclick="window._app.confirmRemoveOrphans()">Yes, Remove</button>
            <button class="emb-confirm-no" onclick="window._app.setEmbConfirm('orphanConfirm', false)">Cancel</button>
          </div>
        </div>
        <button class="emb-orphan-btn" onclick="window._app.setEmbConfirm('orphanConfirm', true)">Remove ${st.orphanCount} Orphaned Embeddings</button>
      </div>` : ''}

      <div class="emb-section-title">Logs</div>
      <div class="emb-log-tabs">
        <button class="emb-log-tab ${_embLogTab === 'common' ? 'active' : ''}" onclick="window._app.setEmbLogTab('common')">Common Logs (${activityRows.length})</button>
        <button class="emb-log-tab ${_embLogTab === 'embeddings' ? 'active' : ''}" onclick="window._app.setEmbLogTab('embeddings')">Embedding Logs (${st.log.length})</button>
      </div>
      <div class="emb-log-panel ${_embLogTab === 'common' ? 'active' : ''}">
        <div class="emb-log-container" id="activityLogContainer" style="display:${_embLogTab === 'common' ? 'block' : 'none'};">
          ${activityHtml.length > 0 ? activityHtml : '<div class="emb-log-empty">No common activity recorded yet.</div>'}
        </div>
      </div>
      <div class="emb-log-panel ${_embLogTab === 'embeddings' ? 'active' : ''}">
        <div class="emb-log-container" id="embLogContainer" style="display:${_embLogTab === 'embeddings' ? 'block' : 'none'};">
          ${logHtml.length > 0 ? logHtml : '<div class="emb-log-empty">No embedding activity recorded this session.<br>Embedding starts automatically after song scan completes.</div>'}
        </div>
      </div>
      <button class="emb-copy-logs-btn" onclick="window._app.copyEmbLogs()">Copy ${_embLogTab === 'embeddings' ? 'Embedding Logs' : 'Common Logs'}</button>
    </div>
  `;

  const focusScrollTarget = () => {
    if (!embScrollTargetId) return;
    const target = document.getElementById(embScrollTargetId);
    if (target) target.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  };

  if (_embScrollState) {
    requestAnimationFrame(() => {
      const restoreScroll = () => {
        if (_embScrollEl) _embScrollEl.scrollTop = _embScrollState.page || 0;
        const unmatchedEl = document.getElementById('unmatchedSongsList');
        const embLogEl = document.getElementById('embLogContainer');
        const activityEl = document.getElementById('activityLogContainer');
        if (unmatchedEl) unmatchedEl.scrollTop = _embScrollState.unmatched || 0;
        if (embLogEl) embLogEl.scrollTop = _embScrollState.embLog || 0;
        if (activityEl) activityEl.scrollTop = _embScrollState.activityLog || 0;
      };
      restoreScroll();
      requestAnimationFrame(() => {
        restoreScroll();
        focusScrollTarget();
      });
    });
  } else if (embScrollTargetId) {
    requestAnimationFrame(focusScrollTarget);
  }
}

function _embToggleSection(key) {
  // Independent toggles now (not exclusive accordion) — needed for nested
  // pending sub-actions. Re-render the detail page so headers update too.
  if (key === 'unmatched' && _embDetailExpanded.unmatched) {
    _embDetailExpanded.unmatchedConfirm = false;
  }
  _embDetailExpanded[key] = !_embDetailExpanded[key];
  showEmbeddingDetail();
}

function _setEmbLogTab(tab) {
  _embLogTab = tab === 'embeddings' ? 'embeddings' : 'common';
  showEmbeddingDetail();
}

function _setEmbConfirm(key, open) {
  if (key !== 'unmatchedConfirm' && key !== 'orphanConfirm') return;
  if (key === 'unmatchedConfirm' && open) {
    _embDetailExpanded.unmatched = true;
  }
  _embDetailExpanded[key] = !!open;
  _embDetailScrollTargetId = open ? key : null;
  showEmbeddingDetail();
}

function _embScrollToPending() {
  const el = document.querySelector('.emb-pending-section');
  if (el) el.scrollIntoView({ behavior: 'smooth' });
}

function _copyEmbLogs() {
  try {
    const st = engine.getEmbeddingStatus();
    const activity = engine.getRecentActivityEvents({ limit: 300 });
    const embText = st.log.map(e => `[${new Date(e.time).toLocaleTimeString()}] EMB ${e.level.toUpperCase()}: ${e.message}`).join('\n');
    const actText = _activityEntriesToCopyText(activity);
    const text = _embLogTab === 'embeddings' ? embText : actText;
    _copyTextToClipboard(text, `${_embLogTab === 'embeddings' ? 'Embedding' : 'Common'} logs copied`);
  } catch (e) {
    showStatusToast('Copy failed', 1500);
  }
}

function renderSimilar() {
  const header = document.getElementById('similar-header');
  const el = document.getElementById('similar-list');
  if (!header || !el) return;

  // Freeze wins — keep snapshot cards visible regardless of other state.
  if (_similarFrozen && _cachedSimilar && _cachedSimilar.length > 0) {
    header.style.display = 'flex';
    updateSimilarFreezeBtn();
    return;
  }

  // Decide state. Reserve the same slot height across every state so nothing
  // below (For You / Because You Played / Unexplored) ever shifts when Most
  // Similar transitions between loading → cards or cards → text.
  const st = engine.getState ? engine.getState() : {};
  const currentSong = st.current;
  const hasCurrent = currentSong != null;
  const currentSongId = hasCurrent && currentSong && currentSong.id != null ? currentSong.id : null;
  const currentHasEmbedding = hasCurrent && (
    (currentSong && currentSong.hasEmbedding === true)
    || (currentSongId != null && engine.hasEmbedding && engine.hasEmbedding(currentSongId))
  );
  const embReady = engine.isEmbeddingsReady && engine.isEmbeddingsReady();
  const status = engine.getEmbeddingStatus ? engine.getEmbeddingStatus() : null;

  // No current song → hide. No anchor to be similar to.
  if (!hasCurrent) {
    header.style.display = 'none';
    el.innerHTML = '';
    return;
  }

  header.style.display = 'flex';
  updateSimilarFreezeBtn();

  const ins = currentHasEmbedding && embReady ? engine.getInsights() : null;
  const haveCards = ins && Array.isArray(ins.topSimilar) && ins.topSimilar.length > 0;

  // State 1: cards.
  if (haveCards) {
    const newIds = ins.topSimilar.map(s => s.id).join(',');
    const prevIds = _lastSimilarIds || '';
    _lastSimilarIds = newIds;
    _cachedSimilar = ins.topSimilar;
    // If same set of cards already showing, skip DOM rewrite to avoid flash.
    if (newIds === prevIds && el.querySelector('.hscroll') && !el.querySelector('.similar-placeholder')) {
      return;
    }
    el.innerHTML = '<div class="hscroll fade-in">' +
      ins.topSimilar.map(s => {
        const pct = Math.round(s.similarity * 100);
        return cardHtml(s, pct + '%', 'discover_similar', 'similar');
      }).join('') +
      '</div>';
    return;
  }

  // All non-card states share placeholder markup. Reserved height matches
  // card-row height so nothing below moves.
  _lastSimilarIds = '';
  let key = 'loading';
  let mainText = '';
  let subText = '';
  let actionHtml = '';

  if (!embReady) {
    // State 2: embedding system still warming up (cold start).
    key = 'loading';
    mainText = 'Loading similar songs';
    subText = '';
  } else if (!currentHasEmbedding) {
    if (status && status.paused) {
      // State 3: user paused embedding.
      key = 'paused';
      mainText = 'Similar songs not available — embedding is paused';
      subText = 'Tap to resume in AI page.';
      actionHtml = ' data-action="open-ai"';
    } else if (status && (status.inProgress || status.unembedded > 0)) {
      // State 4: embedding running, current song not yet processed.
      key = 'waiting';
      mainText = 'Similar songs will appear once this song is embedded';
      const remaining = status.unembedded || 0;
      subText = remaining > 0 ? `${remaining} song${remaining === 1 ? '' : 's'} waiting to embed` : '';
    } else {
      // State 5: idle but this specific song has no embedding.
      key = 'unavailable';
      mainText = 'Similar songs not available for this song';
      subText = 'Tap to re-embed in AI page.';
      actionHtml = ' data-action="open-ai"';
    }
  } else {
    // Has embedding, embReady, but no topSimilar results (rare). Treat as loading.
    key = 'loading';
    mainText = 'Loading similar songs';
    subText = '';
  }

  const dotsHtml = key === 'loading' || key === 'waiting'
    ? '<span class="similar-loading-dots"><span></span><span></span><span></span></span>'
    : '';
  const onclick = actionHtml
    ? ' onclick="window._app.showEmbeddingDetail && window._app.showEmbeddingDetail()"'
    : '';

  el.innerHTML = `
    <div class="similar-placeholder similar-placeholder-${key}"${onclick}>
      <div class="similar-placeholder-text">${esc(mainText)}${dotsHtml}</div>
      ${subText ? `<div class="similar-placeholder-sub">${esc(subText)}</div>` : ''}
    </div>`;
}

function updateSimilarFreezeBtn() {
  const btn = document.getElementById('similarFreezeBtn');
  if (!btn) return;
  btn.textContent = _similarFrozen ? 'Unfreeze' : 'Freeze';
  btn.classList.toggle('similar-freeze-btn-active', _similarFrozen);
}

function toggleSimilarFreezeUI() {
  if (_similarFrozen) {
    _similarFrozen = false;
    renderSimilar();
    showStatusToast('Similar unfrozen', 1200);
  } else {
    if (!_cachedSimilar || _cachedSimilar.length === 0) {
      showStatusToast('No similar songs to freeze yet', 1400);
      return;
    }
    _similarFrozen = true;
    updateSimilarFreezeBtn();
    showStatusToast('Similar frozen \u2014 song changes won\u2019t update this list', 1800);
  }
}

function toggleSectionUI(id) {
  const content = document.getElementById(id + '-list');
  const arrow = document.getElementById(id + '-arrow');
  if (!content) return;
  const collapsed = content.classList.toggle('collapsed');
  if (arrow) arrow.textContent = collapsed ? '\u25B6' : '\u25BC'; // ▶ vs ▼
}

let _lastProfile = null;

function _renderForYouList(forYou, opts = {}) {
  const forYouEl = document.getElementById('for-you-list');
  const badgeEl = document.getElementById('foryou-badge');
  if (!forYouEl) return;
  const fadeCls = opts.fade === false ? '' : ' fade-in';
  if (forYou && forYou.length > 0) {
    _cachedForYou = forYou;
    if (badgeEl) badgeEl.textContent = forYou.length;
    forYouEl.innerHTML = `<div class="hscroll${fadeCls}">` +
      forYou.map(s => cardHtml(s, s.similarity, 'manual_foryou', 'forYou')).join('') +
      '</div>';
  } else {
    _cachedForYou = [];
    if (badgeEl) badgeEl.textContent = '0';
    forYouEl.innerHTML = '<div class="empty-hint">Play songs to get personalized picks</div>';
  }
}

function renderProfile(profile, opts = {}) {
  _lastProfile = profile;
  if (profile.stats && profile.stats.totalSongs > 0) {
    const s = profile.stats;
    const aiReady = Math.max(0, (s.totalSongs || 0) - (s.unembeddedCount || 0));
    const embSt = engine.getEmbeddingStatus();
    const aiLabel = embSt.inProgress ? 'Embedding' : 'AI';
    const aiSub = `${aiReady}/${s.totalSongs}`;
    const btnHtml = `
      <div class="discover-action-row">
        <button class="discover-action-btn" onclick="window._app.showEmbeddingDetail()">
          <div class="dab-main">${aiLabel}</div>
          <div class="dab-sub">${aiSub}</div>
        </button>
        <button class="discover-action-btn" onclick="window._app.showTasteWeights()">
          <div class="dab-main">Taste Signal</div>
          <div class="dab-sub">&#x2696; View Positive / Negative</div>
        </button>
      </div>`;
    const profEl = document.getElementById('profile-stats');
    profEl.innerHTML = btnHtml;
    profEl.style.display = 'block';
    profEl.classList.toggle('fade-in', opts.fade !== false);
  }

  _renderForYouList(profile.forYou, opts);

  renderDiscoverTiles(profile);
}

function renderDiscoverTiles(profile) {
  const el = document.getElementById('browse-tiles');
  if (!el) return;

  const recent = _cachedRecentlyPlayed || [];
  const mostPlayed = (profile && profile.mostPlayed) || [];
  const lastAdded = engine.getLastAddedSongs(4) || [];
  const neverPlayed = (profile && profile.neverPlayed) || [];
  const favorites = _cachedFavorites || engine.getFavoritesList();
  const dislikedSongs = engine.getDislikedSongsList();

  const neverCount = profile && profile.stats ? profile.stats.neverPlayedCount : neverPlayed.length;

  const tileHtml = (label, count, items, viewAllKey, emptyMsg) => {
    const thumbs = items.slice(0, 4).map(s => {
      const art = getArtUrl(s);
      if (art) {
        const letter = (s.title || '?')[0].toUpperCase();
        const onerr = s && s.id != null ? _artOnErrorAttr(s.id, letter, 'dtile-thumb-letter') : '';
        return `<div class="dtile-thumb"><img src="${art}" decoding="async" alt="" ${onerr}></div>`;
      }
      return `<div class="dtile-thumb dtile-thumb-letter"><span>${esc((s.title||'?')[0].toUpperCase())}</span></div>`;
    }).join('');
    const body = items.length > 0
      ? `<div class="dtile-grid">${thumbs}${items.length < 4 ? '<div class="dtile-thumb dtile-thumb-empty"></div>'.repeat(4 - items.length) : ''}</div>`
      : `<div class="dtile-empty">${emptyMsg}</div>`;
    const countStr = count != null ? ` <span class="dtile-count">${count}</span>` : '';
    return `<div class="dtile" onclick="window._app.viewAll('${viewAllKey}')">
      <div class="dtile-header"><span class="dtile-title">${label}</span>${countStr}</div>
      ${body}
    </div>`;
  };

  el.innerHTML = `
    ${tileHtml('Most Played', mostPlayed.length || null, mostPlayed, 'mostPlayed', 'No history yet')}
    ${tileHtml('Recently Played', recent.length || null, recent, 'recentlyPlayed', 'No history yet')}
    ${tileHtml('Never Played', neverCount || null, neverPlayed, 'neverPlayed', 'Played everything!')}
    ${tileHtml('Last Added', lastAdded.length || null, lastAdded, 'lastAdded', 'Nothing new')}
    ${tileHtml('Favorites', favorites.length || null, favorites, 'favorites', 'No favorites yet')}
    ${tileHtml('Disliked Songs', dislikedSongs.length || null, dislikedSongs, 'dislikedSongs', 'No disliked songs')}
  `;
}

let _cachedRecentlyPlayed = [];

// ===== TASTE WEIGHTS OVERLAY =====

let _tasteSignalFilter = 'active';
let _tasteSignalSort = 'positive';
let _tasteSignalVisibleCount = 10;
let _tasteSignalVisibleRows = [];
const TASTE_SIGNAL_PAGE_SIZE = 10;

async function showTasteWeightsOverlay() {
  const panel = document.getElementById('panel-discover');
  if (!panel) return;
  if (!panel.querySelector('.taste-weights-page')) {
    discoverContentBackup = panel.innerHTML;
    discoverContentBackupPanelId = 'panel-discover';
    const content = document.querySelector('.content');
    if (content) content.scrollTop = 0;
  }

  const includeAllEmbedded = _tasteSignalFilter === 'all';
  const data = await engine.getTasteSignal(includeAllEmbedded);
  const summary = data.summary || {};
  let rows = (data.rows || []).slice();
  if (_tasteSignalFilter === 'active') rows = rows.filter(r => r.isActive);
  rows.sort((a, b) => {
    const primary = _tasteSignalSort === 'positive' ? (b.score - a.score) : (a.score - b.score);
    if (primary !== 0) return primary;
    return String(a.title || '').localeCompare(String(b.title || ''));
  });
  _tasteSignalVisibleRows = rows;
  const visibleRows = rows.slice(0, _tasteSignalVisibleCount);
  const remaining = Math.max(0, rows.length - visibleRows.length);

  const rowHtml = (r, idx) => {
    const full = engine.getSongs()[r.id];
    const scoreClass = r.score > 0 ? 'tw-score-pos' : (r.score < 0 ? 'tw-score-neg' : 'tw-score-zero');
    const scoreText = r.score > 0 ? `+${r.score.toFixed(2)}` : r.score.toFixed(2);
    const barPct = r.isActive ? Math.max(2, Math.round((r.scoreNorm || 0) * 100)) : 0;
    const barCls = r.score < 0 ? 'tw-bar-fill tw-bar-neg' : 'tw-bar-fill';
    const chips = [];
    if (r.isFavorite) chips.push('<span class="tw-chip tw-chip-fav">Favorite</span>');
    if (r.isDisliked) chips.push('<span class="tw-chip tw-chip-neg">Disliked</span>');
    if ((r.positiveWeight || 0) > 0 && (r.negativeWeight || 0) > 0) chips.push('<span class="tw-chip">Mixed</span>');
    if ((r.xScore || 0) > 0) chips.push(`<span class="tw-chip tw-chip-neg">X ${Number(r.xScore).toFixed(1)}</span>`);
    if (Math.abs(Number(r.similarityBoost) || 0) > 0.001) {
      const boost = Number(r.similarityBoost) || 0;
      chips.push(`<span class="tw-chip ${boost > 0 ? 'tw-chip-pos' : 'tw-chip-neg'}">Similarity ${boost > 0 ? '+' : ''}${boost.toFixed(2)}</span>`);
    }
    if (r.isHardRecommendationBlock) chips.push('<span class="tw-chip tw-chip-neg">Rec blocked</span>');
    if (!r.isActive) chips.push('<span class="tw-chip">Neutral</span>');
    else if (r.score < 0 && r.plays >= 2 && r.avgFrac != null && r.avgFrac < 0.5) chips.push('<span class="tw-chip tw-chip-neg">Short-listened</span>');
    else if (r.score > 0 && r.inTopPositive30) chips.push('<span class="tw-chip tw-chip-pos">Top +30</span>');
    else if (r.score < 0 && r.inTopNegative30) chips.push('<span class="tw-chip tw-chip-neg">Top -30</span>');
    else if (r.inTop30) chips.push('<span class="tw-chip">Top 30</span>');

    const detailParts = [
      `${r.plays || 0} start${(r.plays || 0) === 1 ? '' : 's'}`,
      r.avgFrac != null ? `avg ${Math.round(r.avgFrac * 100)}%` : 'avg -',
    ];
    if (r.directScore != null) {
      const direct = Number(r.directScore) || 0;
      detailParts.push(`direct ${direct > 0 ? '+' : ''}${direct.toFixed(2)}`);
    }
    if (Math.abs(Number(r.similarityBoost) || 0) > 0.001) {
      const boost = Number(r.similarityBoost) || 0;
      detailParts.push(`similarity ${boost > 0 ? '+' : ''}${boost.toFixed(2)}`);
    }
    if ((r.favoritePrior || 0) > 0) detailParts.push(`fav +${Number(r.favoritePrior).toFixed(2)}`);
    if ((r.dislikePrior || 0) > 0) detailParts.push(`dislike -${Number(r.dislikePrior).toFixed(2)}`);
    if (r.daysSince != null) detailParts.push(`${r.daysSince}d ago`);
    if (r.isHardRecommendationBlock) detailParts.push('hidden from taste recs');

    return `
      <div class="tw-row ${r.score < 0 ? 'tw-row-neg' : ''}" onclick="window._app.playTasteSignal(${idx})">
        ${full ? songThumb(full) : `<div class="song-thumb song-thumb-letter">${esc((r.title || '?')[0])}</div>`}
        <div class="tw-row-main">
          <div class="tw-row-top">
            <div class="tw-title">${esc(r.title)}</div>
            <div class="tw-score ${scoreClass}">${scoreText}</div>
          </div>
          <div class="tw-artist">${esc(r.artist || '')}</div>
          ${chips.length > 0 ? `<div class="tw-chip-row">${chips.join('')}</div>` : ''}
          ${r.isActive ? `<div class="tw-bar-bg"><div class="${barCls}" style="width:${barPct}%;"></div></div>` : ''}
          <div class="tw-detail">${detailParts.join(' &middot; ')}</div>
        </div>
        <button class="tw-reset-btn" onclick="event.stopPropagation(); window._app.resetTasteWeight(${r.id})">Reset</button>
      </div>`;
  };

  const listHtml = rows.length === 0
    ? '<div class="empty-hint">No songs with active Taste Signal yet. Play more music or switch to All Embedded.</div>'
    : visibleRows.map((r, idx) => rowHtml(r, idx)).join('') +
      (remaining > 0 ? `<button class="tw-more-btn" onclick="window._app.tasteSignalMore()">Show ${Math.min(TASTE_SIGNAL_PAGE_SIZE, remaining)} more (${remaining} remaining)</button>` : '');

  const tuning = engine.getTuning();
  const ins = engine.getInsights();
  const playbackTimelineFull = await engine.getRecentPlaybackSignalTimeline(TASTE_PLAYBACK_MAX);
  const playbackVisibleCount = Math.min(_tastePlaybackVisibleCount, playbackTimelineFull.length);
  const playbackTimeline = playbackTimelineFull.slice(0, playbackVisibleCount);
  const playbackRemaining = Math.max(0, playbackTimelineFull.length - playbackVisibleCount);
  const tasteActivityRows = _getTasteActivityEntries(160).slice().reverse();
  const tPct = (v) => Math.round(v * 100);
  const playbackTimelineHtml = (!playbackTimelineFull || playbackTimelineFull.length === 0)
    ? '<div class="empty-hint">No recent playback signal updates yet. After songs are skipped or completed, each change will appear here one by one.</div>'
    : playbackTimeline.map(evt => {
      const when = evt.timestamp ? new Date(evt.timestamp).toLocaleString() : '';
      const sourceLabel = evt.source ? String(evt.source).replace(/_/g, ' ') : '-';
      const beforePull = evt.sessionBefore && evt.sessionBefore.weight != null ? `${Math.round(evt.sessionBefore.weight * 100)}%` : '-';
      const afterPull = evt.sessionAfter && evt.sessionAfter.weight != null ? `${Math.round(evt.sessionAfter.weight * 100)}%` : '-';
      const beforeSessionEncounters = evt.sessionBefore && evt.sessionBefore.encounters != null ? evt.sessionBefore.encounters : 0;
      const afterSessionEncounters = evt.sessionAfter && evt.sessionAfter.encounters != null ? evt.sessionAfter.encounters : 0;
      const beforeSessionSkips = evt.sessionBefore && evt.sessionBefore.skips != null ? evt.sessionBefore.skips : 0;
      const afterSessionSkips = evt.sessionAfter && evt.sessionAfter.skips != null ? evt.sessionAfter.skips : 0;
      const beforeSessionPositive = evt.sessionBefore && evt.sessionBefore.plays != null ? evt.sessionBefore.plays : 0;
      const afterSessionPositive = evt.sessionAfter && evt.sessionAfter.plays != null ? evt.sessionAfter.plays : 0;
      const beforeAvg = evt.summaryBefore && evt.summaryBefore.avgFrac != null ? `${Math.round(evt.summaryBefore.avgFrac * 100)}%` : '-';
      const afterAvg = evt.summaryAfter && evt.summaryAfter.avgFrac != null ? `${Math.round(evt.summaryAfter.avgFrac * 100)}%` : '-';
      const beforePlays = evt.summaryBefore && evt.summaryBefore.plays != null ? evt.summaryBefore.plays : 0;
      const afterPlays = evt.summaryAfter && evt.summaryAfter.plays != null ? evt.summaryAfter.plays : 0;
      const beforeSkips = evt.summaryBefore && evt.summaryBefore.skips != null ? evt.summaryBefore.skips : 0;
      const afterSkips = evt.summaryAfter && evt.summaryAfter.skips != null ? evt.summaryAfter.skips : 0;
      const beforeCompletions = evt.summaryBefore && evt.summaryBefore.completions != null ? evt.summaryBefore.completions : 0;
      const afterCompletions = evt.summaryAfter && evt.summaryAfter.completions != null ? evt.summaryAfter.completions : 0;
      const beforeFracs = evt.summaryBefore && evt.summaryBefore.fracsCount != null ? evt.summaryBefore.fracsCount : 0;
      const afterFracs = evt.summaryAfter && evt.summaryAfter.fracsCount != null ? evt.summaryAfter.fracsCount : 0;
      const beforeNeg = evt.negativeBefore != null ? Number(evt.negativeBefore).toFixed(1) : '0.0';
      const afterNeg = evt.negativeAfter != null ? Number(evt.negativeAfter).toFixed(1) : '0.0';
      const directPlayLine = _formatDirectPlayLine(evt.tasteBefore, evt.tasteAfter);
      const similarityDeltaLine = _formatSimilarityDeltaLine(evt.tasteAfter);
      const totalScoreLine = _formatTotalScoreLine(evt.tasteBefore, evt.tasteAfter);
      return `
        <div class="tw-session-row tw-playback-row">
          <div class="tw-session-main">
            <div class="tw-session-title">${esc(evt.title || evt.filename || 'Unknown')}</div>
            <div class="tw-session-meta">${esc(when)} &middot; ${evt.classification === 'skip' ? 'Skip' : 'Positive listen'} &middot; listened ${evt.fraction != null ? Math.round(evt.fraction * 100) + '%' : '-'}</div>
            <div class="tw-playback-line">Source: ${esc(sourceLabel)}</div>
            <div class="tw-playback-line">Direct play effect: ${directPlayLine}</div>
            <div class="tw-playback-line">Similarity delta effect: ${similarityDeltaLine}</div>
            <div class="tw-playback-line">Total score: ${totalScoreLine}</div>
            <div class="tw-playback-line">Session pull: ${beforePull} &rarr; ${afterPull}</div>
            <div class="tw-playback-line">Session counts: encounters ${beforeSessionEncounters} &rarr; ${afterSessionEncounters} &middot; skips ${beforeSessionSkips} &rarr; ${afterSessionSkips} &middot; positive ${beforeSessionPositive} &rarr; ${afterSessionPositive}</div>
            <div class="tw-playback-line">Library avg: ${beforeAvg} &rarr; ${afterAvg}</div>
            <div class="tw-playback-line">Library counts: starts ${beforePlays} &rarr; ${afterPlays} &middot; skips ${beforeSkips} &rarr; ${afterSkips} &middot; completions ${beforeCompletions} &rarr; ${afterCompletions} &middot; fractions ${beforeFracs} &rarr; ${afterFracs}</div>
            <div class="tw-playback-line">X-score: ${beforeNeg} &rarr; ${afterNeg}</div>
          </div>
          <div class="tw-session-badge ${evt.classification === 'skip' ? 'tw-session-badge-neg' : ''}">${evt.classification === 'skip' ? 'SKIP' : 'LISTEN'}</div>
        </div>
      `;
    }).join('');
  const tasteActivityHtml = _renderActivityLogHtml(tasteActivityRows);
  window._tuningHints = {
    adventurous: "Higher = more diverse Up Next picks (MMR lambda). Lower = stick closer to what you just played.",
    sessionBias: "Higher = recs follow the mood of what you're playing right now. Lower = lean on long-term taste profile.",
    negativeStrength: "Higher = songs in your Negative list (X'd, disliked, repeat-skipped) pull recommendations farther away from their sound."
  };
  const tuneRow = (key, label, val, defaultPct) => `
      <div class="tuning-row tuning-row-compact">
        <div class="tuning-label-compact">
          <span class="tuning-label-text">${label}</span>
          <button class="tuning-info-btn" title="Info"
            ontouchstart="event.stopPropagation()"
            onmousedown="event.stopPropagation()"
            onclick="event.stopPropagation(); window._app.tuningInfo('${key}')">info</button>
          <span class="tuning-val" id="tune${key}Val">${tPct(val)}%</span>
          <button class="tuning-reset" title="Reset to default (${defaultPct}%)" onclick="window._app.resetTuning('${key}')">\u21BA</button>
        </div>
        <div class="tuning-inline-hint" id="tuningHint-${key}" style="display:none;">${esc(window._tuningHints[key])}</div>
        <input class="tuning-slider" type="range" min="0" max="100" value="${tPct(val)}"
          oninput="document.getElementById('tune${key}Val').textContent=this.value+'%'"
          onchange="window._app.setTuning('${key}', this.value/100)">
      </div>`;
  const tuningHtml = `
    <div class="tuning-block tuning-block-compact">
      <div class="tuning-title">Recommendation Tuning</div>
      ${tuneRow('adventurous', 'Adventurous', tuning.adventurous, 80)}
      ${tuneRow('sessionBias', 'Session weight', tuning.sessionBias, 50)}
      ${tuneRow('negativeStrength', 'Skip strength', tuning.negativeStrength, 50)}
    </div>`;
  const playbackLoadMoreHtml = playbackRemaining > 0
    ? `<button class="tw-more-btn" onclick="window._app.tastePlaybackMore()">Show ${Math.min(TASTE_PLAYBACK_PAGE_SIZE, playbackRemaining)} more (${playbackRemaining} remaining)</button>`
    : '';
  const playbackInsightsHtml = `
    <div class="tw-session-block">
      <button class="tw-session-header" onclick="window._app.toggleTastePlayback()">
        <span>Last 30 Playback Signal Updates (${playbackTimelineFull.length})</span>
        <span>${_tastePlaybackExpanded ? '\u25BC' : '\u25B6'}</span>
      </button>
      <div class="tw-session-body" style="display:${_tastePlaybackExpanded ? 'block' : 'none'};">
        <div class="tw-intro-sub">Each row is one playback result. Repeat listens appear one by one so you can see how session pull, library evidence, and Taste score changed over time. Very early skips under 10% listened are not added here.</div>
        ${playbackTimelineHtml}
        ${playbackLoadMoreHtml}
        <button class="emb-copy-logs-btn tw-copy-logs-btn" onclick="window._app.copyTastePlaybackSignals()">Copy Last 30 Signals</button>
      </div>
    </div>`;
  const tasteLogsHtml = `
    <div class="tw-session-block">
      <button class="tw-session-header" onclick="window._app.toggleTasteLogs()">
        <span>Taste Logs (${tasteActivityRows.length})</span>
        <span>${_tasteLogsExpanded ? '\u25BC' : '\u25B6'}</span>
      </button>
      <div class="tw-session-body" style="display:${_tasteLogsExpanded ? 'block' : 'none'};">
        <div class="tw-intro-sub">Filtered Taste Signal, playback evidence, reset, and tuning logs. Use AI page Common Logs for the full app audit trail.</div>
        <div class="emb-log-container tw-log-container" id="tasteLogContainer">
          ${tasteActivityHtml.length > 0 ? tasteActivityHtml : '<div class="emb-log-empty">No Taste activity recorded yet.</div>'}
        </div>
        <button class="emb-copy-logs-btn tw-copy-logs-btn" onclick="window._app.copyTasteLogs()">Copy Taste Logs</button>
      </div>
    </div>`;
  const engineInsightsHtml = `
    <div class="tw-session-block">
      <button class="tw-session-header" onclick="window._app.toggleTasteEngine()">
        <span>Engine Snapshot</span>
        <span>${_tasteEngineExpanded ? '\u25BC' : '\u25B6'}</span>
      </button>
      <div class="tw-session-body" style="display:${_tasteEngineExpanded ? 'block' : 'none'};">
        <div class="tw-engine-grid">
          <div class="tw-engine-card">
            <div class="tw-engine-key">Current Blend</div>
            <div class="tw-engine-val">${esc(ins.blend.currentSong)} / ${esc(ins.blend.session)} / ${esc(ins.blend.profile)}</div>
            <div class="tw-engine-meta">${esc(ins.blend.mode)}</div>
          </div>
          <div class="tw-engine-card">
            <div class="tw-engine-key">Up Next</div>
            <div class="tw-engine-val">${ins.queue.size} songs</div>
            <div class="tw-engine-meta">${esc(ins.queue.mode)}${ins.queue.label ? ' \u00b7 ' + esc(ins.queue.label) : ''}</div>
          </div>
          <div class="tw-engine-card">
            <div class="tw-engine-key">Library AI</div>
            <div class="tw-engine-val">${esc(ins.embeddingCoverage.percentage)}</div>
            <div class="tw-engine-meta">${ins.embeddingCoverage.embedded}/${ins.embeddingCoverage.total} embedded</div>
          </div>
          <div class="tw-engine-card">
            <div class="tw-engine-key">Now Playing</div>
            <div class="tw-engine-val">${esc(ins.currentSong ? ins.currentSong.title : 'Nothing active')}</div>
            <div class="tw-engine-meta">${esc(ins.currentSong ? ins.currentSong.artist : 'Start a song to see live blend state')}</div>
          </div>
        </div>
      </div>
    </div>`;

  const headline = `${summary.activeCount || 0} active signals across ${summary.totalEmbedded || 0} embedded songs. ${summary.hardBlockedCount || 0} strong negative songs are blocked from taste-based recommendations. Tap any row to start playback from that exact order.`;
  const filterAllEmbeddedChecked = _tasteSignalFilter === 'all';
  const sortNegativeChecked = _tasteSignalSort === 'negative';
  const filterToggleLabel = filterAllEmbeddedChecked ? `All Embedded (${summary.totalEmbedded || 0})` : `Active Only (${summary.activeCount || 0})`;
  const sortToggleLabel = sortNegativeChecked ? 'Top Negative' : 'Top Positive';

  panel.innerHTML = `
    <div class="viewall-header">
      <button class="viewall-back" onclick="window._app.closeTasteWeights()">\u2190</button>
      <span class="viewall-title">Taste Signal</span>
    </div>
    <div class="taste-weights-page">
      ${tuningHtml}
      ${engineInsightsHtml}
      ${playbackInsightsHtml}
      <div class="tw-intro">${headline}</div>
      <div class="tw-intro-sub">Library Signals Reset clears recommendation history plus X-score so a song can re-earn signal from fresh listening. Manual dislikes stay separate. Similarity chips show neighbor influence from related songs.</div>
      <div class="tw-page-actions">
        <button class="tw-page-reset-btn" onclick="window._app.reset()">Reset Engine</button>
        <button class="tuning-info-btn" onclick="window._app.toggleTasteResetInfo()">info</button>
      </div>
      <div class="tw-reset-help" id="tasteResetHelp" style="display:${_tasteResetInfoVisible ? 'block' : 'none'};">
        <div><strong>Clears:</strong> Up Next, playback history, Taste profile summary, favorites, dislikes, X-score, session logs, and saved playback state.</div>
        <div><strong>Keeps:</strong> song files, embeddings, playlists, and Common Logs / activity logs.</div>
        <div>Playback stops and the app reloads after reset.</div>
      </div>
      <div class="tw-toggle-row">
        <label class="tw-toggle">
          <span class="tw-toggle-text">${filterToggleLabel}</span>
          <input type="checkbox" ${filterAllEmbeddedChecked ? 'checked' : ''} onchange="window._app.setTasteSignalFilter(this.checked ? 'all' : 'active')">
          <span class="tw-toggle-slider"></span>
        </label>
        <label class="tw-toggle">
          <span class="tw-toggle-text">${sortToggleLabel}</span>
          <input type="checkbox" ${sortNegativeChecked ? 'checked' : ''} onchange="window._app.setTasteSignalSort(this.checked ? 'negative' : 'positive')">
          <span class="tw-toggle-slider"></span>
        </label>
      </div>
      <div class="tw-section-title">Library Signals (${rows.length})</div>
      <div class="tw-list">${listHtml}</div>
      ${tasteLogsHtml}
    </div>
  `;
}

function closeTasteWeightsOverlay() {
  _tasteSignalFilter = 'active';
  _tasteSignalSort = 'positive';
  _tasteSignalVisibleCount = TASTE_SIGNAL_PAGE_SIZE;
  _tasteSignalVisibleRows = [];
  _tastePlaybackExpanded = false;
  _tastePlaybackVisibleCount = TASTE_PLAYBACK_PAGE_SIZE;
  _tasteLogsExpanded = false;
  _tasteEngineExpanded = false;
  _tasteResetInfoVisible = false;
  _hideTuningInfoPopup();
  const panel = discoverContentBackupPanelId ? document.getElementById(discoverContentBackupPanelId) : document.getElementById('panel-discover');
  if (panel && discoverContentBackup) {
    panel.innerHTML = discoverContentBackup;
    discoverContentBackup = null;
    discoverContentBackupPanelId = null;
    if (!_flushQueuedDiscoverRefresh()) refreshDiscoverPrimaryState();
  } else if (panel) {
    if (!_flushQueuedDiscoverRefresh()) renderDiscoverSnapshotFromCache({ fade: false });
  }
}

async function resetTasteWeightUI(songId) {
  const result = await engine.resetSongProfileWeight(songId);
  showStatusToast(result && result.ok ? 'Taste weight reset' : 'Could not reset weight', 1500);
  if (result && result.ok) showTasteWeightsOverlay();
}

function copyTasteLogsUI() {
  try {
    const entries = _getTasteActivityEntries(160);
    _copyTextToClipboard(_activityEntriesToCopyText(entries), 'Taste logs copied');
  } catch (e) {
    showStatusToast('Copy failed', 1500);
  }
}

async function copyTastePlaybackSignalsUI() {
  try {
    const entries = await engine.getRecentPlaybackSignalTimeline(TASTE_PLAYBACK_MAX);
    if (!entries || entries.length === 0) {
      showStatusToast('No playback signals to copy', 1500);
      return;
    }
    _copyTextToClipboard(_playbackTimelineEntriesToCopyText(entries), 'Playback signals copied');
  } catch (e) {
    showStatusToast('Copy failed', 1500);
  }
}

async function playTasteSignalRowUI(visibleIndex) {
  const row = _tasteSignalVisibleRows[visibleIndex];
  if (!row) return;
  if (row.id === currentSong && nativeAudioPlaying) {
    if (!fullPlayerOpen) openFullPlayer();
    return;
  }
  const frac = getListenFraction();
  const label = `Taste Signal · ${_tasteSignalSort === 'positive' ? 'Top Positive' : 'Top Negative'}${_tasteSignalFilter === 'all' ? ' · All Embedded' : ' · Active Only'}`;
  logActivity({
    category: 'ui',
    type: 'taste_signal_song_tapped',
    message: `Tapped "${row.title}" from Taste Signal`,
    data: { songId: row.id, rank: visibleIndex + 1, sort: _tasteSignalSort, filter: _tasteSignalFilter, prevFraction: frac },
    tags: ['taste', 'queue', 'playback'],
    important: true,
  });
  const info = engine.playFromOrderedList(_tasteSignalVisibleRows.map(item => item.id), visibleIndex, label, 'taste_signal', frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
  }
}

function setTasteSignalFilterUI(mode) {
  _tasteSignalFilter = mode === 'all' ? 'all' : 'active';
  _tasteSignalVisibleCount = TASTE_SIGNAL_PAGE_SIZE;
  showTasteWeightsOverlay();
}

function setTasteSignalSortUI(mode) {
  _tasteSignalSort = mode === 'negative' ? 'negative' : 'positive';
  _tasteSignalVisibleCount = TASTE_SIGNAL_PAGE_SIZE;
  showTasteWeightsOverlay();
}

function showMoreTasteSignalUI() {
  _tasteSignalVisibleCount += TASTE_SIGNAL_PAGE_SIZE;
  showTasteWeightsOverlay();
}


function _renderBecauseYouPlayedSections(sections, opts = {}) {
  const el = document.getElementById('because-you-played');
  if (!el) return;
  const fadeCls = opts.fade === false ? '' : ' fade-in';
  if (!sections || sections.length === 0) {
    _cachedBecauseYouPlayed = [];
    el.innerHTML = '<div class="empty-hint">Play music to get personal recommendations</div>';
    return;
  }
  _cachedBecauseYouPlayed = sections;
  el.innerHTML = sections.map((sec, i) => `
    <div class="section-header${fadeCls}">Because you played ${esc(sec.sourceTitle)}</div>
    <div class="hscroll-wrap${fadeCls}">
      <div class="hscroll">
        ${sec.recommendations.map(s => cardHtml(s, s.similarity, 'manual_because', 'byp:' + i)).join('')}
      </div>
    </div>
  `).join('');
}

function renderCachedBecauseYouPlayed(opts = {}) {
  _renderBecauseYouPlayedSections(_cachedBecauseYouPlayed || [], opts);
}

function renderBecauseYouPlayed(opts = {}) {
  const sections = engine.getBecauseYouPlayed(3, 6, {
    avoidSourceIds: opts.refresh ? (_cachedBecauseYouPlayed || []).map(s => s.sourceId) : [],
    avoidRecIds: opts.refresh ? (_cachedBecauseYouPlayed || []).flatMap(s => (s.recommendations || []).map(r => r.id)) : [],
  });
  _renderBecauseYouPlayedSections(sections, opts);
}

function _renderUnexploredPlaceholder() {
  const header = document.getElementById('unexplored-header');
  const el = document.getElementById('unexplored-clusters');
  if (!el || !header) return;
  header.style.display = '';
  el.innerHTML = `
    <div class="similar-placeholder similar-placeholder-loading">
      <div class="similar-placeholder-text">Loading unexplored sounds<span class="similar-loading-dots"><span></span><span></span><span></span></span></div>
    </div>`;
}

function _renderUnexploredSections(sections, opts = {}) {
  const header = document.getElementById('unexplored-header');
  const el = document.getElementById('unexplored-clusters');
  if (!el || !header) return;
  if (!engine.isEmbeddingsReady()) {
    _renderUnexploredPlaceholder();
    return;
  }
  if (!sections || sections.length === 0) {
    _cachedUnexplored = [];
    header.style.display = 'none';
    el.innerHTML = '';
    return;
  }
  const fadeCls = opts.fade === false ? '' : ' fade-in';
  header.style.display = '';
  _cachedUnexplored = sections;
  const labels = ['Sound you rarely visit', 'Another pocket of your library', 'Off the beaten path'];
  el.innerHTML = sections.map((sec, i) => `
    <div class="section-header${fadeCls}">${labels[i] || 'Unexplored'}</div>
    <div class="hscroll-wrap${fadeCls}">
      <div class="hscroll">
        ${sec.songs.map(s => cardHtml(s, null, 'unexplored', 'unexp:' + i)).join('')}
      </div>
    </div>
  `).join('');
}

function renderCachedUnexplored(opts = {}) {
  const header = document.getElementById('unexplored-header');
  const el = document.getElementById('unexplored-clusters');
  if (!el || !header) return;
  const sections = _cachedUnexplored || [];
  // Render cached sections directly — do NOT gate on isEmbeddingsReady(). The
  // section is a snapshot of the last manual refresh; it must appear on cold
  // start even before embeddings finish merging.
  if (sections.length > 0) {
    const fadeCls = opts.fade === false ? '' : ' fade-in';
    header.style.display = '';
    const labels = ['Sound you rarely visit', 'Another pocket of your library', 'Off the beaten path'];
    el.innerHTML = sections.map((sec, i) => `
      <div class="section-header${fadeCls}">${labels[i] || 'Unexplored'}</div>
      <div class="hscroll-wrap${fadeCls}">
        <div class="hscroll">
          ${sec.songs.map(s => cardHtml(s, null, 'unexplored', 'unexp:' + i)).join('')}
        </div>
      </div>
    `).join('');
    return;
  }
  // No cached sections yet — fall back to the embedding-aware placeholder path.
  _renderUnexploredSections(sections, opts);
}

function renderDiscoverSnapshotFromCache(opts = {}) {
  if (opts.includeSimilar !== false) {
    if (engine.isEmbeddingsReady()) {
      renderSimilar();
    } else {
      const similarHeader = document.getElementById('similar-header');
      const similarList = document.getElementById('similar-list');
      if (similarHeader) similarHeader.style.display = 'none';
      if (similarList) similarList.innerHTML = '';
    }
  }
  renderCachedBecauseYouPlayed({ fade: opts.fade });
  renderCachedUnexplored({ fade: opts.fade });
  if (_lastProfile) {
    renderProfile(_lastProfile, { fade: opts.fade });
  } else {
    renderDiscoverTiles(_lastProfile);
  }
}

function refreshDiscoverPrimaryState() {
  renderSimilar();
  refreshVisibleDiscoverCardState();
}

function refreshVisibleDiscoverCardState() {
  if (activeTab !== 'discover' || _isOnSubPage()) return;
  const panel = document.getElementById('panel-discover');
  if (!panel) return;
  panel.querySelectorAll('.hcard[data-song-id]').forEach(card => {
    const songId = parseInt(card.getAttribute('data-song-id') || '', 10);
    const isPlaying = Number.isFinite(songId) && nativeAudioPlaying && songId === currentSong;
    card.classList.toggle('playing', isPlaying);
    const art = card.querySelector('.hcard-art');
    if (!art) return;
    const eq = art.querySelector('.playing-eq');
    if (isPlaying && !eq) {
      const eqEl = document.createElement('div');
      eqEl.className = 'playing-eq';
      eqEl.innerHTML = '<span></span><span></span><span></span>';
      art.appendChild(eqEl);
    } else if (!isPlaying && eq) {
      eq.remove();
    }
  });
}

async function renderUnexploredClusters(opts = {}) {
  const header = document.getElementById('unexplored-header');
  const el = document.getElementById('unexplored-clusters');
  if (!el || !header) return;
  if (!engine.isEmbeddingsReady()) {
    _renderUnexploredPlaceholder();
    return;
  }
  const sections = await engine.getUnexploredClusters(3, 8, {
    refresh: opts.refresh === true,
    avoidSongIds: opts.refresh ? document.querySelectorAll('#unexplored-clusters .hcard').length > 0
      ? Array.from(new Set((_cachedUnexplored || []).flatMap(sec => (sec.songs || []).map(s => s.id))))
      : []
      : [],
  });
  _renderUnexploredSections(sections, opts);
}

let _cachedBecauseYouPlayed = [];
let _cachedForYou = [];
let _cachedSimilar = [];
let _lastSimilarIds = '';
let _similarFrozen = false;
let _cachedFavorites = [];
let _cachedPlaylists = [];
let _cachedUnexplored = [];
let _viewAllItems = []; // current ViewAll list — sectionKey 'viewAll' resolves to this
let _currentViewAllType = null;
let _currentPlaylistViewId = null;

function _buildDiscoverCacheSnapshot() {
  return {
    profile: _lastProfile || null,
    becauseYouPlayed: _cachedBecauseYouPlayed || [],
    unexplored: _cachedUnexplored || [],
    recentlyPlayed: _cachedRecentlyPlayed || [],
    favorites: _cachedFavorites || [],
  };
}

function _saveVisibleDiscoverCache() {
  engine.saveDiscoverCache(_buildDiscoverCacheSnapshot()).catch(() => {});
}

function _saveVisibleDiscoverCacheDebounced() {
  engine.saveDiscoverCacheDebounced(_buildDiscoverCacheSnapshot());
}

function _filterSongItems(items, songId) {
  return (Array.isArray(items) ? items : []).filter(item => item && item.id !== songId);
}

function _filterBecauseYouPlayedSections(sections, songId) {
  return (Array.isArray(sections) ? sections : [])
    .map(sec => ({
      ...sec,
      recommendations: _filterSongItems(sec && sec.recommendations, songId),
    }))
    .filter(sec => sec && sec.sourceId !== songId && (sec.recommendations || []).length > 0);
}

function _filterSectionSongGroups(sections, songId) {
  return (Array.isArray(sections) ? sections : [])
    .map(sec => ({
      ...sec,
      songs: _filterSongItems(sec && sec.songs, songId),
    }))
    .filter(sec => sec && (sec.songs || []).length > 0);
}

function _pruneSongFromDiscoverCaches(songId) {
  _cachedForYou = _filterSongItems(_cachedForYou, songId);
  _cachedSimilar = _filterSongItems(_cachedSimilar, songId);
  _lastSimilarIds = (_cachedSimilar || []).map(s => s.id).join(',');
  _cachedBecauseYouPlayed = _filterBecauseYouPlayedSections(_cachedBecauseYouPlayed, songId);
  _cachedUnexplored = _filterSectionSongGroups(_cachedUnexplored, songId);
  _cachedRecentlyPlayed = _filterSongItems(_cachedRecentlyPlayed, songId);
  _cachedFavorites = _filterSongItems(_cachedFavorites, songId);
  _viewAllItems = _filterSongItems(_viewAllItems, songId);

  if (_lastProfile) {
    _lastProfile = {
      ..._lastProfile,
      forYou: _filterSongItems(_lastProfile.forYou, songId),
      mostPlayed: _filterSongItems(_lastProfile.mostPlayed, songId),
      neverPlayed: _filterSongItems(_lastProfile.neverPlayed, songId),
      stats: _lastProfile.stats
        ? {
            ..._lastProfile.stats,
            neverPlayedCount: _filterSongItems(_lastProfile.neverPlayed, songId).length,
          }
        : _lastProfile.stats,
    };
  }
}

function _rerenderCachedDiscoverViews() {
  if (_currentViewAllType && !_currentPlaylistViewId) {
    viewAllUI(_currentViewAllType, { items: _viewAllItems }).catch(() => {});
  }
  if (activeTab === 'discover' && !_isOnSubPage() && !_hasDiscoverBackup()) {
    renderDiscoverSnapshotFromCache({ fade: false });
    refreshVisibleDiscoverCardState();
    return;
  }
  if (activeTab === 'browse' && !document.querySelector('#panel-browse .viewall-header')) {
    renderDiscoverTiles(_lastProfile);
  }
}

// sectionKey: when provided, tap plays the song and replaces Up Next with the
// rest of that section starting after the tapped song. When absent, falls back
// to plain engine.play (standalone song tap).
function cardHtml(s, badge, source, sectionKey) {
  const initial = (s.title || '?')[0].toUpperCase();
  const badgeHtml = badge ? `<div class="card-badge">${badge}</div>` : '';
  const redDot = (s.hasEmbedding === false) ? '<span class="red-dot" title="No embedding"></span>' : '';
  const fullSong = (s.id != null) ? engine.getSongs()[s.id] : null;
  const isDis = s.disliked || (fullSong && fullSong.disliked);
  const disBadge = isDis ? '<span class="dislike-badge" title="Disliked">\uD83D\uDC4E</span>' : '';
  const onclick = sectionKey
    ? `window._app.playFromSection(${s.id}, '${sectionKey}')`
    : `window._app.playSong(${s.id}, '${source}')`;
  const isPlaying = (s.id != null && s.id === currentSong && nativeAudioPlaying);
  const playingCls = isPlaying ? ' playing' : '';
  const eqHtml = isPlaying ? '<div class="playing-eq"><span></span><span></span><span></span></div>' : '';
  const artUrl = getArtUrl(s);
  const onerr = s && s.id != null ? _artOnErrorAttr(s.id, initial, '') : '';
  const artContent = artUrl
    ? `<img src="${artUrl}" class="hcard-art-img" decoding="async" alt="" ${onerr}>${redDot}`
    : `${esc(initial)}${redDot}`;
  return `<div class="hcard${playingCls}" data-song-id="${s.id != null ? s.id : ''}" onclick="${onclick}">
    <div class="hcard-art">${disBadge}${artContent}${eqHtml}</div>
    <div class="hcard-title">${esc(s.title)}</div>
    <div class="hcard-artist">${esc(s.artist)}</div>
    <div class="hcard-menu" onclick="event.stopPropagation(); window._app.showSongMenu(${s.id}, this)">&#8942;</div>
    ${badgeHtml}
  </div>`;
}

// ===== VIEW ALL OVERLAY =====

let discoverContentBackup = null;
let discoverContentBackupPanelId = null;

async function viewAllUI(type, opts = {}) {
  const useProvidedItems = Array.isArray(opts.items);
  let items = useProvidedItems ? opts.items.slice() : [];
  const meta = getViewAllMeta(type);
  const title = meta.title;

  if (!useProvidedItems && type === 'mostPlayed') {
    const profile = await engine.analyzeProfile(500);
    items = profile.mostPlayed || [];
  } else if (!useProvidedItems && type === 'recentlyPlayed') {
    items = await engine.loadRecentlyPlayed(200);
  } else if (!useProvidedItems && type === 'lastAdded') {
    items = engine.getLastAddedSongs(200);
  } else if (!useProvidedItems && type === 'neverPlayed') {
    const profile = await engine.analyzeProfile(500);
    items = profile.neverPlayed || [];
  } else if (!useProvidedItems && type === 'noEmbedding') {
    items = allSongs.filter(s => !s.hasEmbedding).map(s => ({
      id: s.id, title: s.title, artist: s.artist, album: s.album, hasEmbedding: false,
    }));
  } else if (!useProvidedItems && type === 'favorites') {
    items = engine.getFavoritesList();
  } else if (!useProvidedItems && type === 'dislikedSongs') {
    items = engine.getDislikedSongsList();
  } else if (!useProvidedItems && type === 'forYou') {
    const profile = await engine.analyzeProfile(500);
    items = profile.forYou || [];
  } else if (!useProvidedItems && type === 'similar') {
    const ins = engine.getInsights();
    items = (ins.topSimilar || []).map(s => {
      const song = engine.getSongs()[s.id];
      return { id: s.id, title: s.title, artist: s.artist, album: song ? song.album : '', hasEmbedding: true, artPath: s.artPath };
    });
  }

  _viewAllItems = items;
  _currentViewAllType = type;
  _currentPlaylistViewId = null;

  const panelId = activeTab === 'browse' ? 'panel-browse' : 'panel-discover';
  const panel = document.getElementById(panelId);
  if (!panel) return;
  if (!panel.querySelector('.viewall-header')) {
    discoverContentBackup = panel.innerHTML;
    discoverContentBackupPanelId = panelId;
  }

  const listHtml = items.length > 0
    ? items.map((s) => {
        const badge = type === 'mostPlayed' && s.play_count ? `<div class="similarity">${s.play_count}\u00d7</div>` : '';
        const redDot = (s.hasEmbedding === false) ? '<span class="red-dot-inline"></span>' : '';
        const full = engine.getSongs()[s.id];
        const onclick = `window._app.playFromSection(${s.id}, 'viewAll')`;
        return `<div class="song-item" onclick="${onclick}">
          ${full ? songThumb(full) : `<div class="song-thumb song-thumb-letter">${esc((s.title||'?')[0])}</div>`}
          <div class="song-info">
            <div class="song-title">${redDot}${esc(s.title)}</div>
            <div class="song-artist">${esc(s.artist)} \u00b7 ${esc(s.album || '')}</div>
          </div>
          ${badge}
          <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${s.id}, this)">&#8942;</div>
        </div>`;
      }).join('')
    : `<div class="playlist-empty-large">${esc(meta.empty)}</div>`;

  panel.innerHTML = `
    <div class="viewall-header">
      <button class="viewall-back" onclick="window._app.closeViewAll()">\u2190</button>
      <span class="viewall-title">${esc(title)}</span>
      <span class="viewall-count">${items.length} songs</span>
    </div>
    <div class="viewall-list">
      ${listHtml}
    </div>
  `;
  const content = document.querySelector('.content');
  if (content) content.scrollTop = 0;
}

async function refreshCurrentViewAllUI() {
  if (!_currentViewAllType || _currentPlaylistViewId) return;
  const content = document.querySelector('.content');
  const prevScrollTop = content ? content.scrollTop : 0;
  await viewAllUI(_currentViewAllType);
  const nextContent = document.querySelector('.content');
  if (!nextContent) return;
  const maxScroll = Math.max(0, nextContent.scrollHeight - nextContent.clientHeight);
  nextContent.scrollTop = Math.min(prevScrollTop, maxScroll);
}

function refreshBrowseCollectionsUI() {
  loadFavorites();
  if (_currentViewAllType === 'favorites' || _currentViewAllType === 'dislikedSongs') {
    refreshCurrentViewAllUI().catch(() => {});
  }
}

function openPlaylistUI(playlistId) {
  const meta = engine.getPlaylistMeta(playlistId);
  if (!meta) {
    showStatusToast('Playlist not found', 1500);
    return;
  }
  const items = engine.getPlaylistSongs(playlistId);
  _viewAllItems = items;
  _currentViewAllType = 'playlist';
  _currentPlaylistViewId = playlistId;

  const panel = document.getElementById('panel-browse');
  if (!panel) return;
  if (!panel.querySelector('.viewall-header')) {
    discoverContentBackup = panel.innerHTML;
    discoverContentBackupPanelId = 'panel-browse';
  }

  panel.innerHTML = `
    <div class="viewall-header">
      <button class="viewall-back" onclick="window._app.closeViewAll()">\u2190</button>
      <span class="viewall-title">${esc(meta.name)}</span>
      <span class="viewall-count">${items.length} songs</span>
    </div>
    <div class="playlist-page-actions">
      <button class="action-btn" onclick="window._app.renamePlaylist('${playlistId}')">Rename</button>
      <button class="action-btn reset-btn" onclick="window._app.deletePlaylist('${playlistId}')">Delete</button>
      <button class="action-btn" onclick="window._app.showPlaylistPicker()">+ New</button>
    </div>
    <div class="viewall-list">
      ${items.length > 0 ? items.map((s) => {
        const redDot = (s.hasEmbedding === false) ? '<span class="red-dot-inline"></span>' : '';
        return `<div class="song-item" onclick="window._app.playFromPlaylist(${s.id}, '${playlistId}')">
          ${songThumb(engine.getSongs()[s.id] || s)}
          <div class="song-info">
            <div class="song-title">${redDot}${esc(s.title)}</div>
            <div class="song-artist">${esc(s.artist)} \u00b7 ${esc(s.album || '')}</div>
          </div>
          <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${s.id}, this)">&#8942;</div>
        </div>`;
      }).join('') : '<div class="playlist-empty-large">No songs in this playlist yet. Use any song menu to add songs here.</div>'}
    </div>
  `;

  const content = document.querySelector('.content');
  if (content) content.scrollTop = 0;
}

function closeViewAll() {
  if (discoverContentBackup && discoverContentBackupPanelId) {
    const panelId = discoverContentBackupPanelId;
    const panel = document.getElementById(discoverContentBackupPanelId);
    panel.innerHTML = discoverContentBackup;
    discoverContentBackup = null;
    discoverContentBackupPanelId = null;
    _viewAllItems = [];
    _currentViewAllType = null;
    _currentPlaylistViewId = null;
    if (panelId === 'panel-browse') {
      loadPlaylistsUI();
      loadFavorites();
      if (_lastProfile) renderDiscoverTiles(_lastProfile);
    } else if (panelId === 'panel-discover') {
      if (!_flushQueuedDiscoverRefresh()) refreshDiscoverPrimaryState();
    }
  }
}

// ===== NATIVE AUDIO EVENTS =====

function setupNativeAudioEvents() {
  if (_nativeAudioEventsBound) return;
  _nativeAudioEventsBound = true;
  try {
    // Position updates from native MediaPlayer (every 250ms while playing)
    MusicBridge.addListener('audioTimeUpdate', (data) => {
      nativeAudioPos = data.position || 0;
      nativeAudioDur = data.duration || 0;
      nativeAudioPlaying = data.isPlaying || false;
      if (typeof data.playedMs === 'number') nativeAudioPlayedMs = data.playedMs;
      if (typeof data.durationMs === 'number' && data.durationMs > 0) nativeAudioDurationMs = data.durationMs;
      if (typeof data.playbackInstanceId === 'number') {
        nativePlaybackInstanceId = data.playbackInstanceId;
        engine.bindCurrentPlaybackStartInstance(nativePlaybackInstanceId, currentSong);
      }
      updateProgressUI(nativeAudioPos, nativeAudioDur);

      // Feed live listen fraction to engine (accumulator-backed, clamped to 1.0)
      const liveFrac = getListenFraction();
      if (liveFrac != null) engine.setLiveListenFraction(liveFrac);
      if (nativeAudioPlaying) persistPlaybackState(false);
    });

    // Play/pause state changes
    MusicBridge.addListener('audioPlayStateChanged', (data) => {
      _dbg('audioPlayStateChanged: isPlaying=' + data.isPlaying + ' currentSong=' + currentSong + ' embReady=' + engine.isEmbeddingsReady() + ' aiReady=' + _aiReadyCommitted);
      nativeAudioPlaying = data.isPlaying;
      document.body.classList.toggle('audio-paused', !data.isPlaying);
      logActivity({
        category: 'native',
        type: 'native_play_state_changed',
        message: data.isPlaying ? 'Native playback resumed' : 'Native playback paused',
        data: { isPlaying: !!data.isPlaying },
        tags: ['native', 'playback'],
        important: false,
      });
      updatePlayIcon(!data.isPlaying);
      persistPlaybackState(true);
    });

    // Native queue advanced (auto-completion, BT skip, notification skip)
    MusicBridge.addListener('queueCurrentChanged', async (data) => {
      _dbg('queueCurrentChanged: action=' + data.action + ' songId=' + data.songId + ' title=' + (data.title || '?') + ' prevFrac=' + data.prevFraction + ' prevCurrentSong=' + currentSong + ' embReady=' + engine.isEmbeddingsReady() + ' aiReady=' + _aiReadyCommitted);
      nativePlaybackInstanceId = Number(data.currentPlaybackInstanceId) || nativePlaybackInstanceId;
      logActivity({
        category: 'native',
        type: 'queue_current_changed',
        message: `Native queue changed via ${data.action || 'unknown'}`,
        data: {
          action: data.action,
          songId: data.songId,
          title: data.title || '',
          artist: data.artist || '',
          album: data.album || '',
          filePath: data.filePath || '',
          prevFraction: data.prevFraction,
        },
        tags: ['native', 'queue'],
        important: true,
      });
      let nativeQueueState = null;
      try {
        nativeQueueState = await MusicBridge.getQueueState();
      } catch (e) { /* ignore */ }
      const result = engine.onNativeAdvance(data, nativeQueueState);
      await _clearPendingListenIfResolvedByNative(data);
      if (result && result.songInfo) {
        const currentInfo = engine.getState().current || result.songInfo;
        currentSong = currentInfo.id;
        currentIsFav = currentInfo.isFavorite || false;
        document.getElementById('npTitle').textContent = currentInfo.title;
        document.getElementById('npArtist').textContent = currentInfo.artist + ' \u00b7 ' + (currentInfo.album || '');
        document.getElementById('nowPlaying').style.display = 'block';
        updateHeartIcon(currentIsFav);
        updateModeIndicator();
        syncFullPlayer();
        nativeAudioPlaying = true;
        nativeFileLoaded = true;
        updatePlayIcon(false);
        nativeAudioPos = 0;
        nativeAudioDur = 0;
        nativeAudioPlayedMs = 0;
        nativeAudioDurationMs = 0;
        if (result.needsSync) {
          const upcoming = engine.getUpcomingNativeItems();
          if (upcoming.length > 0) {
            try { await MusicBridge.replaceUpcoming({ items: upcoming }); } catch (e) { _dbg('replaceUpcoming err: ' + e.message); }
          }
        }

        refreshStateUI();
        engine.savePlaybackState(0);
      }
    });

    MusicBridge.addListener('queueEnded', () => {
      _dbg('queueEnded');
      const st = engine.getState();
      if (!st.playingAlbum && !st.playingFavorites && !st.playingPlaylist) {
        const info = engine.nextSong(null, 'queue_end_resume');
        if (info) {
          loadAndPlay(info).then(() => {
            refreshStateUI();
            _dbg('queueEnded: resumed into rebuilt AI queue');
          }).catch((e) => {
            _dbg('queueEnded resume failed: ' + e.message);
          });
          return;
        }
      }
      nativeAudioPlaying = false;
      logActivity({ category: 'native', type: 'native_queue_ended', message: 'Native queue ended', tags: ['native', 'queue'], important: true });
      updatePlayIcon(true);
      showStatusToast('Queue finished', 2000);
    });

    // Audio errors
    MusicBridge.addListener('audioError', async (data) => {
      _dbg('audioError: ' + (data && data.error) + ' currentSong=' + currentSong + ' nativeFileLoaded=' + nativeFileLoaded + ' nativeAudioPos=' + nativeAudioPos);
      console.error('Native audio error:', data.error);
      logActivity({ category: 'native', type: 'native_audio_error', message: `Native audio error: ${data.error}`, data: { error: data.error }, tags: ['native', 'error'], important: true, level: 'error' });

      // File-not-found from an externally deleted file: reconcile the library
      // so the song disappears from the UI. Embedding stays until the user
      // consents to remove it via the AI-page Orphaned bucket.
      const err = (data && data.error) || '';
      const path = data && data.path;
      const isFileMissing = path && (
        err.indexOf('File not found') === 0 ||
        err.indexOf('ENOENT') >= 0 ||
        err.indexOf('No such file') >= 0 ||
        err.indexOf('FileNotFoundException') >= 0
      );
      if (isFileMissing) {
        try {
          const res = await engine.markSongMissingByPath(path);
          if (res && res.removed > 0) {
            allSongs = engine.getPlayableSongs();
            if (activeTab === 'songs') renderSongs(allSongs);
            refreshStateUI();
            showStatusToast('Song file no longer on device — removed from library', 2500);
          }
        } catch (e) {
          console.warn('markSongMissingByPath failed:', e && e.message || e);
        }
      } else {
        showStatusToast('Playback error: ' + String(err || 'unknown error').slice(0, 80), 3000);
      }
    });
  } catch (e) {
    console.log('Native audio events not available:', e);
  }
}

function updateProgressUI(pos, dur) {
  const pct = dur > 0 ? (pos / dur * 100) : 0;
  document.getElementById('progressFill').style.width = pct + '%';
  const thumb = document.getElementById('seekThumb');
  if (thumb) thumb.style.left = pct + '%';
  document.getElementById('npTime').textContent = formatTime(pos);
  document.getElementById('npDuration').textContent = formatTime(dur);
  updateFullPlayerProgress(pos, dur);
}

function updatePlayIcon(paused) {
  document.getElementById('playIcon').innerHTML = paused
    ? '<path d="M8 5v14l11-7z"/>'
    : '<path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>';
  updateFullPlayerPlayIcon(paused);
}

// ===== NATIVE MEDIA LISTENER (notification/lock screen controls) =====

function setupNativeMediaListener() {
  try {
    MusicBridge.addListener('mediaAction', async (data) => {
      const action = data && data.action ? String(data.action) : '';
      if (action === 'favorite') {
        const fallbackSongId = currentSong;
        const resolvedSongId = Number.isInteger(Number(data.songId)) && Number(data.songId) >= 0
          ? Number(data.songId)
          : fallbackSongId;
        if (resolvedSongId == null) return;
        const result = engine.setFavoriteState(resolvedSongId, !!data.isFavorite, { source: 'native_notification' });
        if (!result || !result.ok) return;
        if (resolvedSongId === currentSong) {
          currentIsFav = !!result.isFavorite;
          updateHeartIcon(currentIsFav);
        }
        loadFavorites();
        updateFavCount(result.count);
        const title = data.title || (engine.getSongs()[resolvedSongId] ? engine.getSongs()[resolvedSongId].title : '');
        logActivity({
          category: 'ui',
          type: 'favorite_notification_pressed',
          message: `${result.isFavorite ? 'Favorited' : 'Unfavorited'} "${title}" from notification`,
          data: { songId: resolvedSongId, isFavorite: result.isFavorite, source: 'native_notification' },
          tags: ['favorite', 'notification'],
          important: true,
        });
        showStatusToast(result.isFavorite ? `"${title}" added to favorites` : `"${title}" removed from favorites`, 1800);
        return;
      }

      if (action === 'dismiss') {
        try { await _persistPendingListenEvidence('notification_close'); } catch (e) { /* ignore */ }
        await engine.clearPlaybackSession();
        nativeAudioPlaying = false;
        nativeFileLoaded = false;
        nativeAudioPos = 0;
        nativeAudioDur = 0;
        nativeAudioPlayedMs = 0;
        nativeAudioDurationMs = 0;
        nativePlaybackInstanceId = 0;
        currentSong = null;
        currentIsFav = false;
        _miniPlayerFromQuickDisplay = false;
        closeFullPlayer();
        updateHeartIcon(false);
        updatePlayIcon(true);
        updateProgressUI(0, 0);
        refreshStateUI();
        logActivity({
          category: 'ui',
          type: 'notification_player_closed',
          message: 'Closed player from notification',
          tags: ['notification', 'playback'],
          important: true,
        });
        showStatusToast('Player closed', 1600);
      }
    });
  } catch (e) {
    console.log('Native media actions not available:', e);
  }
}

// ===== POSITION PERSISTENCE =====

function setupPositionPersistence() {
  // Save position and a non-authoritative pending listen snapshot when the app
  // goes to background. Native transitions remain the final authority; the
  // snapshot is only for cold-start recovery if no later native result exists.
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && currentSong != null) {
      persistPlaybackState(true);
      _persistPendingListenEvidence('visibility_hidden');
    }
  });

  // pagehide fires when the page is being unloaded (navigation, app teardown).
  // On Capacitor Android this is a more reliable "app is going down" signal
  // than visibilitychange alone.
  window.addEventListener('pagehide', () => {
    if (currentSong != null) {
      persistPlaybackState(true);
      _persistPendingListenEvidence('pagehide');
    }
  });

  // Save position periodically while playing so recent-task kills do not fall
  // back to a stale 10s-old checkpoint on next launch.
  setInterval(() => {
    if (nativeAudioPlaying && currentSong != null) {
      persistPlaybackState(false);
    }
  }, 2000);
}

function setupEmbeddingUI() {
  try {
    let embUiRefreshTimer = null;
    let embUiRefreshDiscover = false;

    const refreshIfOnDetailPage = () => {
      // If the detail page is currently showing, refresh it live
      const panel = document.getElementById('panel-discover');
      if (panel && panel.querySelector('.emb-detail-page')) {
        showEmbeddingDetail();
      }
    };

    const scheduleEmbeddingUiRefresh = ({ delay = 120, refreshDiscover = false } = {}) => {
      embUiRefreshDiscover = embUiRefreshDiscover || refreshDiscover;
      if (embUiRefreshTimer) return;
      embUiRefreshTimer = setTimeout(() => {
        embUiRefreshTimer = null;
        const shouldRefreshDiscover = embUiRefreshDiscover;
        embUiRefreshDiscover = false;
        updateEmbeddingStatus();
        refreshIfOnDetailPage();
        if (shouldRefreshDiscover && activeTab === 'discover' && !_isOnSubPage()) {
          refreshDiscoverPrimaryState();
        }
      }, delay);
    };

    MusicBridge.addListener('embeddingProgress', () => {
      scheduleEmbeddingUiRefresh();
    });
    MusicBridge.addListener('embeddingSongComplete', () => {
      scheduleEmbeddingUiRefresh();
    });
    MusicBridge.addListener('embeddingComplete', (data) => {
      _dbg('native embeddingComplete: processed=' + (data && data.processed) + ' failed=' + (data && data.failed) + ' currentSong=' + currentSong + ' nativeAudioPlaying=' + nativeAudioPlaying);
      // Delay slightly to let engine handler finish async work
      scheduleEmbeddingUiRefresh({ delay: 300, refreshDiscover: true });
    });
    MusicBridge.addListener('embeddingError', () => {
      scheduleEmbeddingUiRefresh();
    });

    // Auto-refresh detail page every 2s while it's open (catches missed events)
    setInterval(() => {
      const panel = document.getElementById('panel-discover');
      if (panel && panel.querySelector('.emb-detail-page')) {
        showEmbeddingDetail();
      }
    }, 2000);
  } catch (e) {
    console.log('Embedding UI listeners not available:', e);
  }
}

function getListenFraction() {
  // Prefer the native cumulative-played accumulator (ignores seeks; honest for
  // seek-heavy / sample-heavy listening). Falls back to position/duration only
  // when the accumulator hasn't reported yet.
  if (nativeAudioDurationMs > 0 && nativeAudioPlayedMs >= 0) {
    const raw = nativeAudioPlayedMs / nativeAudioDurationMs;
    return Math.min(1.0, Math.round(raw * 100) / 100);
  }
  if (!nativeAudioDur || nativeAudioDur <= 0) return null;
  return Math.min(1.0, Math.round((nativeAudioPos / nativeAudioDur) * 100) / 100);
}

// ===== MODE INDICATOR =====

function updateModeIndicator() {
  const st = engine.getState();
  const el = document.getElementById('modeIndicator');
  if (!el) return;

  const mode = st.modeIndicator;
  el.className = 'mode-indicator';
  if (mode === 'AI') {
    el.textContent = 'AI';
    el.classList.add('mode-ai');
  } else if (mode === 'List') {
    el.textContent = 'List';
    el.classList.add('mode-album');
  } else if (mode === 'Album') {
    el.textContent = 'Album';
    el.classList.add('mode-album');
  } else if (mode === 'Favorites') {
    el.textContent = 'Favs';
    el.classList.add('mode-album');
  } else if (mode === 'Playlist') {
    el.textContent = 'Playlist';
    el.classList.add('mode-album');
  } else {
    el.textContent = 'Shuffle';
    el.classList.add('mode-shuffle');
  }

  // Red dot for unembedded current song
  const redDot = document.getElementById('npRedDot');
  if (redDot) {
    redDot.style.display = (st.current && st.current.hasEmbedding === false) ? 'inline' : 'none';
  }
}

// ===== SHUFFLE & LOOP =====

function toggleShuffleUI() {
  shuffleOn = !shuffleOn;
  const btn = document.getElementById('shuffleBtn');
  if (btn) { btn.classList.toggle('active-mode', shuffleOn); btn.title = shuffleOn ? 'Shuffle: keep remaining Up Next songs randomized' : 'Shuffle off'; }
  const fpShuffle = document.getElementById('fpShuffleBtn');
  if (fpShuffle) fpShuffle.classList.toggle('active-mode', shuffleOn);
  engine.setQueueShuffleEnabled(shuffleOn);
  showStatusToast(shuffleOn ? 'Shuffle: remaining Up Next songs randomized' : 'Shuffle off', 1800);
  if (shuffleOn) {
    refreshStateUI();
    syncUpcomingNativeQueue();
    _scheduleRecsFocusCurrent();
  }
}

// Loop cycles: off → one → all → off
// - off: play queue to end, then stop
// - one: native MediaPlayer repeats the current song forever
// - all: queue auto-refreshes on exhaustion (AI/shuffle keeps flowing)
function toggleLoopUI() {
  loopMode = loopMode === 'off' ? 'one' : (loopMode === 'one' ? 'all' : 'off');
  const loopModeMap = { off: 0, one: 1, all: 2 };
  try { MusicBridge.setLoopMode({ mode: loopModeMap[loopMode] || 0 }); } catch (e) { /* ignore */ }
  const apply = (el) => {
    if (!el) return;
    el.classList.toggle('active-mode', loopMode !== 'off');
    el.classList.toggle('loop-one', loopMode === 'one');
    el.classList.toggle('loop-all', loopMode === 'all');
    el.title = loopMode === 'one' ? 'Repeat current song' : (loopMode === 'all' ? 'Repeat all (auto-refresh queue)' : 'Loop off');
  };
  apply(document.getElementById('loopBtn'));
  apply(document.getElementById('fpLoopBtn'));
  const msg = loopMode === 'one' ? 'Loop: repeat this song' : (loopMode === 'all' ? 'Loop: repeat all' : 'Loop off');
  showStatusToast(msg, 1800);
}

// ===== REC TOGGLE =====

function goToQueueUI() {
  if (fullPlayerOpen) closeFullPlayer();
  _activateTab('recs', { resetScroll: false });
  _scheduleRecsFocusCurrent();
}

function toggleRecUI(on) {
  engine.setRecToggle(on);
  const toggle = document.getElementById('recToggle');
  if (toggle) toggle.checked = on;
  updateModeIndicator();
  refreshStateUI();
  syncUpcomingNativeQueue();
  showStatusToast(on ? 'AI recommendations on' : 'Shuffle mode', 1500);
}

// ===== PULL TO REFRESH =====

function setupPullToRefresh() {
  const content = document.querySelector('.content');
  if (!content) return;

  // Note: #pullIndicator / #discover-pull-body can be replaced in the DOM when
  // sub-pages (viewAll / taste overlay) close and restore panel.innerHTML.
  // Resolve lazily on every gesture so state still applies after return.
  const getIndicator = () => document.getElementById('pullIndicator');
  const getPullBody = () => document.getElementById('discover-pull-body');

  let startY = 0;
  let pulling = false;
  let currentPull = 0;
  let refreshing = false;
  const PULL_TRIGGER = 128;
  const MAX_PULL = 180;
  const MIN_SPIN_MS = 650;

  // Smooth HSL hue interpolation: 0deg (red) at no pull, 120deg (green) at trigger.
  function colorForPull(dist) {
    const t = Math.max(0, Math.min(1, dist / PULL_TRIGGER));
    const hue = Math.round(t * 120); // red -> yellow -> green
    const alphaTop = 0.30 + t * 0.22;    // 0.30 -> 0.52
    const alphaBot = 0.14 + t * 0.14;    // 0.14 -> 0.28
    const shadow = 0.55 + t * 0.25;      // 0.55 -> 0.80
    return {
      bg: `linear-gradient(180deg, hsla(${hue}, 72%, 48%, ${alphaTop.toFixed(2)}) 0%, hsla(${hue}, 72%, 48%, ${alphaBot.toFixed(2)}) 100%)`,
      shadow: `inset 0 -2px 0 hsla(${hue}, 72%, 48%, ${shadow.toFixed(2)})`,
    };
  }

  function applyPullState(dist) {
    const indicator = getIndicator();
    const pullBody = getPullBody();
    if (!indicator || !pullBody) return;
    currentPull = dist;
    const ready = dist >= PULL_TRIGGER;
    // Subtle sizes: indicator max ~58px, body offset max ~48px.
    const indicatorHeight = Math.min(58, Math.round(dist * 0.42));
    const bodyOffset = Math.min(48, Math.round(dist * 0.36));
    indicator.classList.add('pull-tracking');
    pullBody.classList.add('pull-tracking');
    indicator.style.height = indicatorHeight + 'px';
    indicator.classList.add('pulling');
    indicator.classList.remove('pulling-low', 'pulling-ready', 'refreshing');
    const { bg, shadow } = colorForPull(dist);
    indicator.style.background = bg;
    indicator.style.boxShadow = shadow;
    indicator.style.color = '#ffffff';
    indicator.innerHTML = ready
      ? '<span class="pull-arrow pull-arrow-ready">\u2193</span> Release to refresh'
      : '<span class="pull-arrow">\u2193</span> Pull to refresh';
    pullBody.style.transform = `translateY(${bodyOffset}px)`;
  }

  function clearPullState() {
    currentPull = 0;
    const indicator = getIndicator();
    const pullBody = getPullBody();
    if (indicator) {
      indicator.classList.remove('pull-tracking');
      indicator.style.height = '0';
      indicator.innerHTML = '';
      indicator.style.background = '';
      indicator.style.boxShadow = '';
      indicator.style.color = '';
      indicator.classList.remove('pulling', 'pulling-low', 'pulling-ready', 'refreshing');
    }
    if (pullBody) {
      pullBody.classList.remove('pull-tracking');
      pullBody.style.transform = '';
    }
  }

  content.addEventListener('touchstart', (e) => {
    if (activeTab !== 'discover' || refreshing) return;
    if (content.scrollTop <= 0 && !_isOnSubPage()) {
      startY = e.touches[0].clientY;
      pulling = true;
      currentPull = 0;
    }
  }, { passive: true });

  content.addEventListener('touchmove', (e) => {
    if (!pulling || activeTab !== 'discover' || refreshing) return;
    const dy = e.touches[0].clientY - startY;
    if (dy > 12 && content.scrollTop <= 0) {
      e.preventDefault();
      const eased = Math.min(MAX_PULL, (dy - 12) * 0.80);
      applyPullState(eased);
    } else if (currentPull > 0) {
      clearPullState();
    }
  }, { passive: false });

  async function finishPull() {
    if (!pulling || refreshing) return;
    pulling = false;
    if (currentPull >= PULL_TRIGGER && activeTab === 'discover') {
      refreshing = true;
      const indicator = getIndicator();
      const pullBody = getPullBody();
      if (indicator) {
        indicator.classList.remove('pull-tracking');
        indicator.innerHTML = '<span class="pull-spinner"></span> Refreshing...';
        indicator.classList.remove('pulling-low', 'pulling-ready');
        indicator.classList.add('refreshing');
        indicator.style.height = '58px';
        // Pin green during refresh via inline style so class restoration after
        // DOM-rebuild (sub-page close) cannot strip it mid-refresh.
        const green = colorForPull(PULL_TRIGGER);
        indicator.style.background = green.bg;
        indicator.style.boxShadow = green.shadow;
        indicator.style.color = '#ffffff';
      }
      if (pullBody) {
        pullBody.classList.remove('pull-tracking');
        pullBody.style.transform = 'translateY(48px)';
      }
      const started = Date.now();
      try {
        try { await engine.rebuildProfileVec(); } catch (e) { /* ignore */ }
        await loadDiscover({ refreshForYou: true, refreshDiscover: true });
        const elapsed = Date.now() - started;
        if (elapsed < MIN_SPIN_MS) {
          await new Promise(r => setTimeout(r, MIN_SPIN_MS - elapsed));
        }
        _saveVisibleDiscoverCache();
      } finally {
        refreshing = false;
      }
    }
    clearPullState();
  }

  content.addEventListener('touchend', () => { finishPull().catch(() => { refreshing = false; clearPullState(); }); });
  content.addEventListener('touchcancel', () => {
    pulling = false;
    if (!refreshing) clearPullState();
  });
}
setupPullToRefresh();

// ===== TABS =====

let _songsDirty = true;
let _albumsDirty = true;
let _discoverDirty = false;
let _discoverDirtyOpts = { refreshForYou: false, refreshDiscover: false };

function _clearQueuedDiscoverRefresh() {
  _discoverDirty = false;
  _discoverDirtyOpts = { refreshForYou: false, refreshDiscover: false };
}

function _mergeDiscoverRefreshOpts(opts = {}) {
  return {
    refreshForYou: _discoverDirtyOpts.refreshForYou || opts.refreshForYou === true,
    refreshDiscover: _discoverDirtyOpts.refreshDiscover || opts.refreshDiscover === true,
  };
}

function _flushQueuedDiscoverRefresh() {
  if (!_discoverDirty) return false;
  const opts = { ..._discoverDirtyOpts };
  _clearQueuedDiscoverRefresh();
  loadDiscover(opts);
  return true;
}

function _hasDiscoverBackup() {
  return !!(discoverContentBackup && discoverContentBackupPanelId === 'panel-discover');
}

function queueDiscoverRefresh(reason, opts = {}) {
  _discoverDirty = true;
  _discoverDirtyOpts = _mergeDiscoverRefreshOpts(opts);
  _dbg(`discover refresh queued: ${reason} (refreshForYou=${_discoverDirtyOpts.refreshForYou}, refreshDiscover=${_discoverDirtyOpts.refreshDiscover})`);
  if (activeTab === 'discover' && !_isOnSubPage() && !_hasDiscoverBackup()) {
    _flushQueuedDiscoverRefresh();
  }
}

function _activateTab(target, opts = {}) {
  const pushHistory = opts.pushHistory === true;
  const resetScroll = opts.resetScroll !== false;
  const content = document.querySelector('.content');
  const recsListWrap = document.getElementById('recs-list-wrap');
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  const tabEl = document.querySelector(`.tab[data-tab="${target}"]`);
  const panelEl = document.getElementById('panel-' + target);
  if (!tabEl || !panelEl) return;
  tabEl.classList.add('active');
  panelEl.classList.add('active');
  activeTab = target;
  if (content) {
    content.classList.toggle('content-recs-active', target === 'recs');
    if (resetScroll && target !== 'recs') content.scrollTop = 0;
  }
  if (recsListWrap && resetScroll && target === 'recs') recsListWrap.scrollTop = 0;
  document.getElementById('searchBar').style.display =
    (target === 'songs' || target === 'albums') ? 'block' : 'none';

  if (target === 'songs' && _songsDirty) {
    _songsDirty = false;
    renderSongs(allSongs);
  }
  if (target === 'albums' && _albumsDirty) {
    _albumsDirty = false;
    renderAlbums(allAlbums);
  }

  if (target === 'discover' && _discoverDirty && !_hasDiscoverBackup()) {
    _flushQueuedDiscoverRefresh();
  } else if (target === 'discover' && !_hasDiscoverBackup()) {
    refreshDiscoverPrimaryState();
  }

  if (target === 'recs') {
    _scheduleRecsFocusCurrent();
  }

  if (pushHistory) {
    history.pushState({ appTab: target }, '');
  }
}

if (!history.state || !history.state.appTab) {
  history.replaceState({ appTab: 'discover' }, '');
}

window.addEventListener('popstate', () => {
  if (activeTab !== 'discover') {
    _activateTab('discover', { resetScroll: false });
  }
});

document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    const target = tab.dataset.tab;
    const shouldPush = target !== 'discover' && activeTab !== target;
    _activateTab(target, { pushHistory: shouldPush });
  });
});

// ===== SEARCH =====

document.getElementById('searchInput').addEventListener('input', (e) => {
  const q = e.target.value.toLowerCase();
  const clearBtn = document.getElementById('searchClear');
  if (clearBtn) clearBtn.style.display = q.length > 0 ? 'flex' : 'none';
  if (activeTab === 'songs') {
    renderSongs(allSongs.filter(s =>
      s.title.toLowerCase().includes(q) ||
      s.artist.toLowerCase().includes(q) ||
      s.album.toLowerCase().includes(q)
    ));
  } else if (activeTab === 'albums') {
    renderAlbums(allAlbums.filter(a =>
      a.name.toLowerCase().includes(q) ||
      a.artist.toLowerCase().includes(q)
    ));
  }
});

function clearSearch() {
  const input = document.getElementById('searchInput');
  input.value = '';
  input.dispatchEvent(new Event('input'));
  const clearBtn = document.getElementById('searchClear');
  if (clearBtn) clearBtn.style.display = 'none';
  input.focus(); // keep keyboard open
}

// ===== RENDER SONGS / ALBUMS =====

function songThumb(s) {
  const artUrl = getArtUrl(s);
  const fullSong = _resolveSongForArt(s) || s;
  const songId = fullSong && fullSong.id != null ? fullSong.id : null;
  const initial = (fullSong && fullSong.title ? fullSong.title : '?')[0].toUpperCase();
  const disBadge = s && s.disliked ? '<span class="dislike-badge" title="Disliked">\uD83D\uDC4E</span>' : '';
  if (artUrl) {
    const onerr = songId != null ? _artOnErrorAttr(songId, initial, 'song-thumb-letter') : '';
    return `<div class="song-thumb">${disBadge}<img src="${artUrl}" decoding="async" alt="" ${onerr}></div>`;
  }
  return `<div class="song-thumb song-thumb-letter">${disBadge}${esc(initial)}</div>`;
}

function renderSongs(list) {
  const sorted = [...list].sort((a, b) => a.title.localeCompare(b.title));
  document.getElementById('panel-songs').innerHTML = sorted.map((s, i) => {
    const redDot = !s.hasEmbedding ? '<span class="red-dot-inline"></span>' : '';
    const isPlay = (currentSong === s.id && nativeAudioPlaying);
    const eq = isPlay ? '<div class="playing-eq song-eq"><span></span><span></span><span></span></div>' : '';
    return `<div class="song-item ${isPlay ? 'playing' : ''}" onclick="window._app.playSong(${s.id})">
      ${songThumb(s)}${eq}
      <div class="song-info">
        <div class="song-title">${redDot}${esc(s.title)}</div>
        <div class="song-artist">${esc(s.artist)} \u00b7 ${esc(s.album)}</div>
      </div>
      <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${s.id}, this)">&#8942;</div>
    </div>`;
  }).join('');
}

function renderAlbums(list) {
  document.getElementById('panel-albums').innerHTML = list.map(a => {
    const trackIds = JSON.stringify(a.tracks.map(t => t.id));
    const artTrack = a.tracks.find(t => {
      const full = engine.getSongs()[t.id];
      return full && full.filePath;
    });
    const albumArtUrl = artTrack ? getArtUrl(engine.getSongs()[artTrack.id]) : null;
    const albumArtContent = albumArtUrl
      ? `<img src="${albumArtUrl}" class="album-art-img" decoding="async" alt="" ${artTrack ? _artOnErrorAttr(artTrack.id, a.name.charAt(0), '') : ''}>`
      : esc(a.name.charAt(0));
    return `<div class="album-item">
      <div class="album-header" onclick="window._app.toggleAlbum(this)">
        <div class="album-art">${albumArtContent}</div>
        <div class="album-info">
          <div class="album-name">${esc(a.name)}</div>
          <div class="album-meta">${esc(a.artist)} \u00b7 ${a.count} songs</div>
        </div>
        <div class="album-chevron">&#9654;</div>
      </div>
      <div class="album-tracks" data-track-ids='${trackIds}'>
        ${a.tracks.map((t, j) => {
          const trackSong = engine.getSongs()[t.id];
          return `
          <div class="song-item ${(currentSong === t.id && nativeAudioPlaying) ? 'playing' : ''}" onclick="window._app.playFromAlbum(${t.id}, ${esc(trackIds)})">
            ${trackSong ? songThumb(trackSong) : `<div class="song-thumb song-thumb-letter">${esc(t.title[0])}</div>`}
            <div class="song-info">
              <div class="song-title">${esc(t.title)}</div>
              <div class="song-artist">${esc(t.artist)}</div>
            </div>
            <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${t.id}, this)">&#8942;</div>
          </div>`;
        }).join('')}
      </div>
    </div>`;
  }).join('');
}

function toggleAlbumUI(header) {
  header.nextElementSibling.classList.toggle('expanded');
  header.querySelector('.album-chevron').classList.toggle('expanded');
}

// ===== RENDER RECS / HISTORY =====

function renderRecs(data) {
  const list = document.getElementById('recs-list');
  const label = document.getElementById('recs-session-label');
  if (data.sessionLabel) {
    label.textContent = data.sessionLabel;
    label.style.display = 'block';
  } else {
    label.style.display = 'none';
  }

  // Sync rec toggle checkbox
  const toggle = document.getElementById('recToggle');
  if (toggle) toggle.checked = data.recToggle;

  const timeline = data.timeline && Array.isArray(data.timeline.items) ? data.timeline : { items: [], explicit: false };
  if ((!timeline.items || timeline.items.length === 0) && (!data.queue || data.queue.length === 0)) {
    list.innerHTML = '<div class="empty-state">Play a song to get recommendations</div>';
    return;
  }

  const allSongsData = engine.getSongs();
  const rowHtml = (s) => {
    const full = allSongsData[s.id];
    const manualBadge = s.manual ? '<span class="queue-manual-badge" title="Added via Play Next">\u25B6</span>' : '';
    const roleBadge = s.role === 'current'
      ? '<span class="timeline-badge timeline-badge-current">Now</span>'
      : (s.role === 'previous'
          ? `<span class="timeline-badge">${timeline.explicit ? 'Earlier' : 'Played'}</span>`
          : '');
    const similarity = s.role === 'upcoming' && parseFloat(s.similarity) > 0
      ? `<div class="similarity">${Math.round(parseFloat(s.similarity) * 100)}%</div>`
      : '';
    const removeBtn = s.role === 'upcoming' && s.queueIndex != null
      ? `<div class="queue-remove-btn" onclick="event.stopPropagation(); window._app.removeFromQueue(${s.queueIndex})" title="Remove from Up Next">&times;</div>`
      : '';
    const rowClass = s.role === 'current' ? 'playing' : '';
    return `
    <div class="song-item ${rowClass}" onclick="window._app.playTimelineIndex(${s.timelineIndex})">
      ${full ? songThumb(full) : `<div class="song-thumb song-thumb-letter">${esc((s.title||'?')[0])}</div>`}
      <div class="song-info">
        <div class="song-title">${manualBadge}${esc(s.title)}</div>
        <div class="song-artist">${esc(s.artist)}</div>
      </div>
      ${roleBadge}
      ${similarity}
      ${removeBtn}
      <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${s.id}, this)">&#8942;</div>
    </div>`;
  };

  const previous = timeline.items.filter(item => item.role === 'previous');
  const current = timeline.items.find(item => item.role === 'current');
  const upcoming = timeline.items.filter(item => item.role === 'upcoming');
  const visiblePrevious = timeline.explicit ? previous : previous.slice(-10);
  const hiddenPreviousCount = Math.max(0, previous.length - visiblePrevious.length);
  const prevTitle = timeline.explicit
    ? 'Earlier In This Order'
    : (hiddenPreviousCount > 0
        ? `Previously Played (last ${visiblePrevious.length} of ${previous.length})`
        : 'Previously Played');
  const currentHtml = current
    ? `<div class="timeline-section-title">Now Playing</div>${rowHtml(current)}`
    : '';
  const prevHtml = visiblePrevious.length > 0
    ? `<div class="timeline-section-title">${prevTitle}</div>${visiblePrevious.map(rowHtml).join('')}`
    : '';
  const nextHtml = upcoming.length > 0
    ? `<div class="timeline-section-title">Coming Up</div>${upcoming.map(rowHtml).join('')}`
    : '';

  list.innerHTML = prevHtml + currentHtml + nextHtml;
  if (_recsShouldFocusCurrent && activeTab === 'recs') _scheduleRecsFocusCurrent();
}

function renderHistory(history, historyPos) {
  const list = document.getElementById('history-list');
  if (!list) return;
  if (!history || history.length === 0) {
    list.innerHTML = '<div class="empty-state">No songs played yet</div>';
    return;
  }
  // Reverse: most recently played at top
  const reversed = [...history].reverse();
  const reversedPos = history.length - 1 - historyPos;
  const allSongsData = engine.getSongs();
  list.innerHTML = reversed.map((s, i) => {
    const full = allSongsData[s.id];
    return `
    <div class="history-item ${i === reversedPos ? 'current-pos' : ''}" onclick="window._app.playSong(${s.id}, 'manual_history_tab')">
      ${full ? songThumb(full) : `<div class="song-thumb song-thumb-letter">${esc((s.title||'?')[0])}</div>`}
      <div class="h-info"><div class="h-title">${esc(s.title)}</div><div class="h-artist">${esc(s.artist)}</div></div>
      <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${s.id}, this)">&#8942;</div>
    </div>`;
  }).join('');
}

// ===== LOAD AND PLAY =====

async function loadAndPlay(songInfo, seekToSec) {
  if (!songInfo) return;
  if (!_initRestoreComplete) _startupPlaybackTouched = true;
  const requestedSeek = Number.isFinite(seekToSec) && seekToSec > 0 ? seekToSec : 0;
  currentSong = songInfo.id;
  currentIsFav = songInfo.isFavorite || false;
  document.getElementById('npTitle').textContent = songInfo.title;
  document.getElementById('npArtist').textContent = songInfo.artist + ' \u00b7 ' + (songInfo.album || '');
  document.getElementById('nowPlaying').style.display = 'block';
  updateHeartIcon(currentIsFav);
  updateModeIndicator();
  syncFullPlayer();
  nativeAudioPos = requestedSeek;
  nativeAudioPlayedMs = 0;
  nativeAudioDurationMs = 0;
  nativePlaybackInstanceId = 0;
  if (!(requestedSeek > 0 && nativeAudioDur > 0)) {
    nativeAudioDur = 0;
  }
  updateProgressUI(nativeAudioPos, nativeAudioDur);

  // Build full native queue from engine state and push to native service
  if (songInfo.filePath) {
    const queueSnapshot = engine.getNativeQueueSnapshot();
    const items = queueSnapshot.items || [];
    const startIndex = Number.isInteger(queueSnapshot.startIndex) ? queueSnapshot.startIndex : 0;
    const loopModeMap = { off: 0, one: 1, all: 2 };
    _dbg('loadAndPlay: songId=' + songInfo.id + ' title="' + songInfo.title + '" items=' + items.length + ' startIndex=' + startIndex + ' seekTo=' + requestedSeek + ' embReady=' + engine.isEmbeddingsReady());
    try {
      if (items.length > 0) {
        await MusicBridge.setQueue({ items, startIndex, seekTo: requestedSeek });
        _dbg('loadAndPlay: setQueue OK');
      } else {
        await MusicBridge.playAudio({
          path: songInfo.filePath,
          title: songInfo.title,
          artist: songInfo.artist,
          seekTo: requestedSeek,
        });
        _dbg('loadAndPlay: playAudio OK (no queue)');
      }
      await MusicBridge.setLoopMode({ mode: loopModeMap[loopMode] || 2 });
      await _refreshNativePlaybackInstanceId();
      nativeAudioPlaying = true;
      nativeFileLoaded = true;
      updatePlayIcon(false);
    } catch (e) {
      console.error('Native play failed, retrying once:', e);
      try {
        await new Promise(r => setTimeout(r, 300));
        if (items.length > 0) {
          await MusicBridge.setQueue({ items, startIndex, seekTo: requestedSeek });
        } else {
          await MusicBridge.playAudio({
            path: songInfo.filePath,
            title: songInfo.title,
            artist: songInfo.artist,
            seekTo: requestedSeek,
          });
        }
        await MusicBridge.setLoopMode({ mode: loopModeMap[loopMode] || 2 });
        await _refreshNativePlaybackInstanceId();
        nativeAudioPlaying = true;
        nativeFileLoaded = true;
        updatePlayIcon(false);
      } catch (e2) {
        console.error('Native play retry also failed:', e2);
        showStatusToast('Playback failed. Please try again.', 2200);
      }
    }
  } else {
    console.error('No file path for', songInfo.filename);
  }

  nativeAudioPos = requestedSeek;
  persistPlaybackState(true);
  prefetchNext();
}

function prefetchNext() {
  const st = engine.getState();
  if (st.queue && st.queue.length > 0) {
    const next = st.queue[0];
    const allSongsData = engine.getSongs();
    const song = allSongsData[next.id];
    if (song && song.filePath) {
      nextSongInfo = {
        id: next.id,
        title: song.title,
        artist: song.artist,
        album: song.album,
        filename: song.filename,
        filePath: song.filePath,
        isFavorite: engine.isFavorite(next.id),
      };
      return;
    }
  }
  nextSongInfo = null;
}

async function syncUpcomingNativeQueue() {
  if (!nativeFileLoaded) return;
  const upcoming = engine.getUpcomingNativeItems();
  try {
    await MusicBridge.replaceUpcoming({ items: upcoming });
  } catch (e) {
    _dbg('replaceUpcoming err: ' + e.message);
  }
}

// ===== PLAYER CONTROLS =====

async function playSongUI(id, source) {
  if (!source) source = 'manual_' + activeTab + '_tab';
  _notePlaybackIntent();
  // If the tapped song is already the currently playing song, don't restart it.
  // Open the full player instead so the tap feels intentional.
  if (id === currentSong && nativeAudioPlaying) {
    if (!fullPlayerOpen) openFullPlayer();
    return;
  }
  const song = engine.getSongs()[id];
  logActivity({ category: 'ui', type: 'song_tapped', message: `Tapped "${song ? song.title : id}" from ${source}`, data: { songId: id, source, title: song ? song.title : '' , filename: song ? song.filename : '' }, tags: ['playback', 'ui'], important: true });
  _dbg('SONG-TAP: id=' + id + ' src=' + source);
  const frac = getListenFraction();
  const info = engine.play(id, frac, source);
  _dbg('SONG-TAP: engine.play → ' + (info ? info.title + ' path=' + !!info.filePath : 'NULL'));
  if (info) {
    await loadAndPlay(info);
    _dbg('SONG-TAP: loadAndPlay done');
    refreshStateUI();
  }
}

async function playFromQueueUI(id) {
  const frac = getListenFraction();
  const song = engine.getSongs()[id];
  logActivity({ category: 'ui', type: 'queue_song_tapped', message: `Tapped queued song "${song ? song.title : id}"`, data: { songId: id, prevFraction: frac, title: song ? song.title : '', filename: song ? song.filename : '' }, tags: ['queue'], important: true });
  const info = engine.playFromQueue(id, frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
  }
}

async function playTimelineIndexUI(index) {
  const st = engine.getState();
  const item = st.timeline && Array.isArray(st.timeline.items) ? st.timeline.items[index] : null;
  if (!item) return;
  if (item.id === currentSong && item.role === 'current' && nativeAudioPlaying) {
    if (!fullPlayerOpen) openFullPlayer();
    return;
  }
  const frac = getListenFraction();
  logActivity({
    category: 'ui',
    type: 'timeline_song_tapped',
    message: `Tapped "${item.title}" from Up Next timeline`,
    data: { songId: item.id, timelineIndex: index, role: item.role, prevFraction: frac },
    tags: ['queue', 'playback'],
    important: true,
  });
  const info = engine.playFromTimelineIndex(index, frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
  }
}

async function playFromAlbumUI(id, trackIds) {
  if (id === currentSong && nativeAudioPlaying) {
    if (!fullPlayerOpen) openFullPlayer();
    return;
  }
  const frac = getListenFraction();
  const song = engine.getSongs()[id];
  logActivity({ category: 'ui', type: 'album_song_tapped', message: `Tapped album song "${song ? song.title : id}"`, data: { songId: id, prevFraction: frac, trackCount: Array.isArray(trackIds) ? trackIds.length : 0, title: song ? song.title : '', filename: song ? song.filename : '' }, tags: ['album', 'playback'], important: true });
  const info = engine.playFromAlbum(id, trackIds, frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
  }
}

// Tap-from-section handler. Plays `songId` and replaces Up Next with the rest
// of the resolved section. If songId is omitted, starts from the section head.
async function playFromSectionUI(songId, sectionKey) {
  if (songId === currentSong && nativeAudioPlaying) {
    if (!fullPlayerOpen) openFullPlayer();
    return;
  }
  let ids = [];
  let label = '';
  if (sectionKey === 'forYou') {
    ids = (_cachedForYou || []).map(s => s.id);
    label = 'For You';
  } else if (sectionKey === 'similar') {
    ids = (_cachedSimilar || []).map(s => s.id);
    label = 'Most Similar';
  } else if (sectionKey === 'favorites') {
    ids = (_cachedFavorites || []).map(s => s.id);
    label = 'Favorites';
  } else if (sectionKey === 'viewAll') {
    ids = (_viewAllItems || []).map(s => s.id);
    label = getViewAllMeta(_currentViewAllType).title;
  } else if (typeof sectionKey === 'string' && sectionKey.startsWith('byp:')) {
    const i = parseInt(sectionKey.slice(4), 10);
    const sec = (_cachedBecauseYouPlayed || [])[i];
    if (sec) {
      ids = (sec.recommendations || []).map(s => s.id);
      label = `Because you played ${sec.sourceTitle}`;
    }
  } else if (typeof sectionKey === 'string' && sectionKey.startsWith('unexp:')) {
    const secs = _cachedUnexplored || [];
    const i = parseInt(sectionKey.slice(6), 10);
    const sec = secs[i];
    if (sec) {
      ids = (sec.songs || []).map(s => s.id);
      label = 'Unexplored Sounds';
    }
  }
  if (!ids || ids.length === 0) {
    showStatusToast('Nothing to play', 1500);
    return;
  }
  const startId = (songId != null && ids.includes(songId)) ? songId : ids[0];
  const frac = getListenFraction();
  const song = engine.getSongs()[startId];
  logActivity({ category: 'ui', type: 'section_song_tapped', message: `Tapped "${song ? song.title : startId}" from ${sectionKey}`, data: { songId: startId, sectionKey, prevFraction: frac, sectionSize: ids.length, title: song ? song.title : '', filename: song ? song.filename : '' }, tags: ['section', 'playback'], important: true });
  const info = engine.playFromSection(startId, ids, label, frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
  }
}

// "Play only" — plays the song without touching Up Next. Wired from the ⋮ menu.
async function playOnlyUI(songId) {
  const frac = getListenFraction();
  const song = engine.getSongs()[songId];
  logActivity({ category: 'ui', type: 'play_only_pressed', message: `Play only for "${song ? song.title : songId}"`, data: { songId, prevFraction: frac, title: song ? song.title : '', filename: song ? song.filename : '' }, tags: ['playback'], important: true });
  const info = engine.playOnly(songId, frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
    showStatusToast('Playing only this song (queue kept)', 1800);
  }
}

async function togglePauseUI() {
  try {
    _notePlaybackIntent();
    _dbg('PLAY-TAP: playing=' + nativeAudioPlaying + ' loaded=' + nativeFileLoaded + ' cur=' + currentSong + ' quick=' + !!_quickRestoreInfo + ' init=' + _initRestoreComplete);

    if (nativeAudioPlaying) {
      _dbg('PLAY: pausing');
      MusicBridge.pauseAudio();
    } else if (!nativeFileLoaded && currentSong != null) {
      const songs = engine.getSongs();
      const song = songs[currentSong];
      _dbg('PLAY: cold restore song=' + (song ? song.title : 'NULL') + ' path=' + (song ? song.filePath : 'NULL'));
      if (song && song.filePath) {
        const seekTo = nativeAudioPos || 0;
        await loadAndPlay({
          id: currentSong,
          title: song.title,
          artist: song.artist,
          album: song.album,
          filename: song.filename,
          filePath: song.filePath,
          isFavorite: engine.isFavorite(currentSong),
        }, seekTo);
        _dbg('PLAY: cold restore done');
      } else if (!_initRestoreComplete) {
        _dbg('PLAY: init not complete yet, waiting');
        showStatusToast('Loading...', 1000);
      } else {
        _dbg('PLAY: song has no filePath!');
      }
    } else if (!_initRestoreComplete && _quickRestoreInfo) {
      _pendingStartupResume = true;
      _dbg('PLAY: waiting for authoritative restore');
      showStatusToast('Restoring playback...', 1200);
    } else if (!nativeFileLoaded && currentSong == null) {
      _dbg('PLAY: no song and no quickRestore');
    } else {
      _dbg('PLAY: resuming');
      MusicBridge.resumeAudio();
    }
  } catch (e) {
    _dbg('PLAY ERROR: ' + e.message);
  }
}

async function nextUI(source) {
  if (!source) source = 'next_button';
  if (_shouldBlockRapidNav('next')) {
    _dbg('NEXT blocked duplicate tap');
    return;
  }
  const frac = getListenFraction();
  logActivity({ category: 'ui', type: 'next_pressed', message: `Next pressed (${source})`, data: { source, prevFraction: frac }, tags: ['playback'], important: true });
  if (source === 'next_button') showStatusToast('Dislike skip', 1200);
  if (nativeFileLoaded) {
    try {
      await MusicBridge.nextTrack({ action: 'user_next', prevFraction: frac == null ? -1 : frac });
      return;
    } catch (e) {
      _dbg('NEXT native fallback: ' + e.message);
    }
  }
  const info = engine.nextSong(frac, source);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
  }
}

async function prevUI(source) {
  if (_shouldBlockRapidNav('prev')) {
    _dbg('PREV blocked duplicate tap');
    return;
  }
  const frac = getListenFraction();
  logActivity({ category: 'ui', type: 'prev_pressed', message: `Previous pressed (${source || 'prev_button'})`, data: { source: source || 'prev_button', prevFraction: frac }, tags: ['playback'], important: true });
  if (nativeFileLoaded) {
    try {
      await MusicBridge.prevTrack({ prevFraction: frac == null ? -1 : frac });
      return;
    } catch (e) {
      _dbg('PREV native fallback: ' + e.message);
    }
  }
  if (nativeAudioPos > 3) {
    try { MusicBridge.seekAudio({ position: 0 }); } catch (e) { /* ignore */ }
    return;
  }
  const info = engine.prevSong(frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
  }
}

function seekUI(e) {
  if (e.target.id === 'seekThumb') return;
  const bar = document.getElementById('progressBar');
  const rect = bar.getBoundingClientRect();
  const dur = nativeAudioDur || 0;
  const clientX = e.touches ? e.touches[0].clientX : e.clientX;
  if (dur > 0) {
    const pos = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width)) * dur;
    try { MusicBridge.seekAudio({ position: pos }); } catch (e) { /* ignore */ }
    nativeAudioPos = pos;
    updateProgressUI(nativeAudioPos, nativeAudioDur);
    persistPlaybackState(true);
  }
}

function setupSeekDrag() {
  const bar = document.getElementById('progressBar');
  if (!bar) return;
  let dragging = false;

  function getSeekFraction(e) {
    const rect = bar.getBoundingClientRect();
    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
    return Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
  }

  function startDrag(e) {
    dragging = true;
    bar.classList.add('seeking');
    updateDragPosition(e);
  }

  function updateDragPosition(e) {
    if (!dragging) return;
    e.preventDefault();
    const frac = getSeekFraction(e);
    document.getElementById('progressFill').style.width = (frac * 100) + '%';
    document.getElementById('seekThumb').style.left = (frac * 100) + '%';
  }

  function endDrag(e) {
    if (!dragging) return;
    dragging = false;
    bar.classList.remove('seeking');
    const frac = getSeekFraction(e.changedTouches ? e.changedTouches[0] : e);
    const dur = nativeAudioDur || 0;
    if (dur > 0) {
      nativeAudioPos = frac * dur;
      try { MusicBridge.seekAudio({ position: nativeAudioPos }); } catch (ex) { /* ignore */ }
      updateProgressUI(nativeAudioPos, nativeAudioDur);
      persistPlaybackState(true);
    }
  }

  // Touch events
  bar.addEventListener('touchstart', (e) => { startDrag(e); }, { passive: false });
  bar.addEventListener('touchmove', (e) => { updateDragPosition(e); }, { passive: false });
  bar.addEventListener('touchend', (e) => { endDrag(e); });
  bar.addEventListener('touchcancel', () => { dragging = false; bar.classList.remove('seeking'); });

  // Mouse events (fallback)
  bar.addEventListener('mousedown', (e) => { startDrag(e); });
  document.addEventListener('mousemove', (e) => { updateDragPosition(e); });
  document.addEventListener('mouseup', (e) => { if (dragging) endDrag(e); });

  // Click on track (not thumb) to seek directly
  bar.addEventListener('click', (e) => { seekUI(e); });
}

// ===== FULL-SCREEN PLAYER =====

let fullPlayerOpen = false;

function getArtUrl(song, opts = {}) {
  const resolved = _resolveSongForArt(song);
  if (!resolved) return null;
  if (!resolved.artPath) {
    if (opts.prime !== false) _enqueueSongArt(resolved, { priority: !!opts.priority });
    return null;
  }
  try { return window.Capacitor.convertFileSrc('file://' + resolved.artPath); } catch (e) { return null; }
}

function openFullPlayer() {
  const fp = document.getElementById('fullPlayer');
  if (!fp) return;
  syncFullPlayer();
  fp.classList.add('open');
  fullPlayerOpen = true;
}

function closeFullPlayer() {
  const fp = document.getElementById('fullPlayer');
  if (!fp) return;
  fp.classList.remove('open');
  fullPlayerOpen = false;
}

function syncFullPlayer() {
  if (currentSong == null) return;
  const songs = engine.getSongs();
  const song = songs[currentSong];
  if (!song) return;
  const syncedSongId = song.id;

  document.getElementById('fpTitle').textContent = song.title;
  document.getElementById('fpArtist').textContent = song.artist + ' \u00b7 ' + (song.album || '');

  // Art
  const artUrl = getArtUrl(song, { prime: false });
  const imgEl = document.getElementById('fpArtImg');
  const placeholderEl = document.getElementById('fpArtPlaceholder');
  if (artUrl) {
    imgEl.src = artUrl;
    imgEl.style.display = 'block';
    placeholderEl.style.display = 'none';
  } else {
    imgEl.style.display = 'none';
    placeholderEl.style.display = 'block';
    placeholderEl.textContent = (song.title || '?')[0].toUpperCase();
    if (song.filePath) {
      MusicBridge.getAlbumArtUri({ path: song.filePath }).then((res) => {
        if (!res || !res.exists || !res.uri || currentSong !== syncedSongId) return;
        song.artPath = res.uri;
        syncFullPlayer();
      }).catch(() => {});
    }
  }

  // Mini player art thumbnail
  const npIcon = document.getElementById('npIcon');
  if (npIcon) {
    let thumb = npIcon.querySelector('img');
    if (artUrl) {
      if (!thumb) {
        thumb = document.createElement('img');
        npIcon.appendChild(thumb);
      }
      thumb.src = artUrl;
      thumb.style.display = 'block';
    } else if (thumb) {
      thumb.style.display = 'none';
    }
  }

  // Fav state
  const fpFav = document.getElementById('fpFavBtn');
  if (fpFav) {
    fpFav.classList.toggle('is-fav', currentIsFav);
    fpFav.textContent = currentIsFav ? '\u2665' : '\u2661';
  }

  // Sync loop/shuffle button states
  const fpLoop = document.getElementById('fpLoopBtn');
  if (fpLoop) {
    fpLoop.classList.toggle('active-mode', loopMode !== 'off');
    fpLoop.classList.toggle('loop-one', loopMode === 'one');
    fpLoop.classList.toggle('loop-all', loopMode === 'all');
  }
  const fpShuffle = document.getElementById('fpShuffleBtn');
  if (fpShuffle) fpShuffle.classList.toggle('active-mode', shuffleOn);
}

function updateFullPlayerProgress(pos, dur) {
  if (!fullPlayerOpen) return;
  const pct = dur > 0 ? (pos / dur * 100) : 0;
  const fill = document.getElementById('fpProgressFill');
  const thumb = document.getElementById('fpSeekThumb');
  if (fill) fill.style.width = pct + '%';
  if (thumb) thumb.style.left = pct + '%';
  const timeEl = document.getElementById('fpTime');
  const durEl = document.getElementById('fpDuration');
  if (timeEl) timeEl.textContent = formatTime(pos);
  if (durEl) durEl.textContent = formatTime(dur);
}

function updateFullPlayerPlayIcon(paused) {
  const icon = document.getElementById('fpPlayIcon');
  if (icon) {
    icon.innerHTML = paused
      ? '<path d="M8 5v14l11-7z"/>'
      : '<path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>';
  }
}

// Swipe gesture for full player
function setupFullPlayerGestures() {
  const fp = document.getElementById('fullPlayer');
  const handle = document.getElementById('fpHandle');
  const npCard = document.getElementById('nowPlaying');
  if (!fp || !handle) return;

  // Swipe down on handle OR album art to close
  let startY = 0, currentY = 0, isDragging = false;
  const swipeTargets = [handle, document.getElementById('fpArt')].filter(Boolean);

  for (const target of swipeTargets) {
    target.addEventListener('touchstart', (e) => {
      startY = e.touches[0].clientY;
      isDragging = true;
      fp.classList.add('dragging');
    }, { passive: true });

    target.addEventListener('touchmove', (e) => {
      if (!isDragging) return;
      currentY = e.touches[0].clientY;
      const dy = Math.max(0, currentY - startY);
      fp.style.transform = `translateY(${dy}px)`;
    }, { passive: true });

    target.addEventListener('touchend', () => {
      if (!isDragging) return;
      isDragging = false;
      fp.classList.remove('dragging');
      fp.style.transform = '';
      const dy = currentY - startY;
      if (dy > 80) {
        closeFullPlayer();
      }
    });
  }

  // Swipe up OR tap on mini player to open
  if (npCard) {
    let npStartY = 0, npStartX = 0, npDragging = false;
    npCard.addEventListener('touchstart', (e) => {
      npStartY = e.touches[0].clientY;
      npStartX = e.touches[0].clientX;
      npDragging = true;
    }, { passive: true });

    npCard.addEventListener('touchend', (e) => {
      if (!npDragging) return;
      npDragging = false;
      const dy = e.changedTouches[0].clientY - npStartY;
      const dx = e.changedTouches[0].clientX - npStartX;
      if (dy < -40) {
        openFullPlayer();
        return;
      }
      // Tap (small movement) on song info area opens full player.
      // Excludes: any button, the progress bar, control icons.
      if (Math.abs(dy) < 10 && Math.abs(dx) < 10) {
        const t = e.target;
        if (t && t.closest && !t.closest('button') && !t.closest('#progressBar') && !t.closest('svg') && !t.closest('.np-btn')) {
          openFullPlayer();
        }
      }
    });
  }

  // Full player seek drag
  const fpBar = document.getElementById('fpProgressBar');
  if (fpBar) {
    let seeking = false;
    function getFpFrac(e) {
      const rect = fpBar.getBoundingClientRect();
      const x = e.touches ? e.touches[0].clientX : e.clientX;
      return Math.max(0, Math.min(1, (x - rect.left) / rect.width));
    }
    fpBar.addEventListener('touchstart', (e) => { seeking = true; e.preventDefault(); }, { passive: false });
    fpBar.addEventListener('touchmove', (e) => {
      if (!seeking) return;
      e.preventDefault();
      const frac = getFpFrac(e);
      document.getElementById('fpProgressFill').style.width = (frac * 100) + '%';
      document.getElementById('fpSeekThumb').style.left = (frac * 100) + '%';
    }, { passive: false });
    fpBar.addEventListener('touchend', (e) => {
      if (!seeking) return;
      seeking = false;
      const frac = getFpFrac(e.changedTouches[0]);
      if (nativeAudioDur > 0) {
        nativeAudioPos = frac * nativeAudioDur;
        try { MusicBridge.seekAudio({ position: nativeAudioPos }); } catch (ex) { /* ignore */ }
        updateProgressUI(nativeAudioPos, nativeAudioDur);
        persistPlaybackState(true);
      }
    });
    fpBar.addEventListener('click', (e) => {
      const frac = getFpFrac(e);
      if (nativeAudioDur > 0) {
        nativeAudioPos = frac * nativeAudioDur;
        try { MusicBridge.seekAudio({ position: nativeAudioPos }); } catch (ex) { /* ignore */ }
        updateProgressUI(nativeAudioPos, nativeAudioDur);
        persistPlaybackState(true);
      }
    });
  }
}

async function neutralSkipUI() {
  const frac = getListenFraction();
  logActivity({ category: 'ui', type: 'neutral_skip_pressed', message: 'Neutral skip pressed', data: { prevFraction: frac }, tags: ['playback'], important: true });
  if (nativeFileLoaded) {
    try {
      await MusicBridge.nextTrack({ action: 'neutral_skip', prevFraction: frac == null ? -1 : frac });
      return;
    } catch (e) {
      _dbg('NEUTRAL native fallback: ' + e.message);
    }
  }
  const info = engine.neutralSkip(frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
  }
}

async function refreshRecsUI() {
  logActivity({ category: 'ui', type: 'refresh_pressed', message: 'Refresh recommendations pressed', tags: ['queue', 'recommendation'], important: true });
  engine.refreshQueue();
  const upcoming = engine.getUpcomingNativeItems();
  if (upcoming.length > 0) {
    try { await MusicBridge.replaceUpcoming({ items: upcoming }); } catch (e) { _dbg('replaceUpcoming err: ' + e.message); }
  }
  refreshStateUI();
  _scheduleRecsFocusCurrent();
  showStatusToast('Recommendations refreshed', 1500);
}

function showInsightsUI() {
  const panel = document.getElementById('insights-panel');
  if (!panel) return;

  // Toggle visibility
  if (panel.style.display !== 'none') {
    panel.style.display = 'none';
    return;
  }

  const ins = engine.getInsights();

  let html = '<div class="insights-content">';

  // === NOW PLAYING — current song + live fraction + blend ===
  if (ins.currentSong) {
    html += '<div class="ins-section">';
    html += `<div class="ins-title">Now Playing</div>`;
    html += `<div class="ins-now-playing">`;
    html += `<div class="ins-np-name">${esc(ins.currentSong.title)} <span class="ins-dim">${esc(ins.currentSong.artist)}</span></div>`;
    if (ins.currentSong.liveFraction != null) {
      const livePct = Math.round(ins.currentSong.liveFraction * 100);
      const status = livePct > 50 ? 'ins-good' : 'ins-skip';
      html += `<div class="ins-np-frac"><span class="${status}">${livePct}% listened</span>`;
      html += livePct > 50 ? ' — counts as qualified' : ' — will count as skip';
      html += `</div>`;
    }
    html += `</div>`;
    // Blend weights inline with explanation
    html += `<div class="ins-list-title">What's deciding your next songs?</div>`;
    html += '<div class="ins-bars">';
    html += `<div class="ins-bar-row"><span class="ins-bar-label">This song</span><div class="ins-bar"><div class="ins-bar-fill ins-bar-current" style="width:${ins.blend.currentSong}"></div></div><span class="ins-bar-val">${ins.blend.currentSong}</span></div>`;
    html += `<div class="ins-bar-row"><span class="ins-bar-label">Session</span><div class="ins-bar"><div class="ins-bar-fill ins-bar-session" style="width:${ins.blend.session}"></div></div><span class="ins-bar-val">${ins.blend.session}</span></div>`;
    html += `<div class="ins-bar-row"><span class="ins-bar-label">All-time</span><div class="ins-bar"><div class="ins-bar-fill ins-bar-profile" style="width:${ins.blend.profile}"></div></div><span class="ins-bar-val">${ins.blend.profile}</span></div>`;
    html += '</div>';
    html += `<div class="ins-hint">This song = similarity to what's playing. Session = your recent listening pattern. All-time = your long-term taste from favorites + history. As you listen more, session weight grows.</div>`;
    html += '</div>';
  }

  // === TASTE SIGNAL — what the engine knows about this listening run ===
  html += '<div class="ins-section"><div class="ins-title">Taste Signal</div>';
  html += `<div class="ins-stat-grid">`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.session.totalListened}</div><div class="ins-stat-key">Tracked</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val ins-good">${ins.session.qualifiedForRec}</div><div class="ins-stat-key">Signal (+)</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val ins-skip">${ins.session.skippedUsedAsNegative}</div><div class="ins-stat-key">Signal (-)</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.profile.favoritesCount}</div><div class="ins-stat-key">Favs</div></div>`;
  html += `</div>`;
  html += `<div class="ins-hint">Persists across app restarts. Resets only on Engine Reset.</div>`;

  // Listen log with live status
  if (ins.session.listenedSongs.length > 0) {
    const maxShow = 50;
    const shown = ins.session.listenedSongs.slice(0, maxShow);
    const hidden = ins.session.listenedSongs.length - shown.length;
    html += `<div class="ins-list-title">Songs the engine has seen${hidden > 0 ? ` (showing last ${maxShow} of ${ins.session.listenedSongs.length})` : ''}</div>`;
    for (const s of shown) {
      const statusClass = s.isLive ? 'ins-live' : (s.status === 'qualified' ? 'ins-good' : 'ins-skip');
      const label = s.isLive ? 'NOW' : s.fraction;
      const enc = s.encounters > 1 ? ` <span class="ins-enc">${s.plays} liked / ${s.skips} skipped (${s.encounters}x)</span>` : '';
      html += `<div class="ins-listen-item"><span class="${statusClass}">${esc(label)}</span> <span class="ins-weight">${esc(s.weight)}</span> ${esc(s.title)}${enc}</div>`;
    }
  }

  // Session decay weights — with clear explanation
  if (ins.sessionDecay) {
    html += `<div class="ins-list-title">How much each song steers your recs</div>`;
    html += `<div class="ins-hint" style="margin-top:0;margin-bottom:6px;">Songs you listened to longer have more influence. Recent songs count more than older ones. The bar shows each song's pull on what gets recommended next.</div>`;
    for (const s of ins.sessionDecay.songs) {
      const combined = parseInt(s.combined);
      html += `<div class="ins-decay-item">`;
      html += `<div class="ins-decay-bar" style="width:${Math.min(combined, 100)}%"></div>`;
      html += `<span class="ins-decay-text">${esc(s.title)}</span>`;
      html += `<span class="ins-decay-vals">${esc(s.combined)}</span>`;
      html += `</div>`;
    }
    html += `<div class="ins-hint">= recency (${ins.sessionDecay.decayFactor} decay per song) × listen depth²</div>`;
  }
  html += '</div>';

  // === UP NEXT INTELLIGENCE — replaces old "Queue" section ===
  html += '<div class="ins-section"><div class="ins-title">Up Next Intelligence</div>';
  html += `<div class="ins-stat-grid">`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.queue.size}</div><div class="ins-stat-key">Queued</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${esc(ins.queue.mode)}</div><div class="ins-stat-key">Mode</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.historyLength}</div><div class="ins-stat-key">Played</div></div>`;
  if (ins.queueDiversity) {
    html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.queueDiversity.ratio}</div><div class="ins-stat-key">Diversity</div></div>`;
  }
  html += `</div>`;
  if (ins.queue.size > 0 && ins.queue.mode === 'AI') {
    html += `<div class="ins-zones">`;
    if (ins.queue.frozenZone > 0) html += `<div class="ins-zone ins-zone-frozen" style="flex:${ins.queue.frozenZone}"><span>${ins.queue.frozenZone}</span>Frozen</div>`;
    if (ins.queue.stableZone > 0) html += `<div class="ins-zone ins-zone-stable" style="flex:${ins.queue.stableZone}"><span>${ins.queue.stableZone}</span>Stable</div>`;
    if (ins.queue.fluidZone > 0) html += `<div class="ins-zone ins-zone-fluid" style="flex:${ins.queue.fluidZone}"><span>${ins.queue.fluidZone}</span>Fluid</div>`;
    html += `</div>`;
    html += `<div class="ins-hint">Frozen: untouched. Stable: reordered by evolving taste. Fluid: fresh songs enter here.</div>`;
  }
  html += `<div class="ins-stat ins-label">${esc(ins.queue.label)}</div>`;
  html += '</div>';

  // === LIBRARY ===
  html += '<div class="ins-section"><div class="ins-title">Library</div>';
  html += `<div class="ins-stat-grid">`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.embeddingCoverage.embedded}</div><div class="ins-stat-key">AI Songs</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.embeddingCoverage.total}</div><div class="ins-stat-key">Total</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.embeddingCoverage.percentage}</div><div class="ins-stat-key">Coverage</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.profile.exists ? 'Active' : 'None'}</div><div class="ins-stat-key">Profile</div></div>`;
  html += `</div>`;
  if (ins.onDeviceEmbedding) {
    const ode = ins.onDeviceEmbedding;
    if (ode.inProgress) {
      html += `<div class="ins-hint" style="color:var(--accent);">Embedding in progress... ${ode.queueSize} songs remaining</div>`;
    }
    if (ode.localCount > 0) {
      html += `<div class="ins-hint">${ode.localCount} songs embedded on-device</div>`;
    }
    if (ode.unembedded > 0 && !ode.inProgress) {
      html += `<div class="ins-hint">${ode.unembedded} songs awaiting embedding</div>`;
    }
  }
  html += `</div>`;

  html += '</div>';
  panel.innerHTML = html;
  panel.style.display = 'block';
}

async function surpriseUI() {
  const frac = getListenFraction();
  const info = await engine.doSurprise(frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
    document.querySelector('[data-tab="recs"]').classList.add('active');
    document.getElementById('panel-recs').classList.add('active');
    activeTab = 'recs';
    document.getElementById('searchBar').style.display = 'none';
  }
}

async function shutdownUI() {
  if (confirm('Stop player and exit?')) {
    await _persistPendingListenEvidence('shutdown');
    engine.shutdown();
    try { MusicBridge.stopPlaybackService(); } catch (e) { /* ignore */ }
    document.body.innerHTML = `<div style="display:flex;height:100vh;align-items:center;justify-content:center;color:#555;font-family:sans-serif;">Player stopped.</div>`;
  }
}

function resetUI() {
  closeResetConfirmOverlay();
  const overlay = document.createElement('div');
  overlay.id = 'resetConfirmOverlay';
  overlay.className = 'sd-overlay';
  overlay.innerHTML = `
    <div class="sd-modal reset-confirm-modal">
      <div class="sd-title">Reset Engine?</div>
      <div class="reset-confirm-body">
        <div class="reset-confirm-block">
          <div class="reset-confirm-head reset-confirm-head-clear">Clears</div>
          <div class="reset-confirm-text">Up Next, playback history, Taste profile, favorites, dislikes, X-score, session logs, and saved playback state.</div>
        </div>
        <div class="reset-confirm-block">
          <div class="reset-confirm-head reset-confirm-head-keep">Keeps</div>
          <div class="reset-confirm-text">Song files, embeddings, playlists, and Common Logs / activity logs.</div>
        </div>
        <div class="reset-confirm-note">Playback stops and the app reloads after reset.</div>
      </div>
      <div class="reset-confirm-actions">
        <button class="reset-confirm-cancel" onclick="window._app.closeResetConfirm()">Cancel</button>
        <button class="reset-confirm-confirm" onclick="window._app.confirmResetEngine()">Reset</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) closeResetConfirmOverlay();
  });
}

function closeResetConfirmOverlay() {
  const overlay = document.getElementById('resetConfirmOverlay');
  if (overlay) overlay.remove();
}

async function confirmResetEngineUI() {
  closeResetConfirmOverlay();
  try { MusicBridge.stopPlaybackService(); } catch (e) { /* ignore */ }
  await engine.resetEngine();
  window.location.reload();
}

// ===== STATE REFRESH =====

function refreshStateUI() {
  const data = engine.getState();
  if (data.current) {
    currentSong = data.current.id;
    currentIsFav = !!data.current.isFavorite;
    document.getElementById('npTitle').textContent = data.current.title;
    document.getElementById('npArtist').textContent = data.current.artist + ' \u00b7 ' + (data.current.album || '');
    document.getElementById('nowPlaying').style.display = 'block';
    updateHeartIcon(currentIsFav);
    syncFullPlayer();
    // Real engine state now holds the current song — quickDisplay shim no longer needed.
    _miniPlayerFromQuickDisplay = false;
  } else if (!nativeAudioPlaying && !_miniPlayerFromQuickDisplay) {
    currentSong = null;
    currentIsFav = false;
    document.getElementById('nowPlaying').style.display = 'none';
    updateHeartIcon(false);
  } else if (!nativeAudioPlaying && _miniPlayerFromQuickDisplay) {
    _dbg('refreshStateUI: keeping mini player visible from quickDisplay (engine.current=null, native paused)');
  }
  renderRecs(data);
  renderHistory(data.history, data.historyPos);

  updateModeIndicator();
  prefetchNext();

  // Update "Most Similar" section live (it depends on current song)
  if (engine.isEmbeddingsReady()) renderSimilar();
  refreshVisibleDiscoverCardState();

  // Debounced save of discover cache — captures current queue, history, discover state
  _saveVisibleDiscoverCacheDebounced();
}

function formatTime(s) {
  if (!s || isNaN(s)) return '0:00';
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return m + ':' + (sec < 10 ? '0' : '') + sec;
}

function esc(str) {
  const d = document.createElement('div');
  d.textContent = str || '';
  return d.innerHTML;
}

function closePlaylistPicker() {
  const overlay = document.getElementById('playlistPickerOverlay');
  if (overlay) overlay.remove();
}

function showPlaylistPicker(songId = null) {
  closePlaylistPicker();

  const playlists = engine.getPlaylists();
  const song = songId != null ? engine.getSongs()[songId] : null;
  const title = song ? 'Add to Playlist' : 'Create Playlist';
  const subtitle = song ? `<div class="playlist-modal-sub">${esc(song.title)} \u2022 ${esc(song.artist || '')}</div>` : '';

  const existingSection = song
    ? (playlists.length > 0
      ? `<div class="playlist-target-list">
          ${playlists.map(pl => {
            const already = engine.isSongInPlaylist(pl.id, songId);
            return `<button class="playlist-target-btn${already ? ' is-added' : ''}" ${already ? 'disabled' : ''} onclick="window._app.addSongToPlaylist('${pl.id}', ${songId})">
              <span class="playlist-target-name">${esc(pl.name)}</span>
              <span class="playlist-target-meta">${already ? 'Added' : `${pl.count} songs`}</span>
            </button>`;
          }).join('')}
        </div>`
      : '<div class="playlist-empty-inline">No playlists yet. Create one below.</div>')
    : (playlists.length > 0
      ? `<div class="playlist-existing-list">
          ${playlists.map(pl => `<button class="playlist-existing-chip" onclick="window._app.openPlaylist('${pl.id}'); window._app.closePlaylistPicker()">${esc(pl.name)}</button>`).join('')}
        </div>`
      : '<div class="playlist-empty-inline">No playlists yet. Create your first one below.</div>');

  const overlay = document.createElement('div');
  overlay.id = 'playlistPickerOverlay';
  overlay.className = 'sd-overlay';
  overlay.innerHTML = `
    <div class="sd-modal playlist-modal" onclick="event.stopPropagation()">
      <div class="sd-title">${title}</div>
      ${subtitle}
      ${song ? '<div class="playlist-modal-section-title">Available playlists</div>' : '<div class="playlist-modal-section-title">Existing playlists</div>'}
      ${existingSection}
      <div class="playlist-modal-section-title">${song ? 'Create new playlist' : 'New playlist'}</div>
      <div class="playlist-create-row">
        <input id="playlistNameInput" class="playlist-name-input" type="text" maxlength="60" placeholder="Playlist name">
        <button class="playlist-create-btn" onclick="window._app.createPlaylistFromModal(${songId != null ? songId : 'null'})">Create</button>
      </div>
      <button class="sd-close" onclick="window._app.closePlaylistPicker()">Close</button>
    </div>`;
  overlay.addEventListener('click', closePlaylistPicker);
  document.body.appendChild(overlay);

  const input = document.getElementById('playlistNameInput');
  if (input) {
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        createPlaylistFromModal(songId);
      }
    });
    setTimeout(() => input.focus(), 40);
  }
}

function createPlaylistFromModal(initialSongId = null) {
  const input = document.getElementById('playlistNameInput');
  const name = input ? input.value.trim() : '';
  const res = engine.createPlaylist(name, initialSongId != null ? Number(initialSongId) : null);
  if (!res || !res.ok) {
    showStatusToast(res && res.error ? res.error : 'Could not create playlist', 2000);
    return;
  }
  closePlaylistPicker();
  refreshPlaylistViews();
  if (initialSongId != null) {
    showStatusToast(`Added to new playlist "${res.playlist.name}"`, 2000);
  } else {
    showStatusToast(`Created playlist "${res.playlist.name}"`, 2000);
    openPlaylistUI(res.playlist.id);
  }
}

function addSongToPlaylistUI(playlistId, songId) {
  const res = engine.addSongToPlaylist(playlistId, songId);
  if (!res || !res.ok) {
    showStatusToast(res && res.error ? res.error : 'Could not add to playlist', 2000);
    return;
  }
  closePlaylistPicker();
  refreshPlaylistViews();
  showStatusToast(res.alreadyExists ? 'Song already in playlist' : 'Added to playlist', 1800);
}

function removeSongFromPlaylistUI(songId, playlistId = _currentPlaylistViewId) {
  if (!playlistId) return;
  const res = engine.removeSongFromPlaylist(playlistId, songId);
  if (!res || !res.ok) {
    showStatusToast(res && res.error ? res.error : 'Could not remove from playlist', 2000);
    return;
  }
  refreshPlaylistViews();
  showStatusToast(res.removed ? 'Removed from playlist' : 'Song not in playlist', 1800);
}

function renamePlaylistUI(playlistId) {
  const meta = engine.getPlaylistMeta(playlistId);
  if (!meta) return;
  const nextName = prompt('Rename playlist', meta.name);
  if (nextName == null) return;
  const res = engine.renamePlaylist(playlistId, nextName);
  if (!res || !res.ok) {
    showStatusToast(res && res.error ? res.error : 'Could not rename playlist', 2000);
    return;
  }
  refreshPlaylistViews();
  showStatusToast('Playlist renamed', 1800);
}

function deletePlaylistUI(playlistId) {
  const meta = engine.getPlaylistMeta(playlistId);
  if (!meta) return;
  const ok = confirm(`Delete playlist "${meta.name}"?\n\nSongs will remain in your library.`);
  if (!ok) return;
  const res = engine.deletePlaylist(playlistId);
  if (!res || !res.ok) {
    showStatusToast(res && res.error ? res.error : 'Could not delete playlist', 2000);
    return;
  }
  if (_currentPlaylistViewId === playlistId) {
    closeViewAll();
  } else {
    refreshPlaylistViews();
  }
  showStatusToast('Playlist deleted', 1800);
}

// ===== 3-DOT SONG MENU =====

let _activeMenu = null;
let _activeMenuSongId = null;
let _activeMenuScrollTarget = null;
let _activeMenuScrollHandler = null;

function _closeSongMenu() {
  if (_activeMenu) {
    _activeMenu.remove();
    _activeMenu = null;
  }
  _activeMenuSongId = null;
  document.removeEventListener('click', _closeSongMenu);
  if (_activeMenuScrollTarget && _activeMenuScrollHandler) {
    _activeMenuScrollTarget.removeEventListener('scroll', _activeMenuScrollHandler, true);
  }
  _activeMenuScrollTarget = null;
  _activeMenuScrollHandler = null;
}

function showSongMenu(songId, btnEl) {
  // Toggle: if menu already open for this exact song, just close it
  if (_activeMenu && _activeMenuSongId === songId) {
    _closeSongMenu();
    return;
  }
  _closeSongMenu();
  if (fullPlayerOpen) closeFullPlayer();
  const songs = engine.getSongs();
  const song = songs[songId];
  if (!song) return;

  const isFav = engine.getFavoritesList().some(f => f.id === songId);
  const favLabel = isFav ? 'Remove from Favorites' : 'Add to Favorites';
  const isDis = !!song.disliked;
  const dislikeLabel = isDis ? 'Remove Dislike' : 'Dislike';
  const hasEmb = !!song.hasEmbedding;
  const embLabel = hasEmb ? 'Remove Embedding' : 'Re-add Embedding';
  const currentPlaylistMeta = _currentPlaylistViewId ? engine.getPlaylistMeta(_currentPlaylistViewId) : null;
  const canRemoveFromCurrentPlaylist = !!(currentPlaylistMeta && engine.isSongInPlaylist(_currentPlaylistViewId, songId));

  const menu = document.createElement('div');
  menu.className = 'song-popup-menu';
  menu.innerHTML = `
    <div class="song-popup-item" data-action="playonly">Play only (keep queue)</div>
    <div class="song-popup-item" data-action="playnext">Play Next</div>
    <div class="song-popup-item" data-action="addtoplaylist">Add to Playlist</div>
    ${canRemoveFromCurrentPlaylist ? `<div class="song-popup-item" data-action="removefromplaylist">Remove from ${esc(currentPlaylistMeta.name)}</div>` : ''}
    <div class="song-popup-item" data-action="togglefav">${favLabel}</div>
    <div class="song-popup-item" data-action="toggledislike">${dislikeLabel}</div>
    <div class="song-popup-item" data-action="viewdetails">View Details</div>
    <div class="song-popup-item" data-action="viewalbum">View Album</div>
    <div class="song-popup-item" data-action="toggleemb">${embLabel}</div>
    <div class="song-popup-item song-popup-item-danger" data-action="deletesong">Delete Song</div>
  `;

  menu.addEventListener('click', (e) => {
    const action = e.target.dataset.action;
    if (!action) return;
    e.stopPropagation();
    _closeSongMenu();
    if (action === 'playonly') {
      playOnlyUI(songId);
    } else if (action === 'playnext') {
      logActivity({ category: 'ui', type: 'play_next_pressed', message: `Play Next pressed for "${song.title}"`, data: { songId }, tags: ['queue'], important: true });
      engine.playNext(songId);
      showStatusToast(`"${song.title}" plays next`, 2000);
      refreshStateUI();
      syncUpcomingNativeQueue();
    } else if (action === 'addtoplaylist') {
      showPlaylistPicker(songId);
    } else if (action === 'removefromplaylist') {
      removeSongFromPlaylistUI(songId, _currentPlaylistViewId);
    } else if (action === 'togglefav') {
      const r = engine.toggleFavorite(songId);
      logActivity({ category: 'ui', type: 'favorite_menu_toggled', message: `${isFav ? 'Removed from' : 'Added to'} favorites via menu`, data: { songId, isFavorite: !!(r && r.isFavorite) }, tags: ['favorite'], important: true });
      const msg = isFav ? 'Removed from favorites'
        : (r && r.unDisliked ? 'Added to favorites (removed dislike)' : 'Added to favorites');
      showStatusToast(msg, 1500);
      refreshStateUI();
      refreshBrowseCollectionsUI();
      if (_lastProfile) renderDiscoverTiles(_lastProfile);
    } else if (action === 'toggledislike') {
      const r = engine.toggleDislike(songId);
      logActivity({ category: 'ui', type: 'dislike_menu_toggled', message: `${isDis ? 'Removed dislike from' : 'Disliked'} "${song.title}" via menu`, data: { songId, isDisliked: !!(r && r.isDisliked) }, tags: ['dislike'], important: true });
      const msg = isDis ? 'Dislike removed'
        : (r && r.unFavorited ? 'Disliked (removed favorite)' : 'Disliked');
      showStatusToast(msg, 1500);
      refreshStateUI();
      refreshBrowseCollectionsUI();
      if (_lastProfile) renderDiscoverTiles(_lastProfile);
    } else if (action === 'viewdetails') {
      showSongDetailsModal(songId);
    } else if (action === 'viewalbum') {
      if (fullPlayerOpen) closeFullPlayer();
      viewAlbumForSong(songId);
    } else if (action === 'toggleemb') {
      logActivity({ category: 'ui', type: hasEmb ? 'embedding_remove_pressed' : 'embedding_readd_pressed', message: `${hasEmb ? 'Remove' : 'Re-add'} embedding for "${song.title}"`, data: { songId }, tags: ['embedding'], important: true });
      if (hasEmb) {
        engine.removeSongEmbedding(songId);
        showStatusToast('Embedding removed', 1500);
      } else {
        engine.readdSongEmbedding(songId);
        showStatusToast('Re-added to embedding queue', 1500);
      }
      if (document.querySelector('.emb-detail-page')) showEmbeddingDetail();
    } else if (action === 'deletesong') {
      confirmDeleteSong(songId);
    }
  });

  // Render offscreen so we can measure before positioning
  menu.style.visibility = 'hidden';
  menu.style.left = '0px';
  menu.style.top = '0px';
  menu.style.right = 'auto';
  document.body.appendChild(menu);
  _activeMenu = menu;
  _activeMenuSongId = songId;

  // Viewport-aware positioning: right-align menu to button, but clamp to screen.
  const rect = btnEl.getBoundingClientRect();
  const mW = menu.offsetWidth || 200;
  const mH = menu.offsetHeight || 240;
  const vW = window.innerWidth;
  const vH = window.innerHeight;
  const pad = 8;
  let left = rect.right - mW;
  if (left < pad) left = pad;
  if (left + mW > vW - pad) left = vW - mW - pad;
  let top = rect.bottom + 4;
  if (top + mH > vH - pad) {
    const flipped = rect.top - mH - 4;
    top = flipped >= pad ? flipped : Math.max(pad, vH - mH - pad);
  }
  menu.style.left = left + 'px';
  menu.style.top = top + 'px';
  menu.style.visibility = 'visible';

  // Close on next click anywhere
  setTimeout(() => document.addEventListener('click', _closeSongMenu), 0);

  // Close on scroll of any ancestor panel
  _activeMenuScrollTarget = document;
  _activeMenuScrollHandler = () => _closeSongMenu();
  document.addEventListener('scroll', _activeMenuScrollHandler, true);
}

async function confirmDeleteSong(songId) {
  const song = engine.getSongs()[songId];
  if (!song) return;
  const ok = confirm(`Delete "${song.title}" by ${song.artist}?\n\nThis permanently removes the file from your device. This cannot be undone.`);
  if (!ok) return;
  logActivity({ category: 'ui', type: 'delete_song_confirmed', message: `Confirmed delete for "${song.title}"`, data: { songId, wasCurrent: currentSong === songId }, tags: ['library'], important: true });

  // If the song being deleted is currently playing, skip forward first so
  // the player doesn't try to read a file that's about to disappear.
  if (currentSong === songId) {
    try {
      const nextInfo = engine.nextSong(getListenFraction(), 'delete_song');
      if (nextInfo) {
        await loadAndPlay(nextInfo);
        refreshStateUI();
      } else {
        try { MusicBridge.stopPlaybackService(); } catch (e) { /* ignore */ }
        nativeAudioPlaying = false;
        nativeFileLoaded = false;
        updatePlayIcon(true);
      }
    } catch (e) { /* ignore */ }
  }

  try {
    const result = await engine.deleteSong(songId);
    if (result && result.ok) {
      showStatusToast(`Deleted "${song.title}"`, 2000);
      refreshPlaylistViews();
      refreshStateUI();
      _pruneSongFromDiscoverCaches(songId);
      _rerenderCachedDiscoverViews();
      _saveVisibleDiscoverCache();
      // Refresh Songs / Albums panels if they're the visible tab.
      if (typeof renderSongs === 'function') try { renderSongs(); } catch (e) {}
      if (typeof renderAlbums === 'function') try { renderAlbums(); } catch (e) {}
    } else {
      showStatusToast(`Delete failed: ${result && result.error ? result.error : 'unknown error'}`, 3500);
    }
  } catch (e) {
    showStatusToast(`Delete failed: ${e && e.message ? e.message : e}`, 3500);
  }
}

async function showSongDetailsModal(songId) {
  const song = engine.getSongs()[songId];
  if (!song) return;
  const existing = document.getElementById('songDetailsOverlay');
  if (existing) existing.remove();

  let playCount = 0;
  let avgListen = null;
  let lastPlayed = null;
  try {
    const stats = await engine.getSongPlayStats(songId);
    if (stats) {
      playCount = stats.plays || 0;
      avgListen = stats.avgFrac;
      lastPlayed = stats.lastPlayedAt;
    }
  } catch (e) {}

  const avgStr = avgListen != null ? Math.round(avgListen * 100) + '%' : '—';
  const lastStr = lastPlayed ? new Date(lastPlayed).toLocaleDateString() : '—';

  const rows = [
    ['Title', song.title],
    ['Artist', song.artist],
    ['Album', song.album || '—'],
    ['Format', (song.filePath || '').split('.').pop().toUpperCase() || '—'],
    ['File path', song.filePath || '—'],
    ['Content hash', song.contentHash || '—'],
    ['Embedding', song.hasEmbedding ? 'Yes' : 'No'],
    ['Start count', playCount > 0 ? String(playCount) : '0'],
    ['Avg listen', avgStr],
    ['Last played', lastStr],
    ['Favorite', engine.getFavoritesList().some(f => f.id === songId) ? 'Yes' : 'No'],
  ];
  const rowHtml = rows.map(([k, v]) => `<div class="sd-row"><span class="sd-k">${esc(k)}</span><span class="sd-v">${esc(String(v))}</span></div>`).join('');

  const overlay = document.createElement('div');
  overlay.id = 'songDetailsOverlay';
  overlay.className = 'sd-overlay';
  overlay.innerHTML = `<div class="sd-modal" onclick="event.stopPropagation()">
    <div class="sd-title">Song Details</div>
    ${rowHtml}
    <button class="sd-close" onclick="document.getElementById('songDetailsOverlay').remove()">Close</button>
  </div>`;
  overlay.addEventListener('click', () => overlay.remove());
  document.body.appendChild(overlay);
}

function viewAlbumForSong(songId) {
  const songs = engine.getSongs();
  const song = songs[songId];
  if (!song) return;

  // Switch to albums tab
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.querySelector('.tab[data-tab="albums"]').classList.add('active');
  document.getElementById('panel-albums').classList.add('active');
  activeTab = 'albums';
  document.getElementById('searchBar').style.display = 'none';
  // Ensure albums are rendered
  if (_albumsDirty) { _albumsDirty = false; renderAlbums(allAlbums); }
  history.pushState({ depth: 1 }, '');

  // Find and expand the album
  setTimeout(() => {
    const albumPanel = document.getElementById('panel-albums');
    const headers = albumPanel.querySelectorAll('.album-header');
    for (const header of headers) {
      const nameEl = header.querySelector('.album-name');
      if (nameEl && nameEl.textContent === song.album) {
        const tracks = header.nextElementSibling;
        if (!tracks.classList.contains('expanded')) {
          tracks.classList.add('expanded');
          header.querySelector('.album-chevron').classList.add('expanded');
        }
        header.scrollIntoView({ behavior: 'smooth', block: 'start' });
        break;
      }
    }
  }, 100);
}

// Expose functions for onclick handlers in HTML
window._app = {
  playSong: playSongUI,
  playFromQueue: playFromQueueUI,
  playFromAlbum: playFromAlbumUI,
  playFromFavorites: playFromFavoritesUI,
  playFromPlaylist: playFromPlaylistUI,
  toggleAlbum: toggleAlbumUI,
  toggleFavorite: handleFavToggle,
  togglePause: togglePauseUI,
  prev: prevUI,
  next: nextUI,
  seek: seekUI,
  neutralSkip: neutralSkipUI,
  toggleShuffle: toggleShuffleUI,
  toggleLoop: toggleLoopUI,
  goToQueue: goToQueueUI,
  playTimelineIndex: playTimelineIndexUI,
  removeFromQueue: (idx) => {
    if (engine.removeFromQueue(idx)) {
      refreshStateUI();
      syncUpcomingNativeQueue();
    }
  },
  toggleRec: toggleRecUI,
  refreshRecs: refreshRecsUI,
  showInsights: showInsightsUI,
  surprise: surpriseUI,
  reset: resetUI,
  closeResetConfirm: closeResetConfirmOverlay,
  confirmResetEngine: confirmResetEngineUI,
  viewAll: viewAllUI,
  toggleSimilarFreeze: toggleSimilarFreezeUI,
  closeViewAll,
  openPlaylist: openPlaylistUI,
  showPlaylistCreator: () => showPlaylistPicker(null),
  showPlaylistPicker,
  closePlaylistPicker,
  createPlaylistFromModal,
  addSongToPlaylist: addSongToPlaylistUI,
  renamePlaylist: renamePlaylistUI,
  deletePlaylist: deletePlaylistUI,
  toggleSection: toggleSectionUI,
  clearSearch,
  showEmbeddingDetail,
  setEmbConfirm: _setEmbConfirm,
  retryEmbedding: () => { engine.retryEmbedding(); showEmbeddingDetail(); },
  embedNewPending: () => { engine.retryEmbedding(); showEmbeddingDetail(); },
  embedRemovedPending: () => {
    const n = engine.embedRemovedSongsBatch();
    showStatusToast(n > 0 ? `Queued ${n} songs for re-embedding` : 'No removed songs to re-add', 1800);
    showEmbeddingDetail();
  },
  confirmReembedAll: () => { engine.reembedAll(); setTimeout(showEmbeddingDetail, 500); },
  rescanLibrary: async () => {
    showStatusToast('Rescanning library...', 1500);
    try { await engine.startBackgroundScan({ force: true }); } catch (e) { /* ignore */ }
    showEmbeddingDetail();
  },
  stopEmbedding: () => { engine.stopEmbedding(); setTimeout(showEmbeddingDetail, 1000); },
  confirmRemoveOrphans: () => {
    _embDetailExpanded.orphanConfirm = false;
    const n = engine.removeOrphanedEmbeddings();
    console.log(`Removed ${n} orphaned embeddings`);
    showEmbeddingDetail();
  },
  confirmRemoveUnmatched: async () => {
    _embDetailExpanded.unmatchedConfirm = false;
    const n = await engine.removeUnmatchedEmbeddings();
    _embDetailExpanded.unmatched = false;
    showStatusToast(n > 0 ? `Removed ${n} unmatched embeddings` : 'No unmatched embeddings to remove', 1500);
    showEmbeddingDetail();
  },
  readdEmbedding: (id) => { engine.readdSongEmbedding(id); showEmbeddingDetail(); },
  showTasteWeights: showTasteWeightsOverlay,
  closeTasteWeights: closeTasteWeightsOverlay,
  resetTasteWeight: resetTasteWeightUI,
  playTasteSignal: playTasteSignalRowUI,
  setTasteSignalFilter: setTasteSignalFilterUI,
  setTasteSignalSort: setTasteSignalSortUI,
  tasteSignalMore: showMoreTasteSignalUI,
  toggleTastePlayback: () => {
    _tastePlaybackExpanded = !_tastePlaybackExpanded;
    showTasteWeightsOverlay();
  },
  toggleTasteLogs: () => {
    _tasteLogsExpanded = !_tasteLogsExpanded;
    showTasteWeightsOverlay();
  },
  toggleTasteEngine: () => {
    _tasteEngineExpanded = !_tasteEngineExpanded;
    showTasteWeightsOverlay();
  },
  tastePlaybackMore: () => {
    _tastePlaybackVisibleCount = Math.min(TASTE_PLAYBACK_MAX, _tastePlaybackVisibleCount + TASTE_PLAYBACK_PAGE_SIZE);
    showTasteWeightsOverlay();
  },
  tuningInfo: (key) => { _toggleTuningInfoPopup(key); },
  toggleTasteResetInfo: toggleTasteResetInfoUI,
  setTuning: async (key, val) => {
    await engine.setTuning({ [key]: Number(val) });
    showStatusToast('Tuning saved', 1200);
    if (document.querySelector('#panel-discover .taste-weights-page')) showTasteWeightsOverlay();
  },
  resetTuning: async (key) => {
    const defaults = { adventurous: 0.8, sessionBias: 0.5, negativeStrength: 0.5 };
    if (!(key in defaults)) return;
    await engine.setTuning({ [key]: defaults[key] });
    showStatusToast('Reset to default', 1200);
    showTasteWeightsOverlay();
  },
  playFromSection: (songId, sectionKey) => playFromSectionUI(songId, sectionKey),
  playOnly: (songId) => playOnlyUI(songId),
  embToggle: _embToggleSection,
  setEmbLogTab: _setEmbLogTab,
  embScrollPending: _embScrollToPending,
  copyEmbLogs: _copyEmbLogs,
  copyTasteLogs: copyTasteLogsUI,
  copyTastePlaybackSignals: copyTastePlaybackSignalsUI,
  handleArtError: handleArtErrorUI,
  showSongMenu,
};

// ===== BACK NAVIGATION =====
// Uses @capacitor/app backButton event for Android back gesture/button

function _switchToDiscover() {
  _activateTab('discover');
}

function _isOnSubPage() {
  if (activeTab === 'discover') {
    return !!document.querySelector('#panel-discover .emb-detail-page, #panel-discover .taste-weights-page, #panel-discover .viewall-header');
  }
  if (activeTab === 'browse') {
    return !!document.querySelector('#panel-browse .viewall-header');
  }
  return false;
}

function _closeActiveSubPage() {
  if (document.querySelector('#panel-discover .taste-weights-page')) {
    closeTasteWeightsOverlay();
    return true;
  }
  if (document.querySelector('#panel-discover .emb-detail-page')) {
    if (discoverContentBackup && discoverContentBackupPanelId) {
      closeViewAll();
    } else {
      const panel = document.getElementById('panel-discover');
      if (panel && !_flushQueuedDiscoverRefresh()) renderDiscoverSnapshotFromCache({ fade: false });
    }
    return true;
  }
  if (document.querySelector('#panel-discover .viewall-header, #panel-browse .viewall-header')) {
    closeViewAll();
    return true;
  }
  return false;
}

App.addListener('appStateChange', ({ isActive }) => {
  logActivity({
    category: 'app',
    type: isActive ? 'app_resumed' : 'app_backgrounded',
    message: isActive ? 'App resumed' : 'App backgrounded',
    important: false,
    tags: ['lifecycle'],
  });
  if (!isActive && currentSong != null) {
    persistPlaybackState(true);
    _persistPendingListenEvidence('app_inactive');
  }
});

App.addListener('backButton', () => {
  // Close song popup menu first (top-most transient)
  if (_activeMenu) {
    _closeSongMenu();
    return;
  }

  // Close Song Details modal if open (sd-overlay is a z-index 10001 modal)
  const sdOverlay = document.getElementById('songDetailsOverlay');
  if (sdOverlay) {
    sdOverlay.remove();
    return;
  }

  // Close any other ad-hoc modal overlays that use `.modal-overlay` / `.sd-overlay`
  const genericOverlay = document.querySelector('.sd-overlay, .modal-overlay');
  if (genericOverlay) {
    genericOverlay.remove();
    return;
  }

  // Close full-screen player if open
  if (fullPlayerOpen) {
    closeFullPlayer();
    return;
  }

  // Priority 1: close sub-page within discover
  if (_isOnSubPage() && (activeTab === 'discover' || activeTab === 'browse')) {
    if (_closeActiveSubPage()) return;
    return;
  }

  // Priority 2: return to discover from another tab
  if (activeTab !== 'discover') {
    _switchToDiscover();
    return;
  }

  // On discover main with no sub-page — minimize instead of killing
  // This keeps the WebView alive so reopening is instant
  App.minimizeApp();
});

init();
