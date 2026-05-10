// Profile analytics — recently played, last added, For You, "Because You Played",
// Unexplored Clusters, recommendation rebuild scheduling, Discover page cache.
// Extracted from engine.js. Cross-cutting queue/state callbacks injected via initAnalyticsCallbacks().

import { Preferences } from '@capacitor/preferences';
import { SessionLogger } from './logger.js';
import {
  songs, embeddings, rec, profileVec, favorites,
  _recommendationPolicySnapshot, setProfileVec,
  setRecommendationRebuildTimer, setRecommendationRebuildInFlight,
  setRecommendationRebuildPending, setRecommendationRebuildReason, setRecommendationRebuildOpts,
  _recommendationRebuildTimer, _recommendationRebuildInFlight, _recommendationRebuildPending,
  _recommendationRebuildReason, _recommendationRebuildOpts,
  RECOMMENDATION_REBUILD_DEBOUNCE_MS, state, recToggle,
} from './engine-state.js';
import { _getFilenameMap, _songIdsToEmbExclude, _fastEmbIdxToSongId } from './engine-indexes.js';
import {
  _emitRecommendationStatus, buildProfileVec, _activity,
  _getRecommendationPolicyRow, _applyRecommendationPolicyToSongItems,
  _recommendFromQueryVec, _recommendFromSourceEmb, _getSummaryListenStats,
} from './engine-taste.js';
import { _nativeRecommendFromQueryVec } from './engine-embeddings.js';

// --- Callbacks wired by engine.js to avoid circular import ---
let _acbs = {};
export function initAnalyticsCallbacks(cbs) { _acbs = cbs; }
// _acbs.softRefreshQueue()   — _refreshDynamicRecommendations
// _acbs.doRefresh(reason)    — _refreshDynamicRecommendations
// _acbs.getFavoritesList()   — saveDiscoverCache
// _acbs.getState()           — saveDiscoverCache

// --- For You cache ---

let _forYouCache = null;
let _forYouAutoRefreshCounter = 0;
let _forYouRecentShown = [];
const FORYOU_LISTEN_WINDOW = 5;
const FORYOU_POOL_SIZE = 120;
const FORYOU_SHOW_COUNT = 15;

export function _invalidateForYouCache(reason = 'unspecified') {
  _forYouCache = null;
  _forYouAutoRefreshCounter = 0;
  _activity('engine', 'for_you_cache_invalidated', `For You cache invalidated (${reason})`, { reason }, { important: false, tags: ['cache'] });
}

export function tickForYouListenWindow() {
  _forYouAutoRefreshCounter++;
  if (_forYouAutoRefreshCounter >= FORYOU_LISTEN_WINDOW) _invalidateForYouCache('listen_window');
}

// --- Unexplored Clusters cache ---

let _unexploredClustersCache = null;

export function _invalidateUnexploredClustersCache(reason = 'unspecified') {
  _unexploredClustersCache = null;
  _activity('engine', 'unexplored_cache_invalidated', `Unexplored cache invalidated (${reason})`, { reason }, { important: false, tags: ['cache'] });
}

// --- Recommendation rebuild scheduling ---

function _mergeRecommendationRebuildOpts(base = {}, incoming = {}) {
  return {
    refreshQueue: !!(base.refreshQueue || incoming.refreshQueue),
    refreshDiscover: !!(base.refreshDiscover || incoming.refreshDiscover),
  };
}

export function _refreshDynamicRecommendations(reason = 'signal_rebuild') {
  if (!recToggle) return false;
  if (state.timelineMode === 'explicit') return false;
  if (state.playingAlbum || state.playingFavorites || state.playingPlaylist) return false;
  if (state.current != null && songs[state.current] && songs[state.current].hasEmbedding && state.queue.length > 0) {
    _acbs.softRefreshQueue();
    _activity('engine', 'queue_soft_refreshed_from_signal', `Queue soft-refreshed (${reason})`, {
      reason,
      queueLength: state.queue.length,
    }, { important: true, tags: ['queue', 'recommendation'] });
    return true;
  }
  _acbs.doRefresh(reason);
  return true;
}

