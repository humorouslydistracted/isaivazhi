/**
 * Music engine — session state, favorites, profile, playlists.
 * Supports dynamic phone scan, embedding hot-reload, album play,
 * rec toggle, "Because you played" sections.
 */

import { Preferences } from '@capacitor/preferences';
import { Recommender, vecAdd, vecScale, vecNormalize, weightedAverage } from './recommender.js';
import { SessionLogger } from './logger.js';
import { MusicBridge } from './music-bridge.js';
import * as EmbeddingCache from './embedding-cache.js';
import { initActivityLog, logActivity, getRecentActivityEvents, getActivityLogStatus, flushActivityLog } from './activity-log.js';

import {
  TOP_N, FROZEN_ZONE, STABLE_ZONE, FAVORITE_PRIOR_BASE, DISLIKE_PRIOR_BASE,
  MANUAL_PRIOR_HALF_LIFE_PLAYS, PLAYLISTS_PREF_KEY, PENDING_LISTEN_KEY, TUNING_DEFAULTS,
  SIMILARITY_BOOST_KEY, SIMILARITY_BOOST_MAX, SIMILARITY_NEIGHBOR_COUNT, SIMILARITY_NEIGHBOR_WEIGHTS,
  RECOMMENDATION_REBUILD_DEBOUNCE_MS, RECOMMENDATION_NEGATIVE_HARD_EXCLUDE_SHARE,
  RECOMMENDATION_NEGATIVE_HARD_EXCLUDE_FLOOR, RECOMMENDATION_POSITIVE_BONUS_PER_POINT,
  RECOMMENDATION_POSITIVE_BONUS_MAX, RECOMMENDATION_NEGATIVE_PENALTY_PER_POINT,
  RECOMMENDATION_NEGATIVE_PENALTY_MAX, RECOMMENDATION_POOL_MULTIPLIER, RECOMMENDATION_POOL_PADDING,
  NEG_X_DELTA, NEG_LISTEN_DECAY, NEG_SCORE_MAX, USER_SKIP_NEGATIVE_STEP,
  TASTE_REVIEW_IGNORE_KEY, TASTE_REVIEW_REASON_META,
  EXTERNAL_DATA_DIR, setExternalDataDir,
  songs, setSongs, embeddings, setEmbeddings, embeddingMap, setEmbeddingMap,
  albumList, setAlbumList, albumArray, setAlbumArray,
  rec, setRec, log, setLog, favorites, setFavorites, playlists, setPlaylists,
  profileVec, setProfileVec, scanCallbacks, scanComplete, setScanComplete,
  recToggle, _setRecToggle, queueShuffleEnabled, _setQueueShuffleEnabled,
  _tuning, _setTuning, similarityBoostScores, setSimilarityBoostScores,
  _recommendationPolicySnapshot, setRecommendationPolicySnapshot, _recommendationStatusCbs,
  _recommendationRebuildTimer, setRecommendationRebuildTimer,
  _recommendationRebuildInFlight, setRecommendationRebuildInFlight,
  _recommendationRebuildPending, setRecommendationRebuildPending,
  _recommendationRebuildReason, setRecommendationRebuildReason,
  _recommendationRebuildOpts, setRecommendationRebuildOpts,
  negativeScores, setNegativeScores,
  _currentPlaybackInstanceId, setCurrentPlaybackInstanceId,
  _capturedPlaybackInstanceId, setCapturedPlaybackInstanceId,
  _lastLoggedPlaybackStartInstanceId, setLastLoggedPlaybackStartInstanceId,
  _lastLoggedPlaybackStartSongId, setLastLoggedPlaybackStartSongId,
  dislikedFilenames, setDislikedFilenames,
  _tasteReviewIgnores, setTasteReviewIgnores,
  _lastTasteReviewSnapshot, setLastTasteReviewSnapshot,
  _lastProfileWeightSnapshot, setLastProfileWeightSnapshot,
  MAX_RECENT_PLAYBACK_SIGNALS, PROFILE_DAY_MS, PROFILE_HALF_LIFE_DAYS,
  REVIEW_RESET_PENDING_WINDOW_MS, NEGATIVE_PLAY_THRESHOLD, LISTEN_SKIP_THRESHOLD,
  FULL_LISTEN_THRESHOLD, NEUTRAL_SKIP_CAPTURE_THRESHOLD, NEGATIVE_FRAC_THRESHOLD,
  _recentPlaybackSignalEvents, setRecentPlaybackSignalEvents,
  state,
} from './engine-state.js';
import {
  _getFilenameMap, _invalidateFilenameMap,
  _getPathMap, _invalidatePathMap,
  _fastEmbIdxToSongId, _songIdsToEmbExclude, _invalidateEmbIdxMap,
} from './engine-indexes.js';
import {
  _loadTuning, _saveTuning, getTuning,
  _loadNegativeScores, _saveNegativeScores,
  _loadSimilarityBoostScores, _saveSimilarityBoostScores,
  _loadDislikes, _saveDislikes,
  _loadTasteReviewIgnores, _saveTasteReviewIgnores,
  _applyDislikeFlags, _getSimilarityBoost, _setSimilarityBoostScore,
  _emitRecommendationStatus, onRecommendationRebuildStatus, _bumpNegativeScore,
  _songRef, _activity,
  _copySessionEntry, _snapshotSessionEntry, _formatSessionEntryDelta,
  _roundSignal, _signalWeightFromFraction,
  _summarizeSessionSignalEntry, _summarizeProfileSummaryEntry,
  _cloneProfileSummaryEntry, _previewProfileSummaryEntryEvent,
  _rememberLoggedPlaybackStart, bindCurrentPlaybackStartInstance, _ensurePlaybackStartLogged,
  _tasteDirectionFromScore, _normalizeTasteScoreSnapshot,
  _recencyMultiplierFromLastPlayed, _manualPriorWeight,
  _computeTasteContributions, _computeTasteScoreSnapshot,
  _getRecommendationPoolSize, _calculateRecommendationAdjust,
  _createSignalRow, _buildSignalRowsFromSummary,
  _decorateSignalRows, _buildRecommendationFingerprint, _updateRecommendationPolicySnapshot,
  _getRecommendationPolicyRow, _applyRecommendationPolicyToSongItems,
  _recResultsToSongItems, _nativeNearestResultsToSongItems, _embExcludeToFilepaths,
  _recommendFromQueryVec, _recommendFromSourceEmb, _getNearestNeighborSongIds,
  _applySimilarityBoostPropagation, _buildTasteReviewFingerprint,
  _rememberPlaybackSignalEvent, _normalizePlaybackSignalEvent,
  _makeProfileSnapshot, _logProfileWeightDeltas,
  _getSummaryListenStats, buildProfileVec, getSongPlayStats,
} from './engine-taste.js';
import {
  initEmbeddingCallbacks,
  librarySavedAt, setLibrarySavedAt, SCAN_SKIP_WINDOW_MS,
  _markEmbeddingsReady, isEmbeddingsReady, onEmbeddingsReady,
  _loadLocalEmbeddings, _saveSongLibrary, _mergeLocalEmbeddings,
  _identifyOrphans, _markSongsMissing, markSongMissingByPath,
  _scheduleDeferredDeletionCheck, onSongLibraryChanged,
  getOrphanedSongs, removeOrphanedEmbeddings,
  _loadRemovedEmbeddingKeys, _applyRemovedEmbeddingFlags,
  removeSongEmbedding, removeAlbumEmbeddings, readdSongEmbedding,
  getRemovedEmbeddingSongs, deleteSong, removeUnmatchedEmbeddings,
  _triggerAutoEmbedding, _setupEmbeddingListeners,
  reembedAll, stopEmbedding, retryEmbedding, embedRemovedSongsBatch,
  _runPostBatchMerge, getEmbeddingStatus,
  reloadEmbeddingsFromDisk, resyncEmbeddingState,
  _nativeRecommendFromQueryVec,
} from './engine-embeddings.js';
import {
  initDataCallbacks,
  setArtCacheDir, onLoadingStatus, onAlbumArtReady, _rebuildAlbums,
  onScanComplete, _triggerAlbumArtExtraction, _resolveSongIdFromNativePayload,
  loadData, startBackgroundScan, preloadEmbeddingsEarly, isEarlyEmbeddingLoaded,
  loadFavorites, saveFavorites,
  _normalizePlaylistName, _normalizePlaylist,
  _findPlaylistIndex, _getPlaylistById, _resolvePlaylistSongIds, _playlistSummary,
  loadPlaylists, savePlaylists,
} from './engine-data.js';
import {
  initAnalyticsCallbacks,
  _invalidateForYouCache, _invalidateUnexploredClustersCache, tickForYouListenWindow,
  scheduleRecommendationRebuild,
  analyzeProfile, getBecauseYouPlayed, getUnexploredClusters,
  loadRecentlyPlayed, getLastAddedSongs,
  saveDiscoverCache, saveDiscoverCacheDebounced, loadDiscoverCache, validateDiscoverCache,
} from './engine-analytics.js';
import {
  getProfileWeights, getTasteSignal,
  getSuspiciousRecommendationData, ignoreSuspiciousRecommendation,
  getRecentPlaybackSignalTimeline,
  rebuildProfileVec, resetSongRecommendationHistory, resetSongProfileWeight,
} from './engine-insights.js';
import {
  initPlaybackCallbacks, _filenameToId,
  savePlaybackState, savePendingListenSnapshot, loadPendingListenSnapshot, clearPendingListenSnapshot,
  getLastPlayedDisplay, restorePlaybackState, restorePlaybackStateCritical,
  resetEngine, clearPlaybackSession, shutdown,
  getNativeQueueSnapshot, getNativeQueueItems, getUpcomingNativeItems,
  syncCurrentFromNativeState, syncQueueFromNativeSnapshot, onNativeAdvance,
} from './engine-playback.js';
import {
  toggleFavorite, setFavoriteState, toggleDislike, isDisliked,
  getFavoritesList, getDislikedSongsList,
  getPlaylists, getPlaylistSongs, getPlaylistMeta, isSongInPlaylist,
  createPlaylist, renamePlaylist, deletePlaylist, addSongToPlaylist, removeSongFromPlaylist,
} from './engine-favorites.js';
import { setLiveListenFraction, getInsights } from './engine-session-ui.js';
async function setTuning(partial) {
  _setTuning({ ..._tuning, ...partial });
  // Clamp to [0,1]
  for (const k of Object.keys(_tuning)) _tuning[k] = Math.max(0, Math.min(1, _tuning[k]));
  await _saveTuning();
  // MMR semantics: score = lam * relevance - (1-lam) * redundancy. High lam =
  // pure relevance (clustered picks); low lam = diversity-biased. The UI exposes
  // this as the "adventurous" slider where the user expects high = more diverse.
  // Invert so the math matches the label.
  if (rec) rec.lam = 1 - _tuning.adventurous;
  // Negative β changed -> rebuild profile vector
  setProfileVec(await buildProfileVec('tuning_changed'));
  _invalidateForYouCache('tuning_changed');
  _invalidateUnexploredClustersCache('tuning_changed');
  const refreshQueueApplied = _refreshDynamicRecommendations('tuning_changed');
  _emitRecommendationStatus({
    phase: 'completed',
    reason: 'tuning_changed',
    version: _recommendationPolicySnapshot.version,
    refreshQueueApplied,
    refreshDiscoverSuggested: true,
  });
  return { ..._tuning };
}

