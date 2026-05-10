import * as engine from './engine.js';
import { MusicBridge } from './music-bridge.js';
import { logActivity } from './activity-log.js';

export function createSongMenuSupport({
  esc,
  showStatusToast,
  refreshStateUI,
  refreshBrowseCollectionsUI,
  refreshPlaylistViews,
  showPlaylistPicker,
  removeSongFromPlaylistUI,
  showEmbeddingDetail,
  syncUpcomingNativeQueue,
  getLastProfile,
  renderDiscoverTiles,
  playOnlyUI,
  closeFullPlayer,
  getFullPlayerOpen,
  getCurrentPlaylistViewId,
  loadAndPlay,
  getListenFraction,
  getCurrentSong,
  setNativeAudioPlaying,
  setNativeFileLoaded,
  updatePlayIcon,
  pruneSongFromDiscoverCaches,
  rerenderCachedDiscoverViews,
  saveVisibleDiscoverCache,
  getActiveTab,
  setActiveTab,
  activateTab,
  renderSongs,
  renderAlbums,
  getAllAlbums,
  getAlbumsDirty,
  setAlbumsDirty,
}) {
  let _activeMenu = null;
  let _activeMenuSongId = null;
  let _activeMenuScrollTarget = null;
  let _activeMenuScrollHandler = null;

  function closeSongMenu() {
    if (_activeMenu) {
      _activeMenu.remove();
      _activeMenu = null;
    }
    _activeMenuSongId = null;
    document.removeEventListener('click', closeSongMenu);
    if (_activeMenuScrollTarget && _activeMenuScrollHandler) {
      _activeMenuScrollTarget.removeEventListener('scroll', _activeMenuScrollHandler, true);
    }
    _activeMenuScrollTarget = null;
    _activeMenuScrollHandler = null;
  }

  function showSongMenu(songId, btnEl) {
    if (_activeMenu && _activeMenuSongId === songId) {
      closeSongMenu();
      return;
    }
    closeSongMenu();
    if (getFullPlayerOpen()) closeFullPlayer();
    const songs = engine.getSongs();
    const song = songs[songId];
    if (!song) return;

    const isFav = engine.getFavoritesList().some(f => f.id === songId);
    const favLabel = isFav ? 'Remove from Favorites' : 'Add to Favorites';
    const isDis = !!song.disliked;
    const dislikeLabel = isDis ? 'Remove Dislike' : 'Dislike';
    const hasEmb = !!song.hasEmbedding;
    const embLabel = hasEmb ? 'Remove Embedding' : 'Re-add Embedding';
    const currentPlaylistId = getCurrentPlaylistViewId();
    const currentPlaylistMeta = currentPlaylistId ? engine.getPlaylistMeta(currentPlaylistId) : null;
    const canRemoveFromCurrentPlaylist = !!(currentPlaylistMeta && engine.isSongInPlaylist(currentPlaylistId, songId));

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
      closeSongMenu();
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
        removeSongFromPlaylistUI(songId, getCurrentPlaylistViewId());
      } else if (action === 'togglefav') {
        const r = engine.toggleFavorite(songId);
        logActivity({ category: 'ui', type: 'favorite_menu_toggled', message: `${isFav ? 'Removed from' : 'Added to'} favorites via menu`, data: { songId, isFavorite: !!(r && r.isFavorite) }, tags: ['favorite'], important: true });
        const msg = isFav ? 'Removed from favorites'
          : (r && r.unDisliked ? 'Added to favorites (removed dislike)' : 'Added to favorites');
        showStatusToast(msg, 1500);
        refreshStateUI();
        refreshBrowseCollectionsUI();
        const lastProfile = getLastProfile();
        if (lastProfile) renderDiscoverTiles(lastProfile);
      } else if (action === 'toggledislike') {
        const r = engine.toggleDislike(songId);
        logActivity({ category: 'ui', type: 'dislike_menu_toggled', message: `${isDis ? 'Removed dislike from' : 'Disliked'} "${song.title}" via menu`, data: { songId, isDisliked: !!(r && r.isDisliked) }, tags: ['dislike'], important: true });
        const msg = isDis ? 'Dislike removed'
          : (r && r.unFavorited ? 'Disliked (removed favorite)' : 'Disliked');
        showStatusToast(msg, 1500);
        refreshStateUI();
        refreshBrowseCollectionsUI();
        const lastProfile = getLastProfile();
        if (lastProfile) renderDiscoverTiles(lastProfile);
      } else if (action === 'viewdetails') {
        showSongDetailsModal(songId);
      } else if (action === 'viewalbum') {
        if (getFullPlayerOpen()) closeFullPlayer();
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

    menu.style.visibility = 'hidden';
    menu.style.left = '0px';
    menu.style.top = '0px';
    menu.style.right = 'auto';
    document.body.appendChild(menu);
    _activeMenu = menu;
    _activeMenuSongId = songId;

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

    setTimeout(() => document.addEventListener('click', closeSongMenu), 0);
    _activeMenuScrollTarget = document;
    _activeMenuScrollHandler = () => closeSongMenu();
    document.addEventListener('scroll', _activeMenuScrollHandler, true);
  }

  async function confirmDeleteSong(songId) {
    const song = engine.getSongs()[songId];
    if (!song) return;
    const ok = confirm(`Delete "${song.title}" by ${song.artist}?\n\nThis permanently removes the file from your device. This cannot be undone.`);
    if (!ok) return;
    logActivity({ category: 'ui', type: 'delete_song_confirmed', message: `Confirmed delete for "${song.title}"`, data: { songId, wasCurrent: getCurrentSong() === songId }, tags: ['library'], important: true });

    if (getCurrentSong() === songId) {
      try {
        const nextInfo = engine.nextSong(getListenFraction(), 'delete_song');
        if (nextInfo) {
          await loadAndPlay(nextInfo);
          refreshStateUI();
        } else {
          try { MusicBridge.stopPlaybackService(); } catch (e) { /* ignore */ }
          setNativeAudioPlaying(false);
          setNativeFileLoaded(false);
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
        pruneSongFromDiscoverCaches(songId);
        rerenderCachedDiscoverViews();
        saveVisibleDiscoverCache();
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

    // Use the central tab activator so the panel-strip scroll-snap navigates
    // correctly (manual `.active`-class toggling no longer controls visibility
    // after the scroll-snap rewrite), the search overlay closes via
    // `_clearSearchInput`, and the albums-dirty render runs once.
    activateTab('albums', { pushHistory: true });

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

  return {
    closeSongMenu,
    showSongMenu,
    confirmDeleteSong,
    showSongDetailsModal,
    viewAlbumForSong,
    getActiveMenu: () => _activeMenu,
  };
}
