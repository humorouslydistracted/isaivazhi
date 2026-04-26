import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({
  preferences: new Map(),
  files: new Map(),
  library: [],
  savedLibraryWrites: [],
  fetchMode: 'success',
}));

vi.mock('@capacitor/preferences', () => ({
  Preferences: {
    async get({ key }) {
      return { value: harness.preferences.has(key) ? harness.preferences.get(key) : null };
    },
    async set({ key, value }) {
      harness.preferences.set(key, value);
    },
    async remove({ key }) {
      harness.preferences.delete(key);
    },
  },
}));

vi.mock('@capacitor/filesystem', () => ({
  Directory: { Data: 'DATA' },
  Encoding: { UTF8: 'utf8' },
  Filesystem: {
    async mkdir() {},
    async readdir({ path }) {
      const prefix = `${path}/`;
      const files = [];
      for (const key of harness.files.keys()) {
        if (!key.startsWith(prefix)) continue;
        files.push({ name: key.slice(prefix.length) });
      }
      return { files };
    },
    async appendFile({ path, data }) {
      const existing = harness.files.get(path) || '';
      harness.files.set(path, existing + data);
    },
    async writeFile({ path, data }) {
      harness.files.set(path, data);
    },
    async deleteFile({ path }) {
      harness.files.delete(path);
    },
  },
}));

vi.mock('../src/music-bridge.js', () => ({
  MusicBridge: {
    async getAppDataDir() {
      return {
        path: '/mock-data',
        artCacheDir: '/mock-data/art-cache',
      };
    },
    async readTextFile({ path }) {
      if (path.endsWith('song_library.json')) {
        return {
          data: JSON.stringify({
            savedAt: 1710000000000,
            songs: harness.library,
          }),
        };
      }
      return { data: '' };
    },
    async writeTextFile(payload) {
      harness.savedLibraryWrites.push(payload);
    },
    async scanAudioFiles() {
      return { songs: [] };
    },
  },
}));

vi.mock('../src/embedding-cache.js', () => ({
  setDataDir() {},
  async loadFromDisk() {
    return [];
  },
  async saveToDisk() {},
  async recoverPendingToStable() {},
}));

function makeLibrarySongs() {
  return [
    {
      filename: 'alpha.opus',
      title: 'Alpha',
      artist: 'Artist One',
      album: 'Album One',
      hasEmbedding: false,
      embeddingIndex: null,
      contentHash: 'hash-alpha',
      filePath: '/music/alpha.opus',
      artPath: '/art/alpha.jpg',
      dateModified: 1,
    },
    {
      filename: 'beta.opus',
      title: 'Beta',
      artist: 'Artist Two',
      album: 'Album Two',
      hasEmbedding: false,
      embeddingIndex: null,
      contentHash: 'hash-beta',
      filePath: '/music/beta.opus',
      artPath: '/art/beta.jpg',
      dateModified: 2,
    },
    {
      filename: 'gamma.opus',
      title: 'Gamma',
      artist: 'Artist Three',
      album: 'Album Three',
      hasEmbedding: false,
      embeddingIndex: null,
      contentHash: 'hash-gamma',
      filePath: '/music/gamma.opus',
      artPath: '/art/gamma.jpg',
      dateModified: 3,
    },
  ];
}

function seedPreferences(entries = {}) {
  for (const [key, value] of Object.entries(entries)) {
    harness.preferences.set(key, typeof value === 'string' ? value : JSON.stringify(value));
  }
}

async function flushAsync(times = 6) {
  for (let i = 0; i < times; i++) {
    await Promise.resolve();
  }
}

async function loadFreshEngine({ prefSeed = {}, fetchMode = 'success' } = {}) {
  harness.preferences.clear();
  harness.files.clear();
  harness.savedLibraryWrites = [];
  harness.library = makeLibrarySongs();
  harness.fetchMode = fetchMode;
  seedPreferences(prefSeed);

  vi.resetModules();
  window.Capacitor = window.Capacitor || {};
  window.Capacitor.convertFileSrc = (path) => path;

  globalThis.fetch = vi.fn(async () => {
    if (harness.fetchMode === 'fail') {
      throw new Error('fetch failed');
    }
    return {
      ok: true,
      async json() {
        return {
          savedAt: 1710000000000,
          songs: harness.library,
        };
      },
    };
  });

  const engine = await import('../src/engine.js');
  await engine.loadData();
  await flushAsync();
  return engine;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
  delete globalThis.fetch;
});