function _cloneTimelineItem(item, fallback = {}) {
  if (!item || item.id == null) return null;
  return {
    id: item.id,
    similarity: item.similarity != null ? item.similarity : (fallback.similarity != null ? fallback.similarity : 0),
    manual: item.manual != null ? !!item.manual : !!fallback.manual,
  };
}

function _setExplicitTimeline(items, currentIndex) {
  const cleaned = [];
  let resolvedIndex = -1;
  for (let i = 0; i < (items || []).length; i++) {
    const entry = _cloneTimelineItem(items[i]);
    if (!entry) continue;
    if (entry.id < 0 || entry.id >= songs.length) continue;
    if (!songs[entry.id] || !songs[entry.id].filePath) continue;
    cleaned.push(entry);
    if (i === currentIndex) resolvedIndex = cleaned.length - 1;
  }
  state.timelineMode = (cleaned.length > 0 && resolvedIndex >= 0) ? 'explicit' : null;
  state.timelineItems = cleaned;
  state.timelineIndex = resolvedIndex;
  state.explicitPlayedIds = [];
}

function _clearTimelineContext() {
  state.timelineMode = null;
  state.timelineItems = [];
  state.timelineIndex = -1;
  state.explicitPlayedIds = [];
}

function _syncDynamicTimelineFromState() {
  if (state.timelineMode === 'explicit') return;
  state.explicitPlayedIds = [];

  const items = [];
  const pushItem = (id, extra = {}) => {
    if (id == null || id < 0 || id >= songs.length) return;
    if (!songs[id] || !songs[id].filePath) return;
    items.push({
      id,
      similarity: extra.similarity != null ? extra.similarity : 0,
      manual: !!extra.manual,
    });
  };

  const hist = Array.isArray(state.history) ? state.history : [];
  const histPos = Math.max(-1, Math.min(state.historyPos, hist.length - 1));
  const currentId = state.current;

  for (let i = 0; i < hist.length; i++) {
    if (i === histPos) continue;
    pushItem(hist[i]);
  }

  if (currentId != null) {
    const before = histPos >= 0 ? hist.slice(0, histPos) : hist.slice();
    const after = histPos >= 0 ? hist.slice(histPos + 1) : [];
    const rebuilt = [];
    for (const sid of before) {
      if (sid == null || sid < 0 || sid >= songs.length) continue;
      if (!songs[sid] || !songs[sid].filePath) continue;
      rebuilt.push({ id: sid, similarity: 0, manual: false });
    }
    const currentIndex = rebuilt.length;
    if (songs[currentId] && songs[currentId].filePath) {
      rebuilt.push({ id: currentId, similarity: 0, manual: false });
    }
    for (const sid of after) {
      if (sid == null || sid < 0 || sid >= songs.length) continue;
      if (!songs[sid] || !songs[sid].filePath) continue;
      rebuilt.push({ id: sid, similarity: 0, manual: false });
    }
    for (const q of state.queue) {
      const entry = _cloneTimelineItem(q);
      if (!entry) continue;
      if (entry.id < 0 || entry.id >= songs.length) continue;
      if (!songs[entry.id] || !songs[entry.id].filePath) continue;
      rebuilt.push(entry);
    }
    state.timelineMode = 'dynamic';
    state.timelineItems = rebuilt;
    state.timelineIndex = songs[currentId] && songs[currentId].filePath ? currentIndex : -1;
    return;
  }

  for (const q of state.queue) {
    const entry = _cloneTimelineItem(q);
    if (!entry) continue;
    if (entry.id < 0 || entry.id >= songs.length) continue;
    if (!songs[entry.id] || !songs[entry.id].filePath) continue;
    items.push(entry);
  }
  state.timelineMode = items.length > 0 ? 'dynamic' : null;
  state.timelineItems = items;
  state.timelineIndex = -1;
}

function _queueFromTimelineAfter(index) {
  const next = [];
  for (let i = index + 1; i < state.timelineItems.length; i++) {
    const entry = _cloneTimelineItem(state.timelineItems[i]);
    if (!entry) continue;
    next.push(entry);
  }
  state.queue = next;
}

function _setExplicitPlayedIds(ids) {
  const seen = new Set();
  state.explicitPlayedIds = [];
  for (const id of (ids || [])) {
    if (id == null || id < 0 || id >= songs.length) continue;
    if (seen.has(id)) continue;
    seen.add(id);
    state.explicitPlayedIds.push(id);
  }
}

function _markExplicitSongPlayed(songId) {
  if (state.timelineMode !== 'explicit' || songId == null) return;
  if (state.explicitPlayedIds.includes(songId)) return;
  state.explicitPlayedIds.push(songId);
}

function _markExplicitCurrentPlayed() {
  if (state.timelineMode !== 'explicit' || state.current == null) return;
  _markExplicitSongPlayed(state.current);
}

function _rebuildExplicitShuffledQueue() {
  if (state.timelineMode !== 'explicit' || state.current == null || !Array.isArray(state.timelineItems) || state.timelineItems.length === 0) return;
  let currentEntry = (state.timelineIndex >= 0 && state.timelineIndex < state.timelineItems.length)
    ? _cloneTimelineItem(state.timelineItems[state.timelineIndex])
    : null;
  if (!currentEntry || currentEntry.id !== state.current) {
    currentEntry = _cloneTimelineItem((state.timelineItems || []).find(item => item && item.id === state.current));
  }
  if (!currentEntry) return;

  const playedSet = new Set((state.explicitPlayedIds || []).filter(id => id != null && id !== currentEntry.id));
  const playedContext = [];
  const remaining = [];
  for (let i = 0; i < state.timelineItems.length; i++) {
    if (i === state.timelineIndex) continue;
    const entry = _cloneTimelineItem(state.timelineItems[i]);
    if (!entry) continue;
    if (playedSet.has(entry.id)) playedContext.push(entry);
    else remaining.push(entry);
  }

  state.queue = _shuffleQueueEntries(remaining);
  state.timelineItems = [
    ...playedContext,
    currentEntry,
    ...state.queue.map(item => _cloneTimelineItem(item)),
  ].filter(Boolean);
  state.timelineIndex = playedContext.length;
}

function _shuffleQueueEntries(entries) {
  const manualItems = [];
  const automaticItems = [];
  for (const entry of (entries || [])) {
    if (!entry) continue;
    if (entry.manual) manualItems.push(entry);
    else automaticItems.push(entry);
  }
  for (let i = automaticItems.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [automaticItems[i], automaticItems[j]] = [automaticItems[j], automaticItems[i]];
  }
  return [...manualItems, ...automaticItems];
}

function _applyQueueShuffleState() {
  if (!queueShuffleEnabled || state.queue.length <= 1) return;
  state.queue = _shuffleQueueEntries(state.queue);
}

function _recToQueue(recResults, opts = {}) {
  if (!recResults) return [];
  return _recResultsToSongItems(recResults, opts).map(item => ({
    id: item.id,
    similarity: item.policySimilarity != null ? item.policySimilarity : item.similarity,
  }));
}

// Build a map of embeddingIndex -> artist for artist bonus in recommendations
let _artistMap = null;
function _getArtistMap() {
  if (_artistMap && _artistMap.size > 0) return _artistMap;
  _artistMap = new Map();
  for (const s of songs) {
    if (s.embeddingIndex != null) {
      _artistMap.set(s.embeddingIndex, s.artist);
    }
  }
  return _artistMap;
}

function _getCurrentArtist() {
  if (state.current != null && state.current < songs.length) {
    return songs[state.current].artist;
  }
  return null;
}

function _isSkipClassification(fraction) {
  return fraction != null && fraction <= LISTEN_SKIP_THRESHOLD;
}

function _isStrongListen(fraction) {
  return fraction != null && fraction >= FULL_LISTEN_THRESHOLD;
}

function _shouldAccumulateUserSkipNegative(action) {
  const normalized = String(action || '').toLowerCase();
  return normalized === 'user_next'
    || normalized === 'next_button'
    || normalized === 'native_user_next';
}

function _computeUserSkipNegativeDelta(skipCount) {
  const count = Math.max(1, Number(skipCount) || 1);
  return Math.round(USER_SKIP_NEGATIVE_STEP * count * 100) / 100;
}

function _beginPlaybackInstance() {
  setCurrentPlaybackInstanceId(_currentPlaybackInstanceId + 1);
  setCapturedPlaybackInstanceId(null);
}

// --- Session State Methods ---

