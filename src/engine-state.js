// Shared state for engine.js — all module-level vars and constants live here
// so future helper slices can import them without circular dependencies.
// Mutable lets expose setter functions; ES module live bindings propagate to all importers.

// --- Recommendation constants ---
export const TOP_N = 50;
export const FROZEN_ZONE = 5;
export const STABLE_ZONE = 25;
export const FAVORITE_PRIOR_BASE = 2.0;
export const DISLIKE_PRIOR_BASE = 3.0;
export const MANUAL_PRIOR_HALF_LIFE_PLAYS = 2;
export const PLAYLISTS_PREF_KEY = 'playlists_v1';
export const PENDING_LISTEN_KEY = 'pending_listen_v1';
export const TUNING_DEFAULTS = { adventurous: 0.8, sessionBias: 0.5, negativeStrength: 0.5 };
export const SIMILARITY_BOOST_KEY = 'similarity_boost_scores_v1';
export const SIMILARITY_BOOST_MAX = 4;
export const SIMILARITY_NEIGHBOR_COUNT = 10;
export const SIMILARITY_NEIGHBOR_WEIGHTS = Object.freeze([0.10, 0.09, 0.08, 0.07, 0.06, 0.05, 0.04, 0.03, 0.02, 0.01]);
export const RECOMMENDATION_REBUILD_DEBOUNCE_MS = 220;
export const RECOMMENDATION_NEGATIVE_HARD_EXCLUDE_SHARE = 0.18;
export const RECOMMENDATION_NEGATIVE_HARD_EXCLUDE_FLOOR = 1.5;
export const RECOMMENDATION_POSITIVE_BONUS_PER_POINT = 0.03;
export const RECOMMENDATION_POSITIVE_BONUS_MAX = 0.10;
export const RECOMMENDATION_NEGATIVE_PENALTY_PER_POINT = 0.05;
export const RECOMMENDATION_NEGATIVE_PENALTY_MAX = 0.24;
export const RECOMMENDATION_POOL_MULTIPLIER = 4;
export const RECOMMENDATION_POOL_PADDING = 40;
export const NEG_X_DELTA = 0.5;
export const NEG_LISTEN_DECAY = 0.5;
export const NEG_SCORE_MAX = 10;
export const USER_SKIP_NEGATIVE_STEP = 0.1;
export const TASTE_REVIEW_IGNORE_KEY = 'taste_review_ignores_v1';
export const TASTE_REVIEW_REASON_META = {
  no_result: {
    label: 'No Result',
    tone: 'danger',
    description: 'Playback starts exist, but no skip/completion result was saved.',
  },
  x_only: {
    label: 'X Only',
    tone: 'danger',
    description: 'Negative pull is coming mostly from X-score, not listen evidence.',
  },
  mismatch: {
    label: 'Mismatch',
    tone: 'danger',
    description: 'Signal direction conflicts with the recorded listen evidence.',
  },
  reset_pending: {
    label: 'Reset Pending',
    tone: 'warn',
    description: 'This song was reset recently and is waiting for fresh evidence.',
  },
  uncertain: {
    label: 'Uncertain',
    tone: 'warn',
    description: 'Evidence is still too incomplete to trust the signal.',
  },
};

// --- Mutable core library state ---
export let EXTERNAL_DATA_DIR = '/storage/emulated/0/MusicPlayerData';
export function setExternalDataDir(v) { EXTERNAL_DATA_DIR = v; }

export let songs = [];
export function setSongs(v) { songs = v; }

export let embeddings = [];
export function setEmbeddings(v) { embeddings = v; }

export let embeddingMap = {};
export function setEmbeddingMap(v) { embeddingMap = v; }

export let albumList = {};
export function setAlbumList(v) { albumList = v; }

export let albumArray = [];
export function setAlbumArray(v) { albumArray = v; }

export let rec = null;
export function setRec(v) { rec = v; }

export let log = null;
export function setLog(v) { log = v; }

export let favorites = new Set();
export function setFavorites(v) { favorites = v; }

export let playlists = [];
export function setPlaylists(v) { playlists = v; }

export let profileVec = null;
export function setProfileVec(v) { profileVec = v; }

export let scanCallbacks = [];

export let scanComplete = false;
export function setScanComplete(v) { scanComplete = v; }

