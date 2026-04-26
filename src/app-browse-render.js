export function createBrowseRenderSupport({
  esc,
  getArtUrl,
  resolveSongForArt,
  artOnErrorAttr,
  getCurrentSongId,
  isNativeAudioPlaying,
  getSongsMap,
}) {
  function clearSearch() {
    const input = document.getElementById('searchInput');
    input.value = '';
    input.dispatchEvent(new Event('input'));
    const clearBtn = document.getElementById('searchClear');
    if (clearBtn) clearBtn.style.display = 'none';
    input.focus(); // keep keyboard open
  }

  function songThumb(s) {
    const artUrl = getArtUrl(s);
    const fullSong = resolveSongForArt(s) || s;
    const songId = fullSong && fullSong.id != null ? fullSong.id : null;
    const initial = (fullSong && fullSong.title ? fullSong.title : '?')[0].toUpperCase();
    const disBadge = s && s.disliked ? '<span class="dislike-badge" title="Disliked">\uD83D\uDC4E</span>' : '';
    if (artUrl) {
      const onerr = songId != null ? artOnErrorAttr(songId, initial, 'song-thumb-letter') : '';
      return `<div class="song-thumb">${disBadge}<img src="${artUrl}" decoding="async" alt="" ${onerr}></div>`;
    }
    return `<div class="song-thumb song-thumb-letter">${disBadge}${esc(initial)}</div>`;
  }

  function renderSongs(list) {
    const sorted = [...list].sort((a, b) => a.title.localeCompare(b.title));
    document.getElementById('panel-songs').innerHTML = sorted.map((s) => {
      const redDot = !s.hasEmbedding ? '<span class="red-dot-inline"></span>' : '';
      const isPlay = (getCurrentSongId() === s.id && isNativeAudioPlaying());
      const eq = isPlay ? '<div class="playing-eq song-eq"><span></span><span></span><span></span></div>' : '';
      return `<div class="song-item ${isPlay ? 'playing' : ''}" onclick="window._app.playSong(${s.id})">
        ${songThumb(s)}${eq}
        <div class="song-info">
          <div class="song-title">${redDot}${esc(s.title)}</div>
          <div class="song-artist">${esc(s.artist)} \u00b7 ${esc(s.album)}</div>
        </div>
        <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${s.id}, this)">&#8942;</div>
      </div>`;
    }).join('');
  }

  function renderAlbums(list) {
    document.getElementById('panel-albums').innerHTML = list.map(a => {
      const trackIds = JSON.stringify(a.tracks.map(t => t.id));
      const songs = getSongsMap();
      const artTrack = a.tracks.find(t => {
        const full = songs[t.id];
        return full && full.filePath;
      });
      const albumArtUrl = artTrack ? getArtUrl(songs[artTrack.id]) : null;
      const albumArtContent = albumArtUrl
        ? `<img src="${albumArtUrl}" class="album-art-img" decoding="async" alt="" ${artTrack ? artOnErrorAttr(artTrack.id, a.name.charAt(0), '') : ''}>`
        : esc(a.name.charAt(0));
      return `<div class="album-item">
        <div class="album-header" onclick="window._app.toggleAlbum(this)">
          <div class="album-art">${albumArtContent}</div>
          <div class="album-info">
            <div class="album-name">${esc(a.name)}</div>
            <div class="album-meta">${esc(a.artist)} \u00b7 ${a.count} songs</div>
          </div>
          <div class="album-chevron">&#9654;</div>
        </div>
        <div class="album-tracks" data-track-ids='${trackIds}'>
          ${a.tracks.map((t) => {
            const trackSong = songs[t.id];
            return `
            <div class="song-item ${(getCurrentSongId() === t.id && isNativeAudioPlaying()) ? 'playing' : ''}" onclick="window._app.playFromAlbum(${t.id}, ${esc(trackIds)})">
              ${trackSong ? songThumb(trackSong) : `<div class="song-thumb song-thumb-letter">${esc(t.title[0])}</div>`}
              <div class="song-info">
                <div class="song-title">${esc(t.title)}</div>
                <div class="song-artist">${esc(t.artist)}</div>
              </div>
              <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${t.id}, this)">&#8942;</div>
            </div>`;
          }).join('')}
        </div>
      </div>`;
    }).join('');
  }

  function toggleAlbumUI(header) {
    header.nextElementSibling.classList.toggle('expanded');
    header.querySelector('.album-chevron').classList.toggle('expanded');
  }

  return {
    clearSearch,
    songThumb,
    renderSongs,
    renderAlbums,
    toggleAlbumUI,
  };
}
