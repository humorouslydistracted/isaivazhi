/**
 * Embedding storage with a fast binary runtime cache plus a portable JSON mirror.
 *
 * Stable runtime store:
 *   - local_embeddings.bin
 *   - local_embeddings_meta.json
 *
 * In-progress recovery store:
 *   - pending_embeddings.json
 *
 * Portable interchange / merge file:
 *   - local_embeddings.json
 */

import { MusicBridge } from './music-bridge.js';

let dataDir = '/storage/emulated/0/MusicPlayerData';

const STABLE_BIN = 'local_embeddings.bin';
const STABLE_META = 'local_embeddings_meta.json';
const PENDING_JSON = 'pending_embeddings.json';
const LEGACY_JSON = 'local_embeddings.json';

export function setDataDir(dir) {
  dataDir = dir;
}

export async function loadFromDisk() {
  const [stableInfo, legacyInfo, pendingInfo] = await Promise.all([
    _getStableStoreInfo(),
    _getFileInfo(LEGACY_JSON),
    _getFileInfo(PENDING_JSON),
  ]);

  // Fast path for the first JSON import: avoid pulling a large file over the
  // JS bridge when there is no binary cache yet and no pending app-side work.
  if (!stableInfo.exists && legacyInfo.exists && !pendingInfo.exists) {
    try {
      const convResult = await MusicBridge.convertEmbeddingsJsonToBinary();
      if (convResult.success) {
        console.log(`Native JSON->binary conversion: ${convResult.entries} entries, ${convResult.dim}D`);
        const migrated = await _loadBinaryStore();
        if (migrated) return migrated;
      } else {
        console.log('No local_embeddings.json available for native conversion:', convResult.reason);
      }
    } catch (e) {
      console.log('Native JSON conversion unavailable:', e.message);
    }
  }

  const stable = await _loadBinaryStore();
  const shouldImportLegacy = legacyInfo.exists && (!stableInfo.exists || legacyInfo.lastModified > stableInfo.lastModified);

  let merged = stable ? _normalizeEmbeddingObject(stable) : { _path_index: {} };
  let changed = false;

  if (shouldImportLegacy) {
    const legacy = await _readEmbeddingsJson(LEGACY_JSON);
    if (legacy) {
      merged = _mergeEmbeddingObjects(merged, legacy);
      changed = true;
      console.log('Imported local_embeddings.json into the stable store');
    }
  }

  const pending = await _readEmbeddingsJson(PENDING_JSON);
  const pendingCount = _countEmbeddings(pending);
  if (pendingCount > 0) {
    merged = _mergeEmbeddingObjects(merged, pending);
    changed = true;
    console.log(`Recovered ${pendingCount} pending embeddings into the stable store`);
  }

  if (changed) {
    await saveToDisk(merged);
    if (pendingCount > 0) {
      await _clearPending();
    }
    return merged;
  }

  if (stable) return stable;
  return null;
}

export async function saveToDisk(embObj) {
  const normalized = _normalizeEmbeddingObject(embObj);
  await _writeLegacyJson(normalized);
  await _writeBinaryStore(normalized);
}

export async function recoverPendingToStable() {
  const pending = await _readEmbeddingsJson(PENDING_JSON);
  if (!pending) return false;

  const pendingCount = _countEmbeddings(pending);
  if (pendingCount === 0) {
    await _clearPending();
    return false;
  }

  const stable = (await _loadBinaryStore()) || { _path_index: {} };
  const merged = _mergeEmbeddingObjects(stable, pending);
  await saveToDisk(merged);
  await _clearPending();
  console.log(`Recovered ${pendingCount} pending embeddings into stable store + local_embeddings.json mirror`);
  return true;
}

async function _loadBinaryStore() {
  try {
    const [binInfo, metaInfo] = await Promise.all([
      MusicBridge.getFileModified({ path: _path(STABLE_BIN) }),
      MusicBridge.getFileModified({ path: _path(STABLE_META) }),
    ]);
    if (!binInfo.exists || !metaInfo.exists) return null;

    const metaResult = await MusicBridge.readTextFile({ path: _path(STABLE_META) });
    if (!metaResult.exists || !metaResult.content) return null;
    const meta = JSON.parse(metaResult.content);

    const binResult = await MusicBridge.readBinaryFile({ path: _path(STABLE_BIN) });
    if (!binResult.exists || !binResult.data) return null;

    const raw = Uint8Array.from(atob(binResult.data), c => c.charCodeAt(0));
    const floats = new Float32Array(raw.buffer);
    const dim = meta.dim;
    if (!dim || !Array.isArray(meta.entries)) return null;

    const result = { _path_index: meta.pathIndex || {} };
    for (let i = 0; i < meta.entries.length; i++) {
      const entry = meta.entries[i];
      const off = i * dim;
      result[entry.key] = {
        embedding: Array.from(floats.subarray(off, off + dim)),
        filepath: entry.filepath || '',
        content_hash: entry.contentHash || '',
        contentHash: entry.contentHash || '',
        timestamp: entry.timestamp || 0,
        filename: entry.filename || '',
        artist: entry.artist || '',
        album: entry.album || '',
      };
    }
    console.log(`Loaded ${meta.entries.length} embeddings from stable binary store`);
    return result;
  } catch (e) {
    console.log('Stable binary read failed:', e.message);
    return null;
  }
}

