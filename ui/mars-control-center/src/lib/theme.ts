export type Theme = 'dark' | 'light';

const KEY = 'mars.theme';

export function storedTheme(): Theme {
  try {
    return localStorage.getItem(KEY) === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  document.documentElement.classList.toggle('dark', theme === 'dark');
  try {
    localStorage.setItem(KEY, theme);
  } catch {
    // storage unavailable (private mode): the theme still applies for this page view
  }
}

export function applyStoredTheme() {
  applyTheme(storedTheme());
}