export async function _performRecommendationRebuild(reason = 'signal_rebuild', opts = {}) {
  _emitRecommendationStatus({ phase: 'running', reason });
  setProfileVec(await buildProfileVec(reason));
  _invalidateForYouCache(reason);
  _invalidateUnexploredClustersCache(reason);
  const refreshQueueApplied = opts.refreshQueue === true ? _refreshDynamicRecommendations(reason) : false;
  _emitRecommendationStatus({
    phase: 'completed',
    reason,
    version: _recommendationPolicySnapshot.version,
    refreshQueueApplied,
    refreshDiscoverSuggested: opts.refreshDiscover === true,
  });
  return profileVec;
}

function _startRecommendationRebuildNow() {
  if (_recommendationRebuildInFlight) {
    setRecommendationRebuildPending(true);
    return;
  }
  const reason = _recommendationRebuildReason || 'signal_rebuild';
  const opts = { ..._recommendationRebuildOpts };
  setRecommendationRebuildReason(null);
  setRecommendationRebuildOpts({ refreshQueue: false, refreshDiscover: false });
  setRecommendationRebuildInFlight(true);
  Promise.resolve()
    .then(() => _performRecommendationRebuild(reason, opts))
    .catch(e => {
      console.error('Recommendation rebuild failed:', e);
      _emitRecommendationStatus({ phase: 'completed', reason });
    })
    .finally(() => {
      setRecommendationRebuildInFlight(false);
      if (_recommendationRebuildPending || _recommendationRebuildReason) {
        setRecommendationRebuildPending(false);
        _startRecommendationRebuildNow();
      }
    });
}

export function scheduleRecommendationRebuild(reason = 'signal_rebuild', opts = {}) {
  setRecommendationRebuildReason(reason);
  setRecommendationRebuildOpts(_mergeRecommendationRebuildOpts(_recommendationRebuildOpts, opts));
  _emitRecommendationStatus({ phase: 'queued', reason });
  if (_recommendationRebuildTimer) clearTimeout(_recommendationRebuildTimer);
  const delay = opts.immediate === true ? 0 : RECOMMENDATION_REBUILD_DEBOUNCE_MS;
  setRecommendationRebuildTimer(setTimeout(() => {
    setRecommendationRebuildTimer(null);
    _startRecommendationRebuildNow();
  }, delay));
}

// --- Recently Played ---

export async function loadRecentlyPlayed(limit = 20) {
  const events = await SessionLogger.loadRecentLogs(5);
  const seen = new Set();
  const recent = [];

  // Prepend current session history (most recent first) — always up to date
  for (let i = state.history.length - 1; i >= 0; i--) {
    const sid = state.history[i];
    if (sid != null && !seen.has(sid) && sid < songs.length) {
      seen.add(sid);
      recent.push({
        id: sid,
        title: songs[sid].title,
        artist: songs[sid].artist,
        album: songs[sid].album,
        hasEmbedding: songs[sid].hasEmbedding,
        artPath: songs[sid].artPath,
      });
      if (recent.length >= limit) break;
    }
  }

  // Then fill from persisted logs
  if (recent.length < limit) {
    for (let i = events.length - 1; i >= 0; i--) {
      const event = events[i];
      if (event.event === 'song_played') {
        const sid = event.song_id;
        if (sid != null && !seen.has(sid) && sid < songs.length) {
          seen.add(sid);
          recent.push({
            id: sid,
            title: songs[sid].title,
            artist: songs[sid].artist,
            album: songs[sid].album,
            hasEmbedding: songs[sid].hasEmbedding,
            artPath: songs[sid].artPath,
          });
          if (recent.length >= limit) break;
        }
      }
    }
  }

  return recent;
}

// --- Last Added ---

