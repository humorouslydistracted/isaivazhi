import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({
  clock: 0,
  files: new Map(),
}));

function nextStamp() {
  harness.clock += 1;
  return harness.clock;
}

function writeFile(path, file) {
  harness.files.set(path, { ...file, lastModified: nextStamp() });
}

function readFile(path) {
  return harness.files.get(path) || null;
}

vi.mock('../src/music-bridge.js', () => ({
  MusicBridge: {
    async getFileModified({ path }) {
      const file = readFile(path);
      return {
        exists: !!file,
        lastModified: file ? file.lastModified : 0,
      };
    },
    async readTextFile({ path }) {
      const file = readFile(path);
      return {
        exists: !!file && file.type === 'text',
        content: file && file.type === 'text' ? file.content : '',
      };
    },
    async writeTextFile({ path, content }) {
      writeFile(path, { type: 'text', content });
      return { success: true };
    },
    async readBinaryFile({ path }) {
      const file = readFile(path);
      return {
        exists: !!file && file.type === 'binary',
        data: file && file.type === 'binary' ? file.data : '',
      };
    },
    async writeBinaryFile({ path, data }) {
      writeFile(path, { type: 'binary', data });
      return { success: true };
    },
    async deleteFile({ path }) {
      harness.files.delete(path);
      return { success: true };
    },
    async renameFile({ from, to }) {
      const file = readFile(from);
      if (!file) throw new Error(`Missing file for rename: ${from}`);
      harness.files.delete(from);
      harness.files.set(to, { ...file, lastModified: nextStamp() });
      return { success: true };
    },
    async convertEmbeddingsJsonToBinary() {
      return { success: false, reason: 'not_needed_in_test' };
    },
  },
}));

function makeEntry(hash, filepath, filename, seed) {
  return {
    embedding: [seed, seed + 0.25, seed + 0.5],
    content_hash: hash,
    filepath,
    filename,
    artist: `Artist ${seed}`,
    album: `Album ${seed}`,
    timestamp: 1000 + seed,
  };
}

function makeEmbeddings(entries) {
  const out = { _path_index: {} };
  for (const [hash, entry] of Object.entries(entries)) {
    out[hash] = entry;
    if (entry.filepath) out._path_index[entry.filepath] = hash;
  }
  return out;
}

function countEmbeddings(obj) {
  return Object.keys(obj).filter((key) => key !== '_path_index').length;
}

async function loadCache() {
  vi.resetModules();
  const cache = await import('../src/embedding-cache.js');
  cache.setDataDir('/mock-data');
  return cache;
}

beforeEach(() => {
  harness.clock = 0;
  harness.files.clear();
});

describe('embedding cache sync', () => {
  it('merges a newer local_embeddings.json on top of the stable binary cache instead of replacing it', async () => {
    const cache = await loadCache();

    const appOnly = makeEmbeddings({
      'hash-app': makeEntry('hash-app', '/songs/app.opus', 'app.opus', 1),
    });
    await cache.saveToDisk(appOnly);

    const colabOnly = makeEmbeddings({
      'hash-colab': makeEntry('hash-colab', '/songs/colab.opus', 'colab.opus', 2),
    });
    writeFile('/mock-data/local_embeddings.json', {
      type: 'text',
      content: JSON.stringify(colabOnly),
    });

    const loaded = await cache.loadFromDisk();
    expect(countEmbeddings(loaded)).toBe(2);
    expect(loaded['hash-app']).toBeTruthy();
    expect(loaded['hash-colab']).toBeTruthy();

    const mirroredJson = JSON.parse(readFile('/mock-data/local_embeddings.json').content);
    expect(countEmbeddings(mirroredJson)).toBe(2);
    expect(mirroredJson._path_index['/songs/app.opus']).toBe('hash-app');
    expect(mirroredJson._path_index['/songs/colab.opus']).toBe('hash-colab');
  });

  it('promotes pending embeddings into both the stable cache and the JSON mirror', async () => {
    const cache = await loadCache();

    const base = makeEmbeddings({
      'hash-base': makeEntry('hash-base', '/songs/base.opus', 'base.opus', 3),
    });
    await cache.saveToDisk(base);

    const pending = makeEmbeddings({
      'hash-pending': makeEntry('hash-pending', '/songs/pending.opus', 'pending.opus', 4),
    });
    writeFile('/mock-data/pending_embeddings.json', {
      type: 'text',
      content: JSON.stringify(pending),
    });

    await expect(cache.recoverPendingToStable()).resolves.toBe(true);

    const loaded = await cache.loadFromDisk();
    expect(countEmbeddings(loaded)).toBe(2);
    expect(loaded['hash-base']).toBeTruthy();
    expect(loaded['hash-pending']).toBeTruthy();

    const mirroredJson = JSON.parse(readFile('/mock-data/local_embeddings.json').content);
    expect(countEmbeddings(mirroredJson)).toBe(2);
    expect(mirroredJson['hash-pending'].filepath).toBe('/songs/pending.opus');

    const pendingAfter = JSON.parse(readFile('/mock-data/pending_embeddings.json').content);
    expect(countEmbeddings(pendingAfter)).toBe(0);
  });
});
