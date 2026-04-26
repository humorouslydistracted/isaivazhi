export function createStatusUi() {
  let toastTimer = null;
  let recommendationStatusTimer = null;

  function showStatusToast(text, duration) {
    const el = document.getElementById('statusToast');
    if (!el) return;
    el.textContent = text;
    el.style.display = 'block';
    el.classList.remove('hidden');
    if (toastTimer) clearTimeout(toastTimer);
    if (duration) {
      toastTimer = setTimeout(() => {
        el.classList.add('hidden');
        setTimeout(() => { el.style.display = 'none'; }, 300);
      }, duration);
    }
  }

  function hideStatusToast() {
    const el = document.getElementById('statusToast');
    if (!el) return;
    el.classList.add('hidden');
    setTimeout(() => { el.style.display = 'none'; }, 300);
  }

  function setRecommendationStatus(text, show) {
    const el = document.getElementById('recommendationStatus');
    if (!el) return;
    if (!show || !text) {
      el.classList.remove('is-visible');
      el.textContent = '';
      return;
    }
    el.textContent = text;
    el.classList.add('is-visible');
  }

  function formatRecommendationReason(reason) {
    switch (reason) {
      case 'favorite_toggle': return 'Updating recommendations after favorite...';
      case 'dislike_toggle': return 'Updating recommendations after dislike...';
      case 'playback_skip': return 'Updating recommendations after skip...';
      case 'playback_complete': return 'Updating recommendations after listen...';
      case 'queue_remove': return 'Updating recommendations after X...';
      case 'tuning_changed': return 'Applying recommendation tuning...';
      case 'reset_song_recommendation_history': return 'Refreshing recommendations after reset...';
      default: return 'Updating recommendations...';
    }
  }

  function handleRecommendationRebuildStatus(state, opts = {}) {
    const refreshStateUI = typeof opts.refreshStateUI === 'function' ? opts.refreshStateUI : null;
    const showTasteWeightsOverlay = typeof opts.showTasteWeightsOverlay === 'function' ? opts.showTasteWeightsOverlay : null;

    if (!state || !state.phase) return;
    if (state.phase === 'queued' || state.phase === 'running') {
      if (recommendationStatusTimer) clearTimeout(recommendationStatusTimer);
      const text = formatRecommendationReason(state.reason);
      recommendationStatusTimer = setTimeout(() => {
        setRecommendationStatus(text, true);
      }, 320);
      return;
    }

    if (recommendationStatusTimer) {
      clearTimeout(recommendationStatusTimer);
      recommendationStatusTimer = null;
    }
    setRecommendationStatus('', false);

    if (state.phase === 'completed') {
      if (refreshStateUI) refreshStateUI();
      if (showTasteWeightsOverlay && document.querySelector('#panel-discover .taste-weights-page')) {
        showTasteWeightsOverlay();
      }
    }
  }

  return {
    showStatusToast,
    hideStatusToast,
    handleRecommendationRebuildStatus,
  };
}