describe('engine regression coverage', () => {
  it('dedupes duplicate native queue advances so the next song is not misclassified', async () => {
    const engine = await loadFreshEngine();

    engine.playOnly(0);

    const first = engine.onNativeAdvance({
      action: 'user_jump',
      songId: 1,
      title: 'Beta',
      artist: 'Artist Two',
      album: 'Album Two',
      filePath: '/music/beta.opus',
      newIndex: 1,
      prevSongId: 0,
      prevFilename: 'alpha.opus',
      prevPlaybackInstanceId: 101,
      currentPlaybackInstanceId: 102,
      prevFraction: 0.53,
    });
    const duplicate = engine.onNativeAdvance({
      action: 'user_jump',
      songId: 1,
      title: 'Beta',
      artist: 'Artist Two',
      album: 'Album Two',
      filePath: '/music/beta.opus',
      newIndex: 1,
      prevSongId: 0,
      prevFilename: 'alpha.opus',
      prevPlaybackInstanceId: 101,
      currentPlaybackInstanceId: 102,
      prevFraction: 0.53,
    });

    const insights = engine.getInsights();

    expect(first && first.duplicate).not.toBe(true);
    expect(duplicate).toMatchObject({ duplicate: true });
    expect(engine.getState().current).toMatchObject({ id: 1, title: 'Beta' });
    expect(insights.session.totalListened).toBe(1);
    expect(insights.session.listenedSongs.map((song) => song.title)).toEqual(['Alpha']);
  });

  it('restores favorites and dislikes from persisted filename-based storage', async () => {
    const engine = await loadFreshEngine({
      prefSeed: {
        favorites: { filenames: ['beta.opus'] },
        disliked_songs: ['gamma.opus'],
      },
    });

    expect(engine.getSongs()).toHaveLength(3);
    expect(engine.getFavoritesList().map((song) => song.title)).toEqual(['Beta']);
    expect(engine.getDislikedSongsList().map((song) => song.title)).toEqual(['Gamma']);
    expect(engine.isFavorite(1)).toBe(true);
    expect(engine.isDisliked(2)).toBe(true);
  });

  it('favoriting a disliked song removes the dislike and persists the favorite state', async () => {
    const engine = await loadFreshEngine({
      prefSeed: {
        disliked_songs: ['alpha.opus'],
      },
    });

    const result = engine.toggleFavorite(0);
    await flushAsync();

    expect(result).toMatchObject({
      ok: true,
      isFavorite: true,
      unDisliked: true,
    });
    expect(engine.isFavorite(0)).toBe(true);
    expect(engine.isDisliked(0)).toBe(false);
    expect(JSON.parse(harness.preferences.get('favorites'))).toEqual({ filenames: ['alpha.opus'] });
    expect(JSON.parse(harness.preferences.get('disliked_songs'))).toEqual([]);
  });

  it('disliking a favorite song removes the favorite and persists the dislike state', async () => {
    const engine = await loadFreshEngine({
      prefSeed: {
        favorites: { filenames: ['beta.opus'] },
      },
    });

    const result = engine.toggleDislike(1);
    await flushAsync();

    expect(result).toMatchObject({
      ok: true,
      isDisliked: true,
      unFavorited: true,
    });
    expect(engine.isFavorite(1)).toBe(false);
    expect(engine.isDisliked(1)).toBe(true);
    expect(JSON.parse(harness.preferences.get('favorites'))).toEqual({ filenames: [] });
    expect(JSON.parse(harness.preferences.get('disliked_songs'))).toEqual(['beta.opus']);
  });

  it('normalizes, merges, and conditionally clears pending listen snapshots', async () => {
    const engine = await loadFreshEngine();

    const firstSave = await engine.savePendingListenSnapshot({
      songId: 0,
      playedMs: 190000,
      durationMs: 120000,
      playbackInstanceId: 44,
      reason: 'visibility_hidden',
      capturedAt: '2026-04-20T12:00:00.000Z',
    });
    const secondSave = await engine.savePendingListenSnapshot({
      filename: 'alpha.opus',
      playedMs: 90000,
      durationMs: 120000,
      playbackInstanceId: 44,
      reason: 'pagehide',
      capturedAt: '2026-04-20T12:01:00.000Z',
    });

    expect(firstSave.saved).toBe(true);
    expect(secondSave.saved).toBe(true);

    const stored = await engine.loadPendingListenSnapshot();
    expect(stored).toMatchObject({
      songId: 0,
      filename: 'alpha.opus',
      playedMs: 120000,
      durationMs: 120000,
      playbackInstanceId: 44,
      reason: 'pagehide',
      capturedAt: '2026-04-20T12:01:00.000Z',
    });

    expect(await engine.clearPendingListenSnapshot({ playbackInstanceId: 99 })).toBe(false);
    expect(await engine.loadPendingListenSnapshot()).not.toBeNull();
    expect(await engine.clearPendingListenSnapshot({ playbackInstanceId: 44, filename: 'alpha.opus' })).toBe(true);
    expect(await engine.loadPendingListenSnapshot()).toBeNull();
  });

  it('records recovered listens and backfills playback starts into song stats', async () => {
    const engine = await loadFreshEngine();

    const result = engine.recordRecoveredListen({
      filename: 'beta.opus',
      playedMs: 72000,
      durationMs: 120000,
      playbackInstanceId: 73,
      reason: 'native_transition_recovery',
    });
    await flushAsync();

    expect(result).toMatchObject({
      ok: true,
      songId: 1,
      fraction: 0.6,
      transitionAction: 'native_transition_recovery',
    });

    const stats = await engine.getSongPlayStats(1);
    expect(stats.plays).toBe(1);
    expect(stats.avgFrac).toBeCloseTo(0.6, 5);
  });

  it('restores playback state by filename and preserves favorite state on restore', async () => {
    const engine = await loadFreshEngine({
      prefSeed: {
        favorites: { filenames: ['gamma.opus'] },
        playback_state: {
          currentFilename: 'gamma.opus',
          currentTitle: 'Gamma',
          currentArtist: 'Artist Three',
          currentAlbum: 'Album Three',
          currentFilePath: '/music/gamma.opus',
          currentTime: 31,
          duration: 120,
          historyFilenames: ['alpha.opus'],
          queueFilenames: [{ filename: 'beta.opus', similarity: 0.42 }],
          listenedFilenames: [{ filename: 'alpha.opus', listen_fraction: 0.5 }],
          sessionLabel: 'Restored session',
          recToggle: true,
        },
      },
    });

    const restored = await engine.restorePlaybackState();

    expect(restored).toMatchObject({
      id: 2,
      title: 'Gamma',
      filename: 'gamma.opus',
      isFavorite: true,
      currentTime: 31,
      duration: 120,
    });
    expect(engine.getState().current).toMatchObject({ id: 2, title: 'Gamma' });
    expect(engine.getState().history.map((item) => item.id)).toEqual([0]);
    expect(engine.getState().queue).toEqual([
      expect.objectContaining({
        id: 1,
        similarity: '0.42',
      }),
    ]);
  });

  it('resetEngine clears persisted playback and taste state while emptying runtime lists', async () => {
    const engine = await loadFreshEngine({
      prefSeed: {
        favorites: { filenames: ['alpha.opus'] },
        disliked_songs: ['beta.opus'],
        playback_state: {
          currentFilename: 'alpha.opus',
        },
      },
    });

    await engine.savePendingListenSnapshot({
      filename: 'alpha.opus',
      playedMs: 30000,
      durationMs: 120000,
      playbackInstanceId: 5,
      reason: 'shutdown',
    });
    engine.toggleFavorite(0);
    engine.toggleDislike(1);
    await flushAsync();

    await engine.resetEngine();
    await flushAsync();

    expect(await engine.loadPendingListenSnapshot()).toBeNull();
    expect(engine.getFavoritesList()).toEqual([]);
    expect(engine.getDislikedSongsList()).toEqual([]);
    expect(engine.getState().current).toBeNull();
    expect(harness.preferences.has('favorites')).toBe(false);
    expect(harness.preferences.has('disliked_songs')).toBe(false);
    expect(harness.preferences.has('playback_state')).toBe(false);
    expect(harness.preferences.has('pending_listen_v1')).toBe(false);
    expect(harness.preferences.has('profile_summary_v2')).toBe(false);
  });
});
