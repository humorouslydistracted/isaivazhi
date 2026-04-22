import { Filesystem, Directory, Encoding } from '@capacitor/filesystem';
import { Preferences } from '@capacitor/preferences';

const LOG_DIR = 'logs/activity';
const LOG_INDEX_KEY = 'activity_log_sessions_v1';
const MAX_VISIBLE_EVENTS = 300;
const MAX_SESSIONS = 30;
const FLUSH_DELAY_MS = 2000;
const MAX_FILE_BYTES = 262144; // 256 KB

let sessionId = null;
let seq = 0;
let recentEvents = [];
let pendingLines = [];
let flushTimer = null;
let activeFilePath = null;
let activeFilePart = 1;
let estimatedFileBytes = 0;
let initialized = false;

function _nowIso() {
  return new Date().toISOString();
}

function _sessionStamp() {
  return new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
}

function _eventId() {
  return `evt_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}

function _safeMessage(type, message) {
  if (message && String(message).trim()) return String(message);
  return String(type || 'event').replace(/_/g, ' ');
}

function _buildFilePath() {
  const suffix = activeFilePart > 1 ? `.part${activeFilePart}` : '';
  return `${LOG_DIR}/${sessionId}${suffix}.jsonl`;
}

async function _ensureIndex() {
  try {
    const { value } = await Preferences.get({ key: LOG_INDEX_KEY });
    const index = value ? JSON.parse(value) : [];
    const entry = { sessionId, startedAt: _nowIso() };
    index.push(entry);
    let trimmed = index;
    const removed = [];
    if (index.length > MAX_SESSIONS) {
      const cut = index.length - MAX_SESSIONS;
      trimmed = index.slice(cut);
      removed.push(...index.slice(0, cut));
    }
    await Preferences.set({ key: LOG_INDEX_KEY, value: JSON.stringify(trimmed) });
    if (removed.length > 0) {
      const dir = await Filesystem.readdir({ path: LOG_DIR, directory: Directory.Data }).catch(() => null);
      const files = (dir && dir.files) || [];
      for (const oldEntry of removed) {
        const prefix = `${oldEntry.sessionId}`;
        for (const file of files) {
          const name = typeof file === 'string' ? file : file.name;
          if (!name || !name.startsWith(prefix)) continue;
          try {
            await Filesystem.deleteFile({ path: `${LOG_DIR}/${name}`, directory: Directory.Data });
          } catch (e) { /* ignore */ }
        }
      }
    }
  } catch (e) { /* ignore */ }
}

async function _ensureDir() {
  try {
    await Filesystem.mkdir({ path: LOG_DIR, directory: Directory.Data, recursive: true });
  } catch (e) { /* ignore */ }
}

async function _rotateFileIfNeeded(extraBytes) {
  if ((estimatedFileBytes + extraBytes) <= MAX_FILE_BYTES) return;
  activeFilePart += 1;
  activeFilePath = _buildFilePath();
  estimatedFileBytes = 0;
}

async function _flush() {
  if (pendingLines.length === 0 || !activeFilePath) return;
  const chunk = pendingLines.join('');
  pendingLines = [];
  try {
    await _ensureDir();
    await _rotateFileIfNeeded(chunk.length);
    try {
      await Filesystem.appendFile({
        path: activeFilePath,
        data: chunk,
        directory: Directory.Data,
        encoding: Encoding.UTF8,
      });
    } catch (e) {
      await Filesystem.writeFile({
        path: activeFilePath,
        data: chunk,
        directory: Directory.Data,
        encoding: Encoding.UTF8,
        recursive: true,
      });
    }
    estimatedFileBytes += chunk.length;
  } catch (e) {
    console.error('[ActivityLog] flush failed:', e);
  }
}

function _scheduleFlush() {
  if (flushTimer) return;
  flushTimer = setTimeout(async () => {
    flushTimer = null;
    await _flush();
  }, FLUSH_DELAY_MS);
}

async function initActivityLog() {
  if (initialized) return { sessionId, recentLimit: MAX_VISIBLE_EVENTS };
  sessionId = `activity_${_sessionStamp()}`;
  activeFilePart = 1;
  activeFilePath = _buildFilePath();
  estimatedFileBytes = 0;
  initialized = true;
  await _ensureDir();
  await _ensureIndex();
  logActivity({
    category: 'app',
    type: 'activity_log_started',
    message: 'Activity logging started',
    important: false,
    level: 'info',
    tags: ['logger'],
  });
  return { sessionId, recentLimit: MAX_VISIBLE_EVENTS };
}

function logActivity(evt) {
  if (!initialized) {
    sessionId = sessionId || `activity_${_sessionStamp()}`;
    activeFilePart = activeFilePart || 1;
    activeFilePath = activeFilePath || _buildFilePath();
    initialized = true;
  }
  const event = {
    id: _eventId(),
    ts: _nowIso(),
    sessionId,
    seq: ++seq,
    level: evt.level || 'info',
    category: evt.category || 'app',
    type: evt.type || 'event',
    message: _safeMessage(evt.type, evt.message),
    data: evt.data || {},
    tags: Array.isArray(evt.tags) ? evt.tags : [],
    important: evt.important !== false,
  };
  recentEvents.push(event);
  if (recentEvents.length > MAX_VISIBLE_EVENTS) {
    recentEvents = recentEvents.slice(recentEvents.length - MAX_VISIBLE_EVENTS);
  }
  pendingLines.push(JSON.stringify(event) + '\n');
  _scheduleFlush();
  return event;
}

function getRecentActivityEvents(opts = {}) {
  const limit = Math.max(1, Math.min(MAX_VISIBLE_EVENTS, opts.limit || MAX_VISIBLE_EVENTS));
  const category = opts.category || null;
  const importantOnly = opts.importantOnly === true;
  let rows = recentEvents;
  if (category && category !== 'all') rows = rows.filter(e => e.category === category);
  if (importantOnly) rows = rows.filter(e => e.important);
  return rows.slice(-limit);
}

function getActivityLogStatus() {
  return {
    sessionId,
    recentCount: recentEvents.length,
    recentLimit: MAX_VISIBLE_EVENTS,
    filePath: activeFilePath,
  };
}

async function flushActivityLog() {
  if (flushTimer) {
    clearTimeout(flushTimer);
    flushTimer = null;
  }
  await _flush();
}

export {
  initActivityLog,
  logActivity,
  getRecentActivityEvents,
  getActivityLogStatus,
  flushActivityLog,
};
