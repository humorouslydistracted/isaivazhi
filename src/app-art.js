export function createArtSupport({
  getArtUrl,
  musicBridge,
  onArtReady,
  resolveSong,
  concurrency = 4,
}) {
  const artRequestQueue = [];
  const artRequestState = new Map();
  let artRequestActive = 0;

  function pumpArtRequestQueue() {
    while (artRequestActive < concurrency && artRequestQueue.length > 0) {
      const nextSongId = artRequestQueue.shift();
      const entry = artRequestState.get(nextSongId);
      const nextSong = resolveSong(nextSongId);
      if (!entry || !nextSong || !nextSong.filePath) {
        if (entry && entry.resolve) entry.resolve(false);
        continue;
      }
      if (entry.status !== 'queued') continue;
      entry.status = 'pending';
      artRequestActive++;
      musicBridge.getAlbumArtUri({ path: nextSong.filePath }).then((res) => {
        if (res && res.exists && res.uri) {
          nextSong.artPath = res.uri;
          entry.status = 'ready';
          entry.resolve(true);
          onArtReady();
        } else {
          nextSong.artPath = null;
          entry.status = 'missing';
          entry.resolve(false);
        }
      }).catch(() => {
        nextSong.artPath = null;
        entry.status = 'missing';
        entry.resolve(false);
      }).finally(() => {
        artRequestActive = Math.max(0, artRequestActive - 1);
        pumpArtRequestQueue();
      });
    }
  }

  function enqueueSongArt(songInput, opts = {}) {
    const song = resolveSong(songInput);
    if (!song || song.id == null || !song.filePath) return Promise.resolve(false);
    if (song.artPath) return Promise.resolve(true);

    const existing = artRequestState.get(song.id);
    const retry = opts.retry === true;
    if (existing) {
      if (existing.status === 'pending' || existing.status === 'queued') return existing.promise;
      if (!retry && (existing.status === 'ready' || existing.status === 'missing')) return existing.promise;
    }

    let resolvePromise = null;
    const promise = new Promise(resolve => { resolvePromise = resolve; });
    artRequestState.set(song.id, { status: 'queued', promise, resolve: resolvePromise });
    if (opts.priority) artRequestQueue.unshift(song.id);
    else artRequestQueue.push(song.id);
    pumpArtRequestQueue();
    return promise;
  }

  function applyArtFallback(imgEl, fallbackText, fallbackClass) {
    if (!imgEl) return;
    const parent = imgEl.parentElement;
    if (!parent) return;
    if (fallbackClass) parent.classList.add(fallbackClass);
    imgEl.remove();
    if (!parent.querySelector('.art-fallback-text')) {
      const span = document.createElement('span');
      span.className = 'art-fallback-text';
      span.textContent = fallbackText || '?';
      parent.appendChild(span);
    }
  }

  async function handleArtErrorUI(imgEl, songId, fallbackText = '?', fallbackClass = '') {
    if (!imgEl) return;
    if (imgEl.dataset.artRecovered === '1') {
      applyArtFallback(imgEl, fallbackText, fallbackClass);
      return;
    }
    imgEl.dataset.artRecovered = '1';
    const song = resolveSong(songId);
    if (song) song.artPath = null;
    const recovered = await enqueueSongArt(songId, { retry: true, priority: true });
    const recoveredSong = resolveSong(songId);
    if (recovered && recoveredSong && recoveredSong.artPath) {
      const recoveredUrl = getArtUrl(recoveredSong, { prime: false });
      if (recoveredUrl) {
        imgEl.src = recoveredUrl;
        return;
      }
    }
    applyArtFallback(imgEl, fallbackText, fallbackClass);
  }

  function artOnErrorAttr(songId, fallbackText, fallbackClass) {
    return `onerror='window._app.handleArtError(this, ${songId}, ${JSON.stringify(fallbackText || '?')}, ${JSON.stringify(fallbackClass || '')})'`;
  }

  return {
    artOnErrorAttr,
    enqueueSongArt,
    handleArtErrorUI,
  };
}
