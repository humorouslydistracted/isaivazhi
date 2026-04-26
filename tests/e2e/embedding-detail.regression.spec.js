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
    {
      filename: 'orphan.opus',
      title: 'Orphan',
      artist: 'Lost Artist',
      album: 'Archive',
      hasEmbedding: false,
      embeddingIndex: null,
      contentHash: 'hash-orphan',
      filePath: null,
      artPath: '/mock-device/art/orphan.jpg',
      dateModified: 4,
    },
  ];
}

function makeLegacyEmbeddings() {
  const base = {
    'hash-alpha': {
      embedding: [0.1, 0.2],
      filepath: '/mock-device/music/alpha.opus',
      contentHash: 'hash-alpha',
      filename: 'alpha.opus',
      artist: 'Artist One',
      album: 'Album One',
    },
    'hash-beta': {
      embedding: [0.2, 0.3],
      filepath: '/mock-device/music/beta.opus',
      contentHash: 'hash-beta',
      filename: 'beta.opus',
      artist: 'Artist Two',
      album: 'Album Two',
    },
    'hash-gamma': {
      embedding: [0.3, 0.4],
      filepath: '/mock-device/music/gamma.opus',
      contentHash: 'hash-gamma',
      filename: 'gamma.opus',
      artist: 'Artist Three',
      album: 'Album Three',
    },
    'hash-orphan': {
      embedding: [0.4, 0.5],
      filepath: '/mock-device/music/orphan.opus',
      contentHash: 'hash-orphan',
      filename: 'orphan.opus',
      artist: 'Lost Artist',
      album: 'Archive',
    },
  };

  for (let i = 1; i <= 18; i += 1) {
    base[`hash-extra-${i}`] = {
      embedding: [0.5 + i / 100, 0.6 + i / 100],
      filepath: `/mock-device/music/unmatched-${i}.opus`,
      contentHash: `hash-extra-${i}`,
      filename: `unmatched-${i}.opus`,
      artist: 'Imported Artist',
      album: 'Imported Album',
    };
  }

  return base;
}

function makeSeed() {
  return {
    library: makeLibrarySongs(),
    localEmbeddings: makeLegacyEmbeddings(),
    preferences: {},
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
        const strPath = String(path || '');
        if (strPath.endsWith('song_library.json')) {
          return {
            exists: true,
            content: JSON.stringify({
              savedAt: Date.now(),
              songs: seed.library || [],
            }),
            data: JSON.stringify({
              savedAt: Date.now(),
              songs: seed.library || [],
            }),
          };
        }
        if (strPath.endsWith('local_embeddings.json')) {
          return {
            exists: true,
            content: JSON.stringify(seed.localEmbeddings || {}),
            data: JSON.stringify(seed.localEmbeddings || {}),
          };
        }
        return { exists: false, content: '', data: '' };
      },
      async readBinaryFile() {
        return { exists: false, data: '' };
      },
      async writeTextFile() {},
      async writeBinaryFile() {},
      async deleteFile() {},
      async renameFile() {},
      async getFileModified({ path }) {
        const strPath = String(path || '');
        if (strPath.endsWith('local_embeddings.json')) {
          return { exists: true, lastModified: 10 };
        }
        return { exists: false, lastModified: 0 };
      },
      async convertEmbeddingsJsonToBinary() {
        return { success: false, reason: 'mocked browser seed' };
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
  await page.waitForFunction(() => typeof window._app?.showEmbeddingDetail === 'function');
  await page.waitForFunction(() => {
    return Array.from(document.querySelectorAll('.discover-action-btn'))
      .some((btn) => /AI/.test(btn.textContent || ''));
  });
}

test('AI embedding cleanup confirms stay visible and reachable during rerenders', async ({ page }) => {
  await bootstrap(page);

  await page.evaluate(() => window._app.showEmbeddingDetail());
  await expect(page.locator('.viewall-title')).toHaveText('AI Embedding');

  const unmatchedTile = page.locator('.emb-stat.clickable').filter({ hasText: 'Unmatched' }).first();
  await unmatchedTile.click();
  const unmatchedList = page.locator('#unmatchedSongsList');
  await expect(unmatchedList).toBeVisible();
  await unmatchedList.evaluate((el) => { el.scrollTop = el.scrollHeight; });

  const unmatchedButton = page.getByRole('button', { name: /Remove 18 Unmatched Embeddings/ });
  await unmatchedButton.click();
  const unmatchedConfirmYes = page.locator('#unmatchedConfirm .emb-confirm-yes');
  await expect(unmatchedConfirmYes).toBeVisible();

  const unmatchedVisibleInViewport = await page.evaluate(() => {
    const confirmBtn = document.querySelector('#unmatchedConfirm .emb-confirm-yes');
    if (!confirmBtn) return false;
    const btnRect = confirmBtn.getBoundingClientRect();
    return btnRect.top >= 0 && btnRect.bottom <= window.innerHeight;
  });
  expect(unmatchedVisibleInViewport).toBeTruthy();

  await page.evaluate(() => window._app.showEmbeddingDetail());
  await expect(unmatchedConfirmYes).toBeVisible();

  const orphanButton = page.getByRole('button', { name: /Remove 1 Orphaned Embeddings/ });
  await orphanButton.scrollIntoViewIfNeeded();
  const contentScrollBefore = await page.locator('.content').evaluate((el) => el.scrollTop);
  await orphanButton.click();
  const orphanConfirmYes = page.locator('#orphanConfirm .emb-confirm-yes');
  await expect(orphanConfirmYes).toBeVisible();

  const contentScrollAfter = await page.locator('.content').evaluate((el) => el.scrollTop);
  expect(contentScrollAfter).toBeGreaterThan(0);
  expect(contentScrollAfter).toBeGreaterThanOrEqual(Math.max(0, contentScrollBefore - 40));

  await page.evaluate(() => window._app.showEmbeddingDetail());
  await expect(orphanConfirmYes).toBeVisible();
});
