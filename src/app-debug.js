export function createAppDebug({ maxLines = 900 } = {}) {
  const dbgLines = [];

  function ensureGlobalDebugToast() {
    const existing = document.getElementById('debug-toast');
    if (existing) return existing;
    const d = document.createElement('div');
    d.id = 'debug-toast';
    d.style.cssText = 'position:fixed;top:10px;left:50%;transform:translateX(-50%);background:#c00;color:#fff;padding:8px 16px;border-radius:8px;z-index:99999;font-size:11px;max-width:90%;word-break:break-all;';
    document.body.appendChild(d);
    return d;
  }

  function showGlobalDebugToast(text) {
    try {
      const el = ensureGlobalDebugToast();
      el.textContent = text;
      el.style.opacity = '1';
    } catch (e) { /* ignore */ }
  }

  function summarizeError(errLike) {
    if (errLike == null) return 'unknown';
    if (typeof errLike === 'string') return errLike;
    if (errLike && errLike.stack) return String(errLike.stack);
    if (errLike && errLike.message) return String(errLike.message);
    try { return JSON.stringify(errLike); } catch (e) { return String(errLike); }
  }

  function dbg(msg) {
    const ts = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 });
    const line = ts + ' ' + String(msg);
    console.log('[DBG] ' + msg);
    dbgLines.push(line);
    if (dbgLines.length > maxLines) {
      dbgLines.splice(0, dbgLines.length - maxLines);
    }
    try {
      const el = document.getElementById('debugLogText');
      if (el) {
        el.textContent = dbgLines.join('\n') + '\n';
        const panel = document.getElementById('debugLogPanel');
        if (panel && panel.style.display !== 'none') panel.scrollTop = panel.scrollHeight;
      }
    } catch (e) { /* ignore */ }
  }

  function installGlobalErrorHandlers() {
    window.onerror = (msg, src, line, col, err) => {
      const errorText = 'JS ERROR: ' + msg + ' (line ' + line + (col ? ':' + col : '') + ')';
      dbg(errorText);
      if (src) dbg('JS ERROR SRC: ' + src);
      if (err && err.stack) dbg('JS ERROR STACK: ' + err.stack);
      showGlobalDebugToast(errorText);
    };

    window.onunhandledrejection = (e) => {
      const reason = summarizeError(e && e.reason);
      console.error('Unhandled rejection:', e && e.reason);
      dbg('UNHANDLED REJECTION: ' + reason);
      showGlobalDebugToast('Unhandled rejection: ' + reason.slice(0, 180));
    };
  }

  return {
    dbg,
    dbgLines,
    installGlobalErrorHandlers,
    summarizeError,
  };
}
