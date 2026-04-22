/**
 * Session logger — captures every user interaction and system event
 * as structured JSON lines persisted via Capacitor Preferences API.
 *
 * All methods are failure-safe. Logging issues never crash the player.
 */

import { Preferences } from '@capacitor/preferences';
import { logActivity } from './activity-log.js';

const SESSION_LOGS_KEY = 'session_logs_index';
const PROFILE_SUMMARY_KEY = 'profile_summary_v2'; // v2: keyed by filename, not ID
const PROFILE_SUMMARY_KEY_V1 = 'profile_summary_v1'; // old key for migration
const PROFILE_RESET_MARKERS_KEY = 'profile_reset_markers_v1';
const MAX_SESSIONS = 30; // keep last 30 sessions, purge older ones
const FLUSH_DELAY_MS = 2000; // debounce flush interval

function _makeEmptyProfileSummary() {
  return { songs: {}, totalPlays: 0, totalSkips: 0 };
}

function _makeEmptyProfileEntry() {
  return { plays: 0, skips: 0, completions: 0, fracs: [], lastPlayedAt: null };
}

function _cloneProfileEntry(entry) {
  if (!entry) return null;
  return {
    plays: Math.max(0, Number(entry.plays) || 0),
    skips: Math.max(0, Number(entry.skips) || 0),
    completions: Math.max(0, Number(entry.completions) || 0),
    fracs: Array.isArray(entry.fracs) ? entry.fracs.slice() : [],
    lastPlayedAt: entry.lastPlayedAt || null,
  };
}

function _avgFrac(fracs) {
  if (!Array.isArray(fracs) || fracs.length === 0) return null;
  return fracs.reduce((a, b) => a + b, 0) / fracs.length;
}

function _roundFrac(value) {
  return value == null ? null : Math.round(value * 100) / 100;
}

function _applyProfileSummaryEvent(summary, filename, eventType, fraction, playedAt = new Date().toISOString()) {
  if (!summary.songs) summary.songs = {};
  const before = _cloneProfileEntry(summary.songs[filename]);
  if (!summary.songs[filename]) summary.songs[filename] = _makeEmptyProfileEntry();
  const s = summary.songs[filename];

  if (eventType === 'played') {
    s.plays++;
    s.lastPlayedAt = playedAt;
    summary.totalPlays = Math.max(0, Number(summary.totalPlays) || 0) + 1;
  } else if (eventType === 'skipped') {
    s.skips++;
    summary.totalSkips = Math.max(0, Number(summary.totalSkips) || 0) + 1;
    if (fraction != null) {
      s.fracs.push(fraction);
      if (s.fracs.length > 10) s.fracs.shift();
    }
  } else if (eventType === 'completed') {
    s.completions++;
    if (fraction != null) {
      s.fracs.push(fraction);
      if (s.fracs.length > 10) s.fracs.shift();
    }
  }

  return { before, after: _cloneProfileEntry(s) };
}

function _isEventIgnoredByReset(eventTime, resetAt) {
  if (!resetAt) return false;
  const resetMs = Date.parse(resetAt);
  if (!Number.isFinite(resetMs)) return false;
  if (!eventTime) return true;
  const eventMs = Date.parse(eventTime);
  if (!Number.isFinite(eventMs)) return true;
  return eventMs < resetMs;
}

class SessionLogger {
  constructor() {
    this.sessionKey = null;
    this.sessionStart = Date.now();
    this.buffer = [];
    this._profileSummary = null; // loaded lazily
    this._flushTimer = null;

    const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    this.sessionKey = `session_${ts}`;
    this._write({ event: 'session_start', session_key: this.sessionKey });
    this._registerSession();
    this._loadProfileSummary().catch(() => {});
  }

  async _registerSession() {
    try {
      const { value } = await Preferences.get({ key: SESSION_LOGS_KEY });
      const index = value ? JSON.parse(value) : [];
      index.push(this.sessionKey);

      // Purge oldest sessions beyond MAX_SESSIONS
      if (index.length > MAX_SESSIONS) {
        const toRemove = index.splice(0, index.length - MAX_SESSIONS);
        for (const oldKey of toRemove) {
          try { await Preferences.remove({ key: oldKey }); } catch (e) { /* ignore */ }
        }
      }

      await Preferences.set({ key: SESSION_LOGS_KEY, value: JSON.stringify(index) });
    } catch (e) { /* ignore */ }
  }

