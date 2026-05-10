// Taste, profile-vector, and signal-row helpers extracted from engine.js.
// Depends on engine-state.js (shared state) and engine-indexes.js (lookup maps).

import { Preferences } from '@capacitor/preferences';
import { vecNormalize, weightedAverage } from './recommender.js';
import { SessionLogger } from './logger.js';
import { logActivity } from './activity-log.js';
import { MusicBridge } from './music-bridge.js';
import {
  TOP_N, TUNING_DEFAULTS, SIMILARITY_BOOST_KEY, SIMILARITY_BOOST_MAX,
  SIMILARITY_NEIGHBOR_COUNT, SIMILARITY_NEIGHBOR_WEIGHTS,
  RECOMMENDATION_POOL_MULTIPLIER, RECOMMENDATION_POOL_PADDING,
  RECOMMENDATION_POSITIVE_BONUS_PER_POINT, RECOMMENDATION_POSITIVE_BONUS_MAX,
  RECOMMENDATION_NEGATIVE_PENALTY_PER_POINT, RECOMMENDATION_NEGATIVE_PENALTY_MAX,
  RECOMMENDATION_NEGATIVE_HARD_EXCLUDE_SHARE, RECOMMENDATION_NEGATIVE_HARD_EXCLUDE_FLOOR,
  NEG_SCORE_MAX, TASTE_REVIEW_IGNORE_KEY,
  MANUAL_PRIOR_HALF_LIFE_PLAYS, FAVORITE_PRIOR_BASE, DISLIKE_PRIOR_BASE,
  MAX_RECENT_PLAYBACK_SIGNALS, PROFILE_DAY_MS, PROFILE_HALF_LIFE_DAYS,
  NEGATIVE_PLAY_THRESHOLD, NEGATIVE_FRAC_THRESHOLD,
  EXTERNAL_DATA_DIR,
  songs, embeddings, rec, log, favorites,
  _tuning, _setTuning, similarityBoostScores, setSimilarityBoostScores,
  negativeScores, setNegativeScores,
  _recommendationPolicySnapshot, setRecommendationPolicySnapshot, _recommendationStatusCbs,
  _lastProfileWeightSnapshot, setLastProfileWeightSnapshot,
  _tasteReviewIgnores, setTasteReviewIgnores,
  dislikedFilenames, setDislikedFilenames,
  _lastLoggedPlaybackStartInstanceId, setLastLoggedPlaybackStartInstanceId,
  _lastLoggedPlaybackStartSongId, setLastLoggedPlaybackStartSongId,
  _recentPlaybackSignalEvents, setRecentPlaybackSignalEvents,
  state,
} from './engine-state.js';
import {
  _getFilenameMap, _getPathMap, _fastEmbIdxToSongId, _songIdsToEmbExclude,
} from './engine-indexes.js';

// --- Tuning load/save ---

async function _loadTuning() {
  try {
    const { value } = await Preferences.get({ key: 'tuning_params' });
    if (value) {
      const p = JSON.parse(value);
      _setTuning({ ...TUNING_DEFAULTS, ...p });
    }
  } catch (e) {}
}
async function _saveTuning() {
  try { await Preferences.set({ key: 'tuning_params', value: JSON.stringify(_tuning) }); } catch (e) {}
}
function getTuning() { return { ..._tuning }; }

// --- Negative / similarity-boost persistence ---

async function _loadNegativeScores() {
  try {
    const { value } = await Preferences.get({ key: 'negative_scores' });
    if (value) setNegativeScores(JSON.parse(value) || {});
  } catch (e) { setNegativeScores({}); }
}
async function _saveNegativeScores() {
  try { await Preferences.set({ key: 'negative_scores', value: JSON.stringify(negativeScores) }); } catch (e) {}
}
async function _loadSimilarityBoostScores() {
  try {
    const { value } = await Preferences.get({ key: SIMILARITY_BOOST_KEY });
    if (value) setSimilarityBoostScores(JSON.parse(value) || {});
  } catch (e) { setSimilarityBoostScores({}); }
}
async function _saveSimilarityBoostScores() {
  try { await Preferences.set({ key: SIMILARITY_BOOST_KEY, value: JSON.stringify(similarityBoostScores) }); } catch (e) {}
}

// --- Dislike persistence ---

async function _loadDislikes() {
  try {
    const { value } = await Preferences.get({ key: 'disliked_songs' });
    if (value) {
      const arr = JSON.parse(value);
      if (Array.isArray(arr)) setDislikedFilenames(new Set(arr));
    }
  } catch (e) { setDislikedFilenames(new Set()); }
}
async function _saveDislikes() {
  try {
    await Preferences.set({ key: 'disliked_songs', value: JSON.stringify([...dislikedFilenames]) });
  } catch (e) {}
}

// --- Taste-review ignore persistence ---

