/**
 * Sidebar / Settings module.
 *
 * Provides:
 *   - Hamburger button (top-left) that opens a slide-in sidebar
 *   - Sidebar with Settings entry
 *   - Settings page with file upload for the embedding pair
 *     (local_embeddings.bin + local_embeddings_meta.json)
 *
 * Upload flow:
 *   1. User picks two files via the native file picker (HTML <input type=file>).
 *   2. JS validates the pair: meta JSON parses with shape { dim, entries[] };
 *      .bin byte length must equal entries.length * dim * 4 (float32 packed).
 *   3. Files are written to the app data dir via MusicBridge (same target dir
 *      that the on-device embedding pipeline uses).
 *   4. Engine reloads embeddings from disk and rebuilds the recommender.
 *
 * Wrong files / mismatched pairs are surfaced as "Not supported" in the UI
 * without writing anything to disk.
 */

import * as engine from './engine.js';
import { MusicBridge } from './music-bridge.js';

const STABLE_BIN = 'local_embeddings.bin';
const STABLE_META = 'local_embeddings_meta.json';

export function createSettingsSupport({ showStatusToast }) {
  let sidebarOpen = false;
  let onSettingsPageOpen = null;  // optional hook for activity logging

  // Root sidebar body — used at first render and when navigating back from
  // the Settings sub-page. Items are placed inside `.sidebar-items` which is
  // pushed to the bottom of the drawer via a flex spacer (CSS) so they're
  // within thumb reach. AI Embedding + Taste Signal moved here from the
  // Discover panel; they're settings-style controls, not surfaces the user
  // needs every time they open the app.
  const ROOT_SIDEBAR_BODY_HTML = `
    <div class="sidebar-spacer"></div>
    <div class="sidebar-items">
      <div class="sidebar-item" id="sidebarAiItem">
        <span class="sidebar-icon">&#129504;</span>
        <span>AI Embedding</span>
      </div>
      <div class="sidebar-item" id="sidebarTasteItem">
        <span class="sidebar-icon">&#9878;</span>
        <span>Taste Signal</span>
      </div>
      <div class="sidebar-item" id="sidebarSettingsItem">
        <span class="sidebar-icon">&#9881;</span>
        <span>Settings</span>
      </div>
    </div>
  `;

  function _wireRootSidebarHandlers() {
    const ai = document.getElementById('sidebarAiItem');
    const taste = document.getElementById('sidebarTasteItem');
    const settings = document.getElementById('sidebarSettingsItem');
    if (ai) ai.addEventListener('click', () => {
      closeSidebar();
      try {
        if (window._app && typeof window._app.showEmbeddingDetail === 'function') {
          window._app.showEmbeddingDetail();
        }
      } catch (e) { /* ignore */ }
    });
    if (taste) taste.addEventListener('click', () => {
      closeSidebar();
      try {
        if (window._app && typeof window._app.showTasteWeights === 'function') {
          window._app.showTasteWeights();
        }
      } catch (e) { /* ignore */ }
    });
    if (settings) settings.addEventListener('click', openSettingsPage);
  }

  function _ensureDom() {
    if (document.getElementById('sidebar')) return;
    const root = document.body;

    // 2026-05-10 follow-up #8: hamburger button now lives in the static
    // .top-bar in index.html (no longer dynamically created here). Just wire
    // up its click handler. The button id is `hamburgerBtn`.
    const hamburger = document.getElementById('hamburgerBtn');
    if (hamburger) hamburger.addEventListener('click', openSidebar);

    const backdrop = document.createElement('div');
    backdrop.id = 'sidebarBackdrop';
    backdrop.className = 'sidebar-backdrop';
    backdrop.addEventListener('click', closeSidebar);
    root.appendChild(backdrop);

    const sidebar = document.createElement('div');
    sidebar.id = 'sidebar';
    sidebar.className = 'sidebar';
    sidebar.setAttribute('data-no-tab-swipe', '');
    sidebar.innerHTML = `
      <div class="sidebar-header">
        <span class="sidebar-title">IsaiVazhi</span>
        <button class="sidebar-close" id="sidebarCloseBtn" aria-label="Close menu">&times;</button>
      </div>
      <div class="sidebar-body" id="sidebarBody">${ROOT_SIDEBAR_BODY_HTML}</div>
    `;
    root.appendChild(sidebar);

    document.getElementById('sidebarCloseBtn').addEventListener('click', closeSidebar);
    _wireRootSidebarHandlers();

    // Swipe-right to close gesture — touchstart anywhere on the sidebar,
    // tracks dx, snaps closed if user dragged > 60px right or fast flick.
    _attachSidebarSwipe(sidebar);
  }

  // Track current drag state so app.js's edge-swipe handler can also drive
  // the sidebar drawer transform via the same code path.
  let _sidebarDragDx = 0;
  let _sidebarDragActive = false;

  function _setSidebarDragOffset(px) {
    const sb = document.getElementById('sidebar');
    const bd = document.getElementById('sidebarBackdrop');
    if (!sb) return;
    const w = sb.getBoundingClientRect().width || 320;
    const clamped = Math.min(0, Math.max(-w, px));  // -w (closed) .. 0 (open)
    sb.style.transform = `translateX(${clamped}px)`;
    sb.classList.add('dragging');
    if (bd) {
      const opacity = (1 - Math.abs(clamped) / w);
      bd.style.opacity = String(opacity);
      bd.classList.add('open');
      bd.style.pointerEvents = opacity > 0.05 ? 'auto' : 'none';
    }
  }

  function _commitSidebarDrag(open) {
    const sb = document.getElementById('sidebar');
    const bd = document.getElementById('sidebarBackdrop');
    const eh = document.getElementById('sidebarEdgeHandle');
    if (sb) {
      sb.classList.remove('dragging');
      sb.style.transform = '';   // let CSS class drive the final position
      if (open) sb.classList.add('open');
      else sb.classList.remove('open');
    }
    if (bd) {
      bd.style.opacity = '';
      bd.style.pointerEvents = '';
      if (open) bd.classList.add('open');
      else bd.classList.remove('open');
    }
    if (eh) {
      if (open) eh.classList.add('hidden');
      else eh.classList.remove('hidden');
    }
    sidebarOpen = open;
    if (!open) _resetSidebarBody();  // reset to root view on close
  }

  function _resetSidebarBody() {
    const body = document.getElementById('sidebarBody');
    if (!body) return;
    body.innerHTML = ROOT_SIDEBAR_BODY_HTML;
    _wireRootSidebarHandlers();
  }

  function _attachSidebarSwipe(sidebar) {
    let startX = 0, startY = 0, startT = 0, locked = false, tracking = false;
    sidebar.addEventListener('touchstart', (e) => {
      if (!e.touches || e.touches.length !== 1) return;
      // Don't start swipe if user is interacting with a form control or scroller
      const tgt = e.target;
      if (tgt && tgt.closest && tgt.closest('input, textarea, select, .hscroll, .hscroll-wrap')) return;
      startX = e.touches[0].clientX;
      startY = e.touches[0].clientY;
      startT = Date.now();
      locked = false;
      tracking = true;
    }, { passive: true });
    sidebar.addEventListener('touchmove', (e) => {
      if (!tracking) return;
      const t = e.touches[0];
      const dx = t.clientX - startX;
      const dy = t.clientY - startY;
      if (!locked) {
        if (Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(dy)) locked = true;
        else if (Math.abs(dy) > 12) { tracking = false; return; }
      }
      if (locked && dx < 0) {
        _sidebarDragActive = true;
        _sidebarDragDx = dx;  // negative = moving left (closing)
        _setSidebarDragOffset(dx);
      }
    }, { passive: true });
    sidebar.addEventListener('touchend', (e) => {
      if (!tracking) return;
      tracking = false;
      if (!_sidebarDragActive) return;
      _sidebarDragActive = false;
      const t = (e.changedTouches && e.changedTouches[0]) || null;
      const dx = t ? (t.clientX - startX) : _sidebarDragDx;
      const dt = Date.now() - startT;
      const fastFlick = dt < 250 && dx < -40;
      const longDrag = dx < -80;
      _commitSidebarDrag(!(fastFlick || longDrag));
      _sidebarDragDx = 0;
    }, { passive: true });
    sidebar.addEventListener('touchcancel', () => {
      if (!_sidebarDragActive) { tracking = false; return; }
      _sidebarDragActive = false;
      tracking = false;
      _commitSidebarDrag(true);  // snap back to open on cancel
      _sidebarDragDx = 0;
    });
  }

  function openSidebar() {
    _ensureDom();
    document.getElementById('sidebar').classList.add('open');
    document.getElementById('sidebarBackdrop').classList.add('open');
    sidebarOpen = true;
  }

  function closeSidebar() {
    const sb = document.getElementById('sidebar');
    const bd = document.getElementById('sidebarBackdrop');
    if (sb) sb.classList.remove('open');
    if (bd) bd.classList.remove('open');
    _resetSidebarBody();
    sidebarOpen = false;
  }

  function _renderSettingsPage(statusMsg) {
    const body = document.getElementById('sidebarBody');
    if (!body) return;

    let embStatus = '';
    try {
      const st = engine.getEmbeddingStatus();
      embStatus = `<div class="settings-stat">
        <div class="settings-stat-label">Currently loaded</div>
        <div class="settings-stat-value">${st.totalEmbedded}/${st.totalSongs} songs embedded</div>
      </div>`;
    } catch (e) {
      embStatus = '<div class="settings-stat-label">Engine status unavailable</div>';
    }

    body.innerHTML = `
      <div class="settings-back-row">
        <button class="settings-back-btn" id="settingsBackBtn" aria-label="Back">&lsaquo; Back</button>
      </div>
      <div class="settings-section">
        <div class="settings-section-title">Embeddings</div>
        ${embStatus}
        <p class="settings-help">
          Upload pre-computed embeddings from your laptop. Pick BOTH files together:
          <code>local_embeddings.bin</code> and <code>local_embeddings_meta.json</code>.
        </p>
        <input type="file" id="settingsFileInput" multiple accept=".bin,.json,application/json,application/octet-stream" style="display:none;">
        <button class="settings-upload-btn" id="settingsUploadBtn">Pick files to upload</button>
        <div class="settings-upload-status" id="settingsUploadStatus">${statusMsg || ''}</div>
      </div>
    `;

    document.getElementById('settingsBackBtn').addEventListener('click', _resetSidebarBody);

    const fileInput = document.getElementById('settingsFileInput');
    const uploadBtn = document.getElementById('settingsUploadBtn');
    uploadBtn.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', _handleFilePick);
  }

  function openSettingsPage() {
    if (typeof onSettingsPageOpen === 'function') {
      try { onSettingsPageOpen(); } catch (_) { /* ignore */ }
    }
    _renderSettingsPage('');
  }

  function _setStatus(html) {
    const el = document.getElementById('settingsUploadStatus');
    if (el) el.innerHTML = html;
  }

  async function _handleFilePick(e) {
    const fileList = e.target.files;
    if (!fileList || fileList.length === 0) return;
    const files = Array.from(fileList);
    e.target.value = '';  // allow re-picking same files

    _setStatus('<span class="settings-status-busy">Validating files...</span>');

    // Identify .bin and .json by extension (case-insensitive)
    let binFile = null;
    let metaFile = null;
    let unsupportedNames = [];
    for (const f of files) {
      const lower = (f.name || '').toLowerCase();
      if (lower.endsWith('.bin')) {
        if (!binFile) binFile = f;
        else unsupportedNames.push(f.name + ' (extra .bin)');
      } else if (lower.endsWith('.json')) {
        if (!metaFile) metaFile = f;
        else unsupportedNames.push(f.name + ' (extra .json)');
      } else {
        unsupportedNames.push(f.name + ' (unsupported)');
      }
    }

    const errors = [];
    if (!binFile) errors.push('Missing local_embeddings.bin');
    if (!metaFile) errors.push('Missing local_embeddings_meta.json');
    if (unsupportedNames.length > 0) {
      errors.push('Not supported: ' + unsupportedNames.join(', '));
    }
    if (errors.length > 0) {
      _setStatus('<span class="settings-status-error">' + errors.map(e => '&#10005; ' + e).join('<br>') + '</span>');
      return;
    }

    // Parse meta JSON
    let metaText, meta;
    try {
      metaText = await metaFile.text();
      meta = JSON.parse(metaText);
    } catch (err) {
      _setStatus('<span class="settings-status-error">&#10005; Meta file is not valid JSON: ' + (err && err.message || err) + '</span>');
      return;
    }
    if (!meta || typeof meta !== 'object') {
      _setStatus('<span class="settings-status-error">&#10005; Meta file is not an object</span>');
      return;
    }
    const dim = Number(meta.dim);
    const entries = meta.entries;
    if (!Number.isFinite(dim) || dim <= 0) {
      _setStatus('<span class="settings-status-error">&#10005; Meta file missing valid dim</span>');
      return;
    }
    if (!Array.isArray(entries) || entries.length === 0) {
      _setStatus('<span class="settings-status-error">&#10005; Meta file has no entries</span>');
      return;
    }
    // Spot-check entry shape on first row
    const sample = entries[0] || {};
    if (typeof sample.filepath !== 'string' && typeof sample.filename !== 'string') {
      _setStatus('<span class="settings-status-error">&#10005; Entries missing filepath / filename fields</span>');
      return;
    }

    // Validate binary size matches metadata
    const expectedBinBytes = entries.length * dim * 4;
    if (binFile.size !== expectedBinBytes) {
      _setStatus(`<span class="settings-status-error">&#10005; Binary size mismatch: expected ${expectedBinBytes} bytes (${entries.length} entries &#215; ${dim}D &#215; 4), got ${binFile.size}</span>`);
      return;
    }

    _setStatus(`<span class="settings-status-busy">Writing ${entries.length} embeddings to device...</span>`);

    // Read .bin as ArrayBuffer → base64
    let binBase64;
    try {
      const arrayBuffer = await binFile.arrayBuffer();
      binBase64 = _arrayBufferToBase64(arrayBuffer);
    } catch (err) {
      _setStatus('<span class="settings-status-error">&#10005; Failed to read binary file: ' + (err && err.message || err) + '</span>');
      return;
    }

    // Resolve target paths in app data dir
    let dataDir;
    try {
      const dirResult = await MusicBridge.getAppDataDir();
      dataDir = dirResult && dirResult.path;
      if (!dataDir) throw new Error('Empty data dir from native');
    } catch (err) {
      _setStatus('<span class="settings-status-error">&#10005; Could not get app data dir: ' + (err && err.message || err) + '</span>');
      return;
    }

    const binPath = `${dataDir}/${STABLE_BIN}`;
    const metaPath = `${dataDir}/${STABLE_META}`;

    try {
      await MusicBridge.writeBinaryFile({ path: binPath, data: binBase64 });
      await MusicBridge.writeTextFile({ path: metaPath, content: metaText });
    } catch (err) {
      _setStatus('<span class="settings-status-error">&#10005; Write failed: ' + (err && err.message || err) + '</span>');
      return;
    }

    _setStatus('<span class="settings-status-busy">Reloading recommender from new embeddings...</span>');

    // Trigger engine reload of embeddings from disk
    let reloadResult = null;
    try {
      if (typeof engine.reloadEmbeddingsFromDisk === 'function') {
        reloadResult = await engine.reloadEmbeddingsFromDisk();
      }
    } catch (err) {
      // Treat reload failure as a soft warning — files are saved, app restart will pick them up.
      _setStatus(`<span class="settings-status-warn">&#10003; Files saved (${entries.length} entries). Reload pending: ${err && err.message || err}. Restart the app to apply.</span>`);
      try { showStatusToast('Embeddings saved. Restart to apply.', 3000); } catch (_) { /* ignore */ }
      return;
    }

    const merged = reloadResult && reloadResult.merged != null ? reloadResult.merged : entries.length;
    _setStatus(`<span class="settings-status-ok">&#10003; Embeddings loaded (${merged} merged into library)</span>`);
    try { showStatusToast(`Loaded ${merged} embeddings`, 2500); } catch (_) { /* ignore */ }
  }

  function _arrayBufferToBase64(buffer) {
    // Process in chunks to avoid call-stack overflows on large buffers.
    const bytes = new Uint8Array(buffer);
    const CHUNK = 0x8000;  // 32 KB
    let binary = '';
    for (let i = 0; i < bytes.length; i += CHUNK) {
      const slice = bytes.subarray(i, Math.min(i + CHUNK, bytes.length));
      binary += String.fromCharCode.apply(null, slice);
    }
    return btoa(binary);
  }

  // Build the hamburger button + sidebar shell up front so it's available
  // immediately. Settings page content is rendered lazily on demand.
  if (typeof document !== 'undefined' && document.body) {
    _ensureDom();
  } else if (typeof document !== 'undefined') {
    document.addEventListener('DOMContentLoaded', _ensureDom, { once: true });
  }

  return {
    openSidebar,
    closeSidebar,
    openSettingsPage,
    isOpen: () => sidebarOpen,
  };
}
