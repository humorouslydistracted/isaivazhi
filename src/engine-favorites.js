import {
  songs, favorites, log, dislikedFilenames, playlists, state, negativeScores,
} from './engine-state.js';
import {
  _computeTasteContributions, _applySimilarityBoostPropagation,
  _saveDislikes, _activity, _songRef,
} from './engine-taste.js';
import { _saveSongLibrary } from './engine-embeddings.js';
import {
  saveFavorites, savePlaylists,
  _normalizePlaylistName, _findPlaylistIndex,
  _getPlaylistById, _resolvePlaylistSongIds, _playlistSummary,
} from './engine-data.js';
import { scheduleRecommendationRebuild } from './engine-analytics.js';

export function toggleFavorite(songId) {
  return setFavoriteState(songId, !favorites.has(songId), { source: 'toggle' });
}

export function setFavoriteState(songId, shouldBeFavorite, opts = {}) {
  console.log('[FAV] setFavoriteState called with songId =', songId, 'target =', shouldBeFavorite, 'type =', typeof songId, 'songs.length =', songs.length);
  if (songId == null || songId < 0 || songId >= songs.length) {
    console.error('[FAV] GUARD REJECTED songId =', songId);
    return { ok: false, error: 'invalid songId: ' + songId };
  }
  const song = songs[songId];
  const entry = log && typeof log.peekProfileSummaryEntry === 'function'
    ? log.peekProfileSummaryEntry(song.filename)
    : null;
  const xScore = negativeScores[song.filename] || 0;
  const beforeSignals = _computeTasteContributions(songId, entry, {
    xScore,
    isFavorite: favorites.has(songId),
    isDisliked: !!song.disliked,
  });
  const wasFav = favorites.has(songId);
  if (shouldBeFavorite) {
    favorites.add(songId);
  } else {
    favorites.delete(songId);
  }
  const isFav = favorites.has(songId);
  let unDisliked = false;
  if (isFav) {
    const s = songs[songId];
    if (s && s.disliked) {
      s.disliked = false;
      dislikedFilenames.delete(s.filename);
      _saveDislikes().catch(() => {});
      unDisliked = true;
    }
  }
  const count = favorites.size;
  console.log('[FAV] was =', wasFav, 'now =', isFav, 'count =', count);
  saveFavorites().catch(e => console.error('[FAV] save failed:', e));
  if (wasFav !== isFav || unDisliked) {
    _activity('taste', 'favorite_toggled', `${isFav ? 'Added to' : 'Removed from'} favorites`, {
      ..._songRef(songId),
      isFavorite: isFav,
      source: opts.source || 'set',
      unDisliked,
    }, { important: true, tags: ['favorite'] });
  }
  if (unDisliked) {
    _saveSongLibrary();
    _activity('taste', 'favorite_dislike_mutex_applied', 'Favorite removed existing dislike', {
      ..._songRef(songId),
      source: opts.source || 'set',
    }, { important: true, tags: ['favorite', 'dislike'] });
  }
  const afterSignals = _computeTasteContributions(songId, entry, {
    xScore,
    isFavorite: isFav,
    isDisliked: !!song.disliked,
  });
  const scoreDelta = (afterSignals.directScore || 0) - (beforeSignals.directScore || 0);
  _applySimilarityBoostPropagation(songId, scoreDelta, 'favorite_toggle');
  scheduleRecommendationRebuild('favorite_toggle', {
    refreshQueue: true,
    refreshDiscover: true,
  });
  return { ok: true, isFavorite: isFav, count, unDisliked, changed: wasFav !== isFav };
}