export function getLastAddedSongs(limit = 20) {
  return songs
    .filter(s => s.dateModified > 0 && s.filePath)
    .sort((a, b) => b.dateModified - a.dateModified)
    .slice(0, limit)
    .map(s => ({
      id: s.id,
      title: s.title,
      artist: s.artist,
      album: s.album,
      hasEmbedding: s.hasEmbedding,
      artPath: s.artPath,
    }));
}

// --- Profile Analysis ---

// Persistent For You cache. Regenerates only when:
//   - first call after cold start (cache empty)
//   - opts.refreshForYou === true (explicit user refresh)
//   - tickForYouListenWindow() hits FORYOU_LISTEN_WINDOW qualified listens
//   - resetSongProfileWeight or embeddings-ready explicitly invalidate

export async function analyzeProfile(mostPlayedLimit = 15, opts = {}) {
  // Use incremental profile summary instead of re-scanning all logs
  let summary = await SessionLogger.loadProfileSummary();
  if (Object.keys(summary.songs).length === 0 && summary.totalPlays === 0) {
    summary = await SessionLogger.rebuildProfileSummary();
  }

  const playCounts = {};
  const listenFractions = {};
  let totalPlays = summary.totalPlays || 0;
  let totalSkips = summary.totalSkips || 0;

  // v2 summary is keyed by filename — resolve to current song IDs
  const fnMap = _getFilenameMap();
  for (const [key, data] of Object.entries(summary.songs)) {
    const sid = fnMap[key.toLowerCase()];
    if (sid == null) continue;
    if (data.plays > 0) playCounts[sid] = data.plays;
    if (data.fracs && data.fracs.length > 0) listenFractions[sid] = [...data.fracs];
  }

  // Merge in-memory session data (may not be flushed to summary yet)
  for (const sid of state.history) {
    if (sid >= songs.length) continue;
    if (!playCounts[sid]) {
      playCounts[sid] = 1;
      totalPlays++;
    }
  }
  for (const entry of state.listened) {
    if (entry.id >= songs.length) continue;
    if (!listenFractions[entry.id]) listenFractions[entry.id] = [];
    const existing = listenFractions[entry.id];
    if (existing.length === 0 || existing[existing.length - 1] !== entry.listen_fraction) {
      listenFractions[entry.id].push(entry.listen_fraction);
    }
    if (!playCounts[entry.id]) {
      playCounts[entry.id] = 1;
      totalPlays++;
    }
  }

  const playedIds = new Set(Object.keys(playCounts).map(Number));
  for (const sid of state.history) {
    if (sid < songs.length) playedIds.add(sid);
  }

  const scored = [];
  for (const [sidStr, count] of Object.entries(playCounts)) {
    const sid = parseInt(sidStr);
    const fracs = listenFractions[sid] || [];
    const avgFrac = fracs.length > 0 ? fracs.reduce((a, b) => a + b, 0) / fracs.length : null;
    const score = avgFrac != null ? Math.round(count * avgFrac * 100) / 100 : 0;
    scored.push({ sid, count, avgFrac: avgFrac != null ? Math.round(avgFrac * 100) / 100 : null, score });
  }
  scored.sort((a, b) => b.score - a.score);

  const mostPlayed = scored.slice(0, mostPlayedLimit).map(s => ({
    id: s.sid,
    title: songs[s.sid].title,
    artist: songs[s.sid].artist,
    album: songs[s.sid].album,
    play_count: s.count,
    avg_listen: s.avgFrac,
    score: s.score,
    hasEmbedding: songs[s.sid].hasEmbedding,
    artPath: songs[s.sid].artPath,
  }));

  const neverIds = [];
  for (let i = 0; i < songs.length; i++) {
    if (!playedIds.has(i) && songs[i].filePath) neverIds.push(i);
  }
  for (let i = neverIds.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [neverIds[i], neverIds[j]] = [neverIds[j], neverIds[i]];
  }
  const neverPlayed = neverIds.slice(0, Math.min(mostPlayedLimit, neverIds.length)).map(sid => ({
    id: sid,
    title: songs[sid].title,
    artist: songs[sid].artist,
    album: songs[sid].album,
    hasEmbedding: songs[sid].hasEmbedding,
    artPath: songs[sid].artPath,
  }));

  // For You: cached unless opts.refreshForYou explicitly requests rebuild.
  // Reusing the cache across loadDiscover() triggers (album art, embedding
  // complete, tab re-activation) prevents the "constantly shuffling" feel.
  let forYou = [];
  if (opts.refreshForYou === true) _forYouCache = null;
  const wantForYouRefresh = opts.refreshForYou === true || _forYouCache == null;
  if (!wantForYouRefresh && _forYouCache) {
    // Validate cached entries still resolve to playable songs
    forYou = _forYouCache.filter(s => songs[s.id] && songs[s.id].filePath);
    if (forYou.length < _forYouCache.length * 0.7) {
      // Too many entries stale — force rebuild
      _forYouCache = null;
    } else {
      _forYouCache = forYou;
    }
  }
  if (wantForYouRefresh && profileVec && rec) {
    const heavilyPlayed = new Set(scored.slice(0, 30).map(s => s.sid));
    const excludeIdx = new Set();
    for (const sid of heavilyPlayed) {
      if (songs[sid].hasEmbedding) excludeIdx.add(songs[sid].embeddingIndex);
    }
    // Build a large candidate pool via raw nearest-neighbor (fast) and
    // random-sample 15 so each explicit refresh surfaces a visibly different
    // (but still high-similarity) slate. Bypass rec.recommend's MMR loop
    // because its O(topN^2 * pool) cost is prohibitive at pool=120 and we
    // don't need MMR diversity for a pool we're about to shuffle anyway.
    const recentShownSet = new Set(_forYouRecentShown);
    let rawPool = [];
    const nativePool = await _nativeRecommendFromQueryVec(profileVec, FORYOU_POOL_SIZE, excludeIdx);
    if (nativePool.length > 0) {
      rawPool = nativePool;
    } else if (typeof rec._findNearest === 'function') {
      const desired = FORYOU_POOL_SIZE + excludeIdx.size + 20;
      const { topIndices } = rec._findNearest(profileVec, desired);
      const rawItems = [];
      for (const embIdx of topIndices) {
        if (excludeIdx.has(embIdx)) continue;
        const sid = _fastEmbIdxToSongId(embIdx);
        if (sid == null || sid < 0) continue;
        const s = songs[sid];
        if (!s || !s.filePath) continue;
        rawItems.push({ id: sid, similarity: 0 });
        if (rawItems.length >= FORYOU_POOL_SIZE * 2) break;
      }
      rawPool = _applyRecommendationPolicyToSongItems(rawItems, { limit: FORYOU_POOL_SIZE });
    } else {
      rawPool = _recommendFromQueryVec(profileVec, FORYOU_POOL_SIZE, excludeIdx);
    }
    let filteredPool = rawPool.filter(item => !recentShownSet.has(item.id));
    // Fallback: if the recent-shown exclusion would starve the pool below the
    // target size, fall back to the full pool so we still return a full slate.
    if (filteredPool.length < FORYOU_SHOW_COUNT) filteredPool = rawPool.slice();
    const pool = filteredPool.slice();
    for (let i = pool.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [pool[i], pool[j]] = [pool[j], pool[i]];
    }
    const selected = pool.slice(0, FORYOU_SHOW_COUNT);
    forYou = selected.map(item => {
      const s = songs[item.id];
      if (!s || !s.filePath) return null;
      return {
        id: item.id,
        title: s.title,
        artist: s.artist,
        album: s.album,
        similarity: item.policySimilarity != null ? item.policySimilarity : item.similarity,
        hasEmbedding: true,
        artPath: s.artPath,
      };
    }).filter(Boolean);
    _forYouCache = forYou;
    _forYouRecentShown = forYou.map(s => s.id);
    _forYouAutoRefreshCounter = 0;
  }

  const playable = songs.filter(s => s.filePath);
  const unembeddedCount = playable.filter(s => !s.hasEmbedding).length;

  return {
    forYou,
    mostPlayed,
    neverPlayed,
    stats: {
      totalPlays,
      totalSkips,
      uniqueSongsPlayed: playedIds.size,
      totalSongs: playable.length,
      neverPlayedCount: neverIds.length,
      favoritesCount: favorites.size,
      unembeddedCount,
    },
  };
}

