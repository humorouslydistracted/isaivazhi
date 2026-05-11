/**
 * IsaiVazhi UI — Capacitor version.
 * No server fetch calls — all logic runs locally via engine.js.
 */

// Global error handler to surface hidden errors
window.onerror = (msg, src, line, col, err) => {
  const el = document.getElementById('debug-toast') || (() => {
    const d = document.createElement('div');
    d.id = 'debug-toast';
    d.style.cssText = 'position:fixed;top:10px;left:50%;transform:translateX(-50%);background:#c00;color:#fff;padding:8px 16px;border-radius:8px;z-index:99999;font-size:11px;max-width:90%;word-break:break-all;';
    document.body.appendChild(d);
    return d;
  })();
  el.textContent = 'JS ERROR: ' + msg + ' (line ' + line + ')';
  el.style.opacity = '1';
};
window.onunhandledrejection = (e) => {
  console.error('Unhandled rejection:', e.reason);
};

import { Filesystem, Directory } from '@capacitor/filesystem';
import { App } from '@capacitor/app';
import * as engine from './engine.js';
import { MusicBridge } from './music-bridge.js';
import { initActivityLog, logActivity } from './activity-log.js';
import { createStatusUi } from './app-status-ui.js';
import { createBrowseRenderSupport } from './app-browse-render.js';
import { createPlaylistUi } from './app-playlists-ui.js';
import { createArtSupport } from './app-art.js';
import { createBackNavigationSupport } from './app-back-navigation.js';
import { createSongMenuSupport } from './app-song-menu.js';
import { createTasteUiSupport } from './app-taste-ui.js';
import { createAiPageSupport } from './app-ai-page.js';
import { createDiscoverUiSupport } from './app-discover-ui.js';
import { createPlayerUiSupport } from './app-player-ui.js';
import { createSettingsSupport } from './app-settings.js';

const _statusUi = createStatusUi();
const showStatusToast = _statusUi.showStatusToast;
const hideStatusToast = _statusUi.hideStatusToast;
function _handleRecommendationRebuildStatus(state) {
  return _statusUi.handleRecommendationRebuildStatus(state, {
    refreshStateUI: () => refreshStateUI(),
    showTasteWeightsOverlay: () => showTasteWeightsOverlay(),
  });
}

const _browseRender = createBrowseRenderSupport({
  esc: (...a) => esc(...a),
  getArtUrl: (...a) => getArtUrl(...a),
  resolveSongForArt: (...a) => _resolveSongForArt(...a),
  artOnErrorAttr: (...a) => _artOnErrorAttr(...a),
  getCurrentSongId: () => currentSong,
  isNativeAudioPlaying: () => nativeAudioPlaying,
  getSongsMap: () => engine.getSongs(),
});
const clearSearch = _browseRender.clearSearch;
const songThumb = _browseRender.songThumb;
const renderSongs = _browseRender.renderSongs;
const renderAlbums = _browseRender.renderAlbums;
const toggleAlbumUI = _browseRender.toggleAlbumUI;

const _playlistsUi = createPlaylistUi({
  esc: (...a) => esc(...a),
  showStatusToast: (...a) => showStatusToast(...a),
  refreshPlaylistViews: () => refreshPlaylistViews(),
  openPlaylist: (id) => openPlaylistUI(id),
  closeViewAll: () => closeViewAll(),
  getCurrentPlaylistViewId: () => _currentPlaylistViewId,
});
const closePlaylistPicker = _playlistsUi.closePlaylistPicker;
const showPlaylistPicker = _playlistsUi.showPlaylistPicker;
const createPlaylistFromModal = _playlistsUi.createPlaylistFromModal;
const addSongToPlaylistUI = _playlistsUi.addSongToPlaylistUI;
const removeSongFromPlaylistUI = _playlistsUi.removeSongFromPlaylistUI;
const renamePlaylistUI = _playlistsUi.renamePlaylistUI;
const deletePlaylistUI = _playlistsUi.deletePlaylistUI;

const _artSupport = createArtSupport({
  getArtUrl: (song, opts) => getArtUrl(song, opts),
  musicBridge: MusicBridge,
  onArtReady: () => _scheduleArtUiRefresh(),
  resolveSong: (input) => _resolveSongForArt(input),
});
const _enqueueSongArt = _artSupport.enqueueSongArt;
const handleArtErrorUI = _artSupport.handleArtErrorUI;
const _artOnErrorAttr = _artSupport.artOnErrorAttr;

const _backNav = createBackNavigationSupport({
  activateTab: (target, opts) => _activateTab(target, opts),
  getActiveTab: () => activeTab,
  closeTasteWeights: () => closeTasteWeightsOverlay(),
  hasDiscoverBackup: () => _hasDiscoverBackup(),
  closeViewAll: () => closeViewAll(),
  flushQueuedDiscoverRefresh: () => _flushQueuedDiscoverRefresh(),
  renderDiscoverSnapshotFromCache: (opts) => renderDiscoverSnapshotFromCache(opts),
  getActiveMenu: () => _songMenu.getActiveMenu(),
  closeSongMenu: () => _closeSongMenu(),
  getFullPlayerOpen: () => fullPlayerOpen,
  closeFullPlayer: () => closeFullPlayer(),
  minimizeApp: () => App.minimizeApp(),
});
const _switchToDiscover = _backNav.switchToDiscover;
const _isOnSubPage = _backNav.isOnSubPage;
const _closeActiveSubPage = _backNav.closeActiveSubPage;
const _handleBackButton = _backNav.handleBackButton;

const _songMenu = createSongMenuSupport({
  esc: (...a) => esc(...a),
  showStatusToast: (...a) => showStatusToast(...a),
  refreshStateUI: () => refreshStateUI(),
  refreshBrowseCollectionsUI: () => refreshBrowseCollectionsUI(),
  refreshPlaylistViews: () => refreshPlaylistViews(),
  showPlaylistPicker: (...a) => showPlaylistPicker(...a),
  removeSongFromPlaylistUI: (...a) => removeSongFromPlaylistUI(...a),
  showEmbeddingDetail: () => showEmbeddingDetail(),
  syncUpcomingNativeQueue: () => syncUpcomingNativeQueue(),
  getLastProfile: () => _lastProfile,
  renderDiscoverTiles: (...a) => renderDiscoverTiles(...a),
  playOnlyUI: (...a) => playOnlyUI(...a),
  closeFullPlayer: () => closeFullPlayer(),
  getFullPlayerOpen: () => fullPlayerOpen,
  getCurrentPlaylistViewId: () => _currentPlaylistViewId,
  loadAndPlay: (...a) => loadAndPlay(...a),
  getListenFraction: () => getListenFraction(),
  getCurrentSong: () => currentSong,
  setNativeAudioPlaying: (v) => { nativeAudioPlaying = v; },
  setNativeFileLoaded: (v) => { nativeFileLoaded = v; },
  updatePlayIcon: (...a) => updatePlayIcon(...a),
  pruneSongFromDiscoverCaches: (id) => _pruneSongFromDiscoverCaches(id),
  rerenderCachedDiscoverViews: () => _rerenderCachedDiscoverViews(),
  saveVisibleDiscoverCache: () => _saveVisibleDiscoverCache(),
  getActiveTab: () => activeTab,
  setActiveTab: (v) => { activeTab = v; },
  activateTab: (...a) => _activateTab(...a),
  renderSongs: (...a) => renderSongs(...a),
  renderAlbums: (...a) => renderAlbums(...a),
  getAllAlbums: () => allAlbums,
  getAlbumsDirty: () => _albumsDirty,
  setAlbumsDirty: (v) => { _albumsDirty = v; },
});
const _closeSongMenu = _songMenu.closeSongMenu;
const showSongMenu = _songMenu.showSongMenu;
const confirmDeleteSong = _songMenu.confirmDeleteSong;
const showSongDetailsModal = _songMenu.showSongDetailsModal;
const viewAlbumForSong = _songMenu.viewAlbumForSong;

const _tasteUi = createTasteUiSupport({
  esc: (...a) => esc(...a),
  showStatusToast: (...a) => showStatusToast(...a),
  songThumb: (...a) => songThumb(...a),
  getAllSongs: () => allSongs,
  getActiveTab: () => activeTab,
  getCurrentSong: () => currentSong,
  getNativeAudioPlaying: () => nativeAudioPlaying,
  getFullPlayerOpen: () => fullPlayerOpen,
  openFullPlayer: () => openFullPlayer(),
  getListenFraction: () => getListenFraction(),
  loadAndPlay: (...a) => loadAndPlay(...a),
  refreshStateUI: () => refreshStateUI(),
  loadFavorites: () => loadFavorites(),
  loadPlaylistsUI: () => loadPlaylistsUI(),
  renderDiscoverTiles: (...a) => renderDiscoverTiles(...a),
  renderDiscoverSnapshotFromCache: (opts) => renderDiscoverSnapshotFromCache(opts),
  refreshDiscoverPrimaryState: () => refreshDiscoverPrimaryState(),
  flushQueuedDiscoverRefresh: () => _flushQueuedDiscoverRefresh(),
  getLastProfile: () => _lastProfile,
  activityEntriesToCopyText: (entries) => _activityEntriesToCopyText(entries),
  copyTextToClipboard: (text, label) => _copyTextToClipboard(text, label),
  getViewAllMeta: (type) => getViewAllMeta(type),
  getDiscoverBackup: () => discoverContentBackup,
  setDiscoverBackup: (v) => { discoverContentBackup = v; },
  getDiscoverBackupPanelId: () => discoverContentBackupPanelId,
  setDiscoverBackupPanelId: (v) => { discoverContentBackupPanelId = v; },
  getViewAllItems: () => _viewAllItems,
  setViewAllItems: (v) => { _viewAllItems = v; },
  getCurrentViewAllType: () => _currentViewAllType,
  setCurrentViewAllType: (v) => { _currentViewAllType = v; },
  getCurrentPlaylistViewId: () => _currentPlaylistViewId,
  setCurrentPlaylistViewId: (v) => { _currentPlaylistViewId = v; },
});
const _renderActivityLogHtml = _tasteUi.renderActivityLogHtml;
const showTasteWeightsOverlay = _tasteUi.showTasteWeightsOverlay;
const closeTasteWeightsOverlay = _tasteUi.closeTasteWeightsOverlay;
const resetTasteWeightUI = _tasteUi.resetTasteWeightUI;
const copyTasteLogsUI = _tasteUi.copyTasteLogsUI;
const copyTastePlaybackSignalsUI = _tasteUi.copyTastePlaybackSignalsUI;
const playTasteSignalRowUI = _tasteUi.playTasteSignalRowUI;
const setTasteSignalFilterUI = _tasteUi.setTasteSignalFilterUI;
const setTasteSignalSortUI = _tasteUi.setTasteSignalSortUI;
const showMoreTasteSignalUI = _tasteUi.showMoreTasteSignalUI;
const toggleTasteResetInfoUI = _tasteUi.toggleTasteResetInfoUI;
const viewAllUI = _tasteUi.viewAllUI;
const refreshCurrentViewAllUI = _tasteUi.refreshCurrentViewAllUI;
const refreshBrowseCollectionsUI = _tasteUi.refreshBrowseCollectionsUI;
const openPlaylistUI = _tasteUi.openPlaylistUI;
const closeViewAll = _tasteUi.closeViewAll;

const _aiPage = createAiPageSupport({
  esc: (...a) => esc(...a),
  showStatusToast: (...a) => showStatusToast(...a),
  getAllSongs: () => allSongs,
  getDiscoverBackup: () => discoverContentBackup,
  setDiscoverBackup: (v) => { discoverContentBackup = v; },
  getDiscoverBackupPanelId: () => discoverContentBackupPanelId,
  setDiscoverBackupPanelId: (v) => { discoverContentBackupPanelId = v; },
  activityEntriesToCopyText: (entries) => _activityEntriesToCopyText(entries),
  copyTextToClipboard: (text, label) => _copyTextToClipboard(text, label),
  renderActivityLogHtml: (entries) => _renderActivityLogHtml(entries),
  getEmbDetailExpanded: () => _embDetailExpanded,
});
const showEmbeddingDetail = _aiPage.showEmbeddingDetail;
const _embToggleSection = _aiPage.embToggleSection;
const _setEmbLogTab = _aiPage.setEmbLogTab;
const _setEmbConfirm = _aiPage.setEmbConfirm;

const _settings = createSettingsSupport({
  showStatusToast: (...a) => showStatusToast(...a),
});
const _embScrollToPending = _aiPage.embScrollToPending;
const _copyEmbLogs = _aiPage.copyEmbLogs;

const _discoverUi = createDiscoverUiSupport({
  esc: (...a) => esc(...a),
  showStatusToast: (...a) => showStatusToast(...a),
  getArtUrl: (s, opts) => getArtUrl(s, opts),
  artOnErrorAttr: (...a) => _artOnErrorAttr(...a),
  getLastProfile: () => _lastProfile,
  setLastProfile: (v) => { _lastProfile = v; },
  getCachedFavorites: () => _cachedFavorites,
  setCachedFavorites: (v) => { _cachedFavorites = v; },
  getCachedRecentlyPlayed: () => _cachedRecentlyPlayed,
  setCachedRecentlyPlayed: (v) => { _cachedRecentlyPlayed = v; },
  getCachedForYou: () => _cachedForYou,
  setCachedForYou: (v) => { _cachedForYou = v; },
  getCachedSimilar: () => _cachedSimilar,
  setCachedSimilar: (v) => { _cachedSimilar = v; },
  getCachedBecauseYouPlayed: () => _cachedBecauseYouPlayed,
  setCachedBecauseYouPlayed: (v) => { _cachedBecauseYouPlayed = v; },
  getCachedUnexplored: () => _cachedUnexplored,
  setCachedUnexplored: (v) => { _cachedUnexplored = v; },
  getViewAllItems: () => _viewAllItems,
  setViewAllItems: (v) => { _viewAllItems = v; },
  getCurrentViewAllType: () => _currentViewAllType,
  getCurrentPlaylistViewId: () => _currentPlaylistViewId,
  getActiveTab: () => activeTab,
  getCurrentSong: () => currentSong,
  getNativeAudioPlaying: () => nativeAudioPlaying,
  viewAllUI: (...a) => viewAllUI(...a),
  isOnSubPage: () => _isOnSubPage(),
  hasDiscoverBackup: () => _hasDiscoverBackup(),
});
const renderSimilar = _discoverUi.renderSimilar;
const toggleSimilarFreezeUI = _discoverUi.toggleSimilarFreezeUI;
const toggleSectionUI = _discoverUi.toggleSectionUI;
const renderProfile = _discoverUi.renderProfile;
const renderDiscoverTiles = _discoverUi.renderDiscoverTiles;
const renderBecauseYouPlayed = _discoverUi.renderBecauseYouPlayed;
const renderCachedBecauseYouPlayed = _discoverUi.renderCachedBecauseYouPlayed;
const _renderBecauseYouPlayedSections = _discoverUi.renderBecauseYouPlayedSections;
const renderCachedUnexplored = _discoverUi.renderCachedUnexplored;
const renderDiscoverSnapshotFromCache = _discoverUi.renderDiscoverSnapshotFromCache;
const refreshDiscoverPrimaryState = _discoverUi.refreshDiscoverPrimaryState;
const refreshVisibleDiscoverCardState = _discoverUi.refreshVisibleDiscoverCardState;
const renderUnexploredClusters = _discoverUi.renderUnexploredClusters;
const _saveVisibleDiscoverCache = _discoverUi.saveVisibleDiscoverCache;
const _saveVisibleDiscoverCacheDebounced = _discoverUi.saveVisibleDiscoverCacheDebounced;
const _pruneSongFromDiscoverCaches = _discoverUi.pruneSongFromDiscoverCaches;
const _rerenderCachedDiscoverViews = _discoverUi.rerenderCachedDiscoverViews;

