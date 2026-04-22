import { test, expect } from '@playwright/test';

const ONE_PIXEL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl9pS8AAAAASUVORK5CYII=',
  'base64'
);

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
      filePath: '/mock-device/music/alpha.opus',
      artPath: '/mock-device/art/alpha.jpg',
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
      filePath: '/mock-device/music/beta.opus',
      artPath: '/mock-device/art/beta.jpg',
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
      filePath: '/mock-device/music/gamma.opus',
      artPath: '/mock-device/art/gamma.jpg',
      dateModified: 3,
    },
  ];
}

function makeSeed() {
  return {
    library: makeLibrarySongs(),
    preferences: {
      favorites: { filenames: ['alpha.opus'] },
      profile_summary_v2: {
        songs: {
          'beta.opus': {
            plays: 2,
            skips: 0,
            completions: 1,
            fracs: [0.75],
            lastPlayedAt: '2026-04-20T10:00:00.000Z',
          },
        },
        totalPlays: 2,
        totalSkips: 0,
      },
      playback_state: {
        currentFilename: 'beta.opus',
        currentTitle: 'Beta',
        currentArtist: 'Artist Two',
        currentAlbum: 'Album Two',
        currentFilePath: '/mock-device/music/beta.opus',
        currentTime: 31,
        duration: 120,
        historyFilenames: ['alpha.opus'],
        queueFilenames: [{ filename: 'gamma.opus', similarity: 0.52 }],
        listenedFilenames: [{ filename: 'alpha.opus', listen_fraction: 0.5 }],
        sessionLabel: 'Restored session',
        recToggle: true,
      },
    },
  };
}

function installBrowserSeed(seed) {
  window.localStorage.clear();
  for (const [key, value] of Object.entries(seed.preferences || {})) {
    const stored = typeof value === 'string' ? value : JSON.stringify(value);
    window.localStorage.setItem(`CapacitorStorage.${key}`, stored);
  }

  window.Capacitor = window.Capacitor || {};
  window.Capacitor.convertFileSrc = (path) => {
    if (typeof path !== 'string') return path;
    return path.replace(/^file:\/\//, '');
  };

  const listenerMap = new Map();
  const addListener = (eventName, callback) => {
    const listeners = listenerMap.get(eventName) || [];
    listeners.push(callback);
    listenerMap.set(eventName, listeners);
    return {
      remove: async () => {
        const next = (listenerMap.get(eventName) || []).filter((cb) => cb !== callback);
        listenerMap.set(eventName, next);
      },
    };
  };

  const emit = async (eventName, payload) => {
    const listeners = listenerMap.get(eventName) || [];
    for (const cb of listeners) {
      await cb(payload);
    }
  };

  window.__MUSIC_APP_TEST__ = {
    MusicBridge: {
      addListener,
      async getAppDataDir() {
        return {
          path: '/mock-device',
          artCacheDir: '/mock-device/art-cache',
        };
      },
      async readTextFile({ path }) {
        if (String(path || '').endsWith('song_library.json')) {
          return {
            data: JSON.stringify({
              savedAt: Date.now(),
              songs: seed.library || [],
            }),
          };
        }
        return { data: '' };
      },
      async scanAudioFiles() {
        return { songs: [] };
      },
      async getAudioState() {
        return {
          isPlaying: false,
          position: 0,
          duration: 0,
          filePath: '',
          currentPlaybackInstanceId: 0,
          completedState: false,
          playedMs: 0,
          durationMs: 0,
        };
      },
      async getQueueState() {
        return { items: [], currentIndex: -1 };
      },
      async getRecentPlaybackTransitions() {
        return { items: [] };
      },
      async getAlbumArtUri({ path }) {
        return { uri: path || '' };
      },
      async writeTextFile() {},
      async extractAlbumArtBatch() {
        return { queued: 0 };
      },
      async embedNewSongs() {
        return { started: false };
      },
      async stopEmbedding() {
        return { stopped: true };
      },
      async setQueue() {},
      async playAudio() {},
      async setLoopMode() {},
      async replaceUpcoming() {},
      async updatePlaybackState() {},
      async pauseAudio() {},
      async resumeAudio() {},
      async nextTrack() {},
      async prevTrack() {},
      async seekAudio() {},
      stopPlaybackService() {},
      __emit: emit,
    },
  };
}

async function bootstrap(page) {
  const seed = makeSeed();
  await page.addInitScript(installBrowserSeed, seed);
  await page.route('**/song_library.json', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        savedAt: Date.now(),
        songs: seed.library,
      }),
    });
  });
  await page.route('**/mock-device/art/*.jpg', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/png',
      body: ONE_PIXEL_PNG,
    });
  });
  await page.goto('/');
  await expect(page.locator('.tab[data-tab="songs"]')).toBeVisible();
  await expect(page.locator('#npTitle')).toHaveText('Beta');
}