async function _loadTasteReviewIgnores() {
  if (_tasteReviewIgnores) return _tasteReviewIgnores;
  try {
    const { value } = await Preferences.get({ key: TASTE_REVIEW_IGNORE_KEY });
    const parsed = value ? JSON.parse(value) : {};
    setTasteReviewIgnores(parsed && typeof parsed === 'object' ? parsed : {});
  } catch (e) {
    setTasteReviewIgnores({});
  }
  return _tasteReviewIgnores;
}
async function _saveTasteReviewIgnores() {
  if (!_tasteReviewIgnores) setTasteReviewIgnores({});
  try {
    await Preferences.set({ key: TASTE_REVIEW_IGNORE_KEY, value: JSON.stringify(_tasteReviewIgnores) });
  } catch (e) {}
}

// --- Dislike flag application ---

function _applyDislikeFlags() {
  for (const s of songs) {
    if (s && s.filename) s.disliked = dislikedFilenames.has(s.filename);
  }
}

// --- Similarity boost accessors ---

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

// --- Recommendation status emit ---

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

// --- Negative score bump ---

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

// --- Song reference and activity helpers ---

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

// --- Session entry helpers ---

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

// --- Signal math utilities ---

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

// --- Playback-start deduplication ---

function _rememberLoggedPlaybackStart(songId, playbackInstanceId = 0) {
  setLastLoggedPlaybackStartSongId(songId != null ? songId : null);
  setLastLoggedPlaybackStartInstanceId(Math.max(0, Math.round(Number(playbackInstanceId) || 0)));
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

// --- Taste direction / score normalization ---

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

// --- Taste contribution computation ---

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

// --- Recommendation pool / adjust helpers ---

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

// --- Signal row building ---

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

// --- Signal row decoration and fingerprinting ---

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
  setRecommendationPolicySnapshot({
    rowsById,
    hardExcludeSongIds: new Set(decorated.hardExcludeSongIds),
    fingerprint,
    version: _recommendationPolicySnapshot.version + 1,
    updatedAt: Date.now(),
  });
  return decorated;
}

// --- Recommendation policy application ---

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

// --- Rec result → song item converters ---

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

// --- Recommendation query functions ---

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

// --- Similarity boost propagation ---

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

// --- Taste review fingerprint ---

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

// --- Playback signal events ---

