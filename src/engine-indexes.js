// Shared O(1) lookup index helpers — rebuilt lazily when songs/embeddings change.
// Extracted from engine.js so engine-taste.js and engine-embeddings.js can share them
// without circular imports.

import { songs, embeddings } from './engine-state.js';

// --- Filename → Song ID ---
let _fnMap = null;
export function _getFilenameMap() {
  if (_fnMap && _fnMap._size === songs.length) return _fnMap;
  _fnMap = {};
  for (const s of songs) {
    _fnMap[s.filename.toLowerCase()] = s.id;
  }
  _fnMap._size = songs.length;
  return _fnMap;
}
export function _invalidateFilenameMap() { _fnMap = null; }

// --- FilePath → Song ID ---
let _pathMap = null;
export function _getPathMap() {
  if (_pathMap && _pathMap._size === songs.length) return _pathMap;
  _pathMap = {};
  for (const s of songs) {
    if (s.filePath) _pathMap[s.filePath.toLowerCase()] = s.id;
  }
  _pathMap._size = songs.length;
  return _pathMap;
}
export function _invalidatePathMap() { _pathMap = null; }

// --- EmbeddingIndex → Song ID ---
let _embIdxMap = null;
function _ensureEmbIdxMap() {
  if (_embIdxMap && _embIdxMap.size === songs.length) return;
  _embIdxMap = new Map();
  for (const s of songs) {
    if (s.embeddingIndex != null) _embIdxMap.set(s.embeddingIndex, s.id);
  }
}
export function _fastEmbIdxToSongId(embIdx) {
  _ensureEmbIdxMap();
  return _embIdxMap.get(embIdx) ?? -1;
}
export function _invalidateEmbIdxMap() { _embIdxMap = null; }

// --- Song ID set → embedding-index exclude set ---
export function _songIdsToEmbExclude(songIds) {
  const embExclude = new Set();
  for (const sid of songIds) {
    if (sid < songs.length && songs[sid].hasEmbedding) {
      embExclude.add(songs[sid].embeddingIndex);
    }
  }
  return embExclude;
}