  _write(data) {
    if (!this.sessionKey) return;
    try {
      data.timestamp = new Date().toISOString();
      data.elapsed_sec = Math.round((Date.now() - this.sessionStart) / 100) / 10;
      this.buffer.push(JSON.stringify(data));
      // Debounced flush — batches rapid events into a single write
      this._scheduleFlush();
    } catch (e) { /* never crash */ }
  }

  _scheduleFlush() {
    if (this._flushTimer) return; // already scheduled
    this._flushTimer = setTimeout(() => {
      this._flushTimer = null;
      this._flush();
    }, FLUSH_DELAY_MS);
  }

  async _flush() {
    if (this.buffer.length === 0 || this._flushing) return;
    this._flushing = true;
    try {
      const { value } = await Preferences.get({ key: this.sessionKey });
      const existing = value || '';
      const newLines = this.buffer.join('\n') + '\n';
      this.buffer = [];
      await Preferences.set({ key: this.sessionKey, value: existing + newLines });
    } catch (e) { /* ignore */ }
    this._flushing = false;
    // If new items were buffered during flush, schedule another
    if (this.buffer.length > 0) this._scheduleFlush();
  }

  songPlayed(song, source, prevSong = null, prevFraction = null) {
    const entry = {
      event: 'song_played',
      song_id: song.id,
      filename: song.filename,
      title: song.title,
      artist: song.artist,
      album: song.album,
      source,
    };
    if (prevSong != null) {
      entry.prev_song_id = prevSong.id;
      entry.prev_title = prevSong.title;
      entry.prev_listen_fraction = prevFraction;
    }
    this._write(entry);
    // Update profile summary incrementally (keyed by filename)
    this._updateProfileSummary(song, 'played', null);
  }

  songSkipped(song, fraction) {
    this._write({
      event: 'song_skipped',
      song_id: song.id,
      filename: song.filename,
      title: song.title,
      listen_fraction: fraction,
    });
    this._updateProfileSummary(song, 'skipped', fraction);
  }

  songCompleted(song, fraction) {
    this._write({
      event: 'song_completed',
      song_id: song.id,
      filename: song.filename,
      title: song.title,
      listen_fraction: fraction,
    });
    this._updateProfileSummary(song, 'completed', fraction);
  }

  queueGenerated(sourceType, sourceSongs, queue) {
    this._write({
      event: 'queue_generated',
      source_type: sourceType,
      source_songs: sourceSongs,
      recommendations: queue.map((q, i) => ({
        rank: i + 1,
        title: q.title,
        similarity: q.similarity,
      })),
      queue_size: queue.length,
    });
  }

  queueRefreshedManual() {
    this._write({ event: 'refresh_button_pressed' });
  }

  queueExhausted() {
    this._write({ event: 'queue_exhausted' });
  }

  prevNavigated(song) {
    this._write({ event: 'prev_navigated', song_id: song.id, title: song.title });
  }

  historyForward(song) {
    this._write({ event: 'history_forward', song_id: song.id, title: song.title });
  }

  playbackSignalApplied(payload) {
    this._write({
      event: 'playback_signal_applied',
      ...payload,
    });
  }

  async shutdown() {
    this._write({ event: 'session_end' });
    // Cancel debounce timer and flush immediately
    if (this._flushTimer) { clearTimeout(this._flushTimer); this._flushTimer = null; }
    await this._flush();
  }

  // ===== INCREMENTAL PROFILE SUMMARY (v2 — keyed by filename) =====
  // { songs: { [filename]: { plays, skips, completions, fracs: [last 10 fractions], lastPlayedAt } },
  //   totalPlays, totalSkips }

  async _loadProfileSummary() {
    if (this._profileSummary) return this._profileSummary;
    try {
      const { value } = await Preferences.get({ key: PROFILE_SUMMARY_KEY });
      if (value) {
        this._profileSummary = JSON.parse(value);
      } else {
        // Try migrating from v1 (ID-based) — rebuild from logs if v1 exists
        const { value: v1 } = await Preferences.get({ key: PROFILE_SUMMARY_KEY_V1 });
        if (v1) {
          console.log('[PROFILE] Migrating v1 → v2: rebuilding from logs');
          this._profileSummary = await SessionLogger.rebuildProfileSummary();
        } else {
          this._profileSummary = _makeEmptyProfileSummary();
        }
      }
    } catch (e) {
      this._profileSummary = _makeEmptyProfileSummary();
    }
    return this._profileSummary;
  }

