import * as engine from './engine.js';

export function createPlaylistUi({
  esc,
  showStatusToast,
  refreshPlaylistViews,
  openPlaylist,
  closeViewAll,
  getCurrentPlaylistViewId,
}) {
  function closePlaylistPicker() {
    const overlay = document.getElementById('playlistPickerOverlay');
    if (overlay) overlay.remove();
  }

  function showPlaylistPicker(songId = null) {
    closePlaylistPicker();

    const playlists = engine.getPlaylists();
    const song = songId != null ? engine.getSongs()[songId] : null;
    const title = song ? 'Add to Playlist' : 'Create Playlist';
    const subtitle = song ? `<div class="playlist-modal-sub">${esc(song.title)} \u2022 ${esc(song.artist || '')}</div>` : '';

    const existingSection = song
      ? (playlists.length > 0
        ? `<div class="playlist-target-list">
            ${playlists.map(pl => {
              const already = engine.isSongInPlaylist(pl.id, songId);
              return `<button class="playlist-target-btn${already ? ' is-added' : ''}" ${already ? 'disabled' : ''} onclick="window._app.addSongToPlaylist('${pl.id}', ${songId})">
                <span class="playlist-target-name">${esc(pl.name)}</span>
                <span class="playlist-target-meta">${already ? 'Added' : `${pl.count} songs`}</span>
              </button>`;
            }).join('')}
          </div>`
        : '<div class="playlist-empty-inline">No playlists yet. Create one below.</div>')
      : (playlists.length > 0
        ? `<div class="playlist-existing-list">
            ${playlists.map(pl => `<button class="playlist-existing-chip" onclick="window._app.openPlaylist('${pl.id}'); window._app.closePlaylistPicker()">${esc(pl.name)}</button>`).join('')}
          </div>`
        : '<div class="playlist-empty-inline">No playlists yet. Create your first one below.</div>');

    const overlay = document.createElement('div');
    overlay.id = 'playlistPickerOverlay';
    overlay.className = 'sd-overlay';
    overlay.innerHTML = `
      <div class="sd-modal playlist-modal" onclick="event.stopPropagation()">
        <div class="sd-title">${title}</div>
        ${subtitle}
        ${song ? '<div class="playlist-modal-section-title">Available playlists</div>' : '<div class="playlist-modal-section-title">Existing playlists</div>'}
        ${existingSection}
        <div class="playlist-modal-section-title">${song ? 'Create new playlist' : 'New playlist'}</div>
        <div class="playlist-create-row">
          <input id="playlistNameInput" class="playlist-name-input" type="text" maxlength="60" placeholder="Playlist name">
          <button class="playlist-create-btn" onclick="window._app.createPlaylistFromModal(${songId != null ? songId : 'null'})">Create</button>
        </div>
        <button class="sd-close" onclick="window._app.closePlaylistPicker()">Close</button>
      </div>`;
    overlay.addEventListener('click', closePlaylistPicker);
    document.body.appendChild(overlay);

    const input = document.getElementById('playlistNameInput');
    if (input) {
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          createPlaylistFromModal(songId);
        }
      });
      setTimeout(() => input.focus(), 40);
    }
  }

  function createPlaylistFromModal(initialSongId = null) {
    const input = document.getElementById('playlistNameInput');
    const name = input ? input.value.trim() : '';
    const res = engine.createPlaylist(name, initialSongId != null ? Number(initialSongId) : null);
    if (!res || !res.ok) {
      showStatusToast(res && res.error ? res.error : 'Could not create playlist', 2000);
      return;
    }
    closePlaylistPicker();
    refreshPlaylistViews();
    if (initialSongId != null) {
      showStatusToast(`Added to new playlist "${res.playlist.name}"`, 2000);
    } else {
      showStatusToast(`Created playlist "${res.playlist.name}"`, 2000);
      openPlaylist(res.playlist.id);
    }
  }

  function addSongToPlaylistUI(playlistId, songId) {
    const res = engine.addSongToPlaylist(playlistId, songId);
    if (!res || !res.ok) {
      showStatusToast(res && res.error ? res.error : 'Could not add to playlist', 2000);
      return;
    }
    closePlaylistPicker();
    refreshPlaylistViews();
    showStatusToast(res.alreadyExists ? 'Song already in playlist' : 'Added to playlist', 1800);
  }

  function removeSongFromPlaylistUI(songId, playlistId = getCurrentPlaylistViewId()) {
    if (!playlistId) return;
    const res = engine.removeSongFromPlaylist(playlistId, songId);
    if (!res || !res.ok) {
      showStatusToast(res && res.error ? res.error : 'Could not remove from playlist', 2000);
      return;
    }
    refreshPlaylistViews();
    showStatusToast(res.removed ? 'Removed from playlist' : 'Song not in playlist', 1800);
  }

  function renamePlaylistUI(playlistId) {
    const meta = engine.getPlaylistMeta(playlistId);
    if (!meta) return;
    const nextName = prompt('Rename playlist', meta.name);
    if (nextName == null) return;
    const res = engine.renamePlaylist(playlistId, nextName);
    if (!res || !res.ok) {
      showStatusToast(res && res.error ? res.error : 'Could not rename playlist', 2000);
      return;
    }
    refreshPlaylistViews();
    showStatusToast('Playlist renamed', 1800);
  }

  function deletePlaylistUI(playlistId) {
    const meta = engine.getPlaylistMeta(playlistId);
    if (!meta) return;
    const ok = confirm(`Delete playlist "${meta.name}"?\n\nSongs will remain in your library.`);
    if (!ok) return;
    const res = engine.deletePlaylist(playlistId);
    if (!res || !res.ok) {
      showStatusToast(res && res.error ? res.error : 'Could not delete playlist', 2000);
      return;
    }
    if (getCurrentPlaylistViewId() === playlistId) {
      closeViewAll();
    } else {
      refreshPlaylistViews();
    }
    showStatusToast('Playlist deleted', 1800);
  }

  return {
    closePlaylistPicker,
    showPlaylistPicker,
    createPlaylistFromModal,
    addSongToPlaylistUI,
    removeSongFromPlaylistUI,
    renamePlaylistUI,
    deletePlaylistUI,
  };
}