const _playerUi = createPlayerUiSupport({
  getCurrentSong: () => currentSong,
  getCurrentIsFav: () => currentIsFav,
  setCurrentIsFav: (v) => { currentIsFav = v; },
  getNativeAudioPlaying: () => nativeAudioPlaying,
  getNativeFileLoaded: () => nativeFileLoaded,
  getNativeAudioDur: () => nativeAudioDur,
  getNativeAudioPos: () => nativeAudioPos,
  setNativeAudioPos: (v) => { nativeAudioPos = v; },
  getShuffleOn: () => shuffleOn,
  setShuffleOn: (v) => { shuffleOn = v; },
  getLoopMode: () => loopMode,
  setLoopMode: (v) => { loopMode = v; },
  getFullPlayerOpen: () => fullPlayerOpen,
  setFullPlayerOpen: (v) => { fullPlayerOpen = v; },
  getActiveTab: () => activeTab,
  getRecsShouldFocusCurrent: () => _recsShouldFocusCurrent,
  getInitRestoreComplete: () => _initRestoreComplete,
  getQuickRestoreInfo: () => _quickRestoreInfo,
  setPendingStartupResume: (v) => { _pendingStartupResume = v; },
  getCachedForYou: () => _cachedForYou,
  getCachedSimilar: () => _cachedSimilar,
  getCachedFavorites: () => _cachedFavorites,
  getCachedBecauseYouPlayed: () => _cachedBecauseYouPlayed,
  getCachedUnexplored: () => _cachedUnexplored,
  getViewAllItems: () => _viewAllItems,
  getCurrentViewAllType: () => _currentViewAllType,
  esc: (...a) => esc(...a),
  showStatusToast: (...a) => showStatusToast(...a),
  loadAndPlay: (...a) => loadAndPlay(...a),
  refreshStateUI: (...a) => refreshStateUI(...a),
  getListenFraction: () => getListenFraction(),
  persistPlaybackState: (...a) => persistPlaybackState(...a),
  songThumb: (...a) => songThumb(...a),
  dbg: (...a) => _dbg(...a),
  notePlaybackIntent: () => _notePlaybackIntent(),
  shouldBlockRapidNav: (...a) => _shouldBlockRapidNav(...a),
  scheduleRecsFocusCurrent: () => _scheduleRecsFocusCurrent(),
  resolveSongForArt: (...a) => _resolveSongForArt(...a),
  enqueueSongArt: (...a) => _enqueueSongArt(...a),
  activateTab: (...a) => _activateTab(...a),
  syncUpcomingNativeQueue: () => syncUpcomingNativeQueue(),
  updateModeIndicator: () => updateModeIndicator(),
  updateHeartIcon: (...a) => updateHeartIcon(...a),
  getViewAllMeta: (...a) => getViewAllMeta(...a),
});
const playFromFavoritesUI = _playerUi.playFromFavoritesUI;
const playFromPlaylistUI = _playerUi.playFromPlaylistUI;
const updateProgressUI = _playerUi.updateProgressUI;
const updatePlayIcon = _playerUi.updatePlayIcon;
const toggleShuffleUI = _playerUi.toggleShuffleUI;
const toggleLoopUI = _playerUi.toggleLoopUI;
const goToQueueUI = _playerUi.goToQueueUI;
const toggleRecUI = _playerUi.toggleRecUI;
const renderRecs = _playerUi.renderRecs;
const renderHistory = _playerUi.renderHistory;
const playSongUI = _playerUi.playSongUI;
const playFromQueueUI = _playerUi.playFromQueueUI;
const playTimelineIndexUI = _playerUi.playTimelineIndexUI;
const playFromAlbumUI = _playerUi.playFromAlbumUI;
const playFromSectionUI = _playerUi.playFromSectionUI;
const playOnlyUI = _playerUi.playOnlyUI;
const togglePauseUI = _playerUi.togglePauseUI;
const nextUI = _playerUi.nextUI;
const prevUI = _playerUi.prevUI;
const seekUI = _playerUi.seekUI;
const setupSeekDrag = _playerUi.setupSeekDrag;
const getArtUrl = _playerUi.getArtUrl;
const openFullPlayer = _playerUi.openFullPlayer;
const closeFullPlayer = _playerUi.closeFullPlayer;
const syncFullPlayer = _playerUi.syncFullPlayer;
const updateFullPlayerProgress = _playerUi.updateFullPlayerProgress;
const setupFullPlayerGestures = _playerUi.setupFullPlayerGestures;
const formatTime = _playerUi.formatTime;

// On-screen debug logger — writes to #debugLogText if it exists + console.
// Re-enabled 2026-05-11 #3: tiny bottom-right toggle, full-screen panel on tap.
// Mirrors any console.log line that starts with one of the well-known tags
// ([PERF], [Embedding], [FAV], [SCAN], [DBG], [REC]) so we don't have to hunt
// for adb logcat to get a startup trace.
function _dbg(msg) {
  // The console.log hook below already mirrors [DBG]-tagged lines into the
  // panel via _appendDbgLine. No direct call here — calling both produced
  // duplicate lines (every entry showed up twice in the captured logs.txt).
  console.log('[DBG] ' + msg);
}

function _appendDbgLine(line) {
  try {
    const el = document.getElementById('debugLogText');
    if (!el) return;
    const ts = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 });
    el.textContent += ts + ' ' + line + '\n';
    const panel = document.getElementById('debugLogPanel');
    if (panel && panel.style.display !== 'none') panel.scrollTop = panel.scrollHeight;
  } catch (e) { /* ignore */ }
}

// Hook console.log so tagged lines are mirrored into the DBG panel without
// requiring every call site to invoke _dbg() explicitly. Untagged console.log
// goes straight through.
(function _installDbgConsoleMirror() {
  const _origConsoleLog = console.log.bind(console);
  const TAG_RE = /^\[(PERF|Embedding|FAV|SCAN|DBG|REC|GPU|Library)\]/;
  console.log = function (...args) {
    _origConsoleLog(...args);
    try {
      if (args.length > 0 && typeof args[0] === 'string' && TAG_RE.test(args[0])) {
        const line = args.map(a => typeof a === 'string' ? a : (typeof a === 'object' ? JSON.stringify(a) : String(a))).join(' ');
        _appendDbgLine(line);
      }
    } catch (e) { /* ignore */ }
  };
})();

// Wire toggle / copy / close buttons once the DOM exists.
if (typeof document !== 'undefined' && document.readyState !== 'loading') {
  _wireDbgPanel();
} else if (typeof document !== 'undefined') {
  document.addEventListener('DOMContentLoaded', _wireDbgPanel);
}

function _wireDbgPanel() {
  const toggle = document.getElementById('debugLogToggle');
  const panel = document.getElementById('debugLogPanel');
  const closeBtn = document.getElementById('debugLogClose');
  const copyBtn = document.getElementById('debugLogCopy');
  if (toggle && panel) {
    toggle.addEventListener('click', () => {
      panel.style.display = (panel.style.display === 'none' || !panel.style.display) ? 'block' : 'none';
      if (panel.style.display === 'block') panel.scrollTop = panel.scrollHeight;
    });
  }
  if (closeBtn && panel) {
    closeBtn.addEventListener('click', () => { panel.style.display = 'none'; });
  }
  if (copyBtn) {
    copyBtn.addEventListener('click', async () => {
      try {
        const el = document.getElementById('debugLogText');
        const text = el ? el.textContent : '';
        await navigator.clipboard.writeText(text);
        copyBtn.textContent = 'Copied!';
        setTimeout(() => { copyBtn.textContent = 'Copy Logs'; }, 1200);
      } catch (e) {
        copyBtn.textContent = 'Copy failed';
        setTimeout(() => { copyBtn.textContent = 'Copy Logs'; }, 1500);
      }
    });
  }
}


let allSongs = [];
let allAlbums = [];
let currentSong = null;
let currentIsFav = false;
let activeTab = 'discover';
let shuffleOn = false;
let loopMode = 'all';
let nextSongInfo = null;
let _recsShouldFocusCurrent = false;
let _artUiRefreshTimer = null;
// Native audio state — updated by audioTimeUpdate events from native service
let nativeAudioPos = 0;   // seconds
let nativeAudioDur = 0;   // seconds
let nativeAudioPlayedMs = 0;       // cumulative listened ms from native accumulator
let nativeAudioDurationMs = 0;     // track duration in ms from native
let nativePlaybackInstanceId = 0;   // native playback-instance authority for recovery
let nativeAudioPlaying = false;
let nativeFileLoaded = false; // true once playAudio has been called this session
let _initRestoreComplete = false; // true after restorePlaybackState resolves
let _quickRestoreInfo = null; // cached song info for UI only; not used as a playback source
let _miniPlayerFromQuickDisplay = false; // mini player was shown from quickDisplay; keep visible even if engine restore returned null (library not ready yet)
let _startupPlaybackTouched = false; // user started playback before startup restore finished
let _pendingStartupResume = false; // play was tapped before authoritative restore completed
let _aiHydrationState = 'cold'; // cold -> playback_ready -> ai_hydrating -> ai_ready
let _scanCompleted = false;
let _embeddingsReadyEvent = false;
let discoverContentBackup = null;
let discoverContentBackupPanelId = null;
let _lastProfile = null;
let _cachedRecentlyPlayed = [];
let _cachedBecauseYouPlayed = [];
let _cachedForYou = [];
let _cachedSimilar = [];
let _cachedFavorites = [];
let _cachedPlaylists = [];
let _cachedUnexplored = [];
let _viewAllItems = [];
let _currentViewAllType = null;
let _currentPlaylistViewId = null;
let fullPlayerOpen = false;
let _aiReadyCommitted = false;
let _lastPlaybackIntentAt = 0;
let _lastPlaybackPersistAt = 0;
let _lastPlaybackPersistPos = -1;
let _nativeAudioEventsBound = false;
const NAV_GUARD_MS = 320;
const STARTUP_AI_HYDRATION_DELAY_MS = 1500;
const PLAY_INTENT_AI_DEFER_MS = 4000;
const _lastNavAtByKind = { next: 0, prev: 0 };

function _notePlaybackIntent() {
  _lastPlaybackIntentAt = Date.now();
}

function _shouldBlockRapidNav(kind) {
  const now = Date.now();
  const lastAt = _lastNavAtByKind[kind] || 0;
  if ((now - lastAt) < NAV_GUARD_MS) return true;
  _lastNavAtByKind[kind] = now;
  return false;
}

function _resolveSongFromNativeState(data) {
  if (!data) return null;
  const songs = engine.getSongs();
  if (!songs || songs.length === 0) return null;

  if (data.filePath) {
    const match = songs.find(s => s && s.filePath && s.filePath.toLowerCase() === String(data.filePath).toLowerCase());
    if (match) return match;
  }

  const filename = data.filename || (data.filePath ? String(data.filePath).split(/[\\/]/).pop() : null);
  if (filename) {
    const match = songs.find(s => s && s.filename && s.filename.toLowerCase() === String(filename).toLowerCase());
    if (match) return match;
  }

  return null;
}

function _buildPendingListenSnapshot(reason) {
  if (currentSong == null) return null;
  const songs = engine.getSongs();
  const song = songs && songs[currentSong];
  if (!song || !song.filename) return null;

  const durationMs = nativeAudioDurationMs > 0
    ? Math.round(nativeAudioDurationMs)
    : (nativeAudioDur > 0 ? Math.round(nativeAudioDur * 1000) : 0);
  const playedMs = nativeAudioDurationMs > 0
    ? Math.round(Math.max(0, nativeAudioPlayedMs))
    : (nativeAudioDur > 0 ? Math.round(Math.max(0, nativeAudioPos) * 1000) : 0);
  if (!(durationMs > 0)) return null;

  return {
    songId: currentSong,
    filename: song.filename,
    title: song.title || song.filename,
    artist: song.artist || '',
    album: song.album || '',
    playbackInstanceId: Number(nativePlaybackInstanceId) || 0,
    playedMs: Math.min(playedMs, durationMs),
    durationMs,
    reason,
    capturedAt: new Date().toISOString(),
  };
}

async function _persistPendingListenEvidence(reason) {
  try {
    const snapshot = _buildPendingListenSnapshot(reason);
    if (!snapshot) return false;
    const result = await engine.savePendingListenSnapshot(snapshot);
    if (result && result.saved) {
      logActivity({
        category: 'playback',
        type: 'pending_listen_saved',
        message: `Saved pending listen snapshot on ${reason}`,
        data: {
          reason,
          filename: snapshot.filename,
          playbackInstanceId: snapshot.playbackInstanceId,
          playedMs: snapshot.playedMs,
          durationMs: snapshot.durationMs,
        },
        tags: ['listen', 'recovery'],
        important: false,
      });
      return true;
    }
  } catch (e) { /* best effort */ }
  return false;
}

async function _clearPendingListenIfResolvedByNative(data) {
  try {
    const prevPlaybackInstanceId = Number(data && data.prevPlaybackInstanceId) || 0;
    let cleared = false;
    if (prevPlaybackInstanceId > 0) {
      cleared = await engine.clearPendingListenSnapshot({ playbackInstanceId: prevPlaybackInstanceId });
    }
    if (!cleared && data && data.prevFilename) {
      cleared = await engine.clearPendingListenSnapshot({ filename: data.prevFilename });
    }
    if (cleared) {
      logActivity({
        category: 'playback',
        type: 'pending_listen_cleared',
        message: 'Cleared pending listen after native transition',
        data: {
          action: data.action || '',
          prevPlaybackInstanceId,
          prevFraction: data.prevFraction,
        },
        tags: ['listen', 'recovery'],
        important: false,
      });
    }
    return cleared;
  } catch (e) {
    return false;
  }
}

function _findMatchingNativeTransition(pendingSnapshot, transitions) {
  if (!pendingSnapshot || !Array.isArray(transitions) || transitions.length === 0) return null;
  const targetInstanceId = Number(pendingSnapshot.playbackInstanceId) || 0;
  if (!(targetInstanceId > 0)) return null;
  for (let i = transitions.length - 1; i >= 0; i--) {
    const evt = transitions[i];
    if ((Number(evt && evt.prevPlaybackInstanceId) || 0) === targetInstanceId) return evt;
  }
  return null;
}

async function _recoverPendingListenIfNeeded(liveAudioState = null) {
  try {
    const pending = await engine.loadPendingListenSnapshot();
    if (!pending) return;

    let recentTransitions = [];
    try {
      const payload = await MusicBridge.getRecentPlaybackTransitions({ limit: 24 });
      recentTransitions = Array.isArray(payload && payload.items) ? payload.items : [];
    } catch (e) { /* ignore */ }

    const matchingTransition = _findMatchingNativeTransition(pending, recentTransitions);
    const livePlaybackInstanceId = Number(liveAudioState && liveAudioState.currentPlaybackInstanceId) || 0;
    const nativeCompletedState = !!(liveAudioState && liveAudioState.completedState);
    const nativeHasLiveSession = !!(liveAudioState && liveAudioState.filePath && (liveAudioState.isPlaying || liveAudioState.position > 0 || liveAudioState.duration > 0));
    const nativeHasUsableSession = nativeHasLiveSession && !nativeCompletedState;

    if (matchingTransition) {
      const authoritativeAction = matchingTransition.action || 'native_transition_recovery';
      const result = engine.recordRecoveredListen({
        ...pending,
        songId: matchingTransition.prevSongId != null ? matchingTransition.prevSongId : pending.songId,
        filename: matchingTransition.prevFilename || pending.filename,
        playedMs: matchingTransition.prevPlayedMs != null ? matchingTransition.prevPlayedMs : pending.playedMs,
        durationMs: matchingTransition.prevDurationMs != null ? matchingTransition.prevDurationMs : pending.durationMs,
        reason: authoritativeAction,
      }, {
        fraction: matchingTransition.prevFraction,
        transitionAction: authoritativeAction,
      });
      await engine.clearPendingListenSnapshot({ playbackInstanceId: pending.playbackInstanceId, filename: pending.filename });
      logActivity({
        category: 'playback',
        type: 'pending_listen_recovered',
        message: result && result.ok
          ? 'Recovered pending listen from native transition history'
          : 'Dropped pending listen after native transition history match',
        data: {
          pendingPlaybackInstanceId: pending.playbackInstanceId,
          action: matchingTransition.action || '',
          recovered: !!(result && result.ok),
          fraction: result && result.fraction != null ? result.fraction : matchingTransition.prevFraction,
          filename: (result && result.filename) || pending.filename,
        },
        tags: ['listen', 'recovery'],
        important: true,
      });
      return;
    }

    if (nativeHasUsableSession && livePlaybackInstanceId > 0 && livePlaybackInstanceId === (Number(pending.playbackInstanceId) || 0)) {
      logActivity({
        category: 'playback',
        type: 'pending_listen_deferred',
        message: 'Pending listen snapshot kept because the same native playback instance is still active',
        data: {
          playbackInstanceId: pending.playbackInstanceId,
          filename: pending.filename,
        },
        tags: ['listen', 'recovery'],
        important: false,
      });
      return;
    }

    const result = engine.recordRecoveredListen(pending, {
      transitionAction: 'background_recovery',
    });
    await engine.clearPendingListenSnapshot({ playbackInstanceId: pending.playbackInstanceId, filename: pending.filename });
    logActivity({
      category: 'playback',
      type: 'pending_listen_recovered',
      message: result && result.ok
        ? 'Recovered pending listen from saved snapshot'
        : 'Dropped pending listen snapshot during recovery',
      data: {
        playbackInstanceId: pending.playbackInstanceId,
        recovered: !!(result && result.ok),
        fraction: result && result.fraction != null ? result.fraction : null,
        filename: (result && result.filename) || pending.filename,
      },
      tags: ['listen', 'recovery'],
      important: true,
    });
  } catch (e) { /* best effort */ }
}

