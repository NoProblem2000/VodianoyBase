// Per-browser conveniences only (e.g. the author name). A failing localStorage must never break the UI.
export function loadSetting(key: string, fallback: string): string {
  try {
    return window.localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

export function saveSetting(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // ignore
  }
}
