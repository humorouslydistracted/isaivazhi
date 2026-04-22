if (!globalThis.window) {
  globalThis.window = globalThis;
}

window.Capacitor = {
  convertFileSrc(path) {
    return path;
  },
};