// --- "Because You Played" ---

export function getBecauseYouPlayed(limit = 3, songsPerSection = 6, opts = {}) {
  if (!rec || embeddings.length === 0) return [];
  const avoidSourceIds = new Set(opts.avoidSourceIds || []);
  const avoidRecIds = new Set(opts.avoidRecIds || []);

  // Gather candidate source songs from real playback only.
  const candidates = [];
  const seenIds = new Set();

  // Recently listened embedded songs from session
  for (let i = state.listened.length - 1; i >= 0; i--) {
    const s = state.listened[i];
    if (s.listen_fraction > 0.4 && songs[s.id] && songs[s.id].hasEmbedding && !seenIds.has(s.id)) {
      seenIds.add(s.id);
      candidates.push({ sid: s.id, embIdx: songs[s.id].embeddingIndex, source: 'session' });
    }
  }

  // Also pull from historical profile (most played songs from listened history)
  if (candidates.length < limit * 3) {
    for (const entry of state.listened) {
      const s = songs[entry.id];
      if (s && s.hasEmbedding && !seenIds.has(s.id) && entry.listen_fraction > 0.4) {
        seenIds.add(s.id);
        candidates.push({ sid: s.id, embIdx: s.embeddingIndex, source: 'history' });
      }
    }
    // Also from session history
    for (const sid of state.history) {
      const s = songs[sid];
      if (s && s.hasEmbedding && !seenIds.has(s.id)) {
        seenIds.add(s.id);
        candidates.push({ sid: s.id, embIdx: s.embeddingIndex, source: 'history' });
      }
    }
  }

  if (candidates.length === 0) return [];

  const filteredCandidates = candidates.filter(c => {
    const policyRow = _getRecommendationPolicyRow(c.sid);
    return !(policyRow && policyRow.isHardRecommendationBlock);
  });
  candidates.length = 0;
  candidates.push(...filteredCandidates);
  if (candidates.length === 0) return [];

  // Shuffle candidates so each refresh gives different sources
  for (let i = candidates.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [candidates[i], candidates[j]] = [candidates[j], candidates[i]];
  }

  if (avoidSourceIds.size > 0) {
    candidates.sort((a, b) => {
      const aAvoid = avoidSourceIds.has(a.sid) ? 1 : 0;
      const bAvoid = avoidSourceIds.has(b.sid) ? 1 : 0;
      return aAvoid - bAvoid;
    });
  }

  // Select sources that are diverse in mood (maximize embedding distance between sources)
  const sources = [candidates[0]];
  const usedEmbIdxs = new Set([candidates[0].embIdx]);

  for (let pick = 1; pick < limit && pick < candidates.length; pick++) {
    let bestCandidate = null;
    let bestMinDist = -1;

    // From the remaining candidates, pick the one most distant from all already-selected sources
    for (const c of candidates) {
      if (usedEmbIdxs.has(c.embIdx)) continue;
      let minDist = Infinity;
      for (const src of sources) {
        // Cosine similarity via dot product (embeddings are L2-normalized)
        const vec1 = embeddings[c.embIdx];
        const vec2 = embeddings[src.embIdx];
        let dot = 0;
        for (let d = 0; d < vec1.length; d++) dot += vec1[d] * vec2[d];
        const dist = 1 - dot; // cosine distance
        if (dist < minDist) minDist = dist;
      }
      if (minDist > bestMinDist) {
        bestMinDist = minDist;
        bestCandidate = c;
      }
    }
    if (bestCandidate) {
      sources.push(bestCandidate);
      usedEmbIdxs.add(bestCandidate.embIdx);
    }
  }

  // Generate recommendations for each source, excluding cross-section overlap
  const allExclude = new Set(sources.map(s => s.embIdx));
  const sections = [];

  for (const src of sources) {
    const exclude = new Set(allExclude);
    for (const sec of sections) {
      for (const r of sec.recommendations) {
        if (songs[r.id] && songs[r.id].hasEmbedding) exclude.add(songs[r.id].embeddingIndex);
      }
    }
    for (const sid of avoidRecIds) {
      if (songs[sid] && songs[sid].hasEmbedding) exclude.add(songs[sid].embeddingIndex);
    }

    const recs = _recommendFromSourceEmb(src.embIdx, songsPerSection, exclude);
    sections.push({
      sourceId: src.sid,
      sourceTitle: songs[src.sid].title,
      sourceArtist: songs[src.sid].artist,
      recommendations: recs
        .map(item => {
          const s = songs[item.id];
          if (!s || !s.filePath) return null;
          return {
            id: item.id,
            title: s.title,
            artist: s.artist,
            similarity: item.policySimilarity != null ? item.policySimilarity : item.similarity,
            artPath: s.artPath,
          };
        })
        .filter(Boolean),
    });
  }

  return sections;
}

