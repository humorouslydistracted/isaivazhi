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

const TOP_N = 50;
const FROZEN_ZONE = 5;    // top 5 never change during soft refresh
const STABLE_ZONE = 25;   // positions 6-25: reorder only, no additions/removals
const FAVORITE_PRIOR_BASE = 2.0;
const DISLIKE_PRIOR_BASE = 3.0;
const MANUAL_PRIOR_HALF_LIFE_PLAYS = 2;
let EXTERNAL_DATA_DIR = '/storage/emulated/0/MusicPlayerData'; // overridden at startup

let songs = [];           // ALL songs (embedded + scanned)
let embeddings = [];      // Float32Array[] — only embedded songs
let embeddingMap = {};    // filename -> embedding index
let albumList = {};
let albumArray = [];
let rec = null;
let log = null;
let favorites = new Set();
let playlists = [];
let profileVec = null;
let scanCallbacks = [];
let scanComplete = false;
let recToggle = true;     // true = AI recs, false = shuffle
let queueShuffleEnabled = false;
const PLAYLISTS_PREF_KEY = 'playlists_v1';
const PENDING_LISTEN_KEY = 'pending_listen_v1';

// Tunable recommendation knobs — persisted in Preferences as 'tuning_params'.
// adventurous      : 0..1, maps directly to Recommender.lam (MMR diversity λ)
// sessionBias      : 0..1, scales wSession/wProfile in _blendWeights
// negativeStrength : 0..1, scales β subtracted in buildProfileVec (max 0.7)
const TUNING_DEFAULTS = { adventurous: 0.8, sessionBias: 0.5, negativeStrength: 0.5 };
let _tuning = { ...TUNING_DEFAULTS };
const SIMILARITY_BOOST_KEY = 'similarity_boost_scores_v1';
const SIMILARITY_BOOST_MAX = 4;
const SIMILARITY_NEIGHBOR_COUNT = 10;
const SIMILARITY_NEIGHBOR_WEIGHTS = Object.freeze([0.10, 0.09, 0.08, 0.07, 0.06, 0.05, 0.04, 0.03, 0.02, 0.01]);
const RECOMMENDATION_REBUILD_DEBOUNCE_MS = 220;
const RECOMMENDATION_NEGATIVE_HARD_EXCLUDE_SHARE = 0.18;
const RECOMMENDATION_NEGATIVE_HARD_EXCLUDE_FLOOR = 1.5;
const RECOMMENDATION_POSITIVE_BONUS_PER_POINT = 0.03;
const RECOMMENDATION_POSITIVE_BONUS_MAX = 0.10;
const RECOMMENDATION_NEGATIVE_PENALTY_PER_POINT = 0.05;
const RECOMMENDATION_NEGATIVE_PENALTY_MAX = 0.24;
const RECOMMENDATION_POOL_MULTIPLIER = 4;
const RECOMMENDATION_POOL_PADDING = 40;
let similarityBoostScores = {}; // { filename: number }
let _recommendationPolicySnapshot = {
  rowsById: new Map(),
  hardExcludeSongIds: new Set(),
  fingerprint: '',
  version: 0,
  updatedAt: 0,
};
let _recommendationStatusCbs = [];
let _recommendationRebuildTimer = null;
let _recommendationRebuildInFlight = false;
let _recommendationRebuildPending = false;
let _recommendationRebuildReason = null;
let _recommendationRebuildOpts = { refreshQueue: false, refreshDiscover: false };
async function _loadTuning() {
  try {
    const { value } = await Preferences.get({ key: 'tuning_params' });
    if (value) {
      const p = JSON.parse(value);
      _tuning = { ...TUNING_DEFAULTS, ...p };
    }
  } catch (e) {}
}
async function _saveTuning() {
  try { await Preferences.set({ key: 'tuning_params', value: JSON.stringify(_tuning) }); } catch (e) {}
}
function getTuning() { return { ..._tuning }; }
async function setTuning(partial) {
  _tuning = { ..._tuning, ...partial };
  // Clamp to [0,1]
  for (const k of Object.keys(_tuning)) _tuning[k] = Math.max(0, Math.min(1, _tuning[k]));
  await _saveTuning();
  if (rec) rec.lam = _tuning.adventurous;
  // Negative β changed -> rebuild profile vector
  profileVec = await buildProfileVec('tuning_changed');
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
// --- Negative signal state ---
// Linear accumulation keyed by filename: every X-from-queue bumps +0.5,
// user-driven skips can add smaller graduated bumps, and every genuine long
// listen decays -0.5. Floor 0, ceiling 10.
// Separate from `disliked` flag which is a persistent boolean on the song.
let negativeScores = {}; // { filename: number }
const NEG_X_DELTA = 0.5;
const NEG_LISTEN_DECAY = 0.5;
const NEG_SCORE_MAX = 10;
const USER_SKIP_NEGATIVE_STEP = 0.1;
// Guard for duplicate classification within a single playback instance.
// This is intentionally keyed to playback starts, not just song ID, so
// replaying the same song later in the session can still be classified.
let _currentPlaybackInstanceId = 0;
let _capturedPlaybackInstanceId = null;
let _lastLoggedPlaybackStartInstanceId = 0;
let _lastLoggedPlaybackStartSongId = null;
let dislikedFilenames = new Set(); // authoritative dislike set (persisted)
const TASTE_REVIEW_IGNORE_KEY = 'taste_review_ignores_v1';
let _tasteReviewIgnores = null;
let _lastTasteReviewSnapshot = '';
const TASTE_REVIEW_REASON_META = {
  no_result: {
    label: 'No Result',
    tone: 'danger',
    description: 'Playback starts exist, but no skip/completion result was saved.',
  },
  x_only: {
    label: 'X Only',
    tone: 'danger',
    description: 'Negative pull is coming mostly from X-score, not listen evidence.',
  },
  mismatch: {
    label: 'Mismatch',
    tone: 'danger',
    description: 'Signal direction conflicts with the recorded listen evidence.',
  },
  reset_pending: {
    label: 'Reset Pending',
    tone: 'warn',
    description: 'This song was reset recently and is waiting for fresh evidence.',
  },
  uncertain: {
    label: 'Uncertain',
    tone: 'warn',
    description: 'Evidence is still too incomplete to trust the signal.',
  },
};

async function _loadNegativeScores() {
  try {
    const { value } = await Preferences.get({ key: 'negative_scores' });
    if (value) negativeScores = JSON.parse(value) || {};
  } catch (e) { negativeScores = {}; }
}
async function _saveNegativeScores() {
  try { await Preferences.set({ key: 'negative_scores', value: JSON.stringify(negativeScores) }); } catch (e) {}
}
async function _loadSimilarityBoostScores() {
  try {
    const { value } = await Preferences.get({ key: SIMILARITY_BOOST_KEY });
    if (value) similarityBoostScores = JSON.parse(value) || {};
  } catch (e) { similarityBoostScores = {}; }
}
async function _saveSimilarityBoostScores() {
  try { await Preferences.set({ key: SIMILARITY_BOOST_KEY, value: JSON.stringify(similarityBoostScores) }); } catch (e) {}
}
async function _loadDislikes() {
  try {
    const { value } = await Preferences.get({ key: 'disliked_songs' });
    if (value) {
      const arr = JSON.parse(value);
      if (Array.isArray(arr)) dislikedFilenames = new Set(arr);
    }
  } catch (e) { dislikedFilenames = new Set(); }
}
async function _saveDislikes() {
  try {
    await Preferences.set({ key: 'disliked_songs', value: JSON.stringify([...dislikedFilenames]) });
  } catch (e) {}
}
async function _loadTasteReviewIgnores() {
  if (_tasteReviewIgnores) return _tasteReviewIgnores;
  try {
    const { value } = await Preferences.get({ key: TASTE_REVIEW_IGNORE_KEY });
    const parsed = value ? JSON.parse(value) : {};
    _tasteReviewIgnores = parsed && typeof parsed === 'object' ? parsed : {};
  } catch (e) {
    _tasteReviewIgnores = {};
  }
  return _tasteReviewIgnores;
}
async function _saveTasteReviewIgnores() {
  if (!_tasteReviewIgnores) _tasteReviewIgnores = {};
  try {
    await Preferences.set({ key: TASTE_REVIEW_IGNORE_KEY, value: JSON.stringify(_tasteReviewIgnores) });
  } catch (e) {}
}
function _applyDislikeFlags() {
  for (const s of songs) {
    if (s && s.filename) s.disliked = dislikedFilenames.has(s.filename);
  }
}
function _getSimilarityBoost(songId) {
  const s = songs[songId];
  if (!s || !s.filename) return 0;
  return Number(similarityBoostScores[s.filename]) || 0;
}
function _setSimilarityBoostScore(songId, value) {
  const s = songs[songId];
  if (!s || !s.filename) return 0;
  const next = Math.max(-SIMILARITY_BOOST_MAX, Math.min(SIMILARITY_BOOST_MAX, Number(value) || 0));
  if (Math.abs(next) < 0.001) delete similarityBoostScores[s.filename];
  else similarityBoostScores[s.filename] = Math.round(next * 1000) / 1000;
  return similarityBoostScores[s.filename] || 0;
}
function _emitRecommendationStatus(payload = {}) {
  const state = {
    phase: payload.phase || 'idle',
    reason: payload.reason || null,
    version: payload.version != null ? payload.version : _recommendationPolicySnapshot.version,
    refreshQueueApplied: !!payload.refreshQueueApplied,
    refreshDiscoverSuggested: !!payload.refreshDiscoverSuggested,
    updatedAt: Date.now(),
  };
  for (const cb of _recommendationStatusCbs) {
    try { cb(state); } catch (e) { /* ignore */ }
  }
}
function onRecommendationRebuildStatus(cb) {
  if (typeof cb !== 'function') return;
  _recommendationStatusCbs.push(cb);
}
function _bumpNegativeScore(songId, delta) {
  const s = songs[songId];
  if (!s || !s.filename) return;
  const cur = negativeScores[s.filename] || 0;
  const next = Math.max(0, Math.min(NEG_SCORE_MAX, cur + delta));
  if (next === 0) delete negativeScores[s.filename];
  else negativeScores[s.filename] = next;
  _saveNegativeScores().catch(() => {});
  _activity('taste', delta >= 0 ? 'negative_score_bumped' : 'negative_score_decayed',
    `${delta >= 0 ? 'Negative score increased' : 'Negative score decayed'} for "${s.title}"`, {
      ..._songRef(songId),
      delta,
      before: cur,
      after: next,
    }, { important: true, tags: ['negative'] });
}

let embeddingInProgress = false;
let embeddingQueue = [];        // songs waiting to be embedded
let localEmbeddings = {};       // filename -> { embedding, contentHash }
let embeddingLog = [];          // chronological log of embedding events
let embeddingCurrentFile = '';  // currently processing filename
let embeddingTotalCount = 0;    // native-reported total items in the active batch
let embeddingProcessedCount = 0; // native-reported completed items in the active batch
let embeddingReportedFailedCount = 0; // native-reported failed items in the active batch
let embeddingFailedPaths = [];  // paths that failed during last run (for retry)
let embeddingStartTime = null;  // when embedding batch started
let embeddingPausedByUser = false; // user explicitly stopped — don't auto-restart
let unmatchedEmbeddings = [];     // Colab embeddings that didn't match any device song
let librarySavedAt = 0;           // timestamp from song_library.json for smart scan skip
const SCAN_SKIP_WINDOW_MS = 6 * 60 * 60 * 1000; // 6 hours — skip full scan if library cache is fresher
let _lastProfileWeightSnapshot = { positive: new Map(), negative: new Map() };

function _songRef(songId) {
  const s = songs[songId];
  if (!s) return {};
  return { songId: s.id, title: s.title, artist: s.artist, album: s.album, filename: s.filename };
}

function _activity(category, type, message, data = {}, opts = {}) {
  try {
    logActivity({
      category,
      type,
      message,
      data,
      tags: opts.tags || [],
      important: opts.important !== false,
      level: opts.level || 'info',
    });
  } catch (e) { /* ignore */ }
}

function _copySessionEntry(entry) {
  if (!entry) return null;
  return {
    id: entry.id,
    listen_fraction: entry.listen_fraction,
    encounters: entry.encounters || 0,
    totalFraction: entry.totalFraction || 0,
    skips: entry.skips || 0,
    plays: entry.plays || 0,
    source: entry.source || null,
  };
}

function _snapshotSessionEntry(songId) {
  return _copySessionEntry(state.listened.find(e => e.id === songId) || null);
}

function _formatSessionEntryDelta(before, after) {
  const b = before || { plays: 0, skips: 0, encounters: 0, listen_fraction: 0 };
  const a = after || { plays: 0, skips: 0, encounters: 0, listen_fraction: 0 };
  return {
    before: b,
    after: a,
    deltaPlays: (a.plays || 0) - (b.plays || 0),
    deltaSkips: (a.skips || 0) - (b.skips || 0),
    deltaEncounters: (a.encounters || 0) - (b.encounters || 0),
    beforeFrac: Math.round((b.listen_fraction || 0) * 100) / 100,
    afterFrac: Math.round((a.listen_fraction || 0) * 100) / 100,
  };
}

const MAX_RECENT_PLAYBACK_SIGNALS = 60;
let _recentPlaybackSignalEvents = [];
const PROFILE_DAY_MS = 86400000;
const PROFILE_HALF_LIFE_DAYS = 30;
const REVIEW_RESET_PENDING_WINDOW_MS = 24 * 60 * 60 * 1000;

function _roundSignal(value) {
  return value == null ? null : Math.round(value * 100) / 100;
}

function _signalWeightFromFraction(fraction) {
  if (fraction == null || Number.isNaN(Number(fraction))) return null;
  const frac = Math.max(0, Math.min(1, Number(fraction)));
  return frac * frac;
}

function _summarizeSessionSignalEntry(entry) {
  if (!entry) return null;
  const listenFraction = _roundSignal(entry.listen_fraction || 0);
  return {
    listenFraction,
    weight: _roundSignal(_signalWeightFromFraction(listenFraction)),
    encounters: Math.max(0, Number(entry.encounters) || 0),
    skips: Math.max(0, Number(entry.skips) || 0),
    plays: Math.max(0, Number(entry.plays) || 0),
  };
}

function _summarizeProfileSummaryEntry(entry) {
  if (!entry) {
    return { plays: 0, skips: 0, completions: 0, fracsCount: 0, avgFrac: null };
  }
  const fracs = Array.isArray(entry.fracs)
    ? entry.fracs.filter(v => typeof v === 'number' && !Number.isNaN(v))
    : [];
  return {
    plays: Math.max(0, Number(entry.plays) || 0),
    skips: Math.max(0, Number(entry.skips) || 0),
    completions: Math.max(0, Number(entry.completions) || 0),
    fracsCount: fracs.length,
    avgFrac: fracs.length > 0 ? _roundSignal(fracs.reduce((a, b) => a + b, 0) / fracs.length) : null,
  };
}

function _cloneProfileSummaryEntry(entry) {
  if (!entry) return null;
  return {
    plays: Math.max(0, Number(entry.plays) || 0),
    skips: Math.max(0, Number(entry.skips) || 0),
    completions: Math.max(0, Number(entry.completions) || 0),
    fracs: Array.isArray(entry.fracs) ? entry.fracs.slice() : [],
    lastPlayedAt: entry.lastPlayedAt || null,
  };
}

function _previewProfileSummaryEntryEvent(entry, eventType, fraction, playedAt = new Date().toISOString()) {
  const next = _cloneProfileSummaryEntry(entry) || {
    plays: 0,
    skips: 0,
    completions: 0,
    fracs: [],
    lastPlayedAt: null,
  };
  if (eventType === 'played') {
    next.plays += 1;
    next.lastPlayedAt = playedAt;
  } else if (eventType === 'skipped') {
    next.skips += 1;
    if (fraction != null) {
      next.fracs.push(fraction);
      if (next.fracs.length > 10) next.fracs.shift();
    }
  } else if (eventType === 'completed') {
    next.completions += 1;
    if (fraction != null) {
      next.fracs.push(fraction);
      if (next.fracs.length > 10) next.fracs.shift();
    }
  }
  return next;
}

function _rememberLoggedPlaybackStart(songId, playbackInstanceId = 0) {
  _lastLoggedPlaybackStartSongId = songId != null ? songId : null;
  _lastLoggedPlaybackStartInstanceId = Math.max(0, Math.round(Number(playbackInstanceId) || 0));
}

function bindCurrentPlaybackStartInstance(playbackInstanceId, songId = state.current) {
  const resolvedPlaybackInstanceId = Math.max(0, Math.round(Number(playbackInstanceId) || 0));
  if (!(resolvedPlaybackInstanceId > 0) || songId == null) return false;
  if (_lastLoggedPlaybackStartInstanceId === resolvedPlaybackInstanceId && _lastLoggedPlaybackStartSongId === songId) {
    return false;
  }
  if (_lastLoggedPlaybackStartSongId === songId && !(_lastLoggedPlaybackStartInstanceId > 0)) {
    _rememberLoggedPlaybackStart(songId, resolvedPlaybackInstanceId);
    return true;
  }
  return false;
}

function _ensurePlaybackStartLogged(songId, source, prevSongInfo = null, prevFraction = null, opts = {}) {
  const song = songs[songId];
  if (!song || !log || typeof log.songPlayed !== 'function') {
    return { logged: false, summaryEntry: null, deduped: false };
  }
  const playbackInstanceId = Math.max(0, Math.round(Number(opts.playbackInstanceId) || 0));
  const summaryBefore = log && typeof log.peekProfileSummaryEntry === 'function'
    ? log.peekProfileSummaryEntry(song.filename)
    : null;
  if (!opts.force && playbackInstanceId > 0
      && _lastLoggedPlaybackStartInstanceId === playbackInstanceId
      && _lastLoggedPlaybackStartSongId === songId) {
    return { logged: false, summaryEntry: summaryBefore, deduped: true };
  }
  log.songPlayed(song, source || state.currentSource || 'playing', prevSongInfo, prevFraction);
  _rememberLoggedPlaybackStart(songId, playbackInstanceId);
  return {
    logged: true,
    summaryEntry: _previewProfileSummaryEntryEvent(summaryBefore, 'played', null),
    deduped: false,
  };
}

function _tasteDirectionFromScore(score) {
  const numeric = Number(score) || 0;
  if (numeric > 0) return 'positive';
  if (numeric < 0) return 'negative';
  return 'neutral';
}

function _normalizeTasteScoreSnapshot(snapshot) {
  if (!snapshot || typeof snapshot !== 'object') return null;
  const score = _roundSignal(Number(snapshot.score) || 0);
  const directScore = _roundSignal(snapshot.directScore != null ? Number(snapshot.directScore) || 0 : score);
  const similarityBoost = _roundSignal(Number(snapshot.similarityBoost) || 0);
  return {
    direction: snapshot.direction || _tasteDirectionFromScore(score),
    score,
    directScore,
    similarityBoost,
    sourceKind: snapshot.sourceKind || _tasteDirectionFromScore(score),
    verified: !!snapshot.verified,
  };
}

function _recencyMultiplierFromLastPlayed(lastPlayedAt) {
  if (!lastPlayedAt) return 1;
  const lastMs = Date.parse(lastPlayedAt);
  if (!Number.isFinite(lastMs)) return 1;
  const daysSince = Math.max(0, (Date.now() - lastMs) / PROFILE_DAY_MS);
  return Math.pow(0.5, daysSince / PROFILE_HALF_LIFE_DAYS);
}

function _manualPriorWeight(base, playCount) {
  const plays = Math.max(0, Number(playCount) || 0);
  if (!(base > 0)) return 0;
  const weight = base * Math.pow(0.5, plays / MANUAL_PRIOR_HALF_LIFE_PLAYS);
  return weight >= 0.05 ? weight : 0;
}

function _computeTasteContributions(songId, entry, opts = {}) {
  const s = songs[songId];
  if (!s) {
    return {
      plays: 0,
      fracs: [],
      avgFracRecorded: null,
      hasListenEvidence: false,
      recencyMult: 1,
      xScore: 0,
      isFavorite: false,
      isDisliked: false,
      favoritePrior: 0,
      dislikePrior: 0,
      positiveListenWeight: 0,
      negativeListenWeight: 0,
      positiveWeight: 0,
      negativeWeight: 0,
      similarityBoost: 0,
      effectivePositiveWeight: 0,
      effectiveNegativeWeight: 0,
      directScore: 0,
      score: 0,
      sourceKind: 'neutral',
      verified: false,
    };
  }

  const listenStats = _getSummaryListenStats(entry);
  const plays = listenStats.plays;
  const avgFracRecorded = listenStats.avgFracRecorded;
  const hasListenEvidence = listenStats.hasRecordedFractions;
  const avgFrac = avgFracRecorded != null ? avgFracRecorded : null;
  const xScore = opts.xScore != null ? (Number(opts.xScore) || 0) : (negativeScores[s.filename] || 0);
  const isFavorite = opts.isFavorite != null ? !!opts.isFavorite : favorites.has(songId);
  const isDisliked = opts.isDisliked != null ? !!opts.isDisliked : !!s.disliked;
  const similarityBoost = opts.similarityBoost != null ? (Number(opts.similarityBoost) || 0) : _getSimilarityBoost(songId);
  const recencyMult = _recencyMultiplierFromLastPlayed(entry && entry.lastPlayedAt ? entry.lastPlayedAt : null);

  const favoritePrior = isFavorite ? _manualPriorWeight(FAVORITE_PRIOR_BASE, plays) : 0;
  const dislikePrior = isDisliked ? _manualPriorWeight(DISLIKE_PRIOR_BASE, plays) : 0;
  const positiveListenWeight = hasListenEvidence && plays > 0 && avgFrac != null
    ? plays * avgFrac * recencyMult
    : 0;

  let negativeListenWeight = 0;
  if (hasListenEvidence && plays > 0 && avgFrac != null) {
    const skipWeight = plays * (1 - avgFrac) * recencyMult;
    if (plays >= NEGATIVE_PLAY_THRESHOLD && avgFrac < NEGATIVE_FRAC_THRESHOLD) {
      negativeListenWeight = skipWeight;
    } else if (xScore > 0) {
      negativeListenWeight = skipWeight * 0.5;
    }
  }

  const positiveWeight = positiveListenWeight + favoritePrior;
  const negativeWeight = Math.max(0, xScore) + negativeListenWeight + dislikePrior;
  const directScore = positiveWeight - negativeWeight;
  const effectivePositiveWeight = positiveWeight + Math.max(0, similarityBoost);
  const effectiveNegativeWeight = negativeWeight + Math.max(0, -similarityBoost);
  const score = directScore + similarityBoost;

  let sourceKind = 'neutral';
  if (effectivePositiveWeight > 0 && effectiveNegativeWeight > 0) {
    if (score > 0) sourceKind = 'mixed_positive';
    else if (score < 0) sourceKind = 'mixed_negative';
    else sourceKind = 'mixed';
  } else if (effectivePositiveWeight > 0) {
    sourceKind = 'positive';
  } else if (effectiveNegativeWeight > 0) {
    sourceKind = 'negative';
  }

  return {
    plays,
    fracs: listenStats.fracs,
    avgFracRecorded,
    hasListenEvidence,
    recencyMult,
    xScore,
    isFavorite,
    isDisliked,
    favoritePrior,
    dislikePrior,
    positiveListenWeight,
    negativeListenWeight,
    positiveWeight,
    negativeWeight,
    similarityBoost,
    effectivePositiveWeight,
    effectiveNegativeWeight,
    directScore,
    score,
    sourceKind,
    verified: hasListenEvidence || isFavorite || isDisliked || Math.abs(similarityBoost) > 0.001,
  };
}

function _computeTasteScoreSnapshot(songId, entry, opts = {}) {
  const s = songs[songId];
  if (!s) return { direction: 'neutral', score: 0, sourceKind: 'neutral', verified: false };
  const signals = _computeTasteContributions(songId, entry, opts);

  return {
    direction: _tasteDirectionFromScore(signals.score),
    score: _roundSignal(signals.score),
    directScore: _roundSignal(signals.directScore),
    similarityBoost: _roundSignal(signals.similarityBoost),
    sourceKind: signals.sourceKind,
    verified: signals.verified,
    plays: signals.plays,
    fracsCount: signals.fracs.length,
    avgFrac: signals.avgFracRecorded != null ? _roundSignal(signals.avgFracRecorded) : null,
    xScore: _roundSignal(signals.xScore),
  };
}

function _getRecommendationPoolSize(limit = TOP_N) {
  return Math.min(embeddings.length, Math.max(limit * RECOMMENDATION_POOL_MULTIPLIER, limit + RECOMMENDATION_POOL_PADDING));
}

function _calculateRecommendationAdjust(score) {
  const positive = Math.max(0, Number(score) || 0);
  const negative = Math.max(0, -(Number(score) || 0));
  const bonus = Math.min(RECOMMENDATION_POSITIVE_BONUS_MAX, positive * RECOMMENDATION_POSITIVE_BONUS_PER_POINT);
  const penalty = Math.min(RECOMMENDATION_NEGATIVE_PENALTY_MAX, negative * RECOMMENDATION_NEGATIVE_PENALTY_PER_POINT);
  return Math.round((bonus - penalty) * 1000) / 1000;
}

function _createSignalRow(songId, data, signals, now = Date.now()) {
  const song = songs[songId];
  if (!song) return null;
  const lastTime = (data && data.lastPlayedAt) ? new Date(data.lastPlayedAt).getTime() : null;
  const daysSince = lastTime != null ? (now - lastTime) / PROFILE_DAY_MS : null;
  return {
    id: songId,
    filename: song.filename,
    title: song.title,
    artist: song.artist,
    album: song.album,
    artPath: song.artPath,
    filePath: song.filePath || null,
    hasEmbedding: song.hasEmbedding,
    plays: signals.plays,
    fracsCount: signals.fracs.length,
    avgFrac: signals.avgFracRecorded != null ? signals.avgFracRecorded : null,
    daysSince: daysSince != null ? Math.round(daysSince * 10) / 10 : null,
    recencyMult: signals.recencyMult,
    isFavorite: signals.isFavorite,
    isDisliked: signals.isDisliked,
    favoritePrior: signals.favoritePrior,
    dislikePrior: signals.dislikePrior,
    positiveListenWeight: signals.positiveListenWeight,
    negativeListenWeight: signals.negativeListenWeight,
    positiveWeight: signals.positiveWeight,
    negativeWeight: signals.negativeWeight,
    xScore: signals.xScore,
    effectivePositiveWeight: signals.effectivePositiveWeight,
    effectiveNegativeWeight: signals.effectiveNegativeWeight,
    similarityBoost: signals.similarityBoost,
    directScore: signals.directScore,
    score: signals.score,
    absScore: Math.abs(signals.score),
    sign: signals.score > 0 ? 1 : (signals.score < 0 ? -1 : 0),
    sourceKind: signals.sourceKind,
    isActive: (
      signals.effectivePositiveWeight > 0.001
      || signals.effectiveNegativeWeight > 0.001
      || Math.abs(signals.score) > 0.001
    ),
    inTop30: false,
    inTopPositive30: false,
    inTopNegative30: false,
    hasListenEvidence: signals.hasListenEvidence,
    verified: signals.verified,
    directNegativeStrength: signals.directScore < 0 ? Math.abs(signals.directScore) : 0,
    isHardRecommendationBlock: false,
    recommendationAdjust: 0,
    effectiveRecScore: signals.score,
  };
}

function _buildSignalRowsFromSummary(summary, opts = {}) {
  const includeAllEmbedded = opts.includeAllEmbedded === true;
  const songEntries = summary && summary.songs ? summary.songs : {};
  const fnMap = _getFilenameMap();
  const now = Date.now();
  const rows = [];
  const seen = new Set();

  const addRow = (sid, data) => {
    if (sid == null || seen.has(sid)) return;
    const song = songs[sid];
    if (!song || !song.hasEmbedding) return;
    const signals = _computeTasteContributions(sid, data);
    const similarityBoost = Math.abs(signals.similarityBoost) > 0.001;
    const hasDirectSignal = (
      signals.positiveWeight > 0.001
      || signals.negativeWeight > 0.001
      || signals.isFavorite
      || signals.isDisliked
      || Math.abs(signals.directScore) > 0.001
    );
    if (!includeAllEmbedded && !hasDirectSignal && !similarityBoost) return;
    const row = _createSignalRow(sid, data, signals, now);
    if (!row) return;
    rows.push(row);
    seen.add(sid);
  };

  for (const [key, data] of Object.entries(songEntries)) {
    const sid = fnMap[key.toLowerCase()];
    addRow(sid, data);
  }

  for (let sid = 0; sid < songs.length; sid++) {
    if (seen.has(sid)) continue;
    const song = songs[sid];
    if (!song || !song.hasEmbedding) continue;
    if (!includeAllEmbedded) {
      const xScore = negativeScores[song.filename] || 0;
      const similarityBoost = _getSimilarityBoost(sid);
      if (!favorites.has(sid) && !song.disliked && xScore <= 0 && Math.abs(similarityBoost) < 0.001) continue;
    }
    addRow(sid, null);
  }

  rows.sort((a, b) => {
    const scoreDelta = Math.abs(b.score || 0) - Math.abs(a.score || 0);
    if (scoreDelta !== 0) return scoreDelta;
    return String(a.title || '').localeCompare(String(b.title || ''));
  });
  return rows;
}

function _decorateSignalRows(rows) {
  const working = Array.isArray(rows) ? rows : [];
  for (const row of working) {
    row.inTop30 = false;
    row.inTopPositive30 = false;
    row.inTopNegative30 = false;
    row.isHardRecommendationBlock = false;
    row.recommendationAdjust = _calculateRecommendationAdjust(row.score || 0);
    row.effectiveRecScore = row.score || 0;
    row.directNegativeStrength = row.directScore < 0 ? Math.abs(row.directScore) : 0;
  }

  const positiveRanked = working
    .filter(r => r.effectivePositiveWeight > 0)
    .slice()
    .sort((a, b) => b.effectivePositiveWeight - a.effectivePositiveWeight);
  for (let i = 0; i < Math.min(30, positiveRanked.length); i++) {
    positiveRanked[i].inTopPositive30 = true;
    positiveRanked[i].inTop30 = true;
  }

  const negativeRanked = working
    .filter(r => r.effectiveNegativeWeight > 0)
    .slice()
    .sort((a, b) => b.effectiveNegativeWeight - a.effectiveNegativeWeight);
  for (let i = 0; i < Math.min(30, negativeRanked.length); i++) {
    negativeRanked[i].inTopNegative30 = true;
    negativeRanked[i].inTop30 = true;
  }

  const activeNegative = working
    .filter(r => r.filePath && r.hasEmbedding && r.directScore < 0)
    .slice()
    .sort((a, b) => b.directNegativeStrength - a.directNegativeStrength);
  const hardExcludeCount = activeNegative.length > 0
    ? Math.max(1, Math.ceil(activeNegative.length * RECOMMENDATION_NEGATIVE_HARD_EXCLUDE_SHARE))
    : 0;
  const hardExcludeSongIds = new Set();
  for (let i = 0; i < Math.min(hardExcludeCount, activeNegative.length); i++) {
    if (activeNegative[i].directNegativeStrength >= RECOMMENDATION_NEGATIVE_HARD_EXCLUDE_FLOOR) {
      hardExcludeSongIds.add(activeNegative[i].id);
    }
  }
  for (const row of working) {
    row.isHardRecommendationBlock = hardExcludeSongIds.has(row.id);
  }

  return { rows: working, positiveRanked, negativeRanked, hardExcludeSongIds, activeNegative };
}

function _buildRecommendationFingerprint(rows, hardExcludeSongIds) {
  const topPositive = rows
    .filter(row => row.score > 0)
    .slice()
    .sort((a, b) => b.score - a.score)
    .slice(0, 10)
    .map(row => `${row.id}:${_roundSignal(row.score)}:${_roundSignal(row.similarityBoost)}`)
    .join('|');
  const topNegative = rows
    .filter(row => row.score < 0)
    .slice()
    .sort((a, b) => a.score - b.score)
    .slice(0, 10)
    .map(row => `${row.id}:${_roundSignal(row.score)}:${_roundSignal(row.similarityBoost)}`)
    .join('|');
  const blocked = [...hardExcludeSongIds].sort((a, b) => a - b).join(',');
  return `${topPositive}::${topNegative}::${blocked}`;
}

function _updateRecommendationPolicySnapshot(rows) {
  const decorated = _decorateSignalRows(rows);
  const fingerprint = _buildRecommendationFingerprint(decorated.rows, decorated.hardExcludeSongIds);
  const rowsById = new Map(decorated.rows.map(row => [row.id, { ...row }]));
  _recommendationPolicySnapshot = {
    rowsById,
    hardExcludeSongIds: new Set(decorated.hardExcludeSongIds),
    fingerprint,
    version: _recommendationPolicySnapshot.version + 1,
    updatedAt: Date.now(),
  };
  return decorated;
}

function _getRecommendationPolicyRow(songId) {
  if (_recommendationPolicySnapshot.rowsById.has(songId)) {
    return _recommendationPolicySnapshot.rowsById.get(songId);
  }
  const song = songs[songId];
  if (!song || !song.hasEmbedding) return null;
  const entry = log && typeof log.peekProfileSummaryEntry === 'function'
    ? log.peekProfileSummaryEntry(song.filename)
    : null;
  const signals = _computeTasteContributions(songId, entry);
  return {
    id: songId,
    score: signals.score,
    directScore: signals.directScore,
    similarityBoost: signals.similarityBoost,
    isHardRecommendationBlock: false,
    recommendationAdjust: _calculateRecommendationAdjust(signals.score),
    effectiveRecScore: signals.score,
    isFavorite: signals.isFavorite,
    isDisliked: signals.isDisliked,
  };
}

function _applyRecommendationPolicyToSongItems(items, opts = {}) {
  const pureSimilarity = opts.pureSimilarity === true;
  const limit = opts.limit != null ? opts.limit : items.length;
  const seen = new Set();
  const next = [];
  for (const item of items || []) {
    const sid = item && item.id != null ? item.id : null;
    if (sid == null || seen.has(sid)) continue;
    const song = songs[sid];
    if (!song || !song.filePath) continue;
    const policyRow = _getRecommendationPolicyRow(sid);
    if (!pureSimilarity && policyRow && policyRow.isHardRecommendationBlock) continue;
    const similarity = Number(item.similarity) || 0;
    const adjust = pureSimilarity ? 0 : (policyRow ? policyRow.recommendationAdjust || 0 : 0);
    next.push({
      ...item,
      id: sid,
      similarity,
      recommendationAdjust: adjust,
      policySimilarity: Math.round((similarity + adjust) * 1000) / 1000,
      effectiveTasteScore: policyRow ? _roundSignal(policyRow.score) : 0,
      directTasteScore: policyRow ? _roundSignal(policyRow.directScore) : 0,
      similarityBoost: policyRow ? _roundSignal(policyRow.similarityBoost) : 0,
    });
    seen.add(sid);
  }

  next.sort((a, b) => {
    const primary = (b.policySimilarity || 0) - (a.policySimilarity || 0);
    if (primary !== 0) return primary;
    const secondary = (b.similarity || 0) - (a.similarity || 0);
    if (secondary !== 0) return secondary;
    return String(songs[a.id]?.title || '').localeCompare(String(songs[b.id]?.title || ''));
  });
  return next.slice(0, limit);
}

function _recResultsToSongItems(recResults, opts = {}) {
  const items = (recResults || [])
    .map(item => ({
      id: _fastEmbIdxToSongId(item.id),
      similarity: item.similarity,
    }))
    .filter(item => {
      const song = songs[item.id];
      return song && song.filePath;
    });
  return _applyRecommendationPolicyToSongItems(items, opts);
}

function _nativeNearestResultsToSongItems(results, opts = {}) {
  const pathMap = _getPathMap();
  const hashMap = {};
  for (const s of songs) {
    if (s && s.contentHash && hashMap[s.contentHash] == null) hashMap[s.contentHash] = s.id;
  }
  const items = (results || [])
    .map(item => {
      const fp = item && (item.filepath || item.filePath);
      const hash = item && (item.contentHash || item.content_hash);
      let id = fp ? pathMap[String(fp).toLowerCase()] : null;
      if (id == null && hash) id = hashMap[hash];
      return {
        id,
        similarity: Number(item && item.similarity) || 0,
      };
    })
    .filter(item => {
      const song = songs[item.id];
      return song && song.filePath;
    });
  return _applyRecommendationPolicyToSongItems(items, opts);
}

function _embExcludeToFilepaths(excludeEmbIdx) {
  const paths = [];
  for (const embIdx of excludeEmbIdx || []) {
    const sid = _fastEmbIdxToSongId(embIdx);
    const song = sid != null && sid >= 0 ? songs[sid] : null;
    if (song && song.filePath) paths.push(song.filePath);
  }
  return paths;
}

async function _nativeRecommendFromQueryVec(queryVec, limit, excludeEmbIdx = new Set(), opts = {}) {
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

function _recommendFromQueryVec(queryVec, limit = TOP_N, exclude = null, artistMap = null, currentArtist = null, opts = {}) {
  if (!rec || !queryVec) return [];
  const raw = rec.recommend(queryVec, _getRecommendationPoolSize(limit), exclude, artistMap, currentArtist);
  return _recResultsToSongItems(raw, { ...opts, limit });
}

function _recommendFromSourceEmb(embIdx, limit = 10, exclude = null, opts = {}) {
  if (!rec || embIdx == null) return [];
  const raw = rec.recommendSingle(embIdx, _getRecommendationPoolSize(limit), exclude);
  return _recResultsToSongItems(raw, { ...opts, limit });
}

function _getNearestNeighborSongIds(songId, limit = SIMILARITY_NEIGHBOR_COUNT, excludeSongIds = new Set()) {
  const song = songs[songId];
  if (!song || !song.hasEmbedding || song.embeddingIndex == null || !rec) return [];
  const embExclude = _songIdsToEmbExclude(excludeSongIds);
  embExclude.add(song.embeddingIndex);
  const count = Math.min(embeddings.length, limit + embExclude.size + 20);
  let raw = [];
  if (typeof rec._findNearest === 'function') {
    const queryVec = embeddings[song.embeddingIndex];
    const result = rec._findNearest(queryVec, count);
    raw = (result && result.topIndices ? result.topIndices : [])
      .filter(idx => !embExclude.has(idx))
      .map(idx => ({ id: _fastEmbIdxToSongId(idx) }));
  } else {
    raw = rec.recommendSingle(song.embeddingIndex, count, embExclude)
      .map(item => ({ id: _fastEmbIdxToSongId(item.id) }));
  }
  return raw
    .filter(item => item.id != null && item.id >= 0 && songs[item.id] && songs[item.id].filePath)
    .slice(0, limit)
    .map(item => item.id);
}

function _applySimilarityBoostPropagation(songId, scoreDelta, reason = 'unspecified') {
  const song = songs[songId];
  if (!song || !song.hasEmbedding) return [];
  const numericDelta = Math.round((Number(scoreDelta) || 0) * 1000) / 1000;
  if (Math.abs(numericDelta) < 0.001) return [];

  const neighbors = _getNearestNeighborSongIds(songId, SIMILARITY_NEIGHBOR_COUNT, new Set([songId]));
  if (neighbors.length === 0) return [];

  const changes = [];
  for (let i = 0; i < Math.min(neighbors.length, SIMILARITY_NEIGHBOR_WEIGHTS.length); i++) {
    const neighborId = neighbors[i];
    const weight = SIMILARITY_NEIGHBOR_WEIGHTS[i];
    const delta = Math.round(numericDelta * weight * 1000) / 1000;
    if (Math.abs(delta) < 0.001) continue;
    const before = _getSimilarityBoost(neighborId);
    const after = _setSimilarityBoostScore(neighborId, before + delta);
    changes.push({
      songId: neighborId,
      title: songs[neighborId] ? songs[neighborId].title : '',
      weight,
      delta,
      before: _roundSignal(before),
      after: _roundSignal(after),
    });
  }
  if (changes.length > 0) {
    _saveSimilarityBoostScores().catch(() => {});
    _activity('taste', numericDelta >= 0 ? 'similarity_boost_propagated' : 'similarity_penalty_propagated',
      `Similarity influence updated from "${song.title}"`, {
        ..._songRef(songId),
        reason,
        scoreDelta: numericDelta,
        neighborsTouched: changes.length,
        neighbors: changes.slice(0, 10),
      }, { important: true, tags: ['taste', 'similarity'] });
  }
  return changes;
}

function _buildTasteReviewFingerprint(row) {
  return JSON.stringify({
    direction: row.signalStatus || 'neutral',
    score: _roundSignal(row.score || 0),
    plays: row.plays || 0,
    skips: row.skips || 0,
    completions: row.completions || 0,
    fracsCount: row.fracsCount || 0,
    avgFrac: row.avgFrac != null ? _roundSignal(row.avgFrac) : null,
    xScore: _roundSignal(row.xScore || 0),
    reasonCodes: Array.isArray(row.reasonCodes) ? row.reasonCodes.slice().sort() : [],
    resetAt: row.resetAt || null,
    lastPlayedAt: row.lastPlayedAt || null,
  });
}

function _rememberPlaybackSignalEvent(evt) {
  if (!evt) return;
  _recentPlaybackSignalEvents.push(evt);
  if (_recentPlaybackSignalEvents.length > MAX_RECENT_PLAYBACK_SIGNALS) {
    _recentPlaybackSignalEvents = _recentPlaybackSignalEvents.slice(_recentPlaybackSignalEvents.length - MAX_RECENT_PLAYBACK_SIGNALS);
  }
}

function _normalizePlaybackSignalEvent(evt) {
  if (!evt) return null;
  const timestamp = evt.timestamp || evt.ts || new Date().toISOString();
  const timestampMs = Date.parse(timestamp);
  return {
    eventId: evt.eventId || `playback_signal_${timestamp}_${evt.filename || ''}`,
    timestamp,
    timestampMs: Number.isFinite(timestampMs) ? timestampMs : 0,
    songId: evt.songId != null ? evt.songId : (evt.song_id != null ? evt.song_id : null),
    title: evt.title || '',
    artist: evt.artist || '',
    filename: evt.filename || '',
    fraction: evt.fraction != null ? Number(evt.fraction) : (evt.listen_fraction != null ? Number(evt.listen_fraction) : null),
    classification: evt.classification || 'positive',
    source: evt.source || null,
    sessionBefore: evt.sessionBefore || null,
    sessionAfter: evt.sessionAfter || null,
    summaryBefore: evt.summaryBefore || null,
    summaryAfter: evt.summaryAfter || null,
    negativeBefore: Number(evt.negativeBefore) || 0,
    negativeAfter: Number(evt.negativeAfter) || 0,
    tasteBefore: _normalizeTasteScoreSnapshot(evt.tasteBefore),
    tasteAfter: _normalizeTasteScoreSnapshot(evt.tasteAfter),
  };
}

function _makeProfileSnapshot(profileSongs, negativeSongs) {
  const positive = new Map();
  const negative = new Map();
  for (const row of profileSongs) positive.set(row.id, row.weight);
  for (const row of negativeSongs) negative.set(row.id, row.weight);
  return { positive, negative };
}

function _logProfileWeightDeltas(nextSnapshot, reason = 'profile_rebuild') {
  const changes = [];
  const ids = new Set([
    ..._lastProfileWeightSnapshot.positive.keys(),
    ..._lastProfileWeightSnapshot.negative.keys(),
    ...nextSnapshot.positive.keys(),
    ...nextSnapshot.negative.keys(),
  ]);
  for (const sid of ids) {
    const prevPos = _lastProfileWeightSnapshot.positive.get(sid) || 0;
    const nextPos = nextSnapshot.positive.get(sid) || 0;
    const prevNeg = _lastProfileWeightSnapshot.negative.get(sid) || 0;
    const nextNeg = nextSnapshot.negative.get(sid) || 0;
    if (Math.abs(prevPos - nextPos) < 0.01 && Math.abs(prevNeg - nextNeg) < 0.01) continue;
    changes.push({
      sid,
      prevPos, nextPos, prevNeg, nextNeg,
      magnitude: Math.abs(prevPos - nextPos) + Math.abs(prevNeg - nextNeg),
    });
  }
  changes.sort((a, b) => b.magnitude - a.magnitude);
  for (const ch of changes.slice(0, 6)) {
    const s = songs[ch.sid];
    if (!s) continue;
    let movement = 'weight changed';
    if (ch.prevPos <= 0 && ch.nextPos > 0) movement = 'entered positive pool';
    else if (ch.prevPos > 0 && ch.nextPos <= 0) movement = 'left positive pool';
    else if (ch.prevNeg <= 0 && ch.nextNeg > 0) movement = 'entered negative pool';
    else if (ch.prevNeg > 0 && ch.nextNeg <= 0) movement = 'left negative pool';
    _activity('taste', 'profile_weight_changed', `"${s.title}" ${movement}`, {
      ..._songRef(ch.sid),
      reason,
      positiveBefore: Math.round(ch.prevPos * 100) / 100,
      positiveAfter: Math.round(ch.nextPos * 100) / 100,
      negativeBefore: Math.round(ch.prevNeg * 100) / 100,
      negativeAfter: Math.round(ch.nextNeg * 100) / 100,
    }, { important: true, tags: ['profile', 'weight'] });
  }
  _lastProfileWeightSnapshot = nextSnapshot;
}

// --- Session State ---
const state = {
  current: null,
  currentSource: null,
  history: [],
  historyPos: -1,
  queue: [],
  listened: [],
  sessionLabel: '',
  playingFavorites: false,
  playingAlbum: false,
  playingPlaylist: false,
  currentPlaylistId: null,
  currentAlbumTracks: [],
  timelineMode: null,
  timelineItems: [],
  timelineIndex: -1,
  explicitPlayedIds: [],
};

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

// --- Data Loading ---

async function loadData() {
  const t0 = performance.now();
  console.log('[PERF] loadData start');
  await initActivityLog();
  _activity('app', 'engine_load_started', 'Engine load started', {}, { important: false, tags: ['startup'] });

  await _loadTuning();
  await _loadNegativeScores();
  await _loadSimilarityBoostScores();
  await _loadDislikes();
  await _loadTasteReviewIgnores();

  // Get app-private data directory from native (no permissions needed, works on GrapheneOS)
  try {
    const dirResult = await MusicBridge.getAppDataDir();
    if (dirResult && dirResult.path) {
      EXTERNAL_DATA_DIR = dirResult.path;
      EmbeddingCache.setDataDir(dirResult.path);
      if (dirResult.artCacheDir) setArtCacheDir(dirResult.artCacheDir);
    }
  } catch (e) {
    console.warn('Could not get app data dir, using fallback:', e.message);
  }
  console.log('[PERF] getAppDataDir:', Math.round(performance.now() - t0), 'ms');

  // Load saved song library for instant UI — embeddings load in parallel
  embeddings = [];
  embeddingMap = {};
  songs = [];

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
        librarySavedAt = library.savedAt || 0;
        songs = library.songs.map((s, i) => ({
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
        }));
        songs = _dedupeSongsArray(songs); _normalizeSongArtPaths(songs); _applyDislikeFlags();
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
          librarySavedAt = library.savedAt || 0;
          songs = library.songs.map((s, i) => ({
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
          }));
          songs = _dedupeSongsArray(songs); _normalizeSongArtPaths(songs); _applyDislikeFlags();
          libraryLoaded = true;
          console.log(`[PERF] Library: ${songs.length} songs via bridge in`, Math.round(performance.now() - t1b), 'ms');
        }
      }
    } catch (e) {
      console.log('[PERF] Bridge read also failed, starting fresh');
    }
  }

  _rebuildAlbums();
  log = new SessionLogger();

  // Load favorites now (single Preferences read) so discover renders them immediately
  // Profile vec is intentionally deferred until embeddings are ready so startup
  // playback is not competing with AI hydration work on the JS thread.
  await loadFavorites();
  await loadPlaylists();

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