function _applyRecordedListen(songId, prevFraction, opts = {}) {
  if (songId == null || prevFraction == null) return false;
  if (opts.useCurrentPlaybackDedupe && _capturedPlaybackInstanceId === _currentPlaybackInstanceId) return false;
  const prevSong = songs[songId];
  if (!prevSong) return false;

  const beforeEntry = _snapshotSessionEntry(songId);
  const transitionAction = opts.transitionAction || opts.sourceFallback || null;
  const isSkip = _isSkipClassification(prevFraction);
  const eventType = isSkip ? 'skipped' : 'completed';
  let summaryPreview = null;
  if (Object.prototype.hasOwnProperty.call(opts, 'summaryEntryBefore')) {
    const summaryBefore = _cloneProfileSummaryEntry(opts.summaryEntryBefore);
    summaryPreview = {
      before: summaryBefore,
      after: _previewProfileSummaryEntryEvent(summaryBefore, eventType, prevFraction),
    };
  } else if (log && typeof log.previewProfileSummaryUpdate === 'function') {
    summaryPreview = log.previewProfileSummaryUpdate(prevSong.filename, eventType, prevFraction);
  }
  const negativeBefore = negativeScores[prevSong.filename] || 0;
  _activity('playback', 'listen_fraction_captured', `Captured ${Math.round(prevFraction * 100)}% listen`, {
    ..._songRef(songId),
    fraction: prevFraction,
    source: transitionAction,
  }, { important: true, tags: ['listen'] });
  _invalidateUnexploredClustersCache('listen_update');

  if (isSkip) {
    log.songSkipped(prevSong, prevFraction);
    _activity('playback', 'song_classified_skip', `Classified "${prevSong.title}" as skip`, {
      ..._songRef(songId),
      fraction: prevFraction,
      transitionAction,
    }, { important: true, tags: ['listen', 'skip'] });

    if (_shouldAccumulateUserSkipNegative(transitionAction)) {
      const skipCount = summaryPreview && summaryPreview.after ? summaryPreview.after.skips : 0;
      _bumpNegativeScore(songId, _computeUserSkipNegativeDelta(skipCount));
    }
  } else {
    log.songCompleted(prevSong, prevFraction);
    tickForYouListenWindow();
    _activity('playback', 'song_classified_complete', `Classified "${prevSong.title}" as completed`, {
      ..._songRef(songId),
      fraction: prevFraction,
      transitionAction,
    }, { important: true, tags: ['listen', 'complete'] });
    if (_isStrongListen(prevFraction)) _bumpNegativeScore(songId, -NEG_LISTEN_DECAY);
  }

  let afterEntry = null;
  for (const entry of state.listened) {
    if (entry.id === songId) {
      entry.encounters = (entry.encounters || 1) + 1;
      entry.totalFraction = (entry.totalFraction || entry.listen_fraction) + prevFraction;
      entry.skips = (entry.skips || (_isSkipClassification(entry.listen_fraction) ? 1 : 0)) + (isSkip ? 1 : 0);
      entry.plays = (entry.plays || (!_isSkipClassification(entry.listen_fraction) ? 1 : 0)) + (isSkip ? 0 : 1);
      const avgFrac = entry.totalFraction / entry.encounters;
      const skipPenalty = entry.skips > entry.plays ? 0.1 * (entry.skips - entry.plays) : 0;
      entry.listen_fraction = Math.max(0, Math.min(1, avgFrac - skipPenalty));
      entry.source = transitionAction;
      afterEntry = _snapshotSessionEntry(songId);
      break;
    }
  }

  if (!afterEntry) {
    state.listened.push({
      id: songId,
      listen_fraction: prevFraction,
      source: transitionAction,
      encounters: 1,
      totalFraction: prevFraction,
      skips: isSkip ? 1 : 0,
      plays: isSkip ? 0 : 1,
    });
    afterEntry = _snapshotSessionEntry(songId);
  }

  _activity('taste', 'session_weight_changed', `"${prevSong.title}" session signal updated`, {
    ..._songRef(songId),
    fraction: prevFraction,
    classification: isSkip ? 'skip' : 'positive',
    ..._formatSessionEntryDelta(beforeEntry, afterEntry),
  }, { important: true, tags: ['session', 'weight'] });

  const negativeAfter = negativeScores[prevSong.filename] || 0;
  const tasteBefore = _computeTasteScoreSnapshot(prevSong.id, summaryPreview && summaryPreview.before, { xScore: negativeBefore });
  const tasteAfter = _computeTasteScoreSnapshot(prevSong.id, summaryPreview && summaryPreview.after, { xScore: negativeAfter });
  const directScoreDelta = ((tasteAfter && tasteAfter.directScore) || 0) - ((tasteBefore && tasteBefore.directScore) || 0);
  const playbackSignalEvent = _normalizePlaybackSignalEvent({
    eventId: `ps_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    timestamp: new Date().toISOString(),
    songId: prevSong.id,
    title: prevSong.title,
    artist: prevSong.artist,
    filename: prevSong.filename,
    fraction: _roundSignal(prevFraction),
    classification: isSkip ? 'skip' : 'positive',
    source: transitionAction || null,
    sessionBefore: _summarizeSessionSignalEntry(beforeEntry),
    sessionAfter: _summarizeSessionSignalEntry(afterEntry),
    summaryBefore: _summarizeProfileSummaryEntry(summaryPreview && summaryPreview.before),
    summaryAfter: _summarizeProfileSummaryEntry(summaryPreview && summaryPreview.after),
    negativeBefore,
    negativeAfter,
    tasteBefore,
    tasteAfter,
  });
  // Skip the "Last 30 Playback Signal Updates" UI entry when the listen is a
  // very-short skip (< NEUTRAL_SKIP_CAPTURE_THRESHOLD, currently 10%). Previously
  // only `action === 'neutral_skip'` at <10% was filtered, so a tile-tap or
  // section-tap that replaced the current song after 1-2 seconds still surfaced
  // as a signal update — inconsistent with the UI promise ("very early skips
  // under 10% listened are not added here"). The engine still records the listen
  // into state/profile so repeat-sampling evidence is preserved; only the
  // per-playback-result timeline skips the render-level event.
  const isVeryShortSkip = isSkip
    && typeof prevFraction === 'number'
    && prevFraction <= NEUTRAL_SKIP_CAPTURE_THRESHOLD;
  if (!isVeryShortSkip) {
    _rememberPlaybackSignalEvent(playbackSignalEvent);
  }
  if (log && typeof log.playbackSignalApplied === 'function') {
    log.playbackSignalApplied(playbackSignalEvent);
  }
  _activity('taste', 'playback_signal_applied', `Playback signal updated for "${prevSong.title}"`, playbackSignalEvent, {
    important: true,
    tags: ['session', 'profile', 'weight'],
  });
  _applySimilarityBoostPropagation(prevSong.id, directScoreDelta, isSkip ? 'playback_skip' : 'playback_complete');
  scheduleRecommendationRebuild(isSkip ? 'playback_skip' : 'playback_complete', {
    refreshQueue: true,
    refreshDiscover: true,
  });

  if (opts.useCurrentPlaybackDedupe) {
    setCapturedPlaybackInstanceId(_currentPlaybackInstanceId);
  }
  return true;
}

function _recordListen(prevFraction, opts = {}) {
  if (state.current == null) return false;
  return _applyRecordedListen(state.current, prevFraction, {
    ...opts,
    sourceFallback: opts.sourceFallback || state.currentSource || null,
    useCurrentPlaybackDedupe: true,
  });
}

function _resolveSongIdFromPendingListen(snapshot) {
  if (!snapshot) return null;
  if (snapshot.filename) {
    const byFilename = _filenameToId(snapshot.filename);
    if (byFilename != null) return byFilename;
  }
  if (snapshot.songId != null && songs[snapshot.songId]) return snapshot.songId;
  return null;
}

function recordRecoveredListen(snapshot, opts = {}) {
  const songId = _resolveSongIdFromPendingListen(snapshot);
  if (songId == null) return { ok: false, reason: 'song_not_found' };

  let fraction = opts.fraction;
  if (!(fraction >= 0) && snapshot && snapshot.durationMs > 0) {
    fraction = snapshot.playedMs / snapshot.durationMs;
  }
  if (!(fraction >= 0)) return { ok: false, reason: 'fraction_unavailable' };

  const clamped = Math.max(0, Math.min(1, Math.round(fraction * 100) / 100));
  const transitionAction = opts.transitionAction || snapshot.reason || 'pending_recovery';
  const playbackStart = _ensurePlaybackStartLogged(songId, transitionAction, null, null, {
    playbackInstanceId: snapshot && snapshot.playbackInstanceId,
  });
  const applied = _applyRecordedListen(songId, clamped, {
    transitionAction,
    sourceFallback: transitionAction,
    useCurrentPlaybackDedupe: false,
    ...(playbackStart && playbackStart.logged ? { summaryEntryBefore: playbackStart.summaryEntry } : {}),
  });
  return {
    ok: applied,
    reason: applied ? 'applied' : 'apply_failed',
    songId,
    filename: songs[songId] ? songs[songId].filename : (snapshot ? snapshot.filename : null),
    fraction: clamped,
    transitionAction,
  };
}

function _cleanQueue() {
  const played = new Set(state.history);
  if (state.current != null) played.add(state.current);
  state.queue = state.queue.filter(q => q.manual || !played.has(q.id));
}

function _getSessionVec() {
  const qualified = [];
  const skipped = [];
  for (const s of state.listened) {
    const frac = s.listen_fraction;
    const source = s.source || 'manual';
    if (String(source).includes('auto') && frac < 0.2) continue;
    if (!songs[s.id] || !songs[s.id].hasEmbedding) continue;
    if (!_isSkipClassification(frac)) {
      qualified.push(s);
    } else {
      skipped.push(s);
    }
  }
  if (qualified.length < 2) return null;
  // Map to embedding indices for the recommender
  const mapped = qualified.map(s => ({
    id: songs[s.id].embeddingIndex,
    listen_fraction: s.listen_fraction,
  }));
  const mappedSkips = skipped.map(s => ({
    id: songs[s.id].embeddingIndex,
    listen_fraction: s.listen_fraction,
  }));
  return rec.buildSessionVector(mapped, mappedSkips);
}

/**
 * Unified query-vector builder for all recommendation entry points.
 *
 * mode = 'play':     song just started. Current-song weight ramps from 0.50 down
 *                    to 0.38 as session grows; session ramps 0 → 0.52; profile
 *                    fades 0.50 → 0.10. See _blendWeights('play').
 * mode = 'refresh':  manual Refresh button. Flatter, less current-anchored:
 *                    fixed 0.30/0.40/0.30. Session-momentum heavy.
 * mode = 'playNext': relevance scoring for a user-picked song. Uses a variant
 *                    with 0.40/0.30/0.30 and is not used to build queues.
 */
function _applySessionBias(w) {
  if (!w) return w;
  if (w.wSession <= 0 || w.wProfile <= 0) return w;
  // sessionBias=0.5 is neutral (no change). 0 = all weight shifts to profile,
  // 1 = all non-current weight shifts to session. Current is preserved.
  const sb = _tuning.sessionBias;
  const rest = w.wSession + w.wProfile;
  const newSession = rest * sb;
  const newProfile = rest * (1 - sb);
  return { wCurrent: w.wCurrent, wSession: newSession, wProfile: newProfile };
}

function _blendWeights(mode, nListened, hasCurrent, hasSession, hasProfile) {
  const w = _blendWeightsRaw(mode, nListened, hasCurrent, hasSession, hasProfile);
  return _applySessionBias(w);
}

function _blendWeightsRaw(mode, nListened, hasCurrent, hasSession, hasProfile) {
  if (mode === 'refresh') {
    if (hasSession && hasProfile && hasCurrent) return { wCurrent: 0.30, wSession: 0.40, wProfile: 0.30 };
    if (hasSession && hasProfile) return { wCurrent: 0, wSession: 0.60, wProfile: 0.40 };
    if (hasSession && hasCurrent) return { wCurrent: 0.30, wSession: 0.70, wProfile: 0 };
    if (hasSession) return { wCurrent: 0, wSession: 1.0, wProfile: 0 };
    if (hasProfile && hasCurrent) return { wCurrent: 0.30, wSession: 0, wProfile: 0.70 };
    if (hasProfile) return { wCurrent: 0, wSession: 0, wProfile: 1.0 };
    if (hasCurrent) return { wCurrent: 1.0, wSession: 0, wProfile: 0 };
    return null;
  }

  if (mode === 'playNext') {
    if (hasCurrent && hasSession && hasProfile) return { wCurrent: 0.40, wSession: 0.30, wProfile: 0.30 };
    if (hasCurrent && hasSession) return { wCurrent: 0.50, wSession: 0.50, wProfile: 0 };
    if (hasCurrent && hasProfile) return { wCurrent: 0.50, wSession: 0, wProfile: 0.50 };
    if (hasCurrent) return { wCurrent: 1.0, wSession: 0, wProfile: 0 };
    if (hasProfile) return { wCurrent: 0, wSession: 0, wProfile: 1.0 };
    if (hasSession) return { wCurrent: 0, wSession: 1.0, wProfile: 0 };
    return null;
  }

  // mode === 'play' — ramps weights as session grows
  if (!hasCurrent) return null;
  if (!hasSession && !hasProfile) return { wCurrent: 1.0, wSession: 0, wProfile: 0 };
  if (!hasSession) {
    const t = Math.min(nListened / 8, 1);
    const wCurrent = 0.5 + 0.1 * t;
    return { wCurrent, wSession: 0, wProfile: 1 - wCurrent };
  }
  if (!hasProfile) {
    const t = Math.min(nListened / 10, 1);
    const wCurrent = 0.6 - 0.2 * t;
    return { wCurrent, wSession: 1 - wCurrent, wProfile: 0 };
  }
  const t = Math.min(nListened / 10, 1);
  let wCurrent = 0.50 - 0.12 * t;
  let wSession = 0.52 * t;
  let wProfile = 1 - wCurrent - wSession;
  if (wProfile < 0.08) wProfile = 0.08;
  const total = wCurrent + wSession + wProfile;
  return { wCurrent: wCurrent / total, wSession: wSession / total, wProfile: wProfile / total };
}

function _buildBlendedVec(songId) {
  if (!songs[songId].hasEmbedding) return { blended: null, label: songs[songId].title };

  const embIdx = songs[songId].embeddingIndex;
  if (!isEmbeddingsReady() || embIdx == null || !embeddings[embIdx]) {
    return { blended: null, label: songs[songId].title };
  }
  const currentVec = embeddings[embIdx];
  const sessionVec = _getSessionVec();
  const nListened = state.listened.length;

  const w = _blendWeights('play', nListened, true, sessionVec != null, profileVec != null);
  if (!w) return { blended: null, label: songs[songId].title };

  let blended = vecScale(currentVec, w.wCurrent);
  if (w.wSession > 0) blended = vecAdd(blended, vecScale(sessionVec, w.wSession));
  if (w.wProfile > 0) blended = vecAdd(blended, vecScale(profileVec, w.wProfile));
  blended = vecNormalize(blended);

  const parts = [songs[songId].title];
  if (w.wSession > 0.05) parts.push('session');
  if (w.wProfile > 0.05) parts.push('profile');
  return { blended, label: parts.join(' + ') };
}

// --- Rec Toggle ---

function setRecToggle(on) {
  _setRecToggle(on);
  if (state.timelineMode === 'explicit') return;
  if (!on && state.current != null && !state.playingAlbum && !state.playingFavorites && !state.playingPlaylist) {
    _buildShuffleQueue();
  } else if (on && state.current != null && songs[state.current].hasEmbedding
             && !state.playingAlbum && !state.playingFavorites && !state.playingPlaylist) {
    const manualItems = state.queue.filter(q => q.manual);
    const manualIds = new Set(manualItems.map(q => q.id));
    const exclude = new Set([...state.history, state.current, ...manualIds]);
    const embExclude = _songIdsToEmbExclude(exclude);
    const { blended, label } = _buildBlendedVec(state.current);
    if (blended && rec) {
      const recQueue = _recToQueue(rec.recommend(blended, TOP_N, embExclude, _getArtistMap(), songs[state.current].artist));
      state.queue = [...manualItems, ...recQueue.slice(0, Math.max(0, TOP_N - manualItems.length))];
      state.sessionLabel = `Based on: ${label}`;
    }
  }
  _applyQueueShuffleState();
  _syncDynamicTimelineFromState();
}

function getRecToggle() { return recToggle; }

function setQueueShuffleEnabled(on) {
  _setQueueShuffleEnabled(!!on);
  if (queueShuffleEnabled) shuffleQueue();
}

function getQueueShuffleEnabled() { return queueShuffleEnabled; }

function _buildShuffleQueue() {
  const manualItems = state.queue.filter(q => q.manual);
  const manualIds = new Set(manualItems.map(q => q.id));

  const exclude = new Set(state.history);
  if (state.current != null) exclude.add(state.current);
  for (const id of manualIds) exclude.add(id);
  const candidates = songs.filter(s => !exclude.has(s.id) && s.filePath);
  for (let i = candidates.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [candidates[i], candidates[j]] = [candidates[j], candidates[i]];
  }
  const shuffled = candidates.slice(0, Math.max(0, TOP_N - manualItems.length)).map(s => ({ id: s.id, similarity: 0 }));
  state.queue = [...manualItems, ...shuffled];
  state.sessionLabel = 'Shuffle';
  _syncDynamicTimelineFromState();
}

function _doRefresh(reason = 'auto') {
  if (state.timelineMode === 'explicit') {
    _clearTimelineContext();
  }
  // Preserve manually queued items (Play Next) across refreshes
  const manualItems = state.queue.filter(q => q.manual);

  if (!recToggle) {
    _buildShuffleQueue();
    // Re-insert manual items at front
    if (manualItems.length > 0) state.queue = [...manualItems, ...state.queue];
    return;
  }

  const exclude = new Set(state.history);
  if (state.current != null) exclude.add(state.current);
  // Also exclude manual items from AI recs to avoid duplicates
  for (const m of manualItems) exclude.add(m.id);
  const embExclude = _songIdsToEmbExclude(exclude);

  const sessionVec = _getSessionVec();
  const artistMap = _getArtistMap();
  const currentArtist = _getCurrentArtist();
  let sourceType = null;

  const hasCurrentEmb = isEmbeddingsReady()
    && state.current != null
    && songs[state.current]
    && songs[state.current].hasEmbedding
    && songs[state.current].embeddingIndex != null
    && !!embeddings[songs[state.current].embeddingIndex];
  const currentVec = hasCurrentEmb ? embeddings[songs[state.current].embeddingIndex] : null;

  const w = _blendWeights('refresh', state.listened.length, hasCurrentEmb, sessionVec != null, profileVec != null);
  if (!w) {
    _buildShuffleQueue();
    sourceType = 'shuffle';
  } else {
    let blended = null;
    if (w.wCurrent > 0) blended = vecScale(currentVec, w.wCurrent);
    if (w.wSession > 0) blended = blended ? vecAdd(blended, vecScale(sessionVec, w.wSession)) : vecScale(sessionVec, w.wSession);
    if (w.wProfile > 0) blended = blended ? vecAdd(blended, vecScale(profileVec, w.wProfile)) : vecScale(profileVec, w.wProfile);
    blended = vecNormalize(blended);
    state.queue = _recToQueue(rec.recommend(blended, TOP_N, embExclude, artistMap, currentArtist));

    // Label based on which signals participated
    if (w.wSession > 0 && w.wProfile > 0) { state.sessionLabel = 'Session + profile blend'; sourceType = 'session_profile_blend'; }
    else if (w.wSession > 0) { state.sessionLabel = 'Session blend'; sourceType = 'session_blend'; }
    else if (w.wProfile > 0) { state.sessionLabel = 'Based on your taste'; sourceType = 'profile'; }
    else { state.sessionLabel = `Based on: ${songs[state.current].title}`; sourceType = 'single_song'; }
  }

  // Re-insert manual items at front of queue
  if (manualItems.length > 0) state.queue = [...manualItems, ...state.queue];
  _applyQueueShuffleState();

  if (sourceType) {
    _logQueue(sourceType, reason);
    _activity('engine', 'queue_refreshed', `Queue refreshed (${reason})`, {
      sourceType,
      reason,
      queueLength: state.queue.length,
      sessionLabel: state.sessionLabel,
    }, { important: true, tags: ['queue', 'recommendation'] });
  }
  _syncDynamicTimelineFromState();
}

/**
 * Soft refresh: subtly evolve the queue without jarring changes.
 * Called after each qualified listen (>30%).
 *
 * Zones:
 *   0..FROZEN_ZONE-1   — untouched
 *   FROZEN_ZONE..STABLE_ZONE-1 — same songs, re-sorted by updated similarity
 *   STABLE_ZONE..end   — fluid, replaced with best fresh candidates
 */
function _softRefreshQueue() {
  if (!recToggle || !isEmbeddingsReady() || state.queue.length < FROZEN_ZONE) return;
  if (state.playingFavorites || state.playingAlbum || state.playingPlaylist) return;
  if (state.timelineMode === 'explicit') return;
  if (state.current == null) return;

  const currentSong = songs[state.current];
  if (!currentSong || !currentSong.hasEmbedding) return;

  // Build fresh blended vector from current state
  const { blended } = _buildBlendedVec(state.current);
  if (!blended) return;

  // Get fresh recommendations (larger pool to have candidates for fluid zone)
  const exclude = new Set(state.history);
  exclude.add(state.current);
  // Also exclude songs already in frozen zone so they don't appear in fresh recs
  const frozenIds = new Set();
  for (let i = 0; i < Math.min(FROZEN_ZONE, state.queue.length); i++) {
    frozenIds.add(state.queue[i].id);
    exclude.add(state.queue[i].id);
  }
  const embExclude = _songIdsToEmbExclude(exclude);
  const freshRaw = rec.recommend(blended, TOP_N + FROZEN_ZONE, embExclude, _getArtistMap(), currentSong.artist);
  const fresh = _recToQueue(freshRaw);

  // Build a similarity lookup from fresh results
  const freshSimMap = new Map();
  for (const item of fresh) {
    freshSimMap.set(item.id, item.similarity);
  }

  // --- Frozen zone (0..FROZEN_ZONE-1): keep as-is ---
  const frozen = state.queue.slice(0, Math.min(FROZEN_ZONE, state.queue.length));

  // --- Stable zone (FROZEN_ZONE..STABLE_ZONE-1): same songs, re-sorted ---
  const stableEnd = Math.min(STABLE_ZONE, state.queue.length);
  const stableSongs = state.queue.slice(FROZEN_ZONE, stableEnd);
  // Update similarity scores from fresh recs (keep old score if not in fresh set)
  for (const item of stableSongs) {
    if (freshSimMap.has(item.id)) {
      item.similarity = freshSimMap.get(item.id);
    }
  }
  // Re-sort stable zone by updated similarity (highest first)
  stableSongs.sort((a, b) => b.similarity - a.similarity);

  // --- Fluid zone (STABLE_ZONE..end): replace with best fresh candidates, but keep manual items ---
  const manualItems = state.queue.slice(stableEnd).filter(q => q.manual);
  const keepIds = new Set([...frozen.map(q => q.id), ...stableSongs.map(q => q.id), ...manualItems.map(q => q.id)]);
  // Filter fresh recs to only those not already in frozen/stable/manual
  const fluidCandidates = fresh.filter(q => !keepIds.has(q.id) && !frozenIds.has(q.id));
  const fluidSize = Math.max(0, TOP_N - frozen.length - stableSongs.length - manualItems.length);
  const fluidZone = fluidCandidates.slice(0, fluidSize);

  // Reassemble queue — manual items first in fluid zone so they play sooner
  state.queue = [...frozen, ...stableSongs, ...manualItems, ...fluidZone];
  _applyQueueShuffleState();
  _syncDynamicTimelineFromState();
}

function _logQueue(sourceType, reason) {
  const queueForLog = state.queue.map(q => ({
    title: songs[q.id] ? songs[q.id].title : 'Unknown',
    similarity: q.similarity,
  }));
  log.queueGenerated(sourceType, [], queueForLog);
}

function _currentSongInfo() {
  if (state.current == null || state.current >= songs.length) return null;
  const s = songs[state.current];
  if (!s) return null;
  return {
    id: s.id,
    title: s.title,
    artist: s.artist,
    album: s.album,
    filename: s.filename,
    filePath: s.filePath,
    hasEmbedding: s.hasEmbedding,
    isFavorite: favorites.has(s.id),
  };
}

// --- Public Session API ---

function play(songId, prevFraction = null, source = 'manual') {
  let prevSongInfo = null;
  if (state.current != null) prevSongInfo = songs[state.current];
  _recordListen(prevFraction);
  _activity('playback', 'song_started', `Started "${songs[songId]?.title || 'song'}"`, {
    ..._songRef(songId),
    source,
    prevSongId: prevSongInfo ? prevSongInfo.id : null,
    prevSongTitle: prevSongInfo ? prevSongInfo.title : null,
    prevFraction,
  }, { important: true, tags: ['playback', 'transition'] });

  if (state.historyPos < state.history.length - 1) {
    state.history = state.history.slice(0, state.historyPos + 1);
  }
  if (state.history.length === 0 || state.history[state.history.length - 1] !== songId) {
    state.history.push(songId);
  }
  state.historyPos = state.history.length - 1;
  state.current = songId;
  state.currentSource = source;
  state.playingFavorites = false;
  state.playingAlbum = false;
  state.playingPlaylist = false;
  state.currentPlaylistId = null;
  state.currentAlbumTracks = [];
  _beginPlaybackInstance();

  _ensurePlaybackStartLogged(songId, source, prevSongInfo, prevFraction);

  // Build queue
  if (!recToggle) {
    _buildShuffleQueue();
  } else if (isEmbeddingsReady() && songs[songId].hasEmbedding) {
    const exclude = new Set([...state.history, songId]);
    const embExclude = _songIdsToEmbExclude(exclude);
    const { blended, label } = _buildBlendedVec(songId);
    if (blended) {
      state.queue = _recToQueue(rec.recommend(blended, TOP_N, embExclude, _getArtistMap(), songs[songId].artist));
      state.sessionLabel = `Based on: ${label}`;
    } else {
      _buildShuffleQueue();
    }
    _logQueue('multi_timescale', 'manual_pick');
  } else {
    _buildShuffleQueue();
  }

  _applyQueueShuffleState();
  _clearTimelineContext();
  _syncDynamicTimelineFromState();

  return _currentSongInfo();
}

function _startExplicitPlayback(songId, timelineItems, currentIndex, prevFraction = null, config = {}) {
  if (!Array.isArray(timelineItems) || timelineItems.length === 0) return null;
  const startIndex = Math.max(0, Math.min(currentIndex, timelineItems.length - 1));
  const startItem = _cloneTimelineItem(timelineItems[startIndex]);
  if (!startItem || !songs[startItem.id]) return null;

  let prevSongInfo = null;
  if (state.current != null) prevSongInfo = songs[state.current];
  _markExplicitCurrentPlayed();
  _recordListen(prevFraction);

  const source = config.source || 'ordered_list';
  const activityMessage = config.activityMessage || `Started "${songs[startItem.id]?.title || 'song'}"`;
  const activityData = {
    ..._songRef(startItem.id),
    source,
    prevSongId: prevSongInfo ? prevSongInfo.id : null,
    prevSongTitle: prevSongInfo ? prevSongInfo.title : null,
    prevFraction,
    ...(config.activityData || {}),
  };
  _activity('playback', 'song_started', activityMessage, activityData, { important: true, tags: ['playback', 'transition'] });

  if (state.historyPos < state.history.length - 1) {
    state.history = state.history.slice(0, state.historyPos + 1);
  }
  if (state.history.length === 0 || state.history[state.history.length - 1] !== startItem.id) {
    state.history.push(startItem.id);
  }
  state.historyPos = state.history.length - 1;
  state.current = startItem.id;
  state.currentSource = source;
  state.playingFavorites = !!config.playingFavorites;
  state.playingAlbum = !!config.playingAlbum;
  state.playingPlaylist = !!config.playingPlaylist;
  state.currentPlaylistId = config.currentPlaylistId || null;
  state.currentAlbumTracks = Array.isArray(config.currentAlbumTracks) ? [...config.currentAlbumTracks] : [];
  _beginPlaybackInstance();

  _ensurePlaybackStartLogged(startItem.id, source, prevSongInfo, prevFraction);

  _setExplicitTimeline(timelineItems, startIndex);
  _queueFromTimelineAfter(state.timelineIndex);
  if (queueShuffleEnabled) {
    _rebuildExplicitShuffledQueue();
  }
  state.sessionLabel = config.sessionLabel || state.sessionLabel || 'Playing list';
  return _currentSongInfo();
}

function playFromAlbum(songId, albumTrackIds, prevFraction = null) {
  const timelineItems = (albumTrackIds || [])
    .filter(id => id != null && id < songs.length && songs[id] && songs[id].filePath)
    .map(id => ({ id, similarity: 0, manual: false }));
  const idx = Math.max(0, timelineItems.findIndex(item => item.id === songId));
  return _startExplicitPlayback(songId, timelineItems, idx, prevFraction, {
    source: 'album',
    activityMessage: `Started "${songs[songId]?.title || 'song'}" from album`,
    playingAlbum: true,
    currentAlbumTracks: albumTrackIds,
    sessionLabel: `Album: ${songs[songId].album}`,
  });
}

// Play a discover section: tapped song plays first, the rest of the section's
// songs fill the queue in order. When the queue is exhausted, the normal
// _doRefresh('queue_exhausted') path takes over and brings fresh AI recs.
function playFromSection(songId, sectionSongIds, sectionLabel, prevFraction = null) {
  const timelineItems = (sectionSongIds || [])
    .filter(id => id != null && id < songs.length && songs[id] && songs[id].filePath)
    .map(id => ({ id, similarity: 1.0, manual: false }));
  const idx = Math.max(0, timelineItems.findIndex(item => item.id === songId));
  return _startExplicitPlayback(songId, timelineItems, idx, prevFraction, {
    source: 'section',
    activityMessage: `Started "${songs[songId]?.title || 'song'}" from section`,
    activityData: { sectionLabel: sectionLabel || '' },
    sessionLabel: sectionLabel || 'Playing section',
  });
}

function playFromOrderedList(songIds, startIndex = 0, sessionLabel = 'Playing list', source = 'ordered_list', prevFraction = null) {
  const timelineItems = (songIds || [])
    .filter(id => id != null && id < songs.length && songs[id] && songs[id].filePath)
    .map(id => ({ id, similarity: 1.0, manual: false }));
  if (timelineItems.length === 0) return null;
  const idx = Math.max(0, Math.min(startIndex, timelineItems.length - 1));
  const songId = timelineItems[idx].id;
  return _startExplicitPlayback(songId, timelineItems, idx, prevFraction, {
    source,
    activityMessage: `Started "${songs[songId]?.title || 'song'}" from ${sessionLabel || 'list'}`,
    sessionLabel,
  });
}

function playFromTimelineIndex(index, prevFraction = null) {
  if (!Array.isArray(state.timelineItems) || state.timelineItems.length === 0) return null;
  const targetIndex = Math.max(0, Math.min(index, state.timelineItems.length - 1));
  const target = _cloneTimelineItem(state.timelineItems[targetIndex]);
  if (!target || !songs[target.id]) return null;

  let prevSongInfo = null;
  if (state.current != null) prevSongInfo = songs[state.current];
  _markExplicitCurrentPlayed();
  _recordListen(prevFraction);

  if (state.timelineMode === 'explicit') {
    if (state.historyPos < state.history.length - 1) {
      state.history = state.history.slice(0, state.historyPos + 1);
    }
    if (state.history.length === 0 || state.history[state.history.length - 1] !== target.id) {
      state.history.push(target.id);
    }
    state.historyPos = state.history.length - 1;
    state.current = target.id;
    state.currentSource = 'manual_timeline';
    state.timelineIndex = targetIndex;
    if (queueShuffleEnabled) _rebuildExplicitShuffledQueue();
    else _queueFromTimelineAfter(targetIndex);
  } else {
    const beforeCount = Math.max(0, state.historyPos);
    const afterCount = Math.max(0, state.history.length - state.historyPos - 1);
    const currentIndex = state.current != null ? beforeCount : -1;

    if (targetIndex < beforeCount) {
      state.historyPos = targetIndex;
      state.current = state.history[state.historyPos];
    } else if (currentIndex >= 0 && targetIndex === currentIndex) {
      state.current = target.id;
    } else if (currentIndex >= 0 && targetIndex <= currentIndex + afterCount) {
      state.historyPos = state.historyPos + (targetIndex - currentIndex);
      state.current = state.history[state.historyPos];
    } else {
      const queueIdx = targetIndex - Math.max(0, currentIndex + 1 + afterCount);
      if (queueIdx < 0 || queueIdx >= state.queue.length) return null;
      if (state.historyPos < state.history.length - 1) {
        state.history = state.history.slice(0, state.historyPos + 1);
      }
      state.current = target.id;
      if (state.history.length === 0 || state.history[state.history.length - 1] !== target.id) {
        state.history.push(target.id);
      }
      state.historyPos = state.history.length - 1;
      state.queue = state.queue.slice(queueIdx + 1);
    }
    state.currentSource = 'manual_timeline';
    _syncDynamicTimelineFromState();
  }
  _beginPlaybackInstance();

  _ensurePlaybackStartLogged(target.id, 'manual_timeline', prevSongInfo, prevFraction);
  _activity('playback', 'song_started', `Started "${songs[target.id]?.title || 'song'}" from timeline`, {
    ..._songRef(target.id),
    source: 'manual_timeline',
    timelineIndex: targetIndex,
    prevSongId: prevSongInfo ? prevSongInfo.id : null,
    prevSongTitle: prevSongInfo ? prevSongInfo.title : null,
    prevFraction,
  }, { important: true, tags: ['playback', 'transition', 'queue'] });
  return _currentSongInfo();
}

// Play one song without touching the existing queue. For the "Play only" menu
// action: user taps ⋮ → Play only, the tapped song starts now but Up Next stays.
function playOnly(songId, prevFraction = null) {
  let prevSongInfo = null;
  if (state.current != null) prevSongInfo = songs[state.current];
  _markExplicitCurrentPlayed();
  _recordListen(prevFraction);
  _activity('playback', 'song_started', `Started "${songs[songId]?.title || 'song'}" with Play Only`, {
    ..._songRef(songId),
    source: 'play_only',
    prevSongId: prevSongInfo ? prevSongInfo.id : null,
    prevSongTitle: prevSongInfo ? prevSongInfo.title : null,
    prevFraction,
  }, { important: true, tags: ['playback', 'transition'] });

  if (state.historyPos < state.history.length - 1) {
    state.history = state.history.slice(0, state.historyPos + 1);
  }
  if (state.history.length === 0 || state.history[state.history.length - 1] !== songId) {
    state.history.push(songId);
  }
  state.historyPos = state.history.length - 1;
  state.current = songId;
  state.currentSource = 'play_only';
  _beginPlaybackInstance();

  _ensurePlaybackStartLogged(songId, 'play_only', prevSongInfo, prevFraction);
  // Queue intentionally untouched.
  _clearTimelineContext();
  _syncDynamicTimelineFromState();
  return _currentSongInfo();
}

function playFromFavorites(songId, prevFraction = null) {
  const favIds = getFavoritesList().map(s => s.id);
  const timelineItems = favIds.map(id => ({ id, similarity: 1.0, manual: false }));
  const idx = Math.max(0, timelineItems.findIndex(item => item.id === songId));
  return _startExplicitPlayback(songId, timelineItems, idx, prevFraction, {
    source: 'favorites',
    activityMessage: `Started "${songs[songId]?.title || 'song'}" from favorites`,
    playingFavorites: true,
    sessionLabel: 'Favorites',
  });
}

function playFromPlaylist(playlistId, songId, prevFraction = null) {
  const playlist = _getPlaylistById(playlistId);
  if (!playlist) return null;
  const ids = _resolvePlaylistSongIds(playlist);
  if (!ids || ids.length === 0) return null;
  const timelineItems = ids
    .filter(id => id != null && id < songs.length && songs[id] && songs[id].filePath)
    .map(id => ({ id, similarity: 1.0, manual: false }));
  const startId = ids.includes(songId) ? songId : ids[0];
  const idx = Math.max(0, timelineItems.findIndex(item => item.id === startId));
  return _startExplicitPlayback(startId, timelineItems, idx, prevFraction, {
    source: 'playlist',
    activityMessage: `Started "${songs[startId]?.title || 'song'}" from playlist "${playlist.name}"`,
    activityData: { playlistId, playlistName: playlist.name },
    playingPlaylist: true,
    currentPlaylistId: playlistId,
    sessionLabel: `Playlist: ${playlist.name}`,
  });
}

function nextSong(prevFraction = null, source = 'next_button') {
  let prevSongInfo = null;
  if (state.current != null) prevSongInfo = songs[state.current];
  _markExplicitCurrentPlayed();
  _recordListen(prevFraction, { transitionAction: source });

  if (state.historyPos < state.history.length - 1) {
    state.historyPos++;
    state.current = state.history[state.historyPos];
    _beginPlaybackInstance();
    log.historyForward(songs[state.current]);
    _syncDynamicTimelineFromState();
    return _currentSongInfo();
  }

  _cleanQueue();

  if (state.queue.length > 0) {
    // Soft refresh after qualified listens (>30%) — evolve queue subtly
    if (prevFraction != null && !_isSkipClassification(prevFraction)) {
      _softRefreshQueue();
    }

    const queueItem = state.queue.shift();
    const songId = queueItem.id;
    const effectiveSource = queueItem.manual ? 'manual' : source;
    state.history.push(songId);
    state.historyPos = state.history.length - 1;
    state.current = songId;
    state.currentSource = effectiveSource;
    _beginPlaybackInstance();
    if (state.timelineMode === 'explicit' && state.timelineIndex >= 0) {
      state.timelineIndex = Math.min(state.timelineItems.length - 1, state.timelineIndex + 1);
      _queueFromTimelineAfter(state.timelineIndex);
    } else {
      _syncDynamicTimelineFromState();
    }
    _ensurePlaybackStartLogged(songId, effectiveSource, prevSongInfo, prevFraction);
    _activity('playback', 'song_started', `Advanced to "${songs[songId]?.title || 'song'}"`, {
      ..._songRef(songId),
      source: effectiveSource,
      prevSongId: prevSongInfo ? prevSongInfo.id : null,
      prevSongTitle: prevSongInfo ? prevSongInfo.title : null,
      prevFraction,
    }, { important: true, tags: ['playback', 'transition'] });
    return _currentSongInfo();
  }

  // Queue exhausted
  if (state.current != null) {
    if (state.playingFavorites) {
      state.sessionLabel = 'All favorites played';
      return null;
    }

    if (state.playingPlaylist) {
      const playlist = _getPlaylistById(state.currentPlaylistId);
      state.sessionLabel = playlist ? `${playlist.name} finished` : 'Playlist finished';
      return null;
    }

    if (state.playingAlbum && state.currentAlbumTracks.length > 0) {
      state.sessionLabel = 'Album finished';
      return null;
    }

    log.queueExhausted();
    _doRefresh('queue_exhausted');

    if (state.queue.length > 0) {
      const songId = state.queue.shift().id;
      state.history.push(songId);
      state.historyPos = state.history.length - 1;
      state.current = songId;
      state.currentSource = source + '_after_rebuild';
      _beginPlaybackInstance();
      _syncDynamicTimelineFromState();
      _ensurePlaybackStartLogged(songId, source + '_after_rebuild', prevSongInfo, prevFraction);
      _activity('playback', 'song_started', `Advanced to "${songs[songId]?.title || 'song'}" after queue rebuild`, {
        ..._songRef(songId),
        source: source + '_after_rebuild',
        prevSongId: prevSongInfo ? prevSongInfo.id : null,
        prevSongTitle: prevSongInfo ? prevSongInfo.title : null,
        prevFraction,
      }, { important: true, tags: ['playback', 'transition'] });
      return _currentSongInfo();
    }
  }

  return null;
}

function prevSong(prevFraction = null) {
  _markExplicitCurrentPlayed();
  _recordListen(prevFraction, { transitionAction: 'user_prev' });
  if (state.timelineMode === 'explicit' && state.timelineIndex > 0) {
    state.timelineIndex--;
    state.current = state.timelineItems[state.timelineIndex].id;
    state.currentSource = 'prev_navigation';
    _beginPlaybackInstance();
    _queueFromTimelineAfter(state.timelineIndex);
    log.prevNavigated(songs[state.current]);
    _activity('playback', 'song_started', `Navigated back to "${songs[state.current]?.title || 'song'}"`, {
      ..._songRef(state.current),
      source: 'prev_navigation',
      prevFraction,
    }, { important: true, tags: ['playback', 'transition'] });
    return _currentSongInfo();
  }
  if (state.historyPos > 0) {
    state.historyPos--;
    state.current = state.history[state.historyPos];
    _beginPlaybackInstance();
    log.prevNavigated(songs[state.current]);
    _syncDynamicTimelineFromState();
    _activity('playback', 'song_started', `Navigated back to "${songs[state.current]?.title || 'song'}"`, {
      ..._songRef(state.current),
      source: 'prev_navigation',
      prevFraction,
    }, { important: true, tags: ['playback', 'transition'] });
    return _currentSongInfo();
  }
  return null;
}

/**
 * Neutral skip: move to the next song without recording any negative signal.
 * The current song is NOT added to listened[] at all, so it won't count as
 * a skip and won't penalize future recommendations for this song.
 */
function neutralSkip(prevFraction = null) {
  _markExplicitCurrentPlayed();
  // Record listen only if user heard a meaningful chunk. Very early
  // neutral skips are not recorded, so they don't penalize the song.
  if (prevFraction != null && prevFraction > NEUTRAL_SKIP_CAPTURE_THRESHOLD) {
    _recordListen(prevFraction, { transitionAction: 'neutral_skip' });
  }
  if (state.historyPos < state.history.length - 1) {
    state.historyPos++;
    state.current = state.history[state.historyPos];
    _beginPlaybackInstance();
    _syncDynamicTimelineFromState();
    return _currentSongInfo();
  }

  _cleanQueue();

  if (state.queue.length > 0) {
    const songId = state.queue.shift().id;
    state.history.push(songId);
    state.historyPos = state.history.length - 1;
    state.current = songId;
    state.currentSource = 'neutral_skip';
    _beginPlaybackInstance();
    if (state.timelineMode === 'explicit' && state.timelineIndex >= 0) {
      state.timelineIndex = Math.min(state.timelineItems.length - 1, state.timelineIndex + 1);
      _queueFromTimelineAfter(state.timelineIndex);
    } else {
      _syncDynamicTimelineFromState();
    }
    _ensurePlaybackStartLogged(songId, 'neutral_skip', null, null);
    return _currentSongInfo();
  }

  if (state.current != null && !state.playingAlbum && !state.playingFavorites && !state.playingPlaylist) {
    _doRefresh('neutral_skip_queue_exhausted');
    if (state.queue.length > 0) {
      const songId = state.queue.shift().id;
      state.history.push(songId);
      state.historyPos = state.history.length - 1;
      state.current = songId;
      state.currentSource = 'neutral_skip_after_rebuild';
      _beginPlaybackInstance();
      _syncDynamicTimelineFromState();
      _ensurePlaybackStartLogged(songId, 'neutral_skip_after_rebuild', null, null);
      return _currentSongInfo();
    }
  }

  return null;
}

function playFromQueue(songId, prevFraction = null) {
  let prevSongInfo = null;
  if (state.current != null) prevSongInfo = songs[state.current];
  _markExplicitCurrentPlayed();
  _recordListen(prevFraction);

  // Add to history but do NOT regenerate queue
  if (state.historyPos < state.history.length - 1) {
    state.history = state.history.slice(0, state.historyPos + 1);
  }
  if (state.history.length === 0 || state.history[state.history.length - 1] !== songId) {
    state.history.push(songId);
  }
  state.historyPos = state.history.length - 1;
  state.current = songId;
  state.currentSource = 'manual_queue';
  state.playingFavorites = false;
  state.playingAlbum = false;
  state.playingPlaylist = false;
  state.currentPlaylistId = null;
  state.currentAlbumTracks = [];
  _beginPlaybackInstance();

  const queueIdx = state.queue.findIndex(item => item.id === songId);
  if (queueIdx !== -1) {
    state.queue = state.queue.slice(queueIdx + 1);
  }
  _clearTimelineContext();
  _syncDynamicTimelineFromState();

  _ensurePlaybackStartLogged(songId, 'manual_queue', prevSongInfo, prevFraction);
  _activity('playback', 'song_started', `Started "${songs[songId]?.title || 'song'}" from queue`, {
    ..._songRef(songId),
    source: 'manual_queue',
    prevSongId: prevSongInfo ? prevSongInfo.id : null,
    prevSongTitle: prevSongInfo ? prevSongInfo.title : null,
    prevFraction,
  }, { important: true, tags: ['playback', 'transition'] });

  return _currentSongInfo();
}

function refreshQueue() {
  state.playingFavorites = false;
  state.playingAlbum = false;
  state.playingPlaylist = false;
  state.currentPlaylistId = null;
  state.currentAlbumTracks = [];
  log.queueRefreshedManual();
  _doRefresh('manual');
}

function playNext(songId) {
  // Dedupe: remove any existing occurrence so the song is moved to its new slot
  // instead of being duplicated in the queue.
  state.queue = state.queue.filter(q => q.id !== songId);

  // Compute a relevance weight for display. User-picked songs are inherently
  // high-interest, so we floor the score — but we still surface the real
  // similarity against the current intent vector when we can compute it.
  let sim = 0.95;
  try {
    const target = songs[songId];
    if (target && target.hasEmbedding) {
      const targetVec = embeddings[target.embeddingIndex];
      const sessionVec = _getSessionVec();
      const hasCurEmb = state.current != null && songs[state.current] && songs[state.current].hasEmbedding;
      const curVec = hasCurEmb ? embeddings[songs[state.current].embeddingIndex] : null;
      const w = _blendWeights('playNext', state.listened.length, hasCurEmb, sessionVec != null, profileVec != null);
      let queryVec = null;
      if (w) {
        if (w.wCurrent > 0) queryVec = vecScale(curVec, w.wCurrent);
        if (w.wSession > 0) queryVec = queryVec ? vecAdd(queryVec, vecScale(sessionVec, w.wSession)) : vecScale(sessionVec, w.wSession);
        if (w.wProfile > 0) queryVec = queryVec ? vecAdd(queryVec, vecScale(profileVec, w.wProfile)) : vecScale(profileVec, w.wProfile);
        if (queryVec) queryVec = vecNormalize(queryVec);
      }
      if (queryVec) {
        let dp = 0;
        for (let i = 0; i < targetVec.length; i++) dp += targetVec[i] * queryVec[i];
        // User pick gets a 0.85 floor — they explicitly want this.
        sim = Math.max(0.85, Math.min(1, dp));
      }
    }
  } catch (e) { /* fall through with default sim */ }

  // FIFO insertion: insert AFTER the last existing manual item so multiple
  // Play Next picks play in the order the user selected them (not reversed).
  let insertAt = 0;
  for (let i = 0; i < state.queue.length; i++) {
    if (state.queue[i].manual) insertAt = i + 1; else break;
  }
  state.queue.splice(insertAt, 0, { id: songId, similarity: Math.round(sim * 1000) / 1000, manual: true });
  if (state.timelineMode === 'explicit' && state.timelineIndex >= 0) {
    state.timelineItems.splice(state.timelineIndex + 1 + insertAt, 0, {
      id: songId,
      similarity: Math.round(sim * 1000) / 1000,
      manual: true,
    });
  } else {
    _syncDynamicTimelineFromState();
  }
  _activity('ui', 'play_next_queued', `Queued "${songs[songId]?.title || 'song'}" to play next`, {
    ..._songRef(songId),
    insertAt,
    similarity: Math.round(sim * 1000) / 1000,
  }, { important: true, tags: ['queue'] });
}

function addToQueue(songId) {
  state.queue.push({ id: songId, similarity: 0, manual: true });
}

function removeFromQueue(queueIdx) {
  if (queueIdx < 0 || queueIdx >= state.queue.length) return false;
  const removed = state.queue[queueIdx];
  state.queue.splice(queueIdx, 1);
  if (state.timelineMode === 'explicit' && state.timelineIndex >= 0) {
    const timelineRemoveIdx = state.timelineIndex + 1 + queueIdx;
    if (timelineRemoveIdx >= 0 && timelineRemoveIdx < state.timelineItems.length) {
      state.timelineItems.splice(timelineRemoveIdx, 1);
    }
  } else {
    _syncDynamicTimelineFromState();
  }
  if (removed && removed.id != null) {
    _bumpNegativeScore(removed.id, NEG_X_DELTA);
    _activity('ui', 'queue_song_removed', `Removed "${songs[removed.id]?.title || 'song'}" from queue`, {
      ..._songRef(removed.id),
      queueIdx,
    }, { important: true, tags: ['queue'] });
    _applySimilarityBoostPropagation(removed.id, -NEG_X_DELTA, 'queue_remove');
    scheduleRecommendationRebuild('queue_remove', {
      refreshQueue: true,
      refreshDiscover: true,
    });
  }
  return true;
}

function shuffleQueue() {
  if (state.timelineMode === 'explicit' && state.timelineIndex >= 0) {
    state.playingFavorites = false;
    state.playingAlbum = false;
    state.playingPlaylist = false;
    state.currentPlaylistId = null;
    state.currentAlbumTracks = [];
    state.sessionLabel = 'Shuffled remaining unplayed songs';
    _rebuildExplicitShuffledQueue();
  } else {
    state.queue = _shuffleQueueEntries(state.queue);
    _syncDynamicTimelineFromState();
  }
}

async function doSurprise(prevFraction = null) {
  _recordListen(prevFraction);
  const sessionVec = _getSessionVec();
  const embExclude = _songIdsToEmbExclude(new Set(state.history));
  const embIdx = rec.surprise(sessionVec, embExclude);
  if (embIdx == null) return null;

  let songId = _fastEmbIdxToSongId(embIdx);
  // Ensure surprise picks a playable song
  if (!songs[songId] || !songs[songId].filePath) {
    // Try to find any playable song
    const playable = songs.filter(s => s.filePath && s.hasEmbedding);
    if (playable.length === 0) return null;
    songId = playable[Math.floor(Math.random() * playable.length)].id;
  }

  state.listened = [];
  state.history = [songId];
  state.historyPos = 0;
  state.current = songId;
  state.currentSource = 'surprise';
  state.playingFavorites = false;
  state.playingAlbum = false;
  state.playingPlaylist = false;
  state.currentPlaylistId = null;
  state.currentAlbumTracks = [];
  _beginPlaybackInstance();

  _ensurePlaybackStartLogged(songId, 'surprise', null, null);

  const surpriseEmbIdx = songs[songId].embeddingIndex;
  state.queue = rec && surpriseEmbIdx != null
    ? _recToQueue(rec.recommendSingle(surpriseEmbIdx, TOP_N, new Set([surpriseEmbIdx])))
    : [];
  state.sessionLabel = `Surprise: ${songs[songId].title}`;
  _applyQueueShuffleState();
  _clearTimelineContext();
  _syncDynamicTimelineFromState();
  _logQueue('surprise', 'surprise');

  setProfileVec(await buildProfileVec());
  return _currentSongInfo();
}

function getState() {
  if (state.timelineMode !== 'explicit') {
    _syncDynamicTimelineFromState();
  }
  const current = state.current != null ? _currentSongInfo() : null;

  const queue = state.queue.map(item => ({
    id: item.id,
    title: songs[item.id] ? songs[item.id].title : 'Unknown',
    artist: songs[item.id] ? songs[item.id].artist : '',
    similarity: String(item.similarity),
    hasEmbedding: songs[item.id] ? songs[item.id].hasEmbedding : false,
    manual: !!item.manual,
  }));

  const history = state.history.map(sid => ({
    id: sid,
    title: songs[sid] ? songs[sid].title : 'Unknown',
    artist: songs[sid] ? songs[sid].artist : '',
  }));

  // Determine mode indicator
  let modeIndicator = 'Shuffle';
  if (state.playingAlbum) modeIndicator = 'Album';
  else if (state.playingFavorites) modeIndicator = 'Favorites';
  else if (state.playingPlaylist) modeIndicator = 'Playlist';
  else if (state.timelineMode === 'explicit') modeIndicator = 'List';
  else if (recToggle && state.current != null && songs[state.current] && songs[state.current].hasEmbedding) modeIndicator = 'AI';
  else if (recToggle) modeIndicator = 'Shuffle';

  const timeline = (state.timelineItems || []).map((item, idx) => {
    const song = songs[item.id];
    const role = state.timelineIndex < 0
      ? 'upcoming'
      : (idx < state.timelineIndex ? 'previous' : (idx === state.timelineIndex ? 'current' : 'upcoming'));
    const futureHistoryCount = state.timelineMode === 'explicit'
      ? 0
      : Math.max(0, state.history.length - state.historyPos - 1);
    let queueIndex = null;
    if (role === 'upcoming') {
      if (state.timelineIndex < 0) {
        queueIndex = idx;
      } else if (state.timelineMode === 'explicit') {
        queueIndex = idx - state.timelineIndex - 1;
      } else {
        const offset = idx - state.timelineIndex - 1;
        queueIndex = offset >= futureHistoryCount ? (offset - futureHistoryCount) : null;
      }
    }
    return {
      id: item.id,
      title: song ? song.title : 'Unknown',
      artist: song ? song.artist : '',
      album: song ? song.album : '',
      similarity: String(item.similarity != null ? item.similarity : 0),
      hasEmbedding: song ? song.hasEmbedding : false,
      manual: !!item.manual,
      timelineIndex: idx,
      role,
      queueIndex,
    };
  });

  return {
    current,
    queue,
    queueRemaining: state.queue.length,
    history,
    historyPos: state.historyPos,
    sessionLabel: state.sessionLabel,
    recToggle,
    modeIndicator,
    playingAlbum: state.playingAlbum,
    playingFavorites: state.playingFavorites,
    playingPlaylist: state.playingPlaylist,
    currentPlaylistId: state.currentPlaylistId,
    timeline: {
      items: timeline,
      mode: state.timelineMode || 'dynamic',
      explicit: state.timelineMode === 'explicit',
      currentIndex: state.timelineIndex,
      previousCount: timeline.filter(item => item.role === 'previous').length,
      upcomingCount: timeline.filter(item => item.role === 'upcoming').length,
    },
  };
}

function getSongs() { return songs; }
// Orphaned songs (filePath cleared by the scan's deletion-detection pass or a
// play-time file-not-found) stay in `songs[]` so embeddingIndex / embeddings[]
// references remain stable until the user consents to remove the embedding via
// the AI-page Orphaned bucket. They must not leak into the Songs tab, Discover
// candidates, queue builders, or favorites — hence this filter.
function getPlayableSongs() { return songs.filter(s => s.filePath); }
function getAlbums() { return albumArray; }
function isFavorite(songId) { return favorites.has(songId); }
function hasEmbedding(songId) { return songId < songs.length && songs[songId].hasEmbedding; }
function getUnembeddedCount() { return songs.filter(s => !s.hasEmbedding).length; }

// Wire engine.js callbacks into engine-embeddings.js (avoids circular import)
initEmbeddingCallbacks({
  saveFavorites,
  savePlaylists,
  savePlaybackState,
  rebuildAlbums: _rebuildAlbums,
  invalidateArtistMap: () => { _artistMap = null; },
  invalidateForYouCache: _invalidateForYouCache,
  invalidateUnexploredClustersCache: _invalidateUnexploredClustersCache,
});
initDataCallbacks({
  softRefreshQueue: _softRefreshQueue,
  invalidateArtistMap: () => { _artistMap = null; },
  setExplicitPlayedIds: _setExplicitPlayedIds,
  syncDynamicTimelineFromState: _syncDynamicTimelineFromState,
});
initAnalyticsCallbacks({
  softRefreshQueue: _softRefreshQueue,
  doRefresh: _doRefresh,
  getFavoritesList,
  getState,
});
initPlaybackCallbacks({
  setExplicitPlayedIds: _setExplicitPlayedIds,
  syncDynamicTimelineFromState: _syncDynamicTimelineFromState,
  clearTimelineContext: _clearTimelineContext,
  markExplicitCurrentPlayed: _markExplicitCurrentPlayed,
  recordListen: _recordListen,
  beginPlaybackInstance: _beginPlaybackInstance,
  cleanQueue: _cleanQueue,
  doRefresh: _doRefresh,
  currentSongInfo: _currentSongInfo,
});

export {
  loadData,
  getSongs,
  getPlayableSongs,
  getAlbums,
  getFavoritesList,
  getDislikedSongsList,
  getPlaylists,
  getPlaylistSongs,
  getPlaylistMeta,
  createPlaylist,
  renamePlaylist,
  deletePlaylist,
  addSongToPlaylist,
  removeSongFromPlaylist,
  isSongInPlaylist,
  toggleFavorite,
  setFavoriteState,
  toggleDislike,
  isDisliked,
  isFavorite,
  hasEmbedding,
  getUnembeddedCount,
  loadRecentlyPlayed,
  getLastAddedSongs,
  analyzeProfile,
  getBecauseYouPlayed,
  getUnexploredClusters,
  play,
  playFromQueue,
  playFromAlbum,
  playFromSection,
  playFromOrderedList,
  playFromTimelineIndex,
  playOnly,
  playFromFavorites,
  playFromPlaylist,
  nextSong,
  prevSong,
  neutralSkip,
  refreshQueue,
  shuffleQueue,
  setQueueShuffleEnabled,
  getQueueShuffleEnabled,
  doSurprise,
  getState,
  setRecToggle,
  getRecToggle,
  onScanComplete,
  onLoadingStatus,
  onRecommendationRebuildStatus,
  getInsights,
  setLiveListenFraction,
  savePlaybackState,
  savePendingListenSnapshot,
  loadPendingListenSnapshot,
  clearPendingListenSnapshot,
  recordRecoveredListen,
  restorePlaybackState,
  restorePlaybackStateCritical,
  resetEngine,
  clearPlaybackSession,
  shutdown,
  getEmbeddingStatus,
  _runPostBatchMerge,
  reloadEmbeddingsFromDisk,
  resyncEmbeddingState,
  retryEmbedding,
  reembedAll,
  stopEmbedding,
  getOrphanedSongs,
  removeOrphanedEmbeddings,
  markSongMissingByPath,
  onSongLibraryChanged,
  removeSongEmbedding,
  readdSongEmbedding,
  embedRemovedSongsBatch,
  rebuildProfileVec,
  getProfileWeights,
  getTasteSignal,
  getRecentPlaybackSignalTimeline,
  getSongPlayStats,
  resetSongProfileWeight,
  removeAlbumEmbeddings,
  deleteSong,
  getRemovedEmbeddingSongs,
  removeUnmatchedEmbeddings,
  playNext,
  addToQueue,
  removeFromQueue,
  getNativeQueueSnapshot,
  getNativeQueueItems,
  getUpcomingNativeItems,
  syncCurrentFromNativeState,
  syncQueueFromNativeSnapshot,
  onNativeAdvance,
  bindCurrentPlaybackStartInstance,
  getTuning,
  setTuning,
  onAlbumArtReady,
  onEmbeddingsReady,
  getLastPlayedDisplay,
  saveDiscoverCache,
  saveDiscoverCacheDebounced,
  loadDiscoverCache,
  validateDiscoverCache,
  isEmbeddingsReady,
  startBackgroundScan,
  preloadEmbeddingsEarly,
  isEarlyEmbeddingLoaded,
  getRecentActivityEvents,
  getActivityLogStatus,
  flushActivityLog,
};