// --- Unexplored Clusters ---
// Runs k-means on embeddings, scores each cluster by how much the user has
// engaged with it (plays × listen fraction), then returns songs from the
// lowest-scored clusters so the user can explore unfamiliar sonic territory.

function _embIdxToSongId(embIdx) {
  for (const s of songs) {
    if (s.embeddingIndex === embIdx) return s.id;
  }
  return -1;
}

export async function getUnexploredClusters(numClusters = 3, songsPerCluster = 6, opts = {}) {
  if (!rec || embeddings.length < 15) return [];
  const forceRefresh = opts.refresh === true;
  const avoidSongIds = new Set(opts.avoidSongIds || []);
  if (!forceRefresh && _unexploredClustersCache) return _unexploredClustersCache;

  const K = 15;
  let labels;
  try {
    // computeClustersAsync uses WebGPU when available (5-20× faster on the
    // assignment step) and falls back transparently to the CPU implementation.
    labels = await rec.computeClustersAsync(K, 30);
  } catch (e) {
    return [];
  }

  // Score each cluster by engagement (played songs weighted by listen fraction).
  const scores = new Array(K).fill(0);
  const counts = new Array(K).fill(0);
  for (let i = 0; i < embeddings.length; i++) counts[labels[i]]++;

  for (const entry of state.listened) {
    const s = songs[entry.id];
    if (!s || !s.hasEmbedding) continue;
    const embIdx = s.embeddingIndex;
    if (embIdx == null || embIdx >= labels.length) continue;
    const c = labels[embIdx];
    const plays = entry.plays || 0;
    const avgFrac = entry.encounters > 0 ? (entry.totalFraction || 0) / entry.encounters : 0;
    scores[c] += plays * avgFrac;
  }

  const summary = await SessionLogger.loadProfileSummary();
  const songEntries = summary.songs || {};
  const fnMap = _getFilenameMap();
  for (const [key, data] of Object.entries(songEntries)) {
    const sid = fnMap[key.toLowerCase()];
    if (sid == null || !songs[sid] || !songs[sid].hasEmbedding) continue;
    const embIdx = songs[sid].embeddingIndex;
    if (embIdx == null || embIdx >= labels.length) continue;
    const c = labels[embIdx];
    const listenStats = _getSummaryListenStats(data);
    if (!listenStats.hasRecordedFractions) continue;
    scores[c] += listenStats.plays * listenStats.avgFracRecorded;
  }

  // Rank clusters by lowest score per song (so tiny clusters aren't unfairly favored).
  const ranked = [];
  for (let c = 0; c < K; c++) {
    if (counts[c] < songsPerCluster + 1) continue;
    const perSong = scores[c] / counts[c];
    ranked.push({ cluster: c, score: perSong, size: counts[c] });
  }
  ranked.sort((a, b) => a.score - b.score);

  const playedIds = new Set(state.listened.filter(e => (e.plays || 0) > 0).map(e => e.id));
  for (const [key, data] of Object.entries(songEntries)) {
    if ((data.plays || 0) <= 0) continue;
    const sid = fnMap[key.toLowerCase()];
    if (sid != null) playedIds.add(sid);
  }
  const sections = [];
  for (const r of ranked) {
    if (sections.length >= numClusters) break;
    const clusterCandidates = [];
    for (let i = 0; i < embeddings.length; i++) {
      if (labels[i] !== r.cluster) continue;
      const sid = _embIdxToSongId(i);
      if (sid == null) continue;
      const s = songs[sid];
      if (!s || !s.filePath) continue;
      if (playedIds.has(sid)) continue;
      if (avoidSongIds.has(sid)) continue;
      clusterCandidates.push({
        id: sid,
        title: s.title,
        artist: s.artist,
        album: s.album,
        hasEmbedding: true,
        artPath: s.artPath,
        similarity: 0,
      });
    }
    const picks = _applyRecommendationPolicyToSongItems(clusterCandidates, { limit: Math.max(songsPerCluster * 2, songsPerCluster) })
      .map(item => ({
        id: item.id,
        title: item.title,
        artist: item.artist,
        album: item.album,
        hasEmbedding: item.hasEmbedding,
        artPath: item.artPath,
      }));
    if (picks.length >= Math.min(3, songsPerCluster)) {
      const topPicks = picks.slice(0, Math.max(songsPerCluster, Math.min(picks.length, songsPerCluster * 2)));
      // Shuffle the best-fitting candidates for some variety without reintroducing blocked songs.
      for (let i = topPicks.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [topPicks[i], topPicks[j]] = [topPicks[j], topPicks[i]];
      }
      sections.push({
        clusterId: r.cluster,
        score: r.score,
        songs: topPicks.slice(0, songsPerCluster),
      });
    }
  }

  if (!forceRefresh) _unexploredClustersCache = sections;
  return sections;
}