export let recToggle = true;
export function _setRecToggle(v) { recToggle = v; }

export let queueShuffleEnabled = false;
export function _setQueueShuffleEnabled(v) { queueShuffleEnabled = v; }

// --- Tuning state ---
export let _tuning = { ...TUNING_DEFAULTS };
export function _setTuning(v) { _tuning = v; }

export let similarityBoostScores = {};
export function setSimilarityBoostScores(v) { similarityBoostScores = v; }

// --- Recommendation policy snapshot ---
export let _recommendationPolicySnapshot = {
  rowsById: new Map(),
  hardExcludeSongIds: new Set(),
  fingerprint: '',
  version: 0,
  updatedAt: 0,
};
export function setRecommendationPolicySnapshot(v) { _recommendationPolicySnapshot = v; }

export let _recommendationStatusCbs = [];

export let _recommendationRebuildTimer = null;
export function setRecommendationRebuildTimer(v) { _recommendationRebuildTimer = v; }

export let _recommendationRebuildInFlight = false;
export function setRecommendationRebuildInFlight(v) { _recommendationRebuildInFlight = v; }

export let _recommendationRebuildPending = false;
export function setRecommendationRebuildPending(v) { _recommendationRebuildPending = v; }

export let _recommendationRebuildReason = null;
export function setRecommendationRebuildReason(v) { _recommendationRebuildReason = v; }

export let _recommendationRebuildOpts = { refreshQueue: false, refreshDiscover: false };
export function setRecommendationRebuildOpts(v) { _recommendationRebuildOpts = v; }

// --- Negative signal state ---
export let negativeScores = {};
export function setNegativeScores(v) { negativeScores = v; }

// --- Playback instance tracking ---
export let _currentPlaybackInstanceId = 0;
export function setCurrentPlaybackInstanceId(v) { _currentPlaybackInstanceId = v; }

export let _capturedPlaybackInstanceId = null;
export function setCapturedPlaybackInstanceId(v) { _capturedPlaybackInstanceId = v; }

export let _lastLoggedPlaybackStartInstanceId = 0;
export function setLastLoggedPlaybackStartInstanceId(v) { _lastLoggedPlaybackStartInstanceId = v; }

export let _lastLoggedPlaybackStartSongId = null;
export function setLastLoggedPlaybackStartSongId(v) { _lastLoggedPlaybackStartSongId = v; }

// --- Dislike / taste state ---
export let dislikedFilenames = new Set();
export function setDislikedFilenames(v) { dislikedFilenames = v; }

export let _tasteReviewIgnores = null;
export function setTasteReviewIgnores(v) { _tasteReviewIgnores = v; }

export let _lastTasteReviewSnapshot = '';
export function setLastTasteReviewSnapshot(v) { _lastTasteReviewSnapshot = v; }

export let _lastProfileWeightSnapshot = { positive: new Map(), negative: new Map() };
export function setLastProfileWeightSnapshot(v) { _lastProfileWeightSnapshot = v; }

// --- Taste / profile constants ---
export const MAX_RECENT_PLAYBACK_SIGNALS = 60;
export const PROFILE_DAY_MS = 86400000;
export const PROFILE_HALF_LIFE_DAYS = 30;
export const REVIEW_RESET_PENDING_WINDOW_MS = 24 * 60 * 60 * 1000;
export const NEGATIVE_PLAY_THRESHOLD = 2;
export const LISTEN_SKIP_THRESHOLD = 0.5;
export const FULL_LISTEN_THRESHOLD = 0.7;
export const NEUTRAL_SKIP_CAPTURE_THRESHOLD = 0.1;
export const NEGATIVE_FRAC_THRESHOLD = LISTEN_SKIP_THRESHOLD;

export let _recentPlaybackSignalEvents = [];
export function setRecentPlaybackSignalEvents(v) { _recentPlaybackSignalEvents = v; }

// --- Session state (mutated in place — no setter needed) ---
export const state = {
  current: null,
  currentSource: null,
  history: [],
  historyPos: -1,
  queue: [],
  listened: [],
  sessionLabel: '',
  playingFavorites: false,
  playingAlbum: false,
  playingPlaylist: false,
  currentPlaylistId: null,
  currentAlbumTracks: [],
  timelineMode: null,
  timelineItems: [],
  timelineIndex: -1,
  explicitPlayedIds: [],
};
