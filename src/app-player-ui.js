import * as engine from './engine.js';
import { MusicBridge } from './music-bridge.js';
import { logActivity } from './activity-log.js';

export function createPlayerUiSupport({
  // State getters/setters
  getCurrentSong,
  getCurrentIsFav,
  setCurrentIsFav,
  getNativeAudioPlaying,
  getNativeFileLoaded,
  getNativeAudioDur,
  getNativeAudioPos,
  setNativeAudioPos,
  getShuffleOn,
  setShuffleOn,
  getLoopMode,
  setLoopMode,
  getFullPlayerOpen,
  setFullPlayerOpen,
  getActiveTab,
  getRecsShouldFocusCurrent,
  getInitRestoreComplete,
  getQuickRestoreInfo,
  setPendingStartupResume,
  getCachedForYou,
  getCachedSimilar,
  getCachedFavorites,
  getCachedBecauseYouPlayed,
  getCachedUnexplored,
  getViewAllItems,
  getCurrentViewAllType,
  // Function deps
  esc,
  showStatusToast,
  loadAndPlay,
  refreshStateUI,
  getListenFraction,
  persistPlaybackState,
  songThumb,
  dbg,
  notePlaybackIntent,
  shouldBlockRapidNav,
  scheduleRecsFocusCurrent,
  resolveSongForArt,
  enqueueSongArt,
  activateTab,
  syncUpcomingNativeQueue,
  updateModeIndicator,
  updateHeartIcon,
  getViewAllMeta,
}) {

  // ===== PLAY FROM CONTEXT =====

  async function playFromFavoritesUI(id) {
    const frac = getListenFraction();
    const info = engine.playFromFavorites(id, frac);
    if (info) {
      await loadAndPlay(info);
      const newIsFav = info.isFavorite || false;
      setCurrentIsFav(newIsFav);
      updateHeartIcon(newIsFav);
      refreshStateUI();
    }
  }

  async function playFromPlaylistUI(id, playlistId) {
    if (id === getCurrentSong() && getNativeAudioPlaying()) {
      if (!getFullPlayerOpen()) openFullPlayer();
      return;
    }
    const frac = getListenFraction();
    const song = engine.getSongs()[id];
    const meta = engine.getPlaylistMeta(playlistId);
    logActivity({
      category: 'ui',
      type: 'playlist_song_tapped',
      message: `Tapped "${song ? song.title : id}" from playlist "${meta ? meta.name : playlistId}"`,
      data: { songId: id, playlistId, playlistName: meta ? meta.name : '', prevFraction: frac, title: song ? song.title : '', filename: song ? song.filename : '' },
      tags: ['playlist', 'playback'],
      important: true,
    });
    const info = engine.playFromPlaylist(playlistId, id, frac);
    if (info) {
      await loadAndPlay(info);
      refreshStateUI();
    }
  }

  // ===== PROGRESS BAR =====

  function updateProgressUI(pos, dur) {
    const pct = dur > 0 ? (pos / dur * 100) : 0;
    document.getElementById('progressFill').style.width = pct + '%';
    const thumb = document.getElementById('seekThumb');
    if (thumb) thumb.style.left = pct + '%';
    document.getElementById('npTime').textContent = formatTime(pos);
    document.getElementById('npDuration').textContent = formatTime(dur);
    updateFullPlayerProgress(pos, dur);
  }

  function updatePlayIcon(paused) {
    document.getElementById('playIcon').innerHTML = paused
      ? '<path d="M8 5v14l11-7z"/>'
      : '<path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>';
    updateFullPlayerPlayIcon(paused);
  }

  // ===== SHUFFLE & LOOP =====

  function toggleShuffleUI() {
    const newShuffle = !getShuffleOn();
    setShuffleOn(newShuffle);
    const btn = document.getElementById('shuffleBtn');
    if (btn) { btn.classList.toggle('active-mode', newShuffle); btn.title = newShuffle ? 'Shuffle: keep remaining Up Next songs randomized' : 'Shuffle off'; }
    const fpShuffle = document.getElementById('fpShuffleBtn');
    if (fpShuffle) fpShuffle.classList.toggle('active-mode', newShuffle);
    engine.setQueueShuffleEnabled(newShuffle);
    showStatusToast(newShuffle ? 'Shuffle: remaining Up Next songs randomized' : 'Shuffle off', 1800);
    if (newShuffle) {
      refreshStateUI();
      syncUpcomingNativeQueue();
      scheduleRecsFocusCurrent();
    }
  }

  // Loop cycles: off → one → all → off
  // - off: play queue to end, then stop
  // - one: native MediaPlayer repeats the current song forever
  // - all: queue auto-refreshes on exhaustion (AI/shuffle keeps flowing)
  function toggleLoopUI() {
    const cur = getLoopMode();
    const newLoop = cur === 'off' ? 'one' : (cur === 'one' ? 'all' : 'off');
    setLoopMode(newLoop);
    const loopModeMap = { off: 0, one: 1, all: 2 };
    try { MusicBridge.setLoopMode({ mode: loopModeMap[newLoop] || 0 }); } catch (e) { /* ignore */ }
    const apply = (el) => {
      if (!el) return;
      el.classList.toggle('active-mode', newLoop !== 'off');
      el.classList.toggle('loop-one', newLoop === 'one');
      el.classList.toggle('loop-all', newLoop === 'all');
      el.title = newLoop === 'one' ? 'Repeat current song' : (newLoop === 'all' ? 'Repeat all (auto-refresh queue)' : 'Loop off');
    };
    apply(document.getElementById('loopBtn'));
    apply(document.getElementById('fpLoopBtn'));
    const msg = newLoop === 'one' ? 'Loop: repeat this song' : (newLoop === 'all' ? 'Loop: repeat all' : 'Loop off');
    showStatusToast(msg, 1800);
  }

  // ===== REC TOGGLE =====

  function goToQueueUI() {
    if (getFullPlayerOpen()) closeFullPlayer();
    activateTab('recs', { resetScroll: false });
    scheduleRecsFocusCurrent();
  }

  function toggleRecUI(on) {
    engine.setRecToggle(on);
    const toggle = document.getElementById('recToggle');
    if (toggle) toggle.checked = on;
    updateModeIndicator();
    refreshStateUI();
    syncUpcomingNativeQueue();
    showStatusToast(on ? 'AI recommendations on' : 'Shuffle mode', 1500);
  }

  // ===== RENDER RECS / HISTORY =====

  function renderRecs(data) {
    const list = document.getElementById('recs-list');
    const label = document.getElementById('recs-session-label');
    if (data.sessionLabel) {
      label.textContent = data.sessionLabel;
      label.style.display = 'block';
    } else {
      label.style.display = 'none';
    }

    // Sync rec toggle checkbox
    const toggle = document.getElementById('recToggle');
    if (toggle) toggle.checked = data.recToggle;

    const timeline = data.timeline && Array.isArray(data.timeline.items) ? data.timeline : { items: [], explicit: false };
    if ((!timeline.items || timeline.items.length === 0) && (!data.queue || data.queue.length === 0)) {
      list.innerHTML = '<div class="empty-state">Play a song to get recommendations</div>';
      return;
    }

    const allSongsData = engine.getSongs();
    const rowHtml = (s) => {
      const full = allSongsData[s.id];
      const manualBadge = s.manual ? '<span class="queue-manual-badge" title="Added via Play Next">▶</span>' : '';
      const roleBadge = s.role === 'current'
        ? '<span class="timeline-badge timeline-badge-current">Now</span>'
        : (s.role === 'previous'
            ? `<span class="timeline-badge">${timeline.explicit ? 'Earlier' : 'Played'}</span>`
            : '');
      const similarity = s.role === 'upcoming' && parseFloat(s.similarity) > 0
        ? `<div class="similarity">${Math.round(parseFloat(s.similarity) * 100)}%</div>`
        : '';
      const removeBtn = s.role === 'upcoming' && s.queueIndex != null
        ? `<div class="queue-remove-btn" onclick="event.stopPropagation(); window._app.removeFromQueue(${s.queueIndex})" title="Remove from Up Next">&times;</div>`
        : '';
      const rowClass = s.role === 'current' ? 'playing' : '';
      return `
    <div class="song-item ${rowClass}" onclick="window._app.playTimelineIndex(${s.timelineIndex})">
      ${full ? songThumb(full) : `<div class="song-thumb song-thumb-letter">${esc((s.title||'?')[0])}</div>`}
      <div class="song-info">
        <div class="song-title">${manualBadge}${esc(s.title)}</div>
        <div class="song-artist">${esc(s.artist)}</div>
      </div>
      ${roleBadge}
      ${similarity}
      ${removeBtn}
      <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${s.id}, this)">&#8942;</div>
    </div>`;
    };

    const previous = timeline.items.filter(item => item.role === 'previous');
    const current = timeline.items.find(item => item.role === 'current');
    const upcoming = timeline.items.filter(item => item.role === 'upcoming');
    const visiblePrevious = timeline.explicit ? previous : previous.slice(-10);
    const hiddenPreviousCount = Math.max(0, previous.length - visiblePrevious.length);
    const prevTitle = timeline.explicit
      ? 'Earlier In This Order'
      : (hiddenPreviousCount > 0
          ? `Previously Played (last ${visiblePrevious.length} of ${previous.length})`
          : 'Previously Played');
    const currentHtml = current
      ? `<div class="timeline-section-title">Now Playing</div>${rowHtml(current)}`
      : '';
    const prevHtml = visiblePrevious.length > 0
      ? `<div class="timeline-section-title">${prevTitle}</div>${visiblePrevious.map(rowHtml).join('')}`
      : '';
    const nextHtml = upcoming.length > 0
      ? `<div class="timeline-section-title">Coming Up</div>${upcoming.map(rowHtml).join('')}`
      : '';

    list.innerHTML = prevHtml + currentHtml + nextHtml;
    if (getRecsShouldFocusCurrent() && getActiveTab() === 'recs') scheduleRecsFocusCurrent();
  }

  function renderHistory(history, historyPos) {
    const list = document.getElementById('history-list');
    if (!list) return;
    if (!history || history.length === 0) {
      list.innerHTML = '<div class="empty-state">No songs played yet</div>';
      return;
    }
    // Reverse: most recently played at top
    const reversed = [...history].reverse();
    const reversedPos = history.length - 1 - historyPos;
    const allSongsData = engine.getSongs();
    list.innerHTML = reversed.map((s, i) => {
      const full = allSongsData[s.id];
      return `
    <div class="history-item ${i === reversedPos ? 'current-pos' : ''}" onclick="window._app.playSong(${s.id}, 'manual_history_tab')">
      ${full ? songThumb(full) : `<div class="song-thumb song-thumb-letter">${esc((s.title||'?')[0])}</div>`}
      <div class="h-info"><div class="h-title">${esc(s.title)}</div><div class="h-artist">${esc(s.artist)}</div></div>
      <div class="song-menu-btn" onclick="event.stopPropagation(); window._app.showSongMenu(${s.id}, this)">&#8942;</div>
    </div>`;
    }).join('');
  }

  // ===== PLAYER CONTROLS =====

  async function playSongUI(id, source) {
    if (!source) source = 'manual_' + getActiveTab() + '_tab';
    notePlaybackIntent();
    // If the tapped song is already the currently playing song, don't restart it.
    // Open the full player instead so the tap feels intentional.
    if (id === getCurrentSong() && getNativeAudioPlaying()) {
      if (!getFullPlayerOpen()) openFullPlayer();
      return;
    }
    const song = engine.getSongs()[id];
    logActivity({ category: 'ui', type: 'song_tapped', message: `Tapped "${song ? song.title : id}" from ${source}`, data: { songId: id, source, title: song ? song.title : '' , filename: song ? song.filename : '' }, tags: ['playback', 'ui'], important: true });
    dbg('SONG-TAP: id=' + id + ' src=' + source);
    const frac = getListenFraction();
    const info = engine.play(id, frac, source);
    dbg('SONG-TAP: engine.play → ' + (info ? info.title + ' path=' + !!info.filePath : 'NULL'));
    if (info) {
      await loadAndPlay(info);
      dbg('SONG-TAP: loadAndPlay done');
      refreshStateUI();
    }
  }

  async function playFromQueueUI(id) {
    const frac = getListenFraction();
    const song = engine.getSongs()[id];
    logActivity({ category: 'ui', type: 'queue_song_tapped', message: `Tapped queued song "${song ? song.title : id}"`, data: { songId: id, prevFraction: frac, title: song ? song.title : '', filename: song ? song.filename : '' }, tags: ['queue'], important: true });
    const info = engine.playFromQueue(id, frac);
    if (info) {
      await loadAndPlay(info);
      refreshStateUI();
    }
  }

  async function playTimelineIndexUI(index) {
    const st = engine.getState();
    const item = st.timeline && Array.isArray(st.timeline.items) ? st.timeline.items[index] : null;
    if (!item) return;
    if (item.id === getCurrentSong() && item.role === 'current' && getNativeAudioPlaying()) {
      if (!getFullPlayerOpen()) openFullPlayer();
      return;
    }
    const frac = getListenFraction();
    logActivity({
      category: 'ui',
      type: 'timeline_song_tapped',
      message: `Tapped "${item.title}" from Up Next timeline`,
      data: { songId: item.id, timelineIndex: index, role: item.role, prevFraction: frac },
      tags: ['queue', 'playback'],
      important: true,
    });
    const info = engine.playFromTimelineIndex(index, frac);
    if (info) {
      await loadAndPlay(info);
      refreshStateUI();
    }
  }

  async function playFromAlbumUI(id, trackIds) {
    if (id === getCurrentSong() && getNativeAudioPlaying()) {
      if (!getFullPlayerOpen()) openFullPlayer();
      return;
    }
    const frac = getListenFraction();
    const song = engine.getSongs()[id];
    logActivity({ category: 'ui', type: 'album_song_tapped', message: `Tapped album song "${song ? song.title : id}"`, data: { songId: id, prevFraction: frac, trackCount: Array.isArray(trackIds) ? trackIds.length : 0, title: song ? song.title : '', filename: song ? song.filename : '' }, tags: ['album', 'playback'], important: true });
    const info = engine.playFromAlbum(id, trackIds, frac);
    if (info) {
      await loadAndPlay(info);
      refreshStateUI();
    }
  }

  // Tap-from-section handler. Plays `songId` and replaces Up Next with the rest
  // of the resolved section. If songId is omitted, starts from the section head.
  async function playFromSectionUI(songId, sectionKey) {
    if (songId === getCurrentSong() && getNativeAudioPlaying()) {
      if (!getFullPlayerOpen()) openFullPlayer();
      return;
    }
    let ids = [];
    let label = '';
    if (sectionKey === 'forYou') {
      ids = (getCachedForYou() || []).map(s => s.id);
      label = 'For You';
    } else if (sectionKey === 'similar') {
      ids = (getCachedSimilar() || []).map(s => s.id);
      label = 'Most Similar';
    } else if (sectionKey === 'favorites') {
      ids = (getCachedFavorites() || []).map(s => s.id);
      label = 'Favorites';
    } else if (sectionKey === 'viewAll') {
      ids = (getViewAllItems() || []).map(s => s.id);
      label = getViewAllMeta(getCurrentViewAllType()).title;
    } else if (typeof sectionKey === 'string' && sectionKey.startsWith('byp:')) {
      const i = parseInt(sectionKey.slice(4), 10);
      const sec = (getCachedBecauseYouPlayed() || [])[i];
      if (sec) {
        ids = (sec.recommendations || []).map(s => s.id);
        label = `Because you played ${sec.sourceTitle}`;
      }
    } else if (typeof sectionKey === 'string' && sectionKey.startsWith('unexp:')) {
      const secs = getCachedUnexplored() || [];
      const i = parseInt(sectionKey.slice(6), 10);
      const sec = secs[i];
      if (sec) {
        ids = (sec.songs || []).map(s => s.id);
        label = 'Unexplored Sounds';
      }
    }
    if (!ids || ids.length === 0) {
      showStatusToast('Nothing to play', 1500);
      return;
    }
    const startId = (songId != null && ids.includes(songId)) ? songId : ids[0];
    const frac = getListenFraction();
    const song = engine.getSongs()[startId];
    logActivity({ category: 'ui', type: 'section_song_tapped', message: `Tapped "${song ? song.title : startId}" from ${sectionKey}`, data: { songId: startId, sectionKey, prevFraction: frac, sectionSize: ids.length, title: song ? song.title : '', filename: song ? song.filename : '' }, tags: ['section', 'playback'], important: true });
    const info = engine.playFromSection(startId, ids, label, frac);
    if (info) {
      await loadAndPlay(info);
      refreshStateUI();
    }
  }

  // "Play only" — plays the song without touching Up Next. Wired from the ⋮ menu.
  async function playOnlyUI(songId) {
    const frac = getListenFraction();
    const song = engine.getSongs()[songId];
    logActivity({ category: 'ui', type: 'play_only_pressed', message: `Play only for "${song ? song.title : songId}"`, data: { songId, prevFraction: frac, title: song ? song.title : '', filename: song ? song.filename : '' }, tags: ['playback'], important: true });
    const info = engine.playOnly(songId, frac);
    if (info) {
      await loadAndPlay(info);
      refreshStateUI();
      showStatusToast('Playing only this song (queue kept)', 1800);
    }
  }

  async function togglePauseUI() {
    try {
      notePlaybackIntent();
      dbg('PLAY-TAP: playing=' + getNativeAudioPlaying() + ' loaded=' + getNativeFileLoaded() + ' cur=' + getCurrentSong() + ' quick=' + !!getQuickRestoreInfo() + ' init=' + getInitRestoreComplete());

      if (getNativeAudioPlaying()) {
        dbg('PLAY: pausing');
        MusicBridge.pauseAudio();
      } else if (!getNativeFileLoaded() && getCurrentSong() != null) {
        const songs = engine.getSongs();
        const song = songs[getCurrentSong()];
        dbg('PLAY: cold restore song=' + (song ? song.title : 'NULL') + ' path=' + (song ? song.filePath : 'NULL'));
        if (song && song.filePath) {
          const seekTo = getNativeAudioPos() || 0;
          await loadAndPlay({
            id: getCurrentSong(),
            title: song.title,
            artist: song.artist,
            album: song.album,
            filename: song.filename,
            filePath: song.filePath,
            isFavorite: engine.isFavorite(getCurrentSong()),
          }, seekTo);
          dbg('PLAY: cold restore done');
        } else if (!getInitRestoreComplete()) {
          dbg('PLAY: init not complete yet, waiting');
          showStatusToast('Loading...', 1000);
        } else {
          dbg('PLAY: song has no filePath!');
        }
      } else if (!getInitRestoreComplete() && getQuickRestoreInfo()) {
        setPendingStartupResume(true);
        dbg('PLAY: waiting for authoritative restore');
        showStatusToast('Restoring playback...', 1200);
      } else if (!getNativeFileLoaded() && getCurrentSong() == null) {
        dbg('PLAY: no song and no quickRestore');
      } else {
        dbg('PLAY: resuming');
        MusicBridge.resumeAudio();
      }
    } catch (e) {
      dbg('PLAY ERROR: ' + e.message);
    }
  }

  async function nextUI(source) {
    if (!source) source = 'next_button';
    if (shouldBlockRapidNav('next')) {
      dbg('NEXT blocked duplicate tap');
      return;
    }
    const frac = getListenFraction();
    logActivity({ category: 'ui', type: 'next_pressed', message: `Next pressed (${source})`, data: { source, prevFraction: frac }, tags: ['playback'], important: true });
    if (source === 'next_button') showStatusToast('Dislike skip', 1200);
    if (getNativeFileLoaded()) {
      try {
        await MusicBridge.nextTrack({ action: 'user_next', prevFraction: frac == null ? -1 : frac });
        return;
      } catch (e) {
        dbg('NEXT native fallback: ' + e.message);
      }
    }
    const info = engine.nextSong(frac, source);
    if (info) {
      await loadAndPlay(info);
      refreshStateUI();
    }
  }

  async function prevUI(source) {
    if (shouldBlockRapidNav('prev')) {
      dbg('PREV blocked duplicate tap');
      return;
    }
    const frac = getListenFraction();
    logActivity({ category: 'ui', type: 'prev_pressed', message: `Previous pressed (${source || 'prev_button'})`, data: { source: source || 'prev_button', prevFraction: frac }, tags: ['playback'], important: true });
    if (getNativeFileLoaded()) {
      try {
        await MusicBridge.prevTrack({ prevFraction: frac == null ? -1 : frac });
        return;
      } catch (e) {
        dbg('PREV native fallback: ' + e.message);
      }
    }
    if (getNativeAudioPos() > 3) {
      try { MusicBridge.seekAudio({ position: 0 }); } catch (e) { /* ignore */ }
      return;
    }
    const info = engine.prevSong(frac);
    if (info) {
      await loadAndPlay(info);
      refreshStateUI();
    }
  }

  function seekUI(e) {
    if (e.target.id === 'seekThumb') return;
    const bar = document.getElementById('progressBar');
    const rect = bar.getBoundingClientRect();
    const dur = getNativeAudioDur() || 0;
    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
    if (dur > 0) {
      const pos = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width)) * dur;
      try { MusicBridge.seekAudio({ position: pos }); } catch (e) { /* ignore */ }
      setNativeAudioPos(pos);
      updateProgressUI(pos, getNativeAudioDur());
      persistPlaybackState(true);
    }
  }

  function setupSeekDrag() {
    const bar = document.getElementById('progressBar');
    if (!bar) return;
    let dragging = false;

    function getSeekFraction(e) {
      const rect = bar.getBoundingClientRect();
      const clientX = e.touches ? e.touches[0].clientX : e.clientX;
      return Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    }

    function startDrag(e) {
      dragging = true;
      bar.classList.add('seeking');
      updateDragPosition(e);
    }

    function updateDragPosition(e) {
      if (!dragging) return;
      e.preventDefault();
      const frac = getSeekFraction(e);
      document.getElementById('progressFill').style.width = (frac * 100) + '%';
      document.getElementById('seekThumb').style.left = (frac * 100) + '%';
    }

    function endDrag(e) {
      if (!dragging) return;
      dragging = false;
      bar.classList.remove('seeking');
      const frac = getSeekFraction(e.changedTouches ? e.changedTouches[0] : e);
      const dur = getNativeAudioDur() || 0;
      if (dur > 0) {
        const newPos = frac * dur;
        setNativeAudioPos(newPos);
        try { MusicBridge.seekAudio({ position: newPos }); } catch (ex) { /* ignore */ }
        updateProgressUI(newPos, getNativeAudioDur());
        persistPlaybackState(true);
      }
    }

    // Touch events
    bar.addEventListener('touchstart', (e) => { startDrag(e); }, { passive: false });
    bar.addEventListener('touchmove', (e) => { updateDragPosition(e); }, { passive: false });
    bar.addEventListener('touchend', (e) => { endDrag(e); });
    bar.addEventListener('touchcancel', () => { dragging = false; bar.classList.remove('seeking'); });

    // Mouse events (fallback)
    bar.addEventListener('mousedown', (e) => { startDrag(e); });
    document.addEventListener('mousemove', (e) => { updateDragPosition(e); });
    document.addEventListener('mouseup', (e) => { if (dragging) endDrag(e); });

    // Click on track (not thumb) to seek directly
    bar.addEventListener('click', (e) => { seekUI(e); });
  }

  // ===== FULL-SCREEN PLAYER =====

  function getArtUrl(song, opts = {}) {
    const resolved = resolveSongForArt(song);
    if (!resolved) return null;
    if (!resolved.artPath) {
      if (opts.prime !== false) enqueueSongArt(resolved, { priority: !!opts.priority });
      return null;
    }
    try { return window.Capacitor.convertFileSrc('file://' + resolved.artPath); } catch (e) { return null; }
  }

  function openFullPlayer() {
    const fp = document.getElementById('fullPlayer');
    if (!fp) return;
    syncFullPlayer();
    fp.classList.add('open');
    setFullPlayerOpen(true);
  }

  function closeFullPlayer() {
    const fp = document.getElementById('fullPlayer');
    if (!fp) return;
    fp.classList.remove('open');
    setFullPlayerOpen(false);
  }

  function syncFullPlayer() {
    if (getCurrentSong() == null) return;
    const songs = engine.getSongs();
    const song = songs[getCurrentSong()];
    if (!song) return;
    const syncedSongId = song.id;

    document.getElementById('fpTitle').textContent = song.title;
    document.getElementById('fpArtist').textContent = song.artist + ' · ' + (song.album || '');

    // Art
    const artUrl = getArtUrl(song, { prime: false });
    const imgEl = document.getElementById('fpArtImg');
    const placeholderEl = document.getElementById('fpArtPlaceholder');
    if (artUrl) {
      imgEl.src = artUrl;
      imgEl.style.display = 'block';
      placeholderEl.style.display = 'none';
    } else {
      imgEl.style.display = 'none';
      placeholderEl.style.display = 'block';
      placeholderEl.textContent = (song.title || '?')[0].toUpperCase();
      if (song.filePath) {
        MusicBridge.getAlbumArtUri({ path: song.filePath }).then((res) => {
          if (!res || !res.exists || !res.uri || getCurrentSong() !== syncedSongId) return;
          song.artPath = res.uri;
          syncFullPlayer();
        }).catch(() => {});
      }
    }

    // Mini player art thumbnail
    const npIcon = document.getElementById('npIcon');
    if (npIcon) {
      let thumb = npIcon.querySelector('img');
      if (artUrl) {
        if (!thumb) {
          thumb = document.createElement('img');
          npIcon.appendChild(thumb);
        }
        thumb.src = artUrl;
        thumb.style.display = 'block';
      } else if (thumb) {
        thumb.style.display = 'none';
      }
    }

    // Fav state
    const fpFav = document.getElementById('fpFavBtn');
    if (fpFav) {
      fpFav.classList.toggle('is-fav', getCurrentIsFav());
      fpFav.textContent = getCurrentIsFav() ? '♥' : '♡';
    }

    // Sync loop/shuffle button states
    const fpLoop = document.getElementById('fpLoopBtn');
    if (fpLoop) {
      fpLoop.classList.toggle('active-mode', getLoopMode() !== 'off');
      fpLoop.classList.toggle('loop-one', getLoopMode() === 'one');
      fpLoop.classList.toggle('loop-all', getLoopMode() === 'all');
    }
    const fpShuffle = document.getElementById('fpShuffleBtn');
    if (fpShuffle) fpShuffle.classList.toggle('active-mode', getShuffleOn());
  }

  function updateFullPlayerProgress(pos, dur) {
    if (!getFullPlayerOpen()) return;
    const pct = dur > 0 ? (pos / dur * 100) : 0;
    const fill = document.getElementById('fpProgressFill');
    const thumb = document.getElementById('fpSeekThumb');
    if (fill) fill.style.width = pct + '%';
    if (thumb) thumb.style.left = pct + '%';
    const timeEl = document.getElementById('fpTime');
    const durEl = document.getElementById('fpDuration');
    if (timeEl) timeEl.textContent = formatTime(pos);
    if (durEl) durEl.textContent = formatTime(dur);
  }

  function updateFullPlayerPlayIcon(paused) {
    const icon = document.getElementById('fpPlayIcon');
    if (icon) {
      icon.innerHTML = paused
        ? '<path d="M8 5v14l11-7z"/>'
        : '<path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>';
    }
  }

  // Swipe gesture for full player
  function setupFullPlayerGestures() {
    const fp = document.getElementById('fullPlayer');
    const handle = document.getElementById('fpHandle');
    const npCard = document.getElementById('nowPlaying');
    if (!fp || !handle) return;

    // Swipe down on handle OR album art to close
    let startY = 0, currentY = 0, isDragging = false;
    const swipeTargets = [handle, document.getElementById('fpArt')].filter(Boolean);

    for (const target of swipeTargets) {
      target.addEventListener('touchstart', (e) => {
        startY = e.touches[0].clientY;
        isDragging = true;
        fp.classList.add('dragging');
      }, { passive: true });

      target.addEventListener('touchmove', (e) => {
        if (!isDragging) return;
        currentY = e.touches[0].clientY;
        const dy = Math.max(0, currentY - startY);
        fp.style.transform = `translateY(${dy}px)`;
      }, { passive: true });

      target.addEventListener('touchend', () => {
        if (!isDragging) return;
        isDragging = false;
        fp.classList.remove('dragging');
        fp.style.transform = '';
        const dy = currentY - startY;
        if (dy > 80) {
          closeFullPlayer();
        }
      });
    }

    // Swipe up OR tap on mini player to open
    if (npCard) {
      let npStartY = 0, npStartX = 0, npDragging = false;
      npCard.addEventListener('touchstart', (e) => {
        npStartY = e.touches[0].clientY;
        npStartX = e.touches[0].clientX;
        npDragging = true;
      }, { passive: true });

      npCard.addEventListener('touchend', (e) => {
        if (!npDragging) return;
        npDragging = false;
        const dy = e.changedTouches[0].clientY - npStartY;
        const dx = e.changedTouches[0].clientX - npStartX;
        if (dy < -40) {
          openFullPlayer();
          return;
        }
        // Tap (small movement) on song info area opens full player.
        // Excludes: any button, the progress bar, control icons.
        if (Math.abs(dy) < 10 && Math.abs(dx) < 10) {
          const t = e.target;
          if (t && t.closest && !t.closest('button') && !t.closest('#progressBar') && !t.closest('svg') && !t.closest('.np-btn')) {
            openFullPlayer();
          }
        }
      });
    }

    // Full player seek drag
    const fpBar = document.getElementById('fpProgressBar');
    if (fpBar) {
      let seeking = false;
      function getFpFrac(e) {
        const rect = fpBar.getBoundingClientRect();
        const x = e.touches ? e.touches[0].clientX : e.clientX;
        return Math.max(0, Math.min(1, (x - rect.left) / rect.width));
      }
      fpBar.addEventListener('touchstart', (e) => { seeking = true; e.preventDefault(); }, { passive: false });
      fpBar.addEventListener('touchmove', (e) => {
        if (!seeking) return;
        e.preventDefault();
        const frac = getFpFrac(e);
        document.getElementById('fpProgressFill').style.width = (frac * 100) + '%';
        document.getElementById('fpSeekThumb').style.left = (frac * 100) + '%';
      }, { passive: false });
      fpBar.addEventListener('touchend', (e) => {
        if (!seeking) return;
        seeking = false;
        const frac = getFpFrac(e.changedTouches[0]);
        if (getNativeAudioDur() > 0) {
          const newPos = frac * getNativeAudioDur();
          setNativeAudioPos(newPos);
          try { MusicBridge.seekAudio({ position: newPos }); } catch (ex) { /* ignore */ }
          updateProgressUI(newPos, getNativeAudioDur());
          persistPlaybackState(true);
        }
      });
      fpBar.addEventListener('click', (e) => {
        const frac = getFpFrac(e);
        if (getNativeAudioDur() > 0) {
          const newPos = frac * getNativeAudioDur();
          setNativeAudioPos(newPos);
          try { MusicBridge.seekAudio({ position: newPos }); } catch (ex) { /* ignore */ }
          updateProgressUI(newPos, getNativeAudioDur());
          persistPlaybackState(true);
        }
      });
    }
  }

  // ===== UTILITIES =====

  function formatTime(s) {
    if (!s || isNaN(s)) return '0:00';
    const m = Math.floor(s / 60);
    const sec = Math.floor(s % 60);
    return m + ':' + (sec < 10 ? '0' : '') + sec;
  }

  return {
    playFromFavoritesUI,
    playFromPlaylistUI,
    updateProgressUI,
    updatePlayIcon,
    toggleShuffleUI,
    toggleLoopUI,
    goToQueueUI,
    toggleRecUI,
    renderRecs,
    renderHistory,
    playSongUI,
    playFromQueueUI,
    playTimelineIndexUI,
    playFromAlbumUI,
    playFromSectionUI,
    playOnlyUI,
    togglePauseUI,
    nextUI,
    prevUI,
    seekUI,
    setupSeekDrag,
    getArtUrl,
    openFullPlayer,
    closeFullPlayer,
    syncFullPlayer,
    updateFullPlayerProgress,
    updateFullPlayerPlayIcon,
    setupFullPlayerGestures,
    formatTime,
  };
}