async function _refreshNativePlaybackInstanceId() {
  try {
    const liveAudioState = await MusicBridge.getAudioState();
    nativePlaybackInstanceId = Number(liveAudioState && liveAudioState.currentPlaybackInstanceId) || nativePlaybackInstanceId;
    if (typeof liveAudioState?.playedMs === 'number') nativeAudioPlayedMs = liveAudioState.playedMs;
    if (typeof liveAudioState?.durationMs === 'number' && liveAudioState.durationMs > 0) nativeAudioDurationMs = liveAudioState.durationMs;
    if (nativePlaybackInstanceId > 0 && currentSong != null) {
      engine.bindCurrentPlaybackStartInstance(nativePlaybackInstanceId, currentSong);
    }
  } catch (e) { /* ignore */ }
}

function _focusCurrentRecsRow() {
  const currentRow = document.querySelector('#recs-list .song-item.playing');
  if (!currentRow) return;
  // 2026-05-10 follow-up #16: do NOT use `scrollIntoView` here. With the
  // scroll-snap panel-strip above us, `inline: 'nearest'` walked up through
  // ancestors and scrolled the .panel-strip horizontally if the recs panel
  // wasn't yet 100% in view (mid-snap arrival from a swipe). That second
  // horizontal scroll raced the browser's natural snap and produced the
  // "flick" the user reported on album→recs and browse→recs swipes.
  // Manual scrollTop on the .recs-list-wrap touches only the vertical scroll,
  // never the strip's horizontal scroll.
  const wrap = document.getElementById('recs-list-wrap');
  if (!wrap) return;
  const wrapRect = wrap.getBoundingClientRect();
  const rowRect = currentRow.getBoundingClientRect();
  // Position the row at the vertical centre of the wrap.
  const rowOffsetWithinWrap = (rowRect.top - wrapRect.top) + wrap.scrollTop;
  const targetTop = rowOffsetWithinWrap - (wrap.clientHeight - currentRow.offsetHeight) / 2;
  wrap.scrollTop = Math.max(0, targetTop);
}

function _scheduleRecsFocusCurrent() {
  _recsShouldFocusCurrent = true;
  const run = () => {
    if (!_recsShouldFocusCurrent) return;
    _focusCurrentRecsRow();
    _recsShouldFocusCurrent = false;
  };
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(() => requestAnimationFrame(run));
  } else {
    setTimeout(run, 30);
  }
}

function _resolveSongForArt(input) {
  if (input == null) return null;
  const songs = engine.getSongs();
  if (typeof input === 'number') return songs[input] || null;
  if (input.id != null && songs[input.id]) return songs[input.id];
  return input;
}

// 2026-05-10 follow-up #12: universal search moved to a top-bar overlay,
// so the Songs/Albums tabs always show the full library. These helpers used
// to read the in-tab search query; now they pass straight through. Kept as
// thin wrappers because many places call them — clearer than rewriting all
// the call sites and harmless if anything else later wants to filter.
function _filteredSongs() { return allSongs; }
function _filteredAlbums() { return allAlbums; }

function _scheduleArtUiRefresh() {
  if (_artUiRefreshTimer) return;
  _artUiRefreshTimer = setTimeout(() => {
    _artUiRefreshTimer = null;
    allSongs = engine.getPlayableSongs();
    allAlbums = engine.getAlbums();
    _songsDirty = true;
    _albumsDirty = true;
    if (activeTab === 'songs') {
      renderSongs(_filteredSongs());
      _songsDirty = false;
    }
    if (activeTab === 'albums') {
      renderAlbums(_filteredAlbums());
      _albumsDirty = false;
    }
    if (activeTab === 'recs') {
      refreshStateUI();
      return;
    }
    const discoverPanel = document.getElementById('panel-discover');
    if (discoverPanel && discoverPanel.querySelector('.taste-weights-page')) {
      showTasteWeightsOverlay();
      return;
    }
    if (activeTab === 'discover' && discoverPanel && !discoverPanel.querySelector('.emb-detail-page, .viewall-header')) {
      renderDiscoverSnapshotFromCache({ fade: false });
      refreshVisibleDiscoverCardState();
    }
    if (activeTab === 'browse') {
      const browsePanel = document.getElementById('panel-browse');
      if (browsePanel && !browsePanel.querySelector('.viewall-header')) {
        loadPlaylistsUI();
        loadFavorites();
        if (_lastProfile) renderDiscoverTiles(_lastProfile);
      }
    }
    syncFullPlayer();
  }, 120);
}

function persistPlaybackState(force = false) {
  if (currentSong == null) return;
  const now = Date.now();
  const pos = nativeAudioPos || 0;
  if (!force) {
    if ((now - _lastPlaybackPersistAt) < 2000) return;
    if (Math.abs(pos - _lastPlaybackPersistPos) < 1.5) return;
  }
  _lastPlaybackPersistAt = now;
  _lastPlaybackPersistPos = pos;
  engine.savePlaybackState(pos, nativeAudioDur || 0);
}

function _setAiHydrationState(next) {
  if (_aiHydrationState === next) return;
  _aiHydrationState = next;
  logActivity({
    category: 'engine',
    type: 'ai_hydration_state_changed',
    message: `AI state -> ${next}`,
    data: { state: next },
    tags: ['startup', 'ai'],
    important: false,
  });
}

function _scheduleBackgroundHydration() {
  const start = () => {
    const recentPlaybackIntentAge = _lastPlaybackIntentAt > 0 ? (Date.now() - _lastPlaybackIntentAt) : Infinity;
    // Fix H: the 4s playback-intent deferral was sized for the old world where
    // the post-deferral work was 5+ seconds (binary read + merge + recommender +
    // profile build) and could fight the audio decoder. After Fix G the binary
    // read happens during loadData (preloadEmbeddingsEarly) and finishes in
    // <500ms. By the time playback intent fires, the heavy work is already
    // done — `startBackgroundScan` then just runs ~50ms of merge + Recommender
    // + GPU-attach work, which can't meaningfully interfere with audio. So
    // when the early load has resolved, we skip the long deferral and use a
    // short ~120ms grace window just to let the audio thread settle.
    const earlyDone = typeof engine.isEarlyEmbeddingLoaded === 'function' && engine.isEarlyEmbeddingLoaded();
    if (_pendingStartupResume || recentPlaybackIntentAge < PLAY_INTENT_AI_DEFER_MS) {
      if (earlyDone && !_pendingStartupResume) {
        _dbg('init: playback intent active but early embeddings already loaded — using short 120ms grace');
        setTimeout(start_now, 120);
        return;
      }
      const remaining = _pendingStartupResume
        ? 350
        : Math.max(350, PLAY_INTENT_AI_DEFER_MS - recentPlaybackIntentAge);
      _dbg('init: background scan deferred ' + remaining + 'ms after playback intent');
      setTimeout(start, remaining);
      return;
    }
    start_now();
  };
  const start_now = () => {
    _setAiHydrationState('ai_hydrating');
    engine.startBackgroundScan();
    _dbg('init: background scan started');
  };
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(() => setTimeout(start, STARTUP_AI_HYDRATION_DELAY_MS));
  } else {
    setTimeout(start, STARTUP_AI_HYDRATION_DELAY_MS);
  }
}

function _commitAiReady(discoverCache) {
  if (_aiReadyCommitted || !_scanCompleted || !_embeddingsReadyEvent) return;
  _aiReadyCommitted = true;
  _setAiHydrationState('ai_ready');
  _dbg('commitAiReady: entry currentSong=' + currentSong + ' nativeAudioPlaying=' + nativeAudioPlaying + ' cachedForYou=' + ((_lastProfile && _lastProfile.forYou) || []).length + ' cachedByp=' + (_cachedBecauseYouPlayed || []).length + ' cachedUnexp=' + (_cachedUnexplored || []).length);

  allSongs = engine.getPlayableSongs();
  allAlbums = engine.getAlbums();
  _songsDirty = true;
  _albumsDirty = true;

  // Preserve the existing Discover snapshot. Only Most Similar refreshes
  // automatically once embeddings become ready; the other Discover rows now
  // wait for manual pull-to-refresh — with one-time repair below when the
  // cached state is clearly incomplete.
  renderSimilar();
  if ((_cachedUnexplored || []).length === 0) renderCachedUnexplored({ fade: false });

  // One-time repair: if the cached discover snapshot is missing or sparse
  // because embeddings were not merged when it was saved, reseed now that
  // embeddings are authoritative. This does NOT make Discover auto-refresh —
  // it only fills in gaps so cached sections actually appear on cold start.
  _repairDiscoverCacheOnReady();

  updateEmbeddingStatus();
  updateModeIndicator();
  refreshStateUI();
  hideStatusToast();
  _dbg('EVENT: aiReady');
  showStatusToast('Embeddings ready', 2000);
  _saveVisibleDiscoverCache();
}

// One-shot repair on the first aiReady commit. Called only from _commitAiReady.
// - Empty For You  → run analyzeProfile once and render + save
// - Fewer than 3 Because You Played sections → call getBecauseYouPlayed and accept
//   the fresh result ONLY if it produces more sections than currently cached
function _repairDiscoverCacheOnReady() {
  const forYouEmpty = !_lastProfile || !Array.isArray(_lastProfile.forYou) || _lastProfile.forYou.length === 0;
  const bypCount = (_cachedBecauseYouPlayed || []).length;

  if (bypCount < 3) {
    try {
      const freshByp = engine.getBecauseYouPlayed(3, 6, {}) || [];
      if (freshByp.length > bypCount) {
        _dbg('commitAiReady: repairing becauseYouPlayed ' + bypCount + ' -> ' + freshByp.length);
        _renderBecauseYouPlayedSections(freshByp, { fade: false });
      } else {
        _dbg('commitAiReady: byp repair skipped (fresh=' + freshByp.length + ' <= cached=' + bypCount + ')');
      }
    } catch (e) {
      _dbg('commitAiReady: byp repair failed ' + e.message);
    }
  }

  if (forYouEmpty) {
    _dbg('commitAiReady: repairing empty For You via analyzeProfile');
    engine.analyzeProfile(15, { refreshForYou: false })
      .then(data => {
        if (data) {
          renderProfile(data);
          _saveVisibleDiscoverCache();
          _dbg('commitAiReady: For You repair done, length=' + ((data.forYou || []).length));
        }
      })
      .catch(e => { _dbg('commitAiReady: For You repair failed ' + (e && e.message || e)); });
  }
}

// ===== INIT =====