// --- Discover Page Cache ---
// Saves a snapshot of all discover page sections + queue state so next startup renders instantly.

let _discoverCacheDirty = false;
let _discoverCacheSaveTimer = null;
let _discoverCachePendingSnapshot = null;

export async function saveDiscoverCache(snapshot = null) {
  try {
    const snap = snapshot && typeof snapshot === 'object' ? snapshot : null;
    const hasSnapshotKey = (key) => !!(snap && Object.prototype.hasOwnProperty.call(snap, key));
    const profile = hasSnapshotKey('profile') ? snap.profile : await analyzeProfile();
    const becauseYouPlayed = hasSnapshotKey('becauseYouPlayed') ? snap.becauseYouPlayed : getBecauseYouPlayed(3, 6);
    const unexplored = hasSnapshotKey('unexplored') ? snap.unexplored : await getUnexploredClusters(3, 8);
    const recentlyPlayed = hasSnapshotKey('recentlyPlayed') ? snap.recentlyPlayed : await loadRecentlyPlayed(20);
    const lastAdded = hasSnapshotKey('lastAdded') ? snap.lastAdded : getLastAddedSongs(20);
    const favs = hasSnapshotKey('favorites') ? snap.favorites : _acbs.getFavoritesList();
    const st = hasSnapshotKey('state') ? (snap.state || {}) : _acbs.getState();

    const cache = {
      profile: profile || null,
      becauseYouPlayed: becauseYouPlayed || [],
      unexplored: unexplored || [],
      recentlyPlayed: recentlyPlayed || [],
      lastAdded: lastAdded || [],
      favorites: favs || [],
      queue: st.queue || [],
      history: st.history || [],
      historyPos: st.historyPos,
      current: st.current,
      sessionLabel: st.sessionLabel,
      recToggle: st.recToggle,
      modeIndicator: st.modeIndicator,
      recommendationFingerprint: _recommendationPolicySnapshot.fingerprint || '',
      savedAt: Date.now(),
    };

    await Preferences.set({ key: 'discover_cache', value: JSON.stringify(cache) });
    _discoverCacheDirty = false;
    console.log('[CACHE] Discover cache saved');
  } catch (e) {
    console.error('[CACHE] Failed to save discover cache:', e);
  }
}

