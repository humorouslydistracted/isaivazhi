/**
 * Thin JS client for the native SQLite-backed embedding store
 * (com.isaivazhi.app.EmbeddingDb + EmbeddingDbManager).
 *
 * All work is performed on a dedicated HandlerThread inside the :main
 * process — the JS thread is never blocked on Float32 encoding, JSON
 * stringification, or disk I/O. Every method returns a Promise that
 * resolves once the native operation completes.
 *
 * Use this module instead of EmbeddingCache.saveToDisk for any mutation
 * path that previously rewrote the entire local_embeddings.bin +
 * local_embeddings.json file pair.
 */

import { MusicBridge } from './music-bridge.js';

// Build a base64 string from a Float32Array's underlying bytes. Mirrors
// embedding-cache._float32ToBase64 but lives here so callers don't need to
// import the cache module.
function _float32ToBase64(floats) {
  if (!floats) return '';
  let view;
  if (floats instanceof Float32Array) {
    view = new Uint8Array(floats.buffer, floats.byteOffset, floats.byteLength);
  } else if (Array.isArray(floats)) {
    const tmp = new Float32Array(floats);
    view = new Uint8Array(tmp.buffer);
  } else if (ArrayBuffer.isView(floats)) {
    view = new Uint8Array(floats.buffer, floats.byteOffset, floats.byteLength);
  } else {
    return '';
  }
  let binary = '';
  for (let i = 0; i < view.length; i += 8192) {
    binary += String.fromCharCode.apply(null, view.subarray(i, Math.min(i + 8192, view.length)));
  }
  return btoa(binary);
}

// Concatenated-base64 → array of Float32Arrays of length `dim`.
function _splitConcatVec(concatB64, count, dim) {
  if (!concatB64 || count <= 0 || dim <= 0) return [];
  const binary = atob(concatB64);
  const rawLen = binary.length;
  const raw = new Uint8Array(rawLen);
  for (let i = 0; i < rawLen; i++) raw[i] = binary.charCodeAt(i);
  const floats = new Float32Array(raw.buffer);
  const out = new Array(count);
  for (let i = 0; i < count; i++) {
    out[i] = floats.slice(i * dim, (i + 1) * dim);
  }
  return out;
}

// ArrayBuffer of concatenated Float32 bytes → array of Float32Array views
// of length `dim`. Used by the fast-path that reads the binary snapshot
// file directly via convertFileSrc + fetch (bypasses the JSI bridge so the
// 5 MB load doesn't pay the multi-second base64 round-trip).
function _splitConcatBuffer(buffer, count, dim) {
  if (!buffer || count <= 0 || dim <= 0) return [];
  const floats = new Float32Array(buffer);
  const expected = count * dim;
  if (floats.length < expected) return [];
  const out = new Array(count);
  for (let i = 0; i < count; i++) {
    out[i] = floats.slice(i * dim, (i + 1) * dim);
  }
  return out;
}

// Fetch a local file via convertFileSrc (WebView's HTTP server). Same
// pattern as embedding-cache._fetchLocalArrayBuffer; duplicated here to
// keep embedding-db.js self-contained.
async function _fetchLocalArrayBuffer(absolutePath) {
  try {
    if (typeof window === 'undefined') return null;
    const conv = window.Capacitor && window.Capacitor.convertFileSrc;
    if (typeof conv !== 'function') return null;
    const url = conv('file://' + absolutePath);
    const resp = await fetch(url);
    if (!resp || !resp.ok) return null;
    return await resp.arrayBuffer();
  } catch (e) {
    return null;
  }
}

/**
 * Read the entire embeddings table back into a JS-shape object identical to
 * what EmbeddingCache.loadFromDisk used to return. Caller code that consumed
 * the old shape (engine-embeddings._mergeLocalEmbeddings) keeps working.
 *
 * Returns `null` when the table is empty so the caller can decide between
 * fresh-install fallback and migration.
 */
export async function loadAll() {
  const t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
  const res = await MusicBridge.embDbLoadAll();
  const tBridge = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
  const count = res && res.count ? res.count : 0;
  if (!count) return null;

  const dim = res.dim || 0;
  const entries = Array.isArray(res.entries) ? res.entries : [];

  // Prefer the file-snapshot fast path: native wrote
  // <dataDir>/embeddings_snapshot.bin atomically as part of embDbLoadAll;
  // we fetch it directly via the WebView's local URL converter. Same
  // pattern as the legacy local_embeddings.bin path that was clocked at
  // ~85 ms for 5 MB. The 6.7 MB base64 round-trip over the JSI bridge
  // (~8–13 s on the 23:17 capture) is bypassed entirely.
  let vectors = null;
  let source = 'unknown';
  if (res.snapshotBinPath && typeof res.snapshotBinPath === 'string' && res.snapshotBinPath.length > 0) {
    const buf = await _fetchLocalArrayBuffer(res.snapshotBinPath);
    if (buf && buf.byteLength > 0 && buf.byteLength % 4 === 0) {
      vectors = _splitConcatBuffer(buf, entries.length, dim);
      source = 'file';
    }
  }
  if ((!vectors || vectors.length === 0) && res.vecBase64) {
    // Fallback path: native couldn't write the file (read-only volume,
    // out of space, etc.) and sent the inline base64 instead.
    vectors = _splitConcatVec(res.vecBase64, entries.length, dim);
    source = 'base64';
  }
  if (!vectors || vectors.length === 0) return null;

  const result = { _path_index: {} };
  for (let i = 0; i < entries.length; i++) {
    const e = entries[i];
    if (!e || !e.contentHash) continue;
    const vec = vectors[i] || null;
    if (!vec) continue;
    result[e.contentHash] = {
      embedding: vec,
      filepath: e.filepath || '',
      filename: e.filename || '',
      artist: e.artist || '',
      album: e.album || '',
      content_hash: e.contentHash,
      contentHash: e.contentHash,
      timestamp: e.timestamp || 0,
    };
  }
  if (res.pathIndex && typeof res.pathIndex === 'object') {
    for (const fp of Object.keys(res.pathIndex)) {
      result._path_index[fp] = res.pathIndex[fp];
    }
  }
  const tEnd = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
  console.log(`[PERF] EmbeddingDb.loadAll: ${count} entries dim=${dim} via ${source}, bridge=${Math.round(tBridge - t0)} ms, total=${Math.round(tEnd - t0)} ms`);
  return result;
}