async function init() {
  try {
    await initActivityLog();
    logActivity({ category: 'app', type: 'app_opened', message: 'App opened', tags: ['startup'], important: true });
    // 2026-05-11 #5: do NOT call preloadEmbeddingsEarly() here anymore. The
    // earlier design (Fix D, 2026-05-10) overlapped the multi-MB bin read
    // with loadData()'s Preferences reads to hide the binary-store latency.
    // After Fix G the binary read goes through fetch(convertFileSrc(...))
    // — same Capacitor WebView native plugin executor pool that handles
    // Preferences.get. On a cold cache the 5MB transfer pins the pool and
    // every other plugin call queues behind it. Captured logs.txt showed
    // Preferences.get(playback_state) blocking on a 10KB payload for
    // 2466ms while the bin fetch was in flight — pushing the mini-player
    // play tap to 3.3s tap-to-audio. preloadEmbeddingsEarly() is now
    // deferred until after restorePlaybackState resolves so the critical-
    // path Preferences read runs uncontended; see the matching call below.
    // Show mini player IMMEDIATELY from cached display info (before loadData)
    const quickDisplay = await engine.getLastPlayedDisplay();
    if (quickDisplay) {
      document.getElementById('npTitle').textContent = quickDisplay.title;
      document.getElementById('npArtist').textContent = quickDisplay.artist + ' \u00b7 ' + (quickDisplay.album || '');
      document.getElementById('nowPlaying').style.display = 'block';
      document.getElementById('npRedDot').style.display = 'none';
      if (quickDisplay.duration > 0) {
        nativeAudioDur = quickDisplay.duration;
        nativeAudioPos = quickDisplay.currentTime;
        updateProgressUI(quickDisplay.currentTime, quickDisplay.duration);
      }
      updatePlayIcon(true); // paused state until we check native
      // Keep quick restore info only as a visual placeholder for the mini
      // player. Playback must wait for the authoritative restore path so it
      // resumes from the real saved position instead of briefly starting at 0.
      if (quickDisplay.filePath) {
        _quickRestoreInfo = {
          title: quickDisplay.title,
          artist: quickDisplay.artist,
          album: quickDisplay.album,
          filename: quickDisplay.filename,
          filePath: quickDisplay.filePath,
        };
      }
      _miniPlayerFromQuickDisplay = true;
      _dbg('init: quickDisplay shown, filename=' + quickDisplay.filename);
    }

    // Load discover cache BEFORE loadData — render instantly from checkpoint
    const discoverCache = await engine.loadDiscoverCache();
    if (discoverCache) {
      renderDiscoverFromCache(discoverCache);
      showStatusToast('Loading embeddings...', 0); // 0 = stay until manually hidden
    }

    const _t0 = performance.now();
    _dbg('init: loadData starting');
    engine.onLoadingStatus((msg) => {
      // Suppress background-scan toasts while the user is on the embedding detail
      // page — they're distracting and unrelated to the user's current action.
      const panel = document.getElementById('panel-discover');
      if (panel && panel.querySelector('.emb-detail-page')) return;
      showStatusToast(msg);
    });
    engine.onRecommendationRebuildStatus(_handleRecommendationRebuildStatus);
    await engine.loadData();
    _dbg('init: loadData done ' + Math.round(performance.now() - _t0) + 'ms, songs=' + engine.getPlayableSongs().length);
    allSongs = engine.getPlayableSongs();
    allAlbums = engine.getAlbums();
    // 2026-05-10 follow-up #11: pre-render Songs + Albums lists immediately so
    // a horizontal swipe to those panels doesn't trigger a 100-300ms lazy
    // render mid-gesture (the dominant cause of swipe hiccups now that
    // scroll-snap takes the gesture itself out of the JS critical path).
    // Done synchronously here while the user is still looking at the
    // Discover panel — the cost gets folded into the existing post-load
    // pause, so it's invisible.
    renderSongs(allSongs);
    renderAlbums(allAlbums);
    _songsDirty = false;
    _albumsDirty = false;
    loadPlaylistsUI();
    if (!discoverCache) hideStatusToast();
    setupNativeAudioEvents();
    setupSeekDrag();
    setupFullPlayerGestures();
    setupNativeMediaListener();
    setupPositionPersistence();
    setupEmbeddingUI();

    // Load discover content (non-blocking) — skip if cache already rendered
    if (!discoverCache) loadDiscover();

    // Now resolve full playback state (needs songs array from loadData)
    _dbg('init: restoring playback state');
    const restored = await engine.restorePlaybackState();
    const pendingListenSnapshot = await engine.loadPendingListenSnapshot();
    _dbg('init: restore done ' + Math.round(performance.now() - _t0) + 'ms, currentSong=' + (restored ? restored.id : 'null') + ', filePath=' + (restored ? !!restored.filePath : 'n/a'));
    // 2026-05-11 #5: kick off the embedding bin preload NOW — restorePlaybackState
    // has finished its critical Preferences.get, so the Capacitor native plugin
    // pool is uncontended for the multi-MB fetch. startBackgroundScan() will
    // reuse this in-flight promise (or discard if the resolved data dir
    // doesn't match). Fire-and-forget; failures are silent and harmless.
    engine.preloadEmbeddingsEarly();
    if (restored || pendingListenSnapshot) {
      let liveAudioState = null;
      let liveQueueState = null;
      try {
        liveAudioState = await MusicBridge.getAudioState();
      } catch (e) { /* ignore */ }
      nativePlaybackInstanceId = Number(liveAudioState && liveAudioState.currentPlaybackInstanceId) || nativePlaybackInstanceId;
      if (typeof liveAudioState?.playedMs === 'number') nativeAudioPlayedMs = liveAudioState.playedMs;
      if (typeof liveAudioState?.durationMs === 'number' && liveAudioState.durationMs > 0) nativeAudioDurationMs = liveAudioState.durationMs;
      const nativeCompletedState = !!(liveAudioState && liveAudioState.completedState);
      const nativeHasLiveSession = !!(liveAudioState && liveAudioState.filePath && (liveAudioState.isPlaying || liveAudioState.position > 0 || liveAudioState.duration > 0));
      const nativeHasUsableSession = nativeHasLiveSession && !nativeCompletedState;
      if (restored && nativeHasUsableSession) {
        try {
          liveQueueState = await MusicBridge.getQueueState();
        } catch (e) { /* ignore */ }
      }
      await _recoverPendingListenIfNeeded(liveAudioState);
      if (restored) {
        const preserveLivePlayback = (_startupPlaybackTouched || nativeFileLoaded) && nativeHasUsableSession;
        const liveSong = nativeHasUsableSession ? _resolveSongFromNativeState(liveAudioState) : null;
        const syncedNativeSong = nativeHasUsableSession
          ? (liveQueueState
              ? engine.syncQueueFromNativeSnapshot(liveQueueState, {
                  appendHistory: true,
                  currentSource: liveAudioState.isPlaying ? 'native_resume' : 'native_paused_restore',
                  ensurePlaybackStart: true,
                  playbackInstanceId: nativePlaybackInstanceId,
                })
              : engine.syncCurrentFromNativeState(liveAudioState, {
                  currentSource: liveAudioState.isPlaying ? 'native_resume' : 'native_paused_restore',
                  ensurePlaybackStart: true,
                  playbackInstanceId: nativePlaybackInstanceId,
                }))
          : null;
        const displaySong = syncedNativeSong || (nativeHasUsableSession && liveSong
          ? {
              id: liveSong.id,
              title: liveSong.title,
              artist: liveSong.artist,
              album: liveSong.album,
              filename: liveSong.filename,
              filePath: liveSong.filePath,
              hasEmbedding: liveSong.hasEmbedding,
              isFavorite: engine.isFavorite(liveSong.id),
            }
          : restored);

        currentSong = displaySong.id;
        currentIsFav = displaySong.isFavorite || false;
        _miniPlayerFromQuickDisplay = false; // real restore succeeded; no longer need the shim
        document.getElementById('npTitle').textContent = displaySong.title;
        document.getElementById('npArtist').textContent = displaySong.artist + ' \u00b7 ' + (displaySong.album || '');
        document.getElementById('nowPlaying').style.display = 'block';
        document.getElementById('npRedDot').style.display = 'none';
        updateHeartIcon(currentIsFav);
        updateModeIndicator();
        syncFullPlayer();
        refreshStateUI();

        if (preserveLivePlayback) {
          nativeAudioPos = liveAudioState.position || nativeAudioPos || 0;
          nativeAudioDur = liveAudioState.duration || nativeAudioDur || displaySong.duration || restored.duration || 0;
          nativeAudioPlaying = !!liveAudioState.isPlaying;
          nativeFileLoaded = true;
          updateProgressUI(nativeAudioPos, nativeAudioDur);
          updatePlayIcon(!nativeAudioPlaying);
          _dbg('init: preserving live playback during restore pos=' + nativeAudioPos + ' playing=' + nativeAudioPlaying);
        } else {
          if (restored.duration > 0) nativeAudioDur = restored.duration;
          if (nativeHasUsableSession) {
            nativeAudioPos = liveAudioState.position || 0;
            nativeAudioDur = liveAudioState.duration || nativeAudioDur;
            nativeAudioPlaying = !!liveAudioState.isPlaying;
            nativeFileLoaded = true;
            updateProgressUI(nativeAudioPos, nativeAudioDur);
            updatePlayIcon(!nativeAudioPlaying);
          } else if (restored.currentTime > 0) {
            nativeAudioPos = restored.currentTime;
            nativeAudioPlaying = false;
            nativeFileLoaded = false;
            updateProgressUI(restored.currentTime, nativeAudioDur);
            updatePlayIcon(true);
          } else {
            nativeFileLoaded = false;
          }
          if (nativeCompletedState) {
            _dbg('init: native session was in completed state, forcing cold restore path');
          }
        }
        if (_pendingStartupResume && !nativeHasUsableSession && displaySong.filePath && !nativeFileLoaded) {
          _pendingStartupResume = false;
          _dbg('init: honoring queued startup resume tap');
          await loadAndPlay({
            id: displaySong.id,
            title: displaySong.title,
            artist: displaySong.artist,
            album: displaySong.album,
            filename: displaySong.filename,
            filePath: displaySong.filePath,
            isFavorite: displaySong.isFavorite || false,
          }, restored.currentTime || nativeAudioPos || 0);
        }
      }
    }
    _quickRestoreInfo = null; // full restore done, no longer needed
    _initRestoreComplete = true;
    _pendingStartupResume = false;
    _setAiHydrationState('playback_ready');
    _dbg('init: READY — currentSong=' + currentSong + ' initComplete=true');

    // Start AI hydration only after playback-ready state is reached so early
    // play taps are not competing with embedding merge/profile work.
    _scheduleBackgroundHydration();

    // Track background scan completion, but defer AI-heavy UI refresh until the
    // embeddings-ready transition commits once.
    engine.onScanComplete(async () => {
      _dbg('EVENT: scanComplete');
      _scanCompleted = true;
      allSongs = engine.getPlayableSongs();
      allAlbums = engine.getAlbums();
      _songsDirty = true;
      _albumsDirty = true;
      // If user is currently on songs/albums tab, render now
      if (activeTab === 'songs') renderSongs(_filteredSongs());
      if (activeTab === 'albums') renderAlbums(_filteredAlbums());
      // If the initial restore could not resolve currentFilename because the song
      // library was not yet complete, retry now that the scan has populated it.
      if (currentSong == null && _miniPlayerFromQuickDisplay) {
        try {
          _dbg('scanComplete: retrying restorePlaybackState (initial restore did not set currentSong)');
          const retried = await engine.restorePlaybackState();
          if (retried && retried.id != null) {
            currentSong = retried.id;
            currentIsFav = retried.isFavorite || false;
            document.getElementById('npTitle').textContent = retried.title;
            document.getElementById('npArtist').textContent = retried.artist + ' \u00b7 ' + (retried.album || '');
            document.getElementById('nowPlaying').style.display = 'block';
            updateHeartIcon(currentIsFav);
            _miniPlayerFromQuickDisplay = false;
            _dbg('scanComplete: retry restore OK id=' + retried.id);
            if (_pendingStartupResume && retried.filePath && !nativeFileLoaded) {
              _pendingStartupResume = false;
              _dbg('scanComplete: honoring queued startup resume tap');
              await loadAndPlay({
                id: retried.id,
                title: retried.title,
                artist: retried.artist,
                album: retried.album,
                filename: retried.filename,
                filePath: retried.filePath,
                isFavorite: retried.isFavorite || false,
              }, retried.currentTime || nativeAudioPos || 0);
            }
          } else {
            _dbg('scanComplete: retry restore still null, keeping quickDisplay shim');
          }
        } catch (e) {
          _dbg('scanComplete: retry restore failed ' + e.message);
        }
      }
      // Refresh mini player state — mode indicator and red dot are now accurate
      updateModeIndicator();
      syncFullPlayer();
      _commitAiReady(discoverCache);
    });

    // Deferred deletion detection (cache-skip startup path) and play-time
    // file-missing reconciliation both fire this callback after the library
    // shape changed outside of the normal scan flow. Re-pull songs and
    // re-render the active tab so the UI reflects the new state.
    engine.onSongLibraryChanged(() => {
      _dbg('EVENT: songLibraryChanged');
      allSongs = engine.getPlayableSongs();
      allAlbums = engine.getAlbums();
      _songsDirty = true;
      _albumsDirty = true;
      if (activeTab === 'songs') renderSongs(_filteredSongs());
      if (activeTab === 'albums') renderAlbums(_filteredAlbums());
      refreshStateUI();
      // If the AI embedding detail page is currently visible, re-render it now
      // instead of waiting for the 2-second polling tick. Without this, a song
      // the user just deleted from the Songs tab would linger in the AI page's
      // orphan/pending counts for up to two seconds.
      const discoverPanel = document.getElementById('panel-discover');
      if (discoverPanel && discoverPanel.querySelector('.emb-detail-page')) {
        try { showEmbeddingDetail(); } catch (e) { /* ignore */ }
      }
    });

    // Embeddings ready is the AI hydration completion signal. Commit the heavy
    // Discover refresh exactly once after both scan + embeddings transitions.
    engine.onEmbeddingsReady(() => {
      _dbg('EVENT: embeddingsReady');
      _embeddingsReadyEvent = true;
      _commitAiReady(discoverCache);
    });

    // When album art extraction finishes, refresh cards and current player art
    engine.onAlbumArtReady(() => {
      allSongs = engine.getPlayableSongs();
      allAlbums = engine.getAlbums();
      _songsDirty = true;
      _albumsDirty = true;
      if (activeTab === 'songs') { renderSongs(_filteredSongs()); _songsDirty = false; }
      if (activeTab === 'albums') { renderAlbums(_filteredAlbums()); _albumsDirty = false; }
      syncFullPlayer();
      _scheduleArtUiRefresh();
    });
  } catch (e) {
    console.error('Init failed:', e);
    document.body.innerHTML = `<div style="display:flex;height:100vh;align-items:center;justify-content:center;color:#e84545;font-family:sans-serif;padding:20px;text-align:center;">
      Failed to load: ${esc(e.message)}<br><br>Make sure embeddings.json and metadata.json are available.
    </div>`;
  }
}

// ===== AUDIO FILE ACCESS =====

async function getAudioUrl(songInfo) {
  // Use full file path if available (from scan)
  if (songInfo.filePath) {
    try {
      return window.Capacitor.convertFileSrc('file://' + songInfo.filePath);
    } catch (e) {
      console.error('convertFileSrc failed for path:', songInfo.filePath, e);
    }
  }
  // Fallback: try reading from known songs directory
  try {
    const result = await Filesystem.getUri({
      path: `songs_downloaded/${songInfo.filename}`,
      directory: Directory.ExternalStorage,
    });
    return window.Capacitor.convertFileSrc(result.uri);
  } catch (e) {
    console.error('Cannot access file:', songInfo.filename, e);
    return null;
  }
}

// ===== FAVORITES =====

function handleFavToggle() {
  try {
    if (currentSong == null) {
      showDebugToast('FAV: no song playing');
      return;
    }
    const result = engine.toggleFavorite(currentSong);
    if (result && result.ok) {
      currentIsFav = result.isFavorite;
      updateHeartIcon(currentIsFav);
      loadFavorites();
      updateFavCount(result.count);
      const songs = engine.getSongs();
      const title = songs[currentSong] ? songs[currentSong].title : '';
      logActivity({
        category: 'ui',
        type: 'favorite_button_pressed',
        message: `${result.isFavorite ? 'Favorited' : 'Unfavorited'} "${title}"`,
        data: { songId: currentSong, isFavorite: result.isFavorite },
        tags: ['favorite'],
        important: true,
      });
      showStatusToast(result.isFavorite ? `"${title}" added to favorites` : `"${title}" removed from favorites`, 2000);
      syncNativeNotificationMeta();
    } else {
      showStatusToast('Failed to update favorite', 2000);
    }
  } catch (e) {
    showDebugToast('FAV CRASH: ' + e.message);
    console.error('[FAV-UI] CRASH:', e);
  }
}

// Attach fav button listener directly via JS
document.addEventListener('DOMContentLoaded', () => {
  const favBtn = document.getElementById('favBtn');
  if (favBtn) {
    favBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      handleFavToggle();
    });
    favBtn.addEventListener('touchend', (e) => {
      e.preventDefault();
      e.stopPropagation();
      handleFavToggle();
    });
  }
});

function showDebugToast(msg) {
  let toast = document.getElementById('debug-toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'debug-toast';
    toast.style.cssText = 'position:fixed;top:10px;left:50%;transform:translateX(-50%);background:#333;color:#fff;padding:8px 16px;border-radius:8px;z-index:99999;font-size:13px;transition:opacity 0.3s;pointer-events:none;';
    document.body.appendChild(toast);
  }
  toast.textContent = msg;
  toast.style.opacity = '1';
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => { toast.style.opacity = '0'; }, 3000);
}

function updateFavCount(count) {
  // Update the fav badge in stats
  const statItems = document.querySelectorAll('#profile-stats .stat-item');
  for (const item of statItems) {
    const label = item.querySelector('.stat-label');
    if (label && label.textContent === 'Favs') {
      const num = item.querySelector('.stat-num');
      if (num) num.textContent = count;
    }
  }
}

function updateHeartIcon(isFav) {
  const btn = document.getElementById('favBtn');
  if (btn) {
    btn.classList.toggle('fav-active', isFav);
    btn.textContent = isFav ? '\u2665' : '\u2661';
  }
  const fpFav = document.getElementById('fpFavBtn');
  if (fpFav) {
    fpFav.classList.toggle('is-fav', isFav);
    fpFav.textContent = isFav ? '\u2665' : '\u2661';
  }
}

function syncNativeNotificationMeta() {
  if (currentSong == null) return;
  const songs = engine.getSongs();
  const song = songs[currentSong];
  if (!song) return;
  try {
    MusicBridge.updatePlaybackState({
      title: song.title || '',
      artist: song.artist || '',
      album: song.album || '',
      isPlaying: !!nativeAudioPlaying,
    });
  } catch (e) { /* ignore */ }
}

function loadFavorites() {
  const favs = engine.getFavoritesList();
  renderFavorites(favs);
}

function renderFavorites(favs) {
  _cachedFavorites = favs || [];
  renderDiscoverTiles(_lastProfile);
}

function getViewAllMeta(type) {
  if (type === 'mostPlayed') return { title: 'Most Played', empty: 'No history yet' };
  if (type === 'recentlyPlayed') return { title: 'Recently Played', empty: 'No history yet' };
  if (type === 'lastAdded') return { title: 'Last Added', empty: 'Nothing new' };
  if (type === 'neverPlayed') return { title: 'Never Played', empty: 'Played everything!' };
  if (type === 'noEmbedding') return { title: 'Songs Without AI Embedding', empty: 'Everything has AI ready' };
  if (type === 'favorites') return { title: 'Favorites', empty: 'No favorites yet' };
  if (type === 'dislikedSongs') return { title: 'Disliked Songs', empty: 'No disliked songs' };
  if (type === 'forYou') return { title: 'For You', empty: 'Play songs to get personalized picks' };
  if (type === 'similar') return { title: 'Most Similar', empty: 'Play a song to see similar picks' };
  return { title: 'View All', empty: 'Nothing here yet' };
}

function loadPlaylistsUI() {
  renderPlaylists(engine.getPlaylists());
}

