import * as engine from './engine.js';

export function createDiscoverUiSupport({
  esc,
  showStatusToast,
  getArtUrl,
  artOnErrorAttr,
  getLastProfile,
  setLastProfile,
  getCachedFavorites,
  setCachedFavorites,
  getCachedRecentlyPlayed,
  setCachedRecentlyPlayed,
  getCachedForYou,
  setCachedForYou,
  getCachedSimilar,
  setCachedSimilar,
  getCachedBecauseYouPlayed,
  setCachedBecauseYouPlayed,
  getCachedUnexplored,
  setCachedUnexplored,
  getViewAllItems,
  setViewAllItems,
  getCurrentViewAllType,
  getCurrentPlaylistViewId,
  getActiveTab,
  getCurrentSong,
  getNativeAudioPlaying,
  viewAllUI,
  isOnSubPage,
  hasDiscoverBackup,
}) {
  let _lastSimilarIds = '';
  let _similarFrozen = false;

  function renderSimilar() {
    const header = document.getElementById('similar-header');
    const el = document.getElementById('similar-list');
    if (!header || !el) return;

    // Freeze wins — keep snapshot cards visible regardless of other state.
    if (_similarFrozen && getCachedSimilar() && getCachedSimilar().length > 0) {
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
      setCachedSimilar(ins.topSimilar);
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
      if (!getCachedSimilar() || getCachedSimilar().length === 0) {
        showStatusToast('No similar songs to freeze yet', 1400);
        return;
      }
      _similarFrozen = true;
      updateSimilarFreezeBtn();
      showStatusToast('Similar frozen — song changes won’t update this list', 1800);
    }
  }

  function toggleSectionUI(id) {
    const content = document.getElementById(id + '-list');
    const arrow = document.getElementById(id + '-arrow');
    if (!content) return;
    const collapsed = content.classList.toggle('collapsed');
    if (arrow) arrow.textContent = collapsed ? '▶' : '▼'; // ▶ vs ▼
  }

  function _renderForYouList(forYou, opts = {}) {
    const forYouEl = document.getElementById('for-you-list');
    const badgeEl = document.getElementById('foryou-badge');
    if (!forYouEl) return;
    const fadeCls = opts.fade === false ? '' : ' fade-in';
    if (forYou && forYou.length > 0) {
      setCachedForYou(forYou);
      if (badgeEl) badgeEl.textContent = forYou.length;
      forYouEl.innerHTML = `<div class="hscroll${fadeCls}">` +
        forYou.map(s => cardHtml(s, s.similarity, 'manual_foryou', 'forYou')).join('') +
        '</div>';
    } else {
      setCachedForYou([]);
      if (badgeEl) badgeEl.textContent = '0';
      forYouEl.innerHTML = '<div class="empty-hint">Play songs to get personalized picks</div>';
    }
  }

  function renderProfile(profile, opts = {}) {
    setLastProfile(profile);
    // AI Embedding + Taste Signal buttons used to render here. They moved to
    // the sidebar (left-edge swipe) on 2026-05-10 — they're settings/configs,
    // not surfaces the user needs every time they open Discover. Keep
    // #profile-stats hidden so the panel doesn't reserve empty space at top.
    const profEl = document.getElementById('profile-stats');
    if (profEl) {
      profEl.innerHTML = '';
      profEl.style.display = 'none';
    }

    _renderForYouList(profile.forYou, opts);

    renderDiscoverTiles(profile);
  }

  function renderDiscoverTiles(profile) {
    const el = document.getElementById('browse-tiles');
    if (!el) return;

    const recent = getCachedRecentlyPlayed() || [];
    const mostPlayed = (profile && profile.mostPlayed) || [];
    const lastAdded = engine.getLastAddedSongs(4) || [];
    const neverPlayed = (profile && profile.neverPlayed) || [];
    const favorites = getCachedFavorites() || engine.getFavoritesList();
    const dislikedSongs = engine.getDislikedSongsList();

    const neverCount = profile && profile.stats ? profile.stats.neverPlayedCount : neverPlayed.length;

    const tileHtml = (label, count, items, viewAllKey, emptyMsg) => {
      const thumbs = items.slice(0, 4).map(s => {
        const art = getArtUrl(s);
        if (art) {
          const letter = (s.title || '?')[0].toUpperCase();
          const onerr = s && s.id != null ? artOnErrorAttr(s.id, letter, 'dtile-thumb-letter') : '';
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

  function _renderBecauseYouPlayedSections(sections, opts = {}) {
    const el = document.getElementById('because-you-played');
    if (!el) return;
    const fadeCls = opts.fade === false ? '' : ' fade-in';
    if (!sections || sections.length === 0) {
      setCachedBecauseYouPlayed([]);
      el.innerHTML = '<div class="empty-hint">Play music to get personal recommendations</div>';
      return;
    }
    setCachedBecauseYouPlayed(sections);
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
    _renderBecauseYouPlayedSections(getCachedBecauseYouPlayed() || [], opts);
  }

  function renderBecauseYouPlayed(opts = {}) {
    const sections = engine.getBecauseYouPlayed(3, 6, {
      avoidSourceIds: opts.refresh ? (getCachedBecauseYouPlayed() || []).map(s => s.sourceId) : [],
      avoidRecIds: opts.refresh ? (getCachedBecauseYouPlayed() || []).flatMap(s => (s.recommendations || []).map(r => r.id)) : [],
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
      setCachedUnexplored([]);
      header.style.display = 'none';
      el.innerHTML = '';
      return;
    }
    const fadeCls = opts.fade === false ? '' : ' fade-in';
    header.style.display = '';
    setCachedUnexplored(sections);
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
    const sections = getCachedUnexplored() || [];
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
    const lp = getLastProfile();
    if (lp) {
      renderProfile(lp, { fade: opts.fade });
    } else {
      renderDiscoverTiles(lp);
    }
  }

  function refreshDiscoverPrimaryState() {
    renderSimilar();
    refreshVisibleDiscoverCardState();
  }

  function refreshVisibleDiscoverCardState() {
    if (getActiveTab() !== 'discover' || isOnSubPage()) return;
    const panel = document.getElementById('panel-discover');
    if (!panel) return;
    panel.querySelectorAll('.hcard[data-song-id]').forEach(card => {
      const songId = parseInt(card.getAttribute('data-song-id') || '', 10);
      const isPlaying = Number.isFinite(songId) && getNativeAudioPlaying() && songId === getCurrentSong();
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
        ? Array.from(new Set((getCachedUnexplored() || []).flatMap(sec => (sec.songs || []).map(s => s.id))))
        : []
        : [],
    });
    _renderUnexploredSections(sections, opts);
  }

  function _buildDiscoverCacheSnapshot() {
    return {
      profile: getLastProfile() || null,
      becauseYouPlayed: getCachedBecauseYouPlayed() || [],
      unexplored: getCachedUnexplored() || [],
      recentlyPlayed: getCachedRecentlyPlayed() || [],
      favorites: getCachedFavorites() || [],
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
    setCachedForYou(_filterSongItems(getCachedForYou(), songId));
    const newSimilar = _filterSongItems(getCachedSimilar(), songId);
    setCachedSimilar(newSimilar);
    _lastSimilarIds = (newSimilar || []).map(s => s.id).join(',');
    setCachedBecauseYouPlayed(_filterBecauseYouPlayedSections(getCachedBecauseYouPlayed(), songId));
    setCachedUnexplored(_filterSectionSongGroups(getCachedUnexplored(), songId));
    setCachedRecentlyPlayed(_filterSongItems(getCachedRecentlyPlayed(), songId));
    setCachedFavorites(_filterSongItems(getCachedFavorites(), songId));
    setViewAllItems(_filterSongItems(getViewAllItems(), songId));

    const lp = getLastProfile();
    if (lp) {
      setLastProfile({
        ...lp,
        forYou: _filterSongItems(lp.forYou, songId),
        mostPlayed: _filterSongItems(lp.mostPlayed, songId),
        neverPlayed: _filterSongItems(lp.neverPlayed, songId),
        stats: lp.stats
          ? {
              ...lp.stats,
              neverPlayedCount: _filterSongItems(lp.neverPlayed, songId).length,
            }
          : lp.stats,
      });
    }
  }

  function _rerenderCachedDiscoverViews() {
    if (getCurrentViewAllType() && !getCurrentPlaylistViewId()) {
      viewAllUI(getCurrentViewAllType(), { items: getViewAllItems() }).catch(() => {});
    }
    if (getActiveTab() === 'discover' && !isOnSubPage() && !hasDiscoverBackup()) {
      renderDiscoverSnapshotFromCache({ fade: false });
      refreshVisibleDiscoverCardState();
      return;
    }
    if (getActiveTab() === 'browse' && !document.querySelector('#panel-browse .viewall-header')) {
      renderDiscoverTiles(getLastProfile());
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
    const disBadge = isDis ? '<span class="dislike-badge" title="Disliked">👎</span>' : '';
    const onclick = sectionKey
      ? `window._app.playFromSection(${s.id}, '${sectionKey}')`
      : `window._app.playSong(${s.id}, '${source}')`;
    const isPlaying = (s.id != null && s.id === getCurrentSong() && getNativeAudioPlaying());
    const playingCls = isPlaying ? ' playing' : '';
    const eqHtml = isPlaying ? '<div class="playing-eq"><span></span><span></span><span></span></div>' : '';
    const artUrl = getArtUrl(s);
    const onerr = s && s.id != null ? artOnErrorAttr(s.id, initial, '') : '';
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

  return {
    renderSimilar,
    updateSimilarFreezeBtn,
    toggleSimilarFreezeUI,
    toggleSectionUI,
    renderProfile,
    renderDiscoverTiles,
    renderBecauseYouPlayed,
    renderCachedBecauseYouPlayed,
    renderBecauseYouPlayedSections: _renderBecauseYouPlayedSections,
    renderCachedUnexplored,
    renderDiscoverSnapshotFromCache,
    refreshDiscoverPrimaryState,
    refreshVisibleDiscoverCardState,
    renderUnexploredClusters,
    saveVisibleDiscoverCache: _saveVisibleDiscoverCache,
    saveVisibleDiscoverCacheDebounced: _saveVisibleDiscoverCacheDebounced,
    pruneSongFromDiscoverCaches: _pruneSongFromDiscoverCaches,
    rerenderCachedDiscoverViews: _rerenderCachedDiscoverViews,
  };
}
