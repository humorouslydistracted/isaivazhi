// Profile weight inspection, suspicious recommendation review, playback signal timeline,
// profileVec rebuild, and per-song taste reset.
// Extracted from engine.js; no engine.js callbacks needed — all deps resolved via module imports.

import { SessionLogger } from './logger.js';
import {
  songs, negativeScores, similarityBoostScores, log, favorites,
  _recentPlaybackSignalEvents,
  _lastTasteReviewSnapshot, setLastTasteReviewSnapshot,
  setProfileVec,
  _recommendationPolicySnapshot,
  _recommendationRebuildTimer, setRecommendationRebuildTimer,
  setRecommendationRebuildReason, setRecommendationRebuildOpts, setRecommendationRebuildPending,
  NEGATIVE_FRAC_THRESHOLD, REVIEW_RESET_PENDING_WINDOW_MS, TASTE_REVIEW_REASON_META,
} from './engine-state.js';
import { _getFilenameMap } from './engine-indexes.js';
import {
  _buildSignalRowsFromSummary, _decorateSignalRows, _roundSignal,
  _computeTasteScoreSnapshot, _summarizeProfileSummaryEntry,
  _buildTasteReviewFingerprint, _normalizeTasteScoreSnapshot, _tasteDirectionFromScore,
  _normalizePlaybackSignalEvent,
  _loadTasteReviewIgnores, _saveTasteReviewIgnores,
  buildProfileVec, _saveNegativeScores, _saveSimilarityBoostScores,
  _emitRecommendationStatus, _activity, _songRef,
} from './engine-taste.js';
import {
  _invalidateForYouCache, _invalidateUnexploredClustersCache,
  _performRecommendationRebuild, _refreshDynamicRecommendations,
} from './engine-analytics.js';

// --- Profile weight inspection & reset ---

/**
 * Return every song that currently contributes to profileVec with its weight
 * and the raw components that produced it. Sorted by weight desc.
 */
export async function getProfileWeights() {
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

export async function getTasteSignal(includeAllEmbedded = false) {
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
    setLastTasteReviewSnapshot(snapshotSignature);
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

export async function getSuspiciousRecommendationData() {
  return _buildSuspiciousRecommendationData({ includeIgnored: false, logSnapshot: true });
}

export async function ignoreSuspiciousRecommendation(songId) {
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

export async function getRecentPlaybackSignalTimeline(limit = 30) {
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
export async function rebuildProfileVec() {
  if (_recommendationRebuildTimer) {
    clearTimeout(_recommendationRebuildTimer);
    setRecommendationRebuildTimer(null);
  }
  setRecommendationRebuildReason(null);
  setRecommendationRebuildOpts({ refreshQueue: false, refreshDiscover: false });
  setRecommendationRebuildPending(false);
  return _performRecommendationRebuild('manual_profile_rebuild', {
    refreshQueue: true,
    refreshDiscover: true,
  });
}

export async function resetSongRecommendationHistory(songId, opts = {}) {
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
    setProfileVec(await buildProfileVec('reset_song_recommendation_history'));
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

export async function resetSongProfileWeight(songId) {
  return resetSongRecommendationHistory(songId, { clearNegativeScore: true });
}