function startBackgroundScan(opts) {
  const force = !!(opts && opts.force);
  _setupEmbeddingListeners();
  if (typeof MusicBridge.requestEmbeddingStatus === 'function') {
    MusicBridge.requestEmbeddingStatus().catch(() => {});
  }
  const embeddingPromise = _loadLocalEmbeddings();
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
      if (embeddingPromise) await embeddingPromise;
      _setLoading('Merging embeddings...');
      const mergedCount = _mergeLocalEmbeddings();
      if (mergedCount > 0 && embeddings.length > 0) {
        rec = new Recommender(embeddings); rec.lam = _tuning.adventurous;
        _artistMap = null;
        _embIdxMap = null;
      }
      await _loadRemovedEmbeddingKeys();
      _applyRemovedEmbeddingFlags();
      _identifyOrphans();
      await loadFavorites();
      try { profileVec = await buildProfileVec('startup_embeddings_ready_cached', { logDeltas: false }); } catch (e) { console.error('buildProfileVec failed (skip path):', e); profileVec = null; }
      _setLoading('');
      _embeddingsReady = true;
      _activity('embedding', 'embedding_ready', 'Embeddings ready after cached startup', {
        embeddingCount: embeddings.length,
        songCount: songs.length,
      }, { important: true, tags: ['embedding'] });
      for (const cb of _embeddingsReadyCbs) {
        try { cb(); } catch (e) { /* ignore */ }
      }
      _embeddingsReadyCbs = [];
      if (rec && recToggle && state.current != null && songs[state.current] && songs[state.current].hasEmbedding) {
        _softRefreshQueue();
      }
      scanComplete = true;
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
    songs = _dedupeSongsArray(songs); _normalizeSongArtPaths(songs); _applyDislikeFlags();
    _invalidateFilenameMap();
    _invalidatePathMap();
    _restoreStateBySongRefs(stateSnapshot);
    _rebuildAlbums();
    _artistMap = null;  // Invalidate artist map after scan
    _embIdxMap = null;  // Invalidate embedding index map too

    // Wait for embeddings to finish loading before merging
    if (embeddingPromise) await embeddingPromise;

    // Now merge local embeddings (needs filePaths to be set from scan)
    _setLoading('Merging embeddings...');
    const mergedCount = _mergeLocalEmbeddings();

    // Rebuild recommender if local embeddings were merged (new vectors added)
    if (mergedCount > 0 && embeddings.length > 0) {
      rec = new Recommender(embeddings); rec.lam = _tuning.adventurous;
      _artistMap = null;
      _embIdxMap = null;
    }

    // Apply any user-removed embedding flags (ISSUE-6)
    await _loadRemovedEmbeddingKeys();
    _applyRemovedEmbeddingFlags();

    // Identify orphaned songs (in embeddings but not on device)
    _identifyOrphans();

    // Save song library (metadata from scan) for instant UI on next startup
    _saveSongLibrary();

    // Reload favorites and build profile vec BEFORE firing callbacks
    // (analyzeProfile needs profileVec for "For You" section)
    await loadFavorites();
    try { profileVec = await buildProfileVec('startup_embeddings_ready_scan', { logDeltas: false }); } catch (e) { console.error('buildProfileVec failed (scan path):', e); profileVec = null; }

    // Embeddings are now ready — notify listeners
    _setLoading('');
    _embeddingsReady = true;
    _activity('embedding', 'embedding_ready', 'Embeddings ready after library scan', {
      embeddingCount: embeddings.length,
      profileReady: !!profileVec,
    }, { important: true, tags: ['embedding'] });
    console.log(`[PERF] Embeddings ready: rec=${!!rec}, profileVec=${!!profileVec}, ${embeddings.length} embeddings`);
    for (const cb of _embeddingsReadyCbs) {
      try { cb(); } catch (e) { /* ignore */ }
    }
    _embeddingsReadyCbs = [];

    // If a song is currently playing with shuffle queue, upgrade to AI recs
    if (rec && recToggle && state.current != null && songs[state.current] && songs[state.current].hasEmbedding) {
      _softRefreshQueue();
    }

    scanComplete = true;
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
    scanComplete = true;
    _embeddingsReady = true;
    _setLoading('');
    for (const cb of _embeddingsReadyCbs) {
      try { cb(); } catch (e2) { /* ignore */ }
    }
    _embeddingsReadyCbs = [];
    for (const cb of scanCallbacks) {
      try { cb(); } catch (e2) { /* ignore */ }
    }
  }
}

