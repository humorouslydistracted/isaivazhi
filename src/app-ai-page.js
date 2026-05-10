import * as engine from './engine.js';

export function createAiPageSupport({
  esc,
  showStatusToast,
  getAllSongs,
  getDiscoverBackup,
  setDiscoverBackup,
  getDiscoverBackupPanelId,
  setDiscoverBackupPanelId,
  activityEntriesToCopyText,
  copyTextToClipboard,
  renderActivityLogHtml,
  getEmbDetailExpanded,
}) {
  let _embLogTab = 'common';
  let _embDetailScrollTargetId = null;

  function showEmbeddingDetail() {
    const st = engine.getEmbeddingStatus();
    const panel = document.getElementById('panel-discover');
    const wasDetailOpen = !!panel.querySelector('.emb-detail-page');
    const embScrollTargetId = _embDetailScrollTargetId;
    _embDetailScrollTargetId = null;
    const allSongs = getAllSongs();
    const _embDetailExpanded = getEmbDetailExpanded();

    if (!wasDetailOpen) {
      setDiscoverBackup(panel.innerHTML);
      setDiscoverBackupPanelId('panel-discover');
      const content = document.querySelector('.content');
      if (content) content.scrollTop = 0;
      _embLogTab = 'common';
    }
    const _embScrollEl = document.querySelector('.content') || panel;
    const _embScrollState = wasDetailOpen ? {
      page: _embScrollEl ? _embScrollEl.scrollTop : 0,
      unmatched: document.getElementById('unmatchedSongsList')?.scrollTop || 0,
      embLog: document.getElementById('embLogContainer')?.scrollTop || 0,
      activityLog: document.getElementById('activityLogContainer')?.scrollTop || 0,
    } : null;

    const removedSongs = engine.getRemovedEmbeddingSongs();
    const removedSet = new Set(removedSongs.map(s => s.id));

    // "Pending" = playable songs without an embedding that the user hasn't
    // manually removed. Includes both never-attempted songs and previously
    // failed retries, since both have hasEmbedding=false. The Embed Pending
    // button calls retryEmbedding which queues exactly this set.
    const pendingNewSongs = allSongs.filter(s => s.filePath && !s.hasEmbedding && !removedSet.has(s.id));
    const unembeddedSongs = allSongs.filter(s => !s.hasEmbedding);
    const playableSongs = allSongs.filter(s => s.filePath);
    // How many of the pending entries are previously failed retries (for the
    // "(N retries)" hint in the section header).
    const failedRetryCount = Math.min(pendingNewSongs.length, st.failedCount || 0);

    const logHtml = st.log.slice().reverse().map(entry => {
      const time = new Date(entry.time).toLocaleTimeString();
      const levelClass = entry.level === 'error' ? 'log-error' : entry.level === 'success' ? 'log-success' : entry.level === 'progress' ? 'log-progress' : 'log-info';
      return `<div class="emb-log-entry ${levelClass}"><span class="emb-log-time">${time}</span> ${esc(entry.message)}</div>`;
    }).join('');
    const activityRows = engine.getRecentActivityEvents({ limit: 300 }).slice().reverse();
    const activityHtml = renderActivityLogHtml(activityRows);

    const elapsed = st.startTime ? Math.round((Date.now() - st.startTime) / 1000) : 0;
    const successCount = st.log.filter(e => e.level === 'success').length;
    const errorCount = st.log.filter(e => e.level === 'error').length;

    const embeddedSongs = playableSongs.filter(s => s.hasEmbedding);
    const unmatched = st.unmatchedEmbeddings || [];

    const disp = (flag) => flag ? 'block' : 'none';

    let statusText = '';
    if (st.inProgress) {
      const done = st.log.filter(e => e.level === 'success').length;
      statusText = `<div class="emb-status-active"><div class="emb-spinner"></div> Embedding in progress: ${done} done, ${st.queueSize} remaining</div>
        <button class="emb-stop-btn" onclick="window._app.stopEmbedding()">Stop Embedding</button>`;
    } else if (pendingNewSongs.length === 0 && removedSongs.length === 0) {
      statusText = `<div class="emb-status-done"><span class="emb-done">&#10003;</span> All songs have AI embeddings</div>`;
    }

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

    // Pending count = playable + unembedded + not user-removed. Failed retries
    // are already part of pendingNewSongs (they have hasEmbedding=false), so
    // we don't add failedCount on top — the previous code did, which inflated
    // the displayed count by the number of failures.
    const pendingCount = pendingNewSongs.length;
    const retryHint = failedRetryCount > 0 ? ` <span class="emb-song-artist">(${failedRetryCount} retry)</span>` : '';
    const caret = (open) => open ? '&#9660;' : '&#9654;';

    const pendingNewHtml = `
      <div class="emb-pending-action">
        <div class="emb-pending-action-header" onclick="window._app.embToggle('pendingNew')">
          <span>${caret(_embDetailExpanded.pendingNew)} Embed Pending Songs (${pendingCount})${retryHint}</span>
        </div>
        <div class="emb-pending-action-body" style="display:${disp(_embDetailExpanded.pendingNew)};">
          ${pendingNewSongs.length === 0
            ? '<div class="emb-song-item" style="color:var(--text3);">No songs pending.</div>'
            : pendingNewSongs.slice(0, 100).map(s =>
                `<div class="emb-song-item"><span class="red-dot-inline"></span> ${esc(s.title)} <span class="emb-song-artist">— ${esc(s.artist)}</span></div>`
              ).join('') + (pendingNewSongs.length > 100 ? `<div class="emb-song-item" style="color:var(--text2);">... and ${pendingNewSongs.length - 100} more</div>` : '')
          }
          ${!st.inProgress && pendingCount > 0 ? `<button class="emb-retry-btn" onclick="window._app.embedPending()">Embed ${pendingCount} Pending Songs</button>` : ''}
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
        <button class="viewall-back" onclick="window._app.closeViewAll()">←</button>
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
    const _embDetailExpanded = getEmbDetailExpanded();
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
    const _embDetailExpanded = getEmbDetailExpanded();
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
      const actText = activityEntriesToCopyText(activity);
      const text = _embLogTab === 'embeddings' ? embText : actText;
      copyTextToClipboard(text, `${_embLogTab === 'embeddings' ? 'Embedding' : 'Common'} logs copied`);
    } catch (e) {
      showStatusToast('Copy failed', 1500);
    }
  }

  return {
    showEmbeddingDetail,
    embToggleSection: _embToggleSection,
    setEmbLogTab: _setEmbLogTab,
    setEmbConfirm: _setEmbConfirm,
    embScrollToPending: _embScrollToPending,
    copyEmbLogs: _copyEmbLogs,
  };
}
