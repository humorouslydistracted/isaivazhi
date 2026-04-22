import { registerPlugin } from '@capacitor/core';

function resolveTestBridge() {
  try {
    const g = globalThis;
    const testConfig = g && g.__MUSIC_APP_TEST__;
    if (testConfig && testConfig.MusicBridge) return testConfig.MusicBridge;
  } catch (e) { /* ignore */ }
  return null;
}

const MusicBridge = resolveTestBridge() || registerPlugin('MusicBridge');

export { MusicBridge };