async function _writeBinaryStore(embObj) {
  const normalized = _normalizeEmbeddingObject(embObj);
  const entries = [];
  let dim = 0;

  for (const [key, data] of Object.entries(normalized)) {
    if (key === '_path_index') continue;
    if (!data.embedding || !Array.isArray(data.embedding)) continue;
    if (dim === 0) dim = data.embedding.length;
    entries.push({
      key,
      filepath: data.filepath || '',
      contentHash: data.contentHash || data.content_hash || '',
      timestamp: data.timestamp || 0,
      filename: data.filename || '',
      artist: data.artist || '',
      album: data.album || '',
    });
  }

  if (entries.length === 0 || dim === 0) {
    await _deleteIfExists(STABLE_BIN);
    await _deleteIfExists(STABLE_META);
    return;
  }

  const floats = new Float32Array(entries.length * dim);
  let offset = 0;
  for (const entry of entries) {
    const emb = normalized[entry.key].embedding;
    for (let i = 0; i < dim; i++) floats[offset + i] = emb[i];
    offset += dim;
  }

  const base64 = _float32ToBase64(floats);
  const meta = {
    version: 2,
    dim,
    pathIndex: normalized._path_index || {},
    entries,
  };

  const tmpBin = _path(`${STABLE_BIN}.tmp`);
  const tmpMeta = _path(`${STABLE_META}.tmp`);

  await Promise.all([
    MusicBridge.writeBinaryFile({ path: tmpBin, data: base64 }),
    MusicBridge.writeTextFile({ path: tmpMeta, content: JSON.stringify(meta) }),
  ]);

  await _replaceFile(tmpBin, _path(STABLE_BIN));
  await _replaceFile(tmpMeta, _path(STABLE_META));
  console.log(`Wrote stable binary store: ${entries.length} entries, ${dim}d`);
}

async function _writeLegacyJson(embObj) {
  const normalized = _normalizeEmbeddingObject(embObj);
  const tmpJson = _path(`${LEGACY_JSON}.tmp`);
  await MusicBridge.writeTextFile({
    path: tmpJson,
    content: JSON.stringify(normalized),
  });
  await _replaceFile(tmpJson, _path(LEGACY_JSON));
  console.log(`Wrote local_embeddings.json mirror: ${_countEmbeddings(normalized)} entries`);
}

async function _readEmbeddingsJson(filename) {
  try {
    const result = await MusicBridge.readTextFile({ path: _path(filename) });
    if (!result.exists || !result.content) return null;
    const parsed = JSON.parse(result.content);
    const count = _countEmbeddings(parsed);
    if (count === 0) return null;
    return _normalizeEmbeddingObject(parsed);
  } catch (e) {
    console.log(`Could not read ${filename}:`, e.message);
    return null;
  }
}

function _normalizeEmbeddingObject(embObj) {
  const normalized = { _path_index: embObj && embObj._path_index ? embObj._path_index : {} };
  for (const [key, data] of Object.entries(embObj || {})) {
    if (key === '_path_index') continue;
    if (!data || !Array.isArray(data.embedding) || data.embedding.length === 0) continue;

    const contentHash = data.contentHash || data.content_hash || key;
    normalized[contentHash] = {
      embedding: data.embedding,
      filepath: data.filepath || '',
      content_hash: contentHash,
      contentHash,
      timestamp: data.timestamp || 0,
      filename: data.filename || '',
      artist: data.artist || '',
      album: data.album || '',
    };

    if (data.filepath) {
      normalized._path_index[data.filepath] = contentHash;
    }
  }
  return normalized;
}

function _mergeEmbeddingObjects(baseObj, extraObj) {
  const merged = _normalizeEmbeddingObject(baseObj);
  const extra = _normalizeEmbeddingObject(extraObj);

  for (const [key, data] of Object.entries(extra)) {
    if (key === '_path_index') continue;
    merged[key] = data;
    if (data.filepath) merged._path_index[data.filepath] = key;
  }

  return merged;
}

function _countEmbeddings(embObj) {
  return Object.keys(embObj || {}).filter(k => k !== '_path_index').length;
}

function _float32ToBase64(floats) {
  const bytes = new Uint8Array(floats.buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 8192) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + 8192, bytes.length)));
  }
  return btoa(binary);
}

async function _clearPending() {
  await MusicBridge.writeTextFile({
    path: _path(PENDING_JSON),
    content: JSON.stringify({ _path_index: {} }),
  });
}

async function _replaceFile(fromPath, toPath) {
  await MusicBridge.deleteFile({ path: toPath }).catch(() => {});
  await MusicBridge.renameFile({ from: fromPath, to: toPath });
}

async function _deleteIfExists(filename) {
  await MusicBridge.deleteFile({ path: _path(filename) }).catch(() => {});
}

async function _getStableStoreInfo() {
  const [binInfo, metaInfo] = await Promise.all([
    _getFileInfo(STABLE_BIN),
    _getFileInfo(STABLE_META),
  ]);
  return {
    exists: binInfo.exists && metaInfo.exists,
    lastModified: Math.max(binInfo.lastModified, metaInfo.lastModified),
  };
}

async function _getFileInfo(filename) {
  try {
    const result = await MusicBridge.getFileModified({ path: _path(filename) });
    return {
      exists: !!(result && result.exists),
      lastModified: Number(result && result.lastModified) || 0,
    };
  } catch (e) {
    return { exists: false, lastModified: 0 };
  }
}

function _path(filename) {
  return `${dataDir}/${filename}`;
}