function renderPlaylists(playlists) {
  const el = document.getElementById('playlists-list');
  if (!el) return;

  _cachedPlaylists = playlists || [];
  if (!_cachedPlaylists.length) {
    el.innerHTML = '<div class="playlist-empty">Create a playlist here, or use any song menu to add songs into one.</div>';
    return;
  }

  const cardHtml = (pl) => {
    const thumbs = (pl.preview || []).slice(0, 4).map(s => {
      const art = getArtUrl(s);
      if (art) {
        const letter = esc((s.title || '?')[0].toUpperCase());
        const onerr = s && s.id != null ? _artOnErrorAttr(s.id, letter, 'playlist-thumb-letter') : '';
        return `<div class="playlist-thumb"><img src="${art}" decoding="async" alt="" ${onerr}></div>`;
      }
      return `<div class="playlist-thumb playlist-thumb-letter"><span>${esc((s.title || '?')[0].toUpperCase())}</span></div>`;
    }).join('');
    const filler = (pl.preview || []).length < 4 ? '<div class="playlist-thumb playlist-thumb-empty"></div>'.repeat(4 - (pl.preview || []).length) : '';
    const grid = pl.count > 0
      ? `<div class="playlist-thumb-grid">${thumbs}${filler}</div>`
      : '<div class="playlist-card-empty">No songs yet</div>';
    return `<div class="playlist-card" onclick="window._app.openPlaylist('${pl.id}')">
      <div class="playlist-card-head">
        <div class="playlist-card-title">${esc(pl.name)}</div>
        <div class="playlist-card-count">${pl.count}</div>
      </div>
      ${grid}
    </div>`;
  };

  el.innerHTML = _cachedPlaylists.map(cardHtml).join('');
}

function refreshPlaylistViews() {
  loadPlaylistsUI();
  if (_currentPlaylistViewId) {
    if (engine.getPlaylistMeta(_currentPlaylistViewId)) {
      openPlaylistUI(_currentPlaylistViewId);
    } else {
      closeViewAll();
    }
  }
}


// ===== DISCOVER TAB =====

function loadDiscover(opts = {}) {
  _clearQueuedDiscoverRefresh();
  // Render sync sections immediately — zero awaits
  if (opts.refreshDiscover === true) {
    renderBecauseYouPlayed({ refresh: true });
    renderUnexploredClusters({ refresh: true });
  } else {
    if ((_cachedBecauseYouPlayed || []).length > 0) renderCachedBecauseYouPlayed();
    else renderBecauseYouPlayed();

    if ((_cachedUnexplored || []).length > 0) renderCachedUnexplored();
    else renderUnexploredClusters();
  }
  // renderSimilar handles all 5 placeholder states internally based on embedding
  // readiness / per-song embedding / paused / in-progress / idle.
  renderSimilar();
  loadFavorites();
  loadPlaylistsUI();

  engine.loadRecentlyPlayed()
    .then(data => {
      _cachedRecentlyPlayed = data || [];
      if (_lastProfile) renderDiscoverTiles(_lastProfile);
      _saveVisibleDiscoverCacheDebounced();
    })
    .catch(() => {});
  // analyzeProfile: pass opts.refreshForYou through so pull-to-refresh (and only
  // pull-to-refresh / explicit refresh) triggers a fresh For You shuffle. All
  // other callers reuse the cached For You list.
  engine.analyzeProfile(15, { refreshForYou: opts.refreshForYou === true })
    .then(data => {
      if (data) {
        renderProfile(data);
        // Persist the populated profile (forYou etc.) into the discover cache so
        // subsequent cold starts render For You from cache instead of showing empty.
        _saveVisibleDiscoverCacheDebounced();
      }
    })
    .catch(() => {});
}

// Render discover page entirely from cached data — no engine calls needed
function renderDiscoverFromCache(cache) {
  // Profile stats
  if (cache.profile && cache.profile.stats) {
    renderProfile(cache.profile, { fade: false });
  }

  // Because You Played
  _cachedBecauseYouPlayed = cache.becauseYouPlayed || [];
  renderCachedBecauseYouPlayed({ fade: false });

  // Unexplored is intentionally snapshot-only here. If startup cache did not
  // include it, it stays empty until a manual Discover refresh populates it.
  _cachedUnexplored = Array.isArray(cache.unexplored) ? cache.unexplored : [];
  renderCachedUnexplored({ fade: false });

  // Reserve the Most Similar slot from cold startup so nothing below shifts
  // when real cards arrive. A loading placeholder occupies the same height as
  // a card row; renderSimilar() later refines to the correct per-song state.
  const similarHeader = document.getElementById('similar-header');
  const similarList = document.getElementById('similar-list');
  if (similarHeader) similarHeader.style.display = 'flex';
  if (similarList) {
    similarList.innerHTML = `
      <div class="similar-placeholder similar-placeholder-loading">
        <div class="similar-placeholder-text">Loading similar songs<span class="similar-loading-dots"><span></span><span></span><span></span></span></div>
      </div>`;
  }

  // Recently Played cache feeds tile grid
  if (cache.recentlyPlayed && cache.recentlyPlayed.length > 0) {
    _cachedRecentlyPlayed = cache.recentlyPlayed;
  }
  if (_lastProfile) renderDiscoverTiles(_lastProfile);

  // Favorites
  if (cache.favorites && cache.favorites.length > 0) {
    renderFavorites(cache.favorites);
  }

  // Queue + History — render Up Next tab from cached state
  if (cache.queue || cache.history) {
    renderRecs({
      queue: cache.queue || [],
      sessionLabel: cache.sessionLabel || '',
      recToggle: cache.recToggle != null ? cache.recToggle : true,
    });
    renderHistory(cache.history || [], cache.historyPos || 0);
  }

  // Embedding status — use cached stats for accurate display
  updateEmbeddingStatus();
}

function updateEmbeddingStatus() {
  // Embedding banner removed (ISSUE: embedding moved into stats tile).
  // The AI stat tile shows `embedded/total` and is updated whenever
  // renderProfile runs. Re-render if profile is available.
  if (_lastProfile) {
    try { renderProfile(_lastProfile, { fade: false }); } catch (e) {}
  }
}

// Track which embedding-detail collapsible sections are expanded so we can
// restore them across re-renders (ISSUE-11).
const _embDetailExpanded = {
  unmatched: false,
  unmatchedConfirm: false,
  orphanConfirm: false,
  removed: false,
  pending: false,
  pendingNew: false,
  pendingRemoved: false,
  pendingReembed: false,
};

function _activityEntriesToCopyText(entries) {
  return entries.map(e => {
    const payload = e.data && Object.keys(e.data).length > 0 ? ` | ${JSON.stringify(e.data)}` : '';
    return `[${new Date(e.ts).toLocaleTimeString()}] ${e.category.toUpperCase()} ${e.level.toUpperCase()}: ${e.message}${payload}`;
  }).join('\n');
}


function _copyTextToClipboard(text, successLabel) {
  navigator.clipboard.writeText(text).then(
    () => showStatusToast(successLabel, 1500),
    () => showStatusToast('Copy failed', 1500)
  );
}



// ===== NATIVE AUDIO EVENTS =====

function setupNativeAudioEvents() {
  if (_nativeAudioEventsBound) return;
  _nativeAudioEventsBound = true;
  try {
    // Position updates from native MediaPlayer (every 250ms while playing)
    MusicBridge.addListener('audioTimeUpdate', (data) => {
      nativeAudioPos = data.position || 0;
      nativeAudioDur = data.duration || 0;
      nativeAudioPlaying = data.isPlaying || false;
      if (typeof data.playedMs === 'number') nativeAudioPlayedMs = data.playedMs;
      if (typeof data.durationMs === 'number' && data.durationMs > 0) nativeAudioDurationMs = data.durationMs;
      if (typeof data.playbackInstanceId === 'number') {
        nativePlaybackInstanceId = data.playbackInstanceId;
        engine.bindCurrentPlaybackStartInstance(nativePlaybackInstanceId, currentSong);
      }
      updateProgressUI(nativeAudioPos, nativeAudioDur);

      // Feed live listen fraction to engine (accumulator-backed, clamped to 1.0)
      const liveFrac = getListenFraction();
      if (liveFrac != null) engine.setLiveListenFraction(liveFrac);
      if (nativeAudioPlaying) persistPlaybackState(false);
    });

    // Play/pause state changes
    MusicBridge.addListener('audioPlayStateChanged', (data) => {
      _dbg('audioPlayStateChanged: isPlaying=' + data.isPlaying + ' currentSong=' + currentSong + ' embReady=' + engine.isEmbeddingsReady() + ' aiReady=' + _aiReadyCommitted);
      nativeAudioPlaying = data.isPlaying;
      document.body.classList.toggle('audio-paused', !data.isPlaying);
      logActivity({
        category: 'native',
        type: 'native_play_state_changed',
        message: data.isPlaying ? 'Native playback resumed' : 'Native playback paused',
        data: { isPlaying: !!data.isPlaying },
        tags: ['native', 'playback'],
        important: false,
      });
      updatePlayIcon(!data.isPlaying);
      persistPlaybackState(true);
    });

    // Native queue advanced (auto-completion, BT skip, notification skip)
    MusicBridge.addListener('queueCurrentChanged', async (data) => {
      _dbg('queueCurrentChanged: action=' + data.action + ' songId=' + data.songId + ' title=' + (data.title || '?') + ' prevFrac=' + data.prevFraction + ' prevCurrentSong=' + currentSong + ' embReady=' + engine.isEmbeddingsReady() + ' aiReady=' + _aiReadyCommitted);
      nativePlaybackInstanceId = Number(data.currentPlaybackInstanceId) || nativePlaybackInstanceId;
      logActivity({
        category: 'native',
        type: 'queue_current_changed',
        message: `Native queue changed via ${data.action || 'unknown'}`,
        data: {
          action: data.action,
          songId: data.songId,
          title: data.title || '',
          artist: data.artist || '',
          album: data.album || '',
          filePath: data.filePath || '',
          prevFraction: data.prevFraction,
        },
        tags: ['native', 'queue'],
        important: true,
      });
      let nativeQueueState = null;
      try {
        nativeQueueState = await MusicBridge.getQueueState();
      } catch (e) { /* ignore */ }
      const result = engine.onNativeAdvance(data, nativeQueueState);
      await _clearPendingListenIfResolvedByNative(data);
      if (result && result.songInfo) {
        const currentInfo = engine.getState().current || result.songInfo;
        currentSong = currentInfo.id;
        currentIsFav = currentInfo.isFavorite || false;
        document.getElementById('npTitle').textContent = currentInfo.title;
        document.getElementById('npArtist').textContent = currentInfo.artist + ' \u00b7 ' + (currentInfo.album || '');
        document.getElementById('nowPlaying').style.display = 'block';
        updateHeartIcon(currentIsFav);
        updateModeIndicator();
        syncFullPlayer();
        nativeAudioPlaying = true;
        nativeFileLoaded = true;
        updatePlayIcon(false);
        nativeAudioPos = 0;
        nativeAudioDur = 0;
        nativeAudioPlayedMs = 0;
        nativeAudioDurationMs = 0;
        if (result.needsSync) {
          const upcoming = engine.getUpcomingNativeItems();
          if (upcoming.length > 0) {
            try { await MusicBridge.replaceUpcoming({ items: upcoming }); } catch (e) { _dbg('replaceUpcoming err: ' + e.message); }
          }
        }

        refreshStateUI();
        engine.savePlaybackState(0);
      }
    });

    MusicBridge.addListener('queueEnded', () => {
      _dbg('queueEnded');
      const st = engine.getState();
      if (!st.playingAlbum && !st.playingFavorites && !st.playingPlaylist) {
        const info = engine.nextSong(null, 'queue_end_resume');
        if (info) {
          loadAndPlay(info).then(() => {
            refreshStateUI();
            _dbg('queueEnded: resumed into rebuilt AI queue');
          }).catch((e) => {
            _dbg('queueEnded resume failed: ' + e.message);
          });
          return;
        }
      }
      nativeAudioPlaying = false;
      logActivity({ category: 'native', type: 'native_queue_ended', message: 'Native queue ended', tags: ['native', 'queue'], important: true });
      updatePlayIcon(true);
      showStatusToast('Queue finished', 2000);
    });

    // Audio errors
    MusicBridge.addListener('audioError', async (data) => {
      _dbg('audioError: ' + (data && data.error) + ' currentSong=' + currentSong + ' nativeFileLoaded=' + nativeFileLoaded + ' nativeAudioPos=' + nativeAudioPos);
      console.error('Native audio error:', data.error);
      logActivity({ category: 'native', type: 'native_audio_error', message: `Native audio error: ${data.error}`, data: { error: data.error }, tags: ['native', 'error'], important: true, level: 'error' });

      // File-not-found from an externally deleted file: reconcile the library
      // so the song disappears from the UI. Embedding stays until the user
      // consents to remove it via the AI-page Orphaned bucket.
      const err = (data && data.error) || '';
      const path = data && data.path;
      const isFileMissing = path && (
        err.indexOf('File not found') === 0 ||
        err.indexOf('ENOENT') >= 0 ||
        err.indexOf('No such file') >= 0 ||
        err.indexOf('FileNotFoundException') >= 0
      );
      if (isFileMissing) {
        try {
          const res = await engine.markSongMissingByPath(path);
          if (res && res.removed > 0) {
            allSongs = engine.getPlayableSongs();
            if (activeTab === 'songs') renderSongs(allSongs);
            refreshStateUI();
            showStatusToast('Song file no longer on device — removed from library', 2500);
          }
        } catch (e) {
          console.warn('markSongMissingByPath failed:', e && e.message || e);
        }
      } else {
        showStatusToast('Playback error: ' + String(err || 'unknown error').slice(0, 80), 3000);
      }
    });
  } catch (e) {
    console.log('Native audio events not available:', e);
  }
}


// ===== NATIVE MEDIA LISTENER (notification/lock screen controls) =====

function setupNativeMediaListener() {
  try {
    MusicBridge.addListener('mediaAction', async (data) => {
      const action = data && data.action ? String(data.action) : '';
      if (action === 'favorite') {
        const fallbackSongId = currentSong;
        const resolvedSongId = Number.isInteger(Number(data.songId)) && Number(data.songId) >= 0
          ? Number(data.songId)
          : fallbackSongId;
        if (resolvedSongId == null) return;
        const result = engine.setFavoriteState(resolvedSongId, !!data.isFavorite, { source: 'native_notification' });
        if (!result || !result.ok) return;
        if (resolvedSongId === currentSong) {
          currentIsFav = !!result.isFavorite;
          updateHeartIcon(currentIsFav);
        }
        loadFavorites();
        updateFavCount(result.count);
        const title = data.title || (engine.getSongs()[resolvedSongId] ? engine.getSongs()[resolvedSongId].title : '');
        logActivity({
          category: 'ui',
          type: 'favorite_notification_pressed',
          message: `${result.isFavorite ? 'Favorited' : 'Unfavorited'} "${title}" from notification`,
          data: { songId: resolvedSongId, isFavorite: result.isFavorite, source: 'native_notification' },
          tags: ['favorite', 'notification'],
          important: true,
        });
        showStatusToast(result.isFavorite ? `"${title}" added to favorites` : `"${title}" removed from favorites`, 1800);
        return;
      }

      if (action === 'dismiss') {
        try { await _persistPendingListenEvidence('notification_close'); } catch (e) { /* ignore */ }
        await engine.clearPlaybackSession();
        nativeAudioPlaying = false;
        nativeFileLoaded = false;
        nativeAudioPos = 0;
        nativeAudioDur = 0;
        nativeAudioPlayedMs = 0;
        nativeAudioDurationMs = 0;
        nativePlaybackInstanceId = 0;
        currentSong = null;
        currentIsFav = false;
        _miniPlayerFromQuickDisplay = false;
        closeFullPlayer();
        updateHeartIcon(false);
        updatePlayIcon(true);
        updateProgressUI(0, 0);
        refreshStateUI();
        logActivity({
          category: 'ui',
          type: 'notification_player_closed',
          message: 'Closed player from notification',
          tags: ['notification', 'playback'],
          important: true,
        });
        showStatusToast('Player closed', 1600);
      }
    });
  } catch (e) {
    console.log('Native media actions not available:', e);
  }
}