async function openSongMenu(page, title) {
  const row = page.locator('#panel-songs .song-item').filter({ hasText: title }).first();
  await expect(row).toBeVisible();
  await row.locator('.song-menu-btn').click();
  await expect(page.locator('.song-popup-menu')).toBeVisible();
}

test.beforeEach(async ({ page }) => {
  await bootstrap(page);
});

test('boots with seeded restore state and supports song search', async ({ page }) => {
  await expect(page.locator('#nowPlaying')).toBeVisible();
  await expect(page.locator('#npArtist')).toContainText('Artist Two');

  await page.locator('.tab[data-tab="songs"]').click();
  await expect(page.locator('#panel-songs .song-item')).toHaveCount(3);

  const searchInput = page.locator('#searchInput');
  await searchInput.fill('ga');
  await expect(page.locator('#panel-songs .song-item')).toHaveCount(1);
  await expect(page.locator('#panel-songs .song-item')).toContainText('Gamma');
});

test('shows seeded song statistics in the song details modal', async ({ page }) => {
  await page.locator('.tab[data-tab="songs"]').click();
  await openSongMenu(page, 'Beta');
  await page.locator('.song-popup-item[data-action="viewdetails"]').click();

  const overlay = page.locator('#songDetailsOverlay');
  await expect(overlay).toBeVisible();
  await expect(overlay.locator('.sd-row').filter({ hasText: 'Start count' }).locator('.sd-v')).toHaveText('2');
  await expect(overlay.locator('.sd-row').filter({ hasText: 'Avg listen' }).locator('.sd-v')).toHaveText('75%');
  await expect(overlay.locator('.sd-row').filter({ hasText: 'Favorite' }).locator('.sd-v')).toHaveText('No');
});

test('favorites can be toggled from the song menu and reflected in browse tiles', async ({ page }) => {
  await page.locator('.tab[data-tab="songs"]').click();
  await openSongMenu(page, 'Gamma');
  await page.locator('.song-popup-item[data-action="togglefav"]').click();

  await expect(page.locator('#statusToast')).toContainText('Added to favorites');

  await page.locator('.tab[data-tab="browse"]').click();
  const favoritesTile = page.locator('#browse-tiles .dtile').filter({
    has: page.locator('.dtile-title', { hasText: 'Favorites' }),
  }).first();

  await expect(favoritesTile).toBeVisible();
  await expect(favoritesTile.locator('.dtile-count')).toHaveText('2');
});

test('loop button cycles off → one → all → off and calls MusicBridge.setLoopMode', async ({ page }) => {
  test.setTimeout(60000);
  // Capture setLoopMode calls on the mocked bridge.
  await page.evaluate(() => {
    window.__loopModeCalls = [];
    const bridge = window.__MUSIC_APP_TEST__ && window.__MUSIC_APP_TEST__.MusicBridge;
    if (bridge) {
      bridge.setLoopMode = async ({ mode }) => {
        window.__loopModeCalls.push(mode);
      };
    }
  });

  const loopBtn = page.locator('#loopBtn');
  await expect(loopBtn).toBeVisible();

  // Internal loopMode starts at 'all'; first click cycles to 'off'.
  await loopBtn.click();
  await expect(loopBtn).not.toHaveClass(/active-mode/);

  await loopBtn.click();
  await expect(loopBtn).toHaveClass(/active-mode/);
  await expect(loopBtn).toHaveClass(/loop-one/);

  await loopBtn.click();
  await expect(loopBtn).toHaveClass(/loop-all/);

  await loopBtn.click();
  await expect(loopBtn).not.toHaveClass(/active-mode/);

  const modes = await page.evaluate(() => window.__loopModeCalls);
  expect(modes).toEqual([0, 1, 2, 0]);
});