export function toggleDislike(songId) {
  if (songId == null || songId < 0 || songId >= songs.length) {
    return { ok: false, error: 'invalid songId: ' + songId };
  }
  const s = songs[songId];
  if (!s) return { ok: false, error: 'no song' };
  const entry = log && typeof log.peekProfileSummaryEntry === 'function'
    ? log.peekProfileSummaryEntry(s.filename)
    : null;
  const xScore = negativeScores[s.filename] || 0;
  const beforeSignals = _computeTasteContributions(songId, entry, {
    xScore,
    isFavorite: favorites.has(songId),
    isDisliked: !!s.disliked,
  });
  const wasDisliked = !!s.disliked;
  s.disliked = !wasDisliked;
  let unFavorited = false;
  if (s.disliked) {
    dislikedFilenames.add(s.filename);
    // Mutex: disliking auto-removes favorite
    if (favorites.has(songId)) {
      favorites.delete(songId);
      saveFavorites().catch(() => {});
      unFavorited = true;
    }
  } else {
    dislikedFilenames.delete(s.filename);
  }
  _saveDislikes().catch(() => {});
  _saveSongLibrary();
  _activity('taste', 'dislike_toggled', `${s.disliked ? 'Disliked' : 'Removed dislike from'} "${s.title}"`, {
    ..._songRef(songId),
    isDisliked: s.disliked,
    unFavorited,
  }, { important: true, tags: ['dislike'] });
  if (unFavorited) {
    _activity('taste', 'favorite_dislike_mutex_applied', 'Dislike removed existing favorite', {
      ..._songRef(songId),
    }, { important: true, tags: ['favorite', 'dislike'] });
  }
  const afterSignals = _computeTasteContributions(songId, entry, {
    xScore,
    isFavorite: favorites.has(songId),
    isDisliked: !!s.disliked,
  });
  const scoreDelta = (afterSignals.directScore || 0) - (beforeSignals.directScore || 0);
  _applySimilarityBoostPropagation(songId, scoreDelta, 'dislike_toggle');
  scheduleRecommendationRebuild('dislike_toggle', {
    refreshQueue: true,
    refreshDiscover: true,
  });
  return { ok: true, isDisliked: s.disliked, unFavorited };
}

export function isDisliked(songId) {
  return !!(songs[songId] && songs[songId].disliked);
}

export function getFavoritesList() {
  return [...favorites]
    .filter(sid => sid < songs.length && songs[sid].filePath)
    .sort((a, b) => a - b)
    .map(sid => ({
      id: sid,
      title: songs[sid].title,
      artist: songs[sid].artist,
      album: songs[sid].album,
      hasEmbedding: songs[sid].hasEmbedding,
      artPath: songs[sid].artPath,
    }));
}

export function getDislikedSongsList() {
  return songs
    .filter(s => s && s.filePath && s.disliked)
    .slice()
    .sort((a, b) =>
      String(a.title || '').localeCompare(String(b.title || ''))
      || String(a.artist || '').localeCompare(String(b.artist || ''))
    )
    .map(s => ({
      id: s.id,
      title: s.title,
      artist: s.artist,
      album: s.album,
      hasEmbedding: s.hasEmbedding,
      artPath: s.artPath,
    }));
}

export function getPlaylists() {
  return playlists
    .slice()
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
    .map(pl => _playlistSummary(pl));
}

export function getPlaylistSongs(playlistId) {
  const playlist = _getPlaylistById(playlistId);
  if (!playlist) return [];
  return _resolvePlaylistSongIds(playlist).map(sid => ({
    id: sid,
    title: songs[sid].title,
    artist: songs[sid].artist,
    album: songs[sid].album,
    hasEmbedding: songs[sid].hasEmbedding,
    artPath: songs[sid].artPath,
  }));
}

export function getPlaylistMeta(playlistId) {
  const playlist = _getPlaylistById(playlistId);
  if (!playlist) return null;
  const summary = _playlistSummary(playlist);
  return {
    id: playlist.id,
    name: playlist.name,
    count: summary.count,
    createdAt: playlist.createdAt,
    updatedAt: playlist.updatedAt,
  };
}

export function isSongInPlaylist(playlistId, songId) {
  const playlist = _getPlaylistById(playlistId);
  const song = songs[songId];
  if (!playlist || !song || !song.filename) return false;
  return (playlist.songFilenames || []).includes(song.filename);
}

