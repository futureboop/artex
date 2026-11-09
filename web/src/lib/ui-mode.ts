export type UiMode = "classic" | "simple";

// New key makes this rollout start in simple mode even for browsers that saved
// the previous classic default. Switching to classic writes an explicit choice.
const COOKIE_KEY = "artex_ui_mode_v2";

export function readUiMode(): UiMode {
  if (typeof document === "undefined") return "simple";
  return document.cookie.split("; ").some((part) => part === `${COOKIE_KEY}=classic`) ? "classic" : "simple";
}

export function saveUiMode(mode: UiMode) {
  document.cookie = `${COOKIE_KEY}=${mode}; max-age=31536000; path=/; SameSite=Lax`;
}