/**
 * Insert / update one embedding row. The native side runs the disk write
 * on its worker thread and resolves once committed. JS-thread cost is
 * roughly one Float32→bytes base64 encode (~512 floats = a few ms).
 */
export async function upsertOne(entry) {
  if (!entry || !entry.contentHash || !entry.embedding) return 0;
  const dim = entry.embedding.length;
  if (!dim) return 0;
  const vecBase64 = _float32ToBase64(entry.embedding);
  const res = await MusicBridge.embDbUpsertOne({
    contentHash: entry.contentHash,
    filepath: entry.filepath || '',
    filename: entry.filename || '',
    artist: entry.artist || '',
    album: entry.album || '',
    timestamp: entry.timestamp || 0,
    dim,
    vecBase64,
  });
  return res && res.upserted ? res.upserted : 0;
}

export async function upsertBatch(entries) {
  if (!Array.isArray(entries) || entries.length === 0) return 0;
  const rows = [];
  for (const entry of entries) {
    if (!entry || !entry.contentHash || !entry.embedding) continue;
    const dim = entry.embedding.length;
    if (!dim) continue;
    rows.push({
      contentHash: entry.contentHash,
      filepath: entry.filepath || '',
      filename: entry.filename || '',
      artist: entry.artist || '',
      album: entry.album || '',
      timestamp: entry.timestamp || 0,
      dim,
      vecBase64: _float32ToBase64(entry.embedding),
    });
  }
  if (rows.length === 0) return 0;
  const res = await MusicBridge.embDbUpsertBatch({ entries: rows });
  return res && res.upserted ? res.upserted : 0;
}

export async function deleteByHashes(hashes) {
  if (!Array.isArray(hashes) || hashes.length === 0) return 0;
  const res = await MusicBridge.embDbDeleteByHashes({ contentHashes: hashes });
  return res && res.deleted ? res.deleted : 0;
}

export async function deleteByFilepaths(paths) {
  if (!Array.isArray(paths) || paths.length === 0) return 0;
  const res = await MusicBridge.embDbDeleteByFilepaths({ filepaths: paths });
  return res && res.deleted ? res.deleted : 0;
}

export async function deleteByFilenames(filenames) {
  if (!Array.isArray(filenames) || filenames.length === 0) return 0;
  const res = await MusicBridge.embDbDeleteByFilenames({ filenames });
  return res && res.deleted ? res.deleted : 0;
}

export async function exportLegacyMirror() {
  return MusicBridge.embDbExportLegacyMirror();
}

/**
 * Force a fresh embeddings_snapshot.bin write. Useful if you want to keep
 * the on-disk snapshot warm without paying the JSON-entries wire cost
 * (e.g., a future appPaused hook). Runs on the EmbeddingDb worker thread.
 */
export async function exportBinSnapshot() {
  return MusicBridge.embDbExportBinSnapshot();
}

export async function stats() {
  return MusicBridge.embDbStats();
}

export async function migrateFromLegacy() {
  return MusicBridge.embDbMigrateFromLegacy();
}

/**
 * Native-backed top-k similarity search. Uses sqlite-vec's
 * vec_distance_cosine when the extension is loaded; falls back to
 * NativeAccelerator (NEON SIMD) when the extension isn't available. All
 * scanning happens on the EmbeddingDb worker thread — JS-thread cost is
 * just the base64 encode of the query vector (~512 floats = ~2 KB, a
 * couple of ms).
 *
 * Parameters:
 *   queryVec      — Float32Array | number[] of length dim
 *   k             — number of results requested
 *   excludeHashes — optional array of contentHashes to skip (typically
 *                   the currently-playing song)
 *
 * Returns: { results: [{contentHash, filepath, filename, similarity}],
 *            usedExtension: boolean, count: number }
 */
export async function nearestNeighbors(queryVec, k, excludeHashes) {
  if (!queryVec || !queryVec.length) return { results: [], usedExtension: false, count: 0 };
  const queryVecBase64 = _float32ToBase64(queryVec);
  const result = await MusicBridge.embDbNearestNeighbors({
    queryVecBase64,
    k: k || 10,
    excludeHashes: Array.isArray(excludeHashes) ? excludeHashes : [],
  });
  return result || { results: [], usedExtension: false, count: 0 };
}