export function createPlaylist(name, initialSongId = null) {
  const cleanName = _normalizePlaylistName(name);
  if (!cleanName) return { ok: false, error: 'Playlist name is required' };
  const exists = playlists.find(pl => pl.name.toLowerCase() === cleanName.toLowerCase());
  if (exists) return { ok: false, error: 'A playlist with this name already exists' };

  const playlist = {
    id: `pl_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`,
    name: cleanName,
    songFilenames: [],
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
  playlists.unshift(playlist);

  let added = false;
  if (initialSongId != null) {
    const addRes = addSongToPlaylist(playlist.id, initialSongId);
    added = !!(addRes && addRes.ok && !addRes.error);
  } else {
    savePlaylists().catch(() => {});
  }

  _activity('library', 'playlist_created', `Created playlist "${playlist.name}"`, {
    playlistId: playlist.id,
    playlistName: playlist.name,
    addedSong: added,
  }, { important: true, tags: ['playlist', 'library'] });

  return { ok: true, playlist: getPlaylistMeta(playlist.id), addedSong: added };
}

export function renamePlaylist(playlistId, name) {
  const idx = _findPlaylistIndex(playlistId);
  if (idx < 0) return { ok: false, error: 'Playlist not found' };
  const cleanName = _normalizePlaylistName(name);
  if (!cleanName) return { ok: false, error: 'Playlist name is required' };
  const dup = playlists.find(pl => pl.id !== playlistId && pl.name.toLowerCase() === cleanName.toLowerCase());
  if (dup) return { ok: false, error: 'A playlist with this name already exists' };
  playlists[idx].name = cleanName;
  playlists[idx].updatedAt = Date.now();
  savePlaylists().catch(() => {});
  _activity('library', 'playlist_renamed', `Renamed playlist to "${cleanName}"`, {
    playlistId,
    playlistName: cleanName,
  }, { important: true, tags: ['playlist', 'library'] });
  return { ok: true, playlist: getPlaylistMeta(playlistId) };
}

export function deletePlaylist(playlistId) {
  const idx = _findPlaylistIndex(playlistId);
  if (idx < 0) return { ok: false, error: 'Playlist not found' };
  const [playlist] = playlists.splice(idx, 1);
  if (state.currentPlaylistId === playlistId) {
    state.playingPlaylist = false;
    state.currentPlaylistId = null;
    if (state.sessionLabel === `Playlist: ${playlist.name}` || state.sessionLabel === `${playlist.name} finished`) {
      state.sessionLabel = '';
    }
  }
  savePlaylists().catch(() => {});
  _activity('library', 'playlist_deleted', `Deleted playlist "${playlist.name}"`, {
    playlistId,
    playlistName: playlist.name,
  }, { important: true, tags: ['playlist', 'library'] });
  return { ok: true };
}

export function addSongToPlaylist(playlistId, songId) {
  const idx = _findPlaylistIndex(playlistId);
  if (idx < 0) return { ok: false, error: 'Playlist not found' };
  const song = songs[songId];
  if (!song || !song.filename || !song.filePath) return { ok: false, error: 'Song not available' };
  const playlist = playlists[idx];
  if ((playlist.songFilenames || []).includes(song.filename)) {
    return { ok: true, alreadyExists: true, playlist: getPlaylistMeta(playlistId) };
  }
  playlist.songFilenames.push(song.filename);
  playlist.updatedAt = Date.now();
  playlists.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  savePlaylists().catch(() => {});
  _activity('library', 'playlist_song_added', `Added "${song.title}" to playlist "${playlist.name}"`, {
    ..._songRef(songId),
    playlistId,
    playlistName: playlist.name,
  }, { important: true, tags: ['playlist', 'library'] });
  return { ok: true, alreadyExists: false, playlist: getPlaylistMeta(playlistId) };
}

export function removeSongFromPlaylist(playlistId, songId) {
  const idx = _findPlaylistIndex(playlistId);
  if (idx < 0) return { ok: false, error: 'Playlist not found' };
  const song = songs[songId];
  if (!song || !song.filename) return { ok: false, error: 'Song not found' };
  const playlist = playlists[idx];
  const before = playlist.songFilenames.length;
  playlist.songFilenames = playlist.songFilenames.filter(fn => fn !== song.filename);
  if (playlist.songFilenames.length === before) {
    return { ok: true, removed: false, playlist: getPlaylistMeta(playlistId) };
  }
  playlist.updatedAt = Date.now();
  playlists.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  savePlaylists().catch(() => {});
  _activity('library', 'playlist_song_removed', `Removed "${song.title}" from playlist "${playlist.name}"`, {
    ..._songRef(songId),
    playlistId,
    playlistName: playlist.name,
  }, { important: true, tags: ['playlist', 'library'] });
  return { ok: true, removed: true, playlist: getPlaylistMeta(playlistId) };
}