// ===== POSITION PERSISTENCE =====

function setupPositionPersistence() {
  // Save position and a non-authoritative pending listen snapshot when the app
  // goes to background. Native transitions remain the final authority; the
  // snapshot is only for cold-start recovery if no later native result exists.
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && currentSong != null) {
      persistPlaybackState(true);
      _persistPendingListenEvidence('visibility_hidden');
    }
  });

  // pagehide fires when the page is being unloaded (navigation, app teardown).
  // On Capacitor Android this is a more reliable "app is going down" signal
  // than visibilitychange alone.
  window.addEventListener('pagehide', () => {
    if (currentSong != null) {
      persistPlaybackState(true);
      _persistPendingListenEvidence('pagehide');
    }
  });

  // Save position periodically while playing so recent-task kills do not fall
  // back to a stale 10s-old checkpoint on next launch.
  setInterval(() => {
    if (nativeAudioPlaying && currentSong != null) {
      persistPlaybackState(false);
    }
  }, 2000);
}

function setupEmbeddingUI() {
  try {
    let embUiRefreshTimer = null;
    let embUiRefreshDiscover = false;

    const refreshIfOnDetailPage = () => {
      // If the detail page is currently showing, refresh it live
      const panel = document.getElementById('panel-discover');
      if (panel && panel.querySelector('.emb-detail-page')) {
        showEmbeddingDetail();
      }
    };

    const scheduleEmbeddingUiRefresh = ({ delay = 120, refreshDiscover = false } = {}) => {
      embUiRefreshDiscover = embUiRefreshDiscover || refreshDiscover;
      if (embUiRefreshTimer) return;
      embUiRefreshTimer = setTimeout(() => {
        embUiRefreshTimer = null;
        const shouldRefreshDiscover = embUiRefreshDiscover;
        embUiRefreshDiscover = false;
        updateEmbeddingStatus();
        refreshIfOnDetailPage();
        if (shouldRefreshDiscover && activeTab === 'discover' && !_isOnSubPage()) {
          refreshDiscoverPrimaryState();
        }
      }, delay);
    };

    MusicBridge.addListener('embeddingProgress', () => {
      scheduleEmbeddingUiRefresh();
    });
    MusicBridge.addListener('embeddingSongComplete', () => {
      scheduleEmbeddingUiRefresh();
    });
    MusicBridge.addListener('embeddingComplete', (data) => {
      _dbg('native embeddingComplete: processed=' + (data && data.processed) + ' failed=' + (data && data.failed) + ' currentSong=' + currentSong + ' nativeAudioPlaying=' + nativeAudioPlaying);
      // Delay slightly to let engine handler finish async work
      scheduleEmbeddingUiRefresh({ delay: 300, refreshDiscover: true });
    });
    MusicBridge.addListener('embeddingError', () => {
      scheduleEmbeddingUiRefresh();
    });

    // Auto-refresh detail page every 2s during active embedding to keep the
    // elapsed-time display, current-file label, and queue counts moving even
    // if a native event slips through. Outside of an active batch the
    // event-driven listeners above (embeddingProgress / embeddingSongComplete /
    // embeddingComplete / embeddingError / _songLibraryChangedCbs) cover every
    // state transition that needs a re-render — so we skip the heavy DOM
    // rebuild when the engine reports inProgress=false.
    setInterval(() => {
      const panel = document.getElementById('panel-discover');
      if (!panel || !panel.querySelector('.emb-detail-page')) return;
      try {
        if (engine.getEmbeddingStatus().inProgress) {
          showEmbeddingDetail();
        }
      } catch (e) { /* engine not ready yet */ }
    }, 2000);
  } catch (e) {
    console.log('Embedding UI listeners not available:', e);
  }
}

function getListenFraction() {
  // Prefer the native cumulative-played accumulator (ignores seeks; honest for
  // seek-heavy / sample-heavy listening). Falls back to position/duration only
  // when the accumulator hasn't reported yet.
  if (nativeAudioDurationMs > 0 && nativeAudioPlayedMs >= 0) {
    const raw = nativeAudioPlayedMs / nativeAudioDurationMs;
    return Math.min(1.0, Math.round(raw * 100) / 100);
  }
  if (!nativeAudioDur || nativeAudioDur <= 0) return null;
  return Math.min(1.0, Math.round((nativeAudioPos / nativeAudioDur) * 100) / 100);
}

// ===== MODE INDICATOR =====

function updateModeIndicator() {
  const st = engine.getState();
  const el = document.getElementById('modeIndicator');
  if (!el) return;

  const mode = st.modeIndicator;
  el.className = 'mode-indicator';
  if (mode === 'AI') {
    el.textContent = 'AI';
    el.classList.add('mode-ai');
  } else if (mode === 'List') {
    el.textContent = 'List';
    el.classList.add('mode-album');
  } else if (mode === 'Album') {
    el.textContent = 'Album';
    el.classList.add('mode-album');
  } else if (mode === 'Favorites') {
    el.textContent = 'Favs';
    el.classList.add('mode-album');
  } else if (mode === 'Playlist') {
    el.textContent = 'Playlist';
    el.classList.add('mode-album');
  } else {
    el.textContent = 'Shuffle';
    el.classList.add('mode-shuffle');
  }

  // Red dot for unembedded current song
  const redDot = document.getElementById('npRedDot');
  if (redDot) {
    redDot.style.display = (st.current && st.current.hasEmbedding === false) ? 'inline' : 'none';
  }
}


// ===== PULL TO REFRESH =====

function setupPullToRefresh() {
  const content = document.querySelector('.content');
  if (!content) return;

  // Note: #pullIndicator / #discover-pull-body can be replaced in the DOM when
  // sub-pages (viewAll / taste overlay) close and restore panel.innerHTML.
  // Resolve lazily on every gesture so state still applies after return.
  const getIndicator = () => document.getElementById('pullIndicator');
  const getPullBody = () => document.getElementById('discover-pull-body');
  // After the scroll-snap rewrite (follow-up #10), `.content` no longer scrolls;
  // each `.panel` is its own vertical scroll container. Read scroll position
  // from the Discover panel so the "am I at the top?" gate is meaningful.
  const getDiscoverScroller = () => document.getElementById('panel-discover');

  let startY = 0;
  let pulling = false;
  let currentPull = 0;
  let refreshing = false;
  const PULL_TRIGGER = 128;
  const MAX_PULL = 180;
  const MIN_SPIN_MS = 650;

  // Smooth HSL hue interpolation: 0deg (red) at no pull, 120deg (green) at trigger.
  function colorForPull(dist) {
    const t = Math.max(0, Math.min(1, dist / PULL_TRIGGER));
    const hue = Math.round(t * 120); // red -> yellow -> green
    const alphaTop = 0.30 + t * 0.22;    // 0.30 -> 0.52
    const alphaBot = 0.14 + t * 0.14;    // 0.14 -> 0.28
    const shadow = 0.55 + t * 0.25;      // 0.55 -> 0.80
    return {
      bg: `linear-gradient(180deg, hsla(${hue}, 72%, 48%, ${alphaTop.toFixed(2)}) 0%, hsla(${hue}, 72%, 48%, ${alphaBot.toFixed(2)}) 100%)`,
      shadow: `inset 0 -2px 0 hsla(${hue}, 72%, 48%, ${shadow.toFixed(2)})`,
    };
  }

  function applyPullState(dist) {
    const indicator = getIndicator();
    const pullBody = getPullBody();
    if (!indicator || !pullBody) return;
    currentPull = dist;
    const ready = dist >= PULL_TRIGGER;
    // Subtle sizes: indicator max ~58px, body offset max ~48px.
    const indicatorHeight = Math.min(58, Math.round(dist * 0.42));
    const bodyOffset = Math.min(48, Math.round(dist * 0.36));
    indicator.classList.add('pull-tracking');
    pullBody.classList.add('pull-tracking');
    indicator.style.height = indicatorHeight + 'px';
    indicator.classList.add('pulling');
    indicator.classList.remove('pulling-low', 'pulling-ready', 'refreshing');
    const { bg, shadow } = colorForPull(dist);
    indicator.style.background = bg;
    indicator.style.boxShadow = shadow;
    indicator.style.color = '#ffffff';
    indicator.innerHTML = ready
      ? '<span class="pull-arrow pull-arrow-ready">\u2193</span> Release to refresh'
      : '<span class="pull-arrow">\u2193</span> Pull to refresh';
    pullBody.style.transform = `translateY(${bodyOffset}px)`;
  }

  function clearPullState() {
    currentPull = 0;
    const indicator = getIndicator();
    const pullBody = getPullBody();
    if (indicator) {
      indicator.classList.remove('pull-tracking');
      indicator.style.height = '0';
      indicator.innerHTML = '';
      indicator.style.background = '';
      indicator.style.boxShadow = '';
      indicator.style.color = '';
      indicator.classList.remove('pulling', 'pulling-low', 'pulling-ready', 'refreshing');
    }
    if (pullBody) {
      pullBody.classList.remove('pull-tracking');
      pullBody.style.transform = '';
    }
  }

  content.addEventListener('touchstart', (e) => {
    if (activeTab !== 'discover' || refreshing) return;
    const scroller = getDiscoverScroller();
    const scrollTop = scroller ? scroller.scrollTop : 0;
    if (scrollTop <= 0 && !_isOnSubPage()) {
      startY = e.touches[0].clientY;
      pulling = true;
      currentPull = 0;
    }
  }, { passive: true });

  content.addEventListener('touchmove', (e) => {
    if (!pulling || activeTab !== 'discover' || refreshing) return;
    const dy = e.touches[0].clientY - startY;
    const scroller = getDiscoverScroller();
    const scrollTop = scroller ? scroller.scrollTop : 0;
    if (dy > 12 && scrollTop <= 0) {
      e.preventDefault();
      const eased = Math.min(MAX_PULL, (dy - 12) * 0.80);
      applyPullState(eased);
    } else if (currentPull > 0) {
      clearPullState();
    }
  }, { passive: false });

  async function finishPull() {
    if (!pulling || refreshing) return;
    pulling = false;
    if (currentPull >= PULL_TRIGGER && activeTab === 'discover') {
      refreshing = true;
      const indicator = getIndicator();
      const pullBody = getPullBody();
      if (indicator) {
        indicator.classList.remove('pull-tracking');
        indicator.innerHTML = '<span class="pull-spinner"></span> Refreshing...';
        indicator.classList.remove('pulling-low', 'pulling-ready');
        indicator.classList.add('refreshing');
        indicator.style.height = '58px';
        // Pin green during refresh via inline style so class restoration after
        // DOM-rebuild (sub-page close) cannot strip it mid-refresh.
        const green = colorForPull(PULL_TRIGGER);
        indicator.style.background = green.bg;
        indicator.style.boxShadow = green.shadow;
        indicator.style.color = '#ffffff';
      }
      if (pullBody) {
        pullBody.classList.remove('pull-tracking');
        pullBody.style.transform = 'translateY(48px)';
      }
      const started = Date.now();
      try {
        try { await engine.rebuildProfileVec(); } catch (e) { /* ignore */ }
        await loadDiscover({ refreshForYou: true, refreshDiscover: true });
        const elapsed = Date.now() - started;
        if (elapsed < MIN_SPIN_MS) {
          await new Promise(r => setTimeout(r, MIN_SPIN_MS - elapsed));
        }
        _saveVisibleDiscoverCache();
      } finally {
        refreshing = false;
      }
    }
    clearPullState();
  }

  content.addEventListener('touchend', () => { finishPull().catch(() => { refreshing = false; clearPullState(); }); });
  content.addEventListener('touchcancel', () => {
    pulling = false;
    if (!refreshing) clearPullState();
  });
}
setupPullToRefresh();

// ===== TABS =====

let _songsDirty = true;
let _albumsDirty = true;
let _discoverDirty = false;
let _discoverDirtyOpts = { refreshForYou: false, refreshDiscover: false };

function _clearQueuedDiscoverRefresh() {
  _discoverDirty = false;
  _discoverDirtyOpts = { refreshForYou: false, refreshDiscover: false };
}

function _mergeDiscoverRefreshOpts(opts = {}) {
  return {
    refreshForYou: _discoverDirtyOpts.refreshForYou || opts.refreshForYou === true,
    refreshDiscover: _discoverDirtyOpts.refreshDiscover || opts.refreshDiscover === true,
  };
}

function _flushQueuedDiscoverRefresh() {
  if (!_discoverDirty) return false;
  const opts = { ..._discoverDirtyOpts };
  _clearQueuedDiscoverRefresh();
  loadDiscover(opts);
  return true;
}

function _hasDiscoverBackup() {
  return !!(discoverContentBackup && discoverContentBackupPanelId === 'panel-discover');
}

function queueDiscoverRefresh(reason, opts = {}) {
  _discoverDirty = true;
  _discoverDirtyOpts = _mergeDiscoverRefreshOpts(opts);
  _dbg(`discover refresh queued: ${reason} (refreshForYou=${_discoverDirtyOpts.refreshForYou}, refreshDiscover=${_discoverDirtyOpts.refreshDiscover})`);
  if (activeTab === 'discover' && !_isOnSubPage() && !_hasDiscoverBackup()) {
    _flushQueuedDiscoverRefresh();
  }
}

function _activateTab(target, opts = {}) {
  const pushHistory = opts.pushHistory === true;
  const resetScroll = opts.resetScroll !== false;
  const recsListWrap = document.getElementById('recs-list-wrap');
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  const tabEl = document.querySelector(`.tab[data-tab="${target}"]`);
  const panelEl = document.getElementById('panel-' + target);
  if (!tabEl || !panelEl) return;
  tabEl.classList.add('active');
  panelEl.classList.add('active');
  activeTab = target;

  // Scroll the panel-strip horizontally to bring the target panel into view —
  // BUT only when this _activateTab call originated from a tab-bar click.
  // When it originated from the IntersectionObserver (after a manual swipe),
  // `opts.instant === true` is set; the browser is mid-snap to that panel
  // already and issuing our own `scrollTo` on top would race the snap and
  // produce the "flick" the user sees.
  if (target !== 'history' && opts.instant !== true) {
    _scrollToPanel(target, 'smooth');
  }

  if (resetScroll && panelEl && target !== 'recs') {
    panelEl.scrollTop = 0;
  }
  if (recsListWrap && resetScroll && target === 'recs') recsListWrap.scrollTop = 0;
  // 2026-05-10 follow-up #12: the standalone .search-bar element no longer
  // exists; the search input now lives in the top-bar permanently and is
  // universal across all tabs. Switching tabs clears any active search so
  // the user sees the destination tab's full content rather than stale
  // search results.
  if (typeof _clearSearchInput === 'function') _clearSearchInput();

  if (target === 'songs' && _songsDirty) {
    _songsDirty = false;
    renderSongs(_filteredSongs());
  }
  if (target === 'albums' && _albumsDirty) {
    _albumsDirty = false;
    renderAlbums(_filteredAlbums());
  }

  if (target === 'discover' && _discoverDirty && !_hasDiscoverBackup()) {
    _flushQueuedDiscoverRefresh();
  } else if (target === 'discover' && !_hasDiscoverBackup()) {
    refreshDiscoverPrimaryState();
  }

  if (target === 'recs') {
    _scheduleRecsFocusCurrent();
  }

  if (pushHistory) {
    history.pushState({ appTab: target }, '');
  }
}

if (!history.state || !history.state.appTab) {
  history.replaceState({ appTab: 'discover' }, '');
}

window.addEventListener('popstate', () => {
  if (activeTab !== 'discover') {
    _activateTab('discover', { resetScroll: false });
  }
});

document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    const target = tab.dataset.tab;
    const shouldPush = target !== 'discover' && activeTab !== target;
    _activateTab(target, { pushHistory: shouldPush });
  });
});