  async _saveProfileSummary() {
    if (!this._profileSummary) return;
    try {
      await Preferences.set({ key: PROFILE_SUMMARY_KEY, value: JSON.stringify(this._profileSummary) });
    } catch (e) { /* ignore */ }
  }

  peekProfileSummaryEntry(filename) {
    if (!filename || !this._profileSummary || !this._profileSummary.songs) return null;
    return _cloneProfileEntry(this._profileSummary.songs[filename]);
  }

  previewProfileSummaryUpdate(filename, eventType, fraction) {
    if (!filename || !this._profileSummary) return null;
    const working = {
      songs: {},
      totalPlays: Math.max(0, Number(this._profileSummary.totalPlays) || 0),
      totalSkips: Math.max(0, Number(this._profileSummary.totalSkips) || 0),
    };
    const existing = this._profileSummary.songs ? this._profileSummary.songs[filename] : null;
    if (existing) working.songs[filename] = _cloneProfileEntry(existing);
    return _applyProfileSummaryEvent(working, filename, eventType, fraction);
  }

  applyRecommendationReset(filename) {
    if (!filename || !this._profileSummary || !this._profileSummary.songs) return;
    delete this._profileSummary.songs[filename];
  }

  async _updateProfileSummary(songOrFilename, eventType, fraction) {
    try {
      const filename = typeof songOrFilename === 'string' ? songOrFilename : songOrFilename && songOrFilename.filename;
      const title = typeof songOrFilename === 'object' && songOrFilename ? songOrFilename.title : null;
      if (!filename) return;
      const summary = await this._loadProfileSummary();
      _applyProfileSummaryEvent(summary, filename, eventType, fraction);
      const s = summary.songs[filename];

      this._saveProfileSummary(); // fire and forget

      const avgFrac = _avgFrac(s.fracs);
      const message = eventType === 'played'
        ? `Playback start saved to recommendation summary`
        : eventType === 'skipped'
          ? `Skip saved to recommendation summary`
          : `Completion saved to recommendation summary`;
      logActivity({
        category: 'taste',
        type: 'profile_summary_updated',
        message,
        data: {
          title: title || filename,
          filename,
          eventType,
          plays: s.plays || 0,
          skips: s.skips || 0,
          completions: s.completions || 0,
          fracsCount: Array.isArray(s.fracs) ? s.fracs.length : 0,
          avgFrac: _roundFrac(avgFrac),
          lastFraction: _roundFrac(fraction),
          lastPlayedAt: s.lastPlayedAt || null,
        },
        tags: ['profile', 'summary'],
        important: false,
        level: eventType === 'skipped' ? 'warn' : 'info',
      });
    } catch (e) { /* never crash */ }
  }

  /**
   * Load the pre-computed profile summary (v2 — keyed by filename).
   */
  static async loadProfileSummary() {
    try {
      const { value } = await Preferences.get({ key: PROFILE_SUMMARY_KEY });
      if (value) return JSON.parse(value);
      // Fallback: try rebuilding from v1/logs
      const { value: v1 } = await Preferences.get({ key: PROFILE_SUMMARY_KEY_V1 });
      if (v1) {
        console.log('[PROFILE] Static load: migrating v1 → v2');
        return await SessionLogger.rebuildProfileSummary();
      }
      return _makeEmptyProfileSummary();
    } catch (e) {
      return _makeEmptyProfileSummary();
    }
  }

  /**
   * Recommendation reset is durable:
   * - current summary entry is cleared
   * - raw session logs are preserved for audit
   * - future rebuilds ignore older events before resetAt
   */
  static async resetSongRecommendationHistory(filename, opts = {}) {
    try {
      if (!filename) return false;
      const resetAt = opts.resetAt || new Date().toISOString();
      const summary = await SessionLogger.loadProfileSummary();
      const markers = await SessionLogger.loadProfileResetMarkers();
      markers[filename] = resetAt;
      if (summary.songs[filename]) delete summary.songs[filename];
      await Preferences.set({ key: PROFILE_SUMMARY_KEY, value: JSON.stringify(summary) });
      await Preferences.set({ key: PROFILE_RESET_MARKERS_KEY, value: JSON.stringify(markers) });
      logActivity({
        category: 'taste',
        type: 'profile_summary_entry_reset',
        message: 'Recommendation history reset for song',
        data: { filename, resetAt },
        tags: ['profile', 'summary', 'reset'],
        important: true,
      });
      return true;
    } catch (e) {
      return false;
    }
  }