// Debounced save — prevents hammering Preferences on rapid song changes
export function saveDiscoverCacheDebounced(snapshot = null) {
  _discoverCacheDirty = true;
  _discoverCachePendingSnapshot = snapshot && typeof snapshot === 'object' ? snapshot : null;
  if (_discoverCacheSaveTimer) clearTimeout(_discoverCacheSaveTimer);
  _discoverCacheSaveTimer = setTimeout(() => {
    const pendingSnapshot = _discoverCachePendingSnapshot;
    _discoverCachePendingSnapshot = null;
    saveDiscoverCache(pendingSnapshot).catch(() => {});
  }, 2000);
}

export async function loadDiscoverCache() {
  try {
    const { value } = await Preferences.get({ key: 'discover_cache' });
    if (!value) return null;
    const cache = JSON.parse(value);
    if (!cache || !cache.savedAt) return null;
    console.log('[CACHE] Discover cache loaded, age:', Math.round((Date.now() - cache.savedAt) / 1000), 's');
    return cache;
  } catch (e) {
    console.error('[CACHE] Failed to load discover cache:', e);
    return null;
  }
}

// Validate cached sections against current song library — returns which sections are stale
export function validateDiscoverCache(cache) {
  if (!cache) return { allStale: true };
  const songIds = new Set(songs.map(s => s.id));
  const recommendationStale = !!(
    cache.recommendationFingerprint
    && _recommendationPolicySnapshot.fingerprint
    && cache.recommendationFingerprint !== _recommendationPolicySnapshot.fingerprint
  );
  const forYouUnexpectedlyEmpty = !!(
    profileVec
    && rec
    && cache.profile
    && Array.isArray(cache.profile.forYou)
    && cache.profile.forYou.length === 0
  );

  function hasStaleSongs(items) {
    if (!items || items.length === 0) return false;
    return items.some(item => !songIds.has(item.id));
  }

  function hasStaleSongsInBecause(sections) {
    if (!sections || sections.length === 0) return false;
    return sections.some(sec =>
      hasStaleSongs(sec.recommendations) || !songIds.has(sec.sourceId)
    );
  }

  function hasStaleSongsInSections(sections) {
    if (!sections || sections.length === 0) return false;
    return sections.some(sec => hasStaleSongs(sec.songs));
  }

  return {
    profileStale: recommendationStale || (cache.profile && hasStaleSongs(cache.profile.mostPlayed)),
    forYouStale: recommendationStale || forYouUnexpectedlyEmpty || (cache.profile && hasStaleSongs(cache.profile.forYou)),
    becauseYouPlayedStale: recommendationStale || hasStaleSongsInBecause(cache.becauseYouPlayed),
    unexploredStale: recommendationStale || hasStaleSongsInSections(cache.unexplored),
    recentlyPlayedStale: hasStaleSongs(cache.recentlyPlayed),
    lastAddedStale: hasStaleSongs(cache.lastAdded),
    favoritesStale: hasStaleSongs(cache.favorites),
    queueStale: recommendationStale || hasStaleSongs(cache.queue),
    recommendationStale,
    allStale: false,
  };
}