// ===== SWIPE BETWEEN TABS =====
// Touch-target based: a swipe that starts on a horizontal scroller (Discover
// section rows, the tab bar itself, anything with overflow-x: auto) lets that
// element scroll its own content. Anywhere else, a clearly horizontal swipe
// past the threshold switches tabs left/right.
//
// Detection rules:
//   - Skip if start target is inside .hscroll, .hscroll-wrap, .tabs, an input,
//     a slider, or any element marked data-no-tab-swipe.
//   - Commit only when |deltaX| > 60px AND |deltaX| > 2 * |deltaY| AND the
//     gesture finished within 600ms (avoids slow accidental drags).
//   - Edges don't wrap: at the first tab a right-swipe is a no-op; at the
//     last tab a left-swipe is a no-op.
// 2026-05-10 follow-up #10: scroll-snap takes over from the JS swipe handler.
// The browser handles the swipe gesture natively at 60fps via
// `scroll-snap-type: x mandatory` on `#panelStrip`. We only need to:
//   1. Programmatically scroll the strip when a bottom-tab is tapped
//      (`_scrollToPanel` below + the call inside `_activateTab`).
//   2. Detect when the user manually swipes to a new tab so we can update
//      `activeTab`, the bottom-tab `.active` class, and run the per-tab
//      arrival logic that used to live inside `_activateTab` (search-bar
//      visibility, dirty-render flushes, history.pushState).
// IntersectionObserver does the detection: it fires when a panel becomes
// >=75% visible inside the strip, which corresponds to the user finishing a
// swipe and the snap completing.
const _SCROLL_TAB_ORDER = ['discover', 'songs', 'albums', 'recs', 'browse'];
function _scrollToPanel(target, behavior) {
  const strip = document.getElementById('panelStrip');
  const panelEl = document.getElementById('panel-' + target);
  if (!strip || !panelEl) return;
  // No-op if the strip is already at the target position (avoids ripple
  // when called from the observer right after a manual swipe).
  if (Math.abs(strip.scrollLeft - panelEl.offsetLeft) < 1) return;
  // Suppress the IntersectionObserver while the smooth-scroll animation is
  // in flight — otherwise the intermediate panels passing through the
  // viewport would each trigger _activateTab → snowball.
  if (typeof window._suppressPanelObserverFor === 'function') {
    window._suppressPanelObserverFor(behavior === 'instant' ? 50 : 500);
  }
  strip.scrollTo({ left: panelEl.offsetLeft, behavior: behavior || 'smooth' });
}
(function setupPanelStripObserver() {
  const strip = document.getElementById('panelStrip');
  if (!strip) return;
  if (typeof IntersectionObserver !== 'function') return;

  // Debounce flag: tab clicks call `_scrollToPanel` which animates the strip.
  // During that animation the observer would fire mid-flight; we don't want
  // intermediate panels triggering `activeTab` updates.
  let suppressUntil = 0;
  function _suppressObserverFor(ms) { suppressUntil = Date.now() + ms; }
  // Make this hookable so `_activateTab` can suppress before each programmatic scroll.
  window._suppressPanelObserverFor = _suppressObserverFor;

  const observer = new IntersectionObserver((entries) => {
    if (Date.now() < suppressUntil) return;
    let bestEntry = null;
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      if (!bestEntry || entry.intersectionRatio > bestEntry.intersectionRatio) {
        bestEntry = entry;
      }
    }
    if (!bestEntry || bestEntry.intersectionRatio < 0.6) return;
    const tab = bestEntry.target.dataset && bestEntry.target.dataset.tab;
    if (!tab || tab === activeTab) return;
    // The user manually swiped to a new tab. Run the same per-arrival logic
    // _activateTab does (search-bar toggle, dirty renders, etc.) but DON'T
    // call _scrollToPanel — they're already there.
    const shouldPush = tab !== 'discover' && activeTab !== tab;
    _activateTab(tab, { pushHistory: shouldPush, resetScroll: false, instant: true });
  }, { root: strip, threshold: [0.6, 0.75, 0.9] });

  strip.querySelectorAll('.panel').forEach(p => observer.observe(p));
})();

// ===== UNIVERSAL SEARCH =====
// 2026-05-10 follow-up #12: search now lives in the top bar and is universal
// across the whole library — not per-tab. Typing shows a results overlay
// (#searchOverlay) layered over the panel-strip with two sections: matching
// songs and matching albums. Empty input or X button hides the overlay,
// revealing whatever tab the user was on. Tab change clears the search too.
const _SEARCH_RESULT_LIMIT_SONGS = 80;
const _SEARCH_RESULT_LIMIT_ALBUMS = 40;

function _filterSearch(q) {
  const songs = allSongs.filter(s =>
    s.title.toLowerCase().includes(q) ||
    (s.artist || '').toLowerCase().includes(q) ||
    (s.album || '').toLowerCase().includes(q)
  );
  const albums = allAlbums.filter(a =>
    a.name.toLowerCase().includes(q) ||
    (a.artist || '').toLowerCase().includes(q)
  );
  return { songs, albums };
}

function _showSearchOverlay(q) {
  const overlay = document.getElementById('searchOverlay');
  const songsTarget = document.getElementById('search-results-songs');
  const albumsTarget = document.getElementById('search-results-albums');
  const songsCount = document.getElementById('searchCountSongs');
  const albumsCount = document.getElementById('searchCountAlbums');
  const empty = document.getElementById('searchEmpty');
  if (!overlay || !songsTarget || !albumsTarget) return;

  const { songs, albums } = _filterSearch(q);
  const songsTrunc = songs.slice(0, _SEARCH_RESULT_LIMIT_SONGS);
  const albumsTrunc = albums.slice(0, _SEARCH_RESULT_LIMIT_ALBUMS);

  // Reuse the same row/card templates the Songs/Albums tabs use.
  renderSongs(songsTrunc, { target: songsTarget, sort: true });
  renderAlbums(albumsTrunc, { target: albumsTarget });

  if (songsCount) songsCount.textContent = songs.length > songsTrunc.length
    ? `${songsTrunc.length} of ${songs.length}` : `${songs.length}`;
  if (albumsCount) albumsCount.textContent = albums.length > albumsTrunc.length
    ? `${albumsTrunc.length} of ${albums.length}` : `${albums.length}`;

  if (empty) empty.style.display = (songs.length === 0 && albums.length === 0) ? 'block' : 'none';

  // Hide whichever section has zero results so we don't show "Songs" with an empty list.
  songsTarget.parentElement.style.display = songs.length > 0 ? '' : 'none';
  albumsTarget.parentElement.style.display = albums.length > 0 ? '' : 'none';

  overlay.style.display = 'block';
}

function _hideSearchOverlay() {
  const overlay = document.getElementById('searchOverlay');
  if (overlay) overlay.style.display = 'none';
}

function _clearSearchInput() {
  const input = document.getElementById('searchInput');
  if (input) input.value = '';
  const clearBtn = document.getElementById('searchClear');
  if (clearBtn) clearBtn.style.display = 'none';
  _hideSearchOverlay();
}

document.getElementById('searchInput').addEventListener('input', (e) => {
  const raw = e.target.value || '';
  const q = raw.toLowerCase().trim();
  const clearBtn = document.getElementById('searchClear');
  if (clearBtn) clearBtn.style.display = raw.length > 0 ? 'flex' : 'none';
  if (q.length === 0) {
    _hideSearchOverlay();
    return;
  }
  _showSearchOverlay(q);
});

// `clearSearch` is the API the X-button onclick + the existing app-browse-render
// `clearSearch()` helper both call. Override the imported one's effect by
// also closing the overlay (the overlay didn't exist when that helper was written).
window.addEventListener('DOMContentLoaded', () => {
  const clearBtn = document.getElementById('searchClear');
  if (clearBtn) {
    clearBtn.addEventListener('click', (e) => {
      e.preventDefault();
      _clearSearchInput();
      const input = document.getElementById('searchInput');
      if (input) input.blur();
    });
  }
}, { once: true });



// ===== LOAD AND PLAY =====

async function loadAndPlay(songInfo, seekToSec) {
  if (!songInfo) return;
  if (!_initRestoreComplete) _startupPlaybackTouched = true;
  const requestedSeek = Number.isFinite(seekToSec) && seekToSec > 0 ? seekToSec : 0;
  currentSong = songInfo.id;
  currentIsFav = songInfo.isFavorite || false;
  document.getElementById('npTitle').textContent = songInfo.title;
  document.getElementById('npArtist').textContent = songInfo.artist + ' \u00b7 ' + (songInfo.album || '');
  document.getElementById('nowPlaying').style.display = 'block';
  updateHeartIcon(currentIsFav);
  updateModeIndicator();
  syncFullPlayer();
  nativeAudioPos = requestedSeek;
  nativeAudioPlayedMs = 0;
  nativeAudioDurationMs = 0;
  nativePlaybackInstanceId = 0;
  if (!(requestedSeek > 0 && nativeAudioDur > 0)) {
    nativeAudioDur = 0;
  }
  updateProgressUI(nativeAudioPos, nativeAudioDur);

  // Build full native queue from engine state and push to native service
  if (songInfo.filePath) {
    const queueSnapshot = engine.getNativeQueueSnapshot();
    const items = queueSnapshot.items || [];
    const startIndex = Number.isInteger(queueSnapshot.startIndex) ? queueSnapshot.startIndex : 0;
    const loopModeMap = { off: 0, one: 1, all: 2 };
    _dbg('loadAndPlay: songId=' + songInfo.id + ' title="' + songInfo.title + '" items=' + items.length + ' startIndex=' + startIndex + ' seekTo=' + requestedSeek + ' embReady=' + engine.isEmbeddingsReady());
    try {
      if (items.length > 0) {
        await MusicBridge.setQueue({ items, startIndex, seekTo: requestedSeek });
        _dbg('loadAndPlay: setQueue OK');
      } else {
        await MusicBridge.playAudio({
          path: songInfo.filePath,
          title: songInfo.title,
          artist: songInfo.artist,
          seekTo: requestedSeek,
        });
        _dbg('loadAndPlay: playAudio OK (no queue)');
      }
      // Fix I: setLoopMode and _refreshNativePlaybackInstanceId don't gate
      // audio playback on native side — setQueue/playAudio already triggered
      // ExoPlayer prepare. Awaiting them sequentially adds ~50-100ms of bridge
      // round-trip latency between the user's tap and the audio actually
      // starting. Run them in parallel without blocking; instance id will be
      // reconciled by the next audioPlayStateChanged event anyway.
      Promise.all([
        MusicBridge.setLoopMode({ mode: loopModeMap[loopMode] || 2 }),
        _refreshNativePlaybackInstanceId(),
      ]).catch(e => _dbg('loadAndPlay aux err: ' + (e && e.message || e)));
      nativeAudioPlaying = true;
      nativeFileLoaded = true;
      updatePlayIcon(false);
    } catch (e) {
      console.error('Native play failed, retrying once:', e);
      try {
        await new Promise(r => setTimeout(r, 300));
        if (items.length > 0) {
          await MusicBridge.setQueue({ items, startIndex, seekTo: requestedSeek });
        } else {
          await MusicBridge.playAudio({
            path: songInfo.filePath,
            title: songInfo.title,
            artist: songInfo.artist,
            seekTo: requestedSeek,
          });
        }
        // Fix I: same parallel pattern as the success path above.
        Promise.all([
          MusicBridge.setLoopMode({ mode: loopModeMap[loopMode] || 2 }),
          _refreshNativePlaybackInstanceId(),
        ]).catch(e => _dbg('loadAndPlay retry aux err: ' + (e && e.message || e)));
        nativeAudioPlaying = true;
        nativeFileLoaded = true;
        updatePlayIcon(false);
      } catch (e2) {
        console.error('Native play retry also failed:', e2);
        showStatusToast('Playback failed. Please try again.', 2200);
      }
    }
  } else {
    console.error('No file path for', songInfo.filename);
  }

  nativeAudioPos = requestedSeek;
  persistPlaybackState(true);
  prefetchNext();
}

function prefetchNext() {
  const st = engine.getState();
  if (st.queue && st.queue.length > 0) {
    const next = st.queue[0];
    const allSongsData = engine.getSongs();
    const song = allSongsData[next.id];
    if (song && song.filePath) {
      nextSongInfo = {
        id: next.id,
        title: song.title,
        artist: song.artist,
        album: song.album,
        filename: song.filename,
        filePath: song.filePath,
        isFavorite: engine.isFavorite(next.id),
      };
      return;
    }
  }
  nextSongInfo = null;
}

async function syncUpcomingNativeQueue() {
  if (!nativeFileLoaded) return;
  const upcoming = engine.getUpcomingNativeItems();
  try {
    await MusicBridge.replaceUpcoming({ items: upcoming });
  } catch (e) {
    _dbg('replaceUpcoming err: ' + e.message);
  }
}


async function neutralSkipUI() {
  const frac = getListenFraction();
  logActivity({ category: 'ui', type: 'neutral_skip_pressed', message: 'Neutral skip pressed', data: { prevFraction: frac }, tags: ['playback'], important: true });
  if (nativeFileLoaded) {
    try {
      await MusicBridge.nextTrack({ action: 'neutral_skip', prevFraction: frac == null ? -1 : frac });
      return;
    } catch (e) {
      _dbg('NEUTRAL native fallback: ' + e.message);
    }
  }
  const info = engine.neutralSkip(frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
  }
}

async function refreshRecsUI() {
  logActivity({ category: 'ui', type: 'refresh_pressed', message: 'Refresh recommendations pressed', tags: ['queue', 'recommendation'], important: true });
  engine.refreshQueue();
  const upcoming = engine.getUpcomingNativeItems();
  if (upcoming.length > 0) {
    try { await MusicBridge.replaceUpcoming({ items: upcoming }); } catch (e) { _dbg('replaceUpcoming err: ' + e.message); }
  }
  refreshStateUI();
  _scheduleRecsFocusCurrent();
  showStatusToast('Recommendations refreshed', 1500);
}

