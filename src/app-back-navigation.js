export function createBackNavigationSupport({
  activateTab,
  getActiveTab,
  closeTasteWeights,
  hasDiscoverBackup,
  closeViewAll,
  flushQueuedDiscoverRefresh,
  renderDiscoverSnapshotFromCache,
  getActiveMenu,
  closeSongMenu,
  getFullPlayerOpen,
  closeFullPlayer,
  minimizeApp,
}) {
  function switchToDiscover() {
    activateTab('discover');
  }

  function isOnSubPage() {
    const activeTab = getActiveTab();
    if (activeTab === 'discover') {
      return !!document.querySelector('#panel-discover .emb-detail-page, #panel-discover .taste-weights-page, #panel-discover .viewall-header');
    }
    if (activeTab === 'browse') {
      return !!document.querySelector('#panel-browse .viewall-header');
    }
    return false;
  }

  function closeActiveSubPage() {
    if (document.querySelector('#panel-discover .taste-weights-page')) {
      closeTasteWeights();
      return true;
    }
    if (document.querySelector('#panel-discover .emb-detail-page')) {
      if (hasDiscoverBackup()) {
        closeViewAll();
      } else {
        const panel = document.getElementById('panel-discover');
        if (panel && !flushQueuedDiscoverRefresh()) renderDiscoverSnapshotFromCache({ fade: false });
      }
      return true;
    }
    if (document.querySelector('#panel-discover .viewall-header, #panel-browse .viewall-header')) {
      closeViewAll();
      return true;
    }
    return false;
  }

  function handleBackButton() {
    if (getActiveMenu()) {
      closeSongMenu();
      return true;
    }

    const sdOverlay = document.getElementById('songDetailsOverlay');
    if (sdOverlay) {
      sdOverlay.remove();
      return true;
    }

    const genericOverlay = document.querySelector('.sd-overlay, .modal-overlay');
    if (genericOverlay) {
      genericOverlay.remove();
      return true;
    }

    if (getFullPlayerOpen()) {
      closeFullPlayer();
      return true;
    }

    const activeTab = getActiveTab();
    if (isOnSubPage() && (activeTab === 'discover' || activeTab === 'browse')) {
      if (closeActiveSubPage()) return true;
      return true;
    }

    if (activeTab !== 'discover') {
      switchToDiscover();
      return true;
    }

    minimizeApp();
    return true;
  }

  return {
    switchToDiscover,
    isOnSubPage,
    closeActiveSubPage,
    handleBackButton,
  };
}