  static async resetSongProfileEntry(filename) {
    return SessionLogger.resetSongRecommendationHistory(filename);
  }

  static async loadProfileResetMarkers() {
    try {
      const { value } = await Preferences.get({ key: PROFILE_RESET_MARKERS_KEY });
      if (!value) return {};
      const parsed = JSON.parse(value);
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch (e) {
      return {};
    }
  }

  /**
   * Rebuild profile summary from all existing logs, keyed by filename.
   * Used for v1→v2 migration and when summary is missing.
   */
  static async rebuildProfileSummary() {
    try {
      const allEvents = await SessionLogger.loadAllLogs();
      const resetMarkers = await SessionLogger.loadProfileResetMarkers();
      const summary = _makeEmptyProfileSummary();

      for (const event of allEvents) {
        // Use filename as key (v2). Fall back to title for very old logs without filename.
        const key = event.filename || event.title;
        if (!key) continue;
        if (_isEventIgnoredByReset(event.timestamp || null, resetMarkers[key])) continue;
        const eventTime = event.timestamp || null;

        if (event.event === 'song_played') {
          _applyProfileSummaryEvent(summary, key, 'played', null, eventTime || new Date().toISOString());
        } else if (event.event === 'song_skipped') {
          _applyProfileSummaryEvent(summary, key, 'skipped', event.listen_fraction, eventTime || new Date().toISOString());
        } else if (event.event === 'song_completed') {
          _applyProfileSummaryEvent(summary, key, 'completed', event.listen_fraction, eventTime || new Date().toISOString());
        }
      }

      await Preferences.set({ key: PROFILE_SUMMARY_KEY, value: JSON.stringify(summary) });
      const entries = Object.values(summary.songs || {});
      logActivity({
        category: 'taste',
        type: 'profile_summary_rebuilt',
        message: 'Recommendation summary rebuilt from raw session logs',
        data: {
          songCount: Object.keys(summary.songs || {}).length,
          totalPlays: summary.totalPlays || 0,
          totalSkips: summary.totalSkips || 0,
          noFractionCount: entries.filter(row => (row.plays || 0) > 0 && (!Array.isArray(row.fracs) || row.fracs.length === 0)).length,
        },
        tags: ['profile', 'summary'],
        important: true,
      });
      // Clean up old v1 key
      try { await Preferences.remove({ key: PROFILE_SUMMARY_KEY_V1 }); } catch (e) { /* ignore */ }
      return summary;
    } catch (e) {
      return { songs: {}, totalPlays: 0, totalSkips: 0 };
    }
  }

  /**
   * Load all session log entries from all stored sessions.
   * Returns array of parsed event objects.
   */
  static async loadAllLogs() {
    try {
      const { value } = await Preferences.get({ key: SESSION_LOGS_KEY });
      if (!value) return [];
      const index = JSON.parse(value);
      // Read all sessions in parallel
      const results = await Promise.all(
        index.map(key => Preferences.get({ key }).catch(() => ({ value: null })))
      );
      const allEvents = [];
      for (const { value: logData } of results) {
        if (!logData) continue;
        const lines = logData.trim().split('\n');
        for (const line of lines) {
          try { allEvents.push(JSON.parse(line)); } catch (e) { /* skip */ }
        }
      }
      return allEvents;
    } catch (e) {
      return [];
    }
  }

  /**
   * Load recent session logs (last N sessions).
   */
  static async loadRecentLogs(nSessions = 5) {
    try {
      const { value } = await Preferences.get({ key: SESSION_LOGS_KEY });
      if (!value) return [];
      const index = JSON.parse(value);
      const recentKeys = index.slice(-nSessions);
      // Read all sessions in parallel
      const results = await Promise.all(
        recentKeys.map(key => Preferences.get({ key }).catch(() => ({ value: null })))
      );
      const allEvents = [];
      for (const { value: logData } of results) {
        if (!logData) continue;
        const lines = logData.trim().split('\n');
        for (const line of lines) {
          try { allEvents.push(JSON.parse(line)); } catch (e) { /* skip */ }
        }
      }
      return allEvents;
    } catch (e) {
      return [];
    }
  }
}

export { SessionLogger };