function showInsightsUI() {
  const panel = document.getElementById('insights-panel');
  if (!panel) return;

  // Toggle visibility
  if (panel.style.display !== 'none') {
    panel.style.display = 'none';
    return;
  }

  const ins = engine.getInsights();

  let html = '<div class="insights-content">';

  // === NOW PLAYING — current song + live fraction + blend ===
  if (ins.currentSong) {
    html += '<div class="ins-section">';
    html += `<div class="ins-title">Now Playing</div>`;
    html += `<div class="ins-now-playing">`;
    html += `<div class="ins-np-name">${esc(ins.currentSong.title)} <span class="ins-dim">${esc(ins.currentSong.artist)}</span></div>`;
    if (ins.currentSong.liveFraction != null) {
      const livePct = Math.round(ins.currentSong.liveFraction * 100);
      const status = livePct > 50 ? 'ins-good' : 'ins-skip';
      html += `<div class="ins-np-frac"><span class="${status}">${livePct}% listened</span>`;
      html += livePct > 50 ? ' — counts as qualified' : ' — will count as skip';
      html += `</div>`;
    }
    html += `</div>`;
    // Blend weights inline with explanation
    html += `<div class="ins-list-title">What's deciding your next songs?</div>`;
    html += '<div class="ins-bars">';
    html += `<div class="ins-bar-row"><span class="ins-bar-label">This song</span><div class="ins-bar"><div class="ins-bar-fill ins-bar-current" style="width:${ins.blend.currentSong}"></div></div><span class="ins-bar-val">${ins.blend.currentSong}</span></div>`;
    html += `<div class="ins-bar-row"><span class="ins-bar-label">Session</span><div class="ins-bar"><div class="ins-bar-fill ins-bar-session" style="width:${ins.blend.session}"></div></div><span class="ins-bar-val">${ins.blend.session}</span></div>`;
    html += `<div class="ins-bar-row"><span class="ins-bar-label">All-time</span><div class="ins-bar"><div class="ins-bar-fill ins-bar-profile" style="width:${ins.blend.profile}"></div></div><span class="ins-bar-val">${ins.blend.profile}</span></div>`;
    html += '</div>';
    html += `<div class="ins-hint">This song = similarity to what's playing. Session = your recent listening pattern. All-time = your long-term taste from favorites + history. As you listen more, session weight grows.</div>`;
    html += '</div>';
  }

  // === TASTE SIGNAL — what the engine knows about this listening run ===
  html += '<div class="ins-section"><div class="ins-title">Taste Signal</div>';
  html += `<div class="ins-stat-grid">`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.session.totalListened}</div><div class="ins-stat-key">Tracked</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val ins-good">${ins.session.qualifiedForRec}</div><div class="ins-stat-key">Signal (+)</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val ins-skip">${ins.session.skippedUsedAsNegative}</div><div class="ins-stat-key">Signal (-)</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.profile.favoritesCount}</div><div class="ins-stat-key">Favs</div></div>`;
  html += `</div>`;
  html += `<div class="ins-hint">Persists across app restarts. Resets only on Engine Reset.</div>`;

  // Listen log with live status
  if (ins.session.listenedSongs.length > 0) {
    const maxShow = 50;
    const shown = ins.session.listenedSongs.slice(0, maxShow);
    const hidden = ins.session.listenedSongs.length - shown.length;
    html += `<div class="ins-list-title">Songs the engine has seen${hidden > 0 ? ` (showing last ${maxShow} of ${ins.session.listenedSongs.length})` : ''}</div>`;
    for (const s of shown) {
      const statusClass = s.isLive ? 'ins-live' : (s.status === 'qualified' ? 'ins-good' : 'ins-skip');
      const label = s.isLive ? 'NOW' : s.fraction;
      const enc = s.encounters > 1 ? ` <span class="ins-enc">${s.plays} liked / ${s.skips} skipped (${s.encounters}x)</span>` : '';
      html += `<div class="ins-listen-item"><span class="${statusClass}">${esc(label)}</span> <span class="ins-weight">${esc(s.weight)}</span> ${esc(s.title)}${enc}</div>`;
    }
  }

  // Session decay weights — with clear explanation
  if (ins.sessionDecay) {
    html += `<div class="ins-list-title">How much each song steers your recs</div>`;
    html += `<div class="ins-hint" style="margin-top:0;margin-bottom:6px;">Songs you listened to longer have more influence. Recent songs count more than older ones. The bar shows each song's pull on what gets recommended next.</div>`;
    for (const s of ins.sessionDecay.songs) {
      const combined = parseInt(s.combined);
      html += `<div class="ins-decay-item">`;
      html += `<div class="ins-decay-bar" style="width:${Math.min(combined, 100)}%"></div>`;
      html += `<span class="ins-decay-text">${esc(s.title)}</span>`;
      html += `<span class="ins-decay-vals">${esc(s.combined)}</span>`;
      html += `</div>`;
    }
    html += `<div class="ins-hint">= recency (${ins.sessionDecay.decayFactor} decay per song) × listen depth²</div>`;
  }
  html += '</div>';

  // === UP NEXT INTELLIGENCE — replaces old "Queue" section ===
  html += '<div class="ins-section"><div class="ins-title">Up Next Intelligence</div>';
  html += `<div class="ins-stat-grid">`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.queue.size}</div><div class="ins-stat-key">Queued</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${esc(ins.queue.mode)}</div><div class="ins-stat-key">Mode</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.historyLength}</div><div class="ins-stat-key">Played</div></div>`;
  if (ins.queueDiversity) {
    html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.queueDiversity.ratio}</div><div class="ins-stat-key">Diversity</div></div>`;
  }
  html += `</div>`;
  if (ins.queue.size > 0 && ins.queue.mode === 'AI') {
    html += `<div class="ins-zones">`;
    if (ins.queue.frozenZone > 0) html += `<div class="ins-zone ins-zone-frozen" style="flex:${ins.queue.frozenZone}"><span>${ins.queue.frozenZone}</span>Frozen</div>`;
    if (ins.queue.stableZone > 0) html += `<div class="ins-zone ins-zone-stable" style="flex:${ins.queue.stableZone}"><span>${ins.queue.stableZone}</span>Stable</div>`;
    if (ins.queue.fluidZone > 0) html += `<div class="ins-zone ins-zone-fluid" style="flex:${ins.queue.fluidZone}"><span>${ins.queue.fluidZone}</span>Fluid</div>`;
    html += `</div>`;
    html += `<div class="ins-hint">Frozen: untouched. Stable: reordered by evolving taste. Fluid: fresh songs enter here.</div>`;
  }
  html += `<div class="ins-stat ins-label">${esc(ins.queue.label)}</div>`;
  html += '</div>';

  // === LIBRARY ===
  html += '<div class="ins-section"><div class="ins-title">Library</div>';
  html += `<div class="ins-stat-grid">`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.embeddingCoverage.embedded}</div><div class="ins-stat-key">AI Songs</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.embeddingCoverage.total}</div><div class="ins-stat-key">Total</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.embeddingCoverage.percentage}</div><div class="ins-stat-key">Coverage</div></div>`;
  html += `<div class="ins-stat-box"><div class="ins-stat-val">${ins.profile.exists ? 'Active' : 'None'}</div><div class="ins-stat-key">Profile</div></div>`;
  html += `</div>`;
  if (ins.onDeviceEmbedding) {
    const ode = ins.onDeviceEmbedding;
    if (ode.inProgress) {
      html += `<div class="ins-hint" style="color:var(--accent);">Embedding in progress... ${ode.queueSize} songs remaining</div>`;
    }
    if (ode.localCount > 0) {
      html += `<div class="ins-hint">${ode.localCount} songs embedded on-device</div>`;
    }
    if (ode.unembedded > 0 && !ode.inProgress) {
      html += `<div class="ins-hint">${ode.unembedded} songs awaiting embedding</div>`;
    }
  }
  html += `</div>`;

  html += '</div>';
  panel.innerHTML = html;
  panel.style.display = 'block';
}

async function surpriseUI() {
  const frac = getListenFraction();
  const info = await engine.doSurprise(frac);
  if (info) {
    await loadAndPlay(info);
    refreshStateUI();
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
    document.querySelector('[data-tab="recs"]').classList.add('active');
    document.getElementById('panel-recs').classList.add('active');
    activeTab = 'recs';
    document.getElementById('searchBar').style.display = 'none';
  }
}

async function shutdownUI() {
  if (confirm('Stop player and exit?')) {
    await _persistPendingListenEvidence('shutdown');
    engine.shutdown();
    try { MusicBridge.stopPlaybackService(); } catch (e) { /* ignore */ }
    document.body.innerHTML = `<div style="display:flex;height:100vh;align-items:center;justify-content:center;color:#555;font-family:sans-serif;">Player stopped.</div>`;
  }
}

function resetUI() {
  closeResetConfirmOverlay();
  const overlay = document.createElement('div');
  overlay.id = 'resetConfirmOverlay';
  overlay.className = 'sd-overlay';
  overlay.innerHTML = `
    <div class="sd-modal reset-confirm-modal">
      <div class="sd-title">Reset Engine?</div>
      <div class="reset-confirm-body">
        <div class="reset-confirm-block">
          <div class="reset-confirm-head reset-confirm-head-clear">Clears</div>
          <div class="reset-confirm-text">Up Next, playback history, Taste profile, favorites, dislikes, X-score, session logs, and saved playback state.</div>
        </div>
        <div class="reset-confirm-block">
          <div class="reset-confirm-head reset-confirm-head-keep">Keeps</div>
          <div class="reset-confirm-text">Song files, embeddings, playlists, and Common Logs / activity logs.</div>
        </div>
        <div class="reset-confirm-note">Playback stops and the app reloads after reset.</div>
      </div>
      <div class="reset-confirm-actions">
        <button class="reset-confirm-cancel" onclick="window._app.closeResetConfirm()">Cancel</button>
        <button class="reset-confirm-confirm" onclick="window._app.confirmResetEngine()">Reset</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) closeResetConfirmOverlay();
  });
}

function closeResetConfirmOverlay() {
  const overlay = document.getElementById('resetConfirmOverlay');
  if (overlay) overlay.remove();
}

async function confirmResetEngineUI() {
  closeResetConfirmOverlay();
  try { MusicBridge.stopPlaybackService(); } catch (e) { /* ignore */ }
  await engine.resetEngine();
  window.location.reload();
}

// ===== STATE REFRESH =====

function refreshStateUI() {
  const data = engine.getState();
  if (data.current) {
    currentSong = data.current.id;
    currentIsFav = !!data.current.isFavorite;
    document.getElementById('npTitle').textContent = data.current.title;
    document.getElementById('npArtist').textContent = data.current.artist + ' \u00b7 ' + (data.current.album || '');
    document.getElementById('nowPlaying').style.display = 'block';
    updateHeartIcon(currentIsFav);
    syncFullPlayer();
    // Real engine state now holds the current song — quickDisplay shim no longer needed.
    _miniPlayerFromQuickDisplay = false;
  } else if (!nativeAudioPlaying && !_miniPlayerFromQuickDisplay) {
    currentSong = null;
    currentIsFav = false;
    document.getElementById('nowPlaying').style.display = 'none';
    updateHeartIcon(false);
  } else if (!nativeAudioPlaying && _miniPlayerFromQuickDisplay) {
    _dbg('refreshStateUI: keeping mini player visible from quickDisplay (engine.current=null, native paused)');
  }
  renderRecs(data);
  renderHistory(data.history, data.historyPos);

  updateModeIndicator();
  prefetchNext();

  // Update "Most Similar" section live (it depends on current song)
  if (engine.isEmbeddingsReady()) renderSimilar();
  refreshVisibleDiscoverCardState();

  // Debounced save of discover cache — captures current queue, history, discover state
  _saveVisibleDiscoverCacheDebounced();
}


function esc(str) {
  const d = document.createElement('div');
  d.textContent = str || '';
  return d.innerHTML;
}


// Expose functions for onclick handlers in HTML
window._app = {
  playSong: playSongUI,
  playFromQueue: playFromQueueUI,
  playFromAlbum: playFromAlbumUI,
  playFromFavorites: playFromFavoritesUI,
  playFromPlaylist: playFromPlaylistUI,
  toggleAlbum: toggleAlbumUI,
  toggleFavorite: handleFavToggle,
  togglePause: togglePauseUI,
  prev: prevUI,
  next: nextUI,
  seek: seekUI,
  neutralSkip: neutralSkipUI,
  toggleShuffle: toggleShuffleUI,
  toggleLoop: toggleLoopUI,
  goToQueue: goToQueueUI,
  playTimelineIndex: playTimelineIndexUI,
  removeFromQueue: (idx) => {
    if (engine.removeFromQueue(idx)) {
      refreshStateUI();
      syncUpcomingNativeQueue();
    }
  },
  toggleRec: toggleRecUI,
  refreshRecs: refreshRecsUI,
  showInsights: showInsightsUI,
  surprise: surpriseUI,
  reset: resetUI,
  closeResetConfirm: closeResetConfirmOverlay,
  confirmResetEngine: confirmResetEngineUI,
  viewAll: viewAllUI,
  toggleSimilarFreeze: toggleSimilarFreezeUI,
  closeViewAll,
  openPlaylist: openPlaylistUI,
  showPlaylistCreator: () => showPlaylistPicker(null),
  showPlaylistPicker,
  closePlaylistPicker,
  createPlaylistFromModal,
  addSongToPlaylist: addSongToPlaylistUI,
  renamePlaylist: renamePlaylistUI,
  deletePlaylist: deletePlaylistUI,
  toggleSection: toggleSectionUI,
  clearSearch,
  showEmbeddingDetail,
  setEmbConfirm: _setEmbConfirm,
  retryEmbedding: () => { engine.retryEmbedding(); showEmbeddingDetail(); },
  // embedPending: kicks off retryEmbedding() which queues every playable song
  // without an embedding (truly new + previously failed) excluding manually
  // removed ones. Kept exported under both names for back-compat with any
  // older references.
  embedPending: () => { engine.retryEmbedding(); showEmbeddingDetail(); },
  embedNewPending: () => { engine.retryEmbedding(); showEmbeddingDetail(); },
  embedRemovedPending: () => {
    const n = engine.embedRemovedSongsBatch();
    showStatusToast(n > 0 ? `Queued ${n} songs for re-embedding` : 'No removed songs to re-add', 1800);
    showEmbeddingDetail();
  },
  confirmReembedAll: () => { engine.reembedAll(); setTimeout(showEmbeddingDetail, 500); },
  rescanLibrary: async () => {
    showStatusToast('Rescanning library...', 1500);
    try { await engine.startBackgroundScan({ force: true }); } catch (e) { /* ignore */ }
    showEmbeddingDetail();
  },
  stopEmbedding: () => { engine.stopEmbedding(); setTimeout(showEmbeddingDetail, 1000); },
  confirmRemoveOrphans: () => {
    _embDetailExpanded.orphanConfirm = false;
    const n = engine.removeOrphanedEmbeddings();
    console.log(`Removed ${n} orphaned embeddings`);
    showEmbeddingDetail();
  },
  confirmRemoveUnmatched: async () => {
    _embDetailExpanded.unmatchedConfirm = false;
    const n = await engine.removeUnmatchedEmbeddings();
    _embDetailExpanded.unmatched = false;
    showStatusToast(n > 0 ? `Removed ${n} unmatched embeddings` : 'No unmatched embeddings to remove', 1500);
    showEmbeddingDetail();
  },
  readdEmbedding: (id) => { engine.readdSongEmbedding(id); showEmbeddingDetail(); },
  showTasteWeights: showTasteWeightsOverlay,
  closeTasteWeights: closeTasteWeightsOverlay,
  resetTasteWeight: resetTasteWeightUI,
  playTasteSignal: playTasteSignalRowUI,
  setTasteSignalFilter: setTasteSignalFilterUI,
  setTasteSignalSort: setTasteSignalSortUI,
  tasteSignalMore: showMoreTasteSignalUI,
  toggleTastePlayback: () => _tasteUi.toggleTastePlayback(),
  toggleTasteLogs: () => _tasteUi.toggleTasteLogs(),
  toggleTasteEngine: () => _tasteUi.toggleTasteEngine(),
  tastePlaybackMore: () => _tasteUi.tastePlaybackMore(),
  tuningInfo: (key) => _tasteUi.tuningInfo(key),
  toggleTasteResetInfo: toggleTasteResetInfoUI,
  setTuning: async (key, val) => {
    await engine.setTuning({ [key]: Number(val) });
    showStatusToast('Tuning saved', 1200);
    if (document.querySelector('#panel-discover .taste-weights-page')) showTasteWeightsOverlay();
  },
  resetTuning: async (key) => {
    const defaults = { adventurous: 0.8, sessionBias: 0.5, negativeStrength: 0.5 };
    if (!(key in defaults)) return;
    // Immediate visual feedback: snap the slider and value text BEFORE awaiting
    // engine.setTuning (which can take ~200–500ms while it rebuilds profileVec).
    // Without this the user taps ↺ and stares at the old position until the
    // async chain resolves and showTasteWeightsOverlay re-renders, which feels
    // like the button is broken.
    const pct = Math.round(defaults[key] * 100);
    const slider = document.querySelector(`.tuning-slider[data-tuning-key="${key}"]`);
    if (slider) slider.value = String(pct);
    const valSpan = document.getElementById(`tune${key}Val`);
    if (valSpan) valSpan.textContent = pct + '%';
    await engine.setTuning({ [key]: defaults[key] });
    showStatusToast('Reset to default', 1200);
    showTasteWeightsOverlay();
  },
  playFromSection: (songId, sectionKey) => playFromSectionUI(songId, sectionKey),
  playOnly: (songId) => playOnlyUI(songId),
  embToggle: _embToggleSection,
  setEmbLogTab: _setEmbLogTab,
  embScrollPending: _embScrollToPending,
  copyEmbLogs: _copyEmbLogs,
  copyTasteLogs: copyTasteLogsUI,
  copyTastePlaybackSignals: copyTastePlaybackSignalsUI,
  handleArtError: handleArtErrorUI,
  showSongMenu,
};

// ===== BACK NAVIGATION =====
// Uses @capacitor/app backButton event for Android back gesture/button

App.addListener('appStateChange', ({ isActive }) => {
  logActivity({
    category: 'app',
    type: isActive ? 'app_resumed' : 'app_backgrounded',
    message: isActive ? 'App resumed' : 'App backgrounded',
    important: false,
    tags: ['lifecycle'],
  });
  if (!isActive && currentSong != null) {
    persistPlaybackState(true);
    _persistPendingListenEvidence('app_inactive');
  }
});

App.addListener('backButton', () => {
  _handleBackButton();
});

init();
