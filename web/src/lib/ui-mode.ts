export type UiMode = "classic" | "simple";

const COOKIE_KEY = "artex_ui_mode";

export function readUiMode(): UiMode {
  if (typeof document === "undefined") return "classic";
  return document.cookie.split("; ").some((part) => part === `${COOKIE_KEY}=simple`) ? "simple" : "classic";
}

export function saveUiMode(mode: UiMode) {
  document.cookie = `${COOKIE_KEY}=${mode}; max-age=31536000; path=/; SameSite=Lax`;
}