function _rememberPlaybackSignalEvent(evt) {
  if (!evt) return;
  _recentPlaybackSignalEvents.push(evt);
  if (_recentPlaybackSignalEvents.length > MAX_RECENT_PLAYBACK_SIGNALS) {
    setRecentPlaybackSignalEvents(_recentPlaybackSignalEvents.slice(_recentPlaybackSignalEvents.length - MAX_RECENT_PLAYBACK_SIGNALS));
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

// --- Profile snapshot helpers ---

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
  setLastProfileWeightSnapshot(nextSnapshot);
}

// --- Profile summary listen stats ---

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

// --- Profile vector build ---

// Fix E: profile vector disk cache. Saves the normalized profileVec (and a
// fingerprint of every input that feeds the vector math) to disk after each
// successful build. On the next startup, if the fingerprint still matches the
// current state, the vector math is skipped entirely — saves the
// `weightedAverage` calls + `vecNormalize` + the negative-vector subtract.
//
// Side effects (`_buildSignalRowsFromSummary`, `_updateRecommendationPolicySnapshot`,
// snapshot/log writes) ALWAYS run regardless of cache state, because the
// recommendation policy snapshot is a Map of live row data that downstream
// scoring reads directly. We never trust the cache for those — only for the
// pure, deterministic vector math output.
//
// Fingerprint inputs (any change → cache miss → rebuild):
//   - top 30 positive song ids + their weights (rounded for stability)
//   - top 30 negative song ids + their weights
//   - each top song's current embeddingIndex (catches re-embeds)
//   - the negative-strength tuning parameter (used in the math as `beta`)
const PROFILE_VEC_CACHE_FILE = 'profile_vec_cache_v1.json';

function _profileVecFingerprint(profileSongs, negativeSongs) {
  const round = w => Math.round(w * 10000) / 10000;
  const idEmb = id => {
    const s = songs[id];
    return s && s.embeddingIndex != null ? s.embeddingIndex : -1;
  };
  return JSON.stringify({
    v: 1,
    pos: profileSongs.slice(0, 30).map(s => [s.id, idEmb(s.id), round(s.weight)]),
    neg: negativeSongs.slice(0, 30).map(s => [s.id, idEmb(s.id), round(s.weight)]),
    beta: round(_tuning.negativeStrength * 0.7),
    embCount: embeddings.length,
  });
}

async function _loadCachedProfileVec() {
  try {
    const result = await MusicBridge.readTextFile({ path: `${EXTERNAL_DATA_DIR}/${PROFILE_VEC_CACHE_FILE}` });
    if (!result || !result.exists || !result.content) return null;
    const data = JSON.parse(result.content);
    if (!data || data.version !== 1 || !Array.isArray(data.vec) || !data.fingerprint) return null;
    return data;
  } catch (e) {
    return null;
  }
}

async function _saveCachedProfileVec(vec, fingerprint) {
  try {
    await MusicBridge.writeTextFile({
      path: `${EXTERNAL_DATA_DIR}/${PROFILE_VEC_CACHE_FILE}`,
      content: JSON.stringify({
        version: 1,
        fingerprint,
        vec: Array.from(vec),
        dim: vec.length,
        savedAt: Date.now(),
      }),
    });
  } catch (e) { /* ignore — cache miss next time, harmless */ }
}

async function buildProfileVec(reason = 'profile_rebuild', opts = {}) {
  const logDeltas = opts.logDeltas !== false;
  const logRebuilt = opts.logRebuilt !== false;
  // Caller can opt out of the cache (e.g. forced refresh). Default: enabled.
  const useCache = opts.useCache !== false;
  let summary = await SessionLogger.loadProfileSummary();

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
  negativeSongs.sort((a, b) => b.weight - a.weight);

  // Fix E: fingerprint + cache check. Computed AFTER side-effect rows/policy
  // updates so the policy snapshot is always current regardless of cache hit.
  const fingerprint = _profileVecFingerprint(profileSongs, negativeSongs);
  let pv = null;
  let cacheHit = false;
  if (useCache) {
    const cached = await _loadCachedProfileVec();
    if (cached && cached.fingerprint === fingerprint && Array.isArray(cached.vec)
        && cached.vec.length > 0 && (embeddings.length === 0 || cached.dim === embeddings[0].length)) {
      pv = new Float32Array(cached.vec);
      cacheHit = true;
    }
  }

  const top30 = profileSongs.slice(0, 30);
  const negValid = negativeSongs.slice(0, 30).filter(s =>
    songs[s.id] && songs[s.id].embeddingIndex != null && embeddings[songs[s.id].embeddingIndex]);

  if (!cacheHit) {
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
    if (pv) pv = vecNormalize(pv);
  }

  // Persist the freshly built vector to disk for next session. Cached entries
  // are already on disk — no rewrite when cacheHit. Fire-and-forget.
  if (!cacheHit && pv) {
    _saveCachedProfileVec(pv, fingerprint);
  }

  const nextSnapshot = _makeProfileSnapshot(profileSongs, negativeSongs);
  if (logDeltas) {
    _logProfileWeightDeltas(nextSnapshot, reason);
  } else {
    setLastProfileWeightSnapshot(nextSnapshot);
  }
  if (logRebuilt) {
    _activity('engine', 'profile_rebuilt', cacheHit ? 'Profile vector reused from cache' : 'Profile vector rebuilt', {
      reason,
      positiveCount: profileSongs.length,
      negativeCount: negativeSongs.length,
      topPositiveCount: top30.length,
      topNegativeCount: negValid.length,
      hardBlockedCount: decorated.hardExcludeSongIds.size,
      cacheHit,
    }, { important: true, tags: ['profile', 'recommendation'] });
  }
  return pv;
}

// --- Song play stats ---

async function getSongPlayStats(songId) {
  const s = songs[songId];
  if (!s) return null;
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

export {
  _loadTuning,
  _saveTuning,
  getTuning,
  _loadNegativeScores,
  _saveNegativeScores,
  _loadSimilarityBoostScores,
  _saveSimilarityBoostScores,
  _loadDislikes,
  _saveDislikes,
  _loadTasteReviewIgnores,
  _saveTasteReviewIgnores,
  _applyDislikeFlags,
  _getSimilarityBoost,
  _setSimilarityBoostScore,
  _emitRecommendationStatus,
  onRecommendationRebuildStatus,
  _bumpNegativeScore,
  _songRef,
  _activity,
  _copySessionEntry,
  _snapshotSessionEntry,
  _formatSessionEntryDelta,
  _roundSignal,
  _signalWeightFromFraction,
  _summarizeSessionSignalEntry,
  _summarizeProfileSummaryEntry,
  _cloneProfileSummaryEntry,
  _previewProfileSummaryEntryEvent,
  _rememberLoggedPlaybackStart,
  bindCurrentPlaybackStartInstance,
  _ensurePlaybackStartLogged,
  _tasteDirectionFromScore,
  _normalizeTasteScoreSnapshot,
  _recencyMultiplierFromLastPlayed,
  _manualPriorWeight,
  _computeTasteContributions,
  _computeTasteScoreSnapshot,
  _getRecommendationPoolSize,
  _calculateRecommendationAdjust,
  _createSignalRow,
  _buildSignalRowsFromSummary,
  _decorateSignalRows,
  _buildRecommendationFingerprint,
  _updateRecommendationPolicySnapshot,
  _getRecommendationPolicyRow,
  _applyRecommendationPolicyToSongItems,
  _recResultsToSongItems,
  _nativeNearestResultsToSongItems,
  _embExcludeToFilepaths,
  _recommendFromQueryVec,
  _recommendFromSourceEmb,
  _getNearestNeighborSongIds,
  _applySimilarityBoostPropagation,
  _buildTasteReviewFingerprint,
  _rememberPlaybackSignalEvent,
  _normalizePlaybackSignalEvent,
  _makeProfileSnapshot,
  _logProfileWeightDeltas,
  _getSummaryListenStats,
  buildProfileVec,
  getSongPlayStats,
};
