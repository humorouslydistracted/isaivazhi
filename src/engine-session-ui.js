import {
  songs, state, profileVec, favorites, recToggle, rec,
  LISTEN_SKIP_THRESHOLD, FROZEN_ZONE, STABLE_ZONE,
} from './engine-state.js';
import { _fastEmbIdxToSongId } from './engine-indexes.js';
import { getEmbeddingStatus } from './engine-embeddings.js';

let _liveListenFraction = null;

export function setLiveListenFraction(fraction) {
  _liveListenFraction = fraction;
}

function _isSkipClassification(fraction) {
  return fraction != null && fraction <= LISTEN_SKIP_THRESHOLD;
}

export function getInsights() {
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
