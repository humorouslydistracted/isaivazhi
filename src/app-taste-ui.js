import * as engine from './engine.js';
import { logActivity } from './activity-log.js';

export function createTasteUiSupport({
  esc,
  showStatusToast,
  songThumb,
  getAllSongs,
  getActiveTab,
  getCurrentSong,
  getNativeAudioPlaying,
  getFullPlayerOpen,
  openFullPlayer,
  getListenFraction,
  loadAndPlay,
  refreshStateUI,
  loadFavorites,
  loadPlaylistsUI,
  renderDiscoverTiles,
  renderDiscoverSnapshotFromCache,
  refreshDiscoverPrimaryState,
  flushQueuedDiscoverRefresh,
  getLastProfile,
  activityEntriesToCopyText,
  copyTextToClipboard,
  getViewAllMeta,
  getDiscoverBackup,
  setDiscoverBackup,
  getDiscoverBackupPanelId,
  setDiscoverBackupPanelId,
  getViewAllItems,
  setViewAllItems,
  getCurrentViewAllType,
  setCurrentViewAllType,
  getCurrentPlaylistViewId,
  setCurrentPlaylistViewId,
}) {
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
  let _tasteSignalFilter = 'active';
  let _tasteSignalSort = 'positive';
  let _tasteSignalVisibleCount = 10;
  let _tasteSignalVisibleRows = [];
  const TASTE_SIGNAL_PAGE_SIZE = 10;

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
    return `${_formatSignedNumber(beforeDirect)} → ${_formatSignedNumber(afterDirect)} (Δ ${_formatSignedNumber(delta)})`;
  }

  function _formatSimilarityDeltaLine(after) {
    const afterBoost = after && after.similarityBoost != null ? Number(after.similarityBoost) || 0 : 0;
    return _formatSignedNumber(afterBoost);
  }

  function _formatTotalScoreLine(before, after) {
    const beforeScore = before && before.score != null ? Number(before.score) || 0 : 0;
    const afterScore = after && after.score != null ? Number(after.score) || 0 : 0;
    return `${_formatSignedNumber(beforeScore)} → ${_formatSignedNumber(afterScore)}`;
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

  // ===== TASTE WEIGHTS OVERLAY =====

  async function showTasteWeightsOverlay() {
    const panel = document.getElementById('panel-discover');
    if (!panel) return;
    if (!panel.querySelector('.taste-weights-page')) {
      setDiscoverBackup(panel.innerHTML);
      setDiscoverBackupPanelId('panel-discover');
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
            <button class="tuning-reset" title="Reset to default (${defaultPct}%)" onclick="window._app.resetTuning('${key}')">↺</button>
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
          <span>${_tastePlaybackExpanded ? '▼' : '▶'}</span>
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
          <span>${_tasteLogsExpanded ? '▼' : '▶'}</span>
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
          <span>${_tasteEngineExpanded ? '▼' : '▶'}</span>
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
              <div class="tw-engine-meta">${esc(ins.queue.mode)}${ins.queue.label ? ' · ' + esc(ins.queue.label) : ''}</div>
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
        <button class="viewall-back" onclick="window._app.closeTasteWeights()">←</button>
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
    const panel = getDiscoverBackupPanelId()
      ? document.getElementById(getDiscoverBackupPanelId())
      : document.getElementById('panel-discover');
    if (panel && getDiscoverBackup()) {
      panel.innerHTML = getDiscoverBackup();
      setDiscoverBackup(null);
      setDiscoverBackupPanelId(null);
      if (!flushQueuedDiscoverRefresh()) refreshDiscoverPrimaryState();
    } else if (panel) {
      if (!flushQueuedDiscoverRefresh()) renderDiscoverSnapshotFromCache({ fade: false });
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
      copyTextToClipboard(activityEntriesToCopyText(entries), 'Taste logs copied');
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
      copyTextToClipboard(_playbackTimelineEntriesToCopyText(entries), 'Playback signals copied');
    } catch (e) {
      showStatusToast('Copy failed', 1500);
    }
  }

  async function playTasteSignalRowUI(visibleIndex) {
    const row = _tasteSignalVisibleRows[visibleIndex];
    if (!row) return;
    if (row.id === getCurrentSong() && getNativeAudioPlaying()) {
      if (!getFullPlayerOpen()) openFullPlayer();
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

  // ===== VIEW ALL OVERLAY =====

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
      items = getAllSongs().filter(s => !s.hasEmbedding).map(s => ({
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

    setViewAllItems(items);
    setCurrentViewAllType(type);
    setCurrentPlaylistViewId(null);

    const panelId = getActiveTab() === 'browse' ? 'panel-browse' : 'panel-discover';
    const panel = document.getElementById(panelId);
    if (!panel) return;
    if (!panel.querySelector('.viewall-header')) {
      setDiscoverBackup(panel.innerHTML);
      setDiscoverBackupPanelId(panelId);
    }

    const listHtml = items.length > 0
      ? items.map((s) => {
          const badge = type === 'mostPlayed' && s.play_count ? `<div class="similarity">${s.play_count}×</div>` : '';
          const redDot = (s.hasEmbedding === false) ? '<span class="red-dot-inline"></span>' : '';
          const full = engine.getSongs()[s.id];
          const onclick = `window._app.playFromSection(${s.id}, 'viewAll')`;
          return `<div class="song-item" onclick="${onclick}">
            ${full ? songThumb(full) : `<div class="song-thumb song-thumb-letter">${esc((s.title||'?')[0])}</div>`}
            <div class="song-info">
              <div class="song-title">${redDot}${esc(s.title)}</div>
              <div class="song-artist">${esc(s.artist)} · ${esc(s.album || '')}</div>
            </div>
            ${badge}
            <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${s.id}, this)">&#8942;</div>
          </div>`;
        }).join('')
      : `<div class="playlist-empty-large">${esc(meta.empty)}</div>`;

    panel.innerHTML = `
      <div class="viewall-header">
        <button class="viewall-back" onclick="window._app.closeViewAll()">←</button>
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
    if (!getCurrentViewAllType() || getCurrentPlaylistViewId()) return;
    const content = document.querySelector('.content');
    const prevScrollTop = content ? content.scrollTop : 0;
    await viewAllUI(getCurrentViewAllType());
    const nextContent = document.querySelector('.content');
    if (!nextContent) return;
    const maxScroll = Math.max(0, nextContent.scrollHeight - nextContent.clientHeight);
    nextContent.scrollTop = Math.min(prevScrollTop, maxScroll);
  }

  function refreshBrowseCollectionsUI() {
    loadFavorites();
    if (getCurrentViewAllType() === 'favorites' || getCurrentViewAllType() === 'dislikedSongs') {
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
    setViewAllItems(items);
    setCurrentViewAllType('playlist');
    setCurrentPlaylistViewId(playlistId);

    const panel = document.getElementById('panel-browse');
    if (!panel) return;
    if (!panel.querySelector('.viewall-header')) {
      setDiscoverBackup(panel.innerHTML);
      setDiscoverBackupPanelId('panel-browse');
    }

    panel.innerHTML = `
      <div class="viewall-header">
        <button class="viewall-back" onclick="window._app.closeViewAll()">←</button>
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
              <div class="song-artist">${esc(s.artist)} · ${esc(s.album || '')}</div>
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
    if (getDiscoverBackup() && getDiscoverBackupPanelId()) {
      const panelId = getDiscoverBackupPanelId();
      const panel = document.getElementById(getDiscoverBackupPanelId());
      panel.innerHTML = getDiscoverBackup();
      setDiscoverBackup(null);
      setDiscoverBackupPanelId(null);
      setViewAllItems([]);
      setCurrentViewAllType(null);
      setCurrentPlaylistViewId(null);
      if (panelId === 'panel-browse') {
        loadPlaylistsUI();
        loadFavorites();
        if (getLastProfile()) renderDiscoverTiles(getLastProfile());
      } else if (panelId === 'panel-discover') {
        if (!flushQueuedDiscoverRefresh()) refreshDiscoverPrimaryState();
      }
    }
  }

  return {
    renderActivityLogHtml: _renderActivityLogHtml,
    showTasteWeightsOverlay,
    closeTasteWeightsOverlay,
    resetTasteWeightUI,
    copyTasteLogsUI,
    copyTastePlaybackSignalsUI,
    playTasteSignalRowUI,
    setTasteSignalFilterUI,
    setTasteSignalSortUI,
    showMoreTasteSignalUI,
    toggleTasteResetInfoUI,
    toggleTastePlayback: () => { _tastePlaybackExpanded = !_tastePlaybackExpanded; showTasteWeightsOverlay(); },
    toggleTasteLogs: () => { _tasteLogsExpanded = !_tasteLogsExpanded; showTasteWeightsOverlay(); },
    toggleTasteEngine: () => { _tasteEngineExpanded = !_tasteEngineExpanded; showTasteWeightsOverlay(); },
    tastePlaybackMore: () => { _tastePlaybackVisibleCount = Math.min(TASTE_PLAYBACK_MAX, _tastePlaybackVisibleCount + TASTE_PLAYBACK_PAGE_SIZE); showTasteWeightsOverlay(); },
    tuningInfo: (key) => { _toggleTuningInfoPopup(key); },
    viewAllUI,
    refreshCurrentViewAllUI,
    refreshBrowseCollectionsUI,
    openPlaylistUI,
    closeViewAll,
  };
}