// --- On-Device Embedding ---

async function _loadLocalEmbeddings() {
  try {
    _embLog('info', `Loading embeddings from: ${EXTERNAL_DATA_DIR}`);
    const loaded = await EmbeddingCache.loadFromDisk();
    if (loaded) {
      const count = Object.keys(loaded).filter(k => k !== '_path_index').length;
      if (count > 0 || Object.keys(localEmbeddings).filter(k => k !== '_path_index').length === 0) {
        localEmbeddings = loaded;
      }
      _embLog('info', `Loaded ${count} embeddings from disk`);
    } else {
      _embLog('info', 'No embeddings file found on disk');
    }
  } catch (e) {
    _embLog('error', `Could not load embeddings: ${e.message}`);
  }
}

function _saveSongLibrary() {
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

function _mergeLocalEmbeddings() {
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

  for (const [key, data] of embEntries) {
    if (!data.embedding || !Array.isArray(data.embedding)) continue;

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

    if (song.hasEmbedding && song.embeddingIndex != null) {
      // Replace Colab embedding with verified on-device one
      embeddings[song.embeddingIndex] = new Float32Array(data.embedding);
    } else {
      // New embedding slot
      const newIdx = embeddings.length;
      embeddings.push(new Float32Array(data.embedding));
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
    if (!data || !data.embedding || !Array.isArray(data.embedding)) continue;

    // Find the embedding vector — it may already be loaded from the first pass
    // (another song with the same hash was matched). Reuse its embeddingIndex.
    const donor = songsByHash[hash];
    if (donor && donor.hasEmbedding && donor.embeddingIndex != null) {
      // Share the same embedding slot — identical audio content
      s.hasEmbedding = true;
      s.embeddingIndex = donor.embeddingIndex;
      embeddingMap[s.filename.toLowerCase()] = donor.embeddingIndex;
    } else {
      // No donor found — create a new embedding slot
      const newIdx = embeddings.length;
      embeddings.push(new Float32Array(data.embedding));
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

// Orphaned songs: exist in embeddings/songs list but NOT on device
let orphanedSongs = [];

function _identifyOrphans() {
  orphanedSongs = songs.filter(s => !s.filePath);
  if (orphanedSongs.length > 0) {
    _embLog('info', `Found ${orphanedSongs.length} orphaned songs (in embeddings but not on device)`);
  }
}

// Clears filePath on songs whose file is no longer present on the device, and
// removes them from favorites / queue / history / playlists / album-mode state.
// Keeps the `songs[]` entry (with hasEmbedding / embeddingIndex intact) so the
// AI-page Orphaned bucket can surface them for consent-gated embedding removal.
// Returns the list of affected songs ({ id, filename, title, filePath }).
function _markSongsMissing(missingFilePaths, reason) {
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
  if (favChanged) saveFavorites().catch(() => {});

  let playlistsChanged = false;
  for (const playlist of playlists) {
    const before = (playlist.songFilenames || []).length;
    playlist.songFilenames = (playlist.songFilenames || []).filter(fn => !affectedFilenames.has(fn));
    if (playlist.songFilenames.length !== before) {
      playlist.updatedAt = Date.now();
      playlistsChanged = true;
    }
  }
  if (playlistsChanged) savePlaylists().catch(() => {});

  _invalidatePathMap();
  _rebuildAlbums();
  _invalidateForYouCache(reason || 'songs_missing');
  _invalidateUnexploredClustersCache(reason || 'songs_missing');
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

async function markSongMissingByPath(filePath) {
  if (!filePath) return { ok: false, error: 'No file path' };
  const affected = _markSongsMissing(new Set([filePath]), 'play_error_file_missing');
  return { ok: true, removed: affected.length };
}

// The cache-skip startup path avoids the full MediaStore scan for perf, which
// also skips deletion detection. Run a lightweight background scan after the
// cache-skip startup is done so externally deleted files still get reconciled.
// Fire-and-forget: failures fall back to the next real scan.
let _deferredDeletionCheckRan = false;
function _scheduleDeferredDeletionCheck() {
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
        for (const cb of _songLibraryChangedCbs) {
          try { cb({ reason: 'external_deletion', affected: affected.length }); } catch (e) { /* ignore */ }
        }
      }
    } catch (e) {
      console.warn('[SCAN] Deferred deletion check failed:', e && e.message || e);
    }
  }, 3000);
}

// Fires when the library shape changes outside of the normal scan callback
// (e.g. deferred deletion detection, play-time file-missing marking) so the
// UI can re-render.
let _songLibraryChangedCbs = [];
function onSongLibraryChanged(cb) { _songLibraryChangedCbs.push(cb); }

function getOrphanedSongs() {
  return orphanedSongs.map(s => ({
    id: s.id,
    filename: s.filename,
    title: s.title,
    artist: s.artist,
    album: s.album,
  }));
}

function removeOrphanedEmbeddings() {
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
  songs = songs.filter(s => !orphanIds.has(s.id));

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
  favorites = newFavorites;

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
  embeddingMap = {};
  for (const s of songs) {
    if (s.embeddingIndex != null) {
      embeddingMap[s.filename.toLowerCase()] = s.embeddingIndex;
    }
  }
  orphanedSongs = [];

  // Rebuild everything with clean, compacted data
  _rebuildAlbums();
  _artistMap = null;
  _invalidateFilenameMap();
  _invalidatePathMap();
  _embIdxMap = null;
  if (embeddings.length > 0) {
    rec = new Recommender(embeddings); rec.lam = _tuning.adventurous;
  }
  buildProfileVec().then(v => { profileVec = v; }).catch(() => {});
  _saveLocalEmbeddings();
  saveFavorites();
  savePlaybackState();

  _embLog('info', `Removed ${removed} orphaned embeddings`);
  return removed;
}

function _saveLocalEmbeddings() {
  EmbeddingCache.saveToDisk(localEmbeddings);
}

// ===== Manually removed embeddings (ISSUE-6) =====
// Songs the user explicitly removed from the embedding set — keyed by filename.
// Auto-embedding skips these; they appear in a "Manually Removed" list in the UI.
let removedEmbeddingKeys = new Set();
const REMOVED_EMB_PREF_KEY = 'embedding_removed_by_user';

async function _loadRemovedEmbeddingKeys() {
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

function _applyRemovedEmbeddingFlags() {
  if (removedEmbeddingKeys.size === 0) return;
  for (const s of songs) {
    if (removedEmbeddingKeys.has(s.filename)) {
      s.hasEmbedding = false;
      s.embeddingIndex = null;
    }
  }
}

function removeSongEmbedding(songId) {
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
  buildProfileVec().then(v => { profileVec = v; }).catch(() => {});
  return true;
}

function removeAlbumEmbeddings(albumName) {
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
    buildProfileVec().then(v => { profileVec = v; }).catch(() => {});
  }
  return count;
}

function readdSongEmbedding(songId) {
  const s = songs[songId];
  if (!s) return false;
  removedEmbeddingKeys.delete(s.filename);
  _saveRemovedEmbeddingKeys();
  _embLog('info', `User re-added to embedding queue: ${s.filename}`);
  _activity('library', 'embedding_readded', `Re-added embedding for "${s.title}"`, {
    ..._songRef(songId),
  }, { important: true, tags: ['embedding', 'library'] });
  // Kick off embedding for just this song
  if (s.filePath && !embeddingInProgress) {
    _startEmbedding([s]);
  }
  return true;
}

function getRemovedEmbeddingSongs() {
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

async function deleteSong(songId) {
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
    if (playlistsChanged) savePlaylists().catch(() => {});
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
    saveFavorites().catch(() => {});
  }

  _invalidatePathMap();
  _rebuildAlbums();
  try { profileVec = await buildProfileVec(); } catch (e) { /* ignore */ }
  _invalidateForYouCache('song_deleted');
  _invalidateUnexploredClustersCache('song_deleted');
  try { _saveSongLibrary(); } catch (e) {}
  _embLog('info', `User deleted song: ${s.filename}`);
  _activity('library', 'song_deleted', `Deleted "${s.title}" from library`, {
    ..._songRef(songId),
  }, { important: true, tags: ['library'] });
  return { ok: true };
}

async function removeUnmatchedEmbeddings() {
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

async function _triggerAutoEmbedding() {
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
  const paths = songList.map(s => s.filePath);
  _setupEmbeddingListeners();
  MusicBridge.embedNewSongs({ paths }).then(result => {
    _embLog('info', `Embedding call returned: ${JSON.stringify(result)}`);
  }).catch(e => {
    _embLog('error', `Embedding failed to start: ${e.message || e}`);
    embeddingInProgress = false;
  });
}

async function reembedAll() {
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

async function stopEmbedding() {
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

function retryEmbedding() {
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
function embedRemovedSongsBatch() {
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
  _embLog('info', `Re-adding ${toEmbed.length} manually-removed songs to embedding queue`);
  embeddingPausedByUser = false;
  Preferences.remove({ key: 'embedding_paused' }).catch(() => {});
  _startEmbedding(toEmbed);
  return toEmbed.length;
}

let _embeddingListenersSet = false;
function _setupEmbeddingListeners() {
  if (_embeddingListenersSet) return;
  _embeddingListenersSet = true;

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
    return;

    // Find song by filename (O(1) map lookup) or filepath fallback
    const fnMap = _getFilenameMap();
    const sidByFn = fnMap[filename.toLowerCase()];
    let song = sidByFn != null ? songs[sidByFn] : null;
    if (!song && filepath) {
      const sidByPath = _getPathMap()[String(filepath).toLowerCase()];
      song = sidByPath != null ? songs[sidByPath] : null;
    }

    const fnLower = filename.toLowerCase();

    if (song) {
      // Update or create embedding slot
      if (song.hasEmbedding && song.embeddingIndex != null) {
        // Re-embedding: replace existing embedding in-place
        embeddings[song.embeddingIndex] = new Float32Array(embArray);
      } else {
        // New embedding
        const newIdx = embeddings.length;
        embeddings.push(new Float32Array(embArray));
        embeddingMap[fnLower] = newIdx;
        song.hasEmbedding = true;
        song.embeddingIndex = newIdx;
      }
      song.contentHash = contentHash;
    } else if (embeddingMap[fnLower] === undefined) {
      // Song not in list but save embedding anyway
      const newIdx = embeddings.length;
      embeddings.push(new Float32Array(embArray));
      embeddingMap[fnLower] = newIdx;
    }

    // Store embedding data directly (not lazily via _embIdx) to avoid
    // race condition with Java's incremental writes to the same file
    if (!localEmbeddings._path_index) localEmbeddings._path_index = {};
    localEmbeddings[contentHash] = {
      embedding: embArray,
      content_hash: contentHash,
      filepath: filepath,
      timestamp: Date.now(),
      filename: song ? song.filename : filename,
      artist: song ? song.artist : '',
      album: song ? song.album : '',
    };
    localEmbeddings._path_index[filepath] = contentHash;

    // Remove from queue (match by filepath, not filename — handles duplicates)
    embeddingQueue = embeddingQueue.filter(q => q.path !== filepath);

    // Propagate to sibling songs that share the same content hash (same audio,
    // different filename). Mark them as embedded and remove from queue so they
    // are not re-embedded redundantly.
    if (contentHash && song) {
      const embIdx = song.embeddingIndex;
      for (const s of songs) {
        if (s.hasEmbedding || !s.filePath || s.id === song.id) continue;
        // Check if this song's path is mapped to the same content hash
        const siblingHash = localEmbeddings._path_index[s.filePath];
        if (siblingHash === contentHash && embIdx != null) {
          s.hasEmbedding = true;
          s.embeddingIndex = embIdx;
          s.contentHash = contentHash;
          embeddingMap[s.filename.toLowerCase()] = embIdx;
          embeddingQueue = embeddingQueue.filter(q => q.path !== s.filePath);
          _embLog('info', `Sibling matched: ${s.filename} shares content with ${filename}`);
        }
      }
    }
  });

  MusicBridge.addListener('embeddingComplete', async (data) => {
    const elapsed = embeddingStartTime ? Math.round((Date.now() - embeddingStartTime) / 1000) : 0;
    _embLog('info', `Embedding complete: ${data.processed} processed, ${data.failed} failed (${elapsed}s total)`);
    embeddingInProgress = false;
    embeddingQueue = [];
    embeddingCurrentFile = '';
    embeddingProcessedCount = Number(data.processed) || 0;
    embeddingReportedFailedCount = Number(data.failed) || 0;
    embeddingTotalCount = embeddingProcessedCount + embeddingReportedFailedCount;

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

    // Check if all songs are now embedded
    const playable = songs.filter(s => s.filePath);
    const embedded = playable.filter(s => s.hasEmbedding);
    if (playable.length > 0 && embedded.length === playable.length) {
      _embLog('info', `All ${playable.length} songs have embeddings — AI recommendations ready!`);
    }

    // Rebuild recommender with updated embeddings
    if (embeddings.length > 0) {
      rec = new Recommender(embeddings);
      rec.lam = _tuning.adventurous;
      _artistMap = null;
      _embIdxMap = null;
    }
    buildProfileVec().then(v => { profileVec = v; }).catch(() => {});
    for (const cb of scanCallbacks) {
      try { cb(); } catch (e) { /* ignore */ }
    }
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

function getEmbeddingStatus() {
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

function _rebuildAlbums() {
  const albumMap = {};
  for (const s of songs) {
    if (!albumMap[s.album]) albumMap[s.album] = [];
    albumMap[s.album].push(s);
  }
  albumList = albumMap;
  albumArray = Object.keys(albumMap).sort().map(name => ({
    name,
    artist: albumMap[name][0].artist,
    count: albumMap[name].length,
    tracks: albumMap[name].map(t => ({ id: t.id, title: t.title, artist: t.artist })),
  }));
}

function onScanComplete(cb) {
  if (scanComplete) { cb(); return; }
  scanCallbacks.push(cb);
}

let _loadingCb = null;
function onLoadingStatus(cb) { _loadingCb = cb; }
function _setLoading(msg) { if (_loadingCb) _loadingCb(msg); }

let _artReadyCbs = [];
function onAlbumArtReady(cb) { _artReadyCbs.push(cb); }

let _embeddingsReadyCbs = [];
let _embeddingsReady = false;
function onEmbeddingsReady(cb) {
  if (_embeddingsReady) { cb(); return; }
  _embeddingsReadyCbs.push(cb);
}

let _albumArtListenerSet = false;
function _triggerAlbumArtExtraction() {
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

let _artCacheDir = '';
function setArtCacheDir(dir) { _artCacheDir = dir; }
const ART_CACHE_KEY_PREFIX = 'art_v2::';

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

// --- Filename → ID map (O(1) lookup, rebuilt when songs change) ---
let _fnMap = null;
function _getFilenameMap() {
  if (_fnMap && _fnMap._size === songs.length) return _fnMap;
  _fnMap = {};
  for (const s of songs) {
    _fnMap[s.filename.toLowerCase()] = s.id;
  }
  _fnMap._size = songs.length;
  return _fnMap;
}
function _invalidateFilenameMap() { _fnMap = null; }

let _pathMap = null;
function _getPathMap() {
  if (_pathMap && _pathMap._size === songs.length) return _pathMap;
  _pathMap = {};
  for (const s of songs) {
    if (s.filePath) _pathMap[s.filePath.toLowerCase()] = s.id;
  }
  _pathMap._size = songs.length;
  return _pathMap;
}
function _invalidatePathMap() { _pathMap = null; }

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
  _setExplicitPlayedIds(restoredExplicitPlayed);

  if (state.current != null && !state.history.includes(state.current)) {
    state.history.push(state.current);
    state.historyPos = state.history.length - 1;
  }
  if (state.historyPos < 0 && state.history.length > 0) {
    state.historyPos = state.history.length - 1;
  }
  if (state.timelineMode !== 'explicit') {
    _setExplicitPlayedIds([]);
    state.timelineMode = null;
    _syncDynamicTimelineFromState();
  } else if (state.timelineIndex < 0 && state.timelineItems.length > 0 && state.current != null) {
    state.timelineIndex = state.timelineItems.findIndex(item => item.id === state.current);
  }
}

function _filenameFromPath(filePath) {
  if (!filePath) return null;
  const norm = String(filePath);
  const slash = Math.max(norm.lastIndexOf('/'), norm.lastIndexOf('\\'));
  return slash >= 0 ? norm.slice(slash + 1) : norm;
}

function _resolveSongIdFromNativePayload(data) {
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

// --- Favorites (stored by filename for stability) ---

async function loadFavorites() {
  try {
    const { value } = await Preferences.get({ key: 'favorites' });
    console.log('[FAV] loadFavorites: raw value =', value ? value.substring(0, 200) : 'null');
    if (value) {
      const data = JSON.parse(value);
      if (data.filenames) {
        favorites = new Set();
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
        favorites = new Set(data.ids.filter(id => id < songs.length));
      }
    }
  } catch (e) {
    console.error('[FAV] loadFavorites ERROR:', e);
  }
  console.log('[FAV] loadFavorites: loaded', favorites.size, 'favorites, ids:', [...favorites]);
}

async function saveFavorites() {
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

function toggleFavorite(songId) {
  return setFavoriteState(songId, !favorites.has(songId), { source: 'toggle' });
}

function setFavoriteState(songId, shouldBeFavorite, opts = {}) {
  console.log('[FAV] setFavoriteState called with songId =', songId, 'target =', shouldBeFavorite, 'type =', typeof songId, 'songs.length =', songs.length);
  if (songId == null || songId < 0 || songId >= songs.length) {
    console.error('[FAV] GUARD REJECTED songId =', songId);
    return { ok: false, error: 'invalid songId: ' + songId };
  }
  const song = songs[songId];
  const entry = log && typeof log.peekProfileSummaryEntry === 'function'
    ? log.peekProfileSummaryEntry(song.filename)
    : null;
  const xScore = negativeScores[song.filename] || 0;
  const beforeSignals = _computeTasteContributions(songId, entry, {
    xScore,
    isFavorite: favorites.has(songId),
    isDisliked: !!song.disliked,
  });
  const wasFav = favorites.has(songId);
  if (shouldBeFavorite) {
    favorites.add(songId);
  } else {
    favorites.delete(songId);
  }
  const isFav = favorites.has(songId);
  let unDisliked = false;
  if (isFav) {
    const s = songs[songId];
    if (s && s.disliked) {
      s.disliked = false;
      dislikedFilenames.delete(s.filename);
      _saveDislikes().catch(() => {});
      unDisliked = true;
    }
  }
  const count = favorites.size;
  console.log('[FAV] was =', wasFav, 'now =', isFav, 'count =', count);
  saveFavorites().catch(e => console.error('[FAV] save failed:', e));
  if (wasFav !== isFav || unDisliked) {
    _activity('taste', 'favorite_toggled', `${isFav ? 'Added to' : 'Removed from'} favorites`, {
      ..._songRef(songId),
      isFavorite: isFav,
      source: opts.source || 'set',
      unDisliked,
    }, { important: true, tags: ['favorite'] });
  }
  if (unDisliked) {
    _saveSongLibrary();
    _activity('taste', 'favorite_dislike_mutex_applied', 'Favorite removed existing dislike', {
      ..._songRef(songId),
      source: opts.source || 'set',
    }, { important: true, tags: ['favorite', 'dislike'] });
  }
  const afterSignals = _computeTasteContributions(songId, entry, {
    xScore,
    isFavorite: isFav,
    isDisliked: !!song.disliked,
  });
  const scoreDelta = (afterSignals.directScore || 0) - (beforeSignals.directScore || 0);
  _applySimilarityBoostPropagation(songId, scoreDelta, 'favorite_toggle');
  scheduleRecommendationRebuild('favorite_toggle', {
    refreshQueue: true,
    refreshDiscover: true,
  });
  return { ok: true, isFavorite: isFav, count, unDisliked, changed: wasFav !== isFav };
}

function toggleDislike(songId) {
  if (songId == null || songId < 0 || songId >= songs.length) {
    return { ok: false, error: 'invalid songId: ' + songId };
  }
  const s = songs[songId];
  if (!s) return { ok: false, error: 'no song' };
  const entry = log && typeof log.peekProfileSummaryEntry === 'function'
    ? log.peekProfileSummaryEntry(s.filename)
    : null;
  const xScore = negativeScores[s.filename] || 0;
  const beforeSignals = _computeTasteContributions(songId, entry, {
    xScore,
    isFavorite: favorites.has(songId),
    isDisliked: !!s.disliked,
  });
  const wasDisliked = !!s.disliked;
  s.disliked = !wasDisliked;
  let unFavorited = false;
  if (s.disliked) {
    dislikedFilenames.add(s.filename);
    // Mutex: disliking auto-removes favorite
    if (favorites.has(songId)) {
      favorites.delete(songId);
      saveFavorites().catch(() => {});
      unFavorited = true;
    }
  } else {
    dislikedFilenames.delete(s.filename);
  }
  _saveDislikes().catch(() => {});
  _saveSongLibrary();
  _activity('taste', 'dislike_toggled', `${s.disliked ? 'Disliked' : 'Removed dislike from'} "${s.title}"`, {
    ..._songRef(songId),
    isDisliked: s.disliked,
    unFavorited,
  }, { important: true, tags: ['dislike'] });
  if (unFavorited) {
    _activity('taste', 'favorite_dislike_mutex_applied', 'Dislike removed existing favorite', {
      ..._songRef(songId),
    }, { important: true, tags: ['favorite', 'dislike'] });
  }
  const afterSignals = _computeTasteContributions(songId, entry, {
    xScore,
    isFavorite: favorites.has(songId),
    isDisliked: !!s.disliked,
  });
  const scoreDelta = (afterSignals.directScore || 0) - (beforeSignals.directScore || 0);
  _applySimilarityBoostPropagation(songId, scoreDelta, 'dislike_toggle');
  scheduleRecommendationRebuild('dislike_toggle', {
    refreshQueue: true,
    refreshDiscover: true,
  });
  return { ok: true, isDisliked: s.disliked, unFavorited };
}

function isDisliked(songId) {
  return !!(songs[songId] && songs[songId].disliked);
}

function getFavoritesList() {
  return [...favorites]
    .filter(sid => sid < songs.length && songs[sid].filePath)
    .sort((a, b) => a - b)
    .map(sid => ({
      id: sid,
      title: songs[sid].title,
      artist: songs[sid].artist,
      album: songs[sid].album,
      hasEmbedding: songs[sid].hasEmbedding,
      artPath: songs[sid].artPath,
    }));
}

function getDislikedSongsList() {
  return songs
    .filter(s => s && s.filePath && s.disliked)
    .slice()
    .sort((a, b) =>
      String(a.title || '').localeCompare(String(b.title || ''))
      || String(a.artist || '').localeCompare(String(b.artist || ''))
    )
    .map(s => ({
      id: s.id,
      title: s.title,
      artist: s.artist,
      album: s.album,
      hasEmbedding: s.hasEmbedding,
      artPath: s.artPath,
    }));
}

function _normalizePlaylistName(name) {
  return String(name || '').replace(/\s+/g, ' ').trim().slice(0, 60);
}

function _normalizePlaylist(raw) {
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

function _findPlaylistIndex(playlistId) {
  return playlists.findIndex(pl => pl.id === playlistId);
}

function _getPlaylistById(playlistId) {
  const idx = _findPlaylistIndex(playlistId);
  return idx >= 0 ? playlists[idx] : null;
}

function _resolvePlaylistSongIds(playlist) {
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

function _playlistSummary(playlist, previewLimit = 4) {
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

async function loadPlaylists() {
  try {
    const { value } = await Preferences.get({ key: PLAYLISTS_PREF_KEY });
    if (!value) {
      playlists = [];
      return;
    }
    const data = JSON.parse(value);
    const list = Array.isArray(data) ? data : (Array.isArray(data.playlists) ? data.playlists : []);
    playlists = list
      .map(_normalizePlaylist)
      .filter(Boolean)
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  } catch (e) {
    playlists = [];
  }
}

async function savePlaylists() {
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

function getPlaylists() {
  return playlists
    .slice()
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
    .map(pl => _playlistSummary(pl));
}

function getPlaylistSongs(playlistId) {
  const playlist = _getPlaylistById(playlistId);
  if (!playlist) return [];
  return _resolvePlaylistSongIds(playlist).map(sid => ({
    id: sid,
    title: songs[sid].title,
    artist: songs[sid].artist,
    album: songs[sid].album,
    hasEmbedding: songs[sid].hasEmbedding,
    artPath: songs[sid].artPath,
  }));
}

function getPlaylistMeta(playlistId) {
  const playlist = _getPlaylistById(playlistId);
  if (!playlist) return null;
  const summary = _playlistSummary(playlist);
  return {
    id: playlist.id,
    name: playlist.name,
    count: summary.count,
    createdAt: playlist.createdAt,
    updatedAt: playlist.updatedAt,
  };
}

function isSongInPlaylist(playlistId, songId) {
  const playlist = _getPlaylistById(playlistId);
  const song = songs[songId];
  if (!playlist || !song || !song.filename) return false;
  return (playlist.songFilenames || []).includes(song.filename);
}

function createPlaylist(name, initialSongId = null) {
  const cleanName = _normalizePlaylistName(name);
  if (!cleanName) return { ok: false, error: 'Playlist name is required' };
  const exists = playlists.find(pl => pl.name.toLowerCase() === cleanName.toLowerCase());
  if (exists) return { ok: false, error: 'A playlist with this name already exists' };

  const playlist = {
    id: `pl_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`,
    name: cleanName,
    songFilenames: [],
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
  playlists.unshift(playlist);

  let added = false;
  if (initialSongId != null) {
    const addRes = addSongToPlaylist(playlist.id, initialSongId);
    added = !!(addRes && addRes.ok && !addRes.error);
  } else {
    savePlaylists().catch(() => {});
  }

  _activity('library', 'playlist_created', `Created playlist "${playlist.name}"`, {
    playlistId: playlist.id,
    playlistName: playlist.name,
    addedSong: added,
  }, { important: true, tags: ['playlist', 'library'] });

  return { ok: true, playlist: getPlaylistMeta(playlist.id), addedSong: added };
}

function renamePlaylist(playlistId, name) {
  const idx = _findPlaylistIndex(playlistId);
  if (idx < 0) return { ok: false, error: 'Playlist not found' };
  const cleanName = _normalizePlaylistName(name);
  if (!cleanName) return { ok: false, error: 'Playlist name is required' };
  const dup = playlists.find(pl => pl.id !== playlistId && pl.name.toLowerCase() === cleanName.toLowerCase());
  if (dup) return { ok: false, error: 'A playlist with this name already exists' };
  playlists[idx].name = cleanName;
  playlists[idx].updatedAt = Date.now();
  savePlaylists().catch(() => {});
  _activity('library', 'playlist_renamed', `Renamed playlist to "${cleanName}"`, {
    playlistId,
    playlistName: cleanName,
  }, { important: true, tags: ['playlist', 'library'] });
  return { ok: true, playlist: getPlaylistMeta(playlistId) };
}

function deletePlaylist(playlistId) {
  const idx = _findPlaylistIndex(playlistId);
  if (idx < 0) return { ok: false, error: 'Playlist not found' };
  const [playlist] = playlists.splice(idx, 1);
  if (state.currentPlaylistId === playlistId) {
    state.playingPlaylist = false;
    state.currentPlaylistId = null;
    if (state.sessionLabel === `Playlist: ${playlist.name}` || state.sessionLabel === `${playlist.name} finished`) {
      state.sessionLabel = '';
    }
  }
  savePlaylists().catch(() => {});
  _activity('library', 'playlist_deleted', `Deleted playlist "${playlist.name}"`, {
    playlistId,
    playlistName: playlist.name,
  }, { important: true, tags: ['playlist', 'library'] });
  return { ok: true };
}

function addSongToPlaylist(playlistId, songId) {
  const idx = _findPlaylistIndex(playlistId);
  if (idx < 0) return { ok: false, error: 'Playlist not found' };
  const song = songs[songId];
  if (!song || !song.filename || !song.filePath) return { ok: false, error: 'Song not available' };
  const playlist = playlists[idx];
  if ((playlist.songFilenames || []).includes(song.filename)) {
    return { ok: true, alreadyExists: true, playlist: getPlaylistMeta(playlistId) };
  }
  playlist.songFilenames.push(song.filename);
  playlist.updatedAt = Date.now();
  playlists.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  savePlaylists().catch(() => {});
  _activity('library', 'playlist_song_added', `Added "${song.title}" to playlist "${playlist.name}"`, {
    ..._songRef(songId),
    playlistId,
    playlistName: playlist.name,
  }, { important: true, tags: ['playlist', 'library'] });
  return { ok: true, alreadyExists: false, playlist: getPlaylistMeta(playlistId) };
}

function removeSongFromPlaylist(playlistId, songId) {
  const idx = _findPlaylistIndex(playlistId);
  if (idx < 0) return { ok: false, error: 'Playlist not found' };
  const song = songs[songId];
  if (!song || !song.filename) return { ok: false, error: 'Song not found' };
  const playlist = playlists[idx];
  const before = playlist.songFilenames.length;
  playlist.songFilenames = playlist.songFilenames.filter(fn => fn !== song.filename);
  if (playlist.songFilenames.length === before) {
    return { ok: true, removed: false, playlist: getPlaylistMeta(playlistId) };
  }
  playlist.updatedAt = Date.now();
  playlists.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  savePlaylists().catch(() => {});
  _activity('library', 'playlist_song_removed', `Removed "${song.title}" from playlist "${playlist.name}"`, {
    ..._songRef(songId),
    playlistId,
    playlistName: playlist.name,
  }, { important: true, tags: ['playlist', 'library'] });
  return { ok: true, removed: true, playlist: getPlaylistMeta(playlistId) };
}

// --- Profile Vector ---

// Persistent negative signal: repeated short listens and X-score pull the
// profile vector away from a song's embedding. Favorite/dislike flags now act
// as fading manual priors, so real playback can override them over time. Beta
// controls how much the negative vec subtracts from the positive vec before
// renormalization.
const NEGATIVE_PLAY_THRESHOLD = 2;
const LISTEN_SKIP_THRESHOLD = 0.5;
const FULL_LISTEN_THRESHOLD = 0.7;
const NEUTRAL_SKIP_CAPTURE_THRESHOLD = 0.1;
const NEGATIVE_FRAC_THRESHOLD = LISTEN_SKIP_THRESHOLD;

function _getSummaryListenStats(data) {
  const plays = Math.max(0, Number(data && data.plays) || 0);
  const fracs = Array.isArray(data && data.fracs)
    ? data.fracs.filter(v => typeof v === 'number' && !Number.isNaN(v))
    : [];
  const avgFracRecorded = fracs.length > 0
    ? fracs.reduce((a, b) => a + b, 0) / fracs.length
    : null;
  return {
    plays,
    fracs,
    hasRecordedFractions: fracs.length > 0,
    avgFracRecorded,
  };
}

async function buildProfileVec(reason = 'profile_rebuild', opts = {}) {
  const logDeltas = opts.logDeltas !== false;
  const logRebuilt = opts.logRebuilt !== false;
  // Use incremental profile summary instead of re-scanning all logs
  let summary = await SessionLogger.loadProfileSummary();

  // One-time migration: if summary is empty but logs exist, rebuild it
  if (Object.keys(summary.songs).length === 0 && summary.totalPlays === 0) {
    summary = await SessionLogger.rebuildProfileSummary();
  }

  const rows = _buildSignalRowsFromSummary(summary);
  const decorated = _updateRecommendationPolicySnapshot(rows);
  const profileSongs = decorated.rows
    .filter(row => row.effectivePositiveWeight > 0)
    .map(row => ({ id: row.id, weight: row.effectivePositiveWeight }));
  const negativeSongs = decorated.rows
    .filter(row => row.effectiveNegativeWeight > 0)
    .map(row => ({ id: row.id, weight: row.effectiveNegativeWeight }));

  profileSongs.sort((a, b) => b.weight - a.weight);
  const top30 = profileSongs.slice(0, 30);
  let pv = null;
  if (top30.length > 0) {
    const totalWeight = top30.reduce((a, b) => a + b.weight, 0);
    if (totalWeight > 0) {
      const valid = top30.filter(s => songs[s.id] && songs[s.id].embeddingIndex != null && embeddings[songs[s.id].embeddingIndex]);
      if (valid.length > 0) {
        const totalW = valid.reduce((a, b) => a + b.weight, 0);
        const weights = valid.map(s => s.weight / totalW);
        const vecs = valid.map(s => embeddings[songs[s.id].embeddingIndex]);
        pv = weightedAverage(vecs, weights);
      }
    }
  }

  // Subtract negative vector if we have enough dislike signal
  negativeSongs.sort((a, b) => b.weight - a.weight);
  const negValid = negativeSongs.slice(0, 30).filter(s =>
    songs[s.id] && songs[s.id].embeddingIndex != null && embeddings[songs[s.id].embeddingIndex]);
  if (pv && negValid.length > 0) {
    const nTotalW = negValid.reduce((a, b) => a + b.weight, 0);
    if (nTotalW > 0) {
      const nWeights = negValid.map(s => s.weight / nTotalW);
      const nVecs = negValid.map(s => embeddings[songs[s.id].embeddingIndex]);
      const nv = weightedAverage(nVecs, nWeights);
      const beta = _tuning.negativeStrength * 0.7;
      for (let i = 0; i < pv.length; i++) pv[i] = pv[i] - beta * nv[i];
    }
  }

  const nextSnapshot = _makeProfileSnapshot(profileSongs, negativeSongs);
  if (logDeltas) {
    _logProfileWeightDeltas(nextSnapshot, reason);
  } else {
    _lastProfileWeightSnapshot = nextSnapshot;
  }
  if (logRebuilt) {
    _activity('engine', 'profile_rebuilt', 'Profile vector rebuilt', {
      reason,
      positiveCount: profileSongs.length,
      negativeCount: negativeSongs.length,
      topPositiveCount: top30.length,
      topNegativeCount: negValid.length,
      hardBlockedCount: decorated.hardExcludeSongIds.size,
    }, { important: true, tags: ['profile', 'recommendation'] });
  }
  return pv ? vecNormalize(pv) : null;
}

async function getSongPlayStats(songId) {
  const s = songs[songId];
  if (!s) return null;
  // Prefer the live incremental summary owned by the current session logger.
  // It already includes the latest playback updates, so merging `state.listened`
  // on top of it would double-count recent events in UI surfaces like song
  // details after recovery / completion.
  let entry = log && typeof log.peekProfileSummaryEntry === 'function'
    ? log.peekProfileSummaryEntry(s.filename)
    : null;
  if (!entry) {
    const summary = await SessionLogger.loadProfileSummary();
    entry = (summary.songs || {})[s.filename];
  }
  let plays = 0, fracs = [], lastPlayedAt = null;
  if (entry) {
    const listenStats = _getSummaryListenStats(entry);
    plays = listenStats.plays;
    fracs = listenStats.fracs;
    lastPlayedAt = entry.lastPlayedAt || null;
  }
  const avgFrac = fracs.length > 0 ? fracs.reduce((a, b) => a + b, 0) / fracs.length : null;
  return { plays, avgFrac, lastPlayedAt };
}

// --- Profile weight inspection & reset ---

/**
 * Return every song that currently contributes to profileVec with its weight
 * and the raw components that produced it. Sorted by weight desc.
 */
async function getProfileWeights() {
  const summary = await SessionLogger.loadProfileSummary();
  const rows = _buildSignalRowsFromSummary(summary);
  const decorated = _decorateSignalRows(rows);
  const positiveRanked = decorated.positiveRanked;
  for (let i = 0; i < Math.min(30, positiveRanked.length); i++) {
    positiveRanked[i].inTopPositive30 = true;
    positiveRanked[i].inTop30 = true;
  }
  const negativeRanked = decorated.negativeRanked;
  for (let i = 0; i < Math.min(30, negativeRanked.length); i++) {
    negativeRanked[i].inTopNegative30 = true;
    negativeRanked[i].inTop30 = true;
  }

  const maxPos = positiveRanked.length > 0 ? positiveRanked[0].effectivePositiveWeight : 1;
  const positive = positiveRanked.map(r => ({
    ...r,
    weight: r.effectivePositiveWeight,
    weightNorm: maxPos > 0 ? r.effectivePositiveWeight / maxPos : 0,
    sign: 1,
    inTop30: r.inTopPositive30,
  }));

  const maxNeg = negativeRanked.length > 0 ? negativeRanked[0].effectiveNegativeWeight : 1;
  const negative = negativeRanked.map(r => ({
    ...r,
    weight: r.effectiveNegativeWeight,
    weightNorm: maxNeg > 0 ? r.effectiveNegativeWeight / maxNeg : 0,
    sign: -1,
    inTop30: r.inTopNegative30,
  }));

  for (const row of rows) {
    row.avgFrac = row.avgFrac != null ? _roundSignal(row.avgFrac) : null;
    row.recencyMult = _roundSignal(row.recencyMult);
    row.favoritePrior = _roundSignal(row.favoritePrior);
    row.dislikePrior = _roundSignal(row.dislikePrior);
    row.positiveListenWeight = _roundSignal(row.positiveListenWeight);
    row.negativeListenWeight = _roundSignal(row.negativeListenWeight);
    row.positiveWeight = _roundSignal(row.positiveWeight);
    row.negativeWeight = _roundSignal(row.negativeWeight);
    row.effectivePositiveWeight = _roundSignal(row.effectivePositiveWeight);
    row.effectiveNegativeWeight = _roundSignal(row.effectiveNegativeWeight);
    row.similarityBoost = _roundSignal(row.similarityBoost);
    row.directScore = _roundSignal(row.directScore);
    row.xScore = _roundSignal(row.xScore);
    row.score = _roundSignal(row.score);
    row.absScore = _roundSignal(row.absScore);
    row.recommendationAdjust = _roundSignal(row.recommendationAdjust);
    row.effectiveRecScore = _roundSignal(row.effectiveRecScore);
  }
  for (const row of positive) {
    row.avgFrac = row.avgFrac != null ? _roundSignal(row.avgFrac) : null;
    row.recencyMult = _roundSignal(row.recencyMult);
    row.favoritePrior = _roundSignal(row.favoritePrior);
    row.dislikePrior = _roundSignal(row.dislikePrior);
    row.positiveListenWeight = _roundSignal(row.positiveListenWeight);
    row.negativeListenWeight = _roundSignal(row.negativeListenWeight);
    row.positiveWeight = _roundSignal(row.positiveWeight);
    row.negativeWeight = _roundSignal(row.negativeWeight);
    row.effectivePositiveWeight = _roundSignal(row.effectivePositiveWeight);
    row.effectiveNegativeWeight = _roundSignal(row.effectiveNegativeWeight);
    row.similarityBoost = _roundSignal(row.similarityBoost);
    row.directScore = _roundSignal(row.directScore);
    row.xScore = _roundSignal(row.xScore);
    row.score = _roundSignal(row.score);
    row.absScore = _roundSignal(row.absScore);
    row.weight = _roundSignal(row.weight);
    row.weightNorm = _roundSignal(row.weightNorm);
    row.recommendationAdjust = _roundSignal(row.recommendationAdjust);
    row.effectiveRecScore = _roundSignal(row.effectiveRecScore);
  }
  for (const row of negative) {
    row.avgFrac = row.avgFrac != null ? _roundSignal(row.avgFrac) : null;
    row.recencyMult = _roundSignal(row.recencyMult);
    row.favoritePrior = _roundSignal(row.favoritePrior);
    row.dislikePrior = _roundSignal(row.dislikePrior);
    row.positiveListenWeight = _roundSignal(row.positiveListenWeight);
    row.negativeListenWeight = _roundSignal(row.negativeListenWeight);
    row.positiveWeight = _roundSignal(row.positiveWeight);
    row.negativeWeight = _roundSignal(row.negativeWeight);
    row.effectivePositiveWeight = _roundSignal(row.effectivePositiveWeight);
    row.effectiveNegativeWeight = _roundSignal(row.effectiveNegativeWeight);
    row.similarityBoost = _roundSignal(row.similarityBoost);
    row.directScore = _roundSignal(row.directScore);
    row.xScore = _roundSignal(row.xScore);
    row.score = _roundSignal(row.score);
    row.absScore = _roundSignal(row.absScore);
    row.weight = _roundSignal(row.weight);
    row.weightNorm = _roundSignal(row.weightNorm);
    row.recommendationAdjust = _roundSignal(row.recommendationAdjust);
    row.effectiveRecScore = _roundSignal(row.effectiveRecScore);
  }

  const totalPosW = positiveRanked.reduce((a, b) => a + b.effectivePositiveWeight, 0);
  const top5W = positiveRanked.slice(0, 5).reduce((a, b) => a + b.effectivePositiveWeight, 0);
  const top5Share = totalPosW > 0 ? Math.round((top5W / totalPosW) * 100) : 0;
  const topWeightRatio = positiveRanked.length >= 2
    ? (positiveRanked[0].effectivePositiveWeight / (totalPosW / positiveRanked.length))
    : 1;

  return {
    rows,
    positive,
    negative,
    negativeCount: negative.length,
    summary: {
      totalPositive: positive.length,
      totalNegative: negative.length,
      hardBlockedCount: decorated.hardExcludeSongIds.size,
      top5Share,
      topWeightRatio: Math.round(topWeightRatio * 10) / 10,
    },
  };
}

async function getTasteSignal(includeAllEmbedded = false) {
  let summary = await SessionLogger.loadProfileSummary();
  if (Object.keys(summary.songs || {}).length === 0 && summary.totalPlays === 0) {
    summary = await SessionLogger.rebuildProfileSummary();
  }
  const rows = _buildSignalRowsFromSummary(summary, { includeAllEmbedded });
  _decorateSignalRows(rows);

  const maxAbsScore = rows.reduce((m, row) => Math.max(m, Math.abs(row.score || 0)), 0);
  for (const row of rows) {
    row.avgFrac = row.avgFrac != null ? _roundSignal(row.avgFrac) : null;
    row.recencyMult = _roundSignal(row.recencyMult);
    row.favoritePrior = _roundSignal(row.favoritePrior);
    row.dislikePrior = _roundSignal(row.dislikePrior);
    row.positiveListenWeight = _roundSignal(row.positiveListenWeight);
    row.negativeListenWeight = _roundSignal(row.negativeListenWeight);
    row.positiveWeight = _roundSignal(row.positiveWeight);
    row.negativeWeight = _roundSignal(row.negativeWeight);
    row.effectivePositiveWeight = _roundSignal(row.effectivePositiveWeight);
    row.effectiveNegativeWeight = _roundSignal(row.effectiveNegativeWeight);
    row.xScore = _roundSignal(row.xScore);
    row.similarityBoost = _roundSignal(row.similarityBoost);
    row.directScore = _roundSignal(row.directScore);
    row.score = _roundSignal(row.score);
    row.absScore = _roundSignal(row.absScore);
    row.recommendationAdjust = _roundSignal(row.recommendationAdjust);
    row.effectiveRecScore = _roundSignal(row.effectiveRecScore);
    row.scoreNorm = maxAbsScore > 0 ? Math.abs(row.score || 0) / maxAbsScore : 0;
  }

  return {
    rows,
    summary: {
      totalEmbedded: songs.filter(s => s && s.filePath && s.hasEmbedding).length,
      activeCount: rows.filter(row => row.isActive).length,
      totalPositive: rows.filter(row => row.score > 0).length,
      totalNegative: rows.filter(row => row.score < 0).length,
      hardBlockedCount: rows.filter(row => row.isHardRecommendationBlock).length,
      maxAbsScore: Math.round(maxAbsScore * 1000) / 1000,
    },
  };
}

function _computeSuspiciousReasonCodes(row) {
  const codes = [];
  if ((row.plays || 0) > 0 && (row.fracsCount || 0) === 0 && (row.completions || 0) === 0 && (row.skips || 0) === 0) {
    codes.push('no_result');
  }
  if (row.signalStatus === 'negative' && (row.xScore || 0) > 0 && (row.fracsCount || 0) === 0 && !row.isDisliked) {
    codes.push('x_only');
  }
  if (row.avgFrac != null) {
    if (row.signalStatus === 'positive' && row.avgFrac < NEGATIVE_FRAC_THRESHOLD && !row.isFavorite) {
      codes.push('mismatch');
    }
    if (row.signalStatus === 'negative' && row.avgFrac >= 0.75 && (row.xScore || 0) <= 0 && !row.isDisliked) {
      codes.push('mismatch');
    }
  }
  if (row.resetAt) {
    const resetMs = Date.parse(row.resetAt);
    if (Number.isFinite(resetMs) && (Date.now() - resetMs) <= REVIEW_RESET_PENDING_WINDOW_MS && (row.fracsCount || 0) === 0) {
      codes.push('reset_pending');
    }
  }
  if (row.signalStatus === 'neutral' && ((row.plays || 0) > 0 || (row.xScore || 0) > 0)) {
    codes.push('uncertain');
  }
  return [...new Set(codes)];
}

async function _buildSuspiciousRecommendationData(opts = {}) {
  const includeIgnored = opts.includeIgnored === true;
  const logSnapshot = opts.logSnapshot !== false;
  const summary = await SessionLogger.loadProfileSummary();
  const resetMarkers = await SessionLogger.loadProfileResetMarkers();
  const taste = await getTasteSignal(true);
  const ignores = await _loadTasteReviewIgnores();
  const rowById = new Map((taste.rows || []).map(row => [row.id, row]));
  const fnMap = _getFilenameMap();
  const candidateIds = new Set();
  const groups = { negative: [], positive: [], uncertain: [] };
  const reasonCounts = {};

  for (const filename of Object.keys(summary.songs || {})) {
    const sid = fnMap[filename.toLowerCase()];
    if (sid != null) candidateIds.add(sid);
  }
  for (const row of (taste.rows || [])) {
    if (!row || row.id == null || !songs[row.id] || !songs[row.id].hasEmbedding) continue;
    if (Math.abs(Number(row.score) || 0) > 0.001 || (row.xScore || 0) > 0 || (row.plays || 0) > 0) {
      candidateIds.add(row.id);
    }
  }

  const allRows = [];
  for (const sid of candidateIds) {
    const s = songs[sid];
    if (sid == null || !s || !s.hasEmbedding) continue;
    const entry = (summary.songs || {})[s.filename] || null;
    const profileSummary = _summarizeProfileSummaryEntry(entry);
    const scoreSnapshot = rowById.has(sid)
      ? _normalizeTasteScoreSnapshot({
          direction: _tasteDirectionFromScore(rowById.get(sid).score || 0),
          score: rowById.get(sid).score || 0,
          sourceKind: rowById.get(sid).sourceKind || _tasteDirectionFromScore(rowById.get(sid).score || 0),
          verified: profileSummary.fracsCount > 0 || favorites.has(sid) || !!s.disliked,
        })
      : _computeTasteScoreSnapshot(sid, entry, {
          xScore: negativeScores[s.filename] || 0,
          isFavorite: favorites.has(sid),
          isDisliked: !!s.disliked,
        });
    const row = {
      id: sid,
      title: s.title,
      artist: s.artist,
      album: s.album,
      filename: s.filename,
      score: _roundSignal(scoreSnapshot && scoreSnapshot.score != null ? scoreSnapshot.score : 0),
      signalStatus: scoreSnapshot ? scoreSnapshot.direction : 'neutral',
      signalLabel: scoreSnapshot ? scoreSnapshot.direction : 'neutral',
      plays: profileSummary.plays,
      skips: profileSummary.skips,
      completions: profileSummary.completions,
      fracsCount: profileSummary.fracsCount,
      avgFrac: profileSummary.avgFrac,
      xScore: _roundSignal(negativeScores[s.filename] || 0),
      isFavorite: favorites.has(sid),
      isDisliked: !!s.disliked,
      lastPlayedAt: entry && entry.lastPlayedAt ? entry.lastPlayedAt : null,
      resetAt: resetMarkers[s.filename] || null,
    };
    row.reasonCodes = _computeSuspiciousReasonCodes(row);
    if (row.reasonCodes.length === 0) continue;
    row.reasonChips = row.reasonCodes.map(code => ({
      code,
      label: TASTE_REVIEW_REASON_META[code] ? TASTE_REVIEW_REASON_META[code].label : code,
      tone: TASTE_REVIEW_REASON_META[code] ? TASTE_REVIEW_REASON_META[code].tone : 'warn',
    }));
    row.reviewFingerprint = _buildTasteReviewFingerprint(row);
    allRows.push(row);
  }

  const sorter = (a, b) => {
    const scoreDelta = Math.abs(b.score || 0) - Math.abs(a.score || 0);
    if (scoreDelta !== 0) return scoreDelta;
    const playDelta = (b.plays || 0) - (a.plays || 0);
    if (playDelta !== 0) return playDelta;
    return String(a.title || '').localeCompare(String(b.title || ''));
  };

  let suppressedCount = 0;
  for (const row of allRows) {
    const ignored = ignores[row.filename];
    if (!includeIgnored && ignored && ignored.fingerprint === row.reviewFingerprint) {
      suppressedCount++;
      continue;
    }
    const groupKey = row.signalStatus === 'negative'
      ? 'negative'
      : row.signalStatus === 'positive'
        ? 'positive'
        : 'uncertain';
    row.groupKey = groupKey;
    groups[groupKey].push(row);
    for (const code of row.reasonCodes) reasonCounts[code] = (reasonCounts[code] || 0) + 1;
  }

  groups.negative.sort(sorter);
  groups.positive.sort(sorter);
  groups.uncertain.sort(sorter);

  const visibleRows = [...groups.negative, ...groups.positive, ...groups.uncertain];
  const snapshotSignature = JSON.stringify({
    rows: visibleRows.map(row => `${row.filename}:${row.reviewFingerprint}`),
    suppressedCount,
  });
  if (logSnapshot && snapshotSignature !== _lastTasteReviewSnapshot) {
    _activity('taste', 'suspicious_review_snapshot_updated', 'Suspicious review recomputed', {
      total: visibleRows.length,
      negative: groups.negative.length,
      positive: groups.positive.length,
      uncertain: groups.uncertain.length,
      suppressed: suppressedCount,
      reasons: reasonCounts,
    }, { important: true, tags: ['review', 'suspicious'] });
    _lastTasteReviewSnapshot = snapshotSignature;
  }

  return {
    rows: visibleRows,
    groups,
    summary: {
      total: visibleRows.length,
      negative: groups.negative.length,
      positive: groups.positive.length,
      uncertain: groups.uncertain.length,
      suppressed: suppressedCount,
      reasonCounts,
      reasonCatalog: Object.entries(TASTE_REVIEW_REASON_META).map(([code, meta]) => ({ code, ...meta })),
    },
  };
}

async function getSuspiciousRecommendationData() {
  return _buildSuspiciousRecommendationData({ includeIgnored: false, logSnapshot: true });
}

async function ignoreSuspiciousRecommendation(songId) {
  const data = await _buildSuspiciousRecommendationData({ includeIgnored: true, logSnapshot: false });
  const row = (data.rows || []).find(item => item.id === songId);
  if (!row) return { ok: false };
  const ignoredAt = new Date().toISOString();
  const store = await _loadTasteReviewIgnores();
  store[row.filename] = {
    fingerprint: row.reviewFingerprint,
    ignoredAt,
    reasonCodes: row.reasonCodes.slice(),
    signalDirection: row.signalStatus,
    score: row.score,
  };
  await _saveTasteReviewIgnores();
  _activity('taste', 'suspicious_review_ignored', `Ignored suspicious review warning for "${row.title}"`, {
    ..._songRef(songId),
    reasonCodes: row.reasonCodes.slice(),
    signalKept: true,
    tasteScore: row.score,
    ignoredAt,
  }, { important: true, tags: ['review', 'ignore'] });
  return { ok: true, row, ignoredAt };
}

async function getRecentPlaybackSignalTimeline(limit = 30) {
  const desired = Math.max(1, Math.min(60, Number(limit) || 30));
  const persisted = await SessionLogger.loadRecentLogs(30);
  const byId = new Map();

  for (const evt of persisted) {
    if (evt.event !== 'playback_signal_applied') continue;
    const normalized = _normalizePlaybackSignalEvent(evt);
    if (!normalized) continue;
    byId.set(normalized.eventId, normalized);
  }
  for (const evt of _recentPlaybackSignalEvents) {
    const normalized = _normalizePlaybackSignalEvent(evt);
    if (!normalized) continue;
    byId.set(normalized.eventId, normalized);
  }

  return [...byId.values()]
    .sort((a, b) => b.timestampMs - a.timestampMs)
    .slice(0, desired);
}

/**
 * Reset one song's profile entry. Song will slowly re-earn its place as the
 * user listens to it again. Rebuilds profileVec after the reset.
 */
async function rebuildProfileVec() {
  if (_recommendationRebuildTimer) {
    clearTimeout(_recommendationRebuildTimer);
    _recommendationRebuildTimer = null;
  }
  _recommendationRebuildReason = null;
  _recommendationRebuildOpts = { refreshQueue: false, refreshDiscover: false };
  _recommendationRebuildPending = false;
  return _performRecommendationRebuild('manual_profile_rebuild', {
    refreshQueue: true,
    refreshDiscover: true,
  });
}

async function resetSongRecommendationHistory(songId, opts = {}) {
  const s = songs[songId];
  if (!s) return { ok: false };
  const summaryBefore = await SessionLogger.loadProfileSummary();
  const entryBefore = (summaryBefore.songs || {})[s.filename] || null;
  const xScoreBefore = _roundSignal(negativeScores[s.filename] || 0);
  const similarityBoostBefore = _roundSignal(similarityBoostScores[s.filename] || 0);
  const tasteBefore = _computeTasteScoreSnapshot(songId, entryBefore, { xScore: xScoreBefore });
  const resetAt = new Date().toISOString();
  const ok = await SessionLogger.resetSongRecommendationHistory(s.filename, { resetAt });
  if (ok && log && typeof log.applyRecommendationReset === 'function') {
    log.applyRecommendationReset(s.filename);
  }
  if (opts.clearNegativeScore === true && negativeScores[s.filename]) {
    delete negativeScores[s.filename];
    await _saveNegativeScores();
    _activity('taste', 'negative_score_reset', `Negative score reset for "${s.title}"`, {
      ..._songRef(songId),
      resetAt,
    }, { important: true, tags: ['negative', 'reset'] });
  }
  if (opts.clearNegativeScore === true && similarityBoostScores[s.filename]) {
    delete similarityBoostScores[s.filename];
    await _saveSimilarityBoostScores();
  }
  if (ok) {
    profileVec = await buildProfileVec('reset_song_recommendation_history');
    _invalidateForYouCache('reset_song_recommendation_history');
    _invalidateUnexploredClustersCache('reset_song_recommendation_history');
    const refreshQueueApplied = _refreshDynamicRecommendations('reset_song_recommendation_history');
    _emitRecommendationStatus({
      phase: 'completed',
      reason: 'reset_song_recommendation_history',
      version: _recommendationPolicySnapshot.version,
      refreshQueueApplied,
      refreshDiscoverSuggested: true,
    });
    const summaryAfter = await SessionLogger.loadProfileSummary();
    const entryAfter = (summaryAfter.songs || {})[s.filename] || null;
    const xScoreAfter = _roundSignal(negativeScores[s.filename] || 0);
    const similarityBoostAfter = _roundSignal(similarityBoostScores[s.filename] || 0);
    const tasteAfter = _computeTasteScoreSnapshot(songId, entryAfter, { xScore: xScoreAfter });
    _activity('taste', 'recommendation_reset_applied', `Taste signal reset applied for "${s.title}"`, {
      ..._songRef(songId),
      action: opts.clearNegativeScore === true ? 'reset_history_and_x' : 'reset_history',
      summaryBefore: _summarizeProfileSummaryEntry(entryBefore),
      summaryAfter: _summarizeProfileSummaryEntry(entryAfter),
      tasteBefore,
      tasteAfter,
      xScoreBefore,
      xScoreAfter,
      similarityBoostBefore,
      similarityBoostAfter,
      resetAt,
    }, { important: true, tags: ['taste', 'reset', 'profile'] });
    return {
      ok: true,
      action: opts.clearNegativeScore === true ? 'reset_history_and_x' : 'reset_history',
      resetAt,
      summaryBefore: _summarizeProfileSummaryEntry(entryBefore),
      summaryAfter: _summarizeProfileSummaryEntry(entryAfter),
      tasteBefore,
      tasteAfter,
      xScoreBefore,
      xScoreAfter,
      similarityBoostBefore,
      similarityBoostAfter,
    };
  }
  return { ok: false };
}

async function resetSongProfileWeight(songId) {
  return resetSongRecommendationHistory(songId, { clearNegativeScore: true });
}

// --- Recently Played ---

async function loadRecentlyPlayed(limit = 20) {
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

function getLastAddedSongs(limit = 20) {
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
//   - _forYouAutoRefreshCounter hits FORYOU_LISTEN_WINDOW qualified listens
//   - resetSongProfileWeight or embeddings-ready explicitly invalidate
let _forYouCache = null;
let _forYouAutoRefreshCounter = 0;
let _forYouRecentShown = [];
const FORYOU_LISTEN_WINDOW = 5; // auto-refresh For You after every 5 qualified listens
const FORYOU_POOL_SIZE = 120;
const FORYOU_SHOW_COUNT = 15;

function _invalidateForYouCache(reason = 'unspecified') {
  _forYouCache = null;
  _forYouAutoRefreshCounter = 0;
  _activity('engine', 'for_you_cache_invalidated', `For You cache invalidated (${reason})`, { reason }, { important: false, tags: ['cache'] });
}

function _mergeRecommendationRebuildOpts(base = {}, incoming = {}) {
  return {
    refreshQueue: !!(base.refreshQueue || incoming.refreshQueue),
    refreshDiscover: !!(base.refreshDiscover || incoming.refreshDiscover),
  };
}

function _refreshDynamicRecommendations(reason = 'signal_rebuild') {
  if (!recToggle) return false;
  if (state.timelineMode === 'explicit') return false;
  if (state.playingAlbum || state.playingFavorites || state.playingPlaylist) return false;
  if (state.current != null && songs[state.current] && songs[state.current].hasEmbedding && state.queue.length > 0) {
    _softRefreshQueue();
    _activity('engine', 'queue_soft_refreshed_from_signal', `Queue soft-refreshed (${reason})`, {
      reason,
      queueLength: state.queue.length,
    }, { important: true, tags: ['queue', 'recommendation'] });
    return true;
  }
  _doRefresh(reason);
  return true;
}

async function _performRecommendationRebuild(reason = 'signal_rebuild', opts = {}) {
  _emitRecommendationStatus({ phase: 'running', reason });
  profileVec = await buildProfileVec(reason);
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
    _recommendationRebuildPending = true;
    return;
  }
  const reason = _recommendationRebuildReason || 'signal_rebuild';
  const opts = { ..._recommendationRebuildOpts };
  _recommendationRebuildReason = null;
  _recommendationRebuildOpts = { refreshQueue: false, refreshDiscover: false };
  _recommendationRebuildInFlight = true;
  Promise.resolve()
    .then(() => _performRecommendationRebuild(reason, opts))
    .catch(e => {
      console.error('Recommendation rebuild failed:', e);
      _emitRecommendationStatus({ phase: 'completed', reason });
    })
    .finally(() => {
      _recommendationRebuildInFlight = false;
      if (_recommendationRebuildPending || _recommendationRebuildReason) {
        _recommendationRebuildPending = false;
        _startRecommendationRebuildNow();
      }
    });
}

function scheduleRecommendationRebuild(reason = 'signal_rebuild', opts = {}) {
  _recommendationRebuildReason = reason;
  _recommendationRebuildOpts = _mergeRecommendationRebuildOpts(_recommendationRebuildOpts, opts);
  _emitRecommendationStatus({ phase: 'queued', reason });
  if (_recommendationRebuildTimer) clearTimeout(_recommendationRebuildTimer);
  const delay = opts.immediate === true ? 0 : RECOMMENDATION_REBUILD_DEBOUNCE_MS;
  _recommendationRebuildTimer = setTimeout(() => {
    _recommendationRebuildTimer = null;
    _startRecommendationRebuildNow();
  }, delay);
}

async function analyzeProfile(mostPlayedLimit = 15, opts = {}) {
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

function getBecauseYouPlayed(limit = 3, songsPerSection = 6, opts = {}) {
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

let _unexploredClustersCache = null;

function _invalidateUnexploredClustersCache(reason = 'unspecified') {
  _unexploredClustersCache = null;
  _activity('engine', 'unexplored_cache_invalidated', `Unexplored cache invalidated (${reason})`, { reason }, { important: false, tags: ['cache'] });
}

async function getUnexploredClusters(numClusters = 3, songsPerCluster = 6, opts = {}) {
  if (!rec || embeddings.length < 15) return [];
  const forceRefresh = opts.refresh === true;
  const avoidSongIds = new Set(opts.avoidSongIds || []);
  if (!forceRefresh && _unexploredClustersCache) return _unexploredClustersCache;

  const K = 15;
  let labels;
  try {
    labels = rec.computeClusters(K, 30);
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

// --- Helper: embedding index -> song id ---

function _embIdxToSongId(embIdx) {
  for (const s of songs) {
    if (s.embeddingIndex === embIdx) return s.id;
  }
  return -1; // no matching song (ghost embedding slot)
}

// Build a fast reverse map (call after songs stabilize)
let _embIdxMap = null;
function _ensureEmbIdxMap() {
  if (_embIdxMap && _embIdxMap.size === songs.length) return;
  _embIdxMap = new Map();
  for (const s of songs) {
    if (s.embeddingIndex != null) _embIdxMap.set(s.embeddingIndex, s.id);
  }
}

function _fastEmbIdxToSongId(embIdx) {
  _ensureEmbIdxMap();
  return _embIdxMap.get(embIdx) ?? -1; // -1 = no matching song (ghost embedding slot)
}

// --- Rec Toggle ---

function setRecToggle(on) {
  recToggle = on;
  if (state.timelineMode === 'explicit') return;
  if (!on && state.current != null && !state.playingAlbum && !state.playingFavorites && !state.playingPlaylist) {
    // Switch to shuffle mode — rebuild queue with random songs
    _buildShuffleQueue();
  } else if (on && state.current != null && songs[state.current].hasEmbedding
             && !state.playingAlbum && !state.playingFavorites && !state.playingPlaylist) {
    // Switch back to recs — preserve manual "Play Next" items.
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
  queueShuffleEnabled = !!on;
  if (queueShuffleEnabled) shuffleQueue();
}

function getQueueShuffleEnabled() { return queueShuffleEnabled; }

function _buildShuffleQueue() {
  // Preserve manual "Play Next" items across queue rebuilds.
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

function _songIdsToEmbExclude(songIds) {
  const embExclude = new Set();
  for (const sid of songIds) {
    if (sid < songs.length && songs[sid].hasEmbedding) {
      embExclude.add(songs[sid].embeddingIndex);
    }
  }
  return embExclude;
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
  _currentPlaybackInstanceId += 1;
  _capturedPlaybackInstanceId = null;
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
    _forYouAutoRefreshCounter++;
    _activity('playback', 'song_classified_complete', `Classified "${prevSong.title}" as completed`, {
      ..._songRef(songId),
      fraction: prevFraction,
      transitionAction,
    }, { important: true, tags: ['listen', 'complete'] });
    if (_forYouAutoRefreshCounter >= FORYOU_LISTEN_WINDOW) _invalidateForYouCache('listen_window');
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
    _capturedPlaybackInstanceId = _currentPlaybackInstanceId;
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
  if (!_embeddingsReady || embIdx == null || !embeddings[embIdx]) {
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

  const hasCurrentEmb = _embeddingsReady
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
  if (!recToggle || !_embeddingsReady || state.queue.length < FROZEN_ZONE) return;
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
  } else if (_embeddingsReady && songs[songId].hasEmbedding) {
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

  profileVec = await buildProfileVec();
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

// Allow UI to report current playback progress for insights
let _liveListenFraction = null;
function setLiveListenFraction(fraction) {
  _liveListenFraction = fraction;
}

function getInsights() {
  const insights = {};

  // Current song info — include live listen fraction
  if (state.current != null && state.current < songs.length) {
    const s = songs[state.current];
    insights.currentSong = {
      id: s.id, title: s.title, artist: s.artist, hasEmbedding: s.hasEmbedding,
      liveFraction: _liveListenFraction,
    };
  }

  // Build a merged listened list: state.listened + current song's live fraction
  const mergedListened = [...state.listened];
  if (state.current != null && _liveListenFraction != null) {
    const existing = mergedListened.find(s => s.id === state.current);
    if (existing) {
      // Update to max of recorded and live
      existing._displayFraction = Math.max(existing.listen_fraction, _liveListenFraction);
    } else {
      mergedListened.push({
        id: state.current,
        listen_fraction: _liveListenFraction,
        _displayFraction: _liveListenFraction,
        source: state.currentSource || 'playing',
        _live: true,
      });
    }
  }

  // Session vector analysis using merged data
  const qualified = [];
  const skipped = [];
  for (const s of mergedListened) {
    if (!songs[s.id] || !songs[s.id].hasEmbedding) continue;
    const frac = s._displayFraction || s.listen_fraction;
    if (!_isSkipClassification(frac)) qualified.push({ ...s, listen_fraction: frac });
    else skipped.push({ ...s, listen_fraction: frac });
  }

  insights.session = {
    totalListened: mergedListened.length,
    qualifiedForRec: qualified.length,
    skippedUsedAsNegative: skipped.length,
    qualificationThreshold: `> ${Math.round(LISTEN_SKIP_THRESHOLD * 100)}% listened`,
    listenedSongs: mergedListened.map(s => {
      const frac = s._displayFraction || s.listen_fraction;
      const enc = s.encounters || 1;
      return {
        id: s.id,
        title: songs[s.id] ? songs[s.id].title : '?',
        fraction: Math.round(frac * 100) + '%',
        weight: Math.round(frac * frac * 100) + '%',
        status: s._live ? 'playing now' : (!_isSkipClassification(frac) ? 'qualified' : 'skipped (negative signal)'),
        isLive: !!s._live,
        encounters: enc,
        skips: s.skips || 0,
        plays: s.plays || 0,
      };
    }),
  };

  // Blending weights
  const nListened = state.listened.length;
  const hasSession = qualified.length >= 2;
  const hasProfile = profileVec != null;

  if (state.current != null && songs[state.current] && songs[state.current].hasEmbedding) {
    if (!hasSession && !hasProfile) {
      insights.blend = { currentSong: '100%', session: '0%', profile: '0%', mode: 'Single song only' };
    } else if (!hasSession && hasProfile) {
      const t = Math.min(nListened / 8, 1);
      const wC = Math.round((0.5 + 0.1 * t) * 100);
      insights.blend = { currentSong: wC + '%', session: '0%', profile: (100 - wC) + '%', mode: 'Current + profile (no session yet)' };
    } else if (hasSession && !hasProfile) {
      const t = Math.min(nListened / 10, 1);
      const wC = Math.round((0.6 - 0.2 * t) * 100);
      insights.blend = { currentSong: wC + '%', session: (100 - wC) + '%', profile: '0%', mode: 'Current + session (no profile yet)' };
    } else {
      const t = Math.min(nListened / 10, 1);
      let wC = 0.50 - 0.12 * t;
      let wS = 0.52 * t;
      let wP = 1 - wC - wS;
      if (wP < 0.08) wP = 0.08;
      const total = wC + wS + wP;
      wC /= total; wS /= total; wP /= total;
      insights.blend = {
        currentSong: Math.round(wC * 100) + '%',
        session: Math.round(wS * 100) + '%',
        profile: Math.round(wP * 100) + '%',
        mode: 'Full blend (current + session + profile)',
      };
    }
  } else {
    insights.blend = { currentSong: 'N/A', session: 'N/A', profile: 'N/A', mode: 'No embedding for current song' };
  }

  // Profile info
  insights.profile = {
    exists: profileVec != null,
    favoritesCount: favorites.size,
  };

  // Queue info with zone breakdown
  const qLen = state.queue.length;
  insights.queue = {
    size: qLen,
    mode: state.timelineMode === 'explicit' ? 'List' : (recToggle ? 'AI' : 'Shuffle'),
    label: state.sessionLabel,
    playingAlbum: state.playingAlbum,
    playingFavorites: state.playingFavorites,
    frozenZone: Math.min(FROZEN_ZONE, qLen),
    stableZone: Math.max(0, Math.min(STABLE_ZONE, qLen) - FROZEN_ZONE),
    fluidZone: Math.max(0, qLen - STABLE_ZONE),
  };

  // Top similarities for current song (if it has embedding)
  if (state.current != null && songs[state.current] && songs[state.current].hasEmbedding && rec) {
    const embIdx = songs[state.current].embeddingIndex;
    const topSimilar = rec.recommendSingle(embIdx, 8, new Set());
    insights.topSimilar = topSimilar.map(item => {
      const sid = _fastEmbIdxToSongId(item.id);
      const s = songs[sid];
      if (!s || !s.filePath) return null;
      return { id: sid, title: s.title, artist: s.artist, similarity: item.similarity, artPath: s.artPath };
    }).filter(Boolean);
  }

  // Artist diversity in queue
  if (qLen > 0) {
    const artistSet = new Set();
    for (const q of state.queue) {
      if (songs[q.id]) artistSet.add(songs[q.id].artist);
    }
    insights.queueDiversity = {
      uniqueArtists: artistSet.size,
      totalSongs: qLen,
      ratio: Math.round((artistSet.size / qLen) * 100) + '%',
    };
  }

  // Embedding coverage
  const totalSongs = songs.length;
  const embeddedCount = songs.filter(s => s.hasEmbedding).length;
  insights.embeddingCoverage = {
    embedded: embeddedCount,
    total: totalSongs,
    percentage: Math.round((embeddedCount / totalSongs) * 100) + '%',
  };

  insights.onDeviceEmbedding = getEmbeddingStatus();

  // Session decay info - show how each qualified song contributes
  if (qualified.length > 0) {
    const decay = 0.75;
    const recentQualified = qualified.slice(-12);
    let totalWeight = 0;
    const decayWeights = [];
    for (let i = 0; i < recentQualified.length; i++) {
      const age = recentQualified.length - 1 - i;
      const decayW = Math.pow(decay, age);
      const fracSq = recentQualified[i].listen_fraction * recentQualified[i].listen_fraction;
      const combined = decayW * fracSq;
      totalWeight += combined;
      decayWeights.push({
        title: songs[recentQualified[i].id] ? songs[recentQualified[i].id].title : '?',
        recency: age,
        decayWeight: Math.round(decayW * 100) + '%',
        fracWeight: Math.round(fracSq * 100) + '%',
        combined: Math.round(combined * 100) + '%',
      });
    }
    insights.sessionDecay = {
      decayFactor: decay,
      window: 12,
      songs: decayWeights.reverse(), // most recent first
      totalWeight: Math.round(totalWeight * 100) + '%',
    };
  }

  // History stats
  insights.historyLength = state.history.length;
  insights.historyPos = state.historyPos;

  return insights;
}

// Helper: resolve song ID from filename (stable across sessions)
function _filenameToId(fn) {
  if (!fn) return null;
  const fnMap = _getFilenameMap();
  const sid = fnMap[fn.toLowerCase()];
  return sid != null ? sid : null;
}

async function savePlaybackState(currentTimeSec, durationSec) {
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
      listenedFilenames: state.listened.map(entry => {
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

async function savePendingListenSnapshot(snapshot) {
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

async function loadPendingListenSnapshot() {
  try {
    const { value } = await Preferences.get({ key: PENDING_LISTEN_KEY });
    if (!value) return null;
    return _normalizePendingListenSnapshot(JSON.parse(value));
  } catch (e) {
    return null;
  }
}

async function clearPendingListenSnapshot(match = null) {
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
async function getLastPlayedDisplay() {
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

async function restorePlaybackState() {
  try {
    const { value } = await Preferences.get({ key: 'playback_state' });
    if (!value) return null;
    const data = JSON.parse(value);

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
          : (encounters > 0 && _isSkipClassification(listenFraction) ? 1 : 0);
        const plays = Number.isFinite(Number(entry.plays))
          ? Number(entry.plays)
          : (encounters > 0 && !_isSkipClassification(listenFraction) ? 1 : 0);
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
        _setExplicitPlayedIds(data.explicitPlayedFilenames.map(fn => _filenameToId(fn)).filter(id => id != null));
      } else {
        _setExplicitPlayedIds([]);
      }
    } else {
      _clearTimelineContext();
    }
    if (data.recToggle !== undefined) recToggle = data.recToggle;
    _lastLoggedPlaybackStartInstanceId = Math.max(0, Math.round(Number(data.loggedPlaybackStartInstanceId) || 0));
    _lastLoggedPlaybackStartSongId = data.loggedPlaybackStartFilename
      ? _filenameToId(data.loggedPlaybackStartFilename)
      : null;
    if (state.playingPlaylist && !_getPlaylistById(state.currentPlaylistId)) {
      state.playingPlaylist = false;
      state.currentPlaylistId = null;
    }
    if (state.timelineMode !== 'explicit') {
      _setExplicitPlayedIds([]);
      state.timelineMode = null;
      _syncDynamicTimelineFromState();
    } else if (state.timelineIndex < 0 && state.current != null) {
      state.timelineIndex = state.timelineItems.findIndex(item => item.id === state.current);
    }

    const s = songs[state.current];
    const isFav = favorites.has(s.id);
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

async function resetEngine() {
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
  _clearTimelineContext();
  recToggle = true;
  queueShuffleEnabled = false;
  _currentPlaybackInstanceId = 0;
  _capturedPlaybackInstanceId = null;
  _lastLoggedPlaybackStartInstanceId = 0;
  _lastLoggedPlaybackStartSongId = null;
  if (_recommendationRebuildTimer) {
    clearTimeout(_recommendationRebuildTimer);
    _recommendationRebuildTimer = null;
  }
  _recommendationRebuildInFlight = false;
  _recommendationRebuildPending = false;
  _recommendationRebuildReason = null;
  _recommendationRebuildOpts = { refreshQueue: false, refreshDiscover: false };

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
  favorites = new Set();
  negativeScores = {};
  similarityBoostScores = {};
  dislikedFilenames = new Set();
  _tasteReviewIgnores = {};
  _lastTasteReviewSnapshot = '';
  _recentPlaybackSignalEvents = [];

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

  profileVec = null;
  _recommendationPolicySnapshot = {
    rowsById: new Map(),
    hardExcludeSongIds: new Set(),
    fingerprint: '',
    version: 0,
    updatedAt: 0,
  };
  log = new SessionLogger();
}

async function clearPlaybackSession() {
  state.current = null;
  state.currentSource = null;
  state.queue = [];
  state.sessionLabel = '';
  state.playingFavorites = false;
  state.playingAlbum = false;
  state.playingPlaylist = false;
  state.currentPlaylistId = null;
  state.currentAlbumTracks = [];
  _clearTimelineContext();
  _currentPlaybackInstanceId = 0;
  _capturedPlaybackInstanceId = null;
  _lastLoggedPlaybackStartInstanceId = 0;
  _lastLoggedPlaybackStartSongId = null;
  await Preferences.remove({ key: 'playback_state' });
}

function shutdown() {
  if (log) log.shutdown();
  savePlaybackState();
  flushActivityLog().catch(() => {});
}

// --- Discover Page Cache ---
// Saves a snapshot of all discover page sections + queue state so next startup renders instantly.

let _discoverCacheDirty = false;
let _discoverCacheSaveTimer = null;
let _discoverCachePendingSnapshot = null;

async function saveDiscoverCache(snapshot = null) {
  try {
    const snap = snapshot && typeof snapshot === 'object' ? snapshot : null;
    const hasSnapshotKey = (key) => !!(snap && Object.prototype.hasOwnProperty.call(snap, key));
    const profile = hasSnapshotKey('profile') ? snap.profile : await analyzeProfile();
    const becauseYouPlayed = hasSnapshotKey('becauseYouPlayed') ? snap.becauseYouPlayed : getBecauseYouPlayed(3, 6);
    const unexplored = hasSnapshotKey('unexplored') ? snap.unexplored : await getUnexploredClusters(3, 8);
    const recentlyPlayed = hasSnapshotKey('recentlyPlayed') ? snap.recentlyPlayed : await loadRecentlyPlayed(20);
    const lastAdded = hasSnapshotKey('lastAdded') ? snap.lastAdded : getLastAddedSongs(20);
    const favs = hasSnapshotKey('favorites') ? snap.favorites : getFavoritesList();
    const st = hasSnapshotKey('state') ? (snap.state || {}) : getState();

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
function saveDiscoverCacheDebounced(snapshot = null) {
  _discoverCacheDirty = true;
  _discoverCachePendingSnapshot = snapshot && typeof snapshot === 'object' ? snapshot : null;
  if (_discoverCacheSaveTimer) clearTimeout(_discoverCacheSaveTimer);
  _discoverCacheSaveTimer = setTimeout(() => {
    const pendingSnapshot = _discoverCachePendingSnapshot;
    _discoverCachePendingSnapshot = null;
    saveDiscoverCache(pendingSnapshot).catch(() => {});
  }, 2000);
}

async function loadDiscoverCache() {
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
function validateDiscoverCache(cache) {
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

function isEmbeddingsReady() {
  return _embeddingsReady;
}

function getNativeQueueSnapshot() {
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

function getNativeQueueItems() {
  return getNativeQueueSnapshot().items;
}

function getUpcomingNativeItems() {
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

function syncCurrentFromNativeState(data, opts = {}) {
  const songId = _resolveSongIdFromNativePayload(data);
  if (songId == null || !songs[songId]) return null;
  state.current = songId;
  if (opts.currentSource) state.currentSource = opts.currentSource;
  if (state.history.length === 0 || state.history[state.history.length - 1] !== songId) {
    state.history.push(songId);
  }
  state.historyPos = state.history.length - 1;
  _syncDynamicTimelineFromState();
  if (opts.ensurePlaybackStart === true) {
    _ensurePlaybackStartLogged(songId, state.currentSource || 'native_restore', null, null, {
      playbackInstanceId: opts.playbackInstanceId,
    });
  }
  return _currentSongInfo();
}

function syncQueueFromNativeSnapshot(queueState, opts = {}) {
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
  return _currentSongInfo();
}

function onNativeAdvance(data, nativeQueueState = null) {
  const prevFraction = (data.prevFraction != null && data.prevFraction >= 0) ? data.prevFraction : null;
  const action = data.action || '';

  _markExplicitCurrentPlayed();
  const shouldRecordListen = !(action === 'neutral_skip' && (prevFraction == null || prevFraction <= NEUTRAL_SKIP_CAPTURE_THRESHOLD));
  if (shouldRecordListen) _recordListen(prevFraction, { transitionAction: action });

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
      _syncDynamicTimelineFromState();
    }
  }
  _beginPlaybackInstance();

  _ensurePlaybackStartLogged(songId, state.currentSource, null, prevFraction, {
    playbackInstanceId: Number(data && data.currentPlaybackInstanceId) || 0,
  });

  const needsRefresh = state.timelineMode !== 'explicit'
    && state.queue.length < 5
    && recToggle
    && !state.playingAlbum
    && !state.playingFavorites
    && !state.playingPlaylist;
  if (needsRefresh) {
    _cleanQueue();
    _doRefresh('queue_low');
  }

  return { songInfo: _currentSongInfo(), needsSync: needsRefresh };
}

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
  resetEngine,
  clearPlaybackSession,
  shutdown,
  getEmbeddingStatus,
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
  getRecentActivityEvents,
  getActivityLogStatus,
  flushActivityLog,
};
